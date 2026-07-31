# 📜 Changelog

Version history for the **Wizstream** extension, newest first. Older rows
are kept short — full detail lives in the matching release notes and git
history. (v83 and newer are **not on GitHub yet** — pushed together when
the owner decides.)


## Recent

| Version | Date | What changed |
|---|---|---|
| **v88** | 2026-07-31 | **Your three asks.** (1) MDBList ratings render as **one flowing line** again ("⭐ IMDb 8.5 · TMDb 85 · Trakt 85 · MAL 8.5") with a hard blank line before the synopsis (HTML `<br>`, since the app collapses raw newlines). (2) **Robustness pass**: every page-critical metadata call — TMDB detail/season (both catalogues), the episode-table fetcher, AniList queries, the ani.zip id-map/episode fallback, and MDBList — now retries **once** after a 350 ms pause on failure, so a single network blip no longer strips a page of titles, ids or ratings; happy path latency is unchanged. (3) README fully user-focused (and corrected) and this changelog compacted. |
| **v87** | 2026-07-31 | Descriptions got **real line breaks** (the app renders plots as HTML — raw `\n` collapses, so breaks now ship as `<br>`; pretty labels for two raw MDBList source names). **JJK S3 header no longer shows S2-era art** — the season-true art picker now uses the absolute window on packed shows (JJK S3 = eps 48–59), so the header is a real still from your season. Settings screen polish: section icons, hairline dividers, and per-toggle "what it serves" blurbs the search also matches. |
| **v86** | 2026-07-31 | **JJK S3 bare rows fixed with a new fallback ladder**: AniList feed → TMDB → **ani.zip** (per-entry titles, stills, overviews, dates, runtimes — zero extra requests on the anime catalogue) → Kitsu. Wrong-season diagnostics added (`resolveAll req … s=/e=`, `<source>: served s=/e=` logcat fingerprints) after the "S3 fetches S2" report could not be reproduced in code. |
| **v85** | 2026-07-30 | **Sirius / HDGhar slow starts fixed the web way.** Measured live: their CDN is fast — the app simply grabbed the heaviest 1080p rung first. Bingr (Sirius) and Moonflix (HDGhar + mp4 ladder) now list links **lowest-quality-first** like a web player's adaptive start; every quality is still one tap away. |
| **v84** | 2026-07-30 | Sync-status line removed from anime descriptions. **AniList episode titles on the main catalogue** (guardrails: entry-sized ±3 or exact whole-season feeds). Franchise walk 30-min cache (one call instead of N serial ones), unaired phantom seasons dropped, defensive row dedupe. |
| **v83** | 2026-07-30 | **The pre-playback source verifier is gone, at your request** — links are listed exactly as servers publish them, zero pre-checks, zero added delay. If a pick stalls on a dead server, tap the next link. |
| **v82** | 2026-07-30 | **One extension, one module, one source of truth.** The separate Wizstream-Anime module is retired — one install registers both `Wizstream` and `Wizstream-Anime` sources (same names; bookmarks/tracking intact). ⚠️ Uninstall the old separate "Wizstream Anime" extension or the catalogue shows twice. GitHub Action sanity-checks and archives every build. |
| **v81** | 2026-07-30 | **One install, both catalogues** — Wizstream registers the anime catalogue itself. Season-accurate headers (art from the season's own episodes). Rebuilt settings screen: rounded section cards, live search, group counts, key-status pills, All on / All off. MAL/Kitsu progress lines removed from descriptions; ratings get their own line above the synopsis. |
| **v80** | 2026-07-30 | Every AniList entry now gets its OWN landscape header instead of the whole franchise sharing one image — and TMDB art is preferred over AniList's, per your call |
| **v79** | 2026-07-30 | Director now shows on movie & TV pages, pinned to the front of the cast row — but it comes from TMDB, because MDBList has no credits at all |
| **v78** | 2026-07-30 | Two new integrations, both wired into ONE shared settings sheet that serves both extensions: Wyzie Subs (subtitles everywhere) and MDBList (aggregated ratings) |
| **v77** | 2026-07-30 | The last-two-AoT-parts title/link mix-up is now closed at its real root, and the special pages can no longer go blank |
| **v76** | 2026-07-30 | ~~Dead links are now probed and pruned~~ — superseded by v83 (verifier removed on your request) |
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
