# Glass Launcher 2.0 — Migration status

_Last updated: 2026-11-12 · branch `glasslauncher-2.0-migration` · baseline tag `glasslauncher-1.5-phase7-baseline` (`452320a`)_

## Where we are
| Phase (brief §6) | Status | Notes |
|---|---|---|
| 0 Baseline & safety | **Done** | Tag pushed. Clean-checkout build reproduced; two blockers found and fixed (below). No secrets, keystores or SDK paths tracked. |
| 1 Architecture & performance audit | **Done (static) / device metrics pending** | `docs/architecture/CURRENT_ARCHITECTURE.md`, `FEATURE_INVENTORY.md`, `docs/benchmarks/BASELINE.md`. No device available, so runtime baseline is unmeasured; a repeatable device procedure exists (`tools/bench`). |
| 2 Rust workspace & build integration | **Done** | `rust/` (glass-core, glass-jni, glass-cli), `app/rust.gradle`, `RustCore` loader with controlled failure, CI. Native library loads on an Android 11 emulator (CI). |
| 3 Migrate pure core logic | **In progress — search done** | Search normalisation/scoring/ranking runs in Rust with exact parity; JS ranking kept as fallback. Other candidates deliberately not moved (ADR 0004). |
| 4 Simplify the bridge | **Started** | Search: 1 coarse call per query + 1 per session. Icons: no bridge call any more (served to the WebView directly). A Control Centre status snapshot (3 calls → 1 per 2 s) was evaluated and deferred: one round trip measured ~0.1–0.25 ms on the emulator, so the saving is < 0.5 ms per 2 s. |
| 5 Performance optimisation | **In progress** | App discovery O(N)→O(1) Binder calls (−76…79 %); WebView timers paused in background; app icons no longer block the JS thread (55 → 0.7 ms for 13 icons). Results in `docs/benchmarks/RESULTS.md`. |
| 6 Rendering decision | Not started | Needs device frame-pacing data (ADR 0001: keep WebView until then). |
| 7 Device validation | **In progress** | First VIP7802 A/B run done with harness v1; its method was flawed (see below). Harness v2 written and tested off-device; device rerun pending. |
| 8 Release hardening | Not started | No signing config in repo (by design); release APK is unsigned. |

## Completed work (commits on this branch)
1. **Phase 0 build fixes** — `gradlew` made executable in Git (was `Permission denied` on Linux/macOS/CI); Gradle daemon pinned to JDK 21 with regenerated download URLs (AGP 8.7.3 lint crashed on the JDK 25 daemon, so `assembleRelease` failed from a clean checkout); `.gitignore` covers Cargo `target/`, keystores, `.env`.
2. **Rust core** — `glass-core` (no deps, `forbid(unsafe_code)`), `glass-jni` (`jni-sys` only; UTF-8 byte arrays; `catch_unwind` everywhere; no threads/callbacks), `glass-cli` (host harness). Toolchain pinned to 1.91.0.
3. **Integration** — per-ABI Gradle build with NDK clang, registered via the AGP variant API; `-Pglass.rust=false` opt-out; `RustCore` checks ABI version + self-test; bridge methods `coreInfo`, `coreSearchIndex`, `coreSearch`; `registry.js` adapter with per-session index and the untouched JS ranking as `queryJs` fallback.
4. **Platform optimisations** — `AppDiscovery` uses 3 package-wide intent queries instead of per-package Binder calls; `MainActivity` pauses/resumes WebView timers on `onPause`/`onResume`; app icons are served to the WebView through `shouldInterceptRequest` (same PNG bytes, no base64, off the JS thread; `icons.js` keeps its callback contract; `PERF.icons:'bridge'` restores the old path); `Trace` sections (`Glass:*`) for Perfetto.
5. **Tests & CI** (`.github/workflows/ci.yml`) — Rust fmt/clippy/test, Node parity + host benchmark, APK build + host-JVM JNI tests, baseline APK artifact, emulator instrumented tests (native load, WebView search parity + timing, discovery parity + timing, background poll stop).
6. **Docs** — architecture (current/target), feature inventory, baseline, results, ADRs 0001–0004, `docs/BUILDING.md`, device benchmark scripts.

## Test evidence (latest CI run `43217231667`, all 4 jobs green)
| Level | What ran | Result |
|---|---|---|
| Host — Rust | 7 unit tests, fmt, clippy `-D warnings` | pass |
| Host — Node | parity: 4,409 norm strings, 8,832 rankings | 0 differences |
| Host — JVM | `RustCoreJniTest` (real JNI against host `libglass`) | 5/5 pass |
| Emulator (API 30, x86_64) | `RustCoreInstrumentedTest`, `GlassEndToEndTest` ×5 (search parity, discovery parity, icon paths, icon cost, background poll) | 6/6 pass |
| Host — PowerShell | `tools/bench/test-bench-lib.ps1` (benchmark parsers vs Android 11/12+ fixtures) + end-to-end runs against a simulated Android 11 device | 65/65 pass |
| Physical device | VIP7802 A/B with harness v1 (owner) | ran; method flawed, see `docs/benchmarks/METHODOLOGY.md` |

## Performance (details and caveats: `docs/benchmarks/RESULTS.md`)
| Metric | Baseline | Current | Target | Evidence |
|---|---:|---:|---:|---|
| Cold start (to first frame) | 1209.5 ms (v1) | 1249.5 ms (v1) | set after v2 baseline | VIP7802, v1: unvalidated, spread unknown; repeat |
| Warm resume | invalid (v1) | invalid (v1) | set after v2 baseline | VIP7802, v1: 10 identical samples; repeat |
| Frames during D-pad animation | p50 61 ms, 99.5 % janky (v1) | p50 61 ms, 99.1 % janky (v1) | set after v2 baseline | VIP7802: ~15–16 fps while animating in both builds (finding holds); latency not measured (needs `-Trace`) |
| Search response (per query, in WebView) | 184–325 µs | 123–254 µs (−22…33 %) | no regression | emulator, 3 runs |
| App discovery (Java) | 14.9–57.1 ms | 3.4–12.2 ms (−76…79 %) | — | emulator, 84 packages, 3 runs |
| JS thread blocked by 13 first-time icons | 55.1 ms | 0.7 ms | — | emulator |
| Control Centre open | not measured | not measured | — | device needed |
| Background poll while hidden | 5 calls / 10 s | 0 | 0 | emulator |
| Idle CPU (app process only) | 1.983 % (v1) | 1.483 % (v1) | set after v2 baseline | VIP7802, v1: renderer excluded, one window; not claimable |
| Memory PSS | null (v1) | null (v1) | no regression | VIP7802, v1 parse failure; repeat |
| APK size (debug, clean build) | 2,617,976 B | 3,211,564 B | monitor | build output |

## Feature preservation
- No HTML, CSS, image, sound or layout file was changed. JS changes are limited to `registry.js` (adapter; original ranking intact as `queryJs`), one `G.registry.invalidate()` call in `search.js open()`, and `icons.js packageIcon` (URL instead of data URI, same callback contract; old path kept). `index.html` still uses `width=1920` without `initial-scale`; WebView wide-viewport/overview settings untouched.
- Manifest, permissions, package id, SDK levels and version unchanged. Scripts (`install-to-box.bat`, `safe-debloat.bat`, `restore-debloat.bat`) unchanged; reviewed: they only toggle `com.google.android.play.games` and `com.google.android.feedback` and never touch DU TV, Projectivy or system launchers.
- Feature checklist status per item: `docs/architecture/FEATURE_INVENTORY.md` (static). Device test of the full checklist is pending.

## Open issues / limitations
- **Device numbers not yet reliable.** The first VIP7802 run used harness v1, which had method flaws (`docs/benchmarks/METHODOLOGY.md`). Only the APK size and the rendering finding (~15–16 fps during focus animation, both builds) are usable. Start time, CPU and memory must be re-measured with harness v2. Remote-control feature checklist not yet run.
- Pre-existing lint errors kept as-is (not regressions): `MissingPermission` ×2 (`BLUETOOTH_CONNECT`, irrelevant at targetSdk 30), `MissingTvBanner`. `ExpiredTargetSdkVersion` disabled because targetSdk 30 is deliberate.
- Gradle 9.6 reports deprecations that will break with Gradle 10 (AGP 8.7.3 is older than this Gradle); no AGP/Gradle upgrade done (out of scope, risky).
- Static findings not acted on because they need device numbers or touch visuals (see TARGET_ARCHITECTURE "Next candidates"): forced layout per D-pad key, 800×480 card art drawn at 280×168, `noblur` fallback likely not applying to `#cc` (CSS specificity — visual, needs review).
- Documented-but-absent features found by the audit (All Apps row, Utilities row, brightness tile, moon/sun header button, …) are listed in `FEATURE_INVENTORY.md`; nothing was removed by the migration.
- GitHub language bar: it showed "100% Batchfile" because the statistics were computed when only the three `.bat` files (978 bytes) were in the repository. They refreshed without any change: the languages API now reports JavaScript 109,555 B, HTML 63,595 B, CSS 32,607 B, Java 21,866 B, Batchfile 978 B (resolved; no `.gitattributes` needed).

## Rollback
- Source: `git checkout glasslauncher-1.5-phase7-baseline` (`main` is still at the baseline until this branch is merged).
- APK: CI artifact `glasslauncher-baseline-1.5-debug-apk`.
- Partial: `-Pglass.rust=false` (no native code), `PERF.core:'js'` (original search ranking) or `PERF.icons:'bridge'` (original icon path) in `apps.js`.

## Next actions
1. **Owner:** rerun both builds with harness v2 as described in `docs/benchmarks/METHODOLOGY.md` (`-CompileSpeed`, same hour band, A/B/A/B) and send the four result folders (zipped), including the old v1 folders if still available. Run the feature checklist with the remote.
2. Rendering: the v1 run already shows ~61 ms frames during focus animation. Use v2 (idle control, refresh rate, `-Trace`) to find what dominates the frame (blur compositing, raster of 800×480 art, layout) before any Phase 6 decision. Visual changes need owner review.
3. Measure the home D-pad path (forced layout per key, unthrottled key repeat) with the gfxinfo part of the benchmark.
4. Set performance targets from the device baseline; then the Phase 6 rendering decision.
