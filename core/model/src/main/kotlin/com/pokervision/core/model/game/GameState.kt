package com.pokervision.core.model.game

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.confidence.Observed

/**
 * Snapshot of a single hand. Built from confirmed and uncertain observations; validation
 * ([com.pokervision.core.model.validation.GameStateValidator]) and calculations only trust
 * [CardSlot.Confirmed] entries.
 */
data class GameState(
    val gameId: String,
    val handNumber: Int,
    val variant: PokerVariant,
    val table: PokerTable,
    val street: Observed<BettingStreet>? = null,
    val players: List<Player> = emptyList(),
    val communityCards: List<CardSlot> = emptyList(),
    val pot: Observed<ChipAmount>? = null,
    val actions: List<BettingAction> = emptyList(),
    val updatedAtMillis: Long,
) {
    val confirmedCommunityCards: List<PlayingCard>
        get() = communityCards.mapNotNull { it.confirmedCardOrNull }

    /** Every confirmed card with a human-readable location, used for duplicate detection. */
    fun confirmedCardLocations(): List<Pair<PlayingCard, CardLocation>> = buildList {
        communityCards.forEachIndexed { i, slot ->
            slot.confirmedCardOrNull?.let { add(it to CardLocation.Board(i)) }
        }
        players.forEach { p ->
            p.cards.forEachIndexed { i, slot ->
                slot.confirmedCardOrNull?.let { add(it to CardLocation.PlayerCard(p.id, i)) }
            }
        }
    }

    val hasUncertainCards: Boolean
        get() = communityCards.any { it is CardSlot.Uncertain } ||
            players.any { p -> p.cards.any { it is CardSlot.Uncertain } }
}

sealed interface CardLocation {
    data class Board(val index: Int) : CardLocation
    data class PlayerCard(val playerId: String, val index: Int) : CardLocation
}

/** A completed (or abandoned) hand as stored in history. */
data class HandHistory(
    val id: Long = 0,
    val gameId: String,
    val handNumber: Int,
    val variant: PokerVariant,
    val boardCards: List<PlayingCard>,
    val heroCards: List<PlayingCard>,
    val finalPot: ChipAmount?,
    val winnerPlayerIds: List<String>,
    val notes: String = "",
    val createdAtMillis: Long,
)
