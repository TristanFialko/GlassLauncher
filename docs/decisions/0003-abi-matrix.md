# ADR 0003 — ABI matrix: armeabi-v7a + arm64-v8a

Date: 2026-11-12 · Status: accepted, to be confirmed on the ARRIS VIP7802

## Context
The VIP7802's ABI list could not be read in the migration environment (no device). Many Android TV set-top boxes run a
32-bit userspace on 64-bit ARM cores, and the brief forbids dropping 32-bit ARM on assumption.

## Decision
Ship `armeabi-v7a` and `arm64-v8a` in one universal APK (minSdk 23 linker). `x86_64` is built only for the CI emulator
test (`-Pglass.rustAbis=x86_64`). No ABI splits yet: a single sideloaded APK is simpler to install with `install-to-box.bat`,
and the combined native size is ~0.57 MB.

## To confirm on device
`adb shell getprop ro.product.cpu.abilist` and `dumpsys package com.glasslauncher | findstr primaryCpuAbi`
(both recorded by `tools/bench/bench-device.ps1`). If the box is 32-bit only, `-Pglass.rustAbis=armeabi-v7a` saves ~0.32 MB.
