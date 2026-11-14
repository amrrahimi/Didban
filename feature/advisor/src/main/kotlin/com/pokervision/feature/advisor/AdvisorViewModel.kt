package com.pokervision.feature.advisor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokervision.core.domain.repository.SettingsRepository
import com.pokervision.core.domain.usecase.AnalyzeHandUseCase
import com.pokervision.core.domain.usecase.HandAnalysis
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.poker.engine.EngineIssue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AdvisorResult {
    data class NeedsInput(val issue: EngineIssue) : AdvisorResult
    data class Ready(val analysis: HandAnalysis) : AdvisorResult
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class AdvisorViewModel @Inject constructor(
    private val analyzeHand: AnalyzeHandUseCase,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _input = MutableStateFlow(AdvisorInput())
    val input: StateFlow<AdvisorInput> = _input.asStateFlow()

    /**
     * Recomputed whenever the input changes. Debounced so quick successive taps cost one calculation;
     * mapLatest drops a stale calculation when new input arrives.
     */
    val result: StateFlow<AdvisorResult?> = _input
        .debounce(DEBOUNCE_MS)
        .mapLatest { input ->
            val q = AdvisorInputReducer.toQuery(input)
            analyzeHand.issue(q)?.let { AdvisorResult.NeedsInput(it) }
                ?: analyzeHand(q)?.let { AdvisorResult.Ready(it) }
                ?: AdvisorResult.NeedsInput(EngineIssue.HERO_CARD_COUNT)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val variant = settingsRepository.settings.first().defaultVariant
            _input.update { AdvisorInputReducer.setVariant(it, variant) }
        }
    }

    fun tapCard(card: PlayingCard) = _input.update { AdvisorInputReducer.tapCard(it, card) }

    fun selectTarget(target: CardTarget) = _input.update { it.copy(target = target) }

    fun setOpponents(n: Int) = _input.update { AdvisorInputReducer.setOpponents(it, n) }

    fun setPot(text: String) = _input.update { it.copy(pot = AdvisorInputReducer.parseAmount(text)) }

    fun setToCall(text: String) = _input.update { it.copy(toCall = AdvisorInputReducer.parseAmount(text)) }

    fun setDrawDone(done: Boolean) = _input.update { it.copy(drawDone = done) }

    fun newHand() = _input.update { AdvisorInputReducer.newHand(it) }

    fun setVariant(variant: PokerVariant) {
        _input.update { AdvisorInputReducer.setVariant(it, variant) }
        viewModelScope.launch { settingsRepository.setDefaultVariant(variant) }
    }

    private companion object {
        const val DEBOUNCE_MS = 150L
    }
}
