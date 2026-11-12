package com.pokervision.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokervision.core.common.result.AppError
import com.pokervision.core.common.result.AppResult
import com.pokervision.core.domain.repository.SettingsRepository
import com.pokervision.core.domain.usecase.ClearAllLocalDataUseCase
import com.pokervision.core.domain.usecase.GetStorageUsageUseCase
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.model.settings.AppSettings
import com.pokervision.core.model.settings.KeyframeSensitivity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings are simple reads/writes with no business rules beyond validation in the model,
 * so this ViewModel talks to the repository directly instead of through pass-through use cases.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setVariant(variant: PokerVariant) = viewModelScope.launch { repository.setDefaultVariant(variant) }

    fun setAnalysisFps(fps: Int) = viewModelScope.launch {
        repository.setAnalysisFps(fps.coerceIn(AppSettings.ANALYSIS_FPS_RANGE))
    }

    /** Keeps the invariant minimumDetection <= autoConfirm by clamping the value that was not edited. */
    fun setThresholds(minimumDetection: Float, autoConfirm: Float, editedMinimum: Boolean) = viewModelScope.launch {
        val min = minimumDetection.coerceIn(0f, 1f)
        val auto = autoConfirm.coerceIn(0f, 1f)
        val (m, a) = when {
            min <= auto -> min to auto
            editedMinimum -> min to min
            else -> auto to auto
        }
        repository.setConfidenceThresholds(m, a)
    }

    fun setVideoSampleInterval(intervalMs: Long) = viewModelScope.launch {
        if (intervalMs in AppSettings.VIDEO_SAMPLE_INTERVALS_MS) repository.setVideoSampleInterval(intervalMs)
    }

    fun setKeyframeSensitivity(sensitivity: KeyframeSensitivity) = viewModelScope.launch {
        repository.setKeyframeSensitivity(sensitivity)
    }

    fun resetDetectionDefaults() = viewModelScope.launch {
        val d = AppSettings()
        repository.setAnalysisFps(d.analysisFps)
        repository.setConfidenceThresholds(d.confidenceThresholds.minimumDetection, d.confidenceThresholds.autoConfirm)
        repository.setVideoSampleInterval(d.videoSampleIntervalMs)
        repository.setKeyframeSensitivity(d.keyframeSensitivity)
    }
}

data class PrivacyUiState(
    val usageBytes: Long? = null,
    val clearing: Boolean = false,
    val cleared: Boolean = false,
    val error: AppError? = null,
)

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val getStorageUsage: GetStorageUsageUseCase,
    private val clearAllLocalData: ClearAllLocalDataUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(PrivacyUiState())
    val state: StateFlow<PrivacyUiState> = _state.asStateFlow()

    init {
        refreshUsage()
    }

    fun refreshUsage() {
        viewModelScope.launch { _state.update { it.copy(usageBytes = getStorageUsage()) } }
    }

    fun clearAll() {
        if (_state.value.clearing) return
        _state.update { it.copy(clearing = true, cleared = false, error = null) }
        viewModelScope.launch {
            val result = clearAllLocalData()
            _state.update {
                it.copy(
                    clearing = false,
                    cleared = result is AppResult.Success,
                    error = (result as? AppResult.Failure)?.error,
                )
            }
            refreshUsage()
        }
    }
}
