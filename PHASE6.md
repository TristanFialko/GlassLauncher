# Glass - Phase 6

## STATUS - read first
**No APK was built and nothing was run on the VIP7802.** This environment has no Android SDK, Gradle or network.
All web-layer changes were tested in headless Chromium at 1920x1080 against a mocked Android bridge (real keyboard events, real mouse
events, pixel comparison against your Phase 5 build). Java is untouched (byte-identical to Phase 5), so build risk is low, but
package launching, the accessibility fix and real-hardware smoothness are **not verified on the box**.

## What changed
- **Home is now two rows**: the original five (YouTube, Netflix, Prime Video, Disney+, Spotify - unchanged, same order) and an
  **Entertainment** row (Stremio, Moonlight, RetroArch, Rhenium). The Utilities row and the all-installed-apps grid (and its
  "No other apps found." message) are removed. Apps are not removed from Android; Search still finds and launches every installed app.
- **Entertainment row**: same card size (14x8.4rem) and gap (1.6rem) as the top row, centred inside a glass panel exactly as wide as
  the top row's (measured: both panels 1674px wide, both centred on 960; 229px margin each side of the four cards; gaps 32px).
  Heading "Entertainment" uses the same small heading style the Utilities row had.
- **Artwork**: your eight files copied byte-for-byte (verified with `cmp`), no processing. Light theme -> `icons/`, dark -> `darkicons/`.
  Switching themes swaps the file; the other variant is pre-loaded so it never flashes.
- **Recently Used**: compact glass panel top-left (never overlaps the clock or the buttons). Shows the last app that was *really launched*
  (home cards, Search or the panel itself), with its theme-aware art and name. Saved across restarts. Empty state: "Apps you open will appear here".
  Reachable by remote (Left of Search), keyboard (Tab) and mouse. It fades away with the clock when the rows scroll.
- **Overlay trap fixed** (see below), plus keyboard/mouse support.
- **Animation pass** (see below).

## Package ids - what is and isn't verified
Glass does **not** trust a hard-coded id. For each of the four apps it launches whichever of these is actually installed:
1. the first installed id in `ids` (Stremio `com.stremio.one`; Moonlight `com.limelight`, `.noroot`, `.root`; RetroArch `com.retroarch`, `.aarch64`, `.ra32`),
2. otherwise the first installed app whose name matches (`/stremio/i`, `/moonlight/i`, `/retroarch/i`, `/rhenium/i`).
I could not read your box, so these ids are from memory of the usual builds, **not confirmed**. Rhenium has no id guess at all - it is found by name.
If an app is not installed its card still shows and selecting it says "<App> is not installed on this box." (nothing fake is launched).
To confirm: open Search and type each app name - the real package id is shown under it. Edit `window.ENTERTAINMENT` in `apps.js` if needed.

## Bugs fixed
1. **Trapped in Search / Control Centre**: three causes. (a) Only the remote's Back was handled; **Escape (and Backspace) did nothing**.
   (b) Neither overlay had click-outside. (c) The Control Centre's backdrop let the pointer through, and moving the mouse over a home
   card switched the active zone to the home screen while the panel stayed open, so keys then drove the hidden home screen.
   Now: Escape/Backspace/Back close either overlay; clicking the dimmed area outside closes it; clicking inside never does;
   hover can't act on anything under an open overlay.
2. **Hover stole focus** when rows slid under a parked mouse pointer (mouseenter fires on layout change). Hover now needs a real
   pointer move (>=4px from the last one). Stress-tested: 0 focus steals in 30 trials; a slow real drag still hover-focuses.
3. A click that rebuilt the on-screen keyboard (123 key) could be misread as an outside click - Search now only closes when the
   backdrop itself is clicked.
4. Holding OK no longer launches / toggles repeatedly.

## Input
Arrows move, Enter activates, Escape closes the open overlay, Backspace = Back (deletes while typing in Search), Tab / Shift+Tab walk every
control (header, then cards left-to-right/top-to-bottom; Control Centre tiles; Search keys then results). Mouse: hover, click, wheel moves
between rows (and through Search results). TV remote behaviour is unchanged (same handlers; Back from Java still arrives as before).

## Animation / performance
One easing curve and consistent durations for card focus (.22s), card shadow now eases in (it used to pop), scroll layers kept promoted
(no text re-rasterising at the end of a move). Overlays are fully hidden when closed (`visibility`, no hidden blur layers, caret animation only
while Search is open). Removed a pointless re-render on every mouse-leave. No new libraries; no animation loops. Glass material and blur untouched.
Scroll position is preserved when opening/closing overlays (tested). **Smoothness on the VIP7802 itself is untested.**

## Design preserved (pixel check vs Phase 5, same mock, light and dark)
Header buttons: identical. Clock/date and top row: identical apart from a few dozen pixels of drop-shadow where the new second panel sits.
The only visible additions are the Recently Used panel and the second row.

## Known limitations / things to look at
- **The Moonlight artwork you supplied reads "SUNSHINE"** (that is Moonlight's host-server app logo). Used exactly as provided; swap the two files in
  `icons/moonlight.png` / `darkicons/moonlight.png` if that was unintended.
- Filenames were inconsistent (`Moonlight.png` is the *dark* one, `Lightmoonlight.png` the light one). I decided by measuring image brightness, not by name.
- Utilities config and the `Glass.utilities` developer API were removed with the row. Phase 3 files are in `assets_phase3/` if you want it back.
- Closing Search after launching an app: Search stays open behind the launched app (as before); Escape closes it.
- Not on the box: Android right-click-as-Back, WebView version differences, real frame rate, real package launching.

## Files
Modified: `assets/apps.js`, `index.html`, `js/glass.js`, `js/appdata.js`, `js/home.js`, `js/main.js`, `js/search.js`, `js/controlcentre.js`,
`js/keyboard.js`, `css/home.css`, `css/search.css`, `css/controlcentre.css`, `AndroidManifest.xml` (package-visibility entries only), `app/build.gradle` (version 1.5 / 6).
New: `js/recent.js`, `css/recent.css`, `icons/{stremio,moonlight,retroarch,rhenium}.png`, `darkicons/{stremio,moonlight,retroarch,rhenium}.png`, `PHASE6.md`.
Untouched: all Java, Glass Utility Hub, its accessibility service, DU launcher state.
