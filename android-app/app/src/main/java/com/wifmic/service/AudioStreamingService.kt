package com.wifmic.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.wifmic.MainActivity
import com.wifmic.R
import com.wifmic.audio.AudioRecorder
import com.wifmic.network.UdpAudioStreamer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that handles audio recording and streaming.
 * This keeps the app running even when in the background or when the screen is off.
 */
class AudioStreamingService : Service() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "wifmic_streaming"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.wifmic.START_STREAMING"
        const val ACTION_STOP = "com.wifmic.STOP_STREAMING"
        const val ACTION_MUTE = "com.wifmic.MUTE"

        const val EXTRA_IP_ADDRESS = "ip_address"
        const val EXTRA_PORT = "port"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var audioRecorder: AudioRecorder
    private lateinit var udpStreamer: UdpAudioStreamer

    private var wakeLock: PowerManager.WakeLock? = null
    private var streamingJob: Job? = null

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    inner class LocalBinder : Binder() {
        fun getService(): AudioStreamingService = this@AudioStreamingService
    }

    override fun onCreate() {
        super.onCreate()
        audioRecorder = AudioRecorder(this)
        udpStreamer = UdpAudioStreamer()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ipAddress = intent.getStringExtra(EXTRA_IP_ADDRESS) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, UdpAudioStreamer.DEFAULT_PORT)
                startStreaming(ipAddress, port)
            }
            ACTION_STOP -> stopStreaming()
            ACTION_MUTE -> audioRecorder.toggleMute()
        }
        return START_STICKY
    }

    private fun startStreaming(ipAddress: String, port: Int) {
        if (_isStreaming.value) return

        // Acquire wake lock to prevent CPU from sleeping
        acquireWakeLock()

        // Start foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification())

        streamingJob = serviceScope.launch {
            try {
                // Connect to PC (waits for acknowledgment)
                udpStreamer.connect(ipAddress, port)
                _isStreaming.value = true

                // Start listening for responses (for latency measurement and heartbeat)
                launch {
                    udpStreamer.listenForResponses()
                    // If listenForResponses exits, connection was lost
                    if (_isStreaming.value) {
                        stopStreaming()
                    }
                }

                // Monitor connection state - stop if disconnected
                launch {
                    udpStreamer.isConnected.collect { connected ->
                        if (!connected && _isStreaming.value) {
                            stopStreaming()
                        }
                    }
                }

                // Start keepalive sender
                launch {
                    while (isActive && udpStreamer.isConnected.value) {
                        delay(1000)
                        udpStreamer.sendKeepalive()
                    }
                }

                // Start recording and streaming
                audioRecorder.startRecording { audioData, size ->
                    if (udpStreamer.isConnected.value) {
                        serviceScope.launch {
                            udpStreamer.sendAudio(audioData, size)
                        }
                    }
                }
            } catch (e: Exception) {
                stopStreaming()
            }
        }
    }

    fun stopStreaming() {
        _isStreaming.value = false
        streamingJob?.cancel()
        streamingJob = null

        serviceScope.launch {
            audioRecorder.stopRecording()
            udpStreamer.disconnect()
        }

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MeoMic::StreamingWakeLock"
            ).apply {
                acquire(10 * 60 * 1000L) // 10 minutes max
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Meo Mic is streaming audio"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioStreamingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, AudioStreamingService::class.java).apply {
            action = ACTION_MUTE
        }
        val mutePendingIntent = PendingIntent.getService(
            this, 2, muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.mute), mutePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.disconnect), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun getAudioRecorder(): AudioRecorder = audioRecorder
    fun getStreamer(): UdpAudioStreamer = udpStreamer

    override fun onDestroy() {
        stopStreaming()
        serviceScope.cancel()
        super.onDestroy()
    }
}
