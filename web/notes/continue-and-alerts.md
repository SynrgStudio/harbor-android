---
title: Continue, new episodes, notifications and shortcuts
status: stable
updated: 2026-11-27
---

Pictures: `continue.jpg`, `continue-coming-soon.jpg`, `spanish-continue.jpg`, `widget.jpg`.

## The Continue tab

One vertical list, top to bottom:

1. **Continue watching:** everything halfway through, with the time left and when it was last watched. Tapping a
   row goes straight back into the player with the same stream and position. Each row's menu opens the title,
   marks it watched, or removes it (which also removes it from Continue watching in the Stremio account).
2. **New episodes:** episodes of followed series that came out in the last `new_episodes_days` days and haven't
   been watched, with a NEW badge and when they came out ("Out yesterday").
3. **Coming soon:** the next `coming_soon_days` days of episodes for the followed series, with the day each one
   comes out ("Out tomorrow", "Out Friday, Dec 4").

## Which series are "followed"

The series in My list, plus any series watched in the last `follow_watched_days` days. No separate "follow" step.

## Where the dates come from

The list of episodes and their air dates come from the same metadata addon Stremio uses for the account (usually
Cinemeta), so "new" means the same thing as in Stremio; episode names and pictures come from TMDB in the app's
language. The list refreshes when the tab is opened and it's more than 3 hours old.

## Notifications

- A background job (Android's JobScheduler, so it costs almost nothing) checks the followed series every
  `episode_check_hours` hours and notifies about episodes that came out since the last check.
- Tapping the notification opens that series at the right season.
- Each series has a bell on its page to silence it; the bell is the same setting as Stremio's "notify me about new
  videos" and syncs with the account.
- Notifications can be turned off entirely in Settings; on Android 13 and later the app asks for the permission.

## Launcher shortcuts

Long-pressing the Harbor icon lists up to `launcher_shortcuts` titles from Continue watching; each one resumes
playback directly. They update by themselves when what is in progress changes.

## Home screen widget

"Continue watching" as a widget (4 × 2, resizable): up to `widget_titles` titles with their picture, the episode,
the time left and a progress bar. Tapping one plays it straight away, like the shortcuts; tapping the header opens
the app. It is plain Android widget code, with no widget library in the APK, and it refreshes when what is in
progress changes.

## Opening the right screen

Notifications, shortcuts and `stremio://` links all land on the right screen even if the app is already open,
because each window handles its own incoming links instead of sharing one global queue.
