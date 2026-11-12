package com.pokervision.core.model.game

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.confidence.Confidence
import com.pokervision.core.model.confidence.ObservationSource

/**
 * A position where a card may be (a hole-card slot or a board slot).
 *
 * The three states keep uncertain vision output separate from confirmed game state:
 * an [Uncertain] slot is never used by poker calculations until it is confirmed,
 * either by the user or by the (future) temporal-agreement logic.
 */
sealed interface CardSlot {

    /** Nothing known about this slot (face down, not visible, or not yet dealt). */
    data object Unknown : CardSlot

    /** Card identity is accepted as fact. */
    data class Confirmed(
        val card: PlayingCard,
        val source: ObservationSource,
        val confidence: Confidence = Confidence.CERTAIN,
    ) : CardSlot

    /** Vision produced one or more candidates, none reliable enough to accept. Sorted best first. */
    data class Uncertain(val candidates: List<CardCandidate>) : CardSlot {
        init {
            require(candidates.isNotEmpty()) { "Uncertain slot needs at least one candidate" }
        }

        val best: CardCandidate get() = candidates.first()
    }

    val confirmedCardOrNull: PlayingCard? get() = (this as? Confirmed)?.card
}

data class CardCandidate(val card: PlayingCard, val confidence: Confidence)
