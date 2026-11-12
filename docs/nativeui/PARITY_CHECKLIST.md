# Glass Launcher — native UI parity checklist

Static audit of the WebView UI on branch `glasslauncher-2.0-migration` (source read, **nothing run on a device**). Purpose: freeze current behaviour so a native (custom Views, Java) port can be tested screen by screen. This is not a redesign; where the source is unclear it says so.

**Citation paths** are relative to `app/src/main/assets/` (`css/…`, `js/…`, `apps.js`, `index.html`) unless prefixed `java/` (= `app/src/main/java/com/glasslauncher/`) or `AndroidManifest.xml`.

## 0. Units and the 1920×1080 @ 320 dpi mapping

| Fact | Value | Source |
|---|---|---|
| CSS viewport | `width=1920` (no `initial-scale`) | `index.html:4`; `PHASE7_DPI.md` |
| WebView mode | wide viewport + overview-zoom: 1920 CSS px are zoomed to fill the physical screen | `java/MainActivity.java:46-55` |
| `rem` | `html{font-size:1.0417vw}` = **20 CSS px** at width 1920 | `css/glass.css:2`; `apps.js:13` |
| CSS px → device | At 1920×1080 physical, **1 CSS px = 1 physical px**. At density 320, 1 dp = 2 px, so **1 rem = 20 px = 10 dp**, 1 CSS px = 0.5 dp | derived from the rows above (not measured on device) |
| Layout height | `window.innerHeight` (1080 CSS px) drives scroll maths; `vh` is used once (`#date` margin-top 5vh = 54 px) | `js/home.js:86`; `css/home.css:4` |
| Fonts | `"SamsungOne","Helvetica Neue",Arial,sans-serif`; the app theme sets `fontFamily=sans` (Roboto on the box). SamsungOne is not bundled, so expect Roboto metrics | `css/glass.css:15`; `res/values/styles.xml` |

All px values below are **at 1920×1080 (rem × 20)**. Anything marked *≈* depends on font line-height and must be measured on the device.

## 1. Surfaces inventory

| # | Surface | Entry | Exit | Notes |
|---|---|---|---|---|
| S1 | Home (clock/date, header buttons, Recently Used panel, 2 dock rows) | boot | n/a (launcher) | `index.html:13-22` |
| S2 | Recently Used panel (top-left) | always on Home | hidden while rows are scrolled | `css/recent.css` |
| S3 | Header buttons: Search, Control Centre | Up from row 0 | n/a | `css/glass.css:48-56` |
| S4 | Control Centre overlay (right panel) | header CC button, Search "Control Centre"/"Volume" shortcuts | Back, scrim click, CC "Search" tile | `js/controlcentre.js` |
| S5 | Search overlay (field, results, on-screen keyboard, hint bar) | header Search button, CC "Search" tile | Back, backdrop click | `js/search.js`, `js/keyboard.js` |
| S6 | Toast (centre of screen) | any error / theme shortcut | auto 3.2 s | `js/glass.js:49` |
| S7 | Installed-apps/ID list overlay | **unreachable**: needs an app with `action:'list'`; none is configured | Back/Enter | `js/main.js:6-17`, `js/home.js:59` |
| S8 | System permission dialog (Android `ACCESS_FINE_LOCATION`) | first ever Control Centre open | system | `js/controlcentre.js:144`; `java/SystemControls.java:159` |
| S9 | Wallpapers + shade, light/dark theme | always | n/a | `css/glass.css:16-20`, `js/theme.js` |
| S10 | Empty/error states | see §3 | | |

Not present: any settings screen inside Glass, any "All Apps" grid, Utilities row, brightness/Wi-Fi/Bluetooth toggles (documented-only per `docs/architecture/FEATURE_INVENTORY.md`). There is no `wallpapers/` asset folder: wallpapers are `wallpaper.jpg` and `wallpaper2.jpg` at the assets root (both JPEG 1920×1080, `apps.js:9`). `assets/ui/` holds only `README.txt` (no PNGs), `icons/overrides/` is empty, `assets/icon.png` exists (not referenced by UI code).

## 2. Per-surface specification

### 2.1 Glass material and shared tokens (`css/glass.css`)

| Token | Value | Line |
|---|---|---|
| Tint (light / dark) | `rgba(24,27,36,.30)` / `rgba(10,12,18,.44)` | 5, 14 |
| Sheen | `linear-gradient(135deg, #fff@.26 0%, @.09 36%, @.04 62%, @.15 100%)` layered over tint | 6, 23 |
| Edge / top highlight | border 1 px `rgba(255,255,255,.20)`; inset 1 px top `rgba(255,255,255,.46)` | 7-8, 23-24 |
| Glass shadow | inset bottom `.05`, inset glow 36 px `.035`, drop `0 36 88 rgba(0,0,0,.26)` + `0 4 12 rgba(0,0,0,.16)` | 24 |
| Backdrop blur | `blur(30px) saturate(1.55)` | 25 |
| No-blur fallback | sheen + `rgba(38,42,54,.80)`; dark `rgba(18,20,28,.86)` | 28-29 |
| Ink (text on focused bright surface) | `#14161c` | 9 |
| Secondary text | `rgba(255,255,255,.68)` | 10 |
| Easing `--ease` | `cubic-bezier(.2,.8,.2,1)` | 11 |
| Radii | panel 52 px, tile (CC/recent) 36 px, key 27 px | 12 |
| Focus-bright surface (`.gt.f`, `.hbtn.f`, `.sr.f`, `#recent.f`, `.k.f`) | `linear-gradient(180deg, rgba(255,255,255,.97), rgba(238,241,248,.90))`, text `#14161c`, border `#fff` | 36, 52; `css/search.css:29,55`; `css/recent.css:14` |
| Body/WebView background | `#0b0b14` | `css/glass.css:15`; `java/MainActivity.java:28` |

**Lite mode is ON** (`PERF={lite:true}`, `apps.js:11`; class added at `js/main.js:39`). Lite only removes backdrop blur from: the tile focus halo (`.slot::before` → flat `rgba(255,255,255,.3)`, `css/glass.css:30`), header buttons (→ `sheen + rgba(30,34,46,.5)`, :54-55), unfocused Recently Used (→ same, `css/recent.css:16`), and slightly raises CC opacity (`css/controlcentre.css:61`). **Dock rows, the CC panel and the Search panel keep their 30 px backdrop blur** in lite mode. A separate auto fallback (`body.noblur`) swaps `.glass` elements to dense colour after two slow overlay openings and persists it (§2.9); `#cc` and `#search` panels have their own rules and are not affected by `noblur` (ID-selector rules win, `css/controlcentre.css:4-9`).

### 2.2 Wallpaper and theme (S9)

| Rule | Detail | Source |
|---|---|---|
| Light wallpaper | `wallpaper.jpg`, `center/cover`, always rendered in `#wp` | `js/theme.js:8`; `css/glass.css:16` |
| Dark wallpaper | `wallpaper2.jpg` in `#wpd` over it; `opacity 0→1` over **700 ms `ease`** when `body.dark` | `css/glass.css:17-18` |
| Dark wallpaper load | preloaded; if load fails, light wallpaper is used with `filter:brightness(.4) saturate(.8)` | `js/theme.js:9-12`; `css/glass.css:19` |
| Shade over wallpaper | `linear-gradient(180deg, rgba(0,0,0,.12) 0, rgba(0,0,0,0) 35%, rgba(0,0,0,.5) 100%)` | `css/glass.css:20` |
| Automatic schedule | dark when `hour>=19 || hour<7` (19:00–06:59, device local time). Initial state computed at boot | `js/theme.js:5-6` |
| Re-evaluation | every 10 s (`tick`), only acts when the night/day flag **flips**; a manual toggle therefore holds until the next 19:00 or 07:00 boundary | `js/main.js:32-40`; `js/theme.js:19-20` |
| Manual toggle | CC "Appearance" tile OK, or Search shortcut "Appearance". **Not persisted** (resets on process restart to the time-based value) | `js/theme.js:17`; `js/controlcentre.js:116`; `js/shortcuts.js:30-31` |
| What changes in dark | wallpaper crossfade; glass tint (`--tint`); home tile artwork (light `icon` ↔ `dark` file, swapped instantly with no crossfade, other theme's art pre-requested); `noblur` fallback colour; CC theme tile state. Text colours are white in both themes | `css/glass.css:14`; `js/home.js:21,179-181`; `js/recent.js:40` |

### 2.3 Home (S1)

**Layering/z-order:** wallpaper `#wp`,`#wpd`, `#shade` → `#head` (clock) → `#stage` (rows, masked) → `#recent`,`#hdrBtns` (z 10) → `#ccScrim` (14) → `#cc` (15) → `#search` (18) → `#ids` (30) → `#toast` (40). (`css/*.css`)

**Clock / date (`#head`, `css/home.css:3-7`)**

| Item | Spec |
|---|---|
| Position | horizontally centred, full width, top 0; `#date` margin-top **54 px** (5vh) |
| Date | 30 px, weight 600, letter-spacing .02em, opacity .9, shadow `0 2 12 rgba(0,0,0,.35)`; text `toLocaleDateString('en-GB',{weekday:'long',day:'numeric',month:'long'})` (e.g. "Thursday 12 November"; exact punctuation depends on the WebView ICU — unverified) `js/main.js:36` |
| Clock | **240 px**, weight 700, line-height 1.1 (box 264 px), letter-spacing −.02em; 12-hour `h:mm` **no leading zero, no AM/PM** (`js/main.js:33-34`); text fill is a vertical gradient `rgba(255,255,255,.96)` at 10% → `rgba(255,255,255,.18)` at 95% |
| Update | every 10 s, so the minute can lag by up to 10 s (`js/main.js:40`); JS timers pause when the activity is paused (`java/MainActivity.java:72`) |
| Collapsed state | when focus is on row index ≥1 (also while CC/Search is open with row ≥1): `translateY(−dy) scale(.6)` about origin (50%,0); `dy = clockOffsetTop*.6 + 32` ≈ 85 px; opacity .55; transition **450 ms var(--ease)** on transform and opacity (`js/home.js:94-106`; `css/home.css:3,6`; `apps.js:19-21`) |
| Under overlays | opacity .3 (`css/home.css:7`) |

**Header controls (`css/glass.css:48-56`, `css/recent.css`)**

| Item | Spec |
|---|---|
| `#hdrBtns` | fixed, top 60 px, right 72 px, gap 20 px, z 10; fades to opacity 0 (300 ms) when CC or Search open; pointer-events off under overlays |
| Button | 68×68 px, radius 34 px (circle), `.glass` look, icon box 32×32 with drop-shadow `0 1 4 rgba(0,0,0,.35)`; Search left (x 1692–1760), Control Centre right (x 1780–1848) |
| Icon | built-in SVG (`search`, `controls`, `js/icons.js:6-7`); `ui/search.png`/`ui/controls.png` are configured but **do not exist** → SVG fallback (`apps.js:32-33`, `js/icons.js:33-40`). SVG: 24×24 viewBox, stroke 1.8, round caps/joins, `currentColor`, `.fill` shapes filled (`css/glass.css:44-45`). All SVG path data: `js/icons.js:6-27` |
| Focus | scale **1.1**, bright surface, ink icon colour, shadow `inset 0 1 0 #fff, 0 14 32 rgba(0,0,0,.3)`; transition 200 ms transform / 180 ms colours, `var(--ease)` |
| Recently Used `#recent` | fixed left 72, top 60, width **328 px**, padding 18/22/20 px, radius 36 px, `.glass`; caption "Recently used" 15.6 px bold uppercase, letter-spacing .12em, `rgba(255,255,255,.68)`, margin-bottom 13 px; body = thumb **122×73.2 px** radius 16 px, shadow `0 6 14 rgba(0,0,0,.28)`, bg `rgba(255,255,255,.12)` (transparent once art loaded) + name 21.6 px weight 650, max 2 lines, gap 17 px; empty text "Apps you open will appear here" 19.6 px `rgba(255,255,255,.68)` |
| Recent focus | scale **1.04**, bright surface, caption/empty text `rgba(20,22,28,.6)`; transition 450 ms opacity/transform (`var(--ease)`), 200 ms colours |
| Recent hide | when rows scrolled: opacity 0, `translateY(−28px)`, pointer-events none (450 ms); under overlays opacity .3 (`css/recent.css:18-19`) |
| Recent artwork | configured card's art for the package (theme-aware, `cover`; `.sq` = height 74% `contain` if aspect < 1.3) → else `ICON_OVERRIDES` → else the app's real icon (128×128 PNG, `.sq`) → else initials (`js/recent.js:13-27`, `css/recent.css:7-10`) |

**Rows (`css/home.css:9-31`, `js/home.js:63-74`)**

| Item | Spec |
|---|---|
| Structure | `#rowsWrap` flex column, centred, row-gap **48 px** (2.4 rem). Row 0 = 5 pinned apps; row 1 = "Entertainment", 4 apps. Pinned apps are chunked by 5 (`LAYOUT.columns` forced to 5, `js/glass.js:12`), so a 6th app would create a new row. Row labels are never shown (`showLabels:false` and labels are empty strings, `js/home.js:41`); `ENTERTAINMENT.title` is unused |
| Dock | `.dock.glass`: padding **32 px 72 px**, gap **32 px**, radius **48 px**, 1 px border, `min-width = 5·280+4·32+2·72+2 = 1674 px`, height = 234 px (168 + 64 + 2). Full panels are **1674×234 centred horizontally (x 123–1797)** |
| Tile | **280×168 px** (14×8.4 rem), radius **22 px**, `overflow:hidden`, bg `#222` (transparent once art loads), text fallback 32 px weight 800 initials |
| Tile x (row 0) | 196 + 312·i, i=0..4 (pitch 312 px) |
| Tile x (row 1) | 4 tiles centred in the same 1674 px dock: 352 + 312·i, i=0..3 (row 1 is offset +156 px vs row 0) |
| Tile y | row 0 dock top = `expTop` = `headH + 108 px`, where `headH` = date line box + 54 + 264 ≈ **353 px** ⇒ *≈ 461*; tile top = dock top + 33 ≈ **494**. Row 1 dock top = row 0 dock top + 234 + 48 ≈ 743; tile top ≈ **776**. Rows end ≈ 977, under `viewBottom` = 1080 − 64 = 1016 (`js/home.js:79-86`). *Measure `headH` on device; it varies with font line-height.* |
| Scroll (focus on row 1) | rows wrapper `translateY(viewTop − (expTop + rowTop(topRow)))`; `viewTop = 264·.6 − 32 + 32 ≈ 158` ⇒ **≈ −303 px**; both rows still fit (516 px < 858 px available), so `topRow` stays 0 (`js/home.js:94-106`). Transition 450 ms `var(--ease)`. Returning to row 0 removes the transform |
| Stage mask | alpha gradient on `#stage`: transparent up to `viewTop−24` (≈134), opaque from `viewTop` (≈158) to `viewBottom−24` (992), transparent at `viewBottom+56` (1072) (`js/home.js:88-90`). Does not affect `#head`, `#recent`, header buttons |
| Cards never move | focus does not change layout; focus only scales/halos the focused card |

**Tile focus visual (`css/home.css:25-31`, `css/glass.css:30`)**

| Layer | Unfocused → Focused | Timing |
|---|---|---|
| `.slot` transform | scale 1 → **1.06** about centre, `z-index 3` | **220 ms** `cubic-bezier(.2,.8,.2,1)` |
| Halo `.slot::before` | inset **−7 px** on all sides (⇒ 294×182 px before scale), radius **29 px**, border 2.4 px `rgba(255,255,255,.85)`, fill `rgba(255,255,255,.2)` (lite: **`rgba(255,255,255,.3)`, no blur**; non-lite: blur 16 px saturate 1.6), inset top highlight `0 1.6 0 rgba(255,255,255,.55)`, drop `0 12 24 rgba(0,0,0,.3)`; opacity 0 → 1; sits under the tile | 220 ms `var(--ease)` |
| Tile shadow | `0 16 28 rgba(0,0,0,0)` → `0 16 28 rgba(0,0,0,.45)` | 220 ms `var(--ease)` |
| Shine sweep `.tile::after` | 115° gradient `transparent 35% → rgba(255,255,255,.35) 50% → transparent 65%`, translateX −120% → +120% **only on gaining focus**; on losing focus it snaps back instantly (no transition on the base state) | **700 ms `ease`** |

**Artwork per tile.** All source files are PNG **800×480** (5:3, verified with `file`), displayed at 280×168 (exactly 5:3 ⇒ `object-fit:cover` crops nothing; 2.86× oversampled). The `<img>` fills the tile (`css/home.css:22`), radius inherited. The PNGs carry their own rounded corners and background colour (`css/home.css:21`).

| Row | Card | Light file (`icons/`) | Dark file (`darkicons/`) | Configured package(s) |
|---|---|---|---|---|
| 0 | YouTube | `Youtube.png` | `darkyoutube.png` | `com.google.android.youtube.tv` |
| 0 | Netflix | `Netflix.png` | `darknetflix.png` | `com.netflix.ninja` |
| 0 | Prime Video | `Prime.png` | `darkprime.png` | `com.amazon.amazonvideo.livingroom` |
| 0 | Disney+ | `Disney+.png` | `darkdisney.png` | `com.disney.disneyplus` |
| 0 | Spotify | `Spotify.png` | `Spotify.png` (capital S) | `com.spotify.tv.android` |
| 1 | Stremio | `stremio.png` | `stremio.png` | ids `com.stremio.one`; name match `/stremio/i` |
| 1 | Rhenium | `rhenium.png` | `rhenium.png` | ids `[]`; name match `/rhenium/i` |
| 1 | Moonlight | `moonlight.png` | `moonlight.png` | ids `com.limelight`, `.noroot`, `.root`; `/moonlight/i` |
| 1 | RetroArch | `retroarch.png` | `retroarch.png` | ids `com.retroarch`, `.aarch64`, `.ra32`; `/retroarch/i` |

Source: `apps.js:2-8,54-62`. Card order is the array order (Stremio, Rhenium, Moonlight, RetroArch); `PHASE6_NOTES.md` states a different order (Stremio, RetroArch, Moonlight, Rhenium) — **the code wins**. Moonsun PNGs (512×512) are effectively unused (see §2.4). Tile art is chosen as `(dark && a.dark) ? a.dark : (a.icon || a.dark)` (`js/home.js:21`). If an image fails, the tile shows the app's initials on `#222` (`js/home.js:17`).

**Home input (`js/home.js:128-173`, `js/main.js:21-29`)**

| Zone | Key | Result |
|---|---|---|
| Rows | Left/Right | move within row, **clamped, no wrap** |
| Rows | Down | next row if any; column index carried and clamped to row length (row 0 col 4 → row 1 col 3) |
| Rows | Up | previous row (column carried); from row 0 → header zone |
| Rows | OK (13) | launch focused card (§3); held-OK repeats ignored (`js/main.js:27`) |
| Rows / header | Back (10009) | `goHome` bridge call (restarts the HOME intent; effectively a no-op for the launcher), or toast "Use Home to leave Glass Launcher" if the bridge is missing |
| Header | Left/Right | move among stops, clamped. Order is `[Recently Used, Search, Control Centre]` when Recently Used has a valid app, else `[Search, Control Centre]` (`js/home.js:140`); the remembered stop starts at Search (`hdrIdx=0`) |
| Header | Down | back to rows (same row/col as left) |
| Header | OK | click sound then open Search / CC / relaunch recent app |
| Header | Up | ignored |
| Any | Tab / Shift+Tab | linear path: header stops then each card L→R, T→B, **wrapping** |
| Any | Esc, Backspace (outside Search) | mapped to Back |
| Any | Menu key, long-press | **not handled**; no long-press logic anywhere |
| Any | arrows held | key repeats are not throttled; each repeat moves focus and plays the nav sound |
| Mouse | hover | moves focus only after a genuine move ≥ 4 px (parked pointer ignored); not while an overlay is open |
| Mouse | click | activate card / button / recent panel |
| Mouse | wheel | one row per notch, throttled 350 ms, ignores \|deltaY\| < 4 |

**Focus memory.** Boot: rows zone, row 0, col 0. Each row keeps its own column; vertical moves copy the column. Focus classes are cleared while an overlay is open (no tile shows focus under CC/Search). Closing CC returns to the header CC button when opened from the header, or to rows when opened from Search shortcuts (`js/controlcentre.js:146-149`). Closing Search returns to the stored zone (rows → rows; header → the same header stop) (`js/search.js:80,85-90`). Returning from another app restores nothing and changes nothing (process state persists).

**Sounds on Home.** Navigate sound plays whenever the *focus key changes* in rows/header and render is not silent (`js/home.js:122-124`): arrow moves, Tab, hover moves. No sound on edge presses, on overlay close, or at boot. Click sound plays for OK/click on cards (via `G.launch`), header buttons, and the recent panel (`js/glass.js:130`; `js/home.js:145,162-163`). Files: `sounds/navigate.wav`, `sounds/click.wav` (WAV PCM 16-bit stereo 44.1 kHz), volume **0.2** (`apps.js:10`), pool of 3 `Audio` objects each (`js/glass.js:53-62`).

### 2.4 Control Centre (S4) — `js/controlcentre.js`, `css/controlcentre.css`

| Item | Spec |
|---|---|
| Panel | fixed top/right/bottom **32 px**, width **800 px** (x 1088–1888, y 32–1048), radius **52 px**, padding 30/32/28, bg `linear-gradient(145deg, #fff@.13, #fff@.055)` over `rgba(25,29,40,.66)` (lite: `.15/.065` over `.70`), border 1 px `rgba(255,255,255,.19)`, shadow `inset 0 1 0 rgba(255,255,255,.22), 0 36 88 rgba(0,0,0,.30)`, backdrop `blur(30px) saturate(1.5)` (`css/controlcentre.css:4-11,61`) |
| Open/close | slides from `translateX(100%+60px)`; opacity 0 → 1; **transform 420 ms `var(--ease)`, opacity 300 ms `ease`**; `visibility` flips at the end of close (420 ms delay) |
| Scrim | fullscreen `linear-gradient(90deg, rgba(6,8,14,0) 38%, rgba(6,8,14,.34) 100%)`, fade **350 ms** `var(--ease)`; clicking it closes (`css/controlcentre.css:2-3`; `js/controlcentre.js:151`) |
| Home under CC | header buttons fade out, clock + recent at .3 opacity, rows stay visible (no stage dimming for CC) |
| Header | right-aligned; time 72 px weight 600 (`h:mm` 12-hour, same format as clock), date 23 px `rgba(255,255,255,.68)` margin-top 7 px; padding-right 18 px; refreshed every 10 s |
| Grid | 4 columns, rows **152, 152, 160, 140, 124 px**, gap 20 px, padding 3/5/16/5 px; column ≈ 166 px *(derived: 800−2−64−10 = 724 → (724−60)/4)*; grid ≈ 827 px; native scroll (no scrollbar) only if the panel is too short, `scrollTo` smooth (`css/controlcentre.css:17-19`; `js/controlcentre.js:97-104`). Whether it scrolls in practice depends on header height — unverified |
| Tile base `.gt.cci` | padding 20/26, radius **36 px**, bg `linear-gradient(145deg, #fff@.145, #fff@.065)`, border 1 px `.15`, shadow `inset 0 1 0 .19, 0 7 18 rgba(0,0,0,.10)`, chip circle 70 px (`rgba(255,255,255,.17)`) with 36 px icon, label 25 px/650, value 20.4 px/600 (.82 opacity), subtitle 20 px `.68`, max 2 lines |
| Tile focus | bg `.29 → .16`, border `.34`, shadow `inset 0 1 0 .34, 0 13 30 rgba(0,0,0,.20)`, **scale 1.018**, chip bg `.34`, subtitle `.78`; 180 ms `var(--ease)` (no bright-white inversion here, unlike other surfaces) |
| States | `.on` chip bg `.25`; `.dim` opacity .58 (fixed volume; "Sound"/"Display" when the settings page is missing); no tile entrance animation (`css/controlcentre.css:59-60`) |

Tiles (`js/controlcentre.js:10-20`; grid area = row-start/col-start/row-end/col-end):

| Id | Area | Size class | Label | Icon | Value / subtitle text | OK action |
|---|---|---|---|---|---|---|
| theme | 1/1/3/3 | big (92 px chip, 45 px icon, 28 px label, column layout, padding 30) | Appearance | built-in sun (light) or crescent (dark) SVG (`:77-84`) — **the moonsun PNGs are overwritten by SVG** | subtitle "Light" / "Dark" | toggle theme (click sound ×2, see B1) |
| wifi | 1/3/2/5 | wide | Wi-Fi | wifi / wifiOff / ethernet | value `Unavailable`/`Ethernet`/`Connected`/`Not connected`/`Off`; subtitle `Status unavailable` / `Wired connection` (+ `  ·  Wi-Fi <ssid>`) / SSID or `Network name unavailable` (permission granted) or `Network name hidden` / `OK opens Wi-Fi settings` (`:46-53`) | open Wi-Fi settings |
| bt | 2/3/3/5 | wide | Bluetooth | bluetooth / bluetoothOff | value `Unavailable`/`Off`/`On`; subtitle `Not available on this box`, `OK opens Bluetooth settings`, connected names (first 2, `+N`), `No device connected · N paired`, `N paired: a, b...`, or `No paired devices` (`:54-63`) | open Bluetooth settings |
| vol | 3/1/4/5 | full, with 12 px bar | Volume | volume / volumeMuted | value `NN%` / `Muted` / empty if fixed; subtitle `[Output: <label>  ·  ]` + `Left / Right to adjust, OK to mute` / `OK to unmute` / `Fixed volume: your TV or soundbar controls it`; `Unavailable` if the bridge fails (`:67-76`) | mute toggle (or toast if fixed) |
| dev | 4/1/5/5 | full | This box | device | `<manufacturer model>  ·  Android <ver>  ·  <ip or "no IP">  ·  <free> GB free` (`:85-89`) | open device-info settings |
| set | 5/1/6/2 | mini (60 px chip, 31 px icon, 19 px label/600, column, padding 12/8) | Settings | settings | none | open main settings |
| snd | 5/2/6/3 | mini | Sound | volume | none (dim if no sound settings page) | open sound settings |
| dsp | 5/3/6/4 | mini | Display | display | none (dim if no display settings page) | open display settings |
| srch | 5/4/6/5 | mini | Search | search | none | close CC, focus header Search, open Search |

Volume bar: track `rgba(255,255,255,.22)`, fill `rgba(255,255,255,.88)`, height 12 px radius 6 px, width transition 140 ms; fill opacity .45 when muted (`css/controlcentre.css:55-57`).

**CC input.** Arrows move by *spatial* nearest tile (centre-to-centre; candidate must be >4 px in that direction; score = primary + 2.5×perpendicular distance, `js/glass.js:86-99`); Left/Right on the Volume tile adjust volume by `max(1, round(max/20))` and play the nav sound instead of moving; OK activates (click sound first); Back closes; Tab/Shift+Tab cycles tiles with wrap (nav sound); other keys are ignored. Nav sound plays only when a move actually happened. Mouse: hover focuses (real move ≥ 4 px), click focuses + activates. Initial focus = Appearance (or Volume when opened from the Search "Volume" shortcut).

**CC bridge/timers.** On open: `getWifiInfo`, `getBluetoothInfo`, `getAudioOutput`, `getVolumeInfo`, `getDeviceInfo`, `hasSettings('sound'|'display')` (cached); then poll **every 2 s** `getVolumeInfo` + `getWifiInfo` + `getBluetoothInfo`, clock every 10 s; stopped on close. `glassResume()` (400 ms after activity resume, `java/MainActivity.java:80`) and `glassVolumeSync()` (250 ms after a hardware volume key, `:83-91`) refresh when CC is open. First ever open triggers the location permission request (§3.5).

### 2.5 Search (S5) — `js/search.js`, `js/keyboard.js`, `js/registry.js`, `css/search.css`

| Item | Spec |
|---|---|
| Backdrop | fullscreen `rgba(6,8,14,.34)`; fade 300 ms `var(--ease)`; home stage drops to opacity .5, header buttons fade out, clock/recent .3 (`css/search.css:2-3`; `css/home.css:7,10`) |
| Panel | **1800 px wide**, centred (x 60–1860), top **170 px**, padding 36 px, radius **60 px**, `.glass`; opens from `translateY(32px) scale(.965)`, opacity 0 → 1; **transform 420 ms `var(--ease)`, opacity 320 ms ease** |
| Field | height 112 px, radius 40 px, padding 0 38 px, margin-bottom 30 px, gap 24 px; gradient `rgba(255,255,255,.20)→.09`, inset shadow `0 2 10 rgba(0,0,0,.18)`; search icon 42 px opacity .8; text **40 px** weight 500, placeholder "Search apps and settings" `rgba(255,255,255,.5)` weight 400; caret 2.8×46 px white, blinks `steps(1)` 1.05 s while open |
| Body | flex row, gap 32 px: results panel (flex 1 ≈ **654 px**) + keyboard **1040 px**; both ≈ 412 px tall (keyboard height drives it) *(derived)* |
| Results panel | radius 40 px, inset padding 16 px; rows **94 px** tall + 4 px margin, radius 26 px, icon 64×64 radius 17 px (`rgba(255,255,255,.14)`, `contain`), title 27 px/600 ellipsis, subtitle 18 px opacity .62 (**shortcuts only**), right-aligned tag 15.6 px uppercase opacity .5 ("App", "Settings", "Glass"). ≈ 4 rows visible |
| Result focus | bright surface, scale **1.02**, shadow `0 12 28 rgba(0,0,0,.26)`; transitions 160–180 ms |
| List scroll | translateY animated 220 ms; keeps selection ≥36 px from top / fully inside bottom (+8 px) (`js/search.js:28-32`) |
| Suggestions (empty query) | header "Suggestions" (16.4 px uppercase) then six rows: Settings, Wi-Fi, Bluetooth, Volume, Play Store, Appearance; 40 px search glyph, no tag; selecting one fills the query and returns focus to the keyboard (`:8,46`) |
| No match | "No apps or settings match “<query>”" 24 px `rgba(255,255,255,.68)` (`:51`) |
| Hint bar | static: "OK Select · Left Results · Back Close"; 19 px, gap 48 px (`index.html:34`); does not change with focus |

**Keyboard (`js/keyboard.js:15-28`, `css/search.css:45-56`).** Key 94 px high, gap 12 px, radius 27 px, glyph 37 px/500 (function keys 26 px/600, darker glass `rgba(18,20,28,.40→.26)`), flex-grown by weight so rows align. Focus: scale **1.1**, white bg, shadow `0 16 36 rgba(0,0,0,.34)`; press: scale .97 for **110 ms**; Shift active = light bg with ink glyph.

| Layout | Row 1 | Row 2 | Row 3 | Row 4 (weights) |
|---|---|---|---|---|
| abc | `qwertyuiop` | `asdfghjkl` + Shift (SVG arrow, filled when on) | `zxcvbnm.-` + Backspace (SVG) | `123` (2.1), `space` (5.4), `clear` (1.9), `search` (2.2) |
| num | `1234567890` | `@#&*()-_+=` | `.,?!':;/"` + Backspace | `abc` (2.1), `space`, `clear`, `search` |

Approx. widths at 1040 px: rows 1–3 keys ≈ 93.2 px; row 4 ≈ 182 / 467 / 164 / 190 px *(derived)*. Shift applies to the next letter only and then resets; switching mode resets shift and keeps the cursor position. Opening Search resets to abc, query empty, focus on key `a` (row 2, col 1).

**Search input and rules.**

| Input | Behaviour |
|---|---|
| Keyboard focus: arrows | move; Up/Down use the remembered x-position (nearest key by centre); moving plays nav sound; Left at the left edge jumps to results if there are any (`js/search.js:121-124`) |
| Keyboard focus: OK | press key (key-type sound, below) |
| Search key / Enter within 2.5 s after hardware typing | flush debounce; if query and results exist → focus results row 0 + nav sound; else toast "Type to search" (empty) or "Nothing matches that" (`:69,118`) |
| Results focus | Up/Down move (clamped, nav sound only on change), Right → keyboard (nav sound), OK → run the entry (click sound); no wrap (`:128-132`) |
| Back / Esc | close, restore previous focus; Backspace *deletes* inside Search (`js/main.js:23`) |
| Hardware keyboard | any single printable char (no Ctrl/Alt/Meta, not Enter) is typed and also moves focus back to the keyboard; Backspace deletes (`:116-117`) |
| Tab | keys L→R,T→B, then results, then wraps to keyboard (nav sound) (`:93-104`) |
| Mouse | hover on key/result focuses; click key presses it; click result runs it; wheel over results moves selection; clicking the dimmed backdrop (outside the panel) closes (`:107-111`) |
| Query limits | max **60** characters; typing is applied to the field instantly, the result list is rebuilt after a **110 ms** debounce; at most **24** results (`:7,14,16`) |
| Key sounds | letters/digits/symbols → `sounds/keys/alpha/d1-d4.mp3`; space → `space/d1-d5`; Search/Enter → `enter/d1-d4`; Shift/Backspace/clear/mode → `alt/d1-d5`; random variant per press, pool of 4, volume **0.3** (`apps.js:10`; `js/glass.js:64-82`; `js/keyboard.js:82-92`). Hardware typing plays alpha/space, Backspace plays alt, Enter-submit plays enter (`js/search.js:116-118`) |
| Selecting a result | plays click, then: app → launch (**Search stays open**); settings shortcut → open Android settings page (Search stays open); "Control Centre"/"Volume" → closes Search and opens CC (from rows); "Appearance" → toggles theme + toast "Dark appearance"/"Light appearance" (`:68`; `js/shortcuts.js:20-31`) |
| Result sources | installed apps (name + package id as subtitle internally, not displayed; tag "App") and shortcuts (tag "Settings" for system pages, "Glass" for own controls). Shortcuts: Wi-Fi, Bluetooth, Display, Sound, Apps, Storage, Date & time, Keyboard, Accessibility, About this box (kind `device`), Settings (superseded when `com.android.tv.settings`/`com.android.settings` is installed, because that app then appears as an app result) (`js/shortcuts.js:6-31`); system-settings shortcuts disappear if `hasSettings(kind)` is false |
| Ranking | normalised (lowercase, strip punctuation); score exact 100 / prefix 85 / word-prefix 70 / substring 50 / fuzzy subsequence 20 (title only, query ≥ 4 chars); keyword matches ×0.92; title-length penalty ≤ 5; ties: apps before shortcuts, then title A→Z. Done by Rust (`coreSearch`) when available, otherwise the equivalent JS (`js/registry.js:7-25,114-150`). Built-in alias: Play Store ← "play store, google play, store, app store, download apps" (`js/shortcuts.js:34`). `SEARCH_ALIASES` is empty in config (`apps.js:74-76`) |
| App result icon | override if configured, else the real app icon (128×128 PNG) via `iconBaseUrl`; initials shown until the image loads (`js/search.js:34-42`) |

### 2.6 Toast (S6)

Centre of screen (`left:50%;top:50%`), padding 20/36 px, radius 28 px, 22 px text, max width 1000 px, centred text, `.glass` with `sheen + rgba(24,27,36,.82)` background, z 40, non-interactive. Opacity fade **300 ms** (default easing) in and out; visible **3200 ms**; a new toast replaces text and restarts the timer (`css/glass.css:58-60`; `js/glass.js:49`). Texts in §3.4.

### 2.7 Installed-apps list (S7, unreachable)

Fixed panel top 8vh (86 px), left 10%, 80%×84%, padding 40/52 px, radius 32 px, bg `rgba(18,20,28,.93)`; title "Installed apps and their IDs (Up/Down to scroll, Back to close)"; rows name (440 px bold) + package id; hides `org.tizen*`/`com.samsung*`; Up/Down scroll 120 px, Back/Enter close; "No app list available." if empty (`js/main.js:6-17,24`; `css/glass.css:63-68`). Not reachable with the current `APPS`/`ENTERTAINMENT`, so low priority.

### 2.8 Android shell behaviour that affects the UI

| Behaviour | Source |
|---|---|
| HOME + LEANBACK_LAUNCHER + DEFAULT launcher, landscape, `configChanges=keyboard\|keyboardHidden\|orientation\|screenSize`, `windowSoftInputMode=adjustNothing\|stateHidden` | `AndroidManifest.xml:40-46` |
| Immersive-sticky fullscreen (system bars hidden) | `java/MainActivity.java:25` |
| Back (KEYCODE_BACK on key-up and `onBackPressed`) is swallowed and delivered to JS as keyCode 10009; Back handling is entirely app logic | `java/MainActivity.java:18,92,96` |
| Hardware volume keys pass through to the system; CC slider resyncs 250 ms later | `java/MainActivity.java:85-91` |
| `onPause`: WebView and its JS timers paused; `onResume`: resumed + `glassResume` after 400 ms | `java/MainActivity.java:69-81` |
| Permissions: network state, Wi-Fi state, Bluetooth (legacy), fine location. `targetSdk 30` | `AndroidManifest.xml:2-5`; `docs/architecture/CURRENT_ARCHITECTURE.md:20` |
| Android on-screen keyboard is never used by the UI (`showKeyboard`/`hideKeyboard` exist but no JS calls them) | `java/GlassBridge.java:76-92` |
| Mouse right-click → Back: asserted by a comment (`js/main.js:19`) but no Java code does it; may rely on Android mapping the secondary button to KEYCODE_BACK — **unverified** | `docs/architecture/FEATURE_INVENTORY.md` |

### 2.9 Bridge calls by surface, persistence, timers

| Bridge method (`java/GlassBridge.java`) | Called from | When |
|---|---|---|
| `getInstalledApps` → `[{id,name,show:true}]` for every package with a launcher/leanback/info intent (`java/AppDiscovery.java:46-77`) | `js/appdata.js:9` | **once at boot** (never refreshed) |
| `launchPackage(pkg)` → boolean (Leanback launch intent, else launcher intent; `NEW_TASK`) (`java/AppDiscovery.java:35-40,79-87`) | `js/glass.js:132` | every launch |
| `goHome()` | `js/glass.js:128` | Back on Home |
| `iconBaseUrl()` + WebView fetch of `https://appassets.androidplatform.net/glass-icon/<pkg>` (128×128 ARGB PNG, LRU 96) | `js/icons.js:45-62`; `java/AppDiscovery.java:104-142` | Recently Used / Search icons (home tiles use asset art). `getAppIcon` data-URI path only if `PERF.icons==='bridge'` (not set) |
| `coreSearchSnapshot`, `coreSearch` (Rust) | `js/registry.js:81-111` | once per Search session, then every query |
| `hasSettings(kind)` | `js/registry.js:122` | Search availability + CC mini tiles, cached per kind |
| `openSettings(kind)` → boolean; falls back to main Settings if the specific page is missing (`java/SettingsLauncher.java:36-47`) | `js/glass.js:142-144` | CC tiles, Search shortcuts |
| `getVolumeInfo`, `setVolume`, `toggleMute`, `getAudioOutput`, `getWifiInfo`, `getBluetoothInfo`, `getDeviceInfo`, `requestLocationPermission` | CC | open + 2 s poll (§2.4) |
| Unused by JS: `isInstalled`, `showKeyboard`, `hideKeyboard`, `coreInfo`, `coreSearchIndex` | | |

| Persistence (`localStorage`, prefix `glass.`, `js/glass.js:41-45`) | Content |
|---|---|
| `glass.recent` | `{id, name}` of last successful launch (`js/recent.js:34`) |
| `glass.locasked` | `true` after the location permission was requested once (`js/controlcentre.js:144`) |
| `glass.noblur` | `true` after the automatic blur fallback triggers (`js/glass.js:120`) |
| `glass.slowopen` | count of slow overlay openings, reset to 0 on a good opening (`js/glass.js:119-121`) |
| Not persisted | theme, focus position, query, CC state |

| Timer | Interval |
|---|---|
| Clock/date + theme schedule | 10 s (`js/main.js:40`) |
| CC live state | 2 s; CC clock 10 s (`js/controlcentre.js:141-142`) |
| Search render debounce | 110 ms; key-press visual 110 ms |
| Toast | 3200 ms |
| Perf watch | frames sampled for 800 ms (CC open) / 700 ms (Search open); "slow" = frame > 42 ms; if > 8 frames and > 50% slow → `slowopen++`, at 2 → `noblur` permanently (`js/glass.js:111-123`) |
| Wheel throttle | 350 ms |

## 3. Behaviour rules that are easy to miss

### 3.1 Resolving configured cards (`js/appdata.js:15-22`)
1. If the card's current `id` is in the installed list, use it (top-row cards have a fixed `id`).
2. Else the first of `ids` that is installed (entertainment cards).
3. Else the first installed app that is shown, not hidden, and whose *name* matches `match`.
4. Else, only if the installed list is empty (e.g. no bridge), use `ids[0]` so launching is still attempted.
5. Otherwise the card has **no id**: it stays visible, plays the click sound and toasts "`<name>` is not installed on this box." Nothing else is launched. Resolution happens once at boot (`G.apps.load`, `js/appdata.js:11`) and mutates the config.

Note: a top-row card with a fixed `id` that is not installed keeps that id; the launch then fails in Java and gives the same "not installed" toast.

### 3.2 Hidden packages and self-exclusion
Hidden-prefix regex `/^(org\.tizen|com\.samsung|DU\.TV\.PRODA)/i` applies to name-match resolution and Search results (`js/appdata.js:7`; Rust mirror `rust/glass-core/src/snapshot.rs:39`). Self package `com.glasslauncher` is excluded from Search results (`js/registry.js:167`) and is never recorded as Recently Used (`js/recent.js:34`). Java reports `show:true` for all (`java/AppDiscovery.java:56`).

### 3.3 Recently Used
Only **successful** launches (bridge returned `true`) from Home cards, the Recently Used panel itself, or Search app results record (`js/glass.js:135`). Settings shortcuts and failed launches do not. Stored `{id,name}`; shown only if the app list is empty **or** the id is still installed (`js/recent.js:12`); otherwise the panel shows its empty text and is not a focus stop. Name shown = configured card name if any, else the stored name.

### 3.4 Toast texts
| Text | Trigger |
|---|---|
| `<name> is not installed on this box.` | no resolved id, or `launchPackage` returned `false` (`js/glass.js:130,134`) |
| `<name> could not be opened.` | bridge missing/threw (`:133`) |
| `Use Home to leave Glass Launcher` | Back on Home without bridge (`:128`) |
| `That settings page is not available on this box.` | `openSettings` returned non-true (`:143`) |
| `Volume is fixed: use your TV or soundbar volume.` | OK on Volume tile when fixed/unavailable (`js/controlcentre.js:117`) |
| `Dark appearance` / `Light appearance` | Search "Appearance" shortcut (`js/shortcuts.js:31`) |
| `Type to search` / `Nothing matches that` | Search submit with empty query / no results (`js/search.js:69`) |

### 3.5 Other rules
- Location permission is requested **once ever**, the first time CC opens, and `locasked` is set immediately regardless of the answer; without it the Wi-Fi tile shows "Network name hidden" (`js/controlcentre.js:50,144`).
- SSID: `unknown ssid` is treated as empty; Ethernet wins over Wi-Fi in the tile (`java/SystemControls.java:88-103`; `js/controlcentre.js:49`).
- Audio output label priority: Bluetooth A2DP "Bluetooth: <product>" > USB audio / Headphones > HDMI ARC > HDMI > Analogue out > Built-in speaker (`java/SystemControls.java:59-80`).
- Bluetooth "connected" list is read by reflection on `BluetoothDevice.isConnected`; if unavailable the tile falls back to the paired count (`:109-136`).
- Device tile: free storage in GB with one decimal, first IPv4 of the active network (`:138-157`).
- Volume step: 5% of the stream's max (min 1); acts on `STREAM_MUSIC`; mute uses `adjustStreamVolume(ADJUST_MUTE/UNMUTE)` (`:45-56`).
- Holding OK does not repeat launches/toggles outside Search (`js/main.js:27`).
- Window `resize` re-measures layout and re-renders silently (`js/main.js:30`).
- Rows only scroll/collapse the clock when `vpos >= 1` **and** zone is rows, cc or search (`js/home.js:94-95`); focusing the header always expands the clock again.
- Theme-art warm-up: the other theme's PNG for each card is pre-requested after first display (`js/home.js:21`) so a theme flip never flashes (memory cost: 18 decoded 800×480 images if a native port decodes at full size).

## 4. Known bugs and limitations (from code/docs)

| # | Item | Source |
|---|---|---|
| B1 | Theme toggle plays the click sound twice (CC/Search activation sound + `G.theme.toggle` sound) | `js/controlcentre.js:113,116`; `js/theme.js:17`; `js/search.js:68`; `js/shortcuts.js:31` |
| B2 | Entertainment order in `PHASE6_NOTES.md` differs from the code | `apps.js:57-60` |
| B3 | Installed apps are loaded once; apps installed/removed while Glass runs are invisible to Home, Search and Recent validity until restart | `js/main.js:40` |
| B4 | Launching an app from Search leaves Search open | `js/search.js:68`; `FEATURE_INVENTORY.md` |
| B5 | `LAYOUT.showLabels` and `ENTERTAINMENT.title` have no visible effect; Utilities/"All apps" rows are dead code (placeholder tile style only) | `js/home.js:39-41`; `css/home.css:33-36` |
| B6 | Installed-apps overlay (S7) is unreachable; hides Samsung/Tizen packages (Tizen leftover) | `js/main.js:6-17` |
| B7 | `ui/*.png` icons are configured but missing → SVG fallback; `moonsun/*` assets effectively unused (CC theme tile overwrites them with SVG); `icons/overrides/` empty | `apps.js:30-45`; `js/controlcentre.js:77-84` |
| B8 | `noblur` fallback does not affect the CC panel or Search panel blur | `css/controlcentre.css:4-9` |
| B9 | Docks keep backdrop blur in lite mode (only the focus halo, header buttons, unfocused recent panel lose it) | `css/glass.css:30,54`; `css/recent.css:16` |
| B10 | Back on Home does nothing visible (launcher re-sent to HOME) | `js/glass.js:128`; `java/GlassBridge.java:30` |
| B11 | Mouse right-click as Back is unverified | §2.8 |
| B12 | `showKeyboard`/`hideKeyboard`/`isInstalled`/`coreInfo`/`coreSearchIndex` bridge methods are unused | `java/GlassBridge.java` |
| B13 | Clock may lag the real minute by up to 10 s | `js/main.js:40` |
| B14 | Date text depends on WebView ICU `en-GB` formatting | `js/main.js:36` |
| B15 | Dock height, tile Y positions and CC grid fit depend on font line-height; no WebView render was measured here | §2.3, §2.4 |
| B16 | CC "Sound"/"Display" tiles are dimmed but still activate (falls back to main Settings via `openSettings`) | `js/controlcentre.js:90-93,119-121`; `java/SettingsLauncher.java:36-47` |
| B17 | Nothing in this audit was run on a device; `PHASE7_DPI.md` states the 320 dpi fix was not device-verified | `PHASE7_DPI.md` |

## 5. Parity checklist

Native status column (Home, Theme, Control Centre, Search, Persistence rows): added with the Stage 1 Home work; "Implemented" means present in the native code (with the named unit test where there is one), never yet confirmed on the device.

Legend: **P1** (●) = must be covered by the first native prototype slice (Home tiles/artwork, D-pad focus + animation, app launching, one overlay). Suggested overlay for P1: the Control Centre (static layout with exact spec above, spatial focus, all live state comes from existing Java classes; no on-screen keyboard or Rust search needed). Items marked `—` can follow later.

### Home — layout and artwork
| # | Check (remote on device) | P1 | Native status |
|---|---|---|---|
| H1 | Wallpaper fills the screen (cover) with a darkening shade: top slightly dark, middle clear, bottom clearly darker | ● | Implemented (home slice); device check pending |
| H2 | Row 0 shows 5 cards 280×168 px, 32 px apart, in one 1674×234 glass dock centred horizontally; order YouTube, Netflix, Prime Video, Disney+, Spotify | ● | Implemented (home slice); device check pending |
| H3 | Row 1 shows 4 cards (Stremio, Rhenium, Moonlight, RetroArch) in a dock the same width as row 0, cards centred (row 1 left edge 156 px right of row 0's) | ● | Implemented (home slice); device check pending |
| H4 | Rows are 48 px apart; both rows are fully visible with the clock expanded | ● | Implemented (home slice); device check pending |
| H5 | Card art fills the card edge to edge with rounded corners (22 px); no labels or text are visible on cards | ● | Implemented (home slice); device check pending |
| H6 | Light theme uses `icons/*` art; dark theme uses `darkicons/*` art (Spotify dark file is `darkicons/Spotify.png`) | ● | Implemented (home slice); device check pending |
| H7 | A card whose artwork fails shows initials (32 px bold) on `#222` | — | Implemented: `HomeArt.label`, `NativeHomeView.drawCard`; test `HomeLogicTest.labelIsTextElseInitials`; device check pending |
| H8 | Docks show a blurred translucent glass look with hairline edge and soft drop shadow (blur 30 px, saturate 1.55) | ● | Implemented (home slice); device check pending |
| H9 | Clock reads `h:mm` 12-hour, no leading zero, no AM/PM, 240 px bold with a white-to-transparent vertical fade | ● | Implemented (home slice); device check pending |
| H10 | Date above the clock: weekday, day, full month (en-GB), 30 px semi-bold, 54 px from top | ● | Implemented (home slice); device check pending |
| H11 | Clock and date are centred horizontally | ● | Implemented (home slice); device check pending |
| H12 | Clock/date update within 10 s of a minute change | — | Implemented (home slice); device check pending |
| H13 | Recently Used panel is at top-left (72, 60), 328 px wide, caption "RECENTLY USED" | ● | Implemented: `HomeRecents`, `NativeHomeView.drawRecent`; tests `HomeLogicTest` (recents rules); device check pending |
| H14 | With no recorded app, panel text reads "Apps you open will appear here" and it cannot be focused | — | Implemented: `HomeRecents`, `NativeHomeView.drawRecent`; tests `HomeLogicTest` (recents rules); device check pending |
| H15 | After launching an app and returning, panel shows that app's art and name (2 lines max) | — | Implemented: `HomeRecents`, `NativeHomeView.drawRecent`; tests `HomeLogicTest` (recents rules); device check pending |
| H16 | Search and Control Centre buttons (68 px circles) at top-right: Search at x≈1692, CC at x≈1780, y 60 | ● | Implemented (home slice); device check pending |

### Home — focus and navigation
| # | Check | P1 | Native status |
|---|---|---|---|
| N1 | On boot, the first card (YouTube) is focused | ● | Implemented (home slice); device check pending |
| N2 | Focused card scales to 1.06 and gains a bright translucent halo ring extending 7 px beyond the card, plus a darker drop shadow | ● | Implemented (home slice); device check pending |
| N3 | Focus scale/halo/shadow animate in 220 ms with `cubic-bezier(.2,.8,.2,1)`; losing focus reverses it | ● | Implemented (home slice); device check pending |
| N4 | A diagonal white shine sweeps left→right across a card for 700 ms when it gains focus; does not replay on blur | ● | Implemented (home slice); device check pending |
| N5 | Right/Left move one card at a time and stop at the row ends (no wrap) | ● | Implemented (home slice); device check pending |
| N6 | Down from row 0 col 4 goes to row 1 col 3 (column clamped); Up returns to the carried column | ● | Implemented (home slice); device check pending |
| N7 | Down on the last row does nothing | ● | Implemented (home slice); device check pending |
| N8 | Up from row 0 focuses the header button (Search first; Recently Used only reachable by Left when it has an app) | ● | Implemented: `HomeNav` header order/clamp; tests `HomeLogicTest.headerOrder*`, `headerStepClampsAndRecovers`; device check pending |
| N9 | Header Left/Right traverse [Recently Used (only if it has an app), Search, Control Centre], clamped | ● | Implemented: `HomeNav` header order/clamp; tests `HomeLogicTest.headerOrder*`, `headerStepClampsAndRecovers`; device check pending |
| N10 | Header Down returns to row 0 | ● | Implemented (home slice); device check pending |
| N11 | Focused header button scales to 1.1 and turns bright white with dark icon; Recently Used panel scales 1.04 and turns bright white | ● | Implemented: `HomeNav` header order/clamp; tests `HomeLogicTest.headerOrder*`, `headerStepClampsAndRecovers`; device check pending |
| N12 | Moving focus to row 1 collapses the clock to 0.6 scale and lifts it ≈85 px (opacity .55), shifts both rows up ≈303 px in 450 ms; Recently Used fades out and rises 28 px | ● | Implemented (home slice); device check pending |
| N13 | Returning to row 0 or the header restores clock, rows and Recently Used in 450 ms | ● | Implemented (home slice); device check pending |
| N14 | Rows fade out under a mask near the top (below the collapsed clock) and bottom edge of the screen | — | **Implemented (native), device check pending** — `NativeHomeView.drawRowsMasked`: DST_IN gradient (0 to 134, 1 at 158, 1 to 992, 0 at 1072) in a saveLayer limited to the rows bounds, used only when rows reach a fade band |
| N15 | Holding Right moves focus repeatedly without throttling; holding OK launches only once | ● | Implemented (home slice); device check pending |
| N16 | Tab / Shift+Tab (hardware keyboard) walk header stops then cards, wrapping | — | Implemented: `HomeNav.tab`; test `HomeLogicTest.tabWalksHeaderThenCardsAndWraps`; device check pending |
| N17 | Esc / Backspace act as Back | — | Implemented (Escape/Backspace mapped to Back in `NativeHomeView.onKeyDown`); device check pending |
| N18 | Back on Home keeps Home shown and shows no toast (bridge present) | ● | Implemented (root Back is a no-op as HOME); device check pending |
| N19 | Menu key does nothing | — | Implemented (Menu is not handled); device check pending |
| N20 | Mouse hover (real movement) focuses a card/button; click launches; wheel moves between rows (350 ms throttle) | — | Implemented: `onGenericMotionEvent` hover/wheel, `onTouchEvent` click; no unit test (needs a pointer on the device) |

### Home — launch, sounds, recents
| # | Check | P1 | Native status |
|---|---|---|---|
| L1 | OK on an installed card launches its app (Leanback launch intent preferred) | ● | Implemented: `NativeHomeActivity.launchPackage`; device check pending |
| L2 | OK on a card whose app is missing toasts "<name> is not installed on this box." and launches nothing | ● | Implemented: exact toast text; device check pending |
| L3 | Entertainment cards launch the first installed id from their list, else the first name match; never another app | ● | Implemented; tests `NativeHomeConfigTest`, `HomeLogicTest.launch*`; device check pending |
| L4 | Moving focus plays `navigate.wav` at volume 0.2; nothing plays on edge presses, at boot, or when an overlay closes | ● | Implemented (home slice); device check pending |
| L5 | OK/click plays `click.wav` (0.2) even when the launch fails | ● | Implemented; device check pending |
| L6 | Only successful launches update Recently Used; Settings/CC items do not | — | Implemented: only `launchPackage` successes call `recordLaunch`; tests `HomeLogicTest` recents; device check pending |
| L7 | Returning from a launched app restores the previous focus position unchanged | ● | Implemented: activity is kept, `settle()` on resume, focus saved in `onSaveInstanceState`; device check pending |
| L8 | Toast appears centred, stays 3.2 s, fades 300 ms; a second toast replaces the first | ● | Implemented (home slice); device check pending |

### Theme and wallpaper
| # | Check | P1 | Native status |
|---|---|---|---|
| T1 | 19:00–06:59 local time shows dark theme; 07:00–18:59 light | ● | Implemented (home slice); device check pending |
| T2 | Theme flip crossfades wallpaper in 700 ms and swaps card art | ● | Implemented (home slice); device check pending |
| T3 | A manual toggle holds until the next 19:00/07:00 boundary and is lost on app restart | — | Implemented (home slice); device check pending |
| T4 | Dark theme uses the darker glass tint (alpha .44 vs .30) | ● | Implemented (home slice); device check pending |
| T5 | If `wallpaper2.jpg` fails to load, the light wallpaper is shown at brightness .4 / saturation .8 | — | **Implemented (native), device check pending** — `NativeHomeActivity.loadAssets` + `NativeArt.dimmedFallback` (ColorMatrix, once at load, blurred backdrop built from the filtered copy) |

### Control Centre
| # | Check | P1 |
|---|---|---|
| C1 | OK on the header CC button plays click and slides in an 800×1016 glass panel (32 px from top/right/bottom) in 420 ms; scrim darkens toward the right edge | ● |
| C2 | Home header buttons fade out; clock and Recently Used dim to 0.3; rows remain visible | ● |
| C3 | Panel header shows time (72 px) and date right-aligned, refreshed every 10 s | ● |
| C4 | Grid: Appearance 2×2 top-left; Wi-Fi and Bluetooth stacked right; Volume full width; This box full width; four mini tiles (Settings, Sound, Display, Search) | ● |
| C5 | Focus starts on Appearance | ● |
| C6 | Arrows move spatially (Right from Appearance → Wi-Fi, Down → Bluetooth); nav sound only when focus actually moved | ● |
| C7 | Focused tile brightens, scales 1.018, border lightens in 180 ms | ● |
| C8 | OK on Appearance toggles theme immediately (tile shows sun/moon and "Light"/"Dark") | ● |
| C9 | Volume tile: Left/Right change volume in 5% steps with the bar following; OK mutes ("Muted", bar empty); fixed volume dims the tile and OK toasts | ● |
| C10 | Wi-Fi/Bluetooth/This box tiles show live strings per §2.4 and refresh every 2 s while open | ● |
| C11 | OK on Wi-Fi/Bluetooth/This box/Settings/Sound/Display opens the matching Android settings page (falls back to main Settings; toast if impossible) | ● |
| C12 | Sound/Display tiles dim when the box has no such page | — |
| C13 | OK on Search tile closes CC and opens Search; Back from that Search returns to the header Search button | — |
| C14 | Back closes CC (420 ms) and focus returns to the CC header button (or rows when opened from a Search shortcut) | ● |
| C15 | Returning from a settings page refreshes the CC state | — |
| C16 | Hardware volume keys update the CC slider within ≈250 ms | — |
| C17 | First-ever CC open shows the Android location permission prompt once | — |
| C18 | Clicking the scrim closes CC; hover focuses tiles; click activates | — |
| C19 | Tab cycles tiles with wrap and nav sound | — |

**Native implementation status (wip/cc): native implemented, device check pending for every row below unless noted.** Code: `ControlCentreLogic.kt`, `NativeControlCentre.kt`, `ControlCentreController.kt`. Unit tests: `ControlCentreLogicTest` (texts, volume step, nav table, poll gate) and `HomeClockTest.ccTimeIs12Hour`.

| Rows | Status | Test ref / note |
|---|---|---|
| C1, C2 | native implemented (420 ms slide, scrim gradient, header buttons fade, clock/recent at .3 were already in `NativeHomeView`); device check pending | n/a (visual) |
| C3 | native implemented; device check pending | `HomeClockTest.ccTimeIs12Hour` (the earlier 24-hour format disagreed with `controlcentre.js`) |
| C4, C5 | native implemented; device check pending | `gridGeometry`; initial focus Appearance |
| C6 | native implemented; device check pending | `navigationTable`, `navigationBottomRow` (same scoring as `G.spatial`, quirks included, e.g. Right from Wi-Fi reaches Search) |
| C7 | native implemented; device check pending | focus fill .29→.16, border .34, scale 1.018, 180 ms |
| C8 | native implemented; device check pending | theme toggle via `NativeHomeView.setDark` |
| C9, C12 | native implemented; device check pending | `volumeTexts`, `volumeStepAndClamp`, `percentRoundsHalfUp`; fixed volume dims and toasts |
| C10 | native implemented; device check pending | `wifi*`, `bluetooth*`, `deviceLine`, `pollRunsOnlyWhileOpenAndResumed` |
| C11 | native implemented; device check pending | worker-thread `SettingsLauncher.open`, toast on failure |
| C13 | partly: tile closes the panel and calls `ControlCentreController.openSearch` (toast until native Search is wired) | pending native Search |
| C14 | native implemented (Back closes, 420 ms); header focus return is `NativeHomeView`'s; device check pending | |
| C15, C16 | native implemented (400 ms after resume, 250 ms after a volume key); device check pending | `pollTimings` |
| C17 | native implemented (once, flag in `glass_native_cc` prefs, result refreshes Wi-Fi); device check pending | |
| C18 | not applicable: the native UI has no pointer | |
| C19 | native implemented (Tab / Shift+Tab with wrap); device check pending | `tabWraps` |

### Search
| # | Check | P1 | Native status |
|---|---|---|---|
| Q1 | Search opens with an 1800 px panel at top 170 px (fade + rise + scale .965→1, 420 ms), backdrop dim, home stage at 50% | — | Implemented; emulator (`b_nativeSurfacesAndRouting`): opens, hardware typing changes results, Back closes; device check pending |
| Q2 | Field shows placeholder "Search apps and settings" and a blinking caret; keyboard focus on `a` | — | Implemented (`SearchController`, `NativeSearchView`, `SearchKeyboard`; unit tests); device check pending |
| Q3 | Empty query lists "Suggestions": Settings, Wi-Fi, Bluetooth, Volume, Play Store, Appearance | — | Implemented; emulator (`b_nativeSurfacesAndRouting`): opens, hardware typing changes results, Back closes; device check pending |
| Q4 | Typing updates the field instantly; results update ≈110 ms later; max 60 chars, 24 results | — | Implemented; emulator (`b_nativeSurfacesAndRouting`): opens, hardware typing changes results, Back closes; device check pending |
| Q5 | Keyboard layouts abc/num, one-shot Shift, Backspace, clear, space, 123/abc, search as in §2.5 | — | Implemented (`SearchController`, `NativeSearchView`, `SearchKeyboard`; unit tests); device check pending |
| Q6 | Up/Down on keyboard use nearest-x key in the new row; Left at row start with results goes to results | — | Implemented (`SearchController`, `NativeSearchView`, `SearchKeyboard`; unit tests); device check pending |
| Q7 | Search key with results moves focus to the first result; with none toasts "Type to search"/"Nothing matches that" | — | Implemented (`SearchController`, `NativeSearchView`, `SearchKeyboard`; unit tests); device check pending |
| Q8 | Results show icon, title, shortcut subtitle, right tag (App/Settings/Glass); focused row turns bright with scale 1.02 | — | Implemented (`SearchController`, `NativeSearchView`, `SearchKeyboard`; unit tests); device check pending |
| Q9 | Zero matches shows "No apps or settings match “…”" | — | Implemented (`SearchController`, `NativeSearchView`, `SearchKeyboard`; unit tests); device check pending |
| Q10 | OK on an app result launches it and Search stays open | — | Implemented (`SearchController`, `NativeSearchView`, `SearchKeyboard`; unit tests); device check pending |
| Q11 | OK on settings shortcuts opens the settings page; "Control Centre"/"Volume" close Search and open CC; "Appearance" toggles theme + toast | — | Implemented (`SearchController`, `NativeSearchView`, `SearchKeyboard`; unit tests); device check pending |
| Q12 | Back closes Search and returns focus to where it was (row card or header stop) | — | Implemented; emulator (`b_nativeSurfacesAndRouting`): opens, hardware typing changes results, Back closes; device check pending |
| Q13 | Hardware keyboard types directly; Backspace deletes; Enter within 2.5 s of typing jumps to results | — | Implemented; emulator (`b_nativeSurfacesAndRouting`): opens, hardware typing changes results, Back closes; device check pending |
| Q14 | Key sounds: alpha/space/enter/alt variants at volume .3; no extra click on key press | — | Implemented (`SearchController`, `NativeSearchView`, `SearchKeyboard`; unit tests); device check pending |
| Q15 | Ranking: exact > prefix > word prefix > substring > fuzzy (≥4 chars); apps before shortcuts on ties | — | Implemented; JVM parity: `SearchRankingParityTest`, `SearchCatalogFixtureTest` (Kotlin fallback == Rust, Rust == pre-migration `entries()`) |
| Q16 | Play Store answers to "play store", "store"; Settings shortcut hidden if a Settings app is installed | — | Implemented; JVM parity: `SearchRankingParityTest`, `SearchCatalogFixtureTest` (Kotlin fallback == Rust, Rust == pre-migration `entries()`) |
| Q17 | Glass itself never appears in results | — | Implemented; JVM parity: `SearchRankingParityTest`, `SearchCatalogFixtureTest` (Kotlin fallback == Rust, Rust == pre-migration `entries()`) |
| Q18 | Mouse: hover, click, wheel, backdrop click | — | Not implemented: the box is D-pad only (see `SEARCH.md`) |

### Persistence, platform, robustness
| # | Check | P1 | Native status |
|---|---|---|---|
| X1 | App is the HOME launcher; landscape; immersive (no system bars) | ● | Implemented (UiRouter opt-in); device check pending |
| X2 | Back key never leaves Glass | ● | Implemented; device check pending |
| X3 | Pausing the app (launching another) stops clock/CC polling timers; resuming restarts them | — | Partly: the clock ticker stops in `onPause`; Control Centre polling belongs to the Control Centre work |
| X4 | Recent app survives process restart; theme override does not | — | Implemented: Recently Used in `SharedPreferences`, theme override not persisted; device check pending |
| X5 | Two slow overlay openings switch `.glass` blur to the dense fallback permanently | — | Not applicable (no live blur natively) |
| X6 | At 1920×1080 / density 320 measurements match the table values (rem = 20 px) | ● | Pending device measurement |
