package com.meo.pairing

import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.Certificate

/**
 * The SHA-256 of a peer's SubjectPublicKeyInfo, lowercase hex.
 *
 * This is the identity both sides actually trust. Plan §6.1 pins the public key
 * rather than the certificate for a specific reason: a certificate expires and
 * can need reissuing, and pinning it would silently invalidate every pairing on
 * the day it was regenerated. The key outlives the certificate.
 *
 * The QR code carries this string for the desktop; the phone stores one per
 * paired desktop.
 */
object SpkiPin {

    fun of(publicKey: PublicKey): String = hex(
        MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
    )

    fun of(certificate: Certificate): String = of(certificate.publicKey)

    /**
     * Constant-time comparison, and case- and whitespace-insensitive because
     * one side of this comparison was typed or scanned.
     *
     * The timing property is close to irrelevant for a public value like a key
     * hash. It costs nothing, and the alternative is a codebase where some
     * comparisons of secret-adjacent material are constant-time and some are
     * not, which is the state in which the wrong one gets copied.
     */
    fun matches(expected: String?, actual: String?): Boolean {
        if (expected == null || actual == null) return false
        val a = expected.trim().lowercase().toByteArray(Charsets.US_ASCII)
        val b = actual.trim().lowercase().toByteArray(Charsets.US_ASCII)
        if (a.size != b.size) return false
        var difference = 0
        for (index in a.indices) {
            difference = difference or (a[index].toInt() xor b[index].toInt())
        }
        return difference == 0
    }

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    fun parseHex(text: String): ByteArray? {
        val clean = text.trim().lowercase()
        if (clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (index in out.indices) {
            val high = Character.digit(clean[index * 2], 16)
            val low = Character.digit(clean[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
