<#
  Glass Launcher on-device benchmark (Android TV over adb), harness v2. Measures any APK the same way so the baseline
  and a Glass 2.0 build can be compared on the SAME device under the SAME conditions.

  Usage (Windows):  tools\bench\bench-device.bat -Label baseline -Apk C:\path\glasslauncher-baseline.apk -CompileSpeed
                    tools\bench\bench-device.bat -Label glass2   -Apk C:\path\app-debug.apk            -CompileSpeed
         (pwsh):    pwsh tools/bench/bench-device.ps1 -Label glass2 -Apk app/build/outputs/apk/debug/app-debug.apk
  Compare:          powershell -ExecutionPolicy Bypass -File tools\bench\compare.ps1 <a>\summary.json <b>\summary.json

  What v2 measures and how (see docs/benchmarks/METHODOLOGY.md):
    cold start   am start -S -W, kept only when LaunchState=COLD; logcat "Displayed" recorded as a cross-check.
    warm start   a full-screen app is opened first (-BackgroundPackage, default: first installed of YouTube / Netflix /
                 Play Store), Glass must be verified STOPPED and its process alive, then am start -W; LaunchState kept.
                 Samples that fail these checks are rejected and listed, never averaged in.
    memory       dumpsys meminfo <pid>, 3 samples, app process AND WebView renderer process(es).
    CPU          /proc/<pid>/stat utime+stime for the app AND renderer process(es), 3 windows each.
    frames       gfxinfo over a scripted D-pad sequence AND over an idle window of the same length (control), plus
                 framestats, histogram and the display refresh rate. These are rendering frame times, not input latency.
    -Trace       optional Perfetto trace of a short D-pad sequence for real input-to-frame analysis (ui.perfetto.dev).

  It never uninstalls, disables or force-stops any app other than Glass. -CompileSpeed runs
  `cmd package compile -m speed -f com.glasslauncher` so both builds are measured with the same ART compilation state.
#>
param(
  [string]$Label = "current",
  [string]$Apk = "",
  [int]$Runs = 10,
  [int]$IdleSeconds = 60,
  [int]$NavRounds = 15,
  [int]$KeyIntervalMs = 500,
  [string]$BackgroundPackage = "",
  [switch]$CompileSpeed,
  [switch]$Trace,
  [string]$Serial = ""
)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "bench-lib.ps1")
$Pkg = "com.glasslauncher"
$Act = "$Pkg/.MainActivity"
$warnings = New-Object System.Collections.ArrayList

function Invoke-AdbCmd([string[]]$a) {
  $all = @()
  if ($Serial) { $all += @("-s", $Serial) }
  $all += $a
  # Windows PowerShell 5.1 turns native stderr into terminating errors under Stop; collect it as text instead.
  $old = $ErrorActionPreference; $ErrorActionPreference = "Continue"
  try { $o = (& adb @all 2>&1 | ForEach-Object { "$_" }) -join "`n" } finally { $ErrorActionPreference = $old }
  return $o
}
function Invoke-AdbShell([string]$cmd) { return Invoke-AdbCmd @("shell", $cmd) }
function Save([string]$name, [string]$text) { Add-Content -Path (Join-Path $out $name) -Value $text }
function Warn([string]$m) { [void]$warnings.Add($m); Write-Warning $m }
function GlassPid() { return (Invoke-AdbShell "pidof $Pkg").Trim() }
function RendererPids() { return @(Get-RendererPids (Invoke-AdbShell "ps -A -o PID,ARGS")) }
function GlassState() { return Get-ActivityState (Invoke-AdbShell "dumpsys activity activities") $Act }

function CpuWindow([int]$seconds, [string[]]$pids) {
  $t0 = @{}; foreach ($p in $pids) { $t0[$p] = Get-ProcTicks (Invoke-AdbShell "cat /proc/$p/stat") }
  Start-Sleep -Seconds $seconds
  $res = [ordered]@{}
  foreach ($p in $pids) {
    $t1 = Get-ProcTicks (Invoke-AdbShell "cat /proc/$p/stat")
    if ($t0[$p] -ne $null -and $t1 -ne $null) { $res[$p] = [math]::Round(100.0 * ($t1 - $t0[$p]) / 100 / $seconds, 3) } else { $res[$p] = $null }  # USER_HZ=100
  }
  return $res
}
function CpuMeasure([string]$name) {
  $app = GlassPid
  if (-not $app) { Warn "$name CPU: Glass process not running"; return $null }
  $rend = RendererPids
  $pids = @($app) + @($rend | ForEach-Object { $_.pid })
  $win = [math]::Max(5, [int]($IdleSeconds / 3))
  $appS = @(); $rendS = @()
  for ($w = 0; $w -lt 3; $w++) {
    $r = CpuWindow $win $pids
    Save "cpu-$name.txt" ("window " + $w + ": " + ($r | ConvertTo-Json -Compress))
    if ($r[$app] -ne $null) { $appS += $r[$app] }
    $sum = 0.0; $okR = $true
    foreach ($x in $rend) { if ($r[$x.pid] -eq $null) { $okR = $false } else { $sum += $r[$x.pid] } }
    if ($okR -and $rend.Count) { $rendS += $sum }
  }
  return [ordered]@{ window_seconds = $win; app_process_pct = (Get-Stats $appS)
                     renderer_processes = @($rend | ForEach-Object { $_.name }); renderer_pct = (Get-Stats $rendS)
                     total_pct_median = $(if ($rendS.Count) { [math]::Round((Get-Stats $appS).median + (Get-Stats $rendS).median, 3) } else { (Get-Stats $appS).median }) }
}
function RunNav([int]$rounds) {
  # invariant culture: a comma-decimal Windows locale would otherwise send "sleep 0,5" to the device
  $sleep = ($KeyIntervalMs / 1000.0).ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
  $cmd = "for r in `$(seq 1 $rounds); do for k in 22 22 22 20 21 21 21 19; do input keyevent `$k; sleep $sleep; done; done"
  $sw = [Diagnostics.Stopwatch]::StartNew()
  Invoke-AdbShell $cmd | Out-Null
  $sw.Stop()
  return $sw.Elapsed.TotalSeconds
}
function PickBackground() {
  if ($BackgroundPackage) { return $BackgroundPackage }
  foreach ($p in @("com.google.android.youtube.tv", "com.netflix.ninja", "com.android.vending")) {
    if ((Invoke-AdbShell "pm path $p") -match "package:") { return $p }
  }
  return $null
}
function OpenBackground([string]$bg) {
  $o = Invoke-AdbShell "monkey -p $bg -c android.intent.category.LEANBACK_LAUNCHER 1"
  if ($o -match "No activities found") { $o = Invoke-AdbShell "monkey -p $bg -c android.intent.category.LAUNCHER 1" }
  return $o
}

& adb version | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path (Get-Location) "bench-$Label-$stamp"
New-Item -ItemType Directory -Path $out | Out-Null
Write-Host "Writing results to $out"

# ---------- device and conditions ----------
$displayDump = Invoke-AdbShell "dumpsys display"; Save "display.txt" $displayDump
$sfDump = Invoke-AdbShell "dumpsys SurfaceFlinger"; Save "surfaceflinger.txt" $sfDump
$refresh = Get-RefreshHz $displayDump $sfDump
$hour = 0; [void][int]::TryParse((Invoke-AdbShell "date +%H").Trim(), [ref]$hour)
$info = [ordered]@{
  label = $Label; timestamp = $stamp; harness_version = 2
  model = (Invoke-AdbShell "getprop ro.product.model").Trim()
  android = (Invoke-AdbShell "getprop ro.build.version.release").Trim()
  sdk = (Invoke-AdbShell "getprop ro.build.version.sdk").Trim()
  abis = (Invoke-AdbShell "getprop ro.product.cpu.abilist").Trim()
  wm_size = (Invoke-AdbShell "wm size").Trim()
  wm_density = (Invoke-AdbShell "wm density").Trim()
  refresh_hz = $refresh
  frame_deadline_ms = $(if ($refresh) { [math]::Round(1000.0 / $refresh, 2) } else { $null })
  device_time = (Invoke-AdbShell "date").Trim()
  expected_theme = (Get-ExpectedTheme $hour)
  webview = ((Invoke-AdbShell "dumpsys webviewupdate") -split "`n" | Where-Object { $_ -match "Current WebView package" } | Select-Object -First 1)
}
if ($info.webview) { $info.webview = $info.webview.Trim() }
if (-not $refresh) { Warn "Display refresh rate not found in dumpsys display/SurfaceFlinger; jank thresholds cannot be checked" }

if ($Apk) {
  $info.apk = (Resolve-Path $Apk).Path
  $info.apk_bytes = (Get-Item $Apk).Length
  $r = Invoke-AdbCmd @("install", "-r", $Apk)
  Save "install.txt" $r
  if ($r -notmatch "Success") {
    Write-Host "Install failed:`n$r"
    Write-Host "INSTALL_FAILED_UPDATE_INCOMPATIBLE means the APKs are signed with different keys (each CI run signs debug APKs with a new key). Uninstall Glass yourself (adb uninstall $Pkg) if you accept losing its saved settings, then rerun - and do the same for the other build so both start from the same state."
    exit 1
  }
}
if ($CompileSpeed) { Save "compile.txt" (Invoke-AdbShell "cmd package compile -m speed -f $Pkg") }
$pkgDump = Invoke-AdbShell "dumpsys package $Pkg"; Save "package.txt" $pkgDump
if ($pkgDump -match "versionName=(\S+)") { $info.versionName = $Matches[1] }
if ($pkgDump -match "primaryCpuAbi=(\S+)") { $info.primaryCpuAbi = $Matches[1] }
if ($pkgDump -match "lastUpdateTime=(.+)") { $info.lastUpdateTime = $Matches[1].Trim() }
$info.dexopt_status = Get-DexoptStatus $pkgDump

# ---------- cold start ----------
$cold = @(); $coldRows = @()
for ($i = 1; $i -le $Runs; $i++) {
  Invoke-AdbCmd @("logcat", "-c") | Out-Null
  $o = Invoke-AdbShell "am start -S -W -n $Act"
  Start-Sleep -Seconds 4
  $disp = Get-DisplayedMs (Invoke-AdbCmd @("logcat", "-d", "-s", "ActivityTaskManager:I", "ActivityManager:I")) $Act
  $a = ConvertFrom-AmStart $o
  $valid = ($a.status -eq "ok" -and $a.launch_state -eq "COLD" -and $a.total_ms -gt 0)
  $coldRows += [ordered]@{ run = $i; am = $a; displayed_ms = $disp; valid = $valid }
  Save "cold.txt" "--- run $i (valid=$valid displayed_ms=$disp)`n$o"
  if ($valid) { $cold += $a.total_ms }
}

# ---------- warm start ----------
$bg = PickBackground
$warm = @(); $warmRows = @()
if (-not $bg) { Warn "No full-screen background app found; pass -BackgroundPackage <pkg>. Warm start skipped." }
else {
  $info.background_package = $bg
  for ($i = 1; $i -le $Runs; $i++) {
    Invoke-AdbShell "am start -W -n $Act" | Out-Null
    Start-Sleep -Seconds 2
    $pidBefore = GlassPid
    Save "warm.txt" ("--- run $i background launch`n" + (OpenBackground $bg))
    Start-Sleep -Seconds 5
    $st = GlassState
    Invoke-AdbCmd @("logcat", "-c") | Out-Null
    $o = Invoke-AdbShell "am start -W -n $Act"
    Start-Sleep -Seconds 2
    $disp = Get-DisplayedMs (Invoke-AdbCmd @("logcat", "-d", "-s", "ActivityTaskManager:I", "ActivityManager:I")) $Act
    $pidAfter = GlassPid
    $a = ConvertFrom-AmStart $o
    $why = @()
    if ($a.status -ne "ok") { $why += "status=$($a.status)" }
    if (-not ($a.total_ms -gt 0)) { $why += "no TotalTime" }
    if ($st.state -ne "STOPPED") { $why += "Glass was $($st.state) (not STOPPED) behind $bg" }
    if (-not $pidBefore -or $pidBefore -ne $pidAfter) { $why += "process changed ($pidBefore -> $pidAfter)" }
    if ($a.launch_state -eq "COLD") { $why += "LaunchState=COLD" }
    $valid = ($why.Count -eq 0)
    $warmRows += [ordered]@{ run = $i; am = $a; displayed_ms = $disp; glass_state_before = $st.state; glass_visible_before = $st.visible; valid = $valid; rejected_because = $why }
    Save "warm.txt" "--- run $i (valid=$valid state_before=$($st.state) pid $pidBefore->$pidAfter displayed_ms=$disp reasons=$($why -join '; '))`n$o"
    if ($valid) { $warm += $a.total_ms }
  }
}

# ---------- settle on home ----------
Invoke-AdbShell "am start -W -n $Act" | Out-Null
Start-Sleep -Seconds 10

# ---------- memory ----------
$memApp = @(); $memRend = @(); $memErr = @()
for ($s = 1; $s -le 3; $s++) {
  $app = GlassPid
  $mo = Invoke-AdbShell "dumpsys meminfo $app"; Save "meminfo.txt" "--- sample $s app pid $app`n$mo"
  $m = ConvertFrom-Meminfo $mo
  if ($m.pss_kb) { $memApp += $m.pss_kb } else { $memErr += "app: $($m.error)" }
  $sum = 0; $ok = $true; $rend = RendererPids
  foreach ($x in $rend) {
    $ro = Invoke-AdbShell "dumpsys meminfo $($x.pid)"; Save "meminfo.txt" "--- sample $s renderer $($x.pid) $($x.name)`n$ro"
    $rm = ConvertFrom-Meminfo $ro
    if ($rm.pss_kb) { $sum += $rm.pss_kb } else { $ok = $false; $memErr += "renderer $($x.pid): $($rm.error)" }
  }
  if ($ok -and $rend.Count) { $memRend += $sum }
  Start-Sleep -Seconds 5
}
foreach ($e in ($memErr | Select-Object -Unique)) { Warn "meminfo: $e" }
$rendNow = RendererPids
if ($rendNow.Count -gt 1) { Warn "More than one WebView renderer process is running ($(($rendNow | ForEach-Object { $_.name }) -join ', ')); renderer CPU/PSS may include another app's WebView" }
$memory = [ordered]@{ app_pss_kb = (Get-Stats $memApp); renderer_pss_kb = (Get-Stats $memRend)
                      renderer_processes = @($rendNow | ForEach-Object { $_.name })
                      webview_mode = $(if ($rendNow.Count) { "multiprocess (renderer in a separate process)" } else { "single-process (no renderer process found)" }) }

# ---------- foreground idle CPU ----------
Write-Host "Measuring foreground idle CPU (3 x $([math]::Max(5, [int]($IdleSeconds / 3))) s)..."
$cpuFg = CpuMeasure "foreground"

# ---------- frames: D-pad sequence vs idle control ----------
Invoke-AdbShell "dumpsys gfxinfo $Pkg reset" | Out-Null
$navSec = RunNav $NavRounds
$gNav = Invoke-AdbShell "dumpsys gfxinfo $Pkg"; Save "gfxinfo-nav.txt" $gNav
$fsNav = Invoke-AdbShell "dumpsys gfxinfo $Pkg framestats"; Save "framestats-nav.txt" $fsNav
Invoke-AdbShell "dumpsys gfxinfo $Pkg reset" | Out-Null
Start-Sleep -Milliseconds ([int]($navSec * 1000))
$gIdle = Invoke-AdbShell "dumpsys gfxinfo $Pkg"; Save "gfxinfo-idle.txt" $gIdle
$period = $(if ($refresh) { 1000.0 / $refresh } else { 0 })
$nav = ConvertFrom-Gfxinfo $gNav; $idle = ConvertFrom-Gfxinfo $gIdle
$keys = $NavRounds * 8
$frames = [ordered]@{
  note = "gfxinfo frame durations of the app's HWUI frames (includes WebView draw). Rendering smoothness, NOT key-to-screen latency."
  keys = $keys; nav_seconds = [math]::Round($navSec, 1); effective_key_interval_ms = [math]::Round(1000.0 * $navSec / $keys)
  nav = $nav; nav_histogram = (Get-HistogramSummary $nav.histogram $period); nav_framestats_last_frames = (ConvertFrom-Framestats $fsNav)
  idle_control = $idle; idle_seconds = [math]::Round($navSec, 1)
  frames_per_key = $(if ($nav.frames) { [math]::Round(($nav.frames - [int]$idle.frames) / $keys, 1) } else { $null })
}
if ($nav.histogram) { $frames.nav.histogram = $null }   # summarised above; the raw histogram stays in gfxinfo-nav.txt
if ($idle.histogram) { $frames.idle_control.histogram = $null }
if ($nav.frames -and $idle.frames -and $idle.frames -gt 0.2 * $nav.frames) { Warn "Idle control rendered $($idle.frames) frames vs $($nav.frames) during D-pad: frames are not attributable to the key presses" }
if (-not $nav.frames) { Warn "gfxinfo returned no frame data for the D-pad sequence" }

# ---------- optional Perfetto trace ----------
if ($Trace) {
  $dev = "/data/misc/perfetto-traces/glass-nav.perfetto-trace"
  $t = Invoke-AdbShell "perfetto --background -o $dev -t 25s -b 64mb sched freq idle gfx view input webview wm am"
  Save "perfetto.txt" $t
  Start-Sleep -Seconds 2
  RunNav 2 | Out-Null
  Start-Sleep -Seconds 25
  $pull = Invoke-AdbCmd @("pull", $dev, (Join-Path $out "glass-nav.perfetto-trace"))
  Save "perfetto.txt" $pull
  if (-not (Test-Path (Join-Path $out "glass-nav.perfetto-trace"))) { Warn "Perfetto trace not captured (see perfetto.txt; on Android 11 tracing may need: adb shell setprop persist.traced.enable 1)" }
}

# ---------- background CPU (Glass hidden behind a full-screen app) ----------
$cpuBg = $null; $bgState = $null
if ($bg) {
  OpenBackground $bg | Out-Null
  Start-Sleep -Seconds 5
  $bgState = (GlassState).state
  if ($bgState -ne "STOPPED") { Warn "Background CPU: Glass was $bgState behind $bg, not STOPPED" }
  Write-Host "Measuring background CPU..."
  $cpuBg = CpuMeasure "background"
  Invoke-AdbShell "am start -W -n $Act" | Out-Null
}

# ---------- summary ----------
$coldStats = Get-Stats $cold; $warmStats = Get-Stats $warm
if ($coldStats -and $coldStats.all_identical) { Warn "All cold-start samples are identical - treat as invalid" }
if ($warmStats -and $warmStats.all_identical) { Warn "All warm-start samples are identical - treat as invalid" }
if ($cold.Count -lt $Runs) { Warn "Cold start: $($Runs - $cold.Count) of $Runs samples rejected (see cold.txt)" }
if ($bg -and $warm.Count -lt $Runs) { Warn "Warm start: $($Runs - $warm.Count) of $Runs samples rejected (see warm.txt)" }

$summary = [ordered]@{
  schema_version = 2
  device = $info
  cold_start_ms = $coldStats; cold_start_runs = $coldRows
  warm_start_ms = $warmStats; warm_start_runs = $warmRows
  memory = $memory
  cpu_foreground_idle = $cpuFg
  cpu_background = $cpuBg; glass_state_during_background = $bgState
  frames = $frames
  warnings = @($warnings)
  notes = "Start times: am start -W TotalTime (to first frame of the activity window; WebView content appears later). CPU: /proc/<pid>/stat utime+stime, USER_HZ=100."
}
$summary | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $out "summary.json")
Write-Host ""
Write-Host ("cold start median {0} ms (n={1}), warm start median {2} ms (n={3})" -f $coldStats.median, $cold.Count, $warmStats.median, $warm.Count)
Write-Host ("app PSS median {0} KB, renderer PSS median {1} KB" -f $memory.app_pss_kb.median, $memory.renderer_pss_kb.median)
Write-Host ("D-pad window: {0} frames ({1} janky), idle control: {2} frames" -f $nav.frames, $nav.janky, $idle.frames)
if ($warnings.Count) { Write-Host "`nWARNINGS:"; $warnings | ForEach-Object { Write-Host " - $_" } }
Write-Host "`nSummary: $(Join-Path $out 'summary.json')"
