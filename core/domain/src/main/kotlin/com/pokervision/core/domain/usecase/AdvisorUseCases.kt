package com.pokervision.core.domain.usecase

import com.pokervision.core.poker.analysis.HandAnalyzer
import com.pokervision.core.poker.engine.EngineIssue
import javax.inject.Inject

/** Shared with iOS: the analysis itself lives in core:poker ([HandAnalyzer]). */
typealias HandQuery = com.pokervision.core.poker.analysis.HandQuery
typealias HandAnalysis = com.pokervision.core.poker.analysis.HandAnalysis

/**
 * Analyses a decision in any supported variant. CPU-bound (thousands of simulated deals): run it off
 * the main thread. Returns null with [issue] explaining why when the input is not analysable yet.
 */
class AnalyzeHandUseCase @Inject constructor() {
    private val analyzer = HandAnalyzer()

    fun issue(q: HandQuery): EngineIssue? = analyzer.issue(q)

    operator fun invoke(q: HandQuery): HandAnalysis? = analyzer(q)
}
