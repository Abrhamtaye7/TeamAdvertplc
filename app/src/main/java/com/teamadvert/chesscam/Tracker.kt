package com.teamadvert.chesscam

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.teamadvert.chesscam.chess.Move
import com.teamadvert.chesscam.chess.Position
import com.teamadvert.chesscam.vision.BoardSampler
import com.teamadvert.chesscam.vision.Detection
import com.teamadvert.chesscam.vision.MoveDetector
import com.teamadvert.chesscam.vision.RgbaFrame
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Receives camera frames on the analysis thread, keeps a copy for display and runs
 * the move detector. Everything that touches the detector happens on that thread;
 * other threads hand it work with [post].
 */
class Tracker(private val listener: Listener) : ImageAnalysis.Analyzer {

    interface Listener {
        /**
         * A new frame to display, while [wantFrames] is set. Called on the analysis thread;
         * the bitmap is reused later.
         */
        fun onFrame(frame: Bitmap, squareChange: FloatArray?)

        /** Called on the analysis thread for each frame checked against [Snapshot.key]. */
        fun onDetection(detection: Detection, key: String)
    }

    /** What the detector should compare against, set from the UI thread. */
    class Snapshot(val position: Position, val hint: Move?, val key: String)

    /** Board corners a1, h1, h8, a8 in frame pixels, or null before the first frame. */
    @Volatile var corners: FloatArray? = null

    /** The position to track, or null while tracking is paused. */
    @Volatile var snapshot: Snapshot? = null

    /** Whether anything is showing the camera picture; when not, frames are not copied for display. */
    @Volatile var wantFrames = true

    val detector = MoveDetector()

    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var nextBitmap = 0
    private var packed: ByteBuffer? = null
    private var sampler: BoardSampler? = null
    private var samplerCorners: FloatArray? = null
    private val features = FloatArray(BoardSampler.FEATURE_COUNT)
    private var lastFrameAt = 0L

    /** Runs [command] on the analysis thread before the next frame. */
    fun post(command: () -> Unit) {
        commands += command
    }

    override fun analyze(image: ImageProxy) {
        image.use {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return
            lastFrameAt = now
            while (true) (commands.poll() ?: break).invoke()

            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val buffer = plane.buffer
            val bitmap = if (wantFrames) displayBitmap(w, h) else null
            if (bitmap != null) copyToBitmap(buffer, plane.rowStride, w, h, bitmap)

            var change: FloatArray? = null
            val c = corners
            val snap = snapshot
            if (c != null) {
                val s = samplerFor(c, w, h)
                if (s.toImage != null) {
                    s.extract(RgbaFrame(buffer, plane.rowStride, w, h), features)
                    if (snap != null) {
                        val detection = detector.process(features, snap.position, snap.hint)
                        change = detector.squareChange.copyOf()
                        listener.onDetection(detection, snap.key)
                    }
                }
            }
            if (bitmap != null) listener.onFrame(bitmap, change)
        }
    }

    private fun samplerFor(c: FloatArray, w: Int, h: Int): BoardSampler {
        val cached = sampler
        if (cached != null && samplerCorners.contentEquals(c) && cached.width == w && cached.height == h) return cached
        return BoardSampler(c, w, h).also {
            sampler = it
            samplerCorners = c.copyOf()
            detector.geometry = it.geometry
        }
    }

    private fun displayBitmap(w: Int, h: Int): Bitmap {
        val i = nextBitmap
        nextBitmap = (nextBitmap + 1) % bitmaps.size
        val b = bitmaps[i]
        if (b != null && b.width == w && b.height == h) return b
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmaps[i] = it }
    }

    private fun copyToBitmap(buffer: ByteBuffer, rowStride: Int, w: Int, h: Int, bitmap: Bitmap) {
        val rowBytes = w * 4
        if (rowStride == rowBytes) {
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            val out = packed?.takeIf { it.capacity() == rowBytes * h } ?: ByteBuffer.allocateDirect(rowBytes * h).also { packed = it }
            out.clear()
            for (y in 0 until h) {
                val src = buffer.duplicate()
                src.position(y * rowStride)
                src.limit(y * rowStride + rowBytes)
                out.put(src)
            }
            out.rewind()
            bitmap.copyPixelsFromBuffer(out)
        }
        buffer.rewind()
    }

    companion object {
        /** About 15 frames a second is plenty to follow a game and saves battery. */
        const val FRAME_INTERVAL_MS = 66L
    }
}
