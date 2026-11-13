package com.pokervision.feature.advisor

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.poker.holdem.HoldemSituation

/** Which list a tapped card goes into. */
enum class CardTarget(val capacity: Int) {
    HERO(2),
    BOARD(5),

    /** Cards known to be out of this hand (e.g. a player showed a card when folding). */
    SEEN(MAX_SEEN_CARDS),
}

private const val MAX_SEEN_CARDS = 12
const val MIN_OPPONENTS = 1
const val MAX_OPPONENTS = 9

data class AdvisorInput(
    val hero: List<PlayingCard> = emptyList(),
    val board: List<PlayingCard> = emptyList(),
    val seen: List<PlayingCard> = emptyList(),
    val opponents: Int = MIN_OPPONENTS,
    val pot: Long? = null,
    val toCall: Long? = null,
    val target: CardTarget = CardTarget.HERO,
    /** Incremented by "new hand"; lets the UI reset its text fields. */
    val handNumber: Int = 1,
) {
    val usedCards: Set<PlayingCard> get() = (hero + board + seen).toSet()

    fun cardsFor(target: CardTarget): List<PlayingCard> = when (target) {
        CardTarget.HERO -> hero
        CardTarget.BOARD -> board
        CardTarget.SEEN -> seen
    }
}

/** Why no analysis can be shown yet. */
enum class MissingInput { HERO_CARDS, BOARD_INCOMPLETE, TOO_MANY_CARDS }

/**
 * Pure editing rules for the advisor form. Kept free of Android so they are unit-tested on the JVM.
 */
object AdvisorInputReducer {

    /**
     * Tapping a card that is already used removes it from wherever it is. Otherwise it is added to the
     * current target if there is room. Filling the two hole cards moves the target to the board.
     */
    fun tapCard(input: AdvisorInput, card: PlayingCard): AdvisorInput {
        if (card in input.usedCards) {
            return input.copy(hero = input.hero - card, board = input.board - card, seen = input.seen - card)
        }
        val current = input.cardsFor(input.target)
        if (current.size >= input.target.capacity) return input
        val updated = when (input.target) {
            CardTarget.HERO -> input.copy(hero = input.hero + card)
            CardTarget.BOARD -> input.copy(board = input.board + card)
            CardTarget.SEEN -> input.copy(seen = input.seen + card)
        }
        return if (updated.target == CardTarget.HERO && updated.hero.size == CardTarget.HERO.capacity) {
            updated.copy(target = CardTarget.BOARD)
        } else {
            updated
        }
    }

    fun setOpponents(input: AdvisorInput, opponents: Int) =
        input.copy(opponents = opponents.coerceIn(MIN_OPPONENTS, MAX_OPPONENTS))

    /** Parses a chip amount typed by the user; blank or invalid becomes null (unknown). Accepts Persian digits. */
    fun parseAmount(text: String): Long? {
        val latin = text.trim().map { c ->
            when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                else -> c
            }
        }.joinToString("").replace(",", "").replace("٬", "")
        return latin.toLongOrNull()?.takeIf { it >= 0 }
    }

    /** Keeps opponents; clears cards and money for the next hand. */
    fun newHand(input: AdvisorInput) = AdvisorInput(opponents = input.opponents, handNumber = input.handNumber + 1)

    fun missing(input: AdvisorInput): MissingInput? {
        if (input.hero.size < 2) return MissingInput.HERO_CARDS
        if (input.board.size in 1..2) return MissingInput.BOARD_INCOMPLETE
        val needed = 2 * input.opponents + (5 - input.board.size)
        if (52 - input.usedCards.size < needed) return MissingInput.TOO_MANY_CARDS
        return null
    }

    fun toSituation(input: AdvisorInput): HoldemSituation? {
        if (missing(input) != null) return null
        return HoldemSituation(
            heroCards = input.hero,
            board = input.board,
            opponents = input.opponents,
            deadCards = input.seen,
            pot = input.pot,
            toCall = input.toCall,
        )
    }
}
