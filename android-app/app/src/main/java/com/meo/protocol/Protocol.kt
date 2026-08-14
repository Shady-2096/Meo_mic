package com.meo.protocol

/**
 * Wire constants and the hard limits every inbound message is checked against.
 *
 * ADR 0001 calls the control channel "the primary attack surface" and it is:
 * the listener accepts TLS connections from the local network, and a message
 * is parsed before the peer has finished proving who it is. Every limit here
 * exists to bound what an unauthenticated peer can make this process allocate
 * or iterate over.
 *
 * The numbers are deliberately generous for legitimate traffic and still small
 * enough that a hostile peer cannot do anything interesting with them.
 */
object Protocol {
    /**
     * Bumped only for a breaking change. Unknown *fields* are tolerated by the
     * decoder for forward compatibility; an unknown *version* is not, because
     * the meaning of the fields we do understand may have changed.
     */
    const val VERSION = 1

    /** Advertised over mDNS so a desktop can refuse a peer it cannot talk to. */
    const val MAJOR_VERSION = 1

    /**
     * The largest frame the listener will read. An SDP offer with several
     * codecs and ICE candidates is a few kilobytes; 64 KiB leaves room for
     * growth while capping a single read at something trivial to allocate.
     */
    const val MAX_FRAME_BYTES = 64 * 1024

    /** Frames are length-prefixed with a 4-byte big-endian unsigned length. */
    const val LENGTH_PREFIX_BYTES = 4

    // Field-level caps. A string that exceeds its cap rejects the whole
    // envelope rather than being silently truncated, because a truncated
    // identifier or SDP is more dangerous than a refused one.

    const val MAX_ID_LENGTH = 128
    const val MAX_NAME_LENGTH = 128
    const val MAX_TOKEN_LENGTH = 128
    const val MAX_HEX_DIGEST_LENGTH = 128
    const val MAX_SDP_LENGTH = 32 * 1024
    const val MAX_CANDIDATE_LENGTH = 1024
    const val MAX_REASON_LENGTH = 256
    const val MAX_CAPTURE_MODES = 64

    /**
     * Sessions are identified by an opaque string chosen by the phone. Before
     * a session exists — the very first message on a connection — this
     * placeholder is used so the envelope shape never varies.
     */
    const val NO_SESSION = "-"
}
