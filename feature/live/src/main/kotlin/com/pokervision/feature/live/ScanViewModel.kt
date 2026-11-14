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
import com.pokervision.core.vision.CardDetector
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
data class ScanBox(val card: PlayingCard, val box: BoundingBox, val accepted: Boolean)

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
    private val detector: CardDetector,
    private val analyzeHand: AnalyzeHandUseCase,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _table = MutableStateFlow(TableState())
    val table: StateFlow<TableState> = _table.asStateFlow()

    private val _phase = MutableStateFlow<ScanPhase>(ScanPhase.Idle)
    val phase: StateFlow<ScanPhase> = _phase.asStateFlow()

    private val _detector = MutableStateFlow<DetectorStatus>(DetectorStatus.Loading)
    val detectorStatus: StateFlow<DetectorStatus> = _detector.asStateFlow()

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
            _detector.value = withContext(Dispatchers.IO) { detector.prepare() }
        }
    }

    /** Called right before the camera takes the photo. */
    fun onScanStarted(target: ScanTarget) {
        _phase.value = ScanPhase.Working(target)
    }

    /** Camera thread: a photo is ready. */
    fun onPhoto(target: ScanTarget, photo: Bitmap) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { detector.scanPhoto(photo, SCAN_THRESHOLD) }
            val confident = result.sightings.filter(ScanAcceptance::isConfident).map { it.card }
            val outcome = TableRules.applyScan(_table.value, target, confident)
            _table.value = outcome.state
            val placed = outcome.added.toSet()
            _phase.value = ScanPhase.Result(
                target = target,
                photo = photo,
                boxes = result.sightings.map { ScanBox(it.card, it.box, it.card in placed) },
                pending = result.sightings.map { it.card }.filter { it !in placed && it !in outcome.state.allCards },
                millis = result.inferenceMillis,
            )
        }
    }

    fun onScanFailed() {
        _phase.value = ScanPhase.Failed
    }

    /** User accepts a card that was read with low confidence. */
    fun acceptPending(card: PlayingCard) {
        val r = _phase.value as? ScanPhase.Result ?: return
        val before = _table.value
        _table.update { TableRules.add(it, card, r.target) }
        if (_table.value != before) {
            _phase.value = r.copy(
                pending = r.pending - card,
                boxes = r.boxes.map { if (it.card == card) it.copy(accepted = true) else it },
            )
        }
    }

    fun closeResult() {
        _phase.value = ScanPhase.Idle
    }

    fun remove(card: PlayingCard) = _table.update { TableRules.remove(it, card) }

    fun addManual(card: PlayingCard, target: ScanTarget) = _table.update { TableRules.add(it, card, target) }

    fun setOpponents(n: Int) = _table.update { TableRules.setOpponents(it, n) }

    fun setDrawDone(done: Boolean) = _table.update { it.copy(drawDone = done) }

    fun newHand() {
        _phase.value = ScanPhase.Idle
        _table.update { TableRules.newHand(it) }
    }

    fun setVariant(variant: PokerVariant) {
        _phase.value = ScanPhase.Idle
        _table.update { TableRules.setVariant(it, variant) }
        viewModelScope.launch { settingsRepository.setDefaultVariant(variant) }
    }

    private companion object {
        /** Low enough to show "maybe" cards for one-tap confirmation; acceptance uses [ScanAcceptance]. */
        const val SCAN_THRESHOLD = 0.3f
    }
}
