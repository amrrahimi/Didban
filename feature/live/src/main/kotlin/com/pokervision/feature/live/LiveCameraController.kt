package com.pokervision.feature.live

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pokervision.core.common.timing.FrameRateLimiter
import com.pokervision.core.media.BitmapOps
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Back camera with Preview + ImageAnalysis, both 16:9 so the analysed frame covers exactly what
 * the preview shows (needed to draw boxes over the right cards). Each analysed frame is delivered
 * upright as a Bitmap on a single background thread, throttled to the configured rate; frames that
 * arrive while the previous one is still being processed are dropped (KEEP_ONLY_LATEST).
 */
class LiveCameraController(private val context: Context, initialFps: Int) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val limiter = FrameRateLimiter(initialFps.toDouble())

    @Volatile
    private var targetFps: Int = initialFps

    @Volatile
    private var released = false
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    fun setFps(fps: Int) {
        targetFps = fps
    }

    fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        onFrame: (Bitmap) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (released) return@addListener
            try {
                val p = future.get()
                provider = p
                val selector16x9 = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
                val preview = Preview.Builder().setResolutionSelector(selector16x9).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                // Full HD for analysis: the card area is cropped from it at near-native resolution.
                val analysisSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    image.use {
                        if (limiter.targetFps != targetFps.toDouble()) limiter.targetFps = targetFps.toDouble()
                        if (!limiter.shouldProcess(it.imageInfo.timestamp)) return@setAnalyzer
                        val upright = BitmapOps.rotate(it.toBitmap(), it.imageInfo.rotationDegrees)
                        try {
                            onFrame(upright)
                        } finally {
                            upright.recycle()
                        }
                    }
                }
                p.unbindAll()
                camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Focuses and meters on the tapped point (cards held close are often out of focus otherwise). */
    fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        control.startFocusAndMetering(
            FocusMeteringAction.Builder(point).setAutoCancelDuration(FOCUS_HOLD_SECONDS, TimeUnit.SECONDS).build(),
        )
    }

    fun release() {
        released = true
        provider?.unbindAll()
        executor.shutdown()
    }

    private companion object {
        val ANALYSIS_SIZE = Size(1920, 1080)
        const val FOCUS_HOLD_SECONDS = 5L
    }
}
