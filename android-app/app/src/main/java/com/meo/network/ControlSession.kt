package com.meo.network

import com.meo.pairing.AuthProofs
import com.meo.pairing.Pairing
import com.meo.pairing.PairingInvite
import com.meo.pairing.PairingStore
import com.meo.pairing.SpkiPin
import com.meo.protocol.AuthAccept
import com.meo.protocol.AuthProof
import com.meo.protocol.CameraCapabilities
import com.meo.protocol.CameraControl
import com.meo.protocol.DecodeResult
import com.meo.protocol.Envelope
import com.meo.protocol.ErrorCode
import com.meo.protocol.FrameRead
import com.meo.protocol.Framing
import com.meo.protocol.Hello
import com.meo.protocol.IceCandidate
import com.meo.protocol.Payload
import com.meo.protocol.Protocol
import com.meo.protocol.ProtocolCodec
import com.meo.protocol.ProtocolErrorMessage
import com.meo.protocol.SdpAnswer
import com.meo.protocol.SessionReady
import com.meo.protocol.SetPaused
import com.meo.protocol.StartStream
import com.meo.protocol.StopStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * What the rest of the app does when a session says something.
 *
 * The session layer knows about authentication and message shape and nothing
 * about cameras; the service knows about cameras and nothing about framing.
 * This is the seam.
 */
interface ControlSessionHost {
    fun capabilitiesSnapshot(): CameraCapabilities
    fun onAuthenticated(session: ControlSession, pairing: Pairing) {}
    fun onStartStream(session: ControlSession, profile: String) {}
    fun onStopStream(session: ControlSession) {}
    fun onPauseChanged(session: ControlSession, paused: Boolean) {}
    fun onCameraControl(session: ControlSession, control: CameraControl, messageId: Long) {}
    fun onSdpAnswer(session: ControlSession, sdp: String) {}
    fun onRemoteIceCandidate(session: ControlSession, candidate: IceCandidate) {}
    fun onClosed(session: ControlSession, reason: String) {}
}

/**
 * One authenticated conversation with one desktop.
 *
 * Runs on its own thread with blocking IO. There is at most one of these at a
 * time — a phone has one camera — so the concurrency here is a session against
 * the camera callbacks that write to it, not many sessions against each other.
 *
 * ## Ordering of the handshake
 *
 * The phone proves possession first, before verifying the desktop's proof. That
 * is safe, and it is the order plan §6.1 step 7 describes, because by the time
 * a single byte of this conversation is read the desktop has *already* been
 * authenticated: TLS completed against a pinned public key, either the one
 * scanned from the QR moments ago or one stored from a previous pairing. An
 * unknown desktop never reaches this class.
 */
class ControlSession internal constructor(
    private val connection: SessionConnection,
    private val host: ControlSessionHost,
    private val pairings: PairingStore,
    private val ownPin: String,
    private val ownDeviceId: String,
    private val ownDisplayName: String,
    private val pendingInvite: () -> PairingInvite?,
    private val onPairingConsumed: () -> Unit,
    private val clock: () -> Long
) {
    /**
     * The peer's network address, for the "show the active peer and source
     * address in the UI" requirement of plan §6.4.
     */
    val peerAddress: InetAddress get() = connection.peerAddress

    @Volatile
    var sessionId: String = Protocol.NO_SESSION
        private set

    @Volatile
    var pairedDesktop: Pairing? = null
        private set

    private val outboundMessageId = AtomicLong(0)
    private var lastInboundMessageId = -1L
    private var state = State.AWAITING_HELLO
    private var ourNonce: String = ""
    private var expectedDesktopLabel: String = AuthProofs.LABEL_AUTH_DESKTOP
    private var negotiatingSecret: String? = null
    private var negotiatingInvite: PairingInvite? = null

    @Volatile
    private var closed = false

    private enum class State { AWAITING_HELLO, AWAITING_AUTH_ACCEPT, READY, CLOSED }

    /**
     * Reads until the peer goes away or misbehaves. Returns the reason the
     * session ended, which is what the UI shows and what the log records.
     */
    fun run(): String {
        val reason = try {
            pump()
        } catch (error: IOException) {
            "connection lost: ${error.message ?: "io error"}"
        } catch (error: Exception) {
            "internal error: ${error.javaClass.simpleName}"
        } finally {
            closeQuietly()
        }
        host.onClosed(this, reason)
        return reason
    }

    private fun pump(): String {
        while (!closed) {
            when (val frame = Framing.readFrame(connection.input)) {
                is FrameRead.EndOfStream -> return "peer closed the connection"

                is FrameRead.TooLarge -> {
                    // Answer before closing: a peer that sent something huge by
                    // mistake deserves to learn the limit rather than see a
                    // bare disconnect.
                    sendError(ErrorCode.TOO_LARGE, "frame of ${frame.declaredLength} bytes refused")
                    return "peer sent an oversized frame"
                }

                is FrameRead.Malformed -> {
                    sendError(ErrorCode.MALFORMED, frame.reason)
                    return "malformed frame: ${frame.reason}"
                }

                is FrameRead.Frame -> {
                    val stop = handleFrame(frame.bytes)
                    if (stop != null) return stop
                }
            }
        }
        return "session stopped"
    }

    private fun handleFrame(bytes: ByteArray): String? {
        when (val decoded = ProtocolCodec.decode(bytes)) {
            is DecodeResult.Rejected -> {
                sendError(decoded.code, decoded.reason)
                // An unknown message type is survivable — a newer desktop
                // talking about something we do not implement — so the session
                // continues. Anything structurally wrong ends it.
                return if (decoded.code == ErrorCode.UNKNOWN_TYPE) null
                else "refused a message: ${decoded.code}"
            }

            is DecodeResult.Ok -> return handleEnvelope(decoded.envelope)
        }
    }

    private fun handleEnvelope(envelope: Envelope): String? {
        if (envelope.messageId <= lastInboundMessageId) {
            sendError(ErrorCode.OUT_OF_ORDER, "message_id did not increase")
            return "peer replayed or reordered a message"
        }
        lastInboundMessageId = envelope.messageId

        if (!envelope.payload.fromDesktop) {
            // A desktop sending a phone-only message is either confused or
            // pretending to be us.
            sendError(ErrorCode.UNEXPECTED_DIRECTION, "message may only be sent by a phone")
            return "peer sent a phone-only message"
        }

        if (state != State.AWAITING_HELLO &&
            state != State.AWAITING_AUTH_ACCEPT &&
            sessionId != Protocol.NO_SESSION &&
            envelope.sessionId != sessionId
        ) {
            sendError(ErrorCode.MALFORMED, "session_id does not match this session")
            return "peer used the wrong session id"
        }

        return when (val payload = envelope.payload) {
            is Hello -> onHello(payload)
            is AuthAccept -> onAuthAccept(payload)
            is StartStream -> requireReady { host.onStartStream(this, payload.profile) }
            StopStream -> requireReady { host.onStopStream(this) }
            is SetPaused -> requireReady { host.onPauseChanged(this, payload.paused) }
            is CameraControl -> requireReady { host.onCameraControl(this, payload, envelope.messageId) }
            is SdpAnswer -> requireReady { host.onSdpAnswer(this, payload.sdp) }
            is IceCandidate -> requireReady { onRemoteCandidate(payload) }
            is ProtocolErrorMessage -> {
                // The desktop telling us something went wrong on its side.
                "desktop reported an error: ${payload.code}"
            }

            else -> {
                sendError(ErrorCode.UNEXPECTED_DIRECTION, "unexpected message for this peer")
                "peer sent an unexpected message"
            }
        }
    }

    private inline fun requireReady(action: () -> Unit): String? {
        if (state != State.READY) {
            sendError(ErrorCode.NOT_AUTHENTICATED, "session is not authenticated yet")
            return "peer sent a session message before authenticating"
        }
        action()
        return null
    }

    // --- Handshake ---------------------------------------------------------

    private fun onHello(hello: Hello): String? {
        if (state != State.AWAITING_HELLO) {
            sendError(ErrorCode.OUT_OF_ORDER, "hello already received")
            return "peer sent a second hello"
        }

        val peerPin = connection.peerPin
        val now = clock()

        val secret: String
        val ourLabel: String
        if (hello.pairingToken != null) {
            val invite = pendingInvite()
            if (invite == null) {
                sendError(ErrorCode.AUTH_FAILED, "no pairing is in progress on the phone")
                return "pairing attempted with no invite scanned"
            }
            if (invite.isExpired(now)) {
                sendError(ErrorCode.PAIRING_EXPIRED, "the pairing code has expired")
                return "pairing code expired"
            }
            if (!SpkiPin.matches(invite.desktopSpkiPin, peerPin)) {
                // Unreachable if the trust manager did its job; asserted anyway
                // because this is the check that makes the QR mean anything.
                sendError(ErrorCode.AUTH_FAILED, "desktop identity does not match the scanned code")
                return "pairing peer pin mismatch"
            }
            if (!SpkiPin.matches(invite.token, hello.pairingToken)) {
                sendError(ErrorCode.AUTH_FAILED, "pairing code does not match")
                return "pairing token mismatch"
            }
            if (invite.desktopDeviceId != hello.deviceId) {
                sendError(ErrorCode.AUTH_FAILED, "device id does not match the scanned code")
                return "pairing device id mismatch"
            }
            secret = invite.token
            ourLabel = AuthProofs.LABEL_PAIR_PHONE
            expectedDesktopLabel = AuthProofs.LABEL_PAIR_DESKTOP
            negotiatingInvite = invite
        } else {
            val pairing = pairings.find(hello.deviceId)
            if (pairing == null) {
                sendError(ErrorCode.NOT_AUTHENTICATED, "this computer is not paired with the phone")
                return "unknown desktop attempted reconnect"
            }
            if (pairing.isExpired(now)) {
                pairings.remove(pairing.desktopDeviceId)
                sendError(ErrorCode.PAIRING_EXPIRED, "the pairing has expired, pair again")
                return "expired pairing attempted reconnect"
            }
            if (!SpkiPin.matches(pairing.desktopSpkiPin, peerPin)) {
                sendError(ErrorCode.AUTH_FAILED, "desktop identity does not match the stored pairing")
                return "reconnect peer pin mismatch"
            }
            secret = pairing.credential
            ourLabel = AuthProofs.LABEL_AUTH_PHONE
            expectedDesktopLabel = AuthProofs.LABEL_AUTH_DESKTOP
            pairedDesktop = pairing
        }

        negotiatingSecret = secret
        ourNonce = AuthProofs.newNonce()

        val proof = AuthProofs.compute(
            label = ourLabel,
            secretHex = secret,
            nonceHex = hello.nonce,
            peerPin = peerPin,
            ownPin = ownPin
        )
        if (proof == null) {
            sendError(ErrorCode.MALFORMED, "nonce was not valid hex")
            return "peer sent an unusable nonce"
        }

        state = State.AWAITING_AUTH_ACCEPT
        send(
            AuthProof(
                deviceId = ownDeviceId,
                displayName = ownDisplayName,
                nonce = ourNonce,
                proof = proof
            )
        )
        return null
    }

    private fun onAuthAccept(accept: AuthAccept): String? {
        if (state != State.AWAITING_AUTH_ACCEPT) {
            sendError(ErrorCode.OUT_OF_ORDER, "unexpected auth_accept")
            return "peer sent auth_accept out of order"
        }
        val secret = negotiatingSecret ?: return "internal: no secret in flight"

        val verified = AuthProofs.verify(
            expectedLabel = expectedDesktopLabel,
            presentedProof = accept.proof,
            secretHex = secret,
            nonceHex = ourNonce,
            proverPin = connection.peerPin,
            verifierPin = ownPin
        )
        if (!verified) {
            sendError(ErrorCode.AUTH_FAILED, "desktop proof did not verify")
            return "desktop failed authentication"
        }

        val now = clock()
        val invite = negotiatingInvite
        val pairing: Pairing
        if (invite != null) {
            val credential = accept.credential
            if (credential == null || SpkiPin.parseHex(credential)?.size?.let { it < 32 } != false) {
                // Anything shorter than 256 bits would weaken every future
                // reconnect, and this is the only moment we can refuse it.
                sendError(ErrorCode.AUTH_FAILED, "credential missing or too short")
                return "desktop issued an unusable credential"
            }
            pairing = Pairing(
                desktopDeviceId = invite.desktopDeviceId,
                displayName = invite.desktopDisplayName,
                desktopSpkiPin = connection.peerPin,
                credential = credential,
                expiresAt = now + Pairing.SLIDING_VALIDITY_MS,
                lastSeenAt = now
            )
            // One successful use, per plan §6.1: the QR is dead from here.
            onPairingConsumed()
        } else {
            val existing = pairedDesktop ?: return "internal: no pairing in flight"
            pairing = existing.renewed(now)
        }

        pairings.save(pairing)
        pairedDesktop = pairing
        sessionId = "s-" + AuthProofs.newSecret().take(12)
        state = State.READY

        send(SessionReady(sessionId = sessionId, capabilities = host.capabilitiesSnapshot()))
        host.onAuthenticated(this, pairing)
        return null
    }

    private fun onRemoteCandidate(candidate: IceCandidate) {
        when (val verdict = IceCandidateFilter.evaluate(candidate.candidate)) {
            is IceCandidateFilter.Verdict.Accept -> host.onRemoteIceCandidate(this, candidate)
            is IceCandidateFilter.Verdict.Reject -> {
                // Not fatal. ICE offers many candidates and expects some to be
                // unusable; refusing one is ordinary, and the session continues
                // with the paths that are allowed.
                sendError(ErrorCode.CANDIDATE_REJECTED, verdict.reason)
            }
        }
    }

    // --- Sending -----------------------------------------------------------

    /** Thread-safe: camera and WebRTC callbacks send from their own threads. */
    fun send(payload: Payload): Boolean {
        if (closed) return false
        val envelope = Envelope(
            protocolVersion = Protocol.VERSION,
            sessionId = sessionId,
            messageId = outboundMessageId.incrementAndGet(),
            sentAtMonotonicMs = connection.monotonicMillis(),
            payload = payload
        )
        return try {
            val bytes = ProtocolCodec.encodeToBytes(envelope)
            synchronized(connection.writeLock) {
                Framing.writeFrame(connection.output, bytes)
            }
            true
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            // Something we generated exceeded the peer's frame limit. Dropping
            // it is right: the alternative is tearing down a live session over
            // an oversized health report.
            false
        }
    }

    private fun sendError(code: String, reason: String) {
        send(
            ProtocolErrorMessage(
                code = code,
                reason = reason.take(Protocol.MAX_REASON_LENGTH),
                fromDesktopFlag = false
            )
        )
    }

    fun close() {
        closed = true
        closeQuietly()
    }

    private fun closeQuietly() {
        state = State.CLOSED
        connection.close()
    }
}

/**
 * The transport a [ControlSession] runs over.
 *
 * Named separately so the session's logic can be exercised over a plain socket
 * pair, or over anything else, without a TLS handshake in the way.
 */
interface SessionConnection {
    val input: BufferedInputStream
    val output: BufferedOutputStream
    val peerAddress: InetAddress

    /** The peer's pinned SPKI hash, established during the handshake. */
    val peerPin: String

    val writeLock: Any
    fun monotonicMillis(): Long
    fun close()
}
