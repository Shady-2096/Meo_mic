# Meo Camera — Windows

Windows is the first desktop platform ([ADR 0005](../adr/0005-first-desktop-platform.md)),
by elimination: [ADR 0004](../adr/0004-macos-distribution-reality.md) measured
that macOS cannot install a camera extension at all under the zero-budget
constraint, so Windows is the only platform where Milestone 5 can be attempted.

Unlike `probes/`, this is product code.

## What is here now

| Component | State |
|---|---|
| `MeoFrameBridge/` | Complete and tested. The shared-memory hand-off from the host to every camera backend ([ADR 0006](../adr/0006-windows-frame-bridge.md)). |
| `MeoVirtualCameraMF/src/Slate.*` | Complete and tested. Every non-live state the camera draws for itself (§8.4). |
| `MeoVirtualCameraMF/src/FrameSource.*` | Complete and tested. The live-frame-or-slate decision both backends share. |
| `Tests/` | 3 suites, run on Windows and on macOS. |

## What is not here yet

- **The COM media source.** `MFCreateVirtualCamera` (§9.4) and the
  `IMFMediaSource` / `IMFMediaStream2` plumbing around `CameraFrameSource`.
  Nothing blocks it.
- **`MeoApp/` and `MeoCameraEngine/`.** The receiver, decode, and native UI
  (§9.3). Milestone 4 work; the bridge is the side of it that already exists.
  [ADR 0007](../adr/0007-windows-one-app.md) makes this one app that also
  contains the mic, with the camera engine — WebRTC and decode — behind a DLL
  loaded only when the camera is switched on. Until the mic is ported into it,
  `pc-app/` ships alongside; that is temporary and scheduled, not the end
  state.
- **`MeoVirtualCameraDS/`.** Only if [ADR 0002](../adr/0002-directshow-scope.md)
  says DirectShow is mandatory. That is still unmeasured.
- **`MeoInstaller/`.** Waits on [ADR 0003](../adr/0003-windows-registration-scope.md),
  which decides whether a one-time UAC prompt is part of install.

## Building and testing

```bash
cmake -S . -B build && cmake --build build && ctest --test-dir build --output-on-failure
```

That works on Windows with Visual Studio 2022 and on macOS with clang. There
are no third-party dependencies, deliberately: the code these tests cover is
loaded inside the Windows frame server and — if ADR 0002 says so — inside the
user's Zoom process, and it should be buildable with nothing but a compiler.

The tests are also worth running under a sanitizer, since the reader's whole
job is to survive hostile input:

```bash
clang++ -std=c++17 -O1 -g -fsanitize=address,undefined -I MeoFrameBridge/include -I MeoFrameBridge/src -I MeoVirtualCameraMF/src -o /tmp/fb MeoFrameBridge/src/FrameBridge.cpp MeoFrameBridge/src/Mapping_posix.cpp MeoVirtualCameraMF/src/Slate.cpp MeoVirtualCameraMF/src/FrameSource.cpp Tests/FrameBridgeTests.cpp && /tmp/fb
```

## Why the core builds on macOS

Not portability. The product is Windows-only until ADR 0004 unblocks.

The seqlock, the ring wrap, the hostile-input validation, and the two liveness
watchdogs are the parts most likely to be wrong and the parts least dependent
on the platform. Keeping them free of Win32 means they can be fuzzed and
race-tested on the development machine instead of only on a Windows box —
`Mapping_posix.cpp` exists for that and ships nowhere.

Only `Mapping_win32.cpp` is platform code, and it is a thin shim over
`CreateFileMappingW` / `MapViewOfFile`. **It has not been compiled or run.**

## What the tests actually assert

The interesting ones, since a test suite that only checks the happy path would
not be worth the lines:

- **The seqlock holds under a real race.** A writer thread publishes 720p
  frames as fast as it can while a reader races it; every byte of every
  accepted frame must match the frame id in its own metadata. Deleting the
  post-copy re-check makes this fail immediately, which is how we know the test
  has teeth.
- **A fuzzed mapping never crashes the reader.** 20,000 iterations, each
  restoring a pristine snapshot and injecting one to three byte faults into the
  header and slot metadata. Roughly half still classify as live, and every one
  of those must be internally consistent — that is the case where a reader gets
  tricked into reading past the end of a slot.
- **A dead host becomes a slate, not a frozen face.** Both watchdogs, plus the
  recovery path when the host comes back with no restart of the consuming app.
- **Stride padding is never touched**, in both planes. Writing into it is what
  produces the skewed diagonal picture the probe README warns about.

## The one thing to measure on Windows

ADR 0006 has an open sub-question: whether the process hosting Meo's media
source can open a `Local\`-namespace section created by the user's host
process, or whether the frame server runs in another session and needs
`Global\` — which would push a UAC prompt into the runtime path.

The existing `probes/windows-virtual-camera` already loads a Meo DLL inside the
frame server, so logging `GetCurrentProcessId` and `ProcessIdToSessionId` from
its `DllMain` and reading it in DebugView answers it in the same session that
answers ADR 0002 and ADR 0003.
