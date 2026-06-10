package com.wizdier

import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.newSubtitleFile
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

    companion object {
        const val PREF_FILE = "CTGMovies"
        const val PREF_EMAIL = "ctg_email"
        const val PREF_PASSWORD = "ctg_password"
        const val PREF_TOKEN = "ctg_token"
        const val PREF_COOKIE = "ctg_cookie"
        const val PREF_API_BASE = "ctg_api_base"
        const val DEFAULT_API_BASE = "https://cockpit.103.109.92.178.nip.io/api/v1"

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

    // ───────────────────────────── Load builders ─────────────────────────────

    private fun loadMovie(pageUrl: String, obj: JSONObject): LoadResponse {
        val title = obj.optStringOrNull("title") ?: "Untitled"
        val data = JSONObject()
            .put("kind", "movie")
            .put("id", obj.optStringOrNull("id"))
            .put("links", obj.optJSONArray("links") ?: JSONArray())
            .toString()

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, data) {
            posterUrl = obj.optStringOrNull("poster_url")
            backgroundPosterUrl = obj.optStringOrNull("backdrop_url")
            plot = obj.optStringOrNull("overview")
            year = obj.optIntOrNull("year") ?: yearFromDate(obj.optStringOrNull("release_date"))
            duration = obj.optIntOrNull("runtime")
            tags = obj.optStringOrNull("genres")?.splitCsv()
            runCatching { obj.optDoubleOrNull("rating")?.let { score = Score.from10(it) } }
            runCatching { obj.optStringOrNull("trailer_url")?.let { addTrailer(it) } }
        }
    }

    private fun loadTv(pageUrl: String, obj: JSONObject): LoadResponse {
        val title = obj.optStringOrNull("name") ?: obj.optStringOrNull("title") ?: "Untitled"
        val episodes = parseEpisodes(obj.optJSONArray("episodes"), anime = false)
        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            posterUrl = obj.optStringOrNull("poster_url") ?: obj.optStringOrNull("cover_url")
            backgroundPosterUrl = obj.optStringOrNull("backdrop_url") ?: obj.optStringOrNull("banner_url")
            plot = obj.optStringOrNull("overview") ?: obj.optStringOrNull("description")
            year = yearFromDate(obj.optStringOrNull("first_air_date")) ?: obj.optIntOrNull("year")
            tags = obj.optStringOrNull("genres")?.splitCsv()
            runCatching { obj.optDoubleOrNull("rating")?.let { score = Score.from10(it) } }
            runCatching { obj.optStringOrNull("trailer_url")?.let { addTrailer(it) } }
        }
    }

    private fun loadAnime(pageUrl: String, obj: JSONObject): LoadResponse {
        val title = obj.optStringOrNull("title")
            ?: obj.optStringOrNull("name")
            ?: obj.optStringOrNull("english_title")
            ?: "Untitled"
        val episodes = parseEpisodes(obj.optJSONArray("episodes"), anime = true)
        val tvType = if (episodes.isEmpty()) TvType.AnimeMovie else TvType.Anime

        return if (tvType == TvType.AnimeMovie) {
            val data = JSONObject()
                .put("kind", "anime")
                .put("id", obj.optStringOrNull("id") ?: obj.optStringOrNull("_id"))
                .put("links", obj.optJSONArray("links") ?: JSONArray())
                .toString()
            newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, data) {
                posterUrl = obj.optStringOrNull("cover_url") ?: obj.optStringOrNull("poster_url")
                backgroundPosterUrl = obj.optStringOrNull("banner_url") ?: obj.optStringOrNull("backdrop_url")
                plot = obj.optStringOrNull("description") ?: obj.optStringOrNull("overview")
                year = obj.optIntOrNull("year") ?: yearFromDate(obj.optStringOrNull("first_air_date"))
                tags = obj.optStringOrNull("genres")?.splitCsv()
                runCatching { obj.optDoubleOrNull("rating")?.let { score = Score.from10(it) } }
                runCatching { obj.optStringOrNull("trailer_url")?.let { addTrailer(it) } }
            }
        } else {
            newAnimeLoadResponse(title, pageUrl, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                posterUrl = obj.optStringOrNull("cover_url") ?: obj.optStringOrNull("poster_url")
                backgroundPosterUrl = obj.optStringOrNull("banner_url") ?: obj.optStringOrNull("backdrop_url")
                plot = obj.optStringOrNull("description") ?: obj.optStringOrNull("overview")
                year = obj.optIntOrNull("year") ?: yearFromDate(obj.optStringOrNull("first_air_date"))
                tags = obj.optStringOrNull("genres")?.splitCsv()
                runCatching { obj.optDoubleOrNull("rating")?.let { score = Score.from10(it) } }
                runCatching { obj.optStringOrNull("trailer_url")?.let { addTrailer(it) } }
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
