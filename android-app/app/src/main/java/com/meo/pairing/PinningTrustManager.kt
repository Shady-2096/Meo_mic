package com.meo.pairing

import android.annotation.SuppressLint
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Accepts a peer only when its public key is one this device already expects.
 *
 * There is no certificate authority anywhere in Meo and there should not be:
 * both endpoints are self-signed by design, so ordinary chain validation would
 * either reject everything or, if disabled, accept everything. Neither is
 * useful. The question this trust manager answers instead is the only one that
 * matters on a LAN — "is this the same key I was told about?"
 *
 * Two modes, matching the two situations in plan §6:
 *
 * - **Pairing** ([expecting]): exactly one pin is acceptable, the one scanned
 *   from the QR code moments ago.
 * - **Reconnect** ([acceptingAnyOf]): any pin belonging to a stored pairing is
 *   acceptable, and which desktop it turned out to be is reported to the caller
 *   so the session layer can look up the right credential.
 *
 * A peer that presents no certificate at all is rejected. That case is reachable
 * — TLS client certificates are optional in the protocol — and it is why the
 * listener requests them explicitly.
 */
@SuppressLint("CustomX509TrustManager")
class PinningTrustManager private constructor(
    private val acceptable: () -> Set<String>
) : X509TrustManager {
    // Lint objects to custom trust managers because the usual reason for
    // writing one is to switch validation off. This one does the opposite: it
    // is strictly *narrower* than the platform default, accepting a fixed set
    // of public keys and no certificate authority at all. The platform default
    // would be useless here — both endpoints are self-signed by design — and
    // the check that replaces it is in `check` below, where it can be read.

    // Deliberately holds no per-connection state. One trust manager is shared
    // by every connection the listener accepts, so a field recording "the pin
    // we just saw" would be a race between two desktops connecting at once.
    // Callers read the peer's certificate from that connection's own SSLSession
    // instead.

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        check(chain)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        check(chain)
    }

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("peer presented no certificate")

        val pin = SpkiPin.of(leaf)
        val allowed = acceptable()
        if (allowed.none { SpkiPin.matches(it, pin) }) {
            // Deliberately does not name the expected pins. This message can
            // reach a log, and a log can reach a bug report.
            throw CertificateException("peer public key is not trusted for this device")
        }
    }

    /**
     * Empty by design: an empty issuer list means "any client certificate is
     * welcome to try", which is what we want, because acceptance is decided by
     * [check] rather than by which CA signed it.
     */
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        /** First pairing: exactly one key is acceptable. */
        fun expecting(pin: String): PinningTrustManager =
            PinningTrustManager { setOf(pin) }

        /**
         * Reconnect: any stored pairing is acceptable. Evaluated on each
         * handshake rather than captured, so a pairing revoked while the
         * listener is running takes effect on the next connection.
         */
        fun acceptingAnyOf(pins: () -> Set<String>): PinningTrustManager =
            PinningTrustManager(pins)
    }
}
