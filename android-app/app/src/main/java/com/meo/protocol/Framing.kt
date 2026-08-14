package com.meo.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * The outcome of reading one frame off the wire.
 *
 * An oversized declared length is a result rather than an exception because it
 * is the single most likely hostile input on this channel — "here comes a 3 GB
 * message" — and the correct response is to answer with an error and close,
 * not to unwind a stack.
 */
sealed class FrameRead {
    class Frame(val bytes: ByteArray) : FrameRead()
    data object EndOfStream : FrameRead()
    data class TooLarge(val declaredLength: Long) : FrameRead()
    data class Malformed(val reason: String) : FrameRead()
}

/**
 * Length-prefixed framing: a 4-byte big-endian unsigned length followed by that
 * many bytes of UTF-8 JSON.
 *
 * TLS gives a byte stream, not a message stream, so something has to say where
 * a message ends. The length prefix is checked against [Protocol.MAX_FRAME_BYTES]
 * *before* any buffer is allocated — allocating what the peer asks for and then
 * checking is the bug this exists to avoid.
 */
object Framing {

    fun writeFrame(output: OutputStream, payload: ByteArray) {
        require(payload.size <= Protocol.MAX_FRAME_BYTES) {
            "refusing to send a frame larger than the peer will accept"
        }
        val length = payload.size
        output.write((length ushr 24) and 0xFF)
        output.write((length ushr 16) and 0xFF)
        output.write((length ushr 8) and 0xFF)
        output.write(length and 0xFF)
        output.write(payload)
        output.flush()
    }

    fun readFrame(input: InputStream): FrameRead {
        val data = input as? DataInputStream ?: DataInputStream(input)

        val header = ByteArray(Protocol.LENGTH_PREFIX_BYTES)
        var read = 0
        while (read < header.size) {
            val count = try {
                data.read(header, read, header.size - read)
            } catch (_: EOFException) {
                -1
            }
            if (count < 0) {
                // A clean close between messages is ordinary; a close partway
                // through a length prefix is not.
                return if (read == 0) FrameRead.EndOfStream
                else FrameRead.Malformed("truncated length prefix")
            }
            read += count
        }

        val declared = ((header[0].toLong() and 0xFF) shl 24) or
            ((header[1].toLong() and 0xFF) shl 16) or
            ((header[2].toLong() and 0xFF) shl 8) or
            (header[3].toLong() and 0xFF)

        if (declared == 0L) return FrameRead.Malformed("empty frame")
        if (declared > Protocol.MAX_FRAME_BYTES) return FrameRead.TooLarge(declared)

        val body = ByteArray(declared.toInt())
        try {
            data.readFully(body)
        } catch (_: EOFException) {
            return FrameRead.Malformed("truncated frame body")
        }
        return FrameRead.Frame(body)
    }
}
