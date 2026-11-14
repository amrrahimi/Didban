package com.pokervision.feature.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pokervision.core.domain.media.PixelSize
import com.pokervision.core.domain.media.ResizeMath
import com.pokervision.core.domain.vision.TilePlan
import com.pokervision.core.media.BitmapOps
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Google Translate style" camera: a live preview plus full-quality still photos on demand. A still
 * photo is focused, exposed and processed by the camera pipeline (sharper than video frames, and not
 * smeared by hand shake). Preview and photo are both 4:3 so the preview shows what will be captured.
 */
class ScanCameraController(private val context: Context) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    @Volatile
    private var released = false

    fun bind(owner: LifecycleOwner, previewView: PreviewView, onError: (Throwable) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (released) return@addListener
            try {
                val p = future.get()
                provider = p
                val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                val preview = Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(ratio)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build(),
                    )
                    .build()
                p.unbindAll()
                camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
            } catch (e: Exception) {
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Takes one photo and delivers it upright, scaled so its long side is [TilePlan.SCAN_LONG_SIDE].
     * Callbacks run on a background thread.
     */
    fun capture(onPhoto: (Bitmap) -> Unit, onFailure: (Throwable) -> Unit) {
        val capture = imageCapture ?: return onFailure(IllegalStateException("Camera not ready"))
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val photo = try {
                    decode(image)
                } catch (e: Exception) {
                    onFailure(e)
                    return
                } finally {
                    image.close()
                }
                if (photo == null) onFailure(IllegalStateException("Could not decode photo")) else onPhoto(photo)
            }

            override fun onError(exception: ImageCaptureException) {
                onFailure(exception)
            }
        })
    }

    /** JPEG bytes → subsampled decode (memory-friendly) → upright → exact scale. */
    private fun decode(image: ImageProxy): Bitmap? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ResizeMath.inSampleSize(PixelSize(bounds.outWidth, bounds.outHeight), TilePlan.SCAN_LONG_SIDE)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val upright = BitmapOps.rotate(decoded, image.imageInfo.rotationDegrees)
        return BitmapOps.scaleToFit(upright, TilePlan.SCAN_LONG_SIDE)
    }

    fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        control.startFocusAndMetering(FocusMeteringAction.Builder(point).setAutoCancelDuration(5, TimeUnit.SECONDS).build())
    }

    fun release() {
        released = true
        provider?.unbindAll()
        executor.shutdown()
    }
}
