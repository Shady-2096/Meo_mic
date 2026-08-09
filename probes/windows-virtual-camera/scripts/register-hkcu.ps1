# Probe 3, case A: register the media source CLSID under HKCU only.
#
# Run from a NORMAL PowerShell. If this needs elevation, something is wrong —
# the whole point is that per-user COM registration does not.
#
# After this, run MeoProbeHost.exe. If the virtual camera starts and shows
# frames, the Windows frame server CAN activate an HKCU-registered source and
# Meo installs with zero UAC prompts. If Start() fails, HKLM is required and
# install costs one UAC prompt (constraint C2 allows that).

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$dll = Join-Path $root "build\out\MeoProbeSource.dll"

if (-not (Test-Path $dll)) {
  throw "Not built yet. Run .\scripts\build.ps1 first (looked for $dll)."
}

Write-Host "Registering $dll into HKCU..." -ForegroundColor Cyan

# /n suppresses DllRegisterServer; /i:user routes to DllInstall with "user",
# which writes HKCU\Software\Classes instead of HKLM.
$registration = Start-Process -FilePath "regsvr32.exe" `
  -ArgumentList "/n /i:user /s `"$dll`"" -Wait -PassThru
if ($registration.ExitCode -ne 0) {
  throw "regsvr32 failed with exit code $($registration.ExitCode)"
}

$clsid = "{0B914DE5-CF52-4F35-B43D-104314D226D1}"
$key = "HKCU:\Software\Classes\CLSID\$clsid\InprocServer32"

if (Test-Path $key) {
  Write-Host "Registered. HKCU entry:" -ForegroundColor Green
  Get-ItemProperty $key | Format-List
} else {
  throw "regsvr32 reported success but $key is missing."
}

Write-Host @"

Now run:  $root\build\out\MeoProbeHost.exe

Record in RESULTS-TEMPLATE.md whether Start() succeeded with HKCU-only
registration. That single yes/no is Probe 3.
"@ -ForegroundColor Yellow
