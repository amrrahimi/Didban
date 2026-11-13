package com.pokervision.core.domain.usecase

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.poker.advice.LiveAdvice
import com.pokervision.core.poker.advice.LiveAdvisor
import com.pokervision.core.poker.holdem.HandCategory
import com.pokervision.core.poker.holdem.HandEvaluator
import com.pokervision.core.poker.holdem.HoldemAnalysis
import com.pokervision.core.poker.holdem.HoldemEquityCalculator
import com.pokervision.core.poker.holdem.HoldemEquityRequest
import com.pokervision.core.poker.holdem.HoldemAnalyzer
import com.pokervision.core.poker.holdem.HoldemSituation
import javax.inject.Inject

/**
 * Full Texas Hold'em analysis for one decision. CPU-bound (up to ~20k simulated deals):
 * callers must run it off the main thread.
 */
class AnalyzeHoldemUseCase @Inject constructor() {
    private val analyzer = HoldemAnalyzer()

    operator fun invoke(situation: HoldemSituation): HoldemAnalysis = analyzer.analyze(situation)
}

/** Result for the live camera screen: equity plus chip-free advice ("call up to X% of the pot"). */
data class LiveAnalysis(
    val currentHand: HandCategory?,
    val equity: Double,
    /** Half-width of the 95% interval for simulated results; null when the result is exact. */
    val estimateHalfWidth: Double?,
    val advice: LiveAdvice,
    val computeMillis: Long,
)

class AnalyzeLiveHandUseCase @Inject constructor() {

    /** Returns null when the hand is not analysable yet (fewer than 2 hole cards or 1-2 board cards). */
    operator fun invoke(
        hero: List<PlayingCard>,
        board: List<PlayingCard>,
        opponents: Int,
    ): LiveAnalysis? {
        if (hero.size != 2 || board.size !in listOf(0, 3, 4, 5)) return null
        val start = System.nanoTime()
        val result = HoldemEquityCalculator.calculate(
            HoldemEquityRequest(hero, board, opponents, iterations = LIVE_ITERATIONS),
        )
        val heroEquity = result.players.first()
        val current = if (board.size >= 3) {
            HandEvaluator.evaluate(hero + board).category
        } else {
            null
        }
        return LiveAnalysis(
            currentHand = current,
            equity = heroEquity.equity,
            estimateHalfWidth = heroEquity.equityInterval?.halfWidth,
            advice = LiveAdvisor.advise(heroEquity.equity, opponents),
            computeMillis = (System.nanoTime() - start) / 1_000_000,
        )
    }

    private companion object {
        /** About ±0.8 percentage points at 95% confidence; keeps phone latency low. */
        const val LIVE_ITERATIONS = 10_000
    }
}
