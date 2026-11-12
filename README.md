# Glass Launcher — Android 11 port

This project ports the supplied Tizen web launcher to an Android TV HOME activity while retaining the supplied HTML/CSS, wallpapers, icons, sound effects, tile layout, clock/date, light/dark schedule, moon/sun toggle, and lite performance setting.

## Glass 2.0 migration (in progress)
Search ranking now runs in a small Rust core (`rust/`, packaged as `libglass.so`) behind the same WebView UI; app discovery
and background behaviour were optimised in Java. Status, evidence and rollback: `MIGRATION_STATUS.md`. Architecture and
benchmarks: `docs/`. Full build guide: `docs/BUILDING.md`.

## Build
1. Open this `GlassLauncher` folder in Android Studio (recent version).
2. Allow Gradle sync and install Android SDK Platform 35 if prompted.
3. Install **NDK 27.2.12479018** (SDK Manager → SDK Tools → NDK (Side by side)) and **Rust** via [rustup](https://rustup.rs); the pinned Rust version and Android targets install themselves on the first build. (Or add `glass.rust=false` to `gradle.properties` to build without the Rust core.)
4. Build > Build Bundle(s) / APK(s) > Build APK(s).
5. APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Install on the ARRIS VIP7802
From Windows Command Prompt (outside `adb shell`):

```bat
adb install -r app-debug.apk
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```
Choose Glass Launcher and select Always if Android offers a HOME app chooser. If no chooser appears, open Settings > Apps > Default apps > Home app and select Glass Launcher.

## Notes
- Du TV (`DU.TV.PRODA`) is not removed or disabled. Keep it as a fallback.
- Projectivy remains installed as another fallback.
- The app uses Android package IDs for YouTube, Netflix, Prime Video, Disney+, and Spotify. Netflix and Spotify IDs may differ on a particular operator firmware; missing apps show a toast rather than crashing.
- The source archive is provided because this environment has no Android SDK/Gradle installation to produce and test a signed APK here.
- The HTML/CSS design and original assets are retained. The Tizen-only application API has been replaced by a small Android JavaScript bridge.
