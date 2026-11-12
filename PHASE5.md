# Glass — Phase 5 (Stability, Performance, Sharing Investigation)

Per your brief: no APK built here — you handle Gradle/install/testing. This covers
what I could fix and verify from the code, plus the one item (LocalSend) that was
explicitly a research/recommendation ask for this phase, not an implementation ask.

## 1. The yellow focus / scroll-jump bug — root cause found

There was no `tabindex`, no `.focus()` call, and no `outline` CSS anywhere in the
project — every bit of visual focus is your own `.f` class. That ruled out a normal
focus bug. What's left, and what your description confirmed exactly ("a yellow border
perfectly around an app or even Search or Control Centre... restricts my scrolling") is
**Android's accessibility focus** — a separate, OS-level highlight (classically yellow)
that Chromium's WebView exposes automatically for every DOM element, so screen readers
can read the page. It runs completely independently of your own JS navigation, and
Android tries to auto-scroll whatever has it into view — which is exactly the
"restricts scrolling" symptom, fighting your own transform-based scroll.

**Fix** (`MainActivity.java`): `web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)`
right after the WebView is created. Glass doesn't need screen-reader support, so this
turns the whole accessibility tree off. This should also measurably help performance
(below), since Chromium was rebuilding part of that tree on every DOM change — including
every Search keystroke.

**To check:** try to reproduce the yellow ring the way you did before (you mentioned it's
intermittent and hard to trigger on demand) — it should no longer be possible to produce,
on Home, Search, or Control Centre.

## 2. The black/soft edge on app cards — root cause found

Your screenshot nailed it. I checked the actual icon files: `Prime.png` and `Netflix.png`
already have their own transparent, rounded corners baked in (confirmed by checking the
pixel alpha at the image corners — fully transparent). The CSS tile *also* clips with its
own `border-radius` + `overflow:hidden`, at a different radius than the icon's own baked
corner. Wherever those two roundings don't exactly line up, the tile's solid `#222`
background was showing through as a thin dark ring — worse on Prime because its bright
blue has more contrast against dark gray than Netflix's near-white icon does.

**Fix** (`css/home.css`, `js/home.js`): once an icon image actually loads, the tile's
background goes transparent and the image is clipped to the *same* radius as the tile
(`border-radius:inherit`), so there's only ever one rounded edge, not two fighting each
other. If an icon fails to load, the tile falls back to its solid background + initials
exactly as before — this only changes tiles that have a real icon showing.

**To check:** Prime and Netflix (and anything else with a real icon) should now have
one clean edge, no dark fringe, at any focus/magnification state.

## 3. Performance — Search specifically

Found the real cost: every keystroke was tearing down and rebuilding up to 24 result
rows from scratch (`list.innerHTML=''` + rebuild), including an icon lookup per row.
Icon lookups are already cached by package name (`js/icons.js`), so repeat lookups were
cheap — but the *first* time a new app/result appeared, that still meant a bridge call
into Android's PackageManager plus a base64 image decode, on the main thread, during
every fast keystroke.

**Fix** (`js/search.js`): the text you type still appears instantly — that part never
waits. Only the expensive part (re-filtering the registry, rebuilding the result rows,
icon lookups) is now debounced by ~110ms, so a fast typing burst triggers one rebuild
shortly after you pause, not one per character. I also made sure nothing reads a stale
result list: pressing Enter right after typing, or moving focus into the results list,
forces that pending rebuild to run immediately first (`flushRender()`), so you'll never
hit "Enter" and see last-query's results.

Combined with #1 (accessibility tree now off), this should be a real, noticeable
improvement while typing. The general "noticeable lag across the interface" from the
brief's performance section is largely the same root cause (#1) — I'd hold off on
touching the blur effects (which already have an adaptive fallback in `G.perf`) until
you've retested with this fix in, so we're not fixing something that's already fixed.

## 4. Control Centre stability

I read through `js/controlcentre.js` in full looking for the bugs described (positioning,
focus restoration, overlapping, state sync). The code itself is solid — `open()`/`close()`
correctly track where you came from and restore focus there, timers are cleared on close,
`refreshAll()` is properly guarded. I didn't find a separate bug to fix here. Given your
own description called out the yellow ring appearing specifically "even Control Centre,"
I think most or all of what you saw here was #1 fighting this panel's own focus-scroll
logic (`inner.style.transform`) the same way it fought Search's. I'd rather you retest
after this fix than have me invent speculative changes to code that already looks correct.
If something's still wrong after retesting, tell me specifically what and I'll dig into
that exact interaction.

## 5. Search and keyboard — covered by #1 and #3 above

No separate changes beyond those. Appearance, layout, key styling, suggestions, results
presentation — all untouched as asked.

## 6. LocalSend — investigation (per the brief, not an implementation ask yet)

- It's a Flutter app, not a library — there's no SDK, AIDL interface, or embeddable
  module to pull into Glass. The practical options are "launch it as a separate app" or
  "don't use it at all." A from-scratch reimplementation of its sync protocol inside
  Glass is a large undertaking for what it'd buy you, and isn't something I'd recommend
  starting without a much narrower, specific need.
- **Recommended architecture:** add it to Glass as a normal launchable app — a tile in
  Utilities, same mechanism as everything else (`AndroidBridge.launchPackage`), not a
  rebuilt interface inside Glass. This matches the brief's own fallback guidance ("if
  integrating would compromise the launcher or add unnecessary complexity, recommend
  keeping it separate") — it would.
- **One thing I can't do from here:** I don't have a verified exact Android package ID
  for LocalSend to hardcode safely — guessing one and shipping a broken launch intent
  isn't worth the small convenience. Once you've installed it on the box, run
  `adb shell pm list packages | grep -i localsend` and send me what it prints; I'll add
  the tile with the real ID in one small change next time.
- No background service, no new permissions, no storage access added in this pass —
  nothing here touches LocalSend's actual file-transfer mechanics at all yet.

## Files touched this phase
- `app/src/main/java/com/glasslauncher/MainActivity.java` — accessibility tree disabled.
- `app/src/main/assets/css/home.css`, `app/src/main/assets/js/home.js` — icon edge fix.
- `app/src/main/assets/js/search.js` — debounced result rebuild, with correctness guards
  (`flushRender`) so nothing ever acts on stale results.

Nothing else was touched — Home layout, wallpaper, clock, five-column rows, Utilities,
Control Centre's own design, keyboard, and search presentation are all exactly as they
were at the end of the sound pass.
