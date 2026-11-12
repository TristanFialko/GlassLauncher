# Glass — quick pass on top of Phase 6

1. **Dark icons replaced**: `darkicons/moonlight.png`, `darkicons/stremio.png`,
   `darkicons/retroarch.png`, `darkicons/rhenium.png` — swapped for the 4 images you sent,
   matched by filename (Moonlight→Sunshine artwork, stremiodark→Stremio, retroarch-dark→RetroArch,
   darkrhenium→Rhenium). Light-theme `icons/` versions untouched.
2. **Double sound on the keyboard, fixed**. Cause: `js/search.js` was calling `G.sound.click()`
   in the keyboard's `onChar`/`onBackspace`/`onClear`/`onSubmit` callbacks, while `js/keyboard.js`'s
   own `press()` already plays the matching mechanical key sound. Every key press was firing both.
   Removed the redundant `G.sound.click()` calls in `search.js` — only the key sound plays now.
3. **RetroArch and Moonlight swapped** in the Entertainment row (`apps.js`): now
   Stremio, RetroArch, Moonlight, Rhenium.
4. **Virtual mouse**: didn't touch anything here. Checked the code — `home.js`, `keyboard.js`,
   `search.js`, and `controlcentre.js` already have real mousemove-based hover-to-focus handling
   (with a dedup guard so content sliding under a parked cursor doesn't steal focus). If you're
   after something beyond that — e.g. the focus highlight continuously following the cursor
   position rather than snapping tile-to-tile — tell me specifically what's missing on-device
   and I'll take a proper pass at it next time rather than guess now.

Nothing else touched.
