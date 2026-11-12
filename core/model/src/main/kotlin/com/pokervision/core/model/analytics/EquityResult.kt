package com.pokervision.core.model.analytics

import kotlin.math.sqrt

enum class CalculationMethod {
    /** Every remaining card combination evaluated: the result is an exact probability. */
    EXACT_ENUMERATION,

    /** Random sampling: the result is an estimate with a confidence interval. */
    MONTE_CARLO,
}

/** Two-sided confidence interval for a probability. */
data class ConfidenceInterval(val lower: Double, val upper: Double, val level: Double) {
    init {
        require(lower in 0.0..1.0 && upper in 0.0..1.0 && lower <= upper) { "Invalid interval [$lower, $upper]" }
        require(level > 0.0 && level < 1.0) { "level must be in (0,1)" }
    }

    val halfWidth: Double get() = (upper - lower) / 2.0

    companion object {
        private val Z_SCORES = mapOf(0.90 to 1.6448536, 0.95 to 1.9599640, 0.99 to 2.5758293)

        /**
         * Wilson score interval for a binomial proportion. Preferred over the normal approximation
         * because it stays inside [0,1] and behaves well for p near 0 or 1 (e.g. drawing dead).
         */
        fun wilson(successes: Double, trials: Long, level: Double = 0.95): ConfidenceInterval {
            require(trials > 0) { "trials must be > 0" }
            require(successes in 0.0..trials.toDouble()) { "successes must be in [0, trials]" }
            val z = Z_SCORES[level] ?: throw IllegalArgumentException("Supported levels: ${Z_SCORES.keys}")
            val n = trials.toDouble()
            val p = successes / n
            val z2 = z * z
            val denominator = 1.0 + z2 / n
            val center = (p + z2 / (2.0 * n)) / denominator
            val margin = z * sqrt(p * (1.0 - p) / n + z2 / (4.0 * n * n)) / denominator
            return ConfidenceInterval(
                lower = (center - margin).coerceIn(0.0, 1.0),
                upper = (center + margin).coerceIn(0.0, 1.0),
                level = level,
            )
        }
    }
}

data class PlayerEquity(
    val playerId: String,
    /** Probability of winning outright. */
    val win: Double,
    /** Probability of splitting the pot. */
    val tie: Double,
    /** Expected share of the pot: win + sum(tie share). */
    val equity: Double,
    /** Present only for Monte Carlo results. */
    val equityInterval: ConfidenceInterval? = null,
) {
    init {
        require(win in 0.0..1.0 && tie in 0.0..1.0 && equity in 0.0..1.0) { "Probabilities must be in [0,1]" }
    }
}

data class SimulationResult(
    val iterations: Long,
    /** Seed used, so a result can be reproduced exactly. */
    val seed: Long,
    val durationMillis: Long,
    val confidenceLevel: Double,
) {
    init {
        require(iterations > 0) { "iterations must be > 0" }
    }
}

data class EquityResult(
    val players: List<PlayerEquity>,
    val method: CalculationMethod,
    /** Non-null if and only if [method] is MONTE_CARLO. */
    val simulation: SimulationResult? = null,
) {
    init {
        require((method == CalculationMethod.MONTE_CARLO) == (simulation != null)) {
            "simulation details must be present exactly for Monte Carlo results"
        }
    }

    /** UI must label estimates differently from exact results. */
    val isEstimate: Boolean get() = method == CalculationMethod.MONTE_CARLO
}
