package com.meo.camera.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.camera.core.Preview
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.meo.MainActivity
import com.meo.R
import com.meo.camera.capture.CameraCaptureManager
import com.meo.camera.capture.CameraCaptureState
import com.meo.camera.capture.CameraLens
import com.meo.camera.capture.CameraSessionStatus
import com.meo.discovery.CameraAdvertiser
import com.meo.network.ControlListener
import com.meo.pairing.DeviceTrust
import com.meo.pairing.KeystoreSecureStore
import com.meo.pairing.PairingInvite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Owns camera capture, the control listener, and the media session
 * independently of the Activity, so a user-started session continues after the
 * preview leaves the screen or the display turns off.
 *
 * Everything durable lives here rather than in the ViewModel for the reason
 * plan §7.2 gives: the Activity's lifecycle is not the session's lifecycle. A
 * phone being used as a webcam spends most of its time with the screen off.
 */
class CameraStreamingService : LifecycleService() {
    companion object {
        const val ACTION_START = "com.meo.camera.START"
        const val ACTION_STOP = "com.meo.camera.STOP"
        const val ACTION_PAUSE = "com.meo.camera.PAUSE"
        const val ACTION_RESUME = "com.meo.camera.RESUME"
        const val EXTRA_LENS = "lens"

        private const val NOTIFICATION_CHANNEL_ID = "meo_camera_capture"
        private const val NOTIFICATION_ID = 2001
    }

    private val binder = LocalBinder()
    private lateinit var captureManager: CameraCaptureManager
    private lateinit var appOpsManager: AppOpsManager
    private lateinit var trust: DeviceTrust
    private lateinit var coordinator: CameraSessionCoordinator
    private lateinit var controlListener: ControlListener
    private lateinit var advertiser: CameraAdvertiser

    private var wakeLock: PowerManager.WakeLock? = null

    private val _streamingState = MutableStateFlow(StreamingState())
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    private val _networkState = MutableStateFlow(NetworkState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    /** What the UI needs to tell the user where the phone is reachable. */
    data class NetworkState(
        val isListening: Boolean = false,
        val address: String? = null,
        val port: Int = 0,
        val advertisedName: String? = null,
        val discoveryError: String? = null,
        val pairingActive: Boolean = false
    )

    private val cameraAppOpsListener = AppOpsManager.OnOpChangedListener { operation, changedPackage ->
        if (operation != AppOpsManager.OPSTR_CAMERA || changedPackage != packageName) {
            return@OnOpChangedListener
        }
        ContextCompat.getMainExecutor(this).execute {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                stopCapture()
            }
        }
    }

    val captureState: StateFlow<CameraCaptureState>
        get() = captureManager.state

    inner class LocalBinder : Binder() {
        fun getService(): CameraStreamingService = this@CameraStreamingService
    }

    override fun onCreate() {
        super.onCreate()
        captureManager = CameraCaptureManager(this, this)
        trust = DeviceTrust(
            secureStore = KeystoreSecureStore(this),
            displayName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android phone"
        )
        coordinator = CameraSessionCoordinator(this, captureManager) { state ->
            _streamingState.value = state
            refreshNotification()
        }
        controlListener = ControlListener(trust, coordinator)
        advertiser = CameraAdvertiser(this)

        appOpsManager = getSystemService(AppOpsManager::class.java)
        createNotificationChannel()
        appOpsManager.startWatchingMode(
            AppOpsManager.OPSTR_CAMERA,
            packageName,
            cameraAppOpsListener
        )
        lifecycleScope.launch {
            captureManager.state.collect { state ->
                if (state.status == CameraSessionStatus.Error) {
                    // A failed bind must not strand an indefinite wake lock or
                    // claim that camera capture is still active.
                    releaseWakeLock()
                    stopNetwork()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startCapture(intent.requestedLens())
            ACTION_STOP -> stopCapture()
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
        }
        return Service.START_NOT_STICKY
    }

    fun attachPreview(provider: Preview.SurfaceProvider) {
        captureManager.attachPreview(provider)
    }

    fun detachPreview(provider: Preview.SurfaceProvider) {
        captureManager.detachPreview(provider)
    }

    fun switchLens() {
        captureManager.switchLens()
    }

    fun setZoomRatio(ratio: Float) {
        captureManager.setZoomRatio(ratio)
    }

    fun setTorch(enabled: Boolean) {
        captureManager.setTorch(enabled)
    }

    /**
     * Pausing from the phone is the same operation the desktop can request: the
     * camera stays open and the OS privacy indicator stays on, but no frames
     * leave the device.
     */
    fun setPaused(paused: Boolean) {
        coordinator.onPauseChanged(
            session = controlListener.activeSession ?: return,
            paused = paused
        )
    }

    /** Called by the UI once a QR code has been scanned and understood. */
    fun beginPairing(invite: PairingInvite) {
        controlListener.beginPairing(invite)
        _networkState.value = _networkState.value.copy(pairingActive = true)
    }

    fun cancelPairing() {
        controlListener.cancelPairing()
        _networkState.value = _networkState.value.copy(pairingActive = false)
    }

    /** This phone's own identity, for the pairing screen. */
    fun phonePin(): String = trust.pin

    fun phoneDeviceId(): String = trust.deviceId

    fun pairedComputers() = trust.pairings.all()

    fun revokePairing(desktopDeviceId: String) {
        trust.pairings.remove(desktopDeviceId)
    }

    fun stopCapture() {
        captureManager.stop()
        stopNetwork()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        appOpsManager.stopWatchingMode(cameraAppOpsListener)
        stopNetwork()
        coordinator.close()
        captureManager.close()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startCapture(lens: CameraLens) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopSelf()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        try {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(),
                serviceType
            )
            acquireWakeLock()
            captureManager.start(lens)
            startNetwork()
        } catch (_: SecurityException) {
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun startNetwork() {
        // Pairings that lapsed while the app was closed are dropped before the
        // listener starts trusting anything.
        trust.pairings.purgeExpired(System.currentTimeMillis())

        if (!controlListener.start()) {
            _networkState.value = NetworkState(
                isListening = false,
                discoveryError = "no private network to listen on — connect to Wi-Fi"
            )
            return
        }

        advertiser.start(
            deviceId = trust.deviceId,
            displayName = trust.displayName,
            port = controlListener.boundPort
        )
        _networkState.value = NetworkState(
            isListening = true,
            address = controlListener.boundAddress?.hostAddress,
            port = controlListener.boundPort,
            advertisedName = advertiser.registeredName,
            discoveryError = advertiser.lastError,
            pairingActive = controlListener.pairingInProgress != null
        )
        refreshNotification()
    }

    private fun stopNetwork() {
        advertiser.stop()
        controlListener.stop()
        _networkState.value = NetworkState()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        // This lock is tied to a visible foreground capture session and is
        // deterministically released by stopCapture()/onDestroy(). A fixed
        // timeout would silently break the required screen-off soak.
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MeoCamera::CaptureWakeLock"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Camera capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while Meo is using the camera"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun refreshNotification() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, createNotification())
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, CameraStreamingService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val streaming = _streamingState.value
        // Connected, Live and Paused are distinct states in the notification as
        // well as in the app (plan §6.5). The one place a user is most likely to
        // look while the screen is otherwise busy is the notification shade.
        val text = when {
            streaming.isPaused -> getString(
                R.string.camera_notification_paused,
                streaming.connectedDesktopName.orEmpty()
            )
            streaming.isStreaming -> getString(
                R.string.camera_notification_live,
                streaming.connectedDesktopName.orEmpty()
            )

            streaming.isConnected -> getString(
                R.string.camera_notification_connected,
                streaming.connectedDesktopName.orEmpty()
            )

            else -> getString(R.string.camera_notification_text)
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.camera_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // Pause is only meaningful while something is actually being sent.
        if (streaming.isStreaming || streaming.isPaused) {
            if (streaming.isPaused) {
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    getString(R.string.resume_camera),
                    serviceIntent(ACTION_RESUME, requestCode = 2)
                )
            } else {
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    getString(R.string.pause_camera),
                    serviceIntent(ACTION_PAUSE, requestCode = 3)
                )
            }
        }

        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.stop_camera),
            serviceIntent(ACTION_STOP, requestCode = 1)
        )
        return builder.build()
    }

    private fun Intent.requestedLens(): CameraLens =
        getStringExtra(EXTRA_LENS)
            ?.let { encoded -> CameraLens.entries.firstOrNull { it.name == encoded } }
            ?: CameraLens.Back
}
