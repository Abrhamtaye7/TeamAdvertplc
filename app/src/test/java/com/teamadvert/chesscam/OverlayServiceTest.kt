package com.teamadvert.chesscam

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.teamadvert.chesscam.chess.Move
import com.teamadvert.chesscam.ui.OverlayIndicatorView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayServiceTest {

    @Test fun showsOverlayAndNotificationAndMutesSpeech() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ShadowSettings.setCanDrawOverlays(true)
        val session = GameSession.get(app)
        session.startGame()

        val controller = Robolectric.buildService(OverlayService::class.java).create()
        ShadowLooper.idleMainLooper()
        val service = controller.get()
        assertTrue(OverlayService.running)
        assertTrue(session.overlayActive)
        assertFalse(session.speaker.enabled)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        val windows = Shadow.extract<ShadowWindowManagerImpl>(app.getSystemService(WindowManager::class.java)).views
        assertEquals(1, windows.size)
        assertTrue(windows[0] is OverlayIndicatorView)

        controller.destroy()
        assertFalse(OverlayService.running)
        assertFalse(session.overlayActive)
        assertTrue(session.speaker.enabled)
    }

    @Test fun indicatorDrawsEveryStatus() {
        val view = OverlayIndicatorView(ApplicationProvider.getApplicationContext())
        view.layout(0, 0, 1080, 2340)
        val canvas = Canvas(Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888))
        for (status in GameSession.Status.values()) {
            view.update(status, Move.fromUci("g1f3"), Move.fromUci("e7e5"), flipped = status.ordinal % 2 == 0, opacity = 0.6f)
            view.draw(canvas)
        }
    }
}
