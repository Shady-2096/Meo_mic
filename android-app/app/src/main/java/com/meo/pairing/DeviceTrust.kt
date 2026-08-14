package com.meo.pairing

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Pairings persisted through a [SecureStore].
 *
 * The whole set is rewritten on every change. There are at most a handful of
 * paired computers, so an incremental format would add failure modes to save
 * microseconds.
 */
class PersistentPairingStore(
    private val secureStore: SecureStore,
    private val key: String = "pairings"
) : PairingStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Pairing.serializer())

    @Synchronized
    override fun all(): List<Pairing> {
        val bytes = secureStore.read(key) ?: return emptyList()
        return try {
            json.decodeFromString(serializer, String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            // Corrupt storage costs a re-pair. Failing closed is right here:
            // the alternative is trusting a record we cannot parse.
            emptyList()
        }
    }

    @Synchronized
    override fun find(desktopDeviceId: String): Pairing? =
        all().firstOrNull { it.desktopDeviceId == desktopDeviceId }

    @Synchronized
    override fun save(pairing: Pairing) {
        val updated = all().filterNot { it.desktopDeviceId == pairing.desktopDeviceId } + pairing
        persist(updated)
    }

    @Synchronized
    override fun remove(desktopDeviceId: String) {
        persist(all().filterNot { it.desktopDeviceId == desktopDeviceId })
    }

    private fun persist(pairings: List<Pairing>) {
        secureStore.write(key, json.encodeToString(serializer, pairings).toByteArray(Charsets.UTF_8))
    }
}

/**
 * This device's identity and the set of computers it trusts.
 *
 * Everything durable about trust lives behind here: the TLS key pair, the
 * device id peers use to address this phone, and the pairing records. It is
 * created once and shared, because generating a second identity would silently
 * invalidate every existing pairing.
 */
class DeviceTrust(
    private val secureStore: SecureStore,
    val displayName: String
) {
    val pairings: PairingStore = PersistentPairingStore(secureStore)

    /**
     * Stable, random, and not derived from anything about the hardware.
     *
     * Deliberately not the Android ID or any hardware identifier: this string is
     * broadcast in the clear over mDNS, and a value that follows the user across
     * apps or survives a reinstall would make the phone trackable by anyone on
     * the network.
     */
    val deviceId: String by lazy {
        val existing = secureStore.read(KEY_DEVICE_ID)?.toString(Charsets.UTF_8)
        if (!existing.isNullOrBlank()) {
            existing
        } else {
            val generated = "phone-" + AuthProofs.newSecret().take(16)
            secureStore.write(KEY_DEVICE_ID, generated.toByteArray(Charsets.UTF_8))
            generated
        }
    }

    val identity: TlsIdentity by lazy { loadOrCreateIdentity() }

    /** The pin a desktop stores for this phone. */
    val pin: String get() = identity.pin

    private fun loadOrCreateIdentity(): TlsIdentity {
        val password = identityPassword()
        secureStore.read(KEY_IDENTITY)
            ?.let { TlsIdentity.fromPkcs12(it, password) }
            ?.let { return it }

        val generated = TlsIdentity.generate()
        secureStore.write(KEY_IDENTITY, generated.toPkcs12(password))
        return generated
    }

    /**
     * The PKCS#12 wrapper password.
     *
     * It protects nothing on its own — it sits in the same encrypted store as
     * the bytes it wraps — and it exists only because `KeyStore.store` requires
     * one. The actual protection is the Keystore-held AES key in
     * [KeystoreSecureStore]. Written down here so nobody later mistakes this for
     * a secret worth strengthening and leaves the real one unexamined.
     */
    private fun identityPassword(): CharArray {
        val existing = secureStore.read(KEY_IDENTITY_PASSWORD)
        if (existing != null) return String(existing, Charsets.UTF_8).toCharArray()
        val generated = AuthProofs.newSecret()
        secureStore.write(KEY_IDENTITY_PASSWORD, generated.toByteArray(Charsets.UTF_8))
        return generated.toCharArray()
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_IDENTITY = "identity_p12"
        const val KEY_IDENTITY_PASSWORD = "identity_p12_password"
    }
}
