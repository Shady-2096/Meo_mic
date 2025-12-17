package com.wifmic.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifmic.MainViewModel
import com.wifmic.network.ServiceDiscovery
import com.wifmic.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestPermission: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val discoveredPCs by viewModel.discoveredPCs.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val latency by viewModel.latency.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val volume by viewModel.volume.collectAsState()

    var showManualConnectDialog by remember { mutableStateOf(false) }
    var manualIpAddress by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Catpuccin.Base)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Meo Mic",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Catpuccin.Text
                )

                IconButton(
                    onClick = { showManualConnectDialog = true },
                    modifier = Modifier.background(Catpuccin.Surface0, RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Manual Connect", tint = Catpuccin.Subtext1)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Use proper conditional branching instead of return@Column
            if (!uiState.hasPermission) {
                PermissionRequest(onRequestPermission)
            } else {
                // Main content when permission is granted
                StatusCard(
                    state = uiState.connectionState,
                    audioLevel = audioLevel,
                    isMuted = isMuted,
                    latency = latency,
                    volume = volume,
                    onMuteToggle = { viewModel.toggleMute() },
                    onVolumeChange = { viewModel.setVolume(it) },
                    onDisconnect = { viewModel.disconnect() }
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (uiState.connectionState != ConnectionState.Connected) {
                    ConnectionSection(
                        connectionState = uiState.connectionState,
                        discoveredPCs = discoveredPCs,
                        onSearch = { viewModel.startDiscovery() },
                        onConnect = { ip, port -> viewModel.connectTo(ip, port) },
                        onManualConnect = { showManualConnectDialog = true }
                    )
                }

                uiState.errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Catpuccin.Red.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = error, color = Catpuccin.Red, modifier = Modifier.padding(16.dp), fontSize = 14.sp)
                    }
                }
            }
        }
    }

    if (showManualConnectDialog) {
        AlertDialog(
            onDismissRequest = { showManualConnectDialog = false },
            containerColor = Catpuccin.Surface0,
            title = { Text("Connect to PC", color = Catpuccin.Text) },
            text = {
                OutlinedTextField(
                    value = manualIpAddress,
                    onValueChange = { manualIpAddress = it },
                    label = { Text("PC IP Address") },
                    placeholder = { Text("192.168.1.100") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Catpuccin.Mauve,
                        unfocusedBorderColor = Catpuccin.Surface2,
                        focusedLabelColor = Catpuccin.Mauve,
                        cursorColor = Catpuccin.Mauve
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (manualIpAddress.isNotBlank()) {
                        viewModel.connectTo(manualIpAddress.trim())
                        showManualConnectDialog = false
                        manualIpAddress = ""
                    }
                }) { Text("Connect", color = Catpuccin.Mauve) }
            },
            dismissButton = {
                TextButton(onClick = { showManualConnectDialog = false }) { Text("Cancel", color = Catpuccin.Subtext0) }
            }
        )
    }
}

@Composable
fun StatusCard(state: ConnectionState, audioLevel: Float, isMuted: Boolean, latency: Long, volume: Float, onMuteToggle: () -> Unit, onVolumeChange: (Float) -> Unit, onDisconnect: () -> Unit) {
    val statusColor by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.Disconnected -> Catpuccin.Red
            ConnectionState.Searching -> Catpuccin.Peach
            ConnectionState.Connecting -> Catpuccin.Yellow
            ConnectionState.Connected -> Catpuccin.Green
        },
        animationSpec = tween(300), label = "statusColor"
    )

    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val scale by pulseAnim.animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Reverse),
        label = "pulseScale"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Catpuccin.Surface0),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(140.dp)
                    .scale(if (state == ConnectionState.Connected) scale else 1f)
                    .shadow(elevation = 20.dp, shape = CircleShape, spotColor = statusColor.copy(alpha = 0.5f))
                    .background(brush = Brush.radialGradient(listOf(statusColor, statusColor.copy(alpha = 0.8f))), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (state) {
                        ConnectionState.Connected -> if (isMuted) Icons.Default.MicOff else Icons.Default.Mic
                        ConnectionState.Searching -> Icons.Default.Search
                        else -> Icons.Default.MicOff
                    },
                    contentDescription = null, tint = Catpuccin.Crust, modifier = Modifier.size(56.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = when (state) {
                    ConnectionState.Disconnected -> "Disconnected"
                    ConnectionState.Searching -> "Searching..."
                    ConnectionState.Connecting -> "Connecting..."
                    ConnectionState.Connected -> if (isMuted) "Muted" else "Streaming"
                },
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Catpuccin.Text
            )

            if (state == ConnectionState.Connected) {
                Spacer(modifier = Modifier.height(24.dp))
                AudioLevelBar(level = if (isMuted) 0f else audioLevel, isMuted = isMuted)
                Spacer(modifier = Modifier.height(16.dp))

                Surface(color = Catpuccin.Surface1, shape = RoundedCornerShape(20.dp)) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Speed, contentDescription = null, tint = Catpuccin.Subtext0, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "${latency}ms", color = Catpuccin.Subtext1, fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Volume Slider
                VolumeSlider(
                    volume = volume,
                    onVolumeChange = onVolumeChange,
                    enabled = !isMuted
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FloatingActionButton(
                        onClick = onMuteToggle,
                        containerColor = if (isMuted) Catpuccin.Red else Catpuccin.Green,
                        contentColor = Catpuccin.Crust,
                        modifier = Modifier.size(64.dp)
                    ) { Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(28.dp)) }

                    FloatingActionButton(
                        onClick = onDisconnect,
                        containerColor = Catpuccin.Surface1,
                        contentColor = Catpuccin.Red,
                        modifier = Modifier.size(64.dp)
                    ) { Icon(Icons.Default.Close, contentDescription = "Disconnect", modifier = Modifier.size(28.dp)) }
                }
            }
        }
    }
}

@Composable
fun AudioLevelBar(level: Float, isMuted: Boolean) {
    val animatedLevel by animateFloatAsState(targetValue = level, animationSpec = tween(50), label = "audioLevel")
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "Audio Level", fontSize = 12.sp, color = Catpuccin.Subtext0)
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Catpuccin.Surface1)) {
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(animatedLevel.coerceIn(0f, 1f)).clip(RoundedCornerShape(4.dp))
                .background(brush = Brush.horizontalGradient(
                    colors = if (isMuted) listOf(Catpuccin.Overlay0, Catpuccin.Overlay0) else listOf(Catpuccin.Green, Catpuccin.Yellow, Catpuccin.Red)
                )))
        }
    }
}

@Composable
fun VolumeSlider(volume: Float, onVolumeChange: (Float) -> Unit, enabled: Boolean) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Input Volume", fontSize = 12.sp, color = Catpuccin.Subtext0)
            Text(
                text = "${(volume * 100).toInt()}%",
                fontSize = 12.sp,
                color = if (enabled) Catpuccin.Mauve else Catpuccin.Overlay0
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.VolumeDown,
                contentDescription = null,
                tint = if (enabled) Catpuccin.Subtext0 else Catpuccin.Overlay0,
                modifier = Modifier.size(20.dp)
            )
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                valueRange = 0f..2f,
                enabled = enabled,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = if (enabled) Catpuccin.Mauve else Catpuccin.Overlay0,
                    activeTrackColor = if (enabled) Catpuccin.Mauve else Catpuccin.Overlay0,
                    inactiveTrackColor = Catpuccin.Surface1
                )
            )
            Icon(
                Icons.Default.VolumeUp,
                contentDescription = null,
                tint = if (enabled) Catpuccin.Subtext0 else Catpuccin.Overlay0,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun ConnectionSection(connectionState: ConnectionState, discoveredPCs: List<ServiceDiscovery.DiscoveredPC>, onSearch: () -> Unit, onConnect: (String, Int) -> Unit, onManualConnect: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (connectionState == ConnectionState.Searching) {
            CircularProgressIndicator(color = Catpuccin.Mauve, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Looking for PCs on network...", color = Catpuccin.Subtext0, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (discoveredPCs.isNotEmpty()) {
            Text(text = "Available PCs", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Catpuccin.Subtext1, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(discoveredPCs) { pc -> PCCard(pc = pc, onClick = { onConnect(pc.host, pc.port) }) }
            }
        } else if (connectionState != ConnectionState.Searching) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onSearch, colors = ButtonDefaults.buttonColors(containerColor = Catpuccin.Mauve, contentColor = Catpuccin.Crust), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Search for PC", fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onManualConnect, colors = ButtonDefaults.outlinedButtonColors(contentColor = Catpuccin.Subtext1), border = androidx.compose.foundation.BorderStroke(1.dp, Catpuccin.Surface2), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Enter IP Manually", fontSize = 14.sp)
        }
    }
}

@Composable
fun PCCard(pc: ServiceDiscovery.DiscoveredPC, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = Catpuccin.Surface0), shape = RoundedCornerShape(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).background(Catpuccin.Surface1, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Computer, contentDescription = null, tint = Catpuccin.Mauve, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = pc.name, fontWeight = FontWeight.Medium, color = Catpuccin.Text, fontSize = 16.sp)
                Text(text = "${pc.host}:${pc.port}", color = Catpuccin.Subtext0, fontSize = 13.sp)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Catpuccin.Overlay0)
        }
    }
}

@Composable
fun PermissionRequest(onRequestPermission: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(modifier = Modifier.size(100.dp).background(Catpuccin.Surface0, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(48.dp), tint = Catpuccin.Mauve)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Microphone Access", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Catpuccin.Text)
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "Meo Mic needs microphone permission\nto stream audio to your PC.", fontSize = 14.sp, textAlign = TextAlign.Center, color = Catpuccin.Subtext0, lineHeight = 22.sp)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onRequestPermission, colors = ButtonDefaults.buttonColors(containerColor = Catpuccin.Mauve, contentColor = Catpuccin.Crust), shape = RoundedCornerShape(16.dp), modifier = Modifier.height(52.dp)) {
            Text("Grant Permission", fontSize = 16.sp)
        }
    }
}

enum class ConnectionState { Disconnected, Searching, Connecting, Connected }
