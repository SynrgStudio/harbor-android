---
title: Why it is built the way it is
status: stable
updated: 2026-11-27
---

The choices that shaped the app, each with the reason. Numbers referenced here are facts in `facts.toml`.

## Native, written for phones

Kotlin and Jetpack Compose, written from scratch for Android rather than wrapping a desktop or web interface.

- **Why:** a phone app lives or dies by scrolling and touch. Rows of posters have to scroll at the display's full
  rate (120 Hz on the test phone, so about `frame_budget_ms` per frame), gestures have to respond under the finger,
  and the app has to use what Android offers: picture-in-picture, background downloads with a notification,
  launcher shortcuts, per-app language. A native app gets all of that directly.

## The Stremio account is the backend

There are no Harbor accounts and no Harbor servers. Signing in uses the user's Stremio account, and the app reads
and writes the same data Stremio does (`stremio.md`).

- **Why:** people already keep their addons, progress and library there, and Stremio and Harbor desktop already
  read it. Using it means nothing to migrate, nothing to run, and a movie paused on the phone continues on the
  computer.
- **Watched marks use Stremio's own format** (a compressed bitfield over the episode list), checked by unit tests
  against stremio-core's own examples, so marks set on the phone land on the same episodes in Stremio.

## No stream addons included

The app adds exactly one addon by itself, OpenSubtitles v3 (subtitles only, the same one every new Stremio account
has). Stream sources come only from the user: from their Stremio account, by URL, or through a `stremio://` link.

- **Why:** Harbor is a client. What it plays is whatever the user's own addons return; the app doesn't choose,
  recommend or ship sources.

## Metadata with nothing to set up

Titles, artwork, logos, cast and episode lists come from TMDB through its public Stremio addon, which needs no key.
A personal TMDB key in Settings is optional and switches to the TMDB API directly, which adds age ratings and
TMDB's recommendations. Both modes use TMDB ids, so progress and the list don't change when switching.

- **Why:** most apps that use TMDB either make the user register for a key or ship one inside the APK. A key in an
  APK can be pulled out and abused by anyone, and asking a new user to register before seeing anything loses them.
  The APK carries no API keys at all (`api_keys_in_apk`).

## The same torrent engine as the desktop app, behind a small door

Torrents play through librqbit, the Rust engine Harbor desktop also uses, compiled for Android (arm64-v8a and
x86_64). Kotlin calls into Rust only to start the engine (`rust_glue_lines` lines of Rust); everything else (adding
a torrent, picking the file, limits, stats) goes through librqbit's own HTTP API on 127.0.0.1.

- **Why:** a wide JNI surface is where native crashes and hard-to-debug bugs live. With an HTTP API in between, the
  player plays a torrent from a local URL exactly like any other stream, and the Rust side stays tiny.
- **Many torrent results come with no trackers;** the app fills in a list of public ones, as Stremio does.
- **Reopening a torrent later** reuses what was already downloaded.

## FFmpeg for the audio phones can't decode

The player is Media3 (ExoPlayer) with Jellyfin's FFmpeg audio decoder, so DTS and TrueHD tracks, common in movie
files, play instead of going silent.

- **Consequence:** that decoder is GPL-3.0, so Harbor for Android is GPL-3.0-or-later.

## Small files instead of a database

Settings, progress, the list, the calendar and downloads are small JSON files, each loaded into memory and written
back in the background (`core/JsonStore.kt`). No Room, no Hilt, no annotation processing.

- **Why:** the data is small (a few hundred titles at most), so a database adds startup work and build complexity
  without buying anything. Builds are faster and there is less code running before the first frame.

## Mobile data is the user's money

Off Wi-Fi the player asks before playing, and torrents keep downloading but upload at `metered_upload_kib_s` KiB/s,
the smallest limit librqbit accepts without dropping requests. Downloads are Wi-Fi only unless the user changes it.

- **Why:** a torrent uploads as much as it downloads if you let it. On a data plan that is invisible until the bill.

## Downloads reuse the torrent engine

A download of a torrent is the same torrent, kept running in a foreground service; when it completes, the file is
moved (a rename on the same storage, not a copy) into the app's downloads folder. Direct links download over HTTP.
Subtitles are downloaded with the video.

- **Why:** no second download stack, no copy of a multi-GB file, and the player already knows how to play it.

## Updates without a store

The app checks GitHub's latest release, downloads the APK for the phone's architecture, checks its SHA-256 against
the one GitHub publishes, and installs it with Android's PackageInstaller. A build flag turns this off for store
builds.

- **Why:** it is not on an app store, and sideloaded apps otherwise never update. Verifying the hash means a
  corrupted or swapped download is never installed.

## Fast by construction, measured on a real phone

A Baseline Profile (generated by a scripted walk through the app) is shipped so Android compiles the hot paths at
install time; Home shows the last saved rows instantly and refreshes behind; a title page opens with what is already
known about it and fills in the rest; the blurred tab bar is rendered at half resolution. Performance is only
measured on release builds on a phone (`performance.md`).

## Three languages, done properly

Every interface string is a resource in English, Spanish and Brazilian Portuguese (`ui_strings` each). The app follows the phone's
language, and on Android 13 or later it can be set per app in the system settings. First-run defaults (metadata
language, region, subtitles) also follow the phone.

## People and sagas without a key: Wikidata

Without a TMDB key, a person's full filmography and a movie's saga come from Wikidata, the open knowledge base behind
Wikipedia, which links titles to their TMDB ids. No key, no account, nothing in the APK.

- **Why:** the keyless TMDB addon has no person or collection data, and its search stops at 40 titles. Wikidata
  fills the gaps for everyone, not only for those who set up a key.

## Two ways to the TV

Chromecast through Google's Cast library, and DLNA (the open standard most smart TVs speak) written in the app.
Torrents and downloads reach the TV through a small server on the phone; direct links go straight to a Chromecast.

- **Why:** Chromecast is the most common TV stick, and only Google's library talks to it. DLNA covers the TVs
  without one and needs nothing from Google. Google's library is only started when the TV list is opened, so it
  doesn't run for people who never use it.

## Skip intro learned from the user

The app doesn't look up intros anywhere: the first time the user skips one by hand, it learns the length, and the
next episodes offer to skip it.

- **Why:** intro databases cover few shows and need a service to ask; detecting intros by audio would mean analysing
  every episode on the phone. Watching what the user already does costs nothing and works for any series.

## One set of rules for every automatic choice

"Play the best stream right away", season downloads and smart downloads all choose a stream with the same rules and
the same two limits the user sets (highest quality, largest file).

- **Why:** one place to understand and adjust. Whatever the app picks by itself, it picks the same way.

## The second subtitle is drawn by the app

Media3 shows one text track at a time, so the second subtitle is read by the app (SRT, WebVTT or ASS) and drawn over
the picture in the same style as the first.

- **Why:** dual subtitles are the most asked-for way to learn a language with films, and the player can't do it by
  itself.
