# Provenance of `ranking-golden.json`

**What it is.** The search ranking produced by the legacy JavaScript registry, frozen as a test fixture so the
Rust core (`rust/glass-core/tests/golden.rs`) and the Kotlin fallback (`SearchRankingGoldenTest`) can be checked
against the original behaviour without Node and after the web assets are deleted.

**How it was captured.** `node tools/parity/ranking-golden.js --write` loads the production
`app/src/main/assets/js/registry.js` and `shortcuts.js` unchanged into a Node `vm` sandbox with no `AndroidBridge`,
so every query goes through the pure JavaScript path `queryJs()`. Inputs:

- the parity fixture box `tools/parity/fixture.js` (installed apps and aliases), with the `storage` settings page reported
  missing (same box as `search-parity.js`);
- a deterministic query list: fixed edge cases, every title/keyword prefix (1–8 characters, plus upper case), whole and
  space-free titles/keywords, and 400 random strings from an own LCG (seed `20261113`);
- queries containing a lone UTF-16 surrogate are excluded: they have no UTF-8 form, so neither JSON nor Rust can carry
  them. That case is covered by `SearchLoneSurrogateTest` (Kotlin ranks it; Rust is never called).

Each case is `[query, "i,j,k"]`: indices into `entries` for `queryJs(q, 24)`. At capture time the script also
checked that `queryJs(q, 5)` is exactly the first 5 of that list, so the short-limit check uses the prefix.

**Recorded shape** (also asserted by `golden.rs`): `max` 24, short limit 5, 228 entries, 2,522 cases.
First committed in `534b975` on `glasslauncher-2.0-native-audit`.

**Status of the source.** At the time of writing `registry.js` and the WebView UI are **still in the tree** and still
the default HOME; nothing has been removed. While `registry.js` exists, CI (`rust` job, "Frozen ranking golden file")
re-runs the capture and fails if the JavaScript no longer reproduces this file byte for byte.

**After the JavaScript is removed** (Stage C) this file is the reference and can no longer be regenerated. Do not edit it
by hand; a deliberate ranking change must update Rust, Kotlin and this file together, with the reason in the commit.
`ranking-golden.js` is capture tooling only and is deleted with the other Node tooling; the tests that read this file
need no Node.
