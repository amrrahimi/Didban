package com.pokervision.core.domain.media

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Compact grayscale fingerprint of an image: [size]×[size] luminance values (0..255). Comparing
 * fingerprints is cheap; live analysis will use it to notice when the table changes.
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
