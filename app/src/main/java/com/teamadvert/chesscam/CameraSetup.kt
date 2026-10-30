package com.teamadvert.chesscam

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/** Binds the back camera to the session's tracker, the same way for the activity and the overlay. */
object CameraSetup {

    /** Starts the camera for as long as [owner] is started. Returns a handle for [unbind]. */
    fun bind(
        context: Context,
        owner: LifecycleOwner,
        session: GameSession,
        onError: (Exception) -> Unit,
    ): ImageAnalysis {
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .setTargetRotation(session.cameraRotation)
            .build()
        analysis.setAnalyzer(session.analysisExecutor, session.tracker)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                future.get().bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e("ChessCam", "camera start failed", e)
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
        return analysis
    }

    fun unbind(context: Context, analysis: ImageAnalysis) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                future.get().unbind(analysis)
            } catch (e: Exception) {
                Log.w("ChessCam", "camera unbind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
