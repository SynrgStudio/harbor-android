---
title: What Harbor for Android is
status: stable
updated: 2026-11-27
---

Harbor for Android is a native Android app for movies and TV shows that works with a Stremio account. It is an
independent project inspired by the Harbor desktop app (HarborCommunity/Harbor), which has no phone version; it is
not made by the Harbor desktop team and doesn't share code with it.

## What it does

- **Browse:** a Home screen with a featured carousel (each title's logo over its artwork) and rows of trending,
  popular, new and genre titles; title pages with seasons, episodes, cast, the saga a movie belongs to and similar
  titles; a page for every actor and director; search. With a TMDB key, where a title streams legally.
- **Your Stremio account:** sign in once and your addons, Continue watching, library ("My list") and watched
  episodes come along. Changes go back to the account, so Stremio and Harbor desktop see them too.
- **Streams:** every addon on the account is asked at once, and each one's streams show up as soon as it answers,
  sorted and filterable by quality. Direct links and torrents both play inside the app.
- **Its own player:** gestures, audio and subtitle tracks (from the file or from subtitle addons), subtitle sync
  and style, a second subtitle in another language, night mode, clearer dialogue and volume boost, "Skip intro"
  learned from you, speed, picture-in-picture, autoplay of the next episode, screen lock and a sleep timer.
- **Play on a TV:** Chromecasts and DLNA smart TVs on the same Wi-Fi, with the phone as the remote.
- **Continue tab:** what you're halfway through, new episodes of the series you follow and what's coming soon.
  A notification when a followed series gets a new episode, and a home screen widget.
- **Downloads** for watching offline, with their subtitles: one episode, a whole season, or smart downloads that
  keep the next episode ready; on the phone or an SD card.
- **Automatic choices,** if you want them: "Watch now" plays the best stream within your limits without a list.
- **Yours:** stats of what you watch, and a backup file to take everything to another phone.
- **Mobile data guard:** asks before streaming off Wi-Fi and keeps torrent uploads to a minimum there.
- **Updates from inside the app**, verified against the hash GitHub publishes.
- **English, Spanish and Portuguese.**

## What it doesn't do

It doesn't host, index or provide any content, and it comes with no stream addons. It shows what the addons the
user configured return, the same way Stremio does. Without addons it still browses everything (metadata comes from
TMDB), but there is nothing to play.

## Who it's for

People who use Stremio or Harbor on a computer and want the same library on their phone, in an app built for a
phone: one hand, portrait browsing, landscape playback, gestures, offline downloads, and mobile data that doesn't
get eaten by a torrent.

## Status

Version and dates are in `facts.toml` (`[project]`). It is distributed as signed APKs on GitHub Releases, which
also works with Obtainium. It is not on Google Play or F-Droid.
