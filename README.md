# 🎬 Wizdier Cloudstream Repository

> **8 Cloudstream extensions** — one unified catalogue plugin (movies · TV ·
> anime) plus seven standalone BDIX source extensions for Bangladeshi networks.
> Movies, web-series, anime, animation, cartoons & documentaries — Bangla,
> Hindi, English, Korean, Japanese and more. Health-checked **2026-07-25**.

[![Cloudstream](https://img.shields.io/badge/Cloudstream-extension-blueviolet?logo=android)](https://github.com/recloudstream/cloudstream)
[![Extensions](https://img.shields.io/badge/extensions-8-success)](#-the-extensions)
[![Latest build](https://img.shields.io/badge/Wizstream-v43-orange)](#-changelog)

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
> The seven standalone extensions are the same sources as separate apps, for
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
| 7 | **FM FTP** | bn | Movies & TV shows via JSON API + direct file index | `fmftp.net` |
| 8 | **Mediaserver** | bn | Movies & single episodes from a WordPress video library | `103.225.94.27/mediaserver` (BDIX) |

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
| **BDIX resolvers** | Cineplex BD · FTPBD · Circle FTP · CTGMovies · FM FTP · Mediaserver — searched by exact/alt title with strict + relaxed matching |
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
├── FM FTP/        standalone (fmftp.net API + file index)
├── Mediaserver/   standalone BDIX (WordPress video library)
├── icons/         repo artwork
├── repo.json      repository index (what you paste into CloudStream)
└── .github/workflows/build.yml   CI: build → builds branch
```

---

## 📜 Changelog

Moved to **[CHANGELOG.md](CHANGELOG.md)** — the full version history (v1 → latest) lives there now.

---

## ❓ Questions or a broken title?

Open an issue with: the extension + version, what you searched for, which
episode, and whether the same title works in the matching standalone
extension. “Works on website X but not in the app” reports are the most
valuable — they usually mean the site changed something we can port.
