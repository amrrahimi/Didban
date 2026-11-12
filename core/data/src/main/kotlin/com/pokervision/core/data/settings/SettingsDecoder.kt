package com.pokervision.core.data.settings

import com.pokervision.core.model.confidence.ConfidenceThresholds
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.model.settings.AppSettings

/**
 * Converts raw stored values into a valid [AppSettings]. Any missing, unknown or out-of-range value
 * falls back to its default so a corrupted preference file can never crash the app.
 */
object SettingsDecoder {
    fun decode(
        variantName: String?,
        analysisFps: Int?,
        minimumDetection: Float?,
        autoConfirm: Float?,
    ): AppSettings {
        val defaults = AppSettings()
        val variant = PokerVariant.entries.firstOrNull { it.name == variantName } ?: defaults.defaultVariant
        val fps = analysisFps?.takeIf { it in AppSettings.ANALYSIS_FPS_RANGE } ?: defaults.analysisFps
        val thresholds = runCatching {
            ConfidenceThresholds(
                minimumDetection = minimumDetection ?: defaults.confidenceThresholds.minimumDetection,
                autoConfirm = autoConfirm ?: defaults.confidenceThresholds.autoConfirm,
            )
        }.getOrDefault(defaults.confidenceThresholds)
        return AppSettings(
            defaultVariant = variant,
            analysisFps = fps,
            confidenceThresholds = thresholds,
        )
    }
}
