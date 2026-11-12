package com.pokervision.core.model.game

import com.pokervision.core.model.card.DeckType

/**
 * Static structural rules of each supported variant. Hand construction/ranking rules live in the
 * per-variant poker engines (Phase 5 and 9); this type only holds what game-state validation needs.
 */
enum class PokerVariant(
    val deckType: DeckType,
    /** Cards each player holds at showdown (Stud: 3 down + 4 up = 7). */
    val cardsPerPlayer: Int,
    /** Shared board cards at the river; 0 for non-community games. */
    val communityCards: Int,
    val minPlayers: Int,
    val maxPlayers: Int,
    val streets: List<BettingStreet>,
) {
    TEXAS_HOLDEM(DeckType.STANDARD_52, 2, 5, 2, 10, BettingStreet.BOARD_STREETS),
    OMAHA(DeckType.STANDARD_52, 4, 5, 2, 10, BettingStreet.BOARD_STREETS),
    OMAHA_HI_LO(DeckType.STANDARD_52, 4, 5, 2, 10, BettingStreet.BOARD_STREETS),

    /** 7 cards x 8 players = 56 > 52; real games handle the rare shortage with a community card. */
    SEVEN_CARD_STUD(DeckType.STANDARD_52, 7, 0, 2, 8, BettingStreet.STUD_STREETS),
    FIVE_CARD_DRAW(DeckType.STANDARD_52, 5, 0, 2, 6, BettingStreet.DRAW_STREETS),
    SHORT_DECK(DeckType.SHORT_DECK_36, 2, 5, 2, 9, BettingStreet.BOARD_STREETS);

    val usesCommunityCards: Boolean get() = communityCards > 0
}

enum class BettingStreet(
    /** Number of community cards visible on this street (board games only). */
    val visibleCommunityCards: Int,
    /** Cards each player holds on this street (stud only; -1 = not applicable). */
    val studCardsPerPlayer: Int = -1,
) {
    PRE_FLOP(0), FLOP(3), TURN(4), RIVER(5),
    THIRD_STREET(0, 3), FOURTH_STREET(0, 4), FIFTH_STREET(0, 5), SIXTH_STREET(0, 6), SEVENTH_STREET(0, 7),
    PRE_DRAW(0), POST_DRAW(0),

    /** Applies to every variant: hand complete, cards may be revealed. */
    SHOWDOWN(-1);

    companion object {
        val BOARD_STREETS = listOf(PRE_FLOP, FLOP, TURN, RIVER, SHOWDOWN)
        val STUD_STREETS = listOf(THIRD_STREET, FOURTH_STREET, FIFTH_STREET, SIXTH_STREET, SEVENTH_STREET, SHOWDOWN)
        val DRAW_STREETS = listOf(PRE_DRAW, POST_DRAW, SHOWDOWN)
    }
}
