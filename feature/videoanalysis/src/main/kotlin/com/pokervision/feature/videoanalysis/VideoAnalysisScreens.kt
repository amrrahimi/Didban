package com.pokervision.feature.videoanalysis

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.pokervision.core.domain.media.Keyframe
import com.pokervision.core.model.media.MediaAsset
import com.pokervision.core.ui.component.EmptyState
import com.pokervision.core.ui.component.ErrorBanner
import com.pokervision.core.ui.component.PlannedFeatureNotice
import com.pokervision.core.ui.component.PokerTopBar
import com.pokervision.core.ui.text.formatBytes
import com.pokervision.core.ui.text.formatDateTime
import com.pokervision.core.ui.text.formatDuration
import com.pokervision.core.ui.text.userMessage
import java.io.File

@Composable
fun VideoListRoute(
    onBack: () -> Unit,
    onOpenVideo: (Long) -> Unit,
    viewModel: VideoListViewModel = hiltViewModel(),
) {
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.import(it.toString()) }
    }
    LaunchedEffect(importState.lastImportedId) {
        importState.lastImportedId?.let {
            viewModel.consumeImported()
            onOpenVideo(it)
        }
    }

    Scaffold(
        topBar = { PokerTopBar(stringResource(R.string.video_title), onBack = onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                icon = { Icon(Icons.Outlined.VideoCall, contentDescription = null) },
                text = { Text(stringResource(R.string.video_import)) },
                expanded = !importState.inProgress,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (importState.inProgress) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.video_importing),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            importState.error?.let {
                ErrorBanner(it.userMessage(), Modifier.padding(16.dp).clickable { viewModel.dismissError() })
            }
            when (val list = videos) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> if (list.isEmpty()) {
                    EmptyState(stringResource(R.string.video_empty))
                } else {
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(list, key = { it.id }) { asset ->
                            ListItem(
                                headlineContent = { Text(asset.displayName) },
                                supportingContent = {
                                    Text(
                                        listOfNotNull(
                                            asset.durationMillis?.let { formatDuration(it) },
                                            formatBytes(asset.sizeBytes),
                                            formatDateTime(asset.createdAtMillis),
                                        ).joinToString(" · "),
                                    )
                                },
                                leadingContent = { VideoThumbnail(asset) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                                modifier = Modifier.clickable { onOpenVideo(asset.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoDetailRoute(onBack: () -> Unit, viewModel: VideoDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyframes by viewModel.keyframes.collectAsStateWithLifecycle()
    val seek by viewModel.seek.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state) { if (state is VideoDetailState.Deleted) onBack() }

    Scaffold(
        topBar = {
            PokerTopBar(
                title = stringResource(R.string.video_detail_title),
                onBack = onBack,
                actions = {
                    if (state is VideoDetailState.Loaded) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.video_delete))
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                VideoDetailState.Loading, VideoDetailState.Deleted ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is VideoDetailState.Failed -> ErrorBanner(s.error.userMessage(), Modifier.padding(16.dp))
                is VideoDetailState.Loaded -> VideoDetailContent(
                    asset = s.asset,
                    keyframes = keyframes,
                    seek = seek,
                    onExtract = viewModel::startExtraction,
                    onCancel = viewModel::cancelExtraction,
                    onKeyframeClick = viewModel::seekTo,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.video_delete_confirm_title)) },
            text = { Text(stringResource(R.string.video_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text(stringResource(R.string.video_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.video_cancel)) }
            },
        )
    }
}

@Composable
private fun VideoDetailContent(
    asset: MediaAsset,
    keyframes: KeyframeUiState,
    seek: SeekRequest?,
    onExtract: () -> Unit,
    onCancel: () -> Unit,
    onKeyframeClick: (Long) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VideoPlayer(asset, seek)
        Text(asset.displayName, style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(
                asset.durationMillis?.let { formatDuration(it) },
                if (asset.width != null && asset.height != null) "${asset.width} × ${asset.height}" else null,
                formatBytes(asset.sizeBytes),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KeyframeSection(keyframes, onExtract, onCancel, onKeyframeClick)
        PlannedFeatureNotice(phase = 3, description = stringResource(R.string.video_analysis_planned))
    }
}

@Composable
private fun VideoThumbnail(asset: MediaAsset) {
    val thumb = asset.thumbnailPath
    if (thumb != null) {
        AsyncImage(
            model = File(thumb),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)),
        )
    } else {
        Icon(Icons.Outlined.Movie, contentDescription = null, modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun KeyframeSection(
    state: KeyframeUiState,
    onExtract: () -> Unit,
    onCancel: () -> Unit,
    onKeyframeClick: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.video_keyframes_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.video_keyframes_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val frames: List<Keyframe> = when (state) {
            is KeyframeUiState.Idle -> state.saved
            is KeyframeUiState.Running -> state.keyframes
            is KeyframeUiState.Done -> state.keyframes
            is KeyframeUiState.Failed -> state.partial
        }
        when (state) {
            is KeyframeUiState.Running -> {
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { state.sampled.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(
                    stringResource(R.string.video_keyframes_progress, state.sampled, state.total, state.keyframes.size),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.video_keyframes_cancel)) }
            }
            is KeyframeUiState.Done -> {
                Text(
                    stringResource(R.string.video_keyframes_done, state.keyframes.size, state.sampled),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.undecodable > 0) {
                    Text(
                        stringResource(R.string.video_keyframes_undecodable, state.undecodable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(onClick = onExtract) { Text(stringResource(R.string.video_keyframes_rerun)) }
            }
            is KeyframeUiState.Failed -> {
                ErrorBanner(state.error.userMessage())
                Button(onClick = onExtract) { Text(stringResource(R.string.video_keyframes_extract)) }
            }
            is KeyframeUiState.Idle -> Button(onClick = onExtract) {
                Text(stringResource(if (state.saved.isEmpty()) R.string.video_keyframes_extract else R.string.video_keyframes_rerun))
            }
        }
        if (frames.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(frames, key = { it.timestampMs }) { kf ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onKeyframeClick(kf.timestampMs) },
                    ) {
                        AsyncImage(
                            model = File(kf.imagePath),
                            contentDescription = stringResource(R.string.video_keyframe_at, formatDuration(kf.timestampMs)),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 128.dp, height = 80.dp).clip(RoundedCornerShape(6.dp)),
                        )
                        Text(formatDuration(kf.timestampMs), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Plays a local file with Media3. Pauses when the screen goes to background; released on dispose. */
@Composable
private fun VideoPlayer(asset: MediaAsset, seek: SeekRequest?) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(asset.localPath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(asset.localPath))))
            prepare()
        }
    }
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }
    LaunchedEffect(seek) {
        seek?.let {
            player.seekTo(it.positionMs)
            player.pause()
        }
    }
    // Local copies: smart casts don't apply to properties declared in another module.
    val w = asset.width
    val h = asset.height
    val aspect = if (w != null && h != null && h > 0) w.toFloat() / h else 16f / 9f
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.5f, 2.5f)),
    )
}
