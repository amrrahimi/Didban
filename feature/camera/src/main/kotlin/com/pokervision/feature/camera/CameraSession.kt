package com.pokervision.feature.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pokervision.core.common.timing.FrameRateLimiter
import com.pokervision.core.common.timing.ThroughputMeter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class CaptureMode { PHOTO, VIDEO }

enum class LensFacing { BACK, FRONT }

/** Diagnostics of the analysis stream (no detection yet: Phase 3 plugs a detector into [FrameAnalyzer]). */
data class FrameStats(
    val analyzedFrames: Long = 0,
    val analyzedFps: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
)

/**
 * Receives camera frames on a background thread, throttled to the configured analysis rate.
 * Phase 1 only measures throughput; the card detector will be invoked from [analyze] in Phase 3.
 */
internal class FrameAnalyzer(
    initialFps: Int,
    private val onStats: (FrameStats) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile
    var targetFps: Int = initialFps

    private val limiter = FrameRateLimiter(initialFps.toDouble())
    private val meter = ThroughputMeter()
    private var count = 0L

    override fun analyze(image: ImageProxy) {
        image.use {
            if (limiter.targetFps != targetFps.toDouble()) limiter.targetFps = targetFps.toDouble()
            val ts = it.imageInfo.timestamp
            if (!limiter.shouldProcess(ts)) return
            meter.record(ts)
            count++
            onStats(FrameStats(count, meter.fps(), it.width, it.height, it.imageInfo.rotationDegrees))
        }
    }
}

/**
 * Owns CameraX use cases. Binds Preview + ImageAnalysis + (ImageCapture | VideoCapture):
 * three use cases is the combination guaranteed on every hardware level, including LEGACY.
 *
 * Create once per screen, call [release] when the screen leaves composition.
 */
class CameraSession(private val context: Context, initialAnalysisFps: Int) {

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var released = false

    private val _frameStats = MutableStateFlow(FrameStats())
    val frameStats: StateFlow<FrameStats> = _frameStats.asStateFlow()

    private val analyzer = FrameAnalyzer(initialAnalysisFps) { _frameStats.value = it }

    fun setAnalysisFps(fps: Int) {
        analyzer.targetFps = fps
    }

    fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        mode: CaptureMode,
        lens: LensFacing,
        onError: (Throwable) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (released) return@addListener
            try {
                val p = future.get()
                provider = p
                val selector = when (lens) {
                    LensFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                    LensFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                }
                if (!p.hasCamera(selector)) {
                    onError(IllegalStateException("Requested camera not available"))
                    return@addListener
                }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer) }

                p.unbindAll()
                imageCapture = null
                videoCapture = null
                when (mode) {
                    CaptureMode.PHOTO -> {
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        p.bindToLifecycle(owner, selector, preview, analysis, capture)
                        imageCapture = capture
                    }
                    CaptureMode.VIDEO -> {
                        val recorder = Recorder.Builder()
                            .setQualitySelector(
                                QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
                            )
                            .build()
                        val capture = VideoCapture.withOutput(recorder)
                        p.bindToLifecycle(owner, selector, preview, analysis, capture)
                        videoCapture = capture
                    }
                }
            } catch (e: Exception) {
                onError(e)
            }
        }, mainExecutor)
    }

    fun takePhoto(target: File, onSaved: () -> Unit, onError: (Throwable) -> Unit) {
        val capture = imageCapture ?: return onError(IllegalStateException("Photo mode not bound"))
        val options = ImageCapture.OutputFileOptions.Builder(target).build()
        capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onSaved()
            override fun onError(exception: ImageCaptureException) {
                target.delete()
                onError(exception)
            }
        })
    }

    val isRecording: Boolean get() = activeRecording != null

    /**
     * Starts video recording without audio (no microphone permission is requested by this app).
     * [onProgress] reports recorded duration; [onFinished] is called once with success or error.
     */
    fun startRecording(
        target: File,
        onProgress: (durationMillis: Long) -> Unit,
        onFinished: (error: Throwable?) -> Unit,
    ) {
        val capture = videoCapture ?: return onFinished(IllegalStateException("Video mode not bound"))
        if (activeRecording != null) return
        val options = FileOutputOptions.Builder(target).build()
        activeRecording = capture.output
            .prepareRecording(context, options)
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Status ->
                        onProgress(event.recordingStats.recordedDurationNanos / 1_000_000)
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        if (event.hasError()) {
                            target.delete()
                            onFinished(event.cause ?: IllegalStateException("Recording error ${event.error}"))
                        } else {
                            onFinished(null)
                        }
                    }
                    else -> Unit
                }
            }
    }

    fun stopRecording() {
        activeRecording?.stop()
    }

    fun release() {
        released = true
        activeRecording?.stop()
        activeRecording = null
        provider?.unbindAll()
        analysisExecutor.shutdown()
    }

    private companion object {
        val ANALYSIS_SIZE = Size(1280, 720)
    }
}
