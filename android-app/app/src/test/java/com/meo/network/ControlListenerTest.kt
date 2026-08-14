package com.meo.network

import com.meo.pairing.AttemptLimiter
import com.meo.pairing.AuthProofs
import com.meo.pairing.DeviceTrust
import com.meo.pairing.InMemorySecureStore
import com.meo.pairing.Pairing
import com.meo.pairing.PairingInvite
import com.meo.protocol.CameraCapabilities
import com.meo.protocol.CameraControl
import com.meo.protocol.ErrorCode
import com.meo.protocol.Health
import com.meo.protocol.IceCandidate
import com.meo.protocol.Protocol
import com.meo.protocol.ProtocolErrorMessage
import com.meo.protocol.SessionReady
import com.meo.protocol.SetPaused
import com.meo.protocol.StartStream
import com.meo.protocol.StopStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The transport, end to end, over a real TLS connection.
 *
 * There is no Android device attached to this repository's development host and
 * no desktop receiver has been written, so "it works" has to mean something
 * that can actually be checked here. This is that check: the real listener, the
 * real pinning, the real handshake, and a real socket — with
 * [TestDesktopClient] standing in for the computer.
 *
 * What this does **not** cover is written down honestly in the Android section
 * of `CAMERA_BUILD_PLAN.md`: nothing here touches a camera sensor, a hardware
 * encoder, or the WebRTC native library.
 */
class ControlListenerTest {

    private lateinit var trust: DeviceTrust
    private lateinit var listener: ControlListener
    private lateinit var host: RecordingHost
    private var now = 1_780_000_000_000L

    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    private class RecordingHost : ControlSessionHost {
        val authenticated = CopyOnWriteArrayList<Pairing>()
        val started = CopyOnWriteArrayList<String>()
        val stopped = CopyOnWriteArrayList<String>()
        val paused = CopyOnWriteArrayList<Boolean>()
        val controls = CopyOnWriteArrayList<CameraControl>()
        val answers = CopyOnWriteArrayList<String>()
        val candidates = CopyOnWriteArrayList<IceCandidate>()
        val closed = CopyOnWriteArrayList<String>()

        override fun capabilitiesSnapshot() = CameraCapabilities(
            lensFacing = "back",
            hasFront = true,
            hasBack = true,
            minZoomRatio = 1f,
            maxZoomRatio = 8f,
            torchAvailable = true
        )

        override fun onAuthenticated(session: ControlSession, pairing: Pairing) {
            authenticated += pairing
        }

        override fun onStartStream(session: ControlSession, profile: String) {
            started += profile
        }

        override fun onStopStream(session: ControlSession) {
            stopped += "stop"
        }

        override fun onPauseChanged(session: ControlSession, isPaused: Boolean) {
            paused += isPaused
        }

        override fun onCameraControl(session: ControlSession, control: CameraControl, messageId: Long) {
            controls += control
        }

        override fun onSdpAnswer(session: ControlSession, sdp: String) {
            answers += sdp
        }

        override fun onRemoteIceCandidate(session: ControlSession, candidate: IceCandidate) {
            candidates += candidate
        }

        override fun onClosed(session: ControlSession, reason: String) {
            closed += reason
        }
    }

    @Before
    fun setUp() {
        trust = DeviceTrust(InMemorySecureStore(), displayName = "Test Phone")
        host = RecordingHost()
        listener = ControlListener(trust, host, clock = { now }, limiter = AttemptLimiter())
        assertTrue("listener must bind", listener.start(bindAddress = loopback, port = 0))
    }

    @After
    fun tearDown() {
        listener.stop()
    }

    // --- Helpers -----------------------------------------------------------

    private fun invite(
        desktop: TestDesktopClient,
        token: String = AuthProofs.newSecret(),
        expiresAt: Long = now + 5 * 60_000
    ) = PairingInvite(
        protocolVersion = Protocol.VERSION,
        desktopDeviceId = desktop.deviceId,
        desktopDisplayName = desktop.displayName,
        desktopSpkiPin = desktop.pin,
        token = token,
        expiresAt = expiresAt
    )

    private fun connect(desktop: TestDesktopClient) =
        desktop.connect(loopback, listener.boundPort, trust.pin)

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $description")
    }

    /** Pairs a fresh desktop and returns it with the credential it issued. */
    private fun pairedDesktop(deviceId: String = "desktop-test-1"): Pair<TestDesktopClient, String> {
        val desktop = TestDesktopClient(deviceId = deviceId)
        val pairingInvite = invite(desktop)
        listener.beginPairing(pairingInvite)
        val credential = AuthProofs.newSecret()

        connect(desktop).use { connection ->
            val result = connection.handshake(
                token = pairingInvite.token,
                issuedCredential = credential
            )
            assertTrue("pairing should succeed, got $result", result is SessionReady)
        }
        waitFor("pairing stored") { trust.pairings.find(desktop.deviceId) != null }
        waitFor("session released") { listener.activeSession == null }
        return desktop to credential
    }

    // --- Pairing -----------------------------------------------------------

    @Test
    fun `a desktop pairs, and the phone stores its key and credential`() {
        val desktop = TestDesktopClient()
        val pairingInvite = invite(desktop)
        listener.beginPairing(pairingInvite)
        val credential = AuthProofs.newSecret()

        connect(desktop).use { connection ->
            val ready = connection.handshake(
                token = pairingInvite.token,
                issuedCredential = credential
            )
            assertTrue(ready is SessionReady)
            ready as SessionReady
            assertTrue(ready.sessionId.startsWith("s-"))
            assertTrue(ready.capabilities.torchAvailable)
        }

        waitFor("pairing stored") { trust.pairings.find(desktop.deviceId) != null }
        val stored = trust.pairings.find(desktop.deviceId)!!
        assertEquals(desktop.pin, stored.desktopSpkiPin)
        assertEquals(credential, stored.credential)
        assertEquals(now + Pairing.SLIDING_VALIDITY_MS, stored.expiresAt)
        waitFor("host notified") { host.authenticated.size == 1 }
    }

    @Test
    fun `the pairing code works once and then never again`() {
        val (desktop, _) = pairedDesktop()
        // The same QR, rescanned and replayed by anyone who photographed it.
        assertNull("invite must be consumed", listener.pairingInProgress)

        val replay = TestDesktopClient(identity = desktop.identity)
        connect(replay).use { connection ->
            val result = connection.handshake(token = AuthProofs.newSecret())
            assertTrue(result is ProtocolErrorMessage)
            assertEquals(ErrorCode.AUTH_FAILED, (result as ProtocolErrorMessage).code)
        }
    }

    @Test
    fun `an expired pairing code is refused`() {
        val desktop = TestDesktopClient()
        val pairingInvite = invite(desktop, expiresAt = now + 1000)
        listener.beginPairing(pairingInvite)
        now += 60_000 // the user took a minute too long

        connect(desktop).use { connection ->
            val result = connection.handshake(token = pairingInvite.token)
            assertTrue(result is ProtocolErrorMessage)
            assertEquals(ErrorCode.PAIRING_EXPIRED, (result as ProtocolErrorMessage).code)
        }
        assertNull(trust.pairings.find(desktop.deviceId))
    }

    @Test
    fun `a wrong pairing code is refused`() {
        val desktop = TestDesktopClient()
        listener.beginPairing(invite(desktop))

        connect(desktop).use { connection ->
            val result = connection.handshake(token = AuthProofs.newSecret())
            assertTrue(result is ProtocolErrorMessage)
            assertEquals(ErrorCode.AUTH_FAILED, (result as ProtocolErrorMessage).code)
        }
        assertNull(trust.pairings.find(desktop.deviceId))
    }

    @Test
    fun `a desktop that cannot prove the code back is refused and not stored`() {
        // Mutual authentication: the phone proving to the desktop is not
        // enough. A desktop that scanned nothing cannot answer the challenge.
        val desktop = TestDesktopClient()
        val pairingInvite = invite(desktop)
        listener.beginPairing(pairingInvite)

        connect(desktop).use { connection ->
            val result = connection.handshake(
                token = pairingInvite.token,
                corruptOurProof = true
            )
            assertTrue(result is ProtocolErrorMessage)
            assertEquals(ErrorCode.AUTH_FAILED, (result as ProtocolErrorMessage).code)
        }
        assertNull(trust.pairings.find(desktop.deviceId))
    }

    @Test
    fun `a desktop whose key is not the scanned one cannot even complete TLS`() {
        // The impostor knows the token — say it was photographed — but the QR
        // also pinned a public key, and this is a different computer.
        val genuine = TestDesktopClient()
        val pairingInvite = invite(genuine)
        listener.beginPairing(pairingInvite)

        val impostor = TestDesktopClient(deviceId = genuine.deviceId)
        try {
            connect(impostor).use { connection ->
                connection.handshake(token = pairingInvite.token)
            }
            throw AssertionError("an unpinned desktop must not complete the TLS handshake")
        } catch (_: IOException) {
            // Correct: rejected at the TLS layer, before any protocol message.
            // The exact exception varies — a clean TLS alert under 1.2, often a
            // broken pipe under 1.3, where the client finishes optimistically
            // and only learns of the refusal when it writes. Both are IOException
            // and neither yields a session, which is the property under test.
        }
        assertNull(trust.pairings.find(genuine.deviceId))
    }

    // --- Reconnect ---------------------------------------------------------

    @Test
    fun `a paired desktop reconnects with its credential and slides its expiry`() {
        val (desktop, credential) = pairedDesktop()
        now += 10L * 24 * 60 * 60 * 1000 // ten days later

        connect(desktop).use { connection ->
            val ready = connection.handshake(credential = credential)
            assertTrue("reconnect should succeed, got $ready", ready is SessionReady)
        }

        waitFor("expiry renewed") {
            trust.pairings.find(desktop.deviceId)?.expiresAt == now + Pairing.SLIDING_VALIDITY_MS
        }
        assertEquals(now, trust.pairings.find(desktop.deviceId)?.lastSeenAt)
    }

    @Test
    fun `a reconnect with the wrong credential is refused`() {
        val (desktop, _) = pairedDesktop()

        connect(desktop).use { connection ->
            try {
                connection.handshake(credential = AuthProofs.newSecret())
                throw AssertionError("expected the phone's proof check to fail first")
            } catch (error: AssertionError) {
                // The phone's own proof is computed with the real credential,
                // so a desktop with the wrong one cannot verify it. That is the
                // mutual half of authentication working.
                assertTrue(error.message!!.contains("proof did not verify"))
            }
        }
        // And the pairing is untouched.
        assertNotNull(trust.pairings.find(desktop.deviceId))
    }

    @Test
    fun `an unpaired desktop cannot reconnect`() {
        val (paired, credential) = pairedDesktop()
        // A different computer, but it presents a key the phone trusts because
        // it reuses the paired identity — so TLS succeeds and the refusal has
        // to come from the protocol layer.
        val stranger = TestDesktopClient(
            deviceId = "desktop-unknown",
            identity = paired.identity
        )

        connect(stranger).use { connection ->
            val result = connection.handshake(credential = credential)
            assertTrue(result is ProtocolErrorMessage)
            assertEquals(ErrorCode.NOT_AUTHENTICATED, (result as ProtocolErrorMessage).code)
        }
    }

    @Test
    fun `an expired pairing stops being trusted at the TLS layer`() {
        // Unlike an expired pairing *code*, an expired pairing gets no grace:
        // it is simply no longer a computer this phone knows, so its key is not
        // in the acceptable set and the connection never reaches the protocol.
        val (desktop, credential) = pairedDesktop()
        now += Pairing.SLIDING_VALIDITY_MS + 1

        try {
            connect(desktop).use { connection -> connection.handshake(credential = credential) }
            throw AssertionError("an expired pairing must not connect")
        } catch (_: IOException) {
            // Expected.
        }
        assertTrue(trust.pairings.trustedPins(now).isEmpty())
    }

    @Test
    fun `a revoked pairing cannot reconnect`() {
        val (desktop, credential) = pairedDesktop()
        trust.pairings.remove(desktop.deviceId)

        try {
            connect(desktop).use { connection ->
                connection.handshake(credential = credential)
            }
            throw AssertionError("a revoked desktop must not connect")
        } catch (_: IOException) {
            // Revocation takes effect on the next connection, because the trust
            // manager reads the store on each handshake rather than capturing
            // the set when the listener started.
        }
    }

    // --- Session behaviour -------------------------------------------------

    @Test
    fun `session messages reach the host once authenticated`() {
        val (desktop, credential) = pairedDesktop()

        connect(desktop).use { connection ->
            assertTrue(connection.handshake(credential = credential) is SessionReady)

            connection.send(StartStream(profile = "balanced"))
            connection.send(CameraControl(lensFacing = "front", zoomRatio = 2f))
            connection.send(SetPaused(paused = true))
            connection.send(StopStream)

            waitFor("start delivered") { host.started.contains("balanced") }
            waitFor("control delivered") { host.controls.isNotEmpty() }
            waitFor("pause delivered") { host.paused.contains(true) }
            waitFor("stop delivered") { host.stopped.isNotEmpty() }
        }
        assertEquals("front", host.controls.first().lensFacing)
    }

    @Test
    fun `a session message before authentication is refused`() {
        val desktop = TestDesktopClient()
        listener.beginPairing(invite(desktop))

        connect(desktop).use { connection ->
            connection.send(StartStream(profile = "balanced"))
            val result = connection.receivePayload()
            assertTrue(result is ProtocolErrorMessage)
            assertEquals(ErrorCode.NOT_AUTHENTICATED, (result as ProtocolErrorMessage).code)
        }
        assertTrue(host.started.isEmpty())
    }

    @Test
    fun `a replayed message id is refused`() {
        val (desktop, credential) = pairedDesktop()

        connect(desktop).use { connection ->
            assertTrue(connection.handshake(credential = credential) is SessionReady)
            connection.send(StartStream(profile = "balanced"), messageIdOverride = 50)
            waitFor("first delivered") { host.started.isNotEmpty() }

            connection.send(StartStream(profile = "replayed"), messageIdOverride = 50)
            val result = connection.receivePayload()
            assertTrue(result is ProtocolErrorMessage)
            assertEquals(ErrorCode.OUT_OF_ORDER, (result as ProtocolErrorMessage).code)
        }
        assertFalse(host.started.contains("replayed"))
    }

    @Test
    fun `an unknown message type is refused but does not end the session`() {
        val (desktop, credential) = pairedDesktop()

        connect(desktop).use { connection ->
            assertTrue(connection.handshake(credential = credential) is SessionReady)

            connection.sendRaw(
                ("""{"protocol_version":1,"session_id":"${connection.sessionId}",""" +
                    """"message_id":900,"sent_at_monotonic_ms":1,""" +
                    """"payload":{"type":"wipe_the_phone"}}""").toByteArray()
            )
            val error = connection.receivePayload()
            assertEquals(ErrorCode.UNKNOWN_TYPE, (error as ProtocolErrorMessage).code)

            // Still usable afterwards: an unknown type is a newer peer, not an
            // attack, and dropping the session would break forward compatibility.
            connection.send(StartStream(profile = "still-alive"), messageIdOverride = 901)
            waitFor("session survived") { host.started.contains("still-alive") }
        }
    }

    @Test
    fun `an oversized frame is refused with an error rather than a bare disconnect`() {
        val (desktop, credential) = pairedDesktop()

        connect(desktop).use { connection ->
            assertTrue(connection.handshake(credential = credential) is SessionReady)
            // Announce 2 GB. If the phone allocated what it was told, this test
            // would not finish.
            connection.sendRawFrame(byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
            val error = connection.receivePayload()
            assertEquals(ErrorCode.TOO_LARGE, (error as ProtocolErrorMessage).code)
        }
    }

    @Test
    fun `a phone-only message sent by the desktop is refused`() {
        val (desktop, credential) = pairedDesktop()

        connect(desktop).use { connection ->
            assertTrue(connection.handshake(credential = credential) is SessionReady)
            connection.send(Health(captureFps = 30.0, width = 1280, height = 720))
            val error = connection.receivePayload()
            assertEquals(ErrorCode.UNEXPECTED_DIRECTION, (error as ProtocolErrorMessage).code)
        }
    }

    @Test
    fun `an ice candidate that is not on a local route is refused without ending the session`() {
        val (desktop, credential) = pairedDesktop()

        connect(desktop).use { connection ->
            assertTrue(connection.handshake(credential = credential) is SessionReady)
            connection.send(
                IceCandidate(
                    sdpMid = "0",
                    sdpMLineIndex = 0,
                    candidate = "candidate:1 1 udp 2130706431 93.184.216.34 51820 typ host",
                    fromDesktopFlag = true
                )
            )
            val error = connection.receivePayload()
            assertEquals(ErrorCode.CANDIDATE_REJECTED, (error as ProtocolErrorMessage).code)
            assertTrue(host.candidates.isEmpty())
        }
    }

    @Test
    fun `a second desktop is refused while one is connected`() {
        // Distinct device ids: pairings are keyed by device id, so two desktops
        // sharing one would replace each other in the store rather than both
        // being trusted.
        val (first, firstCredential) = pairedDesktop(deviceId = "desktop-first")
        val (second, secondCredential) = pairedDesktop(deviceId = "desktop-second")

        connect(first).use { connection ->
            assertTrue(connection.handshake(credential = firstCredential) is SessionReady)
            waitFor("session active") { listener.activeSession != null }

            var secondGotSession = false
            try {
                connect(second).use { intruder ->
                    secondGotSession = intruder.handshake(credential = secondCredential) is SessionReady
                }
            } catch (_: IOException) {
                // Expected: the connection is closed before the handshake.
            }
            assertFalse("a second desktop must not get a session", secondGotSession)
            assertNotNull(listener.lastRefusal)
        }
    }

    @Test
    fun `stopping the listener closes the active session`() {
        val (desktop, credential) = pairedDesktop()

        connect(desktop).use { connection ->
            assertTrue(connection.handshake(credential = credential) is SessionReady)
            waitFor("session active") { listener.activeSession != null }

            listener.stop()
            waitFor("session closed") { host.closed.isNotEmpty() }
            assertNull(listener.activeSession)
            assertEquals(0, listener.boundPort)
        }
    }

    @Test
    fun `the listener refuses to bind to a wildcard address`() {
        // Not a style preference: binding 0.0.0.0 would put the control channel
        // on every interface this device has, including any the user never
        // meant to serve. Refused even when asked for explicitly.
        val other = ControlListener(trust, host, clock = { now })
        try {
            assertFalse(
                "must refuse an any-local bind",
                other.start(bindAddress = InetAddress.getByName("0.0.0.0"), port = 0)
            )
            assertFalse(
                "must refuse the IPv6 wildcard too",
                other.start(bindAddress = InetAddress.getByName("::"), port = 0)
            )
        } finally {
            other.stop()
        }
    }
}
