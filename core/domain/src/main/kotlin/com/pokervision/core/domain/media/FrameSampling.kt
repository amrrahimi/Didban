package com.pokervision.core.domain.media

import com.pokervision.core.model.settings.KeyframeSensitivity
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Chooses which video timestamps to decode. Decoding every frame of a long video is wasteful
 * (a 10-minute 30 FPS video has 18,000 frames); instead frames are sampled at a fixed interval,
 * widened evenly if that would exceed [maxFrames].
 */
object FrameSamplingPlan {

    /**
     * @return strictly increasing timestamps in [0, durationMs), starting at 0. Empty if duration <= 0.
     */
    fun timestamps(durationMs: Long, intervalMs: Long, maxFrames: Int): List<Long> {
        require(intervalMs > 0) { "intervalMs must be > 0" }
        require(maxFrames > 0) { "maxFrames must be > 0" }
        if (durationMs <= 0) return emptyList()
        val natural = ceil(durationMs.toDouble() / intervalMs).toLong()
        val effectiveInterval = if (natural > maxFrames) {
            ceil(durationMs.toDouble() / maxFrames).toLong()
        } else {
            intervalMs
        }
        return generateSequence(0L) { it + effectiveInterval }
            .takeWhile { it < durationMs }
            .take(maxFrames)
            .toList()
    }
}

/**
 * Compact grayscale fingerprint of a frame: [size]×[size] luminance values (0..255), produced by
 * downscaling the frame. Comparing fingerprints is cheap and robust to noise and compression.
 */
class LumaSignature(val size: Int, val values: IntArray) {
    init {
        require(size > 0 && values.size == size * size) { "values must have size*size entries" }
    }

    /** Mean absolute difference per pixel, 0 (identical) .. 255 (inverted). */
    fun distanceTo(other: LumaSignature): Double {
        require(other.size == size) { "Signature sizes differ" }
        var sum = 0L
        for (i in values.indices) sum += abs(values[i] - other.values[i])
        return sum.toDouble() / values.size
    }
}

data class KeyframeDecision(val isKeyframe: Boolean, val changeScore: Double)

/**
 * Streaming keyframe selection. A frame becomes a keyframe when it differs from the last keyframe
 * by more than the threshold, subject to a minimum gap (avoids bursts during camera shake) and a
 * maximum gap (guarantees coverage of long static periods). The first frame is always kept.
 */
class KeyframeSelector(
    private val changeThreshold: Double,
    private val minGapMs: Long = 0,
    private val maxGapMs: Long? = null,
) {
    init {
        require(changeThreshold >= 0) { "changeThreshold must be >= 0" }
        require(minGapMs >= 0) { "minGapMs must be >= 0" }
        require(maxGapMs == null || maxGapMs > minGapMs) { "maxGapMs must be > minGapMs" }
    }

    private var lastSignature: LumaSignature? = null
    private var lastTimestampMs: Long = 0

    fun offer(timestampMs: Long, signature: LumaSignature): KeyframeDecision {
        val last = lastSignature
        if (last == null) {
            accept(timestampMs, signature)
            return KeyframeDecision(true, 0.0)
        }
        require(timestampMs >= lastTimestampMs) { "Frames must be offered in time order" }
        val score = signature.distanceTo(last)
        val gap = timestampMs - lastTimestampMs
        val changed = score > changeThreshold && gap >= minGapMs
        val forced = maxGapMs != null && gap >= maxGapMs
        return if (changed || forced) {
            accept(timestampMs, signature)
            KeyframeDecision(true, score)
        } else {
            KeyframeDecision(false, score)
        }
    }

    private fun accept(timestampMs: Long, signature: LumaSignature) {
        lastSignature = signature
        lastTimestampMs = timestampMs
    }
}

data class PixelSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Size must be positive: ${width}x$height" }
    }

    /** Size after rotating by [degrees] (multiples of 90). */
    fun rotated(degrees: Int): PixelSize {
        require(degrees % 90 == 0) { "Only multiples of 90 degrees are supported" }
        return if ((degrees / 90) % 2 != 0) PixelSize(height, width) else this
    }
}

object ResizeMath {
    /**
     * Largest size with the same aspect ratio that fits inside [maxWidth]×[maxHeight].
     * Never upscales. Each side is at least 1 px.
     */
    fun fitWithin(source: PixelSize, maxWidth: Int, maxHeight: Int): PixelSize {
        require(maxWidth > 0 && maxHeight > 0) { "Bounds must be positive" }
        val scale = minOf(maxWidth.toDouble() / source.width, maxHeight.toDouble() / source.height, 1.0)
        return PixelSize(
            max(1, (source.width * scale).roundToInt()),
            max(1, (source.height * scale).roundToInt()),
        )
    }

    /**
     * Power-of-two subsample factor for BitmapFactory.inSampleSize so the decoded image is still
     * at least [minLongSide] on its longer side (memory-efficient decode before an exact resize).
     */
    fun inSampleSize(source: PixelSize, minLongSide: Int): Int {
        require(minLongSide > 0) { "minLongSide must be > 0" }
        val longSide = maxOf(source.width, source.height)
        var sample = 1
        while (longSide / (sample * 2) >= minLongSide) sample *= 2
        return sample
    }

    /** Normalizes EXIF/metadata rotation values to 0, 90, 180 or 270. */
    fun normalizeRotation(degrees: Int): Int {
        val d = ((degrees % 360) + 360) % 360
        require(d % 90 == 0) { "Rotation must be a multiple of 90, was $degrees" }
        return d
    }
}
