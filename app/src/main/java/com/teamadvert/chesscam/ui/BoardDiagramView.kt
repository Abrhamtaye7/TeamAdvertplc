package com.teamadvert.chesscam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.teamadvert.chesscam.chess.EMPTY
import com.teamadvert.chesscam.chess.Move
import com.teamadvert.chesscam.chess.Position
import com.teamadvert.chesscam.chess.fileOf
import com.teamadvert.chesscam.chess.rankOf
import com.teamadvert.chesscam.chess.square
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A 2D board showing the tracked position, the last move and the suggested move as an arrow. */
class BoardDiagramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var position: Position = Position.start()
        set(v) { field = v; invalidate() }

    /** Draw from Black's side. */
    var flipped = false
        set(v) { field = v; invalidate() }

    var arrow: Move? = null
        set(v) { field = v; invalidate() }

    var lastMove: Move? = null
        set(v) { field = v; invalidate() }

    var selected = -1
        set(v) { field = v; invalidate() }

    var onSquareTapped: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val light = Paint().apply { color = Color.rgb(238, 238, 210) }
    private val dark = Paint().apply { color = Color.rgb(118, 150, 86) }
    private val lastPaint = Paint().apply { color = Color.argb(110, 255, 235, 59) }
    private val selectedPaint = Paint().apply { color = Color.argb(140, 33, 150, 243) }
    private val pieceFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pieceStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE
    }
    private val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f * density }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 112, 67); strokeCap = Paint.Cap.ROUND
    }
    private val arrowPath = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else minOf(w, h)
        setMeasuredDimension(size, size)
    }

    private fun col(sq: Int) = if (flipped) 7 - fileOf(sq) else fileOf(sq)
    private fun row(sq: Int) = if (flipped) rankOf(sq) else 7 - rankOf(sq)

    override fun onDraw(canvas: Canvas) {
        val cell = width / 8f
        for (sq in 0 until 64) {
            val x = col(sq) * cell
            val y = row(sq) * cell
            val isLight = (fileOf(sq) + rankOf(sq)) % 2 == 1
            canvas.drawRect(x, y, x + cell, y + cell, if (isLight) light else dark)
            lastMove?.let { if (sq == it.from || sq == it.to) canvas.drawRect(x, y, x + cell, y + cell, lastPaint) }
            if (sq == selected) canvas.drawRect(x, y, x + cell, y + cell, selectedPaint)
        }

        // File letters on the bottom row, rank numbers on the left column.
        for (i in 0 until 8) {
            val fileSq = square(if (flipped) 7 - i else i, if (flipped) 7 else 0)
            coordPaint.color = if ((fileOf(fileSq) + rankOf(fileSq)) % 2 == 1) dark.color else light.color
            canvas.drawText(('a' + fileOf(fileSq)).toString(), i * cell + cell - 7 * density, 8 * cell - 2 * density, coordPaint)
            val rankSq = square(if (flipped) 7 else 0, if (flipped) i else 7 - i)
            coordPaint.color = if ((fileOf(rankSq) + rankOf(rankSq)) % 2 == 1) dark.color else light.color
            canvas.drawText(('1' + rankOf(rankSq)).toString(), 2 * density, i * cell + 10 * density, coordPaint)
        }

        pieceFill.textSize = cell * 0.82f
        pieceStroke.textSize = cell * 0.82f
        pieceStroke.strokeWidth = cell * 0.035f
        val baseline = -(pieceFill.fontMetrics.ascent + pieceFill.fontMetrics.descent) / 2
        for (sq in 0 until 64) {
            val p = position[sq]
            if (p == EMPTY) continue
            val glyph = GLYPHS[abs(p)]
            val cx = col(sq) * cell + cell / 2
            val cy = row(sq) * cell + cell / 2 + baseline
            pieceFill.color = if (p > 0) Color.WHITE else Color.rgb(20, 20, 20)
            pieceStroke.color = if (p > 0) Color.rgb(20, 20, 20) else Color.rgb(200, 200, 200)
            canvas.drawText(glyph, cx, cy, pieceFill)
            canvas.drawText(glyph, cx, cy, pieceStroke)
        }

        arrow?.let { drawArrow(canvas, it, cell) }
    }

    private fun drawArrow(canvas: Canvas, move: Move, cell: Float) {
        val x1 = col(move.from) * cell + cell / 2
        val y1 = row(move.from) * cell + cell / 2
        val x2 = col(move.to) * cell + cell / 2
        val y2 = row(move.to) * cell + cell / 2
        val angle = atan2(y2 - y1, x2 - x1)
        val head = cell * 0.45f
        val endX = x2 - cos(angle) * head * 0.6f
        val endY = y2 - sin(angle) * head * 0.6f
        arrowPaint.style = Paint.Style.STROKE
        arrowPaint.strokeWidth = cell * 0.2f
        canvas.drawLine(x1, y1, endX, endY, arrowPaint)
        arrowPaint.style = Paint.Style.FILL
        arrowPath.reset()
        arrowPath.moveTo(x2, y2)
        arrowPath.lineTo(x2 - head * cos(angle - 0.5f), y2 - head * sin(angle - 0.5f))
        arrowPath.lineTo(x2 - head * cos(angle + 0.5f), y2 - head * sin(angle + 0.5f))
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val cell = width / 8f
            val c = (event.x / cell).toInt().coerceIn(0, 7)
            val r = (event.y / cell).toInt().coerceIn(0, 7)
            val sq = if (flipped) square(7 - c, r) else square(c, 7 - r)
            onSquareTapped?.invoke(sq)
        }
        return true
    }

    companion object {
        // Solid glyphs for both colours; U+FE0E asks for text rather than emoji style.
        private val GLYPHS = arrayOf("", "♟︎", "♞︎", "♝︎", "♜︎", "♛︎", "♚︎")
    }
}
