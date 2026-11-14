package com.pokervision.core.domain.usecase

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.poker.advice.AdviceAction
import com.pokervision.core.poker.advice.LiveAction
import com.pokervision.core.poker.engine.EngineIssue
import com.pokervision.core.poker.holdem.HandCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzeHandTest {
    private fun cards(s: String) = if (s.isBlank()) emptyList() else s.split(" ").map(PlayingCard::parse)
    private val analyze = AnalyzeHandUseCase()

    @Test
    fun `not analysable until inputs are complete`() {
        val q = HandQuery(PokerVariant.TEXAS_HOLDEM, cards("As"), opponents = 1)
        assertNull(analyze(q))
        assertEquals(EngineIssue.HERO_CARD_COUNT, analyze.issue(q))
        assertEquals(EngineIssue.BOARD_SIZE, analyze.issue(q.copy(mine = cards("As Kd"), board = cards("2c"))))
    }

    @Test
    fun `holdem aces are a bet and flop hand is named`() {
        val a = analyze(HandQuery(PokerVariant.TEXAS_HOLDEM, cards("As Ah"), opponents = 1))!!
        assertEquals(0.852, a.heroEquity, 0.015)
        assertEquals(LiveAction.BET_OR_RAISE, a.liveAdvice.action)
        val flop = analyze(HandQuery(PokerVariant.TEXAS_HOLDEM, cards("As Ah"), cards("Ad 7c 2h"), opponents = 3))!!
        assertEquals(HandCategory.THREE_OF_A_KIND, flop.currentHand)
        assertTrue(flop.improvingCards.isNotEmpty())
    }

    @Test
    fun `pot odds advice when chips are known`() {
        val a = analyze(HandQuery(PokerVariant.TEXAS_HOLDEM, cards("7c 2d"), cards("As Kh Qd"), opponents = 1, pot = 100, toCall = 100))!!
        assertEquals(AdviceAction.FOLD, a.potAdvice.action)
    }

    @Test
    fun `every variant analyses`() {
        val queries = listOf(
            HandQuery(PokerVariant.OMAHA, cards("As Ah Ks Kh"), opponents = 2),
            HandQuery(PokerVariant.OMAHA_HI_LO, cards("As 2h Ks 3h"), cards("4c 5d Qh"), opponents = 2),
            HandQuery(PokerVariant.SHORT_DECK, cards("As Ah"), opponents = 2),
            HandQuery(PokerVariant.SEVEN_CARD_STUD, cards("As Ad 7c"), seen = cards("Kc 9h"), opponents = 2),
            HandQuery(PokerVariant.FIVE_CARD_DRAW, cards("As Ad 7c 4h 2s"), opponents = 2),
        )
        queries.forEach { q ->
            val a = analyze(q)
            assertNotNull("${q.variant} should analyse", a)
            assertTrue("${q.variant} equity in range", a!!.heroEquity in 0.0..1.0)
        }
    }

    @Test
    fun `draw gives keep advice before the draw only`() {
        val before = analyze(HandQuery(PokerVariant.FIVE_CARD_DRAW, cards("As Ad 7c 4h 2s"), opponents = 1))!!
        assertEquals(cards("As Ad").toSet(), before.draw!!.keep.toSet())
        val after = analyze(HandQuery(PokerVariant.FIVE_CARD_DRAW, cards("As Ad 7c 4h 2s"), opponents = 1, drawDone = true))!!
        assertNull(after.draw)
        assertEquals(HandCategory.ONE_PAIR, after.currentHand)
    }
}
