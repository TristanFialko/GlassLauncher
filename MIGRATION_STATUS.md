# Glass Launcher 2.0 — Migration status

_Last updated: 2026-11-12 · branch `glasslauncher-2.0-migration` (PR #2, open, not merged) · baseline
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

Not verified yet: CI for `7f0acd9`/`26f8055` was still running when this was written; check the PR. Nothing in this session ran on the VIP7802.

### Rendering and threading review (code reading only; change after a device trace)

Candidates for the owner's `-Trace` run, in rough order of expected cost on the box:

1. **Search dimming layer.** While Search is open, `NativeHomeView.onDraw` wraps the whole stage in a full-screen `saveLayerAlpha`, so every frame Search redraws (caret, typing, focus animation) also pays a 1920×1080 offscreen pass for Home. Option: bound the layer to the rows and their shadows, or draw the stage with per-paint alpha.
2. **Backdrop overdraw.** Every frame draws the full-screen wallpaper and then a full-screen shade (three passes during the theme crossfade). Option: bake the shade into the wallpaper bitmaps at load, keeping the blur source unshaded.
3. **Memory (PSS gate).** Both themes' wallpapers stay decoded at 1920×1080 ARGB (about 16 MB), plus card art for both themes. Option: after a crossfade, keep only the current theme's full-size wallpaper.
4. **Per key press.** `moved()` allocates a `ValueAnimator` and a listener (input path, not `onDraw`). Option: reuse one animator.

Checked and fine: Home, Control Centre and icon drawing preallocate paints, paths, shaders and rects; the docks' fade layer exists only while rows scroll; art is decoded off the UI thread at display size; Control Centre system calls and polling run on its worker. UI-thread file reads left on purpose: `NativeHomeConfig.load` (`home.json`) in `onCreate` and `UiRouter.mode` in `MainActivity`. Both are small and needed before the first frame or the routing decision.

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
on `glasslauncher-2.0-migration` to `C:\glass\glass2\app-debug.apk`.

1. Install and confirm identity:
   ```bat
   git fetch origin && git checkout glasslauncher-2.0-migration && git pull
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
