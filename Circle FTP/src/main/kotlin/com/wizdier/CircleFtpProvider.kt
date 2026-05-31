package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.DubStatus
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
/**
 * Represents a title after cleansing and normalization.
 * All bracketed/grouped metadata is extracted into typed tag lists.
 */
data class CleanedTitle(
    val baseTitle: String,
    val seasonNumber: Int?,
    val audioTags: List<String>,
    val qualityTags: List<String>
)

/**
 * Internal grouping key used during the consolidation pipeline.
 * Audio-profiled variant groups are collapsed under one shared base key.
 */
data class ConsolidationKey(
    val normalizedBase: String,
    val tvType: TvType,
    val seasonNumber: Int?
)

/**
 * A single unified entry presented to the user.
 * May carry multiple audio-variant post groups internally.
 */
data class ConsolidatedEntry(
    val id: String,
    val baseTitle: String,
    val displayTitle: String,
    val poster: String?,
    val tvType: TvType,
    val variantGroups: List<VariantGroup>,
    val seasons: Map<Int, List<Int>>
)

/**
 * Represents one or more posts that share the SAME audio profile.
 * When a title exists as [Dual Audio] and [Multi Audio] variants,
 * each becomes a separate VariantGroup under one ConsolidatedEntry.
 */
data class VariantGroup(
    val audioLabel: String,
    val postIds: List<Int>
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

    /** Season cache keyed by the ConsolidatedEntry id (e.g., "group:somekey"). */
    private val animeSeasonCache = mutableMapOf<String, Map<Int, List<Int>>>()

    /** TMDB API configuration. */
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

    /**
     * Fetches raw posts from the Circle FTP API.
     * Falls back to secondary API if primary fails.
     */
    private suspend fun fetchPosts(params: String): List<Post> {
        val json = try {
            app.get("$mainApiUrl/api/posts?$params", verify = false, cacheTime = 60)
        } catch (_: Exception) {
            app.get("$apiUrl/api/posts?$params", verify = false, cacheTime = 60)
        }
        val jsonObj = JSONObject(json.text)
        val postsArray = jsonObj.getJSONArray("posts")
        return (0 until postsArray.length()).map { i ->
            val post = postsArray.getJSONObject(i)
            Post(
                id = post.getInt("id"),
                type = post.optString("type"),
                imageSm = post.optString("imageSm"),
                title = post.optString("title"),
                name = post.optString("name").takeIf { it.isNotEmpty() }
            )
        }
    }

    /**
     * Consolidation pipeline:
     * 1. Clean every post title.
     * 2. Group by normalized base title + inferred type + season number.
     * 3. Within each base group, split into audio-variant subgroups.
     * 4. Collapse audio variants into a single ConsolidatedEntry.
     * 5. For anime with multiple seasons, cache season map and expose ONLY Season 1.
     */
    private suspend fun consolidateAndPrepare(
        posts: List<Post>,
        tvTypeHint: TvType?
    ): List<SearchResponse> = coroutineScope {
        // --- Phase 1: Clean titles and group by base identity ---
        val baseGroups = linkedMapOf<String, MutableList<Pair<Post, CleanedTitle>>>()
        for (post in posts) {
            val cleaned = cleanTitle(post.title)
            val normalizedKey = normalizeKey(cleaned.baseTitle)
            baseGroups.getOrPut(normalizedKey) { mutableListOf() }.add(post to cleaned)
        }

        // --- Phase 2: Build ConsolidatedEntry list ---
        val entries = baseGroups.mapNotNull { (normalizedKey, pairs) ->
            val firstPair = pairs.first()
            val firstPost = firstPair.first
            val firstCleaned = firstPair.second
            val baseTitle = firstCleaned.baseTitle

            // Determine final TvType
            var resolvedType = tvTypeHint
            if (resolvedType == null) {
                resolvedType = inferTypeFromPairs(pairs)
            }
            val finalType = resolvedType ?: TvType.Movie

            // Detect anime season spread
            val seasons = mutableMapOf<Int, MutableList<Int>>()
            val nonSeasonedIds = mutableListOf<Int>()
            for ((post, cleaned) in pairs) {
                if (post.type == "series" && finalType == TvType.Anime && cleaned.seasonNumber != null) {
                    seasons.getOrPut(cleaned.seasonNumber) { mutableListOf() }.add(post.id)
                } else {
                    nonSeasonedIds.add(post.id)
                }
            }

            // For anime multi-season: cache and pick Season 1 as baseline
            val entrySeasons = seasons.toMap()
            val isMultiSeasonAnime = finalType == TvType.Anime && entrySeasons.size > 1
            if (isMultiSeasonAnime) {
                val cacheKey = "group:$normalizedKey"
                animeSeasonCache[cacheKey] = entrySeasons
            }

            // Build audio-variant groups from the baseline posts only
            val variantMap = linkedMapOf<String, MutableList<Int>>()
            for ((post, cleaned) in pairs) {
                if (isMultiSeasonAnime) {
                    // Only include posts belonging to baseline season
                    val belongsToBaseline = entrySeasons[1]?.contains(post.id) == true ||
                        (entrySeasons.isEmpty() && nonSeasonedIds.contains(post.id))
                    if (!belongsToBaseline) continue
                }
                val audioLabel = if (cleaned.audioTags.isNotEmpty()) {
                    cleaned.audioTags.joinToString("-").replace(" ", "-")
                } else {
                    "default"
                }
                variantMap.getOrPut(audioLabel) { mutableListOf() }.add(post.id)
            }

            val variantGroups = variantMap.map { (label, ids) ->
                VariantGroup(audioLabel = label, postIds = ids)
            }

            val poster = firstPost.imageSm?.let { "$mainApiUrl/uploads/$it" }
            ConsolidatedEntry(
                id = "group:$normalizedKey",
                baseTitle = baseTitle,
                displayTitle = baseTitle,
                poster = poster,
                tvType = finalType,
                variantGroups = variantGroups,
                seasons = entrySeasons
            )
        }

        // --- Phase 3: Parallel metadata enrichment & SearchResponse mapping ---
        entries.map { entry ->
            async {
                var enrichedType = entry.tvType
                // Distinguish Cartoon vs AnimeMovie via AniList
                if (enrichedType == TvType.Cartoon) {
                    val isAnimeMovie = quickAniListSearch(entry.baseTitle, "MOVIE") == true
                    if (isAnimeMovie) enrichedType = TvType.AnimeMovie
                }
                toSearchResponse(entry, enrichedType)
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Converts a ConsolidatedEntry into a Cloudstream3 SearchResponse.
     * For multi-season anime, only Season 1 is exposed in the grid.
     */
    private fun toSearchResponse(entry: ConsolidatedEntry, tvType: TvType): SearchResponse? {
        val url = if (entry.variantGroups.size == 1 && entry.variantGroups.first().postIds.size == 1) {
            "$mainUrl/content/${entry.variantGroups.first().postIds.first()}"
        } else {
            "circleftp://load?key=${URLEncoder.encode(entry.id, "utf-8")}"
        }
        return newAnimeSearchResponse(entry.displayTitle, url, tvType) {
            this.posterUrl = entry.poster
            this.quality = getSearchQuality(entry.displayTitle)
            addDubStatus(
                dubExist = "dubbed" in entry.displayTitle.lowercase() || "dual audio" in entry.displayTitle.lowercase() || "multi audio" in entry.displayTitle.lowercase(),
                subExist = false
            )
        }
    }

    // ==============================
    // LOAD - Core presentation logic
    // ==============================
    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val postIds: List<Int>
        val variantGroups: List<VariantGroup>
        val seasonOverride: Int?
        val cacheKey: String?
        val baseTitle: String

        if (url.startsWith("circleftp://load")) {
            val uri = java.net.URI(url)
            val queryMap = uri.query.split("&").associate { param ->
                val parts = param.split("=")
                parts[0] to (parts.getOrNull(1) ?: "")
            }
            cacheKey = queryMap["key"]
            seasonOverride = queryMap["season"]?.toIntOrNull()
            val fullEntry = resolveEntryFromCacheKey(cacheKey)
            baseTitle = fullEntry.baseTitle

            // If season override is active for anime, use that season's post IDs
            val seasonPosts = if (seasonOverride != null && fullEntry.tvType == TvType.Anime) {
                val seasonMap = animeSeasonCache[cacheKey] ?: emptyMap()
                seasonMap[seasonOverride] ?: throw Exception("No posts for $cacheKey season $seasonOverride")
            } else {
                fullEntry.variantGroups.flatMap { vg -> vg.postIds }
            }
            postIds = seasonPosts

            // Rebuild variant groups scoped to the active season's posts
            val activeIdSet = seasonPosts.toSet()
            variantGroups = fullEntry.variantGroups.mapNotNull { vg ->
                val filtered = vg.postIds.filter { id -> id in activeIdSet }
                if (filtered.isEmpty()) null else VariantGroup(vg.audioLabel, filtered)
            }
        } else {
            val idString = url.substringAfterLast("/")
            cacheKey = null
            seasonOverride = null
            val singleId = idString.toInt()
            postIds = listOf(singleId)
            variantGroups = listOf(VariantGroup(audioLabel = "default", postIds = listOf(singleId)))
            baseTitle = ""
        }

        // --- Fetch all post details in parallel ---
        val postJsons = postIds.map { id ->
            async {
                try {
                    app.get("$mainApiUrl/api/posts/$id", verify = false, cacheTime = 60)
                } catch (_: Exception) {
                    app.get("$apiUrl/api/posts/$id", verify = false, cacheTime = 60)
                }
            }
        }.awaitAll()

        val loadDataList = postJsons.mapIndexed { index, response ->
            val obj = JSONObject(response.text)
            val loadData = Data(
                type = obj.optString("type"),
                imageSm = obj.optString("imageSm"),
                title = obj.optString("title"),
                image = obj.optString("image"),
                metaData = obj.optString("metaData").takeIf { it.isNotEmpty() },
                name = obj.optString("name"),
                quality = obj.optString("quality").takeIf { it.isNotEmpty() },
                year = obj.optString("year").takeIf { it.isNotEmpty() },
                watchTime = obj.optString("watchTime").takeIf { it.isNotEmpty() }
            )
            val cleaned = cleanTitle(loadData.title)
            val useMain = response.url.contains(mainApiUrl)
            Triple(loadData, useMain, cleaned)
        }

        val firstData = loadDataList.first().first
        val mainCleaned = loadDataList.first().third
        val resolvedBaseTitle = if (baseTitle.isNotEmpty()) baseTitle else mainCleaned.baseTitle
        val tvType = if (firstData.type == "singleVideo") TvType.Movie else inferTypeFromPostData(loadDataList)

        // --- Fetch metadata ---
        val metaResult = resolveMetadata(resolvedBaseTitle, tvType, mainCleaned, seasonOverride)

        // Unpack metadata
        var finalPlot = metaResult.plot ?: firstData.metaData
        var finalYear = metaResult.year ?: selectUntilNonInt(firstData.year)
        var finalPoster: String? = metaResult.posterUrl ?: firstData.image?.let { "$apiUrl/uploads/$it" }
        var finalLogo: String? = metaResult.logoUrl
        var finalBackground: String? = metaResult.backgroundPosterUrl
        val aniListId: Int? = metaResult.aniListId
        val malId: Int? = metaResult.malId
        val kitsuId: Int? = metaResult.kitsuId
        val simklId: Int? = metaResult.simklId

        // --- Build movie response ---
        if (firstData.type == "singleVideo") {
            val firstUseMain = loadDataList.first().second
            val rawUrl = JSONObject(postJsons.first().text).optString("content")
            val movieUrl = if (firstUseMain) rawUrl else linkToIp(rawUrl)
            val duration = getDurationFromString(firstData.watchTime)
            return@coroutineScope newMovieLoadResponse(resolvedBaseTitle, url, tvType, movieUrl) {
                this.posterUrl = finalPoster
                this.year = finalYear
                this.plot = finalPlot
                this.duration = duration
                this.logoUrl = finalLogo
                this.backgroundPosterUrl = finalBackground
            }
        }

        // --- Build episode list: merge all variant groups into unified episodes ---
        val allEpisodes = mutableListOf<Episode>()
        loadDataList.forEachIndexed { dataIndex, triple ->
            val data = triple.first
            val useMain = triple.second
            val cleaned = triple.third
            val postId = postIds[dataIndex]

            // Determine which variant group this post belongs to
            val variantLabel = variantGroups.find { vg -> postId in vg.postIds }?.audioLabel ?: "default"
            val sourceTag = buildSourceTag(variantLabel, cleaned.audioTags)

            val tvObj = JSONObject(postJsons[dataIndex].text)
            val contentArray = tvObj.getJSONArray("content")
            val tvData = TvSeries(
                content = (0 until contentArray.length()).map { i ->
                    val seasonObj = contentArray.getJSONObject(i)
                    val episodesArray = seasonObj.getJSONArray("episodes")
                    Content(
                        episodes = (0 until episodesArray.length()).map { j ->
                            val epObj = episodesArray.getJSONObject(j)
                            EpisodeData(
                                link = epObj.optString("link"),
                                title = epObj.optString("title")
                            )
                        },
                        seasonName = seasonObj.optString("seasonName")
                    )
                }
            )
            var seasonAccumulator = 0
            tvData.content.forEach { season ->
                seasonAccumulator++
                var episodeAccumulator = 0
                season.episodes.forEach { ep ->
                    episodeAccumulator++
                    val link = ep.link?.let { if (useMain) it else linkToIp(it) } ?: return@forEach
                    allEpisodes.add(
                        newEpisode(link) {
                            this.episode = episodeAccumulator
                            this.season = seasonAccumulator
                            this.name = if (sourceTag.isNotBlank()) {
                                "${ep.title.ifBlank { "Episode $episodeAccumulator" }} ($sourceTag)"
                            } else {
                                ep.title.ifBlank { "Episode $episodeAccumulator" }
                            }
                        }
                    )
                }
            }
        }

        // --- Build series/anime response ---
        val isAnimeType = tvType == TvType.Anime || tvType == TvType.AnimeMovie || tvType == TvType.OVA
        val seriesResponse = if (isAnimeType) {
            newAnimeLoadResponse(resolvedBaseTitle, url, tvType) {
                addEpisodes(DubStatus.Dubbed, allEpisodes)
                this.posterUrl = finalPoster
                this.year = finalYear
                this.plot = finalPlot
                this.logoUrl = finalLogo
                this.backgroundPosterUrl = finalBackground
            }
        } else {
            newTvSeriesLoadResponse(resolvedBaseTitle, url, tvType, allEpisodes) {
                this.posterUrl = finalPoster
                this.year = finalYear
                this.plot = finalPlot
                this.logoUrl = finalLogo
                this.backgroundPosterUrl = finalBackground
            }
        }

        // --- Anime recommendation chain (multi-season) ---
        if (tvType == TvType.Anime && cacheKey != null) {
            val seasons = animeSeasonCache[cacheKey] ?: emptyMap()
            val currentSeason = seasonOverride ?: 1
            if (seasons.size > 1) {
                // Fetch independent metadata for each recommendation season in parallel
                val recommendationDeferreds = seasons.filterKeys { seasonKey -> seasonKey != currentSeason }
                    .map { (seasonNum, seasonPostIds) ->
                        async {
                            val seasonSpecificMeta = fetchAnimeSeasonIndependentMetadata(resolvedBaseTitle, seasonNum)
                            val recUrl = "circleftp://load?key=${URLEncoder.encode(cacheKey, "utf-8")}&season=$seasonNum"
                            newAnimeSearchResponse("$resolvedBaseTitle Season $seasonNum", recUrl, TvType.Anime) {
                                this.posterUrl = seasonSpecificMeta?.posterUrl ?: finalPoster
                            }
                        }
                    }
                seriesResponse.recommendations = recommendationDeferreds.awaitAll()
            }
        }

        seriesResponse
    }

    /**
     * Resolves a ConsolidatedEntry from the cache key for load() routing.
     */
    private fun resolveEntryFromCacheKey(cacheKey: String?): ConsolidatedEntry {
        // The cacheKey is the full entry id (e.g., "group:sometitle")
        // We reconstruct a minimal entry from cached season data
        val seasons = animeSeasonCache[cacheKey] ?: emptyMap()
        val allIds = seasons.values.flatten()
        return ConsolidatedEntry(
            id = cacheKey ?: "unknown",
            baseTitle = cacheKey?.removePrefix("group:")?.replace("+", " ") ?: "Unknown",
            displayTitle = cacheKey?.removePrefix("group:")?.replace("+", " ") ?: "Unknown",
            poster = null,
            tvType = TvType.Anime,
            variantGroups = listOf(VariantGroup(audioLabel = "default", postIds = allIds)),
            seasons = seasons
        )
    }

    /**
     * Builds the episode source tag from variant label and extracted audio tags.
     */
    private fun buildSourceTag(variantLabel: String, audioTags: List<String>): String {
        return if (variantLabel != "default") {
            "FTP [${variantLabel.replace("-", " ")}]"
        } else if (audioTags.isNotEmpty()) {
            "FTP [${audioTags.joinToString(", ")}]"
        } else {
            "FTP"
        }
    }

    // ==============================
    // Unified Metadata Resolution
    // ==============================
    /**
     * Routes metadata fetching to the correct backend:
     * - Anime/OVA/AnimeMovie -> AniList + AniZip
     * - Everything else -> TMDB
     */
    private suspend fun resolveMetadata(
        baseTitle: String,
        tvType: TvType,
        cleaned: CleanedTitle,
        seasonOverride: Int?
    ): UnifiedMetadata = coroutineScope {
        val isAnimeType = tvType == TvType.Anime || tvType == TvType.AnimeMovie || tvType == TvType.OVA
        if (isAnimeType) {
            val seasonMeta = if (seasonOverride != null) {
                fetchAnimeSeasonIndependentMetadata(baseTitle, seasonOverride)
            } else {
                fetchAniListMetadata(baseTitle, null)
            }
            if (seasonMeta != null) {
                UnifiedMetadata(
                    plot = seasonMeta.plot,
                    year = seasonMeta.year,
                    posterUrl = seasonMeta.posterUrl,
                    logoUrl = seasonMeta.logoUrl,
                    backgroundPosterUrl = seasonMeta.backgroundPosterUrl,
                    aniListId = seasonMeta.aniListId,
                    malId = seasonMeta.malId,
                    kitsuId = seasonMeta.kitsuId,
                    simklId = seasonMeta.simklId
                )
            } else {
                UnifiedMetadata()
            }
        } else {
            val tmdbMetaDeferred = async { fetchTMDBMetadata(baseTitle, null, tvType) }
            val logoDeferred = async { fetchTMDBLogo(baseTitle, null) }
            val backdropDeferred = async { fetchTMDBBackdrop(baseTitle, null, tvType) }

            val tmdbMeta = tmdbMetaDeferred.await()
            val logoUrl = logoDeferred.await()
            val backdropUrl = backdropDeferred.await()

            UnifiedMetadata(
                plot = tmdbMeta?.plot,
                year = tmdbMeta?.year,
                posterUrl = tmdbMeta?.posterUrl,
                logoUrl = logoUrl ?: tmdbMeta?.logoUrl,
                backgroundPosterUrl = backdropUrl ?: tmdbMeta?.backgroundPosterUrl
            )
        }
    }


    data class UnifiedMetadata(
        val plot: String? = null,
        val year: Int? = null,
        val posterUrl: String? = null,
        val logoUrl: String? = null,
        val backgroundPosterUrl: String? = null,
        val aniListId: Int? = null,
        val malId: Int? = null,
        val kitsuId: Int? = null,
        val simklId: Int? = null
    )

    // ==============================
    // TMDB Integration
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
            val poster = detail.optString("poster_path")
            return TmdbMetadata(
                plot = detail.optString("overview").ifBlank { null },
                year = (detail.optString("first_air_date") ?: detail.optString("release_date"))
                    .substringBefore("-").toIntOrNull(),
                posterUrl = poster?.let { tmdbImageBase + "w500" + it },
                logoUrl = logoPath?.let { tmdbImageBase + "w500" + it },
                backgroundPosterUrl = backdrop?.let { tmdbImageBase + "w1280" + it }
            )
        } catch (_: Exception) { return null }
    }

    /**
     * Fetches a logo image from TMDB's images endpoint.
     */
    private suspend fun fetchTMDBLogo(title: String, year: Int?): String? {
        val searchUrl = if (year != null) {
            "https://api.themoviedb.org/3/search/tv?api_key=$tmdbApiKey&query=${URLEncoder.encode(title, "utf-8")}&first_air_date_year=$year"
        } else {
            "https://api.themoviedb.org/3/search/tv?api_key=$tmdbApiKey&query=${URLEncoder.encode(title, "utf-8")}"
        }
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

    /**
     * Fetches a high-resolution backdrop image from TMDB.
     */
    private suspend fun fetchTMDBBackdrop(title: String, year: Int?, tvType: TvType): String? {
        val type = if (tvType == TvType.Movie || tvType == TvType.Cartoon || tvType == TvType.AnimeMovie) "movie" else "tv"
        var searchUrl = "https://api.themoviedb.org/3/search/$type?api_key=$tmdbApiKey&query=${URLEncoder.encode(title, "utf-8")}"
        if (year != null) searchUrl += if (type == "movie") "&year=$year" else "&first_air_date_year=$year"
        try {
            val searchResponse = app.get(searchUrl, verify = false, cacheTime = 600)
            val results = JSONObject(searchResponse.text).getJSONArray("results")
            if (results.length() == 0) return null
            val best = results.getJSONObject(0)
            val backdropPath = best.optString("backdrop_path")
            if (backdropPath.isNotEmpty()) {
                return tmdbImageBase + "w1280" + backdropPath
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
    // AniList Integration
    // ==============================
    private suspend fun fetchAniListMetadata(query: String, year: Int?): AniListMetadata? {
        val graphql = """
            query (${'$'}query: String) {
                Page(perPage: 3) {
                    media(search: ${'$'}query, type: ANIME) {
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
                json = body.toString(),
                verify = false,
                cacheTime = 600
            )
            val json = JSONObject(response.text)
            val mediaArray = json.getJSONObject("data")?.getJSONObject("Page")?.getJSONArray("media") ?: return null
            if (mediaArray.length() == 0) return null
            var best = mediaArray.getJSONObject(0)
            if (year != null) {
                for (i in 0 until mediaArray.length()) {
                    val mediaItem = mediaArray.getJSONObject(i)
                    val itemYear = mediaItem.optJSONObject("startDate")?.optInt("year") ?: 0
                    val bestYear = best.optJSONObject("startDate")?.optInt("year") ?: 9999
                    if (abs(itemYear - year) < abs(bestYear - year)) {
                        best = mediaItem
                    }
                }
            }
            val anilistId = best.getInt("id")
            val malId = best.optInt("idMal", -1).takeIf { malIdValue -> malIdValue > 0 }
            val titleRomaji = best.getJSONObject("title").optString("romaji")
            val titleEnglish = best.getJSONObject("title").optString("english")
            val title = titleRomaji.ifBlank { titleEnglish }
            val plot = best.optString("description")?.replace(Regex("<[^>]+>"), "")
            val yearAni = best.optJSONObject("startDate")?.optInt("year")
            val poster = best.optJSONObject("coverImage")?.optString("large")
            val banner = best.optString("bannerImage")

            val anizipMeta = fetchAniZip(anilistId)
            val logoFromTmdb = fetchTMDBLogo(title, yearAni)

            return AniListMetadata(
                plot = plot?.ifBlank { null },
                year = yearAni?.takeIf { yearValue -> yearValue > 0 },
                posterUrl = poster,
                logoUrl = logoFromTmdb,
                backgroundPosterUrl = banner,
                aniListId = anilistId,
                malId = malId ?: anizipMeta?.malId,
                kitsuId = anizipMeta?.kitsuId,
                simklId = anizipMeta?.simklId
            )
        } catch (_: Exception) { return null }
    }

    /**
     * Fetches metadata for a SPECIFIC anime season independently.
     * Uses AniList relation traversal + AniZip to resolve unique tracking IDs.
     * Critical: Does NOT inherit Season 1 metadata.
     */
    suspend fun fetchAnimeSeasonIndependentMetadata(
        baseTitle: String,
        seasonNumber: Int
    ): AniListMetadata? {
        if (seasonNumber == 1) {
            return fetchAniListMetadata(baseTitle, null)
        }

        // Strategy 1: Direct search for "Title Season N"
        val directSearch = "$baseTitle Season $seasonNumber"
        val directMeta = fetchAniListMetadata(directSearch, null)
        if (directMeta != null) return directMeta

        // Strategy 2: Traverse AniList relation chain from main series
        val mainId = getMainSeriesId(baseTitle) ?: return null
        val targetId = getSeasonIdFromRelations(mainId, seasonNumber)
        if (targetId != null) {
            return fetchAniListMetadataById(targetId)
        }
        return null
    }

    private suspend fun getMainSeriesId(baseTitle: String): Int? {
        val graphql = """
            query (${'$'}query: String) {
                Page(perPage: 1) {
                    media(search: ${'$'}query, type: ANIME, sort: SEARCH_MATCH) {
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
                json = body.toString(),
                verify = false,
                cacheTime = 600
            )
            val json = JSONObject(response.text)
            val media = json.getJSONObject("data")?.getJSONObject("Page")?.getJSONArray("media")
            return media?.optJSONObject(0)?.optInt("id")?.takeIf { idValue -> idValue > 0 }
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
            query (${'$'}id: Int) {
                Media(id: ${'$'}id) {
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
                json = body.toString(),
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
            query (${'$'}id: Int) {
                Media(id: ${'$'}id) {
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
                json = body.toString(),
                verify = false,
                cacheTime = 600
            )
            val json = JSONObject(response.text)
            val media = json.getJSONObject("data")?.getJSONObject("Media") ?: return null
            val malId = media.optInt("idMal", -1).takeIf { malIdValue -> malIdValue > 0 }
            val titleRomaji = media.getJSONObject("title").optString("romaji")
            val titleEnglish = media.getJSONObject("title").optString("english")
            val title = titleRomaji.ifBlank { titleEnglish }
            val plot = media.optString("description")?.replace(Regex("<[^>]+>"), "")
            val yearAni = media.optJSONObject("startDate")?.optInt("year")
            val poster = media.optJSONObject("coverImage")?.optString("large")
            val banner = media.optString("bannerImage")

            val anizipMeta = fetchAniZip(id)
            val logoFromTmdb = fetchTMDBLogo(title, yearAni)

            return AniListMetadata(
                plot = plot?.ifBlank { null },
                year = yearAni?.takeIf { yearValue -> yearValue > 0 },
                posterUrl = poster,
                logoUrl = logoFromTmdb,
                backgroundPosterUrl = banner,
                aniListId = id,
                malId = malId ?: anizipMeta?.malId,
                kitsuId = anizipMeta?.kitsuId,
                simklId = anizipMeta?.simklId
            )
        } catch (_: Exception) { return null }
    }

    private suspend fun fetchAniZip(anilistId: Int): AniZipMeta? {
        try {
            val response = app.get("$anizipApiUrl?anilist_id=$anilistId", verify = false, cacheTime = 600)
            val json = JSONObject(response.text)
            val mappings = json.optJSONObject("mappings") ?: return null
            return AniZipMeta(
                malId = mappings.optInt("mal_id", -1).takeIf { idValue -> idValue > 0 },
                kitsuId = mappings.optInt("kitsu_id", -1).takeIf { idValue -> idValue > 0 },
                simklId = mappings.optInt("simkl_id", -1).takeIf { idValue -> idValue > 0 }
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
    // Title Cleaning Engine
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

    /**
     * Cleans a raw title by extracting and separating metadata components.
     * Returns a structured CleanedTitle with base title and extracted tags.
     */
    private fun cleanTitle(raw: String): CleanedTitle {
        val stripped = bracketClean.replace(raw, " ").replace("_", " ")
        val qualityTags = qualityPattern.findAll(stripped).map { matchResult -> matchResult.value }.toList()
        val audioTags = audioPattern.findAll(stripped).map { matchResult -> matchResult.value }.toList()
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
    // Quick AniList search for type detection
    // ==============================
    private suspend fun quickAniListSearch(query: String, format: String): Boolean? {
        val graphql = """
            query (${'$'}query: String) {
                Page(perPage: 1) {
                    media(search: ${'$'}query, type: ANIME, format: $format) {
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
                json = body.toString(),
                verify = false,
                cacheTime = 600
            )
            val json = JSONObject(response.text)
            val mediaLength = json.getJSONObject("data")?.getJSONObject("Page")?.getJSONArray("media")?.length() ?: 0
            return mediaLength > 0
        } catch (_: Exception) { return null }
    }

    // ==============================
    // Type Inference Helpers
    // ==============================
    private fun inferTypeFromPairs(pairs: List<Pair<Post, CleanedTitle>>): TvType {
        return if (pairs.any { pair -> pair.second.seasonNumber != null || pair.first.type == "series" }) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
    }

    private fun inferTypeFromPostData(loadDataList: List<Triple<Data, Boolean, CleanedTitle>>): TvType {
        return if (loadDataList.any { triple -> triple.third.seasonNumber != null }) TvType.TvSeries else TvType.Movie
    }

    // ==============================
    // Link processing
    // ==============================
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

    // ==============================
    // Cloudstream3 Link Loading
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(newExtractorLink(source = this.name, name = this.name, url = data))
        return true
    }

    // ==============================
    // Utility Helpers
    // ==============================
    private fun selectUntilNonInt(string: String?): Int? {
        return string?.let { s -> Regex("^.*?(?=\\D|$)").find(s)?.value?.toIntOrNull() }
    }

    private fun getDurationFromString(watchTime: String?): Int? {
        return watchTime?.let { wt ->
            val minutes = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE).find(wt)?.groupValues?.get(1)?.toIntOrNull()
            minutes
        }
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
            lower.contains("camrip") || lower.contains("brrip") || lower.contains("hdrip") -> SearchQuality.CamRip
            lower.contains("hdrip") || lower.contains("hd") || lower.contains("hdtv") -> SearchQuality.HD
            lower.contains("telesync") -> SearchQuality.Telesync
            lower.contains("telecine") -> SearchQuality.Telecine
            else -> null
        }
    }

    // ==============================
    // JSON Data classes
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
