package com.pokervision.core.poker.math

/** Pot odds and expected value of calling. All amounts in the same chip unit. */
object PotMath {

    /**
     * Minimum equity needed for a call to break even: toCall / (pot + toCall).
     * [pot] is everything already in the middle, including opponents' bets this round.
     */
    fun requiredEquity(pot: Long, toCall: Long): Double {
        require(pot >= 0 && toCall > 0) { "pot must be >= 0 and toCall > 0" }
        return toCall.toDouble() / (pot + toCall)
    }

    /** Expected chips won (positive) or lost (negative) by calling, ignoring future betting. */
    fun callEv(equity: Double, pot: Long, toCall: Long): Double {
        require(equity in 0.0..1.0) { "equity must be in [0,1]" }
        require(pot >= 0 && toCall >= 0) { "amounts must be >= 0" }
        return equity * (pot + toCall) - toCall
    }
}
