package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

// ============================
// Metadata container helpers
// ============================
data class CleanedTitle(
    val baseTitle: String,
    val seasonNumber: Int?,
    val audioTags: List<String>,
    val qualityTags: List<String>
)

data class ConsolidatedEntry(
    val id: String,
    val baseTitle: String,
    val displayTitle: String,
    val poster: String?,
    val tvType: TvType,
    val postIds: List<Int>,
    val seasons: Map<Int, List<Int>>
)

// ============================
// Provider
// ============================
class CircleFtpProvider : MainAPI() {
    override var mainUrl = "http://new.circleftp.net"
    private var mainApiUrl = "http://new.circleftp.net:5000"
    private val apiUrl = "http://15.1.1.50:5000"
    override var name = "Circle FTP"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie,
        TvType.Cartoon, TvType.AsianDrama, TvType.Documentary, TvType.OVA, TvType.Others
    )

    private val categoryTypeMap = mapOf(
        "80" to null,
        "6" to TvType.Movie,
        "9" to TvType.TvSeries,
        "22" to TvType.TvSeries,
        "2" to TvType.Movie,
        "5" to TvType.TvSeries,
        "238" to TvType.TvSeries,
        "7" to TvType.Movie,
        "8" to TvType.Movie,
        "3" to TvType.Movie,
        "4" to TvType.Movie,
        "1" to TvType.Cartoon,
        "21" to TvType.Anime,
        "85" to TvType.Documentary,
        "15" to TvType.Others
    )

    override val mainPage = mainPageOf(
        "80" to "Featured",
        "6" to "English Movies",
        "9" to "English & Foreign TV Series",
        "22" to "Dubbed TV Series",
        "2" to "Hindi Movies",
        "5" to "Hindi TV Series",
        "238" to "Indian TV Show",
        "7" to "English & Foreign Hindi Dubbed Movies",
        "8" to "Foreign Language Movies",
        "3" to "South Indian Dubbed Movies",
        "4" to "South Indian Movies",
        "1" to "Animation Movies",
        "21" to "Anime Series",
        "85" to "Documentary",
        "15" to "WWE"
    )

    private val animeSeasonCache = mutableMapOf<String, Map<Int, List<Int>>>()

    private val tmdbApiKey = "YOUR_TMDB_API_KEY"   // Replace with actual key
    private val tmdbImageBase = "https://image.tmdb.org/t/p/"
    private val anilistApiUrl = "https://graphql.anilist.co"
    private val anizipApiUrl = "https://api.ani.zip/mappings"

    // ==============================
    // Main Page & Search
    // ==============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val posts = fetchPosts("categoryExact=${request.data}&page=$page&order=desc&limit=10")
        val tvTypeHint = categoryTypeMap[request.data]
        val results = consolidateAndPrepare(posts, tvTypeHint)
        return newHomePageResponse(request.name, results, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val posts = fetchPosts("searchTerm=${URLEncoder.encode(query, "utf-8")}&order=desc")
        return consolidateAndPrepare(posts, null)
    }

    private suspend fun fetchPosts(params: String): List<Post> {
        val json = try {
            app.get("$mainApiUrl/api/posts?$params", verify = false, cacheTime = 60)
        } catch (_: Exception) {
            app.get("$apiUrl/api/posts?$params", verify = false, cacheTime = 60)
        }
        return AppUtils.parseJson<PageData>(json.text).posts
    }

    private suspend fun consolidateAndPrepare(
        posts: List<Post>,
        tvTypeHint: TvType?
    ): List<SearchResponse> = coroutineScope {
        val cleanedMap = linkedMapOf<String, MutableList<Post>>()
        for (post in posts) {
            val cleaned = cleanTitle(post.title)
            val key = normalizeKey(cleaned.baseTitle)
            cleanedMap.getOrPut(key) { mutableListOf() }.add(post)
        }

        val entries = cleanedMap.mapNotNull { (key, group) ->
            val first = group.first()
            val cleaned = cleanTitle(first.title)
            val baseTitle = cleaned.baseTitle

            var tvType = tvTypeHint
            if (tvType == null) {
                tvType = inferTypeFromPosts(group, cleaned)
            }

            val seasons = mutableMapOf<Int, MutableList<Int>>()
            val singleVideoIds = mutableListOf<Int>()
            for (post in group) {
                val c = cleanTitle(post.title)
                if (post.type == "series" && tvType == TvType.Anime && c.seasonNumber != null) {
                    seasons.getOrPut(c.seasonNumber) { mutableListOf() }.add(post.id)
                } else {
                    singleVideoIds.add(post.id)
                }
            }

            val baselinePostIds = if (tvType == TvType.Anime && seasons.size > 1) {
                val s1Ids = seasons[1] ?: singleVideoIds.ifEmpty {
                    seasons.minByOrNull { it.key }?.value ?: group.map { it.id }
                }
                animeSeasonCache[key] = seasons.toMap()
                s1Ids
            } else {
                group.map { it.id }
            }

            val poster = "$mainApiUrl/uploads/${first.imageSm}"
            ConsolidatedEntry(
                id = "group:$key",
                baseTitle = baseTitle,
                displayTitle = baseTitle,
                poster = poster,
                tvType = tvType ?: TvType.Movie,
                postIds = baselinePostIds,
                seasons = seasons.toMap()
            )
        }

        entries.map { entry ->
            async {
                var finalType = entry.tvType
                if (finalType == TvType.Cartoon) {
                    val isAnimeMovie = quickAniListSearch(entry.baseTitle, "MOVIE") == true
                    if (isAnimeMovie) finalType = TvType.AnimeMovie
                }
                toSearchResponse(entry, finalType)
            }
        }.awaitAll().filterNotNull()
    }

    private fun toSearchResponse(entry: ConsolidatedEntry, tvType: TvType): SearchResponse? {
        val url = if (entry.postIds.size == 1) {
            "$mainUrl/content/${entry.postIds.first()}"
        } else {
            "circleftp://load?key=${URLEncoder.encode(entry.id, "utf-8")}"
        }
        return newAnimeSearchResponse(entry.displayTitle, url, tvType) {
            this.posterUrl = entry.poster
            this.quality = getSearchQuality(entry.displayTitle)
            addDubStatus(
                dubExist = entry.displayTitle.lowercase().let { check ->
                    "dubbed" in check || "dual audio" in check || "multi audio" in check
                },
                subExist = false
            )
        }
    }

    // ==============================
    // LOAD - core logic (with pre-fetched metadata)
    // ==============================
    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val postIds: List<Int>
        val seasonOverride: Int?
        val cacheKey: String?

        if (url.startsWith("circleftp://load")) {
            val uri = java.net.URI(url)
            val query = uri.query.split("&").associate { it.split("=")[0] to it.split("=")[1] }
            cacheKey = query["key"]
            seasonOverride = query["season"]?.toIntOrNull()
            postIds = animeSeasonCache[cacheKey]?.get(seasonOverride ?: 1)
                ?: throw Exception("No posts found for key $cacheKey season $seasonOverride")
        } else {
            val idString = url.substringAfterLast("/")
            cacheKey = null
            seasonOverride = null
            postIds = listOf(idString.toInt())
        }

        val postJsons = postIds.map { id ->
            async {
                try {
                    app.get("$mainApiUrl/api/posts/$id", verify = false, cacheTime = 60)
                } catch (_: Exception) {
                    app.get("$apiUrl/api/posts/$id", verify = false, cacheTime = 60)
                }
            }
        }.awaitAll()

        val loadDataList = postJsons.map { json ->
            val loadData = AppUtils.parseJson<Data>(json.text)
            Triple(loadData, json.url.contains(mainApiUrl), cleanTitle(loadData.title))
        }

        val first = loadDataList.first().first
        val mainClean = loadDataList.first().third
        val baseTitle = mainClean.baseTitle
        val tvType = if (first.type == "singleVideo") TvType.Movie else inferTypeFromPostIds(postIds, mainClean)

        // Pre-fetch metadata (season-aware for anime)
        var finalPlot = first.metaData
        var finalYear = selectUntilNonInt(first.year)
        var finalPoster: String? = "$apiUrl/uploads/${first.image}"
        var finalLogo: String? = null
        var finalBackground: String? = null
        var finalTrailer: String? = null
        var aniListId: Int? = null
        var malId: Int? = null
        var kitsuId: Int? = null
        var simklId: Int? = null

        if (tvType == TvType.Anime || tvType == TvType.AnimeMovie) {
            val seasonMeta = if (seasonOverride != null) {
                fetchAnimeSeasonSpecificMetadata(baseTitle, seasonOverride)
            } else {
                fetchAniListMetadata(baseTitle, finalYear)
            }
            if (seasonMeta != null) {
                finalPlot = seasonMeta.plot ?: finalPlot
                finalYear = seasonMeta.year ?: finalYear
                finalPoster = seasonMeta.posterUrl ?: finalPoster
                finalLogo = seasonMeta.logoUrl
                finalBackground = seasonMeta.backgroundPosterUrl
                aniListId = seasonMeta.aniListId
                malId = seasonMeta.malId
                kitsuId = seasonMeta.kitsuId
                simklId = seasonMeta.simklId
            }
            val trailerQuery = if (seasonOverride != null) "$baseTitle Season $seasonOverride" else baseTitle
            finalTrailer = fetchAniListTrailer(trailerQuery, finalYear)
        } else {
            val year = finalYear
            val tmdbMeta = fetchTMDBMetadata(baseTitle, year, tvType)
            if (tmdbMeta != null) {
                finalPlot = tmdbMeta.plot ?: finalPlot
                finalYear = tmdbMeta.year ?: finalYear
                finalPoster = tmdbMeta.posterUrl ?: finalPoster
                finalLogo = tmdbMeta.logoUrl
                finalBackground = tmdbMeta.backgroundPosterUrl
            }
            finalTrailer = fetchTMDBTrailer(baseTitle, year, tvType)
        }

        // Build movie response
        if (first.type == "singleVideo") {
            val movieUrls = loadDataList.map { (data, useMain, _) ->
                val movieJson = postJsons[loadDataList.indexOf(Triple(data, useMain, cleanTitle(data.title)))]
                val movieUrl = AppUtils.parseJson<Movies>(movieJson.text).content
                if (useMain) movieUrl else linkToIp(movieUrl)
            }
            val duration = getDurationFromString(first.watchTime)
            val movieResponse = newMovieLoadResponse(baseTitle, url, tvType, movieUrls.first()) {
                this.posterUrl = finalPoster
                this.year = finalYear
                this.plot = finalPlot
                this.duration = duration
                this.logoUrl = finalLogo
                this.backgroundPosterUrl = finalBackground
            }
            movieResponse.trailerUrl = finalTrailer
            return@coroutineScope movieResponse
        }

        // Build TV series response
        val allEpisodes = mutableListOf<Episode>()
        loadDataList.forEachIndexed { index, (data, useMain, cleaned) ->
            val tvData = AppUtils.parseJson<TvSeries>(postJsons[index].text)
            var seasonNum = 0
            tvData.content.forEach { season ->
                seasonNum++
                var episodeNum = 0
                season.episodes.forEach { ep ->
                    episodeNum++
                    val epUrl = ep.link
                    val link = if (useMain) epUrl else linkToIp(epUrl)
                    val sourceTag = if (cleaned.audioTags.isNotEmpty()) {
                        "FTP [${cleaned.audioTags.joinToString(", ")}]"
                    } else "FTP"
                    allEpisodes.add(
                        newEpisode(link) {
                            this.episode = episodeNum
                            this.season = seasonNum
                            this.name = sourceTag
                        }
                    )
                }
            }
        }

        val seriesResponse = newTvSeriesLoadResponse(baseTitle, url, tvType, allEpisodes) {
            this.posterUrl = finalPoster
            this.year = finalYear
            this.plot = finalPlot
            this.logoUrl = finalLogo
            this.backgroundPosterUrl = finalBackground
        }
        seriesResponse.trailerUrl = finalTrailer
        // Attach tracking IDs
        aniListId?.let { seriesResponse.addAniListId(it) }
        malId?.let { seriesResponse.addMalId(it) }
        kitsuId?.let { seriesResponse.addKitsuId(it) }
        simklId?.let { seriesResponse.addSimklId(it) }

        if (tvType == TvType.Anime && cacheKey != null) {
            val seasons = animeSeasonCache[cacheKey] ?: emptyMap()
            val currentSeason = seasonOverride ?: 1
            val recommendations = seasons.filterKeys { it != currentSeason }.map { (s, _) ->
                val recUrl = "circleftp://load?key=${URLEncoder.encode(cacheKey, "utf-8")}&season=$s"
                newAnimeSearchResponse("$baseTitle Season $s", recUrl, TvType.Anime) {
                    this.posterUrl = finalPoster // placeholder, updated when loaded
                }
            }
            seriesResponse.recommendations = recommendations
        }

        seriesResponse
    }

    // ==============================
    // TMDB helpers
    // ==============================
    private suspend fun fetchTMDBMetadata(
        query: String,
        year: Int?,
        tvType: TvType
    ): TmdbMetadata? {
        val type = if (tvType == TvType.Movie || tvType == TvType.Cartoon || tvType == TvType.AnimeMovie) "movie" else "tv"
        var searchUrl = "https://api.themoviedb.org/3/search/$type?api_key=$tmdbApiKey&query=${URLEncoder.encode(query, "utf-8")}"
        if (year != null) searchUrl += "&year=$year"
        try {
            val response = app.get(searchUrl, verify = false, cacheTime = 600)
            val results = JSONObject(response.text).getJSONArray("results")
            if (results.length() == 0) return null
            var best = results.getJSONObject(0)
            if (year != null) {
                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val itemYear = item.optString("first_air_date", item.optString("release_date", ""))
                        .substringBefore("-").toIntOrNull()
                    val bestYear = best.optString("first_air_date", best.optString("release_date", ""))
                        .substringBefore("-").toIntOrNull() ?: 9999
                    if (itemYear != null && abs(itemYear - year) < abs(bestYear - year)) {
                        best = item
                    }
                }
            }
            val tmdbId = best.getInt("id")
            val detailsUrl = "https://api.themoviedb.org/3/$type/$tmdbId?api_key=$tmdbApiKey&append_to_response=images"
            val detailResponse = app.get(detailsUrl, verify = false, cacheTime = 600)
            val detail = JSONObject(detailResponse.text)
            val logoPath = detail.getJSONObject("images").optJSONArray("logos")?.optJSONObject(0)?.optString("file_path")
            val backdrop = detail.optString("backdrop_path")
            return TmdbMetadata(
                plot = detail.optString("overview").ifBlank { null },
                year = (detail.optString("first_air_date") ?: detail.optString("release_date"))
                    .substringBefore("-").toIntOrNull(),
                posterUrl = detail.optString("poster_path")?.let { tmdbImageBase + "w500" + it },
                logoUrl = logoPath?.let { tmdbImageBase + "w500" + it },
                backgroundPosterUrl = backdrop?.let { tmdbImageBase + "w1280" + it }
            )
        } catch (_: Exception) { return null }
    }

    private suspend fun fetchTMDBTrailer(
        query: String,
        year: Int?,
        tvType: TvType
    ): String? {
        val type = if (tvType == TvType.Movie || tvType == TvType.Cartoon || tvType == TvType.AnimeMovie) "movie" else "tv"
        var searchUrl = "https://api.themoviedb.org/3/search/$type?api_key=$tmdbApiKey&query=${URLEncoder.encode(query, "utf-8")}"
        if (year != null) searchUrl += "&year=$year"
        try {
            val searchResponse = app.get(searchUrl, verify = false, cacheTime = 600)
            val results = JSONObject(searchResponse.text).getJSONArray("results")
            if (results.length() == 0) return null
            val best = results.getJSONObject(0)
            val tmdbId = best.getInt("id")
            val videosUrl = "https://api.themoviedb.org/3/$type/$tmdbId/videos?api_key=$tmdbApiKey"
            val videoResponse = app.get(videosUrl, verify = false, cacheTime = 600)
            val videos = JSONObject(videoResponse.text).getJSONArray("results")
            for (i in 0 until videos.length()) {
                val v = videos.getJSONObject(i)
                if (v.optString("site").equals("YouTube", true) && v.optString("type").equals("Trailer", true)) {
                    val key = v.optString("key")
                    if (key.isNotEmpty()) return "https://www.youtube.com/watch?v=$key"
                }
            }
        } catch (_: Exception) { }
        return null
    }

    data class TmdbMetadata(
        val plot: String?,
        val year: Int?,
        val posterUrl: String?,
        val logoUrl: String?,
        val backgroundPosterUrl: String?
    )

    // ==============================
    // AniList metadata with season‑specific resolution
    // ==============================
    private suspend fun fetchAniListMetadata(query: String, year: Int?): AniListMetadata? {
        val graphql = """
            query (${"$"}query: String) {
                Page(perPage: 3) {
                    media(search: ${"$"}query, type: ANIME) {
                        id
                        idMal
                        title { romaji english }
                        description
                        startDate { year }
                        season
                        seasonYear
                        coverImage { large }
                        bannerImage
                    }
                }
            }
        """.trimIndent()
        val body = JSONObject().apply {
            put("query", graphql)
            put("variables", JSONObject().apply { put("query", query) })
        }
        try {
            val response = app.post(
                url = anilistApiUrl,
                headers = mapOf("Content-Type" to "application/json"),
                requestBody = body.toString(),
                verify = false,
                cacheTime = 600
            )
            val json = JSONObject(response.text)
            val mediaArray = json.getJSONObject("data")?.getJSONObject("Page")?.getJSONArray("media") ?: return null
            if (mediaArray.length() == 0) return null
            var best = mediaArray.getJSONObject(0)
            if (year != null) {
                for (i in 0 until mediaArray.length()) {
                    val m = mediaArray.getJSONObject(i)
                    val mYear = m.optJSONObject("startDate")?.optInt("year") ?: 0
                    val bestYear = best.optJSONObject("startDate")?.optInt("year") ?: 9999
                    if (abs(mYear - year) < abs(bestYear - year)) best = m
                }
            }
            val id = best.getInt("id")
            val malId = best.optInt("idMal", -1).takeIf { it > 0 }
            val title = best.getJSONObject("title").optString("romaji")
                ?: best.getJSONObject("title").optString("english")
            val plot = best.optString("description")?.replace(Regex("<[^>]+>"), "")
            val yearAni = best.optJSONObject("startDate")?.optInt("year")
            val poster = best.optJSONObject("coverImage")?.optString("large")
            val banner = best.optString("bannerImage")

            val anizipMeta = fetchAniZip(id)
            return AniListMetadata(
                plot = plot?.ifBlank { null },
                year = yearAni?.takeIf { it > 0 },
                posterUrl = poster,
                logoUrl = fetchTMDBLogo(title, yearAni),
                backgroundPosterUrl = banner,
                aniListId = id,
                malId = malId ?: anizipMeta?.malId,
                kitsuId = anizipMeta?.kitsuId,
                simklId = anizipMeta?.simklId
            )
        } catch (_: Exception) { return null }
    }

    private suspend fun fetchAnimeSeasonSpecificMetadata(
        baseTitle: String,
        seasonNumber: Int
    ): AniListMetadata? {
        val searchQuery = "$baseTitle Season $seasonNumber"
        var meta = fetchAniListMetadata(searchQuery, null)
        if (meta != null) return meta

        val mainId = getMainSeriesId(baseTitle) ?: return null
        val targetId = getSeasonIdFromRelations(mainId, seasonNumber)
        if (targetId != null) {
            return fetchAniListMetadataById(targetId)
        }
        return null
    }

    private suspend fun getMainSeriesId(baseTitle: String): Int? {
        val graphql = """
            query (${"$"}query: String) {
                Page(perPage: 1) {
                    media(search: ${"$"}query, type: ANIME, sort: SEARCH_MATCH) {
                        id
                    }
                }
            }
        """.trimIndent()
        val body = JSONObject().apply {
            put("query", graphql)
            put("variables", JSONObject().apply { put("query", baseTitle) })
        }
        try {
            val response = app.post(
                url = anilistApiUrl,
                headers = mapOf("Content-Type" to "application/json"),
                requestBody = body.toString(),
                verify = false,
                cacheTime = 600
            )
            val json = JSONObject(response.text)
            val media = json.getJSONObject("data")?.getJSONObject("Page")?.getJSONArray("media")
            return media?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
        } catch (_: Exception) { return null }
    }

    private suspend fun getSeasonIdFromRelations(mainId: Int, targetSeason: Int): Int? {
        if (targetSeason == 1) return mainId
        var currentId = mainId
        for (step in 1 until targetSeason) {
            val nextId = getSequelId(currentId) ?: return null
            currentId = nextId
        }
        return currentId
    }

    private suspend fun getSequelId(anilistId: Int): Int? {
        val graphql = """
            query (${"$"}id: Int) {
                Media(id: ${"$"}id) {
                    relations {
                        edges {
                            node { id title { romaji } format season seasonYear }
                            relationType
                        }
                    }
                }
            }
        """.trimIndent()
        val body = JSONObject().apply {
            put("query", graphql)
            put("variables", JSONObject().apply { put("id", anilistId) })
        }
        try {
            val response = app.post(
                url = anilistApiUrl,
                headers = mapOf("Content-Type" to "application/json"),
                requestBody = body.toString(),
                verify = false,
                cacheTime = 600
            )
            val json = JSONObject(response.text)
            val edges = json.getJSONObject("data")?.getJSONObject("Media")
                ?.getJSONObject("relations")?.getJSONArray("edges") ?: return null
            for (i in 0 until edges.length()) {
                val edge = edges.getJSONObject(i)
                if (edge.optString("relationType") == "SEQUEL") {
                    return edge.getJSONObject("node").getInt("id")
                }
            }
        } catch (_: Exception) { }
        return null
    }

    private suspend fun fetchAniListMetadataById(id: Int): AniListMetadata? {
        val graphql = """
            query (${"$"}id: Int) {
                Media(id: ${"$"}id) {
                    id
                    idMal
                    title { romaji english }
                    description
                    startDate { year }
                    coverImage { large }
                    bannerImage
                }
            }
        """.trimIndent()
        val body = JSONObject().apply {
            put("query", graphql)
            put("variables", JSONObject().apply { put("id", id) })
        }
        try {
            val response = app.post(
                url = anilistApiUrl,
                headers = mapOf("Content-Type" to "application/json"),
                requestBody = body.toString(),
                verify = false,
                cacheTime = 600
            )
            val json = JSONObject(response.text)
            val media = json.getJSONObject("data")?.getJSONObject("Media") ?: return null
            val malId = media.optInt("idMal", -1).takeIf { it > 0 }
            val title = media.getJSONObject("title").optString("romaji")
                ?: media.getJSONObject("title").optString("english")
            val plot = media.optString("description")?.replace(Regex("<[^>]+>"), "")
            val yearAni = media.optJSONObject("startDate")?.optInt("year")
            val poster = media.optJSONObject("coverImage")?.optString("large")
            val banner = media.optString("bannerImage")

            val anizipMeta = fetchAniZip(id)
            return AniListMetadata(
                plot = plot?.ifBlank { null },
                year = yearAni?.takeIf { it > 0 },
                posterUrl = poster,
                logoUrl = fetchTMDBLogo(title, yearAni),
                backgroundPosterUrl = banner,
                aniListId = id,
                malId = malId ?: anizipMeta?.malId,
                kitsuId = anizipMeta?.kitsuId,
                simklId = anizipMeta?.simklId
            )
        } catch (_: Exception) { return null }
    }

    private suspend fun fetchTMDBLogo(title: String, year: Int?): String? {
        var searchUrl = "https://api.themoviedb.org/3/search/tv?api_key=$tmdbApiKey&query=${URLEncoder.encode(title, "utf-8")}"
        if (year != null) searchUrl += "&first_air_date_year=$year"
        try {
            val searchResponse = app.get(searchUrl, verify = false, cacheTime = 600)
            val results = JSONObject(searchResponse.text).getJSONArray("results")
            if (results.length() == 0) return null
            val best = results.getJSONObject(0)
            val tvId = best.getInt("id")
            val detailsUrl = "https://api.themoviedb.org/3/tv/$tvId?api_key=$tmdbApiKey&append_to_response=images"
            val detailResponse = app.get(detailsUrl, verify = false, cacheTime = 600)
            val detail = JSONObject(detailResponse.text)
            val logos = detail.getJSONObject("images").optJSONArray("logos")
            if (logos != null && logos.length() > 0) {
                val logoPath = logos.getJSONObject(0).optString("file_path")
                if (logoPath.isNotEmpty()) return tmdbImageBase + "w500" + logoPath
            }
        } catch (_: Exception) { }
        return null
    }

    private suspend fun fetchAniListTrailer(query: String, year: Int?): String? {
        val graphql = """
            query (${"$"}query: String) {
                Page(perPage: 1) {
                    media(search: ${"$"}query, type: ANIME) {
                        id
                        trailer {
                            id
                            site
                        }
                    }
                }
            }
        """.trimIndent()
        val body = JSONObject().apply {
            put("query", graphql)
            put("variables", JSONObject().apply { put("query", query) })
        }
        try {
            val response = app.post(
                url = anilistApiUrl,
                headers = mapOf("Content-Type" to "application/json"),
                requestBody = body.toString(),
                verify = false,
                cacheTime = 600
            )
            val json = JSONObject(response.text)
            val media = json.getJSONObject("data")?.getJSONObject("Page")?.getJSONArray("media")?.optJSONObject(0)
            val trailer = media?.optJSONObject("trailer")
            if (trailer != null && trailer.optString("site").equals("youtube", true)) {
                val id = trailer.optString("id")
                if (id.isNotEmpty()) return "https://www.youtube.com/watch?v=$id"
            }
        } catch (_: Exception) { }
        return null
    }

    private suspend fun fetchAniZip(anilistId: Int): AniZipMeta? {
        try {
            val response = app.get("$anizipApiUrl?anilist_id=$anilistId", verify = false, cacheTime = 600)
            val json = JSONObject(response.text)
            val mappings = json.optJSONObject("mappings") ?: return null
            return AniZipMeta(
                malId = mappings.optInt("mal_id", -1).takeIf { it > 0 },
                kitsuId = mappings.optInt("kitsu_id", -1).takeIf { it > 0 },
                simklId = mappings.optInt("simkl_id", -1).takeIf { it > 0 }
            )
        } catch (_: Exception) { return null }
    }

    data class AniListMetadata(
        val plot: String?,
        val year: Int?,
        val posterUrl: String?,
        val logoUrl: String?,
        val backgroundPosterUrl: String?,
        val aniListId: Int?,
        val malId: Int?,
        val kitsuId: Int?,
        val simklId: Int?
    )

    data class AniZipMeta(val malId: Int?, val kitsuId: Int?, val simklId: Int?)

    // ==============================
    // Title cleaning
    // ==============================
    private val qualityPattern = Regex(
        """\b(Bluray|BDRip|BRRip|WEB-DL|WEBRip|HDRip|DVDRip|HDTV|HDTC|HDTS|CAMRip|CAM|TS|TC|R5|DVDScr|Blu-ray|WEB\.DL|WEB.Rip|HD\.Rip|1080p|2160p|4K|720p|480p|360p|HEVC|x264|x265|H\.?264|H\.?265|AV1)\b""",
        RegexOption.IGNORE_CASE
    )
    private val audioPattern = Regex(
        """\b(Dual\s*Audio|Multi\s*Audio|Dubbed|Subbed|Hindi|English|Bengali|Tamil|Telugu|Malayalam|Kannada|Punjabi|Urdu|Marathi|Gujarati|Japanese|Spanish|French|German|Italian|Portuguese|Russian|Korean|Chinese|Arabic)\b""",
        RegexOption.IGNORE_CASE
    )
    private val seasonPattern = Regex("""\b(Season|S)\s*(\d+)\b""", RegexOption.IGNORE_CASE)
    private val bracketClean = Regex("""\[[^\]]*\]|\([^)]*\)""")

    private fun cleanTitle(raw: String): CleanedTitle {
        val stripped = bracketClean.replace(raw, " ").replace("_", " ")
        val qualityTags = qualityPattern.findAll(stripped).map { it.value }.toList()
        val audioTags = audioPattern.findAll(stripped).map { it.value }.toList()
        var clean = stripped
        clean = qualityPattern.replace(clean, "")
        clean = audioPattern.replace(clean, "")
        clean = Regex("""\s+""").replace(clean, " ").trim()

        val seasonMatch = seasonPattern.find(clean)
        val seasonNum = seasonMatch?.groupValues?.get(2)?.toIntOrNull()
        if (seasonMatch != null) {
            clean = clean.removeRange(seasonMatch.range).trim()
        }
        clean = clean.trim('-', ' ', '–', ',', '.')
        clean = Normalizer.normalize(clean, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return CleanedTitle(clean, seasonNum, audioTags, qualityTags)
    }

    private fun normalizeKey(baseTitle: String) =
        baseTitle.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]"), "")

    // ==============================
    // Quick AniList search for movie detection
    // ==============================
    private suspend fun quickAniListSearch(query: String, format: String): Boolean? {
        val graphql = """
            query (${"$"}query: String) {
                Page(perPage: 1) {
                    media(search: ${"$"}query, type: ANIME, format: $format) {
                        id
                    }
                }
            }
        """.trimIndent()
        val body = JSONObject().apply {
            put("query", graphql)
            put("variables", JSONObject().apply { put("query", query) })
        }
        try {
            val response = app.post(
                url = anilistApiUrl,
                headers = mapOf("Content-Type" to "application/json"),
                requestBody = body.toString(),
                verify = false,
                cacheTime = 600
            )
            val json = JSONObject(response.text)
            return json.getJSONObject("data")?.getJSONObject("Page")?.getJSONArray("media")?.length() ?: 0 > 0
        } catch (_: Exception) { return null }
    }

    // ==============================
    // Helpers
    // ==============================
    private fun inferTypeFromPosts(posts: List<Post>, cleaned: CleanedTitle): TvType {
        return if (cleaned.seasonNumber != null || posts.any { it.type == "series" }) TvType.TvSeries else TvType.Movie
    }

    private fun inferTypeFromPostIds(postIds: List<Int>, cleaned: CleanedTitle): TvType {
        return if (cleaned.seasonNumber != null) TvType.TvSeries else TvType.Movie
    }

    private fun linkToIp(data: String?): String {
        if (data == null) return ""
        return when {
            "index.circleftp.net" in data -> data.replace("index.circleftp.net", "15.1.4.2")
            "index2.circleftp.net" in data -> data.replace("index2.circleftp.net", "15.1.4.5")
            "index1.circleftp.net" in data -> data.replace("index1.circleftp.net", "15.1.4.9")
            "ftp3.circleftp.net" in data -> data.replace("ftp3.circleftp.net", "15.1.4.7")
            "ftp4.circleftp.net" in data -> data.replace("ftp4.circleftp.net", "15.1.1.5")
            "ftp5.circleftp.net" in data -> data.replace("ftp5.circleftp.net", "15.1.1.15")
            "ftp6.circleftp.net" in data -> data.replace("ftp6.circleftp.net", "15.1.2.3")
            "ftp7.circleftp.net" in data -> data.replace("ftp7.circleftp.net", "15.1.4.8")
            "ftp8.circleftp.net" in data -> data.replace("ftp8.circleftp.net", "15.1.2.2")
            "ftp9.circleftp.net" in data -> data.replace("ftp9.circleftp.net", "15.1.2.12")
            "ftp10.circleftp.net" in data -> data.replace("ftp10.circleftp.net", "15.1.4.3")
            "ftp11.circleftp.net" in data -> data.replace("ftp11.circleftp.net", "15.1.2.6")
            "ftp12.circleftp.net" in data -> data.replace("ftp12.circleftp.net", "15.1.2.1")
            "ftp13.circleftp.net" in data -> data.replace("ftp13.circleftp.net", "15.1.1.18")
            "ftp15.circleftp.net" in data -> data.replace("ftp15.circleftp.net", "15.1.4.12")
            "ftp17.circleftp.net" in data -> data.replace("ftp17.circleftp.net", "15.1.3.8")
            else -> data
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(newExtractorLink(source = this.name, name = this.name, url = data))
        return true
    }

    private fun selectUntilNonInt(string: String?): Int? {
        return string?.let { Regex("^.*?(?=\\D|$)").find(it)?.value?.toIntOrNull() }
    }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val lower = check?.lowercase()
        return when {
            lower == null -> null
            lower.contains("webrip") || lower.contains("web-dl") -> SearchQuality.WebRip
            lower.contains("bluray") -> SearchQuality.BlueRay
            lower.contains("hdts") || lower.contains("hdcam") || lower.contains("hdtc") -> SearchQuality.HdCam
            lower.contains("dvd") -> SearchQuality.DVD
            lower.contains("cam") -> SearchQuality.Cam
            lower.contains("camrip") || lower.contains("rip") -> SearchQuality.CamRip
            lower.contains("hdrip") || lower.contains("hd") || lower.contains("hdtv") -> SearchQuality.HD
            lower.contains("telesync") -> SearchQuality.Telesync
            lower.contains("telecine") -> SearchQuality.Telecine
            else -> null
        }
    }

    // ==============================
    // Data classes
    // ==============================
    data class PageData(val posts: List<Post>)
    data class Post(
        val id: Int,
        val type: String,
        val imageSm: String,
        val title: String,
        val name: String?
    )
    data class Data(
        val type: String,
        val imageSm: String,
        val title: String,
        val image: String,
        val metaData: String?,
        val name: String,
        val quality: String?,
        val year: String?,
        val watchTime: String?
    )
    data class TvSeries(val content: List<Content>)
    data class Content(val episodes: List<EpisodeData>, val seasonName: String)
    data class EpisodeData(val link: String, val title: String)
    data class Movies(val content: String?)
}