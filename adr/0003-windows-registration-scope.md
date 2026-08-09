# ADR 0003 — `HKCU` or `HKLM` for the Media Foundation source?

- **Status:** **Accepted.** Machine-wide COM registration is required.
- **Date opened:** 2026-08-07
- **Date accepted:** 2026-08-09
- **Plan:** §9.4, §9.6, §18 step 3
- **Resolved by:** running [`probes/windows-virtual-camera`](../probes/windows-virtual-camera/), registering per-user first

## Context

The Windows frame server is a separate service running under a different
account from the user. Meo's media source is an in-process COM object the
frame server activates by CLSID. Whether the frame server can find a CLSID
registered only under the *user's* `HKCU\Software\Classes` is an open
question that §9.4 explicitly refuses to guess at.

The stakes are one UAC prompt:

- **`HKCU` works** → Meo installs entirely per-user with no elevation
  anywhere. Combined with §5.1's outbound-only network design (no firewall
  rule, no prompt), install becomes genuinely friction-free.
- **`HKLM` required** → one UAC prompt at install. Constraint C2 permits this
  — the constraint is money, not friction — but it is worse, and it is worth
  knowing rather than assuming.

Note that §9.5 expects the DirectShow filter, if it is needed at all, to
register cleanly per-user regardless, since `HKCU\Software\Classes` merges
into `HKEY_CLASSES_ROOT` for the app doing the loading. That is a different
mechanism from frame-server activation and does not answer this.

## Measurement

The probe was run on Windows 11 Pro 25H2, build 26200.8973, in the required
order. Full observations are recorded in
[`RESULTS-2026-08-09.md`](../probes/windows-virtual-camera/RESULTS-2026-08-09.md).

- Per-user registration completed without UAC.
- With only the HKCU registration, `MFCreateVirtualCamera` returned `S_OK`,
  but `IMFVirtualCamera::Start` returned `0x80070003`
  (`ERROR_PATH_NOT_FOUND`). No usable camera was published.
- After machine-wide registration (one UAC prompt), `Start` returned `S_OK`
  and `MFEnumDeviceSources` listed
  `Meo Camera Probe (Windows Virtual Camera)`.
- Elevated Global Win32 DebugView capture independently confirmed that the
  DLL was loaded by the frame server in session 0, outside the interactive
  user's session.

## Decision

Register the Media Foundation source under HKLM. The installer must request
elevation once and explain why; runtime camera use must not request elevation.

## How to answer it

The order matters. `scripts/register-hkcu.ps1` **first**, then run
`MeoProbeHost.exe`:

- `Virtual camera STARTED` → `HKCU` is sufficient. Done.
- `MFCreateVirtualCamera` succeeds but `Start()` fails → the frame server
  could not activate the CLSID. Then unregister, run
  `scripts/register-hklm.ps1`, and retry.

Going straight to `HKLM` destroys the result, because a machine-wide
registration also satisfies a per-user lookup. Record the HRESULT either way;
"it didn't work" is not an answer an installer can be designed against.

## Related things the same run should capture

- Whether `MFCreateVirtualCamera` returns `E_ACCESSDENIED`. §9.4 requires the
  Windows camera privacy setting to surface as an actionable message rather
  than a generic failure, and that path needs to be seen at least once.
- Whether the machine is Windows 10, where `MFCreateVirtualCamera` does not
  exist at all. That is a legitimate result and part of why §9.5 exists.

## Consequences

- The Windows installer owns machine-wide COM registration and removal.
- A portable ZIP cannot install the camera without a one-time elevated setup
  action. User-facing installation documentation must say this up front.
- This does not permit elevation during ordinary camera use.
