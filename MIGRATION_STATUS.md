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
| 6 Rendering | Not started; needs device frame-pacing data |
| 7 Device validation | Harness v4 verified off-device; **owner smoke run next** |
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
No HTML/CSS/images/sounds/layout, manifest, permissions, package ID, SDK settings or debloat scripts changed. Search
results, order, icons and actions are unchanged (parity above). Device feature checklist still pending.

## Rollback
Source: `git checkout glasslauncher-1.5-phase7-baseline`; APK: CI artifact `glasslauncher-baseline-1.5-debug-apk`.
Partial: `PERF.core:'js'` in `apps.js` (all search in JS), `-Pglass.rust=false` (no native library), `PERF.icons:'bridge'`.
Snapshot migration only: revert `018e627` (the ABI returns to 1 with it).

## What the owner needs to do next (hardware)

The harness is ready for a **short smoke run** (≈10 min) to confirm v4 reads this box correctly before any long A/B.
Windows CMD, from the repository root, box connected over adb:

```bat
git fetch origin
git checkout glasslauncher-2.0-migration
git pull
adb devices
tools\bench\bench-device.bat -Label smoke-glass2 -Runs 3 -IdleSeconds 15 -NavRounds 3 -BackgroundPackage com.google.android.youtube.tv
```

Please send back the whole `bench-smoke-glass2-<timestamp>` folder (or at least `summary.json`, `warm.txt`,
`renderers.txt`, `meminfo.txt`). It is valid if `warm_start_ms.n` is 3, `memory.renderer_pss_kb` is filled with
`renderer_attribution` = `dumpsys activity services`, and `cpu_background_rejected` is empty. If not, the files say
exactly why, and the harness gets fixed off-device again — no long run is needed until the smoke run is clean.

The full A/B (≈1 h) follows only after that, alternating builds; download both APKs from the same CI run (artifacts
`glasslauncher-baseline-1.5-debug-apk` and `glasslauncher-debug-apk`). Each CI run signs with a different debug key, so
uninstall before every switch (this clears Glass's saved settings, identically for both builds):

```bat
adb uninstall com.glasslauncher
tools\bench\bench-device.bat -Label baseline-1 -Apk C:\glass\baseline\app-debug.apk -CompileSpeed
adb uninstall com.glasslauncher
tools\bench\bench-device.bat -Label glass2-1 -Apk C:\glass\glass2\app-debug.apk -CompileSpeed
adb uninstall com.glasslauncher
tools\bench\bench-device.bat -Label baseline-2 -Apk C:\glass\baseline\app-debug.apk -CompileSpeed
adb uninstall com.glasslauncher
tools\bench\bench-device.bat -Label glass2-2 -Apk C:\glass\glass2\app-debug.apk -CompileSpeed
powershell -ExecutionPolicy Bypass -File tools\bench\compare.ps1 bench-baseline-1-<ts>\summary.json bench-glass2-1-<ts>\summary.json
```

Keep the same time band (theme switches at 19:00/07:00), don't touch the remote during a run, and set Glass as the
default home app after each install if Android asks.

## Next engineering actions
1. Smoke run → fix anything it shows → full A/B.
2. With device frame data and `-Trace`, decide the rendering question (Phase 6).
3. Measure D-pad navigation latency and set targets from the validated device baseline.
