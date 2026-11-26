---
title: The Stremio account and sync
status: stable
updated: 2026-11-26
---

Pictures: `home.jpg` (the sign-in card), `settings-account-addons.jpg`.

## Signing in

Email and password of a Stremio account, in Settings or from the card on Home. The password is used once to open
a session; the app keeps the session key, never the password. Signing out forgets the key. Everything works without
an account too, with the data kept only on the phone.

## What comes from the account

- **Addons**, in the account's order (stream and subtitle addons; the account's metadata addons are used for
  episode lists, below).
- **Continue watching:** the newest titles in progress on any device, with the episode and position.
- **The library**, shown as "My list".
- **Watched episodes and movies.**
- **The per-series bell** ("notify me about new videos"), kept in step with the app's own.

## How the sync works

Stremio keeps one record per title (a "library item", keyed by IMDb id) in its datastore, with the playback state:
the video in progress, the position, and the watched marks. The app reads and writes those same records through
Stremio's API, the way Stremio does.

- **Pull:** when the app opens or comes back, at most once a minute; offline is normal and stays quiet.
- **Push:** when the player pauses, exits or finishes (bursts are collapsed into one write), when a title is added
  to or removed from My list, and when something is marked watched.
- **Merge rules:**
  - Newer wins, per title.
  - A title finished or removed from Continue watching on another device is dropped here too.
  - Something added to the list elsewhere comes in; something removed elsewhere after it was added here goes away.
  - Titles added on the phone before signing in are uploaded.
- **Removing from Continue watching** sets the position back to 0 in the account, which is how Stremio takes a
  title out of its own Continue watching.
- **Finishing an episode** sets its watched mark and moves Continue watching on to the next episode from the start,
  like Stremio.

## Watched marks in Stremio's format

In the app, "watched" is simply progress at the end. In Stremio, a series' watched episodes are a bitfield over its
list of videos: `{anchor video id}:{length}:{base64 of the zlib-compressed bits}`. The bits only mean something
against the same video list, sorted the same way (season, episode, release date), taken from the account's first
metadata addon, usually Cinemeta, just as Stremio does.

- If the order were different, marks would land on the wrong episodes; so the app builds the list the same way and
  has unit tests that decode and encode stremio-core's own examples.
- Marks can be set per episode, per season, or for a movie, from the title page and from Continue.

## Desktop

Harbor desktop uses the same account data, so the phone, the desktop app and Stremio all see the same Continue
watching, list and watched marks.
