package com.pokervision.core.domain.usecase

import com.pokervision.core.model.analytics.EquityResult
import com.pokervision.core.model.card.DeckType
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.poker.advice.Advice
import com.pokervision.core.poker.advice.BasicAdvisor
import com.pokervision.core.poker.advice.LiveAdvice
import com.pokervision.core.poker.advice.LiveAdvisor
import com.pokervision.core.poker.engine.DrawAdvice
import com.pokervision.core.poker.engine.EngineIssue
import com.pokervision.core.poker.engine.EngineSituation
import com.pokervision.core.poker.engine.PokerEngines
import com.pokervision.core.poker.holdem.HandCategory
import com.pokervision.core.poker.holdem.HandEvaluator
import javax.inject.Inject

/** Inputs for one decision, in any variant. */
data class HandQuery(
    val variant: PokerVariant,
    val mine: List<PlayingCard>,
    val board: List<PlayingCard> = emptyList(),
    /** Cards known to be out of play (Stud: opponents' up-cards). */
    val seen: List<PlayingCard> = emptyList(),
    val opponents: Int,
    /** Chips already in the pot, if known (enables exact pot odds). */
    val pot: Long? = null,
    /** Chips hero must add to continue, if anyone has bet. */
    val toCall: Long? = null,
    /** Five Card Draw: true once hero has drawn. Before the draw, keep/discard advice is computed. */
    val drawDone: Boolean = false,
)

data class HandAnalysis(
    val variant: PokerVariant,
    val currentHand: HandCategory?,
    val equity: EquityResult,
    /** Chip-free advice: "call bets up to X% of the pot". */
    val liveAdvice: LiveAdvice,
    /** Pot-odds advice when pot / to-call are known (manual advisor). */
    val potAdvice: Advice,
    /** Five Card Draw before the draw: what to keep. */
    val draw: DrawAdvice?,
    /** Hold'em on the flop/turn: unseen cards that improve hero's hand category. */
    val improvingCards: List<PlayingCard>,
    val computeMillis: Long,
) {
    val heroEquity: Double get() = equity.players.first().equity
    val estimateHalfWidth: Double? get() = equity.players.first().equityInterval?.halfWidth
}

/**
 * Analyses a decision in any supported variant. CPU-bound (thousands of simulated deals): run it off
 * the main thread. Returns null with [issue] explaining why when the input is not analysable yet.
 */
class AnalyzeHandUseCase @Inject constructor() {

    private val advisor = BasicAdvisor()

    fun issue(q: HandQuery): EngineIssue? = PokerEngines.forVariant(q.variant).validate(q.toSituation())

    operator fun invoke(q: HandQuery): HandAnalysis? {
        val engine = PokerEngines.forVariant(q.variant)
        val situation = q.toSituation()
        if (engine.validate(situation) != null) return null
        val start = System.nanoTime()

        val draw = if (q.variant == PokerVariant.FIVE_CARD_DRAW && !q.drawDone) {
            PokerEngines.bestDraw(q.mine, q.opponents, q.seen)
        } else {
            null
        }
        val equity = if (draw != null) {
            // Equity of the recommended draw: kept cards plus random replacements.
            engine.equity(EngineSituation(draw.keep, emptyList(), q.opponents, q.seen + draw.discard))
        } else {
            engine.equity(situation)
        }
        val e = equity.players.first().equity
        return HandAnalysis(
            variant = q.variant,
            currentHand = engine.currentHand(situation),
            equity = equity,
            liveAdvice = LiveAdvisor.advise(e, q.opponents, advisor),
            potAdvice = advisor.advise(e, q.opponents, q.pot, q.toCall),
            draw = draw,
            improvingCards = if (q.variant == PokerVariant.TEXAS_HOLDEM) holdemImprovingCards(q) else emptyList(),
            computeMillis = (System.nanoTime() - start) / 1_000_000,
        )
    }

    private fun HandQuery.toSituation() = EngineSituation(mine, board, opponents, seen)

    private fun holdemImprovingCards(q: HandQuery): List<PlayingCard> {
        if (q.board.size !in 3..4) return emptyList()
        val current = HandEvaluator.evaluate(q.mine + q.board).category
        val used = (q.mine + q.board + q.seen).toSet()
        return DeckType.STANDARD_52.cards.filter { it !in used }
            .filter { HandEvaluator.evaluate(q.mine + q.board + it).category > current }
    }
}
