---
title: Browsing, title pages and metadata
status: stable
updated: 2026-11-27
---

Pictures: `home.jpg`, `home-rows.jpg`, `title-series.jpg`, `title-episodes.jpg`, `title-cast.jpg`, `search.jpg`,
`my-list.jpg`, `person.jpg`, `saga.jpg`.

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

## People

Tapping anyone in the cast opens their page: photo, what they are known for (a row) and their whole filmography,
newest first. With a TMDB key it comes from TMDB in one request, with the biography, birth date and place, and
directors' films as well as acting roles. Without a key, the addon's search gives a first list with posters right
away (it stops at 40 titles per type) and Wikidata adds the full filmography and what the person is best known for
(by how many Wikipedia articles each title has); titles only Wikidata knew get their poster as they scroll into view.
The person is found through the cast of the title they were opened from, so two people with the same name aren't
mixed up. Talk shows, news, reality and documentaries about them are left out: those are appearances, not roles.

## Sagas

A movie that belongs to a saga shows all its parts in release order on its page, with the one on screen marked
("You are here"). With a TMDB key the saga is TMDB's collection; without one it comes from Wikidata's "part of the
series", the smallest series the movie is in (a trilogy rather than a whole franchise), up to `saga_max_parts`
movies.

## Where to watch

With a TMDB key, a title page lists the services that have it in the user's region, by subscription, free, rent and
buy, with their logos; tapping one opens TMDB's page with the links. The data is JustWatch's, through TMDB, and the
page says so. Without a key the section doesn't appear: that data only comes from TMDB's API.

## Search

Search across movies and series as you type, with trending titles shown before typing.

## My list

The titles saved to the library, with a button to Downloads.

## Languages and region

Metadata language (Spanish for Latin America, Spanish for Spain, or English) and region are in Settings; on a new
install they follow the phone.
