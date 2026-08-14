package com.meo.pairing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The properties the handshake actually depends on.
 *
 * Each test here corresponds to an attack that succeeds if one element is
 * dropped from the proof construction. They are written as "this must fail"
 * rather than "this must succeed" because a proof scheme that accepts valid
 * input is easy; the value is entirely in what it rejects.
 */
class AuthProofsTest {

    private val phonePin = "a".repeat(64)
    private val desktopPin = "b".repeat(64)
    private val secret = AuthProofs.newSecret()
    private val nonce = AuthProofs.newNonce()

    private fun phoneProof(
        secretHex: String = secret,
        nonceHex: String = nonce,
        peerPin: String = desktopPin,
        ownPin: String = phonePin
    ) = AuthProofs.compute(AuthProofs.LABEL_AUTH_PHONE, secretHex, nonceHex, peerPin, ownPin)!!

    private fun verifyAsDesktop(
        proof: String,
        secretHex: String = secret,
        nonceHex: String = nonce,
        proverPin: String = phonePin,
        verifierPin: String = desktopPin
    ) = AuthProofs.verify(
        expectedLabel = AuthProofs.LABEL_AUTH_PHONE,
        presentedProof = proof,
        secretHex = secretHex,
        nonceHex = nonceHex,
        proverPin = proverPin,
        verifierPin = verifierPin
    )

    @Test
    fun `a proof made with the right inputs verifies`() {
        assertTrue(verifyAsDesktop(phoneProof()))
    }

    @Test
    fun `a proof does not verify against a different secret`() {
        assertFalse(verifyAsDesktop(phoneProof(), secretHex = AuthProofs.newSecret()))
    }

    @Test
    fun `a captured proof does not verify against a fresh nonce`() {
        // Replay. The verifier picks a new nonce on every connection, so a
        // recording of yesterday's handshake proves nothing today.
        val captured = phoneProof()
        assertFalse(verifyAsDesktop(captured, nonceHex = AuthProofs.newNonce()))
    }

    @Test
    fun `a proof does not verify on a connection between different keys`() {
        // Channel binding, plan 6.2 step 5. An attacker who relays a genuine
        // proof onto their own TLS connection presents a real proof that is
        // evidence about the wrong connection. Both pins are inside the MAC, so
        // it does not verify there.
        val captured = phoneProof()
        assertFalse(verifyAsDesktop(captured, verifierPin = "c".repeat(64)))
        assertFalse(verifyAsDesktop(captured, proverPin = "d".repeat(64)))
    }

    @Test
    fun `a proof cannot be reflected back at the party that issued the challenge`() {
        // Without direction labels both sides compute the same value over the
        // same inputs, and an attacker can answer a challenge by echoing it.
        val fromPhone = phoneProof()
        val asDesktopProof = AuthProofs.verify(
            expectedLabel = AuthProofs.LABEL_AUTH_DESKTOP,
            presentedProof = fromPhone,
            secretHex = secret,
            nonceHex = nonce,
            proverPin = phonePin,
            verifierPin = desktopPin
        )
        assertFalse(asDesktopProof)
    }

    @Test
    fun `pairing and reconnect proofs are not interchangeable`() {
        // Otherwise a one-time pairing token proof could stand in for a durable
        // credential proof.
        val pairingProof = AuthProofs.compute(
            AuthProofs.LABEL_PAIR_PHONE, secret, nonce, desktopPin, phonePin
        )!!
        assertFalse(verifyAsDesktop(pairingProof))
        assertNotEquals(pairingProof, phoneProof())
    }

    @Test
    fun `pin comparison ignores case and surrounding whitespace`() {
        // One side of this comparison was scanned from a QR code or typed.
        assertTrue(SpkiPin.matches("ABCD", "abcd"))
        assertTrue(SpkiPin.matches(" abcd ", "abcd"))
        assertFalse(SpkiPin.matches("abcd", "abce"))
        assertFalse(SpkiPin.matches("abcd", "abcd0"))
        assertFalse(SpkiPin.matches(null, "abcd"))
    }

    @Test
    fun `malformed hex inputs are refused rather than treated as empty`() {
        // The dangerous version of this bug is a parser that returns an empty
        // array for garbage, making every malformed secret hash identically.
        assertNull(AuthProofs.compute(AuthProofs.LABEL_AUTH_PHONE, "zz", nonce, desktopPin, phonePin))
        assertNull(AuthProofs.compute(AuthProofs.LABEL_AUTH_PHONE, secret, "abc", desktopPin, phonePin))
        assertNull(AuthProofs.compute(AuthProofs.LABEL_AUTH_PHONE, "", nonce, desktopPin, phonePin))
        assertNull(SpkiPin.parseHex("xyz"))
        assertNull(SpkiPin.parseHex("abc"))
    }

    @Test
    fun `nonces and secrets are 256 bits and do not repeat`() {
        val nonces = (1..200).map { AuthProofs.newNonce() }
        assertTrue(nonces.all { SpkiPin.parseHex(it)?.size == 32 })
        assertTrue(nonces.toSet().size == nonces.size)
    }
}
