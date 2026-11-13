package com.pokervision.feature.live.learn

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokervision.core.domain.vision.CaptureTarget
import com.pokervision.core.domain.vision.DeckCapturePlan
import com.pokervision.core.domain.vision.FocusRegion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class LearnUiState(
    val index: Int = 0,
    val counts: Map<String, Int> = emptyMap(),
    val capturing: Boolean = false,
    val exporting: Boolean = false,
    /** Set once an export is ready; the screen opens the share sheet and then clears it. */
    val exportFile: File? = null,
    val exportFailed: Boolean = false,
    val storedBytes: Long = 0,
) {
    val target: CaptureTarget get() = DeckCapturePlan.targets[index]
    val shotsForTarget: Int get() = counts[target.datasetName] ?: 0
    val completed: Int get() = DeckCapturePlan.completedTargets(counts)
    val total: Int get() = DeckCapturePlan.targets.size
}

@HiltViewModel
class LearnDeckViewModel @Inject constructor(
    private val store: TrainingCaptureStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LearnUiState())
    val state: StateFlow<LearnUiState> = _state.asStateFlow()

    @Volatile
    private var lastShotAt = 0L

    init {
        viewModelScope.launch {
            val counts = withContext(Dispatchers.IO) { store.counts() }
            val bytes = withContext(Dispatchers.IO) { store.totalBytes() }
            _state.update { it.copy(counts = counts, index = DeckCapturePlan.nextIncomplete(counts) ?: 0, storedBytes = bytes) }
        }
    }

    /** Starts automatic shots for the current target; a finished target is captured again from scratch. */
    fun startCapture() {
        val s = _state.value
        val name = s.target.datasetName
        val retake = s.shotsForTarget >= DeckCapturePlan.SHOTS_PER_TARGET
        viewModelScope.launch {
            if (retake) withContext(Dispatchers.IO) { store.clear(name) }
            _state.update { it.copy(capturing = true, counts = if (retake) it.counts - name else it.counts) }
        }
    }

    /** Camera analyzer thread: saves a shot of the card area while capturing. */
    fun onFrame(frame: Bitmap) {
        val s = _state.value
        if (!s.capturing) return
        val now = System.currentTimeMillis()
        if (now - lastShotAt < DeckCapturePlan.MIN_SHOT_INTERVAL_MS) return
        lastShotAt = now
        val crop = FocusRegion.centerSquare(frame.width, frame.height)
        val area = Bitmap.createBitmap(frame, crop.left, crop.top, crop.width, crop.height)
        val name = s.target.datasetName
        if (!store.save(name, area)) return
        _state.update { st ->
            val shots = (st.counts[name] ?: 0) + 1
            val counts = st.counts + (name to shots)
            if (shots >= DeckCapturePlan.SHOTS_PER_TARGET) {
                val next = DeckCapturePlan.nextIncomplete(counts, st.index + 1) ?: st.index
                st.copy(counts = counts, capturing = false, index = next, storedBytes = store.totalBytes())
            } else {
                st.copy(counts = counts)
            }
        }
    }

    fun redo() {
        val name = _state.value.target.datasetName
        _state.update { it.copy(capturing = false) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clear(name) }
            _state.update { it.copy(counts = it.counts - name) }
        }
    }

    fun skip() = move(+1)

    fun previous() = move(-1)

    private fun move(delta: Int) = _state.update {
        val n = DeckCapturePlan.targets.size
        it.copy(index = (it.index + delta + n) % n, capturing = false)
    }

    fun export(appVersion: String) {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true, exportFailed = false, capturing = false) }
        viewModelScope.launch {
            val file = runCatching { withContext(Dispatchers.IO) { store.exportZip(appVersion) } }.getOrNull()
            _state.update { it.copy(exporting = false, exportFile = file, exportFailed = file == null) }
        }
    }

    fun exportHandled() = _state.update { it.copy(exportFile = null) }

    fun deleteAll() {
        _state.update { it.copy(capturing = false) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clearAll() }
            _state.update { LearnUiState() }
        }
    }
}
