# Glass Launcher — Android 11 port

This project ports the supplied Tizen web launcher to an Android TV HOME activity while retaining the supplied HTML/CSS, wallpapers, icons, sound effects, tile layout, clock/date, light/dark schedule, moon/sun toggle, and lite performance setting.

## Build
1. Open this `GlassLauncher` folder in Android Studio (recent version).
2. Allow Gradle sync and install Android SDK Platform 35 if prompted.
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. APK output: `app/build/outputs/apk/debug/app-debug.apk`.

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
