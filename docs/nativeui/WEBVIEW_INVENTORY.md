# WebView / JavaScript inventory and measured code split

Audited at `glasslauncher-2.0-consolidated` @ `f01f4637` (branch `glasslauncher-2.0-native-audit`). Source-only audit: nothing
here was measured on the VIP7802. PR #3 (`glasslauncher-2.0-render-opt`) is not included and was not modified.

## 1. Lines of code (reproducible)

```
python3 tools/metrics/loc.py          # Markdown tables
python3 tools/metrics/loc.py --json   # machine-readable
```

The script uses `git ls-files` (falls back to walking the tree) and classifies each file, first match wins:
`binary` → `legacy` (`app/src/main/assets_baseline/`, `assets_phase*/`) → `test` (`app/src/test|androidTest/`, `rust/*/tests/`) → `docs` (`*.md`) → `fixture` (`tools/*/fixtures/`)
→ `production` (`app/src/main/{java,kotlin,res}/`, the manifest, `app/src/main/assets/{index.html,apps.js,js/,css/,nativeui/}`,
`rust/glass-{core,jni}/src/`) → `tooling` (everything that builds, tests or benchmarks but does not ship). In Rust files a
`#[cfg(test)]` module counts as test. "Code" = non-blank lines that are not pure comments (heuristic, per language).

Production code after the Stage 4a fix (branch `glasslauncher-2.0-full-native`, 53 files, 7558 physical lines):

| Language | Files | Code | Share |
|---|---:|---:|---:|
| Kotlin | 23 | 3925 | 63.8 % |
| JavaScript | 13 | 1049 | 17.1 % |
| Rust | 4 | 506 | 8.2 % |
| Java | 4 | 347 | 5.6 % |
| CSS | 5 | 202 | 3.3 % |
| XML (manifest/res) | 2 | 51 | 0.8 % |
| HTML | 1 | 47 | 0.8 % |
| JSON (`nativeui/home.json`) | 1 | 24 | 0.4 % |
| **Total** | 53 | 6151 | 100 % |

By surface: WebView UI (HTML/CSS/JS) **1298 = 21.1 %**; WebView host Java (MainActivity, GlassBridge) 164 = 2.7 %; shared Java
glue (AppDiscovery, RustCore) 183 = 3.0 %; Kotlin native UI + glue 63.8 %; Rust core + JNI 8.2 %.

Not production (excluded above, code lines): tests (Kotlin 2278, Rust 582 = `tests/golden.rs` + inline `#[cfg(test)]`, Java 455);
fixtures (JSON 10600, device-dump text 349); tooling (PowerShell 1345, JS 448, Python 230, YAML 223, Gradle 141, Rust `glass-cli`
129, Shell 101, Batch 87); legacy asset snapshots (HTML 975, JS 706, CSS 113, a Java text copy 213); docs (Markdown 2490).
The earlier `f01f4637` table (Kotlin 62.7 %, JS 17.6 %, Rust 8.5 % of 5957) is superseded. Binaries are listed, not counted.

Rust is deliberately small: it owns platform-independent search/snapshot logic only. Raising its share by moving Android
framework calls into Rust is out of scope (see `docs/decisions/0004-migration-scope.md`).

## 2. android.webkit and the WebView host

| Where | What |
|---|---|
| `MainActivity.java` | The HOME activity. `new WebView`; settings: JavaScript, DOM storage, `mediaPlaybackRequiresUserGesture=false`, `allowFileAccess`, wide viewport + overview mode; accessibility events swallowed by a delegate; `addJavascriptInterface(new GlassBridge(this), "AndroidBridge")`; `WebChromeClient`; `WebViewClient.shouldInterceptRequest` → `AppDiscovery.iconResponse`; `loadUrl("file:///android_asset/index.html")`; `FrameLatencyProbe.attachIfRequested(this, "webview")`. Lifecycle: `onPause` → `web.onPause()` + `pauseTimers()`; `onResume` → `resumeTimers()` + `onResume()` and, 400 ms later, `evaluateJavascript("window.glassResume&&glassResume()")`. Volume keys → 250 ms later `glassVolumeSync`. BACK (`ACTION_UP` and `onBackPressed`) → `BACK_JS` (synthetic keydown, keyCode 10009). `UiRouter` extra on `onCreate`/`onNewIntent`; in native mode it opens `NativeHomeActivity` and finishes. Accessors `webView()`/`appDiscovery()` exist for instrumented tests. |
| `GlassBridge.java` | `@JavascriptInterface` surface (`android.webkit.JavascriptInterface`). |
| `AppDiscovery.java` | `android.webkit.WebResourceResponse` for icons at `https://appassets.androidplatform.net/glass-icon/<pkg>` (128 px PNG, LruCache 96, CORS `*`, 404 for unknown packages); also `iconDataUri` for the legacy bridge path. Everything else in this class (launchable packages, launch, isInstalled) is WebView-independent. |
| `RustCore.java` | No WebView API, but its threading contract is written for the JavaBridge thread. |
| `androidTest/GlassEndToEndTest.java` | Drives the WebView (`evaluateJavascript`) for search parity through the bridge and icon timing. Must be ported or retired in Stage 4c. |
| `androidTest/NativeUiPrototypeTest.java` | Compares native vs WebView UI; WebView half retires in 4c. |

## 3. Bridge methods and their JavaScript callers

| `AndroidBridge.*` | Called from | Native equivalent already present |
|---|---|---|
| `launchPackage`, `goHome` | glass.js | `NativeHomeActivity` launch path (`AppDiscovery.launch`) |
| `getInstalledApps`, `isInstalled` | appdata.js (+ recent.js, registry.js for `isInstalled`) | `AppDiscovery.launchablePackages` → `SearchCatalog.build`, `NativeHomeConfig` |
| `iconBaseUrl`, `getAppIcon` | icons.js | `NativeIcons.kt` (`PackageManager` drawables) |
| `coreSearchSnapshot`, `coreSearch` | registry.js | `SearchEngine` (`RustBackend` → `RustCore`, fallback `SearchRanking`) |
| `coreInfo`, `coreSearchIndex` | none in production JS (diagnostics / pre-snapshot path) | `RustCore.version()/status()/setSearchIndex` |
| `getVolumeInfo`, `setVolume`, `toggleMute`, `getAudioOutput`, `getWifiInfo`, `getBluetoothInfo`, `getDeviceInfo`, `requestLocationPermission` | controlcentre.js | `SystemControls.kt`, `ControlCentreController.kt`, `NativeControlCentre.kt` |
| `openSettings`, `hasSettings` | controlcentre.js, glass.js, shortcuts.js (+ registry.js) | `SettingsLauncher.kt`, `SearchCatalog.shortcuts` |
| `showKeyboard`, `hideKeyboard` | none in production JS | `SearchKeyboard.kt` (on-screen keyboard is drawn natively) |

JS hooks called from Java: `window.glassResume` and `window.glassVolumeSync` (controlcentre.js), key 10009 handlers in
home.js, search.js, controlcentre.js, main.js.

## 4. Shipped web assets (`app/src/main/assets/`, 1508 physical lines)

| File | Lines | Holds business logic? | Native counterpart |
|---|---:|---|---|
| `index.html` | 54 | no (script order: apps, glass, icons, appdata, theme, recent, home, controlcentre, registry, keyboard, shortcuts, search, main) | `NativeHomeActivity` |
| `apps.js` | 76 | **config**: APPS, THEME, SOUND, PERF, LAYOUT, UI_ICONS, ENTERTAINMENT, ICON_OVERRIDES, SEARCH_ALIASES | `nativeui/home.json` (+ `nativeui-config-check.js` drift check) |
| `js/registry.js` | 170 | **yes**: norm/score/sort ranking (`queryJs`), entry rules, snapshot wire | `SearchRanking.kt`, `SearchCatalog.kt`, `rust/glass-core` |
| `js/shortcuts.js` | 36 | **yes**: settings/glass shortcuts, default aliases | `SearchCatalog.shortcuts` / `mergeAliases` (exact duplicate) |
| `js/search.js` | 135 | UI + debounce 110 ms, max 24, suggestions | `SearchController.kt`, `NativeSearchView.kt`, `SearchEngine.kt` |
| `js/keyboard.js` | 102 | UI | `SearchKeyboard.kt` |
| `js/appdata.js` | 26 | **yes**: hidden-vendor regex, installed list | `SearchRanking.isHidden/appVisible`, Rust `snapshot.rs` |
| `js/home.js` | 191 | UI + focus rules | `NativeHomeView.kt`, `HomeLogic.kt` |
| `js/recent.js` | 42 | **yes**: recently-used list (localStorage) | `HomeLogic.kt` |
| `js/controlcentre.js` | 158 | UI + system polling | `ControlCentreLogic.kt`, `ControlCentreController.kt`, `NativeControlCentre.kt` |
| `js/glass.js` | 146 | glue: bridge wrapper, launch, toast, sound | `NativeHomeActivity`, `SystemControls` |
| `js/icons.js` | 65 | SVG icon set, icon URLs | `SvgIcons.kt`, `NativeIcons.kt`, `NativeArt.kt` |
| `js/theme.js` | 23 | theme toggle | native theme in `NativeHomeView` |
| `js/main.js` | 41 | key routing, clock | `NativeHomeActivity`, `HomeClock.kt` |
| `css/*.css` (5) | 243 | presentation | `NativeHomeView`/`NativeSearchView`/`NativeControlCentre` drawing |
| `ui/` | binary | images used by CSS/JS (`UI_ICONS`) | native drawables / `NativeArt` |

Legacy snapshots `assets_baseline/`, `assets_phase1/`, `assets_phase2/`, `assets_phase3/` are not a source set, so they are
not packaged (MIGRATION_STATUS.md, CURRENT_ARCHITECTURE.md); they are referenced only by docs and a stale comment on line 1 of
the active `index.html`. The rollback/benchmark APK is built from the `glasslauncher-1.5-phase7-baseline` tag (CI
`baseline-apk`), not from these folders. They can move to `docs/` in 4c once the owner agrees; they are not counted as
production code above.

## 5. Tests and tooling that depend on the JavaScript

| Item | Depends on JS for | Survives 4c? |
|---|---|---|
| `tools/parity/search-parity.js` | `queryJs` as the reference for Rust | no → replaced by `ranking-golden.js --rust` + golden file |
| `tools/parity/snapshot-parity.js` | registry.js entry rules as the reference for Rust `snapshot.rs` | no → `registry-entries.json` (already frozen) + `SearchCatalogFixtureTest` |
| `tools/parity/nativeui-config-check.js` | apps.js vs home.json drift | becomes unnecessary once apps.js is gone |
| `tools/parity/ranking-golden.js` | regenerates/checks `fixtures/ranking-golden.json` from registry.js | the `--write`/JS check retires with registry.js; the frozen file and the Kotlin/Rust checks stay |
| `SearchRankingGoldenTest.kt` (new) | nothing at runtime (frozen file) | yes |
| `GlassEndToEndTest.java` | WebView bridge path | must be ported to the native path or deleted in 4c |
