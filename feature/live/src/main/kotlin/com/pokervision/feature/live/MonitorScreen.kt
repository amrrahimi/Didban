package com.pokervision.feature.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pokervision.core.domain.vision.TableRules
import com.pokervision.core.domain.vision.TableState
import com.pokervision.core.model.detection.BoundingBox
import com.pokervision.core.ui.component.ErrorBanner
import com.pokervision.core.ui.component.PlayingCardView
import com.pokervision.core.ui.component.PokerTopBar
import com.pokervision.core.ui.theme.PokerColors
import com.pokervision.core.vision.DetectorStatus
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Frames per second requested from the camera; detection itself takes ~0.5-1 s per frame. */
private const val MONITOR_FPS = 2

private val MINE_COLOR = Color(0xFF4FC3F7)
private val BOARD_COLOR = Color(0xFFFFD54F)

@Composable
fun MonitorRoute(onBack: () -> Unit, viewModel: MonitorViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    val table by viewModel.table.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            PokerTopBar(
                stringResource(R.string.monitor_title),
                onBack = onBack,
                actions = { VariantMenu(table.variant, viewModel::setVariant) },
            )
        },
        containerColor = Color.Black,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (hasPermission) {
                MonitorContent(viewModel)
            } else {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.monitor_intro), color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.live_permission_grant)) }
                }
            }
        }
    }
}

@Composable
private fun MonitorContent(viewModel: MonitorViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val table by viewModel.table.collectAsStateWithLifecycle()
    val analysis by viewModel.analysis.collectAsStateWithLifecycle()

    // The phone sits on a stand for a whole session: keep its screen on.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val controller = remember { LiveCameraController(context.applicationContext, MONITOR_FPS) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    LaunchedEffect(previewView) {
        previewView?.let { controller.bind(lifecycleOwner, it, viewModel::onFrame) { viewModel.onCameraError() } }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val camera: @Composable (Modifier) -> Unit = { mod ->
            Box(mod, contentAlignment = Alignment.Center) {
                Box(Modifier.aspectRatio(ui.frameAspect, matchHeightConstraintsFirst = landscape || ui.frameAspect > 1f)) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            }.also { previewView = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    RegionOverlay(ui, onRegionDrawn = viewModel::setRegion)
                }
            }
        }
        val panel: @Composable (Modifier) -> Unit = { mod ->
            MonitorPanel(ui, table, analysis, viewModel, mod)
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                camera(Modifier.weight(1f).fillMaxHeight())
                panel(Modifier.width(300.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                camera(Modifier.weight(1f).fillMaxWidth())
                panel(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Draws both areas and the card boxes; while editing, a drag on the preview draws the new area. */
@Composable
private fun RegionOverlay(ui: MonitorUiState, onRegionDrawn: (BoundingBox) -> Unit) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val editing = ui.editing
    val drawModifier = if (editing != null) {
        Modifier.pointerInput(editing) {
            detectDragGestures(
                onDragStart = { dragStart = it; dragEnd = it },
                onDrag = { change, amount -> dragEnd = (dragEnd ?: change.position) + amount },
                onDragEnd = {
                    val a = dragStart
                    val b = dragEnd
                    if (a != null && b != null) {
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val l = (min(a.x, b.x) / w).coerceIn(0f, 1f)
                        val t = (min(a.y, b.y) / h).coerceIn(0f, 1f)
                        val r = (max(a.x, b.x) / w).coerceIn(0f, 1f)
                        val btm = (max(a.y, b.y) / h).coerceIn(0f, 1f)
                        if (r - l > 0.03f && btm - t > 0.03f) onRegionDrawn(BoundingBox(l, t, r, btm))
                    }
                    dragStart = null
                    dragEnd = null
                },
            )
        }
    } else {
        Modifier
    }
    Canvas(Modifier.fillMaxSize().then(drawModifier)) {
        if (editing != RegionKind.MINE) area(ui.regions.mine, MINE_COLOR)
        if (editing != RegionKind.BOARD) area(ui.regions.board, BOARD_COLOR)
        ui.boxes.forEach { b ->
            drawRect(
                color = if (b.accepted) PokerColors.ConfidenceHigh else PokerColors.ConfidenceMedium,
                topLeft = Offset(b.box.left * size.width, b.box.top * size.height),
                size = Size(b.box.width * size.width, b.box.height * size.height),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        val a = dragStart
        val b = dragEnd
        if (editing != null && a != null && b != null) {
            drawRect(
                color = if (editing == RegionKind.MINE) MINE_COLOR else BOARD_COLOR,
                topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
                size = Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
    }
}

private fun DrawScope.area(box: BoundingBox, color: Color) {
    drawRect(
        color = color,
        topLeft = Offset(box.left * size.width, box.top * size.height),
        size = Size(box.width * size.width, box.height * size.height),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
    )
}

@Composable
private fun MonitorPanel(
    ui: MonitorUiState,
    table: TableState,
    analysis: AnalysisState,
    viewModel: MonitorViewModel,
    modifier: Modifier,
) {
    Column(
        modifier.background(Color.Black).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            ui.cameraError -> ErrorBanner(stringResource(R.string.live_camera_error))
            ui.detector is DetectorStatus.Loading -> Text(stringResource(R.string.live_model_loading), color = Color.White)
            ui.detector is DetectorStatus.ModelMissing -> ErrorBanner(stringResource(R.string.live_model_missing))
            ui.detector is DetectorStatus.Failed -> ErrorBanner(stringResource(R.string.live_model_failed))
        }
        if (ui.editing != null) {
            Text(
                stringResource(if (ui.editing == RegionKind.MINE) R.string.monitor_draw_mine else R.string.monitor_draw_board),
                color = if (ui.editing == RegionKind.MINE) MINE_COLOR else BOARD_COLOR,
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = viewModel::cancelEditing) { Text(stringResource(R.string.live_cancel)) }
        } else {
            MonitorDetails(ui, table, analysis, viewModel)
        }
    }
}

@Composable
private fun MonitorDetails(ui: MonitorUiState, table: TableState, analysis: AnalysisState, viewModel: MonitorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AdvicePanel(table, analysis)

        CardsLine(stringResource(R.string.monitor_mine), table.mine, MINE_COLOR)
        if (TableRules.tableScanAvailableFor(table)) {
            CardsLine(stringResource(if (table.variant.usesCommunityCards) R.string.live_board else R.string.monitor_upcards),
                if (table.variant.usesCommunityCards) table.board else table.seen, BOARD_COLOR)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.live_opponents), color = Color.White, modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.setOpponents(table.opponents - 1) }, enabled = table.opponents > 1) {
                Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.live_opponent_folded), tint = Color.White)
            }
            Text(table.opponents.toString(), color = Color.White, style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { viewModel.setOpponents(table.opponents + 1) }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.live_more_opponents), tint = Color.White)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.startEditing(RegionKind.MINE) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.monitor_set_mine), color = MINE_COLOR)
            }
            OutlinedButton(onClick = { viewModel.startEditing(RegionKind.BOARD) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.monitor_set_board), color = BOARD_COLOR)
            }
        }
        TextButton(onClick = viewModel::resetRegions) { Text(stringResource(R.string.monitor_reset_areas)) }
        if (ui.secondsPerReading > 0f) {
            Text(
                stringResource(R.string.monitor_speed, String.format(Locale.getDefault(), "%.1f", ui.secondsPerReading)),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(stringResource(R.string.monitor_tips), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CardsLine(title: String, cards: List<com.pokervision.core.model.card.PlayingCard>, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp))
        if (cards.isEmpty()) Text("—", color = Color.White.copy(alpha = 0.5f))
        cards.forEach { PlayingCardView(it) }
    }
}
