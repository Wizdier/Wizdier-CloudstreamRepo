package com.wizdier.cineby

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray

/**
 * Cineby.at provider — Movies & TV Shows via TMDB + VidKing streaming.
 *
 * Metadata: TMDB API (db.wingsdatabase.com/3)
 * Streaming: VidKing embed player (vidking.net/embed/...)
 */
class CinebyProvider : MainAPI() {
    override var mainUrl = "https://www.cineby.at"
    override var name = "Cineby"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true

    // --- TMDB API base (wingsdatabase mirrors TMDB) ---
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
        "movie_now_playing" to "Now Playing Movies",
        "movie_upcoming" to "Upcoming Movies",
        "tv_airing_today" to "Airing Today TV",
        "movie_popular" to "Popular Movies",
        "tv_popular" to "Popular TV Shows",
        "trending_all_day" to "Trending Today (All)",
        // Genre-based
        "discover_movie_28" to "Action Movies",
        "discover_movie_35" to "Comedy Movies",
        "discover_movie_27" to "Horror Movies",
        "discover_movie_10749" to "Romance Movies",
        "discover_movie_878" to "Sci-Fi Movies",
        "discover_movie_16" to "Animation Movies",
        "discover_tv_18" to "Drama TV",
        "discover_tv_10765" to "Sci-Fi & Fantasy TV",
        "discover_tv_10759" to "Action & Adventure TV",
        // Region-specific
        "discover_movie_ko" to "Korean Movies",
        "discover_movie_ja" to "Japanese Movies",
        "discover_movie_hi" to "Indian Movies"
    )

    // ─────────────────────────────────────────────
    //  Image helpers
    // ─────────────────────────────────────────────
    private fun posterUrl(path: String?, size: String = "w342"): String? {
        if (path.isNullOrBlank()) return null
        return "$imgBase/$size$path"
    }

    private fun backdropUrl(path: String?, size: String = "w780"): String? {
        if (path.isNullOrBlank()) return null
        return "$imgBase/$size$path"
    }

    // ─────────────────────────────────────────────
    //  JSON helpers
    // ─────────────────────────────────────────────
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key, "").takeIf { it.isNotBlank() && it != "null" }
    }

    // ─────────────────────────────────────────────
    //  TMDB API calls
    // ─────────────────────────────────────────────
    private suspend fun tmdbGet(endpoint: String): JSONObject? {
        return try {
            val url = "$tmdbBase/$endpoint?language=en-US"
            val text = app.get(url, timeout = 10_000).text
            JSONObject(text)
        } catch (e: Exception) {
            Log.e("Cineby", "TMDB GET $endpoint failed: ${e.message}")
            null
        }
    }

    private suspend fun searchTMDB(query: String, type: String, page: Int = 1): List<SearchResponse> {
        val endpoint = "search/$type?query=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page"
        val json = tmdbGet(endpoint) ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        val items = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val id = item.optInt("id", 0)
            if (id == 0) continue

            val title = when (type) {
                "movie" -> item.optStringOrNull("title") ?: "Unknown"
                "tv" -> item.optStringOrNull("name") ?: "Unknown"
                else -> "Unknown"
            }
            val poster = posterUrl(item.optStringOrNull("poster_path"))
            val year = item.optStringOrNull("release_date")?.substringBefore("-")
                ?: item.optStringOrNull("first_air_date")?.substringBefore("-")
            val rating = item.optDouble("vote_average", 0.0).takeIf { it > 0 }
            val overview = item.optStringOrNull("overview")
            val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries

            items.add(
                newMovieSearchResponse(title, "$type/$id", tvType) {
                    this.posterUrl = poster
                    this.posterHeader = poster
                    if (year != null) this.year = year.toIntOrNull()
                    if (rating != null) this.rating = (rating * 1000).toInt()
                }
            )
        }
        return items
    }

    // ─────────────────────────────────────────────
    //  Search
    // ─────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return coroutineScope {
            val moviesDeferred = async { searchTMDB(query, "movie") }
            val tvDeferred = async { searchTMDB(query, "tv") }
            moviesDeferred.await() + tvDeferred.await()
        }
    }

    // ─────────────────────────────────────────────
    //  Main Page (Browse)
    // ─────────────────────────────────────────────
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val data = request.data
        val results = mutableListOf<SearchResponse>()

        when {
            data.startsWith("trending_") -> {
                // trending_movie_day, trending_tv_day, trending_all_day
                val parts = data.removePrefix("trending_").split("_")
                val mediaType = parts[0] // movie, tv, all
                val time = parts.getOrElse(1) { "day" }
                val endpoint = "trending/$mediaType/$time?page=$page"
                val json = tmdbGet(endpoint)
                json?.optJSONArray("results")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val id = item.optInt("id", 0); if (id == 0) continue
                        val mediaTypeItem = item.optStringOrNull("media_type") ?: "movie"
                        val title = when (mediaTypeItem) {
                            "movie" -> item.optStringOrNull("title") ?: item.optStringOrNull("name") ?: "?"
                            else -> item.optStringOrNull("name") ?: item.optStringOrNull("title") ?: "?"
                        }
                        val tvType = if (mediaTypeItem == "movie") TvType.Movie else TvType.TvSeries
                        results.add(
                            newMovieSearchResponse(title, "$mediaTypeItem/$id", tvType) {
                                this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                                this.rating = (item.optDouble("vote_average", 0.0) * 1000).toInt().takeIf { it > 0 }
                            }
                        )
                    }
                }
            }
            data.endsWith("_top_rated") -> {
                val type = data.removeSuffix("_top_rated") // movie or tv
                val endpoint = "$type/top_rated?page=$page"
                val json = tmdbGet(endpoint)
                json?.optJSONArray("results")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val id = item.optInt("id", 0); if (id == 0) continue
                        val title = item.optStringOrNull("title") ?: item.optStringOrNull("name") ?: "?"
                        val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
                        results.add(
                            newMovieSearchResponse(title, "$type/$id", tvType) {
                                this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                                this.rating = (item.optDouble("vote_average", 0.0) * 1000).toInt().takeIf { it > 0 }
                            }
                        )
                    }
                }
            }
            data.endsWith("_popular") -> {
                val type = data.removeSuffix("_popular")
                val endpoint = "$type/popular?page=$page"
                val json = tmdbGet(endpoint)
                json?.optJSONArray("results")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val id = item.optInt("id", 0); if (id == 0) continue
                        val title = item.optStringOrNull("title") ?: item.optStringOrNull("name") ?: "?"
                        val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
                        results.add(
                            newMovieSearchResponse(title, "$type/$id", tvType) {
                                this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                                this.rating = (item.optDouble("vote_average", 0.0) * 1000).toInt().takeIf { it > 0 }
                            }
                        )
                    }
                }
            }
            data.startsWith("movie_now_playing") -> {
                val json = tmdbGet("movie/now_playing?page=$page")
                json?.optJSONArray("results")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val id = item.optInt("id", 0); if (id == 0) continue
                        results.add(
                            newMovieSearchResponse(
                                item.optStringOrNull("title") ?: "?",
                                "movie/$id",
                                TvType.Movie
                            ) {
                                this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                                this.rating = (item.optDouble("vote_average", 0.0) * 1000).toInt().takeIf { it > 0 }
                            }
                        )
                    }
                }
            }
            data.startsWith("movie_upcoming") -> {
                val json = tmdbGet("movie/upcoming?page=$page")
                json?.optJSONArray("results")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val id = item.optInt("id", 0); if (id == 0) continue
                        results.add(
                            newMovieSearchResponse(
                                item.optStringOrNull("title") ?: "?",
                                "movie/$id",
                                TvType.Movie
                            ) {
                                this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                                this.rating = (item.optDouble("vote_average", 0.0) * 1000).toInt().takeIf { it > 0 }
                            }
                        )
                    }
                }
            }
            data.startsWith("tv_airing_today") -> {
                val json = tmdbGet("tv/airing_today?page=$page")
                json?.optJSONArray("results")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val id = item.optInt("id", 0); if (id == 0) continue
                        results.add(
                            newMovieSearchResponse(
                                item.optStringOrNull("name") ?: "?",
                                "tv/$id",
                                TvType.TvSeries
                            ) {
                                this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                                this.rating = (item.optDouble("vote_average", 0.0) * 1000).toInt().takeIf { it > 0 }
                            }
                        )
                    }
                }
            }
            data.startsWith("discover_") -> {
                // discover_movie_28, discover_tv_18, discover_movie_ko, etc.
                val parts = data.removePrefix("discover_").split("_")
                val type = parts[0] // movie or tv
                val param = parts.getOrElse(1) { "" }

                val params = mutableListOf<String>()
                // If numeric, it's a genre ID
                param.toIntOrNull()?.let { params.add("with_genres=$it") }
                // If language code
                if (param.length == 2 && param !in listOf("tv", "mo")) {
                    params.add("with_original_language=$param")
                }
                params.add("sort_by=popularity.desc")
                params.add("page=$page")

                val endpoint = "discover/$type?${params.joinToString("&")}"
                val json = tmdbGet(endpoint)
                json?.optJSONArray("results")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val id = item.optInt("id", 0); if (id == 0) continue
                        val title = item.optStringOrNull("title") ?: item.optStringOrNull("name") ?: "?"
                        val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
                        results.add(
                            newMovieSearchResponse(title, "$type/$id", tvType) {
                                this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                                this.rating = (item.optDouble("vote_average", 0.0) * 1000).toInt().takeIf { it > 0 }
                            }
                        )
                    }
                }
            }
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = results),
            hasNext = results.isNotEmpty() && page < 10
        )
    }

    // ─────────────────────────────────────────────
    //  Load (Detail page)
    // ─────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        // url format: "movie/12345" or "tv/12345"
        val parts = url.split("/")
        if (parts.size < 2) return null
        val type = parts[0]
        val tmdbId = parts[1]

        when (type) {
            "movie" -> return loadMovie(tmdbId)
            "tv" -> return loadTvSeries(tmdbId)
        }
        return null
    }

    private suspend fun loadMovie(tmdbId: String): LoadResponse? {
        val json = tmdbGet("movie/$tmdbId") ?: return null
        val title = json.optStringOrNull("title") ?: return null

        val poster = posterUrl(json.optStringOrNull("poster_path"), "w500")
        val backdrop = backdropUrl(json.optStringOrNull("backdrop_path"))
        val plot = json.optStringOrNull("overview")
        val year = json.optStringOrNull("release_date")?.substringBefore("-")?.toIntOrNull()
        val rating = json.optDouble("vote_average", 0.0).takeIf { it > 0 }
        val imdbId = json.optStringOrNull("imdb_id")
        val runtime = json.optInt("runtime", 0)
        val tagline = json.optStringOrNull("tagline")

        // Genres
        val genres = json.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optStringOrNull("name") }
        }

        // Cast (from credits)
        val credits = tmdbGet("movie/$tmdbId/credits")
        val actors = credits?.optJSONArray("cast")?.let { arr ->
            val list = mutableListOf<ActorData>()
            val limit = minOf(arr.length(), 15)
            for (i in 0 until limit) {
                val person = arr.getJSONObject(i)
                list.add(
                    ActorData(
                        actor = Actor(
                            name = person.optStringOrNull("name") ?: "?",
                            image = posterUrl(person.optStringOrNull("profile_path"), "w185")
                        ),
                        role = person.optStringOrNull("character")
                    )
                )
            }
            list
        }

        // Trailers
        val videos = tmdbGet("movie/$tmdbId/videos")
        val trailer = videos?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                if (v.optStringOrNull("type") == "Trailer" &&
                    v.optStringOrNull("site") == "YouTube") {
                    return@let "https://www.youtube.com/watch?v=${v.optStringOrNull("key")}"
                }
            }
            null
        }

        // Recommendations
        val recs = tmdbGet("movie/$tmdbId/recommendations")
        val recommendations = recs?.optJSONArray("results")?.let { arr ->
            val list = mutableListOf<SearchResponse>()
            val limit = minOf(arr.length(), 10)
            for (i in 0 until limit) {
                val item = arr.getJSONObject(i)
                val id = item.optInt("id", 0); if (id == 0) continue
                list.add(
                    newMovieSearchResponse(
                        item.optStringOrNull("title") ?: "?",
                        "movie/$id",
                        TvType.Movie
                    ) {
                        this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                        this.rating = (item.optDouble("vote_average", 0.0) * 1000).toInt().takeIf { it > 0 }
                    }
                )
            }
            list
        }

        // Data for loadLinks: "movie_extract|tmdbId|title|year|imdbId"
        val embedData = "movie_extract|$tmdbId|${java.net.URLEncoder.encode(title, "UTF-8")}|${year ?: ""}|${imdbId ?: ""}"

        return newMovieLoadResponse(title, embedData, TvType.Movie, embedData) {
            this.posterUrl = poster
            this.posterHeader = poster
            this.backdropUrl = backdrop
            this.plot = plot
            this.year = year
            if (rating != null) this.rating = (rating * 1000).toInt()
            this.tags = genres
            if (actors != null) this.actors = actors
            if (trailer != null) addTrailer(trailer)
            if (recommendations != null) this.recommendations = recommendations
            if (imdbId != null) addImdbId(imdbId)
            this.duration = runtime
            this.tagline = tagline
        }
    }

    private suspend fun loadTvSeries(tmdbId: String): LoadResponse? {
        val json = tmdbGet("tv/$tmdbId") ?: return null
        val title = json.optStringOrNull("name") ?: return null

        val poster = posterUrl(json.optStringOrNull("poster_path"), "w500")
        val backdrop = backdropUrl(json.optStringOrNull("backdrop_path"))
        val plot = json.optStringOrNull("overview")
        val year = json.optStringOrNull("first_air_date")?.substringBefore("-")?.toIntOrNull()
        val rating = json.optDouble("vote_average", 0.0).takeIf { it > 0 }
        val imdbId = json.optStringOrNull("imdb_id") ?: (
            json.optJSONObject("external_ids")?.optStringOrNull("imdb_id")
        )
        val seasons = json.optInt("number_of_seasons", 0)
        val tagline = json.optStringOrNull("tagline")

        // Genres
        val genres = json.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optStringOrNull("name") }
        }

        // Fetch external IDs
        val externalIds = tmdbGet("tv/$tmdbId/external_ids")
        val extImdbId = externalIds?.optStringOrNull("imdb_id")

        // Cast
        val credits = tmdbGet("tv/$tmdbId/credits")
        val actors = credits?.optJSONArray("cast")?.let { arr ->
            val list = mutableListOf<ActorData>()
            val limit = minOf(arr.length(), 15)
            for (i in 0 until limit) {
                val person = arr.getJSONObject(i)
                list.add(
                    ActorData(
                        actor = Actor(
                            name = person.optStringOrNull("name") ?: "?",
                            image = posterUrl(person.optStringOrNull("profile_path"), "w185")
                        ),
                        role = person.optStringOrNull("character")
                    )
                )
            }
            list
        }

        // Recommendations
        val recs = tmdbGet("tv/$tmdbId/recommendations")
        val recommendations = recs?.optJSONArray("results")?.let { arr ->
            val list = mutableListOf<SearchResponse>()
            val limit = minOf(arr.length(), 10)
            for (i in 0 until limit) {
                val item = arr.getJSONObject(i)
                val id = item.optInt("id", 0); if (id == 0) continue
                list.add(
                    newMovieSearchResponse(
                        item.optStringOrNull("name") ?: "?",
                        "tv/$id",
                        TvType.TvSeries
                    ) {
                        this.posterUrl = posterUrl(item.optStringOrNull("poster_path"))
                        this.rating = (item.optDouble("vote_average", 0.0) * 1000).toInt().takeIf { it > 0 }
                    }
                )
            }
            list
        }

        // Build episodes list (first season initially, rest loaded on demand)
        val episodes = mutableListOf<Episode>()

        for (seasonNum in 1..seasons) {
            val seasonJson = tmdbGet("tv/$tmdbId/season/$seasonNum")
            val seasonEpisodes = seasonJson?.optJSONArray("episodes") ?: continue

            for (j in 0 until seasonEpisodes.length()) {
                val ep = seasonEpisodes.getJSONObject(j)
                val epNum = ep.optInt("episode_number", j + 1)
                val epName = ep.optStringOrNull("name") ?: "Episode $epNum"
                val epStill = posterUrl(ep.optStringOrNull("still_path"), "w300")
                val epOverview = ep.optStringOrNull("overview")
                val epRating = ep.optDouble("vote_average", 0.0).takeIf { it > 0 }

                // Data format: "tv_extract|tmdbId|season|episode|title|year|imdbId"
                val data = "tv_extract|$tmdbId|$seasonNum|$epNum|${java.net.URLEncoder.encode(title, "UTF-8")}|${year ?: ""}|${extImdbId ?: imdbId ?: ""}"

                episodes.add(
                    newEpisode(data) {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = epStill
                        this.description = epOverview
                        if (epRating != null) this.rating = (epRating * 1000).toInt()
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, "tv/$tmdbId", TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.posterHeader = poster
            this.backdropUrl = backdrop
            this.plot = plot
            this.year = year
            if (rating != null) this.rating = (rating * 1000).toInt()
            this.tags = genres
            if (actors != null) this.actors = actors
            if (recommendations != null) this.recommendations = recommendations
            val finalImdb = extImdbId ?: imdbId
            if (finalImdb != null) addImdbId(finalImdb)
            this.tagline = tagline
        }
    }

    // ─────────────────────────────────────────────
    //  Load Links (Video extraction)
    // ─────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data format:
        //   "movie_extract|tmdbId|title|year|imdbId"
        //   "tv_extract|tmdbId|season|episode|title|year|imdbId"
        val parts = data.split("|")
        if (parts.size < 3) return false

        val extractType = parts[0]

        try {
            val tmdbId = parts[1]
            val (mediaType, seasonId, episodeId, title, year, imdbId) = when (extractType) {
                "movie_extract" -> {
                    val t = java.net.URLDecoder.decode(parts.getOrElse(2) { "" }, "UTF-8")
                    val y = parts.getOrElse(3) { "" }
                    val imdb = parts.getOrElse(4) { "" }
                    listOf("movie", "1", "1", t, y, imdb)
                }
                "tv_extract" -> {
                    val s = parts.getOrElse(2) { "1" }
                    val e = parts.getOrElse(3) { "1" }
                    val t = java.net.URLDecoder.decode(parts.getOrElse(4) { "" }, "UTF-8")
                    val y = parts.getOrElse(5) { "" }
                    val imdb = parts.getOrElse(6) { "" }
                    listOf("tv", s, e, t, y, imdb)
                }
                else -> return false
            }

            // Extract sources from WingsDatabase via enc-dec.app decryption
            extractStreamingSources(
                mediaType, tmdbId, seasonId, episodeId, title, year, imdbId,
                callback, subtitleCallback
            )

            return true
        } catch (e: Exception) {
            Log.e("Cineby", "loadLinks error: ${e.message}")
            return false
        }
    }

    /**
     * Extract streaming sources from the WingsDatabase API using enc-dec.app decryption.
     *
     * Flow:
     * 1. Get seed from api.wingsdatabase.com/seed
     * 2. Fetch encrypted sources from WingsDatabase CDN servers
     * 3. Decrypt via enc-dec.app/api/dec-videasy
     * 4. Extract m3u8/mp4 URLs and subtitles
     */
    private suspend fun extractStreamingSources(
        mediaType: String,
        tmdbId: String,
        seasonId: String,
        episodeId: String,
        title: String,
        year: String,
        imdbId: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        // All English-language WingsDatabase servers
        // (excludes German/Hindi/Spanish/Portuguese-only servers)
        val servers = listOf(
            "jett" to "jett/sources-with-title",          // Jett
            "cdn" to "cdn/sources-with-title",             // Yoru / Hydrogen (movies, may have 4K)
            "tejo" to "tejo/sources-with-title",           // Tejo / Titanium
            "neon2" to "neon2/sources-with-title",         // Neon / Oxygen
            "ym" to "ym/sources-with-title",               // Sage
            "downloader2" to "downloader2/sources-with-title", // Cypher / Lithium
            "m4uhd" to "m4uhd/sources-with-title",         // Breach / Helium
            "hdmovie" to "hdmovie/sources-with-title"      // Vyse (filters to English)
        )

        // ── Step 1: Get seed ──
        val seed: String = try {
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
            JSONObject(seedResp).optStringOrNull("seed") ?: return
        } catch (e: Exception) {
            Log.e("Cineby", "Seed fetch failed: ${e.message}")
            return
        }

        // Double-encode title for WingsDatabase (matching videasy.py sample)
        val encTitle = java.net.URLEncoder.encode(
            java.net.URLEncoder.encode(title, "UTF-8"), "UTF-8"
        )

        for ((serverKey, endpoint) in servers) {
            try {
                // Yoru (cdn) is movies-only — skip for TV series
                if (serverKey == "cdn" && mediaType != "movie") {
                    Log.d("Cineby", "Skipping Yoru — movies only")
                    continue
                }
                // ── Step 2: Fetch encrypted sources ──
                val params = listOf(
                    "title" to encTitle,
                    "mediaType" to mediaType,
                    "year" to year,
                    "episodeId" to episodeId,
                    "seasonId" to seasonId,
                    "tmdbId" to tmdbId,
                    "imdbId" to imdbId,
                    "enc" to "2",
                    "seed" to seed
                )
                val queryString = params.joinToString("&") { (k, v) ->
                    "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
                }
                val apiUrl = "https://api.wingsdatabase.com/$endpoint?$queryString"

                val encData = app.get(
                    apiUrl,
                    timeout = 15_000,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
                        "Accept" to "application/json, text/plain, */*",
                        "Referer" to "$vidKingBase/",
                        "Origin" to vidKingBase,
                        "Cache-Control" to "no-cache"
                    )
                ).text

                if (encData.isBlank() || encData.startsWith("{") || encData.length < 100) {
                    Log.w("Cineby", "$serverKey: empty/bad response (len=${encData.length})")
                    continue
                }

                // ── Step 3: Decrypt via enc-dec.app ──
                val decryptBody = JSONObject().apply {
                    put("text", encData)
                    put("id", tmdbId)
                    put("seed", seed)
                }

                val decryptResp = app.post(
                    "https://enc-dec.app/api/dec-videasy",
                    requestBody = decryptBody.toString().toRequestBody(
                        "application/json".toMediaTypeOrNull()
                    ),
                    timeout = 20_000,
                    headers = mapOf("Content-Type" to "application/json")
                ).text

                val result = JSONObject(decryptResp)
                if (result.optInt("status", -1) != 200) {
                    Log.w("Cineby", "$serverKey: decrypt status=${result.optInt("status")}")
                    continue
                }

                val data = result.optJSONObject("result") ?: continue

                // ── Step 4: Extract sources & subtitles ──
                val sources = data.optJSONArray("sources") ?: continue
                var foundAny = false

                for (i in 0 until sources.length()) {
                    val src = sources.getJSONObject(i)
                    val url = src.optStringOrNull("url") ?: continue
                    val quality = src.optStringOrNull("quality") ?: "Unknown"
                    val isM3u8 = url.contains(".m3u8", ignoreCase = true) ||
                                 url.contains(".m3u", ignoreCase = true)

                    val qualityEnum = getQualityFromName(quality)
                    val serverLabel = when (serverKey) {
                        "jett" -> "Jett"
                        "cdn" -> "Yoru"
                        "tejo" -> "Tejo"
                        "neon2" -> "Neon"
                        "ym" -> "Sage"
                        "downloader2" -> "Cypher"
                        "m4uhd" -> "Breach"
                        "hdmovie" -> "Vyse"
                        else -> serverKey
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = "$name ($serverLabel)",
                            name = "$serverLabel - $quality",
                            url = url,
                            referer = "$vidKingBase/",
                            quality = qualityEnum,
                            isM3u8 = isM3u8
                        )
                    )
                    foundAny = true
                }

                // Extract subtitles
                val subtitles = data.optJSONArray("subtitles")
                if (subtitles != null) {
                    for (i in 0 until subtitles.length()) {
                        val sub = subtitles.getJSONObject(i)
                        val subUrl = sub.optStringOrNull("url") ?: continue
                        val lang = sub.optStringOrNull("lang") ?: "unknown"

                        subtitleCallback.invoke(
                            SubtitleFile(
                                url = subUrl,
                                lang = lang
                            )
                        )
                    }
                }

                if (foundAny) {
                    Log.d("Cineby", "$serverKey: extracted ${sources.length()} sources + ${subtitles?.length() ?: 0} subs")
                    // Continue to next server — aggregate all sources
                }

            } catch (e: Exception) {
                Log.w("Cineby", "$serverKey error: ${e.message}")
                continue
            }
        }

        Log.w("Cineby", "All servers processed — returning aggregated sources")
    }
}
