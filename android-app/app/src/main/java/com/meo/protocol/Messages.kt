package com.meo.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The control-plane message set (ADR 0001, plan §5.3).
 *
 * Every message travels inside an [Envelope] over the authenticated, pinned TLS
 * channel. Nothing here is ever sent in the clear, and SDP in particular must
 * never travel by any other route — plan §6.3 makes that a correctness
 * requirement, not a preference.
 *
 * Direction is written on each payload because "who may send this" is part of
 * the protocol, not a convention. The session layer enforces it: a desktop that
 * sends a phone-only message is a protocol violation, not a message to answer.
 */
@Serializable
sealed class Payload {
    /** Whether this payload may legitimately arrive from the connecting peer. */
    abstract val fromDesktop: Boolean
}

// --- Handshake -------------------------------------------------------------

/**
 * Desktop → phone, always the first message on a connection.
 *
 * Carries the desktop's identity and a fresh nonce for the phone to bind its
 * authentication proof to. The phone has already checked the desktop's TLS SPKI
 * against either the hash it scanned from the QR or its stored pin by the time
 * this is read; this message says which pairing the desktop believes it is
 * resuming, it does not establish trust by itself.
 */
@Serializable
@SerialName("hello")
data class Hello(
    @SerialName("device_id") val deviceId: String,
    @SerialName("display_name") val displayName: String,
    /** Random, per-connection, hex. Binds the phone's proof to this connection. */
    @SerialName("nonce") val nonce: String,
    /**
     * Present only during first pairing: the desktop echoes the token it put in
     * the QR so the phone can tell a pairing attempt from a reconnect before it
     * decides which secret to prove.
     */
    @SerialName("pairing_token") val pairingToken: String? = null
) : Payload() {
    override val fromDesktop: Boolean get() = true
}

/**
 * Phone → desktop. Proves the phone holds the expected secret and supplies a
 * nonce for the desktop's own proof, so authentication is mutual.
 *
 * During first pairing [proof] is keyed by the one-time pairing token. On
 * reconnect it is keyed by the stored per-pairing credential. Both are bound to
 * the peer's pinned SPKI hash, so a proof captured on one connection cannot be
 * replayed onto another (plan §6.2 step 5).
 */
@Serializable
@SerialName("auth_proof")
data class AuthProof(
    @SerialName("device_id") val deviceId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("proof") val proof: String
) : Payload() {
    override val fromDesktop: Boolean get() = false
}

/**
 * Desktop → phone. The desktop's half of the mutual proof, plus — on first
 * pairing — the long-lived credential both sides store.
 *
 * The credential is issued by the desktop because the desktop is the session
 * coordinator (plan §5.1). It is never derived from the pairing token, so a
 * captured QR does not yield the durable secret.
 */
@Serializable
@SerialName("auth_accept")
data class AuthAccept(
    @SerialName("proof") val proof: String,
    /** Hex, >=256 bits. Present on first pairing only; null on reconnect. */
    @SerialName("credential") val credential: String? = null,
    /** Epoch millis. The sliding 30-day expiry of plan §6.1 step 8. */
    @SerialName("expires_at") val expiresAt: Long
) : Payload() {
    override val fromDesktop: Boolean get() = true
}

/** Phone → desktop. The session is authenticated and signalling may begin. */
@Serializable
@SerialName("session_ready")
data class SessionReady(
    @SerialName("session_id") val sessionId: String,
    @SerialName("capabilities") val capabilities: CameraCapabilities
) : Payload() {
    override val fromDesktop: Boolean get() = false
}

// --- Signalling ------------------------------------------------------------

/**
 * Desktop → phone. Asks the phone to go live at a named quality profile.
 *
 * The phone offers rather than answers because the phone owns the media: it
 * knows which encoders and capture modes actually exist on this device, and an
 * offer built from that is more honest than one negotiated blind.
 */
@Serializable
@SerialName("start_stream")
data class StartStream(
    @SerialName("profile") val profile: String
) : Payload() {
    override val fromDesktop: Boolean get() = true
}

@Serializable
@SerialName("sdp_offer")
data class SdpOffer(@SerialName("sdp") val sdp: String) : Payload() {
    override val fromDesktop: Boolean get() = false
}

@Serializable
@SerialName("sdp_answer")
data class SdpAnswer(@SerialName("sdp") val sdp: String) : Payload() {
    override val fromDesktop: Boolean get() = true
}

/**
 * Either direction. Candidates are filtered against the local-route policy on
 * receipt (plan §6.4); arriving over the authenticated channel is not on its
 * own sufficient reason to try one.
 */
@Serializable
@SerialName("ice_candidate")
data class IceCandidate(
    @SerialName("sdp_mid") val sdpMid: String,
    @SerialName("sdp_m_line_index") val sdpMLineIndex: Int,
    @SerialName("candidate") val candidate: String,
    @SerialName("from_desktop") val fromDesktopFlag: Boolean = true
) : Payload() {
    override val fromDesktop: Boolean get() = fromDesktopFlag
}

@Serializable
@SerialName("stop_stream")
data object StopStream : Payload() {
    override val fromDesktop: Boolean get() = true
}

/**
 * Desktop → phone. Pausing replaces the desktop's output with a privacy slate
 * (plan §6.5) and stops the phone encoding, but keeps the session alive.
 */
@Serializable
@SerialName("set_paused")
data class SetPaused(@SerialName("paused") val paused: Boolean) : Payload() {
    override val fromDesktop: Boolean get() = true
}

// --- Camera capability and control ----------------------------------------

@Serializable
data class CaptureMode(
    @SerialName("width") val width: Int,
    @SerialName("height") val height: Int,
    @SerialName("fps") val fps: Int
)

/**
 * What this device actually reported. Plan §7.3 requires a capability model
 * before any control UI, and requires that a control be clamped to the range
 * reported here rather than assumed.
 */
@Serializable
data class CameraCapabilities(
    @SerialName("lens_facing") val lensFacing: String,
    @SerialName("has_front") val hasFront: Boolean,
    @SerialName("has_back") val hasBack: Boolean,
    @SerialName("min_zoom_ratio") val minZoomRatio: Float,
    @SerialName("max_zoom_ratio") val maxZoomRatio: Float,
    @SerialName("torch_available") val torchAvailable: Boolean,
    @SerialName("capture_modes") val captureModes: List<CaptureMode> = emptyList()
)

@Serializable
@SerialName("camera_capabilities")
data class CameraCapabilitiesUpdate(
    @SerialName("capabilities") val capabilities: CameraCapabilities
) : Payload() {
    override val fromDesktop: Boolean get() = false
}

/**
 * Desktop → phone. Only the fields present are changed, so a control that this
 * device does not support can simply be omitted rather than sent and refused.
 */
@Serializable
@SerialName("camera_control")
data class CameraControl(
    @SerialName("lens_facing") val lensFacing: String? = null,
    @SerialName("zoom_ratio") val zoomRatio: Float? = null,
    @SerialName("torch") val torch: Boolean? = null
) : Payload() {
    override val fromDesktop: Boolean get() = true
}

/**
 * Phone → desktop. Reports the **applied** value, never the requested one
 * (plan §5.3). A desktop slider that snaps back is the correct outcome of
 * asking for a zoom this lens cannot do.
 */
@Serializable
@SerialName("camera_control_ack")
data class CameraControlAck(
    @SerialName("in_reply_to") val inReplyTo: Long,
    @SerialName("applied_lens_facing") val appliedLensFacing: String,
    @SerialName("applied_zoom_ratio") val appliedZoomRatio: Float,
    @SerialName("applied_torch") val appliedTorch: Boolean
) : Payload() {
    override val fromDesktop: Boolean get() = false
}

// --- Health and errors -----------------------------------------------------

@Serializable
@SerialName("health")
data class Health(
    @SerialName("capture_fps") val captureFps: Double,
    @SerialName("width") val width: Int,
    @SerialName("height") val height: Int,
    @SerialName("battery_percent") val batteryPercent: Int? = null,
    @SerialName("thermal_status") val thermalStatus: String? = null
) : Payload() {
    override val fromDesktop: Boolean get() = false
}

/**
 * Either direction. Plan §5.3 requires that a message we refuse produces an
 * explicit error rather than a silent drop, so the peer can distinguish "denied"
 * from "lost".
 */
@Serializable
@SerialName("error")
data class ProtocolErrorMessage(
    @SerialName("code") val code: String,
    @SerialName("reason") val reason: String,
    @SerialName("from_desktop") val fromDesktopFlag: Boolean = true
) : Payload() {
    override val fromDesktop: Boolean get() = fromDesktopFlag
}

/**
 * Error codes. Strings rather than an enum so that an unrecognised code from a
 * newer peer degrades to "something went wrong" instead of failing to parse.
 */
object ErrorCode {
    const val UNSUPPORTED_VERSION = "unsupported_version"
    const val MALFORMED = "malformed"
    const val TOO_LARGE = "too_large"
    const val UNKNOWN_TYPE = "unknown_type"
    const val UNEXPECTED_DIRECTION = "unexpected_direction"
    const val OUT_OF_ORDER = "out_of_order"
    const val NOT_AUTHENTICATED = "not_authenticated"
    const val AUTH_FAILED = "auth_failed"
    const val PAIRING_EXPIRED = "pairing_expired"
    const val RATE_LIMITED = "rate_limited"
    const val CANDIDATE_REJECTED = "candidate_rejected"
    const val INTERNAL = "internal"
}
