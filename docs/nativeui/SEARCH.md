# Native Search (surface S5)

Kotlin components in `app/src/main/kotlin/com/glasslauncher/`, a behavioural port of the WebView Search. `NativeHomeActivity` wires them in through the interface below; the Home header Search
button and the Control Centre Search tile both open it. *Historical:* this file was first written before that wiring, when nothing
here touched the home view, the activity or the manifest.

## Components

| File | Role |
|---|---|
| `SearchModel.kt` | `InstalledApp`, `CatalogEntry`, `SearchResult`, sealed `SearchAction`, `KeyKind`, `SearchInitials` (G.initials) |
| `SearchCatalog.kt` | Builds the snapshot wire text from live facts (registry.js `snapshot()`/`listed()`), the built-in shortcut table, alias merge |
| `SearchRanking.kt` | Kotlin fallback: exact port of registry.js `norm/fieldScore/score/queryJs` and the `entries()` visibility rules |
| `SearchEngine.kt` | Background prepare/rank, 110 ms trailing debounce, sequence numbers, UI-thread delivery; `RankBackend` (Rust or test fake) |
| `SearchKeyboard.kt` | keyboard.js model: layouts, `move/step/jump/press/reset`, key geometry for column memory |
| `SearchController.kt` | search.js state machine (query, results, selection, focus, tab, hardware typing) + `SearchHost` |
| `SvgIcons.kt` | js/icons.js path data used by Search, parsed to move/line/cubic commands (arcs -> cubics) |
| `NativeSearchView.kt` | One custom View: draws panel, field, results, keyboard, hint bar; animations; key translation |

Tests (`app/src/test/kotlin/...`): fixture contract, Kotlin-vs-Rust ranking parity, engine debounce/staleness, controller flows, keyboard, icons.

## Integration contract

```kotlin
val engine = SearchEngine(Executors.newSingleThreadExecutor(), MainLooperScheduler(), facts /* SearchFactsSource */)
val search = NativeSearchView(this, host /* SearchHost */, engine, iconSource /* IconSource */, workerExecutor)
frameLayout.addView(search, MATCH_PARENT x MATCH_PARENT)            // above the home view
search.setBackdrops(blurLight, blurDark); search.setDark(isDark)    // same pre-blurred wallpapers as NativeHomeView
// Search button / OK on it:   search.open()
// Activity key dispatch:      if (search.isOpen && search.onKeyDown(keyCode, event)) return true
```

* `engine` needs a **single-thread** executor (ordering). `SearchFactsSource` is only called on that thread: `installedApps()` (AppDiscovery
  data: id, label, show), `aliases()` (window.SEARCH_ALIASES; empty today), `hasSettings(kind)` (`SettingsLauncher.has`, cached per kind
  by the engine), optional `extraEntries()`.
* `IconSource.loadIcon(pkg, sizePx)` runs on `workerExecutor` (reuse `AppDiscovery.iconPng` decoding or draw the drawable) and must return a bitmap
  of about `sizePx`; the view keeps a 6 MB LRU and a bounded negative cache, requesting only visible rows. `workerExecutor` also bakes the
  three nine-slice shadows once on first open.
* `SearchHost` (all on the UI thread). Search plays `playClick()` itself before every activation, so the host must not click again.
  * `launchApp(id, name)` = `G.launch`: start the package; on success record Recently Used; on failure toast
    "<name> could not be opened." / "<name> is not installed on this box.". Search stays open (as in the WebView).
  * `openSettings(kind)` = `G.openSettings`; Search stays open.
  * `toggleTheme(): Boolean` = `G.theme.toggle()`, returns "now dark"; Search toasts "Dark appearance"/"Light appearance". Call `search.setDark` too.
  * `openControlCentre(focusVolume)`: called after Search closed with `onSearchClosed(false)`; open CC from the rows zone, focus the volume row if true.
  * `onSearchClosed(restoreFocus)`: Back = `true` (restore the home focus saved when Search opened - the host saves it before calling `open()`);
    `false` only before `openControlCentre`.
  * `playNav()`, `playClick()`, `playKey(ALPHA|SPACE|ALT|ENTER)` (the `sounds/keys/*` pools), `toast(text)`.
* The activity should dim/fade the home view like `body.searchopen` (`#stage` opacity .5, `#head` .3, header buttons hidden, Recently Used .3);
  that is home-view work, done in `NativeHomeView.setSearchOpen` (its full-screen `saveLayerAlpha` is a rendering-review candidate in `MIGRATION_STATUS.md`).

### Result activation (search.js `run`, registry.js, shortcuts.js)

| Result | Action | Effect |
|---|---|---|
| installed app (tag "App") | `LaunchApp(id, name)` | `host.launchApp` |
| Wi-Fi, Bluetooth, Display, Sound, Apps, Storage, Date & time, Keyboard, Accessibility, About this box, Settings | `OpenSettings(kind)` | `host.openSettings` |
| Control Centre / Volume (tag "Glass") | `OpenControlCentre(false/true)` | close without focus restore, then `host.openControlCentre` |
| Appearance | `ToggleTheme` | `host.toggleTheme()` + toast |
| suggestion (empty query: Settings, Wi-Fi, Bluetooth, Volume, Play Store, Appearance) | `UseSuggestion(text)` | query := text, focus keyboard (internal) |

Home cards are not searchable in the WebView (registry has only the installed-apps provider and shortcuts), so they are not in the catalog.

## Parity sources

* Ranking and entry rules: `registry.js:7-25` (norm, fieldScore, score), `:126-150` (entries, queryJs), `:39-96` (snapshot wire), `snapshot.rs` (rules), `search.rs`.
* Shortcuts and aliases: `shortcuts.js:6-34`; settings availability `registry.js:122` + `SettingsLauncher.has`.
* Flow: `search.js:11-18` (setQuery, 110 ms debounce, 60 char cap), `:19-24` (flush, setFocus), `:25-33` (scroll rule), `:44-67` (render, empty/no-result text),
  `:68-69` (run, submit), `:79-90` (open/close), `:93-104` (tab), `:113-133` (keys, hardware typing, 2.5 s Enter window).
* Keyboard: `keyboard.js:8-28` (layouts), `:62-81` (nearest/move/step/jump), `:82-92` (press), `:97` (reset); held OK repeats only in Search: `main.js:27`.
* Look: `css/search.css`, `css/glass.css` (rem = 20 px at 1920): panel 1800x672 at (60,170), field 112 px, results 654 px, keyboard 1040 px, 94 px rows/keys,
  EASE `cubic-bezier(.2,.8,.2,1)`, open 420 ms, key focus 160 ms, row focus 180 ms, scroll 220 ms, caret 1.05 s blink.

## Fallback decision

`SearchEngine` uses Rust when `RankBackend.available`, the snapshot wire is non-null (no control character in a package id, no lone surrogate)
and `prepareSearch` returned valid references; the query has no lone surrogate. Any failure (null result, bad reference) switches that engine
to the Kotlin ranking for good, like `core.on=false` in registry.js. Kotlin is therefore used when `RustCore.isAvailable()` is false or for those
data-dependent cases; it is a line-for-line port, and `SearchRankingParityTest` asserts identical ordering with Rust on all 8 fixture cases
(thousands of queries: prefixes, case variants, unicode, random strings) plus limits (0, 1, 5, 24, 100).

## Deliberate differences

* Results arrive asynchronously. Where search.js read freshly rendered results (`flushRender` in Enter/Left/Tab), the controller flushes the
  debounce and completes the action when the results arrive (Enter shows "Nothing matches that" or moves to the list then). If the list is
  focused and the new results are empty, focus returns to the keyboard (same end state as the WebView).
* On open, suggestions are shown immediately instead of after the first 110 ms render.
* Held OK repeats on the keyboard but not on a result (the WebView would relaunch repeatedly).
* No pointer/mouse/wheel handling (the target box is D-pad only). Backdrop-click-to-close is not implemented.
* The panel's inner dark inset shadow, the key drop shadows of unfocused keys and the second small panel shadow are omitted; blur is the
  pre-blurred wallpaper (no real backdrop-filter on API 30), so content behind the panel is covered, not blurred.
* Fonts are Roboto (`sans-serif`, `sans-serif-medium`) instead of SamsungOne/Helvetica; widths differ slightly.

## Needs on-device checking

Evidence so far (reconciled 2026-11-13 against `3522999`): JVM tests in CI ("Android build + JVM/JNI tests") and the TV emulator
(`NativeUiPrototypeTest.b_nativeSurfacesAndRouting`: Search opens from Home, hardware typing changes the results, Back closes it).
Emulator timing and visuals are not device evidence. *Historical:* this heading first said no emulator was available.
`onDraw` was made allocation-free by `26f8055` (font metrics cached as fields); that is by code reading, not measured.


* Visual match against the WebView at 1920x1080/320dpi: panel position, glass tint, text metrics and baselines, icon sizes, focus scale/shadows.
* Frame pacing on the Arris VIP7802 while typing and while the results list scrolls (draw is allocation-free by construction, not measured).
* Hardware keyboard and remote digit keys (`KeyEvent.unicodeChar`), Tab/Shift+Tab, Back/Escape routing from the activity.
* Real icon loading latency through `IconSource`, LRU hit rate, and the shadow bake delay on first open (shadows pop in after the first frames).
* Key column memory: Chrome's rounded `offsetLeft/offsetWidth` is emulated; exact ties between keys could differ by one position in rare cases.
