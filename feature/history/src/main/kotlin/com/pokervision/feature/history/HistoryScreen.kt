package com.pokervision.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.pokervision.core.model.media.MediaAsset
import com.pokervision.core.model.media.MediaType
import com.pokervision.core.ui.component.EmptyState
import com.pokervision.core.ui.component.ErrorBanner
import com.pokervision.core.ui.component.PokerTopBar
import com.pokervision.core.ui.text.formatBytes
import com.pokervision.core.ui.text.formatDateTime
import com.pokervision.core.ui.text.label
import com.pokervision.core.ui.text.userMessage
import java.io.File

@Composable
fun HistoryRoute(
    onBack: () -> Unit,
    onOpenImage: (Long) -> Unit,
    onOpenVideo: (Long) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = { PokerTopBar(stringResource(R.string.history_title), onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.history_tab_media)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.history_tab_games)) })
            }
            error?.let { ErrorBanner(it.userMessage(), Modifier.padding(16.dp).clickable { viewModel.dismissError() }) }
            val c = content
            when {
                c == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                tab == 0 -> MediaList(
                    media = c.media,
                    onOpen = { if (it.type == MediaType.IMAGE) onOpenImage(it.id) else onOpenVideo(it.id) },
                    onDelete = { viewModel.deleteMedia(it.id) },
                )
                else -> GameList(c, onDelete = viewModel::deleteGame)
            }
        }
    }
}

@Composable
private fun MediaList(media: List<MediaAsset>, onOpen: (MediaAsset) -> Unit, onDelete: (MediaAsset) -> Unit) {
    if (media.isEmpty()) return EmptyState(stringResource(R.string.history_media_empty))
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(media, key = { it.id }) { asset ->
            ListItem(
                headlineContent = { Text(asset.displayName) },
                supportingContent = { Text("${formatDateTime(asset.createdAtMillis)} · ${formatBytes(asset.sizeBytes)}") },
                leadingContent = {
                    if (asset.type == MediaType.IMAGE) {
                        AsyncImage(
                            model = File(asset.localPath),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
                        )
                    } else {
                        Icon(Icons.Outlined.Movie, contentDescription = null, modifier = Modifier.size(48.dp))
                    }
                },
                trailingContent = {
                    IconButton(onClick = { onDelete(asset) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.history_delete))
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                modifier = Modifier.clickable { onOpen(asset) },
            )
        }
    }
}

@Composable
private fun GameList(content: HistoryContent, onDelete: (String) -> Unit) {
    if (content.games.isEmpty()) return EmptyState(stringResource(R.string.history_games_empty))
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(content.games, key = { it.id }) { game ->
            ListItem(
                headlineContent = { Text(game.variant.label()) },
                supportingContent = {
                    Text(stringResource(R.string.history_game_summary, formatDateTime(game.startedAtMillis), game.playerCount))
                },
                leadingContent = { Icon(Icons.Outlined.Casino, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { onDelete(game.id) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.history_delete))
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
            )
        }
    }
}
