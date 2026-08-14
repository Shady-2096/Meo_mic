package com.meo.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trust lifetime and the QR invite, both of which decide whether a stranger
 * gets a camera.
 */
class PairingStoreTest {

    private val now = 1_780_000_000_000L

    private fun pairing(
        id: String = "desktop-1",
        expiresAt: Long = now + Pairing.SLIDING_VALIDITY_MS
    ) = Pairing(
        desktopDeviceId = id,
        displayName = "Studio PC",
        desktopSpkiPin = "a".repeat(64),
        credential = AuthProofs.newSecret(),
        expiresAt = expiresAt
    )

    @Test
    fun `a pairing survives a round trip through encrypted storage`() {
        val store = PersistentPairingStore(InMemorySecureStore())
        val original = pairing()
        store.save(original)

        assertEquals(listOf(original), store.all())
        assertEquals(original, store.find("desktop-1"))
        assertNull(store.find("desktop-2"))
    }

    @Test
    fun `saving the same desktop twice replaces rather than duplicates`() {
        // Otherwise every reconnect would append a record and the credential
        // actually in use would depend on iteration order.
        val store = PersistentPairingStore(InMemorySecureStore())
        store.save(pairing())
        store.save(pairing().copy(displayName = "Renamed PC"))

        assertEquals(1, store.all().size)
        assertEquals("Renamed PC", store.find("desktop-1")?.displayName)
    }

    @Test
    fun `expiry slides forward on every successful connection`() {
        val original = pairing(expiresAt = now + 1000)
        val renewed = original.renewed(now)

        assertEquals(now + Pairing.SLIDING_VALIDITY_MS, renewed.expiresAt)
        assertEquals(now, renewed.lastSeenAt)
        assertFalse(renewed.isExpired(now))
        // 30 days, per plan 6.1 step 8.
        assertEquals(30L * 24 * 60 * 60 * 1000, Pairing.SLIDING_VALIDITY_MS)
    }

    @Test
    fun `an expired pairing is neither trusted nor kept`() {
        val store = PersistentPairingStore(InMemorySecureStore())
        store.save(pairing(id = "live", expiresAt = now + 1000))
        store.save(pairing(id = "stale", expiresAt = now - 1))

        assertEquals(1, store.trustedPins(now).size)
        assertEquals(1, store.purgeExpired(now))
        assertNotNull(store.find("live"))
        assertNull(store.find("stale"))
    }

    @Test
    fun `a revoked pairing stops being trusted immediately`() {
        val store = PersistentPairingStore(InMemorySecureStore())
        store.save(pairing())
        assertEquals(1, store.trustedPins(now).size)

        store.remove("desktop-1")
        assertTrue(store.trustedPins(now).isEmpty())
    }

    @Test
    fun `corrupt storage fails closed rather than trusting an unparsable record`() {
        val secure = InMemorySecureStore()
        secure.write("pairings", "{ this is not the json you are looking for".toByteArray())
        assertTrue(PersistentPairingStore(secure).all().isEmpty())
    }

    // --- The QR invite -----------------------------------------------------

    private fun inviteJson(
        version: Int = com.meo.protocol.Protocol.VERSION,
        spki: String = "a".repeat(64),
        token: String = "b".repeat(64),
        expiresAt: Long = now + 5 * 60_000
    ) = """{"v":$version,"id":"desktop-1","name":"Studio PC","spki":"$spki",""" +
        """"tok":"$token","exp":$expiresAt}"""

    @Test
    fun `a well formed invite parses`() {
        val invite = PairingInvite.parse(inviteJson())
        assertNotNull(invite)
        assertEquals("desktop-1", invite!!.desktopDeviceId)
        assertFalse(invite.isExpired(now))
        assertTrue(invite.isExpired(now + 5 * 60_000))
    }

    @Test
    fun `an invite with a wrong sized pin or token is refused`() {
        // A 16-byte "SHA-256" is the shape of a QR from something that is not
        // Meo, or from a Meo that got its hashing wrong. Either way, not ours.
        assertNull(PairingInvite.parse(inviteJson(spki = "a".repeat(32))))
        assertNull(PairingInvite.parse(inviteJson(token = "b".repeat(30))))
        assertNull(PairingInvite.parse(inviteJson(spki = "z".repeat(64))))
    }

    @Test
    fun `an invite from another protocol version is refused`() {
        assertNull(PairingInvite.parse(inviteJson(version = 99)))
    }

    @Test
    fun `arbitrary scanned text is refused without throwing`() {
        // The scanner will happily hand over any QR the user points it at,
        // including a Wi-Fi config, a URL, or a payment code.
        listOf(
            "", "not json", "{}", "[]", "https://example.com",
            "WIFI:T:WPA;S:home;P:hunter2;;", "{\"v\":1}"
        ).forEach { assertNull("should refuse: $it", PairingInvite.parse(it)) }
    }

    // --- Rate limiting -----------------------------------------------------

    @Test
    fun `repeated failures trigger backoff and success clears it`() {
        val limiter = AttemptLimiter(maxFailuresBeforeBackoff = 3, baseBackoffMillis = 1000)
        assertNull(limiter.checkAllowed("peer", now))

        repeat(2) { limiter.recordFailure("peer", now) }
        assertNull("under the threshold, still allowed", limiter.checkAllowed("peer", now))

        limiter.recordFailure("peer", now)
        assertNotNull("threshold reached, must back off", limiter.checkAllowed("peer", now))

        // Backoff doubles while failures continue.
        limiter.recordFailure("peer", now)
        val longer = limiter.checkAllowed("peer", now)!!
        assertTrue("backoff should grow, got $longer", longer > 1000)

        limiter.recordSuccess("peer")
        assertNull(limiter.checkAllowed("peer", now))
    }

    @Test
    fun `backoff expires and old failures fall out of the window`() {
        val limiter = AttemptLimiter(
            maxFailuresBeforeBackoff = 1,
            baseBackoffMillis = 1000,
            failureWindowMillis = 10_000
        )
        limiter.recordFailure("peer", now)
        assertNotNull(limiter.checkAllowed("peer", now))
        assertNull("backoff must end", limiter.checkAllowed("peer", now + 1001))

        limiter.recordFailure("peer", now)
        assertNull("stale failures must not count", limiter.checkAllowed("peer", now + 20_000))
    }

    @Test
    fun `one hostile peer cannot lock out another`() {
        val limiter = AttemptLimiter(maxFailuresBeforeBackoff = 1)
        limiter.recordFailure("attacker", now)
        assertNotNull(limiter.checkAllowed("attacker", now))
        assertNull(limiter.checkAllowed("the real desktop", now))
    }
}
