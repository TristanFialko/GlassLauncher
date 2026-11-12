# Glass — Phase 1 (Foundation)

## Changed
- `app/src/main/assets/index.html` — rebuilt as a vertically scrollable home:
  - **Pinned** row: your original 5 apps.js tiles, unchanged behaviour.
  - **All Apps** row (new): built live from `AndroidBridge.getInstalledApps()`, excludes
    anything already pinned and anything starting `org.tizen.`, `com.samsung.`, or
    `DU.TV.PRODA` (the original DU launcher — left alone on purpose).
  - D-pad: Left/Right moves within a row. Up/Down moves between moon ↔ Pinned ↔ All Apps.
    OK launches. Back exits (unchanged).
  - Wallpaper, auto dark mode (7pm–7am), sounds, lite-mode perf flag: untouched, carried over as-is.
- `app/src/main/assets_baseline/` — full copy of your previous working `assets/` folder,
  kept as a rollback point. Not read by the app; safe to ignore in Android Studio.

## Not touched
- `MainActivity.java` (bridge) — already supports `getInstalledApps`, `launchPackage`,
  `goHome`; nothing needed changing for this phase.
- `apps.js` — your 5 pinned apps and their IDs, untouched.
- Glass Utility Hub (Apps-button interception) — separate project, not in this zip, not modified.

## To build
Replace your project's `app/src/main/assets/` folder with the one in this zip
(or just the `index.html`, since that's the only functional change), then
Run As > Tizen... no — **Run** in Android Studio, or build the APK and sideload with Downloader/ADB as before.

## What to check on the box
1. Pinned row looks and behaves exactly like before.
2. Below it, an "All Apps" row appears listing your other installed apps (plain
   initials tiles for now — icons come in Phase 3).
3. Down from Pinned reaches All Apps; Down again does nothing (last row); Up climbs back
   to Pinned, then to the moon.
4. Launching an app from either row works. Back still exits to du's home.
5. Dark mode, sounds, and performance still feel the same as before.

Tell me what's wrong (if anything) and I'll start Phase 2 — Control Centre
(volume slider wired to real audio output, Wi-Fi/Bluetooth status + settings shortcuts).
