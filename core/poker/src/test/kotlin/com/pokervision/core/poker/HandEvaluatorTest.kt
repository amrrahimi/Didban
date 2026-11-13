package com.pokervision.core.poker

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.poker.holdem.HandCategory
import com.pokervision.core.poker.holdem.HandCategory.*
import com.pokervision.core.poker.holdem.HandEvaluator
import com.pokervision.core.poker.holdem.HandValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class HandEvaluatorTest {

    private fun hand(s: String): HandValue = HandEvaluator.evaluate(s.split(" ").map(PlayingCard::parse))
    private fun cat(s: String): HandCategory = hand(s).category

    @Test
    fun `categories`() {
        assertEquals(STRAIGHT_FLUSH, cat("As Ks Qs Js Ts"))
        assertEquals(STRAIGHT_FLUSH, cat("5d 4d 3d 2d Ad"))
        assertEquals(FOUR_OF_A_KIND, cat("9c 9d 9h 9s 2c"))
        assertEquals(FULL_HOUSE, cat("Kc Kd Kh 2s 2c"))
        assertEquals(FLUSH, cat("Ah Th 7h 4h 2h"))
        assertEquals(STRAIGHT, cat("Ts 9h 8d 7c 6s"))
        assertEquals(STRAIGHT, cat("Ac 2d 3h 4s 5c"))
        assertEquals(THREE_OF_A_KIND, cat("7c 7d 7h Ks 2c"))
        assertEquals(TWO_PAIR, cat("Jc Jd 4h 4s Ac"))
        assertEquals(ONE_PAIR, cat("Qc Qd 8h 5s 3c"))
        assertEquals(HIGH_CARD, cat("Ac Jd 8h 5s 3c"))
    }

    @Test
    fun `ordering and kickers`() {
        assertTrue(hand("As Ks Qs Js Ts") > hand("Ks Qs Js Ts 9s"))
        assertTrue(hand("6c 5d 4h 3s 2c") > hand("5d 4d 3c 2h Ac")) // wheel is the lowest straight
        assertTrue(hand("Ac Ad Kh 5s 3c") > hand("Ac Ad Qh Js Tc")) // kicker
        assertTrue(hand("Kc Kd 2h 2s Ac") > hand("Qc Qd Jh Js Ac")) // top pair of two pair
        assertTrue(hand("2c 2d 2h As Ac") < hand("3c 3d 3h 2s 2c")) // full house by trips
        assertEquals(hand("Ac Kd 8h 5s 3c"), hand("Ad Kh 8s 5c 3d")) // exact tie across suits
        assertTrue(hand("Ah Th 7h 4h 3h") > hand("Ah Th 7h 4h 2h"))
    }

    @Test
    fun `seven card best five`() {
        // Board pairs don't beat a flush in hand
        assertEquals(FLUSH, cat("Ah 9h 2h 5h Kh Kd Kc"))
        // Two trips -> full house using the higher trips
        assertEquals(hand("Kc Kd Kh 2s 2c"), hand("Kc Kd Kh 2s 2c 2d 3h"))
        // Three pairs -> best two pairs plus best kicker (the third pair's rank can be the kicker)
        assertEquals(hand("Ac Ad Kh Ks Qc"), hand("Ac Ad Kh Ks Qc Qd 2h"))
        // Straight flush beats quads present in the same 7 cards
        assertEquals(STRAIGHT_FLUSH, cat("9s 8s 7s 6s 5s 5c 5d"))
        // Six-card straight uses the top five
        assertEquals(hand("Ts 9h 8d 7c 6s"), hand("Ts 9h 8d 7c 6s 5h 2c"))
    }

    @Test
    fun `exhaustive five card category counts`() {
        val counts = LongArray(HandCategory.entries.size)
        val cards = IntArray(5)
        for (a in 0 until 52) for (b in a + 1 until 52) for (c in b + 1 until 52) for (d in c + 1 until 52) for (e in d + 1 until 52) {
            cards[0] = a; cards[1] = b; cards[2] = c; cards[3] = d; cards[4] = e
            counts[HandEvaluator.evaluateIndices(cards, 5) ushr 20]++
        }
        // Standard combinatorics of 2,598,960 five-card hands.
        val expected = longArrayOf(1_302_540, 1_098_240, 123_552, 54_912, 10_200, 5_108, 3_744, 624, 40)
        assertEquals(expected.toList(), counts.toList())
    }

    @Test
    fun `seven card evaluation equals best of 21 five card subsets`() {
        val rnd = Random(42)
        val deck = (0 until 52).toMutableList()
        repeat(20_000) {
            deck.shuffle(rnd)
            val seven = IntArray(7) { deck[it] }
            var best = Int.MIN_VALUE
            val five = IntArray(5)
            for (skip1 in 0 until 7) for (skip2 in skip1 + 1 until 7) {
                var k = 0
                for (i in 0 until 7) if (i != skip1 && i != skip2) five[k++] = seven[i]
                best = maxOf(best, HandEvaluator.evaluateIndices(five, 5))
            }
            assertEquals("cards=${seven.toList()}", best, HandEvaluator.evaluateIndices(seven, 7))
        }
    }
}
