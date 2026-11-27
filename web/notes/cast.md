---
title: Play on a TV
status: stable
updated: 2026-11-27
---

Pictures: `cast-picker.jpg`, `cast-remote.jpg`.

## Using it

- The player has a TV button. It lists the **Chromecasts and Google TVs** and the **DLNA smart TVs** (most Samsung,
  LG, Sony, Philips, Hisense…) on the same Wi-Fi, searching while the list is open.
- Picking one sends what is playing, **from the same minute**, with the **subtitle in use** when it comes from an
  addon or a download, already shifted by the sync set by hand.
- The phone becomes the remote: the title's artwork, play and pause, ±10 s, a seek bar with the TV's position and
  length, and **Watch on the phone**, which stops the TV and carries on on the phone from where the TV was.
- Progress keeps saving from the TV's position, so Continue watching and the Stremio account follow along.
- While something plays on a TV there is a notification with pause and stop, and the phone stays awake enough to
  keep serving the video with the screen off.

## How it works

- **The phone as a server:** a small HTTP server on the phone's Wi-Fi address serves the video: proxied from the
  torrent engine (which only listens on the phone itself), a downloaded file, or a link that needs the addon's
  headers. Byte ranges work, so the TV can seek. Each item gets a random address and nothing else on the phone is
  reachable; it stops when playing on the TV stops.
- **Chromecast:** Google's Cast library and Google's default media receiver. Direct links go to the Chromecast
  as they are; subtitles go as WebVTT. It needs Google Play services, and the library only starts the first time the
  TV list opens.
- **DLNA:** written in the app. SSDP finds the TVs; UPnP's AVTransport plays, pauses, seeks and reads the position
  over plain HTTP. Everything goes through the phone's server (many TVs can't open HTTPS); subtitles go as SRT,
  which Samsung TVs show.

## Limits

See `limits.md`: it ends when the player closes, it doesn't continue to the next episode on the TV, a subtitle
track inside the video file isn't sent, and what plays depends on the TV's own decoders.
