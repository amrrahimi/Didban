package com.pokervision.core.poker.holdem

import com.pokervision.core.model.card.PlayingCard

/** Poker hand categories, weakest first in standard poker. */
enum class HandCategory {
    HIGH_CARD, ONE_PAIR, TWO_PAIR, THREE_OF_A_KIND, STRAIGHT, FLUSH, FULL_HOUSE, FOUR_OF_A_KIND, STRAIGHT_FLUSH
}

/**
 * Ranking rules. Standard poker uses [STANDARD]. Short Deck (36 cards, 6 to A) differs in two ways:
 * a flush beats a full house, and the lowest straight is A-6-7-8-9. Whether three of a kind beats a
 * straight differs between rule sets, so it is configurable.
 */
class HandRules private constructor(
    /** Strength position of each [HandCategory] (index = category ordinal). */
    internal val strength: IntArray,
    /** Rank-bit mask of the lowest straight that uses the ace as its lowest card. */
    internal val lowStraightMask: Int,
    /** Top-card rank index assigned to that lowest straight. */
    internal val lowStraightHigh: Int,
    val name: String,
) {
    companion object {
        private fun order(vararg categories: HandCategory): IntArray {
            val strength = IntArray(HandCategory.entries.size)
            categories.forEachIndexed { pos, c -> strength[c.ordinal] = pos }
            return strength
        }

        private const val ACE = 12

        val STANDARD = HandRules(
            strength = order(*HandCategory.entries.toTypedArray()),
            lowStraightMask = (1 shl ACE) or 0xF, // A,2,3,4,5
            lowStraightHigh = 3, // the five
            name = "standard",
        )

        /** Short Deck: flush > full house; A-6-7-8-9 straight; trips vs straight per [tripsBeatStraight]. */
        fun shortDeck(tripsBeatStraight: Boolean = true) = HandRules(
            strength = if (tripsBeatStraight) {
                order(
                    HandCategory.HIGH_CARD, HandCategory.ONE_PAIR, HandCategory.TWO_PAIR, HandCategory.STRAIGHT,
                    HandCategory.THREE_OF_A_KIND, HandCategory.FULL_HOUSE, HandCategory.FLUSH,
                    HandCategory.FOUR_OF_A_KIND, HandCategory.STRAIGHT_FLUSH,
                )
            } else {
                order(
                    HandCategory.HIGH_CARD, HandCategory.ONE_PAIR, HandCategory.TWO_PAIR, HandCategory.THREE_OF_A_KIND,
                    HandCategory.STRAIGHT, HandCategory.FULL_HOUSE, HandCategory.FLUSH,
                    HandCategory.FOUR_OF_A_KIND, HandCategory.STRAIGHT_FLUSH,
                )
            },
            lowStraightMask = (1 shl ACE) or (0xF shl 4), // A,6,7,8,9
            lowStraightHigh = 7, // the nine
            name = if (tripsBeatStraight) "short-deck (trips > straight)" else "short-deck (straight > trips)",
        )
    }
}

/**
 * Comparable strength of the best five-card hand.
 *
 * Encoding: `strength << 24 | category << 20 | k1 << 16 | … | k5`, where strength is the category's
 * position under the [HandRules] used and k1..k5 are rank indices (0 = deuce … 12 = ace) by
 * significance. A larger value is always a stronger hand; equal values are exact ties.
 */
@JvmInline
value class HandValue(val encoded: Int) : Comparable<HandValue> {
    val category: HandCategory get() = HandCategory.entries[(encoded ushr 20) and 0xF]
    override fun compareTo(other: HandValue): Int = encoded.compareTo(other.encoded)
}

/**
 * Deterministic evaluator for 5, 6 or 7 cards (selects the best five). Works on card indices
 * (see [PlayingCard.index]: rank = index / 4, suit = index % 4). Every category the cards can
 * make is scored and the strongest under the given [HandRules] wins, so rule sets with a different
 * category order (Short Deck) are handled exactly.
 */
object HandEvaluator {

    fun evaluate(cards: List<PlayingCard>, rules: HandRules = HandRules.STANDARD): HandValue {
        val idx = IntArray(cards.size) { cards[it].index }
        return HandValue(evaluateIndices(idx, idx.size, rules))
    }

    /** Evaluates the first [count] entries of [cards] (card indices 0..51). Count must be 5..7. */
    fun evaluateIndices(cards: IntArray, count: Int, rules: HandRules = HandRules.STANDARD): Int {
        require(count in 5..7) { "Need 5 to 7 cards, got $count" }
        val rankCount = IntArray(13)
        val suitMask = IntArray(4)
        var rankMask = 0
        for (i in 0 until count) {
            val c = cards[i]
            val r = c shr 2
            rankCount[r]++
            suitMask[c and 3] = suitMask[c and 3] or (1 shl r)
            rankMask = rankMask or (1 shl r)
        }

        var best = Int.MIN_VALUE
        fun offer(v: Int) {
            if (v > best) best = v
        }

        var flushMask = 0
        for (s in 0 until 4) {
            if (Integer.bitCount(suitMask[s]) >= 5) {
                flushMask = suitMask[s]
                val sf = straightHigh(flushMask, rules)
                if (sf >= 0) offer(encode(rules, HandCategory.STRAIGHT_FLUSH, sf))
            }
        }

        var quad = -1
        var trip1 = -1
        var trip2 = -1
        var pair1 = -1
        var pair2 = -1
        for (r in 12 downTo 0) {
            when (rankCount[r]) {
                4 -> if (quad < 0) quad = r
                3 -> if (trip1 < 0) trip1 = r else if (trip2 < 0) trip2 = r
                2 -> if (pair1 < 0) pair1 = r else if (pair2 < 0) pair2 = r
            }
        }

        if (quad >= 0) offer(encode(rules, HandCategory.FOUR_OF_A_KIND, quad, highestExcluding(rankMask, quad)))
        if (trip1 >= 0 && (trip2 >= 0 || pair1 >= 0)) offer(encode(rules, HandCategory.FULL_HOUSE, trip1, maxOf(trip2, pair1)))
        if (flushMask != 0) offer(encodeTopBits(rules, HandCategory.FLUSH, flushMask, 5))
        val straight = straightHigh(rankMask, rules)
        if (straight >= 0) offer(encode(rules, HandCategory.STRAIGHT, straight))
        // Lower categories are scored only when enough distinct kicker ranks exist; otherwise the
        // cards already make a stronger hand (under every rule set) and the category cannot win.
        if (trip1 >= 0) {
            val kickers = rankMask and (1 shl trip1).inv()
            if (Integer.bitCount(kickers) >= 2) offer(encodeWithKickers(rules, HandCategory.THREE_OF_A_KIND, intArrayOf(trip1), kickers, 2))
        }
        if (pair1 >= 0 && pair2 >= 0) {
            val kickers = rankMask and (1 shl pair1).inv() and (1 shl pair2).inv()
            if (Integer.bitCount(kickers) >= 1) offer(encodeWithKickers(rules, HandCategory.TWO_PAIR, intArrayOf(pair1, pair2), kickers, 1))
        }
        if (pair1 >= 0) {
            val kickers = rankMask and (1 shl pair1).inv()
            if (Integer.bitCount(kickers) >= 3) offer(encodeWithKickers(rules, HandCategory.ONE_PAIR, intArrayOf(pair1), kickers, 3))
        }
        if (Integer.bitCount(rankMask) >= 5) offer(encodeTopBits(rules, HandCategory.HIGH_CARD, rankMask, 5))
        return best
    }

    /** Highest card of a 5-card run within [mask], or -1; the ace-low straight per [rules]. */
    private fun straightHigh(mask: Int, rules: HandRules): Int {
        for (high in 12 downTo 4) {
            val run = 0x1F shl (high - 4)
            if (mask and run == run) return high
        }
        return if (mask and rules.lowStraightMask == rules.lowStraightMask) rules.lowStraightHigh else -1
    }

    private fun highestExcluding(mask: Int, exclude: Int): Int {
        val m = mask and (1 shl exclude).inv()
        return 31 - Integer.numberOfLeadingZeros(m)
    }

    private fun header(rules: HandRules, category: HandCategory): Int =
        (rules.strength[category.ordinal] shl 24) or (category.ordinal shl 20)

    private fun encode(rules: HandRules, category: HandCategory, vararg ranks: Int): Int {
        var v = header(rules, category)
        var shift = 16
        for (r in ranks) {
            v = v or (r shl shift)
            shift -= 4
        }
        return v
    }

    private fun encodeTopBits(rules: HandRules, category: HandCategory, mask: Int, n: Int): Int {
        var v = header(rules, category)
        var m = mask
        var shift = 16
        repeat(n) {
            val r = 31 - Integer.numberOfLeadingZeros(m)
            v = v or (r shl shift)
            m = m and (1 shl r).inv()
            shift -= 4
        }
        return v
    }

    private fun encodeWithKickers(rules: HandRules, category: HandCategory, made: IntArray, kickerMask: Int, kickers: Int): Int {
        var v = header(rules, category)
        var shift = 16
        for (r in made) {
            v = v or (r shl shift)
            shift -= 4
        }
        var m = kickerMask
        repeat(kickers) {
            val r = 31 - Integer.numberOfLeadingZeros(m)
            v = v or (r shl shift)
            m = m and (1 shl r).inv()
            shift -= 4
        }
        return v
    }
}
