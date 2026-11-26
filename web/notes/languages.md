---
title: Languages
status: stable
updated: 2026-11-26
---

Pictures: `spanish-home.jpg`, `spanish-continue.jpg`.

## What the user sees

- The interface is in **English and Spanish** (`ui_strings` strings in each), including plurals, dates and numbers
  written the way each language writes them ("S1 · E2" in English, "T1 · E2" in Spanish; "Friday, Dec 4" /
  "viernes, 4 dic").
- It follows the phone's language. On Android 13 or later it can be set for Harbor alone, in the system settings
  (Apps → Harbor → Language), and the app switches without restarting.
- **Metadata** (titles, synopses, episode names, logos) has its own language setting: Spanish for Latin America,
  Spanish for Spain, or English. On a new install it follows the phone, as do the region (used for age ratings and
  release info) and the default subtitle language. Existing users keep what they had chosen.
- Genre rows on Home ask the metadata addon with the genre names in the metadata language, since the addon filters
  by name.

## How it's done

- Every string is an Android resource with the same key in both languages; screens read them directly.
- Code outside the screens (background jobs, notifications, errors shown later) goes through one helper that builds
  its context in the language actually in effect. The application context can keep the old language after a
  per-app language switch, which would otherwise leave stray words in the previous language.
- Only the shipped languages are packaged, and Android's per-app language picker lists exactly those.
