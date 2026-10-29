package com.teamadvert.chesscam.vision

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Read access to an upright camera frame. */
interface FrameSource {
    val width: Int
    val height: Int

    /** Packed 0xRRGGBB colour at pixel (x, y). */
    fun rgb(x: Int, y: Int): Int
}

/** An RGBA_8888 frame as delivered by CameraX ImageAnalysis. */
class RgbaFrame(
    private val buffer: ByteBuffer,
    private val rowStride: Int,
    override val width: Int,
    override val height: Int,
) : FrameSource {
    override fun rgb(x: Int, y: Int): Int {
        val i = y * rowStride + x * 4
        return ((buffer.get(i).toInt() and 0xff) shl 16) or
            ((buffer.get(i + 1).toInt() and 0xff) shl 8) or
            (buffer.get(i + 2).toInt() and 0xff)
    }
}

/**
 * Board coordinates: u runs 0..8 from the a-file to the h-file, v runs 0..8 from
 * the rank-8 edge to the rank-1 edge, so (0, 8) is the outer corner of a1.
 */
object BoardSpace {
    /** Board-space corners in the order the user places them: a1, h1, h8, a8. */
    val CORNERS = doubleArrayOf(0.0, 8.0, 8.0, 8.0, 8.0, 0.0, 0.0, 0.0)

    fun squareLeft(sq: Int) = (sq and 7).toDouble()
    fun squareTop(sq: Int) = (7 - (sq shr 3)).toDouble()
}

/**
 * How squares relate to the camera: which neighbour each square's piece leans into on
 * camera, and how much each cell of a square counts when comparing frames.
 */
class BoardGeometry(
    /** For each square, the square that lies "up" from it in the picture, or -1. */
    val behind: IntArray,
    private val weights: FloatArray,
) {
    private val weightSums = FloatArray(64) { sq -> (0 until CELLS2).sumOf { weights[sq * CELLS2 + it].toDouble() }.toFloat() }

    /** Weighted mean colour difference of one square between two feature arrays. */
    fun diff(a: FloatArray, b: FloatArray, sq: Int): Float {
        var total = 0f
        var o = sq * BoardSampler.PER_SQUARE
        val w = sq * CELLS2
        for (cell in 0 until CELLS2) {
            val d = abs(a[o] - b[o]) + abs(a[o + 1] - b[o + 1]) + abs(a[o + 2] - b[o + 2])
            total += weights[w + cell] * d / 3f
            o += 3
        }
        return total / weightSums[sq]
    }

    companion object {
        private const val CELLS2 = BoardSampler.CELLS * BoardSampler.CELLS

        /** Centre cells count double. */
        fun centreWeight(cell: Int): Float {
            val x = cell % BoardSampler.CELLS
            val y = cell / BoardSampler.CELLS
            return if (x in 1..2 && y in 1..2) 2f else 1f
        }

        /** No camera angle known: every square weighted toward its centre. */
        val DEFAULT = BoardGeometry(IntArray(64) { -1 }, FloatArray(64 * CELLS2) { centreWeight(it % CELLS2) })
    }
}

/**
 * Summarises each of the 64 squares as a small grid of average colours, so that two
 * frames can be compared square by square. Sample positions are worked out once per
 * corner placement.
 */
class BoardSampler(corners: FloatArray, val width: Int, val height: Int) {
    /** Maps board coordinates to frame pixels. Null if the corners are degenerate. */
    val toImage: Homography? = Homography.fromPoints(BoardSpace.CORNERS, DoubleArray(8) { corners[it].toDouble() })

    private val points = IntArray(64 * CELLS * CELLS * SAMPLES * SAMPLES * 2)

    /**
     * Seen at an angle, a standing piece also covers the near part of the square behind
     * it. So each square's cells nearest the camera count less, and the detector is told
     * which square is behind which.
     */
    val geometry: BoardGeometry

    init {
        require(corners.size == 8)
        val h = toImage
        val toBoard = Homography.fromPoints(DoubleArray(8) { corners[it].toDouble() }, BoardSpace.CORNERS)
        val behind = IntArray(64) { -1 }
        val weights = FloatArray(64 * CELLS * CELLS) { BoardGeometry.centreWeight(it % (CELLS * CELLS)) }
        if (h != null && toBoard != null) {
            for (sq in 0 until 64) {
                val u = BoardSpace.squareLeft(sq) + 0.5
                val v = BoardSpace.squareTop(sq) + 0.5
                val x = h.mapX(u, v)
                val y = h.mapY(u, v)
                val step = hypot(h.mapX(u, v + 0.5) - h.mapX(u, v - 0.5), h.mapY(u, v + 0.5) - h.mapY(u, v - 0.5))
                val du = toBoard.mapX(x, y - step) - u
                val dv = toBoard.mapY(x, y - step) - v
                val len = hypot(du, dv)
                if (len < 1e-6) continue
                // The piece in front leans into the camera side of the square, so cells count
                // less the nearer they are to that side, and the nearest not at all.
                for (cell in 0 until CELLS * CELLS) {
                    val ox = cellCentre(cell % CELLS)
                    val oy = cellCentre(cell / CELLS)
                    val towardCamera = -(ox * du + oy * dv) / len
                    weights[sq * CELLS * CELLS + cell] *= (1 - towardCamera / 0.25).toFloat().coerceIn(0f, 1f)
                }
                val nu = u + du / len
                val nv = v + dv / len
                if (nu < 0 || nu >= 8 || nv < 0 || nv >= 8) continue
                val n = (7 - floor(nv).toInt()) * 8 + floor(nu).toInt()
                if (n != sq) behind[sq] = n
            }
        }
        geometry = BoardGeometry(behind, weights)
        if (h != null) {            var i = 0
            for (sq in 0 until 64) {
                val left = BoardSpace.squareLeft(sq)
                val top = BoardSpace.squareTop(sq)
                for (cy in 0 until CELLS) for (cx in 0 until CELLS) {
                    for (sy in 0 until SAMPLES) for (sx in 0 until SAMPLES) {
                        val u = left + MARGIN + (1 - 2 * MARGIN) * (cx + (sx + 0.5) / SAMPLES) / CELLS
                        val v = top + MARGIN + (1 - 2 * MARGIN) * (cy + (sy + 0.5) / SAMPLES) / CELLS
                        points[i++] = h.mapX(u, v).roundToInt().coerceIn(0, width - 1)
                        points[i++] = h.mapY(u, v).roundToInt().coerceIn(0, height - 1)
                    }
                }
            }
        }
    }

    /** Fills [out] (size [FEATURE_COUNT]) with per-cell mean R, G, B values. */
    fun extract(frame: FrameSource, out: FloatArray) {
        var p = 0
        var o = 0
        val n = SAMPLES * SAMPLES
        repeat(64 * CELLS * CELLS) {
            var r = 0; var g = 0; var b = 0
            repeat(n) {
                val c = frame.rgb(points[p], points[p + 1])
                p += 2
                r += (c shr 16) and 0xff
                g += (c shr 8) and 0xff
                b += c and 0xff
            }
            out[o++] = r.toFloat() / n
            out[o++] = g.toFloat() / n
            out[o++] = b.toFloat() / n
        }
    }

    companion object {
        const val CELLS = 4
        const val SAMPLES = 3
        /** Fraction of each square's edge left out, so small misplacements of the corners matter less. */
        const val MARGIN = 0.1
        const val PER_SQUARE = CELLS * CELLS * 3
        const val FEATURE_COUNT = 64 * PER_SQUARE

        /** Offset of a cell's centre from the square's centre, in squares. */
        private fun cellCentre(i: Int) = MARGIN + (1 - 2 * MARGIN) * (i + 0.5) / CELLS - 0.5
    }
}
