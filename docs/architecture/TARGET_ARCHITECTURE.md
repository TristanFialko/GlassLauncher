# Glass Launcher 2.0 — Target architecture

Status: **implemented for two migrated subsystems (search ranking; search snapshot preparation)**; later steps are listed at the end.
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
| `glass-core` | lib | Platform-independent logic. `#![forbid(unsafe_code)]`. Currently: search index (`search.rs`) — exact port of `registry.js` `norm`/`compact`/`subseq`/`fieldScore`/`score`/comparator; search snapshot preparation (`snapshot.rs`) — the `entries()` rules (show flag, Glass itself, hidden vendors, `available`, `supersededBy`) and ordering. | none |
| `glass-jni` | cdylib → `libglass.so` | JNI entry points for `com.glasslauncher.RustCore`. The only crate with `unsafe` (raw JNI calls), each block documented. | `glass-core`, `jni-sys =0.3.0` (no transitive deps) |
| `glass-cli` | bin (host only) | Line-protocol harness used by `tools/parity` (parity checks, benchmarks). Never shipped. | `glass-core` |

Toolchain pinned by `rust/rust-toolchain.toml` (stable 1.91.0, targets `armv7-linux-androideabi`, `aarch64-linux-android`).

## JNI contract (`RustCore` ↔ `glass-jni`)

| Java (`RustCore`) | Native symbol | Input | Output | Failure value |
|---|---|---|---|---|
| static init | `nativeVersion` | — | UTF-8 `"<semver>;<abi>"` | load/ABI/self-test failure ⇒ `isAvailable()==false` |
| static init | `nativeSelfTest` | — | `boolean` | `false` |
| `setSearchIndex(String wire)` | `nativeSetSearchIndex` | UTF-8 bytes of the wire format | entry count | `-1` (panic / bad input / not loaded) |
| `prepareSearch(String snapshot)` | `nativePrepareSearch` (ABI 2) | UTF-8 snapshot (see data flow) | UTF-8 `"r,s,t"`: kept snapshot record numbers in index order (`""` none); replaces the index | `null` (malformed / unsupported / not loaded); index untouched |
| `search(String q, int max)` | `nativeSearch` | UTF-8 query, max results (≤0 ⇒ 24) | UTF-8 `"i,j,k"` indices into the last index, `""` for none | `null` |

Rules (all enforced in code):
- Strings cross as UTF-8 `byte[]`, never `jstring` (no modified-UTF-8 handling, one copy each way). Inputs over 4 MiB are rejected.
- Every entry point is wrapped in `catch_unwind`; the release profile keeps `panic = "unwind"` so this works.
- No native threads, no JNI references kept between calls, no Java callbacks ⇒ no lock is ever held across an Android call.
- Native state = one `Mutex<Option<SearchIndex>>`. The index is built outside the lock and swapped in. Replacement is idempotent, so activity recreation needs no native cleanup; the process owns the memory (~tens of KB).
- `RustCore.EXPECTED_ABI` must equal `glass_core::ABI_VERSION`; a mismatched library disables the native path rather than misbehaving.
- Calls arrive on the WebView **JavaBridge thread**, never the Android UI thread.

## Search data flow (Glass 2.0, snapshot migration — ADR 0005)

1. `search.js open()` → `G.registry.invalidate()` (one index per Search session).
2. First query of the session: `registry.js` builds a **snapshot of live facts** — every installed app as Android
   reported it (`i` record: id, name, show flag, alias list), then every other provider entry and shortcut with its
   `available()` answer already evaluated and its `supersededBy` packages (`e` records). Format: records split by U+001E,
   fields by U+001F, list items by U+001D, header `glass-snapshot␟1`; U+001C–U+001F are stripped from text first.
3. `AndroidBridge.coreSearchSnapshot(snapshot)` (sent **only if it changed** since the last session) →
   `glass_core::snapshot::prepare` applies the rules `entries()` used to apply in JavaScript (show flag, Glass itself,
   hidden vendor prefixes, unavailable, superseded), orders the kept entries, builds the index and returns the kept
   records' numbers (`"0,1,5,…"`).
4. Each (debounced) query: `AndroidBridge.coreSearch(text, max)` → index positions → snapshot records → entry objects,
   created lazily (only for results shown) by the same constructor the JavaScript provider uses, so `run` closures,
   icons and rendering are unchanged.
5. The original JavaScript path (`entries()` + `queryJs`) runs instead when: the bridge methods are missing (plain
   browser), `PERF.core:'js'` is set in `apps.js`, the native library failed to load or a search call failed (sticky for
   the page lifetime), the snapshot is outside what the port covers (non-array aliases/keywords/`supersededBy`, an id
   with control characters, a kept entry whose kind is neither `app` nor `shortcut` — Rust returns `null`), text
   contains a lone UTF-16 surrogate, or `max` is not positive.

Parity: `tools/parity/snapshot-parity.js` (entries vs fixtures generated from the pre-migration `entries()`, 8 cases)
and `tools/parity/search-parity.js` (8,832 rankings through the snapshot path), both on the production `registry.js` +
`shortcuts.js`; the emulator test checks snapshot entries == `entries()` and ranking through the real WebView bridge.

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

