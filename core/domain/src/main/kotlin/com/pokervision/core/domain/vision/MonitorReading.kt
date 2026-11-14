package com.pokervision.core.domain.vision

import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.detection.BoundingBox

/**
 * Temporal confirmation: a card counts only when it has collected [requiredWeight] evidence over the
 * last [window] analysed frames (score >= [minScore]). A frame where both corners of the card were
 * read as the same card weighs 2, a single corner 1. It also drops a card only after it has been
 * missing for most of the window, so a single missed frame does not make cards flicker.
 */
class CardConfirmer(
    private val window: Int = DEFAULT_WINDOW,
    private val requiredWeight: Int = DEFAULT_REQUIRED_WEIGHT,
    private val minScore: Float = DEFAULT_MIN_SCORE,
) {
    init {
        require(window >= 1) { "window must be >= 1" }
        require(requiredWeight in 1..2 * window) { "requiredWeight must be in 1..2*window" }
        require(minScore in 0f..1f) { "minScore must be in [0,1]" }
    }

    private class Hit(val score: Float, val weight: Int)

    private val history = ArrayDeque<Map<PlayingCard, Hit>>()

    data class State(
        /** Cards seen consistently, with their mean score over the frames they appeared in. */
        val confirmed: Map<PlayingCard, Float>,
        /** Cards seen recently but not consistently enough yet. */
        val tentative: Map<PlayingCard, Float>,
    )

    fun update(sightings: List<CardSighting>): State {
        history.addLast(
            sightings.filter { it.score >= minScore }
                .associate { it.card to Hit(it.score, if (it.pipCount >= 2) 2 else 1) },
        )
        while (history.size > window) history.removeFirst()
        val hits = HashMap<PlayingCard, MutableList<Hit>>()
        history.forEach { frame -> frame.forEach { (card, hit) -> hits.getOrPut(card) { mutableListOf() } += hit } }
        val confirmed = HashMap<PlayingCard, Float>()
        val tentative = HashMap<PlayingCard, Float>()
        hits.forEach { (card, list) ->
            val mean = list.map { it.score }.average().toFloat()
            (if (list.sumOf { it.weight } >= requiredWeight) confirmed else tentative)[card] = mean
        }
        return State(confirmed, tentative)
    }

    fun reset() = history.clear()

    companion object {
        const val DEFAULT_WINDOW = 8
        const val DEFAULT_REQUIRED_WEIGHT = 4
        const val DEFAULT_MIN_SCORE = 0.5f
    }
}

/**
 * Where the game draws the player's cards and the board, as normalized boxes of the camera frame.
 * Set once by the user on the camera preview (the phone stays on its stand).
 */
data class MonitorRegions(val mine: BoundingBox, val board: BoundingBox) {
    companion object {
        /** Typical poker table layout: board in the middle, player's cards below it. */
        val DEFAULT = MonitorRegions(
            mine = BoundingBox(0.25f, 0.62f, 0.75f, 0.88f),
            board = BoundingBox(0.10f, 0.32f, 0.90f, 0.60f),
        )
    }
}

/** What is confirmed on screen right now. */
data class MonitorReading(
    val mine: List<PlayingCard>,
    val board: List<PlayingCard>,
    /** Seen but not yet confirmed (shown in yellow). */
    val tentative: Set<PlayingCard>,
)

/**
 * Reads the game continuously. Unlike photographing real cards, the screen always shows the
 * complete current hand, so the reading *is* the state: cards appear when dealt and disappear when
 * the next hand starts, without any button. Each area has its own confirmer. A card confirmed in
 * both areas (overlapping boxes) is kept where it was seen more strongly.
 */
class MonitorReader(
    window: Int = MONITOR_WINDOW,
    requiredWeight: Int = MONITOR_REQUIRED_WEIGHT,
    minScore: Float = CardConfirmer.DEFAULT_MIN_SCORE,
) {
    private val mineConfirmer = CardConfirmer(window, requiredWeight, minScore)
    private val boardConfirmer = CardConfirmer(window, requiredWeight, minScore)

    fun update(mineSightings: List<CardSighting>, boardSightings: List<CardSighting>): MonitorReading {
        val m = mineConfirmer.update(mineSightings)
        val b = boardConfirmer.update(boardSightings)
        val mine = m.confirmed.filter { (card, score) -> score >= (b.confirmed[card] ?: -1f) }.keys
        val board = b.confirmed.keys - mine
        val xMine = mineSightings.associate { it.card to it.box.left }
        val xBoard = boardSightings.associate { it.card to it.box.left }
        return MonitorReading(
            mine = mine.sortedBy { xMine[it] ?: 0f },
            board = board.sortedBy { xBoard[it] ?: 0f },
            tentative = (m.tentative.keys + b.tentative.keys) - mine - board,
        )
    }

    fun reset() {
        mineConfirmer.reset()
        boardConfirmer.reset()
    }

    companion object {
        /** Detection runs about once a second on a monitor (two zoomed areas per frame). */
        const val MONITOR_WINDOW = 5
        const val MONITOR_REQUIRED_WEIGHT = 3
    }
}
