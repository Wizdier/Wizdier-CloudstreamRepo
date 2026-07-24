package com.wizdier

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * WizstreamProvider — TMDB catalog + multi-source resolver.
 *
 * Catalogue:
 *   • The Movie Database (TMDB) v3 public API for Trending / Popular /
 *     Top-Rated / Upcoming / Now Playing / On The Air (TV).
 *
 * Source resolution (all tried concurrently, per-episode):
 *   • Vid-src family embeds (vidsrc.icu / vidsrc.to / vidsrc.mov / vidsrc.me /
 *     vidbinge.com / vidjoy.to) — movie/{imdbOrTmdb} + tv/{imdbOrTmdb}/{s}/{e}.
 *   • Two-embed / 2embed.cc & multiembed / moviesapi / superembed / ezvidapi.
 *   • DatabaseGdriveplayer / gomo / vidsrc.net variants.
 *   • Auto-extract via Cloudstream's built-in `loadExtractor` against every
 *     resolved iframe so any future host is still picked up.
 *
 * Sources are added per-embed with unique labels so duplicates are de-duped
 * by URL at the end.
 */

// ─── File-level constants & helpers (visible to companion objects & lambdas) ───
private const val TMDB_API = "https://api.themoviedb.org/3"
private const val TMDB_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"
private const val IMG = "https://image.tmdb.org/t/p"
private const val WZ_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

private fun String?.toTmdbImg(size: String): String? =
    this?.takeIf { it.isNotBlank() && it != "null" }?.let { "$IMG/$size$it" }

private fun yearFromDate(d: String?): Int? =
    d?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

private fun parseAirDateWz(s: String?): Long? {
    if (s == null) return null
    val parts = s.split("-")
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    return runCatching {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.clear(); c.set(y, m - 1, d, 0, 0, 0); c.timeInMillis
    }.getOrNull()
}

private fun JSONArray.toStringListWz(key: String): List<String> =
    (0 until length()).mapNotNull { i ->
        optJSONObject(i)?.optStringOrNullWz(key)
    }.distinct()

private fun JSONArray.toActorsWz(limit: Int = 20): List<ActorData> =
    (0 until length()).mapNotNull { i ->
        val c = optJSONObject(i) ?: return@mapNotNull null
        val name = c.optStringOrNullWz("name") ?: c.optStringOrNullWz("original_name")
            ?: return@mapNotNull null
        val profile = c.optStringOrNullWz("profile_path").toTmdbImg("w185")
        val role = c.optStringOrNullWz("character")
        ActorData(Actor(name, profile), roleString = role ?: "")
    }.take(limit)

private fun pickLogoWz(logos: JSONArray?): String? {
    if (logos == null || logos.length() == 0) return null
    var enSvg: String? = null; var anyPng: String? = null
    for (i in 0 until logos.length()) {
        val l = logos.optJSONObject(i) ?: continue
        val p = l.optString("file_path").takeIf { it.isNotBlank() } ?: continue
        val lang = l.optString("iso_639_1").trim().lowercase()
        val isSvg = p.endsWith(".svg", true)
        val url = "$IMG/w500$p"
        when {
            lang == "en" && !isSvg -> return url
            lang == "en" && isSvg && enSvg == null -> enSvg = url
            !isSvg && anyPng == null -> anyPng = url
        }
    }
    return enSvg ?: anyPng
}

private fun pickTrailerWz(videos: JSONArray?): String? {
    if (videos == null) return null
    var official: String? = null; var anyTrailer: String? = null
    for (i in 0 until videos.length()) {
        val v = videos.optJSONObject(i) ?: continue
        if (!v.optString("site").equals("YouTube", true)) continue
        val key = v.optStringOrNullWz("key") ?: continue
        val type = v.optString("type")
        when {
            type.equals("Trailer", true) && v.optBoolean("official", false) && official == null ->
                official = "https://www.youtube.com/watch?v=$key"
            type.equals("Trailer", true) && anyTrailer == null ->
                anyTrailer = "https://www.youtube.com/watch?v=$key"
        }
    }
    return official ?: anyTrailer
}

private fun JSONObject.optStringOrNullWz(k: String): String? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").trim().takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject.optIntOrNullWz(k: String): Int? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").toIntOrNull()
        ?: optInt(k, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

private fun JSONObject.optDoubleOrNullWz(k: String): Double? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").toDoubleOrNull()
        ?: optDouble(k, Double.NaN).takeIf { !it.isNaN() }

private fun String.normalizedWz(): String =
    lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

// Re-label an ExtractorLink while preserving url/type/quality/headers.
// The `ExtractorLink(...)` constructor is deprecated to ERROR level in the
// latest cloudstream3 stubs ("Use newExtractorLink"), so we delegate to the
// suspend `newExtractorLink` factory via `runBlocking`. The factory body
// performs no IO so this is fast and safe to call from non-suspend
// callbacks (e.g. inside `loadExtractor { link -> ... }`).
private fun ExtractorLink.relabel(newSource: String, newName: String): ExtractorLink =
    kotlinx.coroutines.runBlocking {
        newExtractorLink(
            source = newSource,
            name = newName,
            url = this@relabel.url,
            type = this@relabel.type,
        ) {
            this.referer = this@relabel.referer
            this.quality = this@relabel.quality
            this.headers = this@relabel.headers
        }
    }

private suspend fun <T, R> boundedParallelMapWz(
    items: List<T>,
    concurrency: Int = 6,
    block: suspend (T) -> R,
): List<R> {
    if (items.isEmpty()) return emptyList()
    val gate = Semaphore(concurrency)
    return coroutineScope {
        items.map { item ->
            async { gate.withPermit { block(item) } }
        }.awaitAll()
    }
}

class WizstreamProvider : MainAPI() {

    override var mainUrl = "https://www.themoviedb.org"
    override var name = "Wizstream"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Cartoon,
    )
    override val supportedSyncNames = setOfNotNull(
        SyncIdName.Imdb,
        runCatching { SyncIdName.valueOf("Simkl") }.getOrNull(),
        runCatching { SyncIdName.valueOf("Tmdb") }.getOrNull(),
        )

    companion object {
        private const val TAG = "Wizstream"

        private data class VidHost(
            val label: String,
            val movie: (String) -> String,
            val tv: (String, Int, Int) -> String,
            val referer: String = "",
        )

        private val VID_HOSTS: List<VidHost> = listOf(
            // ── Verified-reachable Vid[x] family (7 named hosts) ────────────
            // Each host was HTTP-tested on 2026-07-17 — see Wizstream-SOURCES.md.
            // URLs match phisher98's StreamPlay.cs3 patterns where possible.
            // All accept imdb (tt…) or tmdb (digits) ids in the path.
            VidHost("VidSrc",
                { id -> "https://vidsrc.to/embed/movie/$id" },
                { id, s, e -> "https://vidsrc.to/embed/tv/$id/$s/$e" }),
            VidHost("VidNest",
                { id -> "https://vidsrc.mov/embed/movie/$id" },
                { id, s, e -> "https://vidsrc.mov/embed/tv/$id/$s/$e" }),
            VidHost("VidPlay",
                { id -> "https://vidplay.site/embed/movie/$id" },
                { id, s, e -> "https://vidplay.site/embed/tv/$id/$s/$e" }),
            VidHost("VidUp",
                { id -> "https://vidlink.pro/movie/$id" },
                { id, s, e -> "https://vidlink.pro/tv/$id/$s/$e" }),
            VidHost("VidRock",
                { id -> "https://vidrock.ru/embed/movie?imdb=$id" },
                { id, s, e -> "https://vidrock.ru/embed/tv?imdb=$id&season=$s&episode=$e" }),
            VidHost("VidFast",
                { id -> "https://vidfast.pro/movie/$id" },
                { id, s, e -> "https://vidfast.pro/tv/$id/$s/$e" }),
            VidHost("VidEasy",
                { id -> "https://www.2embed.cc/embed/$id" },
                { id, s, e -> "https://www.2embed.cc/embedtv/$id&s=$s&e=$e" }),
            // ── Other verified hosts (kept for breadth) ────────────────────
            VidHost("MultiEmbed",
                { id -> "https://multiembed.mov/?video_id=$id&tmdb=1" },
                { id, s, e -> "https://multiembed.mov/?video_id=$id&tmdb=1&s=$s&e=$e" }),
            VidHost("SuperEmbed",
                { id -> "https://getsuperembed.link/?video_id=$id" },
                { id, s, e -> "https://getsuperembed.link/?video_id=$id&season=$s&episode=$e" }),
            VidHost("DatabaseGdrive",
                { id -> "https://databasegdriveplayer.co/player.php?imdb=$id" },
                { id, s, e -> "https://databasegdriveplayer.co/player.php?type=series&imdb=$id&season=$s&episode=$e" }),
            VidHost("VidAPI",
                { id -> "https://vidapi.ru/embed/movie/$id" },
                { id, s, e -> "https://vidapi.ru/embed/tv/$id/$s/$e" }),
            VidHost("VAPlayer",
                { id -> "https://vaplayer.ru/embed/movie/$id" },
                { id, s, e -> "https://vaplayer.ru/embed/tv/$id/$s/$e" }),
            VidHost("ApiPlayer",
                { id -> "https://apiplayer.ru/embed/movie/$id" },
                { id, s, e -> "https://apiplayer.ru/embed/tv/$id/$s/$e" }),
        )

        private val metaCache = ConcurrentHashMap<String, Pair<Long, TmdbDetail>>()
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Main pages
    // ═══════════════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "trending/movie/day" to "Trending Movies",
        "trending/tv/day" to "Trending TV Shows",
        "movie/popular" to "Popular Movies",
        "tv/popular" to "Popular TV Shows",
        "movie/top_rated" to "Top Rated Movies",
        "tv/top_rated" to "Top Rated TV Shows",
        "tv/airing_today" to "Airing Today",
        "tv/on_the_air" to "Currently On The Air",
        "movie/now_playing" to "Now Playing In Cinemas",
        "movie/upcoming" to "Upcoming Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        // (v28) Segment-based check — "tv/popular" has NO leading slash, so
        // the old contains("/tv/") test missed Popular/TopRated/OnTheAir and
        // those rows were parsed as movies (title=null) → always empty.
        val isTv = path.split("/").contains("tv")
        val json = tmdbGet("/$path", mapOf("page" to page, "language" to "en-US"))
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val results = json.optJSONArray("results") ?: JSONArray()
        val items = (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            if (isTv) tvToSearch(r) else movieToSearch(r)
        }
        val hasNext = page < (json.optInt("total_pages", 1))
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Search
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val results = runCatching { tmdbSearch(q) }.getOrDefault(emptyList()).toMutableList()

        return results.distinctBy { sr ->
            val year = when (sr) {
                is MovieSearchResponse -> sr.year
                is TvSeriesSearchResponse -> sr.year
                else -> null
            }
            "${sr.name.normalizedWz()}|$year|${sr.type?.name ?: ""}"
        }
    }

    private suspend fun tmdbSearch(query: String): List<SearchResponse> {
        val json = tmdbGet("/search/multi", mapOf(
            "query" to query,
            "include_adult" to false,
            "language" to "en-US",
        )) ?: return emptyList()
        val arr = json.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            when (r.optString("media_type")) {
                "movie" -> movieToSearch(r)?.let(out::add)
                "tv" -> tvToSearch(r)?.let(out::add)
            }
        }
        return out
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Load
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val (tmdbId, mediaType) = parseTmdbUrl(url)
            ?: throw ErrorLoadingException("Unsupported URL: $url")

        val detail = fetchDetail(mediaType, tmdbId)
            ?: throw ErrorLoadingException("Failed to fetch TMDB details for $mediaType/$tmdbId")

        val imdbId = detail.imdbId
        val title = detail.title
        val year = detail.year
        val posterUrl = detail.posterUrl
        val backdropUrl = detail.backdropUrl
        val plot = detail.plot
        val rating = detail.rating
        val tags = detail.tags
        val actors = detail.actors
        val trailerUrl = detail.trailerUrl
        val logoUrl = detail.logoUrl
        val simklId = detail.simklId


        return if (mediaType == "movie") {
            val data = LinkContext(
                imdbId = imdbId,
                tmdbId = tmdbId,
                season = null,
                episode = null,
                title = title,
                isMovie = true,
                year = year,
            ).toJson()
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = plot
                this.year = year
                this.duration = detail.runtime
                this.tags = tags
                this.recommendations = detail.recommendations
                runCatching { rating?.let { score = Score.from10(it) } }
                runCatching { actors?.let { this.actors = it } }
                runCatching { trailerUrl?.let { addTrailer(it) } }
                runCatching { logoUrl?.let { this.logoUrl = it } }
                runCatching { imdbId?.let { addImdbId(it) } }
                runCatching { simklId?.let { addSimklId(it) } }
            }
        } else {
            val seasons = detail.seasons.ifEmpty { listOf(1) }
            val episodesAll = boundedParallelMapWz(seasons, 6) { s ->
                fetchSeasonEpisodes(tmdbId, s, title, detail)
            }.flatten().distinctBy { (it.season ?: 1) to (it.episode ?: 0) }
                .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesAll) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = detail.recommendations
                runCatching { rating?.let { score = Score.from10(it) } }
                runCatching { actors?.let { this.actors = it } }
                runCatching { trailerUrl?.let { addTrailer(it) } }
                runCatching { logoUrl?.let { this.logoUrl = it } }
                runCatching { imdbId?.let { addImdbId(it) } }
                runCatching { simklId?.let { addSimklId(it) } }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Links
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        val ctx = try { LinkContext.fromJson(data) } catch (_: Exception) { null }
            ?: return@coroutineScope false

        val id = ctx.imdbId ?: ctx.tmdbId?.toString() ?: return@coroutineScope false
        val s = ctx.season
        val e = ctx.episode

        val seenUrls = Collections.newSetFromMap<String>(ConcurrentHashMap())
        val seenSubs = Collections.newSetFromMap<String>(ConcurrentHashMap())
        val gate = Semaphore(8)
        var anyFound = false

        val jobs = VID_HOSTS.map { host ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val embedUrl = if (ctx.isMovie || s == null || e == null) {
                        host.movie(id)
                    } else {
                        host.tv(id, s, e)
                    }
                    try {
                        val before = anyFound
                        loadExtractor(
                            embedUrl,
                            host.referer.ifBlank { embedUrl.substringBeforeLast("/") },
                            { sub ->
                                if (seenSubs.add(sub.url)) subtitleCallback(sub)
                            }
                        ) { link ->
                            val normalized = link.url.trim()
                            if (normalized.isBlank() || !seenUrls.add(normalized)) return@loadExtractor
                            val newSource = "Wizstream • ${host.label}"
                            val newName = "${host.label} — ${link.name}".trimEnd('—', ' ')
                            callback(link.relabel(newSource, newName))
                            anyFound = true
                        }
                    } catch (t: Throwable) {
                        Log.d(TAG, "Host ${host.label} failed: ${t.message}")
                    }
                }
            }
        }

        // ── Bundled BDIX source resolvers ────────────────────────────────
        // Run the 4 source extensions' search+loadLinks in parallel with
        // the Vid[x] embed family. WizstreamSources handles its own internal
        // concurrency (4-way), so we just await the whole batch here.
        // Pass tmdbId+imdbId so CinebyResolver can call the Cineby API
        // (which requires a TMDB ID for its /seed endpoint).
        val sourceJob = async(Dispatchers.IO) {
            runCatching {
                WizstreamSources.resolveAll(
                    app = app,
                    title = ctx.title ?: "",
                    year = ctx.year,
                    isMovie = ctx.isMovie,
                    season = s,
                    episode = e,
                    labelPrefix = "Wizstream",
                    subtitleCallback = { sub ->
                        if (seenSubs.add(sub.url)) subtitleCallback(sub)
                    },
                    callback = { link ->
                        val normalized = link.url.trim()
                        if (normalized.isNotBlank() && seenUrls.add(normalized)) {
                            callback(link)
                            anyFound = true
                        }
                    },
                    tmdbId = ctx.tmdbId,
                    imdbId = ctx.imdbId,
                )
            }.getOrDefault(false)
        }

        jobs.awaitAll()
        sourceJob.await()
        anyFound
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TMDB helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun parseTmdbUrl(url: String): Pair<Int, String>? {
        val u = url.trim()
        val m = Regex("wiz://tmdb/(movie|tv)/(\\d+)").find(u)
            ?: Regex("tmdb/(movie|tv)/(\\d+)").find(u)
            ?: return null
        val type = m.groupValues[1]
        val id = m.groupValues[2].toIntOrNull() ?: return null
        return id to type
    }

    private fun movieToSearch(r: JSONObject): MovieSearchResponse? {
        val id = r.optInt("id", 0).takeIf { it != 0 } ?: return null
        val title = r.optString("title").ifBlank { r.optString("original_title") }.ifBlank { null }
            ?: return null
        val year = yearFromDate(r.optString("release_date"))
        val poster = r.optString("poster_path").toTmdbImg("w500")
        return newMovieSearchResponse(title, "wiz://tmdb/movie/$id", TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    private fun tvToSearch(r: JSONObject): TvSeriesSearchResponse? {
        val id = r.optInt("id", 0).takeIf { it != 0 } ?: return null
        val title = r.optString("name").ifBlank { r.optString("original_name") }.ifBlank { null }
            ?: return null
        val year = yearFromDate(r.optString("first_air_date"))
        val poster = r.optString("poster_path").toTmdbImg("w500")
        return newTvSeriesSearchResponse(title, "wiz://tmdb/tv/$id", TvType.TvSeries) {
            this.posterUrl = poster
            this.year = year
        }
    }

    private data class TmdbDetail(
        val title: String,
        val year: Int?,
        val plot: String?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val logoUrl: String?,
        val rating: Double?,
        val runtime: Int?,
        val tags: List<String>?,
        val imdbId: String?,
        val simklId: Int?,
        val actors: List<ActorData>?,
        val trailerUrl: String?,
        val seasons: List<Int>,
        val recommendations: List<SearchResponse>,
    )

    private suspend fun fetchDetail(mediaType: String, tmdbId: Int): TmdbDetail? {
        val cacheKey = "$mediaType:$tmdbId"
        val now = System.currentTimeMillis()
        metaCache[cacheKey]?.let { (ts, cached) ->
            if (now - ts < CACHE_TTL_MS) return cached
        }

        val detail = tmdbGet("/$mediaType/$tmdbId", mapOf(
            "append_to_response" to "credits,external_ids,images,videos,recommendations",
            "include_image_language" to "en,null",
            "language" to "en-US",
        )) ?: return null

        val title = if (mediaType == "movie") {
            detail.optStringOrNullWz("title") ?: detail.optStringOrNullWz("original_title")
        } else {
            detail.optStringOrNullWz("name") ?: detail.optStringOrNullWz("original_name")
        } ?: return null

        val dateKey = if (mediaType == "movie") "release_date" else "first_air_date"
        val year = yearFromDate(detail.optStringOrNullWz(dateKey))
        val runtime = if (mediaType == "movie") detail.optIntOrNullWz("runtime")
        else detail.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
        val tags = detail.optJSONArray("genres")?.toStringListWz("name")
        val posterUrl = detail.optStringOrNullWz("poster_path").toTmdbImg("w780")
        val backdropUrl = detail.optStringOrNullWz("backdrop_path").toTmdbImg("original")
        val logoUrl = pickLogoWz(detail.optJSONObject("images")?.optJSONArray("logos"))
            ?: detail.optJSONObject("external_ids")?.optStringOrNullWz("imdb_id")?.let {
                "https://live.metahub.space/logo/medium/$it/img"
            }
        val rating = detail.optDoubleOrNullWz("vote_average")
        val plot = detail.optStringOrNullWz("overview")
        val trailerUrl = pickTrailerWz(detail.optJSONObject("videos")?.optJSONArray("results"))
        val imdbId = detail.optJSONObject("external_ids")?.optStringOrNullWz("imdb_id")
            ?.takeIf { it.startsWith("tt") }
        val simklId = fetchSimklId(imdbId, mediaType)
        val actors = detail.optJSONObject("credits")?.optJSONArray("cast")?.toActorsWz()
        val seasons = if (mediaType == "tv") {
            detail.optJSONArray("seasons")
                ?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optIntOrNullWz("season_number")?.takeIf { it > 0 }
                    }
                }
                .orEmpty()
        } else emptyList()
        val recs = detail.optJSONObject("recommendations")?.optJSONArray("results")
            ?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val r = arr.optJSONObject(i) ?: return@mapNotNull null
                    when {
                        r.has("title") -> movieToSearch(r)
                        r.has("name") -> tvToSearch(r)
                        else -> null
                    }
                }.take(15)
            }.orEmpty()

        val d = TmdbDetail(
            title = title,
            year = year,
            plot = plot,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            logoUrl = logoUrl,
            rating = rating,
            runtime = runtime,
            tags = tags,
            imdbId = imdbId,
            simklId = simklId,
            actors = actors,
            trailerUrl = trailerUrl,
            seasons = seasons.ifEmpty { if (mediaType == "tv") listOf(1) else emptyList() },
            recommendations = recs,
        )
        metaCache[cacheKey] = now to d
        return d
    }

    private suspend fun fetchSeasonEpisodes(
        tmdbId: Int,
        season: Int,
        showTitle: String,
        detail: TmdbDetail,
    ): List<Episode> {
        val json = tmdbGet("/tv/$tmdbId/season/$season", mapOf("language" to "en-US"))
            ?: return (1..12).map { epNum ->
                newEpisode(LinkContext(
                    imdbId = detail.imdbId, tmdbId = tmdbId,
                    season = season, episode = epNum, title = showTitle, isMovie = false,
                    year = detail.year,
                ).toJson()) {
                    name = "Episode $epNum"
                    this.season = season
                    episode = epNum
                }
            }
        val arr = json.optJSONArray("episodes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val ep = arr.optJSONObject(i) ?: return@mapNotNull null
            val epNum = ep.optIntOrNullWz("episode_number") ?: return@mapNotNull null
            val name = ep.optStringOrNullWz("name") ?: "Episode $epNum"
            val overview = ep.optStringOrNullWz("overview")
            val stillUrl = ep.optStringOrNullWz("still_path").toTmdbImg("original")
            val epRating = ep.optDoubleOrNullWz("vote_average")
            val airDate = ep.optStringOrNullWz("air_date")?.let(::parseAirDateWz)
            val epRuntime = ep.optIntOrNullWz("runtime")
                ?: ep.optIntOrNullWz("episode_run_time")
                ?: detail.runtime
            newEpisode(LinkContext(
                imdbId = detail.imdbId,
                tmdbId = tmdbId,
                season = season,
                episode = epNum,
                title = showTitle,
                isMovie = false,
                year = detail.year,
            ).toJson()) {
                this.name = name
                this.season = season
                this.episode = epNum
                this.posterUrl = stillUrl
                this.description = overview
                runCatching { epRating?.let { score = Score.from10(it) } }
                runTime = epRuntime
                this.date = airDate
            }
        }
    }

    private suspend fun tmdbGet(path: String, query: Map<String, Any?> = emptyMap()): JSONObject? {
        return runCatching {
            val q = mapOf("api_key" to TMDB_KEY) + query
            val params = q.entries.filter { it.value != null }.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v.toString(), "UTF-8")}"
            }
            val url = "$TMDB_API${if (path.startsWith("/")) path else "/$path"}?$params"
            val res = app.get(url, headers = mapOf(
                "User-Agent" to WZ_UA,
                "Accept" to "application/json",
            ), timeout = 10_000)
            if (res.code in 200..299) JSONObject(res.text) else null
        }.getOrNull()
    }

    private suspend fun fetchSimklId(imdbId: String?, mediaType: String): Int? {
        if (imdbId.isNullOrBlank()) return null
        val type = if (mediaType == "movie") "movies" else "tv"
        return runCatching {
            val url = "https://api.simkl.com/$type/${URLEncoder.encode(imdbId, "UTF-8")}?client_id=%20&extended=full"
            val text = app.get(url, headers = mapOf(
                "User-Agent" to WZ_UA, "Accept" to "application/json"
            ), timeout = 8_000).text.trim()
            if (!text.startsWith("{")) null
            else JSONObject(text).optJSONObject("ids")?.optInt("simkl")?.takeIf { it != 0 }
        }.getOrNull()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  LinkContext
    // ═══════════════════════════════════════════════════════════════════════

    private data class LinkContext(
        val imdbId: String? = null,
        val tmdbId: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val isMovie: Boolean = false,
        val year: Int? = null,   // (v18) TMDB year — needed for identity matching in BDIX resolvers
    ) {
        fun toJson(): String = JSONObject().apply {
            imdbId?.let { put("imdb_id", it) }
            tmdbId?.let { put("tmdb_id", it) }
            season?.let { put("season", it) }
            episode?.let { put("episode", it) }
            title?.let { put("title", it) }
            year?.let { put("year", it) }
            put("is_movie", isMovie)
        }.toString()

        companion object {
            fun fromJson(s: String): LinkContext {
                val o = JSONObject(s)
                return LinkContext(
                    imdbId = o.optStringOrNullWz("imdb_id"),
                    tmdbId = o.optIntOrNullWz("tmdb_id"),
                    season = o.optIntOrNullWz("season"),
                    episode = o.optIntOrNullWz("episode"),
                    title = o.optStringOrNullWz("title"),
                    year = o.optIntOrNullWz("year"),
                    isMovie = o.optBoolean("is_movie", false),
                )
            }
        }
    }
}
