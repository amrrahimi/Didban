package com.pokervision.feature.live

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokervision.core.common.timing.ThroughputMeter
import com.pokervision.core.domain.repository.SettingsRepository
import com.pokervision.core.domain.usecase.AnalyzeLiveHandUseCase
import com.pokervision.core.domain.usecase.LiveAnalysis
import com.pokervision.core.domain.vision.CardConfirmer
import com.pokervision.core.domain.vision.LiveHand
import com.pokervision.core.domain.vision.LiveHandAssembler
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.detection.BoundingBox
import com.pokervision.core.model.settings.AppSettings
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A card box drawn over the camera image (normalized coordinates of the upright frame). */
data class OverlayBox(val card: PlayingCard, val box: BoundingBox, val confirmed: Boolean)

data class LiveVisionState(
    val detector: DetectorStatus = DetectorStatus.Loading,
    val overlay: List<OverlayBox> = emptyList(),
    /** Width / height of the analysed upright frame; drives the preview box aspect ratio. */
    val frameAspect: Float = 9f / 16f,
    val inferenceMillis: Long = 0,
    val analysedFps: Double = 0.0,
    val cameraError: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val detector: CardDetector,
    private val analyzeLiveHand: AnalyzeLiveHandUseCase,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _hand = MutableStateFlow(LiveHand())
    val hand: StateFlow<LiveHand> = _hand.asStateFlow()

    private val _vision = MutableStateFlow(LiveVisionState())
    val vision: StateFlow<LiveVisionState> = _vision.asStateFlow()

    /** Touched only from the camera analyzer thread. */
    private var confirmer = newConfirmer()
    private val meter = ThroughputMeter()

    @Volatile
    private var resetConfirmer = false

    /** Recomputed only when the cards or the number of opponents change, never per frame. */
    val analysis: StateFlow<LiveAnalysis?> = _hand
        .map { Triple(it.hero, it.board, it.opponents) }
        .distinctUntilChanged()
        .mapLatest { (hero, board, opponents) -> analyzeLiveHand(hero, board, opponents) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { detector.prepare() }
            _vision.update { it.copy(detector = status) }
        }
    }

    private fun newConfirmer() = CardConfirmer(minScore = settings.value.confidenceThresholds.minimumDetection)

    /** Called on the camera analyzer thread for each throttled frame. */
    fun onFrame(frame: Bitmap) {
        if (detector.status != DetectorStatus.Ready) return
        if (resetConfirmer) {
            confirmer = newConfirmer()
            resetConfirmer = false
        }
        val result = detector.detect(frame, DECODE_THRESHOLD)
        val state = confirmer.update(result.sightings)
        meter.record(System.nanoTime())
        _hand.update { LiveHandAssembler.onConfirmed(it, state.confirmed.keys) }
        _vision.update {
            it.copy(
                overlay = result.sightings.map { s -> OverlayBox(s.card, s.box, s.card in state.confirmed) },
                frameAspect = frame.width.toFloat() / frame.height,
                inferenceMillis = result.inferenceMillis,
                analysedFps = meter.fps(),
            )
        }
    }

    fun onCameraError() = _vision.update { it.copy(cameraError = true) }

    fun rejectCard(card: PlayingCard) = _hand.update { LiveHandAssembler.reject(it, card) }

    fun addManual(card: PlayingCard, toHero: Boolean) = _hand.update { LiveHandAssembler.addManual(it, card, toHero) }

    fun rescanHero() {
        resetConfirmer = true
        _hand.update { LiveHandAssembler.rescanHero(it) }
    }

    fun setOpponents(n: Int) = _hand.update { LiveHandAssembler.setOpponents(it, n) }

    fun newHand() {
        resetConfirmer = true
        _hand.update { LiveHandAssembler.newHand(it) }
    }

    private companion object {
        /** Low enough to show "maybe" boxes; confirmation uses the user's minimum-confidence setting. */
        const val DECODE_THRESHOLD = 0.25f
    }
}
