<#
  Glass Launcher on-device benchmark (Android TV over adb). Collects the same measurements for any APK so the
  baseline and a Glass 2.0 build can be compared on the SAME device under the SAME conditions.

  Usage (Windows):  tools\bench\bench-device.bat -Label baseline -Apk C:\path\glasslauncher-baseline.apk
                    tools\bench\bench-device.bat -Label glass2   -Apk C:\path\app-debug.apk
         (pwsh):    pwsh tools/bench/bench-device.ps1 -Label glass2 -Apk app/build/outputs/apk/debug/app-debug.apk

  Without -Apk the currently installed build is measured. Results go to .\bench-<label>-<timestamp>\ (summary.json +
  raw adb output). Compare two summary.json files with: tools\bench\compare.ps1 <a>\summary.json <b>\summary.json

  What it does NOT do: it never uninstalls, disables or changes any other app or launcher. If an -Apk cannot be
  installed over the existing one (different signing key), it stops and tells you; uninstalling is your decision
  because it also clears Glass's saved settings (recently used app, theme), which changes the test conditions.
#>
param(
  [string]$Label = "current",
  [string]$Apk = "",
  [int]$Runs = 10,
  [int]$IdleSeconds = 60,
  [int]$NavRounds = 15,
  [string]$Serial = ""
)
$ErrorActionPreference = "Stop"
$Pkg = "com.glasslauncher"
$Act = "$Pkg/.MainActivity"

function Invoke-AdbCmd([string[]]$a) {
  $all = @()
  if ($Serial) { $all += @("-s", $Serial) }
  $all += $a
  $o = & adb @all 2>&1 | Out-String
  return $o
}
function Invoke-AdbShell([string]$cmd) { return Invoke-AdbCmd @("shell", $cmd) }
function Stats([double[]]$v) {
  if (-not $v -or $v.Count -eq 0) { return $null }
  $s = $v | Sort-Object
  $n = $s.Count
  $med = if ($n % 2) { $s[[int][math]::Floor($n / 2)] } else { ($s[$n / 2 - 1] + $s[$n / 2]) / 2 }
  $p95 = $s[[int][math]::Min($n - 1, [math]::Ceiling(0.95 * $n) - 1)]
  return [ordered]@{ n = $n; median = $med; p95 = $p95; min = $s[0]; max = $s[$n - 1]; samples = $v }
}
function TotalTime([string]$o) { if ($o -match "TotalTime:\s*(\d+)") { return [double]$Matches[1] } return $null }
function ProcTicks([string]$procId) {
  $st = (Invoke-AdbShell "cat /proc/$procId/stat").Trim()
  $f = ($st.Substring($st.LastIndexOf(")") + 2)).Split(" ")
  return [double]$f[11] + [double]$f[12]   # utime + stime (fields 14,15)
}
function CpuPercent([int]$seconds) {
  $procId = (Invoke-AdbShell "pidof $Pkg").Trim()
  if (-not $procId) { return $null }
  $hz = 100   # USER_HZ on Android kernels
  $t0 = ProcTicks $procId; Start-Sleep -Seconds $seconds; $t1 = ProcTicks $procId
  return [math]::Round(100.0 * ($t1 - $t0) / $hz / $seconds, 3)
}

& adb version | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path (Get-Location) "bench-$Label-$stamp"
New-Item -ItemType Directory -Path $out | Out-Null
Write-Host "Writing results to $out"

$info = [ordered]@{
  label = $Label; timestamp = $stamp
  model = (Invoke-AdbShell "getprop ro.product.model").Trim()
  android = (Invoke-AdbShell "getprop ro.build.version.release").Trim()
  sdk = (Invoke-AdbShell "getprop ro.build.version.sdk").Trim()
  abis = (Invoke-AdbShell "getprop ro.product.cpu.abilist").Trim()
  wm_size = (Invoke-AdbShell "wm size").Trim()
  wm_density = (Invoke-AdbShell "wm density").Trim()
  webview = ((Invoke-AdbShell "dumpsys webviewupdate") -split "`n" | Where-Object { $_ -match "Current WebView package" } | Select-Object -First 1)
}
if ($info.webview) { $info.webview = $info.webview.Trim() }

if ($Apk) {
  $info.apk = (Resolve-Path $Apk).Path
  $info.apk_bytes = (Get-Item $Apk).Length
  $r = Invoke-AdbCmd @("install", "-r", $Apk)
  $r | Set-Content (Join-Path $out "install.txt")
  if ($r -notmatch "Success") {
    Write-Host "Install failed:`n$r"
    Write-Host "If this is INSTALL_FAILED_UPDATE_INCOMPATIBLE the APKs are signed with different keys. Uninstall Glass yourself (adb uninstall $Pkg) if you accept losing its saved settings, then rerun."
    exit 1
  }
}
$pkgDump = Invoke-AdbShell "dumpsys package $Pkg"
$pkgDump | Set-Content (Join-Path $out "package.txt")
if ($pkgDump -match "versionName=(\S+)") { $info.versionName = $Matches[1] }
if ($pkgDump -match "primaryCpuAbi=(\S+)") { $info.primaryCpuAbi = $Matches[1] }

# Cold start: -S force-stops the process first. TotalTime is "activity launched -> first frame drawn"; the WebView
# content (home rows) appears after that, so treat this as a lower bound for "first usable frame".
$cold = @()
for ($i = 1; $i -le $Runs; $i++) {
  $o = Invoke-AdbShell "am start -S -W -n $Act"
  Add-Content (Join-Path $out "cold.txt") "--- run $i`n$o"
  $t = TotalTime $o; if ($t -ne $null) { $cold += $t }
  Start-Sleep -Seconds 4
}

# Warm start: bring Settings to the front, then return to Glass (process alive).
$warm = @()
for ($i = 1; $i -le $Runs; $i++) {
  Invoke-AdbShell "am start -W -a android.settings.SETTINGS" | Out-Null
  Start-Sleep -Seconds 2
  $o = Invoke-AdbShell "am start -W -n $Act"
  Add-Content (Join-Path $out "warm.txt") "--- run $i`n$o"
  $t = TotalTime $o; if ($t -ne $null) { $warm += $t }
  Start-Sleep -Seconds 2
}

# Settle on the home screen, then memory and idle CPU in the foreground.
Invoke-AdbShell "am start -W -n $Act" | Out-Null
Start-Sleep -Seconds 10
$mem = Invoke-AdbShell "dumpsys meminfo $Pkg"
$mem | Set-Content (Join-Path $out "meminfo.txt")
$pss = $null
if ($mem -match "TOTAL PSS:\s*(\d+)") { $pss = [int]$Matches[1] } elseif ($mem -match "(?m)^\s*TOTAL\s+(\d+)") { $pss = [int]$Matches[1] }
Write-Host "Measuring foreground idle CPU for $IdleSeconds s..."
$cpuFg = CpuPercent $IdleSeconds

# Frame pacing during a fixed D-pad sequence on the home rows.
Invoke-AdbShell "dumpsys gfxinfo $Pkg reset" | Out-Null
$keys = @("DPAD_RIGHT", "DPAD_RIGHT", "DPAD_RIGHT", "DPAD_DOWN", "DPAD_LEFT", "DPAD_LEFT", "DPAD_LEFT", "DPAD_UP")
for ($r = 0; $r -lt $NavRounds; $r++) { foreach ($k in $keys) { Invoke-AdbShell "input keyevent $k" | Out-Null; Start-Sleep -Milliseconds 150 } }
Start-Sleep -Seconds 1
$gfx = Invoke-AdbShell "dumpsys gfxinfo $Pkg"
$gfx | Set-Content (Join-Path $out "gfxinfo.txt")
$jank = [ordered]@{}
foreach ($pair in @(@("frames", "Total frames rendered:\s*(\d+)"), @("janky", "Janky frames:\s*(\d+)"), @("p50_ms", "50th percentile:\s*(\d+)ms"),
                    @("p90_ms", "90th percentile:\s*(\d+)ms"), @("p95_ms", "95th percentile:\s*(\d+)ms"), @("p99_ms", "99th percentile:\s*(\d+)ms"))) {
  if ($gfx -match $pair[1]) { $jank[$pair[0]] = [int]$Matches[1] }
}

# Background CPU: another app in front, Glass hidden behind it (Glass 2.0 pauses WebView timers here).
Invoke-AdbShell "am start -W -a android.settings.SETTINGS" | Out-Null
Start-Sleep -Seconds 5
Write-Host "Measuring background CPU for $IdleSeconds s..."
$cpuBg = CpuPercent $IdleSeconds
Invoke-AdbShell "am start -W -n $Act" | Out-Null

$summary = [ordered]@{
  device = $info
  cold_start_ms = Stats $cold
  warm_start_ms = Stats $warm
  pss_kb = $pss
  cpu_percent_foreground_idle = $cpuFg
  cpu_percent_background = $cpuBg
  idle_seconds = $IdleSeconds
  dpad_gfxinfo = $jank
  notes = "cold/warm = am start -W TotalTime; CPU = /proc/<pid>/stat utime+stime at USER_HZ=100; gfxinfo over $NavRounds rounds of an 8-key D-pad sequence"
}
$summary | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $out "summary.json")
Write-Host ($summary | ConvertTo-Json -Depth 6)
