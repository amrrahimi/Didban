package com.pokervision.feature.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pokervision.core.model.media.MediaType
import com.pokervision.core.ui.component.ErrorBanner
import com.pokervision.core.ui.component.PlannedFeatureNotice
import com.pokervision.core.ui.component.PokerTopBar
import com.pokervision.core.ui.text.userMessage
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun CameraRoute(onBack: () -> Unit, viewModel: CameraViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        askedOnce = true
    }

    Scaffold(
        topBar = { PokerTopBar(stringResource(R.string.camera_title), onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (hasPermission) {
                CameraContent(viewModel)
            } else {
                PermissionRequest(
                    showSettingsHint = askedOnce,
                    onRequest = { launcher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionRequest(showSettingsHint: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.camera_permission_rationale), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text(stringResource(R.string.camera_permission_grant)) }
        if (showSettingsHint) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.camera_permission_settings)) }
        }
    }
}

@Composable
private fun CameraContent(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val session = remember { CameraSession(context.applicationContext, settings.analysisFps) }
    val stats by session.frameStats.collectAsStateWithLifecycle()
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val snackbar = remember { SnackbarHostState() }

    DisposableEffect(session) { onDispose { session.release() } }
    LaunchedEffect(settings.analysisFps) { session.setAnalysisFps(settings.analysisFps) }
    LaunchedEffect(previewView, state.lens) {
        previewView?.let { pv -> session.bind(lifecycleOwner, pv, state.lens) { viewModel.onBindFailed() } }
    }

    val savedPhoto = stringResource(R.string.camera_saved_photo)
    LaunchedEffect(state.savedMessageCount) {
        if (state.savedMessageCount > 0) snackbar.showSnackbar(savedPhoto)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }.also { previewView = it }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)) {
            DiagnosticsOverlay(stats, settings.analysisFps)
            Spacer(Modifier.height(8.dp))
            PlannedFeatureNotice(phase = 3, description = stringResource(R.string.camera_detection_planned))
            state.error?.let { err ->
                Spacer(Modifier.height(8.dp))
                ErrorBanner(
                    when (err) {
                        CameraError.BindFailed -> stringResource(R.string.camera_error_bind)
                        CameraError.CaptureFailed -> stringResource(R.string.camera_error_capture)
                        is CameraError.Storage -> err.error.userMessage()
                    },
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(snackbar)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                IconButton(onClick = viewModel::toggleLens, enabled = !state.isCapturing) {
                    Icon(Icons.Filled.Cameraswitch, contentDescription = stringResource(R.string.camera_switch_lens), tint = Color.White)
                }
                ShutterButton(
                    enabled = !state.isCapturing,
                    onClick = {
                        scope.launch {
                            val path = viewModel.prepareCaptureFile(MediaType.IMAGE) ?: return@launch
                            viewModel.onCaptureStarted()
                            session.takePhoto(
                                File(path),
                                onSaved = { viewModel.onMediaWritten(path, MediaType.IMAGE) },
                                onError = { viewModel.onCaptureFailed() },
                            )
                        }
                    },
                )
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun DiagnosticsOverlay(stats: FrameStats, targetFps: Int) {
    Text(
        text = stringResource(
            R.string.camera_diagnostics,
            String.format(Locale.getDefault(), "%.1f", stats.analyzedFps),
            targetFps,
            stats.width,
            stats.height,
            stats.rotationDegrees,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(8.dp),
    )
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(72.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.camera_take_photo), modifier = Modifier.size(36.dp))
    }
}
