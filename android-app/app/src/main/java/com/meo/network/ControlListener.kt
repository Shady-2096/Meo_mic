package com.meo.network

import com.meo.pairing.AttemptLimiter
import com.meo.pairing.DeviceTrust
import com.meo.pairing.PairingInvite
import com.meo.pairing.PinningTrustManager
import com.meo.pairing.SpkiPin
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.SocketException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * The phone's TLS control listener.
 *
 * The phone listens and the desktop dials, which is backwards from the obvious
 * arrangement and is the whole point. Plan §5.1: a desktop that listens trips
 * the Windows Defender Firewall prompt on first run, and that prompt needs
 * administrator approval. Dismissed or blocked, Meo silently never works. A
 * desktop that *dials* makes an ordinary outbound connection that Windows
 * permits by default — no prompt, no elevation, no support burden.
 *
 * One session at a time. A phone has one camera, and a second desktop
 * connecting is told so rather than being left to wonder.
 */
class ControlListener(
    private val trust: DeviceTrust,
    private val host: ControlSessionHost,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limiter: AttemptLimiter = AttemptLimiter()
) {
    /** Reported so the UI and the mDNS advertiser can show where we are. */
    @Volatile
    var boundAddress: InetAddress? = null
        private set

    @Volatile
    var boundPort: Int = 0
        private set

    @Volatile
    var activeSession: ControlSession? = null
        private set

    private val running = AtomicBoolean(false)
    private var serverSocket: SSLServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var invite: PairingInvite? = null

    /**
     * Arms first pairing. The scanned invite is acceptable exactly once and
     * only until it expires; both are checked again at handshake time.
     */
    fun beginPairing(scanned: PairingInvite) {
        invite = scanned
    }

    fun cancelPairing() {
        invite = null
    }

    val pairingInProgress: PairingInvite?
        get() = invite?.takeIf { !it.isExpired(clock()) }

    /**
     * @param bindAddress overridable so tests can use loopback. Production
     *   passes null and gets a private LAN address, never a wildcard.
     * @param port 0 asks the OS for a free one, which is then advertised.
     */
    fun start(bindAddress: InetAddress? = null, port: Int = 0): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val address = bindAddress ?: LocalNetwork.preferredBindAddress()
        if (address == null) {
            // No private address means no LAN to serve. Binding to a wildcard
            // "just in case" is exactly what plan §6.4 forbids.
            running.set(false)
            return false
        }
        if (address.isAnyLocalAddress) {
            // Refused even when asked for explicitly. A wildcard bind puts the
            // control channel on every interface this device has, including any
            // the user never meant to serve, and the caller that wants one is
            // more likely to be a mistake than an intention.
            running.set(false)
            return false
        }

        return try {
            val trustManager = PinningTrustManager.acceptingAnyOf {
                val stored = trust.pairings.trustedPins(clock())
                // A pending invite's key is accepted even once the five minutes
                // are up, and the *protocol* layer then refuses it with an
                // explicit "the code expired". The alternative — dropping it at
                // the TLS layer — is indistinguishable from a broken network to
                // the person holding the phone, and this is the first thing
                // they ever do with the app. An expired *pairing* gets no such
                // grace: it is simply no longer trusted.
                val pending = invite?.desktopSpkiPin
                if (pending == null) stored else stored + pending
            }

            val context = trust.identity.sslContext(trustManager)
            val socket = (context.serverSocketFactory.createServerSocket(port, BACKLOG, address)
                as SSLServerSocket).apply {
                // The desktop must present a certificate: its public key is the
                // identity being pinned, and without client auth there is
                // nothing to pin.
                needClientAuth = true
                enabledProtocols = supportedProtocols.filter { it in ALLOWED_PROTOCOLS }.toTypedArray()
            }

            serverSocket = socket
            boundAddress = address
            boundPort = socket.localPort

            acceptThread = Thread({ acceptLoop(socket) }, "meo-control-listener").apply {
                isDaemon = true
                start()
            }
            true
        } catch (_: IOException) {
            running.set(false)
            serverSocket = null
            false
        }
    }

    fun stop() {
        running.set(false)
        activeSession?.close()
        activeSession = null
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // Closing the server socket is what unblocks accept(); a failure
            // here means it was already closed.
        }
        serverSocket = null
        boundAddress = null
        boundPort = 0
        acceptThread?.join(SHUTDOWN_JOIN_MS)
        acceptThread = null
    }

    private fun acceptLoop(socket: SSLServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept() as SSLSocket
            } catch (_: SocketException) {
                return // stop() closed the socket
            } catch (_: IOException) {
                if (!running.get()) return
                continue
            }
            handleClient(client)
        }
    }

    private fun handleClient(client: SSLSocket) {
        val peer = client.inetAddress
        val limiterKey = peer.hostAddress ?: "unknown"

        limiter.checkAllowed(limiterKey, clock())?.let { waitMillis ->
            // Refused before the handshake, so a peer hammering the listener
            // costs a TCP accept rather than an asymmetric key operation.
            closeQuietly(client, "rate limited for ${waitMillis}ms")
            return
        }

        if (activeSession != null) {
            closeQuietly(client, "another computer is already connected")
            return
        }

        val session = try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            client.startHandshake()

            val peerPin = client.session.peerCertificates
                .filterIsInstance<X509Certificate>()
                .firstOrNull()
                ?.let { SpkiPin.of(it) }

            if (peerPin == null) {
                limiter.recordFailure(limiterKey, clock())
                closeQuietly(client, "peer presented no usable certificate")
                return
            }

            // Reads block indefinitely once the handshake is done: an idle
            // paired desktop is normal, and a timeout here would drop healthy
            // sessions between camera controls.
            client.soTimeout = 0

            ControlSession(
                connection = TlsSessionConnection(client, peerPin),
                host = host,
                pairings = trust.pairings,
                ownPin = trust.pin,
                ownDeviceId = trust.deviceId,
                ownDisplayName = trust.displayName,
                // The raw invite, not the expiry-filtered one: the session
                // reports "expired" rather than "no pairing in progress".
                pendingInvite = { invite },
                onPairingConsumed = { invite = null },
                clock = clock
            )
        } catch (_: Exception) {
            // A failed handshake is the signature of both a wrong pin and a
            // scan of someone else's network, so it counts against the limiter.
            limiter.recordFailure(limiterKey, clock())
            closeQuietly(client, "handshake failed")
            return
        }

        activeSession = session
        Thread({
            try {
                session.run()
            } finally {
                if (activeSession === session) activeSession = null
            }
        }, "meo-control-session").apply {
            isDaemon = true
            start()
        }
        limiter.recordSuccess(limiterKey)
    }

    private fun closeQuietly(client: SSLSocket, reason: String) {
        try {
            // Best effort only: the peer may not have completed a handshake, in
            // which case there is no way to say anything and the close is the
            // whole message.
            client.close()
        } catch (_: IOException) {
            // Nothing useful to do; the connection is going away regardless.
        }
        lastRefusal = reason
    }

    /** Exposed for diagnostics and tests; the UI shows session state instead. */
    @Volatile
    var lastRefusal: String? = null
        private set

    private companion object {
        const val BACKLOG = 4
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val SHUTDOWN_JOIN_MS = 2_000L
        val ALLOWED_PROTOCOLS = setOf("TLSv1.2", "TLSv1.3")
    }
}

private class TlsSessionConnection(
    private val socket: SSLSocket,
    override val peerPin: String
) : SessionConnection {
    override val input = BufferedInputStream(socket.inputStream)
    override val output = BufferedOutputStream(socket.outputStream)
    override val peerAddress: InetAddress = socket.inetAddress
    override val writeLock = Any()
    private val startedAt = System.nanoTime()

    override fun monotonicMillis(): Long = (System.nanoTime() - startedAt) / 1_000_000

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
            // Already closed, or the peer vanished. Either way we are done.
        }
    }
}
