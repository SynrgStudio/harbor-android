# What is never published about Harbor for Android

These rules come before everything else in this corpus.

1. **No content sources by name.** Never name, link, recommend or show any third-party stream addon, torrent site,
   release group or debrid service, and never show a torrent or release name in a picture. Say "your addons" or
   "the addons on your Stremio account". The only addons that can be named are OpenSubtitles v3 (it ships with the
   app), Cinemeta (Stremio's metadata addon) and the TMDB addon.
   *Why: Harbor is a client. Naming sources reads as recommending them, and that is not what the app is.*

2. **Never promise content.** No "free movies", "watch anything", "every movie and show" or similar. The app shows
   metadata and plays what the user's own addons return; say so plainly where it fits, and say that it ships no
   stream addons.
   *Why: it is true, and it is the line between a player and a piracy tool.*

3. **Not the official Harbor, and not Stremio.** It is an independent app "inspired by the Harbor desktop app" and
   "works with your Stremio account". Don't call it the official Harbor app or Harbor's mobile version, don't use
   HarborCommunity's or Stremio's logos, and don't imply either project made, endorses or supports it.
   *Why: neither did.*

4. **Nothing about other projects' history or status.** Don't describe what happened to Harbor desktop, its team or
   Stremio, legal or otherwise.
   *Why: it isn't ours to tell, and it isn't about this app.*

5. **Only measured numbers.** Every number on the page is a `fact`. The phone performance numbers are from 0.1.0
   and 0.2.0 and must say which version they are; don't present them as numbers of the current release. No
   "starts in N ms", "N× faster than X" or comparisons with other apps beyond the facts: no other app was measured.
   Describing how it feels ("title pages open instantly") is fine next to the measured numbers.
   Numbers in `notes/` that describe behavior (a 10 s skip, a 6-hour check, a 16 KiB/s limit) come from the source
   and can be used as written there.
   *Why: the facts are what make the page trustworthy.*

6. **Credits that must appear with the pictures.**
   - Pictures with Tears of Steel (`player*.jpg`, `streams.jpg`, `pip.jpg`, `continue.jpg`, `downloads.jpg`,
     `spanish-continue.jpg`): "Tears of Steel (CC BY 3.0) Blender Foundation | mango.blender.org".
   - Posters, artwork and metadata come from TMDB: the page carries "This product uses the TMDB API but is not
     endorsed or certified by TMDB."
   - "Open Movies", the addon in the pictures, was made only to take them; it is not part of Harbor and must not be
     presented as one of its features or as an addon to install.
   *Why: licenses and honesty about the pictures.*

7. **Only what a release has.** The corpus describes 0.7.0. Nothing that isn't in a published release is announced
   or shown, and `limits.md` is not a roadmap.
   *Why: someone who installs the app from the page must find what the page shows.*

8. **No personal data.** No account emails, device names or addresses, IPs, disk paths or user names. The pictures
   were taken with no Stremio account signed in.
   *Why: privacy.*

9. **Not on any store.** Don't say or suggest it is on Google Play, F-Droid or any other store, and don't use
   store badges. It is installed from GitHub releases (or Obtainium).
   *Why: it isn't.*
