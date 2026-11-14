package com.pokervision.feature.live

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokervision.core.domain.repository.SettingsRepository
import com.pokervision.core.domain.usecase.AnalyzeHandUseCase
import com.pokervision.core.domain.usecase.HandQuery
import com.pokervision.core.domain.vision.CardSighting
import com.pokervision.core.domain.vision.CropRect
import com.pokervision.core.domain.vision.FocusRegion
import com.pokervision.core.domain.vision.MonitorReader
import com.pokervision.core.domain.vision.MonitorRegions
import com.pokervision.core.domain.vision.ScanTarget
import com.pokervision.core.domain.vision.TableRules
import com.pokervision.core.domain.vision.TableState
import com.pokervision.core.model.detection.BoundingBox
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.poker.engine.EngineIssue
import com.pokervision.core.vision.CardDetector
import com.pokervision.core.vision.DetectorStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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

enum class RegionKind { MINE, BOARD }

data class MonitorUiState(
    val detector: DetectorStatus = DetectorStatus.Loading,
    val regions: MonitorRegions = MonitorRegions.DEFAULT,
    /** Non-null while the user is drawing an area on the preview. */
    val editing: RegionKind? = null,
    val boxes: List<ScanBox> = emptyList(),
    val frameAspect: Float = 9f / 16f,
    val secondsPerReading: Float = 0f,
    val cameraError: Boolean = false,
)

/**
 * "Monitor mode": the phone stands in front of the computer screen and reads the game continuously.
 * The hand shown in the app is whatever is confirmed on screen right now (see [MonitorReader]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MonitorViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val detector: CardDetector,
    private val analyzeHand: AnalyzeHandUseCase,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val reader = MonitorReader()

    private val _ui = MutableStateFlow(MonitorUiState(regions = loadRegions()))
    val ui: StateFlow<MonitorUiState> = _ui.asStateFlow()

    private val _table = MutableStateFlow(TableState())
    val table: StateFlow<TableState> = _table.asStateFlow()

    val analysis: StateFlow<AnalysisState> = _table
        .map { HandQuery(it.variant, it.mine, it.board, it.seen, it.opponents) }
        .distinctUntilChanged()
        .mapLatest { q ->
            analyzeHand.issue(q)?.let { AnalysisState.Waiting(it) }
                ?: analyzeHand(q)?.let { AnalysisState.Ready(it) }
                ?: AnalysisState.Waiting(EngineIssue.HERO_CARD_COUNT)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisState.Computing)

    @Volatile
    private var lastFrameAt = 0L

    init {
        viewModelScope.launch {
            val variant = settingsRepository.settings.first().defaultVariant
            _table.update { TableRules.setVariant(it, variant).copy(handNumber = 1) }
            val status = withContext(Dispatchers.IO) { detector.prepare() }
            _ui.update { it.copy(detector = status) }
        }
    }

    /** Camera analyzer thread. Reads both areas zoomed in, then updates the hand. */
    fun onFrame(frame: Bitmap) {
        val s = _ui.value
        if (s.detector != DetectorStatus.Ready || s.editing != null) return
        val mine = detectIn(frame, s.regions.mine)
        val board = detectIn(frame, s.regions.board)
        val reading = reader.update(mine, board)
        _table.update { t ->
            val withMine = TableRules.applyScan(t, ScanTarget.MINE, reading.mine).state
            if (TableRules.tableScanAvailable(t.variant)) TableRules.applyScan(withMine, ScanTarget.TABLE, reading.board).state
            else withMine
        }
        val confirmed = (reading.mine + reading.board).toSet()
        val now = System.nanoTime()
        val seconds = if (lastFrameAt == 0L) 0f else (now - lastFrameAt) / 1e9f
        lastFrameAt = now
        _ui.update {
            it.copy(
                boxes = (mine + board).map { b -> ScanBox(b.card, b.box, b.card in confirmed) },
                frameAspect = frame.width.toFloat() / frame.height,
                secondsPerReading = seconds,
            )
        }
    }

    /** Crops an area (plus a small margin) so the model sees it enlarged; boxes are mapped back to the frame. */
    private fun detectIn(frame: Bitmap, area: BoundingBox): List<CardSighting> {
        val m = AREA_MARGIN
        val left = ((area.left - m).coerceIn(0f, 1f) * frame.width).toInt()
        val top = ((area.top - m).coerceIn(0f, 1f) * frame.height).toInt()
        val right = ((area.right + m).coerceIn(0f, 1f) * frame.width).toInt()
        val bottom = ((area.bottom + m).coerceIn(0f, 1f) * frame.height).toInt()
        if (right - left < MIN_AREA_PX || bottom - top < MIN_AREA_PX) return emptyList()
        val crop = CropRect(left, top, right - left, bottom - top)
        val bmp = Bitmap.createBitmap(frame, crop.left, crop.top, crop.width, crop.height)
        return try {
            detector.detect(bmp, DECODE_THRESHOLD).sightings.map {
                it.copy(box = FocusRegion.toFrame(it.box, crop, frame.width, frame.height))
            }
        } finally {
            if (bmp !== frame) bmp.recycle()
        }
    }

    fun onCameraError() = _ui.update { it.copy(cameraError = true) }

    fun startEditing(kind: RegionKind) = _ui.update { it.copy(editing = kind) }

    /** The user finished drawing [box] (normalized to the frame) for the area being edited. */
    fun setRegion(box: BoundingBox) {
        val kind = _ui.value.editing ?: return
        val regions = when (kind) {
            RegionKind.MINE -> _ui.value.regions.copy(mine = box)
            RegionKind.BOARD -> _ui.value.regions.copy(board = box)
        }
        saveRegions(regions)
        reader.reset()
        _ui.update { it.copy(regions = regions, editing = null) }
    }

    fun cancelEditing() = _ui.update { it.copy(editing = null) }

    fun resetRegions() {
        saveRegions(MonitorRegions.DEFAULT)
        reader.reset()
        _ui.update { it.copy(regions = MonitorRegions.DEFAULT, editing = null) }
    }

    fun setOpponents(n: Int) = _table.update { TableRules.setOpponents(it, n) }

    fun setVariant(variant: PokerVariant) {
        reader.reset()
        _table.update { TableRules.setVariant(it, variant) }
        viewModelScope.launch { settingsRepository.setDefaultVariant(variant) }
    }

    private fun loadRegions(): MonitorRegions {
        fun box(prefix: String, d: BoundingBox) = runCatching {
            BoundingBox(
                prefs.getFloat("${prefix}_l", d.left), prefs.getFloat("${prefix}_t", d.top),
                prefs.getFloat("${prefix}_r", d.right), prefs.getFloat("${prefix}_b", d.bottom),
            )
        }.getOrDefault(d)
        return MonitorRegions(box("mine", MonitorRegions.DEFAULT.mine), box("board", MonitorRegions.DEFAULT.board))
    }

    private fun saveRegions(r: MonitorRegions) {
        prefs.edit().apply {
            listOf("mine" to r.mine, "board" to r.board).forEach { (p, b) ->
                putFloat("${p}_l", b.left); putFloat("${p}_t", b.top); putFloat("${p}_r", b.right); putFloat("${p}_b", b.bottom)
            }
        }.apply()
    }

    private companion object {
        const val PREFS = "monitor_mode"
        const val AREA_MARGIN = 0.02f
        const val MIN_AREA_PX = 32
        const val DECODE_THRESHOLD = 0.25f
    }
}
