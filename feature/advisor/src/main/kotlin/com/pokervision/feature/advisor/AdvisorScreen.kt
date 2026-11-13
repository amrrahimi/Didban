package com.pokervision.feature.advisor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.card.Rank
import com.pokervision.core.model.card.Suit
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.poker.advice.AdviceAction
import com.pokervision.core.poker.advice.AdviceReason
import com.pokervision.core.poker.holdem.HandCategory
import com.pokervision.core.ui.component.PlannedFeatureNotice
import com.pokervision.core.ui.component.PlayingCardView
import com.pokervision.core.ui.component.PokerTopBar
import com.pokervision.core.ui.text.label
import com.pokervision.core.ui.theme.PokerColors
import java.util.Locale

@Composable
fun AdvisorRoute(onBack: () -> Unit, viewModel: AdvisorViewModel = hiltViewModel()) {
    val input by viewModel.input.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val variant by viewModel.selectedVariant.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            PokerTopBar(
                title = stringResource(R.string.advisor_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = viewModel::newHand) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.advisor_new_hand))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (variant != PokerVariant.TEXAS_HOLDEM) {
                PlannedFeatureNotice(
                    phase = 9,
                    description = stringResource(R.string.advisor_variant_not_supported, variant.label()),
                )
            }
            ResultCard(result, input.opponents)
            SelectedCards(input, onSelectTarget = viewModel::selectTarget, onRemove = viewModel::tapCard)
            CardPicker(input, onTap = viewModel::tapCard)
            TableInputs(
                input = input,
                onOpponents = viewModel::setOpponents,
                onPot = viewModel::setPot,
                onToCall = viewModel::setToCall,
            )
            Text(
                stringResource(R.string.advisor_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------- Result

@Composable
private fun ResultCard(result: AdvisorResult?, opponents: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (result) {
                null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is AdvisorResult.NeedsInput -> Text(
                    stringResource(
                        when (result.missing) {
                            MissingInput.HERO_CARDS -> R.string.advisor_need_hero
                            MissingInput.BOARD_INCOMPLETE -> R.string.advisor_need_board
                            MissingInput.TOO_MANY_CARDS -> R.string.advisor_too_many_cards
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                is AdvisorResult.Ready -> ReadyResult(result, opponents)
            }
        }
    }
}

@Composable
private fun ReadyResult(result: AdvisorResult.Ready, opponents: Int) {
    val a = result.analysis
    val advice = a.advice
    val hero = a.equity.players.first()
    val (actionColor, actionText) = when (advice.action) {
        AdviceAction.FOLD -> PokerColors.ConfidenceLow to stringResource(R.string.advisor_action_fold)
        AdviceAction.CHECK -> PokerColors.ConfidenceMedium to stringResource(R.string.advisor_action_check)
        AdviceAction.CALL -> PokerColors.ConfidenceMedium to stringResource(R.string.advisor_action_call)
        AdviceAction.BET -> PokerColors.ConfidenceHigh to stringResource(R.string.advisor_action_bet)
        AdviceAction.RAISE -> PokerColors.ConfidenceHigh to stringResource(R.string.advisor_action_raise)
    }
    Text(
        actionText,
        color = actionColor,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { contentDescription = actionText },
    )
    Text(reasonText(advice.reason, advice.equity, advice.requiredEquity, advice.strongThreshold), style = MaterialTheme.typography.bodyMedium)

    Spacer(Modifier.height(4.dp))
    val equityText = percent(hero.equity)
    val interval = hero.equityInterval
    Text(
        if (a.equity.isEstimate && interval != null) {
            stringResource(R.string.advisor_equity_estimate, equityText, percent(interval.halfWidth))
        } else {
            stringResource(R.string.advisor_equity_exact, equityText)
        },
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        stringResource(R.string.advisor_win_tie, percent(hero.win), percent(hero.tie), opponents),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    a.currentHand?.let {
        Text(stringResource(R.string.advisor_current_hand, stringResource(it.labelRes())), style = MaterialTheme.typography.bodyMedium)
    }
    advice.requiredEquity?.let {
        Text(stringResource(R.string.advisor_pot_odds, percent(it)), style = MaterialTheme.typography.bodyMedium)
    }
    advice.callEv?.let {
        Text(
            stringResource(R.string.advisor_call_ev, String.format(Locale.getDefault(), "%+.1f", it)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (a.improvingCards.isNotEmpty()) {
        Text(
            stringResource(R.string.advisor_improving, a.improvingCards.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        stringResource(R.string.advisor_compute_time, result.computeMillis),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun reasonText(reason: AdviceReason, equity: Double, required: Double?, strong: Double): String = when (reason) {
    AdviceReason.BELOW_POT_ODDS -> stringResource(R.string.advisor_reason_below_odds, percent(equity), percent(required ?: 0.0))
    AdviceReason.ABOVE_POT_ODDS -> stringResource(R.string.advisor_reason_above_odds, percent(equity), percent(required ?: 0.0))
    AdviceReason.STRONG_HAND -> stringResource(R.string.advisor_reason_strong, percent(equity), percent(strong))
    AdviceReason.FREE_CARD -> stringResource(R.string.advisor_reason_free, percent(equity), percent(strong))
    AdviceReason.FAIR_SHARE_ONLY -> stringResource(R.string.advisor_reason_fair_share)
}

private fun percent(p: Double): String = String.format(Locale.getDefault(), "%.1f%%", p * 100)

private fun HandCategory.labelRes(): Int = when (this) {
    HandCategory.HIGH_CARD -> R.string.hand_high_card
    HandCategory.ONE_PAIR -> R.string.hand_one_pair
    HandCategory.TWO_PAIR -> R.string.hand_two_pair
    HandCategory.THREE_OF_A_KIND -> R.string.hand_trips
    HandCategory.STRAIGHT -> R.string.hand_straight
    HandCategory.FLUSH -> R.string.hand_flush
    HandCategory.FULL_HOUSE -> R.string.hand_full_house
    HandCategory.FOUR_OF_A_KIND -> R.string.hand_quads
    HandCategory.STRAIGHT_FLUSH -> R.string.hand_straight_flush
}

// ---------------------------------------------------------------- Selected cards

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectedCards(input: AdvisorInput, onSelectTarget: (CardTarget) -> Unit, onRemove: (PlayingCard) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                CardTarget.HERO to R.string.advisor_target_hero,
                CardTarget.BOARD to R.string.advisor_target_board,
                CardTarget.SEEN to R.string.advisor_target_seen,
            ).forEach { (target, label) ->
                FilterChip(
                    selected = input.target == target,
                    onClick = { onSelectTarget(target) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        SlotRow(stringResource(R.string.advisor_target_hero), input.hero, 2, onRemove)
        SlotRow(stringResource(R.string.advisor_target_board), input.board, 5, onRemove)
        if (input.seen.isNotEmpty() || input.target == CardTarget.SEEN) {
            SlotRow(stringResource(R.string.advisor_target_seen), input.seen, input.seen.size.coerceAtLeast(1), onRemove)
            Text(
                stringResource(R.string.advisor_seen_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SlotRow(title: String, cards: List<PlayingCard>, slots: Int, onRemove: (PlayingCard) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(88.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until slots) {
                val card = cards.getOrNull(i)
                if (card != null) {
                    PlayingCardView(card, Modifier.clickable { onRemove(card) })
                } else {
                    Box(
                        Modifier.size(width = 40.dp, height = 56.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Card picker

private val SUIT_ORDER = listOf(Suit.SPADES, Suit.HEARTS, Suit.DIAMONDS, Suit.CLUBS)
private val RANK_ORDER = Rank.entries.reversed()

@Composable
private fun CardPicker(input: AdvisorInput, onTap: (PlayingCard) -> Unit) {
    val used = input.usedCards
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SUIT_ORDER.forEach { suit ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                RANK_ORDER.forEach { rank ->
                    val card = PlayingCard(rank, suit)
                    val isUsed = card in used
                    val color = if (suit.isRed) PokerColors.CardRed else PokerColors.CardBlack
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .background(
                                if (isUsed) MaterialTheme.colorScheme.surfaceVariant else PokerColors.Ivory,
                                RoundedCornerShape(4.dp),
                            )
                            .clickable { onTap(card) }
                            .semantics { contentDescription = card.code },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                rank.symbol.toString(),
                                color = if (isUsed) Color.Gray else color,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                            Text(suit.glyph.toString(), color = if (isUsed) Color.Gray else color, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Table inputs

@Composable
private fun TableInputs(
    input: AdvisorInput,
    onOpponents: (Int) -> Unit,
    onPot: (String) -> Unit,
    onToCall: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.advisor_opponents), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { onOpponents(input.opponents - 1) }, enabled = input.opponents > MIN_OPPONENTS) {
                Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.advisor_fewer_opponents))
            }
            Text(input.opponents.toString(), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { onOpponents(input.opponents + 1) }, enabled = input.opponents < MAX_OPPONENTS) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.advisor_more_opponents))
            }
        }
        // Text is held here; the ViewModel stores the parsed number. Reset on each new hand.
        var potText by rememberSaveable(input.handNumber) { mutableStateOf("") }
        var callText by rememberSaveable(input.handNumber) { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = potText,
                onValueChange = { potText = it; onPot(it) },
                label = { Text(stringResource(R.string.advisor_pot)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = callText,
                onValueChange = { callText = it; onToCall(it) },
                label = { Text(stringResource(R.string.advisor_to_call)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.advisor_money_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
