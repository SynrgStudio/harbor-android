# Changes to Harbor for Android's corpus

## 2026-11-27

- For Harbor for Android 0.8.0.
- `facts.toml`:
  - project version and date, APK sizes, languages (3), translated strings, lines and files of Kotlin, unit tests;
  - new behavior facts: `saga_max_parts`, `volume_boost_max_pct`, `intro_jump_min_s`, `intro_jump_max_s`,
    `intro_learn_window_min`, `auto_pick_wait_s`, `auto_pick_max_s`, `widget_titles`, `unit_tests`;
  - the phone measurements are still those of 0.1.0 and 0.2.0.
- New notes: `cast.md` (Play on a TV) and `stats-and-backup.md`.
- Updated notes:
  - `overview.md`: the new features, and three languages;
  - `metadata.md`: people pages, sagas, where to watch;
  - `player.md`: subtitle style, second subtitle, sound (night mode, dialogue, boost), skip intro;
  - `streams.md`: "Play the best stream right away";
  - `downloads.md`: whole seasons, smart downloads, SD card;
  - `continue-and-alerts.md`: the home screen widget;
  - `languages.md`: Brazilian Portuguese;
  - `decisions.md`: Wikidata without a key, two ways to the TV, intros learned from the user, one set of rules for
    automatic choices, the second subtitle drawn by the app, three languages;
  - `limits.md`: casting is no longer missing, its own limits, three languages, where to watch needs a key;
  - `privacy-and-content.md`: Wikidata, TVs on the network, Google's Cast library (only started from the TV list),
    backups without secrets;
  - `build.md`: Cast, the widget, the tests, the fake TV and the series demo.
- `rules.md`: the TV in the pictures is a pretend one; "Where to watch" credits JustWatch.
- `media/`: screenshots of 0.8.0's new screens (listed in `facts.toml`).

## 2026-11-26

- First version of the corpus, for Harbor for Android 0.7.0.
- `facts.toml`:
  - project, links, signing certificate, the phone the numbers were measured on, and the app's colors and font;
  - the release: APK sizes, minimum Android, languages, translated strings, lines of code, bundled addons, API keys;
  - behavior from the source: mobile data upload limit, next-episode prefetch, autoplay countdown, skip and swipe
    seek, episode check interval, New episodes / Coming soon windows, torrent cache, shortcuts, "watched" threshold;
  - phone measurements of 0.1.0 and 0.2.0: cold start, janky frames, frame time percentiles.
- `notes/`: overview, decisions (why it is built this way), metadata, stremio (account and sync), streams (addons and
  torrents), player, continue-and-alerts, downloads, install-and-updates, languages, performance,
  privacy-and-content, build, limits.
- `data/phone-measurements.csv`: the raw phone numbers.
- `media/`: the icon and 24 screenshots of 0.7.0 taken on 2026-11-26 (English, plus two in Spanish).
- `rules.md`.
