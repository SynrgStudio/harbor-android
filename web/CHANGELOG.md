# Changes to Harbor for Android's corpus

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
