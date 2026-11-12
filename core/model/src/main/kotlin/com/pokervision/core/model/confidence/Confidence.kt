package com.pokervision.core.model.confidence

/** Probability-like confidence score in [0, 1]. */
@JvmInline
value class Confidence(val value: Float) : Comparable<Confidence> {
    init {
        require(value in 0f..1f && !value.isNaN()) { "Confidence must be in [0,1], was $value" }
    }

    override fun compareTo(other: Confidence): Int = value.compareTo(other.value)

    companion object {
        /** Used for values the user entered or corrected manually. */
        val CERTAIN = Confidence(1f)
        val NONE = Confidence(0f)
    }
}

enum class ConfidenceLevel { HIGH, MEDIUM, LOW }

/**
 * User-configurable thresholds (see Model & Detection settings). Defaults are starting points to be
 * tuned against a real validation set once a model exists; they are not measured values.
 */
data class ConfidenceThresholds(
    /** Detections below this are discarded as noise. */
    val minimumDetection: Float = 0.50f,
    /** Detections at or above this may be auto-confirmed (still subject to temporal agreement). */
    val autoConfirm: Float = 0.90f,
) {
    init {
        require(minimumDetection in 0f..1f) { "minimumDetection out of range" }
        require(autoConfirm in 0f..1f) { "autoConfirm out of range" }
        require(minimumDetection <= autoConfirm) { "minimumDetection must be <= autoConfirm" }
    }

    fun levelOf(confidence: Confidence): ConfidenceLevel = when {
        confidence.value >= autoConfirm -> ConfidenceLevel.HIGH
        confidence.value >= minimumDetection -> ConfidenceLevel.MEDIUM
        else -> ConfidenceLevel.LOW
    }
}

/** Where a piece of game-state information came from. */
enum class ObservationSource {
    /** Produced by the computer-vision pipeline. */
    VISION,
    /** Entered or corrected by the user; treated as authoritative. */
    MANUAL,
    /** Derived by game rules (e.g. pot = sum of observed bets). */
    INFERRED,
}

/** A value annotated with its provenance and confidence. */
data class Observed<out T>(
    val value: T,
    val confidence: Confidence,
    val source: ObservationSource,
) {
    companion object {
        fun <T> manual(value: T): Observed<T> = Observed(value, Confidence.CERTAIN, ObservationSource.MANUAL)
    }
}
