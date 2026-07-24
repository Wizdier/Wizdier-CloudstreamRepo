# 🎬 Wizdier Cloudstream Repository

> **6 Cloudstream extensions** — one unified catalogue plugin (movies · TV ·
> anime) plus five standalone BDIX source extensions for Bangladeshi networks.
> Movies, web-series, anime, animation, cartoons & documentaries — Bangla,
> Hindi, English, Korean, Japanese and more. Health-checked **2026-07-24**.

[![Cloudstream](https://img.shields.io/badge/Cloudstream-extension-blueviolet?logo=android)](https://github.com/recloudstream/cloudstream)
[![Extensions](https://img.shields.io/badge/extensions-6-success)](#-the-extensions)
[![Latest build](https://img.shields.io/badge/Wizstream-v39-orange)](#-changelog)

---

## 📥 How to install (2 minutes)

1. Install [**CloudStream**](https://github.com/recloudstream/cloudstream/releases) on your Android phone / TV / box.
2. In CloudStream go to **Settings → Extensions → Add repository** and paste:

   ```
   https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/main/repo.json
   ```

3. Open the repository, tap **Wizstream** (or any standalone extension) → **Install**.

That's it. Search a movie, series or anime, pick an episode, and choose a
source from the list. Updates arrive automatically when this repo publishes
a new version.

> **Which one should I install?** Most people only need **Wizstream** — it
> already includes every BDIX source *and* the big web sources in one app.
> The five standalone extensions are the same sources as separate apps, for
> people who prefer the classic layout or want to compare results.

---

## 🗂️ The extensions

| # | Extension | Lang | What you get | Source host(s) |
|---|-----------|:----:|--------------|----------------|
| 1 | **Wizstream** ⭐ | en | Everything below, in one plugin | multi-source (BDIX + web) |
| 2 | **Cineplex BD** | bn | Movies, web-series, anime, cartoons | `cineplexbd.net` (BDIX) |
| 3 | **Circle FTP** | en | Movies, series, anime, Asian drama, documentaries | `new.circleftp.net` (BDIX) |
| 4 | **CTGMovies** | bn | Movies, web-series, anime | `ctgmovies.com` |
| 5 | **FTPBD** | en | Movies, TV & anime (big Hindi-dubbed section) | `ftpbd.net` |
| 6 | **FlixHub** | en | Hollywood·Bollywood·South-Indian movies & series | `flixhub.net` (BDIX) |

> **What is “BDIX”?** Some of these servers sit behind Bangladeshi ISP
> peering. From outside Bangladesh they simply don't answer — that is normal
> and not a bug. From a BD connection they are usually your **fastest and
> highest-quality** options.

---

## ⭐ Wizstream — what's inside

One install gives you **two catalogue apps**:

- **Wizstream** — movies & TV series, powered by TMDB (Trending, Popular,
  Top Rated, Upcoming, Now Playing, On The Air).
- **Wizstream-Anime** — anime, OVA & anime movies, powered by AniList
  (Trending, Popular This Season, Top Rated, Upcoming, All-Time Popular).

Both show posters, backdrops, logos, trailers, ratings, full cast (anime
pages list up to 25 main+supporting characters with Japanese **and** English
voice actors) and per-episode stills/synopses. MAL · AniList · Kitsu · Simkl
tracking syncs where applicable.

### 📺 Where links come from (all fetched in parallel, de-duplicated)

| Group | Sources |
|-------|---------|
| **BDIX resolvers** | Cineplex BD · FTPBD · Circle FTP · CTGMovies — searched by exact/alt title with strict + relaxed matching |
| **Cineby** | Neon · Yoru · Breach · Vyse · Killjoy (German) · Fade (Hindi) · Omen (Spanish) · Raze (Portuguese) — all eight servers of the site, decrypted locally |
| **Bingr** | Sirius · Elysium · Miller · Mann · Edmunds · Luna · Aditya |
| **Moonflix** | Multi-audio mp4 ladder (English/Hindi/Original) · HDGhar HLS · VIP/LUL HLS + Stremio-track subtitles |
| **Vid-embed family** | VidSrc · VidNest · VidPlay · VidUp/VidLink · VidRock · VidFast · VidEasy · MultiEmbed · SuperEmbed · DatabaseGdrive · VidAPI |
| **Anime sources** (in Wizstream-Anime) | Allmanga (AllAnime family) · AniZone · AniChi · UniqueStream · AniNeko · ReANIME · TokyoInsider (direct MKV/MP4) |

Sub / dub aware: Allmanga emits separate `· DUB` batches; Hindi/German/
Spanish servers are tagged by language.

### 🏷️ Reading the source picker

```
Wizstream  • Cineby · Neon      ← source group (server it came from)
           ├── 1080p · H.264     ← quality chip + codec tag
Wizstream-A • Allmanga · Ak · DUB   ← anime source + dub batch
[Neon] English                  ← subtitles, prefixed with their server
```

- Quality chips (4K/1080p/720p…) are read from the actual stream variants,
  not from filenames.
- `· H.264` / `· HEVC` / `· AV1` codec tags — the extension also asks your
  device what its hardware decoder supports and **never offers a link your
  device can't play** (this is what fixed the old “HTTP 4003” errors on TVs).
- `· DUB`, `· Hindi`, `· German`… mark the audio language.

---

## 🛡️ Why things fail — and what this repo does about it

Streaming sites change constantly: domains move, CDNs die, APIs get
re-encrypted. The extension **probes before it shows**: links whose server
answers an error page or an ad-wall instead of a video are dropped silently;
timeouts are kept (a slow BDIX server never costs you a link). Dead hosts
are removed in health sweeps. Cineby's cipher is re-verified against the
site's own code on every update.

**Honest expectations:**

- **BDIX servers** (Circle FTP, Cineplex BD, FlixHub) only respond inside
  Bangladeshi networks.
- **AllAnime's own video CDN is currently partly down** (their `clock.json`
  endpoints return server errors on their *website* too). Allmanga links in
  the app come back the moment their operator fixes it — nothing you need to
  do; it's also why YouTube-style external mirrors (Ok.ru etc.) sometimes
  look lonely.
- **Yoru** (a Cineby server) has been offline since its CDN domain expired.
  It will reappear automatically if the provider resurrects it.
- A source that is empty for one title may be full for another — catalogues
  differ per title. The app automatically retries searches with the
  alternate (romaji/English) title for anime.

---

## 🛠️ For developers

```bash
./gradlew make            # build every extension → */build/*.cs3
./gradlew makePluginsJson # regenerate build/plugins.json (CI parity)
./gradlew :Wizstream:make # build a single module
```

Repo layout:

```
├── Wizstream/     unified catalogue plugin (this repo's flagship)
├── Cineplex BD/   standalone BDIX
├── Circle FTP/    standalone BDIX
├── CTGMovies/     standalone BDIX
├── FTPBD/         standalone BDIX
├── FlixHub/       standalone BDIX
├── icons/         repo artwork
├── repo.json      repository index (what you paste into CloudStream)
└── .github/workflows/build.yml   CI: build → builds branch
```

---

## 📜 Changelog

| Version | Highlights |
|---------|-----------|
| **v39** (2026-07-24) | **The CineplexBD 2004 root cause, solved for good.** The v38 self-probe chip exposed it: media URLs were being *concatenated onto the whole player-page URL* (`…/player.php?id=76263/ondemand/<hash>/index.m3u8` — an address that cannot exist; the site's catch-all answers it with an HTTP-200 HTML page, which the player rejects as `HTTP 2004`). Relative references now resolve by RFC 3986: root-relative → against the origin, document-relative → against the page's parent path with the query string stripped. Verified with a 10-case unit suite covering the exact failing URL, token-signed HLS master variants (bonus: Sirius/HDGhar audio-track resolution), origin-only and directory bases. Temporary probe chips retired — the stage-diagnostic chips stay until series is confirmed |
| **v38** (2026-07-24) | **Movie-2004 fix candidate + media self-probe chips + series speed caps.** (1) Direct-video links now carry the resolver's session headers (incl. the player-page Cookie) — previously the player fetched the file bare and CineplexBD's CDN could reject it with `HTTP 2004` even though the resolver's own scrape succeeded. (2) When CineplexBD emits a link it now also probes that exact URL with the link's headers and pins a top chip reading `movie/tv probe → HTTP <code> · <first 90 chars of URL>` — one screenshot proves whether a 2004 means a dead URL or a header mismatch. (3) Series latency hard-capped: 6s per subtitle-manifest fetch, and only the first HLS manifest of a page is scanned for subtitle tracks (was every variant) — the series chain can no longer stall for minutes |
| **v37** (2026-07-24) | **Language → Bengali + unmissable CineplexBD diagnostics.** (1) Both Wizstream providers now declare `lang = bn`, matching the five standalone BDIX extensions (CTGMovies, Cineplex BD, Circle FTP, FTPBD, FlixHub) which already were. (2) The temporary ⓘ DIAG chips now pin to the **very top** of the source list and use stage-unique URLs, so on series titles that produce nothing you can see exactly where CineplexBD stops. (3) Series last-resort: a guarded S1E1-only landing-page scrape (mirrors the standalone's "Watch" placeholder episode — bounded so it can never serve the wrong season). **Confirmed working from the user's screenshot (v36)**: CineplexBD movies play (`Wizstream • CineplexBD - Direct`) with `[CineplexBD] English` subtitles attached; Bingr Sirius & Moonflix HDGhar quality tags visible |
| **v36** (2026-07-24) | **Quality tags for Sirius/HDGhar + an unmissable CineplexBD crash net.** (1) **Bingr Sirius and Moonflix HDGhar links now carry proper quality chips** — both CDNs (same streamraiwind family) serve *per-quality demuxed HLS masters*, whose audio tracks force the single-master emission path that previously stamped `Unknown` quality. The chip now shows the master's top resolution (which equals the API's own quality label — verified live: Sirius `1080p`/`720p`). (2) **CineplexBD diagnostics hardened**: resolvers can now opt in to a crash net at the dispatcher level — if CineplexBD's resolver dies with an exception anywhere (even before its own diagnostic points), a `Wizstream • CineplexBD ⓘ DIAG: resolver crashed (<ErrorName>)` chip still appears. Coupled with v35's per-stage chips, a completely silent CineplexBD is now impossible *if the build is actually installed and the resolver runs* |
| **v35** (2026-07-24) | **CineplexBD diagnostic build.** CircleFTP is fully healthy as of v34; CineplexBD still produced zero links on the user's device while the standalone works — and with no way to reach the BDIX site from outside Bangladesh, the resolver can't be debugged blind. v35 therefore makes CineplexBD *talk*: whenever it gives up it now leaves a clearly-labelled, never-playable source chip (`Wizstream • CineplexBD ⓘ DIAG: …`, always at the bottom of the source list) that names the exact stage it stopped at — search unreachable, HTTP error, no cards parsed, no title match, episode list empty, episode page scrape found 0 media, or an internal crash. Also: request headers are now byte-identical to the standalone extension's (same Chrome UA). **ⓘ DIAG chips are temporary** and will be removed as soon as the failure stage is identified |
| **v34** (2026-07-24) | **CircleFTP 2004 root-caused & CineplexBD series revived — with user-supplied site data as proof.** (1) **CircleFTP `HTTP 2004` fixed**: the port rewrote every CDN hostname (`ftp17.circleftp.net`) to its raw BDIX IP (`15.1.3.8`) unconditionally — but the standalone only does that swap when the raw-IP API mirror is actually in use, and in practice always emits the hostname form (verified against the site's own watch page, whose player and download anchors all use the hostname). The CDN refuses the IP-form on vhost-less requests → player 404 → `HTTP 2004`. Wizstream now emits the hostname URL exactly like the standalone, and only falls back to IPs when the IP mirror genuinely answered. (2) **CineplexBD series produced zero links**: episode selection was fixed in v33, but the matched episode URL (`watch.php?…&season=…&ep=N`) was then routed into the generic extractor loader — which knows nothing about cineplexbd — so every episode died silently. The standalone instead *fetches the episode page and scrapes the real media URL out of its HTML*; Wizstream now does exactly that for every non-direct path, with the standalone's cookie+Referer forwarding. (3) **CineplexBD subtitles**: subtitle tracks advertised on episode/player pages (`<track>`, `.srt/.vtt/.ass`) and inside HLS master manifests (`#EXT-X-MEDIA`) are now collected as `[CineplexBD] …` subtitle files, same as the standalone. (4) Watch-page episode anchors now respect their own `&season=N` marker, so a leftover Season-1 strip can never answer a Season-2 request |
| **v33** (2026-07-24) | **BDIX parity round 3 — CircleFTP & CineplexBD rebuilt to match the standalone extensions 1:1.** (1) **CircleFTP**: streams are now emitted *exactly* like the standalone extension — bare links, no forced referer/headers, quality read from the file name. The old port attached extra headers and pre-fetched every HLS playlist, which made the CDN reject requests that the standalone plays cleanly (the `HTTP 2004` errors). The last-resort “dump every episode from every season” fallback is gone for good — it was the source of wrong-season/error-full link batches and timeouts. (2) **Multi-season anime mapping**: Wizstream now walks your anime's prequel chain on AniList (counting real seasons, skipping `Part 2`/`Cour 2` splits) so “Season 4”-structured posts on CircleFTP/FTPBD line up with the right AniList entry — e.g. Attack on Titan's final entry now asks the site for its Season 4 content instead of Season 1. (3) **CineplexBD**: direct `/Data/` video paths are recognized (extensionless MKV/mp4 streams), missing episode numbers are now read from titles like “E04”, the numeric 1–12 season sweep only kicks in when the requested season returned nothing at all, and its results are only trusted when exactly one alternate season has content — so a mislabeled post can't silently serve you Season 1's first episode anymore |
| **v32** (2026-07-24) | **BDIX deep-fix round 2** — (1) **CTGMovies had silently worked for movies only**: its API returns *bare JSON arrays* for TV/anime searches while movies come wrapped in an object — the parser only understood the object, so every series/anime query returned zero. Parses both shapes now, reads the flat `episodes[]` layout (with per-episode `links[]` + `season_number`) that the live `/tv` and `/anime` endpoints actually return, and prefers `hls_url` when a link offers one. (2) **CineplexBD retry ladder**: strict title-matching stays the first tier, but if it matches nothing the resolver now falls back to a relaxed year-compatible match and tries the **top-3 matching posts** until one actually plays (a broken first pick used to poison the whole resolve). (3) **CircleFTP** gets the same relaxed second-chance tier. (4) **FTPBD** no longer collects WordPress `280x176`-style thumbnail attachments disguised as `.avi` media files. (5) **Allmanga**: added the current site's own one-call crypto bootstrap (`/client-crypto/v1/bootstrap`) as a key source, and `api-t.mkissa.net` as a clock.json host, so the internal CDN sources light back up by themselves once AllAnime's servers recover from their ongoing outage. Viewer-focused README refresh |
| **v31** (2026-07-23) | **BDIX parity fixes**: FTPBD series/anime episode pages (slug URLs with no S/E numbers) now resolved via the standalone's numbered-grid scrape (verified live: One Piece S1E5 → HLS, Dune → 3 links); identity gate learned audio/rip decorations (\"ONE PIECE Hindi Dubbed\" etc.) so legit posts stop being rejected; single-letter romans no longer corrupt titles; BDIX resolvers get a second search pass with the anime's alternate (romaji/English) title; CineplexBD gained the standalone's season-probe + watch-page fallbacks; anime cast expanded to 25 main+supporting characters with Japanese **and** English voice actors |
| **v30** (2026-07-23) | **Cineby rebuilt for the site's June-2026 relaunch** — new official domain `cineby.at`, new aggregate `/mbx` endpoint, new in-browser “mvm1” stream cipher ported 1:1 to Kotlin (verified byte-for-byte against the site's JS with test vectors, incl. a rotation-formula gotcha that breaks naive ports). **Yoru is off the entire internet** — its CDN domain (cdntv.one) expired and is parked, so no client can play it; parked-CDN/ad-wall answers are now dropped silently. Subtitles from `subs.videasy.to` with `[Videasy]` labels |
| **v29** (2026-07-23) | **Anime title-mismatch fix**: Allmanga's index is romaji/alias-based while AniList feeds English titles — matching now uses every server-side alias × both AniList titles (One Piece ↔ `1P` etc.); every anime source retries with the romaji alias |
| **v28** (2026-07-23) | Neon resurrection (scheme-less junk-CDN playlist URIs mangled by the resolver, 404'd for weeks); demuxed HLS masters emitted as adaptive master so audio stays muxed; homepage TV rows fixed (missing leading slashes); long-running anime episode counts un-capped (AniList `episodes=null`) |
| **v27** (2026-07-23) | Device codec gate: `MediaCodecList` decides what your hardware can actually decode and unplayable links are never offered (kills ExoPlayer **4003** on TVs); all HLS masters expanded into per-resolution rows; codec tags simplified |
| **v26** (2026-07-23) | Self-descriptive link names: “Source · Server — audio/codec tags” in the player switcher |
| **v25** (2026-07-22) | Health sweep: removed 4 dead embed hosts + SmashyStream extractor; embed-page fallback links removed (3003 fixes); FlixHub icon; README/repo.json reorganised |
| v24 (2026-07-22) | Added **Bingr** (7 servers) + **Moonflix** (3 backends incl. Hindi-dub mp4 ladder); Cineby re-verification |
| v23 | Movie sources trimmed to BDIX + Cineby; Miruro removed |
| v22 | **Allmanga** added (AllAnime family, self-bootstrapping crypto) |
| v21 | TokyoInsider resolver (direct MKV/MP4 anime downloads) |
| v20 | BDIX fuzzy-pick revert (stability) |
| v19 | Mkissa AA-crypto bypass; UniqueStream · AniNeko · ReANIME; probe gate |
| v18 | Codec tagging groundwork for TV decoder errors |

---

## ❓ Questions or a broken title?

Open an issue with: the extension + version, what you searched for, which
episode, and whether the same title works in the matching standalone
extension. “Works on website X but not in the app” reports are the most
valuable — they usually mean the site changed something we can port.
