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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

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
    }

    override val mainPage = mainPageOf(
        "movies" to "Latest Movies",
        "tv" to "Latest TV Shows",
        "anime" to "Latest Anime",
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
        val out = mutableListOf<SearchResponse>()
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        runCatching { out += parseList(apiGet("/movies", mapOf("search" to q)), "movies") }
        runCatching { out += parseList(apiGet("/tv", mapOf("search" to q)), "tv") }
        runCatching { out += parseList(apiGet("/anime", mapOf("search" to q)), "anime") }

        return out.distinctBy { it.url }
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
        val isAnime = kind == "anime" || obj.optBoolean("is_anime", false)
        return when {
            kind == "movies" -> loadMovie(url, obj)
            isAnime -> loadAnime(url, obj)
            else -> loadTv(url, obj)
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

        for (i in 0 until links.length()) {
            val link = links.optJSONObject(i) ?: continue
            if (link.optBoolean("broken", false)) continue

            val rawUrl = link.optStringOrNull("url") ?: continue
            val finalUrl = resolveMediaUrl(rawUrl)
            if (finalUrl.isBlank() || !seenLinks.add(finalUrl)) continue

            link.optJSONArray("subtitle_tracks")?.let { subs ->
                for (s in 0 until subs.length()) {
                    val sub = subs.optJSONObject(s) ?: continue
                    val subUrl = resolveSubtitleUrl(sub.optStringOrNull("url") ?: continue)
                    if (subUrl.isBlank() || !seenSubs.add(subUrl)) continue
                    val label = sub.optStringOrNull("label")
                        ?: sub.optStringOrNull("language")
                        ?: "Subtitle"
                    subtitleCallback(newSubtitleFile(label, subUrl))
                }
            }

            val qualityName = link.optStringOrNull("quality") ?: qualityFromUrl(finalUrl)
            val sourceName = buildString {
                append(name)
                link.optStringOrNull("source_display")?.let { append(" - ").append(it.cleanSourceName()) }
                    ?: link.optStringOrNull("source")?.let { append(" - ").append(it.cleanSourceName()) }
                qualityName?.let { append(" [$it]") }
            }

            if (finalUrl.contains(".m3u8", ignoreCase = true)) {
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
                        source = name,
                        name = sourceName,
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        referer = mainUrl
                        quality = getQualityFromName(qualityName ?: finalUrl)
                    }
                )
                found = true
            } else {
                // Some future CTG sources may be embeds instead of direct files.
                val before = found
                runCatching {
                    loadExtractor(finalUrl, mainUrl, subtitleCallback) { extractorLink ->
                        callback(extractorLink)
                        found = true
                    }
                }
                if (!found && !before && finalUrl.startsWith("http")) {
                    // Last-resort download/folder link. Cloudstream may not play folders,
                    // but exposing it is still useful for clients with external handling.
                    callback(
                        newExtractorLink(
                            source = name,
                            name = sourceName,
                            url = finalUrl,
                            type = INFER_TYPE,
                        ) {
                            referer = mainUrl
                            quality = getQualityFromName(qualityName ?: finalUrl)
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
    )

    private suspend fun fetchTmdbMeta(obj: JSONObject, mediaType: String, fallbackTitle: String?): TmdbMeta {
        val tmdbId = obj.optIntOrNull("tmdb_id") ?: findTmdbId(mediaType, fallbackTitle, obj)
        if (tmdbId == null) return TmdbMeta(
            imdbId = obj.optStringOrNull("imdb_id"),
            tags = obj.optStringOrNull("genres")?.splitCsv()
        )

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

        return TmdbMeta(
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
        )
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
        val title = meta.title ?: baseTitle
        val data = JSONObject()
            .put("kind", "movie")
            .put("id", obj.optStringOrNull("id"))
            .put("links", obj.optJSONArray("links") ?: JSONArray())
            .toString()

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, data) {
            posterUrl = meta.posterUrl ?: obj.optStringOrNull("poster_url")
            backgroundPosterUrl = meta.backdropUrl ?: obj.optStringOrNull("backdrop_url")
            plot = meta.plot ?: obj.optStringOrNull("overview")
            year = meta.year ?: obj.optIntOrNull("year") ?: yearFromDate(obj.optStringOrNull("release_date"))
            duration = meta.runtime ?: obj.optIntOrNull("runtime")
            tags = meta.tags ?: obj.optStringOrNull("genres")?.splitCsv()
            runCatching { meta.actors?.let { actors = it } }
            runCatching { (meta.rating ?: obj.optDoubleOrNull("rating"))?.let { score = Score.from10(it) } }
            runCatching { (meta.trailerUrl ?: obj.optStringOrNull("trailer_url"))?.let { addTrailer(it) } }
            runCatching { meta.logoUrl?.let { logoUrl = it } }
            addSyncIds(obj, meta)
        }
    }

    private suspend fun loadTv(pageUrl: String, obj: JSONObject): LoadResponse {
        val baseTitle = obj.optStringOrNull("name") ?: obj.optStringOrNull("title") ?: "Untitled"
        val meta = fetchTmdbMeta(obj, "tv", baseTitle)
        val title = meta.title ?: baseTitle
        val episodes = parseEpisodes(obj.optJSONArray("episodes"), anime = false)
        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            posterUrl = meta.posterUrl ?: obj.optStringOrNull("poster_url") ?: obj.optStringOrNull("cover_url")
            backgroundPosterUrl = meta.backdropUrl ?: obj.optStringOrNull("backdrop_url") ?: obj.optStringOrNull("banner_url")
            plot = meta.plot ?: obj.optStringOrNull("overview") ?: obj.optStringOrNull("description")
            year = meta.year ?: yearFromDate(obj.optStringOrNull("first_air_date")) ?: obj.optIntOrNull("year")
            tags = meta.tags ?: obj.optStringOrNull("genres")?.splitCsv()
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
        val episodes = parseEpisodes(obj.optJSONArray("episodes"), anime = true)
        val tvType = if (episodes.isEmpty()) TvType.AnimeMovie else TvType.Anime
        val meta = fetchTmdbMeta(obj, if (tvType == TvType.AnimeMovie) "movie" else "tv", baseTitle)
        val title = meta.title ?: baseTitle

        return if (tvType == TvType.AnimeMovie) {
            val data = JSONObject()
                .put("kind", "anime")
                .put("id", obj.optStringOrNull("id") ?: obj.optStringOrNull("_id"))
                .put("links", obj.optJSONArray("links") ?: JSONArray())
                .toString()
            newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, data) {
                posterUrl = meta.posterUrl ?: obj.optStringOrNull("cover_url") ?: obj.optStringOrNull("poster_url")
                backgroundPosterUrl = meta.backdropUrl ?: obj.optStringOrNull("banner_url") ?: obj.optStringOrNull("backdrop_url")
                plot = meta.plot ?: obj.optStringOrNull("description") ?: obj.optStringOrNull("overview")
                year = meta.year ?: obj.optIntOrNull("year") ?: yearFromDate(obj.optStringOrNull("first_air_date"))
                duration = meta.runtime
                tags = meta.tags ?: obj.optStringOrNull("genres")?.splitCsv()
                runCatching { meta.actors?.let { actors = it } }
                runCatching { (meta.rating ?: obj.optDoubleOrNull("rating"))?.let { score = Score.from10(it) } }
                runCatching { (meta.trailerUrl ?: obj.optStringOrNull("trailer_url"))?.let { addTrailer(it) } }
                runCatching { meta.logoUrl?.let { logoUrl = it } }
                addSyncIds(obj, meta)
            }
        } else {
            newAnimeLoadResponse(title, pageUrl, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                posterUrl = meta.posterUrl ?: obj.optStringOrNull("cover_url") ?: obj.optStringOrNull("poster_url")
                backgroundPosterUrl = meta.backdropUrl ?: obj.optStringOrNull("banner_url") ?: obj.optStringOrNull("backdrop_url")
                plot = meta.plot ?: obj.optStringOrNull("description") ?: obj.optStringOrNull("overview")
                year = meta.year ?: obj.optIntOrNull("year") ?: yearFromDate(obj.optStringOrNull("first_air_date"))
                tags = meta.tags ?: obj.optStringOrNull("genres")?.splitCsv()
                runCatching { meta.actors?.let { actors = it } }
                runCatching { (meta.rating ?: obj.optDoubleOrNull("rating"))?.let { score = Score.from10(it) } }
                runCatching { (meta.trailerUrl ?: obj.optStringOrNull("trailer_url"))?.let { addTrailer(it) } }
                runCatching { meta.logoUrl?.let { logoUrl = it } }
                addSyncIds(obj, meta)
            }
        }
    }

    private fun parseEpisodes(array: JSONArray?, anime: Boolean): List<Episode> {
        if (array == null) return emptyList()
        val out = mutableListOf<Episode>()
        for (i in 0 until array.length()) {
            val ep = array.optJSONObject(i) ?: continue
            val epNum = ep.optIntOrNull("episode_number") ?: ep.optIntOrNull("absolute_number") ?: (i + 1)
            val seasonNum = ep.optIntOrNull("season_number") ?: 1
            val epTitle = ep.optStringOrNull("name")
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
                posterUrl = ep.optStringOrNull("still_url") ?: ep.optStringOrNull("thumbnail_url")
                description = ep.optStringOrNull("overview") ?: ep.optStringOrNull("description")
                runTime = ep.optIntOrNull("runtime")
            }
        }
        return out.distinctBy { (it.season ?: 1) to (it.episode ?: 0) to it.data }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    // ───────────────────────────── Search/listing ────────────────────────────

    private fun parseList(raw: String, kind: String): List<SearchResponse> {
        val trimmed = raw.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> JSONObject(trimmed).optJSONArray("movies") ?: JSONArray()
        }

        val out = mutableListOf<SearchResponse>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            toSearchResponse(obj, kind)?.let(out::add)
        }
        return out.distinctBy { it.url }
    }

    private fun toSearchResponse(obj: JSONObject, kind: String): SearchResponse? {
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

        return when {
            isMovie -> newMovieSearchResponse(title, "$mainUrl/movies/$id", TvType.Movie) {
                posterUrl = poster
                this.year = year
            }
            isAnime -> newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
                posterUrl = poster
                this.year = year
                runCatching { obj.optDoubleOrNull("rating")?.let { score = Score.from10(it) } }
            }
            else -> newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    // ───────────────────────────── API/auth ──────────────────────────────────

    private suspend fun apiGet(path: String, query: Map<String, Any?> = emptyMap()): String {
        ensureToken(false)
        val url = buildApiUrl(path, query)
        var response = app.get(url, headers = apiHeaders())

        if (response.code == 401 || response.code == 403) {
            ensureToken(true)
            response = app.get(url, headers = apiHeaders())
        }

        if (response.code !in 200..299) {
            // Same-origin fallback catches deployments where the public API is blocked
            // but Next.js proxy routes still work.
            val fallback = runCatching {
                app.get(mainUrl + "/api/v1" + (if (path.startsWith("/")) path else "/$path") + queryString(query), headers = apiHeaders())
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
                json = mapOf("email" to email, "password" to password)
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
