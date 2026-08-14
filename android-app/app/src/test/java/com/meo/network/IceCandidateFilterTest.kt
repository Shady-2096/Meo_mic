package com.meo.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Where the media is allowed to go.
 *
 * The address predicate is injected rather than read from the machine running
 * the tests, because otherwise these assertions would depend on whether CI
 * happened to be on a 192.168 network — and a security check that passes for
 * environmental reasons is not a check.
 */
class IceCandidateFilterTest {

    /** Stands in for "this device is on 192.168.1.0/24". */
    private val onHomeLan: (InetAddress) -> Boolean = { address ->
        address.hostAddress?.startsWith("192.168.1.") == true
    }

    private fun accepted(candidate: String) =
        IceCandidateFilter.evaluate(candidate, onHomeLan) is IceCandidateFilter.Verdict.Accept

    private fun rejectionReason(candidate: String): String =
        (IceCandidateFilter.evaluate(candidate, onHomeLan) as IceCandidateFilter.Verdict.Reject).reason

    private fun host(address: String, extra: String = "") =
        "candidate:1 1 udp 2130706431 $address 51820 typ host$extra"

    @Test
    fun `a host candidate on our own network is accepted`() {
        assertTrue(accepted(host("192.168.1.42")))
        assertTrue("the a= prefix is optional", accepted("a=" + host("192.168.1.42")))
        assertTrue("tcp is legitimate too", accepted("candidate:1 1 tcp 2130706431 192.168.1.42 51820 typ host"))
    }

    @Test
    fun `unknown trailing attributes do not make a candidate unreadable`() {
        // Tolerant about shape, strict about content: WebRTC adds generation,
        // ufrag and network-id fields that this filter has no opinion about.
        assertTrue(accepted(host("192.168.1.42", " generation 0 ufrag j3Kx network-id 2 network-cost 10")))
    }

    @Test
    fun `a public address is refused`() {
        assertFalse(accepted(host("93.184.216.34")))
        assertTrue(rejectionReason(host("93.184.216.34")).contains("local route"))
    }

    @Test
    fun `a private address on a network we are not on is refused`() {
        // "Private IP" alone is not proof of same LAN, which plan §6.4 says
        // outright. This is the case that catches a peer on another subnet.
        assertFalse(accepted(host("10.0.0.5")))
        assertFalse(accepted(host("192.168.99.5")))
    }

    @Test
    fun `loopback is refused`() {
        // A candidate pointing at the phone itself would leave the session
        // looking healthy while no media ever left the device.
        assertFalse(accepted(host("127.0.0.1")))
        assertFalse(accepted(host("::1")))
    }

    @Test
    fun `relayed and reflexive candidates are refused`() {
        // Meo configures no STUN and no TURN, so these cannot legitimately
        // exist. A relay candidate in particular would route the user's camera
        // through a third party.
        val relay = "candidate:2 1 udp 41885439 192.168.1.42 51820 typ relay raddr 0.0.0.0 rport 0"
        val srflx = "candidate:3 1 udp 1694498815 192.168.1.42 51820 typ srflx raddr 10.0.0.1 rport 4444"
        assertFalse(accepted(relay))
        assertFalse(accepted(srflx))
        assertTrue(rejectionReason(relay).contains("non-host"))
    }

    @Test
    fun `an mDNS-obfuscated candidate is refused because it cannot be checked`() {
        // Resolving it would defeat the check; accepting it unresolved would be
        // no check at all.
        val obfuscated = "candidate:1 1 udp 2130706431 4f2a1c9e-1234-5678-9abc-def012345678.local 51820 typ host"
        assertFalse(accepted(obfuscated))
        assertTrue(rejectionReason(obfuscated).contains("mDNS"))
    }

    @Test
    fun `a hostname candidate never triggers a DNS lookup`() {
        // getByName would resolve this, turning a hostile candidate into a
        // network request to a name of the peer's choosing.
        assertFalse(accepted(host("attacker-controlled.example.com")))
        assertTrue(rejectionReason(host("attacker-controlled.example.com")).contains("IP literal"))
    }

    @Test
    fun `malformed candidates are refused without throwing`() {
        listOf(
            "", "   ", "garbage",
            "candidate:1 1 udp 2130706431 192.168.1.42",
            "candidate:1 1 udp 2130706431 192.168.1.42 51820",
            "candidate:1 1 sctp 2130706431 192.168.1.42 51820 typ host",
            "candidate:1 1 udp 2130706431 192.168.1.42 notaport typ host",
            "candidate:1 1 udp 2130706431 192.168.1.42 99999 typ host",
            "candidate:1 1 udp 2130706431 192.168.1.42 51820 typ",
            "a=sdpMid:0"
        ).forEach { assertFalse("should refuse: $it", accepted(it)) }
    }

    @Test
    fun `end of candidates is allowed through`() {
        assertTrue(accepted("a=end-of-candidates"))
        assertTrue(accepted("end-of-candidates"))
    }
}
