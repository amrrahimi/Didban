package com.pokervision.feature.advisor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokervision.core.domain.repository.SettingsRepository
import com.pokervision.core.domain.usecase.AnalyzeHoldemUseCase
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.poker.holdem.HoldemAnalysis
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

sealed interface AdvisorResult {
    data class NeedsInput(val missing: MissingInput) : AdvisorResult
    data class Ready(val analysis: HoldemAnalysis, val computeMillis: Long) : AdvisorResult
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class AdvisorViewModel @Inject constructor(
    private val analyzeHoldem: AnalyzeHoldemUseCase,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _input = MutableStateFlow(AdvisorInput())
    val input: StateFlow<AdvisorInput> = _input.asStateFlow()

    /** Only Texas Hold'em has an engine so far; the screen says so for other selected variants. */
    val selectedVariant: StateFlow<PokerVariant> = settingsRepository.settings
        .map { it.defaultVariant }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PokerVariant.TEXAS_HOLDEM)

    /**
     * Recomputed whenever the input changes. Debounced so quick successive taps cost one calculation;
     * mapLatest cancels a stale calculation when new input arrives.
     */
    val result: StateFlow<AdvisorResult?> = _input
        .debounce(DEBOUNCE_MS)
        .mapLatest { input ->
            val situation = AdvisorInputReducer.toSituation(input)
            if (situation == null) {
                AdvisorResult.NeedsInput(AdvisorInputReducer.missing(input)!!)
            } else {
                val start = System.nanoTime()
                val analysis = analyzeHoldem(situation)
                AdvisorResult.Ready(analysis, (System.nanoTime() - start) / 1_000_000)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun tapCard(card: PlayingCard) = _input.update { AdvisorInputReducer.tapCard(it, card) }

    fun selectTarget(target: CardTarget) = _input.update { it.copy(target = target) }

    fun setOpponents(n: Int) = _input.update { AdvisorInputReducer.setOpponents(it, n) }

    fun setPot(text: String) = _input.update { it.copy(pot = AdvisorInputReducer.parseAmount(text)) }

    fun setToCall(text: String) = _input.update { it.copy(toCall = AdvisorInputReducer.parseAmount(text)) }

    fun newHand() = _input.update { AdvisorInputReducer.newHand(it) }

    private companion object {
        const val DEBOUNCE_MS = 150L
    }
}
