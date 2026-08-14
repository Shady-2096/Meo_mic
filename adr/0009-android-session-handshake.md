# ADR 0009 — The pairing and session handshake, as built

- **Status:** Accepted for the Android half, verified on the JVM; the desktop half is unwritten
- **Date:** 2026-08-14
- **Plan:** §5.1, §5.3, §6.1–§6.4, §7.2
- **Depends on:** [ADR 0001](0001-transport-and-control-plane.md), [ADR 0008](0008-android-webrtc-distribution.md)

## Context

ADR 0001 chose WebRTC and versioned JSON and stopped there. Plan §6 describes
pairing and reconnect in prose. Turning that prose into a wire protocol required
a dozen decisions that neither document makes, and several of them are the kind
where a plausible-looking choice quietly removes the guarantee.

This record exists because the Android side is now built and the desktop side is
not. Whoever writes the Windows receiver has to make exactly the matching
choices, and "read the Kotlin" is not a specification.

## Decisions

### The phone listens; the desktop dials

Plan §5.1's reasoning, implemented: a desktop that listens trips the Windows
Defender Firewall prompt, which needs administrator approval, and a dismissed
prompt means Meo silently never works. `ControlListener` runs on the phone.

The desktop remains the session coordinator regardless. Who accepts the TCP
connection and who drives the session are independent, and they are split here.

### One session at a time

A phone has one camera. A second desktop connecting is refused with a stated
reason rather than being queued or silently dropped.

### Both sides prove possession, and the phone proves first

The order looks wrong and is not. By the time any protocol byte is read, the
desktop has already authenticated: TLS completed against a pinned public key —
either the one just scanned from the QR, or one stored from an earlier pairing.
An unknown desktop never reaches the protocol layer at all.

### The proof construction

```text
proof = HMAC-SHA256(secret, label ‖ 0x00 ‖ nonce ‖ 0x00 ‖ peerPin ‖ 0x00 ‖ ownPin)
```

Each element removes a specific attack, and each is covered by a test that fails
if it is dropped:

| Element | Without it |
|---|---|
| `secret` | nothing is being proven |
| `nonce`, chosen by the verifier | yesterday's recorded handshake replays today |
| `label`, differing by direction | a challenge can be answered by reflecting it back at whoever issued it |
| `peerPin` and `ownPin` | a genuine proof can be relayed onto an attacker's own connection — real, but evidence about the wrong connection |

NUL separators make the concatenation unambiguous; hex pins cannot contain NUL,
so no combination of inputs encodes as another.

### Pins are of the public key, not the certificate

Plan §6.1 step 3, implemented as SHA-256 of the SubjectPublicKeyInfo. A
certificate can need reissuing; pinning it would invalidate every pairing on the
day that happened.

### An expired pairing *code* gets a grace period; an expired *pairing* does not

The only deliberate asymmetry here. An expired invite's key is still accepted at
the TLS layer so the protocol can answer "that code expired, make a new one" —
this is the first thing a user ever does with the app, and a TLS-layer drop is
indistinguishable from a broken network. An expired pairing gets no such grace:
it is simply no longer a computer this phone knows.

### The credential is issued by the desktop and is not derived from the token

A photographed QR yields a five-minute, single-use bootstrap token and never the
durable secret. The phone refuses a credential shorter than 256 bits, which is
the only moment it can.

### Storage

Android Keystore-held AES-GCM wrapping a preferences blob, fresh IV per write.
The TLS private key sits inside that wrapper as PKCS#12 rather than being a
non-extractable Keystore key.

**This is a real weakening and is recorded as such.** A hardware-backed
non-extractable EC key would be stronger. It was not used because the same TLS
code then could not run off-device, and the alternative was shipping a listener
whose only exercise was on hardware nobody has attached. The wrapper key itself
is Keystore-held and hardware-backed where the device offers it, so the
credentials are not readable from storage alone. Revisit once there is a device
to test on; the seam is `TlsIdentity`.

The Keystore key deliberately does not require user authentication: a camera
session has to survive the screen turning off, and a key needing the user
present would end the stream at the moment the phone was put down.

## What was verified, and how

Everything above is exercised by `ControlListenerTest` against the real listener
over a real TLS connection, with `TestDesktopClient` standing in for the
computer — its own identity, its own pin of the phone, the same handshake. 21
cases covering pairing, reconnect, single-use codes, expiry, revocation,
impostor keys, replayed message ids, oversized frames, unknown message types,
wrong-direction messages, and off-LAN ICE candidates.

Total suite: 97 tests.

## What was not verified

Stated plainly, because the repository's rule is that nothing is Accepted on
reasoning alone:

- **No camera sensor.** No device is attached to the development host.
- **No WebRTC session.** `WebRtcPeer` compiles against the real AAR and has
  never run; it needs `libjingle_peerconnection_so.so` and a camera.
- **No mDNS.** `CameraAdvertiser` needs Android's `NsdManager`.
- **No desktop.** The receiver does not exist, so no end-to-end video path has
  ever existed to test. `TestDesktopClient` is a test double, not a product.

The security-critical logic was deliberately pushed out of the untestable
classes for exactly this reason — candidate policy into `IceCandidateFilter`,
fingerprint shape into `SdpFingerprint`, colour conversion into `YuvConverter`,
proofs into `AuthProofs` — and each of those is tested.

## Consequences

- `protocol/fixtures/` is the cross-language contract. The Windows receiver is
  checked against those files, not against the Kotlin.
- `TestDesktopClient` is written against the protocol rather than the phone's
  classes, so it doubles as a working reference for the C++ side.
- Changing the proof construction, the label strings, or the envelope shape is a
  `Protocol.VERSION` bump.
