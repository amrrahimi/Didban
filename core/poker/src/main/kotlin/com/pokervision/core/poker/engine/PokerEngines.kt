package com.pokervision.core.poker.engine

import com.pokervision.core.model.analytics.CalculationMethod
import com.pokervision.core.model.analytics.ConfidenceInterval
import com.pokervision.core.model.analytics.EquityResult
import com.pokervision.core.model.analytics.PlayerEquity
import com.pokervision.core.model.analytics.SimulationResult
import com.pokervision.core.model.card.DeckType
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.poker.holdem.HandCategory
import com.pokervision.core.poker.holdem.HandEvaluator
import com.pokervision.core.poker.holdem.HandRules
import com.pokervision.core.poker.holdem.HandValue
import com.pokervision.core.poker.holdem.HoldemEquityCalculator
import com.pokervision.core.poker.holdem.HoldemEquityRequest
import java.util.SplittableRandom
import kotlin.math.sqrt

/** What is known at a decision point, for any variant. */
data class EngineSituation(
    /** Hero's own cards: hole cards (board games), all own cards (Stud), the five-card hand (Draw). */
    val hero: List<PlayingCard>,
    /** Community cards; always empty for Stud and Draw. */
    val board: List<PlayingCard> = emptyList(),
    val opponents: Int,
    /** Cards known to be out of play: folded/shown cards, opponents' up-cards in Stud, own discards in Draw. */
    val dead: List<PlayingCard> = emptyList(),
)

enum class EngineIssue { HERO_CARD_COUNT, BOARD_SIZE, CARD_NOT_IN_DECK, DUPLICATE_CARD, TOO_MANY_OPPONENTS, NOT_ENOUGH_CARDS }

/** Five-card Draw: which cards to keep before the draw. */
data class DrawAdvice(val keep: List<PlayingCard>, val discard: List<PlayingCard>, val equity: Double, val standPatEquity: Double)

/**
 * Variant-specific rules for analysis. All engines are deterministic for a given seed. Opponents are
 * modelled as holding random cards (a learning-aid simplification shared by every variant).
 */
interface PokerEngine {
    val variant: PokerVariant
    val deck: DeckType

    /** Hero card counts that can be analysed (e.g. exactly 2 for Hold'em, 3..7 for Stud). */
    val heroCards: IntRange
    val boardSizes: Set<Int>
    val maxOpponents: Int
    val defaultIterations: Int

    fun validate(s: EngineSituation): EngineIssue? {
        if (s.hero.size !in heroCards) return EngineIssue.HERO_CARD_COUNT
        if (s.board.size !in boardSizes) return EngineIssue.BOARD_SIZE
        val all = s.hero + s.board + s.dead
        if (all.size != all.toSet().size) return EngineIssue.DUPLICATE_CARD
        if (all.any { !deck.contains(it) }) return EngineIssue.CARD_NOT_IN_DECK
        if (s.opponents !in 1..maxOpponents) return EngineIssue.TOO_MANY_OPPONENTS
        if (deck.size - all.size < cardsNeeded(s)) return EngineIssue.NOT_ENOUGH_CARDS
        return null
    }

    /** Unknown cards that must still be dealt to finish the hand for everyone. */
    fun cardsNeeded(s: EngineSituation): Int

    /** Hero's made hand from known cards, or null if too few cards are known. */
    fun currentHand(s: EngineSituation): HandCategory?

    fun equity(s: EngineSituation, iterations: Int = defaultIterations, seed: Long = DEFAULT_SEED): EquityResult

    companion object {
        const val DEFAULT_SEED = 0x5EEDL
        const val HERO_ID = "hero"
    }
}

object PokerEngines {
    fun forVariant(variant: PokerVariant, shortDeckTripsBeatStraight: Boolean = true): PokerEngine = when (variant) {
        PokerVariant.TEXAS_HOLDEM -> HoldemEngine
        PokerVariant.SHORT_DECK -> ShortDeckEngine(HandRules.shortDeck(shortDeckTripsBeatStraight))
        PokerVariant.OMAHA -> OmahaEngine(hiLo = false)
        PokerVariant.OMAHA_HI_LO -> OmahaEngine(hiLo = true)
        PokerVariant.SEVEN_CARD_STUD -> StudEngine
        PokerVariant.FIVE_CARD_DRAW -> DrawEngine
    }

    /** Five Card Draw: which cards to keep before the draw (see [DrawEngine.bestDraw]). */
    fun bestDraw(
        hero: List<PlayingCard>,
        opponents: Int,
        dead: List<PlayingCard> = emptyList(),
        iterations: Int = 1_500,
        seed: Long = PokerEngine.DEFAULT_SEED,
    ): DrawAdvice = DrawEngine.bestDraw(hero, opponents, dead, iterations, seed)
}

// ------------------------------------------------------------------ Hold'em

object HoldemEngine : PokerEngine {
    override val variant = PokerVariant.TEXAS_HOLDEM
    override val deck = DeckType.STANDARD_52
    override val heroCards = 2..2
    override val boardSizes = setOf(0, 3, 4, 5)
    override val maxOpponents = 9
    override val defaultIterations = 10_000

    override fun cardsNeeded(s: EngineSituation) = 2 * s.opponents + (5 - s.board.size)

    override fun currentHand(s: EngineSituation): HandCategory? =
        if (s.board.size >= 3) HandEvaluator.evaluate(s.hero + s.board).category else null

    /** Delegates to the Hold'em calculator, which also does exact enumeration heads-up on turn/river. */
    override fun equity(s: EngineSituation, iterations: Int, seed: Long): EquityResult =
        HoldemEquityCalculator.calculate(HoldemEquityRequest(s.hero, s.board, s.opponents, s.dead, iterations = iterations, seed = seed))
}

// ------------------------------------------------------------------ Simulation core

/**
 * Monte Carlo core shared by the non-Hold'em engines: deals the unknown cards uniformly at random,
 * scores every player and accumulates hero's share of the pot (split pots and hi/lo halves included).
 */
internal abstract class SimulatedEngine : PokerEngine {
    /** Cards each player has at showdown. */
    abstract val holeAtShowdown: Int

    /** Community cards at showdown (0 for Stud/Draw). */
    abstract val boardAtShowdown: Int

    /** High-hand strength: larger is better. */
    abstract fun high(hole: IntArray, board: IntArray): Int

    /** Low-hand strength for split-pot games: smaller is better, [NO_LOW] if the hand does not qualify. */
    open fun low(hole: IntArray, board: IntArray): Int = NO_LOW
    open val splitsLow: Boolean = false

    /** True when equity may be asked for fewer own cards than [heroCards] allows (missing ones are drawn). */
    protected open val simulatesPartialHero: Boolean = false

    override fun cardsNeeded(s: EngineSituation) =
        (holeAtShowdown - s.hero.size) + opponentsCards(s) + (boardAtShowdown - s.board.size)

    protected open fun opponentsCards(s: EngineSituation) = holeAtShowdown * s.opponents

    override fun equity(s: EngineSituation, iterations: Int, seed: Long): EquityResult {
        val issue = validate(s)
        val partialOk = issue == EngineIssue.HERO_CARD_COUNT && simulatesPartialHero && s.hero.size <= holeAtShowdown &&
            validate(s.copy(hero = emptyList())).let { it == null || it == EngineIssue.HERO_CARD_COUNT }
        require(issue == null || partialOk) { "Invalid situation: $issue" }
        val start = System.nanoTime()
        val used = (s.hero + s.board + s.dead).map { it.index }.toSet()
        val pool = deck.cards.map { it.index }.filter { it !in used }.toIntArray()
        val heroMissing = holeAtShowdown - s.hero.size
        val boardMissing = boardAtShowdown - s.board.size
        val draw = heroMissing + boardMissing + holeAtShowdown * s.opponents
        val rng = SplittableRandom(seed)

        val hero = IntArray(holeAtShowdown)
        s.hero.forEachIndexed { i, c -> hero[i] = c.index }
        val board = IntArray(boardAtShowdown)
        s.board.forEachIndexed { i, c -> board[i] = c.index }
        val opp = Array(s.opponents) { IntArray(holeAtShowdown) }

        var wins = 0L
        var ties = 0L
        var sum = 0.0
        var sumSq = 0.0
        repeat(iterations) {
            for (i in 0 until draw) {
                val j = i + rng.nextInt(pool.size - i)
                val t = pool[i]; pool[i] = pool[j]; pool[j] = t
            }
            var k = 0
            for (i in 0 until heroMissing) hero[s.hero.size + i] = pool[k++]
            for (i in 0 until boardMissing) board[s.board.size + i] = pool[k++]
            for (p in opp) for (i in 0 until holeAtShowdown) p[i] = pool[k++]

            val share = heroShare(hero, board, opp)
            if (share >= 1.0) wins++ else if (share > 0.0) ties++
            sum += share
            sumSq += share * share
        }
        val n = iterations.toDouble()
        val mean = sum / n
        val margin = Z95 * sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0) / n)
        return EquityResult(
            players = listOf(
                PlayerEquity(
                    PokerEngine.HERO_ID, wins / n, ties / n, mean.coerceIn(0.0, 1.0),
                    ConfidenceInterval((mean - margin).coerceIn(0.0, 1.0), (mean + margin).coerceIn(0.0, 1.0), 0.95),
                ),
            ),
            method = CalculationMethod.MONTE_CARLO,
            simulation = SimulationResult(iterations.toLong(), seed, (System.nanoTime() - start) / 1_000_000, 0.95),
        )
    }

    /** Hero's fraction of the pot for one complete deal. */
    internal fun heroShare(hero: IntArray, board: IntArray, opponents: Array<IntArray>): Double {
        val heroHigh = high(hero, board)
        var bestHigh = heroHigh
        var highWinners = 1
        var heroHasHigh = true
        for (p in opponents) {
            val v = high(p, board)
            when {
                v > bestHigh -> { bestHigh = v; highWinners = 1; heroHasHigh = false }
                v == bestHigh -> highWinners++
            }
        }
        val highShare = if (heroHasHigh) 1.0 / highWinners else 0.0
        if (!splitsLow) return highShare

        val heroLow = low(hero, board)
        var bestLow = heroLow
        var lowWinners = if (heroLow == NO_LOW) 0 else 1
        var heroHasLow = heroLow != NO_LOW
        for (p in opponents) {
            val v = low(p, board)
            if (v == NO_LOW) continue
            when {
                v < bestLow -> { bestLow = v; lowWinners = 1; heroHasLow = false }
                v == bestLow -> lowWinners++
            }
        }
        if (bestLow == NO_LOW) return highShare // no qualifying low: high hand scoops
        val lowShare = if (heroHasLow) 1.0 / lowWinners else 0.0
        return 0.5 * highShare + 0.5 * lowShare
    }

    companion object {
        const val NO_LOW = Int.MAX_VALUE
        private const val Z95 = 1.959964
    }
}

// ------------------------------------------------------------------ Short Deck

internal class ShortDeckEngine(private val rules: HandRules) : SimulatedEngine() {
    override val variant = PokerVariant.SHORT_DECK
    override val deck = DeckType.SHORT_DECK_36
    override val heroCards = 2..2
    override val boardSizes = setOf(0, 3, 4, 5)
    override val maxOpponents = 8
    override val defaultIterations = 10_000
    override val holeAtShowdown = 2
    override val boardAtShowdown = 5

    private val seven = IntArray(7)

    override fun high(hole: IntArray, board: IntArray): Int {
        seven[0] = hole[0]; seven[1] = hole[1]
        for (i in 0 until 5) seven[2 + i] = board[i]
        return HandEvaluator.evaluateIndices(seven, 7, rules)
    }

    override fun currentHand(s: EngineSituation): HandCategory? =
        if (s.board.size >= 3) HandEvaluator.evaluate(s.hero + s.board, rules).category else null
}

// ------------------------------------------------------------------ Omaha / Omaha Hi-Lo

/** Omaha: exactly two of the four hole cards and exactly three board cards. Hi-Lo adds an 8-or-better low. */
internal class OmahaEngine(private val hiLo: Boolean) : SimulatedEngine() {
    override val variant = if (hiLo) PokerVariant.OMAHA_HI_LO else PokerVariant.OMAHA
    override val deck = DeckType.STANDARD_52
    override val heroCards = 4..4
    override val boardSizes = setOf(0, 3, 4, 5)
    override val maxOpponents = 9
    override val defaultIterations = 4_000
    override val holeAtShowdown = 4
    override val boardAtShowdown = 5
    override val splitsLow = hiLo

    private val five = IntArray(5)

    override fun high(hole: IntArray, board: IntArray): Int = bestHigh(hole, board, board.size)

    override fun low(hole: IntArray, board: IntArray): Int = OmahaMath.bestLow(hole, board, board.size)

    private fun bestHigh(hole: IntArray, board: IntArray, boardSize: Int): Int {
        var best = Int.MIN_VALUE
        for ((a, b) in OmahaMath.HOLE_PAIRS) for (t in OmahaMath.boardTriples(boardSize)) {
            five[0] = hole[a]; five[1] = hole[b]
            five[2] = board[t[0]]; five[3] = board[t[1]]; five[4] = board[t[2]]
            val v = HandEvaluator.evaluateIndices(five, 5)
            if (v > best) best = v
        }
        return best
    }

    override fun currentHand(s: EngineSituation): HandCategory? {
        if (s.board.size < 3) return null
        val hole = IntArray(4) { s.hero[it].index }
        val board = IntArray(s.board.size) { s.board[it].index }
        return HandValue(bestHigh(hole, board, board.size)).category
    }
}

object OmahaMath {
    internal val HOLE_PAIRS: List<Pair<Int, Int>> = listOf(0 to 1, 0 to 2, 0 to 3, 1 to 2, 1 to 3, 2 to 3)
    private val TRIPLES: Map<Int, List<IntArray>> = (3..5).associateWith { n ->
        buildList { for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) add(intArrayOf(a, b, c)) }
    }

    internal fun boardTriples(boardSize: Int): List<IntArray> = TRIPLES.getValue(boardSize)

    /** Low rank value: ace = 1, deuce = 2 … eight = 8; anything higher cannot make a low. */
    private fun lowRank(card: Int): Int {
        val r = card shr 2 // 0 = deuce … 12 = ace
        return if (r == 12) 1 else r + 2
    }

    /**
     * Best 8-or-better low using exactly two hole and three board cards: five different ranks, all
     * eight or lower (aces low; straights and flushes don't count). Encoded from the highest card down
     * in base 16, so a smaller value is a better low. Returns Int.MAX_VALUE when no low is possible.
     */
    fun bestLow(hole: IntArray, board: IntArray, boardSize: Int): Int {
        var best = Int.MAX_VALUE
        val ranks = IntArray(5)
        for ((a, b) in HOLE_PAIRS) for (t in boardTriples(boardSize)) {
            ranks[0] = lowRank(hole[a]); ranks[1] = lowRank(hole[b])
            ranks[2] = lowRank(board[t[0]]); ranks[3] = lowRank(board[t[1]]); ranks[4] = lowRank(board[t[2]])
            var mask = 0
            var ok = true
            for (r in ranks) {
                if (r > 8 || mask and (1 shl r) != 0) { ok = false; break }
                mask = mask or (1 shl r)
            }
            if (!ok) continue
            var v = 0
            for (r in 8 downTo 1) if (mask and (1 shl r) != 0) v = v * 16 + r
            if (v < best) best = v
        }
        return best
    }
}

// ------------------------------------------------------------------ Seven Card Stud

/**
 * Seven Card Stud: hero holds 3..7 own cards; opponents' visible up-cards are passed as dead cards
 * (they are out of the deck, but are not attributed to a particular opponent in this model).
 */
internal object StudEngine : SimulatedEngine() {
    override val variant = PokerVariant.SEVEN_CARD_STUD
    override val deck = DeckType.STANDARD_52
    override val heroCards = 3..7
    override val boardSizes = setOf(0)
    override val maxOpponents = 7
    override val defaultIterations = 6_000
    override val holeAtShowdown = 7
    override val boardAtShowdown = 0

    override fun high(hole: IntArray, board: IntArray): Int = HandEvaluator.evaluateIndices(hole, 7)

    override fun currentHand(s: EngineSituation): HandCategory? =
        if (s.hero.size >= 5) HandEvaluator.evaluate(s.hero).category else null
}

// ------------------------------------------------------------------ Five Card Draw

/**
 * Five Card Draw. Hero's five cards (or the cards hero keeps, missing ones drawn at random) against
 * opponents who are dealt five random cards and then draw with a simple standard rule
 * ([OpponentDraw]). This is far more realistic than opponents standing pat with random hands.
 */
internal object DrawEngine : SimulatedEngine() {
    override val variant = PokerVariant.FIVE_CARD_DRAW
    override val deck = DeckType.STANDARD_52
    override val heroCards = 5..5
    override val boardSizes = setOf(0)
    override val maxOpponents = 5
    override val defaultIterations = 6_000
    override val holeAtShowdown = 5
    override val boardAtShowdown = 0

    /** While comparing draw options, kept hands of 0..4 cards are completed at random. */
    override val simulatesPartialHero = true

    /** Opponents may replace up to 5 cards each. */
    override fun opponentsCards(s: EngineSituation) = 0

    override fun high(hole: IntArray, board: IntArray): Int = HandEvaluator.evaluateIndices(hole, 5)

    override fun currentHand(s: EngineSituation): HandCategory = HandEvaluator.evaluate(s.hero).category

    override fun equity(s: EngineSituation, iterations: Int, seed: Long): EquityResult {
        val issue = validate(s)
        require(issue == null || (issue == EngineIssue.HERO_CARD_COUNT && s.hero.size <= 5)) { "Invalid situation: $issue" }
        val start = System.nanoTime()
        val used = (s.hero + s.dead).map { it.index }.toSet()
        val pool = deck.cards.map { it.index }.filter { it !in used }.toIntArray()
        val rng = SplittableRandom(seed)
        val hero = IntArray(5)
        s.hero.forEachIndexed { i, c -> hero[i] = c.index }
        val heroMissing = 5 - s.hero.size
        val opp = Array(s.opponents) { IntArray(5) }

        var wins = 0L
        var ties = 0L
        var sum = 0.0
        var sumSq = 0.0
        repeat(iterations) {
            // Shuffle enough cards for: hero's draw, opponents' deals and opponents' draws.
            val need = minOf(pool.size, heroMissing + 10 * s.opponents)
            for (i in 0 until need) {
                val j = i + rng.nextInt(pool.size - i)
                val t = pool[i]; pool[i] = pool[j]; pool[j] = t
            }
            var k = 0
            for (i in 0 until heroMissing) hero[s.hero.size + i] = pool[k++]
            for (p in opp) for (i in 0 until 5) p[i] = pool[k++]
            for (p in opp) {
                val keepMask = OpponentDraw.keepMask(p)
                for (i in 0 until 5) if (keepMask and (1 shl i) == 0 && k < need) p[i] = pool[k++]
            }
            val share = heroShare(hero, IntArray(0), opp)
            if (share >= 1.0) wins++ else if (share > 0.0) ties++
            sum += share
            sumSq += share * share
        }
        val n = iterations.toDouble()
        val mean = sum / n
        val margin = 1.959964 * sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0) / n)
        return EquityResult(
            players = listOf(
                PlayerEquity(
                    PokerEngine.HERO_ID, wins / n, ties / n, mean.coerceIn(0.0, 1.0),
                    ConfidenceInterval((mean - margin).coerceIn(0.0, 1.0), (mean + margin).coerceIn(0.0, 1.0), 0.95),
                ),
            ),
            method = CalculationMethod.MONTE_CARLO,
            simulation = SimulationResult(iterations.toLong(), seed, (System.nanoTime() - start) / 1_000_000, 0.95),
        )
    }

    /**
     * Tries all 32 ways to keep cards and returns the one with the highest equity. Every option is
     * simulated with the same random numbers so they are compared fairly.
     */
    fun bestDraw(hero: List<PlayingCard>, opponents: Int, dead: List<PlayingCard>, iterations: Int, seed: Long): DrawAdvice {
        require(hero.size == 5) { "Draw needs exactly 5 cards" }
        var bestMask = 0b11111
        var bestEquity = -1.0
        var standPat = 0.0
        for (mask in 0 until 32) {
            val keep = hero.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            val discarded = hero - keep.toSet()
            val e = equity(EngineSituation(keep, emptyList(), opponents, dead + discarded), iterations, seed).players.first().equity
            if (mask == 0b11111) standPat = e
            if (e > bestEquity) {
                bestEquity = e
                bestMask = mask
            }
        }
        val keep = hero.filterIndexed { i, _ -> bestMask and (1 shl i) != 0 }
        return DrawAdvice(keep, hero - keep.toSet(), bestEquity, standPat)
    }
}

/**
 * A simple, common drawing rule for simulated opponents: stand pat with a straight or better; keep
 * trips / two pair / a pair; keep four to a flush or an open-ended straight; otherwise keep the
 * highest card and draw four.
 */
object OpponentDraw {
    /** Bit i set = keep card i. */
    fun keepMask(cards: IntArray): Int {
        val v = HandEvaluator.evaluateIndices(cards, 5)
        val category = HandValue(v).category
        if (category >= HandCategory.STRAIGHT) return 0b11111
        val counts = IntArray(13)
        for (c in cards) counts[c shr 2]++
        if (category >= HandCategory.ONE_PAIR) {
            var mask = 0
            cards.forEachIndexed { i, c -> if (counts[c shr 2] >= 2) mask = mask or (1 shl i) }
            return mask
        }
        val suits = IntArray(4)
        for (c in cards) suits[c and 3]++
        val flushSuit = suits.indexOfFirst { it == 4 }
        if (flushSuit >= 0) {
            var mask = 0
            cards.forEachIndexed { i, c -> if (c and 3 == flushSuit) mask = mask or (1 shl i) }
            return mask
        }
        // Open-ended four-card straight (no aces at the ends for simplicity).
        var rankMask = 0
        for (c in cards) rankMask = rankMask or (1 shl (c shr 2))
        for (low in 0..8) {
            val run = 0xF shl low
            if (rankMask and run == run && low + 3 < 12) {
                var mask = 0
                cards.forEachIndexed { i, c -> if ((c shr 2) in low..low + 3) mask = mask or (1 shl i) }
                return mask
            }
        }
        val highest = cards.indices.maxBy { cards[it] shr 2 }
        return 1 shl highest
    }
}
