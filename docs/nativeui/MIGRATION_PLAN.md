# Native UI: staged migration plan

Decision rule: **migrate only on VIP7802 evidence.** No stage below starts until the gate before it is met with
owner-run data (harness v5, alternating runs, medians and spread). Emulator numbers never satisfy a gate.

## Where we are (2026-11-12)
- WebView UI on the box: D-pad focus animation renders at p50 61 ms per frame (~16 fps), with 96.65 % of frames over budget. That figure comes from the smoke run, which was not a 2.0 build (`primaryCpuAbi=null`, i.e. no native library). The frame-time behaviour matches the earlier v1 observation, but it is still one run.
- The native home prototype exists (`PROTOTYPE.md`) and works on a 1080p TV emulator. On that emulator, all 9 path keys produced frames, with key→frame 43–104 ms on a software GPU, which says nothing about the box.
- The harness can measure both UIs identically (`-Activity`, `-Latency`).

## Stage 0 — measure (owner, ~1 h)
Alternate WebView and native runs of the same Glass 2.0 APK: W, N, W, N (commands in `MIGRATION_STATUS.md`). Compare per UI:
- nav frame time p50/p95 and jank against 16.68 ms;
- key→frame p50/p95 (native exact; WebView a lower bound);
- cold and warm start;
- PSS: app plus renderer for the WebView UI, app only for native;
- foreground and background CPU.

**Gate to Stage 1** (all of these):
- native nav frame time p50 ≤ 25 ms (≥ 2× smoother than 61 ms), or jank ≤ 20 %;
- native key→frame p50 lower than the WebView's lower bound;
- native PSS ≤ WebView app+renderer PSS;
- cold start not more than 20 % slower.

**If the gate fails:** don't migrate. Profile the WebView first with `-Trace` (Perfetto) and test targeted fixes as A/B experiments. The first candidate is the backdrop blur, which lite mode still applies to both docks, the Control Centre and Search, for every frame of the focus animation. Next are oversized artwork (800×480 drawn at 280×168), the halo/shine compositing layers, and the `will-change` layer count. Port only if those don't close the gap.

## Stage 1 — complete the native Home (code only, parity-checked)
- Recently Used panel, recorded on launch and persisted.
- Header order with Recently Used, then Search, then Control Centre.
- Icon overrides; Android app icons for cards without artwork, via the existing icon cache.
- Placeholder cards; mouse/pointer; Tab.
- One config source, read by both UIs: `apps.js` values move to JSON, with the WebView reading the same file.

Exit: every Home item in `PARITY_CHECKLIST.md` passes on the box (owner remote checklist).

## Stage 2 — native Control Centre
All tiles, the live 2 s poll while it is open (off in background), audio output, storage and network details. Volume
must stay in sync with the remote's volume keys.

## Stage 3 — native Search and keyboard
The on-screen D-pad keyboard; results list with icons. Ranking goes straight through `RustCore.prepareSearch`/`search`
from Java on a worker thread, with no JS involved. The snapshot builder moves from `registry.js` to Java, and the
existing parity fixtures are reused as the contract. Rust-load failure needs a Java fallback ranking, or a
"search unavailable" state; that decision is made at this stage.

## Stage 4 — switch HOME, then remove the WebView
1. `MainActivity` routes to the native UI behind a setting, defaulting to native once Stages 1–3 pass. The WebView UI stays selectable as the fallback for one release.
2. After a release with no regressions, remove the WebView UI, its assets, `GlassBridge` and the JS ranking fallback.

## Invariants for every stage
`com.glasslauncher`, HOME behaviour, permissions, SDK levels, `armeabi-v7a` and `arm64-v8a`, and the Phase 7 1920×1080 at
320 dpi layout all stay as they are. No disk, PackageManager or JNI work on the UI thread, and no allocation in `onDraw`.
Every stage ships with screenshots from the TV-profile emulator and an owner remote checklist.
