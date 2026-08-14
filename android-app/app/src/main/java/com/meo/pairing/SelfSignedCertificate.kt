package com.meo.pairing

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.TimeZone

/**
 * Builds a self-signed X.509 certificate, in DER, with no third-party
 * dependency.
 *
 * This exists because Android has no public certificate-generation API. It
 * bundles BouncyCastle, but only as a renamed internal package that
 * applications must not call, and adding a real BouncyCastle would put a
 * megabyte of general-purpose crypto into the APK to produce one certificate at
 * first launch.
 *
 * The certificate is deliberately unremarkable: it is never validated as a
 * certificate. Both sides pin the SHA-256 of the SubjectPublicKeyInfo instead
 * (plan §6.1 step 3), so the certificate is a container for a public key and
 * nothing here depends on its subject, its issuer, or its dates. That is also
 * why pinning the SPKI rather than the certificate is the right choice: the
 * certificate can be regenerated without invalidating every pairing.
 *
 * P-256 with SHA-256 is used because the resulting DER is small and every TLS
 * stack on both platforms supports it.
 */
object SelfSignedCertificate {

    private const val TAG_INTEGER = 0x02
    private const val TAG_BIT_STRING = 0x03
    private const val TAG_OCTET_STRING = 0x04
    private const val TAG_OID = 0x06
    private const val TAG_UTF8_STRING = 0x0C
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_SET = 0x31
    private const val TAG_UTC_TIME = 0x17
    private const val TAG_BOOLEAN = 0x01

    /** ecdsa-with-SHA256, 1.2.840.10045.4.3.2 */
    private val OID_ECDSA_SHA256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)

    /** id-at-commonName, 2.5.4.3 */
    private val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    /** id-ce-basicConstraints, 2.5.29.19 */
    private val OID_BASIC_CONSTRAINTS = byteArrayOf(0x55, 0x1D, 0x13)

    private val random = SecureRandom()

    /**
     * @param commonName appears in the certificate and nowhere else that
     *   matters. Kept short and non-identifying on purpose.
     * @param validityYears long, because expiry is enforced by the pairing
     *   record's own `expires_at`, not by the certificate.
     */
    fun create(
        keyPair: KeyPair,
        commonName: String = "Meo",
        validityYears: Int = 20
    ): X509Certificate {
        val tbs = buildTbsCertificate(keyPair.public, commonName, validityYears)

        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private as PrivateKey)
            update(tbs)
            sign()
        }

        val certificate = sequence(
            tbs,
            algorithmIdentifier(),
            bitString(signature)
        )

        return CertificateFactory.getInstance("X.509")
            .generateCertificate(certificate.inputStream()) as X509Certificate
    }

    private fun buildTbsCertificate(
        publicKey: PublicKey,
        commonName: String,
        validityYears: Int
    ): ByteArray {
        // X.509 v3. The tag is [0] EXPLICIT, hence the extra wrapper.
        val version = explicit(0, encode(TAG_INTEGER, byteArrayOf(2)))

        val serialBytes = ByteArray(16).also(random::nextBytes)
        // A negative serial is a standards violation that some parsers reject.
        val serial = encode(TAG_INTEGER, BigInteger(1, serialBytes).toByteArray())

        val name = sequence(
            encode(
                TAG_SET,
                sequence(
                    encode(TAG_OID, OID_COMMON_NAME),
                    encode(TAG_UTF8_STRING, commonName.toByteArray(Charsets.UTF_8))
                )
            )
        )

        val now = System.currentTimeMillis()
        val validity = sequence(
            // Backdated a day so a peer whose clock is slightly behind does not
            // see a certificate from the future.
            utcTime(Date(now - ONE_DAY_MS)),
            utcTime(Date(now + validityYears * 365L * ONE_DAY_MS))
        )

        // getEncoded() on a public key is already SubjectPublicKeyInfo DER,
        // which is exactly the field required here — and exactly the bytes the
        // SPKI pin is computed over.
        val subjectPublicKeyInfo = publicKey.encoded

        val basicConstraints = sequence(
            encode(TAG_OID, OID_BASIC_CONSTRAINTS),
            encode(TAG_BOOLEAN, byteArrayOf(0xFF.toByte())),
            encode(TAG_OCTET_STRING, sequence(encode(TAG_BOOLEAN, byteArrayOf(0xFF.toByte()))))
        )
        val extensions = explicit(3, sequence(basicConstraints))

        return sequence(
            version,
            serial,
            algorithmIdentifier(),
            name,
            validity,
            name, // self-signed: issuer and subject are the same
            subjectPublicKeyInfo,
            extensions
        )
    }

    private fun algorithmIdentifier(): ByteArray = sequence(encode(TAG_OID, OID_ECDSA_SHA256))

    // --- Minimal DER writer ------------------------------------------------

    private fun encode(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        writeLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    private fun sequence(vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        parts.forEach(body::write)
        return encode(TAG_SEQUENCE, body.toByteArray())
    }

    /** A context-specific constructed tag: `[n] EXPLICIT`. */
    private fun explicit(number: Int, content: ByteArray): ByteArray =
        encode(0xA0 or number, content)

    private fun bitString(content: ByteArray): ByteArray {
        // The leading zero is the count of unused bits in the final octet,
        // which is always zero for whole-byte content.
        val withPadding = ByteArray(content.size + 1)
        withPadding[0] = 0
        content.copyInto(withPadding, 1)
        return encode(TAG_BIT_STRING, withPadding)
    }

    private fun utcTime(date: Date): ByteArray {
        val format = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return encode(TAG_UTC_TIME, format.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 0x80) {
            out.write(length)
            return
        }
        // Long form: 0x80 | byteCount, then the length, big-endian, minimal.
        var remaining = length
        val bytes = ArrayList<Int>(4)
        while (remaining > 0) {
            bytes.add(0, remaining and 0xFF)
            remaining = remaining ushr 8
        }
        out.write(0x80 or bytes.size)
        bytes.forEach(out::write)
    }

    private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
}
