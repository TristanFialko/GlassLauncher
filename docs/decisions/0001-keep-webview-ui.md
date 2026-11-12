# ADR 0001 — Keep the WebView UI (Path A) for the core migration

Date: 2026-11-12 · Status: accepted (revisit at the Phase 6 gate)

## Context
The brief asks whether HTML/WebView is the bottleneck. No Android device or emulator was available in the migration
environment (no `/dev/kvm`, no hardware), so no rendering profile exists yet. Static inspection
(`CURRENT_ARCHITECTURE.md`) found concrete non-rendering costs: per-package Binder calls at boot, synchronous bridge calls
for icons and Control Centre polling, per-keystroke re-normalisation in search, timers running while the launcher is hidden.

## Decision
Keep the existing HTML/CSS/JS UI unchanged. Move logic to Rust behind the existing bridge, fix measured-or-countable
platform costs in Java, and evaluate native rendering only when the device benchmark (`tools/bench`) shows frame pacing
or WebView compositing to be the dominant cost.

## Consequences
- Appearance is unchanged by construction (no CSS/HTML/asset edits in the migration so far).
- Spatial focus navigation stays in JS: it needs live DOM rectangles, and moving it would add a bridge round trip per key press.
- A native-UI rewrite remains possible later but needs device evidence first.
