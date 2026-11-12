<#
  Unit tests for bench-lib.ps1 against Android-format fixtures. Run: pwsh tools/bench/test-bench-lib.ps1
  (exit code 1 on failure). Fixtures follow Android 11 (API 30) output, plus Android 12+ variants where formats differ.
#>
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "bench-lib.ps1")
$script:fail = 0; $script:pass = 0
function Check([string]$name, $actual, $expected) {
  if ("$actual" -eq "$expected") { $script:pass++ } else { $script:fail++; Write-Host "FAIL $name : got '$actual' expected '$expected'" }
}
function CheckLike([string]$name, $actual, [string]$pattern) {
  if ("$actual" -like $pattern) { $script:pass++ } else { $script:fail++; Write-Host "FAIL $name : got '$actual' expected like '$pattern'" }
}

# ---- am start -W ----
$cold = @"
Starting: Intent { cmp=com.glasslauncher/.MainActivity }
Status: ok
LaunchState: COLD
Activity: com.glasslauncher/.MainActivity
TotalTime: 1209
WaitTime: 1214
Complete
"@
$front = @"
Starting: Intent { cmp=com.glasslauncher/.MainActivity }
Warning: Activity not started, its current task has been brought to the front
Status: ok
LaunchState: HOT
Activity: com.glasslauncher/.MainActivity
TotalTime: 1193
WaitTime: 4
Complete
"@
$a = ConvertFrom-AmStart $cold
Check "am cold state" $a.launch_state "COLD"; Check "am cold total" $a.total_ms 1209; Check "am cold warning" $a.warning ""
$b = ConvertFrom-AmStart $front
Check "am front state" $b.launch_state "HOT"; Check "am front warning" $b.warning "Activity not started, its current task has been brought to the front"
Check "am none" (ConvertFrom-AmStart "Status: ok").total_ms ""

# ---- logcat Displayed ----
Check "displayed s+ms" (Get-DisplayedMs "I ActivityTaskManager: Displayed com.glasslauncher/.MainActivity: +1s193ms" "com.glasslauncher/.MainActivity") 1193
Check "displayed ms" (Get-DisplayedMs "I ActivityTaskManager: Displayed com.glasslauncher/.MainActivity: +412ms`nI ActivityTaskManager: Displayed com.glasslauncher/.MainActivity: +398ms" "com.glasslauncher/.MainActivity") 398
Check "displayed none" (Get-DisplayedMs "nothing" "com.glasslauncher/.MainActivity") ""

# ---- meminfo ----
$mem11 = @"
Applications Memory Usage (in Kilobytes):
Uptime: 1234567 Realtime: 1234567

** MEMINFO in pid 4242 [com.glasslauncher] **
                   Pss  Private  Private  SwapPss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty     Size    Alloc     Free
                ------   ------   ------   ------   ------   ------   ------
  Native Heap    21844    21780        0       12    28672    24113     4558
  Dalvik Heap     4517     4436        0       24     6145     3073     3072
        TOTAL    98765    61234    12000      120    34817    27186     7630

 App Summary
                       Pss(KB)
                        ------
           Java Heap:     5940
         Native Heap:    21780
               TOTAL:    98765       TOTAL SWAP PSS:      120
"@
$mem13 = @"
** MEMINFO in pid 4242 [com.glasslauncher] **
                   Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
        TOTAL    87654    50000    10000        0   150000    34817    27186     7630
 App Summary
                       Pss(KB)                        Rss(KB)
           TOTAL PSS:    87654            TOTAL RSS:   150000       TOTAL SWAP PSS:        0
"@
$memTableOnly = "** MEMINFO in pid 7 [x] **`r`n        TOTAL    55555    40000`r`n"
Check "meminfo 11" (ConvertFrom-Meminfo $mem11).pss_kb 98765
Check "meminfo 11 source" (ConvertFrom-Meminfo $mem11).source "summary TOTAL"
Check "meminfo 13" (ConvertFrom-Meminfo $mem13).pss_kb 87654
Check "meminfo CRLF table" (ConvertFrom-Meminfo $memTableOnly).pss_kb 55555
Check "meminfo no process" (ConvertFrom-Meminfo "No process found for: com.glasslauncher").error "process not running"
Check "meminfo garbage" ((ConvertFrom-Meminfo "Permission Denial: can't dump meminfo").error -like "unrecognised*") "True"

# ---- gfxinfo ----
$gfx11 = @"
Applications Graphics Acceleration Info:
Uptime: 1234567 Realtime: 1234567

** Graphics info for pid 4242 [com.glasslauncher] **

Stats since: 123456789000ns
Total frames rendered: 2100
Janky frames: 2090 (99.52%)
50th percentile: 61ms
90th percentile: 73ms
95th percentile: 77ms
99th percentile: 89ms
Number Missed Vsync: 310
Number High input latency: 45
Number Slow UI thread: 520
Number Slow bitmap uploads: 3
Number Slow issue draw commands: 1950
HISTOGRAM: 5ms=0 6ms=0 7ms=0 8ms=0 9ms=0 10ms=5 11ms=5 12ms=0 13ms=0 14ms=0 15ms=0 16ms=0 17ms=0 18ms=0 19ms=0 20ms=0 21ms=0 22ms=0 23ms=0 24ms=0 25ms=0 26ms=0 27ms=0 28ms=0 29ms=0 30ms=0 31ms=0 32ms=0 34ms=0 36ms=0 38ms=0 40ms=0 42ms=0 44ms=0 46ms=0 48ms=0 53ms=200 57ms=800 61ms=300 65ms=300 69ms=200 73ms=200 77ms=70 81ms=10 85ms=5 89ms=5 93ms=0 4950ms=0
"@
$g = ConvertFrom-Gfxinfo $gfx11
Check "gfx frames" $g.frames 2100; Check "gfx janky" $g.janky 2090; Check "gfx janky pct" $g.janky_pct 99.52
Check "gfx p50" $g.p50_ms 61; Check "gfx p95" $g.p95_ms 77; Check "gfx slow draw" $g.slow_draw_commands 1950; Check "gfx deadline (absent on 11)" $g.frame_deadline_missed ""
Check "gfx histogram buckets" $g.histogram.Count 48
$h = Get-HistogramSummary $g.histogram (1000.0 / 60)
Check "hist frames" $h.frames 2100
Check "hist p50" $h.p50.ms 61; Check "hist p50 bucket from" $h.p50.bucket_from_ms 57; Check "hist p95" $h.p95.ms 73; Check "hist p95 bucket from" $h.p95.bucket_from_ms 69
Check "hist >1 vsync" $h.over_1_vsync 2090; Check "hist >3 vsync" $h.over_3_vsync 2090; Check "hist >4 vsync" $h.over_4_vsync 490
$h50 = Get-HistogramSummary $g.histogram 20
Check "hist 50Hz >2 vsync" $h50.over_2_vsync 2090; Check "hist 50Hz >3 vsync" $h50.over_3_vsync 1090
$gfx13 = "Total frames rendered: 10`nJanky frames: 2 (20.00%)`nJanky frames (legacy): 3 (30.00%)`nNumber Frame deadline missed: 2`n50th percentile: 9ms"
$g13 = ConvertFrom-Gfxinfo $gfx13
Check "gfx13 legacy" $g13.janky_legacy 3; Check "gfx13 deadline" $g13.frame_deadline_missed 2; Check "gfx13 p50" $g13.p50_ms 9

# ---- framestats ----
$fs = @"
---PROFILEDATA---
Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,GpuCompleted,
0,1000000000,1000000000,0,0,0,0,0,0,0,0,0,0,1060000000,0,0,0,
0,1050000000,1050000000,0,0,0,0,0,0,0,0,0,0,1112000000,0,0,0,
1,1100000000,1100000000,0,0,0,0,0,0,0,0,0,0,1500000000,0,0,0,
0,1116666666,1116666666,0,0,0,0,0,0,0,0,0,0,1180000000,0,0,0,
---PROFILEDATA---
"@
$f = ConvertFrom-Framestats $fs
Check "fs frames" $f.frames 3; Check "fs flagged" $f.skipped_flagged 1; Check "fs median" $f.duration_ms.median 62
Check "fs min gap" $f.min_vsync_gap_ms 50
$fs13 = "---PROFILEDATA---`nFlags,FrameTimelineVsyncId,IntendedVsync,Vsync,FrameCompleted,`n0,77,1000000000,1000000000,1016000000,`n---PROFILEDATA---`n"
Check "fs13 (extra column)" (ConvertFrom-Framestats $fs13).duration_ms.median 16

# ---- refresh rate ----
Check "refresh SF" (Get-RefreshHz "" "Display 0 HWC layers:`n refresh-rate              : 50.000000 fps") 50
$disp11 = "  mActiveModeId=1`n  mSupportedModes=[{id=1, width=1920, height=1080, fps=60.0}, {id=2, width=1920, height=1080, fps=50.0}]"
Check "refresh display mode" (Get-RefreshHz $disp11 "") 60
$disp11b = "  mActiveModeId=2`n  mSupportedModes=[{id=1, width=1920, height=1080, fps=60.0}, {id=2, width=1920, height=1080, fps=50.0}]"
Check "refresh display mode 2" (Get-RefreshHz $disp11b "") 50
Check "refresh none" (Get-RefreshHz "nothing" "nothing") ""

# ---- activity state ----
# Android 11 (R) shape: the Glass record is referenced in "Activities=[...]" and "mResumedActivity:" summary lines before
# and after its own "* Hist #0:" entry. Harness v2 read the summary line first and stopped before state= (state blank).
$acts11 = @"
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):

  Stack #12: type=standard mode=fullscreen
  isSleeping=false
  mBounds=Rect(0, 0 - 0, 0)
    * Task{a1 #12 visible=true type=standard mode=fullscreen translucent=false A=10101:com.google.android.youtube.tv U=0 StackId=12 sz=1}
      mResumeWithRemoteInputs=false
      affinity=10101:com.google.android.youtube.tv
      Activities=[ActivityRecord{1111 u0 com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity t12}]
      * Hist #0: ActivityRecord{1111 u0 com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity t12}
          packageName=com.google.android.youtube.tv processName=com.google.android.youtube.tv
          state=RESUMED stopped=false delayedResume=false finishing=false
          keysPaused=false inHistory=true setToSleep=false idle=false mStartingWindowState=STARTING_WINDOW_REMOVED
          visible=true sleeping=false idle=false
    Running activities (most recent first):
      Task{a1 #12 visible=true type=standard A=10101:com.google.android.youtube.tv U=0 StackId=12 sz=1}
        Run #0: ActivityRecord{1111 u0 com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity t12}
    mResumedActivity: ActivityRecord{1111 u0 com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity t12}

  Stack #5: type=home mode=fullscreen
    * Task{b2 #5 visible=false type=home mode=fullscreen translucent=false A=10050:com.glasslauncher U=0 StackId=5 sz=1}
      affinity=10050:com.glasslauncher
      intent={act=android.intent.action.MAIN cat=[android.intent.category.HOME] flg=0x10000100 cmp=com.glasslauncher/.MainActivity}
      Activities=[ActivityRecord{2222 u0 com.glasslauncher/.MainActivity t5}]
      * Hist #0: ActivityRecord{2222 u0 com.glasslauncher/.MainActivity t5}
          packageName=com.glasslauncher processName=com.glasslauncher
          launchedFromUid=1000 launchedFromPackage=android userId=0
          app=ProcessRecord{3333 4925:com.glasslauncher/u0a50}
          state=STOPPED stopped=true delayedResume=false finishing=false
          visible=false sleeping=false idle=true mStartingWindowState=STARTING_WINDOW_NOT_SHOWN
    Running activities (most recent first):
      Task{b2 #5 visible=false type=home A=10050:com.glasslauncher U=0 StackId=5 sz=1}
        Run #0: ActivityRecord{2222 u0 com.glasslauncher/.MainActivity t5}
    mLastPausedActivity: ActivityRecord{2222 u0 com.glasslauncher/.MainActivity t5}

 ResumedActivity: ActivityRecord{1111 u0 com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity t12}
"@
$s11 = Get-ActivityState $acts11 "com.glasslauncher/.MainActivity"
Check "act11 found" $s11.found "True"; Check "act11 state (v2 regression)" $s11.state "STOPPED"; Check "act11 visible" $s11.visible "False"
Check "act11 header" $s11.header "* Hist #0: ActivityRecord{2222 u0 com.glasslauncher/.MainActivity t5}"
Check "act11 youtube" (Get-ActivityState $acts11 "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity").state "RESUMED"
$acts11Front = $acts11 -replace "state=STOPPED stopped=true", "state=RESUMED stopped=false"
Check "act11 resumed" (Get-ActivityState $acts11Front "com.glasslauncher/.MainActivity").state "RESUMED"
$noState = $acts11 -replace "          state=STOPPED stopped=true delayedResume=false finishing=false\r?\n", ""
$sNo = Get-ActivityState $noState "com.glasslauncher/.MainActivity"
Check "act11 no state line" "$($sNo.found)/$($sNo.state)" "True/"

# ---- foreground ----
$win11 = @"
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #0 Window{aaa u0 com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity}:
    mDisplayId=0 rootTaskId=12 mSession=Session{bbb 5000:u0a10101} mClient=android.os.BinderProxy@ccc
  mGlobalConfiguration={1.0 ?mcc?mnc [en_US] ldltr sw540dp w960dp h540dp 320dpi lrg long land television -touch qwerty/v/v dpad/v winConfig={ mBounds=Rect(0, 0 - 1920, 1080) }}
  mHasPermanentDpad=true
  mTopFocusedDisplayId=0
  mCurrentFocus=Window{aaa u0 com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity}
  mFocusedApp=ActivityRecord{1111 u0 com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity t12}
"@
$fg = Get-ForegroundInfo $acts11 $win11
Check "fg resumed" $fg.resumed "com.google.android.youtube.tv"; Check "fg app" $fg.focused_app "com.google.android.youtube.tv"
Check "fg window" $fg.focused_window "com.google.android.youtube.tv"; Check "fg package" $fg.package "com.google.android.youtube.tv"
$fgGlass = Get-ForegroundInfo " ResumedActivity: ActivityRecord{2222 u0 com.glasslauncher/.MainActivity t5}" "  mCurrentFocus=null`n  mFocusedApp=ActivityRecord{2222 u0 com.glasslauncher/.MainActivity t5}"
Check "fg glass" $fgGlass.package "com.glasslauncher"; Check "fg null focus" $fgGlass.focused_window ""
Check "fg only mResumed" (Get-ForegroundInfo "    mResumedActivity: ActivityRecord{1 u0 com.netflix.ninja/.MainActivity t9}" "").package "com.netflix.ninja"
Check "fg nothing" (Get-ForegroundInfo "" "").package ""

# ---- resolve-activity ----
$res = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false`ncom.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity"
Check "resolve" (Get-ResolvedComponent $res) "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity"
Check "resolve short" (Get-ResolvedComponent "com.netflix.ninja/.MainActivity`r`n") "com.netflix.ninja/.MainActivity"
Check "resolve none" (Get-ResolvedComponent "No activity found") ""

# ---- per-process sums (PS 5.1: a lone pscustomobject has no .Count) ----
Check "sum single" (Get-SumOrNull @(63536)) 63536
Check "sum two" (Get-SumOrNull @(63536, 1000)) 64536
Check "sum missing" (Get-SumOrNull @(63536, $null)) ""
Check "sum empty" (Get-SumOrNull @()) ""
$one = @(Get-RendererPids "  PID ARGS`n 4958 com.google.android.webview:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:0")
Check "renderer single wrapped length" $one.Length 1
Check "sum from single renderer" (Get-SumOrNull @($one | ForEach-Object { 63536 })) 63536

# ---- renderer meminfo as captured on the VIP7802 (TOTAL PSS summary) ----
$rendMem = @"
Applications Memory Usage (in Kilobytes):
Uptime: 5123456 Realtime: 5123456

** MEMINFO in pid 4958 [com.google.android.webview:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:0] **
                   Pss  Private  Private  SwapPss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty     Size    Alloc     Free
                ------   ------   ------   ------   ------   ------   ------
  Native Heap    20112    20100        0        0    24576    21000     3576
        TOTAL    63536    41000     9000        0    24576    21000     3576

 App Summary
                       Pss(KB)
                        ------
           TOTAL PSS:    63536            TOTAL RSS:   112000      TOTAL SWAP PSS:        0
"@
Check "renderer meminfo" (ConvertFrom-Meminfo $rendMem).pss_kb 63536

$acts = @"
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):
  * Task{a1 #12 visible=true type=standard A=10101:com.google.android.youtube.tv U=0 StackId=12 sz=1}
    * Hist #0: ActivityRecord{1111 u0 com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity t12}
        state=RESUMED stopped=false delayedResume=false finishing=false
        mVisibleRequested=true mVisible=true
  * Task{b2 #3 visible=false type=home A=10050:com.glasslauncher U=0 StackId=3 sz=1}
    * Hist #0: ActivityRecord{2222 u0 com.glasslauncher/.MainActivity t3}
        state=STOPPED stopped=true delayedResume=false finishing=false
        mVisibleRequested=false mVisible=false
"@
$s = Get-ActivityState $acts "com.glasslauncher/.MainActivity"
Check "act found" $s.found "True"; Check "act state" $s.state "STOPPED"; Check "act visible" $s.visible "False"
$acts2 = $acts -replace "state=STOPPED stopped=true", "state=PAUSED stopped=false" -replace "mVisibleRequested=false mVisible=false", "mVisibleRequested=true mVisible=true"
Check "act paused" (Get-ActivityState $acts2 "com.glasslauncher/.MainActivity").state "PAUSED"
Check "act missing" (Get-ActivityState "nothing" "com.glasslauncher/.MainActivity").found "False"

# ---- processes / ticks / misc ----
$ps = "  PID ARGS`n 4242 com.glasslauncher`n 5001 com.google.android.webview:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:0`n 777 webview_zygote"
$r = @(Get-RendererPids $ps)
Check "renderer count" $r.Count 1; Check "renderer pid" $r[0].pid 5001
Check "ticks" (Get-ProcTicks "4242 (com.glass launcher) S 1 2 3 4 5 6 7 8 9 10 1500 250 0 0 20") 1750
Check "ticks bad" (Get-ProcTicks "garbage") ""
Check "theme night" (Get-ExpectedTheme 20) "dark"; Check "theme morning" (Get-ExpectedTheme 7) "light"; Check "theme 6am" (Get-ExpectedTheme 6) "dark"
Check "dexopt" (Get-DexoptStatus "Dexopt state:`n  [com.glasslauncher]`n    path: /data/app/x/base.apk`n      arm: [status=speed-profile] [reason=install]") "speed-profile"

# ---- stats ----
$st = Get-Stats @(1193, 1193, 1193, 1193, 1193, 1193, 1193, 1193, 1193, 1193)
Check "stats identical" $st.all_identical "True"; Check "stats identical iqr" $st.iqr 0
$st2 = Get-Stats @(10, 20, 30, 40, 50)
Check "stats median" $st2.median 30; Check "stats q1" $st2.q1 20; Check "stats iqr" $st2.iqr 20; Check "stats not identical" $st2.all_identical "False"
Check "stats even median" (Get-Stats @(1, 2, 3, 4)).median 2.5

# ---- harness v4 helpers ----
Check "meminfo grouped digits" (ConvertFrom-Meminfo "               TOTAL PSS:    63,536            TOTAL RSS:   99,000").pss_kb 63536
Check "act Android 12+ double-space Hist" (Get-ActivityState "  * Hist  #0: ActivityRecord{2 u0 com.glasslauncher/.MainActivity t5}`n      state=STOPPED stopped=true" "com.glasslauncher/.MainActivity").state "STOPPED"
Check "warm warning brought to front ok" (Test-WarmRelaunchWarning "Activity not started, its current task has been brought to the front") "True"
Check "warm warning top-most rejected" (Test-WarmRelaunchWarning "Activity not started, intent has been delivered to currently running top-most instance.") "False"
Check "warm no warning ok" (Test-WarmRelaunchWarning "") "True"
$stStopped = @{ found = $true; state = "STOPPED"; visible = $false; header = "h" }
Check "covered ok" (Test-GlassCovered @{ package = "com.netflix.ninja" } $stStopped "com.glasslauncher").ok "True"
Check "covered other pkg ok" (Test-GlassCovered @{ package = "com.google.android.gms" } $stStopped "com.glasslauncher").ok "True"
Check "covered glass front" (Test-GlassCovered @{ package = "com.glasslauncher" } $stStopped "com.glasslauncher").ok "False"
Check "covered paused" (Test-GlassCovered @{ package = "x.y" } @{ found = $true; state = "PAUSED" } "com.glasslauncher").ok "False"
Check "covered no fg" (Test-GlassCovered @{ package = $null } $stStopped "com.glasslauncher").ok "False"
$svc = "  * ServiceRecord{51d0 u0 com.glasslauncher/org.chromium.content.app.SandboxedProcessService0:0}`n    app=ProcessRecord{2b7 4958:com.google.android.webview:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:0/u0i9}`n    app=ProcessRecord{9a1 3210:com.glasslauncher/u0a98}"
Check "service pids" ((Get-ServiceProcessPids $svc "3210") -join ",") "4958"
Check "service pids none" (@(Get-ServiceProcessPids "" "3210").Length) 0
$cand = @([pscustomobject]@{ pid = "4958"; name = "r0" }, [pscustomobject]@{ pid = "6123"; name = "r1" })
Check "renderers attributed" ((Select-GlassRenderers $cand @("4958")).renderers | ForEach-Object { $_.pid }) "4958"
Check "renderers ambiguous" ((Select-GlassRenderers $cand @()).error -like "2 WebView renderer*") "True"
Check "renderers single" (@((Select-GlassRenderers @($cand[0]) @()).renderers).Length) 1
Check "renderers single lone object (PS 5.1)" (@((Select-GlassRenderers $cand[0] @()).renderers).Length) 1
Check "renderers other apps only" (@((Select-GlassRenderers $cand @("7777")).renderers).Length) 0
Check "renderers none" (Select-GlassRenderers @() @()).attribution "none running"

# ---- harness v5: REAL captures from the owner's VIP7802 smoke run (2026-11-12, Android 11, WebView 157) ----
$fx = Join-Path $PSScriptRoot "fixtures/vip7802-android11"
function Fx([string]$n) { return [IO.File]::ReadAllText((Join-Path $fx $n)) }
$svcReal = Fx "dumpsys-activity-services.txt"
Check "real: services attribute renderer 13074 only (utilityhub's 1548 excluded)" ((Get-ServiceProcessPids $svcReal "13040" "com.glasslauncher") -join ",") "13074"
Check "real: unfiltered dump would also pick utilityhub" (((Get-ServiceProcessPids $svcReal "13040") -join ",") -like "*1548*") "True"
$candReal = @([pscustomobject]@{ pid = "13074"; name = "com.google.android.webview:sandboxed_process0:org.chromium.content.app.Sand" })
Check "real: renderer selected" ((Select-GlassRenderers $candReal (Get-ServiceProcessPids $svcReal "13040" "com.glasslauncher")).attribution) "dumpsys activity services"
Check "real: renderer meminfo" (ConvertFrom-Meminfo (Fx "meminfo-renderer.txt")).pss_kb 70310
$idReal = Get-PackageIdentity (Fx "dumpsys-package.txt")
Check "real: versionName" $idReal.versionName "1.5"
Check "real: versionCode" $idReal.versionCode 6
Check "real: primaryCpuAbi null = no native library" ($null -eq $idReal.primaryCpuAbi) "True"
Check "real: signature digest" $idReal.signatures "f30c21bb"
CheckLike "real: build kind" (Get-BuildKind $idReal $null) "no native library*NOT a Rust-enabled Glass 2.0 build*"
Check "glass2 kind with native lib" ((Get-BuildKind @{ primaryCpuAbi = "armeabi-v7a" } $null) -like "has a native library (armeabi-v7a)*") "True"
Check "glass2 kind with rust log" (Get-BuildKind @{ primaryCpuAbi = "armeabi-v7a" } (Get-RustCoreStatus "11-12 I GlassRust: Rust core v0.1.0 (ok)")) "glass2 (Rust core v0.1.0 loaded)"
Check "rust unavailable" (Get-RustCoreStatus "I GlassRust: Rust core unavailable (ABI mismatch: 0.1.0;1)").status "ABI mismatch: 0.1.0;1"
Check "rust none" ($null -eq (Get-RustCoreStatus "nothing")) "True"
Check "sha256 parse" (Get-Sha256FromOutput "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855  /data/app/x/base.apk") "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
$idle = ConvertFrom-Gfxinfo (Fx "gfxinfo-idle.txt")
Check "real: idle frames 0" $idle.frames 0
Check "real: idle percentiles nulled (gfxinfo prints 4950ms for empty)" ($null -eq $idle.p50_ms) "True"
Check "real: idle window" $idle.stats_window_s 35.25
$navReal = ConvertFrom-Gfxinfo (Fx "gfxinfo-nav.txt")
Check "real: nav frames" $navReal.frames 418
Check "real: nav p50" $navReal.p50_ms 61
$coldReal = Fx "am-start-cold.txt"
$first = ($coldReal -split "--- run 2")[0]
$why = @(Get-ColdRejections (ConvertFrom-AmStart $first))
CheckLike "real: stale first cold launch rejected with reason" ($why -join ";") "*LaunchState=UNKNOWN*"
Check "real: valid cold launch accepted" (@(Get-ColdRejections (ConvertFrom-AmStart (($coldReal -split "--- run 2")[1]))).Length) 0

$latLog = "11-12 I GlassLatency: probe on ui=native`n11-12 I GlassLatency: ui=native key=22 ms=41.5 frame_ms=9.0`n11-12 I GlassLatency: ui=native key=20 ms=58.25 frame_ms=11.2`n11-12 I GlassLatency: ui=webview key=22 ms=80.0 frame_ms=60.1"
Check "latency native samples" ((Get-LatencySamples $latLog "native") -join ",") "41.5,58.25"
Check "latency webview samples" ((Get-LatencySamples $latLog "webview") -join ",") "80"
Check "latency none" (@(Get-LatencySamples "" "native").Length) 0

Write-Host "bench-lib tests: $script:pass passed, $script:fail failed"
if ($script:fail) { exit 1 }
