# ADR 0008 — Android WebRTC distribution and provenance

- **Status:** Accepted for the Android feasibility spike; Windows distribution remains open
- **Date:** 2026-08-09
- **Plan:** §5.2, §16, Milestone 0 and Milestone 3
- **Depends on:** [ADR 0001](0001-transport-and-control-plane.md)

## Context

ADR 0001 chooses WebRTC but deliberately leaves its binary distribution open.
Google's old Android page points to a JCenter artifact intended for development;
JCenter is no longer an acceptable release dependency. Building Google's tree
directly is possible, but its supported Android build host is Linux and the
checkout is roughly 16 GB before outputs. That is appropriate as a later
reproducibility path, not as the cheapest way to answer the Milestone 0 media
question.

The Android spike needs a pinned AAR with traceable source, permissive licenses,
hardware H.264 support, and no paid service.

## Decision

Use the unprefixed Maven Central artifact below for the Android feasibility
spike:

```text
io.github.webrtc-sdk:android:144.7559.09
```

Do not use a dynamic version. Do not use the package-relocated LiveKit variant;
Meo has no conflicting `org.webrtc` dependency, so relocation adds machinery
without benefit. Do not use the stripped variant until the VP8 fallback decision
has been measured on real phones.

This accepts a **candidate for the spike**, not permission to add it casually to
every APK. The dependency lands only with the first peer-connection code, its
checksum enters Gradle dependency verification, and the before/after APK sizes
are recorded in that change.

## What was measured

Downloaded from Maven Central on 2026-08-09:

```text
android-144.7559.09.aar
SHA-256 34cf91dd7497e5fe88adb76ba29ccae35db42dd6614ce548b79ce037b6d634d5
```

The computed digest exactly matched Maven Central's published `.sha256`.

The 47 MiB AAR contains:

| Entry | Uncompressed size |
|---|---:|
| `classes.jar` (`org.webrtc`, 426 classes) | 1,050,661 bytes |
| ARMv7 native library | 6,828,340 bytes |
| ARM64 native library | 12,092,568 bytes |
| x86 native library | 12,613,568 bytes |
| x86-64 native library | 16,064,080 bytes |

The manifest declares `minSdkVersion=21`, below both Meo Mic's API 24 floor and
Meo Camera's API 29 floor. The classes include `PeerConnectionFactory`,
`HardwareVideoEncoderFactory`, `Camera2Capturer`, `VideoSource`, and
`VideoTrack`, which are the APIs the spike needs.

The wrapper tag `v144.7559.09` maps the binary to
`webrtc-sdk/webrtc@b1800a61db8320af5c14456c13622d8b85b1ed39`. Its publish
workflow downloads the tagged release AAR, checks the expected files, signs the
Maven publication, and publishes it to Sonatype. Maven's POM declares
BSD-3-Clause. The tagged wrapper repository is MIT and includes
`Licenses/WEBRTC.md`, whose first entry is WebRTC's BSD-3-Clause notice followed
by the generated third-party notices.

One important negative result: **the AAR itself contains no license or notice
file**. Maven metadata alone does not satisfy binary-redistribution notices.

## Consequences

- When the dependency lands, copy the tag's generated WebRTC/third-party notice
  into a versioned `licenses/` file, link it from `THIRD_PARTY_NOTICES.md`, and
  include it in release materials. Updating the coordinate without updating the
  notice and source commit is a release failure.
- The current release APK is about 12 MiB unsigned. This AAR carries four ABIs,
  while Meo currently emits one universal APK. The integration change must
  measure the real compressed delta and explicitly decide whether release builds
  keep emulator-only x86 ABIs. This ADR does not guess.
- The capture service remains the lifecycle owner. Integration must choose
  between feeding CameraX analysis frames into a custom `VideoCapturer` and
  replacing CameraX with WebRTC's `Camera2Capturer`; that choice is measured by
  720p30 CPU, thermal, and control support, not by API convenience.
- Updates are manual and pinned. Review the upstream commit mapping, Maven
  checksum, generated license set, ABI list, and spike tests before changing the
  version.
- This does not close the full Milestone 0 distribution gate. The Windows x64
  WebRTC build, its provenance, license bundle, ABI boundary, and update policy
  still need their own measured decision.

## Reproduction

Primary sources used for the decision:

- <https://github.com/webrtc-sdk/android/tree/v144.7559.09>
- <https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/144.7559.09/>
- <https://webrtc.googlesource.com/src/+/main/tools_webrtc/android/build_aar.py>

Re-run the artifact check with a temporary directory; do not commit the AAR:

```bash
curl -fLO https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/144.7559.09/android-144.7559.09.aar
curl -fLO https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/144.7559.09/android-144.7559.09.aar.sha256
shasum -a 256 android-144.7559.09.aar
unzip -l android-144.7559.09.aar
```
