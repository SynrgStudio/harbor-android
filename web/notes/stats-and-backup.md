---
title: Stats and backup
status: stable
updated: 2026-11-27
---

Pictures: `stats.jpg`, `settings-backup.jpg`.

## Stats

From My list (the chart button): this year or all time.

- **Time watched in Harbor,** counted while the player actually plays (from version 0.8.0 on).
- **Movies, episodes and series finished,** from progress, which also includes what was watched in Stremio when the
  account is linked.
- **By month:** a bar per month of the year.
- **Most watched:** the five titles with the most time; tapping one opens it.
- **The day of the week you watch the most.**

Everything is computed on the phone from its own files; nothing is sent anywhere.

## Backup

Settings → Backup:

- **Export** writes one JSON file wherever the user picks (Android's file picker): progress with the per-title
  choices (audio, subtitles, second subtitle, learned intros), My list, series with notifications off, the settings
  with the addons, and the stats.
- **Import** merges a backup into what is on the phone: newer progress wins, lists are joined, settings come back.
- The Stremio sign-in and the TMDB key are never written to the file. Addon links can carry keys, so the screen
  asks to keep the file private.
