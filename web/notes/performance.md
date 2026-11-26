---
title: Performance, and how it was measured
status: stable
updated: 2026-11-26
---

## The numbers

On the phone in `[phone]` (Samsung Galaxy A54, Android 16, 120 Hz), release builds, before and after the first
round of work (0.1.0 → 0.2.0):

| | 0.1.0 | 0.2.0 |
| --- | --- | --- |
| Cold start to first frame | `startup_ms_before` ms | `startup_ms_min`–`startup_ms_max` ms |
| Janky frames while scrolling Home | `janky_pct_before` % | `janky_pct` % |
| Frame time, 90% of frames | `frame_p90_ms_before` ms | `frame_p90_ms` ms |
| Frame time, worst 1% | `frame_p99_ms_before` ms | `frame_p99_ms_min`–`frame_p99_ms_max` ms |

At 120 Hz a frame has about `frame_budget_ms` ms. Later versions added features on top of 0.2.0 and were not
measured again, so these are the numbers of 0.2.0, not of the current release.

## How they were taken

- **Startup:** `adb shell am force-stop`, then `adb shell am start -W`, reading TotalTime (from launch to the first
  frame drawn), three runs.
- **Frames:** `scripts/jank.sh` in the repo. It cold-starts the app, resets Android's frame statistics, scrolls Home
  with a fixed script (four swipes down, three sideways through a row, four back up) and reads `dumpsys gfxinfo`:
  frames rendered, janky frames and frame time percentiles.
- Only release builds count: Compose in a debug build is several times slower and says nothing about the real app.

## What made the difference

- **Baseline Profile:** a scripted walk through the app (cold start, scrolling Home, opening a title, switching
  tabs) records which code runs, and the list ships in the APK so Android compiles it ahead of time when the app is
  installed. Without it, a sideloaded app runs interpreted until Android gets around to compiling it, which it only
  does while the phone is idle and charging. The same script doubles as a smoke test of every release build.
- **Blur at half resolution:** the translucent tab bar blurs whatever scrolls under it, every frame, on the GPU. It
  was the main cost while scrolling; rendering the blur at half resolution looks the same on a dark bar.
- **Home opens full:** it draws the last cached rows immediately and refreshes behind them.
- **Title pages open instantly** with what the poster already knew, and fill in as data arrives.
- **The top bar no longer redraws on every frame of a scroll.**
- **Images:** decoded at the size they're shown, with memory and disk caches (`HarborApp.kt`).
- **Startup work off the main thread:** stores are loaded in the background so the first frame never waits on disk.
- **No database or dependency-injection framework** to initialize (`decisions.md`).
