# WizStreamPlay – MultiAPI Extension like StreamPlay (Dual Provider)

> **Two extensions in one** – just like phisher98's StreamPlay (StremioX + StremioC), this plugin registers **WizPlay - TMDB** (movies/tv) + **WizPlay - AniList** (anime) in a single .cs3 file.

## Architecture (Precious, Robust, Efficient)

### Dual Enrichment – TMDB + AniList for every provider (your request)

**In TMDB provider:**
- Catalog/Search via TMDB `trending/all/day`, `trending/movie/week`, `trending/tv/week`, `discover/movie?with_original_language=bn/hi`, `discover/tv?with_original_language=ko`
- Load: TMDB detail `movie/{id}?append_to_response=credits,external_ids,videos,images` or `tv/{id}?append_to_response=...`
- **AniList fallback enrichment**: if genres contains Animation or title hints anime, calls `WizEnrich.anilist(title)` GraphQL to get `anilistId`, `malId`, cover extraLarge, banner, description, averageScore.
- LoadResponse sets: poster (TMDB w500 or AniList extraLarge), backdrop, plot, year, rating, tags, trailer YouTube, `addImdbId`, `addAniListId`, `addMalId`

**In AniList provider:**
- Catalog: AniList GraphQL trending/popular/top/airing/movie with sort TRENDING_DESC/POPULARITY_DESC/SCORE_DESC
- Search: GraphQL `Page media(search, type:ANIME)`
- Load: GraphQL `Media(id) { id idMal title coverImage bannerImage description averageScore genres episodes duration format studios characters trailer }`
- **TMDB fallback enrichment**: tries `WizEnrich.tmdbTv(title) ?: tmdbMovie(title)` to get tmdbId, imdbId, poster w500, actors – dual enrichment reverse
- Episodes built from `episodes` count, or 1 for movies
- Sets both AniList + MAL + IMDb IDs

**Cache:** `WizCore.TtlCache<String, EnchrichedMeta>(10min, 256 max)` with sweep every 32 puts – fixes memory leak.

### MultiAPI Aggregator – fetches ALL sources present in your 5 extensions + vid[x]

**Your 5 extensions as sources** (implemented in `WizExtractors.kt`):
- `invokeCineplexBD` – searches cineplexbd.net, extracts m3u8/mp4
- `invokeCircleFTP` – hits `new.circleftp.net:5000/api/v1/search?query` then post detail download_links
- `invokeCTG` – `cockpit.103.109.92.178.nip.io/api/v1/movies?search=` then links array
- `invokeFTPBD` – searches ftpbd.net, regex mp4/mkv/m3u8
- `invokeMovies67` – already via vid[x] but placeholder

**vid[x] family** (x = src, nest, rock, fast, easy, zee, prime):
- **vidSrcTo** – `https://vidsrc.to/embed/movie/{tmdbId}` / `tv/{id}/{s}/{e}` → `loadExtractor`
- **vidSrcCc** – `https://vidsrc.cc/v2/embed/movie/{id}` / `tv/...`
- **embedSu** – `https://embed.su/embed/movie/{id}`
- **vidLinkPro** – `https://vidlink.pro/movie/{id}`
- **autoEmbed** – `https://autoembed.cc/movie/tmdb/{id}`
- **vidNest** – `vidnest.fun` / `vidnest.to` movie/tv patterns
- **vidRock** – `vidrock.net` + direct API `vidrock.ru/api/movie/{id}` parsing sources JSON
- **vidFast** – `vidfast.vc` / `vidfast.pro` (CSX `vidfastProApi`)
- **vidEasy** – WingsDatabase 8-server (jett=Yoru, cdn=Yoru, tejo=Tejo, neon2=Neon, ym=Sage, downloader2=Cypher, m4uhd=Breach, hdmovie=Vyse) – seed fetch → `api.wingsdatabase.com/{key}/sources-with-title?title&mediaType&year&...&seed` → POST `enc-dec.app/dec-videasy` → sources+subtitles (most robust, like Movies67)
- **vidZee** – `player.vidzee.wtf/api/movie/{id}` → streams array
- **primeSrc** – `primesrc.me/api/movie/{id}`

**ProviderRegistry** – `ProviderDef(key, displayName, execute)` list of 15 providers, executed concurrently via `WizCore.parallelMapNotNull(concurrency=6)` like CSX `invokeAllSources`

**StreamPlay MultiAPI Stremio aggregator** (`WizStremio.kt`):
- Default addons: `torrentio.strem.fun/limit=4`, `torrentsdb.com/...`, `comet.elfhosted.com`, `mediafusion.elfhosted.com`
- `fetchStreams(imdbId, type, season, episode, addons)` → queries `/{base}/stream/{type}/{imdbId}.json` or `/{imdbId}:{s}:{e}.json` concurrently with semaphore 4, parses `streams[] {title, url, behaviorHints}`
- Converts to `ExtractorLink` via `newExtractorLink` + `getQualityFromName`
- Fallback if local vid[x] fails – exactly StreamPlay's torrent fallback

### Robustness & Efficiency (Precious)

- **Semaphore(8)** bounded parallelism – prevents socket exhaustion, TMDB 429
- **Jittered retry** 3× 400ms base ±25% jitter max 8s, re-throws CancellationException
- **TTL+LRU cache** 256 max, 10min TTL, sweep every 32 puts – memory flat
- **Deduplication** `linkedSetOf` for links + subtitles
- **M3u8Helper** for HLS + subtitle harvesting
- **Simplified title** emoji tags (4K 💿, 1080p 🌟, WEB-DL ☁️, etc) from CSX SpecOption
- **SupervisorScope** isolation – one failing extractor doesn't crash others
- **Fan-out search** `coroutineScope { async }` for movie+tv concurrent (halves latency)
- **Parallel season episodes** concurrency 4 for TV seasons

### Two extensions in one

`WizPlayPlugin.kt`:
```kotlin
@CloudstreamPlugin
class WizPlayPlugin : BasePlugin() {
  override fun load() {
    registerMainAPI(WizTmdbProvider())      // TMDB catalog
    registerMainAPI(WizAnilistProvider())   // AniList catalog
  }
}
```
- Install single .cs3, you get two providers in Extensions list
- Like StreamPlay's StremioX + StremioC dual
- Both share same `WizExtractors` registry + `WizStremio` aggregator

### Build

```bash
./gradlew :WizStreamPlay:make
# outputs builds/WizStreamPlay.cs3
```

Add to `repo.json` manually or via CI.

### Usage

- **TMDB provider**: search movies/tv, load, it will call all 15 providers + stremio torrentio fallback
- **AniList provider**: search anime, same multiAPI

All links deduplicated, quality labeled.

