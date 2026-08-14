package com.meo.pairing

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The nonce-bound, channel-bound HMAC proofs both sides exchange
 * (plan §6.1 step 7, §6.2 steps 4–5).
 *
 * ## The construction
 *
 * ```text
 * proof = HMAC-SHA256(secret, label ‖ 0x00 ‖ nonce ‖ 0x00 ‖ peerPin ‖ 0x00 ‖ ownPin)
 * ```
 *
 * Every element is there to stop a specific attack, and dropping any one of
 * them leaves a working-looking handshake that proves less than it appears to:
 *
 * - **`secret`** is the one-time pairing token on first contact and the durable
 *   per-pairing credential afterwards. Possession of it is the thing being
 *   proven.
 * - **`nonce`** is chosen by the *verifier*, not the prover, so a recording of
 *   a previous handshake is useless: the challenge will not repeat.
 * - **`label`** differs by direction. Without it the two sides compute the same
 *   value over the same inputs, and an attacker can answer a challenge simply
 *   by reflecting it back at whoever issued it.
 * - **`peerPin` and `ownPin`** are the channel binding plan §6.2 step 5 calls
 *   for. They tie the proof to one specific TLS connection between two specific
 *   keys. Without them, an attacker who can reach the signalling path can relay
 *   a valid proof onto a connection of their own — the proof is genuine, but it
 *   is not evidence about *this* connection, and encrypted transport buys
 *   nothing.
 *
 * The separators are NUL bytes so that the concatenation is unambiguous. Hex
 * pins cannot contain NUL, so no combination of inputs can be made to encode as
 * another.
 */
object AuthProofs {

    /** Phone proving the one-time pairing token during first pairing. */
    const val LABEL_PAIR_PHONE = "meo-pair-phone-v1"

    /** Desktop proving the same token back, so pairing is mutual. */
    const val LABEL_PAIR_DESKTOP = "meo-pair-desktop-v1"

    /** Phone proving the stored credential on reconnect. */
    const val LABEL_AUTH_PHONE = "meo-auth-phone-v1"

    /** Desktop proving the stored credential on reconnect. */
    const val LABEL_AUTH_DESKTOP = "meo-auth-desktop-v1"

    private val random = SecureRandom()

    /**
     * @param secretHex the pairing token or the per-pairing credential, hex.
     * @param nonceHex the *verifier's* nonce, hex.
     * @param peerPin the SPKI pin of the party being talked to.
     * @param ownPin this device's own SPKI pin.
     */
    fun compute(
        label: String,
        secretHex: String,
        nonceHex: String,
        peerPin: String,
        ownPin: String
    ): String? {
        val secret = SpkiPin.parseHex(secretHex) ?: return null
        val nonce = SpkiPin.parseHex(nonceHex) ?: return null
        if (secret.isEmpty() || nonce.isEmpty()) return null

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(label.toByteArray(Charsets.US_ASCII))
        mac.update(0)
        mac.update(nonce)
        mac.update(0)
        mac.update(peerPin.lowercase().toByteArray(Charsets.US_ASCII))
        mac.update(0)
        mac.update(ownPin.lowercase().toByteArray(Charsets.US_ASCII))
        return SpkiPin.hex(mac.doFinal())
    }

    /**
     * Verifies a proof a peer sent. Note the pin arguments are swapped relative
     * to [compute]: the prover's "peer" is us, and our "peer" is the prover.
     * Getting this backwards produces a handshake that never succeeds, which is
     * the safe direction for the mistake to fail in.
     */
    fun verify(
        expectedLabel: String,
        presentedProof: String,
        secretHex: String,
        nonceHex: String,
        proverPin: String,
        verifierPin: String
    ): Boolean {
        val expected = compute(
            label = expectedLabel,
            secretHex = secretHex,
            nonceHex = nonceHex,
            peerPin = verifierPin,
            ownPin = proverPin
        ) ?: return false
        return SpkiPin.matches(expected, presentedProof)
    }

    /** A fresh 256-bit nonce, hex. */
    fun newNonce(): String = SpkiPin.hex(ByteArray(32).also(random::nextBytes))

    /** A fresh 256-bit secret, hex. Used for credentials and tokens alike. */
    fun newSecret(): String = SpkiPin.hex(ByteArray(32).also(random::nextBytes))
}

/**
 * Rate limiting with backoff for authentication attempts (plan §6.4).
 *
 * The secrets here are 256-bit, so this is not really about making guessing
 * infeasible — it already is. It is about the cheaper attacks: a peer that can
 * open connections as fast as the phone can perform handshakes can keep the
 * camera service busy and the radio awake until the battery is flat. Failures
 * are what count; a legitimate desktop reconnecting repeatedly is not throttled.
 */
class AttemptLimiter(
    private val maxFailuresBeforeBackoff: Int = 5,
    private val baseBackoffMillis: Long = 2_000,
    private val maxBackoffMillis: Long = 5 * 60_000,
    private val failureWindowMillis: Long = 10 * 60_000
) {
    private data class State(var failures: Int, var lastFailureAt: Long, var blockedUntil: Long)

    private val states = HashMap<String, State>()

    /** Null when allowed, or the millis remaining before another attempt. */
    @Synchronized
    fun checkAllowed(key: String, nowMillis: Long): Long? {
        val state = states[key] ?: return null
        if (nowMillis - state.lastFailureAt > failureWindowMillis) {
            states.remove(key)
            return null
        }
        return if (nowMillis < state.blockedUntil) state.blockedUntil - nowMillis else null
    }

    @Synchronized
    fun recordFailure(key: String, nowMillis: Long) {
        val state = states.getOrPut(key) { State(0, nowMillis, 0) }
        if (nowMillis - state.lastFailureAt > failureWindowMillis) {
            state.failures = 0
        }
        state.failures += 1
        state.lastFailureAt = nowMillis
        if (state.failures >= maxFailuresBeforeBackoff) {
            val overage = state.failures - maxFailuresBeforeBackoff
            // Doubling, capped. Shifting past 62 would overflow, and the cap
            // makes anything near that pointless anyway.
            val shift = overage.coerceAtMost(20)
            val backoff = (baseBackoffMillis shl shift).coerceAtMost(maxBackoffMillis)
            state.blockedUntil = nowMillis + backoff
        }
    }

    @Synchronized
    fun recordSuccess(key: String) {
        states.remove(key)
    }
}
