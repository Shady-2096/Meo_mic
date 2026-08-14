package com.meo.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec

/**
 * The certificate builder writes DER by hand, so it gets checked by the
 * platform's own parser rather than by inspection.
 */
class SelfSignedCertificateTest {

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test
    fun `the certificate parses, self-verifies, and is currently valid`() {
        val pair = keyPair()
        val certificate = SelfSignedCertificate.create(pair)

        // Parsing already happened inside create(); this asserts the signature
        // is real rather than merely well-formed.
        certificate.verify(pair.public)
        certificate.checkValidity()

        assertEquals(3, certificate.version)
        assertEquals(certificate.issuerX500Principal, certificate.subjectX500Principal)
        assertEquals("CN=Meo", certificate.subjectX500Principal.name)
        assertEquals(pair.public, certificate.publicKey)
    }

    @Test
    fun `the serial number is positive`() {
        // A negative serial is a standards violation that some parsers reject
        // outright, and it is the easy mistake when serials come from raw
        // random bytes.
        repeat(20) {
            val certificate = SelfSignedCertificate.create(keyPair())
            assertTrue(
                "serial must be positive, got ${certificate.serialNumber}",
                certificate.serialNumber.signum() > 0
            )
        }
    }

    @Test
    fun `the encoded public key is the SubjectPublicKeyInfo the pin is computed over`() {
        // If these ever diverge, two peers would pin different bytes for the
        // same key and every reconnect would fail with a mismatch.
        val pair = keyPair()
        val certificate = SelfSignedCertificate.create(pair)

        val fromCertificate = MessageDigest.getInstance("SHA-256")
            .digest(certificate.publicKey.encoded)
        val fromKey = MessageDigest.getInstance("SHA-256").digest(pair.public.encoded)

        assertTrue(fromCertificate.contentEquals(fromKey))
        assertEquals(SpkiPin.of(pair.public), SpkiPin.of(certificate))
    }

    @Test
    fun `a long common name still encodes correctly`() {
        // Exercises DER long-form length encoding, which only triggers past 127
        // bytes and would otherwise never be hit by the default "Meo".
        val longName = "M".repeat(200)
        val certificate = SelfSignedCertificate.create(keyPair(), commonName = longName)
        assertEquals("CN=$longName", certificate.subjectX500Principal.name)
    }
}
