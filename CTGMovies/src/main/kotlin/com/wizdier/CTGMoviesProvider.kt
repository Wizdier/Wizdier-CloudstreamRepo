package com.wizdier

import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class CTGMovies(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var mainUrl = "https://ctgmovies.com"
    override var name = "CTGMovies"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
    )

    // Simkl sync works through IMDb ids. Anime also exposes AniList/MAL/Kitsu
    // ids when CTG/TMDB metadata provides them.
    override val supportedSyncNames = setOfNotNull(
        SyncIdName.Anilist,
        SyncIdName.MyAnimeList,
        runCatching { SyncIdName.valueOf("Kitsu") }.getOrNull(),
        runCatching { SyncIdName.valueOf("Simkl") }.getOrNull(),
    )

    companion object {
        const val PREF_FILE = "CTGMovies"
        const val PREF_EMAIL = "ctg_email"
        const val PREF_PASSWORD = "ctg_password"
        const val PREF_TOKEN = "ctg_token"
        const val PREF_COOKIE = "ctg_cookie"
        const val PREF_API_BASE = "ctg_api_base"
        const val DEFAULT_API_BASE = "https://cockpit.103.109.92.178.nip.io/api/v1"

        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        // TTL cache for TMDB metadata. Keyed by mediaType + tmdbId + seasons.
        // Repeat loads of the same title within the window return instantly.
        private val tmdbMetaCache = CTGConcurrent.TtlCache<String, TmdbMeta>(ttlMs = 10 * 60 * 1000L)
    }

    override val mainPage = mainPageOf(
        "movies" to "Latest Movies",
        "tv" to "Latest TV Shows",
        "anime" to "Latest Anime",
    )

    private data class CtgSearchItem(
        val title: String,
        val url: String,
        val kind: String,
        val id: String,
        val type: TvType,
        val poster: String?,
        val year: Int?,
        val sourceLabel: String?,
    )

    private data class GroupedCtgItem(
        val kind: String,
        val id: String,
        val label: String?,
    )

    private data class ObjWithLabel(
        val obj: JSONObject,
        val label: String?,
    )

    private data class TmdbEpisodeMeta(
        val name: String? = null,
        val overview: String? = null,
        val stillUrl: String? = null,
        val rating: Double? = null,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = when (request.data) {
            "tv" -> "/tv"
            "anime" -> "/anime"
            else -> "/movies"
        }
        val text = apiGet(path, mapOf("page" to page))
        val items = parseList(text, request.data)
        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        // Fan out to all three content sections concurrently. The old code
        // issued these sequentially, so search latency was 3× the per-request
        // time. Now it's ~1×.
        val params = mapOf("search" to q)
        val items = coroutineScope {
            val movies = async { runCatching { parseSearchItems(apiGet("/movies", params), "movies") }.getOrDefault(emptyList()) }
            val tv = async { runCatching { parseSearchItems(apiGet("/tv", params), "tv") }.getOrDefault(emptyList()) }
            val anime = async { runCatching { parseSearchItems(apiGet("/anime", params), "anime") }.getOrDefault(emptyList()) }
            movies.await() + tv.await() + anime.await()
        }

        return groupSearchItems(items)
    }

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        if (!name.name.equals("Simkl", ignoreCase = true)) return null
        val syncId = id.trim().removeSuffix("/").substringAfterLast("/").substringBefore("?")
        if (syncId.isBlank()) return null

        val simkl = fetchSimklDetails(syncId) ?: return null
        val title = simkl.optStringOrNull("title") ?: return null
        val year = simkl.optIntOrNull("year")
        val type = simkl.optStringOrNull("type")?.lowercase().orEmpty()

        val preferred = when (type) {
            "movie" -> listOf("movies", "tv", "anime")
            "anime" -> listOf("anime", "tv", "movies")
            else -> listOf("tv", "anime", "movies")
        }
        for (kind in preferred) {
            findCtgUrlByTitle(title, year, kind)?.let { return it }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        decodeGroupedCtgItems(url)?.let { grouped ->
            return loadGrouped(url, grouped)
        }

        val cleanUrl = url.substringBefore("?")
        val (kind, idOrSlug) = when {
            cleanUrl.contains("/anime/") -> "anime" to cleanUrl.substringAfterLast("/")
            cleanUrl.contains("/tv/") -> "tv" to cleanUrl.substringAfterLast("/")
            cleanUrl.contains("/movies/") -> "movies" to cleanUrl.substringAfterLast("/")
            url.startsWith("ctg:anime:") -> "anime" to url.substringAfter("ctg:anime:")
            url.startsWith("ctg:tv:") -> "tv" to url.substringAfter("ctg:tv:")
            url.startsWith("ctg:movie:") -> "movies" to url.substringAfter("ctg:movie:")
            else -> return null
        }

        val obj = JSONObject(apiGet("/$kind/${idOrSlug.encodeUrl()}"))
        return when (kind) {
            "movies" -> loadMovie(url, obj)
            "anime" -> loadAnime(url, obj)
            else -> loadTv(url, obj)
        }
    }

    private suspend fun loadGrouped(url: String, items: List<GroupedCtgItem>): LoadResponse? {
        // Fetch every grouped item concurrently instead of sequentially.
        val loaded = CTGConcurrent.parallelMapNotNull(items.distinctBy { it.kind to it.id }) { item ->
            runCatching { JSONObject(apiGet("/${item.kind}/${item.id.encodeUrl()}")) }
                .getOrNull()?.let { ObjWithLabel(it, item.label) }
        }
        if (loaded.isEmpty()) return null

        val primaryKind = items.first().kind
        val primaryUrl = ctgUrlFor(loaded.first().obj, primaryKind) ?: url
        return when (primaryKind) {
            "movies" -> loadMovie(primaryUrl, mergeMovieObjects(loaded))
            "anime" -> loadAnime(primaryUrl, mergeAnimeObjects(loaded))
            else -> loadTv(primaryUrl, mergeSeriesObjects(loaded))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = when {
            data.trim().startsWith("{") -> runCatching { JSONObject(data).optJSONArray("links") }.getOrNull()
            data.trim().startsWith("[") -> runCatching { JSONArray(data) }.getOrNull()
            data.startsWith("http") -> JSONArray().put(JSONObject().put("url", data))
            else -> null
        } ?: return false

        var found = false
        val seenLinks = linkedSetOf<String>()
        val seenSubs = linkedSetOf<String>()
        val seenSourceNames = mutableMapOf<String, Int>()

        for (i in 0 until links.length()) {
            val link = links.optJSONObject(i) ?: continue
            if (link.optBoolean("broken", false)) continue

            val rawUrl = link.optStringOrNull("url")
                ?: link.optStringOrNull("file")
                ?: link.optStringOrNull("src")
                ?: link.optStringOrNull("link")
                ?: continue
            val finalUrl = resolveMediaUrl(rawUrl)
            if (finalUrl.isBlank() || !seenLinks.add(finalUrl)) continue

            collectSubtitleTracks(link, finalUrl, seenSubs, subtitleCallback)

            val baseSourceName = buildShortSourceName(link, finalUrl, i + 1)
            val sourceCount = (seenSourceNames[baseSourceName] ?: 0) + 1
            seenSourceNames[baseSourceName] = sourceCount
            val sourceName = if (sourceCount > 1) "$baseSourceName #$sourceCount" else baseSourceName
            val qualityHint = link.optStringOrNull("quality") ?: sourceName

            if (finalUrl.contains(".m3u8", ignoreCase = true)) {
                collectM3u8Subtitles(finalUrl, mainUrl, seenSubs, subtitleCallback)
                M3u8Helper.generateM3u8(
                    source = sourceName,
                    streamUrl = finalUrl,
                    referer = mainUrl,
                    headers = directHeaders(finalUrl)
                ).forEach(callback)
                found = true
            } else if (isDirectVideo(finalUrl)) {
                callback(
                    newExtractorLink(
                        source = sourceName,
                        name = "$sourceName - Direct",
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        referer = mainUrl
                        quality = getQualityFromName(qualityHint)
                    }
                )
                found = true
            } else {
                val before = found
                runCatching {
                    loadExtractor(finalUrl, mainUrl, subtitleCallback) { extractorLink ->
                        callback(extractorLink)
                        found = true
                    }
                }
                if (!found && !before && finalUrl.startsWith("http")) {
                    callback(
                        newExtractorLink(
                            source = sourceName,
                            name = "$sourceName - Link",
                            url = finalUrl,
                            type = INFER_TYPE,
                        ) {
                            referer = mainUrl
                            quality = getQualityFromName(qualityHint)
                        }
                    )
                    found = true
                }
            }
        }

        return found
    }

    // ───────────────────────────── TMDB metadata ─────────────────────────────

    private data class TmdbMeta(
        val title: String? = null,
        val plot: String? = null,
        val posterUrl: String? = null,
        val backdropUrl: String? = null,
        val logoUrl: String? = null,
        val year: Int? = null,
        val runtime: Int? = null,
        val tags: List<String>? = null,
        val rating: Double? = null,
        val trailerUrl: String? = null,
        val imdbId: String? = null,
        val simklId: Int? = null,
        val actors: List<ActorData>? = null,
        val episodes: List<TmdbEpisodeMeta> = emptyList(),
        val episodesBySeason: Map<Int, List<TmdbEpisodeMeta>> = emptyMap(),
    )

    private suspend fun fetchTmdbMeta(
        obj: JSONObject,
        mediaType: String,
        fallbackTitle: String?,
        seasonHints: Set<Int> = emptySet(),
    ): TmdbMeta {
        val tmdbId = obj.optIntOrNull("tmdb_id") ?: findTmdbId(mediaType, fallbackTitle, obj)
        if (tmdbId == null) return TmdbMeta(
            imdbId = obj.optStringOrNull("imdb_id"),
            tags = obj.optStringOrNull("genres")?.splitCsv()
        )

        // Cache hit → instant return, zero network I/O.
        val cacheKey = "$mediaType|$tmdbId|${seasonHints.sorted()}"
        tmdbMetaCache.get(cacheKey)?.let { return it }

        val detail = tmdbJson(
            "/$mediaType/$tmdbId",
            mapOf(
                "append_to_response" to "credits,external_ids,images,videos,recommendations",
                "include_image_language" to "en,null"
            )
        ) ?: return TmdbMeta(imdbId = obj.optStringOrNull("imdb_id"))

        val title = if (mediaType == "movie") {
            detail.optStringOrNull("title") ?: detail.optStringOrNull("original_title")
        } else {
            detail.optStringOrNull("name") ?: detail.optStringOrNull("original_name")
        }
        val date = detail.optStringOrNull(if (mediaType == "movie") "release_date" else "first_air_date")
        val runtime = if (mediaType == "movie") {
            detail.optIntOrNull("runtime")
        } else {
            val runtimes = detail.optJSONArray("episode_run_time")
            runtimes?.optInt(0, 0)?.takeIf { it > 0 }
        }
        val tags = detail.optJSONArray("genres")?.toStringList("name")
        val logo = detail.optJSONObject("images")
            ?.optJSONArray("logos")
            ?.bestTmdbImage("file_path", preferLogo = true)
            ?.let { tmdbImg("w500", it) }
        val trailer = detail.optJSONObject("videos")
            ?.optJSONArray("results")
            ?.bestYoutubeTrailer()
        val actors = detail.optJSONObject("credits")
            ?.optJSONArray("cast")
            ?.toActors()
        val imdbId = detail.optJSONObject("external_ids")?.optStringOrNull("imdb_id")
            ?: detail.optStringOrNull("imdb_id")
            ?: obj.optStringOrNull("imdb_id")
        val simklId = fetchSimklId(imdbId, mediaType)
        val targetSeasons = seasonHints.takeIf { it.isNotEmpty() } ?: setOf(1)
        val episodesBySeason = if (mediaType == "tv") {
            val map = linkedMapOf<Int, List<TmdbEpisodeMeta>>()
            for (season in targetSeasons) {
                map[season] = fetchTmdbSeasonEpisodes(tmdbId, season)
            }
            map
        } else emptyMap()
        val episodes = episodesBySeason[targetSeasons.firstOrNull() ?: 1] ?: emptyList()

        val result = TmdbMeta(
            title = title,
            plot = detail.optStringOrNull("overview"),
            posterUrl = detail.optStringOrNull("poster_path")?.let { tmdbImg("w500", it) },
            backdropUrl = detail.optStringOrNull("backdrop_path")?.let { tmdbImg("w1280", it) },
            logoUrl = logo ?: imdbId?.let { "https://live.metahub.space/logo/medium/$it/img" },
            year = yearFromDate(date),
            runtime = runtime,
            tags = tags,
            rating = detail.optDoubleOrNull("vote_average"),
            trailerUrl = trailer,
            imdbId = imdbId,
            simklId = simklId,
            actors = actors,
            episodes = episodes,
            episodesBySeason = episodesBySeason,
        )
        tmdbMetaCache.put(cacheKey, result)
        return result
    }

    private suspend fun fetchTmdbSeasonEpisodes(tmdbId: Int, season: Int): List<TmdbEpisodeMeta> {
        val root = tmdbJson("/tv/$tmdbId/season/$season") ?: return emptyList()
        val arr = root.optJSONArray("episodes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val ep = arr.optJSONObject(i) ?: return@mapNotNull null
            TmdbEpisodeMeta(
                name = ep.optStringOrNull("name"),
                overview = ep.optStringOrNull("overview"),
                stillUrl = ep.optStringOrNull("still_path")?.let { tmdbImg("w300", it) },
                rating = ep.optDoubleOrNull("vote_average"),
            )
        }
    }

    private suspend fun findTmdbId(mediaType: String, title: String?, obj: JSONObject): Int? {
        if (title.isNullOrBlank()) return null
        val year = obj.optIntOrNull("year")
            ?: yearFromDate(obj.optStringOrNull("release_date"))
            ?: yearFromDate(obj.optStringOrNull("first_air_date"))
        val query = buildMap<String, Any?> {
            put("query", title)
            put("include_adult", false)
            if (year != null) put(if (mediaType == "movie") "year" else "first_air_date_year", year)
        }
        return tmdbJson("/search/$mediaType", query)
            ?.optJSONArray("results")
            ?.optJSONObject(0)
            ?.optInt("id")
            ?.takeIf { it != 0 }
    }

    private suspend fun tmdbJson(path: String, query: Map<String, Any?> = emptyMap()): JSONObject? =
        runCatching {
            val q = query + mapOf("api_key" to TMDB_KEY, "language" to "en-US")
            val res = app.get(TMDB_API + path + queryString(q), headers = webHeaders(), timeout = 8000)
            if (res.code in 200..299) JSONObject(res.text) else null
        }.getOrNull()

    private suspend fun fetchSimklId(imdbId: String?, mediaType: String): Int? {
        if (imdbId.isNullOrBlank()) return null
        val simklType = if (mediaType == "movie") "movies" else "tv"
        return fetchSimklObject(simklType, imdbId)
            ?.optJSONObject("ids")
            ?.optInt("simkl")
            ?.takeIf { it != 0 }
    }

    private suspend fun fetchSimklDetails(syncId: String): JSONObject? =
        fetchSimklObject("movies", syncId) ?: fetchSimklObject("tv", syncId)

    private suspend fun fetchSimklObject(type: String, id: String): JSONObject? = runCatching {
        // Simkl needs a client_id parameter; this harmless encoded space is
        // accepted by their public id endpoint and fails closed if they ever
        // tighten validation.
        val res = app.get(
            "https://api.simkl.com/$type/${id.encodeUrl()}?client_id=%20&extended=full",
            headers = webHeaders() + mapOf("Accept" to "application/json"),
            timeout = 8000
        )
        if (res.code !in 200..299) return@runCatching null
        val text = res.text.trim()
        if (!text.startsWith("{")) return@runCatching null
        JSONObject(text)
    }.getOrNull()

    private suspend fun findCtgUrlByTitle(title: String, year: Int?, kind: String): String? = runCatching {
        val raw = apiGet("/$kind", mapOf("search" to title))
        val arr = if (raw.trim().startsWith("[")) JSONArray(raw) else JSONObject(raw).optJSONArray("movies") ?: JSONArray()
        val normalizedTarget = title.normalizedTitle()
        var fallback: String? = null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val itemTitle = obj.optStringOrNull("title") ?: obj.optStringOrNull("name") ?: continue
            val itemYear = obj.optIntOrNull("year")
                ?: yearFromDate(obj.optStringOrNull("release_date"))
                ?: yearFromDate(obj.optStringOrNull("first_air_date"))
            val url = ctgUrlFor(obj, kind) ?: continue
            if (fallback == null) fallback = url
            if (itemTitle.normalizedTitle() == normalizedTarget && (year == null || itemYear == null || itemYear == year)) {
                return@runCatching url
            }
        }
        fallback
    }.getOrNull()

    private fun ctgUrlFor(obj: JSONObject, kind: String): String? {
        val id = obj.optStringOrNull("slug") ?: obj.optStringOrNull("id") ?: obj.optStringOrNull("_id") ?: return null
        return when (kind) {
            "movies" -> "$mainUrl/movies/$id"
            "anime" -> "$mainUrl/anime/$id"
            else -> "$mainUrl/tv/$id"
        }
    }

    private fun tmdbImg(size: String, path: String): String = "$TMDB_IMG/$size$path"

    // ───────────────────────────── Load builders ─────────────────────────────

    private suspend fun loadMovie(pageUrl: String, obj: JSONObject): LoadResponse {
        val baseTitle = obj.optStringOrNull("title") ?: "Untitled"
        val meta = fetchTmdbMeta(obj, "movie", baseTitle)
        val title = baseTitle
        val data = JSONObject()
            .put("kind", "movie")
            .put("id", obj.optStringOrNull("id"))
            .put("links", obj.optJSONArray("links") ?: JSONArray())
            .toString()

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, data) {
            posterUrl = obj.optStringOrNull("poster_url")
            backgroundPosterUrl = meta.backdropUrl ?: obj.optStringOrNull("backdrop_url")
            plot = obj.optStringOrNull("overview") ?: meta.plot
            year = obj.optIntOrNull("year") ?: yearFromDate(obj.optStringOrNull("release_date")) ?: meta.year
            duration = meta.runtime ?: obj.optIntOrNull("runtime")
            tags = obj.optStringOrNull("genres")?.splitCsv() ?: meta.tags
            runCatching { meta.actors?.let { actors = it } }
            runCatching { (meta.rating ?: obj.optDoubleOrNull("rating"))?.let { score = Score.from10(it) } }
            runCatching { (meta.trailerUrl ?: obj.optStringOrNull("trailer_url"))?.let { addTrailer(it) } }
            runCatching { meta.logoUrl?.let { logoUrl = it } }
            addSyncIds(obj, meta)
        }
    }

    private suspend fun loadTv(pageUrl: String, obj: JSONObject): LoadResponse {
        val baseTitle = obj.optStringOrNull("name") ?: obj.optStringOrNull("title") ?: "Untitled"
        val rawEpisodes = obj.optJSONArray("episodes")
        val seasonSet = seasonHints(rawEpisodes)
        val meta = fetchTmdbMeta(obj, "tv", baseTitle, seasonSet)
        val title = baseTitle
        val episodes = parseEpisodes(rawEpisodes, anime = false, tmdbEpisodesBySeason = meta.episodesBySeason)
        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            posterUrl = obj.optStringOrNull("poster_url") ?: obj.optStringOrNull("cover_url")
            backgroundPosterUrl = meta.backdropUrl ?: obj.optStringOrNull("backdrop_url") ?: obj.optStringOrNull("banner_url")
            plot = obj.optStringOrNull("overview") ?: obj.optStringOrNull("description") ?: meta.plot
            year = yearFromDate(obj.optStringOrNull("first_air_date")) ?: obj.optIntOrNull("year") ?: meta.year
            tags = obj.optStringOrNull("genres")?.splitCsv() ?: meta.tags
            runCatching { meta.actors?.let { actors = it } }
            runCatching { (meta.rating ?: obj.optDoubleOrNull("rating"))?.let { score = Score.from10(it) } }
            runCatching { (meta.trailerUrl ?: obj.optStringOrNull("trailer_url"))?.let { addTrailer(it) } }
            runCatching { meta.logoUrl?.let { logoUrl = it } }
            addSyncIds(obj, meta)
        }
    }

    private suspend fun loadAnime(pageUrl: String, obj: JSONObject): LoadResponse {
        val baseTitle = obj.optStringOrNull("title")
            ?: obj.optStringOrNull("name")
            ?: obj.optStringOrNull("english_title")
            ?: "Untitled"
        val rawEpisodes = obj.optJSONArray("episodes")
        val seasonSet = seasonHints(rawEpisodes)
        val tvType = if (rawEpisodes == null || rawEpisodes.length() == 0) TvType.AnimeMovie else TvType.Anime
        val meta = fetchTmdbMeta(obj, if (tvType == TvType.AnimeMovie) "movie" else "tv", baseTitle, seasonSet)
        val episodes = parseEpisodes(rawEpisodes, anime = true, tmdbEpisodesBySeason = meta.episodesBySeason)
        val title = baseTitle

        return if (tvType == TvType.AnimeMovie) {
            val data = JSONObject()
                .put("kind", "anime")
                .put("id", obj.optStringOrNull("id") ?: obj.optStringOrNull("_id"))
                .put("links", obj.optJSONArray("links") ?: JSONArray())
                .toString()
            newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, data) {
                posterUrl = obj.optStringOrNull("cover_url") ?: obj.optStringOrNull("poster_url")
                backgroundPosterUrl = meta.backdropUrl ?: obj.optStringOrNull("banner_url") ?: obj.optStringOrNull("backdrop_url")
                plot = obj.optStringOrNull("description") ?: obj.optStringOrNull("overview") ?: meta.plot
                year = obj.optIntOrNull("year") ?: yearFromDate(obj.optStringOrNull("first_air_date")) ?: meta.year
                duration = meta.runtime
                tags = obj.optStringOrNull("genres")?.splitCsv() ?: meta.tags
                runCatching { meta.actors?.let { actors = it } }
                runCatching { (meta.rating ?: obj.optDoubleOrNull("rating"))?.let { score = Score.from10(it) } }
                runCatching { (meta.trailerUrl ?: obj.optStringOrNull("trailer_url"))?.let { addTrailer(it) } }
                runCatching { meta.logoUrl?.let { logoUrl = it } }
                addSyncIds(obj, meta)
            }
        } else {
            newAnimeLoadResponse(title, pageUrl, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                posterUrl = obj.optStringOrNull("cover_url") ?: obj.optStringOrNull("poster_url")
                backgroundPosterUrl = meta.backdropUrl ?: obj.optStringOrNull("banner_url") ?: obj.optStringOrNull("backdrop_url")
                plot = obj.optStringOrNull("description") ?: obj.optStringOrNull("overview") ?: meta.plot
                year = obj.optIntOrNull("year") ?: yearFromDate(obj.optStringOrNull("first_air_date")) ?: meta.year
                tags = obj.optStringOrNull("genres")?.splitCsv() ?: meta.tags
                runCatching { meta.actors?.let { actors = it } }
                runCatching { (meta.rating ?: obj.optDoubleOrNull("rating"))?.let { score = Score.from10(it) } }
                runCatching { (meta.trailerUrl ?: obj.optStringOrNull("trailer_url"))?.let { addTrailer(it) } }
                runCatching { meta.logoUrl?.let { logoUrl = it } }
                addSyncIds(obj, meta)
            }
        }
    }

    private fun parseEpisodes(
        array: JSONArray?,
        anime: Boolean,
        tmdbEpisodesBySeason: Map<Int, List<TmdbEpisodeMeta>> = emptyMap(),
    ): List<Episode> {
        if (array == null) return emptyList()
        val grouped = linkedMapOf<Pair<Int, Int>, JSONObject>()

        for (i in 0 until array.length()) {
            val ep = array.optJSONObject(i) ?: continue
            val epNum = ep.optIntOrNull("episode_number") ?: ep.optIntOrNull("absolute_number") ?: (i + 1)
            val seasonNum = ep.optIntOrNull("season_number") ?: 1
            val key = seasonNum to epNum
            val target = grouped.getOrPut(key) { JSONObject(ep.toString()).put("links", JSONArray()) }
            appendJsonArray(target.optJSONArray("links") ?: JSONArray().also { target.put("links", it) }, ep.optJSONArray("links"))
        }

        val out = mutableListOf<Episode>()
        grouped.forEach { (key, ep) ->
            val seasonNum = key.first
            val epNum = key.second
            val tmdbEp = tmdbEpisodesBySeason[seasonNum]?.getOrNull(epNum - 1)
            val epTitle = tmdbEp?.name?.takeIf { it.isNotBlank() }
                ?: ep.optStringOrNull("name")
                ?: ep.optStringOrNull("title")
                ?: "Episode $epNum"
            val epData = JSONObject()
                .put("kind", if (anime) "anime_episode" else "episode")
                .put("id", ep.optStringOrNull("id") ?: ep.optStringOrNull("_id"))
                .put("series_id", ep.optStringOrNull("series_id"))
                .put("season", seasonNum)
                .put("episode", epNum)
                .put("links", ep.optJSONArray("links") ?: JSONArray())
                .toString()

            out += newEpisode(epData) {
                name = epTitle
                season = seasonNum
                episode = epNum
                posterUrl = tmdbEp?.stillUrl ?: ep.optStringOrNull("still_url") ?: ep.optStringOrNull("thumbnail_url")
                description = tmdbEp?.overview?.takeIf { it.isNotBlank() }
                    ?: ep.optStringOrNull("overview")
                    ?: ep.optStringOrNull("description")
                runTime = ep.optIntOrNull("runtime")
                runCatching { tmdbEp?.rating?.let { score = Score.from10(it) } }
            }
        }
        return out.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    // ───────────────────────────── Search/listing ────────────────────────────

    private fun parseList(raw: String, kind: String): List<SearchResponse> =
        groupSearchItems(parseSearchItems(raw, kind))

    private fun parseSearchItems(raw: String, kind: String): List<CtgSearchItem> {
        val trimmed = raw.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> JSONObject(trimmed).optJSONArray("movies")
                ?: JSONObject(trimmed).optJSONArray("results")
                ?: JSONObject(trimmed).optJSONArray("data")
                ?: JSONArray()
        }

        val out = mutableListOf<CtgSearchItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            toSearchItem(obj, kind)?.let(out::add)
        }
        return out.distinctBy { it.kind to it.id }
    }

    private fun groupSearchItems(items: List<CtgSearchItem>): List<SearchResponse> =
        items.groupBy { item ->
            val titleKey = item.title.cleanDisplayTitle().normalizedTitle()
            val year = item.year
            // Same rule as Cineplex: only merge when BOTH normalized name and
            // year are common. Missing year keeps the item separate.
            if (year != null) "$titleKey|$year|${item.type}" else "$titleKey|${item.kind}|${item.id}|${item.type}"
        }.values.mapNotNull { group ->
            val first = group.firstOrNull() ?: return@mapNotNull null
            val groupUrl = if (group.size > 1) encodeGroupedCtgItems(group) else first.url
            when (first.type) {
                TvType.Movie -> newMovieSearchResponse(first.title, groupUrl, TvType.Movie) {
                    posterUrl = first.poster
                    year = first.year
                }
                TvType.Anime, TvType.AnimeMovie -> newAnimeSearchResponse(first.title, groupUrl, TvType.Anime) {
                    posterUrl = first.poster
                    year = first.year
                }
                else -> newTvSeriesSearchResponse(first.title, groupUrl, TvType.TvSeries) {
                    posterUrl = first.poster
                    year = first.year
                }
            }
        }

    private fun toSearchItem(obj: JSONObject, kind: String): CtgSearchItem? {
        val isMovie = kind == "movies"
        val isAnime = kind == "anime" || obj.optBoolean("is_anime", false) && kind != "tv"
        val title = obj.optStringOrNull("title")
            ?: obj.optStringOrNull("name")
            ?: obj.optStringOrNull("english_title")
            ?: return null
        val id = obj.optStringOrNull("slug") ?: obj.optStringOrNull("id") ?: obj.optStringOrNull("_id") ?: return null
        val poster = obj.optStringOrNull("poster_url")
            ?: obj.optStringOrNull("cover_url")
        val year = obj.optIntOrNull("year")
            ?: yearFromDate(obj.optStringOrNull("release_date"))
            ?: yearFromDate(obj.optStringOrNull("first_air_date"))
        val type = when {
            isMovie -> TvType.Movie
            isAnime -> TvType.Anime
            else -> TvType.TvSeries
        }
        val url = when {
            isMovie -> "$mainUrl/movies/$id"
            isAnime -> "$mainUrl/anime/$id"
            else -> "$mainUrl/tv/$id"
        }
        return CtgSearchItem(
            title = title.cleanDisplayTitle(),
            url = url,
            kind = kind,
            id = id,
            type = type,
            poster = poster,
            year = year,
            sourceLabel = buildQualityInitials(
                obj.optStringOrNull("quality"),
                obj.optStringOrNull("source"),
                obj.optStringOrNull("source_display"),
                title,
            ),
        )
    }

    private fun encodeGroupedCtgItems(items: List<CtgSearchItem>): String {
        val arr = JSONArray()
        val seenLabels = mutableMapOf<String, Int>()
        items.distinctBy { it.kind to it.id }.forEachIndexed { index, item ->
            val baseLabel = item.sourceLabel ?: "Source ${index + 1}"
            val count = (seenLabels[baseLabel] ?: 0) + 1
            seenLabels[baseLabel] = count
            val label = if (count > 1) "$baseLabel #$count" else baseLabel
            arr.put(JSONObject().apply {
                put("kind", item.kind)
                put("id", item.id)
                put("label", label)
            })
        }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(arr.toString().toByteArray())
        return "load?data=$encoded"
    }

    private fun decodeGroupedCtgItems(url: String): List<GroupedCtgItem>? = runCatching {
        if (!url.contains("load?data=")) return@runCatching null
        val encoded = url.substringAfter("load?data=").substringBefore("&")
        val arr = JSONArray(String(Base64.getUrlDecoder().decode(encoded)))
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = obj.optStringOrNull("kind") ?: return@mapNotNull null
            val id = obj.optStringOrNull("id") ?: return@mapNotNull null
            GroupedCtgItem(kind, id, obj.optStringOrNull("label"))
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun mergeMovieObjects(items: List<ObjWithLabel>): JSONObject {
        val merged = JSONObject(items.first().obj.toString())
        val links = JSONArray()
        items.forEach { item ->
            appendJsonArray(links, item.obj.optJSONArray("links"), item.label)
        }
        merged.put("links", links)
        return merged
    }

    private fun mergeAnimeObjects(items: List<ObjWithLabel>): JSONObject {
        val merged = JSONObject(items.first().obj.toString())
        val links = JSONArray()
        val episodes = JSONArray()
        items.forEach { item ->
            appendJsonArray(links, item.obj.optJSONArray("links"), item.label)
            appendJsonArray(episodes, item.obj.optJSONArray("episodes"), item.label)
        }
        if (links.length() > 0) merged.put("links", links)
        if (episodes.length() > 0) merged.put("episodes", episodes)
        return merged
    }

    private fun mergeSeriesObjects(items: List<ObjWithLabel>): JSONObject {
        val merged = JSONObject(items.first().obj.toString())
        val episodes = JSONArray()
        items.forEach { item -> appendJsonArray(episodes, item.obj.optJSONArray("episodes"), item.label) }
        merged.put("episodes", episodes)
        return merged
    }

    private fun appendJsonArray(target: JSONArray, source: JSONArray?, label: String? = null) {
        if (source == null) return
        for (i in 0 until source.length()) {
            val raw = source.opt(i)
            if (raw !is JSONObject) continue
            val obj = JSONObject(raw.toString())
            label?.takeIf { it.isNotBlank() }?.let { sourceLabel ->
                obj.put("group_source", sourceLabel)
                obj.optJSONArray("links")?.let { links ->
                    for (j in 0 until links.length()) {
                        links.optJSONObject(j)?.put("group_source", sourceLabel)
                    }
                }
            }
            target.put(obj)
        }
    }

    // ───────────────────────────── API/auth ──────────────────────────────────

    private suspend fun apiGet(path: String, query: Map<String, Any?> = emptyMap()): String {
        ensureToken(false)
        val url = buildApiUrl(path, query)
        var response = app.get(url, headers = apiHeaders(), timeout = 10_000)

        if (response.code == 401 || response.code == 403) {
            ensureToken(true)
            response = app.get(url, headers = apiHeaders(), timeout = 10_000)
        }

        if (response.code !in 200..299) {
            // Same-origin fallback catches deployments where the public API is blocked
            // but Next.js proxy routes still work.
            val fallback = runCatching {
                app.get(
                    mainUrl + "/api/v1" + (if (path.startsWith("/")) path else "/$path") + queryString(query),
                    headers = apiHeaders(),
                    timeout = 10_000
                )
            }.getOrNull()
            if (fallback != null && fallback.code in 200..299) return fallback.text
        }
        return response.text
    }

    private suspend fun ensureToken(force: Boolean): String? {
        val current = storedToken()
        if (!force && current.isNotBlank()) return current

        val email = prefs?.getString(PREF_EMAIL, null)?.trim().orEmpty()
        val password = prefs?.getString(PREF_PASSWORD, null).orEmpty()
        if (email.isBlank() || password.isBlank()) return current.ifBlank { null }

        val login = runCatching {
            app.post(
                "${apiBase()}/auth/login",
                headers = webHeaders() + mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                json = mapOf("email" to email, "password" to password),
                timeout = 10_000
            )
        }.getOrNull() ?: return current.ifBlank { null }

        if (login.code !in 200..299) return current.ifBlank { null }
        val token = runCatching { JSONObject(login.text).optStringOrNull("token") }.getOrNull()
        if (!token.isNullOrBlank()) {
            prefs?.edit()?.putString(PREF_TOKEN, token)?.apply()
            return token
        }
        return current.ifBlank { null }
    }

    private fun storedToken(): String {
        val rawToken = prefs?.getString(PREF_TOKEN, null).orEmpty().trim()
        val direct = rawToken
            .removePrefix("Bearer ")
            .removePrefix("bearer ")
            .trim()
        if (direct.isNotBlank() && !direct.contains("=")) return direct

        val cookieLike = if (direct.contains("=")) direct else prefs?.getString(PREF_COOKIE, null).orEmpty()
        return Regex("(?:ctg_token|ctg\\.token)=([^;]+)")
            .find(cookieLike)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
    }

    private fun apiBase(): String =
        prefs?.getString(PREF_API_BASE, DEFAULT_API_BASE)
            ?.trim()
            ?.trimEnd('/')
            ?.ifBlank { DEFAULT_API_BASE }
            ?: DEFAULT_API_BASE

    private fun apiOrigin(): String = apiBase().removeSuffix("/api/v1")

    private fun buildApiUrl(path: String, query: Map<String, Any?> = emptyMap()): String =
        apiBase() + (if (path.startsWith("/")) path else "/$path") + queryString(query)

    private fun queryString(query: Map<String, Any?>): String {
        val params = query.entries
            .filter { it.value != null }
            .joinToString("&") { (k, v) -> "${k.encodeUrl()}=${v.toString().encodeUrl()}" }
        return if (params.isBlank()) "" else "?$params"
    }

    private fun apiHeaders(): Map<String, String> {
        val token = storedToken()
        val cookie = prefs?.getString(PREF_COOKIE, null)?.trim().orEmpty()
        return buildMap {
            putAll(webHeaders())
            put("Accept", "application/json")
            put("Accept-Language", "en")
            if (token.isNotBlank()) {
                put("Authorization", "Bearer $token")
                put("x-auth-token", token)
            }
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
    }

    private fun webHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

    private fun directHeaders(url: String): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Referer" to mainUrl,
    )

    // ───────────────────────────── Helpers ───────────────────────────────────

    private suspend fun collectSubtitleTracks(
        link: JSONObject,
        finalUrl: String,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        listOf("subtitle_tracks", "subtitles", "captions", "tracks").forEach { key ->
            val subs = link.optJSONArray(key) ?: return@forEach
            for (s in 0 until subs.length()) {
                val sub = subs.optJSONObject(s) ?: continue
                val rawSubUrl = sub.optStringOrNull("url")
                    ?: sub.optStringOrNull("file")
                    ?: sub.optStringOrNull("src")
                    ?: continue
                val subUrl = resolveSubtitleUrl(rawSubUrl)
                if (subUrl.isBlank() || !seenSubs.add(subUrl)) continue
                val label = sub.optStringOrNull("label")
                    ?: sub.optStringOrNull("language")
                    ?: subtitleLabelFromUrl(subUrl)
                subtitleCallback(newSubtitleFile(label, subUrl))
            }
        }

        if (finalUrl.contains(".vtt", true) || finalUrl.contains(".srt", true) || finalUrl.contains(".ass", true)) {
            val subUrl = resolveSubtitleUrl(finalUrl)
            if (subUrl.isNotBlank() && seenSubs.add(subUrl)) {
                subtitleCallback(newSubtitleFile(subtitleLabelFromUrl(subUrl), subUrl))
            }
        }
    }

    private suspend fun collectM3u8Subtitles(
        manifestUrl: String,
        referer: String,
        seenSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val manifestHeaders = directHeaders(manifestUrl) + ("Referer" to referer)
        val manifest = runCatching { app.get(manifestUrl, headers = manifestHeaders).text }.getOrNull() ?: return
        Regex("""#EXT-X-MEDIA:([^\r\n]+)""", RegexOption.IGNORE_CASE)
            .findAll(manifest)
            .forEach { match ->
                val attrs = match.groupValues[1]
                if (!attrs.contains("TYPE=SUBTITLES", ignoreCase = true)) return@forEach
                val rawUri = parseM3u8Attribute(attrs, "URI") ?: return@forEach
                val subUrl = resolveRelativeUrl(rawUri, manifestUrl)
                if (subUrl.isBlank() || !seenSubs.add(subUrl)) return@forEach
                val label = parseM3u8Attribute(attrs, "NAME")
                    ?: parseM3u8Attribute(attrs, "LANGUAGE")
                    ?: subtitleLabelFromUrl(subUrl)
                subtitleCallback(newSubtitleFile(label, subUrl))
            }
    }

    private fun parseM3u8Attribute(attrs: String, key: String): String? =
        Regex("""$key=(?:"([^"]*)"|([^,]*))""", RegexOption.IGNORE_CASE)
            .find(attrs)
            ?.let { match ->
                match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: match.groupValues.getOrNull(2)
            }
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun buildShortSourceName(link: JSONObject, finalUrl: String, index: Int): String {
        val actualQuality = buildQualityInitials(
            link.optStringOrNull("quality"),
            link.optStringOrNull("source_display"),
            link.optStringOrNull("source"),
            finalUrl,
        )
        val groupLabel = link.optStringOrNull("group_source")?.takeUnless { it.startsWith("Source ", true) }
        val label = actualQuality
            ?: groupLabel
            ?: link.optStringOrNull("source_display")?.cleanSourceName()?.take(18)
            ?: link.optStringOrNull("source")?.cleanSourceName()?.take(18)
            ?: "Source $index"
        return "$name • ${label.cleanSourceName()}"
    }

    private fun buildQualityInitials(vararg hints: String?): String? {
        val raw = hints.filterNotNull().joinToString(" ")
            .replace("%20", " ")
            .replace("_", " ")
            .replace("-", " ")
        if (raw.isBlank()) return null
        fun has(pattern: String): Boolean = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val parts = linkedSetOf<String>()
        if (has("\\b3d\\b")) parts += "3D"
        if (has("\\b(?:4k|2160p|uhd)\\b")) {
            parts += "4K"
        } else {
            Regex("(?i)\\b(1080|720|576|540|480|360)p\\b")
                .findAll(raw)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull()
                ?.let { parts += "${it}p" }
        }
        if (parts.isEmpty() && has("\\bHD\\b")) parts += "HD"
        if (parts.isEmpty()) {
            listOf(
                "WEB-DL" to "(?i)\\bweb[- ]?dl\\b",
                "WEBRip" to "(?i)\\bwebrip\\b",
                "BluRay" to "(?i)\\b(?:bluray|blu ray|brrip)\\b",
                "HDRip" to "(?i)\\bhdrip\\b",
                "HEVC" to "(?i)\\b(?:hevc|x265|h265)\\b",
                "10bit" to "(?i)\\b10[- ]?bit\\b",
            ).firstOrNull { (_, pattern) -> has(pattern) }?.let { (short, _) -> parts += short }
        }
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun seasonHints(episodes: JSONArray?): Set<Int> {
        if (episodes == null) return emptySet()
        val out = linkedSetOf<Int>()
        for (i in 0 until episodes.length()) {
            episodes.optJSONObject(i)?.optIntOrNull("season_number")?.let { if (it > 0) out += it }
        }
        return out
    }

    private fun subtitleLabelFromUrl(url: String): String {
        val file = url.substringBefore("?").substringAfterLast('/').replace("%20", " ")
        return when {
            file.contains("bangla", true) || file.contains("bengali", true) || file.contains("ben", true) -> "Bangla"
            file.contains("english", true) || file.contains("eng", true) -> "English"
            file.contains("hindi", true) || file.contains("hin", true) -> "Hindi"
            else -> "Subtitle"
        }
    }

    private fun resolveRelativeUrl(url: String, baseUrl: String): String = when {
        url.startsWith("//") -> baseUrl.substringBefore("://", "https") + ":$url"
        url.startsWith("http", true) -> url
        url.startsWith("/") -> apiOrigin() + url
        else -> baseUrl.substringBeforeLast("/", apiOrigin()).trimEnd('/') + "/${url.trimStart('/')}"
    }

    private fun String.cleanDisplayTitle(): String =
        replace(Regex("(?i)\\b(1080p|720p|480p|2160p|4k|web[- ]?dl|webrip|bluray|hdrip|x264|x265|hevc|10bit|dual[- ]?audio|hindi[- ]?dubbed|dubbed|esub)\\b"), " ")
            .replace(Regex("\\[[^]]+]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun LoadResponse.addSyncIds(obj: JSONObject, meta: TmdbMeta) {
        // Simkl matches through IMDb. CTG/TMDB normally provides this id for
        // both movies and TV, so adding IMDb id is enough for Simkl sync.
        runCatching { meta.imdbId?.takeIf { it.isNotBlank() }?.let { addImdbId(it) } }
        runCatching { obj.optStringOrNull("imdb_id")?.let { addImdbId(it) } }
        runCatching { (meta.simklId ?: obj.optIntOrNull("simkl_id"))?.let { addSimklId(it) } }

        // Anime tracker ids are available on CTG anime records when present.
        runCatching { obj.optIntOrNull("mal_id")?.let { addMalId(it) } }
        runCatching { obj.optIntOrNull("anilist_id")?.let { addAniListId(it) } }
        runCatching { obj.optIntOrNull("kitsu_id")?.let { addKitsuId(it) } }
    }

    private fun JSONArray.toStringList(key: String): List<String> =
        (0 until length()).mapNotNull { i -> optJSONObject(i)?.optStringOrNull(key) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun JSONArray.bestTmdbImage(key: String, preferLogo: Boolean = false): String? {
        if (length() == 0) return null
        val candidates = (0 until length()).mapNotNull { i -> optJSONObject(i) }
            .sortedWith(
                compareByDescending<JSONObject> {
                    val lang = it.optStringOrNull("iso_639_1")
                    if (lang == "en") 3 else if (lang == null) 2 else 1
                }.thenByDescending { it.optDoubleOrNull("vote_average") ?: 0.0 }
            )
        val picked = if (preferLogo) {
            candidates.firstOrNull { it.optDoubleOrNull("aspect_ratio")?.let { ratio -> ratio >= 1.5 } == true }
                ?: candidates.firstOrNull()
        } else candidates.firstOrNull()
        return picked?.optStringOrNull(key)
    }

    private fun JSONArray.bestYoutubeTrailer(): String? {
        val videos = (0 until length()).mapNotNull { i -> optJSONObject(i) }
            .filter { it.optStringOrNull("site")?.equals("YouTube", ignoreCase = true) == true }
        val picked = videos.firstOrNull {
            it.optStringOrNull("type")?.equals("Trailer", ignoreCase = true) == true &&
                    it.optStringOrNull("official")?.equals("true", ignoreCase = true) == true
        } ?: videos.firstOrNull { it.optStringOrNull("type")?.equals("Trailer", ignoreCase = true) == true }
            ?: videos.firstOrNull()
        return picked?.optStringOrNull("key")?.let { "https://youtu.be/$it" }
    }

    private fun JSONArray.toActors(limit: Int = 20): List<ActorData> =
        (0 until length()).mapNotNull { i ->
            val cast = optJSONObject(i) ?: return@mapNotNull null
            val name = cast.optStringOrNull("name") ?: cast.optStringOrNull("original_name") ?: return@mapNotNull null
            val profile = cast.optStringOrNull("profile_path")?.let { tmdbImg("w185", it) }
            val character = cast.optStringOrNull("character")
            ActorData(Actor(name, profile), roleString = character ?: "")
        }.take(limit)

    private fun resolveSubtitleUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        url.startsWith("/") -> apiOrigin() + url
        else -> apiOrigin() + "/$url"
    }

    private fun resolveMediaUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        url.startsWith("/") -> apiOrigin() + url
        else -> url
    }

    private fun isDirectVideo(url: String): Boolean {
        val lower = url.lowercase().substringBefore("?")
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
                lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".ts") ||
                lower.endsWith(".m4v")
    }

    private fun qualityFromUrl(url: String): String? =
        Regex("(?i)(2160p|1440p|1080p|720p|480p|360p|4k)").find(url)?.value

    private fun String.cleanSourceName(): String =
        replace("auto:", "")
            .replace(":", " ")
            .replace("-", " ")
            .trim()
            .ifBlank { this }

    private fun String.normalizedTitle(): String =
        lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun String.encodeUrl(): String = URLEncoder.encode(this, "UTF-8")

    private fun String.splitCsv(): List<String> =
        split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun yearFromDate(date: String?): Int? =
        date?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null
        else optString(key, "").trim().takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null
        else optString(key, "").toIntOrNull() ?: optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null
        else optString(key, "").toDoubleOrNull() ?: optDouble(key, Double.NaN).takeIf { !it.isNaN() }
}
