# Glass - Phase 4: visual refinement (Control Centre, Search, Glass keyboard)

## STATUS - read first
**Not compiled, not run on a VIP7802, no APK.** This environment has no Android SDK or Gradle. What WAS verified: the web layer (home, Control Centre,
Search, keyboard) was run in headless Chromium at 1920x1080 against a mocked Android bridge: D-pad navigation, typing, launching from results, Back, volume,
theme toggle, no script errors. Java changes (one new read-only method) were not compiled. Build in Android Studio; send me any error, and screenshots of the box.
Not measured: real frame rate and real blur quality on the box's GPU/WebView.

## Why it looked like blue rectangles
`PERF.lite` was on, which switched OFF backdrop blur and forced near-opaque navy backgrounds (rgba(14,20,40,.9)) on the Control Centre and Search. That is gone for overlays.

## Design system (css/glass.css)
- One material: neutral smoke tint + diagonal sheen + real backdrop blur + hairline edge + top highlight + soft depth. Colour comes from the wallpaper, not from a blue fill.
- Only two surfaces blur (the Control Centre panel, the Search panel). Tiles, rows and keys are gradient glass on top with no nested blur, which keeps it cheap.
- Focus = the item turns bright frosted white with dark text/icon and a gentle scale (as in the tvOS references). No thick ring.
- Shared radii/tokens at the top of glass.css (`--r-panel`, `--r-tile`, `--r-key`, `--tint`, ...).
- Header Search / Control Centre buttons were restyled to the same system; nothing else on the home screen changed (Search dims the home to 50% instead of 35% so it stays visible behind the glass).

## Control Centre (css/controlcentre.css, js/controlcentre.js)
Right-side overlay, home visible behind. Clock + date at top, then a tile grid: **Appearance** (large), **Wi-Fi** and **Bluetooth** (wide), **Volume** and **This box** (full width),
four compact shortcuts (**Settings, Sound, Display, Search**). Tiles stagger in; D-pad moves spatially (nearest tile in the pressed direction). Volume keeps Left/Right = adjust, OK = mute.
All states are real: Wi-Fi/Ethernet, Bluetooth + connected devices, volume/mute/fixed-volume, theme, model/Android/IP/free storage.
New: **audio output** line under Volume (`getAudioOutput`, read-only, from AudioManager's reported outputs: Bluetooth > headphones/USB > HDMI ARC > HDMI > speaker).
Removed as separate item: the Mute row (OK on the Volume tile mutes). No fake controls were added (no Wi-Fi/Bluetooth toggles, no sleep/power).

## Search + Glass keyboard (css/search.css, js/search.js, js/keyboard.js)
One large glass panel: field on top, results left, keyboard right (52rem wide = about 54% of the screen, keys 4.7rem; both are plain CSS values).
**Technical decision:** the keyboard is a custom in-page keyboard used only by Glass Search. Android's keyboard is not invoked in Search (no pop-up over Glass), and nothing global
is replaced, so normal Android text input in other apps is untouched. It types into the real search field, and the same ranking/launching code as Phase 3 runs on it.
- Keys: letters, shift (one-shot), backspace, space, clear, search (moves to the results), 123/abc with digits and symbols.
- D-pad: arrows move across every key (nearest key when changing row); OK types; Left at the first key enters the results; Right from results returns to the keyboard.
- Empty field shows suggestions (Settings, Wi-Fi, Bluetooth, Volume, Play Store, Appearance); OK fills the field.
- Hardware keyboard: letters/digits/space/Backspace type directly; Enter right after hardware typing jumps to results.
- Back closes Search and returns to where you were (header button or the exact home row/column).
- Search entry also added to the Control Centre.

## Performance
- Animations are transform/opacity only.
- `Glass.perf`: if two Search/Control Centre openings animate slowly (most frames over 42 ms), Glass switches to the no-blur glass by itself and remembers it. Force it in apps.js: `PERF.blur = true | false | 'auto'`.
  Undo an automatic fallback: `Glass.store.remove('noblur')`. The no-blur look is a denser smoke gradient, still layered.
- `PERF.lite` still only removes the blur behind the focused home card, as before.

## Known limitations
- Glass blur is Android WebView `backdrop-filter`. It blurs what is behind the panel (wallpaper and dimmed home), but cannot do refraction or true light-bending. If the box's WebView is old or slow, expect the no-blur fallback.
- Audio output label reports the highest-priority output Android lists, which is normally where media goes; it cannot confirm routing in every case. Fixed-volume HDMI setups still hide the slider's effect (shown as "Fixed volume").
- The keyboard is English QWERTY only; no predictive text, emoji or voice.
- No Android system keyboard in Search: for non-Latin input use a hardware keyboard.
- Phase 3 web files are kept in `app/src/main/assets_phase3/` for rollback.

## Please check on the box
1. Control Centre and Search open/close smoothly; panels look like glass over the wallpaper in light and dark appearance.
2. Type a word, press `search`, launch an app from results; Back returns to the header button / home position you came from.
3. Keyboard size and key legibility from the sofa; send a screenshot if anything clips.
4. Volume Left/Right and remote volume keys stay in sync; Audio output line shows what you expect.

---

# Sound pass (post-Phase 4)

- **Navigate / select sounds turned down**: `window.SOUND.volume` 1 → 0.35 in `apps.js`.
  Adjust that one number if it's still too loud/quiet.
- **Keyboard typing sounds added**, using the MacBook-style pack you provided. Only the
  key-down samples are used (one play per press, no separate release sound, since a D-pad
  OK press doesn't give us a real down/up event to match against). Files are in
  `app/src/main/assets/sounds/keys/{alpha,space,enter,alt}/`, each with several variants
  so repeated letters don't sound identical.
  - `alpha` → letters, numbers, punctuation keys.
  - `space` → space bar.
  - `enter` → the search/submit key.
  - `alt` → shift, backspace, clear, and the 123/abc mode-switch key.
  - Volume is separate from nav/select: `window.SOUND.keyVolume` in `apps.js`, set to 0.5.
  - Wired in `js/keyboard.js` (`press()`, used by both the Home search and the on-screen
    keyboard) and in `js/search.js`'s physical-keyboard fallback path, so typing sounds
    the same whichever input method is used.
- Files touched: `apps.js`, `js/glass.js` (new `G.sound.key(type)`), `js/keyboard.js`,
  `js/search.js`. Nothing else in Phase 4 — layout, Control Centre, search UI — was touched.
