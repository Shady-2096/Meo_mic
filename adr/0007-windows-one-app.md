# ADR 0007 — One Windows app for mic and camera, with the camera engine loaded on demand

- **Status:** Accepted (design). The size question it turns on is **unmeasured**; the measurement is named below.
- **Date:** 2026-08-07
- **Plan:** §9.3, §4, §2.1, §2.2
- **Depends on:** [ADR 0005](0005-first-desktop-platform.md), [ADR 0006](0006-windows-frame-bridge.md)

## Context

§9.3 decided the Windows *camera* app is a single native C++ process, and that
the existing Python/Tkinter Meo Mic app does not survive into it. What it never
decided was whether the mic ends up in that same process.

§4 left the question deliberately open: "Meo Mic keeps working while Camera is
built. Do not rewrite the mic protocol as a prerequisite. The new secure
session layer should be designed so Mic can migrate into it later." Migration
was anticipated and never scheduled.

Left alone, that ships two Windows programs — a Python mic app and a C++ camera
app — with two tray icons, two pairing flows, two settings stores, and two
update paths for one product. §2.2 already wants "one connection screen" for a
simultaneous session, which two processes make awkward and which one process
makes ordinary.

## Decision

**One Windows application, containing both mic and camera.**

**The camera engine is not initialised, and its heavy dependency is not loaded,
until the user actually turns the camera on.** A user who only ever uses the
mic should not pay for the camera in memory, in startup time, or in the
resources the OS charges for a running media pipeline.

## What "lightweight" does and does not mean here

Worth stating precisely, because the obvious reading of this decision leads to
the wrong work.

Windows demand-pages an executable. Camera code that never runs is never
faulted into RAM; it costs disk and address space, not working set. So
splitting code apart to "avoid loading it" buys close to nothing on its own,
and structuring the whole app around that belief would be wasted effort.

What actually costs something:

| Cost | When it is paid | Deferred how |
|---|---|---|
| **WebRTC in the binary** | At link time, always — whether or not the camera is used | Put it behind a DLL loaded at runtime, not statically linked into the main executable |
| `MFStartup`, D3D11 device, decoder threads | On init | Do not init until camera start |
| Frame-bridge mapping, ~11.9 MB ([ADR 0006](0006-windows-frame-bridge.md)) | On `FrameBridgeWriter::Create` | Already explicit; call it on camera start, not app start |
| Virtual-camera registration | At install | Not a runtime cost at all |

**WebRTC is the only one large enough to change how the app is structured.**
Everything else is ordinary lazy initialisation.

## Shape

```text
MeoApp.exe              tray, UI, settings, pairing, mic path.
                        Starts without touching any camera subsystem.

MeoCameraEngine.dll     WebRTC, decode, frame normalisation, the frame-bridge
                        writer. Loaded on first camera start and not before.
                        Exposes a small C ABI so the main app never links
                        WebRTC's headers or its C++ ABI.

MeoVirtualCameraMF.dll  Already separate by construction — it is loaded by the
                        Windows frame server, in another process entirely.
                        Nothing about this ADR changes it.
```

The camera engine boundary is a plain C ABI on purpose. A C++ interface across
a DLL boundary couples both sides to one compiler and one build configuration,
and WebRTC is exactly the kind of dependency that makes that painful.

## Sequencing, which matters more than the shape

§4's warning stands and is not softened by this decision: **porting the mic
must not block the camera.** The mic app works today. Rewriting ~3,200 lines of
working Python into C++ as a prerequisite for camera work would stall the thing
this plan is actually about, and would put a working feature at risk to gain
nothing a user can see.

So:

1. The new C++ app is built around the camera first, per Milestone 5.
2. The mic is ported into it afterwards, reusing the session layer §4 asked be
   designed for exactly that migration.
3. **In the interim, the two ship side by side.** This is accepted temporary
   ugliness, not the end state, and the README should say so plainly rather
   than let users assume it is permanent.

A user running both during the interim gets two tray icons. That is worse than
the end state and better than a delayed camera.

## What is unmeasured

The size premise. Nobody has yet linked a WebRTC distribution into anything in
this project, so "tens of MB" is an expectation from its published build sizes,
not a number from this build.

**Measure it at the point WebRTC is first integrated:** build the camera engine
statically linked, record the binary size, then build it as a delay-loaded DLL
and record both the executable size and the private working set of the app at
idle with the camera off. If the delta turns out to be small, the DLL split
stops being justified by size and this ADR should be revisited rather than
honoured out of habit — the split still helps the ABI story, but that alone
would not be worth a second binary.

Per this directory's rule, that keeps this record a design decision resting on
an unverified premise, with the check written down instead of assumed.

## Consequences

- §2.2's "simultaneous Meo Camera + Meo Mic session with one connection screen"
  becomes straightforward instead of cross-process, and §11.2's audio-delay
  alignment becomes possible at all — delaying audio to match measured video
  latency needs both pipelines in one process that can see both.
- `pc-app/` (Python/Tkinter) is now explicitly a **transitional** component
  with a scheduled end, rather than an indefinitely maintained parallel app.
- The mic's UDP protocol is untouched by this. Absorbing the mic into one
  process is not permission to rewrite how it talks to the phone.
- macOS is unaffected. ADR 0004 blocks the camera there, so the Swift Meo Mic
  client keeps shipping exactly as it does now.
- Android was never in question — it is already one app, `com.meo`, which is
  why the package rename happened before any camera code landed (§4.2).
