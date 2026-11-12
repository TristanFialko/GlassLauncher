# ADR 0004 — What moves to Rust first, and what does not

Date: 2026-11-12 · Status: accepted

## Moved: search indexing and ranking
- It is the only pure, CPU-bound logic that runs repeatedly on user input (every debounced keystroke).
- The original JS re-runs `entries()`, `norm()` (two regex replaces) for every title/keyword and again inside the sort
  comparator on every query. The Rust index normalises once per Search session.
- It can be proven equivalent without a device: `tools/parity/search-parity.js` compares the production `registry.js`
  against the Rust path on 8,832 query/max combinations — 0 differences (see RESULTS.md).

## Not moved (and why)
| Candidate from the brief | Reason |
|---|---|
| Recently Used model | One small object written to localStorage on launch; no measurable cost; moving it adds a bridge hop and a second source of truth. |
| App id resolution (`appdata.js resolve`) | Runs once at boot over ≤10 configured apps. |
| Focus/navigation state machine | Needs live DOM geometry; a per-key bridge round trip would add latency. |
| Settings model | Settings are a handful of localStorage keys; no validation hot path. |
| App discovery | Android API work; fixed in Java instead (3 queries instead of up to 3 per package). |

These are revisited only if device profiling shows them on a hot path. "Do not move a subsystem simply because it is easy."
