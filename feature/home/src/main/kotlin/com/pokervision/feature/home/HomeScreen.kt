package com.pokervision.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pokervision.core.domain.usecase.DashboardSummary
import com.pokervision.core.ui.component.FeatureTile
import com.pokervision.core.ui.component.PokerTopBar
import com.pokervision.core.ui.text.label

/** Destinations reachable from the dashboard. The app module maps them to navigation routes. */
enum class HomeEntry {
    ADVISOR, LIVE_CAMERA, IMAGE_ANALYSIS, HISTORY, STATISTICS,
    VARIANT_SELECTION, DETECTION_SETTINGS, PRIVACY_SETTINGS,
}

private data class TileSpec(
    val entry: HomeEntry,
    val title: Int,
    val subtitle: Int,
    val icon: ImageVector,
    /** Phase in which the core function of this screen arrives; null = available now. */
    val plannedPhase: Int? = null,
)

private val TILES = listOf(
    TileSpec(HomeEntry.ADVISOR, R.string.home_tile_advisor, R.string.home_tile_advisor_sub, Icons.Outlined.Lightbulb),
    TileSpec(HomeEntry.LIVE_CAMERA, R.string.home_tile_camera, R.string.home_tile_camera_sub, Icons.Outlined.PhotoCamera),
    TileSpec(HomeEntry.IMAGE_ANALYSIS, R.string.home_tile_image, R.string.home_tile_image_sub, Icons.Outlined.Image),
    TileSpec(HomeEntry.HISTORY, R.string.home_tile_history, R.string.home_tile_history_sub, Icons.Outlined.History),
    TileSpec(HomeEntry.STATISTICS, R.string.home_tile_stats, R.string.home_tile_stats_sub, Icons.Outlined.BarChart, plannedPhase = 8),
    TileSpec(HomeEntry.VARIANT_SELECTION, R.string.home_tile_variant, R.string.home_tile_variant_sub, Icons.Outlined.Style),
    TileSpec(HomeEntry.DETECTION_SETTINGS, R.string.home_tile_detection, R.string.home_tile_detection_sub, Icons.Outlined.Tune),
    TileSpec(HomeEntry.PRIVACY_SETTINGS, R.string.home_tile_privacy, R.string.home_tile_privacy_sub, Icons.Outlined.PrivacyTip),
)

@Composable
fun HomeRoute(
    onOpen: (HomeEntry) -> Unit,
    topBarActions: @Composable () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state = state, onOpen = onOpen, topBarActions = topBarActions)
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpen: (HomeEntry) -> Unit,
    topBarActions: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = { PokerTopBar(title = stringResource(R.string.home_title), actions = topBarActions) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                when (state) {
                    HomeUiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is HomeUiState.Ready -> SummaryCard(state.summary)
                }
            }
            items(TILES, key = { it.entry }) { tile ->
                FeatureTile(
                    title = stringResource(tile.title),
                    subtitle = stringResource(tile.subtitle),
                    icon = tile.icon,
                    onClick = { onOpen(tile.entry) },
                    badge = tile.plannedPhase?.let { stringResource(R.string.home_badge_phase, it) },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.home_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: DashboardSummary) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.home_default_variant, summary.defaultVariant.label()),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Stat(stringResource(R.string.home_stat_images), summary.imageCount)
                Stat(stringResource(R.string.home_stat_games), summary.gameCount)
                Stat(stringResource(R.string.home_stat_hands), summary.handCount)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int) {
    Column {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
