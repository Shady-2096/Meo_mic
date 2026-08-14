package com.meo.camera.service

import android.content.Context
import com.meo.camera.capture.CameraCaptureManager
import com.meo.camera.capture.CameraLens
import com.meo.camera.encode.NullFrameSink
import com.meo.network.ControlSession
import com.meo.network.ControlSessionHost
import com.meo.network.WebRtcPeer
import com.meo.pairing.Pairing
import com.meo.protocol.CameraCapabilities
import com.meo.protocol.CameraControl
import com.meo.protocol.CameraControlAck
import com.meo.protocol.CaptureMode
import com.meo.protocol.Health
import com.meo.protocol.IceCandidate
import com.meo.protocol.SdpOffer
import org.webrtc.PeerConnection
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live session state, for the UI and the notification.
 *
 * Plan §6.5 wants Connected, Live and Paused to be distinct and visible, and
 * §6.4 wants the active peer and its address shown, so all of that is here
 * rather than inferred from a boolean.
 */
data class StreamingState(
    val connectedDesktopName: String? = null,
    val peerAddress: String? = null,
    val isStreaming: Boolean = false,
    val isPaused: Boolean = false,
    val lastError: String? = null
) {
    val isConnected: Boolean get() = connectedDesktopName != null
}

/**
 * Joins the control session to the camera and the media plane.
 *
 * The split is deliberate: [com.meo.network.ControlSession] knows about
 * authentication and message shape and nothing about cameras; the capture
 * manager knows about cameras and nothing about the network. This class is the
 * only place that knows both, which is why it is also the only place that has
 * to think about their ordering — an offer must not be built before there is a
 * camera producing frames, and the frame sink must be detached before the peer
 * connection is disposed.
 */
class CameraSessionCoordinator(
    private val context: Context,
    private val capture: CameraCaptureManager,
    private val onStateChanged: (StreamingState) -> Unit
) : ControlSessionHost {

    @Volatile
    private var state = StreamingState()

    @Volatile
    private var session: ControlSession? = null

    @Volatile
    private var peer: WebRtcPeer? = null

    private val healthExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "meo-health").apply { isDaemon = true }
        }
    private val healthScheduled = AtomicBoolean(false)

    override fun capabilitiesSnapshot(): CameraCapabilities {
        val current = capture.state.value
        return CameraCapabilities(
            lensFacing = current.lens.wireName,
            hasFront = current.hasFrontCamera,
            hasBack = current.hasBackCamera,
            // A camera that has not bound yet reports 1..1 rather than a range
            // it has not been told about, so the desktop never offers a zoom
            // this device did not claim.
            minZoomRatio = current.minZoomRatio,
            maxZoomRatio = current.maxZoomRatio,
            torchAvailable = current.torchAvailable,
            captureModes = if (current.width > 0) {
                listOf(CaptureMode(current.width, current.height, DEFAULT_FPS))
            } else {
                emptyList()
            }
        )
    }

    override fun onAuthenticated(session: ControlSession, pairing: Pairing) {
        this.session = session
        update {
            it.copy(
                connectedDesktopName = pairing.displayName.ifBlank { pairing.desktopDeviceId },
                peerAddress = session.peerAddress.hostAddress,
                lastError = null
            )
        }
        startHealthReports()
    }

    override fun onStartStream(session: ControlSession, profile: String) {
        if (peer != null) return // already live; a repeated start is a no-op

        val created = WebRtcPeer(context, peerEvents(session))
        if (!created.start()) {
            update { it.copy(lastError = "the media engine could not start") }
            return
        }
        peer = created

        // Frames only start flowing once there is somewhere for them to go.
        // Until this line the sink is NullFrameSink and capture costs nothing
        // beyond the preview.
        capture.frameSink = created.frameSink ?: NullFrameSink
        update { it.copy(isStreaming = true, isPaused = false, lastError = null) }
        created.createOffer()
    }

    override fun onSdpAnswer(session: ControlSession, sdp: String) {
        peer?.acceptAnswer(sdp)
            ?: update { it.copy(lastError = "an answer arrived with no stream in progress") }
    }

    override fun onRemoteIceCandidate(session: ControlSession, candidate: IceCandidate) {
        peer?.addRemoteCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
    }

    override fun onStopStream(session: ControlSession) {
        teardownStream()
    }

    override fun onPauseChanged(session: ControlSession, paused: Boolean) {
        // Pausing detaches the sink rather than stopping the camera. The OS
        // privacy indicator stays on — the camera really is still open — and
        // resuming is immediate rather than a fresh bind. The desktop shows its
        // privacy slate because no frames arrive.
        val active = peer
        capture.frameSink = if (paused || active == null) {
            NullFrameSink
        } else {
            active.frameSink ?: NullFrameSink
        }
        update { it.copy(isPaused = paused) }
    }

    override fun onCameraControl(session: ControlSession, control: CameraControl, messageId: Long) {
        control.lensFacing?.let { requested ->
            CameraLens.fromWireName(requested)?.let(capture::setLens)
        }
        control.zoomRatio?.let(capture::setZoomRatio)
        control.torch?.let(capture::setTorch)

        // Read back rather than echoing the request. A lens this device does
        // not have, or a zoom past its range, must come back as what actually
        // happened (plan §5.3, §7.3).
        val applied = capture.state.value
        session.send(
            CameraControlAck(
                inReplyTo = messageId,
                appliedLensFacing = applied.lens.wireName,
                appliedZoomRatio = applied.zoomRatio,
                appliedTorch = applied.torchEnabled
            )
        )
    }

    override fun onClosed(session: ControlSession, reason: String) {
        if (this.session !== session) return
        teardownStream()
        this.session = null
        stopHealthReports()
        update {
            StreamingState(lastError = reason.takeIf { _ -> !reason.startsWith("peer closed") })
        }
    }

    /** Called when the service stops, so nothing outlives the camera. */
    fun close() {
        teardownStream()
        stopHealthReports()
        healthExecutor.shutdownNow()
        session = null
    }

    private fun teardownStream() {
        // Order matters: detach the sink first so no frame is delivered into a
        // peer connection that is being disposed.
        capture.frameSink = NullFrameSink
        peer?.close()
        peer = null
        update { it.copy(isStreaming = false, isPaused = false) }
    }

    private fun peerEvents(session: ControlSession) = object : WebRtcPeer.Events {
        override fun onLocalOffer(sdp: String) {
            session.send(SdpOffer(sdp))
        }

        override fun onLocalIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
            session.send(
                IceCandidate(
                    sdpMid = sdpMid,
                    sdpMLineIndex = sdpMLineIndex,
                    candidate = candidate,
                    fromDesktopFlag = false
                )
            )
        }

        override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
            when (state) {
                PeerConnection.PeerConnectionState.FAILED ->
                    update { it.copy(lastError = "the video connection failed", isStreaming = false) }

                PeerConnection.PeerConnectionState.CLOSED ->
                    update { it.copy(isStreaming = false) }

                else -> Unit
            }
        }

        override fun onFailure(reason: String) {
            update { it.copy(lastError = reason) }
        }
    }

    private fun startHealthReports() {
        if (!healthScheduled.compareAndSet(false, true)) return
        healthExecutor.scheduleWithFixedDelay(
            {
                val current = capture.state.value
                session?.send(
                    Health(
                        captureFps = current.framesPerSecond,
                        width = current.width,
                        height = current.height
                    )
                )
            },
            HEALTH_INTERVAL_SECONDS,
            HEALTH_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    private fun stopHealthReports() {
        healthScheduled.set(false)
    }

    private fun update(transform: (StreamingState) -> StreamingState) {
        val next = transform(state)
        state = next
        onStateChanged(next)
    }

    private companion object {
        const val DEFAULT_FPS = 30
        const val HEALTH_INTERVAL_SECONDS = 2L
    }
}
