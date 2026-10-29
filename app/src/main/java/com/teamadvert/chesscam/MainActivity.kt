package com.teamadvert.chesscam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.teamadvert.chesscam.chess.BISHOP
import com.teamadvert.chesscam.chess.BLACK
import com.teamadvert.chesscam.chess.Game
import com.teamadvert.chesscam.chess.GameStatus
import com.teamadvert.chesscam.chess.KNIGHT
import com.teamadvert.chesscam.chess.Move
import com.teamadvert.chesscam.chess.Position
import com.teamadvert.chesscam.chess.QUEEN
import com.teamadvert.chesscam.chess.ROOK
import com.teamadvert.chesscam.chess.START_FEN
import com.teamadvert.chesscam.chess.WHITE
import com.teamadvert.chesscam.chess.colorWord
import com.teamadvert.chesscam.chess.spokenMove
import com.teamadvert.chesscam.engine.EngineResult
import com.teamadvert.chesscam.engine.UciEngine
import com.teamadvert.chesscam.ui.BoardDiagramView
import com.teamadvert.chesscam.ui.CameraBoardView
import com.teamadvert.chesscam.vision.Detection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sign

class MainActivity : AppCompatActivity(), Tracker.Listener {

    private enum class Mode { SETUP, TRACKING, PAUSED, OVER }

    private lateinit var settings: Settings
    private lateinit var speaker: Speaker
    private lateinit var tracker: Tracker
    private var engine: UciEngine? = null
    private var engineError: String? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

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

    private var game = Game()
    private var mode = Mode.SETUP
    /** Corners are being moved during a game; detection is paused meanwhile. */
    private var adjusting = false
    private var suggestion: EngineResult? = null
    private var suggestionKey: String? = null
    private var thinking = false
    private var detectionText: String? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else hintView.setText(R.string.camera_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = Settings(this)
        speaker = Speaker(this).also { it.enabled = settings.voice }
        tracker = Tracker(this)
        tracker.detector.sensitivity = settings.sensitivity

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

        cameraView.setNormalizedCorners(settings.corners)
        cameraView.onCornersChanged = { c ->
            tracker.corners = c
            settings.corners = cameraView.normalizedCorners()
        }
        diagram.onSquareTapped = ::onSquareTapped

        colorButton.setOnClickListener {
            settings.myColor = -settings.myColor
            render()
        }
        rotateButton.setOnClickListener { cameraView.rotateLabels() }
        startButton.setOnClickListener { if (adjusting) finishAdjusting() else startGame() }
        pauseButton.setOnClickListener { if (mode == Mode.PAUSED) resume() else pause() }
        undoButton.setOnClickListener { undo() }
        repeatButton.setOnClickListener { speaker.repeat() }
        resyncButton.setOnClickListener { resync() }
        findViewById<Button>(R.id.moreButton).setOnClickListener { showMenu(it) }

        restoreGame()
        startEngine()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        refreshBoard()
        render()
        if (!settings.seenHelp) {
            settings.seenHelp = true
            showHelp()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.close()
        speaker.shutdown()
        analysisExecutor.shutdown()
    }

    // ---- Camera and engine ----

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setOutputImageRotationEnabled(true)
                    .setTargetRotation(cameraView.display?.rotation ?: Surface.ROTATION_0)
                    .build()
                analysis.setAnalyzer(analysisExecutor, tracker)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "camera start failed", e)
                hintView.text = "Could not open the camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startEngine() {
        val exe = File(applicationInfo.nativeLibraryDir, "libstockfish.so")
        if (!exe.exists()) {
            engineError = getString(R.string.engine_missing)
            return
        }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        engine = UciEngine(listOf(exe.absolutePath)).also { e ->
            e.start(listOf("Threads" to "$threads", "Hash" to "64")) { message ->
                main.post {
                    engineError = message
                    engine = null
                    thinking = false
                    render()
                }
            }
        }
    }

    override fun onFrame(frame: Bitmap, squareChange: FloatArray?) {
        main.post {
            cameraView.setFrame(frame)
            cameraView.squareChange = if (settings.showChanges && mode == Mode.TRACKING && !adjusting) squareChange else null
        }
    }

    override fun onDetection(detection: Detection, key: String) {
        main.post { handleDetection(detection, key) }
    }

    // ---- Game flow ----

    private fun positionKey() = "${game.moves.size}:${game.position.fen()}"

    private fun handleDetection(d: Detection, key: String) {
        if (mode != Mode.TRACKING || adjusting || key != positionKey()) return
        val text = when (d) {
            Detection.Moving -> "Hand over the board…"
            Detection.Settling -> "Checking…"
            Detection.Synced -> "Board captured."
            Detection.NoChange -> null
            is Detection.Unclear -> {
                if (d.lostTrack) speaker.say("Lost track of the board.", interrupt = true)
                when {
                    d.obstructed -> "Many squares look different. Did the board or phone move? Tap Re-sync if the board is correct."
                    d.lostTrack -> "Lost track. Tap the move on the small board, or Undo / Re-sync."
                    else -> "Not sure what moved yet…"
                }
            }
            is Detection.Found -> {
                playMoves(d.moves, fromCamera = true)
                null
            }
        }
        if (text != detectionText) {
            detectionText = text
            render()
        }
    }

    private fun startGame() {
        val fresh = game.moves.isEmpty() || game.status() != GameStatus.ONGOING
        if (fresh) game = Game(settings.startFen)
        mode = Mode.TRACKING
        cameraView.editing = false
        tracker.post { tracker.detector.requestResync() }
        speaker.say(
            if (fresh) "New game. You play ${colorWord(settings.myColor)}." else "Continuing the game.",
            interrupt = true,
        )
        saveGame()
        afterPositionChanged()
    }

    private fun pause() {
        mode = Mode.PAUSED
        engine?.cancel()
        thinking = false
        speaker.stop()
        updateSnapshot()
        render()
    }

    private fun resume() {
        mode = Mode.TRACKING
        tracker.post { tracker.detector.requestResync() }
        afterPositionChanged()
    }

    private fun resync() {
        tracker.post { tracker.detector.requestResync() }
        if (mode == Mode.PAUSED) resume()
        detectionText = "Re-syncing: keep hands off the board."
        render()
    }

    private fun undo() {
        if (!game.undo()) return
        if (mode != Mode.SETUP) mode = Mode.PAUSED
        engine?.cancel()
        thinking = false
        suggestion = null
        saveGame()
        refreshBoard()
        updateSnapshot()
        render()
    }

    private fun playMoves(moves: List<Move>, fromCamera: Boolean) {
        val lines = mutableListOf<String>()
        val suggested = currentSuggestion()?.bestMove
        for (m in moves) {
            val pos = game.position
            if (!pos.isLegal(m)) break
            val words = spokenMove(pos, m)
            val mover = pos.sideToMove
            game.play(m)
            if (fromCamera && settings.announceOpponent) {
                // Say what the camera saw, so a misread move is noticed straight away.
                if (mover != settings.myColor) lines += "${colorWord(mover)} played $words."
                else if (m != suggested) lines += "You played $words."
            }
        }
        suggestion = null
        if (lines.isNotEmpty()) speaker.say(lines.joinToString(" "), interrupt = true)
        saveGame()
        afterPositionChanged()
    }

    private fun afterPositionChanged() {
        detectionText = null
        refreshBoard()
        val status = game.status()
        if (status != GameStatus.ONGOING) {
            if (mode == Mode.TRACKING) speaker.say(resultText(status))
            mode = Mode.OVER
            engine?.cancel()
            thinking = false
        } else if (mode == Mode.TRACKING && game.position.sideToMove == settings.myColor) {
            requestSuggestion()
        } else {
            engine?.cancel()
            thinking = false
        }
        updateSnapshot()
        render()
    }

    private fun requestSuggestion() {
        val eng = engine ?: return
        val key = positionKey()
        val pos = game.position
        thinking = true
        eng.search(game.startFen, game.moves.toList(), settings.thinkTimeMs) { result ->
            main.post {
                if (key != positionKey() || mode != Mode.TRACKING) return@post
                thinking = false
                suggestion = result
                suggestionKey = key
                val best = result.bestMove
                if (best != null && pos.isLegal(best)) {
                    diagram.arrow = best
                    speaker.say("Your move: ${spokenMove(pos, best)}.")
                }
                updateSnapshot()
                render()
            }
        }
    }

    private fun currentSuggestion(): EngineResult? = suggestion?.takeIf { suggestionKey == positionKey() }

    private fun updateSnapshot() {
        tracker.snapshot = if (mode == Mode.TRACKING && !adjusting) {
            Tracker.Snapshot(game.position, currentSuggestion()?.bestMove, positionKey())
        } else {
            null
        }
    }

    private fun resultText(status: GameStatus): String = when (status) {
        GameStatus.CHECKMATE -> if (game.position.sideToMove == settings.myColor) "Checkmate. You lose." else "Checkmate. You win."
        GameStatus.STALEMATE -> "Stalemate. The game is a draw."
        GameStatus.REPETITION -> "Draw by threefold repetition."
        GameStatus.FIFTY_MOVES -> "Draw by the fifty-move rule."
        GameStatus.INSUFFICIENT_MATERIAL -> "Draw. Neither side can checkmate."
        GameStatus.ONGOING -> ""
    }

    // ---- Manual move entry on the diagram ----

    private fun onSquareTapped(sq: Int) {
        if (mode == Mode.SETUP || mode == Mode.OVER || adjusting) return
        val pos = game.position
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
            options.size == 1 -> manualMove(options[0])
            else -> {
                val kinds = intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)
                AlertDialog.Builder(this)
                    .setTitle(R.string.promote_to)
                    .setItems(arrayOf("Queen", "Rook", "Bishop", "Knight")) { _, i ->
                        options.firstOrNull { it.promotion == kinds[i] }?.let { manualMove(it) }
                    }
                    .show()
            }
        }
    }

    /** A move entered by hand; the real board is assumed to show it already. */
    private fun manualMove(move: Move) {
        tracker.post { tracker.detector.requestResync() }
        if (mode == Mode.PAUSED) mode = Mode.TRACKING
        playMoves(listOf(move), fromCamera = false)
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
                1 -> startAdjusting()
                2 -> confirmNewGame()
                3 -> showSettings()
                4 -> sharePgn()
                5 -> showHelp()
            }
            true
        }
        menu.show()
    }

    private fun startAdjusting() {
        if (mode == Mode.SETUP) return
        adjusting = true
        cameraView.editing = true
        updateSnapshot()
        render()
    }

    private fun finishAdjusting() {
        adjusting = false
        cameraView.editing = false
        tracker.post { tracker.detector.requestResync() }
        updateSnapshot()
        render()
    }

    private fun confirmNewGame() {
        val reset = {
            engine?.cancel()
            thinking = false
            adjusting = false
            game = Game(settings.startFen)
            mode = Mode.SETUP
            cameraView.editing = true
            suggestion = null
            saveGame()
            refreshBoard()
            updateSnapshot()
            render()
        }
        if (game.moves.isEmpty()) {
            reset()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.new_game_confirm)
            .setPositiveButton(R.string.ok) { _, _ -> reset() }
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
        val startFen = view.findViewById<EditText>(R.id.startFen)

        thinkTime.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            THINK_TIMES.map { if (it < 1000) "${it / 1000.0} seconds" else "${it / 1000} second${if (it > 1000) "s" else ""}" },
        )
        thinkTime.setSelection(THINK_TIMES.indexOf(settings.thinkTimeMs).coerceAtLeast(0))
        voice.isChecked = settings.voice
        announce.isChecked = settings.announceOpponent
        showChanges.isChecked = settings.showChanges
        val updateLabel = { p: Int -> sensitivityLabel.text = getString(R.string.sensitivity, 50 + 10 * p) }
        sensitivity.progress = ((settings.sensitivity - 0.5f) * 10).toInt().coerceIn(0, 15)
        updateLabel(sensitivity.progress)
        sensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = updateLabel(p)
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        startFen.setText(settings.startFen.takeIf { it != START_FEN } ?: "")

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_settings)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                settings.thinkTimeMs = THINK_TIMES[thinkTime.selectedItemPosition]
                settings.voice = voice.isChecked
                speaker.enabled = voice.isChecked
                if (!voice.isChecked) speaker.stop()
                settings.announceOpponent = announce.isChecked
                settings.showChanges = showChanges.isChecked
                settings.sensitivity = 0.5f + 0.1f * sensitivity.progress
                tracker.detector.sensitivity = settings.sensitivity
                val fen = startFen.text.toString().trim().ifEmpty { START_FEN }
                try {
                    Position.fromFen(fen)
                    settings.startFen = fen
                    if (mode == Mode.SETUP && game.moves.isEmpty()) {
                        game = Game(fen)
                        refreshBoard()
                    }
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(this, getString(R.string.bad_fen, e.message), Toast.LENGTH_LONG).show()
                }
                render()
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

    private fun pgn(): String {
        val me = "Me"
        val them = "Opponent"
        val date = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date())
        return if (settings.myColor == WHITE) game.pgn(me, them, date) else game.pgn(them, me, date)
    }

    private fun sharePgn() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pgn())
        startActivity(Intent.createChooser(send, getString(R.string.menu_share)))
    }

    // ---- Persistence ----

    private fun restoreGame() {
        val saved = settings.savedGame ?: return
        game = try {
            Game(saved.first).also { g ->
                for (uci in saved.second) {
                    val m = Move.fromUci(uci) ?: break
                    if (!g.position.isLegal(m)) break
                    g.play(m)
                }
            }
        } catch (e: IllegalArgumentException) {
            Game(settings.startFen)
        }
    }

    private fun saveGame() {
        settings.savedGame = game.startFen to game.moves.map { it.uci() }
        try {
            File(filesDir, "game.pgn").writeText(pgn())
        } catch (e: Exception) {
            Log.w(TAG, "could not write game.pgn", e)
        }
    }

    // ---- Rendering ----

    private fun refreshBoard() {
        diagram.position = game.position
        diagram.lastMove = game.moves.lastOrNull()
        diagram.selected = -1
        diagram.arrow = currentSuggestion()?.bestMove?.takeIf { game.position.isLegal(it) }
    }

    private fun render() {
        val my = settings.myColor
        diagram.flipped = my == BLACK
        val setup = mode == Mode.SETUP
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

        hintView.text = when {
            setup -> getString(R.string.setup_hint)
            adjusting -> getString(R.string.adjust_hint)
            mode == Mode.PAUSED -> getString(R.string.paused_hint)
            mode == Mode.OVER -> resultText(game.status())
            else -> detectionText ?: getString(R.string.tracking_hint)
        }

        val pos = game.position
        val toMove = pos.sideToMove
        statusView.text = when (mode) {
            Mode.SETUP -> "Set up the camera. You play ${colorWord(my)}."
            Mode.PAUSED -> "Paused"
            Mode.OVER -> resultText(game.status())
            Mode.TRACKING -> when {
                adjusting -> "Adjusting corners"
                toMove == my -> if (thinking) "Your move: thinking…" else "Your move"
                else -> "Waiting for ${colorWord(toMove)}'s move"
            }
        } + (engineError?.let { "\n$it" } ?: "")

        val s = currentSuggestion()
        val best = s?.bestMove?.takeIf { pos.isLegal(it) }
        suggestionView.text = if (best != null && mode != Mode.SETUP) pos.san(best) else ""
        evaluationView.text = if (best != null && mode != Mode.SETUP) evaluationText(s) else ""
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
        private const val TAG = "ChessCam"
        private val THINK_TIMES = listOf(500, 1000, 2000, 3000, 5000)
    }
}
