---
title: The player
status: stable
updated: 2026-11-26
---

Media3 (ExoPlayer) with Jellyfin's FFmpeg audio decoder, in its own landscape activity, with controls written in
Compose. Pictures: `player.jpg`, `player-subtitles.jpg`, `player-tracks.jpg`, `player-brightness.jpg`,
`player-seek.jpg`, `player-sleep-timer.jpg`, `player-lock.jpg`, `pip.jpg`.

## Formats

- Whatever Media3 plays (MP4, MKV, WebM, MOV, HLS…), from a direct link, a local download or the local torrent
  stream.
- **Audio:** the FFmpeg decoder adds the formats phones usually can't decode in hardware, such as DTS and TrueHD.
- Addon request headers (`proxyHeaders`) are sent with the stream when an addon asks for them.

## Gestures

- **Vertical swipe, left half:** brightness, with its level on screen.
- **Vertical swipe, right half:** volume.
- **Horizontal swipe:** seek, showing the target time and the jump (`+0:12`). A full-width swipe moves
  `swipe_seek_min` minutes, or a fifth of a short video.
- **Double tap left or right:** back or forward `skip_s` s. The on-screen buttons do the same.
- **One tap:** shows or hides the controls.

## Tracks, subtitles and speed

A side panel (it doesn't cover the whole picture) with three tabs: Audio, Subtitles and Speed.

- **Subtitles from the file and online:** online ones come from the subtitle addons on the account (OpenSubtitles
  v3 by default), listed by language and numbered ("English 1", "English 2"…) so they can be told apart.
- **Automatic:** if the preferred subtitle language is set and the file has no track in it, the first online one in
  that language turns on by itself, unless the user already picked something.
- **Remembered per title:** the audio language and the exact subtitle chosen (a track of the file or a specific
  online file) are saved for that movie or series. The next episode uses the same choice when it has it.
- **Subtitle sync:** ±0.1 s and ±0.5 s buttons, saved for that video. Implemented by wrapping Media3's text
  renderer and shifting its clock, since the renderer itself can't be subclassed.
- **Size:** Small, Normal, Large or Huge, in Settings.
- **Speed:** 0.5× to 2×.

## Next episode

- About `next_episode_prefetch_min` minutes before the end of an episode, the player looks up the next one: it asks
  the addons, prefers a stream from the same release group (Stremio's `bingeGroup`), then the same addon and
  quality, and starts loading it.
- During the credits a card with the next episode's picture appears; at the end a `autoplay_countdown_s`-second
  countdown starts it. "Autoplay next episode" can be turned off in Settings.
- Offline, the next episode plays from the downloads.

## Picture-in-picture

Leaving the app while a video plays shrinks it to a floating window (Android's auto-enter on 12 and later), with
subtitles still showing; there is also a button for it.

## Screen lock and sleep timer

- **Lock:** ignores every touch while watching (in a pocket, with a child). A pill at the top unlocks it.
- **Sleep timer:** 15, 30 or 45 minutes, 1 hour, 1 h 30, or at the end of the episode or movie. When it fires it
  lowers the volume over a few seconds and then pauses, so nobody awake gets a sudden silence; after that the
  screen turns off on its own.

## Picture

A button switches between fitting the whole picture and filling the screen (cropping the sides), for phones with
tall screens.

## Resume

Progress is saved on pause, on exit and when a video ends; it counts as watched at `finished_pct`%. Resuming from
Continue, a shortcut or a notification goes straight back into the player with the same stream and position,
without the stream list.
