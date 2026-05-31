package com.wizdier

import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class CircleFtpProvider : MainAPI() {
    override var mainUrl = "http://new.circleftp.net"
    private val mainApiUrl = "http://new.circleftp.net:5000"
    private val fallbackApiUrl = "http://15.1.1.50:5000"
    private val tmdbApiKey = "e6333b32409e02a4a6eba6fb7ff866bb"
    private val tmdbApiBase = "https://api.themoviedb.org/3"
    private val aniListGraphQlUrl = "https://graphql.anilist.co"
    private val aniZipUrl = "https://api.ani.zip/mappings"
    
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

    override val mainPage = mainPageOf(
        "80" to "Featured", "6" to "English Movies", "9" to "English & Foreign TV Series",
        "22" to "Dubbed TV Series", "2" to "Hindi Movies", "5" to "Hindi TV Series",
        "238" to "Indian TV Show", "7" to "English & Foreign Hindi Dubbed Movies",
        "8" to "Foreign Language Movies", "3" to "South Indian Dubbed Movies",
        "4" to "South Indian Movies", "1" to "Animation Movies", "21" to "Anime Series",
        "85" to "Documentary", "15" to "WWE"
    )

    private val categoryTypeMap = mapOf(
        "80" to null, "6" to TvType.Movie, "9" to TvType.TvSeries, "22" to TvType.TvSeries,
        "2" to TvType.Movie, "5" to TvType.TvSeries, "238" to TvType.TvSeries,
        "7" to TvType.Movie, "8" to TvType.Movie, "3" to TvType.Movie, "4" to TvType.Movie,
        "1" to TvType.Cartoon, "21" to TvType.Anime, "85" to TvType.Documentary, "15" to TvType.Others
    )

    private val aniListMediaCache = ConcurrentHashMap<Int, AniListMedia>()
    private var tmdbConfigCache: TmdbConfiguration? = null

    // ==============================
    // MAIN PAGE & SEARCH (FAST — SITE ONLY)
    // ==============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val posts = fetchPosts("categoryExact=${request.data}&page=$page&order=desc&limit=10")
        val tvTypeHint = categoryTypeMap[request.data]
        val results = buildSearchResults(posts, tvTypeHint)
        return newHomePageResponse(request.name, results, true)
    }

    override suspend fun search(query: String): List<<SearchResponse> {
        val posts = fetchPosts("searchTerm=${urlEncode(query)}&order=desc")
        return buildSearchResults(posts, null)
    }

    private suspend fun fetchPosts(params: String): List<<Post> {
        val response = apiGet("/api/posts?$params", cacheTime = 60)
        return response.parsedSafe<<PageData>()?.posts ?: emptyList()
    }

    // ==============================
    // SEARCH BUILDER — SEASONAL SPLITTING
    // ==============================
    private fun buildSearchResults(posts: List<<Post>, tvTypeHint: TvType?): List<<SearchResponse> {
        val indexed = posts.mapNotNull { post ->
            if (post.type != "singleVideo" && post.type != "series") return@mapNotNull null
            val normalized = normalizeTitle(post.title)
            val resolvedType = tvTypeHint ?: inferTypeFast(post, normalized)

            // SEASONAL SPLIT KEY: Ensure each season gets a unique entry in search
            // This allows the app to track and metadata them individually
            val seasonPart = when (resolvedType) {
                TvType.Anime, TvType.AnimeMovie, TvType.TvSeries,
                TvType.AsianDrama, TvType.Cartoon -> (normalized.season ?: 0).toString()
                else -> "0"
            }

            IndexedPost(
                id = post.id,
                imageSm = post.imageSm,
                rawTitle = post.title,
                mediaType = resolvedType,
                postType = post.type,
                displayTitle = normalized.cleanTitle,
                franchiseTitle = normalized.franchiseTitle,
                year = normalized.year,
                declaredSeason = normalized.season,
                quality = normalized.quality,
                audioTag = normalized.audioTag,
                groupKey = "${resolvedType.name}:${normalized.franchiseTitle}:${normalized.year ?: 0}:$seasonPart"
            )
        }

        // Group by the unique key. Since season is part of the key, seasons are separated.
        val groups = indexed.groupBy { it.groupKey }
        val results = mutableListOf<<SearchResponse>()

        groups.values.forEach { group ->
            val primary = group.minByOrNull { it.id } ?: return@forEach
            val posterUrl = buildPosterUrl(primary.imageSm)
            val quality = group.mapNotNull { it.quality }.maxByOrNull { it }

            val hasDub = group.any {
                val tag = it.audioTag?.lowercase() ?: ""
                tag.contains("dub") || tag.contains("dual") || tag.contains("multi")
            }
            val hasSub = group.any {
                val tag = it.audioTag?.lowercase() ?: ""
                tag.contains("sub")
            } || !hasDub

            // Movies/Others: stackable for convenience. 
            // Anime/TV: distinct IDs for correct metadata tracking per season/series
            val isStackable = primary.mediaType in setOf(TvType.Movie, TvType.Others, TvType.Documentary)
            val url = if (isStackable) {
                val allIds = group.map { it.id }.distinct().joinToString(",")
                "$mainUrl/content/${primary.id}?related=$allIds"
            } else {
                "$mainUrl/content/${primary.id}"
            }

            when (primary.mediaType) {
                TvType.Anime, TvType.AnimeMovie, TvType.OVA -> {
                    results += newAnimeSearchResponse(primary.displayTitle, url, primary.mediaType) {
                        this.posterUrl = posterUrl
                        this.year = primary.year
                        this.id = primary.id
                        this.quality = intToSearchQuality(quality)
                        addDubStatus(dubExist = hasDub, subExist = hasSub)
                    }
                }
                TvType.TvSeries, TvType.AsianDrama, TvType.Cartoon -> {
                    results += newTvSeriesSearchResponse(primary.displayTitle, url, primary.mediaType) {
                        this.posterUrl = posterUrl
                        this.year = primary.year
                        this.id = primary.id
                        this.quality = intToSearchQuality(quality)
                    }
                }
                else -> {
                    results += newMovieSearchResponse(primary.displayTitle, url, primary.mediaType) {
                        this.posterUrl = posterUrl
                        this.year = primary.year
                        this.id = primary.id
                        this.quality = intToSearchQuality(quality)
                    }
                }
            }
        }
        return results
    }

    // ==============================
    // LOAD (METADATA ENRICHMENT)
    // ==============================
    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val primaryId = url.substringAfterLast("/").substringBefore("?").toIntOrNull()
            ?: throw ErrorLoadingException("Invalid URL: $url")

        val post = fetchPostDetails(primaryId)
        val resolvedType = inferTypeFromData(post.data)

        when (resolvedType) {
            TvType.Anime, TvType.AnimeMovie, TvType.OVA -> loadAnime(post, resolvedType)
            else -> loadStandard(post, resolvedType)
        }
    }

    private suspend fun loadAnime(post: FetchedPost, mediaType: TvType): LoadResponse = coroutineScope {
        val isMovie = mediaType == TvType.AnimeMovie || post.data.type == "singleVideo"
        val title = post.data.bestTitle()
        val normalized = normalizeTitle(post.data.title)
        val declaredSeason = normalized.season ?: 1

        // Resolve EXACT season metadata for accurate tracking IDs
        val animeMeta = resolveAnimeMetadataForSeason(
            title = title,
            year = post.data.year?.selectUntilNonInt(),
            season = declaredSeason
        )

        val displayTitle = animeMeta?.selectedMedia?.preferredTitle()?.ifBlank { title } ?: title
        val posterUrl = animeMeta?.posterUrl ?: buildPosterUrl(post.data.image)
        val backgroundUrl = animeMeta?.backgroundPosterUrl ?: posterUrl
        val logoUrl = animeMeta?.logoUrl

        if (isMovie) {
            val streamUrl = post.movieLink ?: throw ErrorLoadingException("No stream URL")
            return@coroutineScope newMovieLoadResponse(
                displayTitle, "$mainUrl/content/${post.id}", mediaType, streamUrl
            ) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backgroundUrl
                this.logoUrl = logoUrl
                this.plot = animeMeta?.selectedMedia?.cleanDescription() ?: post.data.metaData
                this.year = animeMeta?.selectedMedia?.resolvedYear() ?: post.data.year.selectUntilNonInt()
                this.duration = animeMeta?.selectedMedia?.duration
                this.tags = animeMeta?.selectedMedia?.genres?.filterNotNull()?.filter { it.isNotBlank() }
                addScore(animeMeta?.selectedMedia?.score())
                animeMeta?.selectedMedia?.id?.let { addAniListId(it) }
                animeMeta?.selectedMedia?.idMal?.let { addMalId(it) }
                animeMeta?.aniZip?.mappings?.kitsuId?.let { addKitsuId(it.toString()) }
                animeMeta?.aniZip?.mappings?.simklId?.let { addSimklId(it) }
                animeMeta?.aniZip?.mappings?.imdbId?.let { if (it.isNotBlank()) addImdbId(it) }
                animeMeta?.aniZip?.mappings?.tmdbId?.let { tmdb ->
                    tmdb.tv?.let { addTMDbId(it.toString()) } ?: tmdb.movie?.let { addTMDbId(it.toString()) }
                }
                animeMeta?.selectedMedia?.trailer?.fullUrl()?.let { addTrailer(it) }
            }
        }

        // TV Anime — enrich episodes with AniZip metadata
        val seasonSlices = discoverSeasons(post)
        val activeSeason = declaredSeason
        val activeSlices = seasonSlices.filter { it.globalSeason == activeSeason }

        val episodes = mergeEpisodes(activeSlices, activeSeason, animeMeta?.aniZip, null)

        val recommendations = animeMeta?.selectedMedia?.relations?.edges?.mapNotNull { edge ->
            val rec = edge.node ?: return@mapNotNull null
            newAnimeSearchResponse(rec.preferredTitle(), "$mainUrl/content/${post.id}?rec=${rec.id}", TvType.Anime) {
                this.posterUrl = rec.coverImage?.extraLarge ?: rec.coverImage?.large
                this.year = rec.resolvedYear()
            }
        } ?: emptyList()

        val hasDub = activeSlices.any { slice ->
            val tag = normalizeTitle(slice.fetchedPost.data.title).audioTag?.lowercase() ?: ""
            tag.contains("dub") || tag.contains("dual") || tag.contains("multi")
        }
        val hasSub = activeSlices.any { slice ->
            val tag = normalizeTitle(slice.fetchedPost.data.title).audioTag?.lowercase() ?: ""
            tag.contains("sub")
        } || !hasDub

        return@coroutineScope newAnimeLoadResponse(displayTitle, "$mainUrl/content/${post.id}", mediaType) {
            addEpisodes(DubStatus.Subbed, episodes)
            if (hasDub) addEpisodes(DubStatus.Dubbed, episodes)
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backgroundUrl
            this.logoUrl = logoUrl
            this.plot = animeMeta?.selectedMedia?.cleanDescription() ?: post.data.metaData
            this.year = animeMeta?.selectedMedia?.resolvedYear() ?: post.data.year.selectUntilNonInt()
            this.duration = animeMeta?.selectedMedia?.duration
            this.tags = animeMeta?.selectedMedia?.genres?.filterNotNull()?.filter { it.isNotBlank() }
            this.recommendations = recommendations
            this.engName = animeMeta?.selectedMedia?.title?.english
            this.japName = animeMeta?.selectedMedia?.title?.romaji ?: animeMeta?.selectedMedia?.title?.native
            addScore(animeMeta?.selectedMedia?.score())
            animeMeta?.selectedMedia?.id?.let { addAniListId(it) }
            animeMeta?.selectedMedia?.idMal?.let { addMalId(it) }
            animeMeta?.aniZip?.mappings?.kitsuId?.let { addKitsuId(it.toString()) }
            animeMeta?.aniZip?.mappings?.simklId?.let { addSimklId(it) }
            animeMeta?.aniZip?.mappings?.imdbId?.let { if (it.isNotBlank()) addImdbId(it) }
            animeMeta?.aniZip?.mappings?.tmdbId?.let { tmdb ->
                tmdb.tv?.let { addTMDbId(it.toString()) } ?: tmdb.movie?.let { addTMDbId(it.toString()) }
            }
            animeMeta?.selectedMedia?.trailer?.fullUrl()?.let { addTrailer(it) }
        }
    }

    private suspend fun loadStandard(post: FetchedPost, mediaType: TvType): LoadResponse = coroutineScope {
        val isSeries = !mediaType.isMovieType() && post.data.type == "series"
        val title = post.data.bestTitle()
        val normalized = normalizeTitle(post.data.title)
        val declaredSeason = normalized.season

        val tmdbMeta = resolveTmdbMetadata(
            mediaType = mediaType,
            queryTitles = listOf(title).filter { it.isNotBlank() },
            year = post.data.year?.selectUntilNonInt(),
            isSeries = isSeries
        )

        if (!isSeries) {
            val streamUrl = post.movieLink ?: throw ErrorLoadingException("No stream URL")
            return@coroutineScope newMovieLoadResponse(
                tmdbMeta?.displayTitle ?: title,
                "$mainUrl/content/${post.id}",
                mediaType,
                streamUrl
            ) {
                this.posterUrl = tmdbMeta?.posterUrl ?: buildPosterUrl(post.data.image)
                this.backgroundPosterUrl = tmdbMeta?.backgroundPosterUrl ?: this.posterUrl
                this.logoUrl = tmdbMeta?.logoUrl
                this.plot = tmdbMeta?.plot ?: post.data.metaData
                this.year = tmdbMeta?.year ?: post.data.year.selectUntilNonInt()
                this.duration = tmdbMeta?.duration ?: getDurationFromString(post.data.watchTime)
                this.tags = tmdbMeta?.genres
                addScore(tmdbMeta?.score)
                tmdbMeta?.imdbId?.let { addImdbId(it) }
                tmdbMeta?.tmdbId?.let { addTMDbId(it.toString()) }
                tmdbMeta?.actors?.let { addActors(it) }
                tmdbMeta?.trailerUrl?.let { addTrailer(it) }
            }
        }

        // TV Series
        val seasonSlices = discoverSeasons(post)
        val seasonNumbers = seasonSlices.map { it.globalSeason }.distinct().sorted()
        val activeSeason = declaredSeason ?: seasonNumbers.firstOrNull() ?: 1

        val tmdbSeasonEps = if (tmdbMeta?.tmdbId != null) {
            fetchTmdbSeasonDetails(tmdbMeta.tmdbId, activeSeason)
        } else null

        val episodes = mergeEpisodes(
            slices = seasonSlices.filter { it.globalSeason == activeSeason },
            seasonNumber = activeSeason,
            aniZip = null,
            tmdbEpisodes = tmdbSeasonEps
        )

        val seasonNames = seasonNumbers.map { num ->
            SeasonData(season = num, name = "Season $num", displaySeason = num)
        }

        return@coroutineScope newTvSeriesLoadResponse(
            tmdbMeta?.displayTitle ?: title,
            "$mainUrl/content/${post.id}",
            mediaType,
            episodes
        ) {
            this.posterUrl = tmdbMeta?.posterUrl ?: buildPosterUrl(post.data.image)
            this.backgroundPosterUrl = tmdbMeta?.backgroundPosterUrl ?: this.posterUrl
            this.logoUrl = tmdbMeta?.logoUrl
            this.plot = tmdbMeta?.plot ?: post.data.metaData
            this.year = tmdbMeta?.year ?: post.data.year.selectUntilNonInt()
            this.duration = tmdbMeta?.duration ?: getDurationFromString(post.data.watchTime)
            this.tags = tmdbMeta?.genres
            this.showStatus = tmdbMeta?.showStatus
            addSeasonNames(seasonNames)
            addScore(tmdbMeta?.score)
            tmdbMeta?.imdbId?.let { addImdbId(it) }
            tmdbMeta?.tmdbId?.let { addTMDbId(it.toString()) }
            tmdbMeta?.actors?.let { addActors(it) }
            tmdbMeta?.trailerUrl?.let { addTrailer(it) }
        }
    }

    // ==============================
    // LINKS (Direct Circle FTP)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = parseStreamPayload(data)
        if (payload != null && payload.variants.isNotEmpty()) {
            payload.variants.distinctBy { it.url }.forEach { variant ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = variant.label,
                        url = variant.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = inferExtractorQuality(variant.label)
                    }
                )
            }
            return true
        }
        // Fallback direct URL
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = data,
                type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }

    // ==============================
    // METADATA RESOLUTION
    // ==============================
    private suspend fun resolveAnimeMetadataForSeason(
        title: String,
        year: Int?,
        season: Int?
    ): AnimeMetaBundle? = coroutineScope {
        val franchiseTitle = normalizeTitle(title).franchiseTitle
        val searchResults = searchAniList(franchiseTitle)
        if (searchResults.isEmpty()) return@coroutineScope null

        val bestSeed = chooseBestAniListMatch(searchResults, listOf(franchiseTitle, title), year)
            ?: return@coroutineScope null

        val targetSeason = season ?: 1
        val selectedMedia = if (targetSeason > 1) {
            val chain = buildAniListSeasonChain(bestSeed.id ?: return@coroutineScope null)
            val chainIndex = targetSeason - 1
            when {
                chainIndex in chain.indices -> chain[chainIndex]
                else -> bestSeed // Fallback if chain shorter than season count
            }
        } else bestSeed

        val aniZipDeferred = async { fetchAniZip(selectedMedia.id ?: return@async null) }
        val aniZip = aniZipDeferred.await()

        AnimeMetaBundle(
            selectedMedia = selectedMedia,
            seasonChain = emptyList(),
            aniZip = aniZip,
            posterUrl = selectedMedia.coverImage?.extraLarge ?: selectedMedia.coverImage?.large,
            backgroundPosterUrl = selectedMedia.bannerImage ?: aniZip?.bestBackgroundUrl()
                ?: selectedMedia.coverImage?.extraLarge,
            logoUrl = aniZip?.bestLogoUrl()
        )
    }

    private suspend fun resolveTmdbMetadata(
        mediaType: TvType,
        queryTitles: List<String>,
        year: Int?,
        isSeries: Boolean
    ): TmdbMetadata? {
        val normalizedQueries = queryTitles.map { normalizeTitle(it).franchiseTitle }.filter { it.isNotBlank() }.distinct()
        if (normalizedQueries.isEmpty()) return null
        val bestMatch = searchTmdbBestMatch(normalizedQueries, year, isSeries) ?: return null
        val config = getTmdbConfig()

        return if (isSeries) {
            val detailText = app.get(
                "$tmdbApiBase/tv/${bestMatch.id}?api_key=$tmdbApiKey&language=en-US&append_to_response=videos,images,external_ids,credits,content_ratings",
                cacheTime = 43200
            ).text
            val detail = parseTmdbTvDetails(JSONObject(detailText))
            TmdbMetadata(
                tmdbId = detail.id,
                imdbId = detail.externalIds?.imdbId,
                displayTitle = detail.name ?: detail.originalName ?: bestMatch.displayTitle,
                posterUrl = buildTmdbImageUrl(config, detail.posterPath, TmdbImageKind.POSTER),
                backgroundPosterUrl = buildTmdbImageUrl(config, detail.backdropPath, TmdbImageKind.BACKDROP),
                logoUrl = buildTmdbLogoUrl(config, detail.images?.logos.orEmpty()),
                plot = detail.overview,
                year = detail.firstAirDate?.take(4)?.toIntOrNull(),
                duration = detail.episodeRunTime?.firstOrNull(),
                genres = detail.genres?.mapNotNull { it.name },
                score = Score.from10(detail.voteAverage),
                actors = detail.credits?.cast.orEmpty().mapNotNull { cast ->
                    cast.name?.let { actorName ->
                        Actor(
                            actorName,
                            buildTmdbImageUrl(config, cast.profilePath, TmdbImageKind.PROFILE)
                        ) to cast.character
                    }
                },
                trailerUrl = pickTmdbTrailerUrl(detail.videos?.results.orEmpty()),
                showStatus = detail.status.toTmdbShowStatus()
            )
        } else {
            val detailText = app.get(
                "$tmdbApiBase/movie/${bestMatch.id}?api_key=$tmdbApiKey&language=en-US&append_to_response=videos,images,external_ids,credits,release_dates",
                cacheTime = 43200
            ).text
            val detail = parseTmdbMovieDetails(JSONObject(detailText))
            TmdbMetadata(
                tmdbId = detail.id,
                imdbId = detail.externalIds?.imdbId,
                displayTitle = detail.title ?: detail.originalTitle ?: bestMatch.displayTitle,
                posterUrl = buildTmdbImageUrl(config, detail.posterPath, TmdbImageKind.POSTER),
                backgroundPosterUrl = buildTmdbImageUrl(config, detail.backdropPath, TmdbImageKind.BACKDROP),
                logoUrl = buildTmdbLogoUrl(config, detail.images?.logos.orEmpty()),
                plot = detail.overview,
                year = detail.releaseDate?.take(4)?.toIntOrNull(),
                duration = detail.runtime,
                genres = detail.genres?.mapNotNull { it.name },
                score = Score.from10(detail.voteAverage),
                actors = detail.credits?.cast.orEmpty().mapNotNull { cast ->
                    cast.name?.let { actorName ->
                        Actor(
                            actorName,
                            buildTmdbImageUrl(config, cast.profilePath, TmdbImageKind.PROFILE)
                        ) to cast.character
                    }
                },
                trailerUrl = pickTmdbTrailerUrl(detail.videos?.results.orEmpty()),
                showStatus = null
            )
        }
    }

    private suspend fun searchTmdbBestMatch(
        queryTitles: List<String>,
        year: Int?,
        isSeries: Boolean
    ): TmdbSearchResult? = coroutineScope {
        val endpoint = if (isSeries) "tv" else "movie"
        val candidates = queryTitles.take(3).map { query ->
            async {
                val url = "$tmdbApiBase/search/$endpoint?api_key=$tmdbApiKey&language=en-US&query=${urlEncode(query)}" +
                        if (year != null) (if (isSeries) "&first_air_date_year=$year" else "&year=$year") else ""
                val text = app.get(url, cacheTime = 43200).text
                parseTmdbSearchPage(JSONObject(text)).results
            }
        }.awaitAll().flatten()

        candidates.maxByOrNull { scoreTmdbCandidate(it, queryTitles, year) }
    }

    private suspend fun fetchTmdbSeasonDetails(tmdbId: Int, seasonNumber: Int): List<TmdbEpisode>? {
        return try {
            app.get(
                "$tmdbApiBase/tv/$tmdbId/season/$seasonNumber?api_key=$tmdbApiKey&language=en-US",
                cacheTime = 43200
            ).parsedSafe<TmdbSeasonDetails>()?.episodes
        } catch (e: Exception) {
            null
        }
    }

    // ==============================
    // ANILIST HELPERS
    // ==============================
    private suspend fun searchAniList(title: String): List<<AniListMedia> {
        val query = """
            query (${'$'}search: String) {
              Page(page: 1, perPage: 10) {
                media(search: ${'$'}search, type: ANIME, sort: [SEARCH_MATCH, POPULARITY_DESC]) {
                   id idMal title { romaji english native userPreferred } synonyms format status seasonYear
                  startDate { year month day } episodes duration averageScore description(asHtml: false)
                  bannerImage genres trailer { id site thumbnail }
                  coverImage { extraLarge large }
                  relations { edges { relationType node {  id idMal format seasonYear startDate { year month day }
                    title { romaji english native userPreferred } synonyms bannerImage coverImage { extraLarge large } } } }
                 }
              }
            }
        """.trimIndent()
        val body = JSONObject().apply {
            put("query", query)
            put("variables", JSONObject().apply { put("search", title) })
        }
        val text = app.post(aniListGraphQlUrl, json = body.toString(), cacheTime = 43200).text
        return parseAniListSearchResponse(JSONObject(text)).data?.page?.media.orEmpty()
    }

    private suspend fun fetchAniListById(id: Int): AniListMedia? {
        aniListMediaCache[id]?.let { return it }
        val query = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id idMal title { romaji english native userPreferred } synonyms format status seasonYear
                 startDate { year month day } episodes duration averageScore description(asHtml: false)
                bannerImage genres trailer { id site thumbnail } coverImage { extraLarge large }
                relations { edges { relationType node { id idMal format seasonYear startDate { year month day }
                  title { romaji english native userPreferred }  synonyms bannerImage coverImage { extraLarge large } } } }
              }
            }
        """.trimIndent()
        val body = JSONObject().apply {
            put("query", query)
            put("variables", JSONObject().apply { put("id", id) })
        }
        val text = app.post(aniListGraphQlUrl, json = body.toString(), cacheTime = 43200).text
        val media = parseAniListByIdResponse(JSONObject(text)).data?.media
        if (media != null) aniListMediaCache[id] = media
        return media
    }

    private suspend fun buildAniListSeasonChain(seedId: Int): List<<AniListMedia> {
        val visited = linkedSetOf<Int>()
        var earliest = fetchAniListById(seedId) ?: return emptyList()

        // Walk backwards through prequels
        while (true) {
            val prequel = earliest.relations?.edges.orEmpty()
                .mapNotNull { if (it.relationType.equals("PREQUEL", ignoreCase = true)) it.node else null }
                .firstOrNull { it.id != null && isMainlineAnimeFormat(it.format) && !visited.contains(it.id!!) }
                ?: break
            val prequelMedia = fetchAniListById(prequel.id!!) ?: break
            if (prequelMedia.id == earliest.id) break
            earliest = prequelMedia
        }

        val chain = mutableListOf<<AniListMedia>()
        var cursor: AniListMedia? = earliest
        while (cursor != null) {
            val cursorId = cursor.id ?: break
            if (!visited.add(cursorId)) break
            chain += cursor
            val sequel = cursor.relations?.edges.orEmpty()
                .mapNotNull { if (it.relationType.equals("SEQUEL", ignoreCase = true)) it.node else null }
                .firstOrNull { it.id != null && isMainlineAnimeFormat(it.format) && !visited.contains(it.id!!) }
            cursor = sequel?.id?.let { fetchAniListById(it) }
        }
        return chain
    }

    private suspend fun fetchAniZip(anilistId: Int): AniZipResponse? {
        val text = app.get("$aniZipUrl?anilist_id=$anilistId", cacheTime = 43200).text
        return parseAniZipResponse(JSONObject(text))
    }

    // ==============================
    // POST FETCHING & EPISODE MERGE
    // ==============================
    private suspend fun fetchPostDetails(id: Int): FetchedPost {
        val response = apiGet("/api/posts/$id", cacheTime = 60)
        val data = response.parsedSafe<Data>() ?: throw ErrorLoadingException("Invalid post data")
        return if (data.type == "singleVideo") {
            val movieData = response.parsedSafe<Movies>()
            FetchedPost(id = id, data = data, movieLink = movieData?.content, tvSeries = null)
        } else {
            FetchedPost(id = id, data = data, movieLink = null, tvSeries = response.parsedSafe<TvSeries>())
        }
    }

    private fun discoverSeasons(fetchedPost: FetchedPost): List<<SeasonSlice> {
        val tvSeries = fetchedPost.tvSeries ?: return emptyList()
        val declaredSeason = normalizeTitle(fetchedPost.data.title).season ?: 1
        return tvSeries.content.mapIndexedNotNull { index, seasonContent ->
            val seasonFromFolder = parseSeasonNumber(seasonContent.seasonName)
            val globalSeason = when {
                seasonFromFolder != null -> seasonFromFolder
                tvSeries.content.size == 1 -> declaredSeason
                else -> declaredSeason + index
            }
            SeasonSlice(
                fetchedPost = fetchedPost,
                globalSeason = globalSeason,
                seasonName = seasonContent.seasonName,
                episodes = seasonContent.episodes
            )
        }
    }

    private fun mergeEpisodes(
        slices: List<<SeasonSlice>,
        seasonNumber: Int?,
        aniZip: AniZipResponse?,
        tmdbEpisodes: List<TmdbEpisode>?
    ): List<<Episode> {
        val episodeMap = linkedMapOf<String, EpisodeAccumulator>()
        slices.forEach { slice ->
            slice.episodes.forEachIndexed { index, epData ->
                val epNum = extractEpisodeNumber(epData.title) ?: index + 1
                val season = seasonNumber ?: slice.globalSeason
                val key = "$season-$epNum"
                val acc = episodeMap.getOrPut(key) {
                    EpisodeAccumulator(
                        season = season,
                        episode = epNum,
                        fallbackName = cleanupEpisodeTitle(epData.title, epNum)
                    )
                }
                val audioTag = normalizeTitle(slice.fetchedPost.data.title).audioTag
                val label = if (audioTag != null) "FTP [$audioTag]" else "FTP"
                acc.sources += StreamVariant(label = label, url = epData.link)
            }
        }
        return episodeMap.values.sortedWith(compareBy({ it.season }, { it.episode })).map { acc ->
            val aniZipEp = aniZip?.episodes?.get(acc.episode.toString())
            val tmdbEp = tmdbEpisodes?.find { it.episodeNumber == acc.episode }

            newEpisode(StreamPayload(variants = acc.sources.distinctBy { it.url }).toJsonString()) {
                this.season = acc.season
                this.episode = acc.episode
                this.name = aniZipEp?.preferredTitle() ?: tmdbEp?.name ?: acc.fallbackName
                this.description = aniZipEp?.overview ?: aniZipEp?.summary ?: tmdbEp?.overview
                this.posterUrl = aniZipEp?.image ?: tmdbEp?.stillPath?.let { "https://image.tmdb.org/t/p/original$it" }
                this.score = Score.from10(aniZipEp?.rating)
                this.runTime = ((aniZipEp?.runtime ?: aniZipEp?.length)?.times(60)) ?: tmdbEp?.runtime
                addDate(aniZipEp?.airDateUtc ?: aniZipEp?.airDate ?: tmdbEp?.airDate)
            }
        }
    }

    // ==============================
    // TMDB HELPERS
    // ==============================
    private suspend fun getTmdbConfig(): TmdbConfiguration {
        tmdbConfigCache?.let { return it }
        val text = app.get("$tmdbApiBase/configuration?api_key=$tmdbApiKey", cacheTime = 86400).text
        val config = parseTmdbConfiguration(JSONObject(text))
        tmdbConfigCache = config
        return config
    }

    private fun buildTmdbImageUrl(config: TmdbConfiguration, path: String?, kind: TmdbImageKind): String? {
        if (path.isNullOrBlank()) return null
        val baseUrl = config.images?.secureBaseUrl ?: "https://image.tmdb.org/t/p/"
        val size = when (kind) {
            TmdbImageKind.POSTER -> config.images?.posterSizes?.lastOrNull() ?: "original"
            TmdbImageKind.BACKDROP -> config.images?.backdropSizes?.lastOrNull() ?: "original"
            TmdbImageKind.LOGO -> config.images?.logoSizes?.lastOrNull() ?: "original"
            TmdbImageKind.PROFILE -> config.images?.profileSizes?.lastOrNull() ?: "original"
        }
        return "$baseUrl$size$path"
    }

    private fun buildTmdbLogoUrl(config: TmdbConfiguration, logos: List<TmdbLogo>): String? {
        val logo = logos.sortedWith(
            compareByDescending<TmdbLogo> { if (it.iso6391 == "en") 1 else 0 }
                .thenByDescending { it.voteAverage ?: 0.0 }
                .thenByDescending { it.width ?: 0 }
        ).firstOrNull() ?: return null
        return buildTmdbImageUrl(config, logo.filePath, TmdbImageKind.LOGO)
    }

    private fun pickTmdbTrailerUrl(videos: List<TmdbVideo>): String? {
        val video = videos.sortedWith(
            compareByDescending<TmdbVideo> { if (it.official == true) 1 else 0 }
                .thenByDescending { if (it.type.equals("Trailer", ignoreCase = true)) 1 else 0 }
                .thenByDescending { if (it.type.equals("Teaser", ignoreCase = true)) 1 else 0 }
        ).firstOrNull { it.site.equals("YouTube", ignoreCase = true) && !it.key.isNullOrBlank() } ?: return null
        return "https://www.youtube.com/watch?v=${video.key}"
    }

    private fun scoreTmdbCandidate(candidate: TmdbSearchResult, queryTitles: List<String>, year: Int?): Int {
        val candidateTitle = normalizeTitle(candidate.displayTitle).franchiseTitle
        val candidateYear = candidate.year
        val titleScore = queryTitles.maxOfOrNull { tokenScore(candidateTitle, normalizeTitle(it).franchiseTitle) } ?: 0
        val yearScore = when {
            year == null || candidateYear == null -> 0
            year == candidateYear -> 25
            abs(year - candidateYear) <= 1 -> 10
            else -> -10
        }
        return titleScore + yearScore
    }

    // ==============================
    // FAST TYPE INFERENCE
    // ==============================
    private fun inferTypeFast(post: Post, normalized: NormalizedTitle): TvType {
        val titleCheck = post.title.lowercase()
        return when {
            titleCheck.contains("anime") -> TvType.Anime
            titleCheck.contains("ova") -> TvType.OVA
            post.type == "series" -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun inferTypeFromData(data: Data): TvType {
        val titleCheck = data.title.lowercase()
        return when {
            titleCheck.contains("anime") -> TvType.Anime
            titleCheck.contains("ova") -> TvType.OVA
            data.type == "series" -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun isMainlineAnimeFormat(format: String?): Boolean {
        return when (format?.uppercase()) { "TV", "TV_SHORT", "ONA", "OVA", "MOVIE" -> true; else -> false }
    }

    // ==============================
    // HELPERS
    // ==============================
    private fun buildPosterUrl(imagePath: String?): String? {
        return imagePath?.takeIf { it.isNotBlank() }?.let { "$mainApiUrl/uploads/$it" }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun String?.selectUntilNonInt(): Int? = this?.let {
        Regex("^.*?(?=\\D|$)").find(it)?.value?.toIntOrNull()
    }

    private fun getDurationFromString(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val minutes = Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.toIntOrNull()
        return minutes
    }

    private fun inferExtractorQuality(label: String): Int {
        val lower = label.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> 2160
            lower.contains("1440") -> 1440
            lower.contains("1080") -> 1080
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            else -> 0
        }
    }

    private fun tokenScore(left: String, right: String): Int {
        val leftTokens = left.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
        val rightTokens = right.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return if (left.equals(right, ignoreCase = true)) 100 else 0
        val overlap = leftTokens.intersect(rightTokens).size
        val exactBonus = if (left.equals(right, ignoreCase = true)) 100 else 0
        return exactBonus + (overlap * 20) - abs(leftTokens.size - rightTokens.size) * 4
    }

    private fun chooseBestAniListMatch(candidates: List<<AniListMedia>, queryTitles: List<String>, year: Int?): AniListMedia? {
        return candidates.maxByOrNull { scoreAniListCandidate(it, queryTitles, year) }
    }

    private fun scoreAniListCandidate(candidate: AniListMedia, queryTitles: List<String>, year: Int?): Int {
        val titles = candidate.allTitles()
        val candidateYear = candidate.resolvedYear()
        val titleScore = queryTitles.maxOfOrNull { queryTitle ->
            titles.maxOfOrNull { tokenScore(normalizeTitle(queryTitle).franchiseTitle, normalizeTitle(it).franchiseTitle) } ?: 0
        } ?: 0
        val yearScore = when {
            year == null || candidateYear == null -> 0
            year == candidateYear -> 25
            abs(year - candidateYear) <= 1 -> 10
            else -> -10
        }
        return titleScore + yearScore
    }

    private fun String?.toTmdbShowStatus(): ShowStatus? = when (this?.uppercase()) {
        "ENDED" -> ShowStatus.Completed
        "RETURNING SERIES", "IN PRODUCTION", "PLANNED", "PILOT" -> ShowStatus.Ongoing
        else -> null
    }

    private fun Data.bestTitle(): String = this.name?.takeIf { it.isNotBlank() } ?: this.title

    private fun AniListMedia.preferredTitle(): String = this.title?.english ?: this.title?.romaji ?: this.title?.native ?: this.title?.userPreferred ?: "Unknown Anime"
    private fun AniListMedia.cleanDescription(): String? = this.description?.replace("<br>", "\n", ignoreCase = true)?.replace(Regex("<[^>]+>"), "")?.trim()
    private fun AniListMedia.allTitles(): List<String> = buildList {
        title?.english?.takeIf { it.isNotBlank() }?.let { add(it) }
        title?.romaji?.takeIf { it.isNotBlank() }?.let { add(it) }
        title?.native?.takeIf { it.isNotBlank() }?.let { add(it) }
        title?.userPreferred?.takeIf { it.isNotBlank() }?.let { add(it) }
        synonyms.orEmpty().forEach { if (!it.isNullOrBlank()) add(it) }
    }.distinct()
    private fun AniListMedia.resolvedYear(): Int? = seasonYear ?: startDate?.year
    private fun AniListMedia.score(): Score? = Score.from100(this.averageScore)
    private fun AniListTrailer.fullUrl(): String? = id?.let { when (site?.lowercase()) { "youtube" -> "https://www.youtube.com/watch?v=$it"; else -> null } }
    private fun AniZipResponse.bestLogoUrl(): String? = images.orEmpty().firstOrNull { it.coverType.equals("Clearlogo", ignoreCase = true) }?.url
    private fun AniZipResponse.bestBackgroundUrl(): String? = images.orEmpty().firstOrNull {
        it.coverType.equals("Fanart", ignoreCase = true) || it.coverType.equals("Banner", ignoreCase = true)
    }?.url
    private fun AniZipEpisode.preferredTitle(): String? = this.title?.en ?: this.title?.ja ?: this.title?.xJat

    // ==============================
    // TITLE NORMALIZATION (CLEAN)
    // ==============================
    private fun normalizeTitle(rawTitle: String): NormalizedTitle {
        val raw = rawTitle.replace('_', ' ').replace('.', ' ').replace(Regex("\\s+"), " ").trim()

        val audioTag = when {
            Regex("dual\\s*audio", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Dual-Audio"
            Regex("multi\\s*audio", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Multi-Audio"
            Regex("subbed", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Subbed"
            Regex("dubbed", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Dubbed"
            Regex("hindi", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Hindi"
            Regex("english", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "English"
            else -> null
        }

        val quality = getSearchQualityInt(raw)
        val qualityTag = Regex("(2160p|1080p|720p|480p|4k|bluray|blu-ray|web-dl|webrip|hdrip|hdr|uhd|sdr)", RegexOption.IGNORE_CASE).find(raw)?.value?.uppercase()

        val year = Regex("(?<!\\d)(19\\d{2}|20\\d{2}|21\\d{2})(?!\\d)").find(raw)?.value?.toIntOrNull()
        val season = parseSeasonNumber(raw)

        val noiseRegex = Regex("""
            (?ix)
            (\[.*?(dual\s*audio|multi\s*audio|dubbed|subbed|bluray|blu-ray|web[- ]?dl|webrip|hdrip|2160p|1080p|720p|480p|x264|x265|hevc|hdr|sdr|hindi|english).*?])|
             (\(.*?(dual\s*audio|multi\s*audio|dubbed|subbed|bluray|blu-ray|web[- ]?dl|webrip|hdrip|2160p|1080p|720p|480p|x264|x265|hevc|hdr|sdr|hindi|english).*?\))|
            \b(?:bluray|blu-ray|web[- ]?dl|webrip|hdrip|brrip|dvdrip|2160p|1080p|720p|480p|4k|x264|x265|h264|h265|hevc|aac|dts|ddp5\.1|dual\s*audio|multi\s*audio|dubbed|subbed|hindi|english|multi|audio|hdr|sdr)\b
        """.trimIndent())

        val cleanedDisplay = noiseRegex.replace(raw, " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\[\\](){}]"), " ")
            .replace(Regex("\\b(19\\d{2}|20\\d{2}|21\\d{2})\\b"), " ")
            .replace(Regex("(?:season|s)\\s*0*(\\d{1,2})", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(\\d{1,2})(?:st|nd|rd|th)?\\s*season", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("part\\s*0*(\\d{1,2})", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("cour\\s*0*(\\d{1,2})", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val franchiseTitle = cleanedDisplay
            .replace(Regex("\\b(19\\d{2}|20\\d{2}|21\\d{2})\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { cleanedDisplay.ifBlank { raw } }

        return NormalizedTitle(
            cleanTitle = cleanedDisplay.ifBlank { raw },
            franchiseTitle = franchiseTitle,
            year = year,
            season = season,
            quality = quality,
            qualityTag = qualityTag,
            audioTag = audioTag
        )
    }

    // FIXED: Returns Int? instead of local SearchQuality enum
    private fun getSearchQualityInt(check: String?): Int? {
        val lower = check?.lowercase() ?: return null
        return when {
            lower.contains("2160p") || lower.contains("4k") -> 2160
            lower.contains("1440p") -> 1440
            lower.contains("1080p") -> 1080
            lower.contains("720p") -> 720
            lower.contains("480p") -> 480
            else -> null
        }
    }

    // HELPER: Convert Int quality to CloudStream's SearchQuality enum
    private fun intToSearchQuality(quality: Int?): SearchQuality? {
        return when (quality) {
            2160 -> SearchQuality.UHD
            1440, 1080 -> SearchQuality.HD
            720 -> SearchQuality.HQ
            480 -> SearchQuality.SD
            else -> null
        }
    }

    private fun parseSeasonNumber(value: String?): Int? {
        val cleaned = value.orEmpty()
        listOf(
            Regex("(?:season|s)\\s*0*(\\d{1,2})\\b", RegexOption.IGNORE_CASE),
            Regex("(\\d{1,2})(?:st|nd|rd|th)?\\s*season\\b", RegexOption.IGNORE_CASE),
            Regex("part\\s*0*(\\d{1,2})\\b", RegexOption.IGNORE_CASE),
            Regex("cour\\s*0*(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
        ).forEach { regex -> regex.find(cleaned)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it } }
        return null
    }

    private fun extractEpisodeNumber(title: String?): Int? {
        val cleaned = title.orEmpty()
        listOf(
            Regex("(?:episode|ep|e)\\s*0*(\\d{1,4})\\b", RegexOption.IGNORE_CASE),
            Regex("(?:^|\\D)(\\d{1,4})(?:\\D|$)")
        ).forEach { regex -> regex.find(cleaned)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it } }
        return null
    }

    private fun cleanupEpisodeTitle(title: String?, episodeNumber: Int): String {
        val cleaned = title.orEmpty()
            .replace(Regex("(?:episode|ep)\\s*0*$episodeNumber\\s*[:-]?", RegexOption.IGNORE_CASE), " ")
            .replace('_', ' ').replace('.', ' ')
            .replace(Regex("\\s+"), " ").trim()
        return if (cleaned.isBlank()) "Episode $episodeNumber" else cleaned
    }

    private suspend fun apiGet(path: String, cacheTime: Int) = try {
        app.get("$mainApiUrl$path", verify = false, cacheTime = cacheTime)
    } catch (_: Exception) {
        app.get("$fallbackApiUrl$path", verify = false, cacheTime = cacheTime)
    }

    // ==============================
    // JSON PARSERS
    // ==============================
    private fun parseStreamPayload(jsonStr: String): StreamPayload? {
        return runCatching {
            val json = JSONObject(jsonStr)
            val variantsArray = json.getJSONArray("variants")
            val variants = mutableListOf<<StreamVariant>()
            for (i in 0 until variantsArray.length()) {
                val obj = variantsArray.getJSONObject(i)
                variants += StreamVariant(label = obj.getString("label"), url = obj.getString("url"))
            }
            StreamPayload(variants = variants)
        }.getOrNull()
    }

    private fun parseTmdbConfiguration(json: JSONObject): TmdbConfiguration {
        val imagesObj = json.optJSONObject("images")
        return TmdbConfiguration(images = imagesObj?.let {
            TmdbImageConfig(
                secureBaseUrl = it.optString("secure_base_url", " ").takeIf { s -> s.isNotEmpty() },
                posterSizes = it.optJSONArray("poster_sizes")?.let { arr -> (0 until arr.length()).map { i -> arr.getString(i) } },
                backdropSizes = it.optJSONArray("backdrop_sizes")?.let { arr -> (0 until arr.length()).map { i -> arr.getString(i) } },
                logoSizes = it.optJSONArray("logo_sizes")?.let { arr -> (0 until arr.length()).map { i -> arr.getString(i) } },
                profileSizes = it.optJSONArray("profile_sizes")?.let { arr -> (0 until arr.length()).map { i -> arr.getString(i) } }
            )
        })
    }

    private fun parseTmdbSearchPage(json: JSONObject): TmdbSearchPage {
        val resultsArray = json.optJSONArray("results") ?: return TmdbSearchPage()
        return TmdbSearchPage(results = (0 until resultsArray.length()).map { i ->
            val obj = resultsArray.getJSONObject(i)
            TmdbSearchResult(
                id = obj.optInt("id", 0).takeIf { it > 0 },
                title = obj.optString("title", " ").takeIf { it.isNotEmpty() },
                name = obj.optString("name", " ").takeIf { it.isNotEmpty() },
                originalTitle = obj.optString("original_title", " ").takeIf { it.isNotEmpty() },
                originalName = obj.optString("original_name", " ").takeIf { it.isNotEmpty() },
                releaseDate = obj.optString("release_date", " ").takeIf { it.isNotEmpty() },
                firstAirDate = obj.optString("first_air_date", " ").takeIf { it.isNotEmpty() }
            )
        })
    }

    private fun parseTmdbTvDetails(json: JSONObject): TmdbTvDetails {
        return TmdbTvDetails(
            id = json.optInt("id", 0).takeIf { it > 0 },
            name = json.optString("name", " ").takeIf { it.isNotEmpty() },
            originalName = json.optString("original_name", " ").takeIf { it.isNotEmpty() },
            overview = json.optString("overview", " ").takeIf { it.isNotEmpty() },
            posterPath = json.optString("poster_path", " ").takeIf { it.isNotEmpty() },
            backdropPath = json.optString("backdrop_path", " ").takeIf { it.isNotEmpty() },
            firstAirDate = json.optString("first_air_date", " ").takeIf { it.isNotEmpty() },
            episodeRunTime = json.optJSONArray("episode_run_time")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } },
            voteAverage = json.optDouble("vote_average").takeIf { !it.isNaN() },
            genres = json.optJSONArray("genres")?.let { arr -> (0 until arr.length()).map { TmdbGenre(name = arr.getJSONObject(it).optString("name", " ").takeIf { n -> n.isNotEmpty() }) } },
            videos = parseTmdbVideos(json.optJSONObject("videos")),
            images = parseTmdbImages(json.optJSONObject("images")),
            externalIds = parseTmdbExternalIds(json.optJSONObject("external_ids")),
            credits = parseTmdbCredits(json.optJSONObject("credits")),
            status = json.optString("status", " ").takeIf { it.isNotEmpty() }
        )
    }

    private fun parseTmdbMovieDetails(json: JSONObject): TmdbMovieDetails {
        return TmdbMovieDetails(
            id = json.optInt("id", 0).takeIf { it > 0 },
            title = json.optString("title", " ").takeIf { it.isNotEmpty() },
            originalTitle = json.optString("original_title", " ").takeIf { it.isNotEmpty() },
            overview = json.optString("overview", " ").takeIf { it.isNotEmpty() },
            posterPath = json.optString("poster_path", " ").takeIf { it.isNotEmpty() },
            backdropPath = json.optString("backdrop_path", " ").takeIf { it.isNotEmpty() },
            releaseDate = json.optString("release_date", " ").takeIf { it.isNotEmpty() },
            runtime = json.optInt("runtime", 0).takeIf { it > 0 },
            voteAverage = json.optDouble("vote_average").takeIf { !it.isNaN() },
            genres = json.optJSONArray("genres")?.let { arr -> (0 until arr.length()).map { TmdbGenre(name = arr.getJSONObject(it).optString("name", " ").takeIf { n -> n.isNotEmpty() }) } },
            videos = parseTmdbVideos(json.optJSONObject("videos")),
            images = parseTmdbImages(json.optJSONObject("images")),
            externalIds = parseTmdbExternalIds(json.optJSONObject("external_ids")),
            credits = parseTmdbCredits(json.optJSONObject("credits"))
        )
    }

    private fun parseTmdbVideos(json: JSONObject?): TmdbVideos? {
        if (json == null) return null
        val resultsArray = json.optJSONArray("results") ?: return TmdbVideos()
        return TmdbVideos(results = (0 until resultsArray.length()).map { i ->
            val obj = resultsArray.getJSONObject(i)
            TmdbVideo(
                key = obj.optString("key", " ").takeIf { it.isNotEmpty() },
                site = obj.optString("site", " ").takeIf { it.isNotEmpty() },
                type = obj.optString("type", " ").takeIf { it.isNotEmpty() },
                official = obj.optBoolean("official")
            )
        })
    }

    private fun parseTmdbImages(json: JSONObject?): TmdbImages? {
        if (json == null) return null
        val logosArray = json.optJSONArray("logos") ?: return TmdbImages()
        return TmdbImages(logos = (0 until logosArray.length()).map { i ->
            val obj = logosArray.getJSONObject(i)
            TmdbLogo(
                filePath = obj.optString("file_path", " ").takeIf { it.isNotEmpty() },
                iso6391 = obj.optString("iso_639_1", " ").takeIf { it.isNotEmpty() },
                voteAverage = obj.optDouble("vote_average").takeIf { !it.isNaN() },
                width = obj.optInt("width", 0).takeIf { it > 0 }
            )
        })
    }

    private fun parseTmdbExternalIds(json: JSONObject?): TmdbExternalIds? {
        return json?.optString("imdb_id", " ")?.takeIf { it.isNotEmpty() }?.let { TmdbExternalIds(imdbId = it) }
    }

    private fun parseTmdbCredits(json: JSONObject?): TmdbCredits? {
        if (json == null) return null
        val castArray = json.optJSONArray("cast") ?: return TmdbCredits()
        return TmdbCredits(cast = (0 until castArray.length()).map { i ->
            val obj = castArray.getJSONObject(i)
            TmdbCast(
                name = obj.optString("name", " ").takeIf { it.isNotEmpty() },
                character = obj.optString("character", " ").takeIf { it.isNotEmpty() },
                profilePath = obj.optString("profile_path", " ").takeIf { it.isNotEmpty() }
            )
        })
    }

    private fun parseAniListSearchResponse(json: JSONObject): AniListSearchResponse {
        val dataObj = json.optJSONObject("data") ?: return AniListSearchResponse()
        val pageObj = dataObj.optJSONObject("Page") ?: return AniListSearchResponse()
        val mediaArray = pageObj.optJSONArray("media") ?: return AniListSearchResponse()
        return AniListSearchResponse(
            data = AniListSearchData(
                page = AniListPage(media = (0 until mediaArray.length()).map { parseAniListMedia(mediaArray.getJSONObject(it)) })
            )
        )
    }

    private fun parseAniListByIdResponse(json: JSONObject): AniListByIdResponse {
        return AniListByIdResponse(
            data = AniListByIdData(media = json.optJSONObject("data")?.optJSONObject("Media")?.let { parseAniListMedia(it) })
        )
    }

    private fun parseAniListMedia(json: JSONObject): AniListMedia {
        return AniListMedia(
            id = json.optInt("id", 0).takeIf { it > 0 },
            idMal = json.optInt("idMal", 0).takeIf { it > 0 },
            title = json.optJSONObject("title")?.let {
                AniListTitle(
                    romaji = it.optString("romaji", " ").takeIf { s -> s.isNotEmpty() },
                    english = it.optString("english", " ").takeIf { s -> s.isNotEmpty() },
                    native = it.optString("native", " ").takeIf { s -> s.isNotEmpty() },
                    userPreferred = it.optString("userPreferred", " ").takeIf { s -> s.isNotEmpty() }
                )
            },
            synonyms = json.optJSONArray("synonyms")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } },
            format = json.optString("format", " ").takeIf { it.isNotEmpty() },
            status = json.optString("status", " ").takeIf { it.isNotEmpty() },
            seasonYear = json.optInt("seasonYear", 0).takeIf { it > 0 },
            startDate = json.optJSONObject("startDate")?.let {
                AniListDate(
                    year = it.optInt("year", 0).takeIf { y -> y > 0 },
                    month = it.optInt("month", 0).takeIf { y -> y > 0 },
                    day = it.optInt("day", 0).takeIf { y -> y > 0 }
                )
            },
            episodes = json.optInt("episodes", 0).takeIf { it > 0 },
            duration = json.optInt("duration", 0).takeIf { it > 0 },
            averageScore = json.optInt("averageScore", 0).takeIf { it > 0 },
            description = json.optString("description", " ").takeIf { it.isNotEmpty() },
            bannerImage = json.optString("bannerImage", " ").takeIf { it.isNotEmpty() },
            genres = json.optJSONArray("genres")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } },
            trailer = json.optJSONObject("trailer")?.let {
                AniListTrailer(
                    id = it.optString("id", " ").takeIf { s -> s.isNotEmpty() },
                    site = it.optString("site", " ").takeIf { s -> s.isNotEmpty() },
                    thumbnail = it.optString("thumbnail", " ").takeIf { s -> s.isNotEmpty() }
                )
            },
            coverImage = json.optJSONObject("coverImage")?.let {
                AniListCoverImage(
                    extraLarge = it.optString("extraLarge", " ").takeIf { s -> s.isNotEmpty() },
                    large = it.optString("large", " ").takeIf { s -> s.isNotEmpty() }
                )
            },
            relations = json.optJSONObject("relations")?.let { relObj ->
                val edgesArray = relObj.optJSONArray("edges") ?: return@let null
                AniListRelations(edges = (0 until edgesArray.length()).map { i ->
                    val edgeObj = edgesArray.getJSONObject(i)
                    AniListRelationEdge(
                        relationType = edgeObj.optString("relationType", " "),
                        node = edgeObj.optJSONObject("node")?.let { parseAniListMedia(it) } ?: AniListMedia()
                    )
                })
            }
        )
    }

    private fun parseAniZipResponse(json: JSONObject): AniZipResponse {
        return AniZipResponse(
            images = json.optJSONArray("images")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    AniZipImage(
                        coverType = obj.optString("coverType", " ").takeIf { it.isNotEmpty() },
                        url = obj.optString("url", " ").takeIf { it.isNotEmpty() }
                    )
                }
            },
            episodes = json.optJSONObject("episodes")?.let { epObj ->
                val map = mutableMapOf<String, AniZipEpisode>()
                epObj.keys().forEach { key ->
                    val ep = epObj.getJSONObject(key)
                    map[key] = AniZipEpisode(
                        title = ep.optJSONObject("title")?.let {
                            AniZipEpisodeTitle(
                                en = it.optString("en", " ").takeIf { s -> s.isNotEmpty() },
                                ja = it.optString("ja", " ").takeIf { s -> s.isNotEmpty() },
                                xJat = it.optString("x-jat", " ").takeIf { s -> s.isNotEmpty() }
                            )
                        },
                        overview = ep.optString("overview", " ").takeIf { it.isNotEmpty() },
                        summary = ep.optString("summary", " ").takeIf { it.isNotEmpty() },
                        image = ep.optString("image", " ").takeIf { it.isNotEmpty() },
                        runtime = ep.optInt("runtime", 0).takeIf { it > 0 },
                        length = ep.optInt("length", 0).takeIf { it > 0 },
                        airDate = ep.optString("airDate", " ").takeIf { it.isNotEmpty() },
                        airDateUtc = ep.optString("airDateUtc", " ").takeIf { it.isNotEmpty() },
                        rating = ep.optString("rating", " ").takeIf { it.isNotEmpty() }
                    )
                }
                map
            },
            mappings = json.optJSONObject("mappings")?.let { mapObj ->
                AniZipMappings(
                    anilistId = mapObj.optInt("anilist_id", 0).takeIf { it > 0 },
                    malId = mapObj.optInt("mal_id", 0).takeIf { it > 0 },
                    kitsuId = mapObj.optInt("kitsu_id", 0).takeIf { it > 0 },
                    simklId = mapObj.optInt("simkl_id", 0).takeIf { it > 0 },
                    imdbId = mapObj.optString("imdb_id", " ").takeIf { it.isNotEmpty() },
                    tmdbId = mapObj.optJSONObject("themoviedb_id")?.let {
                        AniZipTmdbId(
                            movie = it.optInt("movie", 0).takeIf { v -> v > 0 },
                            tv = it.optInt("tv", 0).takeIf { v -> v > 0 }
                        )
                    }
                )
            }
        )
    }

    // ==============================
    // DATA CLASSES
    // ==============================
    private data class IndexedPost(
        val id: Int,
        val imageSm: String?,
        val rawTitle: String,
        val mediaType: TvType,
        val postType: String,
        val displayTitle: String,
        val franchiseTitle: String,
        val year: Int?,
        val declaredSeason: Int?,
        val quality: Int?,
        val audioTag: String?,
        val groupKey: String
    )

    private data class NormalizedTitle(
        val cleanTitle: String,
        val franchiseTitle: String,
        val year: Int?,
        val season: Int?,
        val quality: Int?,
        val qualityTag: String?,
        val audioTag: String?
    )

    private data class FetchedPost(val id: Int, val data: Data, val movieLink: String?, val tvSeries: TvSeries?)
    private data class SeasonSlice(val fetchedPost: FetchedPost, val globalSeason: Int, val seasonName: String?, val episodes: List<<EpisodeData>)
    private data class EpisodeAccumulator(val season: Int, val episode: Int, var fallbackName: String, val sources: MutableList<<StreamVariant> = mutableListOf())
    private data class StreamPayload(val variants: List<<StreamVariant>) {
        fun toJsonString(): String {
            val json = JSONObject()
            val arr = org.json.JSONArray()
            variants.forEach { arr.put(JSONObject().apply { put("label", it.label); put("url", it.url) }) }
            json.put("variants", arr)
            return json.toString()
        }
    }
    private data class StreamVariant(val label: String, val url: String)
    private data class AnimeMetaBundle(
        val selectedMedia: AniListMedia,
        val seasonChain: List<<AniListMedia>,
        val aniZip: AniZipResponse?,
        val posterUrl: String?,
        val backgroundPosterUrl: String?,
        val logoUrl: String?
    )
    private data class TmdbMetadata(
        val tmdbId: Int?,
        val imdbId: String?,
        val displayTitle: String,
        val posterUrl: String?,
        val backgroundPosterUrl: String?,
        val logoUrl: String?,
        val plot: String?,
        val year: Int?,
        val duration: Int?,
        val genres: List<String>?,
        val score: Score?,
        val actors: List<Pair<<Actor, String?>>?,
        val trailerUrl: String?,
        val showStatus: ShowStatus?
    )
    private enum class TmdbImageKind { POSTER, BACKDROP, LOGO, PROFILE }

    data class PageData(val posts: List<<Post> = emptyList())
    data class Post(val id: Int, val type: String, val imageSm: String? = null, val title: String, val name: String? = null, val year: String? = null, val quality: String? = null)
    data class Data(val type: String, val imageSm: String? = null, val title: String, val image: String? = null, val metaData: String? = null, val name: String? = null, val quality: String? = null, val year: String? = null, val watchTime: String? = null)
    data class TvSeries(val content: List<<SeasonContent> = emptyList())
    data class SeasonContent(val episodes: List<<EpisodeData> = emptyList(), val seasonName: String? = null)
    data class EpisodeData(val link: String, val title: String? = null)
    data class Movies(val content: String? = null)

    data class TmdbConfiguration(val images: TmdbImageConfig? = null)
    data class TmdbImageConfig(val secureBaseUrl: String? = null, val posterSizes: List<String>? = null, val backdropSizes: List<String>? = null, val logoSizes: List<String>? = null, val profileSizes: List<String>? = null)
    data class TmdbSearchPage(val results: List<TmdbSearchResult> = emptyList())
    data class TmdbSearchResult(val id: Int? = null, val title: String? = null, val name: String? = null, val originalTitle: String? = null, val originalName: String? = null, val releaseDate: String? = null, val firstAirDate: String? = null) {
        val displayTitle: String get() = title ?: name ?: originalTitle ?: originalName ?: ""
        val year: Int? get() = releaseDate?.take(4)?.toIntOrNull() ?: firstAirDate?.take(4)?.toIntOrNull()
    }
    data class TmdbMovieDetails(val id: Int? = null, val title: String? = null, val originalTitle: String? = null, val overview: String? = null, val posterPath: String? = null, val backdropPath: String? = null, val releaseDate: String? = null, val runtime: Int? = null, val voteAverage: Double? = null, val genres: List<TmdbGenre>? = null, val videos: TmdbVideos? = null, val images: TmdbImages? = null, val externalIds: TmdbExternalIds? = null, val credits: TmdbCredits? = null)
    data class TmdbTvDetails(val id: Int? = null, val name: String? = null, val originalName: String? = null, val overview: String? = null, val posterPath: String? = null, val backdropPath: String? = null, val firstAirDate: String? = null, val episodeRunTime: List<Int>? = null, val voteAverage: Double? = null, val genres: List<TmdbGenre>? = null, val videos: TmdbVideos? = null, val images: TmdbImages? = null, val externalIds: TmdbExternalIds? = null, val credits: TmdbCredits? = null, val status: String? = null)
    data class TmdbGenre(val name: String? = null)
    data class TmdbVideos(val results: List<TmdbVideo> = emptyList())
    data class TmdbVideo(val key: String? = null, val site: String? = null, val type: String? = null, val official: Boolean? = null)
    data class TmdbImages(val logos: List<TmdbLogo> = emptyList())
    data class TmdbLogo(val filePath: String? = null, val iso6391: String? = null, val voteAverage: Double? = null, val width: Int? = null)
    data class TmdbExternalIds(val imdbId: String? = null)
    data class TmdbCredits(val cast: List<TmdbCast> = emptyList())
    data class TmdbCast(val name: String? = null, val character: String? = null, val profilePath: String? = null)
    data class TmdbSeasonDetails(val episodes: List<TmdbEpisode> = emptyList())
    data class TmdbEpisode(val episodeNumber: Int? = null, val name: String? = null, val overview: String? = null, val stillPath: String? = null, val airDate: String? = null, val runtime: Int? = null)

    data class AniListSearchResponse(val data: AniListSearchData? = null)
    data class AniListSearchData(val page: AniListPage? = null)
    data class AniListPage(val media: List<<AniListMedia> = emptyList())
    data class AniListByIdResponse(val data: AniListByIdData? = null)
    data class AniListByIdData(val media: AniListMedia? = null)
    data class AniListMedia(val id: Int? = null, val idMal: Int? = null, val title: AniListTitle? = null, val synonyms: List<String?>? = null, val format: String? = null, val status: String? = null, val seasonYear: Int? = null, val startDate: AniListDate? = null, val episodes: Int? = null, val duration: Int? = null, val averageScore: Int? = null, val description: String? = null, val bannerImage: String? = null, val genres: List<String?>? = null, val trailer: AniListTrailer? = null, val coverImage: AniListCoverImage? = null, val relations: AniListRelations? = null)
    data class AniListTitle(val romaji: String? = null, val english: String? = null, val native: String? = null, val userPreferred: String? = null)
    data class AniListDate(val year: Int? = null, val month: Int? = null, val day: Int? = null)
    data class AniListTrailer(val id: String? = null, val site: String? = null, val thumbnail: String? = null)
    data class AniListCoverImage(val extraLarge: String? = null, val large: String? = null)
    data class AniListRelations(val edges: List<<AniListRelationEdge> = emptyList())
    data class AniListRelationEdge(val relationType: String = " ", val node: AniListMedia = AniListMedia())
    data class AniZipResponse(val images: List<<AniZipImage>? = null, val episodes: Map<String, AniZipEpisode>? = null, val mappings: AniZipMappings? = null)
    data class AniZipImage(val coverType: String? = null, val url: String? = null)
    data class AniZipMappings(val anilistId: Int? = null, val malId: Int? = null, val kitsuId: Int? = null, val simklId: Int? = null, val imdbId: String? = null, val tmdbId: AniZipTmdbId? = null)
    data class AniZipTmdbId(val movie: Int? = null, val tv: Int? = null)
    data class AniZipEpisode(val title: AniZipEpisodeTitle? = null, val overview: String? = null, val summary: String? = null, val image: String? = null, val runtime: Int? = null, val length: Int? = null, val airDate: String? = null, val airDateUtc: String? = null, val rating: String? = null)
    data class AniZipEpisodeTitle(val en: String? = null, val ja: String? = null, val xJat: String? = null)
}
