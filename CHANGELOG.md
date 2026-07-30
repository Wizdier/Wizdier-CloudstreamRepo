# 📜 Changelog

Version history for the **Wizstream** extension and this repo, newest first.

Older releases are summarised — full detail for those lives in the git history.


## Recent

| Version | Date | What changed |
|---|---|---|
| **v81** | 2026-07-30 | **One install, both catalogues.** Wizstream now registers the anime catalogue itself — the separate Wizstream-Anime extension is no longer needed. Season headers are now season-*accurate* (they use art from the season's own episodes, so a Season 1 page can't show Final-Season art). Rebuilt settings screen: rounded section cards, live search, per-group counts and toggles, API-key status pills, All on / All off. MAL & Kitsu progress lines removed from anime descriptions (AniList's own stays) and the ratings line now sits on its own line above the synopsis. |
| **v80** | 2026-07-30 | Every AniList entry now gets its OWN landscape header instead of the whole franchise sharing one image — and TMDB art is preferred over AniList's, per your call |
| **v79** | 2026-07-30 | Director now shows on movie & TV pages, pinned to the front of the cast row — but it comes from TMDB, because MDBList has no credits at all |
| **v78** | 2026-07-30 | Two new integrations, both wired into ONE shared settings sheet that serves both extensions: Wyzie Subs (subtitles everywhere) and MDBList (aggregated ratings) |
| **v77** | 2026-07-30 | The last-two-AoT-parts title/link mix-up is now closed at its real root, and the special pages can no longer go blank |
| **v76** | 2026-07-30 | Dead links are now probed and pruned BEFORE the app can warm-cache them — the "next episode loaded the broken link" bug is dead |
| **v75** | 2026-07-29 | The minutes-long link wait is gone: all discovery routes now race in parallel |
| **v74** | 2026-07-29 | The Haikyuu old-site crack, done the deterministic way — sitemap discovery + failure-proof caching |
| **v73** | 2026-07-29 | Reliability, precision and efficiency hardening for both main modules; |
| **v72** | 2026-07-29 | Haikyuu CircleFTP main-site recovery hardened after your v71 BDIX test said it was still dry; both plugins are v72 |
| **v71** | 2026-07-28 | Five screenshot-proven issues fixed + the Haikyuu main-site crack |
| **v70** | 2026-07-27 | Six reported issues, six targeted fixes |

## Earlier

| Versions | Theme |
|---|---|
| **v60-v69** | Anime metadata maturity: per-entry AniList pages, stacked franchise folding (cours + hour-long specials mapped to the right files), Japanese-VA cast, Kitsu episode titles, tracking-sheet fixes for MAL/AniList/Kitsu/Simkl, and the first per-source settings menu. |
| **v50-v59** | The anime split: a dedicated pure-AniList catalogue alongside the TMDB one, AniList watchlist rows on the home screen, franchise-root title/identity fixes, and the shared TMDB episode-table mapper that gives BDIX packs correct episode names, stills and air dates. |
| **v40-v49** | Source depth: real quality chips and selectable audio tracks, CircleFTP multi-season anime handling, AniList-driven alternate-title searching for BDIX catalogues, and broader anime-web source coverage. |
| **v30-v39** | BDIX parity programme — CircleFTP, CineplexBD, FTPBD and CTGMovies each rebuilt to match their standalone extensions 1:1, plus the Cineby rewrite for that site's 2026 relaunch. |
| **v25-v29** | Foundations: dead embed hosts removed, self-describing link names, hardware codec gating so unplayable links are never offered, and anime title-matching across romaji/English/aliases. |
