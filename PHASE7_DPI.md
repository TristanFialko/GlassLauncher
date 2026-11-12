# Glass — native 320 DPI fix

## What I found (inspection, as asked, before changing anything)

The 160dpi requirement traced to exactly **one line**, not a project-wide pattern:
`app/src/main/assets/index.html`'s viewport meta tag was
`<meta name="viewport" content="width=1920,initial-scale=1">`.

I checked everywhere else a 160dpi assumption could plausibly live — there was none:
- `MainActivity.java` / `GlassBridge.java` etc.: no `setInitialScale`, no `setTextZoom`,
  no `DisplayMetrics`/`densityDpi` reads anywhere.
- `AndroidManifest.xml`: no `<supports-screens>`/`<compatible-screens>` density flags.
- `build.gradle` / `settings.gradle`: nothing density-related.
- `res/`: no `values-XXXdpi` resource directories.
- JS: only one reference to a screen dimension at all (`window.innerHeight` in
  `js/home.js`, used correctly — it reads the CSS layout viewport, not physical pixels).

So the *entire* project was already density-correct except that one meta tag. There was
no 160dpi forcing *inside* the app — it was being forced externally (`adb shell wm density
160`, outside this project), which is consistent with what you described.

## Why that one line caused it

`width=1920` and `initial-scale=1` were declared together, and they conflict. When
`initial-scale` is explicitly set, it pins the CSS layout viewport to
`physical_width_px / (devicePixelRatio × initial_scale)` — not to the declared `width`.

- At 160dpi, `devicePixelRatio = 160/160 = 1`, so that formula gives
  `1920 / (1 × 1) = 1920` — which *happens* to match the declared width. Everything
  looked correct, but only by coincidence of exactly matching the baseline density.
- At 320dpi, `devicePixelRatio = 320/160 = 2`, so the same formula gives
  `1920 / (2 × 1) = 960`. The WebView was silently laying the whole page out as if the
  screen were 960 CSS px wide instead of 1920 — every rem-based size, the 5-card row
  windowing math, Control Centre positioning, all of it — computed against the wrong
  viewport width. That's the clipping/oversizing you were seeing, and exactly why
  forcing 160dpi "fixed" it: it wasn't fixed, it was accidentally correct at that one
  specific density.

## The fix

Removed `initial-scale=1` from the meta tag, keeping only `width=1920`.

`MainActivity.java` already had `setUseWideViewPort(true)` and
`setLoadWithOverviewMode(true)` set (this was already correct, untouched). With
`initial-scale` out of the way, that pairing does exactly what you want: honor the
page's declared 1920 CSS-px width exactly, at any density, then auto-zoom that fixed
layout to fill the real screen. This is the standard, correct way to make a WebView
page render at a fixed logical size independent of device density — not a workaround,
not a half-measure.

I added a comment in `MainActivity.java` next to those two settings explaining this, so
a future edit doesn't accidentally re-add `initial-scale=1` to the viewport meta and
silently reintroduce the exact same bug.

## Files modified
- `app/src/main/assets/index.html` — one line, the viewport meta tag.
- `app/src/main/java/com/glasslauncher/MainActivity.java` — comment only, no behaviour
  change (explains the fix next to the two existing settings it depends on).

Nothing else touched. Stremio, Glass Utility Hub, layout, colors, typography, glass
effects, icons, animations, navigation, and Control Centre are all exactly as they were.

## What I can't do from here
I can't build or run an APK in this environment — no Android SDK, no network, same as
every phase so far. I did verify everything that's checkable without a toolchain: the
Java file's braces/parens are balanced, and every JS file still parses cleanly after the
change (neither was touched in a way that could break compilation, since this was a
one-line HTML attribute change and a Java comment). The actual build/install/on-device
verification is yours to run, same as always.

## To test
1. Build and install as usual, **with Android still set to 320dpi** (don't force 160
   back — that's the whole point of this change).
2. Confirm nothing clips, overlaps, or renders oversized at 1920×1080/320dpi.
3. If anything still looks off at 320dpi after this, it means there's a second,
   less obvious cause I didn't find from static inspection — tell me specifically what's
   wrong (which screen, which element) and I'll dig further rather than guess again.
