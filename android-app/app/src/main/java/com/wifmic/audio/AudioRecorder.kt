package com.wifmic.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.log10

/**
 * Handles microphone recording with configurable quality settings.
 * Provides raw PCM audio data via a callback for streaming.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        // Audio format constants
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Buffer size - balance between latency and stability
        val BUFFER_SIZE: Int = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2,
            4096
        )
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    // Audio level for UI visualization (0.0 to 1.0)
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel

    // Mute state
    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    // Volume control (0.0 to 2.0, where 1.0 is normal)
    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
    }

    fun setVolume(volume: Float) {
        _volume.value = volume.coerceIn(0f, 2f)
    }

    /**
     * Starts recording and calls onAudioData with PCM audio chunks.
     * This is a suspend function that runs until stopRecording() is called.
     */
    suspend fun startRecording(onAudioData: (ByteArray, Int) -> Unit) = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            throw SecurityException("Microphone permission not granted")
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            ).also { recorder ->
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord failed to initialize")
                }
            }

            audioRecord?.startRecording()
            isRecording = true

            val buffer = ByteArray(BUFFER_SIZE)

            while (isRecording && isActive) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1

                if (bytesRead > 0) {
                    // Calculate audio level for visualization
                    updateAudioLevel(buffer, bytesRead)

                    // Send audio data (or silence if muted)
                    if (_isMuted.value) {
                        // Send silence when muted
                        val silence = ByteArray(bytesRead)
                        onAudioData(silence, bytesRead)
                    } else {
                        // Apply volume and send
                        val volumeAdjusted = applyVolume(buffer, bytesRead)
                        onAudioData(volumeAdjusted, bytesRead)
                    }
                }
            }
        } finally {
            releaseRecorder()
        }
    }

    fun stopRecording() {
        isRecording = false
    }

    private fun releaseRecorder() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        audioRecord = null
        _audioLevel.value = 0f
    }

    /**
     * Applies volume adjustment to PCM audio data.
     */
    private fun applyVolume(buffer: ByteArray, size: Int): ByteArray {
        val volume = _volume.value
        if (volume == 1.0f) {
            return buffer.copyOf(size)
        }

        val result = ByteArray(size)
        val samples = size / 2 // 16-bit samples

        for (i in 0 until samples) {
            // Read 16-bit sample (little endian)
            val sample = (buffer[i * 2].toInt() and 0xFF) or
                        (buffer[i * 2 + 1].toInt() shl 8)

            // Apply volume and clamp to 16-bit range
            val adjusted = (sample * volume).toInt().coerceIn(-32768, 32767)

            // Write back (little endian)
            result[i * 2] = (adjusted and 0xFF).toByte()
            result[i * 2 + 1] = ((adjusted shr 8) and 0xFF).toByte()
        }

        return result
    }

    /**
     * Calculates RMS audio level from PCM data for visualization.
     */
    private fun updateAudioLevel(buffer: ByteArray, size: Int) {
        if (_isMuted.value) {
            _audioLevel.value = 0f
            return
        }

        var sum = 0L
        val samples = size / 2 // 16-bit samples

        for (i in 0 until samples) {
            val sample = (buffer[i * 2].toInt() and 0xFF) or
                        (buffer[i * 2 + 1].toInt() shl 8)
            sum += sample.toLong() * sample.toLong()
        }

        val rms = kotlin.math.sqrt(sum.toDouble() / samples)

        // Convert to 0-1 range (16-bit audio max is 32767)
        val level = (rms / 32767.0).toFloat().coerceIn(0f, 1f)

        // Apply some smoothing
        _audioLevel.value = (_audioLevel.value * 0.7f + level * 0.3f)
    }
}
