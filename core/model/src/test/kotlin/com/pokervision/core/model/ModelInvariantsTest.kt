package com.pokervision.core.model

import com.pokervision.core.model.analytics.CalculationMethod
import com.pokervision.core.model.analytics.ConfidenceInterval
import com.pokervision.core.model.analytics.EquityResult
import com.pokervision.core.model.analytics.PlayerEquity
import com.pokervision.core.model.analytics.SimulationResult
import com.pokervision.core.model.confidence.Confidence
import com.pokervision.core.model.confidence.ConfidenceLevel
import com.pokervision.core.model.confidence.ConfidenceThresholds
import com.pokervision.core.model.detection.BoundingBox
import com.pokervision.core.model.game.ChipAmount
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.model.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInvariantsTest {

    @Test(expected = IllegalArgumentException::class)
    fun `confidence above one rejected`() {
        Confidence(1.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `confidence NaN rejected`() {
        Confidence(Float.NaN)
    }

    @Test
    fun `confidence levels follow thresholds`() {
        val t = ConfidenceThresholds(minimumDetection = 0.5f, autoConfirm = 0.9f)
        assertEquals(ConfidenceLevel.HIGH, t.levelOf(Confidence(0.95f)))
        assertEquals(ConfidenceLevel.HIGH, t.levelOf(Confidence(0.9f)))
        assertEquals(ConfidenceLevel.MEDIUM, t.levelOf(Confidence(0.7f)))
        assertEquals(ConfidenceLevel.LOW, t.levelOf(Confidence(0.2f)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `thresholds must be ordered`() {
        ConfidenceThresholds(minimumDetection = 0.9f, autoConfirm = 0.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative chips rejected`() {
        ChipAmount(-1)
    }

    @Test
    fun `chip addition`() {
        assertEquals(ChipAmount(150), ChipAmount(100) + ChipAmount(50))
    }

    @Test
    fun `bounding box iou`() {
        val a = BoundingBox(0f, 0f, 0.5f, 0.5f)
        assertEquals(1f, a.iou(a), 1e-6f)
        assertEquals(0f, a.iou(BoundingBox(0.5f, 0.5f, 1f, 1f)), 1e-6f)
        val b = BoundingBox(0.25f, 0f, 0.75f, 0.5f) // half overlap
        assertEquals(1f / 3f, a.iou(b), 1e-5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bounding box must be normalized`() {
        BoundingBox(0f, 0f, 1.5f, 1f)
    }

    @Test
    fun `wilson interval contains point estimate and narrows with n`() {
        val small = ConfidenceInterval.wilson(50.0, 100)
        val large = ConfidenceInterval.wilson(5_000.0, 10_000)
        assertTrue(0.5 in small.lower..small.upper)
        assertTrue(large.halfWidth < small.halfWidth)
        // Known value: p=0.5, n=100, 95% -> approx [0.4038, 0.5962]
        assertEquals(0.4038, small.lower, 1e-3)
        assertEquals(0.5962, small.upper, 1e-3)
    }

    @Test
    fun `wilson interval stays in bounds at extremes`() {
        val zero = ConfidenceInterval.wilson(0.0, 1000)
        assertEquals(0.0, zero.lower, 1e-12)
        assertTrue(zero.upper > 0.0 && zero.upper < 0.01)
        val one = ConfidenceInterval.wilson(1000.0, 1000)
        assertEquals(1.0, one.upper, 1e-12)
    }

    @Test
    fun `monte carlo result must carry simulation details`() {
        val eq = listOf(PlayerEquity("p1", 0.5, 0.0, 0.5))
        val mc = EquityResult(eq, CalculationMethod.MONTE_CARLO, SimulationResult(10_000, 42, 10, 0.95))
        assertTrue(mc.isEstimate)
        assertFalse(EquityResult(eq, CalculationMethod.EXACT_ENUMERATION).isEstimate)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `exact result must not carry simulation`() {
        EquityResult(emptyList(), CalculationMethod.EXACT_ENUMERATION, SimulationResult(1, 1, 1, 0.95))
    }

    @Test
    fun `variant structure`() {
        assertEquals(2, PokerVariant.TEXAS_HOLDEM.cardsPerPlayer)
        assertEquals(4, PokerVariant.OMAHA.cardsPerPlayer)
        assertEquals(36, PokerVariant.SHORT_DECK.deckType.size)
        assertFalse(PokerVariant.SEVEN_CARD_STUD.usesCommunityCards)
        assertFalse(PokerVariant.FIVE_CARD_DRAW.usesCommunityCards)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `analysis fps bounded`() {
        AppSettings(analysisFps = 60)
    }
}
