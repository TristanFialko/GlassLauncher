# Building Glass Launcher

## Requirements
| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | Gradle's daemon is pinned to 21 (`gradle/gradle-daemon-jvm.properties`) and is downloaded automatically if missing. Android Studio's bundled JBR 21 also works. |
| Android SDK | platform 35, build-tools 34+ | via Android Studio SDK Manager or `sdkmanager`. |
| Android NDK | 27.2.12479018 | `sdkmanager "ndk;27.2.12479018"` (SDK Manager → SDK Tools → NDK (Side by side) → 27.2.12479018). Only the linker is used. |
| Rust | 1.91.0 | Install [rustup](https://rustup.rs). The pinned toolchain and the two Android targets are installed automatically from `rust/rust-toolchain.toml` on the first build. |
| Node.js | 18+ | Only for `tools/parity` (search parity check). |

No signing keys are needed for debug builds. Never commit `local.properties`, keystores or passwords (`.gitignore` covers them).

## Windows
```bat
rem one-time
winget install Rustlang.Rustup
rem (install Android Studio, then SDK Manager -> NDK 27.2.12479018)
echo sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk> local.properties

gradlew.bat assembleDebug
rem APK: app\build\outputs\apk\debug\app-debug.apk  ->  install-to-box.bat
```
Rust on Windows uses the MSVC host toolchain by default; rustup offers to install the Visual Studio Build Tools if they are
missing (needed only for the host-side unit-test library, not for the Android libraries, which link with the NDK's clang).

## Linux / macOS
```sh
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

## Useful tasks and switches
| Command | Purpose |
|---|---|
| `gradlew assembleDebug` / `assembleRelease` | APKs with `libglass.so` for `armeabi-v7a` + `arm64-v8a` (release is unsigned unless you add a signing config locally). |
| `gradlew testDebugUnitTest` | JVM tests, including real JNI calls into a host build of `libglass`. |
| `gradlew connectedDebugAndroidTest -Pglass.rustAbis=x86_64` | Instrumented tests on an x86_64 emulator (`rustup target add x86_64-linux-android` first). |
| `-Pglass.rust=false` | Build without Rust; the app uses its original JavaScript search (behaviour identical, the APK is ~0.59 MB smaller). Run `clean` after toggling it, or a stale `libglass.so` can stay in the APK. |
| `-Pglass.rustAbis=armeabi-v7a` | Only 32-bit ARM (if the box turns out to be 32-bit only). |
| `cd rust && cargo fmt --all -- --check && cargo clippy --all-targets -- -D warnings && cargo test` | Rust checks (same as CI). |
| `cargo build --release -p glass-cli --manifest-path rust/Cargo.toml && node tools/parity/search-parity.js --bench` | Search parity (registry.js vs Rust) and host benchmark. |
| `tools\bench\bench-device.bat -Label <name> -Apk <apk>` | On-device benchmark over adb (see `docs/benchmarks/BASELINE.md`). |

## Rollback
- Source: tag `glasslauncher-1.5-phase7-baseline` (= `main` before the migration).
- APK: CI artifact `glasslauncher-baseline-1.5-debug-apk` on every run, or build the tag (`gradlew assembleDebug` works there; its release build needs JDK 21 for lint).
- Feature switch: `window.PERF = {lite:true, core:'js'}` in `apps.js` (rebuild + reinstall) forces the original JavaScript search while keeping the other Glass 2.0 changes.
