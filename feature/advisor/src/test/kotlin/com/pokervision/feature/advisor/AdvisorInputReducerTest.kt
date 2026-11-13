package com.pokervision.feature.advisor

import com.pokervision.core.model.card.PlayingCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `missing input states`() {
        assertEquals(MissingInput.HERO_CARDS, AdvisorInputReducer.missing(AdvisorInput()))
        val hero = tap(AdvisorInput(), "As", "Kd")
        assertNull(AdvisorInputReducer.missing(hero))
        assertEquals(MissingInput.BOARD_INCOMPLETE, AdvisorInputReducer.missing(tap(hero, "2c")))
        assertNotNull(AdvisorInputReducer.toSituation(hero))
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
        assertEquals(MAX_OPPONENTS, AdvisorInputReducer.setOpponents(AdvisorInput(), 20).opponents)
        assertEquals(MIN_OPPONENTS, AdvisorInputReducer.setOpponents(AdvisorInput(), 0).opponents)
    }
}
