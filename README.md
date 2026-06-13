# 🎬 Wizdier Cloudstream Repository

A collection of **Cloudstream** extensions for BDIX / Bangladeshi streaming sources.

Provides movies, web-series, anime, animation and TV shows — Bangla, Hindi,
English, Korean, Japanese, Chinese and more.

---

## 📦 Extensions

| Extension | Content | Sources |
|-----------|---------|---------|
| **Circle FTP** | Movies, TV series, anime, animation, documentaries, Asian dramas | `new.circleftp.net` |
| **CTGMovies** | Movies, TV shows, anime | `ctgmovies.com` |
| **Cineplex BD** | Movies, anime, web-series, cartoons | `cineplexbd.net` |
| **FTPBD** | Movies, TV shows, anime movies | `ftpbd.net` |

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
# Requires JDK 17+ and Android SDK (compileSdk 35)
./gradlew assembleDebug
```

Built extensions (`.cs3` files) are output to each module's
`build/outputs/apk/debug/` directory.

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
├── Circle FTP/      # CircleFtpProvider + CircleFtpHttp (resilience layer)
├── CTGMovies/       # CTGMovies + CTGConcurrent (concurrency/cache)
├── Cineplex BD/     # CineplexBD + MetadataEnricher + CineplexConcurrent
├── FTPBD/           # FTPBD + FTPBDConcurrent
├── .github/         # GitHub Actions CI (build.yml)
├── build.gradle.kts # Root config
└── repo.json        # Repository manifest
```

## 📄 License

Provided as-is for personal use.

---

<div align="center">

Made with ❤️ by **Wizdier**

</div>
