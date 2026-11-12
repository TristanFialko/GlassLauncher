# Device benchmark methodology (harness v4)

`tools/bench/bench-device.ps1` measures an installed build over adb. v2 replaced v1 after the first VIP7802 comparison
(2026-11-12) exposed problems in v1's method. v3 fixes the device-specific problems found in the first VIP7802 run of v2.
This page lists each problem, how the harness handles it now, and how to run a valid A/B. Summaries from v1 are refused by
`compare.ps1`; summaries from different harness versions trigger a warning.

## What was wrong with v1, and what v2 does

| # | v1 problem | Evidence | v2 behaviour |
|---|---|---|---|
| 1 | **Warm start never verified that Glass left the screen.** v1 opened `android.settings.SETTINGS`, waited 2 s and ran `am start -W` on Glass. On Android TV, Settings usually opens as a side panel *over* the launcher, so Glass is only paused and still visible. `am start` then just brings the existing task to the front. No new frame is drawn, so the `TotalTime` it prints does not measure a warm launch. v1 kept the number without checking `LaunchState`, the `Warning:` line or Glass's state. | Device run: all 10 warm samples were identical within each run (baseline 1193 ms, Glass 2.0 1246 ms) and roughly equal to the cold-start medians. A real warm start of a live process should be much faster than a cold start and should vary. The exact output is in each run's `warm.txt`; it was not sent, so the mechanism above is the most likely one, not confirmed. | Glass is put behind a **full-screen** app (`-BackgroundPackage`; default is the first installed of YouTube, Netflix or Play Store). The harness reads `dumpsys activity activities` and keeps a sample only if Glass was `STOPPED`, its process survived, `Status: ok` and a `TotalTime` was printed. Each sample records `LaunchState`, any warning and logcat's `Displayed` time. Rejected samples are listed with the reason. If every sample is identical, the run is flagged invalid. |
| 2 | **Cold start samples were not validated.** Force-stopping the HOME app can let the system relaunch home itself, so a "cold" sample may not be cold. | Not observable from the summary. | Only `LaunchState: COLD` samples are kept. Logcat `Displayed` is recorded as a cross-check. Median, p95, IQR and every sample are reported. |
| 3 | **"D-pad p50/p95" were frame times, not D-pad latency.** gfxinfo reports how long each rendered frame took. | — | Renamed. The summary says these are rendering frame times. Key-to-screen latency needs `-Trace` (Perfetto, read in ui.perfetto.dev). |
| 4 | **No control for frame attribution.** gfxinfo *was* reset just before the key sequence. But nothing showed whether the frames came from the key presses or from continuous redraws. | 2,100 frames for 120 keys ≈ 17.5 frames per key. The CSS has no infinite animation on the home screen; only the Search caret blinks. Each key starts 0.22–0.7 s focus animations (tile scale 0.22 s, focus sheen 0.7 s, row scroll 0.45 s). At ~61 ms per frame, and with each PC-side `adb shell input keyevent` taking well over 150 ms, the box was probably animating for nearly the whole sequence. That points to key-driven frames, but it was never measured. | After the D-pad window, gfxinfo is reset and the same duration is recorded **idle**. Frames per key = (D-pad frames − idle frames) / keys. Keys are now sent by one on-device loop at a fixed interval (`-KeyIntervalMs`, default 500) instead of one adb round trip per key. |
| 5 | **Percentiles are coarse.** gfxinfo percentiles are histogram bucket edges. Above ~50 ms the buckets are 4 ms wide (53, 57, 61, 65, 69, 73, 77 …). | p95 77 → 73 ms is exactly one bucket, from one run each. That is not evidence of an improvement. | Each percentile is reported with its bucket range, from the full HISTOGRAM. Per-frame durations come from `framestats` (last ~120 frames, flagged frames excluded). |
| 6 | **Refresh rate and frame deadline were not recorded.** | gfxinfo's "Janky" means the frame missed its vsync deadline: 16.7 ms at 60 Hz, 20 ms at 50 Hz. At p50 61 ms, almost every frame misses by about 3× either way. So "2090 / 2100 janky" is a correct reading: the box renders focus animations at roughly 15–16 fps in **both** builds. | Refresh rate is read from `dumpsys SurfaceFlinger` / `dumpsys display`. The harness reports the deadline and the share of frames slower than 1–4 vsync periods. |
| 7 | **PSS was null and the failure was silent.** v1 looked for the Android 12+ `TOTAL PSS:` line, then a fallback regex. When neither matched it stored null and gave no reason. | `pss_kb: null` in both runs. The cause is in each run's `meminfo.txt`, which was not sent. | The parser handles the Android 8–11 `TOTAL:` summary, the Android 12+ `TOTAL PSS:` summary and the table `TOTAL` row (unit-tested, including CRLF output). It samples 3 times by PID. On failure it records the reason and the first lines of output as a warning. |
| 8 | **CPU and memory ignored the WebView renderer process.** With multiprocess WebView (the Android 8+ default on most devices), JavaScript, layout and raster run in a separate `…:sandboxed_process0` process. | v1's foreground CPU (1.98 → 1.48 %) and background CPU (1.55 → 1.20 %) covered only the app process, one 60 s window each. | The harness finds renderer processes with `ps` and reports app and renderer CPU and PSS separately, plus their sum. It records whether WebView runs single- or multi-process, and warns if more than one renderer exists. CPU is sampled in 3 windows. |
| 9 | **"Background" CPU may not have been background.** It was also measured behind Settings. | Same as #1. | Measured behind the full-screen app, after verifying Glass is `STOPPED`. |
| 10 | **Test conditions were not recorded.** Glass picks the dark theme from 19:00–06:59, with a different wallpaper and artwork. A freshly installed APK is not yet fully compiled by Android. Each CI run signs debug APKs with a different key, so switching builds needs an uninstall, which clears saved settings. | — | The harness records device time, expected theme, `dexopt` status and last update time. `-CompileSpeed` compiles both builds the same way first. `compare.ps1` warns when model, resolution, density, refresh rate, WebView, expected theme, compile status or background app differ. |
| 11 | **Comparisons ignored noise.** v1 compared single medians. | Cold 1209.5 → 1249.5 ms (+40 ms) with unknown spread. | `compare.ps1` reports median, IQR and n. It calls a difference better or worse only if both runs have enough valid samples and the medians differ by more than the larger IQR. Otherwise it reports "within noise". It refuses v1 summaries. |
| 12 | Windows PowerShell 5.1: adb stderr output combined with `$ErrorActionPreference = "Stop"` can abort the script. | Not hit in the device run. | adb output is collected as text with errors non-terminating. |

## What the first VIP7802 run of v2 exposed, and what v3 does

| # | v2 problem (from the owner's `warm.txt` / `meminfo.txt`) | Cause | v3 behaviour |
|---|---|---|---|
| 13 | **Glass's state was always blank.** Every warm sample was rejected as "Glass was  (not STOPPED)". | Android 11 mentions Glass's activity in a task summary line (`Activities=[ActivityRecord{...}]`) before its own `* Hist #0: ActivityRecord{...}` entry. v2 started reading at the summary line and stopped at the Hist entry, before its `state=` line. Reproduced: the v2 parser returns an empty state on an Android 11 fixture. | Only the activity's own `Hist #N` entry is read, up to the next Hist/Task header. If the record or its `state=` line is missing, the sample is rejected and the diagnostic says which. |
| 14 | **The background app never displaced Glass.** Every Glass relaunch printed `Warning: Activity not started, its current task has been brought to the front`, with the same PID. | `monkey -p com.google.android.youtube.tv ...` returned on the box without bringing YouTube to the front. | The background activity is resolved explicitly with `cmd package resolve-activity --brief -a MAIN -c LEANBACK_LAUNCHER <pkg>` (LAUNCHER as a fallback) and started with `am start -W -n <pkg/cls>`. `-BackgroundComponent pkg/cls` overrides it. |
| 15 | **Nothing confirmed what was actually in front.** | — | For up to `-BackgroundWaitSeconds` (default 15 s), the harness polls `dumpsys activity activities` (`ResumedActivity` / `mResumedActivity`) and `dumpsys window windows` (`mFocusedApp`, `mCurrentFocus`). It accepts only when the background package is in front **and** Glass's own record is `STOPPED`. Android stops a covered activity only after the new one goes idle, or after a 10 s idle timeout if it keeps animating (video autoplay), hence the polling. Otherwise **no Glass launch is timed** and the sample is rejected with a diagnostic, e.g. `com.google.android.youtube.tv did not come to the front after 15.1 s (resumed=com.glasslauncher, focused app=com.glasslauncher, ...); Glass state=RESUMED`. A relaunch that still prints an `am` warning is also rejected. |
| 16 | **Renderer PSS was blank in the summary** even though `meminfo.txt` had it (renderer PID 4958, ~63.5 MB). | `RendererPids` returned a one-element array, which PowerShell unwraps into a single object. Windows PowerShell 5.1 gives a lone `[pscustomobject]` no `.Count`, so `$rend.Count` was empty and the renderer sum was dropped. Renderer CPU was blanked the same way. PowerShell 7, used for the earlier tests, does have `.Count`, which is why the tests passed. | Results are wrapped in `@()` and summed by `Get-SumOrNull([object[]])`, so one renderer works the same as several. `meminfo.txt` now records the parsed values for each sample. The summary has app, renderer and total PSS. |
| 17 | **Background CPU was recorded even when Glass wasn't confirmed hidden.** | Same as 14/15. | Background CPU is measured only after the check above passes, and re-checked afterwards. If either check fails, `cpu_background` is null and `cpu_background_rejected` gives the reason. App, renderer and combined CPU are reported for foreground idle and background. |

## What v3 would still have got wrong, and what v4 does

Found by auditing v3 against realistic Android 11 output before any further device run. All three are reproduced by
`tools/bench/test-bench-device.ps1` (below): it fails 19 checks against the v3 script and passes against v4.

| # | v3 problem | Cause | v4 behaviour |
|---|---|---|---|
| 18 | **Every warm sample would still be rejected.** | Relaunching a Glass whose task is behind another app *always* prints `Warning: Activity not started, its current task has been brought to the front` (that message is the warm/hot relaunch itself; row 14's v2 log shows it). v3 rejected any sample with an `am` warning. This also means v2's YouTube launches most likely *did* displace Glass, and the v2 rejections came from the state parser (row 13). | Only that warning is accepted (`Test-WarmRelaunchWarning`); any other warning — e.g. `delivered to currently running top-most instance`, meaning Glass was already in front — still rejects the sample. |
| 19 | **Another app's WebView would be counted as Glass's renderer.** | v3 summed every `sandboxed_process`/`privileged_process` on the device and only warned when there were several. | Glass's renderer is attributed via `dumpsys activity services com.glasslauncher` (WebView's renderer is an external isolated service whose ServiceRecord is filed under the host package, with `app=ProcessRecord{… <pid>:…sandboxed_process…}`). One unattributed renderer is accepted and flagged (`renderer_attribution`); several unattributed renderers are **rejected** (`renderer_rejected`, renderer and total null) instead of summed. The services dump is saved to `renderers.txt`. |
| 20 | **A trampolining background app failed the sample outright.** | `am start -W` of a splash/trampoline activity reports `Status: timeout`; v3 treated any non-`ok` status as fatal. It also required the requested package itself to be in front, which fails if it hands off to another package. | Only `Error:` is fatal. The polling decides: Glass's own record `STOPPED` **and** a package other than Glass in front (`requested_in_front` is recorded for review). |

Also: `meminfo` totals tolerate digit grouping (`63,536`), and the `Hist` header tolerates Android 12+ spacing.
`summary.json` is `schema_version` 3 / `harness_version` 4.

### Off-device verification

- `tools/bench/test-bench-lib.ps1`: 108 parser checks against Android 11 and 12+ fixtures.
- `tools/bench/test-bench-device.ps1`: runs the **real** `bench-device.ps1` against a simulated Android 11 TV (a fake
  `adb` function with an activity/window/services/meminfo/proc state machine). Scenarios: Android 11 with a trampolining
  YouTube, brought-to-front relaunches and a second app's WebView renderer (expects all warm samples valid, renderer PSS =
  63,536 KB from PID 4958 only, background CPU accepted); Glass never stopping (expects every warm sample and background
  CPU rejected with the reason); two unattributed renderers (expects renderer PSS/CPU rejected, app PSS kept); one
  unattributed renderer (accepted, flagged).
- CI runs both under PowerShell 7 (Linux) **and Windows PowerShell 5.1** (`windows-latest`, `powershell.exe`), the shell
  the owner uses — 5.1's array semantics are what blanked renderer PSS in v2.

Limits: the simulated dumps follow AOSP Android 11 formats, not captures from this box. The `dumpsys activity services`
attribution in particular is unverified on the VIP7802; if it finds nothing, the harness falls back to the single/ambiguous
rules above and says so. **v4 has not yet been run on the VIP7802.**

## Running a valid A/B comparison

1. Install the baseline APK. If adb reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall Glass first. Then apply the same steps to the other build, so both start from cleared settings.
2. Use the same box, the same hour band (both before 19:00 or both after, for the same theme), nothing else running, and the remote untouched.
3. Run each build twice, alternating (A, B, A, B), to see run-to-run variation:
   ```bat
   tools\bench\bench-device.bat -Label baseline-1 -Apk glasslauncher-baseline-1.5-debug.apk -CompileSpeed
   tools\bench\bench-device.bat -Label glass2-1   -Apk glasslauncher-debug.apk             -CompileSpeed
   tools\bench\bench-device.bat -Label baseline-2 -Apk glasslauncher-baseline-1.5-debug.apk -CompileSpeed
   tools\bench\bench-device.bat -Label glass2-2   -Apk glasslauncher-debug.apk             -CompileSpeed
   powershell -ExecutionPolicy Bypass -File tools\bench\compare.ps1 bench-baseline-1-*\summary.json bench-glass2-1-*\summary.json
   ```
4. Check the `WARNINGS` printed at the end of each run before reading any numbers.
5. Optional, for D-pad latency: add `-Trace` and open `glass-nav.perfetto-trace` at https://ui.perfetto.dev.

## What these numbers can and cannot say

- **Start times** run from the start of the launch to the first frame of the activity window. With a WebView, that frame
  appears before the home rows are filled in, so "first usable screen" is later. Measuring that needs an app-side marker,
  which has not been added because it would change the app.
- **Frame metrics** describe how smoothly the box renders Glass's animations. They are not the time from a key press to
  the focus change on screen.
- **CPU/PSS** come from one run of three windows or samples. Treat a single run's difference as a hint until a repeat
  run confirms it.
