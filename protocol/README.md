# Meo Camera control protocol

The control plane between the phone and the desktop: versioned JSON over the
authenticated, pinned TLS channel ([ADR 0001](../adr/0001-transport-and-control-plane.md)).

Today there is exactly one implementation, the Kotlin one under
`android-app/app/src/main/java/com/meo/protocol/`. This directory exists because
there will not always be one — the Windows receiver will parse the same messages
with `nlohmann/json`, and two independent implementations that each believe
their own serializer is the specification will diverge quietly. `fixtures/` is
what both get checked against.

## Wire format

Each message is a length-prefixed frame on the TLS stream:

```text
[4-byte big-endian unsigned length][UTF-8 JSON, exactly that many bytes]
```

The length is validated against the 64 KiB maximum **before** a buffer is
allocated. A receiver that allocates the declared length and then range-checks
it has an out-of-memory bug reachable by any peer that completes a TLS
handshake.

Every frame decodes to an envelope:

```json
{
  "protocol_version": 1,
  "session_id": "s-7f3a91",
  "message_id": 42,
  "sent_at_monotonic_ms": 1234567,
  "payload": { "type": "…", "…": "…" }
}
```

`session_id` is `"-"` before a session exists, so the envelope shape never
varies. `message_id` is strictly increasing per connection per direction.
`sent_at_monotonic_ms` is the sender's monotonic clock, never wall-clock.

## The two compatibility rules

They point in opposite directions and both are load-bearing:

- **Unknown fields are ignored.** This is what lets one side add a field without
  coordinating a release, and it is the entire reason ADR 0001 could pick JSON
  over Protocol Buffers. Kotlin gets it from `ignoreUnknownKeys`; a C++
  implementation gets it by not erroring on unrecognised keys.
- **Unknown message types are refused**, with an `error` payload carrying code
  `unknown_type`. Dropping one silently would let a peer believe a command ran
  when it never did.

Optional fields are **omitted** rather than written as `null`, so "absent" and
"explicitly null" never diverge on the wire.

## Fixtures

`fixtures/` holds one committed file per payload type, generated from the Kotlin
encoder and asserted in both directions by `GoldenFixtureTest`: the bytes must
decode and validate, and re-encoding must reproduce the file exactly. The second
half is what catches a silent wire change that decoding alone would tolerate.

Regenerate after a deliberate protocol change, then read the diff:

```bash
cd android-app && ./gradlew testDebugUnitTest --tests '*GoldenFixtureTest*' -Dmeo.fixtures.write=true
```

A new payload type also needs the count in that test bumped — a type nobody
pinned is a type nobody checked.

## Limits

All of these reject the whole envelope; nothing is silently truncated.

| Bound | Value |
|---|---|
| Frame | 64 KiB |
| SDP | 32 KiB |
| ICE candidate | 1024 |
| Identifiers, names, tokens, digests | 128 |
| Error reason | 256 |
| Capture modes in a list | 64 |

## Message flow

First pairing, after the phone has scanned the QR and started its listener. The
desktop dials out ([plan §5.1](../CAMERA_BUILD_PLAN.md)); the phone listens.

```text
desktop → hello         { device_id, display_name, nonce, pairing_token }
phone   → auth_proof    { device_id, display_name, nonce, proof }
desktop → auth_accept   { proof, credential, expires_at }
phone   → session_ready { session_id, capabilities }
```

Reconnect is identical without `pairing_token` and without `credential`: both
sides prove the stored per-pairing credential instead.

Then, per session:

```text
desktop → start_stream       { profile }
phone   → sdp_offer          { sdp }
desktop → sdp_answer         { sdp }
both    ↔ ice_candidate      { sdp_mid, sdp_m_line_index, candidate }
desktop → camera_control     { lens_facing?, zoom_ratio?, torch? }
phone   → camera_control_ack { in_reply_to, applied_* }
phone   → health             { capture_fps, width, height, … }
desktop → set_paused         { paused }
desktop → stop_stream        {}
```

The phone offers rather than answers because the phone owns the media and knows
which encoders and capture modes exist on that device. Acknowledgements report
the **applied** value, never the requested one.

## Two things a receiver must not get wrong

- **SDP travels only here.** Plan §6.3 makes this correctness, not preference:
  the DTLS-SRTP fingerprint carried in this SDP is what the media handshake is
  checked against. A fingerprint that arrived by any other route proves nothing.
- **Arriving on this channel does not make an ICE candidate acceptable.** Each
  side filters candidates against its own local-route policy on receipt
  (plan §6.4).
