package com.meo

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meo.audio.AudioRecorder
import com.meo.network.ConnectionTarget
import com.meo.network.ServiceDiscovery
import com.meo.network.UdpAudioStreamer
import com.meo.service.AudioStreamingService
import com.meo.ui.ConnectionState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MainUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val hasPermission: Boolean = false,
    val errorMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val serviceDiscovery = ServiceDiscovery(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val discoveredPCs: StateFlow<List<ServiceDiscovery.DiscoveredPC>> =
        serviceDiscovery.discoveredServices

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _latency = MutableStateFlow(0L)
    val latency: StateFlow<Long> = _latency.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private var streamingService: AudioStreamingService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as AudioStreamingService.LocalBinder
            streamingService = localBinder.getService()
            serviceBound = true

            // Observe service state
            viewModelScope.launch {
                streamingService?.getAudioRecorder()?.audioLevel?.collect {
                    _audioLevel.value = it
                }
            }
            viewModelScope.launch {
                streamingService?.getAudioRecorder()?.isMuted?.collect {
                    _isMuted.value = it
                }
            }
            viewModelScope.launch {
                streamingService?.getAudioRecorder()?.volume?.collect {
                    _volume.value = it
                }
            }
            viewModelScope.launch {
                streamingService?.getStreamer()?.latencyMs?.collect {
                    _latency.value = it
                }
            }
            viewModelScope.launch {
                streamingService?.getStreamer()?.isConnected?.collect { connected ->
                    if (connected) {
                        _uiState.update { it.copy(connectionState = ConnectionState.Connected) }
                    } else if (_uiState.value.connectionState == ConnectionState.Connected) {
                        _uiState.update { it.copy(connectionState = ConnectionState.Disconnected) }
                    }
                }
            }
            viewModelScope.launch {
                streamingService?.isStreaming?.collect { streaming ->
                    if (!streaming && _uiState.value.connectionState == ConnectionState.Connected) {
                        _uiState.update { it.copy(connectionState = ConnectionState.Disconnected) }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            serviceBound = false
        }
    }

    init {
        checkPermission()

        // Observe discovery state
        viewModelScope.launch {
            serviceDiscovery.isSearching.collect { searching ->
                if (searching && _uiState.value.connectionState == ConnectionState.Disconnected) {
                    _uiState.update { it.copy(connectionState = ConnectionState.Searching) }
                } else if (!searching && _uiState.value.connectionState == ConnectionState.Searching) {
                    _uiState.update { it.copy(connectionState = ConnectionState.Disconnected) }
                }
            }
        }
    }

    fun checkPermission() {
        val hasPermission = AudioRecorder(getApplication()).hasPermission()
        _uiState.update { it.copy(hasPermission = hasPermission) }
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasPermission = granted) }
        if (granted) {
            startDiscovery()
        }
    }

    fun startDiscovery() {
        _uiState.update { it.copy(errorMessage = null) }
        serviceDiscovery.startDiscovery()
    }

    fun stopDiscovery() {
        serviceDiscovery.stopDiscovery()
    }

    /** Connects to the address in a scanned QR code. */
    fun connectToScannedCode(rawValue: String?) {
        connectToAddress(
            rawValue,
            failureMessage = "That QR code is not a Meo Mic address. " +
                "Open Meo Mic on your computer and scan the code it shows."
        )
    }

    /** Connects to an address someone typed or pasted. */
    fun connectToTypedAddress(rawValue: String?) {
        connectToAddress(
            rawValue,
            failureMessage = "That does not look like an address. " +
                "Try something like 192.168.1.100"
        )
    }

    private fun connectToAddress(rawValue: String?, failureMessage: String) {
        val target = ConnectionTarget.parse(rawValue)
        if (target == null) {
            _uiState.update { it.copy(errorMessage = failureMessage) }
            return
        }
        connectTo(target.host, target.port)
    }

    fun connectTo(ipAddress: String, port: Int = UdpAudioStreamer.DEFAULT_PORT) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.Connecting,
                    errorMessage = null
                )
            }

            stopDiscovery()

            try {
                val context = getApplication<Application>()

                // Start and bind to the streaming service
                val intent = Intent(context, AudioStreamingService::class.java).apply {
                    action = AudioStreamingService.ACTION_START
                    putExtra(AudioStreamingService.EXTRA_IP_ADDRESS, ipAddress)
                    putExtra(AudioStreamingService.EXTRA_PORT, port)
                }

                ContextCompat.startForegroundService(context, intent)
                context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                // Connection state will be updated by isConnected flow
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.Disconnected,
                        errorMessage = "Failed to connect: ${e.message}"
                    )
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            try {
                streamingService?.stopStreaming()

                if (serviceBound) {
                    getApplication<Application>().unbindService(serviceConnection)
                    serviceBound = false
                }

                streamingService = null
            } catch (e: Exception) {
                // Ignore cleanup errors
            }

            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.Disconnected,
                    errorMessage = null
                )
            }
            _audioLevel.value = 0f
            _latency.value = 0L
        }
    }

    fun toggleMute() {
        streamingService?.getAudioRecorder()?.toggleMute()
    }

    fun setVolume(volume: Float) {
        streamingService?.getAudioRecorder()?.setVolume(volume)
        _volume.value = volume.coerceIn(0f, 2f)
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
        if (serviceBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
