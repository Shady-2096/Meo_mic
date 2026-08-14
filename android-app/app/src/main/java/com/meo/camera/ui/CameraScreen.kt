package com.meo.camera.ui

import androidx.camera.view.PreviewView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.meo.camera.CameraViewModel
import com.meo.camera.capture.CameraLens
import com.meo.camera.capture.CameraSessionStatus
import com.meo.camera.service.CameraStreamingService
import com.meo.camera.service.StreamingState
import com.meo.ui.theme.Catpuccin

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit
) {
    BackHandler(onBack = onBack)
    val uiState by viewModel.uiState.collectAsState()
    val capture = uiState.capture
    val isActive = capture.status == CameraSessionStatus.Starting ||
        capture.status == CameraSessionStatus.Capturing

    // The scanner asks for the camera permission itself, and returns null
    // contents when the user backs out of it.
    val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.onQrScanned(it) }
    }
    val scanPairingCode = {
        qrScanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Point at the pairing code shown in Meo Camera on your computer")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Catpuccin.Base)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.background(Catpuccin.Surface0, RoundedCornerShape(12.dp))
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back to Meo Mic",
                    tint = Catpuccin.Subtext1
                )
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    text = "Meo Camera",
                    color = Catpuccin.Text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Android capture probe",
                    color = Catpuccin.Subtext0,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (!uiState.isPlatformSupported) {
            CameraUnsupportedPlatform()
            return@Column
        }

        if (!uiState.hasPermission) {
            CameraPermissionRequest(onRequestPermission)
            return@Column
        }

        CameraPreview(
            viewModel = viewModel,
            status = capture.status,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = when (capture.status) {
                    CameraSessionStatus.Capturing -> Catpuccin.Green.copy(alpha = 0.16f)
                    CameraSessionStatus.Starting -> Catpuccin.Yellow.copy(alpha = 0.16f)
                    CameraSessionStatus.Error -> Catpuccin.Red.copy(alpha = 0.16f)
                    CameraSessionStatus.Idle -> Catpuccin.Surface0
                },
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = when (capture.status) {
                        CameraSessionStatus.Capturing -> "CAPTURING"
                        CameraSessionStatus.Starting -> "STARTING"
                        CameraSessionStatus.Error -> "CAMERA ERROR"
                        CameraSessionStatus.Idle -> "CAMERA OFF"
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    color = when (capture.status) {
                        CameraSessionStatus.Capturing -> Catpuccin.Green
                        CameraSessionStatus.Starting -> Catpuccin.Yellow
                        CameraSessionStatus.Error -> Catpuccin.Red
                        CameraSessionStatus.Idle -> Catpuccin.Subtext0
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }

            if (capture.status == CameraSessionStatus.Capturing && capture.width > 0) {
                Text(
                    text = "${capture.width}×${capture.height}  •  ${capture.framesPerSecond.toInt()} fps",
                    color = Catpuccin.Subtext0,
                    fontSize = 13.sp
                )
            }
        }

        capture.errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = Catpuccin.Red.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    color = Catpuccin.Red,
                    fontSize = 13.sp
                )
            }
        }

        if (isActive) {
            Spacer(modifier = Modifier.height(12.dp))
            SessionStatus(
                network = uiState.network,
                streaming = uiState.streaming,
                onPair = scanPairingCode,
                onTogglePause = { viewModel.setPaused(!uiState.streaming.isPaused) }
            )
        }

        uiState.scanError?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = Catpuccin.Yellow.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp),
                onClick = viewModel::dismissScanError
            ) {
                Text(
                    text = "$message\nTap to dismiss.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    color = Catpuccin.Yellow,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }

        if (isActive && capture.maxZoomRatio > capture.minZoomRatio) {
            Spacer(modifier = Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Zoom", color = Catpuccin.Subtext0, fontSize = 13.sp)
                Text(
                    text = "${formatZoom(capture.zoomRatio)}×",
                    color = Catpuccin.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = capture.zoomRatio.coerceIn(
                    capture.minZoomRatio,
                    capture.maxZoomRatio
                ),
                onValueChange = viewModel::setZoomRatio,
                valueRange = capture.minZoomRatio..capture.maxZoomRatio,
                colors = SliderDefaults.colors(
                    thumbColor = Catpuccin.Mauve,
                    activeTrackColor = Catpuccin.Mauve,
                    inactiveTrackColor = Catpuccin.Surface1
                )
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = if (isActive) {
                "Capture and the connection are owned by the foreground service, so both continue with the display off. " +
                    "Video is sent only to a computer you have paired, and only over your local network."
            } else {
                "Start the camera, then pair a computer to send video to it."
            },
            color = Catpuccin.Subtext0,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isActive && capture.hasFrontCamera && capture.hasBackCamera) {
                IconButton(
                    onClick = viewModel::switchLens,
                    modifier = Modifier
                        .size(54.dp)
                        .background(Catpuccin.Surface0, CircleShape)
                ) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription = "Switch camera",
                        tint = Catpuccin.Text
                    )
                }
            }

            if (isActive && capture.torchAvailable && capture.lens == CameraLens.Back) {
                IconButton(
                    onClick = { viewModel.setTorch(!capture.torchEnabled) },
                    modifier = Modifier
                        .size(54.dp)
                        .background(
                            if (capture.torchEnabled) Catpuccin.Yellow.copy(alpha = 0.18f)
                            else Catpuccin.Surface0,
                            CircleShape
                        )
                ) {
                    Icon(
                        if (capture.torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = if (capture.torchEnabled) "Turn torch off" else "Turn torch on",
                        tint = if (capture.torchEnabled) Catpuccin.Yellow else Catpuccin.Text
                    )
                }
            }

            Button(
                onClick = if (isActive) viewModel::stopCamera else viewModel::startCamera,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) Catpuccin.Red else Catpuccin.Green,
                    contentColor = Catpuccin.Crust
                )
            ) {
                Icon(
                    if (isActive) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Text(
                    text = if (isActive) "Stop camera" else "Start camera",
                    modifier = Modifier.padding(start = 8.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Connected, Live and Paused as distinct states, plus where the phone can be
 * reached (plan §6.4, §6.5).
 *
 * The address is shown even when discovery is working, because plan §5.1 makes
 * typing it a first-class path rather than a fallback: receiving mDNS on
 * Windows can itself involve a firewall interaction, and a user who can read
 * the address off this screen is never stuck.
 */
@Composable
private fun SessionStatus(
    network: CameraStreamingService.NetworkState,
    streaming: StreamingState,
    onPair: () -> Unit,
    onTogglePause: () -> Unit
) {
    val accent = when {
        streaming.isPaused -> Catpuccin.Yellow
        streaming.isStreaming -> Catpuccin.Green
        streaming.isConnected -> Catpuccin.Blue
        else -> Catpuccin.Subtext0
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Catpuccin.Surface0)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        streaming.isPaused -> "PAUSED"
                        streaming.isStreaming -> "LIVE"
                        streaming.isConnected -> "CONNECTED"
                        network.isListening -> "WAITING FOR A COMPUTER"
                        else -> "NOT ON A NETWORK"
                    },
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                if (streaming.isStreaming || streaming.isPaused) {
                    Text(
                        text = if (streaming.isPaused) "Resume" else "Pause",
                        modifier = Modifier
                            .background(accent.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        color = accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            streaming.connectedDesktopName?.let { name ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = name + (streaming.peerAddress?.let { " • $it" } ?: ""),
                    color = Catpuccin.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (network.isListening) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "This phone: ${network.address}:${network.port}",
                    color = Catpuccin.Subtext0,
                    fontSize = 12.sp
                )
            }

            network.discoveryError?.let { message ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "$message — the computer can still connect using the address above.",
                    color = Catpuccin.Yellow,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            streaming.lastError?.let { message ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = message, color = Catpuccin.Red, fontSize = 12.sp, lineHeight = 17.sp)
            }

            if (!streaming.isConnected && network.isListening) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onPair,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (network.pairingActive) "Scan a different code" else "Pair a computer",
                        color = Catpuccin.Text,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (network.pairingActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Waiting for the computer to connect. The code is valid for five minutes and can be used once.",
                        color = Catpuccin.Subtext0,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            if (streaming.isStreaming || streaming.isPaused) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onTogglePause,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (streaming.isPaused) "Resume sending video" else "Pause video",
                        color = Catpuccin.Text,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    viewModel: CameraViewModel,
    status: CameraSessionStatus,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(previewView) {
        viewModel.attachPreview(previewView.surfaceProvider)
        onDispose { viewModel.detachPreview(previewView.surfaceProvider) }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            if (status == CameraSessionStatus.Idle || status == CameraSessionStatus.Error) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Videocam,
                        contentDescription = null,
                        tint = Catpuccin.Overlay0,
                        modifier = Modifier.size(42.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (status == CameraSessionStatus.Error) "Capture stopped" else "Camera is off",
                        color = Catpuccin.Subtext0,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionRequest(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(Catpuccin.Surface0, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Videocam,
                contentDescription = null,
                tint = Catpuccin.Mauve,
                modifier = Modifier.size(46.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "Camera access",
            color = Catpuccin.Text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "Meo only starts capture after you tap Start. Android will show its camera indicator and an ongoing notification while capture is active.",
            modifier = Modifier.fillMaxWidth(0.88f),
            color = Catpuccin.Subtext0,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(28.dp))
        Button(
            onClick = onRequestPermission,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Catpuccin.Mauve,
                contentColor = Catpuccin.Crust
            ),
            modifier = Modifier.height(52.dp)
        ) {
            Text("Allow camera", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CameraUnsupportedPlatform() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.Videocam,
            contentDescription = null,
            tint = Catpuccin.Overlay0,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            "Android 10 or newer required",
            color = Catpuccin.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(9.dp))
        Text(
            "Meo Mic still works on this phone. The camera path has a higher minimum because background camera capture and device encoder behavior differ on older Android releases.",
            modifier = Modifier.fillMaxWidth(0.88f),
            color = Catpuccin.Subtext0,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatZoom(zoomRatio: Float): String =
    if (zoomRatio >= 10f) "%.0f".format(zoomRatio) else "%.1f".format(zoomRatio)
