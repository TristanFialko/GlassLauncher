# Glass - Phase 1 refinements + Phase 2 (Control Centre)

NOT BUILT OR RUN ON A REAL BOX. The layout and Control Centre logic were tested in a headless
browser at 1920x1080 with a simulated Android bridge. The Java side (MainActivity.java) could not be
compiled here because there is no Android SDK, so build it in Android Studio and send me any error.

## Part A - refinements you asked for
All in `app/src/main/assets/index.html` (layout) and `apps.js` (new `window.LAYOUT` settings).
1. Exactly 5 apps per row. The column count is fixed at 5 in code and never depends on screen width.
   Cards are 14rem x 8.4rem (were 13 x 7.8): same 5:3 shape, a little wider, with even spacing.
   Pinned apps and the library are both split into rows of 5.
2. Row spacing: gap between rows is `LAYOUT.rowGap` (2.4rem = 48px).
3. Clock and apps: the clock is bigger (12rem) and the apps start lower (`LAYOUT.clockGap`).
   The clock still shrinks on scroll. All positions are now measured from the real elements
   (clock height, row heights) instead of fixed pixel offsets. During the shrink the gap between the
   clock and the first row never drops below 32px (sampled at 40ms steps). Rows fade out at the top
   and bottom edges so they never run under the clock.
4. "Pinned" and "All Apps" headings are hidden. They still exist: set `showLabels:true` in `apps.js`.
5. Not changed: wallpapers, colours, glass look, clock animation, icons, app discovery, pinned apps.

## Part B - Phase 2: Control Centre
Open it with Up from the first row to the header, then the "Controls" button, OK. Back closes it.
- Wi-Fi: shows Wi-Fi name, or "Wired (Ethernet)" if the box is on a cable. OK opens Wi-Fi settings.
- Bluetooth: shows on/off and paired devices. OK opens Bluetooth settings.
- Appearance: switches light/dark (same as the moon button).
- Volume: real media volume slider (Left/Right), OK mutes. Syncs when you press the remote's volume keys.
- Brightness: see "Honest limits".
- This box: model, Android version, IP, free storage. OK opens Android's device-info page.
- Android Settings: opens the system settings.

### Honest limits (Android 11, normal app, no root)
- Wi-Fi on/off: not offered. Apps targeting Android 10+ are blocked from toggling it.
- Bluetooth on/off: not offered on purpose. Your remote is Bluetooth; switching it off could lock you out.
- Volume: if the box is set to fixed volume (TV or soundbar controls it), the slider greys out and says so.
- Brightness: needs the "modify system settings" permission (OK on the tile opens that screen).
  Android has no way to tell whether the setting does anything over HDMI, so the tile says it only
  affects displays the box controls itself. Test it: if the picture does not change, ignore this tile.
- Wi-Fi name needs the Location permission on Android 11. It asks once when you first open the
  Control Centre. If you decline, it shows "Connected" without the name.
- Not done this phase: sleep timer, night mode, screen saver (no safe way to switch the TV off from a normal app).

## Your icons
Nothing was drawn for you. Control Centre icons are slots filled by YOUR files:
see `app/src/main/assets/ui/README.txt` for file names and `window.UI_ICONS` in `apps.js`
to change names/paths. Missing files just show no icon; the "Controls" button shows text until you
add `ui/controls.png`. Black icons on transparent work as-is (`invert:true`). Sun/moon reuse your moonsun files.

## Files changed
- app/src/main/assets/index.html (rewritten)
- app/src/main/assets/apps.js (added LAYOUT and UI_ICONS)
- app/src/main/assets/ui/README.txt (new)
- app/src/main/java/com/glasslauncher/MainActivity.java (new bridge methods; Phase 1 methods untouched)
- app/src/main/AndroidManifest.xml (permissions: network state, Wi-Fi state, Bluetooth, location, write settings)
- app/build.gradle (versionCode 3, versionName 1.2)
- Rollback copies: assets_baseline/ (before Phase 1), assets_phase1/ (Phase 1 index.html + apps.js)

## Build
Android Studio > Build > Build APK(s), then `adb install -r app-debug.apk` as before.
Glass Utility Hub and the Apps-button interception are not touched.

## Check on the box
1. Pinned row has 5 tiles; every library row has 5; spacing even; no headings.
2. Down from the pinned row: clock shrinks, rows slide up, nothing overlaps.
3. Up from the first row reaches Controls / moon; OK on Controls opens the panel.
4. Volume slider moves the real volume; pressing the remote's volume keys moves the slider.
5. Wi-Fi / Bluetooth / Android Settings tiles open the right screens (a toast says if the box lacks one).
