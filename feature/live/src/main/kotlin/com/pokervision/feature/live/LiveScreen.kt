package com.pokervision.feature.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pokervision.core.domain.usecase.LiveAnalysis
import com.pokervision.core.domain.vision.LiveHand
import com.pokervision.core.domain.vision.LivePhase
import com.pokervision.core.model.card.PlayingCard
import com.pokervision.core.poker.advice.LiveAction
import com.pokervision.core.poker.holdem.HandCategory
import com.pokervision.core.ui.component.ErrorBanner
import com.pokervision.core.ui.component.PlayingCardView
import com.pokervision.core.ui.component.PokerTopBar
import com.pokervision.core.ui.theme.PokerColors
import com.pokervision.core.vision.DetectorStatus
import java.util.Locale

@Composable
fun LiveRoute(onBack: () -> Unit, viewModel: LiveViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

    Scaffold(
        topBar = { PokerTopBar(stringResource(R.string.live_title), onBack = onBack) },
        containerColor = Color.Black,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (hasPermission) {
                LiveContent(viewModel)
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
private fun LiveContent(viewModel: LiveViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hand by viewModel.hand.collectAsStateWithLifecycle()
    val vision by viewModel.vision.collectAsStateWithLifecycle()
    val analysis by viewModel.analysis.collectAsStateWithLifecycle()

    val controller = remember { LiveCameraController(context.applicationContext, settings.analysisFps) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    LaunchedEffect(settings.analysisFps) { controller.setFps(settings.analysisFps) }
    LaunchedEffect(previewView) {
        previewView?.let { controller.bind(lifecycleOwner, it, viewModel::onFrame) { viewModel.onCameraError() } }
    }

    Box(Modifier.fillMaxSize()) {
        // Camera + box overlay share one box with the analysed frame's aspect ratio, so boxes line up.
        Box(Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(vision.frameAspect)) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }.also { previewView = it }
                },
                modifier = Modifier.fillMaxSize(),
            )
            DetectionOverlay(vision.overlay)
        }

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            StatusBanner(vision, hand)
            CardsPanel(hand, onReject = viewModel::rejectCard)
        }

        AdvicePanel(
            hand = hand,
            analysis = analysis,
            onOpponents = viewModel::setOpponents,
            onNewHand = viewModel::newHand,
            onRescan = viewModel::rescanHero,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun DetectionOverlay(boxes: List<OverlayBox>) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        boxes.forEach { b ->
            val color = if (b.confirmed) PokerColors.ConfidenceHigh else PokerColors.ConfidenceMedium
            val topLeft = Offset(b.box.left * size.width, b.box.top * size.height)
            drawRect(
                color = color,
                topLeft = topLeft,
                size = Size(b.box.width * size.width, b.box.height * size.height),
                style = Stroke(width = 3.dp.toPx()),
            )
            drawText(
                textMeasurer = measurer,
                text = b.card.code,
                topLeft = Offset(topLeft.x, (topLeft.y - 18.dp.toPx()).coerceAtLeast(0f)),
                style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, background = Color.Black.copy(alpha = 0.6f)),
            )
        }
    }
}

@Composable
private fun StatusBanner(vision: LiveVisionState, hand: LiveHand) {
    val text = when {
        vision.cameraError -> stringResource(R.string.live_camera_error)
        vision.detector is DetectorStatus.Loading -> stringResource(R.string.live_model_loading)
        vision.detector is DetectorStatus.ModelMissing -> stringResource(R.string.live_model_missing)
        vision.detector is DetectorStatus.Failed -> stringResource(R.string.live_model_failed)
        hand.tooManyCardsForHero -> stringResource(R.string.live_too_many_cards)
        hand.phase == LivePhase.SCAN_HERO -> stringResource(R.string.live_show_hero, hand.hero.size)
        hand.board.size in 1..2 -> stringResource(R.string.live_reading_flop)
        else -> stringResource(R.string.live_watching_board)
    }
    val isProblem = vision.cameraError || vision.detector is DetectorStatus.ModelMissing || vision.detector is DetectorStatus.Failed
    if (isProblem) {
        ErrorBanner(text, Modifier.padding(8.dp))
    } else {
        Row(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (vision.detector is DetectorStatus.Loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (vision.detector is DetectorStatus.Ready) {
                Text(
                    stringResource(R.string.live_speed, vision.inferenceMillis, String.format(Locale.getDefault(), "%.1f", vision.analysedFps)),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun CardsPanel(hand: LiveHand, onReject: (PlayingCard) -> Unit) {
    var pendingReject by remember { mutableStateOf<PlayingCard?>(null) }
    Row(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.live_my_cards), color = Color.White, style = MaterialTheme.typography.labelMedium)
        SlotCards(hand.hero, 2) { pendingReject = it }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.live_board), color = Color.White, style = MaterialTheme.typography.labelMedium)
        SlotCards(hand.board, 5) { pendingReject = it }
    }
    pendingReject?.let { card ->
        Row(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.live_reject_question, card.code), color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = { onReject(card); pendingReject = null }) { Text(stringResource(R.string.live_reject_yes)) }
            TextButton(onClick = { pendingReject = null }) { Text(stringResource(R.string.live_reject_no)) }
        }
    }
}

@Composable
private fun SlotCards(cards: List<PlayingCard>, slots: Int, onTap: (PlayingCard) -> Unit) {
    for (i in 0 until slots) {
        val card = cards.getOrNull(i)
        if (card != null) {
            PlayingCardView(card, Modifier.size(width = 30.dp, height = 42.dp).clickable { onTap(card) })
        } else {
            Box(Modifier.size(width = 30.dp, height = 42.dp).border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun AdvicePanel(
    hand: LiveHand,
    analysis: LiveAnalysis?,
    onOpponents: (Int) -> Unit,
    onNewHand: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.75f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (analysis != null) {
            val (color, title) = when (analysis.advice.action) {
                LiveAction.BET_OR_RAISE -> PokerColors.ConfidenceHigh to stringResource(R.string.live_action_bet)
                LiveAction.CHECK_OR_CALL -> PokerColors.ConfidenceMedium to stringResource(R.string.live_action_call)
                LiveAction.CHECK_OR_FOLD -> PokerColors.ConfidenceLow to stringResource(R.string.live_action_fold)
            }
            Text(
                title,
                color = color,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = title
                },
            )
            Text(adviceDetail(analysis), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            val pct = percent(analysis.equity)
            val line = analysis.estimateHalfWidth?.let { stringResource(R.string.live_equity_estimate, pct, percent(it)) }
                ?: stringResource(R.string.live_equity_exact, pct)
            val handName = analysis.currentHand?.let { " · " + stringResource(it.labelRes()) } ?: ""
            Text(line + handName, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                stringResource(if (hand.hero.size < 2) R.string.live_waiting_hero else R.string.live_waiting_board),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.live_opponents), color = Color.White, modifier = Modifier.weight(1f))
            IconButton(onClick = { onOpponents(hand.opponents - 1) }, enabled = hand.opponents > 1) {
                Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.live_opponent_folded), tint = Color.White)
            }
            Text(hand.opponents.toString(), color = Color.White, style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { onOpponents(hand.opponents + 1) }, enabled = hand.opponents < 9) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.live_more_opponents), tint = Color.White)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onNewHand, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.live_new_hand))
            }
            TextButton(onClick = onRescan) { Text(stringResource(R.string.live_rescan)) }
        }
    }
}

@Composable
private fun adviceDetail(a: LiveAnalysis): String {
    val max = a.advice.maxCallBetFraction
    return when (a.advice.action) {
        LiveAction.BET_OR_RAISE -> stringResource(R.string.live_detail_bet)
        LiveAction.CHECK_OR_CALL ->
            if (max == null) stringResource(R.string.live_detail_call_any)
            else stringResource(R.string.live_detail_call_up_to, (max * 100).toInt().coerceAtMost(999))
        LiveAction.CHECK_OR_FOLD -> stringResource(R.string.live_detail_fold)
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
