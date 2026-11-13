package com.pokervision.core.domain.vision

import com.pokervision.core.model.card.PlayingCard

/**
 * Temporal confirmation: a card counts only when it was detected (score >= [minScore]) in at least
 * [requiredHits] of the last [window] analysed frames. Single-frame false positives — the model's
 * precision is far from perfect — therefore never reach the poker calculation.
 */
class CardConfirmer(
    private val window: Int = DEFAULT_WINDOW,
    private val requiredHits: Int = DEFAULT_REQUIRED_HITS,
    private val minScore: Float = DEFAULT_MIN_SCORE,
) {
    init {
        require(window >= 1) { "window must be >= 1" }
        require(requiredHits in 1..window) { "requiredHits must be in 1..window" }
        require(minScore in 0f..1f) { "minScore must be in [0,1]" }
    }

    private val history = ArrayDeque<Map<PlayingCard, Float>>()

    data class State(
        /** Cards seen consistently, with their mean score over the frames they appeared in. */
        val confirmed: Map<PlayingCard, Float>,
        /** Cards seen recently but not consistently enough yet. */
        val tentative: Map<PlayingCard, Float>,
    )

    fun update(sightings: List<CardSighting>): State {
        history.addLast(sightings.filter { it.score >= minScore }.associate { it.card to it.score })
        while (history.size > window) history.removeFirst()
        val hits = HashMap<PlayingCard, MutableList<Float>>()
        history.forEach { frame -> frame.forEach { (card, score) -> hits.getOrPut(card) { mutableListOf() } += score } }
        val confirmed = HashMap<PlayingCard, Float>()
        val tentative = HashMap<PlayingCard, Float>()
        hits.forEach { (card, scores) ->
            (if (scores.size >= requiredHits) confirmed else tentative)[card] = scores.average().toFloat()
        }
        return State(confirmed, tentative)
    }

    fun reset() = history.clear()

    companion object {
        /** At ~3-5 analysed frames per second this is about 1.5-2 seconds of evidence. */
        const val DEFAULT_WINDOW = 6
        const val DEFAULT_REQUIRED_HITS = 3
        const val DEFAULT_MIN_SCORE = 0.5f
    }
}

enum class LivePhase {
    /** Waiting for the player to show their two hole cards to the camera. */
    SCAN_HERO,

    /** Hole cards known; new confirmed cards are treated as board cards. */
    WATCH_BOARD,
}

data class LiveHand(
    val hero: List<PlayingCard> = emptyList(),
    val board: List<PlayingCard> = emptyList(),
    val opponents: Int = 1,
    val phase: LivePhase = LivePhase.SCAN_HERO,
    /** Cards the user removed as misdetections; never auto-added again during this hand. */
    val rejected: Set<PlayingCard> = emptySet(),
    /** More than two unknown cards visible while scanning hole cards: cannot tell which are the player's. */
    val tooManyCardsForHero: Boolean = false,
    val handNumber: Int = 1,
) {
    val boardReady: Boolean get() = board.size == 0 || board.size >= 3
}

/**
 * Turns confirmed cards into a hand: the first two confirmed cards are the player's hole cards
 * (shown to the camera on purpose), every later new card is a board card. Pure state transitions.
 */
object LiveHandAssembler {

    fun onConfirmed(hand: LiveHand, confirmed: Set<PlayingCard>): LiveHand {
        val known = (hand.hero + hand.board).toSet()
        val fresh = confirmed.filter { it !in known && it !in hand.rejected }
        return when (hand.phase) {
            LivePhase.SCAN_HERO -> {
                val room = 2 - hand.hero.size
                when {
                    fresh.isEmpty() -> hand.copy(tooManyCardsForHero = false)
                    fresh.size > room -> hand.copy(tooManyCardsForHero = true)
                    else -> {
                        val hero = hand.hero + fresh.sortedBy { it.index }
                        hand.copy(
                            hero = hero,
                            phase = if (hero.size == 2) LivePhase.WATCH_BOARD else LivePhase.SCAN_HERO,
                            tooManyCardsForHero = false,
                        )
                    }
                }
            }
            LivePhase.WATCH_BOARD -> {
                val room = 5 - hand.board.size
                if (fresh.isEmpty() || room == 0) hand else hand.copy(board = hand.board + fresh.sortedBy { it.index }.take(room))
            }
        }
    }

    /** User says a card is wrong: remove it and ignore it for the rest of the hand. */
    fun reject(hand: LiveHand, card: PlayingCard): LiveHand {
        val hero = hand.hero - card
        return hand.copy(
            hero = hero,
            board = hand.board - card,
            rejected = hand.rejected + card,
            phase = if (hero.size < 2) LivePhase.SCAN_HERO else hand.phase,
        )
    }

    fun rescanHero(hand: LiveHand) = hand.copy(hero = emptyList(), phase = LivePhase.SCAN_HERO, tooManyCardsForHero = false)

    fun setOpponents(hand: LiveHand, n: Int) = hand.copy(opponents = n.coerceIn(1, 9))

    fun newHand(hand: LiveHand) = LiveHand(opponents = hand.opponents, handNumber = hand.handNumber + 1)
}
