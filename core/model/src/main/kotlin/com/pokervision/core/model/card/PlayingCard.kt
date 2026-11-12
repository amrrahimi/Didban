package com.pokervision.core.model.card

/** Card rank. [value] follows poker convention (Ace high = 14); ace-low handling belongs to engines. */
enum class Rank(val value: Int, val symbol: Char) {
    TWO(2, '2'), THREE(3, '3'), FOUR(4, '4'), FIVE(5, '5'), SIX(6, '6'), SEVEN(7, '7'),
    EIGHT(8, '8'), NINE(9, '9'), TEN(10, 'T'), JACK(11, 'J'), QUEEN(12, 'Q'), KING(13, 'K'), ACE(14, 'A');

    companion object {
        fun fromSymbol(symbol: Char): Rank? = entries.firstOrNull { it.symbol == symbol.uppercaseChar() }
    }
}

enum class Suit(val symbol: Char, val glyph: Char) {
    CLUBS('c', '♣'), DIAMONDS('d', '♦'), HEARTS('h', '♥'), SPADES('s', '♠');

    val isRed: Boolean get() = this == DIAMONDS || this == HEARTS

    companion object {
        fun fromSymbol(symbol: Char): Suit? = entries.firstOrNull { it.symbol == symbol.lowercaseChar() }
    }
}

/**
 * One of the 52 standard playing cards. Immutable value type.
 * Text code uses the common two-character notation: rank + suit, e.g. "As", "Td", "7h".
 */
data class PlayingCard(val rank: Rank, val suit: Suit) : Comparable<PlayingCard> {

    /** Stable index 0..51 (rank-major), suitable for bitmasks and lookup tables. */
    val index: Int get() = (rank.value - 2) * 4 + suit.ordinal

    val code: String get() = "${rank.symbol}${suit.symbol}"

    override fun compareTo(other: PlayingCard): Int = index.compareTo(other.index)

    override fun toString(): String = code

    companion object {
        /** Parses "As", "td", "10h" (10 accepted as an alias for T). Returns null for invalid input. */
        fun parseOrNull(text: String): PlayingCard? {
            val t = text.trim()
            val normalized = if (t.length == 3 && t.startsWith("10")) "T${t[2]}" else t
            if (normalized.length != 2) return null
            val rank = Rank.fromSymbol(normalized[0]) ?: return null
            val suit = Suit.fromSymbol(normalized[1]) ?: return null
            return PlayingCard(rank, suit)
        }

        fun parse(text: String): PlayingCard =
            parseOrNull(text) ?: throw IllegalArgumentException("Invalid card code: '$text'")

        fun fromIndex(index: Int): PlayingCard {
            require(index in 0..51) { "Card index must be in 0..51, was $index" }
            return PlayingCard(Rank.entries[index / 4], Suit.entries[index % 4])
        }
    }
}

/** Encodes card lists as compact strings ("AsKd7h") for storage and decodes them back. */
object CardCodec {
    fun encode(cards: List<PlayingCard>): String = cards.joinToString(separator = "") { it.code }

    /** Decodes a string produced by [encode]. Returns null if the string is malformed. */
    fun decodeOrNull(encoded: String): List<PlayingCard>? {
        if (encoded.length % 2 != 0) return null
        return encoded.chunked(2).map { PlayingCard.parseOrNull(it) ?: return null }
    }
}
