package com.wizdier

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
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
        // (v54) StreamPlay-style anime row on the TMDB catalogue.
        "anime/trending" to "Trending Anime (JP Animation)",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        // (v54) StreamPlay-style anime row: TMDB discover, Japanese
        // animation by popularity; typed Anime by tvToSearch.
        if (path == "anime/trending") {
            val json = tmdbGet("/discover/tv", mapOf(
                "page" to page,
                "language" to "en-US",
                "with_genres" to "16",
                "with_origin_country" to "JP",
                "sort_by" to "popularity.desc",
            )) ?: return newHomePageResponse(request.name, emptyList(), false)
            val results = json.optJSONArray("results") ?: JSONArray()
            val items = (0 until results.length()).mapNotNull { i ->
                results.optJSONObject(i)?.let { tvToSearch(it) }
            }
            val hasNext = page < (json.optInt("total_pages", 1))
            return newHomePageResponse(
                HomePageList(request.name, items, isHorizontalImages = false),
                hasNext
            )
        }
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

    // (v50, re-applied in v52) One plain /search/multi pass.
    private suspend fun tmdbMultiSearch(query: String): List<SearchResponse> {
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

    // (v50) Collapse rōmaji long vowels the user commonly doubles
    // ("haikyuu" → "haikyu"); uu/oo/ee/aa only — skip ou/oh, too many
    // false positives.
    private fun romajiVowelVariant(query: String): String {
        var s = query
        for ((a, b) in listOf("uu" to "u", "oo" to "o", "ee" to "e", "aa" to "a")) {
            s = s.replace(a, b, ignoreCase = true)
        }
        return s.trim()
    }

    // (v50) Plain pass, plus ONE variant pass when the query actually has
    // collapsible vowels. Variant hits whose title matches the variant
    // query exactly are promoted to the front — TMDB files Haikyu!! under
    // single-u, so a bare "haikyuu" search only headlined a recap movie
    // (verified live). Ordinary titles (no doubled vowels) cost zero extra
    // requests and are reordered zero.
    private suspend fun tmdbSearch(query: String): List<SearchResponse> {
        val primary = tmdbMultiSearch(query)
        val variant = romajiVowelVariant(query)
        if (variant.isBlank() || variant.equals(query, ignoreCase = true)) return primary
        val secondary = tmdbMultiSearch(variant)
        if (secondary.isEmpty()) return primary
        val vNorm = variant.normalizedWz()
        val (exact, rest) = secondary.partition { it.name.normalizedWz() == vNorm }
        return exact + primary + rest
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


        // (v54) One decision up front: TMDB entry = Japanese animation →
        // Anime page enriched from AniList (StreamPlay style).
        val isAnime = detail.isAnime
        val enrich = if (isAnime) fetchAnimeEnrich(tmdbId) else null

        return if (mediaType == "movie") {
            val data = LinkContext(
                imdbId = imdbId,
                tmdbId = tmdbId,
                season = null,
                episode = null,
                title = title,
                isMovie = true,
                year = year,
                // (v70) anime movies carry the AniList entry ids too, so the
                // anime-web resolvers (AniZone/Allmanga/…) resolve them.
                anilistId = enrich?.anilistId,
                malId = enrich?.malId,
            ).toJson()
            newMovieLoadResponse(
                title, url, if (isAnime) TvType.AnimeMovie else TvType.Movie, data
            ) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = enrich?.bannerUrl ?: backdropUrl
                this.plot = plot
                this.year = year
                this.duration = detail.runtime
                this.tags = tags
                this.recommendations = detail.recommendations
                runCatching { rating?.let { score = Score.from10(it) } }
                runCatching { (enrich?.actors ?: actors)?.let { this.actors = it } }
                runCatching { trailerUrl?.let { addTrailer(it) } }
                runCatching { logoUrl?.let { this.logoUrl = it } }
                runCatching { imdbId?.let { addImdbId(it) } }
                runCatching { simklId?.let { addSimklId(it) } }
                runCatching { enrich?.malId?.let { addMalId(it) } }
                runCatching { enrich?.kitsuId?.let { addKitsuId(it) } }
                runCatching { enrich?.anilistId?.let { addAniListId(it) } }
            }
        } else {
            // (v59) Anime episode tables come from the recursive
            // WizEpisodeTable mapper: same catalogue shape as CircleFTP —
            // per-season rows extended SEQUENTIALLY with tail-absorbed
            // story-specials (Attack on Titan S4 = 28 TV eps + The Final
            // Chapters 1&2 as E29/E30), titles and descriptions included —
            // then renumbered like the site's tabs. Non-anime shows keep
            // the plain per-season fetch. Null table (network/empty show)
            // falls back to the plain fetch too.
            val animeTable = if (isAnime) buildAnimeEpisodeTable(tmdbId, title, detail) else null
            // (v70) PER-ENTRY DE-STACKED anime pages ("CircleFTP
            // structure"): group rows by AniList franchise entry instead
            // of TMDB's merged cours stack. Rows keep BOTH coordinate
            // sets, so BDIX/TMDB/embed lookups are bit-identical to the
            // stacked table while the anime-web resolvers gain their
            // expected per-entry ids + entry-local numbers. Safety rails:
            //   • walk failure → today's stacked table (v59 buildAnime
            //     EpisodeTable), which itself falls back to plain TMDB;
            //   • ONE-entry shows whose TMDB page legitimately carries
            //     many seasons (absolute-numbered long runners, One
            //     Piece-style) keep the stacked table — season
            //     navigation beats a 1000-row single season there.
            var entryRows: List<Episode>? = null
            if (isAnime && enrich?.anilistId != null) {
                val fr = fetchAnimeFranchise(enrich.anilistId)
                if (fr != null) {
                    val (members, rootTitles) = fr
                    val broadcastCount =
                        members.count { it.format in franchiseBroadcastFormatsWz }
                    if (members.isNotEmpty() &&
                        (broadcastCount >= 2 || detail.seasons.size <= 3)
                    ) {
                        entryRows = runCatching {
                            buildFranchiseEpisodes(
                                members, rootTitles, tmdbId, title, detail, enrich
                            )
                        }.getOrNull()?.takeIf { it.isNotEmpty() }
                    }
                }
            }
            val episodesAll = entryRows ?: animeTable ?: run {
                val seasons = detail.seasons.ifEmpty { listOf(1) }
                boundedParallelMapWz(seasons, 6) { s ->
                    fetchSeasonEpisodes(tmdbId, s, title, detail)
                }.flatten().distinctBy { (it.season ?: 1) to (it.episode ?: 0) }
                    .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
            }

            if (isAnime) {
                // Anime: TMDB hosts the episode table (its packed-cours
                // numbering is exactly what BDIX sites use), AniList lends
                // tracking ids, banner art and the JA cast.
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = enrich?.bannerUrl ?: backdropUrl
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.recommendations = detail.recommendations
                    runCatching { rating?.let { score = Score.from10(it) } }
                    runCatching { (enrich?.actors ?: actors)?.let { this.actors = it } }
                    runCatching { trailerUrl?.let { addTrailer(it) } }
                    runCatching { logoUrl?.let { this.logoUrl = it } }
                    runCatching { imdbId?.let { addImdbId(it) } }
                    runCatching { simklId?.let { addSimklId(it) } }
                    runCatching { enrich?.malId?.let { addMalId(it) } }
                    runCatching { enrich?.kitsuId?.let { addKitsuId(it) } }
                    runCatching { enrich?.anilistId?.let { addAniListId(it) } }
                    addEpisodes(DubStatus.Subbed, episodesAll)
                }
            } else {
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
        // (v59) Tail-absorbed story-specials (WizEpisodeTable) carry their
        // canonical TMDB location: TMDB-indexed embed hosts must ask for
        // S0E36, not the stacked S4E29 (which TMDB has no row for).
        val embedS = ctx.tmdbSeason ?: s
        val embedE = ctx.tmdbEpisode ?: e

        val seenUrls = Collections.newSetFromMap<String>(ConcurrentHashMap())
        val seenSubs = Collections.newSetFromMap<String>(ConcurrentHashMap())
        val gate = Semaphore(8)
        var anyFound = false

        val jobs = VID_HOSTS.map { host ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val embedUrl = if (ctx.isMovie || embedS == null || embedE == null) {
                        host.movie(id)
                    } else {
                        host.tv(id, embedS, embedE)
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
                    // (v70) entry title first (cours "Part N" ask), franchise
                    // root titles as the BDIX search keys, entry-local
                    // episode for split-cours posts — same invocation
                    // contract WizstreamAnimeProvider uses.
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
                    altTitle = ctx.altTitle,
                    extraAltTitles = ctx.franchiseTitles ?: emptyList(),
                    entryEpisode = ctx.entryEpisode,
                )
            }.getOrDefault(false)
        }

        // ── (v70) Anime-web source resolvers ─────────────────────────────
        // De-stacked anime rows carry their owning AniList entry's ids and
        // entry-local episode, so the 7 anime streaming resolvers
        // (AniZone, Allmanga, AniChi, UniqueStream, AniNeko, ReANIME,
        // TokyoInsider — mirrored from the WizstreamAnime module) resolve
        // them exactly like they do in Wizstream-Anime: an entry is a
        // whole show there, episodes 1..N.
        val animeJob = if (ctx.anilistId == null) null else async(Dispatchers.IO) {
            runCatching {
                WizstreamAnimeSources.resolveAnime(
                    app = app,
                    title = ctx.title ?: "",
                    altTitle = ctx.altTitle,
                    anilistId = ctx.anilistId,
                    malId = ctx.malId,
                    isMovie = ctx.isMovie,
                    season = 1,
                    episode = if (ctx.isMovie) null else (ctx.entryEpisode ?: ctx.episode),
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
                )
            }.getOrDefault(false)
        }

        jobs.awaitAll()
        sourceJob.await()
        animeJob?.await()
        anyFound
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TMDB helpers
    // ═══════════════════════════════════════════════════════════════════════

    // (v50) Japanese animation detector: TMDB genre 16 (Animation) plus a
    // Japanese origin — origin_country on TV results, original_language
    // fallback for movies (multi-search movie payloads often omit
    // origin_country).
    private fun isJpAnimation(r: JSONObject): Boolean {
        val genres = r.optJSONArray("genre_ids")
        val animated = genres?.let { g -> (0 until g.length()).any { g.optInt(it) == 16 } } == true
        if (!animated) return false
        val oc = r.optJSONArray("origin_country")
        val jpCountry = oc?.let { a -> (0 until a.length()).any { a.optString(it) == "JP" } } == true
        return jpCountry || r.optString("original_language") == "ja"
    }

    private fun parseTmdbUrl(url: String): Pair<Int, String>? {
        val u = url.trim()
        val m = Regex("wiz://tmdb/(movie|tv)/(\\d+)").find(u)
            ?: Regex("tmdb/(movie|tv)/(\\d+)").find(u)
            // (v52) real themoviedb.org URLs are what we emit now.
            ?: Regex("themoviedb\\.org/(movie|tv)/(\\d+)").find(u)
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
        // (v50) anime typing for Japanese animation; (v52) real TMDB URLs.
        val type = if (isJpAnimation(r)) TvType.AnimeMovie else TvType.Movie
        return newMovieSearchResponse(title, "https://www.themoviedb.org/movie/$id", type) {
            this.posterUrl = poster
            this.year = year
        }
    }

    // (v50) return type widened: Japanese animation TV results are emitted
    // as AnimeSearchResponse so they blend with anime rows correctly.
    private fun tvToSearch(r: JSONObject): SearchResponse? {
        val id = r.optInt("id", 0).takeIf { it != 0 } ?: return null
        val title = r.optString("name").ifBlank { r.optString("original_name") }.ifBlank { null }
            ?: return null
        val year = yearFromDate(r.optString("first_air_date"))
        val poster = r.optString("poster_path").toTmdbImg("w500")
        // (v52) real TMDB URLs (see parseTmdbUrl's fallback).
        return if (isJpAnimation(r)) {
            newAnimeSearchResponse(title, "https://www.themoviedb.org/tv/$id", TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, "https://www.themoviedb.org/tv/$id", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
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
        // (v54) Japanese animation = AniList-enriched Anime page.
        val isAnime: Boolean = false,
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

        // (v54) StreamPlay-style anime detection on the DETAIL payload
        // (detail carries genres-with-ids + origin_country, unlike
        // multi-search rows).
        val isAnime = run {
            val g = detail.optJSONArray("genres")
            val animated = g?.let { arr ->
                (0 until arr.length()).any { arr.optJSONObject(it)?.optInt("id") == 16 }
            } == true
            val oc = detail.optJSONArray("origin_country")
            val jp = oc?.let { arr ->
                (0 until arr.length()).any { arr.optString(it) == "JP" }
            } == true
            animated && (jp || detail.optStringOrNullWz("original_language") == "ja")
        }

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
            isAnime = isAnime,
        )
        metaCache[cacheKey] = now to d
        return d
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  (v54) StreamPlay-style AniList enrichment for TMDB anime pages
    // ═══════════════════════════════════════════════════════════════════════
    //  Catalogue and identity stay TMDB (exactly like StreamPlay); when the
    //  TMDB entry is Japanese animation we additionally borrow from AniList:
    //  tracking ids (AniList/MAL/Kitsu via api.ani.zip mapping), banner art
    //  and the Japanese voice-actor cast. One mapping call + one GraphQL
    //  call per anime page, fully best-effort (nulls on any failure).
    private data class AnimeEnrich(
        val anilistId: Int?,
        val malId: Int?,
        val kitsuId: String?,
        val bannerUrl: String?,
        val actors: List<ActorData>?,
    )

    // Small local AniList GraphQL client (self-contained copy of the one
    // in WizstreamAnimeProvider — that one is a provider MEMBER function,
    // so it can't be borrowed across classes).
    private suspend fun anilistGraph(query: String, variables: JSONObject): JSONObject? =
        runCatching {
            val body = JSONObject().apply {
                put("query", query); put("variables", variables)
            }.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val res = app.post(
                "https://graphql.anilist.co",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                requestBody = body,
                timeout = 12_000,
            )
            if (res.code !in 200..299) null
            else JSONObject(res.text).optJSONObject("data")
        }.getOrNull()

    private suspend fun fetchAnimeEnrich(tmdbId: Int): AnimeEnrich? {
        return runCatching {
            val map = JSONObject(
                app.get(
                    "https://api.ani.zip/mappings?tmdb_id=$tmdbId",
                    timeout = 8000,
                ).text
            )
            val anilistId = map.optIntOrNullWz("anilist_id")
            val malId = map.optIntOrNullWz("mal_id")
            val kitsuId = map.optStringOrNullWz("kitsu_id")

            var bannerUrl: String? = null
            var actors: List<ActorData>? = null
            if (anilistId != null) {
                val gql = """
                    query (${'$'}id: Int) {
                      Media(id: ${'$'}id, type: ANIME) {
                        bannerImage
                        characters(sort: [ROLE, RELEVANCE], perPage: 25) {
                          edges {
                            role
                            node { name { full } image { large } }
                            voiceActorsJapanese: voiceActors(language: JAPANESE, sort: [RELEVANCE]) { name { full } image { large } }
                          }
                        }
                      }
                    }
                """.trimIndent()
                val media = anilistGraph(gql, JSONObject().put("id", anilistId))
                    ?.optJSONObject("Media")
                bannerUrl = media?.optStringOrNullWz("bannerImage")
                actors = media?.optJSONObject("characters")
                    ?.optJSONArray("edges")?.let { edges ->
                        (0 until edges.length()).mapNotNull { i ->
                            val e = edges.optJSONObject(i) ?: return@mapNotNull null
                            val node = e.optJSONObject("node") ?: return@mapNotNull null
                            val charName = node.optJSONObject("name")
                                ?.optStringOrNullWz("full") ?: return@mapNotNull null
                            val charImg = node.optJSONObject("image")
                                ?.optStringOrNullWz("large")
                            val jaVa = e.optJSONArray("voiceActorsJapanese")
                                ?.optJSONObject(0)?.let { va ->
                                    va.optJSONObject("name")?.optStringOrNullWz("full")
                                        ?.let { n ->
                                            Actor(n, va.optJSONObject("image")
                                                ?.optStringOrNullWz("large"))
                                        }
                                }
                            ActorData(
                                actor = Actor(charName, charImg),
                                roleString = e.optString("role").ifBlank { null },
                                voiceActor = jaVa,
                            )
                        }.ifEmpty { null }
                    }
            }
            AnimeEnrich(anilistId, malId, kitsuId, bannerUrl, actors)
        }.getOrNull()
    }

    // (v59) Recursive BDIX-shaped episode table for JP-animation pages:
    // WizEpisodeTable folds tail attached story-specials into their parent
    // season sequentially (AoT Final Chapters 1/2 → S4E29/E30) while
    // remembering each row's canonical TMDB location so the embed hosts
    // can be pointed at it (see LinkContext.tmdbSeason/tmdbEpisode).
    private suspend fun buildAnimeEpisodeTable(
        tmdbId: Int,
        showTitle: String,
        detail: TmdbDetail,
    ): List<Episode>? {
        val tbl = runCatching { WizEpisodeTable.table(app, tmdbId) }.getOrNull()
            ?.seasons ?: return null
        return tbl.toSortedMap().flatMap { (season, eps) ->
            eps.toSortedMap().map { (epNum, row) ->
                newEpisode(LinkContext(
                    imdbId = detail.imdbId,
                    tmdbId = tmdbId,
                    season = season,
                    episode = epNum,
                    title = showTitle,
                    isMovie = false,
                    year = detail.year,
                    tmdbSeason = row.tmdbSeason,
                    tmdbEpisode = row.tmdbEpisode,
                ).toJson()) {
                    this.name = row.name ?: "Episode $epNum"
                    this.season = season
                    this.episode = epNum
                    this.posterUrl = row.stillUrl
                    this.description = row.overview
                    runCatching { row.score?.let { score = Score.from10(it) } }
                    runTime = row.runtime
                    this.date = row.airDate
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  (v70) Anime franchise de-stacking — "CircleFTP structure"
    // ═══════════════════════════════════════════════════════════════════
    //  User request: "separate multi stacked season animes in Wizstream
    //  like CircleFTP". TMDB files a cours-split anime as one merged
    //  season stack (AoT S3 = 22 rows spanning AniList's S3 + S3-Part-2
    //  entries); AniList — and every anime-web streaming site — files
    //  each entry separately with its OWN episode numbering. So the
    //  anime page now groups rows BY ANILIST ENTRY (each entry = one
    //  season group of entry-local rows, tail-absorbed story-specials
    //  continuing the last group exactly like the site's tabs), while
    //  every row carries BOTH coordinate sets: the stacked (site/TMDB)
    //  season+episode for BDIX/TMDB-indexed resolvers AND the owning
    //  entry's ids + entry-local episode for the anime-web resolvers.
    //  This is the same machine WizstreamAnimeProvider runs (v48 fold),
    //  ported lean: no streaming-feed fetch — row meta comes from the
    //  shared WizEpisodeTable (TMDB canon, specials already attached).

    private val franchiseBroadcastFormatsWz = setOf("TV", "TV_SHORT", "ONA")
    private val coursSplitRegexWz =
        Regex("""(?i)\b(part|cour)\s*\d+|\bpart\s+[ivxlcdm]+\b""")
    private val partNumberRegexWz = Regex(
        """(?i)\b(?:part|cour)\s*(\d{1,2})\b|\bpart\s+([ivxlcdm]{1,5})\b"""
    )

    private data class FEntry(
        val id: Int,
        val title: String,
        val altTitle: String?,
        val episodes: Int,
        val malId: Int?,
        val format: String?,
    ) {
        var siteSeason: Int = 0
        var seasonStart: Int = 1
    }

    private fun romanToIntWz(s: String): Int? {
        val vals = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100)
        var total = 0
        var prev = 0
        for (ch in s.lowercase().reversed()) {
            val v = vals[ch] ?: return null
            total += if (v < prev) -v else v
            prev = v
        }
        return total.takeIf { it > 0 }
    }

    private fun titlePartNumberWz(title: String): Int? {
        if (!coursSplitRegexWz.containsMatchIn(title)) return null
        val m = partNumberRegexWz.find(title) ?: return 2
        return m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            ?: m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::romanToIntWz)
            ?: 2
    }

    private fun JSONObject.toFEntry(): FEntry? {
        val nt = optJSONObject("title")
        val en = nt?.optStringOrNullWz("english")
        val ro = nt?.optStringOrNullWz("romaji")
        val t = en ?: ro ?: nt?.optStringOrNullWz("native") ?: return null
        val alt = listOfNotNull(en, ro).firstOrNull { !it.equals(t, ignoreCase = true) }
        val idV = optInt("id", 0).takeIf { it != 0 } ?: return null
        return FEntry(
            id = idV,
            title = t,
            altTitle = alt,
            episodes = optInt("episodes", 0),
            malId = optInt("idMal", 0).takeIf { it != 0 },
            format = optStringOrNullWz("format"),
        )
    }

    /** One AniList query: the entry's own core fields + its relation edges. */
    private suspend fun franchiseNodeWz(id: Int): Pair<FEntry?, JSONArray?> {
        val gql = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id idMal format episodes title { english romaji native }
                relations { edges { node { id type format episodes idMal title { english romaji native } } relationType } }
              }
            }
        """.trimIndent()
        val media = anilistGraph(gql, JSONObject().put("id", id))
            ?.optJSONObject("Media") ?: return null to null
        val self = media.toFEntry()
        val edges = media.optJSONObject("relations")?.optJSONArray("edges")
        return self to edges
    }

    private fun JSONArray.pickPrequelWz(visited: Set<Int>): Triple<FEntry?, FEntry?, FEntry?> {
        var counted: FEntry? = null
        var anyBroadcast: FEntry? = null
        var bridge: FEntry? = null
        for (i in 0 until length()) {
            val e = optJSONObject(i) ?: continue
            if (!e.optString("relationType").equals("PREQUEL", true)) continue
            val node = e.optJSONObject("node") ?: continue
            if (!node.optString("type").equals("ANIME", true)) continue
            val cand = node.toFEntry() ?: continue
            if (cand.id in visited) continue
            if (bridge == null) bridge = cand
            if (cand.format !in franchiseBroadcastFormatsWz) continue
            if (anyBroadcast == null) anyBroadcast = cand
            if (!coursSplitRegexWz.containsMatchIn(cand.title)) {
                counted = cand
                break
            }
        }
        return Triple(counted, anyBroadcast, bridge)
    }

    /** Walk prequels to the root, then sequels forward. OVA/movie nodes
     *  are invisible bridges; long SEQUEL specials absorb at the tail. */
    private suspend fun fetchAnimeFranchise(
        startId: Int,
    ): Pair<List<FEntry>, List<String>>? = runCatching {
        val (self, startEdges) = franchiseNodeWz(startId)
        val opened = self ?: return@runCatching null
        val visited = hashSetOf(startId)
        val pre = mutableListOf<FEntry>()
        val rootTitles = mutableListOf<String>()
        var edges = startEdges
        var hops = 0
        var bridges = 0
        while (hops < 8) {
            hops++
            val (counted, anyBroadcast, bridge) = (edges ?: break).pickPrequelWz(visited)
            val hop = counted ?: anyBroadcast
                ?: (if (bridges < 3) bridge else null)
                ?: break
            visited += hop.id
            if (hop.format in franchiseBroadcastFormatsWz) {
                pre += hop
                if (counted != null && counted.id == hop.id) {
                    listOfNotNull(
                        hop.title.takeIf { it.isNotBlank() }, hop.altTitle
                    ).forEach { cand ->
                        if (rootTitles.none { it.equals(cand, true) }) rootTitles += cand
                    }
                }
            } else {
                bridges++
            }
            edges = franchiseNodeWz(hop.id).second ?: break
        }
        val post = mutableListOf<FEntry>()
        val tailSpecials = mutableListOf<FEntry>()
        var sEdges = startEdges
        var sHops = 0
        var sBridges = 0
        while (sHops < 6) {
            sHops++
            val arr = sEdges ?: break
            var best: FEntry? = null
            var bestEps = 0
            var sBridge: FEntry? = null
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (!e.optString("relationType").equals("SEQUEL", true)) continue
                val node = e.optJSONObject("node") ?: continue
                if (!node.optString("type").equals("ANIME", true)) continue
                val cand = node.toFEntry() ?: continue
                if (cand.id in visited) continue
                if (sBridge == null) sBridge = cand
                if (cand.format !in franchiseBroadcastFormatsWz) continue
                if (cand.episodes <= 0) continue
                if (best == null || cand.episodes > bestEps) {
                    best = cand
                    bestEps = cand.episodes
                }
            }
            val hop = best ?: (if (sBridges < 3) sBridge else null) ?: break
            visited += hop.id
            if (hop.format in franchiseBroadcastFormatsWz) {
                post += hop
            } else {
                sBridges++
                if (hop.format == "SPECIAL" && hop.episodes in 1..6 && tailSpecials.size < 3) {
                    tailSpecials += hop
                }
            }
            sEdges = franchiseNodeWz(hop.id).second ?: break
        }
        val members = ArrayList<FEntry>()
        pre.asReversed().forEach { members += it }
        members += opened
        post.forEach { members += it }
        tailSpecials.forEach { members += it }
        val seenIds = HashSet<Int>()
        members.filter { seenIds.add(it.id) } to rootTitles
    }.getOrNull()

    /** Fold members into stacked site seasons: a cours part joins the
     *  season its prequel opened and continues its numbering; long
     *  story-specials tail-attach the season they follow. */
    private fun foldFranchiseWz(members: List<FEntry>) {
        var seasonCounter = 0
        var nextStart = 1
        members.forEach { m ->
            when {
                m.format !in franchiseBroadcastFormatsWz -> {
                    if (seasonCounter == 0) {
                        seasonCounter = 1
                        m.siteSeason = 1
                        m.seasonStart = 1
                    } else {
                        m.siteSeason = seasonCounter
                        m.seasonStart = nextStart
                    }
                }
                titlePartNumberWz(m.title) == null || seasonCounter == 0 -> {
                    seasonCounter++
                    m.siteSeason = seasonCounter
                    m.seasonStart = 1
                }
                else -> {
                    m.siteSeason = seasonCounter
                    m.seasonStart = nextStart
                }
            }
            nextStart = m.seasonStart + m.episodes.coerceAtLeast(1)
        }
    }

    /** Entry-grouped anime rows: each franchise entry = one season group
     *  of entry-local rows; non-broadcast members (absorbed story
     *  specials) continue the previous group's numbering, exactly like
     *  CircleFTP's per-season tabs. Row meta = shared WizEpisodeTable
     *  (TMDB canon, already specials-aware). */
    private suspend fun buildFranchiseEpisodes(
        members: List<FEntry>,
        rootTitles: List<String>,
        tmdbId: Int,
        showTitle: String,
        detail: TmdbDetail,
        enrich: AnimeEnrich?,
    ): List<Episode> {
        foldFranchiseWz(members)
        val tbl = runCatching { WizEpisodeTable.table(app, tmdbId) }.getOrNull()
        val franchiseKeys = (
            rootTitles + showTitle + members.map { it.title } + members.mapNotNull { it.altTitle }
            ).filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        val out = ArrayList<Episode>()
        var groupIdx = 0
        var i = 0
        while (i < members.size) {
            val head = members[i]
            groupIdx++
            val group = mutableListOf(head)
            while (i + 1 < members.size && members[i + 1].format !in franchiseBroadcastFormatsWz) {
                group += members[i + 1]
                i++
            }
            var displayCounter = 0
            group.forEach { gm ->
                (1..gm.episodes.coerceAtLeast(1)).forEach { localEp ->
                    displayCounter++
                    val stacked = gm.seasonStart + localEp - 1
                    val meta = tbl?.seasons?.get(gm.siteSeason)?.get(stacked)
                    out += newEpisode(
                        LinkContext(
                            imdbId = detail.imdbId,
                            tmdbId = tmdbId,
                            season = gm.siteSeason,
                            episode = stacked,
                            title = gm.title,
                            isMovie = false,
                            year = detail.year,
                            tmdbSeason = meta?.tmdbSeason,
                            tmdbEpisode = meta?.tmdbEpisode,
                            anilistId = gm.id,
                            malId = gm.malId ?: enrich?.malId,
                            entryEpisode = localEp,
                            altTitle = gm.altTitle,
                            franchiseTitles = franchiseKeys,
                        ).toJson()
                    ) {
                        name = meta?.name
                            ?.takeUnless { it.equals("Episode $stacked", true) }
                            ?: "Episode $displayCounter"
                        season = groupIdx
                        episode = displayCounter
                        posterUrl = meta?.stillUrl
                        description = meta?.overview
                        runCatching { meta?.score?.let { score = Score.from10(it) } }
                        runTime = meta?.runtime
                        this.date = meta?.airDate
                    }
                }
            }
            i++
        }
        Log.d(
            TAG, "Wizstream: franchise de-stack for '$showTitle' — " +
                "${members.size} entries, $groupIdx groups, ${out.size} rows"
        )
        return out
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
        // (v59) Canonical TMDB location of a tail-absorbed story-special
        // (e.g. AoT "The Final Chapters Special (1)" = S0E36 while its
        // stacked site row is S4E29 — see WizEpisodeTable). BDIX resolvers
        // keep reading the stacked season/episode; only the TMDB-indexed
        // embed hosts get pointed at these. null = regular row.
        val tmdbSeason: Int? = null,
        val tmdbEpisode: Int? = null,
        // (v70) Per-entry de-stacked anime rows ("CircleFTP structure",
        // user request): the OWNING AniList entry's ids + its ENTRY-LOCAL
        // episode + the franchise's BDIX search keys ride every row. The
        // 7 anime-web resolvers mirror AniList's per-entry split (an
        // entry is a whole show there, episodes 1..N), so this is the
        // coordinate set resolveAnime needs; BDIX/TMDB/embed hosts keep
        // reading the stacked season/episode above (unchanged).
        val anilistId: Int? = null,
        val malId: Int? = null,
        val entryEpisode: Int? = null,
        val altTitle: String? = null,
        val franchiseTitles: List<String>? = null,
    ) {
        fun toJson(): String = JSONObject().apply {
            imdbId?.let { put("imdb_id", it) }
            tmdbId?.let { put("tmdb_id", it) }
            season?.let { put("season", it) }
            episode?.let { put("episode", it) }
            title?.let { put("title", it) }
            year?.let { put("year", it) }
            tmdbSeason?.let { put("t_season", it) }
            tmdbEpisode?.let { put("t_ep", it) }
            anilistId?.let { put("anilist_id", it) }
            malId?.let { put("mal_id", it) }
            entryEpisode?.let { put("entry_ep", it) }
            altTitle?.let { put("alt_title", it) }
            franchiseTitles?.takeIf { tl -> tl.isNotEmpty() }
                ?.let { tl -> put("f_titles", JSONArray(tl)) }
            put("is_movie", isMovie)
        }.toString()

        companion object {
            fun fromJson(s: String): LinkContext {
                val o = JSONObject(s)
                val fArr = o.optJSONArray("f_titles")
                return LinkContext(
                    imdbId = o.optStringOrNullWz("imdb_id"),
                    tmdbId = o.optIntOrNullWz("tmdb_id"),
                    season = o.optIntOrNullWz("season"),
                    episode = o.optIntOrNullWz("episode"),
                    title = o.optStringOrNullWz("title"),
                    year = o.optIntOrNullWz("year"),
                    isMovie = o.optBoolean("is_movie", false),
                    tmdbSeason = o.optIntOrNullWz("t_season"),
                    tmdbEpisode = o.optIntOrNullWz("t_ep"),
                    anilistId = o.optIntOrNullWz("anilist_id"),
                    malId = o.optIntOrNullWz("mal_id"),
                    entryEpisode = o.optIntOrNullWz("entry_ep"),
                    altTitle = o.optStringOrNullWz("alt_title"),
                    franchiseTitles = fArr?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it, null) }
                    },
                )
            }
        }
    }
}
