package com.pokervision.feature.imageanalysis

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.pokervision.core.ui.component.EmptyState
import com.pokervision.core.ui.component.ErrorBanner
import com.pokervision.core.ui.component.PlannedFeatureNotice
import com.pokervision.core.ui.component.PokerTopBar
import com.pokervision.core.ui.text.formatBytes
import com.pokervision.core.ui.text.formatDateTime
import com.pokervision.core.ui.text.userMessage
import java.io.File

@Composable
fun ImageListRoute(
    onBack: () -> Unit,
    onOpenImage: (Long) -> Unit,
    viewModel: ImageListViewModel = hiltViewModel(),
) {
    val images by viewModel.images.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.import(it.toString()) }
    }
    LaunchedEffect(importState.lastImportedId) {
        importState.lastImportedId?.let {
            viewModel.consumeImported()
            onOpenImage(it)
        }
    }

    Scaffold(
        topBar = { PokerTopBar(stringResource(R.string.image_title), onBack = onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                icon = { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null) },
                text = { Text(stringResource(R.string.image_import)) },
                expanded = !importState.inProgress,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (importState.inProgress) LinearProgressIndicator(Modifier.fillMaxWidth())
            importState.error?.let {
                ErrorBanner(it.userMessage(), Modifier.padding(16.dp).clickable { viewModel.dismissError() })
            }
            when (val list = images) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> if (list.isEmpty()) {
                    EmptyState(stringResource(R.string.image_empty))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(120.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(list, key = { it.id }) { asset ->
                            AsyncImage(
                                model = File(asset.localPath),
                                contentDescription = asset.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpenImage(asset.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ImageDetailRoute(onBack: () -> Unit, viewModel: ImageDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state) { if (state is ImageDetailState.Deleted) onBack() }

    Scaffold(
        topBar = {
            PokerTopBar(
                title = stringResource(R.string.image_detail_title),
                onBack = onBack,
                actions = {
                    if (state is ImageDetailState.Loaded) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.image_delete))
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                ImageDetailState.Loading, ImageDetailState.Deleted ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ImageDetailState.Failed -> ErrorBanner(s.error.userMessage(), Modifier.padding(16.dp))
                is ImageDetailState.Loaded -> ImageDetailContent(s.asset)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.image_delete_confirm_title)) },
            text = { Text(stringResource(R.string.image_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text(stringResource(R.string.image_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.image_cancel)) }
            },
        )
    }
}

@Composable
private fun ImageDetailContent(asset: MediaAsset) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = File(asset.localPath),
            contentDescription = asset.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).clip(RoundedCornerShape(12.dp)),
        )
        MetadataRow(stringResource(R.string.image_meta_name), asset.displayName)
        MetadataRow(
            stringResource(R.string.image_meta_resolution),
            if (asset.width != null && asset.height != null) "${asset.width} × ${asset.height}" else "—",
        )
        MetadataRow(stringResource(R.string.image_meta_size), formatBytes(asset.sizeBytes))
        MetadataRow(stringResource(R.string.image_meta_added), formatDateTime(asset.createdAtMillis))
        PlannedFeatureNotice(phase = 3, description = stringResource(R.string.image_detection_planned))
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
