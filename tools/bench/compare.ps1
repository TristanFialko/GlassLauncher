<#
  Compares two summary.json files written by bench-device.ps1 v2 (e.g. baseline vs Glass 2.0 on the same box).
  Usage: powershell -ExecutionPolicy Bypass -File tools\bench\compare.ps1 bench-baseline-...\summary.json bench-glass2-...\summary.json

  A difference is only called "better"/"worse" when both runs have enough valid samples (start times: 5, CPU/PSS: 3
  windows, frames: 20) and the medians differ by more
  than the larger interquartile range; otherwise it is reported as "within noise".
#>
param([Parameter(Mandatory = $true)][string]$First, [Parameter(Mandatory = $true)][string]$Second)
$a = Get-Content $First -Raw | ConvertFrom-Json
$b = Get-Content $Second -Raw | ConvertFrom-Json
foreach ($x in @($a, $b)) {
  if (-not $x.schema_version -or $x.schema_version -lt 2) {
    Write-Error "One of the files was written by harness v1, whose warm-start, PSS and frame numbers are unreliable (see docs/benchmarks/METHODOLOGY.md). Rerun both builds with the current bench-device.ps1."
    exit 1
  }
}
foreach ($k in @("model", "android", "wm_size", "wm_density", "refresh_hz", "webview", "expected_theme", "dexopt_status", "background_package")) {
  if ("$($a.device.$k)" -ne "$($b.device.$k)") { Write-Warning "Different $k : '$($a.device.$k)' vs '$($b.device.$k)' - results are not directly comparable" }
}
foreach ($x in @(@($a, $First), @($b, $Second))) { foreach ($w in $x[0].warnings) { Write-Warning ("[" + $x[0].device.label + "] " + $w) } }

function Verdict($sa, $sb, [string]$lowerIs, [int]$minN, [bool]$identicalIsInvalid) {
  if (-not $sa -or -not $sb) { return "n/a" }
  if ($identicalIsInvalid -and ($sa.all_identical -or $sb.all_identical)) { return "INVALID (identical samples)" }
  if ($sa.n -lt $minN -or $sb.n -lt $minN) { return "too few samples" }
  $d = $sb.median - $sa.median
  $noise = [math]::Max([double]$sa.iqr, [double]$sb.iqr)
  if ([math]::Abs($d) -le $noise) { return "within noise" }
  $tag = $(if ($minN -lt 5) { " (one run; repeat to confirm)" } else { "" })
  if (($d -lt 0) -eq ($lowerIs -eq "better")) { return "better" + $tag } else { return "worse" + $tag }
}
function Fmt($s) { if (-not $s) { return "n/a" }; return ("{0:0.##} (IQR {1:0.##}, n={2})" -f $s.median, $s.iqr, $s.n) }
function Row([string]$name, $sa, $sb, [int]$minN = 3, [bool]$identicalIsInvalid = $false) {
  "{0,-30} {1,-30} {2,-30} {3}" -f $name, (Fmt $sa), (Fmt $sb), (Verdict $sa $sb "better" $minN $identicalIsInvalid)
}
function Val([string]$name, $x, $y) { "{0,-30} {1,-30} {2,-30} {3}" -f $name, "$x", "$y", "single value" }

"{0,-30} {1,-30} {2,-30} {3}" -f "metric", $a.device.label, $b.device.label, "verdict"
Row "cold start ms" $a.cold_start_ms $b.cold_start_ms 5 $true
Row "warm start ms" $a.warm_start_ms $b.warm_start_ms 5 $true
Row "app PSS KB" $a.memory.app_pss_kb $b.memory.app_pss_kb
Row "renderer PSS KB" $a.memory.renderer_pss_kb $b.memory.renderer_pss_kb
Row "CPU % fg idle (app)" $a.cpu_foreground_idle.app_process_pct $b.cpu_foreground_idle.app_process_pct
Row "CPU % fg idle (renderer)" $a.cpu_foreground_idle.renderer_pct $b.cpu_foreground_idle.renderer_pct
Row "CPU % background (app)" $a.cpu_background.app_process_pct $b.cpu_background.app_process_pct
Row "CPU % background (renderer)" $a.cpu_background.renderer_pct $b.cpu_background.renderer_pct
Row "frame ms (last frames)" $a.frames.nav_framestats_last_frames.duration_ms $b.frames.nav_framestats_last_frames.duration_ms 20
Val "D-pad frames / idle frames" ("{0} / {1}" -f $a.frames.nav.frames, $a.frames.idle_control.frames) ("{0} / {1}" -f $b.frames.nav.frames, $b.frames.idle_control.frames)
Val "D-pad janky %" $a.frames.nav.janky_pct $b.frames.nav.janky_pct
Val "D-pad frames > 2 vsync %" $a.frames.nav_histogram.over_2_vsync_pct $b.frames.nav_histogram.over_2_vsync_pct
Val "gfxinfo p50 ms (bucket)" ("{0} ({1}-{0})" -f $a.frames.nav_histogram.p50.ms, $a.frames.nav_histogram.p50.bucket_from_ms) ("{0} ({1}-{0})" -f $b.frames.nav_histogram.p50.ms, $b.frames.nav_histogram.p50.bucket_from_ms)
Val "gfxinfo p95 ms (bucket)" ("{0} ({1}-{0})" -f $a.frames.nav_histogram.p95.ms, $a.frames.nav_histogram.p95.bucket_from_ms) ("{0} ({1}-{0})" -f $b.frames.nav_histogram.p95.ms, $b.frames.nav_histogram.p95.bucket_from_ms)
Val "APK bytes" $a.device.apk_bytes $b.device.apk_bytes
