package com.pokervision.feature.live.learn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pokervision.core.domain.vision.CaptureTarget
import com.pokervision.core.domain.vision.DeckCapturePlan
import com.pokervision.core.domain.vision.FocusRegion
import com.pokervision.core.ui.component.ErrorBanner
import com.pokervision.core.ui.component.PokerTopBar
import com.pokervision.core.ui.text.formatBytes
import com.pokervision.core.ui.theme.PokerColors
import com.pokervision.feature.live.LiveCameraController
import com.pokervision.feature.live.R

/** Frames per second delivered while capturing; shots are additionally spaced by MIN_SHOT_INTERVAL_MS. */
private const val CAPTURE_FPS = 3

@Composable
fun LearnDeckRoute(onBack: () -> Unit, viewModel: LearnDeckViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    // Open the share sheet once an export is ready.
    LaunchedEffect(state.exportFile) {
        val file = state.exportFile ?: return@LaunchedEffect
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.learn_share_title)))
        viewModel.exportHandled()
    }

    Scaffold(
        topBar = { PokerTopBar(stringResource(R.string.learn_title), onBack = onBack) },
        containerColor = Color.Black,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (!hasPermission) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.learn_intro), color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.live_permission_grant)) }
                }
            } else {
                LearnContent(
                    state = state,
                    viewModel = viewModel,
                    onExport = {
                        val version = runCatching {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }.getOrNull() ?: "unknown"
                        viewModel.export(version)
                    },
                    onDeleteAll = { confirmDelete = true },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.learn_delete_title)) },
            text = { Text(stringResource(R.string.learn_delete_body)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deleteAll() }) { Text(stringResource(R.string.live_reject_yes)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.live_cancel)) } },
        )
    }
}

@Composable
private fun LearnContent(
    state: LearnUiState,
    viewModel: LearnDeckViewModel,
    onExport: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { LiveCameraController(context.applicationContext, CAPTURE_FPS) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    LaunchedEffect(previewView) {
        previewView?.let { controller.bind(lifecycleOwner, it, viewModel::onFrame) { } }
    }

    Column(Modifier.fillMaxSize()) {
        // Instruction for the current target
        Column(Modifier.fillMaxWidth().background(Color.Black).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val target = state.target
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (target) {
                    is CaptureTarget.Card -> Text(
                        "${target.card.rank.symbol.let { if (it == 'T') "10" else it.toString() }}${target.card.suit.glyph}",
                        color = if (target.card.suit.isRed) PokerColors.CardRed else Color.White,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    CaptureTarget.Background -> Text("∅", color = Color.White, fontSize = 40.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(if (target is CaptureTarget.Background) R.string.learn_instruction_background else R.string.learn_instruction_card),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LinearProgressIndicator(
                progress = { state.shotsForTarget.toFloat() / DeckCapturePlan.SHOTS_PER_TARGET },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.learn_progress, state.shotsForTarget, DeckCapturePlan.SHOTS_PER_TARGET, state.completed, state.total),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        // Camera with the card-area square
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            setOnTouchListener { view, event ->
                                if (event.action == android.view.MotionEvent.ACTION_UP) {
                                    controller.focusAt(this, event.x, event.y)
                                    view.performClick()
                                }
                                true
                            }
                        }.also { previewView = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(Modifier.fillMaxSize()) {
                    val crop = FocusRegion.centerSquare(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                    drawRect(
                        color = if (state.capturing) PokerColors.ConfidenceHigh else Color.White,
                        topLeft = Offset(crop.left.toFloat(), crop.top.toFloat()),
                        size = Size(crop.width.toFloat(), crop.height.toFloat()),
                        style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                    )
                }
            }
        }

        // Controls
        Column(Modifier.fillMaxWidth().background(Color.Black).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.exportFailed) ErrorBanner(stringResource(R.string.learn_export_failed))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = viewModel::previous) { Text("‹") }
                Button(onClick = viewModel::startCapture, enabled = !state.capturing, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            when {
                                state.capturing -> R.string.learn_capturing
                                state.shotsForTarget >= DeckCapturePlan.SHOTS_PER_TARGET -> R.string.learn_retake
                                else -> R.string.learn_capture
                            },
                        ),
                    )
                }
                OutlinedButton(onClick = viewModel::skip) { Text("›") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onExport, enabled = state.completed > 0 && !state.exporting, modifier = Modifier.weight(1f)) {
                    if (state.exporting) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.learn_send, formatBytes(state.storedBytes)))
                }
                TextButton(onClick = onDeleteAll) { Text(stringResource(R.string.learn_delete_all)) }
            }
            Text(stringResource(R.string.learn_tips), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        }
    }
}
