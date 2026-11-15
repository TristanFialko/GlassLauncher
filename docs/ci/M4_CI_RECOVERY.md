# M4 CI recovery: runs #49 and #50 (Foojay plugin resolution)

_2026-11-15 · branch `glasslauncher-2.0-full-native` · PR #5 (draft, unmerged, base `glasslauncher-2.0-consolidated`)_

## Symptom

| Run | Commit | Failed job | Other jobs |
|---|---|---|---|
| [#49 43734506381](https://github.com/TristanFialko/GlassLauncher/actions/runs/43734506381) | `2195d8f` (M4 code) | Android build + JVM/JNI tests | passed |
| [#50 43735105466](https://github.com/TristanFialko/GlassLauncher/actions/runs/43735105466) | `7394582` (M4 docs) | Instrumented test (x86_64 emulator, API 30) | 5 of 6 passed, **including Android build + JVM/JNI tests** |

Both failures stop in settings evaluation, at `settings.gradle` line 2:
`Plugin [id: 'org.gradle.toolchains.foojay-resolver-convention', version: '1.0.0'] was not found` (searched Google,
MavenRepo and the Gradle Central Plugin Repository).

## Root cause

The plugin marker fetch from the Gradle Plugin Portal failed while settings were evaluated in the affected jobs. It was
not a change in the repository:

- `settings.gradle` is byte-identical at green `5f26378` (run #48, all six jobs passed) and at `7394582` (blob `5718b69`).
- `2195d8f` changed only Kotlin sources and JVM tests (`NativeHomeActivity`, `NativeSearchFacts`, `SettingsLauncher`,
  their tests, `UiRouterTest`). `7394582` changed only `MIGRATION_STATUS.md` and `docs/nativeui/SEARCH.md`. Neither
  touched Gradle files, the wrapper (Gradle 9.6.0), `gradle/gradle-daemon-jvm.properties` or `.github/workflows/ci.yml`.
- A config error would fail every Gradle job on that commit. In run #50 the Android build job and the Native UI emulator
  job resolved the same plugin from the same `settings.gradle` and passed, and only the instrumented emulator job failed.
  The failing job changed between runs (#49 build job, #50 emulator job). The failure is non-deterministic and per job.

Limit of this diagnosis: the agent cannot read GitHub Actions job logs, so the exact HTTP status from the plugin
repository (timeout, 5xx or rate limit) is not recorded here.

## Is Foojay needed?

Not at build time in CI. No project uses a Java/Kotlin toolchain block (`app/build.gradle` sets only `compileOptions` 17
and `jvmTarget` 17). `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=21`) already contains the generated
`toolchainUrl.*` entries, and CI installs Temurin 21 with `actions/setup-java`. The plugin is used when
`./gradlew updateDaemonJvm` regenerates those URLs and for daemon JVM provisioning on machines without a JDK 21. It is
kept so that developer workflow is unchanged. Removing it is only worth doing if this failure recurs.

## Fix

No build, workflow or application change. There is no `continue-on-error`, no retry wrapper, no mirror and no test
change. This commit only records the evidence and starts a fresh full CI run on a new head, which replaces a rerun of
the failed jobs. The PR #5 checks on this commit are the verification. Device verification: **not performed** (Stage 4a
VIP7802 checklist in `MIGRATION_STATUS.md` is still pending).
