# Glass Launcher 2.0 — Target architecture

Status: **implemented for the first migrated subsystem (search ranking)**; later steps are listed at the end.
Evidence for every claim below is in `MIGRATION_STATUS.md` and `docs/benchmarks/RESULTS.md`.

## Layers

```text
┌──────────────────────── Android shell (Java) ────────────────────────┐
│ MainActivity   WebView host, HOME activity, key routing, lifecycle   │
│                (Glass 2.0: pauses WebView timers while in background) │
│ GlassBridge    the only object exposed to JS ("AndroidBridge")        │
│ AppDiscovery   PackageManager: app list (3 queries, not N), launch,   │
│                icons served to the WebView (shouldInterceptRequest)   │
│                icons                                                   │
│ SystemControls audio / Wi-Fi / Bluetooth / device info                │
│ SettingsLauncher settings intents                                     │
│ RustCore       loads libglass.so once; ABI check + self-test;         │
│                every failure = "unavailable", never a crash            │
└───────────────┬──────────────────────────────────┬───────────────────┘
                │ @JavascriptInterface (JavaBridge │ JNI (byte[] UTF-8,
                │ thread, synchronous for JS)      │ coarse calls)
┌───────────────▼─────────────┐      ┌─────────────▼───────────────────┐
│ WebView UI (unchanged look) │      │ Rust: libglass.so               │
│ index.html, css/, js/       │      │  glass-jni  JNI boundary only   │
│ registry.js adapter →       │      │  glass-core platform-free logic │
│   Rust search, JS fallback  │      │   └ search: norm/score/rank     │
└─────────────────────────────┘      └─────────────────────────────────┘
```

Retained deliberately in Java: everything that is an Android API call (package manager, intents, audio,
connectivity, Bluetooth, lifecycle, input capture). Retained in JavaScript: DOM rendering, spatial focus
navigation (it needs live DOM rectangles), overlays, animation. See `docs/decisions/` for why.

## Rust workspace (`rust/`)

| Crate | Kind | Purpose | Dependencies |
|---|---|---|---|
| `glass-core` | lib | Platform-independent logic. `#![forbid(unsafe_code)]`. Currently: search index (`search.rs`) — exact port of `registry.js` `norm`/`compact`/`subseq`/`fieldScore`/`score`/comparator. | none |
| `glass-jni` | cdylib → `libglass.so` | JNI entry points for `com.glasslauncher.RustCore`. The only crate with `unsafe` (raw JNI calls), each block documented. | `glass-core`, `jni-sys =0.3.0` (no transitive deps) |
| `glass-cli` | bin (host only) | Line-protocol harness used by `tools/parity` (parity checks, benchmarks). Never shipped. | `glass-core` |

Toolchain pinned by `rust/rust-toolchain.toml` (stable 1.91.0, targets `armv7-linux-androideabi`, `aarch64-linux-android`).

## JNI contract (`RustCore` ↔ `glass-jni`)

| Java (`RustCore`) | Native symbol | Input | Output | Failure value |
|---|---|---|---|---|
| static init | `nativeVersion` | — | UTF-8 `"<semver>;<abi>"` | load/ABI/self-test failure ⇒ `isAvailable()==false` |
| static init | `nativeSelfTest` | — | `boolean` | `false` |
| `setSearchIndex(String wire)` | `nativeSetSearchIndex` | UTF-8 bytes of the wire format | entry count | `-1` (panic / bad input / not loaded) |
| `search(String q, int max)` | `nativeSearch` | UTF-8 query, max results (≤0 ⇒ 24) | UTF-8 `"i,j,k"` indices into the last index, `""` for none | `null` |

Rules (all enforced in code):
- Strings cross as UTF-8 `byte[]`, never `jstring` (no modified-UTF-8 handling, one copy each way). Inputs over 4 MiB are rejected.
- Every entry point is wrapped in `catch_unwind`; the release profile keeps `panic = "unwind"` so this works.
- No native threads, no JNI references kept between calls, no Java callbacks ⇒ no lock is ever held across an Android call.
- Native state = one `Mutex<Option<SearchIndex>>`. The index is built outside the lock and swapped in. Replacement is idempotent, so activity recreation needs no native cleanup; the process owns the memory (~tens of KB).
- `RustCore.EXPECTED_ABI` must equal `glass_core::ABI_VERSION`; a mismatched library disables the native path rather than misbehaving.
- Calls arrive on the WebView **JavaBridge thread**, never the Android UI thread.

## Search data flow (Glass 2.0)

1. `search.js open()` → `G.registry.invalidate()` (one index per Search session).
2. First query of the session: `registry.js` snapshots `G.registry.entries()` (same filtering as before: `available()`,
   `supersededBy`), serialises them (`G.registry.wire`: entries split by U+001E, fields `kind,title,keyword…` split by U+001F)
   and calls `AndroidBridge.coreSearchIndex` **only if the payload changed** since the last session.
3. Each (debounced) query: `AndroidBridge.coreSearch(text, max)` → indices → the session's entry objects (with their
   `run` closures), rendered exactly as before.
4. The original JavaScript ranking runs instead when: the bridge methods are missing (plain browser), `PERF.core:'js'` is
   set in `apps.js`, the native library failed to load or returned an error (sticky for the page lifetime), an entry has a
   kind other than `app`/`shortcut` (registry.js orders those with an asymmetric comparator), text contains a lone UTF-16
   surrogate (no UTF-8 form), or `max` is not positive.

Parity is checked by `tools/parity/search-parity.js`, which loads the production `registry.js` + `shortcuts.js`.

## Build integration

`app/rust.gradle` (applied by `app/build.gradle`):
- `cargoBuildAndroid` — `cargo build --release -p glass-jni --target <triple>` per ABI with the NDK clang
  (`<triple><minSdk>-clang`) as linker; output registered through the AGP variant API
  (`sources.jniLibs.addGeneratedSourceDirectory`), so packaging depends on it correctly. Incremental (inputs: `rust/**/*.rs`, manifests, lockfile).
- `cargoBuildHost` — host build used by JVM unit tests (`RustCoreJniTest` exercises the real JNI layer on the host JVM).
- Properties: `glass.rust=false` (no native core; app runs the original JS paths), `glass.rustAbis`, `glass.cargo`, `glass.rustProfile`.
- No `cargo-ndk`, no CMake, no UniFFI (see ADR 0002).

## App icon flow (Glass 2.0)

1.5 path: `icons.js packageIcon` → `setTimeout(0)` → synchronous `AndroidBridge.getAppIcon(pkg)` (bitmap → PNG → base64
on the JavaBridge thread while JavaScript waits) → `data:` URI.

2.0 path: `packageIcon` creates an `Image` for `https://appassets.androidplatform.net/glass-icon/<pkg>` (a host reserved
for app-local content; nothing goes to the network). `MainActivity`'s `WebViewClient.shouldInterceptRequest` answers it on
WebView's loader threads with the same 128 px PNG bytes (`AppDiscovery.iconPng`, LRU-cached), or a 404 if the package has
no icon. The callback still receives a URL only after the image has loaded, or `null`, so every caller (home tiles,
Recently Used, Search results) behaves exactly as before. `PERF.icons:'bridge'` in `apps.js` restores the 1.5 path, and
`getAppIcon` remains on the bridge.

## Next candidates (only with measurement — see MIGRATION_STATUS.md)

1. Home D-pad path: forced layout per key press and unthrottled key repeat (static finding). JS-side; measure with the
   D-pad gfxinfo part of `tools/bench` first.
2. Card art decoded at 800×480 for 280×168 tiles (memory). This is an asset change, so it needs visual review before anything changes.
3. Control Centre status snapshot (3 bridge calls → 1 per 2 s tick): deferred. One round trip measured ~0.1–0.25 ms on the
   emulator, so the saving is under 0.5 ms every 2 s.
4. Phase 6 rendering decision, once device frame-pacing data exists (ADR 0001).

