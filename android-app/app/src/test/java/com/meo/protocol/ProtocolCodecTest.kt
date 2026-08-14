package com.meo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * What the codec does with input it did not expect.
 *
 * The happy path is covered by the golden fixtures. This file is about the
 * other path, which is the one that matters: this decoder runs before the peer
 * has proven anything, so every case here is reachable by anyone who can open a
 * TCP connection to the phone and complete a TLS handshake.
 */
class ProtocolCodecTest {

    private fun rejection(text: String): DecodeResult.Rejected {
        val result = ProtocolCodec.decode(text)
        assertTrue("Expected a rejection, got $result", result is DecodeResult.Rejected)
        return result as DecodeResult.Rejected
    }

    private fun envelopeJson(payload: String, version: Int = Protocol.VERSION): String =
        """{"protocol_version":$version,"session_id":"s-1","message_id":1,""" +
            """"sent_at_monotonic_ms":10,"payload":$payload}"""

    // --- Forward compatibility ---------------------------------------------

    @Test
    fun `an unknown field is ignored so a newer peer does not break this build`() {
        val json = envelopeJson(
            """{"type":"set_paused","paused":true,"reason_the_user_paused":"meeting over"}"""
        )
        val result = ProtocolCodec.decode(json)
        assertTrue(result is DecodeResult.Ok)
        assertEquals(SetPaused(paused = true), (result as DecodeResult.Ok).envelope.payload)
    }

    @Test
    fun `an unknown envelope field is ignored too`() {
        val json = """{"protocol_version":1,"session_id":"s-1","message_id":1,""" +
            """"sent_at_monotonic_ms":10,"trace_id":"abc","payload":{"type":"stop_stream"}}"""
        assertTrue(ProtocolCodec.decode(json) is DecodeResult.Ok)
    }

    // --- Refusals ----------------------------------------------------------

    @Test
    fun `an unknown message type is refused explicitly rather than dropped`() {
        // The distinction this asserts is the whole reason the codec returns a
        // typed result: a peer that sent "reboot_phone" must learn that nothing
        // happened, instead of assuming it did.
        val result = rejection(envelopeJson("""{"type":"reboot_phone","when":"now"}"""))
        assertEquals(ErrorCode.UNKNOWN_TYPE, result.code)
    }

    @Test
    fun `a different protocol version is refused`() {
        val result = rejection(envelopeJson("""{"type":"stop_stream"}""", version = 2))
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, result.code)
    }

    @Test
    fun `malformed json is refused without throwing`() {
        assertEquals(ErrorCode.MALFORMED, rejection("{not json at all").code)
        assertEquals(ErrorCode.MALFORMED, rejection("").code)
        assertEquals(ErrorCode.MALFORMED, rejection("[]").code)
        assertEquals(ErrorCode.MALFORMED, rejection("null").code)
    }

    @Test
    fun `a payload that is not an object is refused`() {
        assertTrue(ProtocolCodec.decode(envelopeJson(""""stop_stream"""")) is DecodeResult.Rejected)
        assertTrue(ProtocolCodec.decode(envelopeJson("42")) is DecodeResult.Rejected)
    }

    @Test
    fun `a negative or empty header field is refused`() {
        val negativeId = """{"protocol_version":1,"session_id":"s-1","message_id":-1,""" +
            """"sent_at_monotonic_ms":10,"payload":{"type":"stop_stream"}}"""
        assertEquals(ErrorCode.MALFORMED, rejection(negativeId).code)

        val emptySession = """{"protocol_version":1,"session_id":"","message_id":1,""" +
            """"sent_at_monotonic_ms":10,"payload":{"type":"stop_stream"}}"""
        assertEquals(ErrorCode.MALFORMED, rejection(emptySession).code)
    }

    @Test
    fun `an oversized field is refused rather than truncated`() {
        val hugeSdp = "v=0\\r\\n" + "a=".repeat(Protocol.MAX_SDP_LENGTH)
        val result = rejection(envelopeJson("""{"type":"sdp_offer","sdp":"$hugeSdp"}"""))
        assertEquals(ErrorCode.TOO_LARGE, result.code)
    }

    @Test
    fun `a frame beyond the maximum is refused before parsing`() {
        val huge = "x".repeat(Protocol.MAX_FRAME_BYTES + 1)
        assertEquals(ErrorCode.TOO_LARGE, rejection(huge).code)
    }

    @Test
    fun `an out-of-range zoom is refused`() {
        val nan = Envelope(
            Protocol.VERSION, "s-1", 1, 10,
            CameraControl(zoomRatio = Float.NaN)
        )
        assertEquals(ErrorCode.MALFORMED, nan.validate())

        val inverted = Envelope(
            Protocol.VERSION, "s-1", 1, 10,
            CameraCapabilitiesUpdate(
                CameraCapabilities(
                    lensFacing = "back",
                    hasFront = true,
                    hasBack = true,
                    minZoomRatio = 4f,
                    maxZoomRatio = 1f,
                    torchAvailable = false
                )
            )
        )
        assertEquals(ErrorCode.MALFORMED, inverted.validate())
    }

    @Test
    fun `a well formed envelope validates`() {
        assertNull(Envelope(Protocol.VERSION, "s-1", 0, 0, StopStream).validate())
    }

    // --- Framing -----------------------------------------------------------

    @Test
    fun `frames round-trip`() {
        val payload = ProtocolCodec.encodeToBytes(
            Envelope(Protocol.VERSION, "s-1", 3, 99, StopStream)
        )
        val buffer = ByteArrayOutputStream()
        Framing.writeFrame(buffer, payload)
        Framing.writeFrame(buffer, payload)

        val input = ByteArrayInputStream(buffer.toByteArray())
        repeat(2) {
            val frame = Framing.readFrame(input)
            assertTrue(frame is FrameRead.Frame)
            assertTrue((frame as FrameRead.Frame).bytes.contentEquals(payload))
        }
        assertEquals(FrameRead.EndOfStream, Framing.readFrame(input))
    }

    @Test
    fun `a declared length beyond the maximum is refused without allocating it`() {
        // 0x7FFFFFFF announced, two bytes delivered. If the reader allocated
        // what it was told, this test would be an OutOfMemoryError instead of an
        // assertion. That is exactly the failure being prevented.
        val hostile = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 1, 2)
        val result = Framing.readFrame(ByteArrayInputStream(hostile))
        assertTrue(result is FrameRead.TooLarge)
        assertEquals(0x7FFFFFFFL, (result as FrameRead.TooLarge).declaredLength)
    }

    @Test
    fun `a truncated body is refused`() {
        val truncated = byteArrayOf(0, 0, 0, 16, 1, 2, 3)
        assertTrue(Framing.readFrame(ByteArrayInputStream(truncated)) is FrameRead.Malformed)
    }

    @Test
    fun `a truncated length prefix is refused but a clean close is not`() {
        val partialHeader = byteArrayOf(0, 0)
        assertTrue(Framing.readFrame(ByteArrayInputStream(partialHeader)) is FrameRead.Malformed)
        assertEquals(FrameRead.EndOfStream, Framing.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `a zero length frame is refused`() {
        val empty = byteArrayOf(0, 0, 0, 0)
        assertTrue(Framing.readFrame(ByteArrayInputStream(empty)) is FrameRead.Malformed)
    }
}
