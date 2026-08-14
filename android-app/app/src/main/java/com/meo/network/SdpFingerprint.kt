package com.meo.network

/**
 * The DTLS-SRTP fingerprint carried in an SDP.
 *
 * ## What this does and does not do
 *
 * Plan §6.3 requires that the fingerprint in the SDP be verified against the
 * DTLS handshake of the actual media connection, and is right that without it
 * "encrypted transport buys nothing" — anything that reaches the signalling path
 * could otherwise substitute its own media endpoint.
 *
 * The cryptographic half of that check is performed inside libwebrtc: it
 * refuses a DTLS handshake whose peer certificate does not hash to the
 * fingerprint from the remote description. Reimplementing it here would mean
 * reimplementing the DTLS handshake, which is exactly the sort of thing ADR
 * 0001 chose WebRTC to avoid.
 *
 * What is left for us is the part libwebrtc cannot do, because it concerns
 * where the SDP came from and what shape it is in:
 *
 * - The SDP must arrive over the authenticated, pinned control channel. That is
 *   structural — [ControlSession] is the only thing that hands an SDP to the
 *   peer connection — rather than checked here.
 * - The SDP must actually **contain** a fingerprint. An SDP with none would
 *   leave libwebrtc with nothing to check against.
 * - Every fingerprint in it must **agree**. An SDP with a session-level
 *   fingerprint and a different media-level one is a bundling attack shape: it
 *   invites the two ends to validate against different values.
 * - The hash must be one worth trusting. SHA-1 collisions are practical, and a
 *   peer offering only a SHA-1 fingerprint is either very old or trying
 *   something.
 *
 * Pure string handling, so all of that is testable without a media stack.
 */
object SdpFingerprint {

    sealed class Result {
        /** [value] is the agreed fingerprint, normalised to lowercase. */
        data class Ok(val algorithm: String, val value: String) : Result()
        data class Rejected(val reason: String) : Result()
    }

    /** Hashes strong enough to bind a media connection to an SDP. */
    private val ACCEPTED_ALGORITHMS = mapOf(
        "sha-256" to 32,
        "sha-384" to 48,
        "sha-512" to 64
    )

    fun extract(sdp: String): Result {
        val lines = sdp.split('\n').map { it.trim() }.filter { it.startsWith("a=fingerprint:") }
        if (lines.isEmpty()) {
            return Result.Rejected("SDP carries no DTLS fingerprint")
        }

        val parsed = lines.map { line ->
            val body = line.removePrefix("a=fingerprint:").trim()
            val separator = body.indexOf(' ')
            if (separator <= 0) return Result.Rejected("malformed fingerprint line")

            val algorithm = body.substring(0, separator).lowercase()
            val value = body.substring(separator + 1).trim().lowercase()

            val expectedBytes = ACCEPTED_ALGORITHMS[algorithm]
                ?: return Result.Rejected("unacceptable fingerprint algorithm: $algorithm")

            val octets = value.split(':')
            if (octets.size != expectedBytes) {
                return Result.Rejected("$algorithm fingerprint has ${octets.size} octets, expected $expectedBytes")
            }
            if (octets.any { octet -> octet.length != 2 || octet.any { !isHex(it) } }) {
                return Result.Rejected("fingerprint is not colon-separated hex octets")
            }
            algorithm to value
        }

        val distinct = parsed.distinct()
        if (distinct.size != 1) {
            // Two different fingerprints in one SDP means the two ends could
            // validate against different values.
            return Result.Rejected("SDP carries ${distinct.size} conflicting fingerprints")
        }

        val (algorithm, value) = distinct.first()
        return Result.Ok(algorithm, value)
    }

    private fun isHex(character: Char): Boolean =
        character in '0'..'9' || character in 'a'..'f'
}
