# Results — Glass 2.0 migration vs 1.5 baseline

Every row states where the number came from. "Emulator" = Android 11 (API 30) x86_64 emulator on a GitHub Actions
`ubuntu-24.04` runner (CI runs `43214807069`, `43215959721`, `43217231667`, job "Instrumented test"; runner speed varies between runs, so each row compares both builds within the same run); "host" = Linux x86_64 machine running Node/Rust
directly. The first VIP7802 comparison (harness v1) is in its own section below; most of its numbers must be repeated.
"Emulator" and "host" rows are not hardware results.

## Summary
| Metric | Baseline 1.5 | Glass 2.0 (this branch) | Change | Evidence |
|---|---:|---:|---:|---|
| App discovery (`getInstalledApps` Java work), median of 7 | 32.9 / 57.1 / 14.9 ms | 7.8 / 12.2 / 3.4 ms | **−76 % to −79 %** | Emulator, three runs: 84 installed / 13 launchable packages; identical JSON output asserted each run. |
| Binder calls for app discovery | 1 + up to 3 per installed package | 4 | O(N) → O(1) | Source: `AppDiscovery.java` (baseline vs current). |
| Search ranking per query through the WebView (median of 9 rounds × 1,668 queries) | 317 / 325 / 184 µs | 219 / 254 / 123 µs | **−22 % to −33 %** | Emulator, three runs, 225-entry registry, real WebView → AndroidBridge → JNI → Rust; 0 ranking differences asserted each run. |
| Search ranking per query, engine only (median, 30 rounds × 4,396 queries) | 247.3 µs (JS, Node 24) | 16.8 µs (Rust) | 14.7× | Host (2 vCPU sandbox); excludes the bridge. CI runner: 185.9 → 8.6 µs. |
| One-time cost per Search session (index) | — | ~137 µs serialise + ~126 µs build | new | Host. Re-sent only when entries change. |
| Control Centre poll while Glass is in the background (calls / 10 s) | 5 (runs unchanged; baseline has no `onPause` handling) | **0** | stops | Emulator: foreground 5 → background 0 (asserted). Baseline figure is from the 2 s interval in `controlcentre.js:141` and the absence of pause handling — not run on the emulator. |
| JS-thread time blocked loading 13 uncached app icons | 55.1 ms | **0.7 ms** | −99 % | Emulator (run `43217231667`): 1.5 path = synchronous `getAppIcon` (bitmap → PNG → base64); 2.0 = WebView fetches the same PNG via `shouldInterceptRequest`. Wall time until all 13 icons loaded: 55.1 → 43.3 ms. 13/13 icons, decoded 128×128, asserted. The 1.5 path ran second (warm system caches), so the comparison favours it. |
| One uncached `getAppIcon` call (what the 1.5 path blocks JS for) | 13.7 / 3.2 ms median, 80 / 74 ms max | — | — | Emulator, two runs (`iconEncodingCost`). |
| APK, debug (clean build) | 2,617,976 B | 3,211,564 B | +593,588 B (+22.7 %) | Build output. Native: `armeabi-v7a` 242,848 B + `arm64-v8a` 329,728 B (stored uncompressed). |
| APK, release unsigned (clean build) | 2,601,760 B | 3,196,840 B | +595,080 B (+22.9 %) | Build output. |
| APK with `-Pglass.rust=false` (clean build) | 2,617,976 B | 2,622,524 B | +4,548 B | Build output. Incremental builds pad the APK; compare clean builds only. |
| Cold / warm start, PSS, CPU, D-pad frames on the VIP7802 | see below | see below | **not established** | First device run used harness v1; see the next section. |

## VIP7802, first comparison (owner-run 2026-11-12, harness v1): what the numbers can and cannot say
Device: ARRIS VIP7802, Android 11, armeabi-v7a, 1920×1080, 320 DPI. One run per build. The raw folders were not
available for this review; the assessment below comes from the reported summary and the v1 harness code
(details: `METHODOLOGY.md`).

| Metric | Baseline 1.5 | Glass 2.0 | Reported change | Verdict |
|---|---:|---:|---|---|
| Cold start median | 1209.5 ms | 1249.5 ms | +40 ms | **Unproven either way.** LaunchState not validated, spread unknown, one run each. No startup-path cause is known: the Rust library loads only on the first search. The new startup work is constructing `AppDiscovery` in `onCreate` and an icon-URL prefix check per WebView request. Repeat with v2. |
| Warm start median | 1193 ms (10 identical) | 1246 ms (10 identical) | +53 ms | **Invalid measurement** (identical samples ≈ cold time; Glass most likely never left the screen). Repeat with v2. |
| Foreground idle CPU | 1.983 % | 1.483 % | −0.5 pt | **Not claimable.** App process only, excluding the WebView renderer; one 60 s window each. |
| Background CPU | 1.55 % | 1.20 % | −0.35 pt | **Not claimable.** Same limits, and measured behind Settings, which may not hide Glass. |
| gfxinfo frame p50 / p95 during D-pad | 61 / 77 ms | 61 / 73 ms | p95 −4 ms | **No change shown.** 77 → 73 is one 4 ms histogram bucket in a single run. |
| Janky frames during D-pad | 2090 / 2100 | 2088 / 2106 | none | **Valid as a finding, the same in both builds:** almost every frame takes ~3–4 vsync periods (~15–16 fps) while the focus animations run. Rendering is the main smoothness cost on this box, and this migration has not changed it. |
| PSS | null | null | — | **Not measured** (v1 parse failure). |
| APK | 2.62 MB | 3.21 MB | +0.59 MB | **Valid** (two native libraries). The box reported armeabi-v7a. If its ABI list (`device.abis` in a v2 summary) has no arm64-v8a, building with `-Pglass.rustAbis=armeabi-v7a` saves ~0.33 MB (ADR 0003). |

**Conclusion:** the first device run shows no reliable performance improvement or regression in start time, CPU or
memory. It does show, credibly, that frame rendering during focus animation is far over budget on the VIP7802 in both builds.

### Reading these honestly
- The search speed-up through the real bridge (−22 % to −33 % on the emulator) is much smaller than the engine-only ratio, because one
  synchronous `@JavascriptInterface` round trip (roughly 100–250 µs on the emulator) now dominates. On a slow ARM box the JS side is
  expected to slow down more than the bridge, but that is unverified. Search runs after a 110 ms debounce, so either way the
  per-keystroke ranking cost was already small next to icon loading (fixed separately, below) and the DOM rebuild (unchanged).
- App discovery scales with the number of *installed* packages (most are non-launchable system packages that cost the
  legacy code 3 Binder calls each). The emulator has 84 packages; operator boxes typically have more, so the absolute saving
  at boot should be larger there — to be confirmed by cold-start measurements.
- Icons were the largest measured JS-thread stall: up to ~80 ms for a single first-time icon. They no longer block JavaScript at all; the encoding work still happens (on WebView loader threads), so total CPU is similar, but input and rendering are not held up while results fill in.
- Background polling: the effect is binary (poll stops) and was observed; the CPU saving it represents on the box is unmeasured.
- APK size grows by the two native libraries; see ADR 0003 for the 32-bit-only option.

## Regressions / trade-offs
- APK +0.59 MB (native libraries).
- WebView timers are paused whenever the activity is paused, including when a dialog (e.g. the one-time location-permission
  prompt) covers Glass; the UI behind the dialog does not update until it closes. The `G.perf.watch` blur-fallback sampler
  also cannot count frames during that window, so it records nothing for that opening (it neither marks it slow nor fast).
- Building now needs Rust + NDK (or `-Pglass.rust=false`).

## Parity evidence
| Check | Result | Where |
|---|---|---|
| `norm()` JS vs Rust | 4,409 strings, 0 differences (7 lone-surrogate strings excluded — no UTF-8 form; adapter keeps them on JS) | `tools/parity/search-parity.js` (host + CI) |
| Ranking JS vs adapter→Rust | 8,832 query/max combinations, 0 differences | same |
| Ranking JS vs Rust inside the Android WebView | 1,668 queries, 0 differences | `GlassEndToEndTest.searchParityThroughWebViewBridge` (emulator) |
| App discovery 1.5 vs 2.0 output | identical JSON | `GlassEndToEndTest.appDiscoveryMatchesLegacyAlgorithm` (emulator) |
| JNI on host JVM (load, index, unicode, bad input, 5,000 entries) | 5/5 pass | `RustCoreJniTest` |
| Native load on Android | pass | `RustCoreInstrumentedTest` (emulator) |
| Icons via WebView vs 1.5 bridge: same set available, decoded 128×128 | 13/13, pass | `GlassEndToEndTest.iconsLoadOffTheJsThread` (emulator) |
