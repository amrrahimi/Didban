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

    val controller = remember { ScanCameraController(context.applicationContext) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraError by remember { mutableStateOf(false) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    LaunchedEffect(previewView) { previewView?.let { controller.bind(lifecycleOwner, it) { cameraError = true } } }

    val scan: (ScanTarget) -> Unit = { target ->
        viewModel.onScanStarted(target)
        controller.capture(onPhoto = { viewModel.onPhoto(target, it) }, onFailure = { viewModel.onScanFailed() })
    }

    Column(Modifier.fillMaxSize()) {
        // Camera preview, or the analysed photo with boxes.
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
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
                    modifier = Modifier.fillMaxSize(),
                )
                when (val p = phase) {
                    is ScanPhase.Result -> PhotoWithBoxes(p)
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
            Modifier.fillMaxWidth().background(Color.Black).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (phase as? ScanPhase.Result)?.let { ResultBar(it, viewModel::acceptPending, viewModel::closeResult) }
            CardsArea(table, onRemove = viewModel::remove, onAdd = viewModel::addManual)
            AdvicePanel(table, analysis)
            if (table.variant == PokerVariant.FIVE_CARD_DRAW) {
                FilterChip(
                    selected = table.drawDone,
                    onClick = { viewModel.setDrawDone(!table.drawDone) },
                    label = { Text(stringResource(R.string.scan_draw_done)) },
                )
            }
            val busy = phase is ScanPhase.Working || detector !is DetectorStatus.Ready
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
        }
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
private fun PhotoWithBoxes(result: ScanPhase.Result) {
    val image = remember(result.photo) { result.photo.asImageBitmap() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(result.photo.width.toFloat() / result.photo.height)) {
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                result.boxes.forEach { b ->
                    drawRect(
                        color = if (b.accepted) PokerColors.ConfidenceHigh else PokerColors.ConfidenceMedium,
                        topLeft = Offset(b.box.left * size.width, b.box.top * size.height),
                        size = Size(b.box.width * size.width, b.box.height * size.height),
                        style = Stroke(width = 3.dp.toPx()),
                    )
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
        TextButton(onClick = onClose) { Text(stringResource(R.string.scan_back_to_camera)) }
    }
}

private fun cardLabel(card: PlayingCard) =
    (if (card.rank == Rank.TEN) "10" else card.rank.symbol.toString()) + card.suit.glyph

@Composable
private fun CardsArea(table: TableState, onRemove: (PlayingCard) -> Unit, onAdd: (PlayingCard, ScanTarget) -> Unit) {
    var pendingRemove by remember { mutableStateOf<PlayingCard?>(null) }
    var picking by remember { mutableStateOf<ScanTarget?>(null) }
    val mineCap = TableRules.mineCapacity(table.variant)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CardRow(stringResource(R.string.live_my_cards), table.mine, mineCap, { pendingRemove = it }) { picking = ScanTarget.MINE }
        when {
            TableRules.usesBoard(table.variant) ->
                CardRow(stringResource(R.string.live_board), table.board, TableRules.BOARD_CAPACITY, { pendingRemove = it }) { picking = ScanTarget.TABLE }
            table.variant == PokerVariant.SEVEN_CARD_STUD ->
                CardRow(stringResource(R.string.scan_seen), table.seen, table.seen.size + 1, { pendingRemove = it }) { picking = ScanTarget.TABLE }
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
    picking?.let { target ->
        CardPickerDialog(
            variant = table.variant,
            used = table.allCards,
            onPick = { onAdd(it, target); picking = null },
            onDismiss = { picking = null },
        )
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
private fun CardPickerDialog(variant: PokerVariant, used: Set<PlayingCard>, onPick: (PlayingCard) -> Unit, onDismiss: () -> Unit) {
    val deck = PokerEngines.forVariant(variant).deck
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    com.pokervision.core.model.card.Suit.SPADES, com.pokervision.core.model.card.Suit.HEARTS,
                    com.pokervision.core.model.card.Suit.DIAMONDS, com.pokervision.core.model.card.Suit.CLUBS,
                ).forEach { suit ->
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Rank.entries.reversed().filter { it in deck.ranks }.forEach { rank ->
                            val card = PlayingCard(rank, suit)
                            val isUsed = card in used
                            val color = if (suit.isRed) PokerColors.CardRed else PokerColors.CardBlack
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .background(if (isUsed) Color.Gray else PokerColors.Ivory, RoundedCornerShape(4.dp))
                                    .clickable(enabled = !isUsed) { onPick(card) }
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
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.live_cancel)) }
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
    Text(
        title,
        color = color,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = title
        },
    )
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
    val pct = percent(a.heroEquity)
    val line = a.estimateHalfWidth?.let { stringResource(R.string.live_equity_estimate, pct, percent(it)) }
        ?: stringResource(R.string.live_equity_exact, pct)
    val handName = a.currentHand?.let { " · " + stringResource(it.labelRes()) } ?: ""
    Text(line + handName, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
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
