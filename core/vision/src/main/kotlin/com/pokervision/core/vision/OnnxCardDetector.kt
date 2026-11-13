package com.pokervision.core.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.pokervision.core.domain.vision.CardLabels
import com.pokervision.core.domain.vision.CardSighting
import com.pokervision.core.domain.vision.Letterbox
import com.pokervision.core.domain.vision.PipClusterer
import com.pokervision.core.domain.vision.YoloDecoder
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.detection.ModelInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import java.io.FileNotFoundException
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DetectorStatus {
    data object Loading : DetectorStatus
    data object Ready : DetectorStatus

    /** The model file is not in the APK (e.g. a local build without scripts/fetch_card_model.sh). */
    data object ModelMissing : DetectorStatus
    data class Failed(val message: String) : DetectorStatus
}

data class FrameDetections(val sightings: List<CardSighting>, val inferenceMillis: Long)

interface CardDetector {
    val model: ModelInfo
    val status: DetectorStatus

    /** Loads the model if needed (about a second; blocking: call off the main thread). Idempotent. */
    fun prepare(): DetectorStatus

    /** Detects cards in an upright image. Blocking (tens to hundreds of ms); call off the main thread. */
    fun detect(image: Bitmap, scoreThreshold: Float): FrameDetections
}

/**
 * Runs the YOLO11s playing-card model (sroot/lgd-cards-gen3, AGPL-3.0 weights) with ONNX Runtime.
 * Preprocessing matches Ultralytics: letterbox to 640x640 with gray (114) padding, RGB, 0..1, NCHW.
 * Not thread-safe for concurrent [detect] calls (buffers are reused); the camera analyzer is single-threaded.
 */
@Singleton
class OnnxCardDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) : CardDetector {

    override val model = ModelInfo(name = "lgd-cards-gen3 (YOLO11s)", version = "gen3", isMock = false)

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private var labels: List<PlayingCard> = emptyList()

    @Volatile
    override var status: DetectorStatus = DetectorStatus.Loading
        private set

    private val loadLock = Any()

    override fun prepare(): DetectorStatus = synchronized(loadLock) {
        if (status == DetectorStatus.Loading) status = loadModel()
        status
    }

    private val input = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(input)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val chw: FloatBuffer = FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)

    private fun loadModel(): DetectorStatus = try {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            try {
                // XNNPACK accelerates float convolutions on ARM; it manages its own thread pool.
                addXnnpack(mapOf("intra_op_num_threads" to THREADS.toString()))
                setIntraOpNumThreads(1)
            } catch (e: OrtException) {
                setIntraOpNumThreads(THREADS)
            }
        }
        session = env.createSession(bytes, options)
        labels = loadLabels()
        DetectorStatus.Ready
    } catch (e: FileNotFoundException) {
        DetectorStatus.ModelMissing
    } catch (e: Exception) {
        DetectorStatus.Failed(e.message ?: e.javaClass.simpleName)
    }

    private fun loadLabels(): List<PlayingCard> {
        val codes = try {
            val json = context.assets.open(LABELS_ASSET).bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            List(array.length()) { array.getString(it) }
        } catch (e: Exception) {
            CardLabels.DEFAULT_ORDER
        }
        return CardLabels.parse(codes)
    }

    override fun detect(image: Bitmap, scoreThreshold: Float): FrameDetections {
        prepare()
        val s = session ?: return FrameDetections(emptyList(), 0)
        val start = System.nanoTime()
        val letterbox = Letterbox(image.width, image.height, INPUT_SIZE)
        canvas.drawColor(Color.rgb(PAD_GRAY, PAD_GRAY, PAD_GRAY))
        canvas.drawBitmap(
            image,
            null,
            Rect(letterbox.padX, letterbox.padY, letterbox.padX + letterbox.scaledWidth, letterbox.padY + letterbox.scaledHeight),
            paint,
        )
        input.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val plane = INPUT_SIZE * INPUT_SIZE
        chw.clear()
        val arr = chw.array()
        for (i in 0 until plane) {
            val p = pixels[i]
            arr[i] = ((p shr 16) and 0xFF) / 255f
            arr[plane + i] = ((p shr 8) and 0xFF) / 255f
            arr[2 * plane + i] = (p and 0xFF) / 255f
        }
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val pips = OnnxTensor.createTensor(env, chw, shape).use { tensor ->
            s.run(mapOf(s.inputNames.first() to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                val flat = FloatArray(out.remaining()).also { out.get(it) }
                val numClasses = labels.size
                YoloDecoder.decode(flat, numClasses, flat.size / (4 + numClasses), scoreThreshold, letterbox)
            }
        }
        val sightings = PipClusterer.cluster(pips, labels)
        return FrameDetections(sightings, (System.nanoTime() - start) / 1_000_000)
    }

    companion object {
        const val MODEL_ASSET = "models/card_detector.onnx"
        const val LABELS_ASSET = "models/card_detector.classes.json"
        const val INPUT_SIZE = 640
        private const val PAD_GRAY = 114
        private const val THREADS = 4
    }
}
