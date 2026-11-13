package com.pokervision.core.data.settings

import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.model.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDecoderTest {

    @Test
    fun `all null gives defaults`() {
        assertEquals(AppSettings(), SettingsDecoder.decode(null, null, null, null))
    }

    @Test
    fun `valid values are kept`() {
        val s = SettingsDecoder.decode("OMAHA", 8, 0.4f, 0.95f)
        assertEquals(PokerVariant.OMAHA, s.defaultVariant)
        assertEquals(8, s.analysisFps)
        assertEquals(0.4f, s.confidenceThresholds.minimumDetection)
        assertEquals(0.95f, s.confidenceThresholds.autoConfirm)
    }

    @Test
    fun `corrupt values fall back individually`() {
        val s = SettingsDecoder.decode("NOT_A_VARIANT", 999, 0.9f, 0.2f)
        assertEquals(AppSettings().defaultVariant, s.defaultVariant)
        assertEquals(AppSettings.DEFAULT_ANALYSIS_FPS, s.analysisFps)
        assertEquals(AppSettings().confidenceThresholds, s.confidenceThresholds) // min > auto is invalid
    }
}
