package com.pokervision.core.poker.holdem

import com.pokervision.core.model.analytics.CalculationMethod
import com.pokervision.core.model.analytics.ConfidenceInterval
import com.pokervision.core.model.analytics.EquityResult
import com.pokervision.core.model.analytics.PlayerEquity
import com.pokervision.core.model.analytics.SimulationResult
import com.pokervision.core.model.card.PlayingCard
import java.util.SplittableRandom
import kotlin.math.sqrt

data class HoldemEquityRequest(
    val heroCards: List<PlayingCard>,
    val board: List<PlayingCard>,
    /** Opponents still in the hand, each assumed to hold two unknown random cards. */
    val opponents: Int,
    /** Cards known to be out of play (folded and shown, burned and seen…). */
    val deadCards: List<PlayingCard> = emptyList(),
    /** Enumerate every outcome when there are at most this many; otherwise simulate. */
    val maxExactOutcomes: Long = DEFAULT_MAX_EXACT_OUTCOMES,
    val iterations: Int = DEFAULT_ITERATIONS,
    val seed: Long = DEFAULT_SEED,
) {
    init {
        require(heroCards.size == 2) { "Texas Hold'em hero needs exactly 2 cards" }
        require(board.size in listOf(0, 3, 4, 5)) { "Board must have 0, 3, 4 or 5 cards" }
        require(opponents in 1..9) { "opponents must be in 1..9" }
        require(iterations > 0) { "iterations must be > 0" }
        val all = heroCards + board + deadCards
        require(all.size == all.toSet().size) { "Duplicate card in request" }
        val needed = 2 * opponents + (5 - board.size)
        require(52 - all.size >= needed) { "Not enough cards left in the deck" }
    }

    companion object {
        const val DEFAULT_MAX_EXACT_OUTCOMES = 250_000L
        const val DEFAULT_ITERATIONS = 20_000
        const val DEFAULT_SEED = 0x5EEDL
        const val HERO_ID = "hero"
    }
}

/**
 * Hero's equity against random opponent hands. Exact enumeration (heads-up, when the number of
 * outcomes is small, e.g. on the turn or river) or seeded Monte Carlo with a 95% confidence interval.
 */
object HoldemEquityCalculator {

    fun calculate(request: HoldemEquityRequest): EquityResult {
        val start = System.nanoTime()
        val used = (request.heroCards + request.board + request.deadCards).map { it.index }.toSet()
        val deck = (0 until 52).filter { it !in used }.toIntArray()
        val missingBoard = 5 - request.board.size

        val exactOutcomes = if (request.opponents == 1) {
            choose(deck.size, 2) * choose(deck.size - 2, missingBoard)
        } else {
            Long.MAX_VALUE
        }
        return if (exactOutcomes <= request.maxExactOutcomes) {
            exact(request, deck, missingBoard)
        } else {
            monteCarlo(request, deck, missingBoard, start)
        }
    }

    private fun exact(request: HoldemEquityRequest, deck: IntArray, missingBoard: Int): EquityResult {
        val hero = IntArray(7)
        val opp = IntArray(7)
        val boardIdx = request.board.map { it.index }
        request.heroCards.forEachIndexed { i, c -> hero[i] = c.index }
        boardIdx.forEachIndexed { i, b ->
            hero[2 + i] = b
            opp[2 + i] = b
        }
        var wins = 0L
        var ties = 0L
        var total = 0L
        val n = deck.size
        for (a in 0 until n) for (b in a + 1 until n) {
            opp[0] = deck[a]
            opp[1] = deck[b]
            forEachCombination(deck, missingBoard, exclude1 = a, exclude2 = b) { extra ->
                for (k in 0 until missingBoard) {
                    hero[2 + boardIdx.size + k] = extra[k]
                    opp[2 + boardIdx.size + k] = extra[k]
                }
                val h = HandEvaluator.evaluateIndices(hero, 7)
                val o = HandEvaluator.evaluateIndices(opp, 7)
                when {
                    h > o -> wins++
                    h == o -> ties++
                }
                total++
            }
        }
        val win = wins.toDouble() / total
        val tie = ties.toDouble() / total
        return EquityResult(
            players = listOf(PlayerEquity(HoldemEquityRequest.HERO_ID, win, tie, win + tie / 2)),
            method = CalculationMethod.EXACT_ENUMERATION,
        )
    }

    private fun monteCarlo(request: HoldemEquityRequest, deck: IntArray, missingBoard: Int, startNanos: Long): EquityResult {
        val rng = SplittableRandom(request.seed)
        val pool = deck.copyOf()
        val opponents = request.opponents
        val draw = 2 * opponents + missingBoard
        val hero = IntArray(7)
        val opp = IntArray(7)
        val boardSize = request.board.size
        request.heroCards.forEachIndexed { i, c -> hero[i] = c.index }
        request.board.forEachIndexed { i, c ->
            hero[2 + i] = c.index
            opp[2 + i] = c.index
        }

        var wins = 0L
        var ties = 0L
        var shareSum = 0.0
        var shareSqSum = 0.0
        repeat(request.iterations) {
            // Partial Fisher–Yates: the first `draw` slots become a uniform random sample.
            for (i in 0 until draw) {
                val j = i + rng.nextInt(pool.size - i)
                val t = pool[i]
                pool[i] = pool[j]
                pool[j] = t
            }
            for (k in 0 until missingBoard) {
                val c = pool[2 * opponents + k]
                hero[2 + boardSize + k] = c
                opp[2 + boardSize + k] = c
            }
            val heroValue = HandEvaluator.evaluateIndices(hero, 7)
            var best = Int.MIN_VALUE
            var tiedAtBest = 0
            for (p in 0 until opponents) {
                opp[0] = pool[2 * p]
                opp[1] = pool[2 * p + 1]
                val v = HandEvaluator.evaluateIndices(opp, 7)
                if (v > best) {
                    best = v
                    tiedAtBest = 1
                } else if (v == best) {
                    tiedAtBest++
                }
            }
            val share = when {
                heroValue > best -> { wins++; 1.0 }
                heroValue == best -> { ties++; 1.0 / (tiedAtBest + 1) }
                else -> 0.0
            }
            shareSum += share
            shareSqSum += share * share
        }
        val n = request.iterations.toDouble()
        val mean = shareSum / n
        val variance = (shareSqSum / n - mean * mean).coerceAtLeast(0.0)
        val margin = Z95 * sqrt(variance / n)
        val interval = ConfidenceInterval((mean - margin).coerceIn(0.0, 1.0), (mean + margin).coerceIn(0.0, 1.0), 0.95)
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000
        return EquityResult(
            players = listOf(
                PlayerEquity(HoldemEquityRequest.HERO_ID, wins / n, ties / n, mean.coerceIn(0.0, 1.0), interval),
            ),
            method = CalculationMethod.MONTE_CARLO,
            simulation = SimulationResult(request.iterations.toLong(), request.seed, durationMs, 0.95),
        )
    }

    /** Calls [action] for every k-combination of [deck] skipping two indices. Reuses one buffer. */
    private inline fun forEachCombination(deck: IntArray, k: Int, exclude1: Int, exclude2: Int, action: (IntArray) -> Unit) {
        val candidates = IntArray(deck.size - 2)
        var m = 0
        for (i in deck.indices) if (i != exclude1 && i != exclude2) candidates[m++] = deck[i]
        val out = IntArray(k)
        if (k == 0) {
            action(out)
            return
        }
        val idx = IntArray(k) { it }
        while (true) {
            for (i in 0 until k) out[i] = candidates[idx[i]]
            action(out)
            var i = k - 1
            while (i >= 0 && idx[i] == m - k + i) i--
            if (i < 0) return
            idx[i]++
            for (j in i + 1 until k) idx[j] = idx[j - 1] + 1
        }
    }

    internal fun choose(n: Int, k: Int): Long {
        if (k < 0 || k > n) return 0
        var r = 1L
        for (i in 1..k) r = r * (n - k + i) / i
        return r
    }

    private const val Z95 = 1.959964
}
