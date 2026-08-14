package com.meo.pairing

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * This device's long-lived TLS identity: one EC key pair and the self-signed
 * certificate that carries its public half.
 *
 * Generated once, on first launch, and then persisted. Regenerating it is not a
 * routine operation — every desktop that has paired with this phone has pinned
 * [pin], and a new key means every one of them must pair again.
 */
class TlsIdentity(
    val keyPair: KeyPair,
    val certificate: X509Certificate
) {
    /** What peers pin. See [SpkiPin]. */
    val pin: String by lazy { SpkiPin.of(keyPair.public) }

    fun keyManagers(): Array<KeyManager> {
        // An in-memory PKCS#12 exists only to satisfy KeyManagerFactory, which
        // has no API for "here is a key and a certificate". The password
        // protects nothing — the store never reaches disk in this form — so it
        // is random rather than a constant that would look meaningful.
        val password = CharArray(32).also { chars ->
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            bytes.forEachIndexed { index, byte -> chars[index] = ((byte.toInt() and 0x3F) + 32).toChar() }
        }
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry(ALIAS, keyPair.private, password, arrayOf<java.security.cert.Certificate>(certificate))
        }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, password)
        return factory.keyManagers
    }

    /**
     * A TLS context that presents this identity and trusts exactly the peers
     * [trustManager] accepts.
     *
     * TLS 1.2 is the floor. It is what Android 10 — the camera floor for this
     * feature — guarantees, and both ends of this connection are ours, so
     * nothing here needs to interoperate with anything older.
     */
    fun sslContext(trustManager: TrustManager): SSLContext =
        SSLContext.getInstance("TLSv1.2").apply {
            init(keyManagers(), arrayOf(trustManager), SecureRandom())
        }

    /** Serialises key and certificate together for [SecureStore]. */
    fun toPkcs12(password: CharArray): ByteArray {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry(ALIAS, keyPair.private, password, arrayOf<java.security.cert.Certificate>(certificate))
        }
        return java.io.ByteArrayOutputStream().also { keyStore.store(it, password) }.toByteArray()
    }

    companion object {
        private const val ALIAS = "meo-identity"

        fun generate(): TlsIdentity {
            val keyPair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            }.generateKeyPair()
            return TlsIdentity(keyPair, SelfSignedCertificate.create(keyPair))
        }

        fun fromPkcs12(bytes: ByteArray, password: CharArray): TlsIdentity? = try {
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(bytes.inputStream(), password)
            }
            val privateKey = keyStore.getKey(ALIAS, password) as? java.security.PrivateKey
            val certificate = keyStore.getCertificate(ALIAS) as? X509Certificate
            if (privateKey == null || certificate == null) {
                null
            } else {
                TlsIdentity(KeyPair(certificate.publicKey, privateKey), certificate)
            }
        } catch (_: Exception) {
            // A corrupted or unreadable identity is recoverable by generating a
            // new one; it costs the user a re-pair, which is better than an app
            // that cannot start.
            null
        }
    }
}
