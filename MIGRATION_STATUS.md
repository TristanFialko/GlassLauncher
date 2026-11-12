# Glass Launcher 2.0 — Migration status

_Last updated: 2026-11-13 · branch `glasslauncher-2.0-consolidated` (from `glasslauncher-2.0-migration` at `3522999`; PR #2 open on the old branch, not merged) · baseline
`glasslauncher-1.5-phase7-baseline` (`452320a`) · app version `2.0.0-dev` (versionCode 7)_

## Summary (read this first)

- **The native UI is now code-complete for every surface the WebView UI has** (Home, Recently Used, header, Control
  Centre, Search with on-screen keyboard, toasts, theme/wallpaper, empty/error states). It is Kotlin, drawn with custom
  Views, in its own `:native` process. Status per item: `docs/nativeui/PARITY_CHECKLIST.md` §5.
- **The WebView UI is still the HOME screen by default.** The native UI becomes HOME only when the owner opts in
  (commands below), and it falls back to the WebView UI by itself after 2 crashes in 10 minutes.
- **Nothing is verified on the VIP7802 yet.** Verified: JVM unit/parity tests and an Android TV 1080p **emulator** in CI
  (software GPU). Emulator timings are never device evidence.
- **WebView/HTML/CSS/JS are not removed yet**, on purpose: they are the fallback until the owner's device checklist and
  A/B pass (Stage 4 in `docs/nativeui/MIGRATION_PLAN.md`). The removal inventory is ready (below).

## Consolidation (2026-11-13)

- New working branch `glasslauncher-2.0-consolidated`, created on GitHub from the verified head of `glasslauncher-2.0-migration`.
  Starting SHA: `3522999a54f0e4aae6d6103d22d617ab0d7d8d65` (identical to `glasslauncher-2.0-migration`, so it contains every commit
  listed below). `main` stays at the baseline `452320a` (tag `glasslauncher-1.5-phase7-baseline`). The old branch and PR #2 are unchanged.
- CI at `3522999` (Actions run 43303322890, 2026-11-12 20:44–20:50 UTC): all six jobs succeeded: Rust fmt/clippy/tests/parity;
  Android build + JVM/JNI tests (includes the `libglass.so` armeabi-v7a/arm64-v8a packaging check); Instrumented test (x86_64 emulator,
  API 30); Native UI prototype (emulator, TV 1080p, API 30); Baseline APK; Benchmark harness on Windows PowerShell 5.1. The `NativeUiPrototypeTest`
  timeout seen at `2f056ef` is resolved at this head.
- CI now also runs on pushes to `glasslauncher-2.0-consolidated` (`.github/workflows/ci.yml`), because this branch has no PR of its own.
- Docs reconciled against the code (labels: implemented and JVM-tested / exercised on emulator / verified on physical hardware /
  intentionally different / historical). Stale Stage 1 statements that Search and the Control Centre Search tile were toasts, or that
  no emulator had run Search, are now marked *Historical* in `PROTOTYPE.md`, `SEARCH.md` and `PARITY_CHECKLIST.md`.
- Checked by code reading at `3522999`: native Search is wired into Home (`NativeHomeActivity.openSearch`) and into the Control Centre tile;
  settings shortcuts run on the `pm` executor; UI and key sounds are opened (`openFd`) on the worker; `NativeSearchView` and
  `NativeControlCentre` draw paths allocate nothing; `NativeHomeView` builds its two gradients once (lazy, cached). Release builds
  set `nativeUiExported=false` (debug `true`); package `com.glasslauncher`, minSdk 23, targetSdk 30, versionName `2.0.0-dev`, versionCode 7.
- Still unverified: everything on the VIP7802 (owner steps below).

## This session (2026-11-12, second half)

| Commit | What |
|---|---|
| `29a521d` | Kotlin Android plugin 2.3.21 (Java/Kotlin target 17). Version `2.0.0-dev` / code 7, so 2.0 is no longer indistinguishable from 1.5 (the cause of the smoke-run mix-up) |
| `f8a6fd6`, `5cba564` | `UiRouter`: opt-in native HOME through `MainActivity`, crash fallback, Back is a no-op when native is HOME. `NativeHomeActivity` is `exported` only in debug builds (release: reachable only through `MainActivity`) |
| `3629971` | Native prototype ported from Java to Kotlin, no behaviour change; date/clock in `HomeClock` with tests |
| `fc9e953` | Control Centre: every WebView tile, spatial focus, 2 s poll only while open and resumed, resume and volume-key sync, first-open location request |
| `f09ecd7`, `6b50fa8`, `b07d3af` | Home: Recently Used (persisted), header stops, card-art fallbacks (overrides, Android icons, placeholders, initials), Tab/pointer, package-change refresh, focus restore |
| `f0708b3`, `e3786af` | Search: catalog + Rust ranking (Kotlin fallback with parity tests), debounced off-thread engine, keyboard, results, wired into Home and the CC |
| `3375ae1`, `0846876` | Edge fade (N14), dark-wallpaper fallback (T5); the fade's offscreen layer exists only while rows scroll |
| `bc948bb` | `SettingsLauncher`, `FrameLatencyProbe`, `SystemControls` migrated to Kotlin |
| `3bb357a` | Emulator test of Search, Control Centre, theme, toast and HOME routing |

Verification for the head of this session: 114 JVM tests (incl. real-JNI Rust tests and Kotlin-vs-Rust ranking parity),
Rust fmt/clippy/tests, search/snapshot/config parity, harness tests (132 parser + 46 simulated-device checks, PowerShell
7 locally; 5.1 in CI), and the TV-emulator instrumented tests. The native-UI emulator test (`NativeUiPrototypeTest`, CI job `native-ui`) did **not** pass at `2f056ef`: it hit the job's 45-minute limit (see the follow-up session below). See the PR's checks for the CI run of the latest commit.

## Follow-up session (2026-11-13)

The previous agent's local workspace could not be reached, and GitHub had nothing newer than `2f056ef`, so any unpushed work from that session is lost. This session continued from the remote branch.

| Commit | What |
|---|---|
| `1bc1bc1` | Docs update for the session above |
| `e72e5e6`, `4f46a53`, `2f71ff9`, `2f056ef` | `NativeUiPrototypeTest` fixes for the emulator; `2f056ef` starts native Home with `am start -W --activity-clear-task` and logs more diagnostics. Its `native-ui` run was cancelled at the 45-minute job limit: the test's shell reads had no timeout, so one stuck `am start -W` hung the job |
| `7f0acd9` | Bounded shell reads in `NativeUiPrototypeTest`: 60 s per command, 20 s per `am start -W` (non-fatal), 8 min per test. A stuck command now ends the test with a `[sh timed out …]` log line instead of hanging CI |
| `26f8055` | Search `onDraw` no longer allocates (font metrics were re-read per bar, row and key: about 50 objects per frame). Search's settings shortcuts call `startActivity` on the `pm` executor instead of the UI thread. Navigate/click sound `openFd` moved from `onCreate` to the worker |

Harness v5.1 (`tools/bench`): native runs also report the main `com.glasslauncher` process in `memory.main_process` (never summed). It can stay cached after the router hands over to native HOME, or be relaunched by Android when a WebView-mode Glass is the default HOME, and the PSS gate should not ignore it silently.

*Historical:* CI for `7f0acd9`/`26f8055` was still running when this was written. It has since passed at `3522999` (see "Consolidation" above). Nothing in this session ran on the VIP7802.

### Rendering and threading review (code reading only; change after a device trace)

Candidates for the owner's `-Trace` run, in rough order of expected cost on the box:

1. **Search dimming layer.** While Search is open, `NativeHomeView.onDraw` wraps the whole stage in a full-screen `saveLayerAlpha`, so every frame Search redraws (caret, typing, focus animation) also pays a 1920×1080 offscreen pass for Home. Option: bound the layer to the rows and their shadows, or draw the stage with per-paint alpha.
2. **Backdrop overdraw.** Every frame draws the full-screen wallpaper and then a full-screen shade (three passes during the theme crossfade). Option: bake the shade into the wallpaper bitmaps at load, keeping the blur source unshaded.
3. **Memory (PSS gate).** Both themes' wallpapers stay decoded at 1920×1080 ARGB (about 16 MB), plus card art for both themes. Option: after a crossfade, keep only the current theme's full-size wallpaper.
4. **Per key press.** `moved()` allocates a `ValueAnimator` and a listener (input path, not `onDraw`). Option: reuse one animator.

Checked and fine: Home, Control Centre and icon drawing preallocate paints, paths, shaders and rects; the docks' fade layer exists only while rows scroll; art is decoded off the UI thread at display size; Control Centre system calls and polling run on its worker. UI-thread file reads left on purpose: `NativeHomeConfig.load` (`home.json`) in `onCreate` and `UiRouter.mode` in `MainActivity`. Both are small and needed before the first frame or the routing decision.

### Rendering changes from that review (branch `glasslauncher-2.0-render-opt`, not on a device yet)

All four candidates above are implemented on `glasslauncher-2.0-render-opt` (draft PR into `glasslauncher-2.0-consolidated`). They were written by code reading in a sandbox with no Gradle, Kotlin compiler or emulator, so **CI is the first compile**. They need the owner's `-Trace` run and the A/B/PSS bench before merging.

| Item | Change | Files |
|---|---|---|
| 1. Search dimming layer | The full-screen `saveLayerAlpha` is gone. `drawRowsMasked(c, alpha)` uses one layer for both the Search dimming and the scroll fade mask, limited to what the rows paint (60 px halo above; the baked dock shadow, 124 px, below), and only while the rows are dimmed or reach a fade band. At rest with Search closed there is still no layer. | `NativeHomeView` |
| 2. Backdrop overdraw | The home shade (`#bg::after`) is baked into each full-size wallpaper once (`NativeArt.shaded`), after the blurred glass backdrop is made from the unshaded image. `drawBackdrop` draws the shade only over the bare base colour (no wallpaper yet, or a failed decode). Source-over is affine in the destination, so the crossfade gives the same pixels as before. | `NativeArt`, `NativeHomeView`, `NativeHomeActivity` |
| 3. Memory (PSS gate) | Only the on-screen theme keeps its full-size wallpaper (about 8 MB at 1920x1080 instead of 16 MB). The other theme's wallpaper is decoded only to make its 240x135 blur, then recycled. A theme flip asks the activity (`Host.wallpaperNeeded`) to decode it on the worker, and the 700 ms crossfade starts when it arrives (`wallpaperLoaded`). After the crossfade settles, the hidden wallpaper is dropped (never `recycle()`d, since display lists may still hold it). Both blurred backdrops and both themes' card art stay loaded, so Search, Control Centre and the cards never wait. | `NativeHomeView`, `NativeHomeActivity` |
| 4. Per key press | `anim()` reuses each slot's `ValueAnimator` (`setFloatValues` + `start`) and every caller passes a preallocated `Setter`, so `moved()`, Control Centre, Search and toast animations no longer allocate an animator, value holder, listener and lambda per press. The interpolator is set explicitly (`ACCEL_DECEL`, ValueAnimator's default) because a reused animator keeps the previous one. | `NativeHomeView`, `NativeControlCentre` |

Smaller fixes in the same change:

- The bottom dock shadow was clipped at +60 px while the rows scrolled through the fade band (the layer used the 60 px halo, but the baked shadow reaches 124 px below the dock). The layer now covers it, so the shadow fades instead of being cut.
- The theme crossfade could stop a hair short of 1 (float lerp), leaving the dark wallpaper drawn at alpha 254 over the light one for good. Its end now snaps to the target, which also lets the hidden wallpaper be dropped.
- `loadAssets` takes the theme from `onCreate` instead of reading `view.dark` on the worker thread.

Trade-offs and what to check on the box:

- **Theme flip latency:** a manual flip (Control Centre > Appearance, Search theme toggle, or the day/night change) now waits for one 1920x1080 JPEG decode before the crossfade starts. Expected about 100-300 ms on the VIP7802; look for the `Glass:nativeWallpaper` section in the `-Trace` run. If it feels slow, keep both wallpapers again (revert item 3 only) and accept the extra 8 MB.
- **Frames with Search open:** `Glass:nativeDraw` should get cheaper while typing in Search; compare against `glasslauncher-2.0-consolidated` in the same harness run.
- **PSS:** the native process should be about 8 MB smaller from startup on, since the second theme's full-size wallpaper is no longer kept.
- `NativeUiPrototypeTest`'s Appearance check sleeps 800 ms after the toggle; the decode makes the crossfade start later, but any change in the backdrop still counts as a difference.

## Language and process layout

| Layer | Language | Files |
|---|---|---|
| Native UI (production candidate) | Kotlin | `app/src/main/kotlin/com/glasslauncher/*` — `NativeHomeActivity`, `NativeHomeView`, `NativeControlCentre`, `ControlCentreController/Logic`, `NativeSearchView`, `Search*`, `HomeLogic`, `HomeClock`, `NativeArt`, `NativeIcons`, `SvgIcons`, `UiRouter` |
| Android platform glue | Kotlin | `SettingsLauncher`, `SystemControls`, `FrameLatencyProbe` |
| Android platform glue | Java (kept) | `AppDiscovery` (shared by both UIs, icon serving for the WebView), `RustCore` (JNI symbol names/static loader; stable, tested) |
| WebView UI (fallback, to be removed) | Java + JS/HTML/CSS | `MainActivity` (also the HOME router), `GlassBridge`, `assets/index.html`, `assets/js/*`, `assets/css/*`, `assets/apps.js` |
| Core logic | Rust | `rust/glass-core` (search normalise/score/rank, snapshot preparation), `rust/glass-jni`, `rust/glass-cli` |

## Rust migration matrix

| Subsystem | Language now | Rust responsibility | Why / why not | Parity tests | Measured impact |
|---|---|---|---|---|---|
| Search normalisation, scoring, ranking, index | **Rust** (Kotlin fallback; JS fallback in the WebView UI) | all of it | pure, per keystroke | `search-parity.js` 8,832 rankings; emulator 1,668 queries; Rust unit; `SearchRankingParityTest` (Kotlin fallback == Rust) | emulator: −22…33 % per query via bridge |
| Search snapshot preparation (registry rules) | **Rust** | show flag, Glass itself, hidden vendor prefixes, `available`, `supersededBy`, ordering, index | pure rules over plain data | `snapshot-parity.js` 8 cases; `SearchCatalogFixtureTest` (Kotlin catalog → Rust == pre-migration `entries()`) | no measurable end-to-end gain (host ~249 → ~217 µs); kept for one implementation of the rules |
| Native Search catalog gathering | Kotlin | none | Android facts (installed apps, settings availability) | fixture test above | — |
| Configured-card resolution | Kotlin (`NativeHomeConfig.resolve`) | none | ~10 cards once per load; a JNI hop costs more than it saves | `NativeHomeConfigTest` | not worth porting |
| Control Centre texts/focus tables | Kotlin (`ControlCentreLogic`) | none | trivial, UI-coupled | `ControlCentreLogicTest` | — |
| Recently Used, header/Tab rules | Kotlin (`HomeLogic`) | none | trivial | `HomeLogicTest` | — |
| App discovery, icons, settings, Wi-Fi/BT/volume | Java/Kotlin | none | Android APIs | emulator tests | discovery −76…79 % (Java change) |

Audit result: no remaining pure, CPU-heavy logic justifies more Rust. The native UI removes the per-session JS→Rust
bridge; Rust is called directly from Kotlin on a worker thread (`SearchEngine`). ABI: `armeabi-v7a` and `arm64-v8a`
(`libglass.so` in every Rust-enabled APK; CI checks both are packaged). JNI errors return null and fall back; panics are
caught at the JNI boundary (`glass-jni`).

## WebView / HTML / CSS / JS dependency inventory (for Stage 4 removal)

| Item | Class | Removal |
|---|---|---|
| `MainActivity` WebView setup, `BACK_JS`, `glassResume`/`glassVolumeSync` calls | production runtime (fallback UI) | Stage 4; keep `MainActivity` as the HOME entry that hosts/forwards to the native UI |
| `GlassBridge.java` (`@JavascriptInterface`) | production runtime | Stage 4 |
| `AppDiscovery.iconResponse` (`WebResourceResponse`) | production runtime | Stage 4 (native uses `iconPng`) |
| `assets/index.html`, `assets/js/*`, `assets/css/*`, `assets/apps.js` (18 APK entries) | production runtime | Stage 4; `apps.js` values already live in `assets/nativeui/home.json` (drift check in CI) |
| JS ranking fallback in `registry.js` | production runtime | Stage 4 (Kotlin fallback replaces it) |
| `app/src/main/assets_baseline`, `assets_phase1..3` | not packaged (not a source set) | historical reference; may be moved to `docs/` |
| `tools/parity/*.js`, fixtures | dev tooling / CI | keep (they become fixture-only once `registry.js` is gone) |
| `GlassEndToEndTest` (WebView↔bridge↔Rust) | test | remove with the WebView |
| `FrameLatencyProbe` `ui=webview` label, harness `-Activity .MainActivity` | tooling | keep until the A/B is finished |

## Performance (emulator/host unless stated — not hardware claims)

| Metric | Baseline | Current | Notes |
|---|---:|---:|---|
| Search per query (via bridge) | 184–325 µs | 123–254 µs | −22…33 %, emulator, WebView UI |
| App discovery | 14.9–57.1 ms | 3.4–12.2 ms | −76…79 %, emulator |
| 13 first-time icons, JS block | 55.1 ms | 0.7 ms | emulator, WebView UI |
| Background polling / 10 s | 5 calls | 0 | emulator |
| Native D-pad key→frame | — | 43–104 ms (earlier prototype) | software GPU; says nothing about the box |
| Cold/warm start, CPU, PSS, frame pacing on the VIP7802 | — | — | **no 2.0 device data yet** |

Native UI performance design (to be confirmed with device traces, not assumed): UI thread does keys and drawing only;
config, image decoding/scaling, wallpaper blur, package queries, icons, sounds and search run on worker executors; no
allocation in `onDraw`; artwork decoded once at display size; no live blur (pre-blurred wallpaper); the only
`saveLayer`s are the 450 ms row scroll through the fade band and Search's dimming.

## Owner smoke run (harness v4, earlier today): what it showed
It did **not** measure Glass 2.0: `primaryCpuAbi=null` (no `libglass.so`), so it was the 1.5 baseline or a Rust-disabled
build; both then reported `versionName 1.5`. None of its numbers are attributed to 2.0. It did validate the harness on
the box (3/3 warm samples valid, renderer attribution, background CPU). Harness v5 adds build identity (signing digest,
APK SHA-256, native-library presence), a discarded warm-up launch, per-sample rejection reasons, no fake percentiles for
empty histograms, and exact-package renderer matching. Its tests pass locally under PowerShell 7 (132 + 46) and in CI
under Windows PowerShell 5.1.

## Rollback
- Native UI → WebView UI on the box: `adb shell am start -W --activity-clear-task -n com.glasslauncher/.MainActivity --es glass.ui webview` (also automatic after 2 native crashes in 10 min).
- Source: `git checkout glasslauncher-1.5-phase7-baseline`; APK: CI artifact `glasslauncher-baseline-1.5-debug-apk`. 2.0 is versionCode 7 and 1.5 is 6, so going back needs `adb install -r -d` (debug APKs) or `adb uninstall com.glasslauncher` first (clears Glass's settings).
- Partial: `-Pglass.rust=false` (no native library; search uses the Kotlin/JS fallbacks), `PERF.core:'js'` in `apps.js`.

## What the owner needs to do next (when the TV is free, about 1.5 h)

All commands are Windows CMD from the repository root. Download `glasslauncher-debug-apk` from the latest green CI run
on `glasslauncher-2.0-consolidated` (or PR #2's run at `3522999`, the same app code) to `C:\glass\glass2\app-debug.apk`.

1. Install and confirm identity:
   ```bat
   git fetch origin && git checkout glasslauncher-2.0-consolidated && git pull
   adb install -r C:\glass\glass2\app-debug.apk
   adb shell dumpsys package com.glasslauncher | findstr /C:"versionName" /C:"primaryCpuAbi"
   ```
   Expect `versionName=2.0.0-dev` and `primaryCpuAbi=armeabi-v7a`. If not, stop and send the output.
2. Look at the native UI without changing HOME (Back returns to the WebView home):
   ```bat
   adb shell am start -n com.glasslauncher/.NativeHomeActivity
   ```
   About 10 minutes with the remote, going through the checklist below. Note anything that looks or behaves differently from the WebView home.
3. A/B benchmark, alternating, one sitting, hands off the remote during runs:
   ```bat
   tools\bench\bench-device.bat -Label web-1 -Apk C:\glass\glass2\app-debug.apk -CompileSpeed -Latency -Runs 5 -IdleSeconds 30 -NavRounds 10 -BackgroundPackage com.google.android.youtube.tv
   tools\bench\bench-device.bat -Label native-1 -Activity .NativeHomeActivity -CompileSpeed -Latency -Runs 5 -IdleSeconds 30 -NavRounds 10 -BackgroundPackage com.google.android.youtube.tv
   tools\bench\bench-device.bat -Label web-2 -CompileSpeed -Latency -Runs 5 -IdleSeconds 30 -NavRounds 10 -BackgroundPackage com.google.android.youtube.tv
   tools\bench\bench-device.bat -Label native-2 -Activity .NativeHomeActivity -CompileSpeed -Latency -Runs 5 -IdleSeconds 30 -NavRounds 10 -BackgroundPackage com.google.android.youtube.tv
   ```
   Each run must print `Build under test: … has a native library (armeabi-v7a)`. If not, stop and send that folder.
4. Only if 2 and 3 look fine: try the native UI as HOME, then reboot once to check recovery.
   ```bat
   adb shell am start -W -n com.glasslauncher/.MainActivity --es glass.ui native
   adb reboot
   ```
   After the reboot the native UI should come up as the home screen; press Home from inside an app to check it returns. Switch back at any time with:
   ```bat
   adb shell am start -W --activity-clear-task -n com.glasslauncher/.MainActivity --es glass.ui webview
   ```
5. Send the four `bench-*` folders and your notes.

### Remote checklist for step 2 (P1 first)
- Home: clock/date ("Thursday 12 November" style, no comma), 9 cards, artwork crisp, focus scale/halo/shine, Left/Right/Down/Up incl. carrying the column between rows, row 2 collapses the clock.
- Header: Up from row 0; Recently Used appears after launching an app and relaunches it; Search; Control Centre.
- Launch an app from a card, press Home/Back to return: same card still focused, Recently Used updated.
- Control Centre: every tile, Left/Right on Volume, OK mutes, remote volume keys move the slider, Wi-Fi name shown (first open asks for location permission once), Appearance toggles theme, Settings/Sound/Display open and Back returns with fresh values.
- Search: on-screen keyboard (abc/123, Shift, Backspace, clear, space), results with icons, OK launches/opens, "Volume" opens the CC on the volume row, Back restores focus.
- Leave it 10 minutes, then sleep/wake the box: clock correct, no stuck animation, no crash.
- Open/close the Control Centre and Search 20 times quickly: no stutter build-up or stuck dimming.

## Next engineering actions
1. Analyse the A/B against the Stage 0 gate in `docs/nativeui/MIGRATION_PLAN.md`; profile with `-Trace` before optimising anything.
2. Fix whatever the owner's checklist finds; re-run the emulator tests.
3. After the device gate and one release with native as HOME without regressions: Stage 4 removal per the inventory above, then promote `NativeHomeActivity` to the HOME entry directly.
