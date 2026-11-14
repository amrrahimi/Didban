package com.pokervision.core.domain.vision

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.poker.engine.PokerEngines

/** What a scan button reads. */
enum class ScanTarget {
    /** The player's own cards. */
    MINE,

    /** Board games: the community cards. Stud: opponents' face-up cards. Not used in Draw. */
    TABLE,
}

/** Everything the player has told the app (by scanning or tapping) about the current hand. */
data class TableState(
    val variant: PokerVariant = PokerVariant.TEXAS_HOLDEM,
    val mine: List<PlayingCard> = emptyList(),
    /** Community cards (Hold'em, Omaha, Short Deck). */
    val board: List<PlayingCard> = emptyList(),
    /** Cards known to be out of play: opponents' up-cards in Stud, cards shown by folded players. */
    val seen: List<PlayingCard> = emptyList(),
    val opponents: Int = 1,
    /** Five Card Draw: true once the player has drawn (advice then evaluates the final hand). */
    val drawDone: Boolean = false,
    val handNumber: Int = 1,
) {
    val allCards: Set<PlayingCard> get() = (mine + board + seen).toSet()
}

data class ScanOutcome(val state: TableState, val added: List<PlayingCard>, val ignored: List<PlayingCard>)

/** Pure rules for building a hand from scans and taps, per variant. */
object TableRules {

    fun mineCapacity(v: PokerVariant): Int = PokerEngines.forVariant(v).heroCards.last

    fun usesBoard(v: PokerVariant): Boolean = v.usesCommunityCards

    /** Stud shows opponents' up-cards; Draw has nothing on the table to scan. */
    fun tableScanAvailable(v: PokerVariant): Boolean = v != PokerVariant.FIVE_CARD_DRAW

    fun tableScanAvailableFor(state: TableState): Boolean = tableScanAvailable(state.variant)

    fun maxOpponents(v: PokerVariant): Int = PokerEngines.forVariant(v).maxOpponents

    /**
     * A scan replaces what it reads (scanning the board again after the turn gives the full board).
     * Cards already assigned to the other area and cards not in this variant's deck are ignored;
     * cards beyond the capacity are ignored and reported.
     */
    fun applyScan(state: TableState, target: ScanTarget, cards: List<PlayingCard>): ScanOutcome {
        val deck = PokerEngines.forVariant(state.variant).deck
        val valid = cards.distinct().filter { deck.contains(it) }
        val invalid = cards.distinct() - valid.toSet()
        return when (target) {
            ScanTarget.MINE -> {
                val candidates = valid.filter { it !in state.board && it !in state.seen }
                val cap = mineCapacity(state.variant)
                val kept = candidates.take(cap)
                ScanOutcome(state.copy(mine = kept), kept, invalid + candidates.drop(cap))
            }
            ScanTarget.TABLE -> {
                val candidates = valid.filter { it !in state.mine }
                if (usesBoard(state.variant)) {
                    val kept = candidates.take(BOARD_CAPACITY)
                    ScanOutcome(state.copy(board = kept), kept, invalid + candidates.drop(BOARD_CAPACITY))
                } else {
                    val kept = candidates.take(SEEN_CAPACITY)
                    ScanOutcome(state.copy(seen = kept), kept, invalid + candidates.drop(SEEN_CAPACITY))
                }
            }
        }
    }

    /** Manual add (tap). Returns the state unchanged if the card is used, invalid, or the area is full. */
    fun add(state: TableState, card: PlayingCard, target: ScanTarget): TableState {
        if (card in state.allCards || !PokerEngines.forVariant(state.variant).deck.contains(card)) return state
        return when {
            target == ScanTarget.MINE ->
                if (state.mine.size < mineCapacity(state.variant)) state.copy(mine = state.mine + card) else state
            usesBoard(state.variant) ->
                if (state.board.size < BOARD_CAPACITY) state.copy(board = state.board + card) else state
            else ->
                if (state.seen.size < SEEN_CAPACITY) state.copy(seen = state.seen + card) else state
        }
    }

    fun remove(state: TableState, card: PlayingCard) =
        state.copy(mine = state.mine - card, board = state.board - card, seen = state.seen - card)

    fun setOpponents(state: TableState, n: Int) = state.copy(opponents = n.coerceIn(1, maxOpponents(state.variant)))

    fun newHand(state: TableState) = TableState(variant = state.variant, opponents = state.opponents, handNumber = state.handNumber + 1)

    fun setVariant(state: TableState, variant: PokerVariant) =
        TableState(variant = variant, opponents = state.opponents.coerceIn(1, maxOpponents(variant)), handNumber = state.handNumber + 1)

    const val BOARD_CAPACITY = 5
    const val SEEN_CAPACITY = 21
}
