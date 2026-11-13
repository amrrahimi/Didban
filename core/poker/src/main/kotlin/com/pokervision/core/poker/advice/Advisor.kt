package com.pokervision.core.poker.advice

import com.pokervision.core.poker.math.PotMath

enum class AdviceAction { FOLD, CHECK, CALL, BET, RAISE }

enum class AdviceReason {
    /** Equity is below the price the pot offers. */
    BELOW_POT_ODDS,

    /** Equity covers the price of calling. */
    ABOVE_POT_ODDS,

    /** Clearly ahead of the field: build the pot. */
    STRONG_HAND,

    /** Nothing to pay and not strong enough to bet. */
    FREE_CARD,

    /** Pot size unknown: compared with a fair share (1 / players) instead of pot odds. */
    FAIR_SHARE_ONLY,
}

data class Advice(
    val action: AdviceAction,
    val reason: AdviceReason,
    val equity: Double,
    /** Equity needed to call profitably; null when there is nothing to call or the pot is unknown. */
    val requiredEquity: Double?,
    /** Expected value of a call in chips; null when not applicable. */
    val callEv: Double?,
    /** Equity at or above which betting/raising is suggested. */
    val strongThreshold: Double,
)

/**
 * Simple, explainable decision rule built only on equity and pot odds. It deliberately ignores
 * opponent tendencies, bluffing, position and future betting, so it is a learning aid, not a
 * solver. Thresholds are parameters, not hidden constants.
 */
class BasicAdvisor(
    /** Fraction of the gap between a fair share and certainty required to bet/raise. */
    private val strengthFactor: Double = DEFAULT_STRENGTH_FACTOR,
) {
    init {
        require(strengthFactor in 0.0..1.0) { "strengthFactor must be in [0,1]" }
    }

    /** Fair share of the pot with [opponents] + hero all equal: 1 / (opponents + 1). */
    fun fairShare(opponents: Int): Double = 1.0 / (opponents + 1)

    fun strongThreshold(opponents: Int): Double {
        val fair = fairShare(opponents)
        return fair + (1.0 - fair) * strengthFactor
    }

    /**
     * @param pot chips already in the pot (null if unknown)
     * @param toCall chips hero must add to continue (0 or null = nothing to call)
     */
    fun advise(equity: Double, opponents: Int, pot: Long?, toCall: Long?): Advice {
        require(equity in 0.0..1.0) { "equity must be in [0,1]" }
        require(opponents >= 1) { "opponents must be >= 1" }
        val strong = strongThreshold(opponents)

        if (toCall == null || toCall <= 0L) {
            return if (equity >= strong) {
                Advice(AdviceAction.BET, AdviceReason.STRONG_HAND, equity, null, null, strong)
            } else {
                Advice(AdviceAction.CHECK, AdviceReason.FREE_CARD, equity, null, null, strong)
            }
        }

        if (pot == null) {
            val fair = fairShare(opponents)
            val action = when {
                equity >= strong -> AdviceAction.RAISE
                equity >= fair -> AdviceAction.CALL
                else -> AdviceAction.FOLD
            }
            return Advice(action, AdviceReason.FAIR_SHARE_ONLY, equity, null, null, strong)
        }

        val required = PotMath.requiredEquity(pot, toCall)
        val ev = PotMath.callEv(equity, pot, toCall)
        return when {
            equity < required -> Advice(AdviceAction.FOLD, AdviceReason.BELOW_POT_ODDS, equity, required, ev, strong)
            equity >= strong -> Advice(AdviceAction.RAISE, AdviceReason.STRONG_HAND, equity, required, ev, strong)
            else -> Advice(AdviceAction.CALL, AdviceReason.ABOVE_POT_ODDS, equity, required, ev, strong)
        }
    }

    companion object {
        /** Heads-up: bet/raise from 70% equity; six-handed: from 50%. */
        const val DEFAULT_STRENGTH_FACTOR = 0.4
    }
}
