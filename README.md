# 🎬 Wizdier Cloudstream Repository

> **6 Cloudstream extensions** — one unified catalogue plugin (movies · TV ·
> anime) plus five standalone BDIX source extensions.
> Movies, web-series, anime, animation, cartoons & documentaries — Bangla,
> Hindi, English, Korean, Japanese and more. Health-checked **2026-07-22**.

[![Cloudstream](https://img.shields.io/badge/Cloudstream-extension-blueviolet?logo=android)](https://github.com/recloudstream/cloudstream)
[![Extensions](https://img.shields.io/badge/extensions-6-success)](#-extensions-index)
[![Latest build](https://img.shields.io/badge/Wizstream-v31-orange)](#-changelog)

---

## 📥 Install this repository

In **CloudStream → Settings → Extensions → Add repository**, paste:

```
https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/main/repo.json
```

Then you'll see every extension below in the repo's extension list.
CI rebuilds and republishes the `builds` branch (with fresh `plugins.json`
and all `.cs3` files) on every push.

---

## 🗂️ Extensions Index

| # | Extension | Version | Lang | Content types | Source host(s) | Today's health |
|---|-----------|:-------:|:----:|---------------|----------------|:--------------:|
| 1 | **Wizstream** ⭐ | v31 | en | Movie · TvSeries · Anime · AnimeMovie · OVA | Multi-source (see below) | ✅ |
| 2 | **Cineplex BD** | v8 | bn | Movie · TvSeries · Anime · AnimeMovie · Cartoon | `cineplexbd.net` | ✅ BDIX |
| 3 | **Circle FTP** | v8 | en | Movie · TvSeries · Anime · AnimeMovie · Cartoon · AsianDrama · Documentary · OVA | `new.circleftp.net` | ✅ BDIX |
| 4 | **CTGMovies** | v4 | bn | Movie · TvSeries · Anime · AnimeMovie | `ctgmovies.com` | ✅ |
| 5 | **FTPBD** | v3 | en | Movie · TvSeries · AnimeMovie | `ftpbd.net` | ✅ |
| 6 | **FlixHub** | v2 | en | Movie · TvSeries (Hollywood · Bollywood · South-Indian · anime/animation sections) | `flixhub.net` | ✅ BDIX |

> **About "BDIX" health marks:** `cineplexbd.net`, `new.circleftp.net` and
> `flixhub.net` live behind Bangladeshi ISP peering (one of them is even on a
> private `15.1.1.50` intranet address). They time out from outside BD — that
> is **expected** and *not* an error; from a Bangladeshi connection they work.
> "Today's health" for BDIX sites = reachable & serving from a BD network.

---

## ⭐ Wizstream in depth

One `.cs3` registers **two catalogue providers**:

| Provider | Catalogue | Pages |
|----------|-----------|-------|
| **Wizstream** | TMDB (movies & TV) | Trending · Popular · Top Rated · Upcoming · Now Playing · On The Air |
| **Wizstream-Anime** | AniList (anime, OVA, movies) | Trending · Popular This Season · Top Rated · Upcoming · All-Time Popular |

Trackers: **MAL · AniList · Kitsu · Simkl** sync where applicable. Posters,
backdrops, logos, trailers, ratings, cast & per-episode stills via TMDB,
AniList and ani.zip.

### 🎥 Movie / TV link pipeline (parallel, de-duplicated)

| Group | Sources |
|-------|---------|
| **BDIX resolvers** | Cineplex BD · FTPBD · Circle FTP · CTGMovies (fuzzy title-match ≥ 0.4 + year ±1, direct/iframe extraction) |
| **Cineby** | 8 servers: Neon · Yoru (up to 4K) · Breach · Vyse (English HD) · Killjoy (German) · Fade (Hindi) · Omen (Spanish) · Raze (Portuguese) — encrypted `sources-with-title` API + seed + decrypt service |
| **Bingr** (new in v24) | 7 scraper servers: Sirius (Global) · Elysium · Miller · Mann · Edmunds · Luna · Aditya (IN) — every link range-probed before it shows |
| **Moonflix** (new in v24) | 3 backends: multi-audio mp4 ladder (English/**Hindi**/Original…) · HDGhar HLS · VIP/LUL HLS servers for TV + Stremio-track subtitles |
| **Vid-embed family** | VidSrc · VidNest · VidPlay · VidUp/VidLink · VidRock · VidFast · VidEasy (2embed) · MultiEmbed · SuperEmbed · DatabaseGdrive · VidAPI · VAPlayer · ApiPlayer |

### 🍥 Anime link pipeline (Wizstream-Anime only)

AniZone · **Allmanga** (AllAnime-family persisted API, live-bootstrapped
aaReq AES-GCM crypto with 3-tier key fallback) · AniChi · UniqueStream ·
AniNeko · ReANIME · TokyoInsider (direct MKV/MP4 downloads)

Sub/dub aware (Allmanga emits separate `· DUB` batches). AVI/WMV containers
are excluded on purpose.

### 🏷️ Label conventions in the source picker

```
Wizstream  • Cineby · Yoru          ← source group (server)
           ├── 1080p · Original · H.264   ← quality · audio · codec tags
Wizstream-A • Allmanga · Ak · DUB         ← anime source + dub batch
[Neon] English                          ← subtitles prefixed with their server
```

- `· H.264` — TV-safe codec &nbsp;·&nbsp; `· HEVC ⚠` / ` · AV1 ⚠` — no hardware
  decode on many smart TVs
- `· DUB`, `· Hindi`, `· German`, … — audio language
- `· HLS` — adaptive stream

### 🛡️ Error hygiene (no 2004 / 3003 / 4003 cascades)

- **Probe gate** — direct links are verified with a `Range: bytes=0-511`
  request before they appear in your list; 4xx/5xx and HTML bodies are
  dropped silently *(kept-alive links on timeouts, so a slow BDIX server
  never loses you a link)*.
- **Codec tags** from live HLS-master / MP4-header probing so you can avoid
  decoders your device lacks (kills ExoPlayer 4003 on old TVs).
- **No embed-page links ever** — v25 removes the legacy fallback that could
  emit a raw HTML player page as a stream (root cause of 3003-style errors).
- **Dead-host policy** — every health sweep, broken non-BDIX hosts are
  *removed*, not just hidden.

---

## 🧹 v25 removals (health sweep 2026-07-22)

Removed because their hosts are dead/unreachable today:

- `SmashyStream` (embed.smashystream.com — broken TLS) → host + extractor
- `AutoEmbe` (autoembe.xyz — connection refused)
- `AnimeStream` (anicdn.stream — unreachable)
- `ZoroAnime` (hianime.to — hangs indefinitely)

Broken-upstream servers that an operator can still fix (e.g. Cineby's
Vyse/Fade/Raze, Allmanga's Ak/Uv/Luf clock hosts) stay in code and skip
gracefully until they recover — they cost nothing while dead.

---

## ✨ Features

- 🚀 Parallel fetching everywhere, TTL metadata caches, bounded retries
  with exponential back-off.
- ⏱️ Hard timeouts on every call — a dead server can never freeze the UI.
- 🖼️ Rich metadata: posters, backdrops, logos, trailers, ratings, cast,
  per-episode stills & synopses.
- 🔗 Multi-release grouping — several encodes/audio tracks of the same
  title grouped under one server label.
- 🎚️ Quality tags (4K/1080p/720p…) detected from HLS variants, filenames
  and API fields.
- 🔐 Cineby account/token settings for protected content.

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
├── icons/         repo artwork (FlixHubIcon, WizstreamIcon, WizstreamAnimeIcon)
├── repo.json      repository index (what you paste into CloudStream)
└── .github/workflows/build.yml   CI: build → builds branch
```

---

## 📜 Changelog

| Version | Highlights |
|---------|-----------|
| **v31** (2026-07-23) | **BDIX parity fixes** ("standalone works, Wizstream doesn't"): (1) **FTPBD TV/anime** — episode permalinks are slug-based (`/episodes/romance-dawn/`) with no S/E numbers, so the ported matcher never matched and FTPBD emitted nothing for series; now scrapes the numbered grid at `<series>/episodes/?season=N` like the standalone (verified live: One Piece S1E5 → S01E05 HLS, Dune → 3 links). (2) **Identity gate relaxation** — BDIX posts are tagged with audio/rip words ("ONE PIECE Hindi Dubbed", "Dune Bengali Dubbed 720p", "SPY x FAMILY") which the strict token gate rejected wholesale; audio/language/rip decorations + "x"/"movie"/"film" joiners now count as same-media, and single-letter roman numerals no longer corrupt titles ("SPY×FAMILY" ≠ "SPY 10 FAMILY"). (3) **Anime alternate-title search** — the four BDIX resolvers now run a second pass with the AniList alt title (romaji ↔ English), so "Sousou no Frieren" also searches "Frieren: Beyond Journey's End". (4) **CineplexBD TV robustness** — mirrored the standalone's fallbacks: season-probe for single-season anime filed under odd season numbers + HTML episode-anchor scraping when `meta=1` yields nothing. **Cast data expansion**: anime detail pages now show up to 25 main+supporting characters with **both Japanese and English voice actors** (was 10 mains, JA-only), and the role label shows Main/Supporting properly |
| **v30** (2026-07-23) | **Cineby rebuilt for the site's June-2026 relaunch** — the "Yoru still broken" report turned out to be upstream, and bigger than Yoru: Cineby lost its domains (cineby.ru is a parked "for sale" page; the official site is now **cineby.at**) and the whole sources backend was re-engineered. The old pipeline the extension used (per-server calls + the enc-dec.app decryption service) was retired — the site now calls ONE aggregate endpoint (`/mbx/sources-with-title`) and decrypts the answer **in the browser** with a small custom stream cipher ("mvm1"). This update ports that cipher 1:1 into Kotlin — verified byte-for-byte against the site's own JavaScript — and rewires Cineby to the new aggregate endpoint (with the old per-server endpoints kept as fallback, same local decryption). **Yoru's CDN domain itself (cdntv.one) expired and is parked by the registrar**, so no client can play it right now; the extension now *drops* any stream whose host answers an HTML ad-wall instead of a playlist, so dead servers vanish from the picker instead of erroring, and Yoru will silently reappear if the provider resurrects it on a new domain. Subtitles for Cineby picks now come from the site's subtitle service (`subs.videasy.to`) with `[Videasy]` / `[Cineby]` labels |
| **v29** (2026-07-23) | **Anime title-mismatch fix** (the "Allmanga almost never appears" bug): AniList feeds the extension *English* titles while the AllAnime index uses *romaji* (`Sousou no Frieren`) or aliases like `1P` — a 0.17 similarity meant Allmanga silently gave up and AniNeko was all you ever saw. Verified live: "One Piece" now matches `1P` via its `englishName` alias and resolves 1171 episodes; Frieren/Chainsaw/Fruits Basket likewise. The extension now matches every candidate against **server-side aliases (name/englishName/nativeName) × both AniList titles (english/romaji)**, and every other anime source retries its search with the romaji alias before giving up |
| **v28** (2026-07-23) | **Neon resurrection + buffering insight**: fixed scheme-less junk-CDN playlist URIs (`host.tld/…`) that had been mangled by the URL resolver — Cineby's Neon (and any sibling server using them) emitted 404 links for weeks and silently left Yoru as the only working server; demuxed HLS masters (separate `#EXT-X-MEDIA` audio groups) are now emitted as the adaptive master so video+audio stay muxed (expanding those variant-by-variant would have produced silent video); failover duplicate variants collapsed. **Homepage fix**: `tv/popular`-style paths have no leading slash, so 3 of 4 TV rows were parsed as movies and rendered empty — homepage now shows Trending/Popular/Top Rated/Airing Today/On The Air TV rows. **Anime episodes un-capped**: AniList reports `episodes=null` for long runners (One Piece, Conan); the `?: 12` fallback chopped them to one cour — episode count now comes from max(final count, nextAiring−1, streamingEpisodes) and an "Airing Now" row was added |
| **v27** (2026-07-23) | **TV 4003 fix**: the extension now asks the device's `MediaCodecList` which codecs *and resolutions* it can hardware-decode, and simply never offers a link the device can't play (2160p variants on 1080p TVs, over-level widescreen H.264 like 2580×1080@L3.2, HEVC/AV1 on hardware without those decoders). All HLS masters (Cineby · Bingr · Moonflix HDGhar/VIP) are expanded into explicit per-resolution rows so adaptive "Auto" links can't smuggle 4K onto old TVs. Codec tags simplified (`· H.264`/`· HEVC` — the ⚠ is gone because unplayable ones are filtered out instead) |
| **v26** (2026-07-23) | Self-descriptive link names: every stream now shows "Source · Server — audio/codec tags" in the player switcher; duplicate quality text removed |
| **v25** (2026-07-22) | Full-repo health sweep: removed 4 dead embed hosts + SmashyStream extractor; removed embed-page fallback links (3003 fixes); FlixHub icon added; README & repo.json reorganised |
| v24 (2026-07-22) | Added **Bingr** (7 servers) and **Moonflix** (3 backends incl. Hindi-dub mp4 ladder); Cineby server re-verification |
| v23 | Movie sources trimmed to BDIX + Cineby (removed Aether); Miruro removed from anime sources |
| v22 | Mkissa removed → **Allmanga** added (AllAnime family, self-bootstrapping crypto) |
| v21 | TokyoInsider resolver (direct MKV/MP4 anime downloads) |
| v20 | BDIX fuzzy-pick revert (stability); Aether source (removed in v23) |
| v19 | Mkissa AA-crypto bypass; UniqueStream · AniNeko · ReANIME anime resolvers; probe gate |
| v18 | Codec tagging (HEVC ⚠/AV1 ⚠) against decoder errors on older TVs |
