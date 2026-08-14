package com.meo.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meo.camera.capture.CameraCaptureState
import com.meo.camera.capture.CameraLens
import com.meo.camera.capture.CameraSessionStatus
import com.meo.camera.service.CameraStreamingService
import com.meo.camera.service.StreamingState
import com.meo.pairing.PairingInvite
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CameraUiState(
    val isPlatformSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    val hasPermission: Boolean = false,
    val isServiceBound: Boolean = false,
    val capture: CameraCaptureState = CameraCaptureState(),
    val streaming: StreamingState = StreamingState(),
    val network: CameraStreamingService.NetworkState = CameraStreamingService.NetworkState(),
    /** Set when a scanned QR code was not a Meo pairing code. */
    val scanError: String? = null
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(
        CameraUiState(hasPermission = application.hasCameraPermission())
    )
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    @SuppressLint("StaticFieldLeak") // Cleared after unbinding in onCleared().
    private var service: CameraStreamingService? = null
    private var stateJob: Job? = null
    private var previewProvider: Preview.SurfaceProvider? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val cameraBinder = binder as? CameraStreamingService.LocalBinder ?: return
            service = cameraBinder.getService()
            bound = true
            _uiState.update { it.copy(isServiceBound = true) }
            previewProvider?.let { provider -> service?.attachPreview(provider) }

            stateJob?.cancel()
            stateJob = viewModelScope.launch {
                val bound = service ?: return@launch
                // Three independent flows: what the camera is doing, what the
                // session is doing, and where the phone is reachable. They
                // change at different times and the screen shows all three.
                launch {
                    bound.captureState.collect { capture ->
                        _uiState.update { it.copy(capture = capture) }
                    }
                }
                launch {
                    bound.streamingState.collect { streaming ->
                        _uiState.update { it.copy(streaming = streaming) }
                    }
                }
                launch {
                    bound.networkState.collect { network ->
                        _uiState.update { it.copy(network = network) }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            stateJob?.cancel()
            stateJob = null
            service = null
            bound = false
            _uiState.update {
                it.copy(
                    isServiceBound = false,
                    capture = it.capture.copy(status = CameraSessionStatus.Idle)
                )
            }
        }
    }

    init {
        bindService()
    }

    fun checkPermission() {
        _uiState.update {
            it.copy(hasPermission = getApplication<Application>().hasCameraPermission())
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasPermission = granted) }
    }

    fun startCamera() {
        val context = getApplication<Application>()
        if (!_uiState.value.isPlatformSupported) return
        if (!context.hasCameraPermission()) {
            _uiState.update { it.copy(hasPermission = false) }
            return
        }

        val lens = _uiState.value.capture.lens
        val intent = Intent(context, CameraStreamingService::class.java).apply {
            action = CameraStreamingService.ACTION_START
            putExtra(CameraStreamingService.EXTRA_LENS, lens.name)
        }
        ContextCompat.startForegroundService(context, intent)
        if (!bound) bindService()
    }

    fun stopCamera() {
        service?.stopCapture() ?: run {
            val context = getApplication<Application>()
            context.stopService(Intent(context, CameraStreamingService::class.java))
        }
    }

    fun switchLens() {
        service?.switchLens()
    }

    fun setZoomRatio(ratio: Float) {
        service?.setZoomRatio(ratio)
    }

    fun setTorch(enabled: Boolean) {
        service?.setTorch(enabled)
    }

    fun setPaused(paused: Boolean) {
        service?.setPaused(paused)
    }

    /**
     * Handles scanned QR text.
     *
     * A scanner will hand over whatever the user pointed it at — a Wi-Fi
     * config, a URL, someone else's app's code — so anything that is not a
     * valid, unexpired Meo invite is reported to the user rather than acted on.
     */
    fun onQrScanned(scanned: String): Boolean {
        val invite = PairingInvite.parse(scanned)
        if (invite == null) {
            _uiState.update { it.copy(scanError = "That is not a Meo pairing code.") }
            return false
        }
        if (invite.isExpired(System.currentTimeMillis())) {
            _uiState.update {
                it.copy(scanError = "That pairing code has expired. Generate a new one on the computer.")
            }
            return false
        }
        val bound = service
        if (bound == null) {
            _uiState.update { it.copy(scanError = "Start the camera before pairing.") }
            return false
        }
        bound.beginPairing(invite)
        _uiState.update { it.copy(scanError = null) }
        return true
    }

    fun cancelPairing() {
        service?.cancelPairing()
    }

    fun dismissScanError() {
        _uiState.update { it.copy(scanError = null) }
    }

    fun attachPreview(provider: Preview.SurfaceProvider) {
        previewProvider = provider
        service?.attachPreview(provider)
    }

    fun detachPreview(provider: Preview.SurfaceProvider) {
        if (previewProvider !== provider) return
        service?.detachPreview(provider)
        previewProvider = null
    }

    override fun onCleared() {
        stateJob?.cancel()
        previewProvider?.let { provider -> service?.detachPreview(provider) }
        if (bound) {
            try {
                getApplication<Application>().unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // The process may already have disconnected the service.
            }
        }
        bound = false
        service = null
        super.onCleared()
    }

    private fun bindService() {
        if (bound) return
        val context = getApplication<Application>()
        bound = context.bindService(
            Intent(context, CameraStreamingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun Context.hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
