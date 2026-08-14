package com.meo.network

import android.content.Context
import com.meo.camera.encode.FrameSink
import com.meo.camera.encode.WebRtcFrameSink
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate as WebRtcIceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * The media plane: one peer connection, sending video, on the LAN only.
 *
 * ## Local-only, structurally
 *
 * The `RTCConfiguration` below is created with an **empty** ICE server list.
 * That is not a default that could drift: with no STUN server the only
 * candidates that can be gathered are host candidates, and with no TURN server
 * there is nothing that could relay the user's camera through a third party.
 * Plan §6.4 asks for local-only to be an enforced property; this is the
 * enforcement, and [IceCandidateFilter] is the second layer that checks what
 * arrives from the other side.
 *
 * ## What is verified where
 *
 * SDP reaches this class only from [ControlSession], over the authenticated and
 * pinned TLS channel. Before a remote description is applied, [SdpFingerprint]
 * checks that it carries exactly one agreed, strong DTLS fingerprint; libwebrtc
 * then refuses any DTLS handshake that does not match it. Together that is
 * plan §6.3's requirement.
 *
 * ## Untested
 *
 * Everything in this file needs `libjingle_peerconnection_so.so` and a camera.
 * It is compiled but has never run: no Android device is attached to this
 * repository's development host. The pieces that *can* be checked without a
 * device were deliberately pushed out of it — candidate policy into
 * [IceCandidateFilter], fingerprint shape into [SdpFingerprint], colour
 * conversion into `YuvConverter` — and each of those has tests.
 */
class WebRtcPeer(
    context: Context,
    private val events: Events
) {
    interface Events {
        fun onLocalOffer(sdp: String)
        fun onLocalIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String)
        fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
        fun onFailure(reason: String)
    }

    private val appContext = context.applicationContext
    private val eglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    @Volatile
    var frameSink: FrameSink? = null
        private set

    /** Non-null once a remote description has been accepted. */
    @Volatile
    var remoteFingerprint: String? = null
        private set

    fun start(): Boolean {
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    // No field trials and no internal tracer: both are
                    // diagnostics that write outside our control, and plan §6.5
                    // rules out anything that leaves the device.
                    .createInitializationOptions()
            )

            val encoderFactory = DefaultVideoEncoderFactory(
                eglBase.eglBaseContext,
                /* enableIntelVp8Encoder = */ true,
                // H.264 High profile is left off: ADR 0001 negotiates
                // Baseline/Main for the broadest decoder coverage, and a
                // profile the desktop cannot decode is worse than a lower one.
                /* enableH264HighProfile = */ false
            )
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

            val builtFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            factory = builtFactory

            val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                // ALL is correct here and is *not* a permissive choice. The
                // enum's restrictive values are the wrong restrictions for us:
                // RELAY would force a TURN server we refuse to have, and NOHOST
                // excludes exactly the host candidates that are the only ones
                // we want. With an empty ICE server list, ALL can gather
                // nothing but host candidates anyway, and IceCandidateFilter
                // rejects anything else that turns up.
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                keyType = PeerConnection.KeyType.ECDSA
            }

            val connection = builtFactory.createPeerConnection(configuration, observer)
                ?: return failStart("could not create the peer connection")
            peerConnection = connection

            val source = builtFactory.createVideoSource(/* isScreencast = */ false)
            videoSource = source
            frameSink = WebRtcFrameSink(source.capturerObserver)

            val track = builtFactory.createVideoTrack(VIDEO_TRACK_ID, source)
            videoTrack = track
            connection.addTrack(track, listOf(STREAM_ID))
            true
        } catch (error: Exception) {
            failStart("WebRTC failed to start: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun failStart(reason: String): Boolean {
        events.onFailure(reason)
        close()
        return false
    }

    /**
     * Builds the offer. The phone offers because it owns the media and knows
     * which encoders this device actually has.
     */
    fun createOffer() {
        val connection = peerConnection ?: return events.onFailure("no peer connection")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        connection.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(description: SessionDescription) {
                connection.setLocalDescription(
                    object : SimpleSdpObserver("setLocalDescription") {},
                    description
                )
                events.onLocalOffer(description.description)
            }
        }, constraints)
    }

    /**
     * Applies the desktop's answer, after checking it carries a fingerprint
     * worth binding the media handshake to.
     */
    fun acceptAnswer(sdp: String) {
        val connection = peerConnection ?: return events.onFailure("no peer connection")

        when (val fingerprint = SdpFingerprint.extract(sdp)) {
            is SdpFingerprint.Result.Rejected -> {
                // Plan §6.3: a mismatch aborts the session. So does an answer
                // with nothing to match against.
                events.onFailure("answer refused: ${fingerprint.reason}")
                return
            }

            is SdpFingerprint.Result.Ok -> remoteFingerprint = fingerprint.value
        }

        connection.setRemoteDescription(
            object : SimpleSdpObserver("setRemoteDescription") {},
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    /**
     * Adds a remote candidate. [ControlSession] has already applied the
     * local-route policy; this re-checks rather than trusting that, because a
     * candidate reaching libwebrtc is the point of no return.
     */
    fun addRemoteCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        if (IceCandidateFilter.evaluate(candidate) !is IceCandidateFilter.Verdict.Accept) {
            events.onFailure("refused a remote ICE candidate that is not on a local route")
            return
        }
        peerConnection?.addIceCandidate(WebRtcIceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun close() {
        try {
            videoTrack?.dispose()
            videoSource?.dispose()
            peerConnection?.dispose()
            factory?.dispose()
            eglBase.release()
        } catch (_: Exception) {
            // Disposal races with the native threads shutting down. Nothing
            // useful remains to do either way.
        } finally {
            videoTrack = null
            videoSource = null
            peerConnection = null
            factory = null
            frameSink = null
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: WebRtcIceCandidate) {
            // Our own candidates are filtered too. A phone on a VPN would
            // otherwise offer the desktop a tunnel address to dial.
            val verdict = IceCandidateFilter.evaluate(candidate.sdp)
            if (verdict is IceCandidateFilter.Verdict.Accept) {
                events.onLocalIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            events.onConnectionStateChanged(newState)
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out WebRtcIceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private open inner class SimpleSdpObserver(private val stage: String) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            events.onFailure("$stage failed: ${error ?: "unknown"}")
        }

        override fun onSetFailure(error: String?) {
            events.onFailure("$stage failed: ${error ?: "unknown"}")
        }
    }

    private companion object {
        const val VIDEO_TRACK_ID = "meo-video"
        const val STREAM_ID = "meo-stream"
    }
}
