<#
  End-to-end test of bench-device.ps1 against a SIMULATED Android 11 (API 30) Android TV device. No device needed.
  `adb` and `Start-Sleep` are replaced by functions in this scope (functions win over commands and cmdlets), so the real
  harness script runs unchanged and writes a real summary.json, which is then checked.

  Scenarios reproduce what the owner's VIP7802 runs exposed:
    android11     Glass STOPs ~2 polls after YouTube starts (YouTube's am start reports Status: timeout, as trampolines
                  do); every warm relaunch prints "its current task has been brought to the front" (v3 rejected all
                  of these); another app's WebView renderer runs alongside Glass's renderer (pid 4958, TOTAL PSS 63536).
    never-stops   YouTube in front but Glass stays PAUSED: warm samples and background CPU must be rejected with reasons.
    ambiguous     two renderers, no services attribution: renderer PSS/CPU rejected, never summed or blanked silently.
    single        one renderer, no services attribution: accepted and flagged as unconfirmed.
  Run: pwsh tools/bench/test-bench-device.ps1  (or Windows PowerShell 5.1: powershell -File ...). Exit code 1 on failure.
#>
$ErrorActionPreference = "Stop"
$script:fail = 0; $script:pass = 0
function Check([string]$name, $actual, $expected) {
  if ("$actual" -eq "$expected") { $script:pass++ } else { $script:fail++; Write-Host "FAIL $name : got '$actual' expected '$expected'" }
}
function CheckLike([string]$name, $actual, [string]$pattern) {
  if ("$actual" -like $pattern) { $script:pass++ } else { $script:fail++; Write-Host "FAIL $name : got '$actual' expected like '$pattern'" }
}

$GLASS = "com.glasslauncher"
$YT = "com.google.android.youtube.tv"
$YTACT = "$YT/com.google.android.apps.youtube.tv.activity.ShellActivity"
$RENDERER = "com.google.android.webview:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:0"

function New-Sim([string]$name, [int]$stopAfter, [bool]$attrib, [bool]$extraRenderer) {
  return @{ name = $name; stopAfter = $stopAfter; attrib = $attrib; extraRenderer = $extraRenderer
            glassPid = 3210; front = "glass"; glassState = "RESUMED"; polls = 0; logcat = ""; ticks = @{}; ytStarted = $false; coldCalls = 0; act = ".MainActivity"; proc = $GLASS }
}

function Sim-Activities($s) {
  $glassHist = @"
    * Hist #0: ActivityRecord{2222 u0 $GLASS/$($s.act) t5}
        packageName=$GLASS processName=$GLASS
        launchedFromUid=0 launchedFromPackage=null launchedFromFeature=null userId=0
        app=ProcessRecord{9a1 $($s.glassPid):$GLASS/u0a98}
        Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME] flg=0x10000100 cmp=$GLASS/$($s.act) }
        haveState=$($s.glassState -eq 'STOPPED') icicle=null
        state=$($s.glassState) stopped=$($s.glassState -eq 'STOPPED') delayedResume=false finishing=false
        keysPaused=false inHistory=true setToSleep=false idle=true mStartingWindowState=STARTING_WINDOW_NOT_SHOWN
        occludesParent=true noDisplay=false immersive=false launchMode=0
        mVisibleRequested=$($s.front -eq 'glass') mVisible=$($s.front -eq 'glass') mClientVisible=$($s.front -eq 'glass')
"@
  $glassTask = @"
  * Task{c5e1 #5 visible=$($s.front -eq 'glass') type=home mode=fullscreen translucent=false I=$GLASS/$($s.act) U=0 StackId=5 sz=1}
    mBounds=Rect(0, 0 - 1920, 1080)
    Activities=[ActivityRecord{2222 u0 $GLASS/$($s.act) t5}]
$glassHist
"@
  if ($s.front -eq "glass") {
    return @"
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):
$glassTask
    mResumedActivity: ActivityRecord{2222 u0 $GLASS/$($s.act) t5}

 ResumedActivity: ActivityRecord{2222 u0 $GLASS/$($s.act) t5}
"@
  }
  return @"
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):
  * Task{a8f2 #12 visible=true type=standard mode=fullscreen translucent=false A=10112:$YT U=0 StackId=12 sz=1}
    mBounds=Rect(0, 0 - 1920, 1080)
    mResumedActivity: ActivityRecord{1111 u0 $YTACT t12}
    mLastPausedActivity: ActivityRecord{2222 u0 $GLASS/$($s.act) t5}
    Activities=[ActivityRecord{1111 u0 $YTACT t12}]
    * Hist #0: ActivityRecord{1111 u0 $YTACT t12}
        packageName=$YT processName=$YT
        state=RESUMED stopped=false delayedResume=false finishing=false
        mVisibleRequested=true mVisible=true mClientVisible=true
$glassTask
 ResumedActivity: ActivityRecord{1111 u0 $YTACT t12}
"@
}

function Sim-Windows($s) {
  if ($s.front -eq "glass") { return "  mCurrentFocus=Window{77 u0 $GLASS/$GLASS$($s.act)}`n  mFocusedApp=ActivityRecord{2222 u0 $GLASS/$($s.act) t5}" }
  return "  mCurrentFocus=Window{88 u0 $YTACT}`n  mFocusedApp=ActivityRecord{1111 u0 $YTACT t12}"
}

function Sim-Meminfo([string]$p, [string]$proc, [int]$pss) {
  return @"
Applications Memory Usage (in Kilobytes):
Uptime: 5032113 Realtime: 5032113

** MEMINFO in pid $p [$proc] **
                   Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
                ------   ------   ------   ------   ------   ------   ------   ------
  Native Heap     9120     9064        0       12    11000    14336    12011     2324
        TOTAL    $pss    31000    12000       40    99000    20000    16000     4000

 App Summary
                       Pss(KB)                        Rss(KB)
                        ------                         ------
           Java Heap:     4104                          13820
               TOTAL PSS:    $pss            TOTAL RSS:    99000       TOTAL SWAP PSS:       40
"@
}

function Sim-Shell([string]$c) {
  $s = $global:GlassBenchSim
  switch -regex ($c) {
    "^pidof (.+)$" { if ($Matches[1] -eq $s.proc) { return "$($s.glassPid)" } else { return "" } }
    "^am start -S -W -n $GLASS/$([regex]::Escape($s.act))(?: --ez glass\.latency true)?$" {
      $s.glassPid += 7; $s.front = "glass"; $s.glassState = "RESUMED"
      $s.coldCalls++
      # as on the VIP7802: the first force-stop launch of a run returned a stale wait result
      if ($s.coldCalls -eq 1) { return "Stopping: $GLASS`nStarting: Intent { cmp=$GLASS/$($s.act) }`nStatus: ok`nLaunchState: UNKNOWN (-1)`nActivity: $GLASS/$($s.act)`nWaitTime: 442480`nComplete" }
      $s.logcat = "I ActivityTaskManager: Displayed $GLASS/$($s.act): +1s210ms"
      return "Starting: Intent { cmp=$GLASS/$($s.act) }`nStatus: ok`nLaunchState: COLD`nActivity: $GLASS/$($s.act)`nTotalTime: $(1150 + $s.glassPid % 50)`nWaitTime: 1220`nComplete"
    }
    "^am start -W -n $GLASS/$([regex]::Escape($s.act))(?: --ez glass\.latency true)?$" {
      if ($s.front -eq "glass") { return "Starting: Intent { cmp=$GLASS/$($s.act) }`nWarning: Activity not started, intent has been delivered to currently running top-most instance.`nStatus: ok`nLaunchState: UNKNOWN (0)`nActivity: $GLASS/$($s.act)`nWaitTime: 3`nComplete" }
      $s.front = "glass"; $s.glassState = "RESUMED"
      $s.logcat = "I ActivityTaskManager: Displayed $GLASS/$($s.act): +240ms"
      return "Starting: Intent { cmp=$GLASS/$($s.act) }`nWarning: Activity not started, its current task has been brought to the front`nStatus: ok`nLaunchState: HOT`nActivity: $GLASS/$($s.act)`nTotalTime: $(230 + $s.polls % 20)`nWaitTime: 245`nComplete"
    }
    "^am start -W -n $([regex]::Escape($YTACT))$" {
      $s.front = "bg"; $s.glassState = "PAUSED"; $s.polls = 0
      $first = -not $s.ytStarted; $s.ytStarted = $true
      if ($first) { return "Starting: Intent { cmp=$YTACT }`nStatus: timeout`nLaunchState: COLD`nActivity: $YTACT`nWaitTime: 10012`nComplete" }
      return "Starting: Intent { cmp=$YTACT }`nWarning: Activity not started, its current task has been brought to the front`nStatus: ok`nLaunchState: HOT`nActivity: $YTACT`nTotalTime: 410`nWaitTime: 415`nComplete"
    }
    "^dumpsys activity activities$" {
      if ($s.front -eq "bg") { $s.polls++; if ($s.polls -ge $s.stopAfter) { $s.glassState = "STOPPED" } elseif ($s.glassState -eq "PAUSED" -and $s.stopAfter -lt 1000) { $s.glassState = "STOPPING" } }
      return (Sim-Activities $s)
    }
    "^dumpsys window windows$" { return (Sim-Windows $s) }
    "^dumpsys activity services $GLASS$" {
      $out = "ACTIVITY MANAGER SERVICES (dumpsys activity services)`n  User 0 active services:"
      # the real VIP7802 dump also lists com.glasslauncher.utilityhub (prefix match); here it owns the OTHER renderer
      if ($s.extraRenderer) {
        $out += "`n  * ServiceRecord{77 u0 com.glasslauncher.utilityhub/org.chromium.content.app.SandboxedProcessService0:1}`n    packageName=com.glasslauncher.utilityhub`n    app=ProcessRecord{78 6123:com.google.android.webview:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:1/u0a81i3}"
      }
      if ($s.attrib) {
        $out += @"

  * ServiceRecord{51d0 u0 $GLASS/org.chromium.content.app.SandboxedProcessService0:0}
    intent={cmp=com.google.android.webview/org.chromium.content.app.SandboxedProcessService0}
    packageName=$GLASS
    processName=$RENDERER
    baseDir=/product/app/WebViewGoogle/WebViewGoogle.apk
    app=ProcessRecord{2b7 4958:$RENDERER/u0i9}
    isolatedProc=ProcessRecord{2b7 4958:$RENDERER/u0i9}
    createTime=-1h3m lastActivity=-1h3m restartTime=-1h3m createdFromFg=true
    Bindings:
    * IntentBindRecord{6c3 CREATE}:
      intent={cmp=com.google.android.webview/org.chromium.content.app.SandboxedProcessService0}
      binder=android.os.BinderProxy@1f0
      requested=true received=true hasBound=true doRebind=false
      * Client AppBindRecord{8d1 ProcessRecord{9a1 $($s.glassPid):$GLASS/u0a98}}
        Per-process Connections:
          ConnectionRecord{3e2 u0 CR IMP $GLASS/org.chromium.content.app.SandboxedProcessService0:0:@5a4}
"@
      }
      return $out
    }
    "^ps -A -o PID,ARGS$" {
      $out = "  PID ARGS`n    1 init second_stage`n  612 zygote`n  640 webview_zygote`n $($s.glassPid) $GLASS`n 4958 $RENDERER"
      if ($s.extraRenderer) { $out += "`n 6123 com.google.android.webview:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:1`n 6050 com.android.vending" }
      return $out
    }
    "^dumpsys meminfo (\d+)$" {
      $p = $Matches[1]
      if ($p -eq "$($s.glassPid)") { return (Sim-Meminfo $p $GLASS 53686) }
      if ($p -eq "4958") { return (Sim-Meminfo $p $RENDERER 63536) }
      if ($p -eq "6123") { return (Sim-Meminfo $p $RENDERER 41000) }
      return "No process found for: $p"
    }
    "^cat /proc/(\d+)/stat$" {
      $p = $Matches[1]; if (-not $s.ticks.ContainsKey($p)) { $s.ticks[$p] = 1000 }; $s.ticks[$p] += 7
      return "$p (proc) S 1 1 1 0 -1 4194560 100 0 0 0 $($s.ticks[$p]) 50 0 0 20 0 30 0"
    }
    "^getprop ro.product.model$" { return "VIP7802" }
    "^getprop ro.build.version.release$" { return "11" }
    "^getprop ro.build.version.sdk$" { return "30" }
    "^getprop ro.product.cpu.abilist$" { return "armeabi-v7a,armeabi" }
    "^wm size$" { return "Physical size: 1920x1080" }
    "^wm density$" { return "Physical density: 320" }
    "^date \+%H$" { return "20" }
    "^date$" { return "Thu Nov 12 20:00:00 GMT 2026" }
    "^dumpsys SurfaceFlinger$" { return "Display 0 HWC layers:`n refresh-rate              : 60.000000 fps" }
    "^dumpsys package $GLASS$" { return "    versionCode=6 minSdk=23 targetSdk=30`n    versionName=1.5`n    primaryCpuAbi=armeabi-v7a`n    apkSigningVersion=2`n    signatures=PackageSignatures{b8 version:2, signatures:[0a1b2c3d], past signatures:[]}`n    lastUpdateTime=2026-11-12 10:00:00`n    arm: [status=speed-profile] [reason=install]" }
    "^pm path $GLASS$" { return "package:/data/app/~~g/$GLASS-1/base.apk" }
    "^sha256sum /data/app/~~g/$GLASS-1/base.apk$" { return "ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34  /data/app/~~g/$GLASS-1/base.apk" }
    "^pm path $YT$" { return "package:/data/app/~~x/$YT-1/base.apk" }
    "^pm path " { return "" }
    "^cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER $YT$" { return "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false`n$YTACT" }
    "^dumpsys gfxinfo (.+)$" { if ($Matches[1] -ne $s.proc) { return "No process found for: $($Matches[1])" } return "Total frames rendered: 240`nJanky frames: 12 (5.00%)`n50th percentile: 16ms`n90th percentile: 33ms`n95th percentile: 40ms`n99th percentile: 66ms" }
    default { return "" }
  }
}

function adb {
  $a = @($args)
  if ($a.Length -ge 2 -and $a[0] -eq "-s") { $a = $a[2..($a.Length - 1)] }
  switch ($a[0]) {
    "version" { return "Android Debug Bridge version 1.0.41 (simulated)" }
    "logcat" {
      if ($a -contains "GlassLatency:I") { return (1..8 | ForEach-Object { "11-12 I GlassLatency: ui=$(if ($global:GlassBenchSim.act -eq ".NativeHomeActivity") { "native" } else { "webview" }) key=22 ms=$(30 + $_).5 frame_ms=8.0" }) -join "`n" }
      if ($a -contains "-d") { return $global:GlassBenchSim.logcat } else { $global:GlassBenchSim.logcat = ""; return "" }
    }
    "shell" { return (Sim-Shell ($a[1..($a.Length - 1)] -join " ")) }
    default { return "" }
  }
}
function Start-Sleep { }

function Invoke-Sim($sim, [int]$runs, [int]$waitSeconds, [hashtable]$more = @{}) {
  $global:GlassBenchSim = $sim
  $dir = Join-Path ([IO.Path]::GetTempPath()) ("glass-bench-sim-" + [guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Path $dir | Out-Null
  Push-Location $dir
  try {
    & (Join-Path $PSScriptRoot "bench-device.ps1") -Label $sim.name -Runs $runs -IdleSeconds 15 -NavRounds 1 -KeyIntervalMs 1 `
      -BackgroundPackage $YT -BackgroundWaitSeconds $waitSeconds @more 3>$null 6>$null | Out-Null
    $res = Get-ChildItem $dir -Directory | Select-Object -First 1
    return (Get-Content (Join-Path $res.FullName "summary.json") -Raw | ConvertFrom-Json)
  } finally { Pop-Location; Remove-Item -Recurse -Force $dir }
}

# ---- android11: the VIP7802 failure modes, all expected to measure correctly now ----
$s = Invoke-Sim (New-Sim "android11" 3 $true $true) 3 15
Check "a11 harness version" $s.device.harness_version 5
Check "a11 cold n (stale first launch is the discarded warm-up)" $s.cold_start_ms.n 3
Check "a11 cold rows all valid" (@($s.cold_start_runs | Where-Object { $_.valid }).Count) 3
CheckLike "a11 build kind" $s.device.build.kind "has a native library (armeabi-v7a)*"
Check "a11 device apk sha256" $s.device.build.device_apk_sha256 "ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34"
Check "a11 signature" $s.device.build.identity.signatures "0a1b2c3d"
Check "a11 idle window ok" $s.frames.idle_control_window_ok ""
Check "a11 warm n (brought-to-front warning accepted)" $s.warm_start_ms.n 3
Check "a11 warm runs valid" (@($s.warm_start_runs | Where-Object { $_.valid }).Count) 3
Check "a11 warm launch state" $s.warm_start_runs[0].am.launch_state "HOT"
Check "a11 background state" $s.warm_start_runs[0].background.glass_state "STOPPED"
Check "a11 background front" $s.warm_start_runs[0].background.foreground $YT
Check "a11 app pss" $s.memory.app_pss_kb.median 53686
Check "a11 renderer pss = Glass's renderer only" $s.memory.renderer_pss_kb.median 63536
Check "a11 total pss" $s.memory.total_pss_kb.median 117222
Check "a11 renderer process" ($s.memory.renderer_processes -join ";") "$RENDERER (pid 4958)"
Check "a11 renderer attribution" $s.memory.renderer_attribution "dumpsys activity services"
Check "a11 fg cpu renderer measured" $s.cpu_foreground_idle.renderer_pct.n 3
Check "a11 fg cpu total measured" $s.cpu_foreground_idle.total_pct.n 3
Check "a11 bg cpu accepted" $s.cpu_background_rejected ""
Check "a11 bg cpu app n" $s.cpu_background.app_process_pct.n 3
Check "a11 no rejection warnings" (@($s.warnings | Where-Object { $_ -match "rejected" }).Count) 0

# ---- never-stops: Glass stays PAUSED behind YouTube ----
$s = Invoke-Sim (New-Sim "never-stops" 100000 $true $false) 2 1
Check "ns warm stats null" ($null -eq $s.warm_start_ms) "True"
CheckLike "ns warm reason" ($s.warm_start_runs[0].rejected_because -join ";") "*$YT is in front but Glass is PAUSED*not STOPPED*"
Check "ns bg cpu null" ($null -eq $s.cpu_background) "True"
CheckLike "ns bg cpu reason" $s.cpu_background_rejected "before measuring:*Glass is PAUSED*"
CheckLike "ns warning" ($s.warnings -join "|") "*Warm start: 2 of 2 samples rejected*"

# ---- ambiguous: two renderers, nothing attributes either to Glass ----
$s = Invoke-Sim (New-Sim "ambiguous" 2 $false $true) 1 15
Check "amb renderer pss rejected" ($null -eq $s.memory.renderer_pss_kb) "True"
Check "amb total pss not computed" ($null -eq $s.memory.total_pss_kb) "True"
Check "amb app pss still measured" $s.memory.app_pss_kb.median 53686
CheckLike "amb reason" $s.memory.renderer_rejected "2 WebView renderer processes running*4958*6123*"
CheckLike "amb warning" ($s.warnings -join "|") "*renderer rejected*"
Check "amb cpu total not computed" ($null -eq $s.cpu_foreground_idle.total_pct) "True"

# ---- single: one renderer, no attribution available ----
$s = Invoke-Sim (New-Sim "single" 2 $false $false) 1 15
Check "single renderer pss" $s.memory.renderer_pss_kb.median 63536
CheckLike "single attribution flagged" $s.memory.renderer_attribution "single candidate*"

# ---- native UI prototype: own process (com.glasslauncher:native), no WebView renderer, latency probe on ----
$ns = New-Sim "native" 3 $true $true
$ns.act = ".NativeHomeActivity"; $ns.proc = "com.glasslauncher:native"
$s = Invoke-Sim $ns 2 15 @{ Activity = ".NativeHomeActivity"; Latency = $true }
Check "native ui" $s.device.ui "native"
Check "native process" $s.device.process "com.glasslauncher:native"
Check "native cold n" $s.cold_start_ms.n 2
Check "native warm n" $s.warm_start_ms.n 2
Check "native app pss" $s.memory.app_pss_kb.median 53686
Check "native renderer not applicable" $s.memory.renderer_attribution "not applicable (native UI process has no WebView)"
Check "native renderer pss null" ($null -eq $s.memory.renderer_pss_kb) "True"
Check "native total = app" $s.memory.total_pss_kb.median 53686
Check "native frames" $s.frames.nav.frames 240
Check "native latency n" $s.frames.key_to_frame_ms.n 8
Check "native latency median" $s.frames.key_to_frame_ms.median 35
Check "native main process reported" ($null -ne $s.memory.main_process) "True"
Check "native main process not running" ($null -eq $s.memory.main_process.pid) "True"

Write-Host "bench-device simulation tests: $($script:pass) passed, $($script:fail) failed"
if ($script:fail -gt 0) { exit 1 }
