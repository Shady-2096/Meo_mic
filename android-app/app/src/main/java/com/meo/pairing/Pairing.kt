package com.meo.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the desktop puts in the QR code (plan §6.1 step 3).
 *
 * Note what is *not* here: any reusable secret. [token] is a one-time bootstrap
 * credential with minutes of validity, and the durable per-pairing credential is
 * issued over the authenticated channel afterwards. A photographed QR is
 * therefore worth very little, and worth nothing at all once used or expired.
 */
@Serializable
data class PairingInvite(
    @SerialName("v") val protocolVersion: Int,
    @SerialName("id") val desktopDeviceId: String,
    @SerialName("name") val desktopDisplayName: String,
    /** SHA-256 of the desktop's TLS SubjectPublicKeyInfo, hex. */
    @SerialName("spki") val desktopSpkiPin: String,
    /** 256-bit one-time token, hex. */
    @SerialName("tok") val token: String,
    /** Epoch millis. Five minutes out, per plan §6.1 step 2. */
    @SerialName("exp") val expiresAt: Long,
    /**
     * Optional addresses the desktop believes it can be reached on. Unused by
     * the phone — the desktop dials out — but carried so a future manual-entry
     * or reverse-dial path does not need a new QR format.
     */
    @SerialName("addr") val addresses: List<String> = emptyList()
) {
    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAt

    /**
     * Bounds everything before any of it is stored or compared. A QR code is
     * attacker-supplied input: anyone can print one.
     */
    fun validate(): String? = when {
        protocolVersion != com.meo.protocol.Protocol.VERSION -> "unsupported_version"
        desktopDeviceId.isEmpty() || desktopDeviceId.length > 128 -> "bad_device_id"
        desktopDisplayName.length > 128 -> "bad_display_name"
        SpkiPin.parseHex(desktopSpkiPin)?.size != 32 -> "bad_spki_pin"
        SpkiPin.parseHex(token)?.size != 32 -> "bad_token"
        expiresAt <= 0 -> "bad_expiry"
        addresses.size > 8 -> "too_many_addresses"
        addresses.any { it.length > 64 } -> "bad_address"
        else -> null
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses scanned text. Returns null for anything that is not ours. */
        fun parse(scanned: String): PairingInvite? = try {
            val invite = json.decodeFromString(serializer(), scanned)
            if (invite.validate() == null) invite else null
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * A desktop this phone trusts, and the secret that proves it on reconnect.
 *
 * [expiresAt] slides: every successful connection pushes it 30 days out
 * (plan §6.1 step 8), so a computer in daily use never expires and one that
 * disappears for a month stops being trusted without anyone having to revoke
 * it.
 */
@Serializable
data class Pairing(
    @SerialName("device_id") val desktopDeviceId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("spki_pin") val desktopSpkiPin: String,
    /** >=256 bits, hex. Issued by the desktop over the authenticated channel. */
    @SerialName("credential") val credential: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("last_seen_at") val lastSeenAt: Long = 0
) {
    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAt

    fun renewed(nowMillis: Long): Pairing = copy(
        expiresAt = nowMillis + SLIDING_VALIDITY_MS,
        lastSeenAt = nowMillis
    )

    companion object {
        const val SLIDING_VALIDITY_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * Where pairings live.
 *
 * An interface because the persistence and the policy have very different
 * testing needs: the sliding expiry, the revocation semantics and the pin
 * lookups are logic worth exercising thoroughly on the JVM, while the Android
 * implementation is a thin Keystore-encrypted blob that can only run on a
 * device.
 */
interface PairingStore {
    fun all(): List<Pairing>
    fun find(desktopDeviceId: String): Pairing?
    fun save(pairing: Pairing)
    fun remove(desktopDeviceId: String)

    /** Pins currently acceptable for a reconnect handshake. */
    fun trustedPins(nowMillis: Long): Set<String> =
        all().filterNot { it.isExpired(nowMillis) }.map { it.desktopSpkiPin }.toSet()

    /** Drops expired records. Returns how many were removed. */
    fun purgeExpired(nowMillis: Long): Int {
        val expired = all().filter { it.isExpired(nowMillis) }
        expired.forEach { remove(it.desktopDeviceId) }
        return expired.size
    }
}

class InMemoryPairingStore : PairingStore {
    private val pairings = LinkedHashMap<String, Pairing>()

    @Synchronized
    override fun all(): List<Pairing> = pairings.values.toList()

    @Synchronized
    override fun find(desktopDeviceId: String): Pairing? = pairings[desktopDeviceId]

    @Synchronized
    override fun save(pairing: Pairing) {
        pairings[pairing.desktopDeviceId] = pairing
    }

    @Synchronized
    override fun remove(desktopDeviceId: String) {
        pairings.remove(desktopDeviceId)
    }
}
