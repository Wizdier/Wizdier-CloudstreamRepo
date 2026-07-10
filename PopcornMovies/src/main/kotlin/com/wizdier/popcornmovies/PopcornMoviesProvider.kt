package com.wizdier.popcornmovies

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
import org.json.JSONArray

class PopcornMoviesProvider : MainAPI() {
    override var mainUrl = "https://popcornmovies.io"
    override var name = "PopcornMovies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true

    private val tmdbAPI = "https://api.themoviedb.org/3"
    private val tmdbKey = "98ae14df2b8d8f8f8136499daf79f0e0"
    private val imgBase = "https://image.tmdb.org/t/p"

    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama
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
        "discover_movie_hi" to "Indian Movies"
    )

    // ── Helpers ──
    private fun posterUrl(path: String?, size: String = "w342"): String? {
        if (path.isNullOrBlank()) return null; return "$imgBase/$size$path"
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key, "").takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.optStringSafe(key: String): String? {
        if (isNull(key)) return null
        val v = optString(key); return v.takeIf { it.isNotBlank() && it != "null" }
    }

    private suspend fun tmdbGet(endpoint: String): JSONObject? {
        val sep = if (endpoint.contains("?")) "&" else "?"
        return try { JSONObject(app.get("$tmdbAPI/$endpoint${sep}api_key=$tmdbKey", timeout = 10_000).text) }
        catch (e: Exception) { Log.e(name, "TMDB $endpoint: ${e.message}"); null }
    }

    /** Logo picker — same algorithm as your other plugins */
    private fun pickBestTmdbLogo(logos: JSONArray, preferredLang: String?): String? {
        if (logos.length() == 0) return null
        val lng = preferredLang?.trim()?.lowercase()?.substringBefore("-")
        var enSvg: JSONObject? = null; var anyNonSvg: JSONObject? = null; var anySvg: JSONObject? = null
        for (i in 0 until logos.length()) {
            val l = logos.optJSONObject(i) ?: continue
            val path = l.optString("file_path", ""); if (path.isBlank()) continue
            val ll = l.optString("iso_639_1").trim().lowercase()
            val isSvg = path.endsWith(".svg", true)
            if (ll == lng && !isSvg) return "$imgBase/w500$path"
            if (ll == lng && isSvg && enSvg == null) enSvg = l
            if (!isSvg && anyNonSvg == null) anyNonSvg = l
            if (isSvg && anySvg == null) anySvg = l
        }
        return (enSvg ?: anyNonSvg ?: anySvg)?.optString("file_path")?.takeIf { it.isNotBlank() }?.let { "$imgBase/w500$it" }
    }

    // ── Subtitle helpers ──
    private val langNames = mapOf(
        "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
        "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian", "ja" to "Japanese",
        "ko" to "Korean", "zh" to "Chinese", "ar" to "Arabic", "hi" to "Hindi",
        "bn" to "Bengali", "tr" to "Turkish", "nl" to "Dutch", "sv" to "Swedish",
        "no" to "Norwegian", "da" to "Danish", "fi" to "Finnish", "pl" to "Polish",
        "th" to "Thai", "vi" to "Vietnamese", "id" to "Indonesian", "uk" to "Ukrainian",
        "el" to "Greek", "bg" to "Bulgarian", "fa" to "Persian"
    )
    private val emittedSubUrls = mutableSetOf<String>()

    private fun emitSub(url: String, rawLang: String?, sc: (SubtitleFile) -> Unit) {
        if (url in emittedSubUrls) return; emittedSubUrls.add(url)
        val code = rawLang?.trim()?.lowercase()?.substringBefore("-")?.takeIf { it.length in 2..3 } ?: "unknown"
        sc(SubtitleFile(url, langNames[code] ?: code.uppercase()))
    }

    private fun emitSubsFromResult(result: JSONObject, sc: (SubtitleFile) -> Unit) {
        result.optJSONArray("subtitles")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.getJSONObject(i); s.optStringOrNull("url")?.let { emitSub(it, s.optStringOrNull("lang"), sc) }
            }
        }
    }

    // ── SEARCH (site API → quick, no TMDB overhead) ──
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val resp = app.get("$mainUrl/api/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}", timeout = 8_000).text
            val json = JSONObject(resp)
            val results = json.optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { i ->
                val item = results.getJSONObject(i)
                val id = item.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
                val mt = item.optStringOrNull("media_type") ?: "movie"
                val title = item.optStringOrNull("title") ?: item.optStringOrNull("name") ?: "?"
                val tvType = if (mt == "movie") TvType.Movie else TvType.TvSeries
                newMovieSearchResponse(title, "$mt/$id", tvType) {
                    this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                    item.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) }
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    // ── HOME PAGE ──
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data; val results = mutableListOf<SearchResponse>()
        when {
            data.startsWith("trending_") -> {
                val parts = data.removePrefix("trending_").split("_")
                val mt = parts[0]; val time = parts.getOrElse(1) { "day" }
                tmdbGet("trending/$mt/$time?page=$page")?.optJSONArray("results")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i); val id = item.optInt("id", 0).takeIf { it > 0 } ?: continue
                        val mt2 = item.optStringOrNull("media_type") ?: "movie"
                        val title = item.optStringOrNull("title") ?: item.optStringOrNull("name") ?: "?"
                        val tvType = if (mt2 == "movie") TvType.Movie else TvType.TvSeries
                        results.add(newMovieSearchResponse(title, "$mt2/$id", tvType) {
                            this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                            item.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) }
                        })
                    }
                }
            }
            data.endsWith("_top_rated") || data.endsWith("_popular") -> {
                val suffix = if (data.endsWith("_top_rated")) "_top_rated" else "_popular"
                val type = data.removeSuffix(suffix)
                val ep = if (suffix == "_top_rated") "top_rated" else "popular"
                tmdbGet("$type/$ep?page=$page")?.optJSONArray("results")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i); val id = item.optInt("id", 0).takeIf { it > 0 } ?: continue
                        val title = item.optStringOrNull("title") ?: item.optStringOrNull("name") ?: "?"
                        val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
                        results.add(newMovieSearchResponse(title, "$type/$id", tvType) {
                            this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                            item.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) }
                        })
                    }
                }
            }
            data.startsWith("movie_now_playing") -> tmdbGet("movie/now_playing?page=$page")?.optJSONArray("results")?.let { addResults(it, "movie", results) }
            data.startsWith("movie_upcoming") -> tmdbGet("movie/upcoming?page=$page")?.optJSONArray("results")?.let { addResults(it, "movie", results) }
            data.startsWith("tv_airing_today") -> tmdbGet("tv/airing_today?page=$page")?.optJSONArray("results")?.let { addResults(it, "tv", results) }
            data.startsWith("discover_") -> {
                val parts = data.removePrefix("discover_").split("_"); val type = parts[0]; val param = parts.getOrElse(1) { "" }
                val p = mutableListOf<String>(); param.toIntOrNull()?.let { p.add("with_genres=$it") }
                if (param.length == 2 && param !in listOf("tv", "mo")) p.add("with_original_language=$param")
                p.add("sort_by=popularity.desc"); p.add("page=$page")
                tmdbGet("discover/$type?${p.joinToString("&")}")?.optJSONArray("results")?.let { addResults(it, type, results) }
            }
        }
        return newHomePageResponse(request.name, results, results.isNotEmpty() && page < 10)
    }

    private fun addResults(arr: JSONArray, type: String, dest: MutableList<SearchResponse>) {
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i); val id = item.optInt("id", 0).takeIf { it > 0 } ?: continue
            val title = item.optStringOrNull("title") ?: item.optStringOrNull("name") ?: "?"
            val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
            dest.add(newMovieSearchResponse(title, "$type/$id", tvType) {
                this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                item.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) }
            })
        }
    }

    // ── LOAD ──
    override suspend fun load(url: String): LoadResponse? {
        val segments = url.trimEnd('/').split("/")
        if (segments.size < 2) return null
        val tmdbId = segments.last()
        val type = segments[segments.size - 2]
        return when (type) { "movie" -> loadMovie(tmdbId); "tv" -> loadTvSeries(tmdbId); else -> null }
    }

    // ── LOAD MOVIE ──
    private suspend fun loadMovie(tmdbId: String): LoadResponse? {
        val json = tmdbGet("movie/$tmdbId?append_to_response=credits,videos,recommendations,external_ids,images") ?: return null
        val title = json.optStringOrNull("title") ?: return null
        val poster = posterUrl(json.optStringOrNull("poster_path"))
        val backdrop = posterUrl(json.optStringOrNull("backdrop_path"), "w780")
        val plot = json.optStringOrNull("overview")
        val year = json.optStringOrNull("release_date")?.substringBefore("-")?.toIntOrNull()
        val rating = json.optDouble("vote_average", 0.0).takeIf { it > 0 }
        val imdbId = json.optJSONObject("external_ids")?.optStringSafe("imdb_id") ?: json.optStringSafe("imdb_id")
        val runtime = json.optInt("runtime", 0).takeIf { it > 0 }
        val logoUrl = json.optJSONObject("images")?.optJSONArray("logos")?.let { pickBestTmdbLogo(it, lang) }
        val genres = json.optJSONArray("genres")?.let { arr -> (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optStringOrNull("name") } }
        val actors = json.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            (0 until minOf(arr.length(), 15)).mapNotNull { i ->
                val p = arr.getJSONObject(i); val n = p.optStringOrNull("name") ?: return@mapNotNull null
                ActorData(Actor(n, posterUrl(p.optStringOrNull("profile_path"), "w185")), roleString = p.optStringOrNull("character"))
            }
        }
        var trailer: String? = null
        json.optJSONObject("videos")?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) { val v = arr.getJSONObject(i); if (v.optString("type") == "Trailer" && v.optString("site") == "YouTube") { trailer = "https://www.youtube.com/watch?v=${v.optString("key")}"; break } }
        }
        val recs = json.optJSONObject("recommendations")?.optJSONArray("results")?.let { arr ->
            (0 until minOf(arr.length(), 10)).mapNotNull { i -> val item = arr.getJSONObject(i); val id = item.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null; newMovieSearchResponse(item.optStringOrNull("title") ?: "?", "movie/$id", TvType.Movie) { this.posterUrl = posterUrl(item.optStringOrNull("poster_path")) } }
        }
        val data = "movie|$tmdbId|${java.net.URLEncoder.encode(title, "UTF-8")}|${year ?: ""}|${imdbId ?: ""}"
        return newMovieLoadResponse(title, "movie/$tmdbId", TvType.Movie, data) {
            this.posterUrl = poster; this.backgroundPosterUrl = backdrop
            this.plot = plot; this.year = year
            if (rating != null) this.score = Score.from10(rating)
            this.tags = genres; if (actors != null) this.actors = actors; if (recs != null) this.recommendations = recs
            if (trailer != null) addTrailer(trailer); if (imdbId != null) addImdbId(imdbId)
            if (runtime != null) this.duration = runtime
            if (logoUrl != null) this.logoUrl = logoUrl
        }
    }

    // ── LOAD TV SERIES ──
    private suspend fun loadTvSeries(tmdbId: String): LoadResponse? {
        val json = tmdbGet("tv/$tmdbId?append_to_response=external_ids,credits,recommendations,images") ?: return null
        val title = json.optStringOrNull("name") ?: return null
        val poster = posterUrl(json.optStringOrNull("poster_path"))
        val backdrop = posterUrl(json.optStringOrNull("backdrop_path"), "w780")
        val plot = json.optStringOrNull("overview")
        val year = json.optStringOrNull("first_air_date")?.substringBefore("-")?.toIntOrNull()
        val rating = json.optDouble("vote_average", 0.0).takeIf { it > 0 }
        val imdbId = json.optJSONObject("external_ids")?.optStringSafe("imdb_id") ?: json.optStringSafe("imdb_id")
        val seasons = json.optInt("number_of_seasons", 1)
        val logoUrl = json.optJSONObject("images")?.optJSONArray("logos")?.let { pickBestTmdbLogo(it, lang) }
        val genres = json.optJSONArray("genres")?.let { arr -> (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optStringOrNull("name") } }
        val actors = json.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            (0 until minOf(arr.length(), 15)).mapNotNull { i ->
                val p = arr.getJSONObject(i); val n = p.optStringOrNull("name") ?: return@mapNotNull null
                ActorData(Actor(n, posterUrl(p.optStringOrNull("profile_path"), "w185")), roleString = p.optStringOrNull("character"))
            }
        }
        val recs = json.optJSONObject("recommendations")?.optJSONArray("results")?.let { arr ->
            (0 until minOf(arr.length(), 10)).mapNotNull { i -> val item = arr.getJSONObject(i); val id = item.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null; newMovieSearchResponse(item.optStringOrNull("name") ?: "?", "tv/$id", TvType.TvSeries) { this.posterUrl = posterUrl(item.optStringOrNull("poster_path")) } }
        }

        val maxSeasons = minOf(seasons, 15)
        val episodes = mutableListOf<Episode>()
        val sznResults = coroutineScope { (1..maxSeasons).map { s -> async { val sj = tmdbGet("tv/$tmdbId/season/$s"); s to (sj?.optJSONArray("episodes")) } }.awaitAll() }
        for ((s, epArr) in sznResults) {
            if (epArr == null) continue
            for (j in 0 until epArr.length()) {
                val ep = epArr.getJSONObject(j)
                val epNum = ep.optInt("episode_number", j + 1)
                val epName = ep.optStringOrNull("name") ?: "Episode $epNum"
                val edata = "tv|$tmdbId|$s|$epNum|${java.net.URLEncoder.encode(title, "UTF-8")}|${year ?: ""}|${imdbId ?: ""}"
                episodes.add(newEpisode(edata) {
                    this.name = epName; this.season = s; this.episode = epNum
                    this.posterUrl = posterUrl(ep.optStringOrNull("still_path"), "w300")
                    this.description = ep.optStringOrNull("overview")
                })
            }
        }

        return newTvSeriesLoadResponse(title, "tv/$tmdbId", TvType.TvSeries, episodes) {
            this.posterUrl = poster; this.backgroundPosterUrl = backdrop
            this.plot = plot; this.year = year
            if (rating != null) this.score = Score.from10(rating)
            this.tags = genres; if (actors != null) this.actors = actors; if (recs != null) this.recommendations = recs
            if (imdbId != null) addImdbId(imdbId)
            if (logoUrl != null) this.logoUrl = logoUrl
        }
    }

    // ── LOAD LINKS (parallel aggregation — every source + every subtitle collected) ──
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        emittedSubUrls.clear()
        val parts = data.split("|")
        if (parts.size < 3) return false
        val type = parts[0]
        if (type !in listOf("movie", "tv")) return false

        val tmdbId = parts[1]; val title: String; val year: String; val imdbId: String; val seasonId: String; val episodeId: String
        if (type == "movie") {
            title = java.net.URLDecoder.decode(parts.getOrElse(2) { "" }, "UTF-8"); year = parts.getOrElse(3) { "" }; imdbId = parts.getOrElse(4) { "" }
            seasonId = "1"; episodeId = "1"
        } else {
            seasonId = parts.getOrElse(2) { "1" }; episodeId = parts.getOrElse(3) { "1" }
            title = java.net.URLDecoder.decode(parts.getOrElse(4) { "" }, "UTF-8"); year = parts.getOrElse(5) { "" }; imdbId = parts.getOrElse(6) { "" }
        }

        // Fire ALL source extractors in parallel — aggregates every quality from every server
        coroutineScope {
            // Embed sources (Cloudstream's built-in scrapers)
            val embedJobs = if (type == "movie") listOf(
                async { loadExtractor("https://vidsrc.to/embed/movie/$tmdbId", mainUrl, subtitleCallback, callback) },
                async { loadExtractor("https://vidlink.pro/movie/$tmdbId", mainUrl, subtitleCallback, callback) },
                async { loadExtractor("https://nites.is/embed/movie/$tmdbId", mainUrl, subtitleCallback, callback) },
                async { loadExtractor("https://www.2embed.cc/embed/movie/$tmdbId", mainUrl, subtitleCallback, callback) },
                async { loadExtractor("https://player.smashy.stream/movie/$tmdbId", mainUrl, subtitleCallback, callback) }
            ) else listOf(
                async { loadExtractor("https://vidsrc.to/embed/tv/$tmdbId/$seasonId/$episodeId", mainUrl, subtitleCallback, callback) },
                async { loadExtractor("https://vidlink.pro/tv/$tmdbId/$seasonId/$episodeId", mainUrl, subtitleCallback, callback) },
                async { loadExtractor("https://nites.is/embed/tv/$tmdbId/$seasonId/$episodeId", mainUrl, subtitleCallback, callback) },
                async { loadExtractor("https://www.2embed.cc/embed/tv/$tmdbId/$seasonId/$episodeId", mainUrl, subtitleCallback, callback) },
                async { loadExtractor("https://player.smashy.stream/tv/$tmdbId/$seasonId/$episodeId", mainUrl, subtitleCallback, callback) },
                async { loadExtractor("https://autoembed.co/tv/$tmdbId/$seasonId/$episodeId", mainUrl, subtitleCallback, callback) }
            )

            // WingsDatabase direct streams (8 servers → highest quality m3u8/mp4 + subtitles)
            val wingsJob = async { extractWingsDb(type, tmdbId, seasonId, episodeId, title, year, imdbId, callback, subtitleCallback) }

            // Wait for all sources to complete — aggregate everything
            embedJobs.forEach { try { it.await() } catch (_: Exception) {} }
            try { wingsJob.await() } catch (_: Exception) {}
        }
        return true
    }

    // ── WingsDatabase 8-server direct sources ──
    private suspend fun extractWingsDb(mediaType: String, tmdbId: String, seasonId: String, episodeId: String, title: String, year: String, imdbId: String, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        val servers = listOf("jett" to "Jett", "cdn" to "Yoru", "tejo" to "Tejo", "neon2" to "Neon", "ym" to "Sage", "downloader2" to "Cypher", "m4uhd" to "Breach", "hdmovie" to "Vyse")
        val seed: String
        try {
            seed = JSONObject(app.get("https://api.wingsdatabase.com/seed?mediaId=$tmdbId", timeout = 10_000,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14)", "Accept" to "application/json", "Referer" to "https://www.vidking.net/", "Origin" to "https://www.vidking.net")).text).optStringOrNull("seed") ?: return
        } catch (_: Exception) { return }
        val encTitle = java.net.URLEncoder.encode(java.net.URLEncoder.encode(title, "UTF-8"), "UTF-8")
        for ((key, label) in servers) {
            if (key == "cdn" && mediaType != "movie") continue
            try {
                val qs = listOf("title" to encTitle, "mediaType" to mediaType, "year" to year, "episodeId" to episodeId, "seasonId" to seasonId, "tmdbId" to tmdbId, "imdbId" to imdbId, "enc" to "2", "seed" to seed).joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
                val encData = app.get("https://api.wingsdatabase.com/${key}/sources-with-title?$qs", timeout = 15_000,
                    headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14)", "Accept" to "application/json, text/plain, */*", "Referer" to "https://www.vidking.net/", "Origin" to "https://www.vidking.net", "Cache-Control" to "no-cache")).text
                if (encData.isBlank() || encData.startsWith("{") || encData.length < 100) continue
                val body = JSONObject().apply { put("text", encData); put("id", tmdbId); put("seed", seed) }
                val resp = JSONObject(app.post("https://enc-dec.app/api/dec-videasy", requestBody = body.toString().toRequestBody("application/json".toMediaTypeOrNull()), timeout = 20_000, headers = mapOf("Content-Type" to "application/json")).text)
                if (resp.optInt("status", -1) != 200) continue
                val obj = resp.optJSONObject("result") ?: continue
                val sources = obj.optJSONArray("sources") ?: continue
                for (i in 0 until sources.length()) {
                    val src = sources.getJSONObject(i); val url = src.optStringOrNull("url") ?: continue
                    val quality = src.optStringOrNull("quality") ?: "Unknown"
                    callback.invoke(newExtractorLink("$name ($label)", "$label - $quality", url) { this.quality = getQualityFromName(quality) })
                }
                emitSubsFromResult(obj, subtitleCallback)
            } catch (_: Exception) { continue }  // fail silently, next server
        }
    }
}
