package com.teamadvert.chesscam.vision

import com.teamadvert.chesscam.chess.EMPTY
import com.teamadvert.chesscam.chess.Game
import com.teamadvert.chesscam.chess.Move
import com.teamadvert.chesscam.chess.Position
import com.teamadvert.chesscam.chess.fileOf
import com.teamadvert.chesscam.chess.rankOf
import com.teamadvert.chesscam.chess.square
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Renders a board in perspective, with pieces as round blobs whose tops lean "up" in the
 * picture into the square behind them (as upright pieces do on a tilted camera), and
 * feeds the frames through the sampler into a detector.
 */
class SyntheticBoard(
    /** a1, h1, h8, a8 in pixels. */
    private val corners: FloatArray = WHITE_SIDE,
    /** Per-channel pixel noise. */
    private val noise: Int = 6,
    seed: Int = 42,
) {
    class Image(override val width: Int, override val height: Int) : FrameSource {
        val px = IntArray(width * height)
        override fun rgb(x: Int, y: Int) = px[y * width + x]
    }

    /** Result of [playThrough]: descriptions of missed and wrongly detected moves. */
    class Outcome(val missed: List<String>, val wrong: List<String>)

    val width = 640
    val height = 640
    val sampler = BoardSampler(corners, width, height)
    private val toBoard = Homography.fromPoints(DoubleArray(8) { corners[it].toDouble() }, BoardSpace.CORNERS)!!
    private val random = Random(seed)

    // Per pixel: the square colour, the square whose piece base covers it, and the square
    // whose piece top covers it. A piece is a disc on its square plus a "head" disc drawn
    // LEAN pixels higher in the picture.
    private val background = IntArray(width * height)
    private val baseOf = IntArray(width * height)
    private val headOf = IntArray(width * height)

    init {
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val sq = squareAt(x.toDouble(), y.toDouble())
            background[i] = when {
                sq < 0 -> 0x606060
                (fileOf(sq) + rankOf(sq)) % 2 == 1 -> 0xD8D0B0
                else -> 0x6E8C50
            }
            baseOf[i] = discAt(x.toDouble(), y.toDouble(), 0.36)
            headOf[i] = discAt(x.toDouble(), y + LEAN, 0.3)
        }
    }

    private fun squareAt(x: Double, y: Double): Int {
        val u = toBoard.mapX(x, y)
        val v = toBoard.mapY(x, y)
        if (u < 0.0 || u > 8.0 || v < 0.0 || v > 8.0) return -1
        return square(floor(u).toInt().coerceIn(0, 7), 7 - floor(v).toInt().coerceIn(0, 7))
    }

    private fun discAt(x: Double, y: Double, radius: Double): Int {
        val sq = squareAt(x, y)
        if (sq < 0) return -1
        val du = toBoard.mapX(x, y) - fileOf(sq) - 0.5
        val dv = toBoard.mapY(x, y) - (7 - rankOf(sq)) - 0.5
        return if (hypot(du, dv) < radius) sq else -1
    }

    fun render(pos: Position, brightness: Int = 0, hand: Pair<Int, Int>? = null): Image {
        val img = Image(width, height)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val head = headOf[i]
            val base = baseOf[i]
            var c = background[i]
            if (base >= 0 && pos[base] != EMPTY) c = pieceColor(pos[base])
            if (head >= 0 && pos[head] != EMPTY) c = pieceColor(pos[head])
            if (hand != null && hypot((x - hand.first).toDouble(), (y - hand.second).toDouble()) < 90) c = 0xC89070
            img.px[i] = jitter(c, brightness)
        }
        return img
    }

    private fun pieceColor(p: Int): Int {
        val k = abs(p) * 6
        return if (p > 0) rgb(235 - k, 230 - k, 220 - k) else rgb(40 + k, 32 + k, 30 + k)
    }

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    private fun jitter(c: Int, brightness: Int): Int {
        fun ch(v: Int) = (v + brightness + random.nextInt(-noise, noise + 1)).coerceIn(0, 255)
        return rgb(ch((c shr 16) and 0xff), ch((c shr 8) and 0xff), ch(c and 0xff))
    }

    fun features(img: Image) = FloatArray(BoardSampler.FEATURE_COUNT).also { sampler.extract(img, it) }

    fun detector() = MoveDetector().also { it.geometry = sampler.geometry }

    /** Feeds still frames of [shown] until the detector reports more than motion or settling. */
    fun settle(d: MoveDetector, shown: Position, tracked: Position, hint: Move? = null, brightness: Int = 0): Detection {
        repeat(30) {
            val r = d.process(features(render(shown, brightness)), tracked, hint)
            if (r != Detection.Settling && r != Detection.Moving) return r
        }
        return Detection.Settling
    }

    fun handOver(d: MoveDetector, pos: Position, tracked: Position) {
        for (x in listOf(200, 300, 400)) {
            d.process(features(render(pos, hand = x to 350)), tracked, null)
        }
    }

    /** Plays the moves one by one with a hand over the board each time. */
    fun playThrough(d: MoveDetector, g: Game, moves: List<Move>): Outcome {
        val missed = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        for (m in moves) {
            val before = g.position
            val after = before.play(m)
            handOver(d, after, before)
            val r = settle(d, after, before)
            val what = "${before.san(m)} in ${before.fen()}"
            if (r is Detection.Found && r.moves != listOf(m)) wrong += "$what -> ${r.moves.map { it.uci() }}"
            if (r !is Detection.Found) missed += "$what -> $r"
            g.play(m)
            if (r != Detection.Found(listOf(m))) {
                // What the user would do: enter the move by hand, which re-syncs.
                d.requestResync()
                settle(d, g.position, g.position)
            }
        }
        return Outcome(missed, wrong)
    }

    companion object {
        private const val LEAN = 30.0

        /** Camera in front of White, tilted, so the near edge looks wider. */
        val WHITE_SIDE = floatArrayOf(60f, 560f, 580f, 570f, 500f, 120f, 130f, 110f)

        /** The same view from Black's side: a1 is at the top right. */
        val BLACK_SIDE = floatArrayOf(500f, 120f, 130f, 110f, 60f, 560f, 580f, 570f)

        /** Looking straight down. */
        val OVERHEAD = floatArrayOf(80f, 560f, 560f, 560f, 560f, 80f, 80f, 80f)
    }
}
