@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pokervision.feature.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pokervision.core.domain.usecase.HandAnalysis
import com.pokervision.core.domain.vision.ScanTarget
import com.pokervision.core.domain.vision.TableRules
import com.pokervision.core.domain.vision.TableState
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.model.card.Rank
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.poker.advice.LiveAction
import com.pokervision.core.poker.engine.EngineIssue
import com.pokervision.core.poker.engine.PokerEngines
import com.pokervision.core.poker.holdem.HandCategory
import com.pokervision.core.ui.component.ErrorBanner
import com.pokervision.core.ui.component.PlayingCardView
import com.pokervision.core.ui.component.PokerTopBar
import com.pokervision.core.ui.text.label
import com.pokervision.core.ui.theme.PokerColors
import com.pokervision.core.vision.DetectorStatus
import java.util.Locale

@Composable
fun LiveRoute(onBack: () -> Unit, viewModel: ScanViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    val table by viewModel.table.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            PokerTopBar(
                title = stringResource(R.string.live_title),
                onBack = onBack,
                actions = { VariantMenu(table.variant, viewModel::setVariant) },
            )
        },
        containerColor = Color.Black,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (hasPermission) {
                ScanContent(viewModel)
            } else {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.live_permission), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.live_permission_grant)) }
                }
            }
        }
    }
}

@Composable
internal fun VariantMenu(current: PokerVariant, onSelect: (PokerVariant) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(current.label() + " ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PokerVariant.entries.forEach { v ->
                DropdownMenuItem(
                    text = { Text((if (v == current) "✓ " else "") + v.label()) },
                    onClick = {
                        open = false
                        if (v != current) onSelect(v)
                    },
                )
            }
        }
    }
}

@Composable
private fun ScanContent(viewModel: ScanViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val table by viewModel.table.collectAsStateWithLifecycle()
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val analysis by viewModel.analysis.collectAsStateWithLifecycle()
    val detector by viewModel.detectorStatus.collectAsStateWithLifecycle()
    val learned by viewModel.learnedExamples.collectAsStateWithLifecycle()
    var correcting by remember { mutableStateOf<Int?>(null) }
    var teaching by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    val manualOnly by viewModel.manualOnly.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<ScanTarget?>(null) }
    var cameraReady by remember { mutableStateOf(false) }

    val controller = remember { ScanCameraController(context.applicationContext) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraError by remember { mutableStateOf(false) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    // The camera runs only while a photo is about to be taken: it turns off once the photo is in,
    // while cards are chosen by hand, and in manual mode.
    val cameraWanted = !manualOnly && picking == null && phase !is ScanPhase.Result
    LaunchedEffect(previewView, cameraWanted) {
        val view = previewView
        if (cameraWanted && view != null) {
            controller.bind(lifecycleOwner, view, onReady = { cameraReady = true }) { cameraError = true }
        } else if (!cameraWanted) {
            cameraReady = false
            controller.stop()
        }
    }

    val scan: (ScanTarget) -> Unit = { target ->
        viewModel.onScanStarted(target)
        controller.capture(onPhoto = { viewModel.onPhoto(target, it) }, onFailure = { viewModel.onScanFailed() })
    }

    Column(Modifier.fillMaxSize()) {
        // Camera preview, or the analysed photo with boxes.
        if (!manualOnly) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FIT_CENTER
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
                    onRelease = { if (previewView === it) previewView = null },
                    modifier = Modifier.fillMaxSize(),
                )
                when (val p = phase) {
                    is ScanPhase.Result -> PhotoWithBoxes(
                        p,
                        onBoxTap = { correcting = it },
                        onEmptyTap = { x, y -> teaching = x to y },
                    )
                    is ScanPhase.Working -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.scan_reading), color = Color.White)
                        }
                    }
                    else -> Unit
                }
            }
            StatusLine(detector, cameraError, phase, Modifier.align(Alignment.TopCenter))
        }

        // Cards + advice + controls
        Column(
            Modifier.fillMaxWidth().then(if (manualOnly) Modifier.weight(1f) else Modifier)
                .background(Color.Black).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (phase as? ScanPhase.Result)?.let { ResultBar(it, viewModel::acceptPending, viewModel::closeResult) }
            FilterChip(
                selected = manualOnly,
                onClick = { viewModel.setManualOnly(!manualOnly) },
                label = { Text(stringResource(if (manualOnly) R.string.scan_manual_on else R.string.scan_manual_off)) },
            )
            CardsArea(table, onRemove = viewModel::remove, onPick = { picking = it })
            AdvicePanel(table, analysis)
            if (table.variant == PokerVariant.FIVE_CARD_DRAW) {
                FilterChip(
                    selected = table.drawDone,
                    onClick = { viewModel.setDrawDone(!table.drawDone) },
                    label = { Text(stringResource(R.string.scan_draw_done)) },
                )
            }
            if (phase is ScanPhase.Result) {
                Button(onClick = viewModel::closeResult, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.scan_new_photo))
                }
            } else if (!manualOnly) {
                val busy = phase is ScanPhase.Working || detector !is DetectorStatus.Ready || !cameraReady
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scan(ScanTarget.MINE) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.scan_mine))
                    }
                    if (TableRules.tableScanAvailable(table.variant)) {
                        Button(onClick = { scan(ScanTarget.TABLE) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (TableRules.usesBoard(table.variant)) R.string.scan_board else R.string.scan_upcards))
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.live_opponents), color = Color.White, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.setOpponents(table.opponents - 1) }, enabled = table.opponents > 1) {
                    Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.live_opponent_folded), tint = Color.White)
                }
                Text(table.opponents.toString(), color = Color.White, style = MaterialTheme.typography.titleLarge)
                IconButton(
                    onClick = { viewModel.setOpponents(table.opponents + 1) },
                    enabled = table.opponents < TableRules.maxOpponents(table.variant),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.live_more_opponents), tint = Color.White)
                }
            }
            FilledTonalButton(onClick = viewModel::newHand, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.live_new_hand))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (learned == 0) stringResource(R.string.learn_memory_empty) else stringResource(R.string.learn_memory_count, learned),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (learned > 0) TextButton(onClick = { confirmForget = true }) { Text(stringResource(R.string.learn_memory_forget)) }
            }
        }
    }

    val result = phase as? ScanPhase.Result
    val box = correcting?.let { result?.boxes?.getOrNull(it) }
    if (result != null && box != null) {
        CardPickerDialog(
            variant = table.variant,
            used = emptySet(),
            title = stringResource(R.string.learn_correct_title, cardLabel(box.card)),
            onPick = { card -> viewModel.correctBox(correcting!!, card); correcting = null },
            onNotACard = { viewModel.dismissBox(correcting!!); correcting = null },
            onDismiss = { correcting = null },
        )
    } else if (correcting != null) {
        correcting = null
    }
    val tap = teaching
    if (result != null && tap != null) {
        CardPickerDialog(
            variant = table.variant,
            used = emptySet(),
            title = stringResource(R.string.learn_teach_title),
            onPick = { card -> viewModel.teachAt(tap.first, tap.second, card); teaching = null },
            onDismiss = { teaching = null },
        )
    } else if (tap != null) {
        teaching = null
    }
    picking?.let { target ->
        val free = when {
            target == ScanTarget.MINE -> TableRules.mineCapacity(table.variant) - table.mine.size
            TableRules.usesBoard(table.variant) -> TableRules.BOARD_CAPACITY - table.board.size
            else -> TableRules.SEEN_CAPACITY - table.seen.size
        }
        CardPickerDialog(
            variant = table.variant,
            used = table.allCards,
            title = stringResource(if (target == ScanTarget.MINE) R.string.live_my_cards else R.string.live_board),
            maxSelect = free.coerceAtLeast(1),
            onPickMany = { cards -> viewModel.addManual(cards, target); picking = null },
            onPick = { card -> viewModel.addManual(card, target); picking = null },
            onDismiss = { picking = null },
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.learn_memory_forget)) },
            text = { Text(stringResource(R.string.learn_memory_forget_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; viewModel.forgetLearned() }) { Text(stringResource(R.string.live_reject_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.live_reject_no)) } },
        )
    }
}

@Composable
private fun StatusLine(detector: DetectorStatus, cameraError: Boolean, phase: ScanPhase, modifier: Modifier) {
    val text = when {
        cameraError -> stringResource(R.string.live_camera_error)
        detector is DetectorStatus.ModelMissing -> stringResource(R.string.live_model_missing)
        detector is DetectorStatus.Failed -> stringResource(R.string.live_model_failed)
        phase is ScanPhase.Failed -> stringResource(R.string.scan_failed)
        else -> null
    }
    if (text != null) ErrorBanner(text, modifier.padding(8.dp))
    else if (detector is DetectorStatus.Loading) {
        Text(
            stringResource(R.string.live_model_loading),
            color = Color.White,
            modifier = modifier.background(Color.Black.copy(alpha = 0.6f)).padding(8.dp),
        )
    }
}

@Composable
private fun PhotoWithBoxes(result: ScanPhase.Result, onBoxTap: (Int) -> Unit, onEmptyTap: (Float, Float) -> Unit) {
    val image = remember(result.photo) { result.photo.asImageBitmap() }
    val measurer = rememberTextMeasurer()
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(result.photo.width.toFloat() / result.photo.height)) {
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(result) {
                        detectTapGestures { pos ->
                            val x = pos.x / size.width
                            val y = pos.y / size.height
                            val hit = result.boxes.withIndex()
                                .filter { (_, b) ->
                                    val mx = maxOf(b.box.width * 0.3f, 0.02f)
                                    val my = maxOf(b.box.height * 0.3f, 0.02f)
                                    x in (b.box.left - mx)..(b.box.right + mx) && y in (b.box.top - my)..(b.box.bottom + my)
                                }
                                .minByOrNull { (_, b) -> b.box.area }
                            if (hit != null) onBoxTap(hit.index) else onEmptyTap(x, y)
                        }
                    },
            ) {
                result.boxes.forEach { b ->
                    val color = when {
                        b.byUser -> PokerColors.Gold
                        b.accepted -> PokerColors.ConfidenceHigh
                        else -> PokerColors.ConfidenceMedium
                    }
                    val topLeft = Offset(b.box.left * size.width, b.box.top * size.height)
                    drawRect(
                        color = color,
                        topLeft = topLeft,
                        size = Size(b.box.width * size.width, b.box.height * size.height),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                    val label = cardLabel(b.card) + (if (b.accepted || b.byUser) "" else "?") + (if (b.fromMemory && !b.byUser) " •" else "")
                    val layout = measurer.measure(label, TextStyle(color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                    val lx = topLeft.x.coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
                    val ly = (topLeft.y - layout.size.height - 2.dp.toPx()).let { if (it < 0f) b.box.bottom * size.height + 2.dp.toPx() else it }
                    drawRect(color, Offset(lx - 2.dp.toPx(), ly), Size(layout.size.width + 4.dp.toPx(), layout.size.height.toFloat()))
                    drawText(layout, topLeft = Offset(lx, ly))
                }
            }
        }
    }
}

@Composable
private fun ResultBar(result: ScanPhase.Result, onAccept: (PlayingCard) -> Unit, onClose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val found = result.boxes.count { it.accepted }
        Text(
            stringResource(R.string.scan_result, found, result.millis),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (result.pending.isNotEmpty()) {
            Text(stringResource(R.string.scan_pending_help), color = PokerColors.ConfidenceMedium, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                result.pending.forEach { card ->
                    AssistChip(onClick = { onAccept(card) }, label = { Text(cardLabel(card) + " ?") })
                }
            }
        }
        Text(stringResource(R.string.learn_tap_help), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onClose) { Text(stringResource(R.string.scan_back_to_camera)) }
    }
}

private fun cardLabel(card: PlayingCard) =
    (if (card.rank == Rank.TEN) "10" else card.rank.symbol.toString()) + card.suit.glyph

@Composable
private fun CardsArea(table: TableState, onRemove: (PlayingCard) -> Unit, onPick: (ScanTarget) -> Unit) {
    var pendingRemove by remember { mutableStateOf<PlayingCard?>(null) }
    val mineCap = TableRules.mineCapacity(table.variant)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CardRow(stringResource(R.string.live_my_cards), table.mine, mineCap, { pendingRemove = it }) { onPick(ScanTarget.MINE) }
        when {
            TableRules.usesBoard(table.variant) ->
                CardRow(stringResource(R.string.live_board), table.board, TableRules.BOARD_CAPACITY, { pendingRemove = it }) { onPick(ScanTarget.TABLE) }
            table.variant == PokerVariant.SEVEN_CARD_STUD ->
                CardRow(stringResource(R.string.scan_seen), table.seen, table.seen.size + 1, { pendingRemove = it }) { onPick(ScanTarget.TABLE) }
            else -> Unit
        }
    }
    pendingRemove?.let { card ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.live_reject_question, cardLabel(card)), color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRemove(card); pendingRemove = null }) { Text(stringResource(R.string.live_reject_yes)) }
            TextButton(onClick = { pendingRemove = null }) { Text(stringResource(R.string.live_reject_no)) }
        }
    }
}

@Composable
private fun CardRow(title: String, cards: List<PlayingCard>, slots: Int, onCardTap: (PlayingCard) -> Unit, onEmptyTap: () -> Unit) {
    val addLabel = stringResource(R.string.live_add_card)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until slots) {
                val card = cards.getOrNull(i)
                if (card != null) {
                    PlayingCardView(card, Modifier.size(width = 34.dp, height = 48.dp).clickable { onCardTap(card) })
                } else {
                    Box(
                        Modifier.size(width = 34.dp, height = 48.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .clickable(onClick = onEmptyTap)
                            .semantics { contentDescription = addLabel },
                        contentAlignment = Alignment.Center,
                    ) { Text("+", color = Color.White.copy(alpha = 0.7f)) }
                }
            }
        }
    }
}

@Composable
private fun CardPickerDialog(
    variant: PokerVariant,
    used: Set<PlayingCard>,
    onPick: (PlayingCard) -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    onNotACard: (() -> Unit)? = null,
    /** Above 1: cards are toggled on and confirmed together with OK (up to this many). */
    maxSelect: Int = 1,
    onPickMany: (List<PlayingCard>) -> Unit = { it.forEach(onPick) },
) {
    val deck = PokerEngines.forVariant(variant).deck
    val multi = maxSelect > 1
    var selected by remember { mutableStateOf(listOf<PlayingCard>()) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                listOf(
                    com.pokervision.core.model.card.Suit.SPADES, com.pokervision.core.model.card.Suit.HEARTS,
                    com.pokervision.core.model.card.Suit.DIAMONDS, com.pokervision.core.model.card.Suit.CLUBS,
                ).forEach { suit ->
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Rank.entries.reversed().filter { it in deck.ranks }.forEach { rank ->
                            val card = PlayingCard(rank, suit)
                            val isUsed = card in used
                            val isSelected = card in selected
                            val color = if (suit.isRed) PokerColors.CardRed else PokerColors.CardBlack
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .background(
                                        when {
                                            isUsed -> Color.Gray
                                            isSelected -> PokerColors.Gold
                                            else -> PokerColors.Ivory
                                        },
                                        RoundedCornerShape(4.dp),
                                    )
                                    .border(if (isSelected) 2.dp else 0.dp, if (isSelected) Color.Black else Color.Transparent, RoundedCornerShape(4.dp))
                                    .clickable(enabled = !isUsed) {
                                        when {
                                            !multi -> onPick(card)
                                            isSelected -> selected = selected - card
                                            selected.size < maxSelect -> selected = selected + card
                                        }
                                    }
                                    .semantics { contentDescription = card.code },
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(rank.symbol.toString(), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(suit.glyph.toString(), color = color, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (onNotACard != null) TextButton(onClick = onNotACard) { Text(stringResource(R.string.learn_not_a_card)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.live_cancel)) }
                    if (multi) {
                        Button(onClick = { onPickMany(selected) }, enabled = selected.isNotEmpty()) {
                            Text(stringResource(R.string.picker_ok, selected.size, maxSelect))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdvicePanel(table: TableState, state: AnalysisState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state) {
            AnalysisState.Computing -> Text(stringResource(R.string.scan_computing), color = Color.White)
            is AnalysisState.Waiting -> Text(waitingText(table, state.issue), color = Color.White, style = MaterialTheme.typography.titleMedium)
            is AnalysisState.Ready -> ReadyAdvice(state.analysis)
        }
    }
}

@Composable
private fun waitingText(table: TableState, issue: EngineIssue): String = when (issue) {
    EngineIssue.HERO_CARD_COUNT -> {
        val range = PokerEngines.forVariant(table.variant).heroCards
        if (range.first == range.last) stringResource(R.string.scan_need_mine_exact, range.first)
        else stringResource(R.string.scan_need_mine_range, range.first, range.last)
    }
    EngineIssue.BOARD_SIZE -> stringResource(R.string.live_waiting_board)
    EngineIssue.CARD_NOT_IN_DECK -> stringResource(R.string.scan_issue_deck)
    EngineIssue.DUPLICATE_CARD -> stringResource(R.string.scan_issue_duplicate)
    EngineIssue.TOO_MANY_OPPONENTS, EngineIssue.NOT_ENOUGH_CARDS -> stringResource(R.string.scan_issue_opponents)
}

@Composable
private fun ReadyAdvice(a: HandAnalysis) {
    val advice = a.liveAdvice
    val (color, title) = when (advice.action) {
        LiveAction.BET_OR_RAISE -> PokerColors.ConfidenceHigh to stringResource(R.string.live_action_bet)
        LiveAction.CHECK_OR_CALL -> PokerColors.ConfidenceMedium to stringResource(R.string.live_action_call)
        LiveAction.CHECK_OR_FOLD -> PokerColors.ConfidenceLow to stringResource(R.string.live_action_fold)
    }
    a.draw?.let { d ->
        val keep = if (d.keep.isEmpty()) "—" else d.keep.joinToString(" ") { cardLabel(it) }
        Text(
            stringResource(R.string.scan_draw_advice, keep, d.discard.size),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
    // Action and chance to win side by side, both large and in the action's colour.
    val pct = percent(a.heroEquity)
    val margin = a.estimateHalfWidth?.let { stringResource(R.string.live_chance_estimate, percent(it)) }
        ?: stringResource(R.string.live_chance_exact)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = color,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = title
            },
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(pct, color = color, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.live_chance_label) + " " + margin,
                color = color.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
    val max = advice.maxCallBetFraction
    Text(
        when (advice.action) {
            LiveAction.BET_OR_RAISE -> stringResource(R.string.live_detail_bet)
            LiveAction.CHECK_OR_CALL ->
                if (max == null) stringResource(R.string.live_detail_call_any)
                else stringResource(R.string.live_detail_call_up_to, (max * 100).toInt().coerceAtMost(999))
            LiveAction.CHECK_OR_FOLD -> stringResource(R.string.live_detail_fold)
        },
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
    )
    a.currentHand?.let {
        Text(
            stringResource(R.string.live_current_hand, stringResource(it.labelRes())),
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (a.variant == PokerVariant.OMAHA_HI_LO) {
        Text(stringResource(R.string.scan_hilo_note), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
    }
}

private fun percent(p: Double): String = String.format(Locale.getDefault(), "%.0f%%", p * 100)

private fun HandCategory.labelRes(): Int = when (this) {
    HandCategory.HIGH_CARD -> R.string.live_hand_high_card
    HandCategory.ONE_PAIR -> R.string.live_hand_one_pair
    HandCategory.TWO_PAIR -> R.string.live_hand_two_pair
    HandCategory.THREE_OF_A_KIND -> R.string.live_hand_trips
    HandCategory.STRAIGHT -> R.string.live_hand_straight
    HandCategory.FLUSH -> R.string.live_hand_flush
    HandCategory.FULL_HOUSE -> R.string.live_hand_full_house
    HandCategory.FOUR_OF_A_KIND -> R.string.live_hand_quads
    HandCategory.STRAIGHT_FLUSH -> R.string.live_hand_straight_flush
}
