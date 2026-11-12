package com.pokervision.core.model.card

/** Deck composition. Short Deck (6+ Hold'em) removes ranks 2 through 5, leaving 36 cards. */
enum class DeckType(val ranks: Set<Rank>) {
    STANDARD_52(Rank.entries.toSet()),
    SHORT_DECK_36(Rank.entries.filter { it.value >= 6 }.toSet());

    val cards: List<PlayingCard> by lazy {
        Rank.entries.filter { it in ranks }.flatMap { r -> Suit.entries.map { s -> PlayingCard(r, s) } }
    }

    val size: Int get() = cards.size

    fun contains(card: PlayingCard): Boolean = card.rank in ranks
}
