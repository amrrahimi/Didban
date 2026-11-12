package com.pokervision.core.domain.media

import com.pokervision.core.common.result.AppError
import com.pokervision.core.domain.repository.MediaRepository
import com.pokervision.core.domain.repository.SettingsRepository
import com.pokervision.core.model.media.MediaType
import com.pokervision.core.model.settings.KeyframeSensitivity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/** A decoded video frame. Owned by the caller, who must call [release]. Implemented with Bitmap on Android. */
interface DecodedFrame {
    val timestampMs: Long
    val size: PixelSize
    fun signature(signatureSize: Int): LumaSignature

    /** Writes the frame as JPEG. Returns false on failure. */
    fun saveJpeg(path: String, quality: Int): Boolean
    fun release()
}

/** An opened video. Not thread-safe; use from one coroutine. */
interface VideoFrameSession : AutoCloseable {
    val durationMs: Long

    /** Decodes the frame nearest to [timestampMs], scaled to fit [maxDimension]; null if it can't be decoded. */
    suspend fun frameAt(timestampMs: Long, maxDimension: Int): DecodedFrame?
}

interface VideoFrameReader {
    /** Opens [path]; throws [java.io.IOException] if the file can't be read as video. */
    suspend fun open(path: String): VideoFrameSession
}

/** Where extracted keyframe images are kept (app cache; regenerable, cleared by the OS under pressure). */
interface KeyframeStore {
    fun pathFor(assetId: Long, timestampMs: Long): String
    fun clear(assetId: Long)
    fun clearAll()

    /** Previously extracted keyframes for [assetId], sorted by time. */
    fun list(assetId: Long): List<Keyframe>
}

data class Keyframe(val timestampMs: Long, val imagePath: String, val changeScore: Double? = null)

data class KeyframeExtractionConfig(
    val sampleIntervalMs: Long,
    val sensitivity: KeyframeSensitivity,
    val maxSamples: Int = DEFAULT_MAX_SAMPLES,
    val maxDimension: Int = DEFAULT_MAX_DIMENSION,
    val minGapMs: Long = DEFAULT_MIN_GAP_MS,
    val maxGapMs: Long = DEFAULT_MAX_GAP_MS,
    val signatureSize: Int = DEFAULT_SIGNATURE_SIZE,
    val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
) {
    companion object {
        const val DEFAULT_MAX_SAMPLES = 600
        /** Long side of saved keyframes; large enough for card detection at typical table distances. */
        const val DEFAULT_MAX_DIMENSION = 1280
        const val DEFAULT_MIN_GAP_MS = 400L
        const val DEFAULT_MAX_GAP_MS = 30_000L
        const val DEFAULT_SIGNATURE_SIZE = 32
        const val DEFAULT_JPEG_QUALITY = 90
    }
}

sealed interface KeyframeExtractionEvent {
    data class Progress(val sampled: Int, val total: Int, val keyframes: List<Keyframe>) : KeyframeExtractionEvent
    data class Completed(val keyframes: List<Keyframe>, val sampled: Int, val undecodable: Int) : KeyframeExtractionEvent
    data class Failed(val error: AppError) : KeyframeExtractionEvent
}

/**
 * Samples a video at the configured rate and keeps only frames where the scene changed.
 * Emits progress after every sampled frame; cancellable at any point (collection cancelled ->
 * session closed, partial keyframes remain in the store).
 */
class ExtractKeyframesUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val settingsRepository: SettingsRepository,
    private val reader: VideoFrameReader,
    private val store: KeyframeStore,
) {
    operator fun invoke(assetId: Long, overrideConfig: KeyframeExtractionConfig? = null): Flow<KeyframeExtractionEvent> =
        flow {
            val asset = mediaRepository.getById(assetId)
            if (asset == null || asset.type != MediaType.VIDEO) {
                emit(KeyframeExtractionEvent.Failed(AppError.NotFound("Video $assetId not found")))
                return@flow
            }
            val config = overrideConfig ?: settingsRepository.settings.first().let {
                KeyframeExtractionConfig(sampleIntervalMs = it.videoSampleIntervalMs, sensitivity = it.keyframeSensitivity)
            }
            val session = try {
                reader.open(asset.localPath)
            } catch (e: java.io.IOException) {
                emit(KeyframeExtractionEvent.Failed(AppError.Io("Cannot open video", e)))
                return@flow
            }
            session.use { s ->
                val plan = FrameSamplingPlan.timestamps(s.durationMs, config.sampleIntervalMs, config.maxSamples)
                if (plan.isEmpty()) {
                    emit(KeyframeExtractionEvent.Failed(AppError.Validation("Video has no duration")))
                    return@flow
                }
                store.clear(assetId)
                val selector = KeyframeSelector(config.sensitivity.changeThreshold, config.minGapMs, config.maxGapMs)
                val keyframes = mutableListOf<Keyframe>()
                var undecodable = 0
                plan.forEachIndexed { index, ts ->
                    currentCoroutineContext().ensureActive()
                    val frame = s.frameAt(ts, config.maxDimension)
                    if (frame == null) {
                        undecodable++
                    } else {
                        try {
                            val decision = selector.offer(frame.timestampMs, frame.signature(config.signatureSize))
                            if (decision.isKeyframe) {
                                val path = store.pathFor(assetId, frame.timestampMs)
                                if (frame.saveJpeg(path, config.jpegQuality)) {
                                    keyframes += Keyframe(frame.timestampMs, path, decision.changeScore)
                                }
                            }
                        } finally {
                            frame.release()
                        }
                    }
                    emit(KeyframeExtractionEvent.Progress(index + 1, plan.size, keyframes.toList()))
                }
                if (undecodable == plan.size) {
                    emit(KeyframeExtractionEvent.Failed(AppError.Unsupported("No frame could be decoded")))
                } else {
                    emit(KeyframeExtractionEvent.Completed(keyframes.toList(), plan.size, undecodable))
                }
            }
        }
}

class GetSavedKeyframesUseCase @Inject constructor(private val store: KeyframeStore) {
    operator fun invoke(assetId: Long): List<Keyframe> = store.list(assetId)
}
