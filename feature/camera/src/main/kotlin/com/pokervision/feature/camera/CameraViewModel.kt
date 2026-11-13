package com.pokervision.feature.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokervision.core.common.result.AppError
import com.pokervision.core.common.result.AppResult
import com.pokervision.core.domain.repository.SettingsRepository
import com.pokervision.core.domain.usecase.CreateCaptureFileUseCase
import com.pokervision.core.domain.usecase.RegisterCapturedMediaUseCase
import com.pokervision.core.model.media.MediaType
import com.pokervision.core.model.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CameraUiState(
    val lens: LensFacing = LensFacing.BACK,
    val isCapturing: Boolean = false,
    val savedMessageCount: Int = 0,
    val error: CameraError? = null,
)

sealed interface CameraError {
    data object BindFailed : CameraError
    data object CaptureFailed : CameraError
    data class Storage(val error: AppError) : CameraError
}

@HiltViewModel
class CameraViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val createCaptureFile: CreateCaptureFileUseCase,
    private val registerCapturedMedia: RegisterCapturedMediaUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun toggleLens() {
        if (_state.value.isCapturing) return
        _state.update { it.copy(lens = if (it.lens == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK) }
    }

    /** Allocates a capture target. Returns null and sets an error on failure. */
    suspend fun prepareCaptureFile(type: MediaType): String? =
        when (val r = createCaptureFile(type)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> {
                _state.update { it.copy(error = CameraError.Storage(r.error)) }
                null
            }
        }

    fun onCaptureStarted() = _state.update { it.copy(isCapturing = true, error = null) }

    fun onMediaWritten(path: String, type: MediaType) {
        viewModelScope.launch {
            when (val r = registerCapturedMedia(path, type)) {
                is AppResult.Success -> _state.update {
                    it.copy(isCapturing = false, savedMessageCount = it.savedMessageCount + 1)
                }
                is AppResult.Failure -> _state.update {
                    it.copy(isCapturing = false, error = CameraError.Storage(r.error))
                }
            }
        }
    }

    fun onCaptureFailed() =
        _state.update { it.copy(isCapturing = false, error = CameraError.CaptureFailed) }

    fun onBindFailed() = _state.update { it.copy(error = CameraError.BindFailed) }

    fun dismissError() = _state.update { it.copy(error = null) }
}
