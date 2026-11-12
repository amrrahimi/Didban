package com.pokervision.core.model.settings

import com.pokervision.core.model.confidence.ConfidenceThresholds
import com.pokervision.core.model.game.PokerVariant

data class AppSettings(
    val defaultVariant: PokerVariant = PokerVariant.TEXAS_HOLDEM,
    /** Frames per second fed to the analyzer during live camera use. */
    val analysisFps: Int = DEFAULT_ANALYSIS_FPS,
    val confidenceThresholds: ConfidenceThresholds = ConfidenceThresholds(),
) {
    init {
        require(analysisFps in ANALYSIS_FPS_RANGE) { "analysisFps must be in $ANALYSIS_FPS_RANGE" }
    }

    companion object {
        const val DEFAULT_ANALYSIS_FPS = 5
        val ANALYSIS_FPS_RANGE = 1..15
    }
}
