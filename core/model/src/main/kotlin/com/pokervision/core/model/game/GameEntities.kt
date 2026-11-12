package com.pokervision.core.model.game

import com.pokervision.core.model.confidence.Observed
import com.pokervision.core.model.detection.BoundingBox

/** Non-negative chip amount in the table's smallest unit. */
@JvmInline
value class ChipAmount(val units: Long) : Comparable<ChipAmount> {
    init {
        require(units >= 0) { "Chip amount cannot be negative: $units" }
    }

    operator fun plus(other: ChipAmount) = ChipAmount(units + other.units)
    override fun compareTo(other: ChipAmount): Int = units.compareTo(other.units)

    companion object {
        val ZERO = ChipAmount(0)
    }
}

enum class TablePosition { BUTTON, SMALL_BLIND, BIG_BLIND, UNDER_THE_GUN, MIDDLE, HIJACK, CUTOFF, UNKNOWN }

data class Player(
    val id: String,
    /** Zero-based seat index around the table. */
    val seatIndex: Int,
    val displayName: String? = null,
    val position: TablePosition = TablePosition.UNKNOWN,
    val stack: Observed<ChipAmount>? = null,
    /** Player's private/up cards. Size must not exceed the variant's cardsPerPlayer. */
    val cards: List<CardSlot> = emptyList(),
    val isActive: Boolean = true,
    /** Hero = the user. Other players' private cards are only stored when legitimately revealed. */
    val isHero: Boolean = false,
)

data class PokerTable(
    val seatCount: Int,
    val buttonSeat: Int? = null,
    /** Detected table region in normalized image coordinates, if known. */
    val boundary: BoundingBox? = null,
) {
    init {
        require(seatCount in 2..10) { "seatCount must be in 2..10, was $seatCount" }
        require(buttonSeat == null || buttonSeat in 0 until seatCount) { "buttonSeat out of range" }
    }
}

enum class ActionType { POST_BLIND, POST_ANTE, FOLD, CHECK, CALL, BET, RAISE, ALL_IN, DRAW }

data class BettingAction(
    val playerId: String,
    val street: BettingStreet,
    val type: ActionType,
    val amount: ChipAmount? = null,
    /** Offset into the media (video) or wall-clock epoch millis for live sessions. */
    val timestampMillis: Long,
    val observed: Observed<ActionType>? = null,
)

enum class GameSource { LIVE_CAMERA, IMAGE, VIDEO, MANUAL }

/** A playing session (one sitting / one analysed media file). Contains zero or more hands. */
data class PokerGame(
    val id: String,
    val variant: PokerVariant,
    val source: GameSource,
    val playerCount: Int,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val mediaAssetId: Long? = null,
    val notes: String = "",
)
