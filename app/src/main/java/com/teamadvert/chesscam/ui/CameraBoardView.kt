package com.teamadvert.chesscam.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.teamadvert.chesscam.vision.BoardSpace
import com.teamadvert.chesscam.vision.Homography
import kotlin.math.hypot
import kotlin.math.min

/**
 * Shows the camera frame with the board grid on top. While [editing], the four corner
 * dots (a1, h1, h8, a8) can be dragged onto the corners of the real board, with a
 * magnifier so the finger does not hide the spot.
 */
class CameraBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called with the new corners (frame pixels) after the user moves one. */
    var onCornersChanged: ((FloatArray) -> Unit)? = null

    var editing = true
        set(v) { field = v; invalidate() }

    /** Board corners a1, h1, h8, a8 in frame pixels. */
    var corners: FloatArray? = null
        private set

    /** Fraction 0..1 of change per square, drawn as a tint. */
    var squareChange: FloatArray? = null
        set(v) { field = v; invalidate() }

    private var frame: Bitmap? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var pendingNormalized: FloatArray? = null
    private var dragging = -1

    private val density = resources.displayMetrics.density
    private val frameRect = RectF()
    private val srcRect = Rect()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 230, 118); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val edgePaint = Paint(gridPaint).apply { strokeWidth = 3f * density }
    private val changePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 193, 7) }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 14f * density; isFakeBoldText = true
        setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
    }
    private val loupeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()

    /** Sets corners from saved fractions; applied once the frame size is known. */
    fun setNormalizedCorners(normalized: FloatArray?) {
        pendingNormalized = normalized
        if (frameWidth > 0) applyNormalized()
    }

    fun normalizedCorners(): FloatArray? = corners?.let { c ->
        FloatArray(8) { if (it % 2 == 0) c[it] / frameWidth else c[it] / frameHeight }
    }

    /** Re-labels the corners a quarter turn, for when a1 is not where the dots say. */
    fun rotateLabels() {
        val c = corners ?: return
        corners = floatArrayOf(c[6], c[7], c[0], c[1], c[2], c[3], c[4], c[5])
        onCornersChanged?.invoke(corners!!.copyOf())
        invalidate()
    }

    fun setFrame(bitmap: Bitmap) {
        frame = bitmap
        if (bitmap.width != frameWidth || bitmap.height != frameHeight) {
            val old = normalizedCorners()
            frameWidth = bitmap.width
            frameHeight = bitmap.height
            if (pendingNormalized == null) pendingNormalized = old
            applyNormalized()
        }
        invalidate()
    }

    private fun applyNormalized() {
        val n = pendingNormalized ?: defaultCorners()
        pendingNormalized = null
        corners = FloatArray(8) { if (it % 2 == 0) n[it] * frameWidth else n[it] * frameHeight }
        onCornersChanged?.invoke(corners!!.copyOf())
    }

    private fun defaultCorners(): FloatArray {
        // A centred square covering 70% of the frame width, White at the bottom.
        val side = 0.7f
        val halfH = side / 2 * frameWidth / frameHeight
        val l = 0.5f - side / 2; val r = 0.5f + side / 2
        val t = 0.5f - halfH; val b = 0.5f + halfH
        return floatArrayOf(l, b, r, b, r, t, l, t)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bmp = frame ?: return
        val scale = min(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val dx = (width - frameWidth * scale) / 2
        val dy = (height - frameHeight * scale) / 2
        frameRect.set(dx, dy, dx + frameWidth * scale, dy + frameHeight * scale)
        canvas.drawBitmap(bmp, null, frameRect, bitmapPaint)

        val c = corners ?: return
        val h = Homography.fromPoints(BoardSpace.CORNERS, DoubleArray(8) { c[it].toDouble() }) ?: return
        fun vx(u: Double, v: Double) = dx + h.mapX(u, v).toFloat() * scale
        fun vy(u: Double, v: Double) = dy + h.mapY(u, v).toFloat() * scale

        squareChange?.let { change ->
            for (sq in 0 until 64) {
                val a = change[sq]
                if (a < 0.15f) continue
                val u = BoardSpace.squareLeft(sq); val v = BoardSpace.squareTop(sq)
                path.reset()
                path.moveTo(vx(u, v), vy(u, v))
                path.lineTo(vx(u + 1, v), vy(u + 1, v))
                path.lineTo(vx(u + 1, v + 1), vy(u + 1, v + 1))
                path.lineTo(vx(u, v + 1), vy(u, v + 1))
                path.close()
                changePaint.color = Color.argb((a * 150).toInt(), 255, 64, 64)
                canvas.drawPath(path, changePaint)
            }
        }

        for (i in 0..8) {
            val p = if (i == 0 || i == 8) edgePaint else gridPaint
            val t = i.toDouble()
            canvas.drawLine(vx(t, 0.0), vy(t, 0.0), vx(t, 8.0), vy(t, 8.0), p)
            canvas.drawLine(vx(0.0, t), vy(0.0, t), vx(8.0, t), vy(8.0, t), p)
        }

        if (!editing) return
        val radius = 11f * density
        for (i in 0 until 4) {
            val x = dx + c[2 * i] * scale
            val y = dy + c[2 * i + 1] * scale
            canvas.drawCircle(x, y, radius, handlePaint)
            canvas.drawCircle(x, y, radius, handleRing)
            canvas.drawText(LABELS[i], x + radius + 2 * density, y - radius, labelPaint)
        }

        if (dragging >= 0) drawLoupe(canvas, bmp, c[2 * dragging], c[2 * dragging + 1], dx, dy, scale)
    }

    private fun drawLoupe(canvas: Canvas, bmp: Bitmap, fx: Float, fy: Float, dx: Float, dy: Float, scale: Float) {
        val size = 120f * density
        val zoom = 3f
        val half = size / zoom / scale / 2
        srcRect.set((fx - half).toInt(), (fy - half).toInt(), (fx + half).toInt(), (fy + half).toInt())
        // Put the magnifier in the top corner away from the finger.
        val fingerX = dx + fx * scale
        val left = if (fingerX < width / 2f) width - size - 8 * density else 8 * density
        val top = 8 * density
        val dst = RectF(left, top, left + size, top + size)
        canvas.save()
        path.reset()
        path.addOval(dst, Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bmp, srcRect, dst, bitmapPaint)
        canvas.restore()
        canvas.drawOval(dst, loupeBorder)
        canvas.drawLine(dst.centerX() - 10 * density, dst.centerY(), dst.centerX() + 10 * density, dst.centerY(), handleRing)
        canvas.drawLine(dst.centerX(), dst.centerY() - 10 * density, dst.centerX(), dst.centerY() + 10 * density, handleRing)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = corners
        if (!editing || c == null || frameWidth == 0) return false
        val scale = min(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val dx = (width - frameWidth * scale) / 2
        val dy = (height - frameHeight * scale) / 2
        val fx = ((event.x - dx) / scale).coerceIn(0f, frameWidth - 1f)
        val fy = ((event.y - dy) / scale).coerceIn(0f, frameHeight - 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var best = -1
                var bestDist = 48 * density / scale
                for (i in 0 until 4) {
                    val d = hypot(c[2 * i] - fx, c[2 * i + 1] - fy)
                    if (d < bestDist) { best = i; bestDist = d }
                }
                dragging = best
                if (best < 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                c[2 * dragging] = fx
                c[2 * dragging + 1] = fy
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging >= 0) {
                dragging = -1
                onCornersChanged?.invoke(c.copyOf())
            }
        }
        invalidate()
        return true
    }

    companion object {
        private val LABELS = arrayOf("a1", "h1", "h8", "a8")
    }
}
