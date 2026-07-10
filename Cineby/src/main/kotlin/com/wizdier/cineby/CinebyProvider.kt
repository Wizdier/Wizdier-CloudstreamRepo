package com.wizdier.cineby

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class CinebyProvider : MainAPI() {
    override var mainUrl = "https://www.cineby.at"
    override var name = "Cineby"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true

    private val tmdbBase = "https://db.wingsdatabase.com/3"
    private val imgBase = "https://image.tmdb.org/t/p"
    private val vidKingBase = "https://www.vidking.net"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "trending_movie_day" to "Trending Movies",
        "trending_tv_day" to "Trending TV Shows",
        "movie_top_rated" to "Top Rated Movies",
        "tv_top_rated" to "Top Rated TV Shows",
        "movie_now_playing" to "Now Playing",
        "movie_upcoming" to "Upcoming",
        "tv_airing_today" to "Airing Today TV",
        "movie_popular" to "Popular Movies",
        "tv_popular" to "Popular TV Shows",
        "trending_all_day" to "Trending Today (All)",
        "discover_movie_28" to "Action Movies",
        "discover_movie_35" to "Comedy Movies",
        "discover_movie_27" to "Horror Movies",
        "discover_movie_10749" to "Romance Movies",
        "discover_movie_878" to "Sci-Fi Movies",
        "discover_movie_16" to "Animation Movies",
        "discover_tv_18" to "Drama TV",
        "discover_tv_10765" to "Sci-Fi & Fantasy TV",
        "discover_tv_10759" to "Action & Adventure TV",
        "discover_movie_ko" to "Korean Movies",
        "discover_movie_ja" to "Japanese Movies",
        "discover_movie_hi" to "Indian Movies"
    )

    private fun posterUrl(path: String?, size: String = "w342"): String? {
        if (path.isNullOrBlank()) return null
        return "$imgBase/$size$path"
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key, "").takeIf { it.isNotBlank() && it != "null" }
    }

    private suspend fun tmdbGet(endpoint: String): JSONObject? {
        return try {
            JSONObject(app.get("$tmdbBase/$endpoint", timeout = 10_000).text)
        } catch (e: Exception) {
            Log.e("Cineby", "TMDB $endpoint failed: ${e.message}")
            null
        }
    }

    // ── TMDB search → search responses ──
    private fun tmdbResultsToSearch(arr: org.json.JSONArray?, type: String): List<SearchResponse> {
        if (arr == null) return emptyList()
        val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
        return (0 until arr.length()).mapNotNull { i ->
            val item = arr.getJSONObject(i)
            val id = item.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
            val title = item.optStringOrNull("title") ?: item.optStringOrNull("name") ?: "?"
            val rating = item.optDouble("vote_average", 0.0).takeIf { it > 0 }
            newMovieSearchResponse(title, "$type/$id", tvType) {
                this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                if (rating != null) this.score = Score.from10(rating)
            }
        }
    }

    // ── SEARCH ──
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return coroutineScope {
            val movies = async {
                tmdbGet("search/movie?query=${java.net.URLEncoder.encode(query, "UTF-8")}")
                    ?.optJSONArray("results")?.let { tmdbResultsToSearch(it, "movie") } ?: emptyList()
            }
            val tv = async {
                tmdbGet("search/tv?query=${java.net.URLEncoder.encode(query, "UTF-8")}")
                    ?.optJSONArray("results")?.let { tmdbResultsToSearch(it, "tv") } ?: emptyList()
            }
            movies.await() + tv.await()
        }
    }

    // ── HOME PAGE ──
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val results = mutableListOf<SearchResponse>()

        when {
            data.startsWith("trending_") -> {
                val parts = data.removePrefix("trending_").split("_")
                val mediaType = parts[0]
                val time = parts.getOrElse(1) { "day" }
                val json = tmdbGet("trending/$mediaType/$time?page=$page")
                json?.optJSONArray("results")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val id = item.optInt("id", 0).takeIf { it > 0 } ?: continue
                        val mediaTypeItem = item.optStringOrNull("media_type") ?: "movie"
                        val title = item.optStringOrNull("title") ?: item.optStringOrNull("name") ?: "?"
                        val tvType = if (mediaTypeItem == "movie") TvType.Movie else TvType.TvSeries
                        results.add(
                            newMovieSearchResponse(title, "$mediaTypeItem/$id", tvType) {
                                this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                                item.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) }
                            }
                        )
                    }
                }
            }
            data.endsWith("_top_rated") || data.endsWith("_popular") -> {
                val suffix = if (data.endsWith("_top_rated")) "_top_rated" else "_popular"
                val type = data.removeSuffix(suffix)
                val tmdbType = when (suffix) { "_top_rated" -> "top_rated" else -> "popular" }
                val json = tmdbGet("$type/$tmdbType?page=$page")
                json?.optJSONArray("results")?.let { arr -> results.addAll(tmdbResultsToSearch(arr, type)) }
            }
            data.startsWith("movie_now_playing") -> {
                tmdbGet("movie/now_playing?page=$page")?.optJSONArray("results")?.let { results.addAll(tmdbResultsToSearch(it, "movie")) }
            }
            data.startsWith("movie_upcoming") -> {
                tmdbGet("movie/upcoming?page=$page")?.optJSONArray("results")?.let { results.addAll(tmdbResultsToSearch(it, "movie")) }
            }
            data.startsWith("tv_airing_today") -> {
                tmdbGet("tv/airing_today?page=$page")?.optJSONArray("results")?.let { results.addAll(tmdbResultsToSearch(it, "tv")) }
            }
            data.startsWith("discover_") -> {
                val parts = data.removePrefix("discover_").split("_")
                val type = parts[0]
                val param = parts.getOrElse(1) { "" }
                val p = mutableListOf<String>()
                param.toIntOrNull()?.let { p.add("with_genres=$it") }
                if (param.length == 2 && param !in listOf("tv", "mo")) p.add("with_original_language=$param")
                p.add("sort_by=popularity.desc")
                p.add("page=$page")
                tmdbGet("discover/$type?${p.joinToString("&")}")?.optJSONArray("results")?.let {
                    results.addAll(tmdbResultsToSearch(it, type))
                }
            }
        }

        return newHomePageResponse(request.name, results, results.isNotEmpty() && page < 10)
    }

    // ── LOAD (Detail page) ──
    override suspend fun load(url: String): LoadResponse? {
        // Cloudstream prepends mainUrl → url can be "https://.../movie/123" or just "movie/123"
        // Grab the last two path segments: type then id
        val segments = url.trimEnd('/').split("/")
        if (segments.size < 2) return null
        val tmdbId = segments.last()
        val type = segments[segments.size - 2]
        return when (type) {
            "movie" -> loadMovie(tmdbId)
            "tv" -> loadTvSeries(tmdbId)
            else -> null
        }
    }

    private suspend fun loadMovie(tmdbId: String): LoadResponse? {
        val json = tmdbGet("movie/$tmdbId?append_to_response=credits,videos,recommendations") ?: return null
        val title = json.optStringOrNull("title") ?: return null
        val poster = posterUrl(json.optStringOrNull("poster_path"))
        val backdrop = posterUrl(json.optStringOrNull("backdrop_path"), "w780")
        val plot = json.optStringOrNull("overview")
        val year = json.optStringOrNull("release_date")?.substringBefore("-")?.toIntOrNull()
        val rating = json.optDouble("vote_average", 0.0).takeIf { it > 0 }
        val imdbId = json.optStringOrNull("imdb_id")
        val runtime = json.optInt("runtime", 0).takeIf { it > 0 }

        val genres = json.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optStringOrNull("name") }
        }

        val actors = json.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            (0 until minOf(arr.length(), 15)).mapNotNull { i ->
                val p = arr.getJSONObject(i)
                val name = p.optStringOrNull("name") ?: return@mapNotNull null
                ActorData(
                    actor = Actor(name, posterUrl(p.optStringOrNull("profile_path"), "w185")),
                    roleString = p.optStringOrNull("character")
                )
            }
        }

        var trailer: String? = null
        json.optJSONObject("videos")?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                if (v.optString("type") == "Trailer" && v.optString("site") == "YouTube") {
                    trailer = "https://www.youtube.com/watch?v=${v.optString("key")}"
                    break
                }
            }
        }

        val recs = json.optJSONObject("recommendations")?.optJSONArray("results")?.let { arr ->
            (0 until minOf(arr.length(), 10)).mapNotNull { i ->
                val item = arr.getJSONObject(i)
                val id = item.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
                newMovieSearchResponse(item.optStringOrNull("title") ?: "?", "movie/$id", TvType.Movie) {
                    this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                }
            }
        }

        val loadUrl = "movie/$tmdbId"
        // Pipe-delimited data: movie_extract|tmdbId|encodedTitle|year|imdbId
        val data = "movie_extract|$tmdbId|${java.net.URLEncoder.encode(title, "UTF-8")}|${year ?: ""}|${imdbId ?: ""}"

        return newMovieLoadResponse(title, loadUrl, TvType.Movie, data) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            if (rating != null) this.score = Score.from10(rating)
            this.tags = genres
            if (actors != null) this.actors = actors
            if (recs != null) this.recommendations = recs
            if (trailer != null) addTrailer(trailer)
            if (imdbId != null) addImdbId(imdbId)
            if (runtime != null) this.duration = runtime
        }
    }

    private suspend fun loadTvSeries(tmdbId: String): LoadResponse? {
        val json = tmdbGet("tv/$tmdbId?append_to_response=external_ids,credits,recommendations") ?: return null
        val title = json.optStringOrNull("name") ?: return null
        val poster = posterUrl(json.optStringOrNull("poster_path"))
        val backdrop = posterUrl(json.optStringOrNull("backdrop_path"), "w780")
        val plot = json.optStringOrNull("overview")
        val year = json.optStringOrNull("first_air_date")?.substringBefore("-")?.toIntOrNull()
        val rating = json.optDouble("vote_average", 0.0).takeIf { it > 0 }
        val imdbId = json.optJSONObject("external_ids")?.optStringOrNull("imdb_id")
            ?: json.optStringOrNull("imdb_id")
        val seasons = json.optInt("number_of_seasons", 1)

        val genres = json.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optStringOrNull("name") }
        }

        val actors = json.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            (0 until minOf(arr.length(), 15)).mapNotNull { i ->
                val p = arr.getJSONObject(i)
                val name = p.optStringOrNull("name") ?: return@mapNotNull null
                ActorData(
                    actor = Actor(name, posterUrl(p.optStringOrNull("profile_path"), "w185")),
                    roleString = p.optStringOrNull("character")
                )
            }
        }

        val recs = json.optJSONObject("recommendations")?.optJSONArray("results")?.let { arr ->
            (0 until minOf(arr.length(), 10)).mapNotNull { i ->
                val item = arr.getJSONObject(i)
                val id = item.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
                newMovieSearchResponse(item.optStringOrNull("name") ?: "?", "tv/$id", TvType.TvSeries) {
                    this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                }
            }
        }

        // Build episodes
        val episodes = mutableListOf<Episode>()
        for (s in 1..minOf(seasons, 20)) {
            val sJson = tmdbGet("tv/$tmdbId/season/$s") ?: continue
            val epArr = sJson.optJSONArray("episodes") ?: continue
            for (j in 0 until epArr.length()) {
                val ep = epArr.getJSONObject(j)
                val epNum = ep.optInt("episode_number", j + 1)
                val epName = ep.optStringOrNull("name") ?: "Episode $epNum"
                val epStill = posterUrl(ep.optStringOrNull("still_path"), "w300")
                val epDesc = ep.optStringOrNull("overview")

                // Format: "tv_extract|tmdbId|season|episode|encodedTitle|year|imdbId"
                val edata = "tv_extract|$tmdbId|$s|$epNum|${java.net.URLEncoder.encode(title, "UTF-8")}|${year ?: ""}|${imdbId ?: ""}"

                episodes.add(
                    newEpisode(edata) {
                        this.name = epName
                        this.season = s
                        this.episode = epNum
                        this.posterUrl = epStill
                        this.description = epDesc
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, "tv/$tmdbId", TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            if (rating != null) this.score = Score.from10(rating)
            this.tags = genres
            if (actors != null) this.actors = actors
            if (recs != null) this.recommendations = recs
            if (imdbId != null) addImdbId(imdbId)
        }
    }

    // ── LOAD LINKS (8 WingsDatabase servers + VidKing web fallback) ──
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 3) return false

        val extractType = parts[0]
        if (extractType !in listOf("movie_extract", "tv_extract")) return false

        try {
            val tmdbId = parts[1]
            val title: String
            val year: String
            val imdbId: String
            val mediaType: String
            val seasonId: String
            val episodeId: String

            if (extractType == "movie_extract") {
                title = java.net.URLDecoder.decode(parts.getOrElse(2) { "" }, "UTF-8")
                year = parts.getOrElse(3) { "" }
                imdbId = parts.getOrElse(4) { "" }
                mediaType = "movie"; seasonId = "1"; episodeId = "1"
            } else {
                seasonId = parts.getOrElse(2) { "1" }
                episodeId = parts.getOrElse(3) { "1" }
                title = java.net.URLDecoder.decode(parts.getOrElse(4) { "" }, "UTF-8")
                year = parts.getOrElse(5) { "" }
                imdbId = parts.getOrElse(6) { "" }
                mediaType = "tv"
            }

            // Try WingsDatabase → enc-dec.app for direct stream URLs
            extractFromAllServers(mediaType, tmdbId, seasonId, episodeId, title, year, imdbId, callback, subtitleCallback)

            // Fallback: VidKing embed page (always available, web-based player)
            val fallbackUrl = if (mediaType == "movie") {
                "$vidKingBase/embed/movie/$tmdbId"
            } else {
                "$vidKingBase/embed/tv/$tmdbId/$seasonId/$episodeId"
            }
            callback.invoke(
                newExtractorLink(
                    source = "$name (Web)",
                    name = "VidKing Player",
                    url = fallbackUrl
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )

            return true
        } catch (e: Exception) {
            Log.e("Cineby", "loadLinks: ${e.message}")
            return false
        }
    }

    private suspend fun extractFromAllServers(
        mediaType: String, tmdbId: String, seasonId: String, episodeId: String,
        title: String, year: String, imdbId: String,
        callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit
    ) {
        // All 8 English servers from WingsDatabase
        val servers = listOf(
            "jett" to "jett/sources-with-title",
            "cdn" to "cdn/sources-with-title",        // Yoru — movies only
            "tejo" to "tejo/sources-with-title",
            "neon2" to "neon2/sources-with-title",
            "ym" to "ym/sources-with-title",
            "downloader2" to "downloader2/sources-with-title",
            "m4uhd" to "m4uhd/sources-with-title",
            "hdmovie" to "hdmovie/sources-with-title"
        )

        val serverLabels = mapOf(
            "jett" to "Jett", "cdn" to "Yoru", "tejo" to "Tejo", "neon2" to "Neon",
            "ym" to "Sage", "downloader2" to "Cypher", "m4uhd" to "Breach", "hdmovie" to "Vyse"
        )

        // Step 1: Get seed
        val seed: String
        try {
            val seedResp = app.get(
                "https://api.wingsdatabase.com/seed?mediaId=$tmdbId",
                timeout = 10_000,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
                    "Accept" to "application/json",
                    "Referer" to "$vidKingBase/",
                    "Origin" to vidKingBase
                )
            ).text
            seed = JSONObject(seedResp).optStringOrNull("seed") ?: return
        } catch (e: Exception) {
            Log.e("Cineby", "Seed failed: ${e.message}")
            return
        }

        // Double-encode title
        val encTitle = java.net.URLEncoder.encode(java.net.URLEncoder.encode(title, "UTF-8"), "UTF-8")

        for ((serverKey, endpoint) in servers) {
            // Yoru is movies-only
            if (serverKey == "cdn" && mediaType != "movie") continue

            try {
                val params = listOf(
                    "title" to encTitle, "mediaType" to mediaType, "year" to year,
                    "episodeId" to episodeId, "seasonId" to seasonId,
                    "tmdbId" to tmdbId, "imdbId" to imdbId,
                    "enc" to "2", "seed" to seed
                )
                val qs = params.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
                val apiUrl = "https://api.wingsdatabase.com/$endpoint?$qs"

                val encData = app.get(apiUrl, timeout = 15_000, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to "$vidKingBase/",
                    "Origin" to vidKingBase,
                    "Cache-Control" to "no-cache"
                )).text

                if (encData.isBlank() || encData.startsWith("{") || encData.length < 100) continue

                // Step 3: Decrypt via enc-dec.app
                val decryptBody = JSONObject().apply {
                    put("text", encData); put("id", tmdbId); put("seed", seed)
                }
                val decryptResp = app.post(
                    "https://enc-dec.app/api/dec-videasy",
                    requestBody = decryptBody.toString().toRequestBody("application/json".toMediaTypeOrNull()),
                    timeout = 20_000,
                    headers = mapOf("Content-Type" to "application/json")
                ).text

                val result = JSONObject(decryptResp)
                if (result.optInt("status", -1) != 200) continue

                val obj = result.optJSONObject("result") ?: continue
                val sources = obj.optJSONArray("sources") ?: continue
                val label = serverLabels[serverKey] ?: serverKey

                for (i in 0 until sources.length()) {
                    val src = sources.getJSONObject(i)
                    val url = src.optStringOrNull("url") ?: continue
                    val quality = src.optStringOrNull("quality") ?: "Unknown"
                    callback.invoke(
                        newExtractorLink(
                            source = "$name ($label)",
                            name = "$label - $quality",
                            url = url
                        ) {
                            this.quality = getQualityFromName(quality)
                        }
                    )
                }

                // Subtitles
                obj.optJSONArray("subtitles")?.let { subs ->
                    for (i in 0 until subs.length()) {
                        val sub = subs.getJSONObject(i)
                        val subUrl = sub.optStringOrNull("url") ?: continue
                        val lang = sub.optStringOrNull("lang") ?: "unknown"
                        subtitleCallback.invoke(SubtitleFile(subUrl, lang))
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }
    }
}
