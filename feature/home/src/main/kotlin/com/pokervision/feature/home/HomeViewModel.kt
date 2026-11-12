package com.pokervision.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokervision.core.domain.usecase.DashboardSummary
import com.pokervision.core.domain.usecase.ObserveDashboardUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(val summary: DashboardSummary) : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeDashboard: ObserveDashboardUseCase,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = observeDashboard()
        .map<DashboardSummary, HomeUiState> { HomeUiState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState.Loading)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
