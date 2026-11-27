---
title: Downloads
status: stable
updated: 2026-11-27
---

Pictures: `streams.jpg` (the ⬇ button on a stream), `downloads.jpg`, `my-list.jpg`, `settings-automatic.jpg`
(smart downloads and the SD card).

## Using it

- Every stream in the stream sheet has a ⬇ button: it saves that movie or episode to the phone.
- Downloads keep going in the background, with a progress notification, and a second one when a title is ready to
  watch offline.
- They live in My list → Downloads, with progress, speed and size while downloading, the space used and free on
  the phone, and a menu per title to pause, resume, cancel or delete. A download shows "Waiting for Wi-Fi" when it
  is held back by the Wi-Fi-only setting.
- **Wi-Fi only** by default (Settings → Downloads).
- **Offline:** a downloaded title plays from the phone with no connection; in a series, the next episode also plays
  from the downloads.

## A whole season

The download button next to "Episodes" on a series page queues every released episode of the selected season that
isn't downloaded yet, after a confirmation. Each one gets the best stream within the limits set for automatic
choices (`streams.md`), keeping the same release from one episode to the next. It keeps going if the page is closed,
and says how many it queued and for how many it found nothing.

## Smart downloads

An option in Settings → Downloads. When an episode watched from a download counts as watched, the app downloads the
next one (with the same release when it can) and deletes the one just watched. The player keeps reading the file it
has open, so deleting it doesn't cut the credits.

## SD card

With a card in the phone, Settings → Downloads offers where new downloads go: the phone or the SD card. It uses the
app's own folder on the card, which needs no permission (and Android removes it with the app). Downloads already
saved stay where they are. Torrents finish in the engine's cache on the phone and are copied to the card; on the
phone itself they are moved instead.

## How it works

- One download per video, run in a foreground service (`dataSync`), which Android lets keep going while the app is
  in the background.
- **Direct links** are downloaded over HTTP.
- **Torrents** are downloaded by the same engine that streams them. A downloading torrent is "pinned" so closing the
  player doesn't pause it, and when it completes the file is moved (renamed on the same storage, not copied) into
  the app's downloads folder.
- **Subtitles** are downloaded first, while there is internet: the one the user picked for that title, if any, plus
  up to three in the title's subtitle language (four files at most), so they work offline too.
