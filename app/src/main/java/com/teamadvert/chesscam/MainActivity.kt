package com.teamadvert.chesscam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.ImageAnalysis
import androidx.core.content.ContextCompat
import com.teamadvert.chesscam.GameSession.Mode
import com.teamadvert.chesscam.chess.BISHOP
import com.teamadvert.chesscam.chess.BLACK
import com.teamadvert.chesscam.chess.GameStatus
import com.teamadvert.chesscam.chess.KNIGHT
import com.teamadvert.chesscam.chess.Move
import com.teamadvert.chesscam.chess.Position
import com.teamadvert.chesscam.chess.QUEEN
import com.teamadvert.chesscam.chess.ROOK
import com.teamadvert.chesscam.chess.START_FEN
import com.teamadvert.chesscam.chess.WHITE
import com.teamadvert.chesscam.chess.colorWord
import com.teamadvert.chesscam.engine.EngineResult
import com.teamadvert.chesscam.ui.BoardDiagramView
import com.teamadvert.chesscam.ui.CameraBoardView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign

/** The main screen: camera view, corner setup, the tracked board and controls for a [GameSession]. */
class MainActivity : AppCompatActivity(), GameSession.Listener {

    private lateinit var session: GameSession
    private val settings get() = session.settings

    private lateinit var cameraView: CameraBoardView
    private lateinit var diagram: BoardDiagramView
    private lateinit var hintView: TextView
    private lateinit var statusView: TextView
    private lateinit var suggestionView: TextView
    private lateinit var evaluationView: TextView
    private lateinit var moveListView: TextView
    private lateinit var colorButton: Button
    private lateinit var rotateButton: Button
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var undoButton: Button
    private lateinit var repeatButton: Button
    private lateinit var resyncButton: Button
    private lateinit var overlayButton: Button

    private var analysis: ImageAnalysis? = null
    private var cameraError: String? = null
    /** Waiting to come back from the "Display over other apps" settings screen. */
    private var overlayPermissionPending = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) bindCamera() else hintView.setText(R.string.camera_denied)
    }

    // The overlay works without it, but then its notification (with Undo / Re-sync / Stop) is hidden.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        launchOverlay()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        session = GameSession.get(this)

        cameraView = findViewById(R.id.camera)
        diagram = findViewById(R.id.diagram)
        hintView = findViewById(R.id.hint)
        statusView = findViewById(R.id.status)
        suggestionView = findViewById(R.id.suggestion)
        evaluationView = findViewById(R.id.evaluation)
        moveListView = findViewById(R.id.moveList)
        colorButton = findViewById(R.id.colorButton)
        rotateButton = findViewById(R.id.rotateButton)
        startButton = findViewById(R.id.startButton)
        pauseButton = findViewById(R.id.pauseButton)
        undoButton = findViewById(R.id.undoButton)
        repeatButton = findViewById(R.id.repeatButton)
        resyncButton = findViewById(R.id.resyncButton)
        overlayButton = findViewById(R.id.overlayButton)

        cameraView.setNormalizedCorners(settings.corners)
        cameraView.onCornersChanged = { c ->
            session.tracker.corners = c
            settings.corners = cameraView.normalizedCorners()
        }
        diagram.onSquareTapped = ::onSquareTapped

        colorButton.setOnClickListener {
            settings.myColor = -settings.myColor
            render()
        }
        rotateButton.setOnClickListener { cameraView.rotateLabels() }
        startButton.setOnClickListener { if (session.adjusting) session.setAdjusting(false) else session.startGame() }
        pauseButton.setOnClickListener { if (session.mode == Mode.PAUSED) session.resume() else session.pause() }
        undoButton.setOnClickListener { session.undo() }
        repeatButton.setOnClickListener { session.repeatLastLine() }
        resyncButton.setOnClickListener { session.resync() }
        overlayButton.setOnClickListener { startOverlay() }
        findViewById<Button>(R.id.moreButton).setOnClickListener { showMenu(it) }

        if (!hasCameraPermission()) cameraPermission.launch(Manifest.permission.CAMERA)
        if (!settings.seenHelp) {
            settings.seenHelp = true
            showHelp()
        }
    }

    override fun onStart() {
        super.onStart()
        // Coming back to the app ends the overlay; the camera moves back here.
        if (OverlayService.running) OverlayService.stop(this)
        session.addListener(this)
        session.tracker.wantFrames = true
        if (hasCameraPermission()) bindCamera()
        onSessionChanged()
    }

    override fun onResume() {
        super.onResume()
        if (overlayPermissionPending) {
            overlayPermissionPending = false
            if (android.provider.Settings.canDrawOverlays(this)) startOverlay()
            else Toast.makeText(this, R.string.overlay_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onStop() {
        super.onStop()
        session.removeListener(this)
        session.tracker.wantFrames = false
        analysis?.let { CameraSetup.unbind(this, it) }
        analysis = null
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun bindCamera() {
        if (analysis != null) return
        session.cameraRotation = cameraView.display?.rotation ?: Surface.ROTATION_0
        cameraError = null
        analysis = CameraSetup.bind(this, this, session) { e ->
            cameraError = "Could not open the camera: ${e.message}"
            render()
        }
    }

    override fun onFrame(frame: Bitmap, squareChange: FloatArray?) {
        cameraView.setFrame(frame)
        cameraView.squareChange =
            if (settings.showChanges && session.mode == Mode.TRACKING && !session.adjusting) squareChange else null
    }

    override fun onSessionChanged() {
        cameraView.editing = session.mode == Mode.SETUP || session.adjusting
        refreshBoard()
        render()
    }

    // ---- Overlay ----

    private fun startOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.overlay_permission_title)
                .setMessage(R.string.overlay_permission_text)
                .setPositiveButton(R.string.ok) { _, _ ->
                    overlayPermissionPending = true
                    startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchOverlay()
    }

    private fun launchOverlay() {
        OverlayService.start(this)
        moveTaskToBack(true)
    }

    // ---- Manual move entry on the diagram ----

    private fun onSquareTapped(sq: Int) {
        if (session.mode == Mode.SETUP || session.mode == Mode.OVER || session.adjusting) return
        val pos = session.game.position
        val ownPiece = pos[sq] != 0 && pos[sq].sign == pos.sideToMove
        val sel = diagram.selected
        if (sel < 0 || sq == sel || ownPiece) {
            diagram.selected = if (ownPiece && sq != sel) sq else -1
            return
        }
        diagram.selected = -1
        val options = pos.legalMoves().filter { it.from == sel && it.to == sq }
        when {
            options.isEmpty() -> Toast.makeText(this, "That move is not legal here.", Toast.LENGTH_SHORT).show()
            options.size == 1 -> session.manualMove(options[0])
            else -> {
                val kinds = intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)
                AlertDialog.Builder(this)
                    .setTitle(R.string.promote_to)
                    .setItems(arrayOf("Queen", "Rook", "Bishop", "Knight")) { _, i ->
                        options.firstOrNull { it.promotion == kinds[i] }?.let { session.manualMove(it) }
                    }
                    .show()
            }
        }
    }

    // ---- Menu and dialogs ----

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.menu_adjust)
        menu.menu.add(0, 2, 1, R.string.menu_new_game)
        menu.menu.add(0, 3, 2, R.string.menu_settings)
        menu.menu.add(0, 4, 3, R.string.menu_share)
        menu.menu.add(0, 5, 4, R.string.menu_help)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> session.setAdjusting(true)
                2 -> confirmNewGame()
                3 -> showSettings()
                4 -> sharePgn()
                5 -> showHelp()
            }
            true
        }
        menu.show()
    }

    private fun confirmNewGame() {
        if (session.game.moves.isEmpty()) {
            session.newGame()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.new_game_confirm)
            .setPositiveButton(R.string.ok) { _, _ -> session.newGame() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val thinkTime = view.findViewById<Spinner>(R.id.thinkTime)
        val voice = view.findViewById<SwitchCompat>(R.id.voice)
        val announce = view.findViewById<SwitchCompat>(R.id.announceOpponent)
        val showChanges = view.findViewById<SwitchCompat>(R.id.showChanges)
        val sensitivityLabel = view.findViewById<TextView>(R.id.sensitivityLabel)
        val sensitivity = view.findViewById<SeekBar>(R.id.sensitivity)
        val overlayVoice = view.findViewById<SwitchCompat>(R.id.overlayVoice)
        val opacityLabel = view.findViewById<TextView>(R.id.overlayOpacityLabel)
        val opacity = view.findViewById<SeekBar>(R.id.overlayOpacity)
        val startFen = view.findViewById<EditText>(R.id.startFen)

        thinkTime.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            THINK_TIMES.map { if (it < 1000) "${it / 1000.0} seconds" else "${it / 1000} second${if (it > 1000) "s" else ""}" },
        )
        thinkTime.setSelection(THINK_TIMES.indexOf(settings.thinkTimeMs).coerceAtLeast(0))
        voice.isChecked = settings.voice
        announce.isChecked = settings.announceOpponent
        showChanges.isChecked = settings.showChanges
        overlayVoice.isChecked = settings.overlayVoice
        sensitivity.progress = ((settings.sensitivity - 0.5f) * 10).toInt().coerceIn(0, 15)
        opacity.progress = ((settings.overlayOpacity - 0.2f) * 10).toInt().coerceIn(0, 8)
        val updateLabels = {
            sensitivityLabel.text = getString(R.string.sensitivity, 50 + 10 * sensitivity.progress)
            opacityLabel.text = getString(R.string.overlay_opacity, 20 + 10 * opacity.progress)
        }
        updateLabels()
        val onChange = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = updateLabels()
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        sensitivity.setOnSeekBarChangeListener(onChange)
        opacity.setOnSeekBarChangeListener(onChange)
        startFen.setText(settings.startFen.takeIf { it != START_FEN } ?: "")

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_settings)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                settings.thinkTimeMs = THINK_TIMES[thinkTime.selectedItemPosition]
                settings.voice = voice.isChecked
                settings.announceOpponent = announce.isChecked
                settings.showChanges = showChanges.isChecked
                settings.overlayVoice = overlayVoice.isChecked
                settings.sensitivity = 0.5f + 0.1f * sensitivity.progress
                settings.overlayOpacity = 0.2f + 0.1f * opacity.progress
                val fen = startFen.text.toString().trim().ifEmpty { START_FEN }
                try {
                    Position.fromFen(fen)
                    settings.startFen = fen
                    session.startPositionChanged()
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(this, getString(R.string.bad_fen, e.message), Toast.LENGTH_LONG).show()
                }
                session.settingsChanged()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_text)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun sharePgn() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, session.pgn())
        startActivity(Intent.createChooser(send, getString(R.string.menu_share)))
    }

    // ---- Rendering ----

    private fun refreshBoard() {
        diagram.position = session.game.position
        diagram.lastMove = session.game.moves.lastOrNull()
        diagram.selected = -1
        diagram.arrow = session.suggestedMove()
    }

    private fun render() {
        val my = settings.myColor
        val mode = session.mode
        val game = session.game
        diagram.flipped = my == BLACK
        val setup = mode == Mode.SETUP
        val adjusting = session.adjusting
        val editingCorners = setup || adjusting

        colorButton.visibility = if (setup) View.VISIBLE else View.GONE
        colorButton.setText(if (my == WHITE) R.string.i_play_white else R.string.i_play_black)
        rotateButton.visibility = if (editingCorners) View.VISIBLE else View.GONE
        startButton.visibility = if (editingCorners) View.VISIBLE else View.GONE
        startButton.setText(
            when {
                adjusting -> R.string.done
                game.moves.isNotEmpty() && game.status() == GameStatus.ONGOING -> R.string.continue_game
                else -> R.string.start
            },
        )
        val playing = !setup && !adjusting
        pauseButton.visibility = if (playing && mode != Mode.OVER) View.VISIBLE else View.GONE
        pauseButton.setText(if (mode == Mode.PAUSED) R.string.resume else R.string.pause)
        undoButton.visibility = if (playing && game.moves.isNotEmpty()) View.VISIBLE else View.GONE
        repeatButton.visibility = if (playing) View.VISIBLE else View.GONE
        resyncButton.visibility = if (playing && mode != Mode.OVER) View.VISIBLE else View.GONE
        overlayButton.visibility = if (playing && mode != Mode.OVER) View.VISIBLE else View.GONE

        hintView.text = cameraError ?: when {
            setup -> getString(R.string.setup_hint)
            adjusting -> getString(R.string.adjust_hint)
            mode == Mode.PAUSED -> getString(R.string.paused_hint)
            mode == Mode.OVER -> session.resultText()
            else -> session.detectionText ?: getString(R.string.tracking_hint)
        }

        val pos = game.position
        val toMove = pos.sideToMove
        statusView.text = when (mode) {
            Mode.SETUP -> "Set up the camera. You play ${colorWord(my)}."
            Mode.PAUSED -> "Paused"
            Mode.OVER -> session.resultText()
            Mode.TRACKING -> when {
                adjusting -> "Adjusting corners"
                toMove == my -> if (session.thinking) "Your move: thinking…" else "Your move"
                else -> "Waiting for ${colorWord(toMove)}'s move"
            }
        } + (session.engineError?.let { "\n$it" } ?: "")

        val s = session.currentSuggestion()
        val best = session.suggestedMove()
        suggestionView.text = if (best != null && !setup) pos.san(best) else ""
        evaluationView.text = if (best != null && s != null && !setup) evaluationText(s) else ""
        moveListView.text = game.moveText()
    }

    private fun evaluationText(r: EngineResult): String {
        val mate = r.mateIn
        val text = when {
            mate != null && mate > 0 -> "You mate in $mate"
            mate != null -> "Opponent mates in ${abs(mate)}"
            r.scoreCp != null -> String.format(Locale.US, "Eval %+.2f", r.scoreCp / 100.0)
            else -> ""
        }
        return if (r.depth > 0) "$text  (depth ${r.depth})" else text
    }

    companion object {
        private val THINK_TIMES = listOf(500, 1000, 2000, 3000, 5000)
    }
}
