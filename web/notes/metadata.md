---
title: Browsing, title pages and metadata
status: stable
updated: 2026-11-26
---

Pictures: `home.jpg`, `home-rows.jpg`, `title-series.jpg`, `title-episodes.jpg`, `title-cast.jpg`, `search.jpg`,
`my-list.jpg`.

## Where the data comes from

- **Without a key (the default):** TMDB through its public Stremio addon (tmdb.elfhosted.com), in the language set
  in Settings. Nothing to sign up for.
- **With a personal TMDB key** (optional, in Settings): straight from the TMDB API, which adds age ratings for the
  chosen region and TMDB's recommendations, plus extra Home rows (top rated, airing now, now in cinemas).
- Both use TMDB ids, so switching modes keeps progress and My list.
- **Episode lists and air dates** for sync and the calendar come from the account's first metadata addon, usually
  Cinemeta, so they match Stremio's.
- **Images** come from TMDB's image servers, cached on the phone (up to 400 MB on disk).

## Home

- **Featured carousel:** the week's top trending titles, each with its logo (the title's real typography, not
  text) over the artwork, the type, year and genres, and Play / Details.
- **Rows:** Continue watching, trending this week, popular movies, popular series, new releases, anime, new series,
  sci-fi, comedy and horror (more with a TMDB key).
- **Floating tab bar** at the bottom, translucent with a blur of what scrolls under it: Home, Continue, Search,
  My list, Settings.
- **Opens full:** Home first draws what is in the HTTP cache, even if old, and refreshes behind it, so there is no
  empty screen while the network answers.

## Title pages

- Artwork, logo, rating, year, runtime or number of seasons, genres and synopsis.
- **Watch now** (or "Continue · N min left"), **Streams** to pick another stream, **Add to my list**, and
  **Mark watched**.
- **Series:** season chips and the episode list with stills, runtimes, synopses and per-episode watched marks;
  "Mark season watched".
- **Cast** with their characters, and **More like this**.
- **Opens instantly:** a title page appears as soon as a poster is tapped, with what the poster already knew, and
  the rest fills in as it arrives.

## Search

Search across movies and series as you type, with trending titles shown before typing.

## My list

The titles saved to the library, with a button to Downloads.

## Languages and region

Metadata language (Spanish for Latin America, Spanish for Spain, or English) and region are in Settings; on a new
install they follow the phone.
