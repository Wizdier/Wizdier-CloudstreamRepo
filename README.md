# 🎬 Wizdier Cloudstream Repository

A collection of **Cloudstream** extensions for BDIX / Bangladeshi streaming sources.

Provides movies, web-series, anime, animation and TV shows — Bangla, Hindi,
English, Korean, Japanese, Chinese and more.

---

## 📦 Extensions

| Extension | Content | Sources |
|-----------|---------|---------|
| **Wizstream** *(unified)* | Movies, TV series, anime, OVA — TMDB + AniList catalogues in one plugin | Vid[x] family (vidsrc, vidnest, vidplay, vidup, vidrock, vidfast, videasy) + 4 BDIX sites bundled |
| **Circle FTP** | Movies, TV series, anime, animation, documentaries, Asian dramas | `new.circleftp.net` |
| **CTGMovies** | Movies, TV shows, anime | `ctgmovies.com` |
| **Cineplex BD** | Movies, anime, web-series, cartoons | `cineplexbd.net` |
| **FTPBD** | Movies, TV shows, anime movies | `ftpbd.net` |

### 🆕 Wizstream — unified catalogue plugin

A single `.cs3` plugin file that registers **two MainAPI providers** from one
Cloudstream plugin (the "streamplay" pattern):

1. **Wizstream** — TMDB catalogue (movies & TV). Trending / Popular / Top-Rated
   / Upcoming / Now Playing / On The Air.
2. **Wizstream-Anime** — AniList catalogue (anime / OVA / movies). Trending /
   Popular This Season / Top Rated / Upcoming / All-Time Popular.

Both providers share a single `WizstreamSources` resolver bundle, so every
`loadLinks` call attempts **all** of these in parallel:

- **Vid[x] family** (7 explicit hosts): `vidsrc`, `vidnest`, `vidplay`,
  `vidup`, `vidrock`, `vidfast`, `videasy`.
- **Extended VidSrc family** (12 more): VidSrc.icu / VidSrc.to / VidSrc.me /
  VidSrc.xyz / VidSrc.mov / VidBinge / VidJoy / 2Embed / MultiEmbed /
  SuperEmbed / Gomo / SmashyStream / VidAPI / VAPlayer / ApiPlayer /
  111Movies / Remotestream / AutoEmbe.
- **4 BDIX source sites** (bundled resolvers): Cineplex BD · FTPBD ·
  Circle FTP · CTGMovies — each searched by title, best-matched, then
  detail-page-scraped for direct media URLs + iframe embeds.
- **Anime-specific** (Wizstream-Anime only): AllManga · AnimeStream · ZoroAnime.

Duplicate URLs are de-duped globally across all sources.

## ✨ Features

- 🚀 **Fast & responsive** — parallel fetching, TTL metadata caching, and
  bounded retries with exponential back-off keep every extension snappy.
- ⏱️ **Hard timeouts** on every network call so a slow or dead server never
  freezes the UI.
- 🖼️ **Rich metadata** — posters, backdrops, title logos, trailers, ratings,
  cast/voice-actor data, and per-episode stills & synopses via **TMDB**,
  **AniList**, and **ani.zip**.
- 🔗 **Multi-source support** — multiple releases / audio tracks for the same
  title are grouped together automatically.
- 📺 **Tracker sync** — **MAL**, **AniList**, **Kitsu**, and **Simkl** (via IMDb)
  integration where applicable.
- 🎚️ **Quality detection** — sources are auto-tagged (4K / 1080p / WEB-DL / etc.)
  from filenames and metadata.
- 🧩 **Unified plugin** — `Wizstream.cs3` ships both TMDB + AniList catalogues
  in one file, mirroring phisher98's streamplay architecture.

## 🔧 Installation

### Add the repository to Cloudstream

```
https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/builds/plugins.json
```

1. Open **Cloudstream** → **Settings** → **Extensions** → **Repositories**
2. Tap **➕** and paste the URL above
3. Install the extensions you want from the list

### Build from source

```bash
# Requires JDK 21+ and Android SDK (compileSdk 35)
./gradlew make        # build all .cs3 files
./gradlew :Wizstream:make   # build only the unified Wizstream plugin
```

Built extensions (`.cs3` files) are output to each module's `build/`
directory (e.g. `Wizstream/build/Wizstream.cs3`).

> **CTGMovies note:** some content requires authentication. Open the extension's
> settings to enter an email/password (auto-login), a `ctg.token` / Bearer
> token, or a raw `Cookie` header for protected content.

## 🏗️ Tech Stack

- **Kotlin** + **Coroutines** (concurrent fetches)
- **Cloudstream Plugin** framework (`com.lagradost.cloudstream3`)
- **Gradle** (Kotlin DSL) with the recloudstream plugin
- **Jsoup** for HTML parsing · **org.json** for JSON · **NiceHttp** for HTTP
- **TMDB / AniList / ani.zip** for metadata enrichment

## 📂 Project Structure

```
Wizdier-CloudstreamRepo/
├── Wizstream/        # Unified plugin: WizstreamProvider (TMDB) +
│                     # WizstreamAnimeProvider (AniList) + WizstreamSources
│                     # (4 BDIX resolvers + Vid[x] family)
├── Circle FTP/       # CircleFtpProvider + CircleFtpHttp (resilience layer)
├── CTGMovies/        # CTGMovies + CTGConcurrent (concurrency/cache)
├── Cineplex BD/      # CineplexBD + MetadataEnricher + CineplexConcurrent
├── FTPBD/            # FTPBD + FTPBDConcurrent
├── icons/            # WizstreamIcon.png + WizstreamAnimeIcon.png
├── build.gradle.kts  # Root config
└── repo.json         # Repository manifest
```

## 📄 License

Provided as-is for personal use.

---

<div align="center">

Made with ❤️ by **Wizdier**

</div>
