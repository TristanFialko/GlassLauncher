# Stage 4a device runbook — Arris VIP7802 (Android 11, 1920x1080 @ 320 dpi)

This is the exact owner procedure for Stage 4a. Nothing in it has run on the VIP7802 yet. Every "Expected" line below is what
the code and CI predict, not something seen on the box. If the box shows something different, stop, capture (section 9) and report it.

Scope: Stage 4a only. The WebView UI stays the default HOME and stays in the APK. Native HOME is an opt-in you can undo with one
command. Do not start Stage 4b or 4c from this runbook.

Commands are for Windows CMD on the PC, with `adb` on PATH and the box already paired (`adb connect <box-ip>:5555`).
**Destructive commands** (uninstall, downgrade, clearing app data) are marked ⚠ and are only for recovery. Run them only after you
have decided to.

## 0. Get the build under test

1. Open PR #5's latest **green** CI run (Checks tab → "CI" → the run for the head commit) and download the artifact
   `glasslauncher-debug-apk`. Unzip `app-debug.apk` to `C:\glass\glass2\app-debug.apk`.
2. From the same run, download `glasslauncher-baseline-1.5-debug-apk` to `C:\glass\baseline\` (this is the recovery APK).
3. Check the APK contains the 32-bit Rust library (the VIP7802 runs `armeabi-v7a`):

   ```
   tar -tf C:\glass\glass2\app-debug.apk | findstr libglass.so
   ```

   Expected: `lib/armeabi-v7a/libglass.so` and `lib/arm64-v8a/libglass.so`.

## 1. Recovery route (read before installing)

- Back to the WebView UI (the HOME entry point is unchanged; this always works if the app starts at all):

  ```
  adb shell am start -W --activity-clear-task -n com.glasslauncher/.MainActivity --es glass.ui webview
  ```

- If the 2.0 app will not start at all: ⚠ reinstall 1.5 (downgrade from versionCode 7 to 6 keeps data):

  ```
  adb install -r -d C:\glass\baseline\app-debug.apk
  ```

- If even that fails: ⚠ `adb uninstall com.glasslauncher` (the stock launcher then takes HOME), then reinstall.
- You can always open stock Settings with `adb shell am start -a android.settings.SETTINGS`.

## 2. Install and confirm identity

```
adb install -r C:\glass\glass2\app-debug.apk
adb shell dumpsys package com.glasslauncher | findstr /C:"versionName" /C:"versionCode" /C:"primaryCpuAbi"
adb shell run-as com.glasslauncher cat files/ui-mode.properties
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Expected:
- `Success` from the install.
- `versionName=2.0.0-dev`, `versionCode=7`, `primaryCpuAbi=armeabi-v7a`.
- `ui-mode.properties` is missing ("No such file") or shows `mode=webview`.
- HOME resolves to `com.glasslauncher/.MainActivity` (it was already Glass with 1.5). If it resolves to the stock launcher, press
  HOME on the remote and choose Glass Launcher → Always. Do not change anything else.

Press HOME. Expected: the familiar WebView UI. Nothing native yet.

## 3. Rust core on the device

```
adb logcat -c
adb shell am start -n com.glasslauncher/.NativeHomeActivity
adb logcat -d -s GlassRust GlassNative GlassUiRouter AndroidRuntime DEBUG
```

Expected: no `UnsatisfiedLinkError` and no `AndroidRuntime FATAL EXCEPTION`. Any fatal line or tombstone (`DEBUG` tag,
`signal 11`/`signal 4`) means `libglass.so` (armeabi-v7a) fails on this SoC. In that case stop and capture.

## 4. Native UI checklist (opt-in view, WebView is still HOME)

The command in section 3 opens the native UI as a normal activity. Back leaves it and returns to the WebView HOME. Check each item
and note pass or fail:

| # | Check | Expected |
|---|---|---|
| 1 | D-pad over the dock, Recently Used and header | Focus ring moves one item per press, never lost, wraps as in the WebView UI |
| 2 | OK on an app (e.g. YouTube) | App opens; Back returns to the native UI with focus on the same tile |
| 3 | Pointer/air-mouse (if paired) hover and click | Hover highlights, click launches |
| 4 | Search: open, type with the on-screen keyboard, D-pad results, launch | Results update per key; Back closes OSK first, then Search |
| 5 | Control Centre: open, volume, mute, Wi-Fi, Bluetooth, audio output, device tiles | Values match `adb shell dumpsys audio` / system Settings |
| 6 | Settings shortcuts in the Control Centre | Each opens the right system Settings screen, or shows a toast if the box lacks it (no crash) |
| 7 | Theme and wallpaper switch | Applied immediately and still applied after Back and reopen |
| 8 | Toasts | Visible, readable, disappear by themselves |
| 9 | Empty/error states (uninstall nothing; just open Recently Used after a reboot or with no history) | Readable empty state, no blank screen |
| 10 | Back from the native UI | Returns to the WebView HOME (because native is not HOME yet) |

Take one screenshot per screen:

```
adb exec-out screencap -p > C:\glass\shots\native-home.png
```

## 5. A/B benchmark (fixed order: web-1, native-1, web-2, native-2)

Run each one to completion before the next. Same box state for all four: no other app in the foreground, same network.

```
tools\bench\bench-device.bat -Label web-1    -Apk C:\glass\glass2\app-debug.apk -CompileSpeed -Latency -Runs 5 -IdleSeconds 30 -NavRounds 10 -BackgroundPackage com.google.android.youtube.tv
tools\bench\bench-device.bat -Label native-1 -Apk C:\glass\glass2\app-debug.apk -CompileSpeed -Latency -Runs 5 -IdleSeconds 30 -NavRounds 10 -BackgroundPackage com.google.android.youtube.tv -Activity .NativeHomeActivity
tools\bench\bench-device.bat -Label web-2    -Apk C:\glass\glass2\app-debug.apk -CompileSpeed -Latency -Runs 5 -IdleSeconds 30 -NavRounds 10 -BackgroundPackage com.google.android.youtube.tv
tools\bench\bench-device.bat -Label native-2 -Apk C:\glass\glass2\app-debug.apk -CompileSpeed -Latency -Runs 5 -IdleSeconds 30 -NavRounds 10 -BackgroundPackage com.google.android.youtube.tv -Activity .NativeHomeActivity
```

Expected in each run's console output: `Build under test: … has a native library (armeabi-v7a)`, five cold starts, and no
`FATAL`. Keep each run's whole output folder. The bench launches `.NativeHomeActivity` directly (not as HOME), so these runs never
touch the crash fallback budget.

## 6. Opt into native HOME

```
adb shell am start -W -n com.glasslauncher/.MainActivity --es glass.ui native
adb shell run-as com.glasslauncher cat files/ui-mode.properties
```

Expected: `mode=native`, `crashes=` empty. Press HOME: the native UI appears. Wait at least 10 s, then:

```
adb shell run-as com.glasslauncher ls files
```

Expected: no `native-start.pending` (the start confirmed itself healthy after 5 s). Repeat checklist items 1, 2 and 4 with native
as HOME, then launch an app and press HOME. You should come back to the native UI.

## 7. Crash fallback (safe: it ends on the WebView UI, which is the default anyway)

There are two independent paths. Test both, and re-opt-in between them (section 6, first command) to reset the budget.

**7a. JVM exception path (uncaught-exception handler).**

```
adb shell pidof com.glasslauncher:native
adb shell am crash <pid-from-above>
```

Press HOME, wait for the native UI, then run both commands again with the new pid. After the second crash (within 10 min), press HOME.
Expected: the WebView UI, and `ui-mode.properties` shows `mode=webview`, two `crashes=` timestamps, and
`fallback=native UI crashed 2x within 10 min (<exception class>)`. Assumption: `am crash <pid>` exists on this Android 11 build.
If it prints "Unknown command", skip to 7b.

**7b. Unclean-exit path (signal or kill; the handler never runs). This is new in this commit.**
The kill must happen **within 5 s** of the native UI starting as HOME, otherwise that start already counts as healthy:

```
adb shell am start -W -n com.glasslauncher/.MainActivity --es glass.ui native
adb shell "run-as com.glasslauncher sh -c 'kill -9 $(pidof com.glasslauncher:native)'"
```

Then, twice: press HOME (or let Android relaunch it) and immediately run the `kill -9` line again. On the start after the second
unclean exit, expected: the native UI hands HOME straight back to the WebView UI, `ui-mode.properties` shows `mode=webview` and
`fallback=native UI crashed 2x within 10 min (UncleanExit)`, and logcat (`-s GlassUiRouter`) shows
`native UI did not start cleanly; HOME falls back to the WebView UI`.

If you cannot get the kill in within 5 s, you can simulate an unclean exit instead. Plant the marker, then press HOME twice:

```
adb shell "run-as com.glasslauncher sh -c 'echo $(date +%s)000 > files/native-start.pending'"
```

That counts one unclean exit per planted marker, so plant one, press HOME, wait for the native UI, quickly plant a second one, and
press HOME again.

After section 7, opt back into native HOME (section 6) if you want to continue with section 8.

## 8. Native HOME across one reboot (last step)

```
adb reboot
```

Wait for the box to boot, then press nothing. Expected: the native UI comes up as HOME, and `ui-mode.properties` still shows
`mode=native` with no new crash. Use it for normal TV watching. Stage 4b needs this plus about one week without a fallback.

To stop at any time, run the WebView command in section 1.

## 9. Capture (after any failure, and once at the end)

```
adb logcat -d -v time > C:\glass\logs\logcat.txt
adb shell run-as com.glasslauncher cat files/ui-mode.properties > C:\glass\logs\ui-mode.txt
adb shell dumpsys activity activities | findstr /C:"mResumedActivity" > C:\glass\logs\resumed.txt
adb bugreport C:\glass\logs\bugreport.zip
```

The bugreport contains tombstones for native signal crashes.

## Known residual risk

- If the native UI hangs **after** its first 5 s, an ANR is not counted. Android shows "isn't responding"; choose Close, then run
  the WebView command in section 1.
- If the system kills the native process within 5 s for memory reasons (not a bug), that counts as one unclean exit. You need two
  within 10 min to trigger a fallback, and opting in again resets the budget.
