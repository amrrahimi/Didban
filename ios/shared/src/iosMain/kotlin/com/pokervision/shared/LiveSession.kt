package com.pokervision.shared

import com.pokervision.core.domain.vision.GlyphFeature
import com.pokervision.core.domain.vision.ScanTarget
import com.pokervision.core.domain.vision.TableRules
import com.pokervision.core.domain.vision.TableState
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.card.Suit
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.poker.advice.LiveAction
import com.pokervision.core.poker.analysis.HandAnalyzer
import com.pokervision.core.poker.analysis.HandQuery
import com.pokervision.core.poker.engine.EngineIssue
import com.pokervision.core.poker.engine.PokerEngines
import platform.Foundation.NSData

/** A card found on the scanned photo (same meaning as ScanBox in the Android app). */
class PhotoBox internal constructor(
    internal val card: PlayingCard,
    val box: BoxView,
    val accepted: Boolean,
    internal val features: List<GlyphFeature>,
    val fromMemory: Boolean,
    val byUser: Boolean,
    internal val learned: Boolean,
) {
    val view: CardView get() = card.view()

    internal fun copy(
        card: PlayingCard = this.card,
        accepted: Boolean = this.accepted,
        byUser: Boolean = this.byUser,
        learned: Boolean = this.learned,
    ) = PhotoBox(card, box, accepted, features, fromMemory, byUser, learned)
}

class VariantView(val id: String, val usesBoard: Boolean, val tableScan: Boolean, val isDraw: Boolean, val isStud: Boolean)

/** Everything the advice panel shows. */
class AdviceView internal constructor(
    /** null when ready; otherwise one of: need_mine, need_board, deck, duplicate, opponents. */
    val issue: String?,
    val needMin: Int,
    val needMax: Int,
    /** bet, call or fold. */
    val action: String,
    /** -1 = any bet can be called. */
    val maxCallPercent: Int,
    val equityPercent: Int,
    /** -1 = exact. */
    val plusMinusPercent: Int,
    /** high_card, one_pair, ... or empty. */
    val handKey: String,
    val drawKeep: List<CardView>?,
    val drawDiscardCount: Int,
    val hiLo: Boolean,
)

/** One query, safe to analyse on a background thread. */
class AdviceRequest internal constructor(internal val query: HandQuery)

/**
 * The live table on the iPhone: cards on the table, the latest scanned photo, corrections that
 * teach the card memory, and the advice. Call from the main thread (except [Advisor.analyze]).
 */
class LiveSession(val memory: MemoryStore, variantId: String) {
    private var table = TableRules.setVariant(TableState(), variantOf(variantId))
    private var resultTarget: ScanTarget? = null
    private var reading: ScanReading? = null
    private var photoAspect = 1f

    var boxes: List<PhotoBox> = emptyList()
        private set
    var pending: List<CardView> = emptyList()
        private set
    private var pendingCards: List<PlayingCard> = emptyList()

    // ---------------- table

    val variant: VariantView get() = variantView(table.variant)
    val mine: List<CardView> get() = table.mine.map { it.view() }
    val board: List<CardView> get() = table.board.map { it.view() }
    val seen: List<CardView> get() = table.seen.map { it.view() }
    val opponents: Int get() = table.opponents
    val maxOpponents: Int get() = TableRules.maxOpponents(table.variant)
    val mineCapacity: Int get() = TableRules.mineCapacity(table.variant)
    val boardCapacity: Int get() = TableRules.BOARD_CAPACITY
    val drawDone: Boolean get() = table.drawDone
    val hasResult: Boolean get() = resultTarget != null

    /** Cards that can be chosen in the picker for this variant (rows by suit: ♠ ♥ ♦ ♣, high to low). */
    fun deckRows(): List<List<CardView>> {
        val deck = PokerEngines.forVariant(table.variant).deck
        return listOf(Suit.SPADES, Suit.HEARTS, Suit.DIAMONDS, Suit.CLUBS).map { suit ->
            deck.cards.filter { it.suit == suit }.sortedByDescending { it.rank.value }.map { it.view() }
        }
    }

    fun usedCodes(): List<String> = table.allCards.map { it.code }

    /** Free places in the row the picker fills ("mine" or "table"). */
    fun freeSlots(target: String): Int = when {
        target == MINE -> mineCapacity - table.mine.size
        TableRules.usesBoard(table.variant) -> boardCapacity - table.board.size
        else -> TableRules.SEEN_CAPACITY - table.seen.size
    }.coerceAtLeast(0)

    fun setVariant(id: String) {
        commitLearning()
        clearResult()
        table = TableRules.setVariant(table, variantOf(id))
    }

    fun setOpponents(n: Int) {
        table = TableRules.setOpponents(table, n)
    }

    fun setDrawDone(done: Boolean) {
        table = table.copy(drawDone = done)
    }

    fun newHand() {
        commitLearning()
        clearResult()
        table = TableRules.newHand(table)
    }

    fun addCards(codes: List<String>, target: String) {
        val t = targetOf(target)
        table = codes.mapNotNull { PlayingCard.parseOrNull(it) }.fold(table) { s, c -> TableRules.add(s, c, t) }
    }

    fun remove(code: String) {
        val card = PlayingCard.parseOrNull(code) ?: return
        table = TableRules.remove(table, card)
    }

    // ---------------- photo result

    /** A photo was read: confident cards go on the table, the rest wait for the user. */
    fun applyReading(target: String, scan: ScanReading, photoWidth: Int, photoHeight: Int) {
        val t = targetOf(target)
        val outcome = TableRules.applyScan(table, t, scan.cards.filter { it.confident }.map { it.card })
        table = outcome.state
        val placed = outcome.added.toSet()
        resultTarget = t
        reading = scan
        photoAspect = photoWidth.toFloat() / photoHeight
        boxes = scan.cards.map { PhotoBox(it.card, it.box, it.card in placed, it.features, it.fromMemory, byUser = false, learned = false) }
        pendingCards = scan.cards.map { it.card }.filter { it !in placed && it !in outcome.state.allCards }
        pending = pendingCards.map { it.view() }
    }

    fun acceptPending(code: String) {
        val target = resultTarget ?: return
        val card = PlayingCard.parseOrNull(code) ?: return
        val before = table
        table = TableRules.add(table, card, target)
        if (table == before) return
        boxes.firstOrNull { it.card == card }?.let { memory.learn(card, it.features) }
        boxes = boxes.map { if (it.card == card) it.copy(accepted = true, learned = true) else it }
        setPending(pendingCards - card)
    }

    /** Box [index] actually shows [code]: fixes the table and teaches the memory. */
    fun correctBox(index: Int, code: String) {
        val target = resultTarget ?: return
        val old = boxes.getOrNull(index) ?: return
        val card = PlayingCard.parseOrNull(code) ?: return
        var t = table
        if (old.card != card && old.accepted) t = TableRules.remove(t, old.card)
        if (card in t.allCards) t = TableRules.remove(t, card)
        t = TableRules.add(t, card, target)
        table = t
        if (old.card == card) memory.learn(card, old.features) else memory.correct(old.card, card, old.features)
        boxes = boxes.mapIndexed { i, b ->
            when {
                i == index -> b.copy(card = card, accepted = card in t.allCards, byUser = true, learned = true)
                b.card == card -> b.copy(accepted = false)
                else -> b
            }
        }
        setPending((pendingCards - card - old.card).filter { it !in t.allCards })
    }

    /** Box [index] is not a card. */
    fun dismissBox(index: Int) {
        val old = boxes.getOrNull(index) ?: return
        if (old.accepted) table = TableRules.remove(table, old.card)
        memory.unlearn(old.card, old.features)
        boxes = boxes.filterIndexed { i, _ -> i != index }
        setPending(pendingCards - old.card)
    }

    /** Box to cut out for a tap at (x, y) on the photo, before [teachAt]. */
    fun tapBox(x: Float, y: Float): BoxView = PhotoScan.tapBox(reading, x, y, photoAspect)

    /** The user tapped a missed card at [box] and chose [code]; [rgbx] = its pixels at [GlyphSize]. */
    fun teachAt(box: BoxView, code: String, rgbx: NSData?) {
        val target = resultTarget ?: return
        val card = PlayingCard.parseOrNull(code) ?: return
        var t = table
        if (card in t.allCards) t = TableRules.remove(t, card)
        t = TableRules.add(t, card, target)
        table = t
        val features = listOfNotNull(rgbx?.let { PhotoScan.glyph(it) })
        memory.learn(card, features)
        boxes = boxes.filter { it.card != card } +
            PhotoBox(card, box, card in t.allCards, features, fromMemory = false, byUser = true, learned = true)
        setPending(pendingCards - card)
    }

    /** Back to the camera: cards left as read (and still on the table) are learned as correct. */
    fun closeResult() {
        commitLearning()
        clearResult()
    }

    private fun commitLearning() {
        if (resultTarget == null) return
        val onTable = table.allCards
        val toLearn = boxes.filter { it.accepted && !it.learned && it.card in onTable && it.features.isNotEmpty() }
        toLearn.forEach { memory.learn(it.card, it.features) }
        boxes = boxes.map { if (it in toLearn) it.copy(learned = true) else it }
    }

    private fun clearResult() {
        resultTarget = null
        reading = null
        boxes = emptyList()
        setPending(emptyList())
    }

    private fun setPending(cards: List<PlayingCard>) {
        pendingCards = cards
        pending = cards.map { it.view() }
    }

    // ---------------- advice

    fun adviceRequest(): AdviceRequest =
        AdviceRequest(HandQuery(table.variant, table.mine, table.board, table.seen, table.opponents, drawDone = table.drawDone))

    companion object {
        const val MINE = "mine"
        const val TABLE = "table"

        fun variants(): List<VariantView> = PokerVariant.entries.map(::variantView)

        private fun variantView(v: PokerVariant) = VariantView(
            id = v.name,
            usesBoard = TableRules.usesBoard(v),
            tableScan = TableRules.tableScanAvailable(v),
            isDraw = v == PokerVariant.FIVE_CARD_DRAW,
            isStud = v == PokerVariant.SEVEN_CARD_STUD,
        )

        private fun variantOf(id: String) = PokerVariant.entries.firstOrNull { it.name == id } ?: PokerVariant.TEXAS_HOLDEM

        private fun targetOf(id: String) = if (id == MINE) ScanTarget.MINE else ScanTarget.TABLE
    }
}

/** Runs the (CPU-heavy) analysis; call off the main thread. */
object Advisor {
    private val analyzer = HandAnalyzer()

    fun analyze(request: AdviceRequest): AdviceView {
        val q = request.query
        analyzer.issue(q)?.let { return waiting(q, it) }
        val a = analyzer(q) ?: return waiting(q, EngineIssue.HERO_CARD_COUNT)
        val live = a.liveAdvice
        return AdviceView(
            issue = null,
            needMin = 0,
            needMax = 0,
            action = when (live.action) {
                LiveAction.BET_OR_RAISE -> "bet"
                LiveAction.CHECK_OR_CALL -> "call"
                LiveAction.CHECK_OR_FOLD -> "fold"
            },
            maxCallPercent = live.maxCallBetFraction?.let { (it * 100).toInt().coerceAtMost(999) } ?: -1,
            equityPercent = (a.heroEquity * 100).toInt(),
            plusMinusPercent = a.estimateHalfWidth?.let { kotlin.math.round(it * 100).toInt() } ?: -1,
            handKey = a.currentHand?.name?.lowercase() ?: "",
            drawKeep = a.draw?.keep?.map { it.view() },
            drawDiscardCount = a.draw?.discard?.size ?: 0,
            hiLo = q.variant == PokerVariant.OMAHA_HI_LO,
        )
    }

    private fun waiting(q: HandQuery, issue: EngineIssue): AdviceView {
        val range = PokerEngines.forVariant(q.variant).heroCards
        val key = when (issue) {
            EngineIssue.HERO_CARD_COUNT -> "need_mine"
            EngineIssue.BOARD_SIZE -> "need_board"
            EngineIssue.CARD_NOT_IN_DECK -> "deck"
            EngineIssue.DUPLICATE_CARD -> "duplicate"
            EngineIssue.TOO_MANY_OPPONENTS, EngineIssue.NOT_ENOUGH_CARDS -> "opponents"
        }
        return AdviceView(key, range.first, range.last, "", 0, 0, -1, "", null, 0, false)
    }
}
