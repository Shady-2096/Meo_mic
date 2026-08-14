# Vendored license notices

Binary redistribution obligations that cannot be satisfied by a dependency
coordinate alone. Each file here is copied verbatim from a pinned upstream tag
and is versioned in its filename, so a release artifact can always be matched to
the exact notice set that applies to it.

`THIRD_PARTY_NOTICES.md` is the index. This directory is the evidence.

## `webrtc-sdk-android-144.7559.09-*`

Covers `io.github.webrtc-sdk:android:144.7559.09`, the media-plane dependency of
the Android app ([ADR 0008](../adr/0008-android-webrtc-distribution.md)).

[ADR 0008](../adr/0008-android-webrtc-distribution.md) recorded a negative
result that makes these files mandatory: **the AAR itself contains no license or
notice file.** Maven POM metadata declaring BSD-3-Clause does not satisfy the
notice requirements of the code actually shipping inside the APK, which includes
WebRTC and its own bundled third-party components.

| File | Source | Covers |
|---|---|---|
| `...-NOTICES.md` | `Licenses/WEBRTC.md` at tag `v144.7559.09` | WebRTC's BSD-3-Clause notice followed by the generated third-party notice set for everything vendored inside the WebRTC tree |
| `...-WRAPPER-MIT.txt` | `LICENSE` at tag `v144.7559.09` | The `webrtc-sdk/android` wrapper repository that builds and publishes the artifact |

Provenance, re-verified on 2026-08-14:

```text
io.github.webrtc-sdk:android:144.7559.09
android-144.7559.09.aar
SHA-256 34cf91dd7497e5fe88adb76ba29ccae35db42dd6614ce548b79ce037b6d634d5
wrapper tag  v144.7559.09
upstream     webrtc-sdk/webrtc@b1800a61db8320af5c14456c13622d8b85b1ed39
```

The computed digest matched both Maven Central's published `.sha256` and the
digest ADR 0008 recorded on 2026-08-09.

## When the WebRTC version changes

All four of these move together, and a change that does only some of them is a
release failure:

1. The coordinate in `android-app/app/build.gradle.kts`.
2. The checksum in `android-app/gradle/verification-metadata.xml`.
3. These notice files, re-fetched from the new tag and renamed to the new
   version.
4. The version and digest recorded in ADR 0008 and `THIRD_PARTY_NOTICES.md`.

Do not commit the AAR itself. It is 47 MiB and Gradle fetches it reproducibly
from the pinned coordinate and verified checksum.
