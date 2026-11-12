# Glass - Phase 2 corrections + Phase 3 (Search, Utilities row)

## STATUS - read first
**Not compiled and not run on a real VIP7802.** The environment this was written in has no Android SDK, Gradle or network,
so no APK was produced. What WAS verified: the whole web layer (home, Control Centre, Search, Utilities row, icon overrides)
was run in headless Chromium at 1920x1080 against a mocked Android bridge, and the Java sources were checked for syntax only
(no parse errors; real compilation still needs Android Studio). Build it, and send me any compiler error.

## Corrections to Phase 2
- **Control Centre trigger**: the "Controls" text is gone; a compact icon button (built-in SVG, or your `ui/controls.png`) with a D-pad focus ring.
  The moon/sun button is removed from the home screen; the toggle now lives in the Control Centre.
- **Control Centre**: now a glass panel sliding in from the right (36rem wide, 1.6rem inset). Home stays visible behind it with a soft edge shade.
  Sections: Connectivity (Wi-Fi, Bluetooth), Audio (volume slider, mute), Appearance (real light/dark switch), Device (this box, Android Settings).
  Back closes it.
- **Card focus bug**: cause was `layoutRow()` applying a cosine magnification to the whole active row (+24%). Removed.
  Now only the focused card scales (1.06) with the existing halo. Measured: neighbours stay 280px; first/last cards stay inside the screen (188px / 1732px of 1920).
  Five columns per row, unchanged padding.
- **Kept**: wallpapers, big clock, scroll-shrinking clock logic, card artwork, glass panels, 5-column rows, "Pinned"/"All Apps" headings hidden,
  no Recently Installed / Favourites. Phase 2 copies are in `assets_phase2/`.

## Phase 3
- **Utilities row**: a normal home-screen row (same 5-column cards, D-pad, scrolling) with a "Utilities" heading. All five slots are placeholder cards
  (selecting one says it is empty). Configure in `apps.js` > `window.UTILITIES.slots`: replace `null` with `{name, id, icon?, dark?}`;
  set back to `null` to remove; swap entries to reorder; add entries to grow in rows of 5. No Utilities screen exists.
  Developer API (no UI yet): `Glass.utilities.assign / remove / move / set / reset`, persisted in localStorage.
- **Search** (own full-screen interface, magnifier button in the header): live results while typing, ranked exact > starts-with > word-start > contains.
  Installed apps with real icons (package id shown under the name), plus Android settings shortcuts (Wi-Fi, Bluetooth, Display, Sound, Apps, Storage,
  Date & time, Keyboard, Accessibility, About this box, Settings), Google Play Store (also answers "store", "google play"),
  and Glass items (Control Centre, Volume, Appearance). Settings shortcuts are hidden automatically if the box has no such screen.
  Extensible: `js/shortcuts.js` (one table), `Glass.registry.register(provider)`, `window.SEARCH_ALIASES` in apps.js.
- **Icon overrides**: `window.ICON_OVERRIDES` in apps.js maps a package id to an image in `assets/icons/overrides/`. Visual only: the package and launch
  path are untouched. Applies on the home rows and in Search. Uncomment the Play Store line once your icon is in place.

## Architecture
`index.html` is now a shell. `css/` (glass, home, controlcentre, search) and `js/` (glass core, icons, appdata, theme, home, controlcentre,
registry, shortcuts, search, main). Java: `MainActivity` (WebView host), `GlassBridge` (JS interface), `AppDiscovery`, `SystemControls`, `SettingsLauncher`.
All Phase 2 bridge names are unchanged.

## Decisions and limits
- **Brightness removed** from the Control Centre (and its permission/bridge methods). Android cannot tell whether it does anything over HDMI; a slider that may do nothing would be fake.
- **Sleep timer not built**: a normal app cannot switch the TV or box off, so it could not honestly do what it says.
- **Bluetooth** shows on/off, paired devices, and connected devices when this Android build lets apps read them (it falls back to "paired" if not). No toggle, by design (your remote is Bluetooth).
- **Wi-Fi** shows real status; the network name needs the Location permission (asked once). No toggle (blocked for apps on Android 10+).
- **On-screen keyboard is the least certain part**: Search asks Android to show the keyboard and relies on the TV's keyboard app. Untested on the box.
  Expected flow: type, Back hides the keyboard, Down moves to results, Back again closes Search.
- Manifest changes: removed `WRITE_SETTINGS`; added package-visibility `<queries>` (Play Store, settings, launcher apps) and `windowSoftInputMode`.
- Untouched: Glass Utility Hub, its AccessibilityService, the VOD button behaviour, DU launcher, application id `com.glasslauncher`. Version 1.3 (code 4).

## Build and install
Android Studio > Build > Build APK(s), then:
`adb connect 192.168.1.70:5555` and `adb install -r app-debug.apk`

## Please check on the box
1. Row spacing, and that moving across a row only enlarges the focused card.
2. Control Centre opens from the header icon; volume slider and remote volume keys stay in sync; Wi-Fi/Bluetooth cards open the right settings.
3. Search: type with the on-screen keyboard; "wifi", "bluetooth", "volume", "display", "play store", "settings".
4. Utilities row scrolls like the others and the clock still shrinks.
