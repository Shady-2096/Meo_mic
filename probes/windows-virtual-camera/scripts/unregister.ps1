# Removes the probe from both hives and deletes any leftover system-lifetime
# virtual camera.
#
# Plan 9.6 requires that uninstall leave no orphan COM entries, so a probe
# that cannot clean itself up is a probe that teaches the wrong habit. Run
# this when you are done measuring.

$ErrorActionPreference = "Continue"

$root = Split-Path -Parent $PSScriptRoot
$dll = Join-Path $root "build\out\MeoProbeSource.dll"
$host_exe = Join-Path $root "build\out\MeoProbeHost.exe"
$clsid = "{0B914DE5-CF52-4F35-B43D-104314D226D1}"

if (Test-Path $host_exe) {
  Write-Host "Removing any system-lifetime virtual camera..." -ForegroundColor Cyan
  & $host_exe --lifetime system --remove
}

if (Test-Path $dll) {
  Write-Host "Unregistering from HKCU..." -ForegroundColor Cyan
  Start-Process -FilePath "regsvr32.exe" `
    -ArgumentList "/n /u /i:user /s `"$dll`"" -Wait
}

Remove-Item -Path "HKCU:\Software\Classes\CLSID\$clsid" -Recurse -Force `
  -ErrorAction SilentlyContinue

$isAdmin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()
  ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (Test-Path "HKLM:\Software\Classes\CLSID\$clsid") {
  if ($isAdmin) {
    Write-Host "Unregistering from HKLM..." -ForegroundColor Cyan
    if (Test-Path $dll) {
      Start-Process -FilePath "regsvr32.exe" `
        -ArgumentList "/u /s `"$dll`"" -Wait
    }
    Remove-Item -Path "HKLM:\Software\Classes\CLSID\$clsid" -Recurse -Force `
      -ErrorAction SilentlyContinue
  } else {
    Write-Warning "HKLM registration is still present. Re-run this script elevated to remove it."
  }
}

Write-Host ""
Write-Host "Remaining traces (should be empty):" -ForegroundColor Cyan
foreach ($hive in @("HKCU", "HKLM")) {
  $path = "${hive}:\Software\Classes\CLSID\$clsid"
  if (Test-Path $path) { Write-Warning "  still present: $path" }
}
Write-Host "Done." -ForegroundColor Green
