package com.meo.network

import com.meo.pairing.AuthProofs
import com.meo.pairing.PinningTrustManager
import com.meo.pairing.SpkiPin
import com.meo.pairing.TlsIdentity
import com.meo.protocol.AuthAccept
import com.meo.protocol.AuthProof
import com.meo.protocol.DecodeResult
import com.meo.protocol.Envelope
import com.meo.protocol.FrameRead
import com.meo.protocol.Framing
import com.meo.protocol.Hello
import com.meo.protocol.Payload
import com.meo.protocol.Protocol
import com.meo.protocol.ProtocolCodec
import com.meo.protocol.ProtocolErrorMessage
import com.meo.protocol.SessionReady
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetAddress
import javax.net.ssl.SSLSocket

/**
 * A minimal desktop, in the test source set.
 *
 * The phone's transport cannot be proven by talking to itself, and the real
 * desktop receiver does not exist yet — `windows/` has the virtual camera but
 * no `MeoApp`. So the other end of the conversation is built here: its own TLS
 * identity, its own pin of the phone, and the same handshake a real desktop
 * will have to perform.
 *
 * This is deliberately written against the *protocol* rather than against the
 * phone's classes, so that when the C++ receiver is written this file is a
 * working reference for what it has to do — and the golden fixtures in
 * `protocol/fixtures/` are what it has to produce.
 */
class TestDesktopClient(
    val deviceId: String = "desktop-test-1",
    val displayName: String = "Test PC",
    val identity: TlsIdentity = TlsIdentity.generate()
) {
    val pin: String get() = identity.pin

    /** Deliberately settable, so a test can send a deviceId that lies. */
    var announcedDeviceId: String = deviceId

    fun connect(address: InetAddress, port: Int, expectedPhonePin: String): Connection {
        val context = identity.sslContext(PinningTrustManager.expecting(expectedPhonePin))
        val socket = context.socketFactory.createSocket(address, port) as SSLSocket
        socket.soTimeout = SOCKET_TIMEOUT_MS
        socket.startHandshake()
        return Connection(socket)
    }

    inner class Connection(private val socket: SSLSocket) : Closeable {
        private val input = BufferedInputStream(socket.inputStream)
        private val output = BufferedOutputStream(socket.outputStream)
        private var outboundId = 0L
        var sessionId: String = Protocol.NO_SESSION
            private set

        val phonePin: String = socket.session.peerCertificates
            .filterIsInstance<java.security.cert.X509Certificate>()
            .first()
            .let { SpkiPin.of(it) }

        fun send(payload: Payload, sessionOverride: String? = null, messageIdOverride: Long? = null) {
            val envelope = Envelope(
                protocolVersion = Protocol.VERSION,
                sessionId = sessionOverride ?: sessionId,
                messageId = messageIdOverride ?: ++outboundId,
                sentAtMonotonicMs = System.nanoTime() / 1_000_000,
                payload = payload
            )
            Framing.writeFrame(output, ProtocolCodec.encodeToBytes(envelope))
        }

        /** Sends bytes the encoder would never produce. */
        fun sendRaw(bytes: ByteArray) = Framing.writeFrame(output, bytes)

        fun sendRawFrame(header: ByteArray) {
            output.write(header)
            output.flush()
        }

        fun receive(): Envelope? = when (val frame = Framing.readFrame(input)) {
            is FrameRead.Frame -> when (val decoded = ProtocolCodec.decode(frame.bytes)) {
                is DecodeResult.Ok -> decoded.envelope
                is DecodeResult.Rejected ->
                    throw AssertionError("phone sent something undecodable: ${decoded.code}")
            }

            else -> null
        }

        fun receivePayload(): Payload? = receive()?.payload

        /**
         * Runs the full handshake. Supply [token] for first pairing or
         * [credential] for a reconnect.
         *
         * Returns the phone's [SessionReady] on success. Any refusal is
         * returned as the [ProtocolErrorMessage] the phone sent, because "which
         * error, and did it arrive at all" is exactly what the tests assert.
         */
        fun handshake(
            token: String? = null,
            credential: String? = null,
            issuedCredential: String = AuthProofs.newSecret(),
            corruptOurProof: Boolean = false
        ): Payload? {
            val secret = token ?: credential ?: error("need a token or a credential")
            val pairing = token != null
            val ourNonce = AuthProofs.newNonce()

            send(
                Hello(
                    deviceId = announcedDeviceId,
                    displayName = displayName,
                    nonce = ourNonce,
                    pairingToken = token
                )
            )

            val reply = receivePayload() ?: return null
            val proof = reply as? AuthProof ?: return reply

            val phoneLabel =
                if (pairing) AuthProofs.LABEL_PAIR_PHONE else AuthProofs.LABEL_AUTH_PHONE
            val phoneProved = AuthProofs.verify(
                expectedLabel = phoneLabel,
                presentedProof = proof.proof,
                secretHex = secret,
                nonceHex = ourNonce,
                proverPin = phonePin,
                verifierPin = pin
            )
            if (!phoneProved) throw AssertionError("phone's proof did not verify")

            val desktopLabel =
                if (pairing) AuthProofs.LABEL_PAIR_DESKTOP else AuthProofs.LABEL_AUTH_DESKTOP
            val ourProof = AuthProofs.compute(
                label = desktopLabel,
                secretHex = secret,
                nonceHex = proof.nonce,
                peerPin = phonePin,
                ownPin = pin
            )!!.let { if (corruptOurProof) AuthProofs.newSecret() else it }

            send(
                AuthAccept(
                    proof = ourProof,
                    credential = if (pairing) issuedCredential else null,
                    expiresAt = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
                )
            )

            val result = receivePayload()
            if (result is SessionReady) sessionId = result.sessionId
            return result
        }

        override fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
                // Test teardown; the assertion has already been made.
            }
        }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 15_000
    }
}
