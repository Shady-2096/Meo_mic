package com.meo.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Somewhere to keep bytes that must not be readable by other apps or by anyone
 * with the device's storage but not its keys.
 *
 * An interface because the policy that uses it — sliding expiry, revocation,
 * pin lookup — is worth testing thoroughly on the JVM, and the Android
 * implementation cannot run there.
 */
interface SecureStore {
    fun read(key: String): ByteArray?
    fun write(key: String, value: ByteArray)
    fun clear(key: String)
}

class InMemorySecureStore : SecureStore {
    private val values = HashMap<String, ByteArray>()

    @Synchronized
    override fun read(key: String): ByteArray? = values[key]?.copyOf()

    @Synchronized
    override fun write(key: String, value: ByteArray) {
        values[key] = value.copyOf()
    }

    @Synchronized
    override fun clear(key: String) {
        values.remove(key)
    }
}

/**
 * Keystore-backed encrypted storage (plan §6.2).
 *
 * The values — this device's TLS private key, and the per-pairing credentials —
 * are encrypted with AES-GCM under a key that lives in the Android Keystore and
 * never enters this process. On devices with a secure element or TEE the key is
 * hardware-backed; where it is not, it is still outside the app sandbox.
 *
 * Deliberately **not** requiring user authentication to unlock. A camera
 * session has to survive the screen turning off (plan §7.2), and a key that
 * needs the user present would end the stream at the moment the user put the
 * phone down, which is precisely when it is being used as a webcam.
 *
 * Each write gets a fresh random IV, stored alongside the ciphertext. Reusing an
 * IV with GCM is catastrophic rather than merely weak, so the IV is never
 * derived or fixed.
 */
class KeystoreSecureStore(
    context: Context,
    private val preferencesName: String = "meo_secure_store"
) : SecureStore {

    private val appContext = context.applicationContext

    private val preferences by lazy {
        appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    override fun read(key: String): ByteArray? {
        val stored = preferences.getString(key, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size <= IV_LENGTH) return null
            val iv = raw.copyOfRange(0, IV_LENGTH)
            val ciphertext = raw.copyOfRange(IV_LENGTH, raw.size)
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
                doFinal(ciphertext)
            }
        } catch (_: Exception) {
            // Unreadable means the Keystore key is gone — a device restore, or
            // the user changing lock settings on some OEMs. Treating it as
            // absent costs a re-pair, which is the only recovery available and
            // is better than refusing to start.
            null
        }
    }

    override fun write(key: String, value: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val ciphertext = cipher.doFinal(value)
        val combined = ByteArray(cipher.iv.size + ciphertext.size)
        cipher.iv.copyInto(combined)
        ciphertext.copyInto(combined, cipher.iv.size)
        preferences.edit { putString(key, Base64.encodeToString(combined, Base64.NO_WRAP)) }
    }

    override fun clear(key: String) {
        preferences.edit { remove(key) }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Enrolling a new fingerprint must not destroy the key. This store
            // holds the pairing credentials, and losing them would silently
            // un-pair every computer the moment the user changed a biometric.
            .setInvalidatedByBiometricEnrollment(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "meo-secure-store-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_LENGTH = 12
    }
}
