package com.pokervision.core.domain.usecase

import com.pokervision.core.poker.holdem.HoldemAnalysis
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
