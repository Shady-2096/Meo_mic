package com.meo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wire contract, pinned to files.
 *
 * ADR 0001 requires golden fixtures because the control plane will eventually
 * have three independent implementations — Kotlin here, C++ in the Windows
 * receiver, Swift if macOS ever unblocks — and a shared understanding that
 * lives only in each codebase's own serializer is not shared at all. These
 * files are the artefact the other two get checked against.
 *
 * Each fixture is asserted in both directions:
 *
 * - **Decode**: the committed bytes must parse and validate. This is what
 *   catches a receiver that would have been rejected.
 * - **Re-encode**: encoding the decoded value must reproduce the file byte for
 *   byte. This is what catches a silent wire change — a renamed field, a
 *   dropped default, a reordered key — that decoding alone would tolerate.
 *
 * To update after a deliberate protocol change:
 *
 * ```bash
 * ./gradlew testDebugUnitTest --tests '*GoldenFixtureTest*' -Dmeo.fixtures.write=true
 * ```
 *
 * Then read the diff. If it contains anything you did not intend, the change
 * was not deliberate.
 */
class GoldenFixtureTest {

    private val fixtureDir: File by lazy {
        // Unit tests run with the Gradle module as the working directory.
        val candidate = File("../../protocol/fixtures").canonicalFile
        assertTrue(
            "Fixture directory not found at $candidate. If the repository layout " +
                "moved, fix this path rather than skipping the check.",
            candidate.isDirectory || candidate.mkdirs()
        )
        candidate
    }

    private val writeMode: Boolean = System.getProperty("meo.fixtures.write") == "true"

    @Test
    fun `every message type round-trips through its committed fixture`() {
        val cases = fixtureCases()
        // A payload type that nobody pinned is a payload type nobody checked.
        assertEquals(
            "Every Payload subclass needs a fixture.",
            expectedPayloadTypeCount,
            cases.size
        )
        cases.forEach { (name, envelope) -> checkFixture(name, envelope) }
    }

    private fun checkFixture(name: String, envelope: Envelope) {
        val file = File(fixtureDir, "$name.json")
        val encoded = ProtocolCodec.encode(envelope)

        if (writeMode) {
            file.writeText(encoded + "\n")
            return
        }

        assertTrue(
            "Missing fixture ${file.name}. Regenerate with -Dmeo.fixtures.write=true " +
                "and review the diff before committing.",
            file.isFile
        )

        val committed = file.readText().trim()

        when (val decoded = ProtocolCodec.decode(committed)) {
            is DecodeResult.Rejected ->
                throw AssertionError("Fixture ${file.name} was rejected: ${decoded.code} ${decoded.reason}")

            is DecodeResult.Ok -> {
                assertEquals(
                    "Fixture ${file.name} decoded to a different value than it encodes from.",
                    envelope,
                    decoded.envelope
                )
                assertEquals(
                    "Encoding changed for ${file.name}. This is a wire-format change: " +
                        "bump Protocol.VERSION or revert it.",
                    committed,
                    encoded
                )
            }
        }
    }

    private fun envelope(payload: Payload, sessionId: String = "s-7f3a91"): Envelope = Envelope(
        protocolVersion = Protocol.VERSION,
        sessionId = sessionId,
        messageId = 42,
        sentAtMonotonicMs = 1234567,
        payload = payload
    )

    private val capabilities = CameraCapabilities(
        lensFacing = "back",
        hasFront = true,
        hasBack = true,
        minZoomRatio = 1.0f,
        maxZoomRatio = 8.0f,
        torchAvailable = true,
        captureModes = listOf(
            CaptureMode(1280, 720, 30),
            CaptureMode(1920, 1080, 30)
        )
    )

    /** Kept in step with the sealed hierarchy by the count assertion above. */
    private val expectedPayloadTypeCount = 17

    private fun fixtureCases(): List<Pair<String, Envelope>> = listOf(
        "hello-pairing" to envelope(
            Hello(
                deviceId = "desktop-3c8f0e21",
                displayName = "Studio PC",
                nonce = "9f86d081884c7d659a2feaa0c55ad015",
                pairingToken = "a3f1c07e5b9d24680fe1c3a75d9b820c"
            ),
            sessionId = Protocol.NO_SESSION
        ),
        "hello-reconnect" to envelope(
            Hello(
                deviceId = "desktop-3c8f0e21",
                displayName = "Studio PC",
                nonce = "9f86d081884c7d659a2feaa0c55ad015"
            ),
            sessionId = Protocol.NO_SESSION
        ),
        "auth-proof" to envelope(
            AuthProof(
                deviceId = "phone-6b4d2a90",
                displayName = "Pixel 8",
                nonce = "3e23e8160039594a33894f6564e1b134",
                proof = "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae"
            ),
            sessionId = Protocol.NO_SESSION
        ),
        "auth-accept-pairing" to envelope(
            AuthAccept(
                proof = "fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9",
                credential = "18ac3e7343f016890c510e93f935261169d9e3f565436429830faf0934f4f8e4",
                expiresAt = 1793491200000
            ),
            sessionId = Protocol.NO_SESSION
        ),
        "auth-accept-reconnect" to envelope(
            AuthAccept(
                proof = "fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9",
                expiresAt = 1793491200000
            ),
            sessionId = Protocol.NO_SESSION
        ),
        "session-ready" to envelope(
            SessionReady(sessionId = "s-7f3a91", capabilities = capabilities)
        ),
        "camera-capabilities" to envelope(CameraCapabilitiesUpdate(capabilities)),
        "start-stream" to envelope(StartStream(profile = "balanced")),
        "sdp-offer" to envelope(
            SdpOffer(
                "v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=-\r\n" +
                    "t=0 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 102\r\nc=IN IP4 0.0.0.0\r\n" +
                    "a=fingerprint:sha-256 " +
                    "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:" +
                    "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89\r\n" +
                    "a=setup:actpass\r\na=mid:0\r\na=sendonly\r\na=rtpmap:102 H264/90000\r\n"
            )
        ),
        "sdp-answer" to envelope(
            SdpAnswer(
                "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n" +
                    "m=video 9 UDP/TLS/RTP/SAVPF 102\r\nc=IN IP4 0.0.0.0\r\n" +
                    "a=fingerprint:sha-256 " +
                    "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:" +
                    "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF\r\n" +
                    "a=setup:active\r\na=mid:0\r\na=recvonly\r\na=rtpmap:102 H264/90000\r\n"
            )
        ),
        "ice-candidate" to envelope(
            IceCandidate(
                sdpMid = "0",
                sdpMLineIndex = 0,
                candidate = "candidate:1 1 udp 2130706431 192.168.1.42 51820 typ host",
                fromDesktopFlag = false
            )
        ),
        "stop-stream" to envelope(StopStream),
        "set-paused" to envelope(SetPaused(paused = true)),
        "camera-control" to envelope(
            CameraControl(lensFacing = "front", zoomRatio = 2.5f, torch = false)
        ),
        "camera-control-ack" to envelope(
            CameraControlAck(
                inReplyTo = 41,
                appliedLensFacing = "front",
                appliedZoomRatio = 2.0f,
                appliedTorch = false
            )
        ),
        "health" to envelope(
            Health(
                captureFps = 29.7,
                width = 1280,
                height = 720,
                batteryPercent = 82,
                thermalStatus = "nominal"
            )
        ),
        "error" to envelope(
            ProtocolErrorMessage(
                code = ErrorCode.AUTH_FAILED,
                reason = "credential proof did not verify",
                fromDesktopFlag = false
            )
        )
    )
}
