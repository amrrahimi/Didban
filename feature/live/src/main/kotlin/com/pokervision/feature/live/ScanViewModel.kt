package com.pokervision.feature.live

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokervision.core.domain.repository.SettingsRepository
import com.pokervision.core.domain.usecase.AnalyzeHandUseCase
import com.pokervision.core.domain.usecase.HandAnalysis
import com.pokervision.core.domain.usecase.HandQuery
import com.pokervision.core.domain.vision.ScanAcceptance
import com.pokervision.core.domain.vision.ScanTarget
import com.pokervision.core.domain.vision.TableRules
import com.pokervision.core.domain.vision.TableState
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.detection.BoundingBox
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.poker.engine.EngineIssue
import com.pokervision.core.domain.vision.GlyphFeature
import com.pokervision.core.vision.LearnedScan
import com.pokervision.core.vision.LearningCardReader
import com.pokervision.core.vision.DetectorStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A card found on the scanned photo; [accepted] = used automatically, otherwise awaiting a tap. */
data class ScanBox(
    val card: PlayingCard,
    val box: BoundingBox,
    val accepted: Boolean,
    /** Looks of this card's corner symbols, kept so the user's confirmation can be learned. */
    val features: List<GlyphFeature> = emptyList(),
    /** Read with help of what the user taught earlier. */
    val fromMemory: Boolean = false,
    /** Set by the user (corrected or tapped in): shown differently, already learned. */
    val byUser: Boolean = false,
    /** Already stored in the card memory. */
    val learned: Boolean = false,
)

sealed interface ScanPhase {
    data object Idle : ScanPhase
    data class Working(val target: ScanTarget) : ScanPhase
    data class Result(
        val target: ScanTarget,
        val photo: Bitmap,
        val boxes: List<ScanBox>,
        /** Cards read but not accepted automatically (low confidence) or not placed (full / other area). */
        val pending: List<PlayingCard>,
        val millis: Long,
        /** Raw reading, used to cut out a symbol the user taps. */
        val scan: LearnedScan? = null,
    ) : ScanPhase
    data object Failed : ScanPhase
}

sealed interface AnalysisState {
    data class Waiting(val issue: EngineIssue) : AnalysisState
    data object Computing : AnalysisState
    data class Ready(val analysis: HandAnalysis) : AnalysisState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val reader: LearningCardReader,
    private val analyzeHand: AnalyzeHandUseCase,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _table = MutableStateFlow(TableState())
    val table: StateFlow<TableState> = _table.asStateFlow()

    private val _phase = MutableStateFlow<ScanPhase>(ScanPhase.Idle)
    val phase: StateFlow<ScanPhase> = _phase.asStateFlow()

    private val _detector = MutableStateFlow<DetectorStatus>(DetectorStatus.Loading)
    val detectorStatus: StateFlow<DetectorStatus> = _detector.asStateFlow()

    /** Number of symbol examples the app has learned from the user. */
    val learnedExamples: StateFlow<Int> = reader.examples

    val analysis: StateFlow<AnalysisState> = _table
        .map { HandQuery(it.variant, it.mine, it.board, it.seen, it.opponents, drawDone = it.drawDone) }
        .distinctUntilChanged()
        .mapLatest { q ->
            analyzeHand.issue(q)?.let { AnalysisState.Waiting(it) }
                ?: analyzeHand(q)?.let { AnalysisState.Ready(it) }
                ?: AnalysisState.Waiting(EngineIssue.HERO_CARD_COUNT)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisState.Computing)

    init {
        viewModelScope.launch {
            val variant = settingsRepository.settings.first().defaultVariant
            _table.update { TableRules.setVariant(it, variant).copy(handNumber = 1) }
            _detector.value = withContext(Dispatchers.IO) { reader.prepare() }
        }
    }

    /** Called right before the camera takes the photo. */
    fun onScanStarted(target: ScanTarget) {
        commitLearning()
        _phase.value = ScanPhase.Working(target)
    }

    /** Camera thread: a photo is ready. */
    fun onPhoto(target: ScanTarget, photo: Bitmap) {
        viewModelScope.launch {
            val scan = withContext(Dispatchers.Default) { reader.scanPhoto(photo, SCAN_THRESHOLD) }
            val confident = scan.cards.filter { ScanAcceptance.isConfident(it.sighting) }.map { it.card }
            val outcome = TableRules.applyScan(_table.value, target, confident)
            _table.value = outcome.state
            val placed = outcome.added.toSet()
            _phase.value = ScanPhase.Result(
                target = target,
                photo = photo,
                boxes = scan.cards.map {
                    ScanBox(it.card, it.sighting.box, it.card in placed, it.features, it.fromMemory)
                },
                pending = scan.cards.map { it.card }.filter { it !in placed && it !in outcome.state.allCards },
                millis = scan.inferenceMillis,
                scan = scan,
            )
        }
    }

    fun onScanFailed() {
        _phase.value = ScanPhase.Failed
    }

    /** User accepts a card that was read with low confidence: it is learned right away. */
    fun acceptPending(card: PlayingCard) {
        val r = _phase.value as? ScanPhase.Result ?: return
        val before = _table.value
        _table.update { TableRules.add(it, card, r.target) }
        if (_table.value != before) {
            val box = r.boxes.firstOrNull { it.card == card }
            box?.let { learn(card, it.features) }
            _phase.value = r.copy(
                pending = r.pending - card,
                boxes = r.boxes.map { if (it.card == card) it.copy(accepted = true, learned = true) else it },
            )
        }
    }

    /** User says box [index] shows [card] (a correction, or confirming a "?" card). */
    fun correctBox(index: Int, card: PlayingCard) {
        val r = _phase.value as? ScanPhase.Result ?: return
        val old = r.boxes.getOrNull(index) ?: return
        var t = _table.value
        if (old.card != card && old.accepted) t = TableRules.remove(t, old.card)
        // The chosen card may already sit elsewhere (e.g. misplaced); move it here.
        if (card in t.allCards) t = TableRules.remove(t, card)
        t = TableRules.add(t, card, r.target)
        _table.value = t
        val features = old.features
        if (old.card == card) learn(card, features) else correct(old.card, card, features)
        _phase.value = r.copy(
            boxes = r.boxes.mapIndexed { i, b ->
                when {
                    i == index -> b.copy(card = card, accepted = card in t.allCards, byUser = true, learned = true)
                    b.card == card -> b.copy(accepted = false) // a duplicate reading of the same card
                    else -> b
                }
            },
            pending = (r.pending - card - old.card).filter { it !in t.allCards },
        )
    }

    /** User says box [index] is not a card at all (or reads nothing useful): drop it. */
    fun dismissBox(index: Int) {
        val r = _phase.value as? ScanPhase.Result ?: return
        val old = r.boxes.getOrNull(index) ?: return
        if (old.accepted) _table.update { TableRules.remove(it, old.card) }
        if (old.features.isNotEmpty()) {
            val features = old.features
            viewModelScope.launch(Dispatchers.IO) { features.forEach { reader.unlearnOnly(old.card, it) } }
        }
        _phase.value = r.copy(boxes = r.boxes.filterIndexed { i, _ -> i != index }, pending = r.pending - old.card)
    }

    /**
     * User tapped a place on the photo where a card was missed (x, y normalized to the photo)
     * and chose [card]: the symbol there is learned and the card is added.
     */
    fun teachAt(x: Float, y: Float, card: PlayingCard) {
        val r = _phase.value as? ScanPhase.Result ?: return
        var t = _table.value
        if (card in t.allCards) t = TableRules.remove(t, card)
        t = TableRules.add(t, card, r.target)
        _table.value = t
        viewModelScope.launch {
            val feature = withContext(Dispatchers.Default) { reader.featureAround(r.photo, x, y, r.scan) }
            val features = listOfNotNull(feature)
            learn(card, features)
            val (w, h) = r.scan?.typicalPip ?: (0.05f to 0.06f)
            val box = BoundingBox(
                (x - w / 2).coerceIn(0f, 1f), (y - h / 2).coerceIn(0f, 1f),
                (x + w / 2).coerceIn(0f, 1f), (y + h / 2).coerceIn(0f, 1f),
            )
            val current = _phase.value as? ScanPhase.Result ?: return@launch
            if (current.photo !== r.photo) return@launch
            _phase.value = current.copy(
                boxes = current.boxes.filter { it.card != card } +
                    ScanBox(card, box, card in _table.value.allCards, features, byUser = true, learned = true),
                pending = current.pending - card,
            )
        }
    }

    fun closeResult() {
        commitLearning()
        _phase.value = ScanPhase.Idle
    }

    /** Forget everything learned from the user's photos. */
    fun forgetLearned() {
        viewModelScope.launch(Dispatchers.IO) { reader.forgetAll() }
    }

    /**
     * Cards read automatically and left unchanged by the user are taken as correct and learned
     * (only if still on the table: removing a wrong card from the table prevents learning it).
     */
    private fun commitLearning() {
        val r = _phase.value as? ScanPhase.Result ?: return
        val onTable = _table.value.allCards
        val toLearn = r.boxes.filter { it.accepted && !it.learned && it.card in onTable && it.features.isNotEmpty() }
        if (toLearn.isEmpty()) return
        _phase.value = r.copy(boxes = r.boxes.map { if (it in toLearn) it.copy(learned = true) else it })
        viewModelScope.launch(Dispatchers.IO) { toLearn.forEach { reader.learn(it.card, it.features) } }
    }

    private fun learn(card: PlayingCard, features: List<GlyphFeature>) {
        if (features.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { reader.learn(card, features) }
    }

    private fun correct(wrong: PlayingCard, right: PlayingCard, features: List<GlyphFeature>) {
        if (features.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { reader.correct(wrong, right, features) }
    }

    fun remove(card: PlayingCard) = _table.update { TableRules.remove(it, card) }

    fun addManual(card: PlayingCard, target: ScanTarget) = _table.update { TableRules.add(it, card, target) }

    fun setOpponents(n: Int) = _table.update { TableRules.setOpponents(it, n) }

    fun setDrawDone(done: Boolean) = _table.update { it.copy(drawDone = done) }

    fun newHand() {
        commitLearning()
        _phase.value = ScanPhase.Idle
        _table.update { TableRules.newHand(it) }
    }

    fun setVariant(variant: PokerVariant) {
        commitLearning()
        _phase.value = ScanPhase.Idle
        _table.update { TableRules.setVariant(it, variant) }
        viewModelScope.launch { settingsRepository.setDefaultVariant(variant) }
    }

    private companion object {
        /** Low enough to show "maybe" cards for one-tap confirmation; acceptance uses [ScanAcceptance]. */
        const val SCAN_THRESHOLD = 0.3f
    }
}
