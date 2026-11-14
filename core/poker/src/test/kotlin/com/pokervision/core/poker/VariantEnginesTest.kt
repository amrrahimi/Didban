package com.pokervision.core.poker

import com.pokervision.core.model.analytics.CalculationMethod
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.poker.engine.EngineIssue
import com.pokervision.core.poker.engine.EngineSituation
import com.pokervision.core.poker.engine.OmahaMath
import com.pokervision.core.poker.engine.OpponentDraw
import com.pokervision.core.poker.engine.PokerEngines
import com.pokervision.core.poker.holdem.HandCategory
import com.pokervision.core.poker.holdem.HandEvaluator
import com.pokervision.core.poker.holdem.HandRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantEnginesTest {

    private fun cards(s: String) = if (s.isBlank()) emptyList() else s.split(" ").map(PlayingCard::parse)
    private fun sit(hero: String, board: String = "", opponents: Int = 1, dead: String = "") =
        EngineSituation(cards(hero), cards(board), opponents, cards(dead))

    // ---------------- Short Deck

    @Test
    fun `short deck flush beats full house`() {
        val sd = HandRules.shortDeck()
        val flush = HandEvaluator.evaluate(cards("Ah Th 8h 7h 6h"), sd)
        val boat = HandEvaluator.evaluate(cards("Kc Kd Kh 9s 9c"), sd)
        assertTrue(flush > boat)
        // Standard rules: the opposite
        assertTrue(HandEvaluator.evaluate(cards("Ah Th 8h 7h 6h")) < HandEvaluator.evaluate(cards("Kc Kd Kh 9s 9c")))
    }

    @Test
    fun `short deck A-6-7-8-9 is the lowest straight`() {
        val sd = HandRules.shortDeck()
        val wheel = HandEvaluator.evaluate(cards("Ac 6d 7h 8s 9c"), sd)
        assertEquals(HandCategory.STRAIGHT, wheel.category)
        assertTrue(wheel < HandEvaluator.evaluate(cards("6c 7d 8h 9s Tc"), sd))
        // Not a straight under standard rules
        assertEquals(HandCategory.HIGH_CARD, HandEvaluator.evaluate(cards("Ac 6d 7h 8s 9c")).category)
    }

    @Test
    fun `short deck trips versus straight is configurable`() {
        val trips = cards("Qc Qd Qh 7s 6c")
        val straight = cards("6c 7d 8h 9s Tc")
        assertTrue(HandEvaluator.evaluate(trips, HandRules.shortDeck(true)) > HandEvaluator.evaluate(straight, HandRules.shortDeck(true)))
        assertTrue(HandEvaluator.evaluate(trips, HandRules.shortDeck(false)) < HandEvaluator.evaluate(straight, HandRules.shortDeck(false)))
    }

    @Test
    fun `short deck rejects low cards and simulates`() {
        val engine = PokerEngines.forVariant(PokerVariant.SHORT_DECK)
        assertEquals(EngineIssue.CARD_NOT_IN_DECK, engine.validate(sit("As 5d")))
        val r = engine.equity(sit("As Ah"))
        assertEquals(CalculationMethod.MONTE_CARLO, r.method)
        assertTrue(r.players.first().equity in 0.7..0.9)
    }

    // ---------------- Omaha

    @Test
    fun `omaha must use exactly two hole cards`() {
        val omaha = PokerEngines.forVariant(PokerVariant.OMAHA)
        // Four hearts on board + one heart in hand is NOT a flush in Omaha (needs two hole hearts).
        val s = sit("Ah Kc Qd Js", "2h 5h 8h 9h Tc")
        assertNotEquals(HandCategory.FLUSH, omaha.currentHand(s))
        // Hold'em with the same cards would be a flush.
        assertEquals(HandCategory.FLUSH, HandEvaluator.evaluate(cards("Ah Kc 2h 5h 8h 9h Tc")).category)
        // Board quads cannot be "four of a kind" for every player: only 3 board cards may be used.
        val quadsBoard = sit("2c 3d 4h 7s", "Kc Kd Kh Ks 9c")
        assertEquals(HandCategory.THREE_OF_A_KIND, omaha.currentHand(quadsBoard))
    }

    @Test
    fun `omaha needs four hole cards`() {
        assertEquals(EngineIssue.HERO_CARD_COUNT, PokerEngines.forVariant(PokerVariant.OMAHA).validate(sit("As Ah")))
    }

    @Test
    fun `omaha equity is sane`() {
        val e = PokerEngines.forVariant(PokerVariant.OMAHA).equity(sit("As Ah Ks Kh")).players.first().equity
        assertTrue("AAKK double-suited vs random: $e", e in 0.6..0.85)
    }

    // ---------------- Omaha Hi-Lo

    private fun idx(s: String) = cards(s).map { it.index }.toIntArray()

    @Test
    fun `low hand rules`() {
        val wheel = OmahaMath.bestLow(idx("Ac 2d Kh Ks"), idx("3c 4d 5h Qc Jd"), 5)
        assertEquals(0x54321, wheel)
        val eightLow = OmahaMath.bestLow(idx("Ac 8d Kh Ks"), idx("3c 4d 5h Qc Jd"), 5)
        assertTrue(eightLow > wheel)
        // Only two low cards on board: no low possible
        assertEquals(Int.MAX_VALUE, OmahaMath.bestLow(idx("Ac 2d 3h 4s"), idx("5c 6d Kh Qc Jd"), 5))
        // Paired low cards don't count twice
        assertEquals(Int.MAX_VALUE, OmahaMath.bestLow(idx("Ac Ad Kh Ks"), idx("2c 2d 3h Qc Jd"), 5))
    }

    @Test
    fun `hi-lo scoop hand is worth more than a high-only hand`() {
        val hiLo = PokerEngines.forVariant(PokerVariant.OMAHA_HI_LO)
        // A-2 with 3-4-5 on board: the nut low (wheel) and a straight for high.
        val scoop = hiLo.equity(sit("Ac 2c Kc Qd", "3c 4c 5d Th Js")).players.first().equity
        assertTrue("strong two-way hand: $scoop", scoop > 0.6)
        // Kings with no low cards: can win at most the high half when a low is made.
        val highOnly = hiLo.equity(sit("Kh Kd Qh Qd", "3c 4c 5d Th Js")).players.first().equity
        assertTrue(highOnly < scoop)
    }

    // ---------------- Stud

    @Test
    fun `stud with a made royal flush wins`() {
        val stud = PokerEngines.forVariant(PokerVariant.SEVEN_CARD_STUD)
        val e = stud.equity(sit("As Ks Qs Js Ts 2c 3d", opponents = 3)).players.first()
        assertTrue("royal flush only ties another royal: ${e.equity}", e.equity > 0.999)
        assertEquals(HandCategory.STRAIGHT_FLUSH, stud.currentHand(sit("As Ks Qs Js Ts 2c 3d")))
    }

    @Test
    fun `stud on third street simulates the rest and limits opponents`() {
        val stud = PokerEngines.forVariant(PokerVariant.SEVEN_CARD_STUD)
        assertNull(stud.validate(sit("As Ad 7c", opponents = 3, dead = "Kc 9h 2d")))
        assertNull(stud.currentHand(sit("As Ad 7c")))
        assertEquals(EngineIssue.TOO_MANY_OPPONENTS, stud.validate(sit("As Ad 7c", opponents = 8)))
        val e = stud.equity(sit("As Ad 7c", opponents = 3)).players.first().equity
        assertTrue("rolled-up-ish aces 4-handed: $e", e in 0.3..0.7)
    }

    // ---------------- Draw

    @Test
    fun `draw keeps the pair of aces`() {
        val advice = PokerEngines.bestDraw(cards("As Ad 7c 4h 2s"), opponents = 1)
        assertEquals(cards("As Ad").toSet(), advice.keep.toSet())
        assertEquals(3, advice.discard.size)
        assertTrue(advice.equity >= advice.standPatEquity)
    }

    @Test
    fun `opponent draw rule`() {
        fun keep(h: String) = OpponentDraw.keepMask(idx(h))
        assertEquals(0b11111, keep("9h 8d 7c 6s 5h")) // straight: stand pat
        assertEquals(0b00011, keep("Kh Kd 7c 4s 2h")) // pair: keep it
        assertEquals(0b01111, keep("Ah 9h 6h 3h Kc")) // four to a flush
        assertEquals(0b10000, keep("2c 7d 9h Js Ah")) // nothing: keep the ace
    }

    @Test
    fun `draw stands pat with a straight flush`() {
        val advice = PokerEngines.bestDraw(cards("9h 8h 7h 6h 5h"), opponents = 2)
        assertEquals(5, advice.keep.size)
        assertTrue(advice.discard.isEmpty())
    }

    @Test
    fun `draw needs five cards for analysis`() {
        val draw = PokerEngines.forVariant(PokerVariant.FIVE_CARD_DRAW)
        assertEquals(EngineIssue.HERO_CARD_COUNT, draw.validate(sit("As Ad")))
        assertEquals(HandCategory.ONE_PAIR, draw.currentHand(sit("As Ad 7c 4h 2s")))
    }

    @Test
    fun `holdem engine still uses exact enumeration on the river`() {
        val r = PokerEngines.forVariant(PokerVariant.TEXAS_HOLDEM).equity(sit("As Ks", "Qs Js Ts 2c 3d"))
        assertEquals(CalculationMethod.EXACT_ENUMERATION, r.method)
    }
}
