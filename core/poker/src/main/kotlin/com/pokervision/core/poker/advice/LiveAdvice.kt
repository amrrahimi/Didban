package com.pokervision.core.poker.advice

/**
 * Advice for the live screen, where chips are not counted. Instead of exact pot odds it tells the
 * player the largest bet (as a fraction of the pot) they can profitably call, which can be judged
 * by eye at the table ("about half the pot").
 */
enum class LiveAction {
    /** Strong: bet if nobody has, raise if someone has. */
    BET_OR_RAISE,

    /** Check if possible; call bets up to [LiveAdvice.maxCallBetFraction] of the pot. */
    CHECK_OR_CALL,

    /** Check if possible; fold to almost any bet. */
    CHECK_OR_FOLD,
}

data class LiveAdvice(
    val action: LiveAction,
    val equity: Double,
    /** Largest callable bet as a fraction of the pot before the bet; null = any size is callable. */
    val maxCallBetFraction: Double?,
    val strongThreshold: Double,
)

object LiveAdvisor {

    /**
     * A bet of b × pot means calling b to win (1 + 2b) pot, so a call breaks even at
     * equity = b / (1 + 2b). Solving for b: b = e / (1 − 2e); from 50% equity any bet is callable.
     */
    fun maxCallBetFraction(equity: Double): Double? {
        require(equity in 0.0..1.0) { "equity must be in [0,1]" }
        return if (equity >= 0.5) null else equity / (1 - 2 * equity)
    }

    fun advise(
        equity: Double,
        opponents: Int,
        advisor: BasicAdvisor = BasicAdvisor(),
        minUsefulCallFraction: Double = DEFAULT_MIN_USEFUL_CALL_FRACTION,
    ): LiveAdvice {
        val strong = advisor.strongThreshold(opponents)
        val maxCall = maxCallBetFraction(equity)
        val action = when {
            equity >= strong -> LiveAction.BET_OR_RAISE
            maxCall == null || maxCall >= minUsefulCallFraction -> LiveAction.CHECK_OR_CALL
            else -> LiveAction.CHECK_OR_FOLD
        }
        return LiveAdvice(action, equity, maxCall, strong)
    }

    /** Below a third of the pot, practically every real bet is too big to call (equity < 20%). */
    const val DEFAULT_MIN_USEFUL_CALL_FRACTION = 1.0 / 3.0
}
