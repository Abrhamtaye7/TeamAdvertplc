package com.teamadvert.chesscam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.ImageAnalysis
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.teamadvert.chesscam.chess.BLACK
import com.teamadvert.chesscam.chess.colorWord
import com.teamadvert.chesscam.ui.OverlayIndicatorView

/**
 * Keeps following the game while another app is on screen: runs the camera as a
 * foreground service and draws [OverlayIndicatorView] over everything. The overlay does
 * not take touches, so the app underneath works as normal.
 */
class OverlayService : LifecycleService(), GameSession.Listener {

    private lateinit var session: GameSession
    private lateinit var windowManager: WindowManager
    private var overlay: OverlayIndicatorView? = null
    private var analysis: ImageAnalysis? = null
    private var notificationText: String? = null
    private var notificationPaused: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        session = GameSession.get(this)
        windowManager = getSystemService(WindowManager::class.java)
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0,
        )
        session.tracker.wantFrames = false
        analysis = CameraSetup.bind(this, this, session) { stopSelf() }
        addOverlay()
        session.addListener(this)
        session.overlayActive = true
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_UNDO -> session.undo()
            ACTION_RESYNC -> session.resync()
            ACTION_RESUME -> session.resume()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        session.removeListener(this)
        session.overlayActive = false
        analysis?.let { CameraSetup.unbind(this, it) }
        overlay?.let { windowManager.removeView(it) }
        overlay = null
        super.onDestroy()
    }

    override fun onSessionChanged() {
        overlay?.update(
            session.status(),
            session.suggestedMove(),
            session.game.moves.lastOrNull(),
            session.settings.myColor == BLACK,
            session.settings.overlayOpacity,
        )
        val text = statusText()
        val paused = session.mode == GameSession.Mode.PAUSED
        if (text != notificationText || paused != notificationPaused) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun addOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )
        // Android 12+ only lets touches through to the app below if the overlay is at most 80% opaque.
        params.alpha = 0.8f
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val view = OverlayIndicatorView(this)
        try {
            windowManager.addView(view, params)
        } catch (e: RuntimeException) {
            // The "display over other apps" permission was taken away.
            Log.w("ChessCam", "overlay refused", e)
            stopSelf()
            return
        }
        overlay = view
        onSessionChanged()
    }

    private fun statusText(): String = when (session.status()) {
        GameSession.Status.YOUR_MOVE -> "Your move is shown"
        GameSession.Status.THINKING -> "Thinking…"
        GameSession.Status.WAITING -> "Waiting for ${colorWord(session.game.position.sideToMove)}'s move"
        GameSession.Status.ATTENTION -> "Lost track of the board. Tap to open, or Re-sync"
        GameSession.Status.PAUSED -> "Paused. Set the board to match, then Resume"
        GameSession.Status.OVER -> session.resultText()
        GameSession.Status.SETUP -> "Open ChessCam to set up"
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val text = if (::session.isInitialized) statusText() else ""
        val paused = ::session.isInitialized && session.mode == GameSession.Mode.PAUSED
        notificationText = text
        notificationPaused = paused
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_board)
            .setContentTitle(getString(R.string.overlay_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.undo), action(ACTION_UNDO))
            .addAction(
                0,
                getString(if (paused) R.string.resume else R.string.resync),
                action(if (paused) ACTION_RESUME else ACTION_RESYNC),
            )
            .addAction(0, getString(R.string.stop), action(ACTION_STOP))
            .build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this, name.hashCode(),
        Intent(this, OverlayService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.teamadvert.chesscam.STOP"
        private const val ACTION_UNDO = "com.teamadvert.chesscam.UNDO"
        private const val ACTION_RESYNC = "com.teamadvert.chesscam.RESYNC"
        private const val ACTION_RESUME = "com.teamadvert.chesscam.RESUME"

        /** Whether the overlay is up, read on the main thread. */
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
