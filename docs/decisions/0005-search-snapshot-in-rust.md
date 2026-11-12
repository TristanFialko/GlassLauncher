# 0005 — Search snapshot preparation moves into Rust

**Status:** accepted (2026-11-12) · **Supersedes:** nothing · **Related:** 0002 (Rust integration), 0004 (migration scope)

## Context

After 0004, Rust ranked search results, but every Search session still ran the registry rules in JavaScript:
`G.registry.entries()` filtered the installed list (`show`, Glass itself, `HIDDEN` vendor prefixes), attached aliases,
evaluated each shortcut's `available()`, scanned the installed list for each `supersededBy` package, built ~230 entry
objects with closures, and serialised them for Rust. That is pure data processing on the WebView's JavaScript thread,
and the rules existed only in JavaScript.

## Decision

- JavaScript gathers **live facts only** into one compact snapshot: the installed list as Android reported it, alias
  lists, and every provider/shortcut entry with its `available()` answer already evaluated (`available()` stays in JS: it
  asks Android through `hasSettings`, and providers are JS objects).
- `glass_core::snapshot::prepare` applies the rules, orders the kept entries and builds the index in **one JNI call**
  (`nativePrepareSearch`, ABI 2) and returns the kept snapshot record numbers.
- JavaScript creates entry objects lazily, only for results shown, with the constructor the JS provider uses.
- `entries()`/`queryJs` stay unchanged as the fallback and the parity reference; anything outside the port (unknown
  kinds that survive filtering, non-array data, control characters in ids) makes Rust return `null` and the session
  uses JavaScript.
- `HIDDEN` and the self package id now exist in both `appdata.js`/`registry.js` and `snapshot.rs`; the fixtures in
  `tools/parity/fixtures/registry-entries.json` (generated from the pre-migration JavaScript) catch drift in CI.

## Not moved, and why

`available()` (Android `hasSettings` via the bridge), provider callbacks and `run` closures (JS functions), installed
list discovery (PackageManager, Java), `G.apps.resolve` (runs once at load over ~10 configured cards; no measurable
workload) and all DOM/focus/animation code.

## Consequences

- Host (node 24, x86_64), 228 entries: JS side of session preparation ~125 → ~79 µs; Rust side ~124 → ~138 µs (rules +
  hash set); ~249 → ~217 µs total, excluding the JNI copy. JS allocations per session drop from ~230 entry objects and
  closures to the ≤24 shown. Through the real WebView bridge on the emulator: 600 → 700 µs median (one ~100 µs timer
  tick; no measurable gain, the snapshot is larger than the old filtered wire). **No VIP7802 measurement yet**, and
  the absolute cost (sub-millisecond on host) means this is primarily a maintainability/ownership move, not a
  user-visible speedup claim.
- Rollback: `PERF.core:'js'` in `apps.js` (JS path at runtime), `-Pglass.rust=false` (no native library), or revert the
  commit; the old `coreSearchIndex` bridge method and `nativeSetSearchIndex` remain for tooling.
