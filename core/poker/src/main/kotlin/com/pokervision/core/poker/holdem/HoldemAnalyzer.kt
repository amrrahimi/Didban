package com.pokervision.core.poker.holdem

import com.pokervision.core.model.analytics.EquityResult
import com.pokervision.core.model.card.DeckType
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.poker.advice.Advice
import com.pokervision.core.poker.advice.BasicAdvisor

data class HoldemSituation(
    val heroCards: List<PlayingCard>,
    val board: List<PlayingCard>,
    val opponents: Int,
    val deadCards: List<PlayingCard> = emptyList(),
    val pot: Long? = null,
    val toCall: Long? = null,
)

data class HoldemAnalysis(
    /** Current made hand; null pre-flop (fewer than 5 cards known). */
    val currentHand: HandCategory?,
    val equity: EquityResult,
    /**
     * Unseen cards that would improve hero's hand category on the next card (flop/turn only).
     * "Improve" is about hero's own hand, not a guarantee of beating opponents.
     */
    val improvingCards: List<PlayingCard>,
    val advice: Advice,
)

/** Complete Texas Hold'em analysis for one decision point. Pure and deterministic for a given seed. */
class HoldemAnalyzer(
    private val advisor: BasicAdvisor = BasicAdvisor(),
    private val iterations: Int = HoldemEquityRequest.DEFAULT_ITERATIONS,
) {

    fun analyze(s: HoldemSituation): HoldemAnalysis {
        val equity = HoldemEquityCalculator.calculate(
            HoldemEquityRequest(
                heroCards = s.heroCards,
                board = s.board,
                opponents = s.opponents,
                deadCards = s.deadCards,
                iterations = iterations,
            ),
        )
        val known = s.heroCards + s.board
        val current = if (known.size >= 5) HandEvaluator.evaluate(known).category else null
        val improving = if (s.board.size == 3 || s.board.size == 4) improvingCards(s, current!!) else emptyList()
        val heroEquity = equity.players.first().equity
        return HoldemAnalysis(current, equity, improving, advisor.advise(heroEquity, s.opponents, s.pot, s.toCall))
    }

    private fun improvingCards(s: HoldemSituation, current: HandCategory): List<PlayingCard> {
        val used = (s.heroCards + s.board + s.deadCards).toSet()
        return DeckType.STANDARD_52.cards.filter { it !in used }.filter { next ->
            HandEvaluator.evaluate(s.heroCards + s.board + next).category > current
        }
    }
}
