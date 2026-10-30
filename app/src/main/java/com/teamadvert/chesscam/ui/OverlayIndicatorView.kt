package com.teamadvert.chesscam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.teamadvert.chesscam.GameSession.Status
import com.teamadvert.chesscam.chess.Move
import com.teamadvert.chesscam.chess.fileOf
import com.teamadvert.chesscam.chess.rankOf

/**
 * The full-screen overlay: a see-through board grid, seen from the player's side, with
 * the suggested move's squares filled in (yellow = piece to move, green = where it goes),
 * the opponent's last move in faint blue, and a frame around the screen whose colour
 * says what is happening (see [statusColor]).
 */
class OverlayIndicatorView(context: Context) : View(context) {

    private var status = Status.WAITING
    private var move: Move? = null
    private var lastMove: Move? = null
    private var flipped = false
    private var opacity = 0.6f

    private val density = resources.displayMetrics.density
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gridDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * density }
    private val gridLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * density }

    fun update(status: Status, move: Move?, lastMove: Move?, flipped: Boolean, opacity: Float) {
        this.status = status
        this.move = move
        this.lastMove = lastMove
        this.flipped = flipped
        this.opacity = opacity.coerceIn(0.2f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val alpha = (opacity * 255).toInt()
        val frameWidth = 10f * density
        framePaint.strokeWidth = frameWidth
        framePaint.color = statusColor(status)
        framePaint.alpha = alpha
        canvas.drawRect(frameWidth / 2, frameWidth / 2, width - frameWidth / 2, height - frameWidth / 2, framePaint)

        val size = minOf(width, height) - 2 * 24f * density
        val left = (width - size) / 2
        val top = (height - size) / 2
        val cell = size / 8

        lastMove?.let {
            fill.color = LAST_MOVE
            fill.alpha = (alpha * 0.45f).toInt()
            square(canvas, it.from, left, top, cell, fill)
            square(canvas, it.to, left, top, cell, fill)
        }
        move?.let {
            outline.strokeWidth = 3f * density
            outline.color = Color.WHITE
            outline.alpha = alpha
            fill.color = FROM
            fill.alpha = alpha
            square(canvas, it.from, left, top, cell, fill)
            square(canvas, it.from, left, top, cell, outline)
            fill.color = TO
            fill.alpha = alpha
            square(canvas, it.to, left, top, cell, fill)
            square(canvas, it.to, left, top, cell, outline)
        }

        // Grid drawn twice (dark then light) so it shows over light and dark screens alike.
        gridDark.color = Color.BLACK
        gridDark.alpha = (alpha * 0.4f).toInt()
        gridLight.color = Color.WHITE
        gridLight.alpha = (alpha * 0.6f).toInt()
        for (p in listOf(gridDark, gridLight)) {
            for (i in 0..8) {
                canvas.drawLine(left + i * cell, top, left + i * cell, top + size, p)
                canvas.drawLine(left, top + i * cell, left + size, top + i * cell, p)
            }
        }
    }

    private fun square(canvas: Canvas, sq: Int, left: Float, top: Float, cell: Float, paint: Paint) {
        val col = if (flipped) 7 - fileOf(sq) else fileOf(sq)
        val row = if (flipped) rankOf(sq) else 7 - rankOf(sq)
        val x = left + col * cell
        val y = top + row * cell
        canvas.drawRect(x, y, x + cell, y + cell, paint)
    }

    companion object {
        private val FROM = Color.rgb(255, 193, 7)
        private val TO = Color.rgb(0, 230, 118)
        private val LAST_MOVE = Color.rgb(66, 165, 245)

        /** Green: your move is shown. Amber: thinking. Grey: opponent to move. Red: needs you. */
        fun statusColor(status: Status): Int = when (status) {
            Status.YOUR_MOVE -> Color.rgb(67, 160, 71)
            Status.THINKING -> Color.rgb(255, 179, 0)
            Status.WAITING -> Color.rgb(120, 144, 156)
            Status.ATTENTION -> Color.rgb(229, 57, 53)
            Status.PAUSED, Status.SETUP -> Color.rgb(158, 158, 158)
            Status.OVER -> Color.rgb(142, 36, 170)
        }
    }
}
