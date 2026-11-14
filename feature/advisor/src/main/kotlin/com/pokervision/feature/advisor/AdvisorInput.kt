package com.pokervision.feature.advisor

import com.pokervision.core.domain.usecase.HandQuery
import com.pokervision.core.domain.vision.TableRules
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.poker.engine.PokerEngines

/** Which list a tapped card goes into. */
enum class CardTarget {
    HERO,
    BOARD,

    /** Cards known to be out of this hand (shown by a folded player; opponents' up-cards in Stud). */
    SEEN,
}

private const val MAX_SEEN_CARDS = 21
const val MIN_OPPONENTS = 1

data class AdvisorInput(
    val variant: PokerVariant = PokerVariant.TEXAS_HOLDEM,
    val hero: List<PlayingCard> = emptyList(),
    val board: List<PlayingCard> = emptyList(),
    val seen: List<PlayingCard> = emptyList(),
    val opponents: Int = MIN_OPPONENTS,
    val pot: Long? = null,
    val toCall: Long? = null,
    val target: CardTarget = CardTarget.HERO,
    /** Five Card Draw: whether the draw has happened. */
    val drawDone: Boolean = false,
    /** Incremented by "new hand"; lets the UI reset its text fields. */
    val handNumber: Int = 1,
) {
    val usedCards: Set<PlayingCard> get() = (hero + board + seen).toSet()

    fun cardsFor(target: CardTarget): List<PlayingCard> = when (target) {
        CardTarget.HERO -> hero
        CardTarget.BOARD -> board
        CardTarget.SEEN -> seen
    }

    fun capacity(target: CardTarget): Int = when (target) {
        CardTarget.HERO -> TableRules.mineCapacity(variant)
        CardTarget.BOARD -> if (TableRules.usesBoard(variant)) TableRules.BOARD_CAPACITY else 0
        CardTarget.SEEN -> MAX_SEEN_CARDS
    }

    val maxOpponents: Int get() = TableRules.maxOpponents(variant)
}

/** Pure editing rules for the advisor form, for every variant. Unit-tested on the JVM. */
object AdvisorInputReducer {

    /**
     * Tapping a card that is already used removes it. Otherwise it is added to the current target if
     * there is room and the card exists in this variant's deck. Filling the hero's cards moves the
     * target to the board in community-card games.
     */
    fun tapCard(input: AdvisorInput, card: PlayingCard): AdvisorInput {
        if (card in input.usedCards) {
            return input.copy(hero = input.hero - card, board = input.board - card, seen = input.seen - card)
        }
        if (!PokerEngines.forVariant(input.variant).deck.contains(card)) return input
        if (input.cardsFor(input.target).size >= input.capacity(input.target)) return input
        val updated = when (input.target) {
            CardTarget.HERO -> input.copy(hero = input.hero + card)
            CardTarget.BOARD -> input.copy(board = input.board + card)
            CardTarget.SEEN -> input.copy(seen = input.seen + card)
        }
        val heroFull = updated.hero.size == updated.capacity(CardTarget.HERO)
        return if (updated.target == CardTarget.HERO && heroFull && TableRules.usesBoard(updated.variant)) {
            updated.copy(target = CardTarget.BOARD)
        } else {
            updated
        }
    }

    fun setOpponents(input: AdvisorInput, opponents: Int) =
        input.copy(opponents = opponents.coerceIn(MIN_OPPONENTS, input.maxOpponents))

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

    /** Keeps variant and opponents; clears cards and money for the next hand. */
    fun newHand(input: AdvisorInput) =
        AdvisorInput(variant = input.variant, opponents = input.opponents, handNumber = input.handNumber + 1)

    fun setVariant(input: AdvisorInput, variant: PokerVariant): AdvisorInput {
        if (variant == input.variant) return input
        val fresh = newHand(input).copy(variant = variant)
        return setOpponents(fresh, fresh.opponents)
    }

    fun toQuery(input: AdvisorInput) = HandQuery(
        variant = input.variant,
        mine = input.hero,
        board = input.board,
        seen = input.seen,
        opponents = input.opponents,
        pot = input.pot,
        toCall = input.toCall,
        drawDone = input.drawDone,
    )
}
