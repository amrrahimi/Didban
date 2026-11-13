package com.pokervision.core.poker

import com.pokervision.core.model.analytics.CalculationMethod
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.poker.advice.AdviceAction
import com.pokervision.core.poker.advice.AdviceReason
import com.pokervision.core.poker.advice.BasicAdvisor
import com.pokervision.core.poker.holdem.HandCategory
import com.pokervision.core.poker.holdem.HoldemAnalyzer
import com.pokervision.core.poker.holdem.HoldemEquityCalculator
import com.pokervision.core.poker.holdem.HoldemEquityRequest
import com.pokervision.core.poker.holdem.HoldemSituation
import com.pokervision.core.poker.math.PotMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EquityAndAdviceTest {

    private fun cards(s: String) = if (s.isBlank()) emptyList() else s.split(" ").map(PlayingCard::parse)

    private fun equity(hero: String, board: String = "", opponents: Int = 1, dead: String = "", maxExact: Long = 250_000) =
        HoldemEquityCalculator.calculate(
            HoldemEquityRequest(cards(hero), cards(board), opponents, cards(dead), maxExactOutcomes = maxExact),
        )

    @Test
    fun `aces preflop heads up is about 85 percent (published value 85_2)`() {
        val r = equity("As Ah")
        assertEquals(CalculationMethod.MONTE_CARLO, r.method)
        val p = r.players.single()
        assertEquals(0.852, p.equity, 0.01)
        val ci = p.equityInterval!!
        assertTrue("CI should contain published value", 0.852 in (ci.lower - 0.005)..(ci.upper + 0.005))
    }

    @Test
    fun `seven deuce offsuit preflop heads up is about 35 percent (published 34_6)`() {
        assertEquals(0.346, equity("7c 2d").players.single().equity, 0.01)
    }

    @Test
    fun `aces lose equity against more opponents (published ~49 percent vs 5)`() {
        assertEquals(0.49, equity("As Ah", opponents = 5).players.single().equity, 0.015)
    }

    @Test
    fun `river with royal flush is exact certainty`() {
        val r = equity("As Ks", "Qs Js Ts 2c 3d")
        assertEquals(CalculationMethod.EXACT_ENUMERATION, r.method)
        assertEquals(1.0, r.players.single().win, 0.0)
    }

    @Test
    fun `board plays is an exact split`() {
        // Board is a royal flush; everyone ties.
        val r = equity("2c 3d", "As Ks Qs Js Ts")
        assertEquals(0.0, r.players.single().win, 0.0)
        assertEquals(1.0, r.players.single().tie, 0.0)
        assertEquals(0.5, r.players.single().equity, 1e-12)
    }

    @Test
    fun `turn exact enumeration agrees with simulation`() {
        val exact = equity("Ah Kh", "Qh 7h 2c 9s")
        val simulated = equity("Ah Kh", "Qh 7h 2c 9s", maxExact = 0)
        assertEquals(CalculationMethod.EXACT_ENUMERATION, exact.method)
        assertEquals(CalculationMethod.MONTE_CARLO, simulated.method)
        val ci = simulated.players.single().equityInterval!!
        val e = exact.players.single().equity
        assertTrue("exact $e should be near interval $ci", e in (ci.lower - 0.01)..(ci.upper + 0.01))
    }

    @Test
    fun `dead cards change the odds`() {
        // Hero needs a heart for the flush; four hearts are dead -> far fewer outs.
        val live = equity("Ah Kh", "Qh 7h 2c 9s").players.single().equity
        val dead = equity("Ah Kh", "Qh 7h 2c 9s", dead = "3h 4h 5h 6h").players.single().equity
        assertTrue("$dead < $live", dead < live)
    }

    @Test
    fun `same seed gives same result`() {
        assertEquals(equity("Td 9d").players.single().equity, equity("Td 9d").players.single().equity, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate cards rejected`() {
        equity("As Ah", "As Kd 2c")
    }

    @Test
    fun `pot math`() {
        assertEquals(0.25, PotMath.requiredEquity(pot = 150, toCall = 50), 1e-12)
        assertEquals(50.0, PotMath.callEv(equity = 0.5, pot = 150, toCall = 50), 1e-12)
        assertEquals(-50.0, PotMath.callEv(equity = 0.0, pot = 150, toCall = 50), 1e-12)
    }

    @Test
    fun `advisor decisions`() {
        val a = BasicAdvisor()
        assertEquals(0.7, a.strongThreshold(1), 1e-12)
        assertEquals(AdviceAction.FOLD, a.advise(0.20, 1, pot = 100, toCall = 50).action) // needs 33%
        assertEquals(AdviceAction.CALL, a.advise(0.40, 1, pot = 100, toCall = 50).action)
        assertEquals(AdviceAction.RAISE, a.advise(0.80, 1, pot = 100, toCall = 50).action)
        assertEquals(AdviceAction.CHECK, a.advise(0.40, 1, pot = 100, toCall = 0).action)
        assertEquals(AdviceAction.BET, a.advise(0.75, 1, pot = 100, toCall = null).action)
        val unknownPot = a.advise(0.30, 3, pot = null, toCall = 20)
        assertEquals(AdviceAction.CALL, unknownPot.action) // 30% > fair share 25%
        assertEquals(AdviceReason.FAIR_SHARE_ONLY, unknownPot.reason)
        assertNull(unknownPot.requiredEquity)
    }

    @Test
    fun `analyzer flop with flush draw lists the hearts`() {
        val analysis = HoldemAnalyzer().analyze(
            HoldemSituation(cards("Ah Kh"), cards("Qh 7h 2c"), opponents = 1, pot = 100, toCall = 50),
        )
        assertEquals(HandCategory.HIGH_CARD, analysis.currentHand)
        assertEquals(9, analysis.improvingCards.count { it.suit == PlayingCard.parse("2h").suit })
        assertTrue(analysis.improvingCards.size > 9) // pairing A/K also improves
        assertNotNull(analysis.advice.requiredEquity)
        assertTrue(analysis.advice.action != AdviceAction.FOLD) // nut flush draw + overcards is far above 33%
    }

    @Test
    fun `analyzer preflop has no made hand`() {
        val analysis = HoldemAnalyzer().analyze(HoldemSituation(cards("As Ad"), emptyList(), opponents = 1))
        assertNull(analysis.currentHand)
        assertTrue(analysis.improvingCards.isEmpty())
        assertEquals(AdviceAction.BET, analysis.advice.action)
    }
}
