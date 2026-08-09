# ADR 0006 — Windows frame bridge: shared memory, single writer, N readers

- **Status:** Accepted (portable core); Windows mapping ownership is Blocked
- **Date:** 2026-08-07
- **Plan:** §9.3, §9.2, §8.4, §5.4, §13.1, §14, Milestone 4 gate
- **Depends on:** [ADR 0005](0005-first-desktop-platform.md)

## Context

§9.3 says the host "may use Media Foundation hardware decode and Direct3D 11
textures, then expose a bounded shared-texture or shared-memory frame pool to
both camera backends. Both backends read the **same** bridge." Milestone 4's
gate requires "a bounded frame bridge with a documented wire layout, shared by
every backend."

[`adr/README.md`](README.md) listed this record as blocked behind
[ADR 0002](0002-directshow-scope.md), on the reasoning that whether one backend
or two read the bridge changes its design.

**That dependency is dissolved rather than waited on.** Readers never mutate
the bridge, so a bridge that supports N concurrent readers is a strict superset
of one that supports a single reader, and it costs nothing extra to build — no
reader-side locks, no registration, no fan-out bookkeeping. Designing for N
means ADR 0002 can land either way without a redesign. If DirectShow turns out
to be unnecessary, nothing here is wasted; the second reader slot simply goes
unused.

## Decision

### Shared memory, not shared textures

§9.3 permits either. Shared memory wins for v1:

- Both consumers want CPU-visible pixels anyway. A DirectShow filter fills
  `IMediaSample` buffers, and an MF software source fills `IMFMediaBuffer`.
  A D3D11 shared texture would be converted straight back down.
- §13.1 requires the bridge contents to be **fuzzed**. Fuzzing a byte range is
  straightforward; fuzzing a shared D3D texture handle is not.
- §9.2 requires the DirectShow filter to be a dumb reader that never blocks and
  never allocates. Shared memory needs no device, no context, and no driver
  interaction to read — a shared texture needs all three, inside the consuming
  application's process.

A D3D11 path stays available later as a host-side optimisation for decode and
scaling. It should not reach across the process boundary.

### Bounded ring of slots, seqlock per slot

One writer (the host), N lock-free readers (MF source, DirectShow filter,
diagnostics).

- **4 slots**, each sized for the largest advertised output — 1920×1080 NV12,
  3,110,400 bytes. Total mapping ≈ 11.9 MiB.
- Writer publishes into `(latest + 1) % 4`, then stores the new index. §5.4's
  "latest-frame, stale frames dropped" falls out of this directly: a reader
  always takes the newest published slot and never walks a backlog.
- Each slot carries a **seqlock** counter — odd while being written, even when
  stable. A reader samples it, copies, samples again, and retries on mismatch.
- The ring is what makes retries rare rather than what makes the copy correct.
  At 30 FPS the writer needs 133 ms to wrap all four slots, against a ~200 µs
  copy. The seqlock is the correctness guarantee; the ring is the headroom.

Retries are **bounded** (§9.2: never block). Exhausting them returns a torn
read, which the caller treats exactly like a missing frame — it draws a slate.

### Readers trust nothing

§13.1 names "fuzzed frame-bridge contents" and §9.2 names "torn writes,
zero-length frames, format changes mid-stream, host killed under load." The
reader therefore validates, on every single read, before dereferencing
anything: magic, layout version, header size, slot count, slot stride, payload
capacity, that every derived offset lands inside the mapping, that
`latest_slot` is in range, and that `payload_bytes` both fits the slot and
matches the bytes implied by the declared format, stride, and height.

The reader **never allocates from a value it read out of shared memory.** The
caller supplies the destination buffer and its capacity.

A `payload_bytes` of zero is valid, not an error. It is how the writer
publishes a state change — Paused, phone offline — with no new pixels, so the
camera can switch to a slate without the host synthesising slate pixels itself.

### Liveness is the reader's job, and it answers §8.4

§8.4 is written for macOS, but its rule is platform-neutral and applies with
full force here: the virtual camera is a separate process with its own
lifetime, it appears in Zoom's device list whether or not the Meo host is
running, and **a camera that returns no frames because the host is closed will
hang or error inside the consuming app.**

Two independent watchdogs, both evaluated by the reader against a machine-wide
monotonic clock:

- **Producer heartbeat** — the host stamps the header on a timer even when
  idle. Older than 1500 ms means no host process. This is the "Meo isn't
  running" slate, and it is derived, never published, precisely because a dead
  process cannot publish its own death.
- **Frame age** — a published frame older than 500 ms is stale. This is §14's
  "stale frozen image mistaken for live" mitigation, and it covers the case
  where the host is alive but the phone stopped sending.

Every non-live condition therefore maps to a slate the camera backend can
render alone, with no help from the host, satisfying §8.4's requirement.

### The format on the bridge is the *output* format

§5.4: "The network stream format and the virtual-camera output format are
separate and must stay separate. The phone may drop to 540p while the virtual
camera keeps publishing a stable 720p. Renegotiating a virtual camera's media
type mid-call is the most reliable way to break Zoom."

The bridge carries the stable output format. Scaling from whatever the phone
sent happens on the host side, before publishing. Each slot still carries its
own dimensions so a reader can detect and reject a mid-stream change rather
than render garbage — but under correct operation that value never moves while
a camera is streaming.

## What was measured

The portable core — seqlock correctness, wrap behaviour, bounded retries,
validation against hostile headers, zero-length state frames, and both
watchdogs — is unit-tested and passing on macOS via a POSIX `shm_open`
mapping, because the concurrency logic is the part most likely to be wrong
and the part least dependent on the platform.

On Windows 11 Pro 25H2 build 26200.8973, elevated DebugView Global Win32
capture recorded the probe DLL in the validation host at PID 4436/session 1
and in the actual Windows frame server (`svchost.exe`) at PID 4352/session 0.
The raw observations and method are recorded in
[`RESULTS-2026-08-09.md`](../probes/windows-virtual-camera/RESULTS-2026-08-09.md).

The Win32 mapping shim was compiled and run on the same machine. The bridge,
fuzz, concurrent writer/reader, slate, and frame-source suites all passed using
`CreateFileMappingW` / `MapViewOfFile`. This validates the shim itself, but not
cross-session attachment: those tests use names inside one interactive
session.

## Resolved measurement: `Local\` cannot join these processes

The probe measured the process hosting Meo's MF source in session 0 while the
user host was in session 1. A `Local\` named section is session-scoped, so the
current `Local\` mapping name cannot connect these measured processes.

- If the source is activated in the user's own session, `Local\` works and no
  privilege is needed anywhere.
- If it is activated in session 0 under a service account, `Local\` will not
  resolve, and the mapping needs the `Global\` namespace plus an explicit DACL
  granting the frame server read access. Creating a `Global\` section requires
  `SeCreateGlobalPrivilege`, which a standard user does not hold — that would
  push a UAC prompt into the *runtime* path, not just install, which is
  materially worse than ADR 0003's worst case.

The name and its security descriptor remain confined to
`Mapping_win32.cpp`, so changing the ownership model does not alter the wire
format or readers.

## Blocked decision: who creates the cross-session mapping

The observation rules out `Local\`, but it does not by itself choose between
a `Global\` section and a broker/service-owned section. Documentation is not
accepted as measurement here.

**Required experiment:** compile the Win32 mapping shim, run the normal user
host with a standard (non-elevated) token, attempt to create the `Global\`
mapping with the intended read-only frame-server DACL, and record the exact
HRESULT/Win32 error. In the same run, verify that the session-0 probe source
can open it read-only. If standard-user creation is denied, design a
long-lived installer-created broker/service that owns the mapping; runtime
UAC is not acceptable.

## Consequences

- Milestone 4's frame-bridge gate has a documented wire layout to test against.
  `windows/MeoFrameBridge/include/meo/FrameBridge.h` is that document; the
  layout is versioned and readers reject anything else.
- The DirectShow filter, if ADR 0002 says it is needed, gets an API that
  already cannot block, cannot allocate, and cannot throw — §9.2's discipline
  is enforced by the reader's shape rather than by reviewer vigilance.
- §13.1's frame-bridge fuzzing has a defined target: the mapped bytes, with the
  reader as the harness entry point.
- The bridge is deliberately free of Windows assumptions in its core, per ADR
  0005's note that macOS may unblock later. Only the mapping is platform code.
