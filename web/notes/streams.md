---
title: Streams, addons and torrents
status: stable
updated: 2026-11-26
---

Picture: `streams.jpg`.

## Where streams come from

- **Addons on the Stremio account**, in the account's order, once signed in.
- **Addons added by URL** in Settings (a `manifest.json`, a `stremio://` link or a bare base URL).
- **`stremio://` links** opened from a browser or another app.
- The app itself only adds OpenSubtitles v3, for subtitles. It ships no stream addons (`bundled_stream_addons`).

Each addon can be turned off or removed in Settings without touching the account.

## Asking the addons

Every enabled addon that serves streams for that kind of title is asked at the same time, and each one's results
appear as soon as it answers; a slow or broken addon shows its own error and doesn't hold the others back.

## Reading and sorting the results

Addons describe streams in free text. The app reads it into:

- **Quality:** 4K, 1080p, 720p, SD or unknown, from the name, description or file name.
- **Size**, **seeds**, **source** and **language flags** when the text has them.
- **Tags:** DV, HDR, HEVC, AV1, 10bit, Atmos, REMUX, BluRay, WEB, Multi, CAM.
- **Debrid cache:** whether a debrid service already has it (shown with a lightning mark).

Order within each addon: already cached on a debrid service first, CAM recordings last, then by quality, then by
seeds. Chips filter by quality (All, 4K, 1080p, 720p, Other).

## Continue with the same stream

If a title was started in the app, the sheet starts with "Continue with the same stream" and the minute it stopped
at, and resuming from anywhere else skips the sheet entirely. The title page keeps a **Streams** button to pick
another one.

## Kinds of stream

- **Direct links** play as they are.
- **Torrents** play through the built-in engine (below).
- **External links** (an addon that points to a website) are listed but can't be played in the app.

## The torrent engine

- librqbit, the Rust engine Harbor desktop uses, compiled for arm64-v8a and x86_64 and started through a thin JNI
  layer. It serves the chosen file over HTTP on 127.0.0.1, and the player streams from there while it downloads;
  librqbit fetches first the pieces the player is asking for.
- Results without trackers get a list of public trackers added, as Stremio does.
- A torrent opened again later reuses what was already downloaded.
- The cache is capped: folders untouched for 2 days are removed, then the oldest above `torrent_cache_gb` GiB.

## Mobile data

The app watches whether the current network is metered (mobile data, or a hotspot Android marks as metered):

- **Before playing** off Wi-Fi it asks ("Watching this can use several GB of your data plan"). The question can be
  turned off in Settings.
- **Torrents upload at `metered_upload_kib_s` KiB/s** while metered, the smallest limit librqbit accepts without
  dropping peers' requests; download speed isn't limited. Back on Wi-Fi the limit is lifted.
- Downloads wait for Wi-Fi unless the user allows mobile data.
