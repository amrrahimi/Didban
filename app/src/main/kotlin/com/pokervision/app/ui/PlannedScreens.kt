package com.pokervision.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pokervision.app.R
import com.pokervision.core.ui.component.PlannedFeatureNotice
import com.pokervision.core.ui.component.PokerTopBar

/**
 * Screens whose functionality is scheduled for later phases. They are reachable so navigation is
 * complete, but deliberately show no calculated values: nothing here is a fake implementation.
 */
@Composable
private fun PlannedScreen(title: String, phase: Int, description: String, intro: String, onBack: () -> Unit) {
    Scaffold(
        topBar = { PokerTopBar(title, onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(intro, style = MaterialTheme.typography.bodyLarge)
            PlannedFeatureNotice(phase = phase, description = description)
        }
    }
}

@Composable
fun StatisticsPlaceholderScreen(onBack: () -> Unit) = PlannedScreen(
    title = stringResource(R.string.statistics_title),
    phase = 8,
    intro = stringResource(R.string.statistics_intro),
    description = stringResource(R.string.statistics_planned),
    onBack = onBack,
)
