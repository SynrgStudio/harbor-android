---
title: Stack and build
status: stable
updated: 2026-11-26
---

## Stack

| Part | What |
| --- | --- |
| Language | Kotlin 2.2 (`kotlin_files` files, `kotlin_lines` lines), Rust for the torrent engine glue (`rust_glue_lines` lines) |
| UI | Jetpack Compose with Material 3, Navigation Compose, Inter typeface |
| Player | Media3 1.9 (ExoPlayer) + Jellyfin's FFmpeg audio decoder |
| Torrents | librqbit 9.1, built with the Android NDK for arm64-v8a and x86_64, driven over its HTTP API |
| Network | OkHttp 5, kotlinx.serialization |
| Images | Coil 3, with a 400 MB disk cache |
| Blur | Haze (the translucent tab bar) |
| Storage | small JSON files (`core/JsonStore.kt`), no database |
| Background | a foreground service for downloads; JobScheduler for the new-episode check |
| Startup | Baseline Profile generated with Macrobenchmark + UI Automator, shipped in the APK |
| Target | Android 10 (API 29) to Android 16 (API 36) |

No Room, Hilt, Dagger or KSP: no annotation processing in the build.

## Building

Scripts in `scripts/` (PowerShell on Windows), documented in `docs/DEVELOPMENT.md`:

- `build-native.ps1` builds the Rust engine for both architectures.
- `run.ps1` builds, installs on a phone over wireless debugging or on the emulator, and opens the app; with
  `-Release` it also compiles the Baseline Profile on the device.
- `baseline-profile.ps1` regenerates the profile on the emulator; it is also a smoke test of the release build.
- `release.ps1` builds both signed APKs, checks they aren't signed with the debug key, tags the version and
  publishes the GitHub release.
- `jank.sh` measures frames on a phone (`performance.md`).

## Tests

JVM unit tests check Stremio's watched-marks format against stremio-core's own examples (`stremio.md`). The
Baseline Profile run exercises the release build on every regeneration.
