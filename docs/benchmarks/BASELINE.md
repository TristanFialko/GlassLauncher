# Baseline — Glass Launcher 1.5 (Phase 7, 320 DPI)

Baseline = tag `glasslauncher-1.5-phase7-baseline` (commit `452320a`). Every number is labelled with its source.
**No Android device or emulator was available** in the migration environment (no hardware, no `/dev/kvm`), so no
runtime metric of the launcher itself has been measured yet. Those rows say so; they will be filled from
`tools/bench/bench-device.ps1` runs on the VIP7802.

## Environment (build/host measurements)
Linux x86_64 sandbox, 2 vCPU (model not reported), JDK 21.0.13 (Temurin), Gradle 9.6.0 wrapper, AGP 8.7.3,
Android SDK platform 35 / build-tools 34.0.0, Node v24.22.2.

## Build from a clean checkout (observed)
| Step | Result |
|---|---|
| `git clone` + `./gradlew assembleDebug` | `gradlew` not executable from Git (uploaded from Windows) → `Permission denied`. After `chmod +x`: **BUILD SUCCESSFUL**, 31 tasks, ~40 s cold. Gradle auto-provisioned a JDK 25 daemon per `gradle/gradle-daemon-jvm.properties`. |
| `./gradlew assembleRelease` | **FAILED** in `:app:lintVitalAnalyzeRelease` (AGP 8.7.3 lint cannot handle JDK `25.0.3`). Succeeds once the daemon is pinned to JDK 21 (fixed in the Phase 0 commit). |
| `./gradlew lintDebug` | 4 errors, 7 warnings: `ExpiredTargetSdkVersion` (targetSdk 30 is deliberate), `MissingPermission` ×2 (`BLUETOOTH_CONNECT`, only enforced for targetSdk ≥ 31), `MissingTvBanner`; warnings `DiscouragedApi`, `LeanbackUsesWifi`, `MissingApplicationIcon`, `PermissionImpliesUnsupportedHardware` ×2, `QueryPermissionsNeeded`, `SetJavaScriptEnabled`. |

## APK (observed, build output)
| Artifact | Bytes |
|---|---:|
| `app-debug.apk` | 2,617,976 |
| `app-release-unsigned.apk` | 2,601,760 |
| Entries in APK | 77 (no native libraries) |
| Largest entries | `assets/icons/Disney+.png` 284,080 · `assets/icons/retroarch.png` 195,626 · `assets/sounds/click.wav` 160,200 · `assets/darkicons/darkdisney.png` 135,740 |

`assets_baseline/`, `assets_phase1..3/` are **not** packaged (only `src/main/assets/` is).

## Runtime metrics (target device)
| Metric | Baseline | Source / status |
|---|---:|---|
| Cold start (`am start -S -W` TotalTime) median / p95 | not measured | needs device — `bench-device.ps1` |
| Warm start median / p95 | not measured | needs device |
| D-pad frame pacing (gfxinfo janky %, p90/p99) | not measured | needs device |
| Search response (per keystroke) | not measured on device | host proxy below |
| Control Centre open | not measured | needs device (Perfetto) |
| Idle CPU (foreground) / CPU with another app in front | not measured | needs device |
| Memory PSS | not measured | needs device |

## Host proxy: search ranking cost of the original JavaScript (observed)
`node tools/parity/search-parity.js --bench` runs the **production** `registry.js` + `shortcuts.js` under Node/V8 on a
228-entry registry (fixture: plausible TV-box app list + edge cases), 4,396 non-empty queries × 30 rounds after warm-up:

| Implementation | per query median | p95 |
|---|---:|---:|
| Original JS `G.registry.query` (V8 JIT, x86_64 host) | 247.3 µs | 260.6 µs |

Confidence: repeatable on this host (two runs: 247.7 / 247.3 µs). It is **not** a device number: a set-top-box WebView on
ARM is expected to be several times slower, and this excludes DOM rebuild and icon loading.

## Static inventory of likely costs (from `docs/architecture/CURRENT_ARCHITECTURE.md`, not measured)
- Boot: `getInstalledApps` → `getInstalledPackages(GET_META_DATA)` then 1–3 PackageManager Binder calls **per installed package**, synchronously, before home rows render.
- Search: every keystroke (110 ms debounce) rebuilds `entries()`, re-normalises every title/keyword with two regexes, and calls `norm()` again inside the sort comparator.
- Icons: each uncached `getAppIcon` = 128 px bitmap → PNG (quality 100) → base64 on the JavaBridge thread, blocking JS.
- Control Centre: 3 synchronous bridge calls (volume, Wi-Fi, Bluetooth) every 2 s while open.
- Background: no `onPause` handling — WebView JS timers (10 s clock, and the 2 s Control Centre poll if left open) keep running while other apps are in front.
- Rendering: `backdrop-filter` blur on `.glass` docks (also in lite mode); 800×480 card art drawn at 280×168; forced layout per home key press.

## How to record the device baseline
```bat
rem CI artifact "glasslauncher-baseline-1.5-debug-apk" is the baseline build.
tools\bench\bench-device.bat -Label baseline -Apk glasslauncher-baseline-1.5-debug.apk
tools\bench\bench-device.bat -Label glass2   -Apk glasslauncher-debug.apk
powershell -ExecutionPolicy Bypass -File tools\bench\compare.ps1 bench-baseline-*\summary.json bench-glass2-*\summary.json
```
Use the same box, resolution/density, WebView version and app data state for both runs (the script records them and
`compare.ps1` warns on differences).
