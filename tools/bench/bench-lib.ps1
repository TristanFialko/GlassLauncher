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
  if ($o -match "(?m)^\s*TOTAL PSS:\s*(\d+)") { $r.pss_kb = [int]$Matches[1]; $r.source = "summary TOTAL PSS"; return $r }
  if ($o -match "(?m)^\s*TOTAL:\s*(\d+)") { $r.pss_kb = [int]$Matches[1]; $r.source = "summary TOTAL"; return $r }
  if ($o -match "(?m)^\s*TOTAL\s+(\d+)\s") { $r.pss_kb = [int]$Matches[1]; $r.source = "table TOTAL"; return $r }
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

<# Glass's ActivityRecord state from `dumpsys activity activities`: state (RESUMED/PAUSED/STOPPED...) and visibility. #>
function Get-ActivityState([string]$o, [string]$component) {
  $r = [ordered]@{ found = $false; state = $null; visible = $null }
  $lines = $o -split "\r?\n"
  $in = $false
  foreach ($l in $lines) {
    if ($l -match "ActivityRecord\{") { if ($in) { break }; $in = ($l -like ("*" + $component + "*")); if ($in) { $r.found = $true }; continue }
    if (-not $in) { continue }
    if (-not $r.state -and $l -match "\bstate=([A-Z_]+)") { $r.state = $Matches[1] }
    if ($null -eq $r.visible -and $l -match "\b(?:mVisibleRequested|visible|nowVisible)=(true|false)") { $r.visible = ($Matches[1] -eq "true") }
  }
  return $r
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
