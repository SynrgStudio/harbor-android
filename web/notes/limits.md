---
title: Known limits
status: stable
updated: 2026-11-27
---

What it doesn't do today. None of these are promises; they are here so the page doesn't claim more than it does.

- **Android phones only.** No Android TV interface, no iOS. Chromebooks and emulators can run the x86_64 APK, but
  the interface is designed for a phone.
- **Not on an app store.** It is installed from GitHub (or through Obtainium) and updates itself.
- **Three languages:** English, Spanish and Brazilian Portuguese.
- **Playing on a TV** ends when the player is closed; it doesn't continue to the next episode on the TV, and a
  subtitle track inside the video file isn't sent (subtitles from addons and downloads are). Chromecast needs Google
  Play services on the phone. What plays depends on the TV: many can't play every container or codec (MKV, HEVC,
  DTS…).
- **Where to watch** needs a personal TMDB key.
- **Skip intro** needs one skip by hand per series first, and it jumps a fixed length.
- **Filmographies without a TMDB key** come from the addon's search and Wikidata, which can miss a title or list
  something odd.
- **External streams** (addons that link to a website) are listed but can't be played.
- **Nothing to play without addons:** the app ships none.
- **Performance numbers are from 0.2.0** (`performance.md`); later versions were not measured again.
- **Torrents need the engine:** on a phone where the native engine can't start, torrent streams are shown but
  disabled; direct links still play.
