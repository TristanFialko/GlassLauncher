# WebView removal matrix (Stage 4c prerequisites)

Source audit at `f01f4637`. "Blocks 4c" = the WebView/JS cannot be deleted until the gap is closed. Device gates are
unchanged (`MIGRATION_PLAN.md`): nothing here makes the native UI the default HOME; that still needs owner data from the VIP7802.

| # | Area | Current implementation (WebView path) | Native replacement (exists) | Gap | API contract | Parity tests | Blocks 4c? |
|---|---|---|---|---|---|---|---|
| 1 | Search ranking | `registry.js` `queryJs` (JS fallback) + Rust via `coreSearch` | `SearchEngine` → Rust (`RustCore.search`), fallback `SearchRanking.kt` | Once registry.js is deleted, `search-parity.js` has no reference. **Closed on this branch:** frozen `fixtures/ranking-golden.json` (2522 queries × max 24/5) checked against Kotlin (JVM, always) and Rust (JNI when built; `glass-cli` in CI) | `SearchRanking.query(List<Candidate>, text, max): IntArray`; Rust returns indices into the kept entries | `SearchRankingGoldenTest`, `ranking-golden.js --rust`, `SearchRankingParityTest` | no (after this branch) |
| 2 | Search entry rules (visibility, hidden vendors, self, `supersededBy`, `available`) | `registry.js` `entries()` + `appdata.js` HIDDEN | `SearchCatalog.build`, Rust `snapshot.rs` | none known; `registry-entries.json` is already frozen (8 cases) | `SearchCatalog.Snapshot` | `SearchCatalogFixtureTest`, `snapshot-parity.js` | no |
| 3 | Settings shortcuts + default aliases | `shortcuts.js` | `SearchCatalog.shortcuts` / `mergeAliases` | exact duplicate today; JS copy goes with 4c | `shortcuts(settingsAvailable: (String) -> Boolean)` | fixture test (case "every optional settings page missing") | no |
| 4 | Search UI, keyboard, debounce | `search.js`, `keyboard.js` | `SearchController`, `NativeSearchView`, `SearchKeyboard` | device checklist only | — | `SearchControllerTest`, `SearchKeyboardTest`, `SearchEngineTest`, emulator | device gate |
| 5 | Home, focus, Recently Used | `home.js`, `recent.js`, `glass.js`, `main.js` | `NativeHomeView`, `HomeLogic`, `NativeHomeActivity` | Recently Used is stored separately (WebView localStorage vs native prefs); no migration of the list. Decide: migrate once or accept a reset | — | `HomeLogicTest`, emulator | owner decision |
| 6 | Config (cards, theme, sound, aliases) | `apps.js` globals | `nativeui/home.json` + `NativeHomeConfig` | duplicated; CI drift check. After 4c, `home.json` is the only source and the drift check retires | `NativeHomeConfig.load` | `nativeui-config-check.js`, `NativeHomeConfigTest` | no |
| 7 | Icons and artwork | `icons.js` + `AppDiscovery.iconResponse` (WebResourceResponse) / `iconDataUri` | `NativeIcons`, `SvgIcons`, `NativeArt` | `iconResponse`/`iconDataUri` and `ICON_BASE` become dead code; `iconPng` stays only if a native caller uses it | — | `SvgIconsTest`, emulator screenshots | no |
| 8 | Control Centre + system state | `controlcentre.js` → `GlassBridge` volume/Wi-Fi/BT/device/location | `ControlCentreController`, `SystemControls`, `NativeControlCentre` | device checklist only | — | `ControlCentreLogicTest`, emulator | device gate |
| 9 | Settings launching | `GlassBridge.openSettings/hasSettings` | `SettingsLauncher` | none known | — | emulator | no |
| 10 | Lifecycle / routing | `MainActivity` WebView, `BACK_JS`, `glassResume`, `glassVolumeSync`, `UiRouter` | `NativeHomeActivity` (`:native`), `UiRouter` | 4b flips the default; 4c makes `NativeHomeActivity` (main process) the HOME activity and drops `UiRouter`. Manifest HOME identity must not change before the owner agrees | `UiRouter.mode/applyExtra` | `UiRouterTest`, emulator | **yes** (device gates 4a/4b) |
| 11 | Theme / sound | `theme.js`, `glass.js` sound | native theme + `SoundPool` in `NativeHomeActivity` | device checklist | — | emulator | device gate |
| 12 | Measurement | `FrameLatencyProbe ui=webview`, harness `-Activity .MainActivity` | same probe, `ui=native` | needed for the Stage 0 A/B; retire the WebView half after 4c | — | harness tests | keep until A/B done |
| 13 | Instrumented tests | `GlassEndToEndTest` (WebView ↔ bridge ↔ Rust), WebView half of `NativeUiPrototypeTest` | golden + JVM/JNI tests; native half of the prototype test | port the icon-timing check or drop it | — | — | delete with the WebView |
| 14 | Legacy asset folders | `assets_baseline`, `assets_phase1..3` (not packaged) | — | move to `docs/` or delete with owner OK | — | — | no |

## Order of work that keeps every commit buildable

1. Freeze references (this branch: ranking golden; `registry-entries.json` already frozen). Tests first, no production change.
2. Optional, still while the WebView is HOME: let `GlassBridge.coreSearch*` fall back to Kotlin `SearchRanking` when the Rust
   library is missing, so the JS ranking is never used, then delete `queryJs`/`score`/`fieldScore`. Needs a Kotlin parser for
   the snapshot wire (or a JSON variant) and JVM tests; the lone-surrogate and `PERF.core:'js'` paths need a decision first.
3. Owner gates 0 → 4a → 4b on the VIP7802 (unchanged).
4. 4c deletions in this order, each its own commit with CI green: WebView instrumented tests → `MainActivity` WebView code and
   `GlassBridge` → `AppDiscovery.iconResponse/iconDataUri` → `assets/index.html`, `js/`, `css/`, `apps.js`, `ui/` (if unused
   natively) → JS-only tooling (`search-parity.js` JS half, `snapshot-parity.js`, `nativeui-config-check.js`, `ranking-golden.js`
   JS check). Then verify the release APK has no `android.webkit` references and no web assets.
