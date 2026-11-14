package com.pokervision.feature.advisor

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.game.PokerVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdvisorInputReducerTest {

    private fun c(s: String) = PlayingCard.parse(s)
    private fun tap(input: AdvisorInput, vararg cards: String) =
        cards.fold(input) { acc, code -> AdvisorInputReducer.tapCard(acc, c(code)) }

    @Test
    fun `two hole cards then target moves to board`() {
        val input = tap(AdvisorInput(), "As", "Kd")
        assertEquals(listOf(c("As"), c("Kd")), input.hero)
        assertEquals(CardTarget.BOARD, input.target)
        val withBoard = tap(input, "2c", "7h", "9s")
        assertEquals(3, withBoard.board.size)
    }

    @Test
    fun `tapping a used card removes it`() {
        val input = tap(AdvisorInput(), "As", "Kd", "As")
        assertEquals(listOf(c("Kd")), input.hero)
    }

    @Test
    fun `capacity is respected`() {
        val input = tap(AdvisorInput(), "As", "Kd", "2c", "3c", "4c", "5c", "6c", "7c")
        assertEquals(5, input.board.size)
        assertEquals(false, c("7c") in input.usedCards)
    }

    @Test
    fun `query carries the input for analysis`() {
        val hero = tap(AdvisorInput(), "As", "Kd")
        val q = AdvisorInputReducer.toQuery(hero)
        assertEquals(listOf(c("As"), c("Kd")), q.mine)
        assertEquals(PokerVariant.TEXAS_HOLDEM, q.variant)
    }

    @Test
    fun `omaha takes four hole cards before moving to the board`() {
        val omaha = AdvisorInputReducer.setVariant(AdvisorInput(), PokerVariant.OMAHA)
        val three = tap(omaha, "As", "Kd", "Qh")
        assertEquals(CardTarget.HERO, three.target)
        val four = tap(three, "Jc")
        assertEquals(4, four.hero.size)
        assertEquals(CardTarget.BOARD, four.target)
    }

    @Test
    fun `short deck rejects low cards`() {
        val sd = AdvisorInputReducer.setVariant(AdvisorInput(), PokerVariant.SHORT_DECK)
        assertEquals(emptyList<PlayingCard>(), tap(sd, "5h").hero)
        assertEquals(listOf(c("6h")), tap(sd, "6h").hero)
    }

    @Test
    fun `stud and draw have no board`() {
        val stud = AdvisorInputReducer.setVariant(AdvisorInput(), PokerVariant.SEVEN_CARD_STUD)
        assertEquals(0, stud.capacity(CardTarget.BOARD))
        assertEquals(7, stud.capacity(CardTarget.HERO))
        val draw = AdvisorInputReducer.setVariant(AdvisorInput(), PokerVariant.FIVE_CARD_DRAW)
        assertEquals(5, draw.capacity(CardTarget.HERO))
    }

    @Test
    fun `amount parsing accepts Persian and Arabic digits`() {
        assertEquals(1500L, AdvisorInputReducer.parseAmount("1,500"))
        assertEquals(250L, AdvisorInputReducer.parseAmount("۲۵۰"))
        assertEquals(40L, AdvisorInputReducer.parseAmount("٤٠"))
        assertNull(AdvisorInputReducer.parseAmount(""))
        assertNull(AdvisorInputReducer.parseAmount("abc"))
        assertNull(AdvisorInputReducer.parseAmount("-5"))
    }

    @Test
    fun `new hand keeps opponents and clears the rest`() {
        val input = AdvisorInputReducer.setOpponents(tap(AdvisorInput(), "As", "Kd"), 4).copy(pot = 100)
        val next = AdvisorInputReducer.newHand(input)
        assertEquals(4, next.opponents)
        assertEquals(emptyList<PlayingCard>(), next.hero)
        assertNull(next.pot)
        assertEquals(input.handNumber + 1, next.handNumber)
    }

    @Test
    fun `opponents are clamped`() {
        assertEquals(AdvisorInput().maxOpponents, AdvisorInputReducer.setOpponents(AdvisorInput(), 20).opponents)
        assertEquals(MIN_OPPONENTS, AdvisorInputReducer.setOpponents(AdvisorInput(), 0).opponents)
    }
}
