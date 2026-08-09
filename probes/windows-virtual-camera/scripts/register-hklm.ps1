# Probe 3, case B: register the media source CLSID machine-wide under HKLM.
#
# This one DOES need elevation, and the script relaunches itself elevated,
# which is exactly the one-time UAC prompt constraint C2 permits.
#
# Only run this after trying register-hkcu.ps1 first. The interesting result
# is whether HKCU alone was enough; going straight to HKLM throws that away.

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$dll = Join-Path $root "build\out\MeoProbeSource.dll"

if (-not (Test-Path $dll)) {
  throw "Not built yet. Run .\scripts\build.ps1 first (looked for $dll)."
}

$isAdmin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()
  ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
  Write-Host "Elevating (this is the one-time UAC prompt)..." -ForegroundColor Cyan
  $elevated = Start-Process powershell.exe -Verb RunAs -Wait -PassThru -ArgumentList @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", "`"$PSCommandPath`""
  )
  exit $elevated.ExitCode
}

Write-Host "Registering $dll into HKLM..." -ForegroundColor Cyan
$registration = Start-Process -FilePath "regsvr32.exe" `
  -ArgumentList "/s `"$dll`"" -Wait -PassThru
if ($registration.ExitCode -ne 0) {
  throw "regsvr32 failed with exit code $($registration.ExitCode)"
}

$clsid = "{0B914DE5-CF52-4F35-B43D-104314D226D1}"
$key = "HKLM:\Software\Classes\CLSID\$clsid\InprocServer32"

if (Test-Path $key) {
  Write-Host "Registered. HKLM entry:" -ForegroundColor Green
  Get-ItemProperty $key | Format-List
} else {
  throw "regsvr32 reported success but $key is missing."
}

Write-Host "Now run build\out\MeoProbeHost.exe again." -ForegroundColor Yellow
