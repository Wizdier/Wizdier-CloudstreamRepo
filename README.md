# 🎬 Wizdier CloudStream Repository

> **Free movies, series & anime for CloudStream — built for Bangladesh.**
> Eight extensions, led by **Wizstream**: one install that gives you a full
> Movies / TV / Anime catalogue backed by BDIX servers (fast and usually
> free of data charges on Bangladeshi ISPs) with worldwide web sources as
> backup.

[![CloudStream](https://img.shields.io/badge/CloudStream-extension-blueviolet?logo=android)](https://github.com/recloudstream/cloudstream)
[![Extensions](https://img.shields.io/badge/extensions-8-success)](#-whats-inside)
[![Latest build](https://img.shields.io/badge/Wizstream-v89-orange)](CHANGELOG.md)

---

## 📥 Install in 2 minutes

1. Install [**CloudStream**](https://github.com/recloudstream/cloudstream/releases) on your Android phone, TV or box.
2. Open **Settings → Extensions → Add repository** and paste:

   ```
   https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/main/repo.json
   ```

3. Open the repo, tap **Wizstream → Install**. Done.

**That single install gives you both catalogues** — the main Movies/TV
catalogue *and* the dedicated Anime catalogue. You don't need to hunt for a
second extension; they ship bound together and share one settings screen.

> ⚠️ **Migrating from v81 or earlier?** If you previously installed the
> separate **Wizstream Anime** extension, uninstall it after updating
> (*Settings → Extensions → Wizstream Anime → Delete*). The single v82
> Wizstream extension registers the same `Wizstream` and `Wizstream-Anime`
> sources — keeping the old package would show the anime catalogue twice.

---

## ✨ What you get

| | |
|---|---|
| 🇧🇩 **BDIX first** | Six Bangladeshi FTP servers (Circle FTP, Cineplex BD, FTPBD, CTGMovies, FM FTP, Mediaserver). On a BDIX ISP these stream fast and usually don't count against your data quota. |
| 🌍 **Web backup** | Cineby, Bingr, Moonflix and the Vid[x] embed family, so you still get a link when BDIX doesn't have the title. |
| 🍥 **Real anime catalogue** | A dedicated AniList-powered catalogue: proper per-season entries, Japanese voice-actor cast, and three anime-specific streaming sources (AniNeko, KickAssAnime, AnimeX). |
| 📺 **Correct episodes** | Multi-season packs, split cours and hour-long specials are mapped to the right files — the messy part of BDIX anime, handled. |
| 🔤 **Subtitles anywhere** | Optional Wyzie Subs integration adds subtitles even to raw BDIX `.mkv` files that carry none. |
| ⭐ **Ratings** | Optional MDBList integration shows IMDb, Rotten Tomatoes, Metacritic and MAL scores together on the page. |
| 🔄 **Tracking** | Syncs progress with AniList, MAL, Kitsu and Simkl. |
| ⚡ **Web-like fast start** | Streams that ladder by quality are listed lowest-first, so playback starts light and ramps up — just like a web player — instead of stalling on a heavy 1080p file your line can't hold yet. |
| 🛟 **Honest fallback** | Links are listed exactly as servers publish them (no fake "verified" checks). If one pick stalls on a dead box, tap the next link for that episode — recovery is one tap, not minutes. |

---

## 🗂️ What's inside

**Wizstream** — the one to install. Movies, TV, Asian drama, cartoons and a
full anime catalogue in a single extension, with every source below built in.

**Seven standalone extensions** — Circle FTP, Cineplex BD, CTGMovies, FTPBD,
FlixHub, FM FTP and Mediaserver, each as its own small app. These are the
same BDIX sources Wizstream already includes, published separately for people
who prefer one-site-per-extension or want to compare results side by side.

---

## ⚙️ Settings

Go to **Settings → Extensions → Wizstream → Open Settings** to:

- switch individual sources on or off — grouped under 📡 BDIX, 🌐 Web and
  🎌 Anime-web, each labelled with what it actually serves (handy for
  trimming slow ones, or going BDIX-only when you're off Wi-Fi),
- search the source list,
- paste optional API keys for **Wyzie Subs** (subtitles) and **MDBList**
  (ratings).

Both integrations are **off until you add your own free key**, and keys are
stored only on your device. Everything applies on the next episode you
tap — no restart.

---

## ❓ FAQ

**Do I need a BDIX connection?**
No, but it's the point of this repo. Without BDIX the Bangladeshi servers
won't reach and you'll fall back to the web sources.

**Nothing plays / no links found.**
Check you're on your BDIX ISP, then open Settings and confirm the sources
aren't switched off. If one server is down, another usually has the title.

**Why do I need my own API keys?**
Wyzie and MDBList issue free per-user keys (1,000 requests/day each) and
their terms don't allow shipping a shared key inside an app. Both features
are entirely optional.

**Is this legal?**
These extensions only *index* publicly reachable servers — no content is
hosted, uploaded or owned here. You are responsible for what you access.

---

## 🔧 For developers

Kotlin, built with the [recloudstream](https://github.com/recloudstream) Gradle plugin (AGP 8.7.3 / Kotlin 2.3.0, `compileSdk 35`).

```bash
./gradlew :Wizstream:make          # builds Wizstream/build/Wizstream.cs3
```

`WizstreamSources.kt` is the shared link-resolving engine. Version history
lives in [CHANGELOG.md](CHANGELOG.md).

---

<sub>Not affiliated with CloudStream, TMDB, AniList, MDBList or any listed
site. Made in Dhaka 🇧🇩</sub>
