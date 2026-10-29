package com.teamadvert.chesscam.vision

import kotlin.math.abs

/** A projective map between two planes, defined by four point pairs. */
class Homography private constructor(private val h: DoubleArray) {

    fun mapX(x: Double, y: Double): Double = (h[0] * x + h[1] * y + h[2]) / (h[6] * x + h[7] * y + 1.0)

    fun mapY(x: Double, y: Double): Double = (h[3] * x + h[4] * y + h[5]) / (h[6] * x + h[7] * y + 1.0)

    companion object {
        /**
         * Finds H with H(src[i]) = dst[i]. Points are given as flat (x0, y0, ..., x3, y3) arrays.
         * Returns null when the points are degenerate (three of them in a line).
         */
        fun fromPoints(src: DoubleArray, dst: DoubleArray): Homography? {
            require(src.size == 8 && dst.size == 8)
            // Each pair gives two rows of A * [h0..h7] = b.
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = src[2 * i]; val y = src[2 * i + 1]
                val u = dst[2 * i]; val v = dst[2 * i + 1]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            val solution = solve(a) ?: return null
            return Homography(solution)
        }

        // Gaussian elimination with partial pivoting on an 8x9 augmented matrix.
        private fun solve(a: Array<DoubleArray>): DoubleArray? {
            val n = 8
            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
                if (abs(a[pivot][col]) < 1e-10) return null
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                for (r in 0 until n) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    if (f == 0.0) continue
                    for (c in col..n) a[r][c] -= f * a[col][c]
                }
            }
            return DoubleArray(n) { a[it][n] / a[it][it] }
        }
    }
}
