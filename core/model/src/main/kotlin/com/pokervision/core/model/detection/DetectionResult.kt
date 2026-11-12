package com.pokervision.core.model.detection

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.confidence.Confidence

/** Axis-aligned box in normalized image coordinates (0..1), origin top-left. */
data class BoundingBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left in 0f..1f && right in 0f..1f && top in 0f..1f && bottom in 0f..1f) {
            "BoundingBox must be normalized to [0,1]: $this"
        }
        require(left <= right && top <= bottom) { "BoundingBox has negative size: $this" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height

    /** Intersection-over-union, used for non-maximum suppression and frame-to-frame tracking. */
    fun iou(other: BoundingBox): Float {
        val ix = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
        val iy = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        val inter = ix * iy
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}

/** What a detection claims to be. */
sealed interface DetectionLabel {
    data class Card(val card: PlayingCard) : DetectionLabel
    data object CardBack : DetectionLabel
    data object Chip : DetectionLabel
    data object ChipStack : DetectionLabel
    data object Table : DetectionLabel
    data object PlayerSeat : DetectionLabel
    data object DealerButton : DetectionLabel
}

data class Detection(
    val label: DetectionLabel,
    val box: BoundingBox,
    val confidence: Confidence,
    /** Stable id assigned by the tracker across frames; null for single-image analysis. */
    val trackId: Long? = null,
)

data class ModelInfo(
    val name: String,
    val version: String,
    /**
     * True for the placeholder detector used until a trained model ships. The UI must label
     * results from a mock model as such and they must never feed confirmed game state.
     */
    val isMock: Boolean,
)

/**
 * Raw output of one inference pass. Deliberately separate from [com.pokervision.core.model.game.GameState]:
 * detections are evidence, not facts.
 */
data class DetectionResult(
    /** Identifies the frame: media asset id + frame offset, or a live-camera frame counter. */
    val frameId: String,
    val timestampMillis: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val detections: List<Detection>,
    val model: ModelInfo,
    val inferenceTimeMillis: Long,
) {
    init {
        require(imageWidth > 0 && imageHeight > 0) { "Image size must be positive" }
        require(inferenceTimeMillis >= 0) { "inferenceTimeMillis must be >= 0" }
    }

    fun cardDetections(): List<Detection> = detections.filter { it.label is DetectionLabel.Card }
}
