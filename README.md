# Harbor for Android

A fast, native movie and TV app for Android that works with your Stremio account. Inspired by the [Harbor](https://github.com/HarborCommunity/Harbor) desktop app.

- Metadata and artwork from TMDB, with nothing to set up.
- Your addons, Continue watching, library and watched episodes, synced with Stremio.
- Its own player, with gestures, built for phones.

## Features

- **Home:** featured carousel, Continue watching, trending, popular, new releases, anime and more.
- **Title pages:** seasons, episodes, cast and similar titles. Works without any key through the TMDB addon; add your own TMDB key in Settings to use the API directly.
- **Stremio account:** sign in once and your addons, Continue watching, library and watched marks come along, and stay in sync with Stremio and Harbor desktop.
- **Streams:** from your account's addons, from addons added by URL or through `stremio://` links. Sorted by debrid cache, quality and seeds, with resolution filters.
- **Continue tab:** everything you're halfway through, new episodes of the series you follow, and what's coming soon. Resuming goes straight back to the player with the same stream.
- **Player:**
  - Media3 + FFmpeg, including DTS and TrueHD.
  - Brightness, volume and seek gestures; double tap to skip.
  - Audio and subtitle tracks from the file or from subtitle addons, remembered per title.
  - Subtitle delay, playback speed, picture-in-picture and autoplay of the next episode.
  - Screen lock and sleep timer.
  - Torrent streaming with librqbit.
- **Downloads:** save movies and episodes with their subtitles to watch offline. Wi-Fi only by default.
- **Mobile data:** asks before streaming on a metered connection and keeps torrent uploads to a minimum there.
- **Notifications** when a series you follow gets a new episode, with a per-series bell.
- **Launcher shortcuts** to resume what you're watching.
- **In-app updates** from GitHub Releases, verified against the published SHA-256.

Available in English and Spanish: it follows the phone's language, and on Android 13 or later you can pick it per app in the system settings.

Harbor doesn't host or provide any content: it shows what the addons you configure return.

## Install

1. Download the **arm64-v8a** APK from the [latest release](https://github.com/SynrgStudio/harbor-android/releases/latest).
   - To get updates automatically, you can also add this repo to [Obtainium](https://github.com/ImranR98/Obtainium).
2. Signing certificate (SHA-256):
   `E2:FA:56:B9:3D:A9:A7:ED:2D:09:D3:23:58:A6:18:16:2F:97:55:99:18:E3:C6:F5:24:00:72:B4:AF:54:B0:B8`

Requires Android 10 or later.

## Build

On Windows, with Java 17, the Android SDK + NDK and Rust:

1. `.\scripts\build-native.ps1` builds the torrent engine (`native/`). Needed the first time and whenever `native/` changes.
2. `.\scripts\run.ps1 -Release -Serial <ip:port>` builds and installs on a phone over wireless debugging.

Without a release key in `local.properties`, builds are signed with the debug key. More in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## License

[GPL-3.0-or-later](LICENSE). Uses Jellyfin's FFmpeg decoder (GPL-3.0) and [librqbit](https://github.com/ikatson/rqbit) (Apache-2.0). The Inter font is under the SIL Open Font License.
