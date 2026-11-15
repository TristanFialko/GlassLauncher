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

**Status of the source (N7).** `registry.js`, `shortcuts.js`, `apps.js` and the WebView UI were deleted in N6, and the
Node capture/parity scripts (`tools/parity/{fixture,ranking-golden,search-parity,snapshot-parity,nativeui-config-check}.js`)
were deleted in N7; their last revision is `394fdc8` on `glasslauncher-2.0-full-native`. This file is therefore the frozen
reference and **cannot be regenerated**. Do not edit it by hand; a deliberate ranking change must update Rust, Kotlin, this
file and the pinned hashes below together, with the reason in the commit.

**Pinned bytes** (asserted by `ParityFixtureIntegrityTest`, JVM unit tests):

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `ranking-golden.json` | 112,551 | `560e7b658cc52868509dfd94760bc60a8b6636d3858c2bfec9f0a56230cece47` |
| `registry-entries.json` | 179,658 | `55fcf4a8f94d5030861b77ba60b83076c4b19cd540440f33b983a1436c3c7c2a` |

**Checks that read the fixtures (no Node, no Python):**

- `rust/glass-core/tests/golden.rs` (`cargo test`): shape (228 entries, 2,522 unique cases, max 24), Rust snapshot
  preparation keeps exactly the 228 entries, and Rust ranks every case exactly as recorded at limit 24 and limit 5.
- `SearchRankingGoldenTest`: the same 2,522 × 2 rankings for the Kotlin fallback, for `SearchCatalog` + `SearchRanking`
  built from registry-entries case 0, and for the Rust core through the real JNI library.
- `SearchCatalogFixtureTest`: every `registry-entries.json` case (8 boxes) on the Kotlin catalog and on Rust snapshot
  preparation through JNI (replaces `snapshot-parity.js`).
- `SearchRankingParityTest`: Kotlin fallback vs Rust on all 8 boxes, more than 3,000 queries (replaces the
  JavaScript-vs-Rust half of `search-parity.js`, whose JavaScript side no longer exists).
- `ParityFixtureIntegrityTest`: pinned bytes above, golden provenance text, case count/uniqueness, no lone surrogates,
  and the 8 registry-entries case sizes (228, 228, 226, 23, 14, 19, 19, 52).
