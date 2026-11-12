package com.pokervision.feature.imageanalysis

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokervision.core.common.result.AppError
import com.pokervision.core.common.result.AppResult
import com.pokervision.core.domain.usecase.DeleteMediaUseCase
import com.pokervision.core.domain.usecase.GetMediaAssetUseCase
import com.pokervision.core.domain.usecase.ImportMediaUseCase
import com.pokervision.core.domain.usecase.ObserveMediaUseCase
import com.pokervision.core.model.media.MediaAsset
import com.pokervision.core.model.media.MediaType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val IMAGE_ID_ARG = "mediaId"

data class ImportState(val inProgress: Boolean = false, val error: AppError? = null, val lastImportedId: Long? = null)

@HiltViewModel
class ImageListViewModel @Inject constructor(
    observeMedia: ObserveMediaUseCase,
    private val importMedia: ImportMediaUseCase,
) : ViewModel() {

    val images: StateFlow<List<MediaAsset>?> =
        observeMedia(MediaType.IMAGE).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _import = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _import.asStateFlow()

    fun import(uri: String) {
        if (_import.value.inProgress) return
        _import.value = ImportState(inProgress = true)
        viewModelScope.launch {
            _import.value = when (val r = importMedia(uri, MediaType.IMAGE)) {
                is AppResult.Success -> ImportState(lastImportedId = r.value.id)
                is AppResult.Failure -> ImportState(error = r.error)
            }
        }
    }

    fun consumeImported() = _import.update { it.copy(lastImportedId = null) }

    fun dismissError() = _import.update { it.copy(error = null) }
}

sealed interface ImageDetailState {
    data object Loading : ImageDetailState
    data class Loaded(val asset: MediaAsset) : ImageDetailState
    data class Failed(val error: AppError) : ImageDetailState
    data object Deleted : ImageDetailState
}

@HiltViewModel
class ImageDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getMediaAsset: GetMediaAssetUseCase,
    private val deleteMedia: DeleteMediaUseCase,
) : ViewModel() {

    private val id: Long = checkNotNull(savedStateHandle.get<Long>(IMAGE_ID_ARG)) { "Missing $IMAGE_ID_ARG" }

    private val _state = MutableStateFlow<ImageDetailState>(ImageDetailState.Loading)
    val state: StateFlow<ImageDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = when (val r = getMediaAsset(id)) {
                is AppResult.Success -> ImageDetailState.Loaded(r.value)
                is AppResult.Failure -> ImageDetailState.Failed(r.error)
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            _state.value = when (val r = deleteMedia(id)) {
                is AppResult.Success -> ImageDetailState.Deleted
                is AppResult.Failure -> ImageDetailState.Failed(r.error)
            }
        }
    }
}
