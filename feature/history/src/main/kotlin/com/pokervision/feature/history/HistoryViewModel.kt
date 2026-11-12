package com.pokervision.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokervision.core.common.result.AppError
import com.pokervision.core.common.result.AppResult
import com.pokervision.core.domain.usecase.DeleteGameUseCase
import com.pokervision.core.domain.usecase.DeleteMediaUseCase
import com.pokervision.core.domain.usecase.ObserveGamesUseCase
import com.pokervision.core.domain.usecase.ObserveMediaUseCase
import com.pokervision.core.model.game.PokerGame
import com.pokervision.core.model.media.MediaAsset
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryContent(val games: List<PokerGame>, val media: List<MediaAsset>)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    observeGames: ObserveGamesUseCase,
    observeMedia: ObserveMediaUseCase,
    private val deleteGameUseCase: DeleteGameUseCase,
    private val deleteMediaUseCase: DeleteMediaUseCase,
) : ViewModel() {

    val content: StateFlow<HistoryContent?> = combine(observeGames(), observeMedia()) { games, media ->
        HistoryContent(games, media)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<AppError?>(null)
    val error: StateFlow<AppError?> = _error.asStateFlow()

    fun deleteGame(id: String) = launchDelete { deleteGameUseCase(id) }

    fun deleteMedia(id: Long) = launchDelete { deleteMediaUseCase(id) }

    fun dismissError() {
        _error.value = null
    }

    private fun launchDelete(block: suspend () -> AppResult<Unit>) {
        viewModelScope.launch {
            (block() as? AppResult.Failure)?.let { _error.value = it.error }
        }
    }
}
