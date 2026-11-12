# ADR 0002 — Rust integration: Gradle task + NDK clang, `jni-sys`, UTF-8 byte arrays

Date: 2026-11-12 · Status: accepted

## Options considered
| Option | Verdict |
|---|---|
| `cargo-ndk` | Extra tool every developer/CI must install; it only sets the same linker env vars we set. Not needed. |
| CMake/`externalNativeBuild` + Corrosion | Adds CMake and a C++ layer for no benefit. |
| `jni` crate (0.21) | Convenient, but pulls several transitive crates; we need four functions. |
| UniFFI | Generates Kotlin by default, adds a runtime and codegen step; the brief says only if it materially helps. It does not here. |
| **Gradle task calling `cargo` with the NDK clang as linker + `jni-sys` (0 transitive deps)** | **Chosen.** Small, transparent, works on Windows/Linux/macOS (`.cmd` linker on Windows). |

## Decision details
- Strings cross JNI as UTF-8 `byte[]` (no modified UTF-8, no per-char JNI).
- `catch_unwind` on every entry point; `panic = "unwind"` kept in the release profile so panics are catchable.
- The native library is optional at runtime: `RustCore` reports unavailable and the JS paths run (`-Pglass.rust=false` builds without it).
- Output is wired through the AGP variant API (`addGeneratedSourceDirectory`), not by editing `jniLibs.srcDirs`, so Gradle knows the task dependency.

## Consequences
- Building the APK now requires Rust 1.91.0 (auto-installed by rustup from `rust/rust-toolchain.toml`) and NDK 27.2.12479018, or `-Pglass.rust=false`.
- APK size grows by the native libraries (see RESULTS.md); the library is dominated by Rust `std`, `opt-level=s/z` saved only ~10 KB, so speed-optimised builds were kept.
