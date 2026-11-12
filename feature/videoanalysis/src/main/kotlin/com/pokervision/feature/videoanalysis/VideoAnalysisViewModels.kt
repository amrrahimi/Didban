package com.pokervision.feature.videoanalysis

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokervision.core.common.result.AppError
import com.pokervision.core.common.result.AppResult
import com.pokervision.core.domain.media.ExtractKeyframesUseCase
import com.pokervision.core.domain.media.GetSavedKeyframesUseCase
import com.pokervision.core.domain.media.Keyframe
import com.pokervision.core.domain.media.KeyframeExtractionEvent
import com.pokervision.core.domain.usecase.DeleteMediaUseCase
import com.pokervision.core.domain.usecase.GetMediaAssetUseCase
import com.pokervision.core.domain.usecase.ImportMediaUseCase
import com.pokervision.core.domain.usecase.ObserveMediaUseCase
import com.pokervision.core.model.media.MediaAsset
import com.pokervision.core.model.media.MediaType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

const val VIDEO_ID_ARG = "mediaId"

data class VideoImportState(val inProgress: Boolean = false, val error: AppError? = null, val lastImportedId: Long? = null)

@HiltViewModel
class VideoListViewModel @Inject constructor(
    observeMedia: ObserveMediaUseCase,
    private val importMedia: ImportMediaUseCase,
) : ViewModel() {

    val videos: StateFlow<List<MediaAsset>?> =
        observeMedia(MediaType.VIDEO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _import = MutableStateFlow(VideoImportState())
    val importState: StateFlow<VideoImportState> = _import.asStateFlow()

    fun import(uri: String) {
        if (_import.value.inProgress) return
        _import.value = VideoImportState(inProgress = true)
        viewModelScope.launch {
            _import.value = when (val r = importMedia(uri, MediaType.VIDEO)) {
                is AppResult.Success -> VideoImportState(lastImportedId = r.value.id)
                is AppResult.Failure -> VideoImportState(error = r.error)
            }
        }
    }

    fun consumeImported() = _import.update { it.copy(lastImportedId = null) }

    fun dismissError() = _import.update { it.copy(error = null) }
}

sealed interface VideoDetailState {
    data object Loading : VideoDetailState
    data class Loaded(val asset: MediaAsset) : VideoDetailState
    data class Failed(val error: AppError) : VideoDetailState
    data object Deleted : VideoDetailState
}

sealed interface KeyframeUiState {
    /** No extraction run yet; [saved] holds keyframes cached from an earlier run, if any. */
    data class Idle(val saved: List<Keyframe>) : KeyframeUiState
    data class Running(val sampled: Int, val total: Int, val keyframes: List<Keyframe>) : KeyframeUiState
    data class Done(val keyframes: List<Keyframe>, val sampled: Int, val undecodable: Int) : KeyframeUiState
    data class Failed(val error: AppError, val partial: List<Keyframe>) : KeyframeUiState
}

/** A request to move playback; [nonce] lets the same position be requested twice. */
data class SeekRequest(val positionMs: Long, val nonce: Long)

@HiltViewModel
class VideoDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getMediaAsset: GetMediaAssetUseCase,
    private val deleteMedia: DeleteMediaUseCase,
    private val extractKeyframes: ExtractKeyframesUseCase,
    private val getSavedKeyframes: GetSavedKeyframesUseCase,
) : ViewModel() {

    private val id: Long = checkNotNull(savedStateHandle.get<Long>(VIDEO_ID_ARG)) { "Missing $VIDEO_ID_ARG" }

    private val _state = MutableStateFlow<VideoDetailState>(VideoDetailState.Loading)
    val state: StateFlow<VideoDetailState> = _state.asStateFlow()

    private val _keyframes = MutableStateFlow<KeyframeUiState>(KeyframeUiState.Idle(emptyList()))
    val keyframes: StateFlow<KeyframeUiState> = _keyframes.asStateFlow()

    private val _seek = MutableStateFlow<SeekRequest?>(null)
    val seek: StateFlow<SeekRequest?> = _seek.asStateFlow()

    private var extractionJob: Job? = null

    init {
        viewModelScope.launch {
            _state.value = when (val r = getMediaAsset(id)) {
                is AppResult.Success -> VideoDetailState.Loaded(r.value)
                is AppResult.Failure -> VideoDetailState.Failed(r.error)
            }
            _keyframes.value = KeyframeUiState.Idle(withContext(Dispatchers.IO) { getSavedKeyframes(id) })
        }
    }

    fun startExtraction() {
        if (extractionJob?.isActive == true) return
        _keyframes.value = KeyframeUiState.Running(0, 0, emptyList())
        extractionJob = viewModelScope.launch {
            // Decoding, fingerprinting and JPEG encoding are CPU work: keep them off the main thread.
            extractKeyframes(id).flowOn(Dispatchers.Default).collect { event ->
                _keyframes.value = when (event) {
                    is KeyframeExtractionEvent.Progress -> KeyframeUiState.Running(event.sampled, event.total, event.keyframes)
                    is KeyframeExtractionEvent.Completed -> KeyframeUiState.Done(event.keyframes, event.sampled, event.undecodable)
                    is KeyframeExtractionEvent.Failed -> KeyframeUiState.Failed(event.error, currentKeyframes())
                }
            }
        }
    }

    fun cancelExtraction() {
        extractionJob?.cancel()
        extractionJob = null
        _keyframes.value = KeyframeUiState.Idle(currentKeyframes())
    }

    fun seekTo(positionMs: Long) {
        _seek.value = SeekRequest(positionMs, System.nanoTime())
    }

    private fun currentKeyframes(): List<Keyframe> = when (val k = _keyframes.value) {
        is KeyframeUiState.Idle -> k.saved
        is KeyframeUiState.Running -> k.keyframes
        is KeyframeUiState.Done -> k.keyframes
        is KeyframeUiState.Failed -> k.partial
    }

    fun delete() {
        extractionJob?.cancel()
        viewModelScope.launch {
            _state.value = when (val r = deleteMedia(id)) {
                is AppResult.Success -> VideoDetailState.Deleted
                is AppResult.Failure -> VideoDetailState.Failed(r.error)
            }
        }
    }
}
