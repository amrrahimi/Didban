package com.pokervision.core.domain.vision

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.card.Rank
import com.pokervision.core.model.card.Suit

/** One step of the guided "learn my cards" session: a specific card, or the empty table. */
sealed interface CaptureTarget {
    /** Folder/file name used in the exported dataset (e.g. "AS", "10H", "background"). */
    val datasetName: String

    data class Card(val card: PlayingCard) : CaptureTarget {
        override val datasetName: String = DeckCapturePlan.datasetCode(card)
    }

    /** Photos of the table without cards: teach the model what is *not* a card. */
    data object Background : CaptureTarget {
        override val datasetName: String = "background"
    }
}

/**
 * Order and size of the guided capture. Cards are grouped by suit (as they come out of a sorted
 * deck) and the empty table comes last. Pure, so the flow is unit-tested.
 */
object DeckCapturePlan {

    const val SHOTS_PER_TARGET = 6

    /** Minimum time between automatic shots, so the user's small phone movements add variety. */
    const val MIN_SHOT_INTERVAL_MS = 500L

    val SUIT_ORDER = listOf(Suit.SPADES, Suit.HEARTS, Suit.DIAMONDS, Suit.CLUBS)

    val targets: List<CaptureTarget> =
        SUIT_ORDER.flatMap { suit -> Rank.entries.reversed().map { CaptureTarget.Card(PlayingCard(it, suit)) } } +
            CaptureTarget.Background

    /** Same codes the detection model uses: rank ("10" for ten) + uppercase suit letter. */
    fun datasetCode(card: PlayingCard): String {
        val rank = if (card.rank == Rank.TEN) "10" else card.rank.symbol.toString()
        return rank + card.suit.symbol.uppercaseChar()
    }

    /** Index of the first target that still needs shots, or null when everything is captured. */
    fun nextIncomplete(counts: Map<String, Int>, from: Int = 0): Int? {
        val n = targets.size
        for (k in 0 until n) {
            val i = (from + k) % n
            if ((counts[targets[i].datasetName] ?: 0) < SHOTS_PER_TARGET) return i
        }
        return null
    }

    fun completedTargets(counts: Map<String, Int>): Int =
        targets.count { (counts[it.datasetName] ?: 0) >= SHOTS_PER_TARGET }
}
