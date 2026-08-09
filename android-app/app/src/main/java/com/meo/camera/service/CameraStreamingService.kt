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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Owns camera capture independently of the Activity so a user-started session
 * can continue after the preview leaves the screen or the display turns off.
 */
class CameraStreamingService : LifecycleService() {
    companion object {
        const val ACTION_START = "com.meo.camera.START"
        const val ACTION_STOP = "com.meo.camera.STOP"
        const val EXTRA_LENS = "lens"

        private const val NOTIFICATION_CHANNEL_ID = "meo_camera_capture"
        private const val NOTIFICATION_ID = 2001
    }

    private val binder = LocalBinder()
    private lateinit var captureManager: CameraCaptureManager
    private lateinit var appOpsManager: AppOpsManager
    private var wakeLock: PowerManager.WakeLock? = null
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

    fun stopCapture() {
        captureManager.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        appOpsManager.stopWatchingMode(cameraAppOpsListener)
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
        } catch (_: SecurityException) {
            releaseWakeLock()
            stopSelf()
        }
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

        val stopIntent = Intent(this, CameraStreamingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.camera_notification_title))
            .setContentText(getString(R.string.camera_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.stop_camera),
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun Intent.requestedLens(): CameraLens =
        getStringExtra(EXTRA_LENS)
            ?.let { encoded -> CameraLens.entries.firstOrNull { it.name == encoded } }
            ?: CameraLens.Back
}
