# ADR 0002 — Is a DirectShow backend mandatory on Windows?

- **Status:** **Blocked.** Nothing is decided here yet.
- **Date opened:** 2026-08-07
- **Plan:** §9.1, §9.2, §9.5, §18 step 1
- **Resolved by:** running [`probes/windows-virtual-camera`](../probes/windows-virtual-camera/) and filling in its results template

## Context

§9.1 calls this the most consequential correction to the previous plan. An
application only sees a camera created by `MFCreateVirtualCamera` if that
application enumerates through the Windows frame server. Applications that
enumerate through **DirectShow** — historically including OBS's classic Video
Capture Device source, and possibly Zoom — will not see one at all.

The size of the entire Windows effort turns on this. §9.2 is blunt about why
it is not a small addition: a DirectShow source filter is a COM DLL loaded
**in-process by the consuming application**. A null dereference in Meo's
filter crashes the user's Zoom call, and it looks like Zoom's fault. It is the
highest-blast-radius code in the project, and it carries its own fuzzing and
host-kill test suite.

So the difference between "mandatory" and "unnecessary" here is roughly the
difference between two Windows backends and one, plus a hardened test regime.

## Why this is still open

The registration and frame-server portions of the probe were run on Windows
11 on 2026-08-09, but the required application matrix has not been measured.
The partial results are recorded in
[`RESULTS-2026-08-09.md`](../probes/windows-virtual-camera/RESULTS-2026-08-09.md).

Answering this by reading documentation is explicitly not good enough. §9.1
requires "a recorded observation of which API each app uses on each OS",
because the previous plan's framing was wrong precisely by reasoning instead
of measuring.

## What has to be recorded

For each of Zoom, Discord, Chrome/Meet, Edge/Meet, Teams, OBS (both the
classic and V2 capture sources), and the Windows Camera app, with an MF-only
virtual camera running:

1. Does the camera appear in that app's device list?
2. Does selecting it produce **moving** frames?
3. The exact friendly-name string shown (this also feeds §1.1).

The template is `probes/windows-virtual-camera/RESULTS-TEMPLATE.md`.

## The decision this will produce

One of:

- **Mandatory** — at least one required app cannot see an MF-only camera.
  §9.5 proceeds in full, with §9.2's minimal-filter discipline, and Milestone
  6 grows a fuzz and host-kill suite.
- **Optional** — every required app sees it, but a valued non-required one
  does not. DirectShow becomes post-1.0.
- **Unnecessary** — everything sees it. §9.5 is deleted and the Windows path
  is roughly half the size it currently looks.

## Consequences of leaving it open

The Windows frame-bridge ADR cannot be written, because whether one backend or
two read the bridge changes its design. Milestone 6 cannot be scoped. Any
estimate of the Windows path is currently a guess with a factor-of-two range.
