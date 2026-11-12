# Glass Launcher 2.0 — Migration status

_Last updated: 2026-11-12 · branch `glasslauncher-2.0-migration` (PR #2, open, not merged) · baseline
`glasslauncher-1.5-phase7-baseline` (`452320a`)_

## Handover (2026-11-12): what was actually done before this session

Verified from the code, tests and CI (run `43236935685`, head `df114e5`, 18 commits, 47 files), not from status text:

- **Rust:** `glass-core` held one subsystem — search `norm`/score/rank and the per-session index (`search.rs`). JS still
  built every searchable entry (`entries()`), so the registry rules lived only in JavaScript. `glass-jni` (only `unsafe`
  crate) and `glass-cli` (host harness) as described in `docs/architecture/TARGET_ARCHITECTURE.md`.
- **Java (Android APIs, unchanged in scope):** package-wide launchable-app queries, WebView timers paused in background,
  icons served through `shouldInterceptRequest`, Perfetto sections, `RustCore` loader with ABI/self-test and fallback.
- **JS/HTML/CSS:** all UI, focus, animation and providers; JS ranking kept as fallback.
- **Benchmark harness v3:** fixed the v2 parser bug, but an audit with simulated Android 11 output showed it would still
  reject every warm sample and could count another app's WebView renderer (details: `docs/benchmarks/METHODOLOGY.md`,
  rows 18–20). v3 was never run on the box; v4 replaces it.

## This session

| Commit | What |
|---|---|
| `c181134` | Harness v4 + simulated Android 11 device test; CI runs the harness tests in PowerShell 7 and Windows PowerShell 5.1 |
| `e2ab394` | Parity fixtures captured from the pre-migration production `entries()` (8 cases) — before any behaviour change |
| `018e627` | Search snapshot preparation moved into Rust (ADR 0005): production path, JS fallback, parity + JNI + emulator tests |
| (docs) | This file, ADR 0005, architecture, methodology, results |

## Rust migration matrix

| Subsystem | Source | Language now | Rust responsibility | Why / why not | Workload | Parity tests | Measured end-to-end impact |
|---|---|---|---|---|---|---|---|
| Search normalisation, scoring, ranking, index | `registry.js` `norm`/`fieldScore`/`score`/sort | **Rust** (JS fallback) | all of it | pure, per keystroke | every debounced keystroke, ~230 entries | `search-parity.js` 8,832 rankings; emulator 1,668 queries; Rust unit | emulator: −22…33 % per query via bridge |
| Search snapshot preparation (registry rules) | `registry.js` `entries()`, built-in app provider, `supersededBy` | **Rust** (JS fallback) — new | show flag, Glass itself, hidden vendor prefixes, `available` result, `supersededBy`, ordering, index build; refs back to JS | pure rules over plain data; lazily creating entries removes ~230 objects/closures per session | once per Search session | `snapshot-parity.js` 8 fixture cases; `search-parity.js` through the snapshot path; `RustCoreJniTest` 2 new; emulator snapshot == `entries()` | host: ~249 → ~217 µs/session (JS side 125 → 79 µs); emulator through the bridge: 600 → 700 µs median, i.e. no measurable gain (timer resolution ~100 µs); **no device number** |
| `available()` checks | `shortcuts.js` → `hasSettings` bridge | JS + Java | none | Android query (PackageManager) behind the bridge, cached | once per kind | — | — |
| Provider callbacks, `run` closures | `registry.js`, `shortcuts.js` | JS | none | JS functions / launch side effects | — | — | — |
| Configured-card resolution | `appdata.js` `G.apps.resolve` | JS | none (for now) | pure, but runs once at load over ~10 cards; a JNI hop would cost more than it saves | once per load | — | not worth porting |
| App discovery | `AppDiscovery.java` | Java | none | PackageManager / Binder | boot + package changes | emulator identical-output test | emulator −76…79 % (Java change) |
| Icons | `AppDiscovery.java`, `icons.js` | Java + JS | none | Bitmap/Drawable APIs | first view per app | emulator 13/13 | 13 icons JS block 55.1 → 0.7 ms (Java change) |
| Recently Used, theme, sounds, toast, store | `recent.js`, `theme.js`, `glass.js` | JS | none | DOM, `localStorage`, audio; trivial logic | rare | — | — |
| Home rows, Search UI, keyboard, Control Centre | `home.js`, `search.js`, `keyboard.js`, `controlcentre.js` | JS | none | DOM geometry, focus, animation (ADR 0001) | per key | — | rendering decision waits for device frame data |
| Wi-Fi/Bluetooth/volume/settings | `SystemControls.java`, `SettingsLauncher.java` | Java | none | Android APIs | user actions | — | — |

The audit found no other pure computation with meaningful workload: everything else is Android API, DOM, or trivial.

## Phases
| Phase | Status |
|---|---|
| 0 Baseline & safety | Done |
| 1 Audit | Static done; device metrics pending |
| 2 Rust integration | Done; ABI 2 |
| 3 Core logic | Search ranking + search snapshot preparation in Rust with exact parity; JS fallback retained |
| 4 Bridge | 1 coarse call per Search session + 1 per query; icons bypass the bridge |
| 5 Performance | Emulator/host gains below; device unmeasured |
| 6 Rendering | Native home prototype built (`docs/nativeui/`), emulator-verified; **decision waits for the owner W/N A/B on the VIP7802** |
| 7 Device validation | v4 smoke run on the box validated warm/renderer/background checks but measured a build without the native library; harness v5 fixes identity and the issues found |
| 8 Release hardening | Not started; APK unsigned |

## Performance (emulator/host unless stated — not hardware claims)
| Metric | Baseline | Current | Notes |
|---|---:|---:|---|
| Search per query (via bridge) | 184–325 µs | 123–254 µs | −22…33 %, emulator |
| Search session preparation | ~249 µs / 600 µs | ~217 µs / 700 µs | host (excl. JNI) / emulator through the bridge: no measurable end-to-end gain |
| App discovery | 14.9–57.1 ms | 3.4–12.2 ms | −76…79 %, emulator |
| 13 first-time icons, JS block | 55.1 ms | 0.7 ms | emulator |
| Background polling / 10 s | 5 calls | 0 | emulator |
| D-pad animation frames | p50 61 ms | p50 61 ms | ~15–16 fps both builds, v1 single run |
| Cold/warm start, CPU, PSS | — | — | v1 invalid, v2 diagnostic only; awaiting v4 runs |
| Debug APK | 2,617,976 B | 3,250,079 B | +0.63 MB native libs (`armeabi-v7a` + `arm64-v8a`), CI run `43243655890` |

## Preservation
No HTML/CSS/images/sounds/layout, permissions, package ID, SDK settings or debloat scripts changed. The manifest only
gains the explicit-start `NativeHomeActivity` (own process, no HOME/launcher filter). Search results, order, icons and
actions are unchanged (parity above). Device feature checklist still pending.

## Rollback
Source: `git checkout glasslauncher-1.5-phase7-baseline`; APK: CI artifact `glasslauncher-baseline-1.5-debug-apk`.
Partial: `PERF.core:'js'` in `apps.js` (all search in JS), `-Pglass.rust=false` (no native library), `PERF.icons:'bridge'`.
Snapshot migration only: revert `018e627` (the ABI returns to 1 with it). Native prototype: it is never started unless
launched explicitly; removing it is reverting `c011c97` and its follow-ups.

## Owner smoke run (harness v4, `bench-smoke-glass2-20261112-191851`): what it showed

- **Harness:** v4 worked on the box. 3/3 warm samples were valid (YouTube in front, Glass `STOPPED` after ~1.3 s). The renderer (PID 13074) was attributed through `dumpsys activity services`, and background CPU was accepted.
- **Build:** the run did **not** measure Glass 2.0. The package dump has `primaryCpuAbi=null`, meaning an APK with no native library, and every Rust-enabled 2.0 build ships `libglass.so`. That leaves the 1.5 baseline or a `-Pglass.rust=false` build. `versionName` is `1.5` in both builds, which hid this. None of the numbers can be attributed to 2.0.
- **Observations** (one run, not claims):
  - cold start 1,245 ms (2/3 valid); warm start 140 ms;
  - PSS: app 54,362 KB, renderer 71,761 KB;
  - D-pad frames: p50 61 ms (~16 fps), 96.65 % over budget;
  - idle control: 0 frames over 35.25 s, which is correct for a static screen.
- **Harness v5** (`a9306c9`) fixes what the run exposed:
  - build identity: package, signing digest, APK SHA-256 and libglass presence, with a warning if the installed APK doesn't match `-Apk`;
  - a discarded warm-up launch, plus a rejection reason on every cold row;
  - no fake 4,950 ms percentiles for empty histograms; idle-window coverage recorded;
  - renderer attribution limited to exactly `com.glasslauncher` (the real dump also listed `com.glasslauncher.utilityhub`).

  The box's real captures are now test fixtures: 132 parser and 46 simulated-device checks, in PowerShell 7 and Windows PowerShell 5.1.

## Native UI (Phase 6): status
- `docs/nativeui/PARITY_CHECKLIST.md`: a screen-by-screen inventory and a testable parity checklist, from a source audit.
- `docs/nativeui/PROTOTYPE.md`: the home-screen slice as a custom `View`. It covers artwork cards, D-pad focus with the CSS timings, row collapse, launching with the same resolution rules, a Control Centre overlay and theme. It is a separate activity in its own process; the WebView stays HOME.
- `docs/nativeui/MIGRATION_PLAN.md`: stages with device-evidence gates. Nothing migrates until the owner's A/B passes Stage 0.
- Verified so far, only on a CI emulator with the Android TV 1080p profile: it renders, focus moves, the Entertainment row scrolls, the Control Centre opens and closes, and every D-pad key produced a measured frame. Screenshots and logs are in the `native-ui-prototype` artifact. Emulator timings are not device numbers.

## What the owner needs to do next (hardware, about 1 h)

1. Download the `glasslauncher-debug-apk` artifact from the latest green CI run on `glasslauncher-2.0-migration`, to `C:\glass\glass2\app-debug.apk`.
2. From the repository root, in Windows CMD:

```bat
git fetch origin
git checkout glasslauncher-2.0-migration
git pull
adb install -r C:\glass\glass2\app-debug.apk
```

   If the install reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, run `adb uninstall com.glasslauncher` first (this clears Glass's saved settings), then install again.
3. Look at the prototype with the remote (visual judgement, about 2 minutes); Back returns to Glass:

```bat
adb shell am start -n com.glasslauncher/.NativeHomeActivity
```

4. Run the WebView and native UIs alternately, in one sitting, without touching the remote during runs:

```bat
tools\bench\bench-device.bat -Label web-1 -Apk C:\glass\glass2\app-debug.apk -CompileSpeed -Latency -Runs 5 -IdleSeconds 30 -NavRounds 10 -BackgroundPackage com.google.android.youtube.tv
tools\bench\bench-device.bat -Label native-1 -Activity .NativeHomeActivity -CompileSpeed -Latency -Runs 5 -IdleSeconds 30 -NavRounds 10 -BackgroundPackage com.google.android.youtube.tv
tools\bench\bench-device.bat -Label web-2 -CompileSpeed -Latency -Runs 5 -IdleSeconds 30 -NavRounds 10 -BackgroundPackage com.google.android.youtube.tv
tools\bench\bench-device.bat -Label native-2 -Activity .NativeHomeActivity -CompileSpeed -Latency -Runs 5 -IdleSeconds 30 -NavRounds 10 -BackgroundPackage com.google.android.youtube.tv
```

   Each run prints `Build under test: …` first. It must say `has a native library (armeabi-v7a)` for all four runs; if it doesn't, stop and send that folder.
5. Send back the four `bench-*` folders.

The 1.5-vs-2.0 WebView A/B is a separate comparison: it needs the baseline APK artifact and an uninstall before each switch. It has lower priority than the UI decision.

## Next engineering actions
1. Analyse the WebView/native A/B against the Stage 0 gate in `docs/nativeui/MIGRATION_PLAN.md`.
2. If the gate passes, start Stage 1 (complete the native Home). If it fails, profile the WebView focus animation with `-Trace`, starting with the docks' backdrop blur, before considering a port.
3. Remote-control parity checklist on the box (P1 items in `PARITY_CHECKLIST.md`) for the prototype.

