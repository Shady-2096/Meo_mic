package com.wifmic.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams audio data over UDP to the PC receiver.
 *
 * Packet format:
 * - Header (8 bytes):
 *   - Magic bytes: "WM" (2 bytes) - identifies Meo Mic packets
 *   - Version: 1 byte
 *   - Packet type: 1 byte (0=audio, 1=keepalive, 2=disconnect)
 *   - Sequence number: 4 bytes (for packet ordering/loss detection)
 * - Payload: PCM audio data
 */
class UdpAudioStreamer {

    companion object {
        const val DEFAULT_PORT = 48888
        const val MAGIC_BYTE_1: Byte = 'W'.code.toByte()
        const val MAGIC_BYTE_2: Byte = 'M'.code.toByte()
        const val PROTOCOL_VERSION: Byte = 1
        const val HEADER_SIZE = 8

        // Packet types
        const val PACKET_AUDIO: Byte = 0
        const val PACKET_KEEPALIVE: Byte = 1
        const val PACKET_DISCONNECT: Byte = 2
        const val PACKET_ACK: Byte = 3

        // Timeout for connection verification (ms)
        const val CONNECTION_TIMEOUT_MS = 3000
        // Timeout for considering connection lost (ms)
        const val HEARTBEAT_TIMEOUT_MS = 5000
    }

    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = DEFAULT_PORT
    private var sequenceNumber: Int = 0

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs

    // Track last send time for latency calculation
    private var lastSendTime: Long = 0

    // Track last response time for heartbeat timeout
    private var lastResponseTime: Long = 0

    /**
     * Connects to the PC receiver at the specified address.
     * Waits for acknowledgment from PC before marking as connected.
     */
    suspend fun connect(ipAddress: String, port: Int = DEFAULT_PORT) = withContext(Dispatchers.IO) {
        try {
            disconnect()

            targetAddress = InetAddress.getByName(ipAddress)
            targetPort = port
            socket = DatagramSocket().apply {
                soTimeout = CONNECTION_TIMEOUT_MS // Timeout for connection verification
                sendBufferSize = 65536
            }
            sequenceNumber = 0

            // Send keepalive and wait for acknowledgment
            val packet = createPacket(PACKET_KEEPALIVE, ByteArray(0), 0)
            socket?.send(packet)

            // Wait for response from PC to verify it's actually running
            val responseBuffer = ByteArray(64)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)

            try {
                socket?.receive(responsePacket)

                // Verify it's a valid response (check magic bytes)
                if (responseBuffer[0] == MAGIC_BYTE_1 && responseBuffer[1] == MAGIC_BYTE_2) {
                    lastResponseTime = System.currentTimeMillis()
                    _isConnected.value = true

                    // Set longer timeout for normal operation
                    socket?.soTimeout = HEARTBEAT_TIMEOUT_MS
                } else {
                    throw Exception("Invalid response from PC")
                }
            } catch (e: java.net.SocketTimeoutException) {
                // No response - PC app is not running
                _isConnected.value = false
                throw Exception("PC not responding. Make sure Meo Mic is running on your PC.")
            }
        } catch (e: Exception) {
            _isConnected.value = false
            socket?.close()
            socket = null
            targetAddress = null
            throw e
        }
    }

    /**
     * Sends audio data to the PC.
     */
    suspend fun sendAudio(audioData: ByteArray, size: Int) = withContext(Dispatchers.IO) {
        if (!_isConnected.value || socket == null || targetAddress == null) {
            return@withContext
        }

        try {
            val packet = createPacket(PACKET_AUDIO, audioData, size)
            socket?.send(packet)
            lastSendTime = System.currentTimeMillis()
        } catch (e: Exception) {
            // Network error - mark as disconnected
            _isConnected.value = false
        }
    }

    /**
     * Sends a keepalive packet to maintain the connection.
     */
    suspend fun sendKeepalive() = withContext(Dispatchers.IO) {
        if (socket == null || targetAddress == null) return@withContext

        try {
            val packet = createPacket(PACKET_KEEPALIVE, ByteArray(0), 0)
            socket?.send(packet)
        } catch (e: Exception) {
            _isConnected.value = false
        }
    }

    /**
     * Disconnects from the PC.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            if (_isConnected.value && socket != null && targetAddress != null) {
                // Send disconnect packet
                val packet = createPacket(PACKET_DISCONNECT, ByteArray(0), 0)
                socket?.send(packet)
            }
        } catch (e: Exception) {
            // Ignore send errors during disconnect
        } finally {
            socket?.close()
            socket = null
            targetAddress = null
            _isConnected.value = false
            sequenceNumber = 0
        }
    }

    /**
     * Creates a UDP packet with the Meo Mic header.
     */
    private fun createPacket(type: Byte, data: ByteArray, dataSize: Int): DatagramPacket {
        val packetSize = HEADER_SIZE + dataSize
        val buffer = ByteBuffer.allocate(packetSize).order(ByteOrder.BIG_ENDIAN)

        // Header
        buffer.put(MAGIC_BYTE_1)
        buffer.put(MAGIC_BYTE_2)
        buffer.put(PROTOCOL_VERSION)
        buffer.put(type)
        buffer.putInt(sequenceNumber++)

        // Payload
        if (dataSize > 0) {
            buffer.put(data, 0, dataSize)
        }

        val packetData = buffer.array()
        return DatagramPacket(packetData, packetData.size, targetAddress, targetPort)
    }

    /**
     * Listens for responses from the PC (for latency measurement and ACKs).
     * Disconnects if no response is received within HEARTBEAT_TIMEOUT_MS.
     */
    suspend fun listenForResponses() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(64)
        val packet = DatagramPacket(buffer, buffer.size)

        while (_isConnected.value) {
            try {
                socket?.receive(packet)

                // Verify magic bytes
                if (buffer[0] == MAGIC_BYTE_1 && buffer[1] == MAGIC_BYTE_2) {
                    lastResponseTime = System.currentTimeMillis()

                    // Calculate latency from response
                    if (lastSendTime > 0) {
                        _latencyMs.value = System.currentTimeMillis() - lastSendTime
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Check if we've exceeded heartbeat timeout
                val timeSinceLastResponse = System.currentTimeMillis() - lastResponseTime
                if (lastResponseTime > 0 && timeSinceLastResponse > HEARTBEAT_TIMEOUT_MS) {
                    // PC stopped responding - disconnect
                    _isConnected.value = false
                    break
                }
            } catch (e: Exception) {
                // Socket error - likely disconnected
                if (_isConnected.value) {
                    _isConnected.value = false
                }
                break
            }
        }
    }

    /**
     * Check if connection is still alive based on heartbeat.
     */
    fun checkHeartbeat(): Boolean {
        if (!_isConnected.value) return false

        val timeSinceLastResponse = System.currentTimeMillis() - lastResponseTime
        if (lastResponseTime > 0 && timeSinceLastResponse > HEARTBEAT_TIMEOUT_MS) {
            _isConnected.value = false
            return false
        }
        return true
    }
}
