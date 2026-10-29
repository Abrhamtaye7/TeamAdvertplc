package com.teamadvert.chesscam.engine

import com.teamadvert.chesscam.chess.Move
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import kotlin.concurrent.thread

/** The engine's answer; the score is from the point of view of the side to move. */
data class EngineResult(
    val bestMove: Move?,
    val scoreCp: Int?,
    val mateIn: Int?,
    val depth: Int,
)

/** Fields of interest from one UCI "info" line. */
data class UciInfo(val depth: Int?, val scoreCp: Int?, val mateIn: Int?, val bound: Boolean) {
    companion object {
        fun parse(line: String): UciInfo? {
            val t = line.trim().split(Regex("\\s+"))
            if (t.firstOrNull() != "info" || t.getOrNull(1) == "string") return null
            if (t.indexOf("multipv").let { it >= 0 && t.getOrNull(it + 1) != "1" }) return null
            var depth: Int? = null
            var cp: Int? = null
            var mate: Int? = null
            var bound = false
            var i = 1
            while (i < t.size) {
                when (t[i]) {
                    "depth" -> depth = t.getOrNull(++i)?.toIntOrNull()
                    "score" -> when (t.getOrNull(++i)) {
                        "cp" -> cp = t.getOrNull(++i)?.toIntOrNull()
                        "mate" -> mate = t.getOrNull(++i)?.toIntOrNull()
                    }
                    "lowerbound", "upperbound" -> bound = true
                    "pv" -> i = t.size
                }
                i++
            }
            if (depth == null && cp == null && mate == null) return null
            return UciInfo(depth, cp, mate, bound)
        }
    }
}

/**
 * Runs a UCI engine as a child process. One search runs at a time; asking for a new
 * search stops the current one and its result is dropped. Callbacks run on the
 * engine's reader thread.
 */
class UciEngine(private val command: List<String>) {
    private class Request(
        val fen: String,
        val moves: List<Move>,
        val moveTimeMs: Int,
        val onResult: (EngineResult) -> Unit,
    )

    private val lock = Object()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var options: List<Pair<String, String>> = emptyList()
    private var onError: (String) -> Unit = {}

    // Guarded by lock.
    private var ready = false
    private var active: Request? = null
    private var activeCancelled = false
    private var pending: Request? = null
    private var depth = 0
    private var scoreCp: Int? = null
    private var mateIn: Int? = null

    /** Starts the process. [onError] is called if it cannot start or exits. */
    fun start(options: List<Pair<String, String>>, onError: (String) -> Unit) {
        this.options = options
        this.onError = onError
        val p = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            onError("Could not start the chess engine: ${e.message}")
            return
        }
        process = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream))
        thread(name = "uci-reader", isDaemon = true) {
            try {
                p.inputStream.bufferedReader().forEachLine { handle(it) }
            } catch (_: IOException) {
            }
            val closing = synchronized(lock) { process == null }
            if (!closing) onError("The chess engine stopped unexpectedly.")
        }
        send("uci")
    }

    fun search(fen: String, moves: List<Move>, moveTimeMs: Int, onResult: (EngineResult) -> Unit) {
        synchronized(lock) {
            pending = Request(fen, moves, moveTimeMs, onResult)
            if (!ready) return
            if (active != null) {
                if (!activeCancelled) {
                    activeCancelled = true
                    send("stop")
                }
            } else {
                launchPending()
            }
        }
    }

    /** Drops any queued search and stops the running one without reporting it. */
    fun cancel() {
        synchronized(lock) {
            pending = null
            if (active != null && !activeCancelled) {
                activeCancelled = true
                send("stop")
            }
        }
    }

    fun close() {
        val p = synchronized(lock) {
            val p = process
            process = null
            p
        } ?: return
        try {
            writer?.apply { write("quit\n"); flush() }
        } catch (_: IOException) {
        }
        p.destroy()
    }

    private fun handle(line: String) {
        val l = line.trim()
        when {
            l == "uciok" -> {
                for ((name, value) in options) send("setoption name $name value $value")
                send("isready")
            }
            l == "readyok" -> synchronized(lock) {
                if (!ready) {
                    ready = true
                    if (pending != null) launchPending()
                }
            }
            l.startsWith("info ") -> UciInfo.parse(l)?.let { info ->
                if (info.bound) return
                synchronized(lock) {
                    info.depth?.let { depth = it }
                    if (info.scoreCp != null || info.mateIn != null) {
                        scoreCp = info.scoreCp
                        mateIn = info.mateIn
                    }
                }
            }
            l.startsWith("bestmove") -> {
                var deliver: Pair<Request, EngineResult>? = null
                synchronized(lock) {
                    val req = active ?: return
                    val move = l.split(Regex("\\s+")).getOrNull(1)?.let { Move.fromUci(it) }
                    if (!activeCancelled) deliver = req to EngineResult(move, scoreCp, mateIn, depth)
                    active = null
                    activeCancelled = false
                    if (pending != null) launchPending()
                }
                deliver?.let { (req, result) -> req.onResult(result) }
            }
        }
    }

    // Caller holds lock.
    private fun launchPending() {
        val req = pending ?: return
        pending = null
        active = req
        activeCancelled = false
        depth = 0
        scoreCp = null
        mateIn = null
        val moves = if (req.moves.isEmpty()) "" else " moves " + req.moves.joinToString(" ") { it.uci() }
        send("position fen ${req.fen}$moves")
        send("go movetime ${req.moveTimeMs}")
    }

    private fun send(command: String) {
        try {
            writer?.apply {
                write(command)
                write("\n")
                flush()
            }
        } catch (e: IOException) {
            onError("Lost connection to the chess engine.")
        }
    }
}
