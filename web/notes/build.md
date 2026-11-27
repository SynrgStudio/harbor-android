---
title: Stack and build
status: stable
updated: 2026-11-27
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
| Background | foreground services for downloads and for playing on a TV; JobScheduler for the new-episode check |
| TV | Google Cast library 22.1 (Chromecast); DLNA written in the app (SSDP + UPnP AVTransport); a small HTTP server on the phone's Wi-Fi address |
| Widget | plain RemoteViews |
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

JVM unit tests (`unit_tests`) check Stremio's watched-marks format against stremio-core's own examples
(`stremio.md`), the subtitle reader (SRT, WebVTT, ASS, old encodings), the intro learning, and playing on a TV: the
phone's server answering byte ranges and proxying, and a whole DLNA conversation with a pretend TV. The Baseline
Profile run exercises the release build on every regeneration.

For trying TV playback and series features without real ones, `scripts/fake-tv.py` is a pretend DLNA TV on the PC
and `scripts/demo-addon.py --series` answers any episode of a series with an open movie.
