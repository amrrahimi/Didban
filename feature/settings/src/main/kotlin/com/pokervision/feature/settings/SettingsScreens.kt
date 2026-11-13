package com.pokervision.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.model.settings.AppSettings
import com.pokervision.core.ui.component.ErrorBanner
import com.pokervision.core.ui.component.PlannedFeatureNotice
import com.pokervision.core.ui.component.PokerTopBar
import com.pokervision.core.ui.component.StatusChip
import com.pokervision.core.ui.text.formatBytes
import com.pokervision.core.ui.text.label
import com.pokervision.core.ui.text.userMessage
import com.pokervision.core.vision.DetectorStatus
import kotlin.math.roundToInt

@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = { PokerTopBar(title, onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) { content() }
    }
}

// ---------------------------------------------------------------- Variant selection

@Composable
fun VariantSelectionRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsScaffold(stringResource(R.string.settings_variant_title), onBack) {
        val s = settings
        if (s == null) {
            CircularProgressIndicator(Modifier.padding(32.dp))
        } else Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PokerVariant.entries.forEach { variant ->
                VariantRow(variant, selected = variant == s.defaultVariant, onSelect = { viewModel.setVariant(variant) })
            }
            PlannedFeatureNotice(phase = 5, description = stringResource(R.string.settings_variant_engines_planned))
        }
    }
}

@Composable
private fun VariantRow(variant: PokerVariant, selected: Boolean, onSelect: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text(variant.label(), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (variant.usesCommunityCards) {
                        stringResource(R.string.settings_variant_board_desc, variant.cardsPerPlayer, variant.communityCards, variant.maxPlayers, variant.deckType.size)
                    } else {
                        stringResource(R.string.settings_variant_noboard_desc, variant.cardsPerPlayer, variant.maxPlayers, variant.deckType.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Model & detection

@Composable
fun DetectionSettingsRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsScaffold(stringResource(R.string.settings_detection_title), onBack) {
        val s = settings
        if (s == null) {
            CircularProgressIndicator(Modifier.padding(32.dp))
        } else Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
            ModelStatusCard(modelStatus, viewModel.modelName)
            FpsSlider(s.analysisFps, viewModel::setAnalysisFps)
            ThresholdSlider(
                title = stringResource(R.string.settings_min_confidence),
                help = stringResource(R.string.settings_min_confidence_help),
                value = s.confidenceThresholds.minimumDetection,
                onCommit = { viewModel.setThresholds(it, s.confidenceThresholds.autoConfirm, editedMinimum = true) },
            )
            ThresholdSlider(
                title = stringResource(R.string.settings_auto_confirm),
                help = stringResource(R.string.settings_auto_confirm_help),
                value = s.confidenceThresholds.autoConfirm,
                onCommit = { viewModel.setThresholds(s.confidenceThresholds.minimumDetection, it, editedMinimum = false) },
            )
            OutlinedButton(onClick = viewModel::resetDetectionDefaults) { Text(stringResource(R.string.settings_reset_defaults)) }
        }
    }
}

@Composable
private fun ModelStatusCard(status: DetectorStatus, modelName: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.settings_model_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
            }
            val (chip, desc) = when (status) {
                DetectorStatus.Loading -> stringResource(R.string.settings_model_loading) to ""
                DetectorStatus.Ready -> stringResource(R.string.settings_model_ready, modelName) to stringResource(R.string.settings_model_ready_desc)
                DetectorStatus.ModelMissing -> stringResource(R.string.settings_model_none) to stringResource(R.string.settings_model_none_desc)
                is DetectorStatus.Failed -> stringResource(R.string.settings_model_failed) to status.message
            }
            StatusChip(chip)
            if (desc.isNotEmpty()) {
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FpsSlider(current: Int, onCommit: (Int) -> Unit) {
    var value by remember(current) { mutableFloatStateOf(current.toFloat()) }
    val range = AppSettings.ANALYSIS_FPS_RANGE
    Column {
        Text(stringResource(R.string.settings_fps, value.roundToInt()), style = MaterialTheme.typography.titleMedium)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onCommit(value.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
        )
        Text(stringResource(R.string.settings_fps_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThresholdSlider(title: String, help: String, value: Float, onCommit: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$title: ${(v * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = v,
            onValueChange = { v = (it * 20).roundToInt() / 20f }, // 5% steps
            onValueChangeFinished = { onCommit(v) },
            valueRange = 0f..1f,
        )
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- Privacy & storage

@Composable
fun PrivacySettingsRoute(onBack: () -> Unit, viewModel: PrivacyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }

    SettingsScaffold(stringResource(R.string.settings_privacy_title), onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        R.string.settings_privacy_fact_local,
                        R.string.settings_privacy_fact_network,
                        R.string.settings_privacy_fact_private_storage,
                        R.string.settings_privacy_fact_audio,
                    ).forEach { fact ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(fact), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            Text(
                stringResource(
                    R.string.settings_storage_used,
                    state.usageBytes?.let { formatBytes(it) } ?: "…",
                ),
                style = MaterialTheme.typography.titleMedium,
            )

            state.error?.let { ErrorBanner(it.userMessage()) }
            if (state.cleared) Text(stringResource(R.string.settings_cleared), color = MaterialTheme.colorScheme.primary)

            Button(
                onClick = { confirm = true },
                enabled = !state.clearing,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.settings_delete_all)) }

            PlannedFeatureNotice(phase = 8, description = stringResource(R.string.settings_export_planned))
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.settings_delete_all_confirm_title)) },
            text = { Text(stringResource(R.string.settings_delete_all_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; viewModel.clearAll() }) { Text(stringResource(R.string.settings_delete_all)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}
