# Native UI prototype (home screen slice)

Status: **prototype, measured only on an emulator**. It does not replace the WebView UI. Glass still starts the
WebView UI as HOME; the prototype is a separate activity you start explicitly.

## What it is

| Part | Where | Notes |
|---|---|---|
| Activity | `NativeHomeActivity` | Not HOME, not in the launcher list. Runs in its own process `com.glasslauncher:native`, so after a force-stop the WebView HOME the system restarts never shares its memory, CPU or frame counters. |
| Drawing | `NativeHomeView` | One custom `View` draws the whole screen on Android's hardware-accelerated canvas: wallpaper (700 ms theme crossfade), clock/date, two glass docks, 9 artwork cards, focus animation, row collapse, header buttons, toast. |
| Overlay | `NativeControlCentre` | Right-hand panel (800 px, 32 px inset, 52 px radius, 420 ms slide, scrim). Tiles: Wi-Fi, Bluetooth, Volume (Left/Right changes it, OK mutes), Appearance, Settings, About this box. |
| Images | `NativeArt` | 800×480 PNGs decoded at 400×240 (`inSampleSize`), scaled and rounded once to 280×168. The wallpaper is pre-blurred once at 240×135 and drawn as the backdrop of glass panels, because `backdrop-filter`/`RenderEffect` blur is not available on API 30. Shadows are baked once. |
| Config | `assets/nativeui/home.json` | Same cards, packages, artwork, wallpapers and sound settings as `apps.js`. `tools/parity/nativeui-config-check.js` fails CI if they drift. |
| Platform | reused `AppDiscovery`, `SystemControls`, `SettingsLauncher` | Unchanged Java classes. |
| Latency probe | `FrameLatencyProbe` | Shared by both UIs and opt-in (`--ez glass.latency true`). See "Measuring". |

**Threading.** The UI thread only handles keys and draws. Config, image decoding, blur, the installed-app list and
Control Centre state are loaded on one worker thread and posted back. `onDraw` allocates nothing: shaders are built
once and positioned with local matrices.

**Rust.** The prototype does not need the Rust core yet. The home screen has no search. When Search is ported, it
will call the same `RustCore.prepareSearch`/`search` as the WebView, off the UI thread.

## Behaviour it reproduces (from `PARITY_CHECKLIST.md`)
- **Geometry** at 1920×1080: tiles 280×168 with radius 22; docks 1674×234 with radius 48; padding 32/72; gap 32. Row 0 has 5 cards; row 1 has 4 cards, centred. Neither row has a label (`home.js` passes an empty one). Header buttons are 68 px.
- **Focus:** the card scales to 1.06 over 220 ms with `cubic-bezier(.2,.8,.2,1)`. A halo ring sits 7 px out (radius 29, a 2.4 px white .85 stroke over a white .3 fill, as in lite mode). The card shadow fades in, and a 700 ms diagonal shine sweeps the card once per focus gain.
- **Row 2 focus:** the clock shrinks to 0.6 and lifts about 85 px, the head fades to .55, and the rows move up 303 px, all over 450 ms.
- **Keys:**
  - Left/Right clamp at the row ends, and the column carries between rows.
  - Up from row 0 goes to the header (Search, then Control Centre).
  - Held OK does not repeat.
  - Back closes the Control Centre; otherwise it leaves the prototype.
- **Launching:** a card uses its configured id if installed, then the first installed id in its list, then the first visible, non-hidden app whose name matches. Otherwise it shows the toast "<name> is not installed on this box." Navigation and click sounds play at volume 0.2.
- **Theme:** dark from 19:00 to 06:59, rechecked every 10 s; Appearance in the Control Centre toggles it. The clock is 12-hour without AM/PM, and the date uses the en-GB long format.

## Not in the prototype (and why)
Search and the on-screen keyboard; recording launches into Recently Used (the panel shows its empty state only); the Control Centre's
remaining tiles (audio output, storage, network details, sound/display shortcuts, the 2 s live poll); mouse/pointer
input; Tab navigation; the placeholder card; icon overrides and Android app icons for cards without artwork; the
header's Recently Used stop. The goal was a representative slice to measure rendering, focus, artwork, launching and
one overlay. These remain in the WebView UI, and the parity checklist tracks each one.

Known visual differences, to be judged on the device:
- The glass blur is a pre-blurred wallpaper, not a live backdrop: identical while the wallpaper is static, but cards behind the Control Centre are covered rather than blurred.
- Text is drawn in Android's sans-serif (Roboto). The CSS asks for SamsungOne/Helvetica Neue/Arial, which the WebView also resolves to Roboto on this box (unverified).
- The vertical positions marked ≈ in the checklist are estimates.
- Date: the prototype prints "Thursday 12 November" (modern en-GB CLDR, no comma), as the box's current WebView does; older WebViews printed "Thursday, 12 November". Check the box's WebView 157 output on the device.
- CI emulator caveat: that emulator image's system WebView does not support flex `gap`, so its WebView screenshots show touching cards and rows. Compare spacing against the box, not against those shots.

## Build and install
The prototype is part of the normal APK (`glasslauncher-debug-apk` CI artifact or `./gradlew assembleDebug`).

```bat
adb install -r app-debug.apk
adb shell am start -n com.glasslauncher/.NativeHomeActivity
```
Press Back to return to the normal Glass home. Nothing about the installed launcher changes.

## Measuring
Both UIs are measured by the same harness with the same key path:
`bench-device.ps1 -Activity .MainActivity` (WebView) or `-Activity .NativeHomeActivity` (native). Add `-Latency` to
record the key→frame probe. Per UI it reports:
- cold start, warm start;
- PSS and CPU of the UI's own process (the WebView UI also adds its renderer process);
- gfxinfo frame times and jank for that process, with an idle control;
- `frames.key_to_frame_ms`: D-pad event time → the first frame that started after the key was dispatched, handed to the GPU. Add about one vsync (16.7 ms) for scan-out.
  - For the native UI, this is the frame that draws the new focus.
  - For the WebView it is a **lower bound**: the renderer handles the key asynchronously, so that frame may not yet show the change. Use `-Trace` for the exact WebView path.

The CI job "Native UI prototype (emulator, TV 1080p)" runs both UIs over the same D-pad path. It uploads screenshots,
gfxinfo, meminfo and the probe logs (artifact `native-ui-prototype`). Emulator timings (software GPU, x86_64) say
nothing about the VIP7802.

## Control Centre (S4/S8)

The native Control Centre has every tile of `controlcentre.js` (Appearance, Wi-Fi/Ethernet, Bluetooth, Volume with output and bar, This box, Settings, Sound, Display, Search) on the CSS geometry (800 px panel, rows 152/152/160/140/124, gap 20).

- `ControlCentreLogic.kt`: pure rules. Tile table and grid geometry, a navigation table precomputed with the `G.spatial` scoring, volume step (`max(1, round(max/20))`), every subtitle string, and the poll gate.
- `NativeControlCentre.kt`: drawing and keys only. State arrives as ready-made tiles on the UI thread and subtitles are wrapped then, so `onDraw` formats nothing. Left/Right on Volume adjust it; Tab cycles with wrap; hardware volume keys pass through to the system.
- `ControlCentreController.kt`: all `SystemControls`/`SettingsLauncher`/preferences access on the worker. Reads everything on open and 400 ms after resume, then Volume, Wi-Fi and Bluetooth every 2 s only while the panel is open and the activity is resumed. A volume key re-reads the volume after 250 ms. The first-ever open asks for `ACCESS_FINE_LOCATION`; the result refreshes Wi-Fi.
- Search is a hook (`ControlCentreController.openSearch`); until native Search exists the tile closes the panel and shows a toast.

Not ported: grid scrolling (the grid fits at 1080p and the view scales uniformly), pointer hover/click, the tile focus drop shadow.
