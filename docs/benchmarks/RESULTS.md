# Results — Glass 2.0 migration vs 1.5 baseline

Every row states where the number came from. "Emulator" = Android 11 (API 30) x86_64 emulator on a GitHub Actions
`ubuntu-24.04` runner (CI run `43214807069`, job "Instrumented test"); "host" = Linux x86_64 machine running Node/Rust
directly. **Nothing here was measured on the ARRIS VIP7802**; device rows stay "pending" until
`tools/bench/bench-device.ps1` has been run there for both APKs.

## Summary
| Metric | Baseline 1.5 | Glass 2.0 (this branch) | Change | Evidence |
|---|---:|---:|---:|---|
| App discovery (`getInstalledApps` Java work), median of 7 | 32.9 ms | 7.8 ms | **−76 %** (4.2×) | Emulator: 84 installed / 13 launchable packages; identical JSON output asserted. Max of 7: 118.9 → 95.0 ms. |
| Binder calls for app discovery | 1 + up to 3 per installed package | 4 | O(N) → O(1) | Source: `AppDiscovery.java` (baseline vs current). |
| Search ranking per query through the WebView (median of 9 rounds × 1,668 queries) | 317 µs | 218.6 µs | **−31 %** | Emulator, 225-entry registry, real WebView → AndroidBridge → JNI → Rust; 0 ranking differences asserted. Max: 337 → 222.6 µs. |
| Search ranking per query, engine only (median, 30 rounds × 4,396 queries) | 247.3 µs (JS, Node 24) | 16.8 µs (Rust) | 14.7× | Host (2 vCPU sandbox); excludes the bridge. CI runner: 185.9 → 8.6 µs. |
| One-time cost per Search session (index) | — | ~137 µs serialise + ~126 µs build | new | Host. Re-sent only when entries change. |
| Control Centre poll while Glass is in the background (calls / 10 s) | 5 (runs unchanged; baseline has no `onPause` handling) | **0** | stops | Emulator: foreground 5 → background 0 (asserted). Baseline figure is from the 2 s interval in `controlcentre.js:141` and the absence of pause handling — not run on the emulator. |
| APK, debug | 2,617,976 B | 3,211,141 B | +593,165 B (+22.7 %) | Build output. Native: `armeabi-v7a` 242,848 B + `arm64-v8a` 329,728 B (stored uncompressed). |
| APK, release unsigned | 2,601,760 B | 3,196,448 B | +594,688 B | Build output. |
| APK with `-Pglass.rust=false` | 2,617,976 B | 2,620,984 B | +3,008 B | Build output. |
| Cold / warm start, PSS, idle CPU, D-pad jank | not measured | not measured | pending | Device only — `tools/bench`. |

### Reading these honestly
- The search speed-up through the real bridge (−31 % on the emulator) is much smaller than the engine-only ratio, because one
  synchronous `@JavascriptInterface` round trip (~200 µs on the emulator) now dominates. On a slow ARM box the JS side is
  expected to slow down more than the bridge, but that is unverified. Search runs after a 110 ms debounce, so either way the
  per-keystroke cost was already small relative to DOM rebuild and icon loading, which are unchanged.
- App discovery scales with the number of *installed* packages (most are non-launchable system packages that cost the
  legacy code 3 Binder calls each). The emulator has 84 packages; operator boxes typically have more, so the absolute saving
  at boot should be larger there — to be confirmed by cold-start measurements.
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
