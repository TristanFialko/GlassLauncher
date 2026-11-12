# Native UI: staged migration plan

Decision rule: **switch users to the native UI only on VIP7802 evidence.** Code may be written ahead of the device gates
(the owner's 2026-11-12 brief asked for native parity work to continue while the TV is unavailable), but no step that
changes what the box shows by default happens until the gate before it is met with owner-run data (harness v5,
alternating runs, medians and spread). Emulator numbers never satisfy a gate.

## Where we are (2026-11-12, end of day)

| Stage | Code | Device gate |
|---|---|---|
| 0 Measure W/N | harness v5 ready (`-Activity`, `-Latency`) | **pending** (TV unavailable) |
| 1 Native Home | done (Kotlin) | pending: owner remote checklist |
| 2 Native Control Centre | done | pending |
| 3 Native Search + keyboard | done (Rust ranking direct from Kotlin; Kotlin fallback with parity tests) | pending |
| 4a Native as HOME, opt-in | done: `UiRouter` (`--es glass.ui native`), crash fallback | pending: reboot/crash recovery on the box |
| 4b Native as HOME by default | not started | after 4a passes |
| 4c Remove WebView UI | inventory ready (`MIGRATION_STATUS.md`) | after one release of 4b with no regressions |

- Branch (2026-11-13): work continues on `glasslauncher-2.0-consolidated`, created from `glasslauncher-2.0-migration` at
  `3522999` (PR #2 stays open on the old branch). CI at `3522999` (run 43303322890): all six jobs green, including both emulator jobs.

- Audit branch (2026-11-13): `glasslauncher-2.0-native-audit` from `f01f4637` adds the measured LOC split, the WebView
  inventory and removal matrix (`WEBVIEW_INVENTORY.md`, `WEBVIEW_REMOVAL_MATRIX.md`) and a frozen JS ranking golden file
  checked against Kotlin and Rust. No stage or gate changed.

- WebView UI on the box: D-pad focus animation at p50 61 ms per frame (~16 fps), 96.65 % over budget — from the smoke run, which was not a 2.0 build. One run; indicative only.
- Native UI: exercised on a 1080p TV emulator (renders, focus, rows, Search open/type/close, Control Centre, theme, toast, HOME routing). Software GPU: says nothing about the box.

## Stage 0 — measure (owner, ~1 h)
Alternate WebView and native runs of the same Glass 2.0 APK: W, N, W, N (commands in `MIGRATION_STATUS.md`). Compare per UI:
- nav frame time p50/p95 and jank against 16.68 ms (59.94 Hz);
- key→frame p50/p95 (native exact; WebView a lower bound);
- cold and warm start;
- PSS: app plus renderer for the WebView UI, app only for native;
- foreground and background CPU.

**Go (all of these):**
- native nav frame time p50 ≤ 25 ms, or jank ≤ 20 %;
- native key→frame p50 lower than the WebView's lower bound;
- native PSS ≤ WebView app+renderer PSS;
- cold start not more than 20 % slower.

**No-go:** keep the WebView as HOME. Profile the native UI with `-Trace` (Perfetto sections `Glass:native*`) and fix
the measured hot spot (candidates: the dock glass compositing, artwork bitmap sizes, the Search dimming layer). If the
WebView turns out competitive, profile it too, starting with the docks' backdrop blur.

## Stages 1–3 — parity (code done; device check pending)
Exit for each: every row for that surface in `PARITY_CHECKLIST.md` §5 passes the owner's remote checklist on the box,
with no crash and no visible stutter. Known intentional differences are listed in `PROTOTYPE.md` and `SEARCH.md`.

## Stage 4 — HOME and removal
**4a (opt-in HOME):** `adb shell am start -W -n com.glasslauncher/.MainActivity --es glass.ui native`. Go: native comes
back as HOME after `adb reboot` and after pressing Home from an app; crashing the native process twice within 10 minutes
(`adb shell pidof com.glasslauncher:native`, then `adb shell am crash <pid>`; untested on the box) returns the WebView UI; `--es glass.ui webview` switches back.

**4b (default):** flip `UiRouter`'s default to native for one release; the WebView UI stays selectable with
`--es glass.ui webview`. Go: a week of normal use with no regressions reported.

**4c (removal):** delete per the inventory in `MIGRATION_STATUS.md` (WebView setup, `GlassBridge`, WebView icon serving,
`index.html`/`js`/`css`/`apps.js`, JS ranking, `GlassEndToEndTest`); make `NativeHomeActivity` the HOME activity
directly (main process, not exported beyond HOME), drop `UiRouter`. Verify the release APK has no `assets/js`,
`assets/css`, `index.html` and no `android.webkit` references (`apkanalyzer dex packages`), re-run the full checklist.

## Rollback (every stage)
- On the box: `adb shell am start -W --activity-clear-task -n com.glasslauncher/.MainActivity --es glass.ui webview`; automatic after 2 native crashes in 10 minutes.
- APK: `glasslauncher-baseline-1.5-debug-apk` (needs `adb install -r -d`, or uninstall first: 2.0 is versionCode 7).
- Source: tag `glasslauncher-1.5-phase7-baseline`.

## Invariants for every stage
`com.glasslauncher`, HOME behaviour, permissions, SDK levels (min 23, target 30), `armeabi-v7a` and `arm64-v8a`, and the
Phase 7 1920×1080 at 320 dpi layout all stay as they are. No disk, PackageManager or JNI work on the UI thread, and no
allocation in `onDraw`. Every stage ships with TV-profile emulator screenshots (CI artifact `native-ui-prototype`) and an
owner remote checklist.
