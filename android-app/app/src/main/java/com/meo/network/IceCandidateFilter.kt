package com.meo.network

import java.net.InetAddress

/**
 * Decides whether an ICE candidate may be used.
 *
 * Arriving over the authenticated control channel is *not* sufficient. Plan
 * §6.3 is explicit that candidates are accepted "only for addresses that resolve
 * to an allowed local interface route on the receiving side", and the reason is
 * that the control channel proves who the peer is, not where the media will go.
 * A paired desktop that has been compromised, or is simply misconfigured behind
 * a VPN, can offer a perfectly authentic candidate pointing off the LAN.
 *
 * Meo configures no STUN and no TURN server, so in normal operation every
 * candidate is `typ host`. Anything else means either a peer that is not
 * playing by the same rules or a misconfiguration, and both are refused rather
 * than investigated.
 *
 * The parsing is deliberately tolerant about *shape* and strict about
 * *content*: an unfamiliar trailing attribute is fine, an unfamiliar address is
 * not.
 */
object IceCandidateFilter {

    sealed class Verdict {
        data object Accept : Verdict()
        data class Reject(val reason: String) : Verdict()
    }

    /**
     * @param resolver injected so the policy can be tested without depending on
     *   whatever network the test machine happens to be on.
     */
    fun evaluate(
        candidate: String,
        isAllowedAddress: (InetAddress) -> Boolean = LocalNetwork::isAllowedMediaAddress
    ): Verdict {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) return Verdict.Reject("empty candidate")

        // An end-of-candidates signal is legitimate and carries no address.
        if (trimmed.equals("a=end-of-candidates", ignoreCase = true) ||
            trimmed.equals("end-of-candidates", ignoreCase = true)
        ) {
            return Verdict.Accept
        }

        val body = trimmed
            .removePrefix("a=")
            .let { if (it.startsWith("candidate:")) it.removePrefix("candidate:") else return Verdict.Reject("not a candidate line") }

        val fields = body.split(Regex("\\s+"))
        // foundation, component, transport, priority, address, port, "typ", type
        if (fields.size < 8) return Verdict.Reject("malformed candidate")

        val transport = fields[2].lowercase()
        if (transport != "udp" && transport != "tcp") {
            return Verdict.Reject("unsupported transport: $transport")
        }

        val typIndex = fields.indexOf("typ")
        if (typIndex < 0 || typIndex + 1 >= fields.size) return Verdict.Reject("missing candidate type")
        val type = fields[typIndex + 1].lowercase()
        if (type != "host") {
            // srflx and prflx imply a STUN server we did not configure; relay
            // implies a TURN server routing our video through a third party.
            return Verdict.Reject("non-host candidate type: $type")
        }

        val port = fields[5].toIntOrNull() ?: return Verdict.Reject("malformed port")
        if (port !in 1..65535) return Verdict.Reject("port out of range")

        val addressText = fields[4]
        if (addressText.endsWith(".local", ignoreCase = true)) {
            // mDNS-obfuscated candidates hide the address from exactly the check
            // being performed here. Resolving one would defeat the purpose, and
            // accepting it unresolved would mean not checking at all.
            return Verdict.Reject("mDNS-obfuscated candidate cannot be route-checked")
        }

        val address = parseLiteral(addressText)
            ?: return Verdict.Reject("candidate address is not an IP literal")

        if (!isAllowedAddress(address)) {
            return Verdict.Reject("address is not on an allowed local route")
        }
        return Verdict.Accept
    }

    /**
     * Parses an IP literal without ever performing a DNS lookup.
     *
     * `InetAddress.getByName` resolves hostnames, which would turn a hostile
     * candidate into a network request to a name of the peer's choosing, from
     * inside the accept loop. Only literals are accepted here.
     */
    private fun parseLiteral(text: String): InetAddress? {
        val stripped = text.removePrefix("[").removeSuffix("]").substringBefore('%')
        val looksNumeric = stripped.isNotEmpty() &&
            stripped.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }
        if (!looksNumeric) return null
        if (!stripped.contains('.') && !stripped.contains(':')) return null
        return try {
            InetAddress.getByName(stripped)
        } catch (_: Exception) {
            null
        }
    }
}
