---
title: Privacy, and what the app is not
status: stable
updated: 2026-11-27
---

## Content

- Harbor for Android doesn't host, index, link to or provide any movie or show. It is a client: it shows metadata
  from TMDB and plays what the addons the user configured return.
- It ships no stream addons (`bundled_stream_addons`). The only addon it adds by itself is OpenSubtitles v3, for
  subtitles, the same one every new Stremio account has.
- Without addons it still works for browsing, lists and tracking what you watch, but there is nothing to play.

## Data and accounts

- **No Harbor account and no Harbor server.** Settings, progress, lists and downloads live on the phone; with a
  Stremio account, progress, library and watched marks also go to the user's own Stremio account.
- **The Stremio password is never stored:** it is used once to start a session, and only the session key is kept.
- **No analytics, ads, crash reporting or tracking libraries** in the app's own code. No API keys in the APK
  (`api_keys_in_apk`). The one outside library that reports to its maker is Google's Cast library, needed for
  Chromecast: it is only started when the user opens the TV list in the player, and it is Google's, with Google's
  own usage logging. DLNA TVs need nothing from Google.
- **Who the app talks to:** TMDB (through its addon, or its API with the user's own key) and TMDB's image servers;
  Stremio's API when signed in; Cinemeta (or the account's metadata addon) for episode lists; the addons the user
  configured; GitHub, to check for updates (can be turned off); Wikidata, for sagas and filmographies without a
  TMDB key; TVs on the same Wi-Fi, while the TV list is open or something plays on one; and, while playing a
  torrent, its trackers and peers.
- **Backups** (Settings → Backup) are a file the user saves where they want. They never include the Stremio sign-in
  or the TMDB key; addon links can carry keys, and the screen says to keep the file private.
- **Mobile data** is protected by default (`streams.md`).

## License

GPL-3.0-or-later; the source is public on GitHub. It uses Jellyfin's FFmpeg decoder for Media3 (GPL-3.0),
librqbit (Apache-2.0), and the Inter typeface (SIL Open Font License).
