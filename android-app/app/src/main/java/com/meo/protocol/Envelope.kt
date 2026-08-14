package com.meo.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The envelope every control message travels in (ADR 0001).
 *
 * The four header fields are fixed for the life of protocol version 1. They
 * exist so that a receiver can reject, order, and attribute a message without
 * having to understand its payload — which matters, because a receiver must be
 * able to refuse a payload type it has never heard of.
 */
@Serializable
data class Envelope(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("session_id") val sessionId: String,
    /**
     * Strictly increasing per connection, per direction. Gaps are allowed —
     * nothing here retransmits — but repeats and reversals are rejected, which
     * is what makes a captured message useless on a live connection.
     */
    @SerialName("message_id") val messageId: Long,
    /**
     * The sender's own monotonic clock. Never wall-clock: it is used for
     * latency and liveness, and a peer whose clock steps must not appear to
     * have sent something in the future.
     */
    @SerialName("sent_at_monotonic_ms") val sentAtMonotonicMs: Long,
    @SerialName("payload") val payload: Payload
) {
    /**
     * Bounds every field a peer controls. Returns null when acceptable, or the
     * error code to answer with.
     *
     * This runs before any payload is acted on and before the peer has proven
     * anything, so it may not allocate proportionally to the input and may not
     * throw.
     */
    fun validate(): String? {
        if (protocolVersion != Protocol.VERSION) return ErrorCode.UNSUPPORTED_VERSION
        if (sessionId.length > Protocol.MAX_ID_LENGTH) return ErrorCode.TOO_LARGE
        if (sessionId.isEmpty()) return ErrorCode.MALFORMED
        if (messageId < 0) return ErrorCode.MALFORMED
        if (sentAtMonotonicMs < 0) return ErrorCode.MALFORMED
        return validatePayload(payload)
    }

    private fun validatePayload(payload: Payload): String? = when (payload) {
        is Hello -> {
            when {
                payload.deviceId.isEmpty() -> ErrorCode.MALFORMED
                payload.deviceId.length > Protocol.MAX_ID_LENGTH -> ErrorCode.TOO_LARGE
                payload.displayName.length > Protocol.MAX_NAME_LENGTH -> ErrorCode.TOO_LARGE
                payload.nonce.isEmpty() -> ErrorCode.MALFORMED
                payload.nonce.length > Protocol.MAX_HEX_DIGEST_LENGTH -> ErrorCode.TOO_LARGE
                (payload.pairingToken?.length ?: 0) > Protocol.MAX_TOKEN_LENGTH -> ErrorCode.TOO_LARGE
                else -> null
            }
        }

        is AuthProof -> {
            when {
                payload.deviceId.isEmpty() -> ErrorCode.MALFORMED
                payload.deviceId.length > Protocol.MAX_ID_LENGTH -> ErrorCode.TOO_LARGE
                payload.displayName.length > Protocol.MAX_NAME_LENGTH -> ErrorCode.TOO_LARGE
                payload.nonce.isEmpty() -> ErrorCode.MALFORMED
                payload.nonce.length > Protocol.MAX_HEX_DIGEST_LENGTH -> ErrorCode.TOO_LARGE
                payload.proof.isEmpty() -> ErrorCode.MALFORMED
                payload.proof.length > Protocol.MAX_HEX_DIGEST_LENGTH -> ErrorCode.TOO_LARGE
                else -> null
            }
        }

        is AuthAccept -> {
            when {
                payload.proof.isEmpty() -> ErrorCode.MALFORMED
                payload.proof.length > Protocol.MAX_HEX_DIGEST_LENGTH -> ErrorCode.TOO_LARGE
                (payload.credential?.length ?: 0) > Protocol.MAX_HEX_DIGEST_LENGTH -> ErrorCode.TOO_LARGE
                payload.credential?.isEmpty() == true -> ErrorCode.MALFORMED
                payload.expiresAt < 0 -> ErrorCode.MALFORMED
                else -> null
            }
        }

        is SessionReady -> when {
            payload.sessionId.isEmpty() -> ErrorCode.MALFORMED
            payload.sessionId.length > Protocol.MAX_ID_LENGTH -> ErrorCode.TOO_LARGE
            else -> validateCapabilities(payload.capabilities)
        }

        is StartStream -> when {
            payload.profile.isEmpty() -> ErrorCode.MALFORMED
            payload.profile.length > Protocol.MAX_NAME_LENGTH -> ErrorCode.TOO_LARGE
            else -> null
        }

        is SdpOffer -> sdpProblem(payload.sdp)
        is SdpAnswer -> sdpProblem(payload.sdp)

        is IceCandidate -> when {
            payload.candidate.isEmpty() -> ErrorCode.MALFORMED
            payload.candidate.length > Protocol.MAX_CANDIDATE_LENGTH -> ErrorCode.TOO_LARGE
            payload.sdpMid.length > Protocol.MAX_ID_LENGTH -> ErrorCode.TOO_LARGE
            payload.sdpMLineIndex < 0 || payload.sdpMLineIndex > 32 -> ErrorCode.MALFORMED
            else -> null
        }

        is CameraCapabilitiesUpdate -> validateCapabilities(payload.capabilities)

        is CameraControl -> when {
            (payload.lensFacing?.length ?: 0) > Protocol.MAX_NAME_LENGTH -> ErrorCode.TOO_LARGE
            payload.zoomRatio?.isFinite() == false -> ErrorCode.MALFORMED
            else -> null
        }

        is CameraControlAck -> when {
            payload.inReplyTo < 0 -> ErrorCode.MALFORMED
            payload.appliedLensFacing.length > Protocol.MAX_NAME_LENGTH -> ErrorCode.TOO_LARGE
            !payload.appliedZoomRatio.isFinite() -> ErrorCode.MALFORMED
            else -> null
        }

        is Health -> when {
            !payload.captureFps.isFinite() -> ErrorCode.MALFORMED
            (payload.thermalStatus?.length ?: 0) > Protocol.MAX_NAME_LENGTH -> ErrorCode.TOO_LARGE
            else -> null
        }

        is ProtocolErrorMessage -> when {
            payload.code.length > Protocol.MAX_NAME_LENGTH -> ErrorCode.TOO_LARGE
            payload.reason.length > Protocol.MAX_REASON_LENGTH -> ErrorCode.TOO_LARGE
            else -> null
        }

        StopStream -> null
        is SetPaused -> null
    }

    private fun sdpProblem(sdp: String): String? = when {
        sdp.isEmpty() -> ErrorCode.MALFORMED
        sdp.length > Protocol.MAX_SDP_LENGTH -> ErrorCode.TOO_LARGE
        else -> null
    }

    private fun validateCapabilities(capabilities: CameraCapabilities): String? = when {
        capabilities.lensFacing.length > Protocol.MAX_NAME_LENGTH -> ErrorCode.TOO_LARGE
        capabilities.captureModes.size > Protocol.MAX_CAPTURE_MODES -> ErrorCode.TOO_LARGE
        !capabilities.minZoomRatio.isFinite() -> ErrorCode.MALFORMED
        !capabilities.maxZoomRatio.isFinite() -> ErrorCode.MALFORMED
        capabilities.minZoomRatio <= 0f -> ErrorCode.MALFORMED
        capabilities.maxZoomRatio < capabilities.minZoomRatio -> ErrorCode.MALFORMED
        else -> null
    }
}
