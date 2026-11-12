<#
  Parsing and statistics helpers for bench-device.ps1. Pure functions over adb text output, so they can be
  unit-tested against captured Android output (tools/bench/test-bench-lib.ps1) without a device.
  Compatible with Windows PowerShell 5.1 and PowerShell 7.
#>

function Get-Stats([double[]]$v) {
  if (-not $v -or $v.Count -eq 0) { return $null }
  $s = @($v | Sort-Object)
  $n = $s.Count
  function Q([double]$f) {
    # linear interpolation between closest ranks
    $pos = ($n - 1) * $f; $lo = [math]::Floor($pos); $hi = [math]::Ceiling($pos)
    return $s[$lo] + ($s[$hi] - $s[$lo]) * ($pos - $lo)
  }
  $distinct = @($s | Select-Object -Unique).Count
  return [ordered]@{
    n = $n; median = (Q 0.5); p95 = (Q 0.95); q1 = (Q 0.25); q3 = (Q 0.75); iqr = ((Q 0.75) - (Q 0.25))
    min = $s[0]; max = $s[$n - 1]; distinct_values = $distinct
    all_identical = ($n -ge 3 -and $distinct -eq 1)
    samples = @($v)
  }
}

<# `am start -W` output -> status, launch state, times, warnings. TotalTime is null when not printed. #>
function ConvertFrom-AmStart([string]$o) {
  $r = [ordered]@{ status = $null; launch_state = $null; total_ms = $null; wait_ms = $null; activity = $null; warning = $null; error = $null }
  if ($o -match "(?m)^Status:\s*(\S+)") { $r.status = $Matches[1] }
  if ($o -match "(?m)^LaunchState:\s*(\S+)") { $r.launch_state = $Matches[1] }
  if ($o -match "(?m)^TotalTime:\s*(-?\d+)") { $r.total_ms = [double]$Matches[1] }
  if ($o -match "(?m)^WaitTime:\s*(-?\d+)") { $r.wait_ms = [double]$Matches[1] }
  if ($o -match "(?m)^Activity:\s*(\S+)") { $r.activity = $Matches[1] }
  if ($o -match "(?m)^Warning:\s*(.+?)\s*$") { $r.warning = $Matches[1] }
  if ($o -match "(?m)^Error:\s*(.+?)\s*$") { $r.error = $Matches[1] }
  return $r
}

<# logcat "Displayed com.glasslauncher/.MainActivity: +1s193ms" -> 1193 (last match), or null. #>
function Get-DisplayedMs([string]$log, [string]$component) {
  $pat = [regex]::Escape($component) + ":\s*\+(?:(\d+)s)?(\d+)ms"
  $m = [regex]::Matches($log, $pat)
  if ($m.Count -eq 0) { return $null }
  $last = $m[$m.Count - 1]
  $sec = 0; if ($last.Groups[1].Success) { $sec = [int]$last.Groups[1].Value }
  return [double]($sec * 1000 + [int]$last.Groups[2].Value)
}

<#
  Total PSS (KB) from `dumpsys meminfo <pid|pkg>`. Formats handled:
    Android 8-14 main table row      "        TOTAL    98765    ..."
    Android 8-11 App Summary          "              TOTAL:    98765       TOTAL SWAP PSS: ..."
    Android 12+  App Summary          "           TOTAL PSS:    98765            TOTAL RSS: ..."
  Returns @{ pss_kb; source; error }.
#>
function ConvertFrom-Meminfo([string]$o) {
  $r = [ordered]@{ pss_kb = $null; source = $null; error = $null }
  if (-not $o -or -not $o.Trim()) { $r.error = "empty output"; return $r }
  if ($o -match "No process found") { $r.error = "process not running"; return $r }
  # digit grouping ("63,536") is tolerated in case an OEM build or a copy-paste formats the number
  if ($o -match "(?m)^\s*TOTAL PSS:\s*(\d[\d,]*)") { $r.pss_kb = [int]($Matches[1] -replace ",", ""); $r.source = "summary TOTAL PSS"; return $r }
  if ($o -match "(?m)^\s*TOTAL:\s*(\d[\d,]*)") { $r.pss_kb = [int]($Matches[1] -replace ",", ""); $r.source = "summary TOTAL"; return $r }
  if ($o -match "(?m)^\s*TOTAL\s+(\d[\d,]*)\s") { $r.pss_kb = [int]($Matches[1] -replace ",", ""); $r.source = "table TOTAL"; return $r }
  $first = ($o -split "`n" | Where-Object { $_.Trim() } | Select-Object -First 3) -join " | "
  $r.error = "unrecognised meminfo format: " + $first
  return $r
}

<# `dumpsys gfxinfo <pkg>` summary + HISTOGRAM. #>
function ConvertFrom-Gfxinfo([string]$o) {
  $r = [ordered]@{ frames = $null; janky = $null; janky_pct = $null; janky_legacy = $null; p50_ms = $null; p90_ms = $null; p95_ms = $null; p99_ms = $null
                   missed_vsync = $null; high_input_latency = $null; slow_ui_thread = $null; slow_bitmap_uploads = $null; slow_draw_commands = $null
                   frame_deadline_missed = $null; histogram = $null }
  if ($o -match "Total frames rendered:\s*(\d+)") { $r.frames = [int]$Matches[1] }
  if ($o -match "(?m)^Janky frames:\s*(\d+)\s*\(([\d.]+)%\)") { $r.janky = [int]$Matches[1]; $r.janky_pct = [double]$Matches[2] }
  if ($o -match "(?m)^Janky frames \(legacy\):\s*(\d+)") { $r.janky_legacy = [int]$Matches[1] }
  foreach ($p in @(@("p50_ms", "50th"), @("p90_ms", "90th"), @("p95_ms", "95th"), @("p99_ms", "99th"))) {
    if ($o -match ("(?m)^" + $p[1] + " percentile:\s*(\d+)ms")) { $r[$p[0]] = [int]$Matches[1] }
  }
  foreach ($p in @(@("missed_vsync", "Number Missed Vsync"), @("high_input_latency", "Number High input latency"), @("slow_ui_thread", "Number Slow UI thread"),
                   @("slow_bitmap_uploads", "Number Slow bitmap uploads"), @("slow_draw_commands", "Number Slow issue draw commands"),
                   @("frame_deadline_missed", "Number Frame deadline missed"))) {
    if ($o -match ("(?m)^" + [regex]::Escape($p[1]) + ":\s*(\d+)")) { $r[$p[0]] = [int]$Matches[1] }
  }
  if ($o -match "(?m)^HISTOGRAM:(.*)$") {
    $h = @{}   # plain Hashtable: [ordered] treats an int index as a position, not a key
    foreach ($m in [regex]::Matches($Matches[1], "(\d+)ms=(\d+)")) { $h[[int]$m.Groups[1].Value] = [int]$m.Groups[2].Value }
    $r.histogram = $h
  }
  # v5: gfxinfo prints its top bucket (e.g. "4950ms") as every percentile of an EMPTY histogram; that is not a measurement
  if ($r.frames -eq 0) { $r.p50_ms = $null; $r.p90_ms = $null; $r.p95_ms = $null; $r.p99_ms = $null }
  # window covered by these counters = Uptime (ms) - "Stats since" (ns, same uptime clock)
  $r.stats_window_s = $null
  if ($o -match "(?m)^Uptime:\s*(\d+)" ) {
    $up = [double]$Matches[1]
    if ($o -match "(?m)^Stats since:\s*(\d+)ns") { $r.stats_window_s = [math]::Round(($up * 1e6 - [double]$Matches[1]) / 1e9, 2) }
  }
  return $r
}

<#
  From a gfxinfo HISTOGRAM (bucket upper bound ms -> count): frames slower than N vsync periods, and the bucket width
  around a percentile (shows how coarse gfxinfo's percentiles are, e.g. 4 ms steps above ~50 ms).
#>
function Get-HistogramSummary($hist, [double]$periodMs) {
  if (-not $hist -or $hist.Count -eq 0) { return $null }
  $keys = @($hist.Keys | ForEach-Object { [int]$_ } | Sort-Object)
  $total = 0; foreach ($k in $keys) { $total += $hist[$k] }
  if ($total -eq 0) { return [ordered]@{ frames = 0 } }
  function Pct([double]$f) {
    $target = [math]::Ceiling($total * $f); $acc = 0; $prev = 0
    foreach ($k in $keys) { $acc += $hist[$k]; if ($acc -ge $target) { return [ordered]@{ ms = $k; bucket_from_ms = $prev } }; $prev = $k }
    return $null
  }
  $r = [ordered]@{ frames = $total; p50 = (Pct 0.5); p95 = (Pct 0.95) }
  if ($periodMs -gt 0) {
    foreach ($mult in 1, 2, 3, 4) {
      $limit = $periodMs * $mult; $over = 0
      foreach ($k in $keys) { if ($k -gt $limit) { $over += $hist[$k] } }
      $r["over_" + $mult + "_vsync"] = $over
      $r["over_" + $mult + "_vsync_pct"] = [math]::Round(100.0 * $over / $total, 1)
    }
  }
  return $r
}

<# `dumpsys gfxinfo <pkg> framestats` PROFILEDATA -> per-frame durations (ms) for rows with Flags == 0. #>
function ConvertFrom-Framestats([string]$o) {
  $frames = @()
  $blocks = [regex]::Matches($o, "---PROFILEDATA---\s*\r?\n(.*?)---PROFILEDATA---", [System.Text.RegularExpressions.RegexOptions]::Singleline)
  foreach ($b in $blocks) {
    $lines = @($b.Groups[1].Value -split "\r?\n" | Where-Object { $_.Trim() })
    if ($lines.Count -lt 2) { continue }
    $hdr = $lines[0].Trim().TrimEnd(",").Split(",")
    $iFlags = [array]::IndexOf($hdr, "Flags"); $iVsync = [array]::IndexOf($hdr, "IntendedVsync"); $iDone = [array]::IndexOf($hdr, "FrameCompleted")
    if ($iFlags -lt 0 -or $iVsync -lt 0 -or $iDone -lt 0) { continue }
    foreach ($l in $lines[1..($lines.Count - 1)]) {
      $c = $l.Trim().TrimEnd(",").Split(",")
      if ($c.Count -le $iDone) { continue }
      $frames += [pscustomobject]@{ flags = [long]$c[$iFlags]; vsync = [long]$c[$iVsync]; done = [long]$c[$iDone] }
    }
  }
  $ok = @($frames | Where-Object { $_.flags -eq 0 -and $_.done -gt $_.vsync })
  $durs = @($ok | ForEach-Object { ($_.done - $_.vsync) / 1e6 })
  $vs = @($ok | ForEach-Object { $_.vsync } | Sort-Object -Unique)
  $gaps = @(); for ($i = 1; $i -lt $vs.Count; $i++) { $gaps += ($vs[$i] - $vs[$i - 1]) / 1e6 }
  return [ordered]@{ frames = $ok.Count; skipped_flagged = ($frames.Count - $ok.Count); duration_ms = (Get-Stats $durs)
                     min_vsync_gap_ms = $(if ($gaps.Count) { [math]::Round(($gaps | Measure-Object -Minimum).Minimum, 3) } else { $null }) }
}

<# Display refresh rate (Hz) from `dumpsys display` or `dumpsys SurfaceFlinger`. #>
function Get-RefreshHz([string]$display, [string]$sf) {
  if ($sf -match "refresh-rate\s*:\s*([\d.]+)") { return [double]$Matches[1] }
  if ($display -match "mActiveSfDisplayMode=[^\n]*?(?:fps|refreshRate)=([\d.]+)") { return [double]$Matches[1] }
  if ($display -match "mActiveModeId=(\d+)") {
    $id = $Matches[1]
    if ($display -match ("\{id=" + $id + ", width=\d+, height=\d+, fps=([\d.]+)")) { return [double]$Matches[1] }
  }
  if ($display -match "(?:renderFrameRate|refreshRate)\s*[=:]\s*([\d.]+)") { return [double]$Matches[1] }
  if ($display -match "fps=([\d.]+)") { return [double]$Matches[1] }
  return $null
}

<#
  Glass's ActivityRecord state from `dumpsys activity activities`.
  Only the activity's own "* Hist #N: ActivityRecord{...}" entry is read, up to the next Hist/Task/"Running activities"
  header. Android 11 also mentions the record in summary lines (e.g. "Activities=[ActivityRecord{...}]",
  "mResumedActivity: ActivityRecord{...}"); v2 started reading at those and stopped before the state line (state blank).
  Returns found, state (RESUMED/PAUSED/STOPPING/STOPPED...), visible (from mVisibleRequested/visible/nowVisible) and the
  matched header line for diagnostics.
#>
function Get-ActivityState([string]$o, [string]$component) {
  $r = [ordered]@{ found = $false; state = $null; visible = $null; header = $null }
  $in = $false
  foreach ($l in ($o -split "\r?\n")) {
    $isHeader = ($l -match "^\s*\*?\s*(Hist\s+#\d+:|Task\{|TaskRecord\{|Running activities)")
    if ($in -and $isHeader) { break }
    if (-not $in) {
      if ($l -match "^\s*\*?\s*Hist\s+#\d+:\s*ActivityRecord\{" -and $l -like ("*" + $component + "*")) { $in = $true; $r.found = $true; $r.header = $l.Trim() }
      continue
    }
    if (-not $r.state -and $l -match "\bstate=([A-Z_]+)") { $r.state = $Matches[1] }
    if ($null -eq $r.visible -and $l -match "\b(?:mVisibleRequested|visible|nowVisible)=(true|false)") { $r.visible = ($Matches[1] -eq "true") }
  }
  return $r
}

<# Package of an "ActivityRecord{... u0 pkg/act tN}" or "Window{... u0 pkg/act}" reference. #>
function Get-RecordPackage([string]$s) {
  if ($s -match "\bu\d+\s+([A-Za-z0-9_.]+)/") { return $Matches[1] }
  return $null
}

<#
  What is in front, from `dumpsys activity activities` (top-level "ResumedActivity:", else the first "mResumedActivity:")
  and `dumpsys window windows` ("mCurrentFocus=", "mFocusedApp="). Returns the package for each source and a combined
  `package` (resumed activity first, then focused app, then focused window).
#>
function Get-ForegroundInfo([string]$activities, [string]$windows) {
  $r = [ordered]@{ resumed = $null; focused_app = $null; focused_window = $null; package = $null }
  if ($activities -match "(?m)^\s*ResumedActivity:\s*(.+)$") { $r.resumed = Get-RecordPackage $Matches[1] }
  elseif ($activities -match "(?m)^\s*mResumedActivity:\s*(.+)$") { $r.resumed = Get-RecordPackage $Matches[1] }
  if ($windows -match "(?m)^\s*mFocusedApp=(.+)$") { $r.focused_app = Get-RecordPackage $Matches[1] }
  if ($windows -match "(?m)^\s*mCurrentFocus=(.+)$") { $r.focused_window = Get-RecordPackage $Matches[1] }
  foreach ($k in @("resumed", "focused_app", "focused_window")) { if ($r[$k]) { $r.package = $r[$k]; break } }
  return $r
}

<# Component ("pkg/cls") from `cmd package resolve-activity --brief ...`, or null when nothing resolves. #>
function Get-ResolvedComponent([string]$o) {
  $c = $null
  foreach ($l in ($o -split '\r?\n')) { if ($l.Trim() -match '^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$') { $c = $l.Trim() } }
  return $c
}

<#
  Sum of per-process values, or $null if the list is empty or any value is missing. [object[]] makes a single item a
  one-element array: Windows PowerShell 5.1 gives a lone [pscustomobject] no .Count, which blanked renderer PSS/CPU in v2.
#>
function Get-SumOrNull([object[]]$values) {
  if ($null -eq $values -or $values.Length -eq 0) { return $null }
  $sum = 0.0
  foreach ($v in $values) { if ($null -eq $v) { return $null }; $sum += [double]$v }
  return $sum
}

<# PIDs of WebView renderer (sandboxed) processes from `ps -A -o PID,ARGS`. #>
function Get-RendererPids([string]$ps) {
  $out = @()
  foreach ($l in ($ps -split "\r?\n")) {
    if ($l -match "^\s*(\d+)\s+(\S*(?:sandboxed_process|privileged_process)\S*)") { $out += [pscustomobject]@{ pid = $Matches[1]; name = $Matches[2] } }
  }
  return $out
}

<# utime+stime (clock ticks) from /proc/<pid>/stat; null if unreadable. #>
function Get-ProcTicks([string]$stat) {
  $stat = $stat.Trim()
  $i = $stat.LastIndexOf(")")
  if ($i -lt 0) { return $null }
  $f = $stat.Substring($i + 2).Split(" ")
  if ($f.Count -lt 13) { return $null }
  return [double]$f[11] + [double]$f[12]
}

<# Expected Glass theme from device local hour (theme.js: dark 19:00-06:59, unless toggled manually). #>
function Get-ExpectedTheme([int]$hour) { if ($hour -ge 19 -or $hour -lt 7) { return "dark" } return "light" }

<# Compilation filter of the base APK from `dumpsys package <pkg>` (e.g. verify, speed-profile, speed). #>
function Get-DexoptStatus([string]$o) {
  if ($o -match "\[status=([\w-]+)\]") { return $Matches[1] }
  return $null
}

<#
  Harness v4. `am start -n <glass>` while Glass's task is behind another app always prints
  "Warning: Activity not started, its current task has been brought to the front" - that IS the warm/hot relaunch, so
  v3 rejected every warm sample. Any other warning (e.g. "delivered to currently running top-most instance": Glass was
  already in front) still invalidates the sample.
#>
function Test-WarmRelaunchWarning([string]$w) {
  if (-not $w) { return $true }
  return ($w -match "current task has been brought to the front")
}

<#
  Glass counts as backgrounded when its own ActivityRecord is STOPPED and another package is in front. v3 also required
  the requested background package itself to be in front, which fails when that app trampolines to another package
  (account picker, splash) even though Glass is genuinely stopped. Returns @{ ok; reason }.
#>
function Test-GlassCovered($fg, $st, [string]$pkg) {
  if (-not $st.found) { return @{ ok = $false; reason = "Glass activity record not found in dumpsys activity activities" } }
  if (-not $st.state) { return @{ ok = $false; reason = "Glass activity record has no state= line ($($st.header))" } }
  if (-not $fg.package) { return @{ ok = $false; reason = "no foreground package found (resumed activity / focused app / focused window all empty)" } }
  if ($fg.package -eq $pkg) { return @{ ok = $false; reason = "Glass is still in front (Glass state=$($st.state))" } }
  if ($st.state -ne "STOPPED") { return @{ ok = $false; reason = "$($fg.package) is in front but Glass is $($st.state) (visible=$($st.visible)), not STOPPED" } }
  return @{ ok = $true; reason = $null }
}

<#
  PIDs of processes running services bound for a package, from `dumpsys activity services <pkg>`. WebView's renderer is
  an external isolated service: its ServiceRecord is filed under the HOST app's package and carries
  "app=ProcessRecord{<hash> <pid>:com.google.android.webview:sandboxed_process0:.../u0i9}". $exclude is the app's own PID.
#>
function Get-ServiceProcessPids([string]$services, [string]$exclude, [string]$pkg = "") {
  $out = @()
  $inPkg = ($pkg -eq "")
  foreach ($l in ($services -split "\r?\n")) {
    # v5: `dumpsys activity services <pkg>` prefix-matches (on the VIP7802 it also listed com.glasslauncher.utilityhub),
    # so only app= lines inside ServiceRecords filed under exactly <pkg>/ count
    if ($pkg -and $l -match "^\s*\*\s*ServiceRecord\{\S+\s+u\d+\s+([^/\s]+)/") { $inPkg = ($Matches[1] -eq $pkg); continue }
    if ($inPkg -and $l -match "^\s*app=ProcessRecord\{\S+\s+(\d+):") {
      $p = $Matches[1]
      if ($p -ne $exclude -and $out -notcontains $p) { $out += $p }
    }
  }
  return $out
}

<#
  Which renderer candidates (Get-RendererPids, device-wide) belong to Glass. Attribution from the services dump wins;
  with no attribution a single candidate is accepted but flagged; several unattributed candidates are rejected because
  another app's WebView would be counted (v3 summed them all). Returns @{ renderers; attribution; error }.
#>
function Select-GlassRenderers([object[]]$candidates, [object[]]$attributedPids) {
  $c = @($candidates | Where-Object { $null -ne $_ })
  $a = @($attributedPids | Where-Object { $_ })
  if ($c.Length -eq 0) { return @{ renderers = @(); attribution = "none running"; error = $null } }
  if ($a.Length -gt 0) {
    $mine = @($c | Where-Object { $a -contains $_.pid })
    if ($mine.Length -gt 0) { return @{ renderers = $mine; attribution = "dumpsys activity services"; error = $null } }
    # Glass's own service processes are known and none is a running renderer: the candidates belong to other apps
    return @{ renderers = @(); attribution = "none of Glass's"; error = $null }
  }
  if ($c.Length -eq 1) { return @{ renderers = $c; attribution = "single candidate (not confirmed by dumpsys activity services)"; error = $null } }
  $names = ($c | ForEach-Object { "$($_.pid) $($_.name)" }) -join ", "
  return @{ renderers = @(); attribution = "ambiguous"; error = "$($c.Length) WebView renderer processes running and none attributed to Glass by dumpsys activity services: $names" }
}

<#
  Harness v5: which build is installed. versionName is 1.5 in BOTH the baseline and Glass 2.0, so identity comes from
  `dumpsys package` (primaryCpuAbi is null when the APK has no native library - every Rust-enabled 2.0 build ships
  lib/<abi>/libglass.so), the signing certificate digest, the on-device APK hash, and RustCore's own log line.
#>
function Get-PackageIdentity([string]$o) {
  $r = [ordered]@{ versionCode = $null; versionName = $null; primaryCpuAbi = $null; signatures = $null; apkSigningVersion = $null; codePath = $null; lastUpdateTime = $null }
  if ($o -match "(?m)^\s*versionCode=(\d+)") { $r.versionCode = [int]$Matches[1] }
  if ($o -match "(?m)^\s*versionName=(\S+)") { $r.versionName = $Matches[1] }
  if ($o -match "(?m)^\s*primaryCpuAbi=(\S+)") { $r.primaryCpuAbi = $(if ($Matches[1] -eq "null") { $null } else { $Matches[1] }) }
  if ($o -match "signatures:\[([^\]]*)\]") { $r.signatures = $Matches[1] }
  if ($o -match "(?m)^\s*apkSigningVersion=(\d+)") { $r.apkSigningVersion = [int]$Matches[1] }
  if ($o -match "(?m)^\s*codePath=(\S+)") { $r.codePath = $Matches[1] }
  if ($o -match "(?m)^\s*lastUpdateTime=(.+?)\s*$") { $r.lastUpdateTime = $Matches[1] }
  return $r
}

<# RustCore's load line: "Rust core v0.1.0 (ok)" / "Rust core unavailable (<status>)"; the last one wins. #>
function Get-RustCoreStatus([string]$log) {
  $m = [regex]::Matches($log, "Rust core (v[\w.\-]+|unavailable) \((.*?)\)\s*$", "Multiline")
  if ($m.Count -eq 0) { return $null }
  $last = $m[$m.Count - 1]
  return [ordered]@{ loaded = ($last.Groups[1].Value -ne "unavailable"); version = $(if ($last.Groups[1].Value -ne "unavailable") { $last.Groups[1].Value.Substring(1) } else { $null }); status = $last.Groups[2].Value }
}

<# First 64-hex-digit hash in `sha256sum` output, lower case. #>
function Get-Sha256FromOutput([string]$o) {
  if ($o -match "\b([0-9a-fA-F]{64})\b") { return $Matches[1].ToLowerInvariant() }
  return $null
}

<# Plain-language build classification from the evidence above (never from versionName). #>
function Get-BuildKind($identity, $rust) {
  if ($rust -and $rust.loaded) { return "glass2 (Rust core v$($rust.version) loaded)" }
  if ($identity.primaryCpuAbi) {
    if ($rust) { return "has a native library ($($identity.primaryCpuAbi)) but Rust core unavailable: $($rust.status)" }
    return "has a native library ($($identity.primaryCpuAbi)): Rust-enabled build (Rust core loads on first search, not exercised here)"
  }
  return "no native library: the 1.5 baseline or a -Pglass.rust=false build (NOT a Rust-enabled Glass 2.0 build)"
}

<# Reasons a cold-start sample is rejected (empty = valid). #>
function Get-ColdRejections($a) {
  $why = @()
  if ($a.error) { $why += "error: $($a.error)" }
  if ($a.status -ne "ok") { $why += "status=$($a.status)" }
  if ($a.launch_state -ne "COLD") { $why += "LaunchState=$($a.launch_state) (not COLD; e.g. the system relaunched HOME itself after the force-stop)" }
  if (-not ($a.total_ms -gt 0)) { $why += "no TotalTime" }
  return $why
}
