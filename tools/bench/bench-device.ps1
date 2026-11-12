<#
  Glass Launcher on-device benchmark (Android TV over adb), harness v5. Measures any APK the same way so the baseline
  and a Glass 2.0 build can be compared on the SAME device under the SAME conditions.

  Usage (Windows):  tools\bench\bench-device.bat -Label baseline -Apk C:\path\glasslauncher-baseline.apk -CompileSpeed
                    tools\bench\bench-device.bat -Label glass2   -Apk C:\path\app-debug.apk            -CompileSpeed
         (pwsh):    pwsh tools/bench/bench-device.ps1 -Label glass2 -Apk app/build/outputs/apk/debug/app-debug.apk
  Compare:          powershell -ExecutionPolicy Bypass -File tools\bench\compare.ps1 <a>\summary.json <b>\summary.json

  What it measures and how (see docs/benchmarks/METHODOLOGY.md):
    cold start   am start -S -W, kept only when LaunchState=COLD; logcat "Displayed" recorded as a cross-check.
    warm start   a full-screen launcher activity (-BackgroundComponent pkg/cls, or resolved from -BackgroundPackage /
                 first installed of YouTube, Netflix, Play Store) is started with am start -W -n; the harness then polls
                 dumpsys activity/window until another package is in front AND Glass is STOPPED (-BackgroundWaitSeconds,
                 default 15) before timing am start -W of Glass. Anything else is rejected with a diagnostic, never averaged in.
    memory       dumpsys meminfo <pid>, 3 samples, app process AND Glass's WebView renderer process(es), attributed via
                 dumpsys activity services; ambiguous renderers are rejected, never summed.
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
  [string]$BackgroundComponent = "",
  [int]$BackgroundWaitSeconds = 15,
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
# Callers must wrap in @(): PowerShell unrolls a returned one-element array (see Get-SumOrNull in bench-lib.ps1).
function RendererPids() { return @(Get-RendererPids (Invoke-AdbShell "ps -A -o PID,ARGS")) }
<# Glass's own WebView renderer(s): device-wide candidates filtered by dumpsys activity services attribution. #>
function GlassRenderers([string]$appPid) {
  $svc = Invoke-AdbShell "dumpsys activity services $Pkg"
  $sel = Select-GlassRenderers @(RendererPids) (Get-ServiceProcessPids $svc $appPid $Pkg)
  Save "renderers.txt" ("--- " + (Get-Date -Format "HH:mm:ss") + " app pid $appPid -> renderers [" + (($sel.renderers | ForEach-Object { $_.pid }) -join ",") + "] attribution=$($sel.attribution) error=$($sel.error)`n$svc")
  return $sel
}

function CpuWindow([int]$seconds, [string[]]$pids) {
  $t0 = @{}; foreach ($p in $pids) { $t0[$p] = Get-ProcTicks (Invoke-AdbShell "cat /proc/$p/stat") }
  Start-Sleep -Seconds $seconds
  $res = @{}
  foreach ($p in $pids) {
    $t1 = Get-ProcTicks (Invoke-AdbShell "cat /proc/$p/stat")
    if ($t0[$p] -ne $null -and $t1 -ne $null) { $res[$p] = [math]::Round(100.0 * ($t1 - $t0[$p]) / 100 / $seconds, 3) } else { $res[$p] = $null }  # USER_HZ=100
  }
  return $res
}
function CpuMeasure([string]$name) {
  $app = GlassPid
  if (-not $app) { Warn "$name CPU: Glass process not running"; return $null }
  $sel = GlassRenderers $app
  if ($sel.error) { Warn "$name CPU: renderer rejected ($($sel.error)); app process only, total_pct not computed" }
  $rend = @($sel.renderers)
  $pids = @($app) + @($rend | ForEach-Object { $_.pid })
  $win = [math]::Max(5, [int]($IdleSeconds / 3))
  $appS = @(); $rendS = @(); $totS = @()
  for ($w = 0; $w -lt 3; $w++) {
    $r = CpuWindow $win $pids
    Save "cpu-$name.txt" ("window " + $w + ": " + (($pids | ForEach-Object { "$_=" + $r[$_] }) -join " "))
    if ($r[$app] -ne $null) { $appS += $r[$app] }
    $rs = Get-SumOrNull @($rend | ForEach-Object { $r[$_.pid] })
    if ($rs -ne $null) { $rendS += $rs }
    if (-not $sel.error -and $r[$app] -ne $null -and ($rend.Length -eq 0 -or $rs -ne $null)) { $totS += ($r[$app] + [double]$rs) }
  }
  if ($rend.Length -gt 0 -and $rendS.Length -eq 0) { Warn "$name CPU: renderer process(es) found but their /proc stat could not be read" }
  return [ordered]@{ window_seconds = $win; app_process_pct = (Get-Stats $appS)
                     renderer_processes = @($rend | ForEach-Object { $_.name + " (pid " + $_.pid + ")" }); renderer_attribution = $sel.attribution
                     renderer_rejected = $sel.error; renderer_pct = (Get-Stats $rendS)
                     total_pct = (Get-Stats $totS) }
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
<#
  Background app = an explicit, resolved launcher activity (pkg/cls), started with `am start -W -n`. v2 used `monkey`,
  which on the VIP7802 returned without displacing Glass. Returns @{ package; component; diagnostic }.
#>
function PickBackground() {
  $cands = @()
  if ($BackgroundComponent) { return @{ package = ($BackgroundComponent -split "/")[0]; component = $BackgroundComponent; diagnostic = "given" } }
  if ($BackgroundPackage) { $cands = @($BackgroundPackage) } else { $cands = @("com.google.android.youtube.tv", "com.netflix.ninja", "com.android.vending") }
  $diag = @()
  foreach ($p in $cands) {
    if (-not ((Invoke-AdbShell "pm path $p") -match "package:")) { $diag += "$p not installed"; continue }
    foreach ($cat in @("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER")) {
      $o = Invoke-AdbShell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c $cat $p"
      $c = Get-ResolvedComponent $o
      if ($c) { return @{ package = $p; component = $c; diagnostic = "resolved via $cat" } }
    }
    $diag += "$p has no MAIN/LEANBACK_LAUNCHER or LAUNCHER activity"
  }
  return @{ package = $null; component = $null; diagnostic = ($diag -join "; ") }
}
<#
  Starts the background activity and waits (up to $BackgroundWaitSeconds) until BOTH another package is in front
  (resumed activity / focused app) AND Glass's own activity record is STOPPED. Android stops the covered activity only
  after the new one goes idle, or after a 10 s idle timeout when it keeps animating (video autoplay), hence the wait.
  Returns @{ ok; reason; foreground; glass_state; glass_visible; waited_s; am }.
#>
function OpenBackground($bg) {
  $am = Invoke-AdbShell "am start -W -n $($bg.component)"
  $a = ConvertFrom-AmStart $am
  $res = [ordered]@{ ok = $false; reason = $null; foreground = $null; requested_in_front = $null; glass_state = $null; glass_visible = $null; waited_s = 0; am_status = $a.status; am_warning = $a.warning; am_error = $a.error }
  # Only an Error: is fatal. Status "timeout" is common for trampoline/splash activities; the polling below decides.
  if ($a.error) { $res.reason = "am start $($bg.component) failed: status=$($a.status) error=$($a.error)"; return $res }
  $sw = [Diagnostics.Stopwatch]::StartNew()
  do {
    Start-Sleep -Seconds 1
    $acts = Invoke-AdbShell "dumpsys activity activities"
    $fg = Get-ForegroundInfo $acts (Invoke-AdbShell "dumpsys window windows")
    $st = Get-ActivityState $acts $Act
    $cov = Test-GlassCovered $fg $st $Pkg
    $res.foreground = $fg.package; $res.requested_in_front = ($fg.package -eq $bg.package); $res.glass_state = $st.state; $res.glass_visible = $st.visible
    $res.waited_s = [math]::Round($sw.Elapsed.TotalSeconds, 1)
    if ($cov.ok) { $res.ok = $true; return $res }
  } while ($sw.Elapsed.TotalSeconds -lt $BackgroundWaitSeconds)
  $res.reason = "after $($res.waited_s) s: $($cov.reason) (resumed=$($fg.resumed), focused app=$($fg.focused_app), focused window=$($fg.focused_window))"
  return $res
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
  label = $Label; timestamp = $stamp; harness_version = 5
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
# ---------- build identity (v5): versionName is 1.5 in the baseline AND in Glass 2.0 ----------
$ident = Get-PackageIdentity $pkgDump
$apkPath = ((Invoke-AdbShell "pm path $Pkg") -split "\r?\n" | Where-Object { $_ -match "^package:" } | Select-Object -First 1)
if ($apkPath) { $apkPath = $apkPath.Trim().Substring(8) }
$build = [ordered]@{ identity = $ident; device_apk_path = $apkPath
                     device_apk_sha256 = $(if ($apkPath) { Get-Sha256FromOutput (Invoke-AdbShell "sha256sum $apkPath") } else { $null })
                     local_apk_sha256 = $null; local_apk_has_libglass = $null; kind = $null; rust_core = $null }
if ($Apk) {
  $build.local_apk_sha256 = (Get-FileHash -Algorithm SHA256 -Path $Apk).Hash.ToLowerInvariant()
  try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $Apk).Path)
    try { $build.local_apk_has_libglass = (@($z.Entries | Where-Object { $_.FullName -like "lib/*/libglass.so" }).Length -gt 0) } finally { $z.Dispose() }
  } catch { $build.local_apk_has_libglass = $null }
  if ($build.device_apk_sha256 -and $build.device_apk_sha256 -ne $build.local_apk_sha256) {
    Warn "Installed APK (sha256 $($build.device_apk_sha256)) is NOT the -Apk file ($($build.local_apk_sha256)); results do not belong to that file"
  }
}
$build.kind = Get-BuildKind $ident $null
Write-Host "Build under test: $($build.kind); apk sha256 $($build.device_apk_sha256)"
$info.build = $build

# ---------- cold start ----------
$cold = @(); $coldRows = @(); $rustLog = ""
# v5: one discarded launch first. The owner's first sample after a fresh run returned "LaunchState: UNKNOWN (-1)" with a
# 442 s WaitTime (a stale wait result, not a launch time); it was rightly rejected, but it cost a third of a 3-run smoke test.
Save "cold.txt" ("--- warm-up (discarded)`n" + (Invoke-AdbShell "am start -S -W -n $Act"))
Start-Sleep -Seconds 4
for ($i = 1; $i -le $Runs; $i++) {
  Invoke-AdbCmd @("logcat", "-c") | Out-Null
  $o = Invoke-AdbShell "am start -S -W -n $Act"
  Start-Sleep -Seconds 4
  $lc = Invoke-AdbCmd @("logcat", "-d", "-s", "ActivityTaskManager:I", "ActivityManager:I", "GlassRust:I")
  $disp = Get-DisplayedMs $lc $Act
  if ($lc -match "Rust core") { $rustLog += "`n" + $lc }
  $a = ConvertFrom-AmStart $o
  $why = @(Get-ColdRejections $a)
  $valid = ($why.Length -eq 0)
  $coldRows += [ordered]@{ run = $i; am = $a; displayed_ms = $disp; valid = $valid; rejected_because = $why }
  Save "cold.txt" "--- run $i (valid=$valid displayed_ms=$disp reasons=$($why -join '; '))`n$o"
  if ($valid) { $cold += $a.total_ms }
}
if ($rustLog) { $build.rust_core = Get-RustCoreStatus $rustLog; $build.kind = Get-BuildKind $ident $build.rust_core }

# ---------- warm start ----------
$bg = PickBackground
$warm = @(); $warmRows = @()
Save "warm.txt" "background app: package=$($bg.package) component=$($bg.component) ($($bg.diagnostic))"
if (-not $bg.component) { Warn "No full-screen background activity found ($($bg.diagnostic)); pass -BackgroundComponent pkg/cls. Warm start and background CPU skipped." }
else {
  $info.background_package = $bg.package
  $info.background_component = $bg.component
  for ($i = 1; $i -le $Runs; $i++) {
    Invoke-AdbShell "am start -W -n $Act" | Out-Null
    Start-Sleep -Seconds 2
    $pidBefore = GlassPid
    $cover = OpenBackground $bg
    Save "warm.txt" ("--- run $i background: ok=$($cover.ok) foreground=$($cover.foreground) glass_state=$($cover.glass_state) glass_visible=$($cover.glass_visible) waited_s=$($cover.waited_s) am_status=$($cover.am_status) am_warning=$($cover.am_warning)" + $(if ($cover.reason) { " reason=" + $cover.reason } else { "" }))
    if (-not $cover.ok) {
      # Glass not confirmed stopped: relaunching it would not be a warm start, so no launch is timed for this sample.
      $warmRows += [ordered]@{ run = $i; background = $cover; valid = $false; rejected_because = @("background not confirmed: " + $cover.reason) }
      continue
    }
    Invoke-AdbCmd @("logcat", "-c") | Out-Null
    $o = Invoke-AdbShell "am start -W -n $Act"
    Start-Sleep -Seconds 2
    $disp = Get-DisplayedMs (Invoke-AdbCmd @("logcat", "-d", "-s", "ActivityTaskManager:I", "ActivityManager:I")) $Act
    $pidAfter = GlassPid
    $a = ConvertFrom-AmStart $o
    $why = @()
    if ($a.status -ne "ok") { $why += "status=$($a.status)" }
    if ($a.warning -and -not (Test-WarmRelaunchWarning $a.warning)) { $why += "am warning: $($a.warning)" }
    if (-not ($a.total_ms -gt 0)) { $why += "no TotalTime" }
    if (-not $pidBefore -or $pidBefore -ne $pidAfter) { $why += "process changed ($pidBefore -> $pidAfter)" }
    if ($a.launch_state -eq "COLD") { $why += "LaunchState=COLD" }
    $valid = ($why.Length -eq 0)
    $warmRows += [ordered]@{ run = $i; background = $cover; am = $a; displayed_ms = $disp; valid = $valid; rejected_because = $why }
    Save "warm.txt" "--- run $i relaunch (valid=$valid pid $pidBefore->$pidAfter displayed_ms=$disp reasons=$($why -join '; '))`n$o"
    if ($valid) { $warm += $a.total_ms }
  }
}

# ---------- settle on home ----------
Invoke-AdbShell "am start -W -n $Act" | Out-Null
Start-Sleep -Seconds 10

# ---------- memory ----------
$memApp = @(); $memRend = @(); $memTot = @(); $memErr = @()
for ($s = 1; $s -le 3; $s++) {
  $app = GlassPid
  $mo = Invoke-AdbShell "dumpsys meminfo $app"; Save "meminfo.txt" "--- sample $s app pid $app`n$mo"
  $m = ConvertFrom-Meminfo $mo
  if ($m.pss_kb) { $memApp += $m.pss_kb } else { $memErr += "app: $($m.error)" }
  $sel = GlassRenderers $app; $rend = @($sel.renderers); $vals = @()
  if ($sel.error) { $memErr += "renderer rejected: $($sel.error)" }
  foreach ($x in $rend) {
    $ro = Invoke-AdbShell "dumpsys meminfo $($x.pid)"; Save "meminfo.txt" "--- sample $s renderer pid $($x.pid) $($x.name)`n$ro"
    $rm = ConvertFrom-Meminfo $ro
    $vals += $rm.pss_kb
    if (-not $rm.pss_kb) { $memErr += "renderer $($x.pid): $($rm.error)" }
  }
  $rs = Get-SumOrNull $vals
  if ($rs -ne $null) { $memRend += $rs }
  if (-not $sel.error -and $m.pss_kb -and ($rend.Length -eq 0 -or $rs -ne $null)) { $memTot += ($m.pss_kb + [double]$rs) }
  Save "meminfo.txt" "--- sample $s parsed: app=$($m.pss_kb) ($($m.source)) renderer_sum=$rs from $($rend.Length) renderer process(es), attribution=$($sel.attribution)"
  Start-Sleep -Seconds 5
}
foreach ($e in ($memErr | Select-Object -Unique)) { Warn "meminfo: $e" }
$memory = [ordered]@{ app_pss_kb = (Get-Stats $memApp); renderer_pss_kb = (Get-Stats $memRend); total_pss_kb = (Get-Stats $memTot)
                      renderer_processes = @($rend | ForEach-Object { $_.name + " (pid " + $_.pid + ")" })
                      renderer_attribution = $sel.attribution; renderer_rejected = $sel.error
                      webview_mode = $(if ($rend.Length -gt 0) { "multiprocess (renderer in a separate process)" } elseif ($sel.error) { "unknown (renderer ambiguous)" } else { "single-process (no Glass renderer process found)" }) }

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
# v5: an idle control is valid when its counters really covered the idle window; 0 frames then means "a static screen
# draws nothing" (correct), not a failed capture. The owner's smoke run: 0 frames over 35.25 s for a 35 s window.
$frames.idle_control_window_ok = $(if ($null -ne $idle.stats_window_s) { $idle.stats_window_s -ge 0.9 * $navSec } else { $null })
if ($frames.idle_control_window_ok -eq $false) { Warn "Idle control counters cover only $($idle.stats_window_s) s of the $([math]::Round($navSec, 1)) s idle window (gfxinfo reset late?); idle frame count is not comparable" }
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
# Measured only when Glass is confirmed STOPPED behind the background app before AND after the windows; otherwise the
# measurement is rejected (cpu_background = null) with the reason, instead of recording a foreground number as background.
$cpuBg = $null; $bgCheck = $null; $bgReject = $null
if ($bg.component) {
  $bgCheck = OpenBackground $bg
  Save "cpu-background.txt" "background before: ok=$($bgCheck.ok) foreground=$($bgCheck.foreground) glass_state=$($bgCheck.glass_state) waited_s=$($bgCheck.waited_s) reason=$($bgCheck.reason)"
  if (-not $bgCheck.ok) { $bgReject = "before measuring: " + $bgCheck.reason }
  else {
    Write-Host "Measuring background CPU..."
    $cpuBg = CpuMeasure "background"
    $acts = Invoke-AdbShell "dumpsys activity activities"
    $after = Get-ActivityState $acts $Act
    $fgAfterInfo = Get-ForegroundInfo $acts (Invoke-AdbShell "dumpsys window windows"); $fgAfter = $fgAfterInfo.package
    Save "cpu-background.txt" "background after: foreground=$fgAfter glass_state=$($after.state)"
    $covAfter = Test-GlassCovered $fgAfterInfo $after $Pkg
    if (-not $covAfter.ok) { $bgReject = "after measuring: " + $covAfter.reason; $cpuBg = $null }
  }
  if ($bgReject) { Warn "Background CPU rejected ($bgReject)" }
  Invoke-AdbShell "am start -W -n $Act" | Out-Null
}

# ---------- summary ----------
$coldStats = Get-Stats $cold; $warmStats = Get-Stats $warm
if ($coldStats -and $coldStats.all_identical) { Warn "All cold-start samples are identical - treat as invalid" }
if ($warmStats -and $warmStats.all_identical) { Warn "All warm-start samples are identical - treat as invalid" }
if ($cold.Length -lt $Runs) { Warn "Cold start: $($Runs - $cold.Length) of $Runs samples rejected (see cold.txt)" }
if ($bg.component -and $warm.Length -lt $Runs) {
  $reasons = @($warmRows | Where-Object { -not $_.valid } | ForEach-Object { $_.rejected_because } | Select-Object -Unique)
  Warn "Warm start: $($Runs - $warm.Length) of $Runs samples rejected: $($reasons -join ' | ')"
}

$summary = [ordered]@{
  schema_version = 4
  device = $info
  cold_start_ms = $coldStats; cold_start_runs = $coldRows
  warm_start_ms = $warmStats; warm_start_runs = $warmRows
  memory = $memory
  cpu_foreground_idle = $cpuFg
  cpu_background = $cpuBg; cpu_background_check = $bgCheck; cpu_background_rejected = $bgReject
  frames = $frames
  warnings = @($warnings)
  notes = "Start times: am start -W TotalTime (to first frame of the activity window; WebView content appears later). CPU: /proc/<pid>/stat utime+stime, USER_HZ=100."
}
$summary | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $out "summary.json")
Write-Host ""
Write-Host ("cold start median {0} ms (n={1}), warm start median {2} ms (n={3})" -f $coldStats.median, $cold.Length, $warmStats.median, $warm.Length)
Write-Host ("PSS median: app {0} KB, renderer {1} KB, total {2} KB" -f $memory.app_pss_kb.median, $memory.renderer_pss_kb.median, $memory.total_pss_kb.median)
Write-Host ("CPU % median: foreground app {0} + renderer {1}; background app {2} + renderer {3}" -f $cpuFg.app_process_pct.median, $cpuFg.renderer_pct.median, $cpuBg.app_process_pct.median, $cpuBg.renderer_pct.median)
Write-Host ("D-pad window: {0} frames ({1} janky), idle control: {2} frames" -f $nav.frames, $nav.janky, $idle.frames)
if ($warnings.Count) { Write-Host "`nWARNINGS:"; $warnings | ForEach-Object { Write-Host " - $_" } }
Write-Host "`nSummary: $(Join-Path $out 'summary.json')"
