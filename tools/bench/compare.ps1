<#
  Compares two summary.json files written by bench-device.ps1 (e.g. baseline vs Glass 2.0 on the same box).
  Usage: powershell -ExecutionPolicy Bypass -File tools\bench\compare.ps1 bench-baseline-...\summary.json bench-glass2-...\summary.json
#>
param([Parameter(Mandatory = $true)][string]$First, [Parameter(Mandatory = $true)][string]$Second)
$a = Get-Content $First -Raw | ConvertFrom-Json
$b = Get-Content $Second -Raw | ConvertFrom-Json
foreach ($k in @("model", "android", "wm_size", "wm_density", "webview")) {
  if ($a.device.$k -ne $b.device.$k) { Write-Warning "Different $k : '$($a.device.$k)' vs '$($b.device.$k)' - results are not directly comparable" }
}
function Row($name, $x, $y) {
  $d = if ($x -ne $null -and $y -ne $null -and $x -ne 0) { "{0:+0.0;-0.0;0.0}%" -f (100.0 * ($y - $x) / $x) } else { "n/a" }
  "{0,-34} {1,12} {2,12} {3,10}" -f $name, $x, $y, $d
}
"{0,-34} {1,12} {2,12} {3,10}" -f "metric", $a.device.label, $b.device.label, "change"
Row "cold start median (ms)" $a.cold_start_ms.median $b.cold_start_ms.median
Row "cold start p95 (ms)" $a.cold_start_ms.p95 $b.cold_start_ms.p95
Row "warm start median (ms)" $a.warm_start_ms.median $b.warm_start_ms.median
Row "warm start p95 (ms)" $a.warm_start_ms.p95 $b.warm_start_ms.p95
Row "PSS (KB)" $a.pss_kb $b.pss_kb
Row "CPU % foreground idle" $a.cpu_percent_foreground_idle $b.cpu_percent_foreground_idle
Row "CPU % background" $a.cpu_percent_background $b.cpu_percent_background
Row "D-pad frames" $a.dpad_gfxinfo.frames $b.dpad_gfxinfo.frames
Row "D-pad janky frames" $a.dpad_gfxinfo.janky $b.dpad_gfxinfo.janky
Row "D-pad frame p90 (ms)" $a.dpad_gfxinfo.p90_ms $b.dpad_gfxinfo.p90_ms
Row "D-pad frame p99 (ms)" $a.dpad_gfxinfo.p99_ms $b.dpad_gfxinfo.p99_ms
Row "APK bytes" $a.device.apk_bytes $b.device.apk_bytes
