package com.meo.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Which addresses on this device count as "the local network", and which do not.
 *
 * Plan §6.4 makes local-only an enforced property rather than a default, and
 * this is where the enforcement is decided. Two separate jobs:
 *
 * - Choosing what the control listener binds to, so it is never on a public
 *   wildcard.
 * - Judging ICE candidates, so media cannot be steered off the LAN.
 *
 * The plan is blunt about the limit of this check: *"Private IP alone is not
 * proof of same LAN."* Nothing here is a substitute for the pinned TLS identity
 * and the authenticated pairing. It is the second of the two locks.
 */
object LocalNetwork {

    /**
     * Interface-name prefixes treated as tunnels rather than local links.
     *
     * A VPN is not the LAN the user thinks they are on, and sending camera
     * frames down one is the surprising outcome plan §6.4 asks us to refuse
     * until an explicit advanced option exists.
     */
    private val TUNNEL_PREFIXES = listOf("tun", "tap", "ppp", "ipsec", "utun", "wg", "nordlynx")

    data class Candidate(val address: InetAddress, val interfaceName: String)

    /** Every usable private address on a live, non-tunnel interface. */
    fun localAddresses(): List<Candidate> = try {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .filter { networkInterface ->
                runCatching {
                    networkInterface.isUp &&
                        !networkInterface.isLoopback &&
                        !isTunnel(networkInterface.name)
                }.getOrDefault(false)
            }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.toList().map { Candidate(it, networkInterface.name) }
            }
            .filter { isPrivate(it.address) }
    } catch (_: Exception) {
        // Enumeration can fail transiently while the radio changes state. An
        // empty list means "do not bind", which is the safe answer.
        emptyList()
    }

    /**
     * The address the listener should bind to.
     *
     * IPv4 first, deliberately: this connection is dialled by a desktop that
     * found the phone over mDNS or had its address typed in by hand, and an
     * IPv4 address is the one a person can read off a screen and retype. Link
     * local addresses are last because they need a scope id to be useful.
     */
    fun preferredBindAddress(): InetAddress? {
        val candidates = localAddresses()
        return candidates.firstOrNull { it.address is Inet4Address && !it.address.isLinkLocalAddress }?.address
            ?: candidates.firstOrNull { it.address is Inet6Address && !it.address.isLinkLocalAddress }?.address
            ?: candidates.firstOrNull()?.address
    }

    /**
     * Whether media may flow to or from this address.
     *
     * Loopback is refused as well as public: a candidate pointing at 127.0.0.1
     * describes a path to the phone itself, and accepting one would mean the
     * media never leaves the device while the session looked healthy.
     */
    fun isAllowedMediaAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return false
        if (address.isAnyLocalAddress) return false
        if (address.isMulticastAddress) return false
        if (!isPrivate(address)) return false
        // The address must also correspond to a network this device is actually
        // on. A private address from a range we have no interface for is not
        // "the local network", it is somewhere else's.
        return localAddresses().any { sameNetwork(it.address, address) }
    }

    fun isPrivate(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isMulticastAddress) {
            return false
        }
        if (address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        // Unique local addresses, fc00::/7. Java has no predicate for these and
        // its isSiteLocalAddress covers only the deprecated fec0::/10.
        if (address is Inet6Address) {
            val first = address.address[0].toInt() and 0xFE
            return first == 0xFC
        }
        return false
    }

    private fun sameNetwork(ours: InetAddress, theirs: InetAddress): Boolean {
        if (ours.javaClass != theirs.javaClass) return false
        return when (ours) {
            // A /24 comparison rather than the real prefix length. Erring
            // narrow is deliberate: the cost of refusing a candidate is one
            // less path for ICE to try, and the cost of accepting a wrong one
            // is media leaving the network the user is on.
            is Inet4Address -> ours.address.copyOfRange(0, 3)
                .contentEquals(theirs.address.copyOfRange(0, 3))

            // Link-local and ULA addresses share their first 8 bytes within a
            // link or site.
            is Inet6Address -> ours.address.copyOfRange(0, 8)
                .contentEquals(theirs.address.copyOfRange(0, 8))

            else -> false
        }
    }

    private fun isTunnel(name: String): Boolean {
        val lower = name.lowercase()
        return TUNNEL_PREFIXES.any { lower.startsWith(it) }
    }
}
