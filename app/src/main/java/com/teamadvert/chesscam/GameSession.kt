package com.teamadvert.chesscam

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.teamadvert.chesscam.chess.Game
import com.teamadvert.chesscam.chess.GameStatus
import com.teamadvert.chesscam.chess.Move
import com.teamadvert.chesscam.chess.WHITE
import com.teamadvert.chesscam.chess.colorWord
import com.teamadvert.chesscam.chess.spokenMove
import com.teamadvert.chesscam.engine.EngineResult
import com.teamadvert.chesscam.engine.UciEngine
import com.teamadvert.chesscam.vision.Detection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The game being followed: camera tracking, the engine and speech. It outlives the
 * activity, so play can go on behind the floating overlay while another app is open.
 * Everything except [Tracker.Listener] runs on the main thread.
 */
class GameSession private constructor(private val app: Context) : Tracker.Listener {

    enum class Mode { SETUP, TRACKING, PAUSED, OVER }

    /** What the overlay's colors mean. */
    enum class Status { SETUP, YOUR_MOVE, THINKING, WAITING, ATTENTION, PAUSED, OVER }

    interface Listener {
        /** The game or its status changed. */
        fun onSessionChanged()

        /** A camera frame, while [Tracker.wantFrames] is set. */
        fun onFrame(frame: Bitmap, squareChange: FloatArray?) {}
    }

    val settings = Settings(app)
    val speaker = Speaker(app)
    val tracker = Tracker(this)
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    var engine: UciEngine? = null
        private set
    var engineError: String? = null
        private set
    var game = Game()
        private set
    var mode = Mode.SETUP
        private set
    /** Corners are being moved during a game; detection is paused meanwhile. */
    var adjusting = false
        private set
    var thinking = false
        private set
    /** What the camera is seeing right now, e.g. "Hand over the board…". */
    var detectionText: String? = null
        private set
    /** The camera lost track of the board or it looks blocked; cleared by the next move or re-sync. */
    var needsAttention = false
        private set

    /** Display rotation the activity used for the camera, so the overlay's camera matches it. */
    var cameraRotation = 0

    /** The floating overlay is showing instead of the app. */
    var overlayActive = false
        set(v) {
            field = v
            updateVoice()
            notifyChanged()
        }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()
    private var suggestion: EngineResult? = null
    private var suggestionKey: String? = null

    init {
        tracker.detector.sensitivity = settings.sensitivity
        updateVoice()
        restoreGame()
        startEngine()
    }

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    private fun notifyChanged() {
        for (l in listeners.toList()) l.onSessionChanged()
    }

    // ---- Engine and camera callbacks ----

    private fun startEngine() {
        val exe = File(app.applicationInfo.nativeLibraryDir, "libstockfish.so")
        if (!exe.exists()) {
            engineError = app.getString(R.string.engine_missing)
            return
        }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        engine = UciEngine(listOf(exe.absolutePath)).also { e ->
            e.start(listOf("Threads" to "$threads", "Hash" to "64")) { message ->
                main.post {
                    engineError = message
                    engine = null
                    thinking = false
                    notifyChanged()
                }
            }
        }
    }

    override fun onFrame(frame: Bitmap, squareChange: FloatArray?) {
        main.post { for (l in listeners.toList()) l.onFrame(frame, squareChange) }
    }

    override fun onDetection(detection: Detection, key: String) {
        main.post { handleDetection(detection, key) }
    }

    // ---- Game flow ----

    fun positionKey() = "${game.moves.size}:${game.position.fen()}"

    fun currentSuggestion(): EngineResult? = suggestion?.takeIf { suggestionKey == positionKey() }

    /** The suggested move, if it is legal in the current position. */
    fun suggestedMove(): Move? = currentSuggestion()?.bestMove?.takeIf { game.position.isLegal(it) }

    private fun handleDetection(d: Detection, key: String) {
        if (mode != Mode.TRACKING || adjusting || key != positionKey()) return
        var attention = needsAttention
        val text = when (d) {
            Detection.Moving -> "Hand over the board…"
            Detection.Settling -> "Checking…"
            Detection.Synced -> {
                attention = false
                "Board captured."
            }
            Detection.NoChange -> {
                attention = false
                null
            }
            is Detection.Unclear -> {
                if (d.lostTrack) {
                    speaker.say("Lost track of the board.", interrupt = true)
                    attention = true
                }
                if (d.obstructed) attention = true
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
        if (text != detectionText || attention != needsAttention) {
            detectionText = text
            needsAttention = attention
            notifyChanged()
        }
    }

    fun startGame() {
        val fresh = game.moves.isEmpty() || game.status() != GameStatus.ONGOING
        if (fresh) game = Game(settings.startFen)
        mode = Mode.TRACKING
        tracker.post { tracker.detector.requestResync() }
        speaker.say(
            if (fresh) "New game. You play ${colorWord(settings.myColor)}." else "Continuing the game.",
            interrupt = true,
        )
        saveGame()
        afterPositionChanged()
    }

    fun pause() {
        mode = Mode.PAUSED
        engine?.cancel()
        thinking = false
        speaker.stop()
        updateSnapshot()
        notifyChanged()
    }

    fun resume() {
        mode = Mode.TRACKING
        tracker.post { tracker.detector.requestResync() }
        afterPositionChanged()
    }

    fun resync() {
        tracker.post { tracker.detector.requestResync() }
        needsAttention = false
        if (mode == Mode.PAUSED) resume()
        detectionText = "Re-syncing: keep hands off the board."
        notifyChanged()
    }

    fun undo() {
        if (!game.undo()) return
        if (mode != Mode.SETUP) mode = Mode.PAUSED
        engine?.cancel()
        thinking = false
        suggestion = null
        saveGame()
        updateSnapshot()
        notifyChanged()
    }

    /** A move entered by hand; the real board is assumed to show it already. */
    fun manualMove(move: Move) {
        tracker.post { tracker.detector.requestResync() }
        needsAttention = false
        if (mode == Mode.PAUSED) mode = Mode.TRACKING
        playMoves(listOf(move), fromCamera = false)
    }

    fun newGame() {
        engine?.cancel()
        thinking = false
        adjusting = false
        needsAttention = false
        game = Game(settings.startFen)
        mode = Mode.SETUP
        suggestion = null
        saveGame()
        updateSnapshot()
        notifyChanged()
    }

    /** A new starting position from Settings takes effect now if no game is under way. */
    fun startPositionChanged() {
        if (mode == Mode.SETUP && game.moves.isEmpty()) {
            game = Game(settings.startFen)
            notifyChanged()
        }
    }

    fun setAdjusting(on: Boolean) {
        if (on && mode == Mode.SETUP) return
        adjusting = on
        if (!on) tracker.post { tracker.detector.requestResync() }
        updateSnapshot()
        notifyChanged()
    }

    /** Re-reads speech and detection settings after the Settings dialog. */
    fun settingsChanged() {
        tracker.detector.sensitivity = settings.sensitivity
        updateVoice()
        notifyChanged()
    }

    fun repeatLastLine() = speaker.repeat()

    private fun updateVoice() {
        speaker.enabled = if (overlayActive) settings.overlayVoice else settings.voice
        if (!speaker.enabled) speaker.stop()
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
        needsAttention = false
        if (lines.isNotEmpty()) speaker.say(lines.joinToString(" "), interrupt = true)
        saveGame()
        afterPositionChanged()
    }

    private fun afterPositionChanged() {
        detectionText = null
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
        notifyChanged()
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
                if (best != null && pos.isLegal(best)) speaker.say("Your move: ${spokenMove(pos, best)}.")
                updateSnapshot()
                notifyChanged()
            }
        }
    }

    private fun updateSnapshot() {
        tracker.snapshot = if (mode == Mode.TRACKING && !adjusting) {
            Tracker.Snapshot(game.position, currentSuggestion()?.bestMove, positionKey())
        } else {
            null
        }
    }

    fun status(): Status = when {
        mode == Mode.SETUP || adjusting -> Status.SETUP
        mode == Mode.OVER -> Status.OVER
        mode == Mode.PAUSED -> Status.PAUSED
        needsAttention -> Status.ATTENTION
        game.position.sideToMove != settings.myColor -> Status.WAITING
        suggestedMove() != null -> Status.YOUR_MOVE
        else -> Status.THINKING
    }

    fun resultText(status: GameStatus = game.status()): String = when (status) {
        GameStatus.CHECKMATE -> if (game.position.sideToMove == settings.myColor) "Checkmate. You lose." else "Checkmate. You win."
        GameStatus.STALEMATE -> "Stalemate. The game is a draw."
        GameStatus.REPETITION -> "Draw by threefold repetition."
        GameStatus.FIFTY_MOVES -> "Draw by the fifty-move rule."
        GameStatus.INSUFFICIENT_MATERIAL -> "Draw. Neither side can checkmate."
        GameStatus.ONGOING -> ""
    }

    fun pgn(): String {
        val date = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date())
        return if (settings.myColor == WHITE) game.pgn("Me", "Opponent", date) else game.pgn("Opponent", "Me", date)
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
            File(app.filesDir, "game.pgn").writeText(pgn())
        } catch (e: Exception) {
            Log.w(TAG, "could not write game.pgn", e)
        }
    }

    companion object {
        private const val TAG = "ChessCam"
        private var instance: GameSession? = null

        /** The session for this process (a new one if the application object changed, as in tests). */
        fun get(context: Context): GameSession {
            val app = context.applicationContext
            return instance?.takeIf { it.app === app } ?: GameSession(app).also { instance = it }
        }
    }
}
