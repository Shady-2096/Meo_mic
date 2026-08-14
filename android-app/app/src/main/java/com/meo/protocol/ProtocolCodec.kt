package com.meo.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The result of trying to understand something a peer sent.
 *
 * Decoding hostile input is the one place in this app where "throw and let the
 * caller deal with it" is the wrong shape: the caller's only sensible response
 * to any failure is to answer with an error and close, and an exception type
 * that escapes the codec invites someone to catch it too broadly later.
 */
sealed class DecodeResult {
    data class Ok(val envelope: Envelope) : DecodeResult()
    data class Rejected(val code: String, val reason: String) : DecodeResult()
}

/**
 * Encodes and decodes control messages.
 *
 * Forward compatibility and strictness pull in opposite directions here and the
 * split is deliberate:
 *
 * - Unknown **fields** are ignored, so a newer peer can add one without
 *   breaking this build. That is what `ignoreUnknownKeys` buys and it is the
 *   entire reason ADR 0001 could choose JSON over protobuf.
 * - Unknown **message types** are refused with a typed error. Silently dropping
 *   one would let a peer believe it had issued a command that never ran, which
 *   is worse than a clean refusal.
 */
object ProtocolCodec {
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
        // Optional fields are omitted rather than written as null. The wire
        // stays readable and "absent" and "explicitly null" never diverge.
        explicitNulls = false
        prettyPrint = false
    }

    fun encode(envelope: Envelope): String = json.encodeToString(Envelope.serializer(), envelope)

    fun encodeToBytes(envelope: Envelope): ByteArray = encode(envelope).toByteArray(Charsets.UTF_8)

    /**
     * Parses one frame. Never throws, whatever the bytes are.
     */
    fun decode(text: String): DecodeResult {
        if (text.length > Protocol.MAX_FRAME_BYTES) {
            return DecodeResult.Rejected(ErrorCode.TOO_LARGE, "frame exceeds maximum size")
        }
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), text)
        } catch (error: Throwable) {
            // kotlinx throws SerializationException for an unregistered
            // discriminator and for malformed JSON alike, and distinguishing
            // them by message text would be fragile. The distinction that
            // matters to the peer is preserved: both are refusals, and the
            // reason string carries the detail for local diagnostics.
            return DecodeResult.Rejected(
                classify(error),
                error.message?.take(Protocol.MAX_REASON_LENGTH) ?: "undecodable"
            )
        }

        val problem = envelope.validate()
        if (problem != null) {
            return DecodeResult.Rejected(problem, "envelope failed validation")
        }
        return DecodeResult.Ok(envelope)
    }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size > Protocol.MAX_FRAME_BYTES) {
            return DecodeResult.Rejected(ErrorCode.TOO_LARGE, "frame exceeds maximum size")
        }
        return decode(String(bytes, Charsets.UTF_8))
    }

    private fun classify(error: Throwable): String {
        val message = error.message.orEmpty()
        val looksLikeUnknownType = message.contains("polymorphic", ignoreCase = true) ||
            message.contains("serializer was not found", ignoreCase = true) ||
            message.contains("class discriminator", ignoreCase = true)
        return if (looksLikeUnknownType) ErrorCode.UNKNOWN_TYPE else ErrorCode.MALFORMED
    }
}
