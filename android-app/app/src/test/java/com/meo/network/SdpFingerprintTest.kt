package com.meo.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpFingerprintTest {

    private val sha256 = (1..32).joinToString(":") { "%02X".format(it) }
    private val otherSha256 = (33..64).joinToString(":") { "%02X".format(it) }

    private fun sdp(vararg fingerprintLines: String) = buildString {
        append("v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n")
        fingerprintLines.forEach { append("$it\r\n") }
        append("m=video 9 UDP/TLS/RTP/SAVPF 102\r\na=mid:0\r\n")
    }

    private fun rejection(sdpText: String): String =
        (SdpFingerprint.extract(sdpText) as SdpFingerprint.Result.Rejected).reason

    @Test
    fun `a single sha-256 fingerprint is accepted and normalised`() {
        val result = SdpFingerprint.extract(sdp("a=fingerprint:sha-256 $sha256"))
        assertTrue(result is SdpFingerprint.Result.Ok)
        result as SdpFingerprint.Result.Ok
        assertEquals("sha-256", result.algorithm)
        assertEquals(sha256.lowercase(), result.value)
    }

    @Test
    fun `repeated but identical fingerprints are fine`() {
        // Session-level plus media-level is ordinary and correct.
        val result = SdpFingerprint.extract(
            sdp("a=fingerprint:sha-256 $sha256", "a=fingerprint:sha-256 $sha256")
        )
        assertTrue(result is SdpFingerprint.Result.Ok)
    }

    @Test
    fun `conflicting fingerprints are refused`() {
        // The dangerous shape: the two ends could each validate against a
        // different value and both believe they checked.
        val reason = rejection(
            sdp("a=fingerprint:sha-256 $sha256", "a=fingerprint:sha-256 $otherSha256")
        )
        assertTrue(reason.contains("conflicting"))
    }

    @Test
    fun `an SDP with no fingerprint is refused`() {
        // libwebrtc would have nothing to validate the DTLS handshake against.
        assertTrue(rejection(sdp()).contains("no DTLS fingerprint"))
    }

    @Test
    fun `sha-1 is refused`() {
        val sha1 = (1..20).joinToString(":") { "%02X".format(it) }
        assertTrue(rejection(sdp("a=fingerprint:sha-1 $sha1")).contains("unacceptable"))
    }

    @Test
    fun `a fingerprint of the wrong length is refused`() {
        val short = (1..16).joinToString(":") { "%02X".format(it) }
        assertTrue(rejection(sdp("a=fingerprint:sha-256 $short")).contains("octets"))
    }

    @Test
    fun `a malformed fingerprint is refused without throwing`() {
        listOf(
            "a=fingerprint:sha-256",
            "a=fingerprint:",
            "a=fingerprint:sha-256 not-hex-at-all",
            "a=fingerprint:sha-256 ZZ:" + (2..32).joinToString(":") { "%02X".format(it) },
            "a=fingerprint:sha-256 " + (1..32).joinToString(":") { "%01X".format(it) }
        ).forEach { line ->
            assertTrue(
                "should refuse: $line",
                SdpFingerprint.extract(sdp(line)) is SdpFingerprint.Result.Rejected
            )
        }
    }

    @Test
    fun `the committed offer fixture carries a valid fingerprint`() {
        // Ties this check to the wire contract: the fixture the future C++
        // receiver is written against must itself pass.
        val fixture = java.io.File("../../protocol/fixtures/sdp-offer.json").readText()
        val sdpValue = Regex("\"sdp\":\"(.*?)\"").find(fixture)!!.groupValues[1]
            .replace("\\r\\n", "\r\n")
        assertTrue(SdpFingerprint.extract(sdpValue) is SdpFingerprint.Result.Ok)
    }
}
