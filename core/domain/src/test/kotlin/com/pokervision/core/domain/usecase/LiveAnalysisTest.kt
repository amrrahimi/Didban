package com.pokervision.core.domain.usecase

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.poker.advice.LiveAction
import com.pokervision.core.poker.holdem.HandCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveAnalysisTest {
    private fun cards(s: String) = if (s.isBlank()) emptyList() else s.split(" ").map(PlayingCard::parse)
    private val analyze = AnalyzeLiveHandUseCase()

    @Test
    fun `not analysable until hero has two cards and board is complete`() {
        assertNull(analyze(cards("As"), emptyList(), 1))
        assertNull(analyze(cards("As Kd"), cards("2c"), 1))
        assertNotNull(analyze(cards("As Kd"), emptyList(), 1))
    }

    @Test
    fun `aces preflop heads up are a bet`() {
        val a = analyze(cards("As Ah"), emptyList(), 1)!!
        assertEquals(0.852, a.equity, 0.015)
        assertEquals(LiveAction.BET_OR_RAISE, a.advice.action)
        assertNull(a.currentHand)
    }

    @Test
    fun `made hand is reported on the flop`() {
        val a = analyze(cards("As Ah"), cards("Ad 7c 2h"), 3)!!
        assertEquals(HandCategory.THREE_OF_A_KIND, a.currentHand)
    }
}
