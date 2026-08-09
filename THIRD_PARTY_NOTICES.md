# Third-party notices

Meo is MIT licensed (see `LICENSE`). It bundles or links the third-party
components below. This file lists what actually ships inside a Meo release
artifact, plus the build-time tooling that shapes it.

`CAMERA_BUILD_PLAN.md` §16 forbids bundling GPL components in MIT binaries.
Every entry here is permissively licensed. Anything added later must be checked
against that rule before it lands.

## Android app (`android-app/`)

Ships inside the APK.

| Component | Version | License |
|---|---|---|
| AndroidX Core KTX | 1.12.0 | Apache-2.0 |
| AndroidX Lifecycle (runtime, service, viewmodel-compose, runtime-compose) | 2.6.2 | Apache-2.0 |
| AndroidX Activity Compose | 1.8.1 | Apache-2.0 |
| AndroidX CameraX (Core, Camera2, Lifecycle, View) | 1.5.3 | Apache-2.0 |
| Jetpack Compose (BOM) | 2024.02.00 | Apache-2.0 |
| Compose UI, UI Graphics, UI Tooling Preview | via BOM | Apache-2.0 |
| Compose Material 3 | via BOM | Apache-2.0 |
| Compose Material Icons Extended | via BOM | Apache-2.0 |
| kotlinx-coroutines-android | 1.7.3 | Apache-2.0 |
| ZXing Android Embedded (`com.journeyapps`) | 4.3.0 | Apache-2.0 |
| ZXing Core (transitive) | via the above | Apache-2.0 |
| Kotlin standard library | 1.9.20 | Apache-2.0 |

Test-only, not shipped: JUnit 4.13.2 (EPL-1.0), Compose UI Tooling and UI Test
Manifest (Apache-2.0, `debugImplementation` only).

## macOS app (`Sources/`)

The macOS client has **no third-party runtime dependencies**. It links only
Apple system frameworks — AVFAudio, AudioToolbox, CoreAudio, Network, SwiftUI,
AppKit, CoreImage — which are covered by the Apple SDK license and are not
redistributed by this project.

`Sources/CMeoAudio` is first-party C written for this repository.

### BlackHole

The macOS setup flow can install [BlackHole](https://github.com/ExistentialAudio/BlackHole)
(GPL-3.0) as the virtual audio device. BlackHole is **not** bundled, modified,
or linked. Meo downloads the upstream installer and hands it to the user, the
same as pointing them at the project's own download page. No BlackHole code is
combined into a Meo binary.

## Windows app (`pc-app/`)

Shipped inside the PyInstaller bundle.

| Component | Constraint | License |
|---|---|---|
| sounddevice | >=0.4.6 | MIT |
| PortAudio (bundled by sounddevice) | — | MIT |
| NumPy | >=1.24.0 | BSD-3-Clause |
| zeroconf | >=0.131.0 | LGPL-2.1 |
| customtkinter | >=5.2.0 | MIT |
| Pillow | >=10.0.0 | MIT-CMU (HPND) |
| qrcode | >=7.4.2 | BSD-3-Clause |
| psutil | >=5.9.0 | BSD-3-Clause |
| CPython runtime | — | PSF-2.0 |

**`zeroconf` is LGPL-2.1 and this needs a decision.** LGPL permits use by a
non-GPL program, but a frozen PyInstaller bundle is a form of static linking,
which triggers the LGPL relinking obligation. Today Meo satisfies it the cheap
way: the full source of the bundled app is public and the dependency is
declared and unmodified, so a user can rebuild with their own `zeroconf`.

Meo Camera's Windows host is a native C++ process (plan §9.3), so it will not
carry `zeroconf`. mDNS there will use a permissively licensed implementation or
a first-party one. Do not port this dependency into the camera host.

### VB-CABLE

The Windows setup flow points the user at
[VB-CABLE](https://vb-audio.com/Cable/), which is donationware and **not**
redistributed by this project. No VB-Audio code ships in any Meo artifact.

## Build tooling

Not shipped in any artifact; listed for reproducibility.

| Tool | Version | License |
|---|---|---|
| Gradle | 8.13 | Apache-2.0 |
| Android Gradle Plugin | 8.13.2 | Apache-2.0 |
| Kotlin Gradle Plugin | 1.9.20 | Apache-2.0 |
| Swift Package Manager / Swift toolchain | Xcode-provided | Apache-2.0 with LLVM exception |
| PyInstaller | — | GPL-2.0 with a bootloader exception permitting proprietary and non-GPL output |

## Not yet present

Meo Camera will add a WebRTC distribution (plan §5.2) and `nlohmann/json`
(§5.3). Neither is linked yet. [ADR 0008](adr/0008-android-webrtc-distribution.md)
records the measured Android spike candidate, checksum, source commit, and the
fact that its AAR omits required notices. When it lands, its generated notice
set must be versioned under `licenses/` and included in release materials. The
Windows distribution remains open. A GPL fork must not be substituted.

## Corrections

If an attribution here is wrong or missing, open an issue. This file is
maintained by hand and reviewed at each release per plan §8/§16.
