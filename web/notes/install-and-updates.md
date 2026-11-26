---
title: Installing and updating
status: stable
updated: 2026-11-26
---

Picture: `settings-metadata-updates.jpg` (the Updates section).

## Installing

- Download the APK from the latest GitHub release (`[project].download`): **arm64-v8a** for phones, **x86_64** for
  emulators and Chromebooks. Android 10 or later.
- Android asks once to allow installing apps from the browser or file manager.
- **Obtainium:** adding the repo URL to Obtainium keeps it updated from GitHub releases automatically.
- Every APK is signed with the same release key; its certificate fingerprint is in `[project].signing_cert_sha256`
  and in the README, so a download can be checked before installing.

## Updating from inside the app

1. On start (and from Settings → Updates → Check now) the app asks GitHub for the latest release.
2. If it is newer, a card on Home says so; "Update" downloads the APK for the phone's architecture, with progress.
3. The file's SHA-256 is checked against the digest GitHub publishes for that asset. A file that doesn't match is
   discarded ("The download arrived damaged; try again") and never installed.
4. Android's PackageInstaller installs it over the current version: progress, list, settings and downloads stay.
   The first time, Android asks to allow Harbor to install apps; afterwards, on Android 12 and later, updates may
   install without another prompt.

The notice can be turned off in Settings. A build flag (`SELF_UPDATE`) disables the updater entirely for builds
distributed through a store.

## Releases

Each release on GitHub has short notes in English, both APKs, and the signing certificate fingerprint.
