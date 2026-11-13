package com.pokervision.core.poker.holdem

import com.pokervision.core.model.card.PlayingCard

/** Poker hand categories, weakest first. Ordinal is used in [HandValue] encoding. */
enum class HandCategory {
    HIGH_CARD, ONE_PAIR, TWO_PAIR, THREE_OF_A_KIND, STRAIGHT, FLUSH, FULL_HOUSE, FOUR_OF_A_KIND, STRAIGHT_FLUSH
}

/**
 * Comparable strength of the best five-card hand.
 *
 * Encoding: `category << 20 | k1 << 16 | k2 << 12 | k3 << 8 | k4 << 4 | k5`, where k1..k5 are rank
 * indices (0 = deuce … 12 = ace) ordered by significance. A larger value is always a stronger hand
 * and equal values are exact ties.
 */
@JvmInline
value class HandValue(val encoded: Int) : Comparable<HandValue> {
    val category: HandCategory get() = HandCategory.entries[encoded ushr 20]
    override fun compareTo(other: HandValue): Int = encoded.compareTo(other.encoded)
}

/**
 * Deterministic evaluator for 5, 6 or 7 cards (selects the best five). Works on card indices
 * (see [PlayingCard.index]: rank = index / 4, suit = index % 4) and allocates nothing per call
 * except two small arrays, so it is fast enough for Monte Carlo simulation.
 */
object HandEvaluator {

    fun evaluate(cards: List<PlayingCard>): HandValue {
        val idx = IntArray(cards.size) { cards[it].index }
        return HandValue(evaluateIndices(idx, idx.size))
    }

    /** Evaluates the first [count] entries of [cards] (card indices 0..51). Count must be 5..7. */
    fun evaluateIndices(cards: IntArray, count: Int): Int {
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

        // Flush / straight flush
        var flushMask = 0
        for (s in 0 until 4) {
            if (Integer.bitCount(suitMask[s]) >= 5) {
                flushMask = suitMask[s]
                val sf = straightHigh(flushMask)
                if (sf >= 0) return encode(HandCategory.STRAIGHT_FLUSH, sf)
            }
        }

        // Group ranks by multiplicity, highest rank first.
        var quad = -1
        var trip1 = -1
        var trip2 = -1
        var pair1 = -1
        var pair2 = -1
        var pair3 = -1
        for (r in 12 downTo 0) {
            when (rankCount[r]) {
                4 -> if (quad < 0) quad = r
                3 -> if (trip1 < 0) trip1 = r else if (trip2 < 0) trip2 = r
                2 -> if (pair1 < 0) pair1 = r else if (pair2 < 0) pair2 = r else if (pair3 < 0) pair3 = r
            }
        }

        if (quad >= 0) {
            return encode(HandCategory.FOUR_OF_A_KIND, quad, highestExcluding(rankMask, quad))
        }
        if (trip1 >= 0 && (trip2 >= 0 || pair1 >= 0)) {
            val pairPart = maxOf(trip2, pair1)
            return encode(HandCategory.FULL_HOUSE, trip1, pairPart)
        }
        if (flushMask != 0) {
            return encodeTopBits(HandCategory.FLUSH, flushMask, 5)
        }
        val straight = straightHigh(rankMask)
        if (straight >= 0) return encode(HandCategory.STRAIGHT, straight)
        if (trip1 >= 0) {
            val kickers = rankMask and (1 shl trip1).inv()
            return encodeWithKickers(HandCategory.THREE_OF_A_KIND, intArrayOf(trip1), kickers, 2)
        }
        if (pair1 >= 0 && pair2 >= 0) {
            // pair3 (if any) is just a kicker candidate.
            val kickers = rankMask and (1 shl pair1).inv() and (1 shl pair2).inv()
            return encodeWithKickers(HandCategory.TWO_PAIR, intArrayOf(pair1, pair2), kickers, 1)
        }
        if (pair1 >= 0) {
            val kickers = rankMask and (1 shl pair1).inv()
            return encodeWithKickers(HandCategory.ONE_PAIR, intArrayOf(pair1), kickers, 3)
        }
        return encodeTopBits(HandCategory.HIGH_CARD, rankMask, 5)
    }

    /** Highest card of a 5-card run within [mask], or -1. Ace-low straight (A-2-3-4-5) returns 3 (the five). */
    private fun straightHigh(mask: Int): Int {
        for (high in 12 downTo 4) {
            val run = 0x1F shl (high - 4)
            if (mask and run == run) return high
        }
        val wheel = (1 shl 12) or 0xF // A,2,3,4,5
        return if (mask and wheel == wheel) 3 else -1
    }

    private fun highestExcluding(mask: Int, exclude: Int): Int {
        val m = mask and (1 shl exclude).inv()
        return 31 - Integer.numberOfLeadingZeros(m)
    }

    private fun encode(category: HandCategory, vararg ranks: Int): Int {
        var v = category.ordinal shl 20
        var shift = 16
        for (r in ranks) {
            v = v or (r shl shift)
            shift -= 4
        }
        return v
    }

    private fun encodeTopBits(category: HandCategory, mask: Int, n: Int): Int {
        var v = category.ordinal shl 20
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

    private fun encodeWithKickers(category: HandCategory, made: IntArray, kickerMask: Int, kickers: Int): Int {
        var v = category.ordinal shl 20
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
