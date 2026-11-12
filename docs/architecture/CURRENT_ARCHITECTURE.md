# Glass Launcher — Current Architecture (static audit)

Scope: the **pre-migration baseline**, tag `glasslauncher-1.5-phase7-baseline` (commit `452320a`, package `com.glasslauncher`). Source line numbers are identical at `9df3967` (that commit only changed build configuration). Glass 2.0 changes made after this audit are described in `TARGET_ARCHITECTURE.md` and `MIGRATION_STATUS.md`, and shift some line numbers in `registry.js`, `search.js`, `GlassBridge.java`, `AppDiscovery.java` and `MainActivity.java`.
Method: every active source file was read in full (all Java, `AndroidManifest.xml`, `res/values/styles.xml`, `index.html`, `apps.js`, all `js/*.js`, all `css/*.css`, all `PHASE*.md`/`README.md`, the three `.bat` files). Citations are `file:line` relative to `app/src/main/` unless stated.

**Nothing in this document was measured on a device.** Every "performance" statement is static inspection of code. Statements that come from general Android/Chromium knowledge rather than this repo are labelled *(external knowledge)*. Where the source does not answer a question it says "not determinable from source".

---

## 1. Overview

| Layer | Files | Role |
|---|---|---|
| Android shell | `java/com/glasslauncher/MainActivity.java`, `AndroidManifest.xml`, `res/values/styles.xml` | One full-screen `Activity` registered as HOME + Leanback launcher; hosts a single `WebView`; forwards Back / volume keys into JS |
| Bridge | `GlassBridge.java` → `AppDiscovery`, `SystemControls`, `SettingsLauncher` | Exposed to JS as `AndroidBridge` (`MainActivity.java:51`). 17 `@JavascriptInterface` methods, all request/response; Java never pushes state except via `evaluateJavascript` calls to `glassResume` / `glassVolumeSync` / the Back keydown |
| WebView JS | `assets/apps.js` (config), `assets/js/*.js` (12 modules, one global `window.Glass`) | All UI state, navigation, search, Control Centre, theme, sounds |
| CSS | `assets/css/*.css` (5 files) | All visuals; `html{font-size:1.0417vw}` makes `1rem = 20px` at a 1920-wide layout viewport (`css/glass.css:2`) |
| Assets | `assets/{icons,darkicons,moonsun,sounds,wallpaper*.jpg,icon.png,ui}` | Card art, sounds, wallpapers |

Build setup facts (`app/build.gradle:1-13`, `build.gradle:1`, `settings.gradle`, `gradle/wrapper/gradle-wrapper.properties:3`): Android Gradle Plugin **8.7.3**; Gradle wrapper **9.6.0**; `compileSdk 35`, `minSdk 23`, `targetSdk 30`; `versionCode 6`, `versionName '1.5'`; `namespace`/`applicationId` `com.glasslauncher`. **No Kotlin, no `dependencies {}` block, no buildTypes/minify/signing config, no `sourceSets` override** — Java only, `android.useAndroidX=true` is set in `gradle.properties` but nothing uses AndroidX. `settings.gradle` at HEAD adds the Foojay toolchain resolver plugin; `gradle/gradle-daemon-jvm.properties` pins `toolchainVersion=21` (Phase 0 commit). Observed during the migration audit (Linux, clean clone of the baseline): `assembleDebug` succeeds with AGP 8.7.3 on Gradle 9.6.0 (with deprecation warnings); `assembleRelease` failed in `lintVitalAnalyzeRelease` while the daemon ran JDK 25 and succeeds once the daemon is pinned to JDK 21.

### 1.1 Phase 7 viewport rule (the "320 dpi fix")
* `index.html:4` — `<meta name="viewport" content="width=1920">`. **No `initial-scale`.**
* `MainActivity.java:41` — `s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true);` with the explanatory comment at `MainActivity.java:42-50`.
* Effect (per `PHASE7_DPI.md:22-49` and the Java comment): the page lays out at exactly 1920 CSS px regardless of Android density, and overview mode zooms it to the physical screen. Adding `initial-scale=1` back is documented as re-introducing the 160 dpi dependency (`PHASE7_DPI.md:24-37`). All sizes in CSS are `rem` (1rem = `1.0417vw` = 20 CSS px at 1920) so everything scales with the viewport.
* `home.js:77` reads the rem size with `getComputedStyle`; `home.js:86` uses `window.innerHeight`. No other density-related code exists (`PHASE7_DPI.md:9-16`; confirmed by grep: no `devicePixelRatio`, `setInitialScale`, `setTextZoom`).

### 1.2 Boot sequence
1. Android starts `MainActivity` (HOME intent filter, `AndroidManifest.xml:41-46`). `onCreate` (`MainActivity.java:19-56`):
   * sets immersive-sticky system UI flags (`:21`, deprecated `setSystemUiVisibility` API);
   * `new WebView(this)`, background `0xff0b0b14` (`:22-23`);
   * accessibility off: `setImportantForAccessibility(NO)` (`:31`) + a delegate that swallows `sendAccessibilityEvent` (`:36-38`) — Phase 5 fix for the yellow focus ring;
   * settings: JS on, DOM storage on, `mediaPlaybackRequiresUserGesture=false`, `allowFileAccess=true`, wide viewport + overview (`:40-41`);
   * `addJavascriptInterface(new GlassBridge(this), "AndroidBridge")` (`:51`); `GlassBridge` constructs `AppDiscovery`, `SystemControls`, `SettingsLauncher` (`GlassBridge.java:15-20`);
   * default `WebChromeClient` / `WebViewClient` (`:52-53`); `setContentView(web)`; `loadUrl("file:///android_asset/index.html")` (`:54-55`).
2. `index.html` parses (`index.html:12-39` DOM), then loads scripts in this order at the end of `<body>` (`index.html:41-53`): `apps.js`, `js/glass.js`, `icons.js`, `appdata.js`, `theme.js`, `recent.js`, `home.js`, `controlcentre.js`, `registry.js`, `keyboard.js`, `shortcuts.js`, `search.js`, `main.js`. (The HTML comment at `index.html:1` lists an older, shorter module order and omits `recent` and `keyboard`.)
3. Module-load side effects, in order:
   * `glass.js:55,62` creates 3 `Audio` objects each for `navigate.wav` and `click.wav` (`preload='auto'`, `load()`); `glass.js:65-80` creates 4 src-less `Audio` objects for each of 4 key-sound pools (16 total).
   * `theme.js:5-12` computes `dark=night()`, sets `#wp` background to `wallpaper.jpg`, preloads `wallpaper2.jpg` via `new Image()` and then assigns it to `#wpd` (or falls back).
   * `recent.js:5,41` reads `glass.recent` from `localStorage` and calls `refresh()` (before the app list exists).
   * `home.js:8,161-176` sets `--rowgap`, wires header hover/click and the `wheel` listener, fills the Search / Control-Centre header icons.
   * `search.js:9,71` fills the search icon and **builds the whole on-screen keyboard DOM at load** (34 key elements, `keyboard.js:34-51`).
4. `main.js:38-40` boot line (synchronous, still inside script evaluation): optional `body.noinvert` → `body.lite` (unless `PERF.lite===false`) → `G.perf.init(); G.theme.init(); tick(); G.apps.load(); G.recent.refresh(); G.home.rebuild(); setInterval(tick,10000)`.
   * `G.apps.load()` makes the **one and only** `getInstalledApps` call (`appdata.js:9`) and resolves every configured app to an installed package (`appdata.js:11,15-22`).
   * `G.home.rebuild()` (`home.js:63-74`) builds 2 rows (5 pinned + 4 entertainment = 9 slots), then `layout()` + `render(true)`.
5. After `onCreate`, `onResume` posts a delayed `glassResume()` (400 ms, `MainActivity.java:58-62`) which only does work if the Control Centre is open (`controlcentre.js:155`).

---

## 2. Java classes

| Class (file) | Responsibility |
|---|---|
| `MainActivity` (`MainActivity.java`) | HOME activity; creates/configures the WebView; accessibility suppression; immersive flags; `dispatchKeyEvent` (`:64-75`): volume keys pass to `super` then schedule `glassVolumeSync()` 250 ms later on `ACTION_DOWN`; `KEYCODE_BACK` `ACTION_UP` is consumed and turned into a synthetic JS keydown with `keyCode` 10009 (`BACK_JS`, `:15,73`); `onBackPressed` does the same (`:77-79`). `onResume` → delayed `glassResume()` (`:58-62`). No `onPause/onStop/onDestroy/onNewIntent/onTrimMemory/onRequestPermissionsResult` overrides (grep-confirmed). `webView()` accessor for the bridge (`:17`) |
| `GlassBridge` (`GlassBridge.java`) | The only object exposed to JS. Each method is a one-line delegate (apps → `AppDiscovery`, system → `SystemControls`, settings → `SettingsLauncher`); `goHome`, `showKeyboard`, `hideKeyboard` run on the UI thread via `runOnUiThread` |
| `AppDiscovery` (`AppDiscovery.java`) | `launchIntent(pkg)` = `getLeanbackLaunchIntentForPackage` else `getLaunchIntentForPackage` (`:23-28`); `installedAppsJson()` (`:31-45`); `launch` (`:47-55`); `isInstalled` (`:57-59`); `iconDataUri` with `LruCache<String,String>(96)` (`:19,62-79`) |
| `SystemControls` (`SystemControls.java`) | Read-only device state + media volume: `volumeInfo/setVolume/toggleMute` (`:35-56`), `audioOutput` (`:60-80`), `wifiInfo` (`:82-107`), `bluetoothInfo` (`:110-136`, reflection for `BluetoothDevice.isConnected`), `deviceInfo` (`:138-157`), `requestLocationPermission` (`:159-165`) |
| `SettingsLauncher` (`SettingsLauncher.java`) | Maps a `kind` string to a `Settings.ACTION_*` (`:13-27`); `has(kind)` = `resolveActivity != null` (`:30-33`); `open(kind)` starts it, falling back to `ACTION_SETTINGS` (`:36-47`) |
| `AppTheme` (`res/values/styles.xml:1`) | Parent `android:style/Theme.Material.Light.NoActionBar`; fullscreen, no title, status/navigation bar colour `#0b0b14`, `fontFamily sans` |

Manifest `<activity>`: `screenOrientation=landscape`, `exported=true`, `windowSoftInputMode=adjustNothing|stateHidden`, `configChanges=keyboard|keyboardHidden|orientation|screenSize` (`AndroidManifest.xml:40`). No `launchMode`, no `android:icon` (grep-confirmed). Application: `usesCleartextTraffic=true` (`:39`; nothing in the code loads network content), `allowBackup=false`.

---

## 3. `AndroidBridge` (all `@JavascriptInterface` methods)

All JS calls go through `G.bridge.call/json` (`js/glass.js:25-38`): a synchronous `try { AndroidBridge[fn](arg) } catch → null`. `json()` additionally `JSON.parse`s string results. There is **no async/promise bridge**; every call blocks the JS thread until Java returns. Per Android's `addJavascriptInterface` contract, `@JavascriptInterface` methods run on a WebView-owned background thread, not the Android main/UI thread *(external knowledge)*, so the UI thread is not blocked by them, but WebView's JS/renderer work waits for the result. "Binder" below = a call into a system service process (PackageManager, AudioManager, ConnectivityManager, WifiManager, Bluetooth, etc.) *(external knowledge about which Android APIs cross process boundaries)*.

| # | Method (`GlassBridge.java`) | Java implementation | Android APIs | Binder / PackageManager work? | Called from JS (file:line) | When |
|---|---|---|---|---|---|---|
| 1 | `launchPackage(String)` `:23` | `AppDiscovery.launch` `AppDiscovery.java:47-55` | `PackageManager.getLeanbackLaunchIntentForPackage`, fallback `getLaunchIntentForPackage`, `Context.startActivity(FLAG_ACTIVITY_NEW_TASK)` | Yes: 1–3 PM intent queries + `startActivity` | `glass.js:132` inside `G.launch` (callers: `home.js:61`, `recent.js:37`, `registry.js:64` app entries) | On OK/click of a card, Recently-Used panel or app search result |
| 2 | `getInstalledApps()` `:24` | `AppDiscovery.installedAppsJson` `:31-45` | `getInstalledPackages(GET_META_DATA)`; per package `launchIntent()` (Leanback then Launcher lookup) and `applicationInfo.loadLabel(pm)` | **Yes, heavy**: one bulk PM call + per package up to 2–3 PM queries (`getLeanback…`; if null `getLaunch…`) + label load | `appdata.js:9` only | Once at boot (`main.js:40`); never refreshed afterwards |
| 3 | `goHome()` `:25-27` | posts to UI thread: `ACTION_MAIN` + `CATEGORY_HOME`, `FLAG_ACTIVITY_NEW_TASK`, `startActivity` | `Context.startActivity` | Yes (activity start) | `glass.js:128` `G.exitApp` ← `home.js:60` (`action:'exit'`, unused by config), `home.js:135` (Back in rows), `home.js:146` (Back in header) | Back at home level |
| 4 | `isInstalled(String)` `:29` | `AppDiscovery.isInstalled` `:57-59` | same launch-intent lookup | Yes (PM queries) | **No JS call site** (grep). JS uses its own `G.apps.isInstalled` over the cached list (`appdata.js:23`) | Never |
| 5 | `getAppIcon(String)` `:30` | `AppDiscovery.iconDataUri` `:62-79` | `PackageManager.getApplicationIcon`, `Bitmap.createBitmap(128,128,ARGB_8888)`, `Drawable.draw`, `Bitmap.compress(PNG,100)`, `Base64.encodeToString(NO_WRAP)`, `LruCache` (96 entries) | Yes (PM + resource load) on a cache miss; CPU for PNG encode + base64 | `icons.js:49` inside `G.icons.packageIcon`; callers `search.js:40` (every app result row without override), `recent.js:26` (last-used app with no configured art), `home.js:23` (only if a card has `useAppIcon`; none configured) | Per search render for each cold package; JS also memoises in `G.icons._cache` (`icons.js:44-47`) |
| 6 | `getVolumeInfo()` `:33` | `SystemControls.volumeInfo` `:35-44` | `AudioManager.getStreamVolume/getStreamMaxVolume/isStreamMute/isVolumeFixed` (STREAM_MUSIC) | Yes (AudioService, 4 calls) | `controlcentre.js:68` (`refreshVol`) | CC open (`:140`), every 2 s while CC open (`:141`), 250 ms after a volume key (`glassVolumeSync`, `:154`), after mute/volume change, on resume (`:155`) |
| 7 | `setVolume(int)` `:34` | `SystemControls.setVolume` `:45-48` (then `volumeInfo()`) | `AudioManager.setStreamVolume(STREAM_MUSIC, level, 0)` | Yes | `controlcentre.js:110` (`adjustVol`) | Each Left/Right press (incl. key repeat) on the Volume tile |
| 8 | `toggleMute()` `:35` | `SystemControls.toggleMute` `:49-56` | `isStreamMute`, `adjustStreamVolume(ADJUST_MUTE/UNMUTE)` | Yes | `controlcentre.js:117` | OK/click on Volume tile |
| 9 | `getAudioOutput()` `:36` | `SystemControls.audioOutput` `:60-80` | `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`, `AudioDeviceInfo.getType/getProductName` | Yes | `controlcentre.js:65` (`refreshOut`, only inside `refreshAll`) | CC open and on resume; **not** in the 2 s timer |
| 10 | `getWifiInfo()` `:37` | `SystemControls.wifiInfo` `:82-107` | `WifiManager`, `ConnectivityManager.getAllNetworks` + `getNetworkCapabilities` per network, `checkSelfPermission(ACCESS_FINE_LOCATION)`, `WifiManager.getConnectionInfo`, `isWifiEnabled` | Yes (several) | `controlcentre.js:47` | CC open, every 2 s while open, on resume |
| 11 | `getBluetoothInfo()` `:38` | `SystemControls.bluetoothInfo` `:110-136` | `BluetoothAdapter.getDefaultAdapter/isEnabled/getBondedDevices`; per bonded device `getName()` and reflective `isConnected()` | Yes (Bluetooth service; per-device calls scale with paired count) | `controlcentre.js:55` | CC open, every 2 s while open, on resume |
| 12 | `getDeviceInfo()` `:39` | `SystemControls.deviceInfo` `:138-157` | `Build.*`, `ConnectivityManager.getActiveNetwork/getLinkProperties`, `StatFs(Environment.getDataDirectory())` | Yes (connectivity) + a filesystem `statfs` | `controlcentre.js:86` (`refreshDev`, only inside `refreshAll`) | CC open and on resume; not in timer |
| 13 | `requestLocationPermission()` `:40` | `SystemControls.requestLocationPermission` `:159-165` | `checkSelfPermission`; `runOnUiThread(requestPermissions(...,7))` | Permission service | `controlcentre.js:144` | First CC open ever (guarded by `glass.locasked`); result is never handled in Java (no `onRequestPermissionsResult`) |
| 14 | `openSettings(String)` `:41` | `SettingsLauncher.open` `:36-47` | `startActivity(new Intent(Settings.ACTION_*), NEW_TASK)`; fallback `ACTION_SETTINGS` | Yes (activity start) | `glass.js:143` `G.openSettings`; callers `controlcentre.js:114,115,118-121`, `shortcuts.js:22` | OK on CC tiles / search settings shortcuts |
| 15 | `hasSettings(String)` `:43` | `SettingsLauncher.has` `:30-33` | `new Intent(action).resolveActivity(pm)` | Yes (PM) | `registry.js:34` `G.registry.hasSettings` (memoised per kind, `registry.js:33-36`); callers `shortcuts.js:21` (`available()` for 10 kinds), `controlcentre.js:91-92` (`sound`, `display`) | First search render after open / first CC open; then served from JS cache |
| 16 | `showKeyboard()` `:46-54` | `runOnUiThread`: `webView.requestFocus()`, `InputMethodManager.showSoftInput` | IMM | UI thread | **No JS call site** (Phase 4 replaced Android IME with the in-page keyboard, `PHASE4.md:28-29`) | Never |
| 17 | `hideKeyboard()` `:55-62` | `runOnUiThread`: `hideSoftInputFromWindow` | IMM | UI thread | **No JS call site** | Never |

Java → JS (the only reverse direction): `glassResume()` (`MainActivity.java:61` → `controlcentre.js:155`), `glassVolumeSync()` (`MainActivity.java:70` → `controlcentre.js:154`), and the synthetic Back keydown (`MainActivity.java:15,73,78` → `main.js:21`). All three use `evaluateJavascript` on the UI thread.

---

## 4. JS modules (global namespace `window.Glass`, aliased `G`)

| File | Defines | Responsibilities | Depends on |
|---|---|---|---|
| `apps.js` | `window.APPS`, `THEME`, `SOUND`, `PERF`, `LAYOUT`, `UI_ICONS`, `ENTERTAINMENT`, `ICON_OVERRIDES`, `SEARCH_ALIASES` | User-editable config (see `FEATURE_INVENTORY.md` for values) | none |
| `js/glass.js` | `G`, `G.$`, `G.state{zone,dark}`, `G.zones`, `G.cfg`, `G.bridge`, `G.store`, `G.toast`, `G.sound{nav,click,key}`, `G.spatial`, `G.perf{init,watch}`, `G.initials`, `G.exitApp`, `G.launch`, `G.pointer`, `G.openSettings`, `G.setZone` | Core: config merge (`:10-22`), bridge wrapper, `localStorage` store under `glass.` (`:41-45`), toast (3.2 s), sound pools, spatial nav for CC, blur watchdog, launch helper | `apps.js` globals, DOM, `Audio`, `localStorage`, `AndroidBridge` |
| `js/icons.js` | `G.icons{has,svg,fill,override,packageIcon,_cache}` | Inline SVG set (`:5-28`), PNG-slot-with-SVG-fallback (`fill`, `:33-40`), override lookup, async-by-`setTimeout(0)` real app icons (`:45-52`) | `G.cfg`, `G.bridge` |
| `js/appdata.js` | `G.apps{installed,HIDDEN,load,resolve,isInstalled,byId}` | One-shot installed list; id resolution (current id → `ids[]` → name `match`, `:15-22`); `HIDDEN=/^(org\.tizen|com\.samsung|DU\.TV\.PRODA)/i` (`:7`) | `G.bridge`, `G.cfg` |
| `js/theme.js` | `G.theme{isDark,set,toggle,onChange,tick,init}` | Light/dark, 19:00–07:00 schedule (`:5`), wallpaper layers, change listeners | `G.cfg.theme`, `G.state`, `G.sound` |
| `js/recent.js` | `G.recent{record,focusable,setFocus,activate,refresh}` | Last-launched panel | `G.store`, `G.cfg`, `G.apps`, `G.theme`, `G.icons`, `G.launch` |
| `js/home.js` | `G.home{rebuild,render,layout,focusHeader,focusRows,hdrIndex,hdrOrder,rows}`, `G.zones.rows`, `G.zones.hdr` | Rows/cards, clock-shrink scroll (`layout/applyScroll`, `:78-106`), focus render, header buttons, mouse hover/click/wheel, theme-art swap | `G.recent`, `G.icons`, `G.theme`, `G.sound`, `G.launch`, `G.exitApp`, `G.pointer`; lazily `G.search`, `G.cc`, `G.ids` |
| `js/controlcentre.js` | `G.cc{open,close,isOpen}`, `G.zones.cc`, `window.glassVolumeSync`, `window.glassResume` | 9-tile Control Centre, polling while open | `G.bridge`, `G.icons`, `G.theme`, `G.spatial`, `G.home`, `G.perf`, `G.store`, `G.sound`, `G.openSettings`; lazily `G.registry`, `G.search` |
| `js/registry.js` | `G.registry{norm,register,addShortcut,hasSettings,entries,query}` | Search index + scorer; app provider (`:60-66`) | `G.apps`, `G.cfg`, `G.bridge`, `G.launch` |
| `js/keyboard.js` | `G.keyboard.create(host,opts)` | In-page remote-friendly QWERTY/number keyboard | `G.icons`, `G.sound`, `G.pointer` |
| `js/shortcuts.js` | (registers entries; merges Play-Store aliases `:34-35`) | 11 Android-settings shortcuts + 3 Glass shortcuts | `G.registry`, `G.openSettings`, `G.search`, `G.cc`, `G.theme` |
| `js/search.js` | `G.search{open,close,isOpen}`, `G.zones.search` | Search overlay, debounced render, hardware-keyboard typing | `G.registry`, `G.keyboard`, `G.icons`, `G.home`, `G.perf` |
| `js/main.js` | `G.ids{show,isOpen}`, global `keydown` router, `resize`, clock `tick`, boot | Entry point | everything |

### 4.1 HTML structure (`index.html:12-39`) and CSS ownership

| DOM | Purpose | CSS file |
|---|---|---|
| `#wp`, `#wpd`, `#shade` | wallpaper light / dark layers + gradient shade | `glass.css:16-20` |
| `#head > #date, #clock` | clock & date, shrinks on scroll | `home.css:3-7` |
| `#stage > #rowsWrap` | scroll container for rows (`.row > .rowLabel + .dock.glass > .slot > .tile`) | `home.css:9-36` |
| `#recent` (`.glass`) | Recently Used | `recent.css` |
| `#hdrBtns > #searchbtn, #ccbtn` (`.hbtn.glass`) | header buttons | `glass.css:47-56` |
| `#ccScrim`, `#cc` (`.glass`) → `#cchead`, `#ccbody > #ccinner` | Control Centre | `controlcentre.css` |
| `#search > #spanel` (`.glass`) → `#sbar`, `#sbody > #sleft/#sres/#slist`, `#skb`, `#shint` | Search + keyboard | `search.css` |
| `#ids` (`.glass`), `#toast` (`.glass`) | installed-app ID list, toast | `glass.css:58-68` |

Stylesheet load order: `glass.css`, `home.css`, `recent.css`, `controlcentre.css`, `search.css` (`index.html:6-10`). `glass.css` also owns tokens, `.glass`, `.gt`, icon filters. `.gt` is applied only to Control Centre tiles (`controlcentre.js:24` → `gt cci`); `.cci` rules in `controlcentre.css:21-36` override most `.gt` properties.

---

## 5. Flow traces

### 5.1 HOME / boot
HOME key → Android resolves the HOME activity → `MainActivity` (see §1.2). `G.exitApp` (Back in rows/header) calls `goHome` → `ACTION_MAIN/CATEGORY_HOME` with `NEW_TASK` (`GlassBridge.java:26`). What that resolves to when Glass is the default HOME is not determinable from source (no `launchMode`, no `onNewIntent`). README: Du TV and Projectivy are intentionally left installed as fallbacks (`README.md:21-22`).

### 5.2 App discovery
`G.apps.load()` (`appdata.js:8-13`) → `getInstalledApps` → `AppDiscovery.installedAppsJson` (`AppDiscovery.java:31-45`): `pm.getInstalledPackages(GET_META_DATA)`; for **each** package `launchIntent(pkg)` (= `getLeanbackLaunchIntentForPackage`, and when null `getLaunchIntentForPackage`) — packages with neither are skipped; for survivors `{id,name:loadLabel,show:true}`. Because `targetSdk=30`, visible packages are limited by the `<queries>` block (`AndroidManifest.xml:8-38`: LEANBACK_LAUNCHER and LAUNCHER intents, 10 settings actions, 16 named packages); there is no `QUERY_ALL_PACKAGES`. The JS then resolves each configured app (`APPS` + `ENTERTAINMENT.apps`, `appdata.js:11`) with `resolve()` (`:15-22`): keep `a.id` if installed → first installed of `a.ids` → first installed whose name matches `a.match` (skipping `show===false` and `HIDDEN`); never invents an id, except when the list is empty it keeps the first candidate (`:20`). Resolution mutates the config objects in place. The list is **never re-fetched** (single call site) — apps installed/removed while Glass runs are not reflected in Search / Recently Used validity / entertainment resolution until the WebView reloads.

### 5.3 Icon loading
* Configured cards: `<img>` of `icon` or `dark` PNG (`home.js:12-25`); `a._pre` preloads the other theme's PNG via `new Image()` (`home.js:21`), so both variants of every configured card are requested at boot. Theme change reassigns `img.src` (`home.js:179-181`).
* Real app icons (`G.icons.packageIcon`, `icons.js:45-52`): JS cache hit → callback synchronously; miss → `setTimeout(...,0)` then **synchronous** `getAppIcon`. Java (`AppDiscovery.java:62-79`): LruCache(96) lookup; else `getApplicationIcon` → 128×128 ARGB bitmap → `compress(PNG,100)` → `data:image/png;base64,…`. JS accepts only strings starting `data:` (`icons.js:49`). No in-flight de-duplication: two requests for the same package queued before the first returns both call the bridge (second hits the Java LruCache). JS `_cache` has no size bound.
* UI PNG slots (`UI_ICONS`, `apps.js:30-45`): `G.icons.fill` loads the PNG; `onerror` → inline SVG (`icons.js:33-40`). `assets/ui/` contains only `README.txt`, so **every UI PNG slot 404s and falls back to SVG** (a failed `file:///android_asset` load per distinct key). `themeLight`/`themeDark` point at existing `moonsun/*-filled.png`, but the CC theme tile then replaces its holder with inline SVG (`controlcentre.js:80-83`).

### 5.4 Search
* Open: header button / Control-Centre `Search` tile / Tab (`search.js:79-84`); sets zone `search`, resets keyboard, empty query shows 6 suggestions (`SUGGEST`, `search.js:8,45-46`), `G.perf.watch(700)`.
* Typing: `setQuery` updates the visible text immediately and debounces `render` by 110 ms (`search.js:12-15`). `flushRender` forces it before moving into results / submit (`:19,69`).
* `G.registry.query(text,24)` (`registry.js:46-56`): `norm` = lower-case, strip everything but `a-z0-9`/`\u00c0-\uffff`/space, collapse whitespace, trim (`:7`); `fieldScore` (`:11-20`): exact/compact-equal 100, prefix 85, word-prefix 70, compact-contains 50, fuzzy subsequence 20 (titles only, query ≥ 4 chars); `score` (`:21-25`): best of title and keywords × 0.92 minus `min(5, len(title)*0.02)`; sort: score desc, app before shortcut, then `norm(title)` asc (`:50-54`).
* Entries: app provider (`registry.js:60-66`: installed, `show!==false`, not `com.glasslauncher`, not `HIDDEN`; keywords = `SEARCH_ALIASES`), 11 settings shortcuts (`shortcuts.js:6-23`, each hidden if `hasSettings(kind)` false; the `main` "Settings" shortcut is hidden when `com.android.tv.settings` or `com.android.settings` is an installed launchable app, `shortcuts.js:17-18`), 3 Glass shortcuts (`:26-31`). "Play Store" is not a shortcut: it is the installed app `com.android.vending` with aliases `play store, google play, store, app store, download apps` (`shortcuts.js:33-35`).
* Rendering (`search.js:44-67`): `list.innerHTML=''` then up to 24 rows, each with an icon holder; app rows trigger `packageIcon` → bridge `getAppIcon` per cold package. Result subtitle is rendered **only for shortcuts** (`search.js:58`); app results show no package id (contrary to `PHASE3.md:27-28` / `PHASE6.md:30`).
* Input: on-screen keyboard (`keyboard.js`), hardware keyboard (single printable `e.key` typed directly, `search.js:116`; Backspace deletes, `:117`; Enter within 2.5 s of hardware typing = submit, `:118`).

### 5.5 Recently used
Storage key `glass.recent` = `{id,name}` (`recent.js:5,34`). `G.recent.record(a)` is called only from `G.launch` when `launchPackage` returned `true` (`glass.js:135`); it ignores falsy ids and `com.glasslauncher` (`recent.js:34`). The panel is shown only if `valid()` (`recent.js:12`: a record exists and either the installed list is empty or contains the id). Art: configured card art (theme-aware) → icon override → real icon via `packageIcon` → initials. Focusable in header order `[2,0,1]` when valid (`home.js:140`).

### 5.6 Control Centre
Open (`controlcentre.js:136-145`): builds 9 tiles once (`:21-37`, SPEC `:10-20`: Appearance, Wi-Fi, Bluetooth, Volume, This box, Settings, Sound, Display, Search); sets zone `cc` and `body.ccopen`; `refreshAll()` (clock, `getWifiInfo`, `getBluetoothInfo`, `getAudioOutput`, `getVolumeInfo`, theme, `getDeviceInfo`, `hasSettings`×2 first time); starts a **2 s interval** (`refreshVol/refreshWifi/refreshBT`) and a **10 s interval** for the CC clock; `G.perf.watch(800)`; on the very first open ever, `requestLocationPermission` (`glass.locasked`). Close clears both intervals (`:147`). Tiles: Wi-Fi/Bluetooth/This box/Settings/Sound/Display open Android settings; Appearance toggles theme; Volume: Left/Right ±`max(1,round(max/20))`, OK toggles mute (or toast if fixed volume); Search closes CC and opens Search. Reads real state only (no Wi-Fi/Bluetooth toggles). D-pad uses `G.spatial.move` (`glass.js:86-100`, `getBoundingClientRect` on all 9 tiles per press). Scroll uses native `scrollTo({behavior:'smooth'})` (`:97-104`).

### 5.7 Themes
`night()` = hour ≥ 19 or < 7 (`theme.js:5`). `dark` initial = `night()`; `G.theme.tick()` (every 10 s from `main.js:35,40`) flips only when the night/day boundary changes (`theme.js:20`), so a manual toggle holds until the next boundary. The choice is **not persisted** (no `G.store` call in `theme.js`). `apply()` toggles `body.dark`, `G.state.dark`, calls listeners (cards swap art `home.js:179-181`; recent `recent.js:40`; CC tile `controlcentre.js:152`). Wallpapers: `#wp` (light) always set, `#wpd` fades in via `opacity .7s` (`glass.css:17-18`). The moon/sun header button was removed in Phase 3 (`PHASE3.md:10-11`); `THEME.iconLight/iconDark` (`apps.js:9`) are never read by JS (grep). Toggle sources: CC "Appearance" tile (`controlcentre.js:116`) and Search shortcut "Appearance" (`shortcuts.js:30-31`).

### 5.8 Input routing
One `keydown` listener on `document` (`main.js:21-29`):
1. `Escape` (27), or `Backspace` (8) outside the Search zone → `preventDefault`, treated as `10009` (Back) (`:23`).
2. If the ID list overlay is open: Up/Down scroll 120 px, Back/Enter close, everything else swallowed (`:24`).
3. Dispatch to `G.zones[st.zone]` (`rows`, `hdr`, `cc`, `search`) (`:25`). `Tab`/`Shift+Tab` → that zone's `tab(dir)` (`:26`).
4. Key-repeat: `e.repeat && keyCode===13 && zone!=='search'` is dropped (`:27`). **Arrow keys are not throttled on repeat.**
5. `z.key(k,e)`.

Back from the remote: Android delivers `KEYCODE_BACK`; `MainActivity.dispatchKeyEvent` consumes `ACTION_UP` and evaluates `BACK_JS`, which creates a `KeyboardEvent('keydown')`, overrides `keyCode` to `10009`, dispatches on `document` (`MainActivity.java:15,73`). Mouse right-click → Back relies on Android mapping it to `KEYCODE_BACK` *(external knowledge; `PHASE6.md:65` says it was not tested)*. Zone behaviour: rows (`home.js:128-138`), header (`:141-148`), CC (`controlcentre.js:124-134`), search (`search.js:113-133`). Mouse: per-card `mousemove` hover-focus gated by ≥ 4 px real movement (`glass.js:139-141`) and `overlayOpen()`; click activates; window `wheel` (passive) moves between rows, throttled 350 ms (`home.js:167-173`); Search results have a non-passive wheel handler (`search.js:108-111`); CC scrim and Search backdrop click close (`controlcentre.js:151`, `search.js:107`).

### 5.9 Volume-key sync and onResume
Volume keys: `MainActivity.dispatchKeyEvent` calls `super` then, on `ACTION_DOWN`, `postDelayed(glassVolumeSync, 250)` (`:66-72`); JS refreshes only if the CC is open (`controlcentre.js:154`). Each repeated `ACTION_DOWN` posts another call. `onResume` → `postDelayed(glassResume, 400)` (`:58-62`) → `refreshAll()` only if CC open (`controlcentre.js:155`).

---

## 6. Timers, intervals, polling, listeners

| Where | What | Notes |
|---|---|---|
| `main.js:40` (`tick`, `:32-37`) | `setInterval(tick,10000)` forever | updates `#clock` and `#date` text, runs `G.theme.tick()`; never cleared |
| `controlcentre.js:141` | `setInterval(...,2000)`: `refreshVol(); refreshWifi(); refreshBT()` | only while CC is open; 3 synchronous bridge calls per tick; cleared in `close()` (`:147`) |
| `controlcentre.js:142` | `setInterval(refreshClock,10000)` | only while CC open |
| `glass.js:49` | `setTimeout` 3200 ms to hide toast | |
| `glass.js:115-122` | `requestAnimationFrame` loop for 700/800 ms on each Search/CC open | blur watchdog (`G.perf.watch`); skipped once `noblur` is set |
| `icons.js:48` | `setTimeout(…,0)` per uncached icon | wraps a synchronous `getAppIcon` |
| `search.js:14` | `setTimeout(render,110)` debounce | cleared on new key / close |
| `keyboard.js:84` | `setTimeout(…,110)` remove pressed class | per key press |
| `MainActivity.java:61` | `web.postDelayed(…,400)` on every `onResume` | |
| `MainActivity.java:70` | `web.postDelayed(…,250)` per volume-key `ACTION_DOWN` | |
| CSS `search.css:15-16` | `@keyframes blink` infinite, only under `#search.on` | caret |
| Event listeners | `document keydown` (`main.js:21`); `window resize` (`main.js:30`: `layout()` + `render(true)`); `window wheel` (`home.js:167`); per-card `mousemove`/`click` (9 cards, `home.js:49,55`); header buttons + `#recent` (`home.js:160-164`); CC tiles `mousemove`/`click` ×9 (`controlcentre.js:34-35`) + scrim; Search backdrop click, results wheel, per-row `mousemove`/`click` re-attached every render (`search.js:63-64`), 34 keyboard keys `click`/`mousemove` (`keyboard.js:43-44`, re-attached when the 123/abc mode rebuilds); `Image.onload/onerror` handlers | no listener is ever removed; DOM rows/keys are discarded rather than detached |
| Theme listeners | 3 `G.theme.onChange` registrations (home, recent, CC) | `home.js:179`, `recent.js:40`, `controlcentre.js:152` |

---

## 7. State duplicated between Java and JS; persistence

| State | Java side | JS side | Staleness |
|---|---|---|---|
| Installed/launchable apps | live `PackageManager` | `G.apps.installed` snapshot taken once at boot (`appdata.js:9`) | never refreshed (see §5.2); JS and Java disagree after install/uninstall. Launch re-resolves in Java (`AppDiscovery.java:47-55`) so a removed app toasts "not installed" |
| App icons | `LruCache<String,String>(96)` (`AppDiscovery.java:19`) | `G.icons._cache` object (`icons.js:44`) | JS cache unbounded; both hold the same base64 strings |
| Volume | `AudioManager` truth | `vol` object in `controlcentre.js:7,68` | polled every 2 s while CC open; otherwise stale (not displayed) |
| Settings availability | `resolveActivity` | `settingsOk` map (`registry.js:68`) | cached for the process lifetime |
| Wi-Fi / Bluetooth / device info | live | rendered text only | polled while CC open |
| Theme | none | `dark` var + `body.dark` + `G.state.dark` | schedule-driven, not persisted |
| Last launched app | none | `glass.recent` in `localStorage` | `valid()` filters against the boot snapshot |
| Location-permission prompt | `ACCESS_FINE_LOCATION` grant | `glass.locasked` flag | JS asks once; Java never receives the result callback |

`localStorage` keys (all prefixed `glass.`, `glass.js:42-44`): `recent` (`recent.js:5,34`), `locasked` (`controlcentre.js:144`), `noblur`, `slowopen` (`glass.js:109,119-121`). No other keys exist (grep of `G.store` / `localStorage`). Storage origin is `file:///android_asset` with DOM storage enabled (`MainActivity.java:40`).

---

## 8. Lite / performance mode

* Config: `window.PERF = {lite:true}` (`apps.js:11`). `PERF.blur` is **not set**, so the blur mode is `'auto'` (`glass.js:108,112`).
* `main.js:39`: `body.lite` is added unless `PERF.lite===false`. JS never reads `lite` anywhere else. Only these CSS selectors depend on it:
  * `css/glass.css:30` `body.lite .slot::before` → no backdrop blur, flat `rgba(255,255,255,.3)` (the focus halo behind a card);
  * `css/glass.css:54-55` `body.lite .hbtn.glass` (+ `.f`) → no blur on the two header buttons;
  * `css/recent.css:16` `body.lite #recent.glass:not(.f)` → no blur on the Recently-Used panel unless focused;
  * `css/controlcentre.css:61` `body.lite #cc` → background colour change only (the `backdrop-filter` on `#cc` at `controlcentre.css:9` is **not** removed).
* Auto blur fallback (`glass.js:102-124`): `G.perf.init()` (boot) adds `body.noblur` if `PERF.blur===false`, or if `auto` and `glass.noblur` is stored true. `G.perf.watch(ms)` runs on each CC open (800 ms) and Search open (700 ms): a `requestAnimationFrame` loop counts frame deltas (ignoring ≥ 400 ms); if > 8 frames and > 50 % of them exceeded 42 ms it increments `glass.slowopen`; at ≥ 2 it stores `glass.noblur=true` and adds `body.noblur` immediately; a good run (> 8 frames, not slow) resets `slowopen` to 0. Reset by `Glass.store.remove('noblur')` (comment `glass.js:105`).
* `body.noblur` CSS: `glass.css:28-29` (`body.noblur .glass`, specificity 0,2,1: no backdrop-filter, denser background), plus `#toast`/`#ids` backgrounds (`:59,64`).
* **Static-inspection finding:** `#cc` sets its own `backdrop-filter: blur(1.5rem) saturate(1.5)` and `background` through an ID selector (`controlcentre.css:6,9`, specificity 1,0,0), which out-ranks `body.noblur .glass` (0,2,1). By cascade rules the `noblur` fallback therefore does not remove the Control Centre's blur or background (it does for `#spanel`, which sets no own `backdrop-filter`). Not verified in a browser; the watchdog is *triggered by* opening the CC, so on a slow box it can set `noblur` for everything except the panel being measured. Unmeasured.

---

## 9. Glass Utility Hub

Every reference in the repo (grep, excluding `.git/build/.gradle`):
* `AndroidManifest.xml:37` — `<package android:name="com.glasslauncher.utilityhub"/>` inside `<queries>` (package-visibility only).
* Docs only: `PHASE1.md:19`, `PHASE2.md:59`, `PHASE3.md:47`, `PHASE6.md:71`, `PHASE7_DPI.md:60` ("separate project, not in this zip, untouched; its AccessibilityService and Apps-button/VOD-button interception").

No Java, JS, CSS, `apps.js` or script references it. **Nothing in this repo depends on it**; removing the `<queries>` line would only stop Glass from being able to see that package, which no code uses.

---

## 10. Manifest permissions and who uses them

| Permission / feature (`AndroidManifest.xml`) | Used by |
|---|---|
| `ACCESS_NETWORK_STATE` `:2` | `ConnectivityManager.getAllNetworks/getNetworkCapabilities/getActiveNetwork/getLinkProperties` in `SystemControls.wifiInfo` (`:88-93`) and `deviceInfo` (`:144-150`) |
| `ACCESS_WIFI_STATE` `:3` | `WifiManager.isWifiEnabled`, `getConnectionInfo` (`SystemControls.java:96-100`) |
| `BLUETOOTH` `:4` | `BluetoothAdapter.getDefaultAdapter/isEnabled/getBondedDevices`, `BluetoothDevice.getName` (`SystemControls.java:113-125`). Behaviour on Android 12+ with `targetSdk 30` is not determinable from source |
| `ACCESS_FINE_LOCATION` `:5` | `checkSelfPermission` (`SystemControls.java:94,161`) to decide whether the SSID is available; runtime request `requestPermissions(...,7)` (`:162`) |
| `uses-feature leanback required=false` `:6`, `touchscreen required=false` `:7` | install-time filters only |
| `<queries>` `:8-38` | package visibility for `getInstalledPackages`/launch-intent lookups and settings `resolveActivity`; includes `com.glasslauncher.utilityhub` (unused) |
| Not declared | `QUERY_ALL_PACKAGES`, `WRITE_SETTINGS` (removed in Phase 3, `PHASE3.md:46`), `INTERNET`, `BLUETOOTH_CONNECT` |

---

## 11. Lifecycle

* `onCreate`: everything is created once (§1.2). `onResume`: delayed `glassResume` (400 ms). No `onPause`/`onStop`/`onDestroy`: the WebView is never paused, its timers are never stopped by Java (`pauseTimers`/`onPause` not called — grep), and it is not explicitly destroyed. Whether Chromium throttles background timers is not determinable from source. Opening a Settings page from the CC leaves `controlcentre.js:141`'s interval armed until the CC is closed.
* Recreation: `configChanges` covers `keyboard|keyboardHidden|orientation|screenSize` only (`AndroidManifest.xml:40`). A change in any other configuration (e.g. `density`, `uiMode`, `locale`, `layoutDirection`, `smallestScreenSize`, `screenLayout`) would recreate the activity → a new `WebView`, reload of `index.html`, a second boot, re-running `getInstalledApps`; `localStorage` survives, in-memory JS state (zone, theme choice, scroll) does not. There is no `onSaveInstanceState`.
* Memory cleanup: none explicit in Java; JS icon cache unbounded (`icons.js:44`); Search rebuilds its list with `innerHTML=''` (old nodes + closures GC'd); no `onTrimMemory`.
* `onBackPressed` and the consumed `ACTION_UP` Back both route to JS; the Activity never `finish()`es on Back — exit is `goHome` only.

---

## 12. Snapshot folders

`app/src/main/assets_baseline` (1.7 MB: original `index.html` 14 001 B, `apps.js`, icons, sounds, wallpapers), `assets_phase1` (`index.html`, `apps.js`), `assets_phase2` (`index.html`, `apps.js`, `MainActivity.java.txt`), `assets_phase3` (`index.html`, `css/*`, `js/*`). They are rollback copies described in `PHASE1.md:12-13`, `PHASE2.md:55`, `PHASE3.md:19`, `PHASE4.md:48`, `PHASE6.md:63`.
* **Not packaged:** AGP packages `src/main/assets` for the `main` source set only; `app/build.gradle` has no `sourceSets` override (13 lines, read in full) and the folder names differ from `assets`.
* **Referenced by:** only docs and a stale HTML comment in the active `index.html:1` ("Phase 3 copy of this file: assets_phase3/") and in snapshot HTML comments. No Java, JS, CSS, Gradle or script file loads them (grep `assets_(baseline|phase)`).
* `assets_phase3/js/search.js:45` still renders `e.subtitle` for all rows (incl. package id for apps); the active `search.js:58` does not.

---

## 13. Scripts (safety review)

| File | Line | Action | Safety note |
|---|---|---|---|
| `install-to-box.bat` | 2 | requires an APK path argument, else message + exit | — |
| | 3 | `adb get-state` must succeed | fails with several devices attached/ambiguous (not determinable) |
| | 4 | `adb install -r "%~1"` | reinstall keeping data; only this APK |
| | 5 | `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME` | launches whatever HOME resolves to (chooser if several) |
| | 6-7 | instructions + pause | |
| `safe-debloat.bat` | 2 | adb check | |
| | 4 | `pm disable-user --user 0 com.google.android.play.games` | disables Google Play Games for user 0 |
| | 5 | `pm disable-user --user 0 com.google.android.feedback` | disables Google Feedback for user 0 |
| | 6 | echoes "Du TV and Projectivy have not been touched" | true: only the two packages above are named |
| | 7 | `pm list packages -d` | lists **all** disabled packages (read-only) |
| `restore-debloat.bat` | 2 | adb check | |
| | 3-4 | `pm enable` for the same two packages | no `--user 0` flag (default user); symmetrical for user 0 on a single-user box |
| | 5 | prints "Restored." | printed regardless of adb result (no exit-code check) |

None of the three scripts touches `DU.TV.PRODA`, Projectivy, or any system launcher; they do not uninstall anything, reboot, or change the default HOME app. Neither debloat script has error handling beyond the initial `adb get-state`.

---

## 14. Performance-relevant observations (static inspection only — nothing was measured on a device)

### 14.1 Synchronous bridge calls on the JS thread / Binder work
1. **Boot:** `getInstalledApps` (`appdata.js:9`, `main.js:40`) is synchronous and does `getInstalledPackages` + up to 2–3 PM intent queries and `loadLabel` **per package** (`AppDiscovery.java:35-41`). Cost scales with the visible package count (not determinable from source). It blocks the JS thread before `G.home.rebuild()` runs, i.e. before the first cards are built.
2. **Search icons:** each cold app result calls synchronous `getAppIcon` (PM `getApplicationIcon` + 128×128 bitmap + lossless PNG encode + base64) inside `setTimeout(0)` (`icons.js:48-50`). Up to 24 per render; the `setTimeout` only defers, it does not move the work off the JS thread. No in-flight de-duplication; JS cache unbounded, Java cache count-limited (96).
3. **Control Centre polling:** while open, 3 synchronous calls every 2 s (`controlcentre.js:141`): `getVolumeInfo` (4 AudioManager calls), `getWifiInfo` (all networks + per-network capabilities + Wi-Fi info), `getBluetoothInfo` (adapter + per-paired-device `getName` + reflective `isConnected`). Volume key presses add a further `getVolumeInfo` 250 ms after each `ACTION_DOWN` (`MainActivity.java:70`).
4. `getDeviceInfo` runs `StatFs` on the data dir (`SystemControls.java:152`) on each CC open/resume.
5. `isInstalled`, `showKeyboard`, `hideKeyboard` exist but are never called (dead bridge surface).

### 14.2 Repeated work per keystroke / per interaction
* **Home D-pad (`home.js:110-125`):** `render()` removes `.f` from all 9 slots and re-toggles classes on every key, then `applyScroll()` reads `offsetTop/offsetHeight` of rows (`home.js:92-100`) — layout reads right after class writes (forced layout), plus a `transform` write on `#head` and `#rowsWrap`. `G.sound.nav()` plays an `Audio` clip per move (`glass.js:56-60`). Arrow-key auto-repeat is not throttled (`main.js:27` only filters OK).
* **Search typing:** the field updates instantly; the expensive part is debounced 110 ms (`search.js:14`). Each render (`registry.js:46-56`) **rebuilds `entries()`** (`registry.js:37-45`: re-maps every installed app into a new object + closure, concatenates shortcuts, calls every `available()`, and for `supersededBy` entries runs `G.apps.isInstalled` = linear scan of the installed list, `appdata.js:23`); `score()` calls `norm()` (lower-case + 2 regex replaces + trim) once in `fieldScore(title)` and again for the length penalty (`registry.js:12,25`), plus once per keyword; the sort comparator calls `norm()` twice on ties of equal score and kind (`registry.js:53`). Then 24 DOM rows + listeners are rebuilt (`search.js:44-67`) and `paintSel` runs `querySelectorAll('.sr')` (`:26`). Keyboard key-down plays an `Audio` whose `src` is reassigned each press (`glass.js:72`), i.e. a media load per key.
* **Search list scrolling:** `paintSel` reads `offsetTop/offsetHeight/clientHeight` per move (`search.js:29`); scrolling itself is a `transform` (good).
* **Control Centre D-pad:** `G.spatial.move` calls `getBoundingClientRect()` on all 9 tiles per press (`glass.js:89-91`); `focus()` reads `offsetTop/offsetHeight/scrollTop/clientHeight` then may start a smooth scroll.
* `tick()` every 10 s writes two `textContent`s and calls `toLocaleDateString` (`main.js:32-37`) — trivial.
* `resize` handler re-runs `layout()` and `render(true)` (`main.js:30`).

### 14.3 DOM rebuilds
Home rows are built once (`home.js:63-74`; rebuilt only on `rebuild()`); Search result list is rebuilt per render; keyboard rebuilt on 123/abc toggle and `reset()` on every Search open (`keyboard.js:97`, `search.js:82`); CC built once.

### 14.4 CSS costs (static; selectors with file:line)
| Effect | Location |
|---|---|
| `backdrop-filter: blur(1.5rem)` (=30 CSS px) `saturate(1.55)` on every `.glass` element + `0 1.8rem 4.4rem` (88 px blur) drop shadow and `inset 1.8rem` glow | `glass.css:23-25`. `.glass` elements: 2 × `.dock` (`home.js:42`, **always visible**), `#recent` (blur kept only when focused in lite, `recent.css:16`), 2 × `.hbtn` (blur removed in lite, `glass.css:54`), `#cc`, `#spanel`, `#ids`, `#toast` |
| `#cc` blur declared directly (ID specificity), not removed by `noblur` | `controlcentre.css:9` vs `glass.css:28` |
| `#toast` and `#ids` keep `.glass` blur when not `noblur` (`:59,64` only override `background`) | `glass.css:58-64` |
| Per-slot focus halo with backdrop blur `blur(.8rem) saturate(1.6)` + shadow, opacity-animated; **disabled in lite** | `home.css:26-28`, `glass.css:30` |
| `will-change: transform` on `#head`, `#rowsWrap`, 9 × `.slot`, `#cc`, `#spanel` (+`opacity`) | `home.css:3,11,25`; `controlcentre.css:5`; `search.css:5` |
| Full-stage `mask-image: linear-gradient` (set from JS, recomputed in `layout()`) on `#stage` | `home.js:88-90` |
| `background-clip:text` gradient on the 12rem clock | `home.css:5` |
| Animated paint properties (not compositor-only): `box-shadow` on `.tile` (`home.css:29-30`), `.gt`/`.cci`/`.sr`/`.k`/`.hbtn`/`#recent` `background`, `box-shadow`, `color` transitions (`glass.css:35,51`; `controlcentre.css:24-25`; `search.css:28,50`; `recent.css:3`) — contradicts `PHASE4.md:38` ("transform/opacity only") |
| Animated **layout** property `width` on the volume bar | `controlcentre.css:56` (`.bar i{transition:width .14s,...}`) |
| Per-tile sheen overlay `::after` translating on focus (0.7 s) | `home.css:24,31` |
| Full-screen layers: `#wp`, `#wpd` (opacity `.7s` crossfade), `#shade` gradient, `#ccScrim` gradient, `#search` scrim | `glass.css:16-20`; `controlcentre.css:2-3`; `search.css:2-3` |
| `filter: drop-shadow` on header icon holders; `filter: invert(1)` on `.uiimg` (unused because no UI PNGs exist) | `glass.css:50,40-43` |
| `#wpd.fallback` `filter: brightness .4 saturate .8` (only if dark wallpaper fails to load) | `glass.css:19` |
| Infinite caret `blink` animation while Search is open | `search.css:15-16` |
| `.sr` ×24 and `.k` ×34 each carry transitions incl. `box-shadow` and `transform` | `search.css:27-28,48-50` |

### 14.5 Image sizes (measured with `file`/`ls`, decoded sizes are arithmetic, not measured)
* Home card art: all 18 PNGs (`icons/*` 9, `darkicons/*` 9) are **800 × 480 RGBA**; displayed at `14rem × 8.4rem` = 280 × 168 CSS px (`home.css:20`). File sizes: `icons/` total 756 KB (largest `Disney+.png` 284 080 B, `retroarch.png` 195 626 B), `darkicons/` total 1 016 KB (`darkdisney.png` 135 740 B). Uncompressed RGBA of one image = 1 536 000 B (≈ 1.46 MiB); all 18 are requested at boot (preload, `home.js:21`) ≈ 26 MiB if all fully decoded at native size (whether Chromium decodes/downsamples lazily is not determinable from source). `icons/Spotify.png` and `darkicons/Spotify.png` are byte-identical.
* Wallpapers: `wallpaper.jpg` 114 525 B and `wallpaper2.jpg` 102 347 B, both **1920 × 1080** JPEG; both are always set as CSS backgrounds (`theme.js:8-12`); ≈ 8.3 MB decoded each (arithmetic).
* `moonsun/*` 512 × 512 PNGs (7–18 KB); only `sun-filled.png`/`moon-filled.png` are referenced (via `UI_ICONS`, `apps.js:41-42`); `moon (1).png`, `sun (1).png`, and `moon/sun.png` ↔ `*-outline/-filled` are byte-identical duplicates. `icon.png` (512 × 512 RGB, 111 724 B) is not referenced by code or manifest. `assets/` total 2.6 MB, 70 files.
* Sounds: `navigate.wav` 31 732 B, `click.wav` 160 200 B (16-bit stereo 44.1 kHz PCM); 18 key MP3s of 4.8–7.8 KB.

### 14.6 Work on the Android UI thread
`goHome`, `showKeyboard`, `hideKeyboard`, `requestPermissions` run via `runOnUiThread` (small). `evaluateJavascript` calls for `glassResume`, `glassVolumeSync`, Back (`MainActivity.java:61,70,73,78`). `dispatchKeyEvent` runs per key event on the UI thread (cheap). `launchPackage`/`openSettings` call `startActivity` from the bridge thread, not the UI thread. `new WebView` + immersive flags in `onCreate`. No Java-side background threads or executors exist.

---

## 15. Documentation vs code discrepancies found

| Doc claim | Code reality |
|---|---|
| `PHASE1.md:5-8` "All Apps" row | Removed in Phase 6 (`PHASE6.md:10-12`); `home.js:71` comment; only 2 rows are built (`home.js:67-70`) |
| `PHASE1.md:9` Up/Down moon ↔ Pinned ↔ All Apps | Moon button removed (`PHASE3.md:10-11`); header now holds Search + Control Centre (+ Recently Used focus stop) |
| `PHASE2.md:27,35-37,53` Brightness tile, `WRITE_SETTINGS` | Removed in Phase 3 (`PHASE3.md:40,46`); absent from manifest and code |
| `PHASE3.md:22-25` Utilities row, `window.UTILITIES`, `Glass.utilities` | Removed in Phase 6 (`PHASE6.md:63`). Leftovers: placeholder-card support `home.js:30-31`, `home.css:33-36`; `ENTERTAINMENT.title` is never read (`home.js:70` passes `''`) so no "Entertainment" heading renders despite `PHASE6.md:15` |
| `PHASE3.md:27-28`, `PHASE6.md:30` package id shown under app names in Search | Not rendered for apps (`search.js:58`); the installed-apps ID overlay (`G.ids`, `main.js:8-17`) is only reachable via an app entry with `action:'list'`, and no configured entry has one |
| `PHASE3.md:11` CC "36rem wide" | `controlcentre.css:4` `width:40rem` |
| `PHASE4.md:21` "Tiles stagger in" | Removed (`controlcentre.css:59-60`) |
| `PHASE4.md:14` "Only two surfaces blur" | Many `.glass` elements carry `backdrop-filter` (see §14.4) |
| `PHASE4.md:60`, `:71` `SOUND.volume` 0.35 / `keyVolume` 0.5 | `apps.js:10`: `volume:.2`, `keyVolume:.3` |
| `PHASE6.md:10-11` order Stremio, Moonlight, RetroArch, Rhenium; `PHASE6_NOTES.md:11` "Stremio, RetroArch, Moonlight, Rhenium" | `apps.js:57-60`: **Stremio, Rhenium, Moonlight, RetroArch** |
| `PHASE5.md:79` CC `inner.style.transform` scroll | Native `overflow-y:auto` + `scrollTo` (`controlcentre.css:17`, `controlcentre.js:100-103`) |
| `index.html:1` module list / "GLASS PHASE 6" | Omits `recent.js`, `keyboard.js` |
