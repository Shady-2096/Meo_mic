# ADR 0001 — WebRTC media plane, versioned JSON control plane

- **Status:** Accepted (inherited from the plan). One sub-question open.
- **Date:** 2026-08-07
- **Plan:** §5.2, §5.3, §5.4, §6.3

## Context

§12's Milestone 0 gate requires a written transport ADR. `CAMERA_BUILD_PLAN.md`
already argues both halves of this at length; this record exists so the
decision is versioned alongside the code rather than only inside a planning
document, and so the part that is *not* yet decided is visible.

## Decision

**Media plane: WebRTC, restricted to host/local candidates.** DTLS-SRTP gives
encrypted media; loss recovery, jitter handling, keyframe management, and
congestion control already exist and are not worth reimplementing against
variable Wi-Fi. No public STUN, no TURN, no relay — §6.4 makes local-only an
enforced property, not a default.

**Control plane: JSON with an explicit `protocol_version`.** Not Protocol
Buffers. For a handful of small messages per second, protobuf buys compactness
nobody needs in exchange for a codegen toolchain in three languages. The one
property that actually matters — safe handling of unknown fields — comes from
decoder configuration (`ignoreUnknownKeys` in Kotlin, `Codable` in Swift,
`nlohmann/json` in C++) plus golden fixtures.

**Signalling is bound to the authenticated session.** §6.3 is a correctness
requirement, not a nicety: SDP travels only over the pinned TLS control
channel, and the DTLS-SRTP fingerprint in that SDP is verified against the
actual media handshake. Without that check, encrypted transport buys nothing,
because anything reaching the signalling path can substitute its own media
endpoint.

**Initial codec: H.264** via the phone's hardware encoder, negotiated
Baseline/Main-compatible. VP8 software fallback only where no usable H.264
path exists and thermal testing passes.

## Open sub-question — blocking the Milestone 0 gate

**Which WebRTC distribution, and under what license?**

§5.2 and §16 both require the provenance recorded before anything ships.
Google's WebRTC is BSD-3-Clause and MIT-compatible; a GPL fork is not, and
§16 forbids bundling GPL components in MIT binaries. Nothing has been chosen,
so nothing can be recorded, and `THIRD_PARTY_NOTICES.md` currently carries a
placeholder saying exactly that.

What the choice has to answer:

- Which prebuilt binary, from which publisher, for Android and for Windows
  x64.
- Its license, verified from the artifact rather than assumed from the
  project.
- How it gets updated, and who notices when it needs to be.

[ADR 0008](0008-android-webrtc-distribution.md) settles the Android feasibility
candidate from a downloaded and inspected artifact. The Windows half remains
open and needs the receiver to exist far enough to link against something.

## Consequences

- `protocol/` holds JSON schemas with committed golden fixtures, per
  Milestone 1. Every message carries `protocol_version`, `session_id`, a
  monotonic `message_id`, `sent_at_monotonic_ms`, and a typed payload.
- Hard limits on every inbound message — maximum size, array lengths, string
  lengths — with unknown message types rejected with an error rather than
  silently dropped. §5.3 calls this the primary attack surface and it is.
- The network stream format and the virtual-camera output format stay
  separate (§5.4). The phone may drop to 540p while the virtual camera keeps
  publishing a stable 720p. Renegotiating a virtual camera's media type
  mid-call is the most reliable way to break Zoom.
- Meo Mic's existing UDP protocol is untouched. §4 is explicit that Mic keeps
  working and must not be rewritten as a prerequisite; the new session layer
  should merely leave room for Mic to migrate later.
