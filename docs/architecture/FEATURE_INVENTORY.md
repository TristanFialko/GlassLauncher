# Glass Launcher — Feature Inventory (static audit)

Baseline: tag `glasslauncher-1.5-phase7-baseline` (commit `452320a`; source identical at `9df3967`); line numbers are relative to `app/src/main/` unless a path says otherwise. Glass 2.0 edits made afterwards are tracked in `MIGRATION_STATUS.md`. Companion document: `CURRENT_ARCHITECTURE.md`.

**Nothing here was run on a device.** "Status" is derived only from reading the source and the phase docs:

* **Present** — implemented in active code and reachable.
* **Partial** — code exists but is unreachable, unused by current config, or behaves differently from its documentation.
* **Documented-only** — described in a phase doc, absent from active code (usually removed in a later phase).
* **Not found** — neither documented as shipped nor present.

"How to test" lists on-device checks to perform; they have not been executed.

Phase docs: `README.md`, `PHASE1.md`, `PHASE2.md`, `PHASE3.md`, `PHASE4.md` (incl. "Sound pass"), `PHASE5.md`, `PHASE6.md` (+ `PHASE6_NOTES.md`), `PHASE7_DPI.md`.

---

## 1. Platform / shell

| Feature | Documented where | Implemented where | Status | How to test on device |
|---|---|---|---|---|
| HOME integration (HOME + Leanback launcher) | `README.md:3,15-18` | `AndroidManifest.xml:41-46` (MAIN + HOME + DEFAULT + LEANBACK_LAUNCHER); `MainActivity.java:13` | Present | `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME` → Glass listed; press HOME button on remote; Settings → Apps → Default apps → Home app |
| Exit to previous home via Back | `PHASE1.md:10` ("Back exits"), `README.md:21-22` (DU TV / Projectivy fallbacks) | `home.js:135,146` → `G.exitApp` `glass.js:128` → `GlassBridge.goHome` `GlassBridge.java:25-27` | Present (target depends on default HOME; not determinable from source) | Press Back on the home screen; see which launcher appears |
| Landscape-only | `README.md:3` (implicit) | `AndroidManifest.xml:40` `screenOrientation="landscape"`; `configChanges=...orientation|screenSize` | Present | Rotate/force portrait via `adb shell settings put system user_rotation 1` – UI must stay landscape |
| Immersive full screen | not documented | `MainActivity.java:21` (`setSystemUiVisibility`, deprecated API); `styles.xml:1` | Present | No status/nav bars visible; swipe/edge behaviour on box |
| Viewport / density independence (Phase 7) | `PHASE7_DPI.md:22-49` | `index.html:4` (`width=1920`, no `initial-scale`); `MainActivity.java:41-50`; `css/glass.css:2` | Present | Set `adb shell wm density 320` (and 160): layout must be identical, no clipping |
| WebView accessibility tree off (yellow focus-ring fix) | `PHASE5.md:7-27` | `MainActivity.java:31,36-38` | Present | Navigate Home/Search/CC for several minutes; no yellow ring, no scroll fighting |
| Back-key plumbing (Back → JS keyCode 10009) | `PHASE6.md:44-47` | `MainActivity.java:15,64-79` | Present | Press remote Back inside Search/CC (closes), on Home (exits) |
| Mouse right-click as Back | `PHASE6.md:65` (explicitly untested), `PHASE6_NOTES.md:13-18` | Depends on Android mapping right-click → `KEYCODE_BACK` *(external knowledge)*; handled at `MainActivity.java:73` | Partial (not determinable from source) | Attach USB/BT mouse, right-click in Search/CC |
| Package visibility `<queries>` | `PHASE3.md:46`, `PHASE6.md:69` | `AndroidManifest.xml:8-38` | Present | After install, Search lists installed apps (empty list ⇒ visibility problem) |
| Build setup (AGP 8.7.3, Gradle 9.6.0, SDK 35/23/30, v1.5 / code 6, Java only) | `README.md:5-9`; versions in `PHASE2.md:54`, `PHASE3.md:47`, `PHASE6.md:69` | `app/build.gradle:1-13`; `build.gradle:1`; `gradle/wrapper/gradle-wrapper.properties:3` | Present | `./gradlew assembleDebug` (not run in this audit) |

## 2. Home screen

| Feature | Documented where | Implemented where | Status | How to test on device |
|---|---|---|---|---|
| **Primary app row** — exactly 5 configured apps, in this order: **1 YouTube** `com.google.android.youtube.tv`; **2 Netflix** `com.netflix.ninja`; **3 Prime Video** `com.amazon.amazonvideo.livingroom`; **4 Disney+** `com.disney.disneyplus`; **5 Spotify** `com.spotify.tv.android` (art `icons/*.png`, dark `darkicons/*.png`; Spotify's light and dark files are byte-identical) | `README.md:23`; `PHASE6.md:10-11` | `apps.js:2-8`; row built `home.js:67`; cards `home.js:26-38` | Present | Home shows 5 cards; OK launches each; uninstalled ones toast "… is not installed on this box." (`glass.js:134`) |
| **Entertainment row** — 4 apps, **in code order: Stremio, Rhenium, Moonlight, RetroArch** (`apps.js:57-60`). Candidate ids: Stremio `com.stremio.one`; Rhenium none (name match `/rhenium/i`); Moonlight `com.limelight`, `com.limelight.noroot`, `com.limelight.root`; RetroArch `com.retroarch`, `com.retroarch.aarch64`, `com.retroarch.ra32`; art `icons/{stremio,rhenium,moonlight,retroarch}.png` + `darkicons/…` | `PHASE6.md:10-16,24-30`; `PHASE6_NOTES.md:11` | `apps.js:54-62`; row `home.js:69-70` (class `center` `home.css:17`) | Present — **order differs from docs**: `PHASE6.md:10-11` says Stremio, Moonlight, RetroArch, Rhenium; `PHASE6_NOTES.md:11` says Stremio, RetroArch, Moonlight, Rhenium | Home row 2 shows 4 centred cards; each resolves to the real package or toasts |
| Entertainment row heading "Entertainment" | `PHASE6.md:15` | `ENTERTAINMENT.title` (`apps.js:55`) is never read; `makeRow('','ent')` `home.js:70` passes empty label | Documented-only (no heading is rendered) | Look for a heading above row 2 — none expected |
| Utilities row (5 placeholder slots, `window.UTILITIES`, `Glass.utilities` API) | `PHASE3.md:22-25` | Removed in Phase 6 (`PHASE6.md:10-12,63`). Leftover placeholder-card code: `home.js:30-31,180`, `home.css:33-36` | Documented-only (dead leftovers) | Home must show 2 rows only |
| "All Apps" row (live installed list excluding pinned) | `PHASE1.md:5-8` | None. `home.js:71` comment: "installed apps … are not listed here; Search finds … every one" | **Documented-only** (removed in Phase 6, `PHASE6.md:10-12`) | Scroll down: only rows 1–2 exist |
| "Pinned"/"All Apps" row headings (`LAYOUT.showLabels`) | `PHASE2.md:18` | Flag `apps.js:16`, `home.js:9`, CSS `home.css:13-15`; but labels are created with `''` so `home.js:41` forces `display:none` | Partial (setting `showLabels:true` has no visible effect on current rows) | Set flag true & rebuild: no headings expected |
| Exactly 5 columns, fixed card size 14rem×8.4rem, 1.6rem gap | `PHASE2.md:8-10` | `apps.js:15`, `glass.js:12`; `home.css:16,20` | Present | Check even spacing at 1080p |
| Clock shrink + row scroll when moving down | `PHASE2.md:13-17`, `PHASE3.md:18` | `home.js:78-106`; `home.css:3-11`; stage mask `home.js:88-90` | Present | D-pad Down from row 1: clock shrinks, rows slide up; Up restores |
| Card focus (only focused card scales 1.06, halo) | `PHASE3.md:14-16` | `home.css:25-31` | Present | Move across row; neighbours do not grow |
| Card sheen on focus | not documented | `home.css:24,31` | Present | Focus a card: diagonal light sweep |
| Card art fringe fix (transparent bg once loaded) | `PHASE5.md:29-46` | `home.css:21-22`; `home.js:16` (`.icon` class) | Present | Inspect Prime/Netflix edges for dark ring |
| Theme-aware card art + preload of the other theme | `PHASE6.md:16-17` | `home.js:21,179-181` | Present | Toggle theme in CC: card art swaps without flash |
| Placeholder / initials fallback cards | `PHASE5.md:41-45` | `home.js:17,24,30-31` | Present | Remove a PNG → initials tile on `#222` |
| Recently Used panel (last really-launched app, theme-aware, persisted, empty text) | `PHASE6.md:18-20` | `recent.js:1-42`; `index.html:17`; `css/recent.css`; recorded by `glass.js:135` | Present | Launch an app, return Home → panel shows it; reboot → persists; fresh install → "Apps you open will appear here" |
| Recently Used focus stop (Left of Search), Tab, mouse | `PHASE6.md:20` | `home.js:139-145,160-164` | Present | Up from row 1, Left to Recently Used, OK relaunches |
| Header buttons: Search, Control Centre (SVG, or `ui/*.png`) | `PHASE3.md:10`, `PHASE4.md:17` | `index.html:19-22`; `home.js:160-164,176`; `css/glass.css:47-56` | Present | Up from row 1 → header; OK |
| Moon/sun header toggle | `README.md:3`, `PHASE1.md:9` | Removed in Phase 3 (`PHASE3.md:10-11`); `THEME.iconLight/iconDark` `apps.js:9` unused by JS | Documented-only (toggle lives in Control Centre / Search "Appearance") | Header holds only Search + CC |
| Clock (12-hour, no AM/PM) and date (en-GB long) | `README.md:3` | `main.js:32-37`; `index.html:14`; `home.css:4-5`; 10 s interval `main.js:40` | Present | Compare with system time; wait for minute roll-over |
| Wallpapers (light/dark crossfade) | `README.md:3` | `theme.js:7-12`; `glass.css:16-20`; `apps.js:9` | Present | Toggle theme; dark wallpaper fades in over 0.7 s |

## 3. Discovery, search

| Feature | Documented where | Implemented where | Status | How to test on device |
|---|---|---|---|---|
| App discovery (launchable packages incl. Leanback) | `PHASE1.md:5-8`, `PHASE2.md` | `GlassBridge.java:24` → `AppDiscovery.java:31-45`; JS `appdata.js:8-13` | Present (one-shot at boot; never refreshed) | Install an app while Glass runs → not in Search until restart (expected from code) |
| Id resolution (current id → `ids[]` → name `match`; never invents) | `PHASE6.md:24-30` | `appdata.js:15-22` | Present | Search shows resolved app; card with missing app toasts |
| `HIDDEN` filter (`org.tizen`, `com.samsung`, `DU.TV.PRODA`) | `PHASE1.md:5-8` | `appdata.js:7`; used `registry.js:61`, `appdata.js:19` | Present (applies to Search and name-matching only; the ID list uses a narrower regex `main.js:11`) | Search "du" – DU TV must not appear |
| Search overlay (own UI, header button, CC tile) | `PHASE3.md:26-33`, `PHASE4.md:26-35` | `search.js:79-90`; `index.html:27-36`; `css/search.css` | Present | Header Search → OK |
| Ranking (exact > prefix > word-start > contains > fuzzy≥4 chars) | `PHASE3.md:26-27` | `registry.js:7-25,46-56` (norm/fieldScore/score/sort) | Present | Type "net", "wi", "stor" and inspect order |
| Suggestions on empty query (Settings, Wi-Fi, Bluetooth, Volume, Play Store, Appearance) | `PHASE4.md:32` | `search.js:8,45-46` | Present | Open Search; OK on a suggestion fills the field |
| Settings shortcuts (Wi-Fi, Bluetooth, Display, Sound, Apps, Storage, Date & time, Keyboard, Accessibility, About this box, Settings) hidden if missing | `PHASE3.md:27-29` | `shortcuts.js:6-23`; `settings` bridge `SettingsLauncher.java:13-33`; `registry.js:33-36` | Present | Search each; unavailable pages should not be listed |
| "Settings" shortcut hidden when `com.android.tv.settings`/`com.android.settings` is an installed app | not documented | `shortcuts.js:17-18`, `registry.js:41` | Present | Search "settings": real Settings app appears as App result |
| Google Play Store result (aliases: play store, google play, store, app store, download apps) | `PHASE3.md:29` | `shortcuts.js:33-35` (installed app `com.android.vending`) | Present (only if Play Store is installed and launchable) | Search "play store" |
| Glass controls in Search (Control Centre, Volume, Appearance) | `PHASE3.md:29-30` | `shortcuts.js:26-31` | Present | Search "volume": opens CC focused on Volume |
| `SEARCH_ALIASES` per-app words | `PHASE3.md:30` | `apps.js:74-76` (empty), `glass.js:19`, `registry.js:63` | Present (no custom aliases configured) | Add an alias, rebuild |
| Package id shown under app results | `PHASE3.md:27`, `PHASE6.md:30` | Not rendered for apps: `search.js:58` renders subtitle only for shortcuts | **Partial / Documented-only** | Search an app: only name + "App" tag |
| Search debounce (110 ms) with flush before results | `PHASE5.md:48-63` | `search.js:12-19` | Present | Type quickly; list updates after pause; Enter shows current results |
| Max 24 results; 60-char query cap | not documented | `search.js:7,16` | Present | Type a very common letter |
| "No match" message / "Nothing matches that" toast | not documented | `search.js:51,69` | Present | Type "zzzz" |
| Search stays open behind a launched app | `PHASE6.md:64` | `registry.js:64` (no close), `search.js:68` | Present (documented limitation) | Launch from Search, return: Search still open |

## 4. Input

| Feature | Documented where | Implemented where | Status | How to test on device |
|---|---|---|---|---|
| On-screen keyboard (custom, D-pad; abc/123, shift one-shot, space, clear, backspace, search) | `PHASE4.md:26-35` | `keyboard.js:1-100`; host `index.html:32`; created `search.js:71` | Present | Search: arrows move keys, OK types, "search" key jumps to results |
| Android system IME for Search | `PHASE3.md:44-45` (Phase 3 design) | Bridge `showKeyboard/hideKeyboard` `GlassBridge.java:46-62` exist but no JS calls them | Documented-only (superseded in Phase 4, `PHASE4.md:28-29`); dead bridge methods | Confirm no Android keyboard pops up |
| Hardware keyboard typing (printable keys, Enter-after-typing = submit) | `PHASE4.md:33` | `search.js:116-118` | Present | Attach USB keyboard; type in Search |
| Backspace (= Back outside Search; deletes inside Search) | `PHASE6.md:44-46` | `main.js:23`; `search.js:117` | Present | Backspace on Home/CC/Search |
| Escape = Back | `PHASE6.md:33-37` | `main.js:23` | Present | Escape closes CC/Search |
| D-pad arrows / OK | `PHASE1.md:9-10` | `home.js:128-148`; `controlcentre.js:124-134`; `search.js:113-133` | Present | Navigate every zone |
| Back (remote) | `PHASE1.md`, `PHASE6.md:47` | `MainActivity.java:64-79`; `main.js:21-29` | Present | Back from each overlay/zone |
| Tab / Shift+Tab walk | `PHASE6.md:45-46` | `main.js:26`; `home.js:149-159`; `controlcentre.js:124`; `search.js:93-104` | Present | Hardware keyboard Tab |
| Long-press actions | not documented | None; only `e.repeat` OK suppression `main.js:27` | Not found | n/a |
| Hold OK does not repeat launch/toggle | `PHASE6.md:42` | `main.js:27` (not applied in Search) | Present | Hold OK on a card: one launch |
| Arrow-key repeat | not documented | Not throttled (`main.js:27` filters only OK) | Present (unthrottled) | Hold Right across a row: sound + render per repeat |
| Mouse hover-to-focus with ≥4 px real-move guard | `PHASE6.md:38-39` | `glass.js:139-141`; `home.js:49-54,160`; `controlcentre.js:34`; `keyboard.js:44`; `search.js:63` | Present | Move mouse; park it and use D-pad: no focus steal |
| Mouse click | `PHASE6.md:46` | `home.js:55,162-164`; `controlcentre.js:35,151`; `keyboard.js:43`; `search.js:64,107` | Present | Click cards, tiles, keys, rows, scrim |
| Mouse wheel (rows; Search results) | `PHASE6.md:46` | `home.js:165-173` (throttle 350 ms); `search.js:108-111` | Present | Scroll wheel on Home and over results |
| Overlay trap fixes (click outside closes, hover can't act under overlay) | `PHASE6.md:33-37` | `controlcentre.js:151`; `search.js:107`; `home.js:7`; `home.css:38-39` | Present | Hover cards while CC is open: no effect |

## 5. Control Centre

| Feature | Documented where | Implemented where | Status | How to test on device |
|---|---|---|---|---|
| Right-side glass panel, 40rem wide, header clock+date | `PHASE3.md:11`, `PHASE4.md:19-22` | `index.html:24-25`; `controlcentre.css:4-14`; `controlcentre.js:42-45` | Present (docs say 36rem) | Open and compare |
| Tile: **Appearance** (light/dark toggle) | `PHASE4.md:20` | `controlcentre.js:11,77-84,116` | Present | OK toggles; tile text "Light"/"Dark" |
| Tile: **Wi-Fi** (Ethernet / SSID / not connected / off; OK → Wi-Fi settings) | `PHASE2.md:22`, `PHASE4.md:20-22` | `controlcentre.js:12,46-53,114`; `SystemControls.java:82-107` | Present | Wi-Fi and Ethernet cases; SSID needs location grant |
| Tile: **Bluetooth** (on/off, connected/paired names; no toggle by design) | `PHASE3.md:42` | `controlcentre.js:13,54-63,115`; `SystemControls.java:110-136` | Present | Pair a device and open the tile |
| Tile: **Volume** (bar, %, mute, fixed-volume note, "Output:" label) | `PHASE4.md:21-23` | `controlcentre.js:14,64-76,107-111,117`; `SystemControls.java:35-80` | Present | Left/Right changes real volume; OK mutes; fixed-volume box shows note |
| Tile: **This box** (model, Android, IP, free GB; OK → device info) | `PHASE2.md:28` | `controlcentre.js:15,85-89,118`; `SystemControls.java:138-157` | Present | Compare with `adb shell getprop` / Settings |
| Tiles: **Settings / Sound / Display / Search** (compact; Sound/Display dimmed if unavailable) | `PHASE4.md:21` | `controlcentre.js:16-19,90-93,119-122` | Present | OK on each |
| Spatial D-pad navigation between tiles | `PHASE4.md:21` | `glass.js:85-100`; `controlcentre.js:124-134` | Present | Arrows between uneven tiles |
| Live refresh while open (2 s Wi-Fi/BT/volume, 10 s clock) | not documented explicitly | `controlcentre.js:141-142,147` | Present | Change Wi-Fi from elsewhere while open |
| Location permission request (once) for SSID | `PHASE2.md:38-39` | `controlcentre.js:144`; `SystemControls.java:159-165`; manifest `:5` | Present (result not handled in Java) | First CC open ever: permission dialog |
| Brightness tile | `PHASE2.md:27,35-37` | Removed Phase 3 (`PHASE3.md:40`) | Documented-only | Not present |
| Wi-Fi / Bluetooth on-off toggles | `PHASE2.md:32-33`, `PHASE3.md:42` | Intentionally absent | Not found (by design) | n/a |
| Sleep timer / night mode / screen saver | `PHASE2.md:40`, `PHASE3.md:41` | Not built | Not found | n/a |
| Tile stagger-in animation | `PHASE4.md:21` | Removed (`controlcentre.css:59-60`) | Documented-only | n/a |
| Resume refresh after returning from system Settings | `MainActivity.java:60` comment | `MainActivity.java:58-62`; `controlcentre.js:155` | Present | Open Wi-Fi from CC, return: tile refreshed |
| Volume-key sync | `PHASE2.md:65` | `MainActivity.java:64-72`; `controlcentre.js:154` | Present | Press remote volume with CC open |

## 6. Appearance, sound, performance

| Feature | Documented where | Implemented where | Status | How to test on device |
|---|---|---|---|---|
| Light/dark themes | `README.md:3` | `theme.js`; `glass.css:14`, `body.dark` | Present | Toggle |
| Automatic schedule 19:00–07:00; manual toggle holds until next boundary; not persisted | `PHASE1.md:11` ("auto dark mode (7pm–7am)") | `theme.js:5,20`; ticks `main.js:35,40` | Present | Set clock past 19:00/07:00 |
| Navigate/click sounds (3-element pools) | `README.md:3` | `glass.js:51-62`; nav `home.js:123`; click `glass.js:130` etc. | Present | Move/select with sound on |
| Keyboard key sounds (alpha/space/enter/alt, random variant) | `PHASE4.md` "Sound pass" | `glass.js:64-82`; `keyboard.js:85-91`; `search.js:116-118`; files `assets/sounds/keys/*` (18 MP3) | Present | Type in Search |
| Sound levels | `PHASE4.md:60,71` (0.35 / 0.5) | `apps.js:10` (`volume:.2`, `keyVolume:.3`) | Present (**docs out of date**) | Listen |
| Double-sound fix on keyboard | `PHASE6_NOTES.md:7-10` | `search.js:72-75` (no extra click calls) | Present | One sound per key |
| Volume / mute (media stream) | `PHASE2.md:26` | `SystemControls.java:35-56` | Present | CC Volume tile |
| Lite mode (`PERF.lite`) | `README.md:3`, `PHASE4.md:41` | `apps.js:11`; `main.js:39`; CSS `glass.css:30,54-55`, `recent.css:16`, `controlcentre.css:61` | Present (default on) | Set `lite:false`; compare focus halo / header-button blur |
| Auto blur fallback (`PERF.blur` true/false/'auto', stored `glass.noblur`) | `PHASE4.md:39-40` | `glass.js:102-124`; callers `controlcentre.js:143`, `search.js:83` | Present; Partial for `#cc` (own ID-level `backdrop-filter` out-ranks `body.noblur .glass`, `controlcentre.css:9` vs `glass.css:28`) | Open CC/Search repeatedly on a slow box; check `localStorage glass.noblur` |
| Toasts (3.2 s) | `PHASE1.md` (not-installed toast) | `glass.js:47-49`; `glass.css:58-60` | Present | Launch uninstalled card |
| Icon overrides (`ICON_OVERRIDES`, `icons/overrides/`) | `PHASE3.md:31-32` | `apps.js:68-70` (empty, example commented); `icons.js:42`; used `recent.js:15`, `search.js:36`, `home.js:13,22` (only for cards without `icon`/`dark`) | Partial (no overrides configured; on home rows it can only affect art-less cards; `icons/overrides/.keep` only) | Add Play Store override per `apps.js:69` |
| `UI_ICONS` PNG slots | `PHASE2.md:42-46`, `assets/ui/README.txt` | `apps.js:30-45`; `icons.js:33-40` | Partial (`assets/ui/` holds only `README.txt` → all slots fall back to SVG) | Drop a `ui/wifi.png`, reopen CC |
| Installed-apps + IDs list overlay | `PHASE1.md`/`PHASE2.md` ("kept from Phase 2"), `PHASE6.md:30` | `main.js:8-17,24`; trigger `home.js:59` (`action:'list'`) | Partial (no configured entry has `action:'list'`; unreachable from UI) | Not reachable unless a card is given `action:'list'` |
| `action:'exit'` card | not documented | `home.js:60` | Partial (unused by config) | n/a |
| `LAYOUT` tuning values | `PHASE2.md:12-18` | `apps.js:14-23`; `glass.js:11-12` | Present | Edit and rebuild |
| Search/CC overlays hidden when closed (visibility) | `PHASE6.md:51-52` | `controlcentre.css:5,10-11`; `search.css:2-3` | Present | n/a |

## 7. Persistence, scripts, third-party

| Feature | Documented where | Implemented where | Status | How to test on device |
|---|---|---|---|---|
| `localStorage` keys (prefix `glass.`): `recent`, `locasked`, `noblur`, `slowopen` | `PHASE6.md:19` (recent), `PHASE2.md:38` (ask once), `PHASE4.md:39-40` (noblur) | `glass.js:41-45`; `recent.js:5,34`; `controlcentre.js:144`; `glass.js:109,119-121` | Present | Chrome inspect (`chrome://inspect`) or clear app data to reset |
| Glass Utility Hub integration | `PHASE1.md:19`, `PHASE2.md:59`, `PHASE3.md:47`, `PHASE6.md:71`, `PHASE7_DPI.md:60` ("separate project") | Only `AndroidManifest.xml:37` `<queries>` package entry; no code uses it | Partial / **no dependency** | Glass runs identically without the Hub installed |
| LocalSend tile | `PHASE5.md:89-107` (recommendation only, no ID verified) | None | Documented-only (not implemented) | n/a |
| DU TV and Projectivy fallback left installed | `README.md:21-22` | `appdata.js:7` hides DU TV id from Search; `safe-debloat.bat:6` | Present | Confirm both still installed |
| `install-to-box.bat` | `README.md:11-18` | Root `install-to-box.bat:1-7` (`adb install -r`, start HOME) | Present | Run with an APK path |
| `safe-debloat.bat` (disables `com.google.android.play.games`, `com.google.android.feedback` for user 0) | not in a phase doc | Root `safe-debloat.bat:1-8` | Present | `adb shell pm list packages -d` |
| `restore-debloat.bat` (re-enables the same two) | not in a phase doc | Root `restore-debloat.bat:1-6` | Present | Packages absent from `pm list packages -d` |
| Rollback snapshots `assets_baseline/phase1/phase2/phase3` | `PHASE1.md:12-13`, `PHASE2.md:55`, `PHASE3.md:19`, `PHASE4.md:48`, `PHASE6.md:63` | `app/src/main/assets_*` (not packaged, not referenced by code) | Present (non-runtime) | `./gradlew assembleDebug` then list APK `assets/`: no snapshot folders |
