package com.teamadvert.chesscam

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.teamadvert.chesscam.chess.parseSquare
import com.teamadvert.chesscam.ui.BoardDiagramView
import com.teamadvert.chesscam.ui.CameraBoardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings

/** Starts the real activity (without camera or engine) and walks through the main buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {

    @Test fun setUpStartAndEnterAMoveByHand() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val start = a.findViewById<View>(R.id.startButton)
                assertEquals(View.VISIBLE, start.visibility)

                // A camera frame arrives: the default corners appear.
                a.onFrame(Bitmap.createBitmap(960, 1280, Bitmap.Config.ARGB_8888), null)
                ShadowLooper.idleMainLooper()
                assertNotNull(a.findViewById<CameraBoardView>(R.id.camera).corners)

                a.findViewById<View>(R.id.colorButton).performClick()
                a.findViewById<View>(R.id.colorButton).performClick()
                a.findViewById<View>(R.id.rotateButton).performClick()
                start.performClick()
                ShadowLooper.idleMainLooper()
                assertEquals(View.GONE, start.visibility)
                assertEquals(View.VISIBLE, a.findViewById<View>(R.id.pauseButton).visibility)

                val diagram = a.findViewById<BoardDiagramView>(R.id.diagram)
                diagram.onSquareTapped!!.invoke(parseSquare("e2"))
                diagram.onSquareTapped!!.invoke(parseSquare("e4"))
                ShadowLooper.idleMainLooper()
                assertEquals("1. e4", a.findViewById<TextView>(R.id.moveList).text.toString())
                assertTrue(a.findViewById<TextView>(R.id.status).text.contains("Black"))

                a.findViewById<View>(R.id.undoButton).performClick()
                ShadowLooper.idleMainLooper()
                assertEquals("", a.findViewById<TextView>(R.id.moveList).text.toString())
                a.findViewById<View>(R.id.pauseButton).performClick()
                a.findViewById<View>(R.id.resyncButton).performClick()
                a.findViewById<View>(R.id.repeatButton).performClick()
                ShadowLooper.idleMainLooper()
            }
        }
    }

    private fun call(a: MainActivity, name: String) {
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(a)
        ShadowLooper.idleMainLooper()
    }

    @Test fun menusAndDialogs() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.onFrame(Bitmap.createBitmap(960, 1280, Bitmap.Config.ARGB_8888), null)
                ShadowLooper.idleMainLooper()
                call(a, "showSettings")
                (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                call(a, "showHelp")
                a.findViewById<View>(R.id.startButton).performClick()
                val session = GameSession.get(a)
                session.setAdjusting(true)
                assertEquals(View.VISIBLE, a.findViewById<View>(R.id.rotateButton).visibility)
                session.setAdjusting(false)
                assertEquals(View.GONE, a.findViewById<View>(R.id.rotateButton).visibility)
                call(a, "confirmNewGame")
                assertEquals(View.VISIBLE, a.findViewById<View>(R.id.startButton).visibility)
                call(a, "sharePgn")
                a.findViewById<View>(R.id.moreButton).performClick()
                ShadowLooper.idleMainLooper()
            }
        }
    }

    @Test fun overlayAsksForPermissionThenStartsTheService() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.onFrame(Bitmap.createBitmap(960, 1280, Bitmap.Config.ARGB_8888), null)
                a.findViewById<View>(R.id.startButton).performClick()
                ShadowLooper.idleMainLooper()
                val overlay = a.findViewById<View>(R.id.overlayButton)
                assertEquals(View.VISIBLE, overlay.visibility)

                ShadowSettings.setCanDrawOverlays(false)
                overlay.performClick()
                ShadowLooper.idleMainLooper()
                assertTrue(ShadowDialog.getLatestDialog().isShowing)
                ShadowDialog.getLatestDialog().dismiss()

                ShadowSettings.setCanDrawOverlays(true)
                overlay.performClick()
                ShadowLooper.idleMainLooper()
                val started = shadowOf(app).nextStartedService
                assertEquals(OverlayService::class.java.name, started.component!!.className)
            }
        }
    }
}
