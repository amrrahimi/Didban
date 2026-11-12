package com.pokervision.core.model.settings

import com.pokervision.core.model.confidence.ConfidenceThresholds
import com.pokervision.core.model.game.PokerVariant

/** How much visual change is required before a video frame counts as a new keyframe. */
enum class KeyframeSensitivity(
    /** Mean absolute luminance difference (0..255) on a 32x32 fingerprint. */
    val changeThreshold: Double,
) {
    /** Only large changes (new deal, cards moved a lot). */
    LOW(20.0),
    MEDIUM(12.0),

    /** Small changes (a single card turned) also produce keyframes. */
    HIGH(6.0),
}

data class AppSettings(
    val defaultVariant: PokerVariant = PokerVariant.TEXAS_HOLDEM,
    /** Frames per second fed to the analyzer during live camera use. */
    val analysisFps: Int = DEFAULT_ANALYSIS_FPS,
    val confidenceThresholds: ConfidenceThresholds = ConfidenceThresholds(),
    /** Time between sampled frames when analysing a video. */
    val videoSampleIntervalMs: Long = DEFAULT_VIDEO_SAMPLE_INTERVAL_MS,
    val keyframeSensitivity: KeyframeSensitivity = KeyframeSensitivity.MEDIUM,
) {
    init {
        require(analysisFps in ANALYSIS_FPS_RANGE) { "analysisFps must be in $ANALYSIS_FPS_RANGE" }
        require(videoSampleIntervalMs in VIDEO_SAMPLE_INTERVALS_MS) {
            "videoSampleIntervalMs must be one of $VIDEO_SAMPLE_INTERVALS_MS"
        }
    }

    companion object {
        const val DEFAULT_ANALYSIS_FPS = 5
        val ANALYSIS_FPS_RANGE = 1..15
        const val DEFAULT_VIDEO_SAMPLE_INTERVAL_MS = 1_000L
        val VIDEO_SAMPLE_INTERVALS_MS = listOf(200L, 500L, 1_000L, 2_000L)
    }
}
