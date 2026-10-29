package com.teamadvert.chesscam.vision

import com.teamadvert.chesscam.chess.EMPTY
import com.teamadvert.chesscam.chess.Move
import com.teamadvert.chesscam.chess.Position
import com.teamadvert.chesscam.chess.QUEEN

sealed class Detection {
    /** Something is moving over the board (usually a hand). */
    object Moving : Detection()

    /** Motion stopped; waiting a moment to be sure. */
    object Settling : Detection()

    /** The reference picture of the board was (re)taken. */
    object Synced : Detection()

    /** The board looks the same as the last confirmed position. */
    object NoChange : Detection()

    /** One move, or two moves made quickly one after the other. */
    data class Found(val moves: List<Move>) : Detection()

    /** The board changed but no legal move explains it well enough yet. */
    data class Unclear(val lostTrack: Boolean, val obstructed: Boolean) : Detection()
}

/**
 * Detects moves on a physical board by comparing each still frame with a reference
 * frame of the last confirmed position. It never tries to recognise pieces: it finds
 * which squares changed and picks the legal move whose changed squares fit best.
 *
 * Call [process] for every frame, in order, from a single thread.
 */
class MoveDetector {
    /** Above 1 reacts to smaller changes; below 1 needs bigger ones. */
    @Volatile var sensitivity = 1f

    /** Camera geometry from the current [BoardSampler]. */
    @Volatile var geometry: BoardGeometry = BoardGeometry.DEFAULT

    /** Per-square change against the reference, scaled 0..1, from the last [process] call. */
    val squareChange = FloatArray(64)

    private var reference: FloatArray? = null
    private var previous: FloatArray? = null
    private var stableFrames = 0
    private var resyncRequested = false
    private var candidateKey: String? = null
    private var candidateCount = 0
    private var unclearFrames = 0
    private var lostReported = false

    private val motion = FloatArray(64)
    private val change = FloatArray(64)
    /** Per-square evidence that the square changed; see [EVIDENCE_OFFSET]. */
    private val evidence = FloatArray(64)
    private val mark = IntArray(64)
    private var stamp = 0

    /** Take a new reference picture at the next still frame. */
    fun requestResync() {
        resyncRequested = true
    }

    /**
     * @param position the position the reference picture shows
     * @param hint a move that is expected (the engine's suggestion); used only to break near-ties
     */
    fun process(frame: FloatArray, position: Position, hint: Move?): Detection {
        val prev = previous
        previous = frame.copyOf()
        if (prev == null) return still(Detection.Moving)

        val geo = geometry
        for (sq in 0 until 64) motion[sq] = geo.diff(frame, prev, sq)
        val motionNoise = median(motion)
        if (motion.maxOrNull()!! - motionNoise > MOTION_THRESHOLD / sensitivity) {
            stableFrames = 0
            candidateKey = null
            return Detection.Moving
        }
        stableFrames++
        if (stableFrames < STABLE_FRAMES) return Detection.Settling

        val ref = reference
        if (ref == null || resyncRequested) {
            reference = frame.copyOf()
            resyncRequested = false
            resetTracking()
            squareChange.fill(0f)
            return Detection.Synced
        }

        // Change per square against the reference, relative to the typical square
        // (which absorbs exposure changes), in units of the frame-to-frame noise.
        for (sq in 0 until 64) change[sq] = geo.diff(frame, ref, sq)
        val centre = median(change)
        for (sq in 0 until 64) change[sq] -= centre
        val spread = 1.4826f * median(FloatArray(64) { kotlin.math.abs(change[it]) })
        val sigma = maxOf(spread, MIN_CHANGE / 3f / sensitivity)
        for (sq in 0 until 64) {
            val z = change[sq].coerceAtLeast(0f) / sigma
            evidence[sq] = if (z < Z_KNEE) z * z / 2f - EVIDENCE_OFFSET else KNEE_EVIDENCE + (z - Z_KNEE)
            squareChange[sq] = (z / Z_FULL).coerceIn(0f, 1f)
        }
        val changed = evidence.count { it > 0f }
        val clear = evidence.count { it > CLEAR_EVIDENCE }

        if (changed == 0) {
            // Nothing moved: let the reference follow slow lighting changes, but only on
            // squares that look the same, so a faint move is not slowly absorbed.
            for (sq in 0 until 64) {
                if (change[sq] > ADAPT_Z * sigma) continue
                val o = sq * BoardSampler.PER_SQUARE
                for (i in o until o + BoardSampler.PER_SQUARE) ref[i] += ADAPT_RATE * (frame[i] - ref[i])
            }
            resetTracking()
            return Detection.NoChange
        }

        val obstructed = changed > MAX_CHANGED_SQUARES
        val found = if (obstructed) null else bestFit(position, hint)
        if (found != null) {
            val key = found.joinToString(" ") { it.uci() }
            candidateCount = if (key == candidateKey) candidateCount + 1 else 1
            candidateKey = key
            if (candidateCount >= CONFIRM_FRAMES) {
                reference = frame.copyOf()
                resetTracking()
                squareChange.fill(0f)
                return Detection.Found(found)
            }
            return Detection.Settling
        }

        candidateKey = null
        // One clearly changed square is usually a move in progress (a piece lifted), so
        // give that much longer before saying the board is lost.
        if (clear >= 1) unclearFrames++
        val limit = if (obstructed || clear < 2) 3 * LOST_FRAMES else LOST_FRAMES
        val lost = unclearFrames >= limit && !lostReported
        if (lost) lostReported = true
        return Detection.Unclear(lostTrack = lost, obstructed = obstructed)
    }

    private fun still(d: Detection): Detection {
        stableFrames = 0
        return d
    }

    private fun resetTracking() {
        candidateKey = null
        candidateCount = 0
        unclearFrames = 0
        lostReported = false
    }

    private class Fit(val moves: List<Move>, val score: Float, val acceptable: Boolean)

    /**
     * Scores a move (or move pair) by the evidence on the squares it changes, plus half the
     * evidence on squares its pieces lean into on camera. It is acceptable only if each of
     * its squares looks changed and no other square clearly changed.
     */
    private fun fit(moves: List<Move>, before: Position, after: Position, bonus: Float): Fit {
        val spill = geometry.behind
        stamp++
        var weakest = Float.MAX_VALUE
        for (sq in 0 until 64) {
            if (before[sq] == after[sq]) continue
            weakest = minOf(weakest, evidence[sq])
            mark[sq] = stamp
        }
        var score = bonus
        for (sq in 0 until 64) if (mark[sq] == stamp) score += evidence[sq]
        for (sq in 0 until 64) {
            val b = spill[sq]
            if (mark[sq] == stamp && b >= 0 && mark[b] != stamp && mark[b] != -stamp) {
                mark[b] = -stamp
                // A piece arriving or leaving also changes the square it leans into.
                score += LEAN_CREDIT * evidence[b].coerceAtLeast(0f)
            }
        }
        var unexplained = false
        for (sq in 0 until 64) {
            if (mark[sq] != stamp && mark[sq] != -stamp && evidence[sq] > CLEAR_EVIDENCE) unexplained = true
        }
        return Fit(moves, score, weakest >= MIN_SQUARE_EVIDENCE && !unexplained)
    }

    /**
     * The best acceptable candidate, if it leads the next acceptable one by [margin] and is
     * not far behind any unacceptable one.
     */
    private fun pick(fits: List<Fit>, margin: Float): Fit? {
        val (ok, rest) = fits.partition { it.acceptable }
        val sorted = ok.sortedByDescending { it.score }
        val best = sorted.firstOrNull() ?: return null
        val second = sorted.getOrNull(1)?.score ?: Float.NEGATIVE_INFINITY
        val other = rest.maxOfOrNull { it.score } ?: Float.NEGATIVE_INFINITY
        return best.takeIf { it.score - second >= margin && it.score >= other - margin }
    }

    private fun bestFit(position: Position, hint: Move?): List<Move>? {
        val singles = distinctMoves(position.legalMoves(), hint).map { m ->
            fit(listOf(m), position, position.play(m), if (m == hint) HINT_BONUS else 0f)
        }
        pick(singles, MIN_MARGIN)?.let { return it.moves }

        // Maybe both players moved before the board was still long enough. That needs
        // a clear lead over every single move.
        val pairs = ArrayList<Fit>()
        for (first in distinctMoves(position.legalMoves(), null)) {
            val mid = position.play(first)
            for (second in distinctMoves(mid.legalMoves(), null)) {
                pairs += fit(listOf(first, second), position, mid.play(second), 0f)
            }
        }
        val bestPair = pick(pairs, MIN_MARGIN) ?: return null
        val bestSingle = singles.maxOfOrNull { it.score } ?: Float.NEGATIVE_INFINITY
        return bestPair.moves.takeIf { bestPair.score - bestSingle >= 2 * MIN_MARGIN }
    }

    companion object {
        const val MOTION_THRESHOLD = 9f
        const val STABLE_FRAMES = 5
        /** Smallest colour change (0..255 scale) that counts, at sensitivity 1. */
        const val MIN_CHANGE = 6f
        const val CONFIRM_FRAMES = 3
        const val LOST_FRAMES = 45
        const val MAX_CHANGED_SQUARES = 10

        // Evidence is z^2/2 - offset, where z is the change in noise units: positive above
        // z = 2.7. Past the knee it grows only linearly, so a whole piece arriving still
        // outweighs a piece's top leaning in, but one huge change cannot swamp the rest.
        const val EVIDENCE_OFFSET = 3.7f
        const val Z_KNEE = 5.6f
        const val KNEE_EVIDENCE = Z_KNEE * Z_KNEE / 2f - EVIDENCE_OFFSET
        const val CLEAR_EVIDENCE = 3f
        /** Every square a move changes must look changed. */
        const val MIN_SQUARE_EVIDENCE = 0f
        const val MIN_MARGIN = 3f
        const val LEAN_CREDIT = 0.5f
        const val HINT_BONUS = 2f
        const val Z_FULL = 8f

        const val ADAPT_RATE = 0.05f
        const val ADAPT_Z = 1.5f

        /**
         * Promotions to different pieces look identical on camera, so keep one per square
         * pair: the hinted piece if there is one, otherwise a queen.
         */
        fun distinctMoves(moves: List<Move>, hint: Move?): List<Move> = moves.filter { m ->
            m.promotion == EMPTY ||
                if (hint != null && hint.from == m.from && hint.to == m.to) m.promotion == hint.promotion
                else m.promotion == QUEEN
        }

        private fun median(values: FloatArray): Float {
            val s = values.sortedArray()
            return (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
        }
    }
}
