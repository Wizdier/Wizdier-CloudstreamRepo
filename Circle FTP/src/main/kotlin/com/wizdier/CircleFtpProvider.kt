package com.wizdier

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.getDurationFromString
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Base64
import kotlin.math.abs

class CircleFtpProvider : MainAPI() {
    override var mainUrl = "http://new.circleftp.net"
    private val mainApiUrl = "http://new.circleftp.net:5000"
    private val fallbackApiUrl = "http://15.1.1.50:5000"
    private val tmdbApiKey = "e6333b32409e02a4a6eba6fb7ff866bb"
    private val tmdbApiBase = "https://api.themoviedb.org/3"
    private val aniListGraphQlUrl = "https://graphql.anilist.co"
    private val aniZipUrl = "https://api.ani.zip/mappings"
    private val fribbAnimeListUrl = "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-mini.json"
    private val payloadPrefix = "circleftp://"

    override var name = "(BDIX) Circle FTP"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.AsianDrama,
        TvType.Documentary,
        TvType.OVA,
        TvType.Others,
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
        "15" to "WWE",
    )

    private val animeDetectionCache = mutableMapOf<String, Boolean>()
    private val aniListMediaCache = mutableMapOf<Int, AniListMedia>()
    private var tmdbConfigCache: TmdbConfiguration? = null
    private var fribbAnimeCache: List<FribbAnimeEntry>? = null

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val encodedCategory = urlEncode(request.data)
        val path = "/api/posts?categoryExact=$encodedCategory&page=$page&order=desc&limit=10"
        val rawPage = apiGetText(path, cacheTime = 60).text
        val posts = AppUtils.parseJson<PageData>(rawPage).posts
        val results = buildSearchResults(posts, request.name, request.data)
        return newHomePageResponse(request.name, results, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = urlEncode(query)
        val rawPage = apiGetText(
            "/api/posts?searchTerm=$encodedQuery&order=desc",
            cacheTime = 60,
        ).text
        val posts = AppUtils.parseJson<PageData>(rawPage).posts
        return buildSearchResults(posts, null, null)
    }

    override suspend fun load(url: String): LoadResponse {
        val payload = decodePayload(url) ?: buildLegacyPayload(url)
        val payloadType = payload.mediaType.toTvType()
        return when {
            payloadType == TvType.Anime || payloadType == TvType.AnimeMovie || payloadType == TvType.OVA -> {
                loadAnimePayload(payload)
            }

            else -> loadStandardPayload(payload)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val streamPayload = tryParseJson<StreamPayload>(data)
        if (streamPayload != null && streamPayload.variants.isNotEmpty()) {
            streamPayload.variants
                .distinctBy { variant -> variant.url }
                .forEach { variant ->
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = variant.label,
                            url = variant.url,
                        ) {
                            this.quality = inferExtractorQuality(variant.label)
                        }
                    )
                }
            return true
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = data,
            )
        )
        return true
    }

    private suspend fun buildSearchResults(
        posts: List<Post>,
        categoryName: String?,
        categoryId: String?,
    ): List<SearchResponse> = coroutineScope {
        val indexedPosts = posts.map { post ->
            async {
                toIndexedPost(post, categoryName, categoryId)
            }
        }.awaitAll().filterNotNull()

        val animeGroups = indexedPosts
            .filter { indexedPost -> indexedPost.mediaType == TvType.Anime || indexedPost.mediaType == TvType.AnimeMovie || indexedPost.mediaType == TvType.OVA }
            .groupBy { indexedPost -> "${indexedPost.mediaType.name}:${indexedPost.franchiseKey}:${indexedPost.year ?: 0}" }

        val normalGroups = indexedPosts
            .filterNot { indexedPost -> indexedPost.mediaType == TvType.Anime || indexedPost.mediaType == TvType.AnimeMovie || indexedPost.mediaType == TvType.OVA }
            .groupBy { indexedPost -> "${indexedPost.mediaType.name}:${indexedPost.audioMergeKey}" }

        val results = mutableListOf<SearchResponse>()

        animeGroups.values
            .sortedBy { groupPosts -> groupPosts.minOfOrNull { indexedPost -> indexedPost.id } ?: Int.MAX_VALUE }
            .forEach { groupPosts ->
                val baselineSeason = groupPosts.mapNotNull { indexedPost -> indexedPost.declaredSeason }.minOrNull() ?: 1
                val baselinePost = groupPosts.minByOrNull { indexedPost ->
                    val seasonDistance = abs((indexedPost.declaredSeason ?: baselineSeason) - baselineSeason)
                    seasonDistance * 100000 + indexedPost.id
                } ?: return@forEach
                val searchPayload = CirclePayload(
                    mediaType = baselinePost.mediaType.name,
                    displayTitle = baselinePost.franchiseTitle,
                    franchiseTitle = baselinePost.franchiseTitle,
                    year = baselinePost.year,
                    activeSeason = baselineSeason,
                    posts = groupPosts.map { indexedPost -> indexedPost.toPayloadPost() }.distinctBy { payloadPost -> payloadPost.id },
                )
                val posterUrl = buildPosterUrl(baselinePost.imageSm)
                val quality = groupPosts.mapNotNull { indexedPost -> indexedPost.quality }.maxByOrNull { qualityValue -> qualityValue.ordinal }
                val hasDub = groupPosts.any { indexedPost -> indexedPost.audioTag?.contains("dub", ignoreCase = true) == true || indexedPost.audioTag?.contains("hindi", ignoreCase = true) == true || indexedPost.audioTag?.contains("dual", ignoreCase = true) == true || indexedPost.audioTag?.contains("multi", ignoreCase = true) == true }
                val hasSub = groupPosts.any { indexedPost -> indexedPost.audioTag?.contains("sub", ignoreCase = true) == true } || !hasDub

                results += newAnimeSearchResponse(
                    name = baselinePost.franchiseTitle,
                    url = encodePayload(searchPayload),
                    type = baselinePost.mediaType,
                    fix = false,
                ) {
                    this.posterUrl = posterUrl
                    this.year = baselinePost.year
                    this.id = baselinePost.id
                    this.quality = quality
                    addDubStatus(dubExist = hasDub, subExist = hasSub)
                }
            }

        normalGroups.values
            .sortedBy { groupPosts -> groupPosts.minOfOrNull { indexedPost -> indexedPost.id } ?: Int.MAX_VALUE }
            .forEach { groupPosts ->
                val primaryPost = groupPosts.minByOrNull { indexedPost -> indexedPost.id } ?: return@forEach
                val displayTitle = primaryPost.displayTitle.ifBlank { primaryPost.franchiseTitle }
                val searchPayload = CirclePayload(
                    mediaType = primaryPost.mediaType.name,
                    displayTitle = displayTitle,
                    franchiseTitle = primaryPost.franchiseTitle,
                    year = primaryPost.year,
                    activeSeason = null,
                    posts = groupPosts.map { indexedPost -> indexedPost.toPayloadPost() }.distinctBy { payloadPost -> payloadPost.id },
                )
                val posterUrl = buildPosterUrl(primaryPost.imageSm)
                val quality = groupPosts.mapNotNull { indexedPost -> indexedPost.quality }.maxByOrNull { qualityValue -> qualityValue.ordinal }

                val response = when {
                    primaryPost.postType == "series" || primaryPost.mediaType == TvType.TvSeries || primaryPost.mediaType == TvType.AsianDrama -> {
                        newTvSeriesSearchResponse(
                            name = displayTitle,
                            url = encodePayload(searchPayload),
                            type = primaryPost.mediaType,
                            fix = false,
                        ) {
                            this.posterUrl = posterUrl
                            this.year = primaryPost.year
                            this.id = primaryPost.id
                            this.quality = quality
                        }
                    }

                    else -> {
                        newMovieSearchResponse(
                            name = displayTitle,
                            url = encodePayload(searchPayload),
                            type = primaryPost.mediaType,
                            fix = false,
                        ) {
                            this.posterUrl = posterUrl
                            this.year = primaryPost.year
                            this.id = primaryPost.id
                            this.quality = quality
                        }
                    }
                }
                results += response
            }

        results
    }

    private suspend fun toIndexedPost(
        post: Post,
        categoryName: String?,
        categoryId: String?,
    ): IndexedPost? {
        if (post.type != "singleVideo" && post.type != "series") {
            return null
        }

        val normalized = normalizeTitle(post.title)
        val resolvedType = inferPostTvType(post, categoryName, categoryId, normalized)
        return IndexedPost(
            id = post.id,
            imageSm = post.imageSm,
            rawTitle = post.title,
            mediaType = resolvedType,
            postType = post.type,
            displayTitle = if (resolvedType == TvType.Anime || resolvedType == TvType.AnimeMovie || resolvedType == TvType.OVA) normalized.franchiseTitle else normalized.displayTitle,
            franchiseTitle = normalized.franchiseTitle,
            year = normalized.year,
            declaredSeason = normalized.season,
            quality = normalized.quality,
            audioTag = normalized.audioTag,
            franchiseKey = normalized.franchiseKey,
            audioMergeKey = when {
                resolvedType == TvType.TvSeries || resolvedType == TvType.Cartoon || resolvedType == TvType.AsianDrama -> {
                    "${normalized.canonicalKey}:S${normalized.season ?: 0}"
                }

                else -> normalized.canonicalKey
            },
        )
    }

    private suspend fun inferPostTvType(
        post: Post,
        categoryName: String?,
        categoryId: String?,
        normalized: NormalizedTitle,
    ): TvType {
        val fromCategory = inferCategoryTvType(categoryName, categoryId, post.type)
        if (fromCategory != null) {
            return when {
                fromCategory == TvType.Movie && post.type == "series" -> TvType.TvSeries
                else -> fromCategory
            }
        }

        val titleCheck = post.title.lowercase()
        val isAnime = detectAnime(normalized.franchiseTitle, normalized.year)
        return when {
            isAnime && post.type == "singleVideo" && titleCheck.contains("ova") -> TvType.OVA
            isAnime && post.type == "singleVideo" -> TvType.AnimeMovie
            isAnime -> TvType.Anime
            post.type == "series" -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun inferCategoryTvType(
        categoryName: String?,
        categoryId: String?,
        postType: String,
    ): TvType? {
        return when (categoryId ?: categoryName?.lowercase()) {
            "21", "anime series" -> TvType.Anime
            "1", "animation movies" -> TvType.Cartoon
            "85", "documentary" -> TvType.Documentary
            "15", "wwe" -> TvType.Others
            "238", "indian tv show" -> TvType.TvSeries
            "9", "english & foreign tv series" -> TvType.TvSeries
            "22", "dubbed tv series" -> TvType.TvSeries
            "5", "hindi tv series" -> TvType.TvSeries
            "6", "english movies" -> TvType.Movie
            "2", "hindi movies" -> TvType.Movie
            "7", "english & foreign hindi dubbed movies" -> TvType.Movie
            "8", "foreign language movies" -> TvType.Movie
            "3", "south indian dubbed movies" -> TvType.Movie
            "4", "south indian movies" -> TvType.Movie
            else -> when {
                postType == "singleVideo" -> null
                else -> null
            }
        }
    }

    private suspend fun detectAnime(title: String, year: Int?): Boolean {
    val cacheKey = "$title|${year ?: 0}"
    animeDetectionCache[cacheKey]?.let { detectedAnime ->
        return detectedAnime
    }

    // getTracker is not available in the current Cloudstream extension compile classpath.
    // Use AniList search directly instead.
    val detectedAnime = runCatching {
        val candidates = searchAniList(title)
        val bestMatch = chooseBestAniListMatch(
            candidates = candidates,
            queryTitles = listOf(title),
            year = year,
        )

        bestMatch != null && scoreAniListCandidate(
            candidate = bestMatch,
            queryTitles = listOf(title),
            year = year,
        ) >= 60
    }.getOrDefault(false)

    animeDetectionCache[cacheKey] = detectedAnime
    return detectedAnime
    }

    private suspend fun loadAnimePayload(payload: CirclePayload): LoadResponse = coroutineScope {
        val fetchedPosts = payload.posts.distinctBy { payloadPost -> payloadPost.id }
            .map { payloadPost ->
                async {
                    fetchPostDetails(payloadPost)
                }
            }.awaitAll()

        val activeMediaType = resolveEffectiveMediaType(payload.mediaType.toTvType(), fetchedPosts.firstOrNull())
        val activeSeason = payload.activeSeason ?: 1

        if (activeMediaType.isMovieType() || fetchedPosts.all { fetchedPost -> fetchedPost.data.type == "singleVideo" }) {
            return@coroutineScope loadAnimeMovie(payload, fetchedPosts, activeMediaType)
        }

        val allSeasonSlices = fetchedPosts.flatMap { fetchedPost -> discoverSeasonSlices(fetchedPost) }
        val resolvedSeason = when {
            allSeasonSlices.any { seasonSlice -> seasonSlice.globalSeason == activeSeason } -> activeSeason
            else -> allSeasonSlices.minOfOrNull { seasonSlice -> seasonSlice.globalSeason } ?: activeSeason
        }
        val activeSlices = allSeasonSlices.filter { seasonSlice -> seasonSlice.globalSeason == resolvedSeason }
        if (activeSlices.isEmpty()) {
            throw ErrorLoadingException("No anime season data found")
        }

        val animeMeta = resolveAnimeMetadata(payload, fetchedPosts, allSeasonSlices, resolvedSeason)
        val seasonEpisodes = mergeEpisodes(
            slices = activeSlices,
            seasonNumber = resolvedSeason,
            aniZip = animeMeta?.aniZip,
        )
        val fallbackPost = fetchedPosts.firstOrNull()
        val fallbackTitle = payload.displayTitle.ifBlank { fallbackPost?.data?.title ?: "Unknown Title" }
        val fallbackPoster = buildPosterUrl(fallbackPost?.data?.image ?: fallbackPost?.data?.imageSm)
        val fallbackPlot = fallbackPost?.data?.metaData
        val displayName = animeMeta?.selectedMedia?.preferredTitle().orEmpty().ifBlank {
            if (resolvedSeason <= 1) fallbackTitle else "$fallbackTitle Season $resolvedSeason"
        }
        val recommendations = buildAnimeRecommendations(payload, allSeasonSlices, animeMeta, resolvedSeason)

        return@coroutineScope newAnimeLoadResponse(
            name = displayName,
            url = encodePayload(payload.copy(activeSeason = resolvedSeason)),
            type = activeMediaType,
        ) {
            this.posterUrl = animeMeta?.posterUrl ?: fallbackPoster
            this.backgroundPosterUrl = animeMeta?.backgroundPosterUrl ?: fallbackPoster
            this.logoUrl = animeMeta?.logoUrl
            this.plot = animeMeta?.selectedMedia?.cleanDescription() ?: fallbackPlot
            this.year = animeMeta?.selectedMedia?.resolvedYear() ?: fallbackPost?.data?.year?.selectUntilNonInt()
            this.showStatus = animeMeta?.selectedMedia?.toShowStatus()
            this.duration = animeMeta?.selectedMedia?.duration ?: fetchedPosts.firstNotNullOfOrNull { fetchedPost ->
                getDurationFromString(fetchedPost.data.watchTime)
            }
            this.tags = animeMeta?.selectedMedia?.genres?.filterNotNull()?.filter { genre -> genre.isNotBlank() }
            this.recommendations = recommendations
            this.engName = animeMeta?.selectedMedia?.title?.english
            this.japName = animeMeta?.selectedMedia?.title?.romaji ?: animeMeta?.selectedMedia?.title?.native
            this.episodes[DubStatus.None] = seasonEpisodes
            addSeasonNames(listOf(SeasonData(season = resolvedSeason, name = "Season $resolvedSeason", displaySeason = resolvedSeason)))
            addScore(animeMeta?.selectedMedia?.score())
            applyAnimeSyncData(this, animeMeta)
            applyAnimeTrailer(this, animeMeta)
        }
    }

    private suspend fun loadAnimeMovie(
        payload: CirclePayload,
        fetchedPosts: List<FetchedPost>,
        mediaType: TvType,
    ): LoadResponse = coroutineScope {
        val fallbackPost = fetchedPosts.firstOrNull() ?: throw ErrorLoadingException("Anime movie not found")
        val streamVariants = fetchedPosts.mapNotNull { fetchedPost ->
            val streamUrl = fetchedPost.movieLink ?: return@mapNotNull null
            StreamVariant(
                label = buildSourceLabel(fetchedPost.ref, fetchedPost.data),
                url = streamUrl,
            )
        }.distinctBy { variant -> variant.url }

        val animeMeta = resolveAnimeMetadata(payload, fetchedPosts, emptyList(), payload.activeSeason ?: 1)
        val fallbackTitle = payload.displayTitle.ifBlank { fallbackPost.data.title }
        val displayName = animeMeta?.selectedMedia?.preferredTitle() ?: fallbackTitle
        val fallbackPoster = buildPosterUrl(fallbackPost.data.image)
        val fallbackPlot = fallbackPost.data.metaData
        val streamPayload = StreamPayload(variants = streamVariants)

        return@coroutineScope newMovieLoadResponse(
            name = displayName,
            url = encodePayload(payload),
            type = mediaType,
            dataUrl = streamPayload.toJson(),
        ) {
            this.posterUrl = animeMeta?.posterUrl ?: fallbackPoster
            this.backgroundPosterUrl = animeMeta?.backgroundPosterUrl ?: fallbackPoster
            this.logoUrl = animeMeta?.logoUrl
            this.plot = animeMeta?.selectedMedia?.cleanDescription() ?: fallbackPlot
            this.year = animeMeta?.selectedMedia?.resolvedYear() ?: fallbackPost.data.year.selectUntilNonInt()
            this.duration = animeMeta?.selectedMedia?.duration ?: getDurationFromString(fallbackPost.data.watchTime)
            this.tags = animeMeta?.selectedMedia?.genres?.filterNotNull()?.filter { genre -> genre.isNotBlank() }
            addScore(animeMeta?.selectedMedia?.score())
            applyAnimeSyncData(this, animeMeta)
            applyAnimeTrailer(this, animeMeta)
        }
    }

    private suspend fun loadStandardPayload(payload: CirclePayload): LoadResponse = coroutineScope {
        val fetchedPosts = payload.posts.distinctBy { payloadPost -> payloadPost.id }
            .map { payloadPost ->
                async {
                    fetchPostDetails(payloadPost)
                }
            }.awaitAll()

        val effectiveType = resolveEffectiveMediaType(payload.mediaType.toTvType(), fetchedPosts.firstOrNull())
        val fallbackPost = fetchedPosts.firstOrNull() ?: throw ErrorLoadingException("Circle item not found")
        val isSeries = !effectiveType.isMovieType() && fetchedPosts.any { fetchedPost -> fetchedPost.data.type == "series" }
        val tmdbMetadata = resolveTmdbMetadata(
            mediaType = effectiveType,
            queryTitles = listOf(payload.franchiseTitle.ifBlank { payload.displayTitle }, payload.displayTitle).distinct().filter { queryTitle -> queryTitle.isNotBlank() },
            year = payload.year ?: fallbackPost.data.year.selectUntilNonInt(),
            isSeries = isSeries,
        )

        if (!isSeries) {
            val streamVariants = fetchedPosts.mapNotNull { fetchedPost ->
                val streamUrl = fetchedPost.movieLink ?: return@mapNotNull null
                StreamVariant(
                    label = buildSourceLabel(fetchedPost.ref, fetchedPost.data),
                    url = streamUrl,
                )
            }.distinctBy { variant -> variant.url }
            val streamPayload = StreamPayload(variants = streamVariants)
            return@coroutineScope newMovieLoadResponse(
                name = tmdbMetadata?.displayTitle ?: payload.displayTitle.ifBlank { fallbackPost.data.title },
                url = encodePayload(payload),
                type = effectiveType,
                dataUrl = streamPayload.toJson(),
            ) {
                this.posterUrl = tmdbMetadata?.posterUrl ?: buildPosterUrl(fallbackPost.data.image)
                this.backgroundPosterUrl = tmdbMetadata?.backgroundPosterUrl ?: this.posterUrl
                this.logoUrl = tmdbMetadata?.logoUrl
                this.plot = tmdbMetadata?.plot ?: fallbackPost.data.metaData
                this.year = tmdbMetadata?.year ?: fallbackPost.data.year.selectUntilNonInt()
                this.duration = tmdbMetadata?.duration ?: getDurationFromString(fallbackPost.data.watchTime)
                this.tags = tmdbMetadata?.genres
                addScore(tmdbMetadata?.score)
                tmdbMetadata?.imdbId?.let { imdbId -> addImdbId(imdbId) }
                tmdbMetadata?.tmdbId?.let { tmdbId -> addTMDbId(tmdbId.toString()) }
                tmdbMetadata?.actors?.let { actorPairs -> addActors(actorPairs) }
                tmdbMetadata?.trailerUrl?.let { trailerUrl -> addTrailer(trailerUrl) }
            }
        }

        val seasonSlices = fetchedPosts.flatMap { fetchedPost -> discoverSeasonSlices(fetchedPost) }
        val mergedEpisodes = mergeEpisodes(seasonSlices, seasonNumber = null, aniZip = null)
        val seasonNames = seasonSlices
            .map { seasonSlice -> seasonSlice.globalSeason }
            .distinct()
            .sorted()
            .map { seasonNumber -> SeasonData(season = seasonNumber, name = "Season $seasonNumber", displaySeason = seasonNumber) }

        return@coroutineScope newTvSeriesLoadResponse(
            name = tmdbMetadata?.displayTitle ?: payload.displayTitle.ifBlank { fallbackPost.data.title },
            url = encodePayload(payload),
            type = effectiveType,
            episodes = mergedEpisodes,
        ) {
            this.posterUrl = tmdbMetadata?.posterUrl ?: buildPosterUrl(fallbackPost.data.image)
            this.backgroundPosterUrl = tmdbMetadata?.backgroundPosterUrl ?: this.posterUrl
            this.logoUrl = tmdbMetadata?.logoUrl
            this.plot = tmdbMetadata?.plot ?: fallbackPost.data.metaData
            this.year = tmdbMetadata?.year ?: fallbackPost.data.year.selectUntilNonInt()
            this.duration = tmdbMetadata?.duration ?: getDurationFromString(fallbackPost.data.watchTime)
            this.tags = tmdbMetadata?.genres
            this.showStatus = tmdbMetadata?.showStatus
            addSeasonNames(seasonNames)
            addScore(tmdbMetadata?.score)
            tmdbMetadata?.imdbId?.let { imdbId -> addImdbId(imdbId) }
            tmdbMetadata?.tmdbId?.let { tmdbId -> addTMDbId(tmdbId.toString()) }
            tmdbMetadata?.actors?.let { actorPairs -> addActors(actorPairs) }
            tmdbMetadata?.trailerUrl?.let { trailerUrl -> addTrailer(trailerUrl) }
        }
    }

    private fun resolveEffectiveMediaType(payloadType: TvType, fetchedPost: FetchedPost?): TvType {
        if (payloadType != TvType.Others) {
            return payloadType
        }
        return when (fetchedPost?.data?.type) {
            "series" -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private suspend fun fetchPostDetails(ref: PayloadPostRef): FetchedPost {
        val apiResponse = apiGetText("/api/posts/${ref.id}", cacheTime = 60)
        val data = AppUtils.parseJson<Data>(apiResponse.text)
        return if (data.type == "singleVideo") {
            val movieData = AppUtils.tryParseJson<Movies>(apiResponse.text)
            FetchedPost(
                ref = ref,
                data = data,
                movieLink = normalizeCircleStream(movieData?.content, apiResponse.usedFallback),
                tvSeries = null,
                usedFallbackApi = apiResponse.usedFallback,
            )
        } else {
            FetchedPost(
                ref = ref,
                data = data,
                movieLink = null,
                tvSeries = AppUtils.parseJson<TvSeries>(apiResponse.text),
                usedFallbackApi = apiResponse.usedFallback,
            )
        }
    }

    private fun discoverSeasonSlices(fetchedPost: FetchedPost): List<SeasonSlice> {
        val tvSeries = fetchedPost.tvSeries ?: return emptyList()
        val declaredSeason = fetchedPost.ref.declaredSeason ?: normalizeTitle(fetchedPost.data.title).season ?: 1
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
                episodes = seasonContent.episodes,
            )
        }
    }

    private fun mergeEpisodes(
        slices: List<SeasonSlice>,
        seasonNumber: Int?,
        aniZip: AniZipResponse?,
    ): List<Episode> {
        val episodeMap = linkedMapOf<String, EpisodeAccumulator>()
        slices.forEach { seasonSlice ->
            seasonSlice.episodes.forEachIndexed { index, episodeData ->
                val resolvedEpisodeNumber = extractEpisodeNumber(episodeData.title) ?: index + 1
                val resolvedSeason = seasonNumber ?: seasonSlice.globalSeason
                val mapKey = "$resolvedSeason-$resolvedEpisodeNumber"
                val accumulator = episodeMap.getOrPut(mapKey) {
                    EpisodeAccumulator(
                        season = resolvedSeason,
                        episode = resolvedEpisodeNumber,
                        fallbackName = cleanupEpisodeTitle(episodeData.title, resolvedEpisodeNumber),
                    )
                }
                accumulator.sources += StreamVariant(
                    label = buildSourceLabel(seasonSlice.fetchedPost.ref, seasonSlice.fetchedPost.data),
                    url = normalizeCircleStream(episodeData.link, seasonSlice.fetchedPost.dataSourceRequiresIp()),
                )
                if (accumulator.fallbackName.isBlank()) {
                    accumulator.fallbackName = cleanupEpisodeTitle(episodeData.title, resolvedEpisodeNumber)
                }
            }
        }

        return episodeMap.values
            .sortedWith(compareBy<EpisodeAccumulator> { accumulator -> accumulator.season }.thenBy { accumulator -> accumulator.episode })
            .map { accumulator ->
                val aniZipEpisode = aniZip?.episodes?.get(accumulator.episode.toString())
                newEpisode(
                    StreamPayload(variants = accumulator.sources.distinctBy { variant -> variant.url })
                ) {
                    this.season = accumulator.season
                    this.episode = accumulator.episode
                    this.name = aniZipEpisode?.preferredTitle() ?: accumulator.fallbackName
                    this.description = aniZipEpisode?.overview ?: aniZipEpisode?.summary
                    this.posterUrl = aniZipEpisode?.image
                    this.score = Score.from10(aniZipEpisode?.rating)
                    this.runTime = ((aniZipEpisode?.runtime ?: aniZipEpisode?.length)?.times(60))
                    addDate(aniZipEpisode?.airDateUtc ?: aniZipEpisode?.airDate)
                }
            }
    }

    private suspend fun resolveAnimeMetadata(
        payload: CirclePayload,
        fetchedPosts: List<FetchedPost>,
        allSeasonSlices: List<SeasonSlice>,
        activeSeason: Int,
    ): AnimeMetaBundle? = coroutineScope {
        val seasonSpecificTitles = payload.posts
            .filter { payloadPost -> (payloadPost.declaredSeason ?: 1) == activeSeason }
            .map { payloadPost -> normalizeTitle(payloadPost.rawTitle).displayTitle }
            .filter { title -> title.isNotBlank() }
            .distinct()

        val fallbackFranchiseTitle = payload.franchiseTitle.ifBlank {
            normalizeTitle(fetchedPosts.firstOrNull()?.data?.title ?: payload.displayTitle).franchiseTitle
        }
        val searchTitles = buildList {
            addAll(seasonSpecificTitles)
            if (activeSeason > 1) {
                add("$fallbackFranchiseTitle Season $activeSeason")
                add("$fallbackFranchiseTitle $activeSeason")
            }
            add(fallbackFranchiseTitle)
            add(payload.displayTitle)
        }.map { title -> title.trim() }.filter { title -> title.isNotBlank() }.distinct()

        val searchResults = searchTitles.take(4).map { searchTitle ->
            async {
                searchAniList(searchTitle)
            }
        }.awaitAll().flatten()

        val directMatch = chooseBestAniListMatch(
            candidates = searchResults,
            queryTitles = searchTitles,
            year = payload.year ?: fetchedPosts.firstOrNull()?.data?.year?.selectUntilNonInt(),
        )
        val baselineSearch = chooseBestAniListMatch(
            candidates = searchResults,
            queryTitles = listOf(fallbackFranchiseTitle, payload.displayTitle).distinct(),
            year = payload.year ?: fetchedPosts.firstOrNull()?.data?.year?.selectUntilNonInt(),
        )
        val relationSeed = baselineSearch ?: directMatch
        val relationChain = relationSeed?.id?.let { seedId -> buildAniListSeasonChain(seedId) }.orEmpty()
        val selectedMedia = when {
            relationChain.size >= activeSeason -> {
                val relationMediaId = relationChain[activeSeason - 1].id
                when {
                    relationMediaId != null -> fetchAniListById(relationMediaId)
                    directMatch?.id != null -> fetchAniListById(directMatch.id)
                    else -> directMatch
                }
            }

            directMatch?.id != null -> fetchAniListById(directMatch.id)
            relationSeed?.id != null -> fetchAniListById(relationSeed.id)
            else -> null
        }

        if (selectedMedia == null) {
            return@coroutineScope null
        }

        val aniZipDeferred = async {
            fetchAniZip(selectedMedia.id ?: return@async null)
        }
        val fribbDeferred = async {
            fetchFribbAnimeEntry(selectedMedia.id ?: return@async null)
        }
        val aniZip = aniZipDeferred.await()
        val fribbEntry = fribbDeferred.await()

        AnimeMetaBundle(
            selectedMedia = selectedMedia,
            seasonChain = relationChain,
            aniZip = aniZip,
            fribb = fribbEntry,
            posterUrl = selectedMedia.coverImage?.extraLarge ?: selectedMedia.coverImage?.large,
            backgroundPosterUrl = selectedMedia.bannerImage ?: aniZip?.bestBackgroundUrl() ?: selectedMedia.coverImage?.extraLarge,
            logoUrl = aniZip?.bestLogoUrl(),
        )
    }

    private suspend fun buildAnimeSeasonChainFromMedia(seedMedia: AniListMedia): List<AniListMedia> {
        return buildAniListSeasonChain(seedMedia.id ?: return emptyList())
    }

    private suspend fun buildAniListSeasonChain(seedId: Int): List<AniListMedia> {
        val visitedIds = linkedSetOf<Int>()
        var earliestMedia = fetchAniListById(seedId) ?: return emptyList()

        while (true) {
            val prequelNode = earliestMedia.relations?.edges.orEmpty()
                .mapNotNull { relationEdge ->
                    if (relationEdge.relationType.equals("PREQUEL", ignoreCase = true)) relationEdge.node else null
                }
                .firstOrNull { relatedMedia ->
                    val relatedId = relatedMedia.id ?: return@firstOrNull false
                    isMainlineAnimeFormat(relatedMedia.format) && !visitedIds.contains(relatedId)
                }
                ?: break
            val prequelId = prequelNode.id ?: break
            val prequelMedia = fetchAniListById(prequelId) ?: break
            if (prequelMedia.id == earliestMedia.id) {
                break
            }
            earliestMedia = prequelMedia
        }

        val chain = mutableListOf<AniListMedia>()
        var cursorMedia: AniListMedia? = earliestMedia
        while (cursorMedia != null) {
            val cursorId = cursorMedia.id ?: break
            if (!visitedIds.add(cursorId)) {
                break
            }
            chain += cursorMedia
            val sequelNode = cursorMedia.relations?.edges.orEmpty()
                .mapNotNull { relationEdge ->
                    if (relationEdge.relationType.equals("SEQUEL", ignoreCase = true)) relationEdge.node else null
                }
                .firstOrNull { relatedMedia ->
                    val relatedId = relatedMedia.id ?: return@firstOrNull false
                    isMainlineAnimeFormat(relatedMedia.format) && !visitedIds.contains(relatedId)
                }
            cursorMedia = sequelNode?.id?.let { sequelId -> fetchAniListById(sequelId) }
        }
        return chain
    }

    private fun buildAnimeRecommendations(
        payload: CirclePayload,
        allSeasonSlices: List<SeasonSlice>,
        animeMeta: AnimeMetaBundle?,
        activeSeason: Int,
    ): List<SearchResponse> {
        val discoveredSeasons = allSeasonSlices
            .map { seasonSlice -> seasonSlice.globalSeason }
            .distinct()
            .sorted()
        val chainTitles = animeMeta?.seasonChain.orEmpty()

        return discoveredSeasons
            .filter { seasonNumber -> seasonNumber != activeSeason }
            .map { seasonNumber ->
                val chainMedia = chainTitles.getOrNull(seasonNumber - 1)
                val recommendationPayload = payload.copy(activeSeason = seasonNumber)
                val recommendationTitle = chainMedia?.preferredTitle()
                    ?: if (seasonNumber <= 1) payload.franchiseTitle else "${payload.franchiseTitle} Season $seasonNumber"

                newAnimeSearchResponse(
                    name = recommendationTitle,
                    url = encodePayload(recommendationPayload),
                    type = payload.mediaType.toTvType(),
                    fix = false,
                ) {
                    this.posterUrl = chainMedia?.coverImage?.extraLarge ?: chainMedia?.coverImage?.large ?: animeMeta?.posterUrl
                    this.year = chainMedia?.resolvedYear() ?: payload.year
                }
            }
    }

    private suspend fun resolveTmdbMetadata(
        mediaType: TvType,
        queryTitles: List<String>,
        year: Int?,
        isSeries: Boolean,
    ): TmdbMetadata? {
        val normalizedQueries = queryTitles.map { queryTitle -> normalizeTitle(queryTitle).franchiseTitle }.filter { title -> title.isNotBlank() }.distinct()
        if (normalizedQueries.isEmpty()) {
            return null
        }

        val bestMatch = searchTmdbBestMatch(
            queryTitles = normalizedQueries,
            year = year,
            isSeries = isSeries,
        ) ?: return null

        return if (isSeries) {
            val detailText = app.get(
                url = "$tmdbApiBase/tv/${bestMatch.id}?api_key=$tmdbApiKey&language=en-US&append_to_response=videos,images,external_ids,credits,content_ratings",
                cacheTime = 60 * 60 * 12,
            ).text
            val detail = AppUtils.parseJson<TmdbTvDetails>(detailText)
            val config = getTmdbConfig()
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
                genres = detail.genres?.mapNotNull { genre -> genre.name },
                score = Score.from10(detail.voteAverage),
                actors = detail.credits?.cast.orEmpty().mapNotNull { castMember ->
                    val actorName = castMember.name ?: return@mapNotNull null
                    Actor(actorName, buildTmdbImageUrl(config, castMember.profilePath, TmdbImageKind.PROFILE)) to castMember.character
                },
                trailerUrl = pickTmdbTrailerUrl(detail.videos?.results.orEmpty()),
                showStatus = detail.status.toTmdbShowStatus(),
            )
        } else {
            val detailText = app.get(
                url = "$tmdbApiBase/movie/${bestMatch.id}?api_key=$tmdbApiKey&language=en-US&append_to_response=videos,images,external_ids,credits,release_dates",
                cacheTime = 60 * 60 * 12,
            ).text
            val detail = AppUtils.parseJson<TmdbMovieDetails>(detailText)
            val config = getTmdbConfig()
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
                genres = detail.genres?.mapNotNull { genre -> genre.name },
                score = Score.from10(detail.voteAverage),
                actors = detail.credits?.cast.orEmpty().mapNotNull { castMember ->
                    val actorName = castMember.name ?: return@mapNotNull null
                    Actor(actorName, buildTmdbImageUrl(config, castMember.profilePath, TmdbImageKind.PROFILE)) to castMember.character
                },
                trailerUrl = pickTmdbTrailerUrl(detail.videos?.results.orEmpty()),
                showStatus = null,
            )
        }
    }

    private suspend fun searchTmdbBestMatch(
        queryTitles: List<String>,
        year: Int?,
        isSeries: Boolean,
    ): TmdbSearchResult? = coroutineScope {
        val endpoint = if (isSeries) "tv" else "movie"
        val candidates = queryTitles.take(3).map { queryTitle ->
            async {
                val url = buildString {
                    append("$tmdbApiBase/search/$endpoint?api_key=$tmdbApiKey&language=en-US&query=")
                    append(urlEncode(queryTitle))
                    if (year != null) {
                        append(if (isSeries) "&first_air_date_year=$year" else "&year=$year")
                    }
                }
                val responseText = app.get(url, cacheTime = 60 * 60 * 12).text
                AppUtils.parseJson<TmdbSearchPage>(responseText).results
            }
        }.awaitAll().flatten()

        candidates.maxByOrNull { candidate -> scoreTmdbCandidate(candidate, queryTitles, year) }
    }

    private suspend fun searchAniList(title: String): List<AniListMedia> {
        val query = """
            query (${'$'}search: String) {
              Page(page: 1, perPage: 10) {
                media(search: ${'$'}search, type: ANIME, sort: [SEARCH_MATCH, POPULARITY_DESC]) {
                  id
                  idMal
                  title {
                    romaji
                    english
                    native
                    userPreferred
                  }
                  synonyms
                  format
                  status
                  seasonYear
                  startDate {
                    year
                    month
                    day
                  }
                  episodes
                  duration
                  averageScore
                  description(asHtml: false)
                  bannerImage
                  genres
                  trailer {
                    id
                    site
                    thumbnail
                  }
                  coverImage {
                    extraLarge
                    large
                  }
                  relations {
                    edges {
                      relationType
                      node {
                        id
                        idMal
                        format
                        seasonYear
                        startDate {
                          year
                          month
                          day
                        }
                        title {
                          romaji
                          english
                          native
                          userPreferred
                        }
                        synonyms
                        bannerImage
                        coverImage {
                          extraLarge
                          large
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val requestBody = mapOf(
            "query" to query,
            "variables" to mapOf("search" to title),
        ).toJson().toRequestBody("application/json".toMediaTypeOrNull())

        val responseText = app.post(aniListGraphQlUrl, requestBody = requestBody, cacheTime = 60 * 60 * 12).text
        return AppUtils.parseJson<AniListSearchResponse>(responseText).data?.page?.media.orEmpty()
    }

    private suspend fun fetchAniListById(id: Int): AniListMedia? {
        aniListMediaCache[id]?.let { cachedMedia ->
            return cachedMedia
        }

        val query = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id
                idMal
                title {
                  romaji
                  english
                  native
                  userPreferred
                }
                synonyms
                format
                status
                seasonYear
                startDate {
                  year
                  month
                  day
                }
                episodes
                duration
                averageScore
                description(asHtml: false)
                bannerImage
                genres
                trailer {
                  id
                  site
                  thumbnail
                }
                coverImage {
                  extraLarge
                  large
                }
                relations {
                  edges {
                    relationType
                    node {
                      id
                      idMal
                      format
                      seasonYear
                      startDate {
                        year
                        month
                        day
                      }
                      title {
                        romaji
                        english
                        native
                        userPreferred
                      }
                      synonyms
                      bannerImage
                      coverImage {
                        extraLarge
                        large
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val requestBody = mapOf(
            "query" to query,
            "variables" to mapOf("id" to id),
        ).toJson().toRequestBody("application/json".toMediaTypeOrNull())
        val responseText = app.post(aniListGraphQlUrl, requestBody = requestBody, cacheTime = 60 * 60 * 12).text
        val media = AppUtils.parseJson<AniListByIdResponse>(responseText).data?.media
        if (media != null) {
            aniListMediaCache[id] = media
        }
        return media
    }

    private fun chooseBestAniListMatch(
        candidates: List<AniListMedia>,
        queryTitles: List<String>,
        year: Int?,
    ): AniListMedia? {
        return candidates.maxByOrNull { candidate ->
            scoreAniListCandidate(candidate, queryTitles, year)
        }
    }

    private suspend fun fetchAniZip(anilistId: Int): AniZipResponse? {
        val responseText = app.get(
            url = "$aniZipUrl?anilist_id=$anilistId",
            cacheTime = 60 * 60 * 12,
        ).text
        return AppUtils.tryParseJson<AniZipResponse>(responseText)
    }

    private suspend fun fetchFribbAnimeEntry(anilistId: Int): FribbAnimeEntry? {
        val cachedEntries = fribbAnimeCache
        if (cachedEntries != null) {
            return cachedEntries.firstOrNull { fribbEntry -> fribbEntry.anilistId == anilistId }
        }

        val responseText = app.get(fribbAnimeListUrl, cacheTime = 60 * 60 * 24).text
        val parsedEntries = AppUtils.parseJson<List<FribbAnimeEntry>>(responseText)
        fribbAnimeCache = parsedEntries
        return parsedEntries.firstOrNull { fribbEntry -> fribbEntry.anilistId == anilistId }
    }

    private suspend fun getTmdbConfig(): TmdbConfiguration {
        tmdbConfigCache?.let { cachedConfig ->
            return cachedConfig
        }
        val responseText = app.get(
            url = "$tmdbApiBase/configuration?api_key=$tmdbApiKey",
            cacheTime = 60 * 60 * 24,
        ).text
        val config = AppUtils.parseJson<TmdbConfiguration>(responseText)
        tmdbConfigCache = config
        return config
    }

    private fun scoreTmdbCandidate(candidate: TmdbSearchResult, queryTitles: List<String>, year: Int?): Int {
        val candidateTitle = normalizeTitle(candidate.displayTitle).franchiseTitle
        val candidateYear = candidate.year
        val titleScore = queryTitles.maxOfOrNull { queryTitle ->
            tokenScore(candidateTitle, normalizeTitle(queryTitle).franchiseTitle)
        } ?: 0
    
        val yearScore = when {
            year == null || candidateYear == null -> 0
            year == candidateYear -> 25
            abs(year - candidateYear) <= 1 -> 10
            else -> -10
        }
    
        return titleScore + yearScore
    }

    private fun tokenScore(left: String, right: String): Int {
        val leftTokens = left.lowercase().split(" ").filter { token -> token.isNotBlank() }.toSet()
        val rightTokens = right.lowercase().split(" ").filter { token -> token.isNotBlank() }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return if (left.equals(right, ignoreCase = true)) 100 else 0
        }
        val overlap = leftTokens.intersect(rightTokens).size
        val exactBonus = if (left.equals(right, ignoreCase = true)) 100 else 0
        return exactBonus + (overlap * 20) - abs(leftTokens.size - rightTokens.size) * 4
    }

    private fun applyAnimeSyncData(loadResponse: LoadResponse, animeMeta: AnimeMetaBundle?) {
        val selectedMedia = animeMeta?.selectedMedia ?: return
        val aniZip = animeMeta.aniZip
        val fribbEntry = animeMeta.fribb

        loadResponse.addAniListId(selectedMedia.id)
        loadResponse.addMalId(aniZip?.mappings?.malId ?: selectedMedia.idMal)
        val kitsuId = aniZip?.mappings?.kitsuId ?: fribbEntry?.kitsuId
        loadResponse.addKitsuId(kitsuId)
        val simklId = aniZip?.mappings?.simklId ?: fribbEntry?.simklId
        loadResponse.addSimklId(simklId)
        val tmdbId = aniZip?.mappings?.tmdbId?.tv ?: aniZip?.mappings?.tmdbId?.movie ?: fribbEntry?.tmdbId?.tv ?: fribbEntry?.tmdbId?.movie
        if (tmdbId != null) {
            loadResponse.addTMDbId(tmdbId.toString())
        }
        val imdbId = aniZip?.mappings?.imdbId ?: fribbEntry?.imdbId
        if (!imdbId.isNullOrBlank()) {
            loadResponse.addImdbId(imdbId)
        }
    }

    private suspend fun applyAnimeTrailer(loadResponse: LoadResponse, animeMeta: AnimeMetaBundle?) {
        val trailerUrl = animeMeta?.selectedMedia?.trailer?.fullUrl() ?: return
        loadResponse.addTrailer(trailerUrl)
    }

    private fun buildTmdbImageUrl(
        config: TmdbConfiguration,
        path: String?,
        imageKind: TmdbImageKind,
    ): String? {
        if (path.isNullOrBlank()) {
            return null
        }
        val baseUrl = config.images?.secureBaseUrl ?: "https://image.tmdb.org/t/p/"
        val size = when (imageKind) {
            TmdbImageKind.POSTER -> config.images?.posterSizes?.lastOrNull() ?: "original"
            TmdbImageKind.BACKDROP -> config.images?.backdropSizes?.lastOrNull() ?: "original"
            TmdbImageKind.LOGO -> config.images?.logoSizes?.lastOrNull() ?: "original"
            TmdbImageKind.PROFILE -> config.images?.profileSizes?.lastOrNull() ?: "original"
        }
        return "$baseUrl$size$path"
    }

    private fun buildTmdbLogoUrl(
        config: TmdbConfiguration,
        logos: List<TmdbLogo>,
    ): String? {
        val logo = logos
            .sortedWith(
                compareByDescending<TmdbLogo> { logoEntry -> if (logoEntry.iso6391 == "en") 1 else 0 }
                    .thenByDescending { logoEntry -> logoEntry.voteAverage ?: 0.0 }
                    .thenByDescending { logoEntry -> logoEntry.width ?: 0 }
            )
            .firstOrNull()
            ?: return null
        return buildTmdbImageUrl(config, logo.filePath, TmdbImageKind.LOGO)
    }

    private fun pickTmdbTrailerUrl(videos: List<TmdbVideo>): String? {
        val video = videos
            .sortedWith(
                compareByDescending<TmdbVideo> { tmdbVideo -> if (tmdbVideo.official == true) 1 else 0 }
                    .thenByDescending { tmdbVideo -> if (tmdbVideo.type.equals("Trailer", ignoreCase = true)) 1 else 0 }
                    .thenByDescending { tmdbVideo -> if (tmdbVideo.type.equals("Teaser", ignoreCase = true)) 1 else 0 }
            )
            .firstOrNull { tmdbVideo -> tmdbVideo.site.equals("YouTube", ignoreCase = true) && !tmdbVideo.key.isNullOrBlank() }
            ?: return null
        return "https://www.youtube.com/watch?v=${video.key}"
    }

    private fun buildPosterUrl(imagePath: String?): String? {
        if (imagePath.isNullOrBlank()) {
            return null
        }
        return "$mainApiUrl/uploads/$imagePath"
    }

    private fun buildSourceLabel(ref: PayloadPostRef, data: Data): String {
        val labelParts = mutableListOf<String>()
        labelParts += "FTP"
        val audioPart = ref.audioTag ?: normalizeTitle(ref.rawTitle.ifBlank { data.title }).audioTag
        if (!audioPart.isNullOrBlank()) {
            labelParts += "[$audioPart]"
        }
        val qualityPart = normalizeTitle(ref.rawTitle.ifBlank { data.title }).qualityTag
        if (!qualityPart.isNullOrBlank()) {
            labelParts += "[$qualityPart]"
        }
        return labelParts.joinToString(" ")
    }

    private fun normalizeCircleStream(rawUrl: String?, usedFallbackApi: Boolean): String {
        val streamUrl = rawUrl.orEmpty()
        return if (usedFallbackApi) {
            linkToIp(streamUrl)
        } else {
            streamUrl
        }
    }

    private fun FetchedPost.dataSourceRequiresIp(): Boolean {
        return this.usedFallbackApi
    }

    private fun encodePayload(payload: CirclePayload): String {
        val rawJson = payload.toJson().toByteArray()
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(rawJson)
        return "$payloadPrefix$encoded"
    }

    private fun decodePayload(url: String): CirclePayload? {
        if (!url.startsWith(payloadPrefix)) {
            return null
        }
        return runCatching {
            val encoded = url.removePrefix(payloadPrefix)
            val decodedBytes = Base64.getUrlDecoder().decode(encoded)
            val decodedText = String(decodedBytes)
            AppUtils.tryParseJson<CirclePayload>(decodedText)
        }.getOrNull()
    }

    private fun buildLegacyPayload(url: String): CirclePayload {
        val legacyId = url.substringAfterLast("/").substringBefore("?").toIntOrNull()
            ?: throw ErrorLoadingException("Invalid Circle FTP URL")
        return CirclePayload(
            mediaType = TvType.Others.name,
            displayTitle = "Circle FTP",
            franchiseTitle = "Circle FTP",
            year = null,
            activeSeason = 1,
            posts = listOf(
                PayloadPostRef(
                    id = legacyId,
                    rawTitle = "",
                    imageSm = null,
                    declaredSeason = 1,
                    audioTag = null,
                    postType = null,
                )
            ),
        )
    }

    private suspend fun apiGetText(path: String, cacheTime: Int): ApiTextResponse {
        return try {
            val response = app.get(
                url = "$mainApiUrl$path",
                verify = false,
                cacheTime = cacheTime,
            )
            ApiTextResponse(response.text, usedFallback = false)
        } catch (_: Exception) {
            val response = app.get(
                url = "$fallbackApiUrl$path",
                verify = false,
                cacheTime = cacheTime,
            )
            ApiTextResponse(response.text, usedFallback = true)
        }
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }
    private fun String?.selectUntilNonInt(): Int? {
        return this?.let { rawString ->
        Regex("^.*?(?=\\D|$)").find(rawString)?.value?.toIntOrNull()
        }
    }

    private fun parseSeasonNumber(value: String?): Int? {
        val cleanedValue = value.orEmpty()
        val regexes = listOf(
            Regex("(?:season|s)\\s*0*(\\d{1,2})", RegexOption.IGNORE_CASE),
            Regex("(\\d{1,2})(?:st|nd|rd|th)?\\s*season", RegexOption.IGNORE_CASE),
            Regex("part\\s*0*(\\d{1,2})", RegexOption.IGNORE_CASE),
            Regex("cour\\s*0*(\\d{1,2})", RegexOption.IGNORE_CASE),
        )
        regexes.forEach { regex ->
            val match = regex.find(cleanedValue)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (match != null) {
                return match
            }
        }
        return null
    }

    private fun extractEpisodeNumber(title: String?): Int? {
        val cleanedTitle = title.orEmpty()
        val regexes = listOf(
            Regex("(?:episode|ep|e)\\s*0*(\\d{1,4})", RegexOption.IGNORE_CASE),
            Regex("(?:^|\\D)(\\d{1,4})(?:\\D|$)"),
        )
        regexes.forEach { regex ->
            val parsedNumber = regex.find(cleanedTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (parsedNumber != null) {
                return parsedNumber
            }
        }
        return null
    }

    private fun cleanupEpisodeTitle(title: String?, episodeNumber: Int): String {
        val cleaned = title.orEmpty()
            .replace(Regex("""(?:episode|ep)\s*0*$episodeNumber\s*[:-]?""", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ')
            .replace('.', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.isBlank()) {
            "Episode $episodeNumber"
        } else {
            cleaned
        }
    }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val lowercaseCheck = check?.lowercase() ?: return null
        return when {
            lowercaseCheck.contains("2160p") || lowercaseCheck.contains("4k") -> SearchQuality.FourK
            lowercaseCheck.contains("webrip") || lowercaseCheck.contains("web-dl") || lowercaseCheck.contains("webdl") -> SearchQuality.WebRip
            lowercaseCheck.contains("bluray") || lowercaseCheck.contains("blu-ray") -> SearchQuality.BlueRay
            lowercaseCheck.contains("hdts") || lowercaseCheck.contains("hdcam") || lowercaseCheck.contains("hdtc") -> SearchQuality.HdCam
            lowercaseCheck.contains("dvd") -> SearchQuality.DVD
            lowercaseCheck.contains("camrip") -> SearchQuality.CamRip
            lowercaseCheck.contains("cam") -> SearchQuality.Cam
            lowercaseCheck.contains("hdrip") || lowercaseCheck.contains("hdtv") -> SearchQuality.HD
            lowercaseCheck.contains("hdr") -> SearchQuality.HDR
            lowercaseCheck.contains("uhd") -> SearchQuality.UHD
            lowercaseCheck.contains("sdr") -> SearchQuality.SDR
            lowercaseCheck.contains("1080p") || lowercaseCheck.contains("720p") || lowercaseCheck.contains("hd") -> SearchQuality.HD
            lowercaseCheck.contains("telesync") -> SearchQuality.Telesync
            lowercaseCheck.contains("telecine") -> SearchQuality.Telecine
            else -> null
        }
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

    private fun normalizeTitle(rawTitle: String): NormalizedTitle {
        val raw = rawTitle
            .replace('_', ' ')
            .replace('.', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        val audioTag = when {
            Regex("dual\\s*audio", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Dual-Audio"
            Regex("multi\\s*audio", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Multi-Audio"
            Regex("subbed", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Subbed"
            Regex("dubbed", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Dubbed"
            Regex("hindi", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Hindi"
            Regex("english", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "English"
            else -> null
        }
        val quality = getSearchQuality(raw)
        val qualityTag = Regex("(2160p|1080p|720p|480p|4k|bluray|blu-ray|web-dl|webrip|hdrip|hdr|uhd|sdr)", RegexOption.IGNORE_CASE)
            .find(raw)?.value?.uppercase()

        val year = Regex("(?<!\\d)(19\\d{2}|20\\d{2}|21\\d{2})(?!\\d)")
            .find(raw)?.value?.toIntOrNull()
        val season = parseSeasonNumber(raw)

        val noiseRegex = Regex(
            """
            (?ix)
            (\[.*?(dual\s*audio|multi\s*audio|dubbed|subbed|bluray|blu-ray|web[- ]?dl|webrip|hdrip|2160p|1080p|720p|480p|x264|x265|hevc|hdr|sdr|hindi|english).*?])|
            (\(.*?(dual\s*audio|multi\s*audio|dubbed|subbed|bluray|blu-ray|web[- ]?dl|webrip|hdrip|2160p|1080p|720p|480p|x264|x265|hevc|hdr|sdr|hindi|english).*?\))|
            \b(?:bluray|blu-ray|web[- ]?dl|webrip|hdrip|brrip|dvdrip|2160p|1080p|720p|480p|4k|x264|x265|h264|h265|hevc|aac|dts|ddp5\.1|dual\s*audio|multi\s*audio|dubbed|subbed|hindi|english|multi|audio|hdr|sdr)\b
            """.trimIndent(),
        )
        val cleanedDisplay = noiseRegex.replace(raw, " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("""[\[\](){}]"""), " ")
            .trim()

        val franchiseTitle = cleanedDisplay
            .replace(Regex("(?:season|s)\\s*0*(\\d{1,2})", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(\\d{1,2})(?:st|nd|rd|th)?\\s*season", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("part\\s*0*(\\d{1,2})", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("cour\\s*0*(\\d{1,2})", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(19\\d{2}|20\\d{2}|21\\d{2})\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { cleanedDisplay.ifBlank { raw } }

        val displayTitle = cleanedDisplay.ifBlank { raw }
        val canonicalTitle = displayTitle.replace(Regex("\\s+"), " ").trim()

        return NormalizedTitle(
            displayTitle = displayTitle,
            canonicalTitle = canonicalTitle,
            franchiseTitle = franchiseTitle,
            franchiseKey = franchiseTitle.lowercase(),
            canonicalKey = canonicalTitle.lowercase(),
            year = year,
            season = season,
            quality = quality,
            qualityTag = qualityTag,
            audioTag = audioTag,
        )
    }

    private fun linkToIp(data: String): String {
        val hostMap = mapOf(
            "index.circleftp.net" to "15.1.4.2",
            "index2.circleftp.net" to "15.1.4.5",
            "index1.circleftp.net" to "15.1.4.9",
            "ftp3.circleftp.net" to "15.1.4.7",
            "ftp4.circleftp.net" to "15.1.1.5",
            "ftp5.circleftp.net" to "15.1.1.15",
            "ftp6.circleftp.net" to "15.1.2.3",
            "ftp7.circleftp.net" to "15.1.4.8",
            "ftp8.circleftp.net" to "15.1.2.2",
            "ftp9.circleftp.net" to "15.1.2.12",
            "ftp10.circleftp.net" to "15.1.4.3",
            "ftp11.circleftp.net" to "15.1.2.6",
            "ftp12.circleftp.net" to "15.1.2.1",
            "ftp13.circleftp.net" to "15.1.1.18",
            "ftp15.circleftp.net" to "15.1.4.12",
            "ftp17.circleftp.net" to "15.1.3.8",
        )
        var transformed = data
        hostMap.forEach { (hostName, ipAddress) ->
            transformed = transformed.replace(hostName, ipAddress)
        }
        return transformed
    }

    private fun AniListMedia.preferredTitle(): String {
        return this.title?.english
            ?: this.title?.romaji
            ?: this.title?.native
            ?: this.title?.userPreferred
            ?: "Unknown Anime"
    }

    private fun AniListMedia.cleanDescription(): String? {
        return this.description
            ?.replace("<br>", "\n", ignoreCase = true)
            ?.replace(Regex("<[^>]+>"), "")
            ?.trim()
    }

    private fun AniListMedia.allTitles(): List<String> {
        return buildList {
            title?.english?.let { englishTitle -> add(englishTitle) }
            title?.romaji?.let { romajiTitle -> add(romajiTitle) }
            title?.native?.let { nativeTitle -> add(nativeTitle) }
            title?.userPreferred?.let { preferred -> add(preferred) }
            synonyms.orEmpty().forEach { synonym -> if (!synonym.isNullOrBlank()) add(synonym) }
        }.distinct()
    }

    private fun AniListMedia.resolvedYear(): Int? {
        return seasonYear ?: startDate?.year
    }

    private fun AniListMedia.score(): Score? {
        return Score.from100(this.averageScore)
    }

    private fun AniListMedia.toShowStatus(): ShowStatus? {
        return when (status?.uppercase()) {
            "FINISHED" -> ShowStatus.Completed
            "RELEASING" -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun AniListTrailer.fullUrl(): String? {
        val trailerId = id ?: return null
        return when (site?.lowercase()) {
            "youtube" -> "https://www.youtube.com/watch?v=$trailerId"
            else -> null
        }
    }

    private fun AniZipResponse.bestLogoUrl(): String? {
        return images.orEmpty().firstOrNull { aniZipImage -> aniZipImage.coverType.equals("Clearlogo", ignoreCase = true) }?.url
    }

    private fun AniZipResponse.bestBackgroundUrl(): String? {
        return images.orEmpty().firstOrNull { aniZipImage -> aniZipImage.coverType.equals("Fanart", ignoreCase = true) || aniZipImage.coverType.equals("Banner", ignoreCase = true) }?.url
    }

    private fun AniZipEpisode.preferredTitle(): String? {
        return title?.en ?: title?.ja ?: title?.xJat
    }

    private fun isMainlineAnimeFormat(format: String?): Boolean {
        return when (format?.uppercase()) {
            "TV", "TV_SHORT", "ONA", "OVA", "MOVIE" -> true
            else -> false
        }
    }

    private fun String?.toTmdbShowStatus(): ShowStatus? {
        return when (this?.uppercase()) {
            "ENDED" -> ShowStatus.Completed
            "RETURNING SERIES", "IN PRODUCTION", "PLANNED", "PILOT" -> ShowStatus.Ongoing
            else -> null
        }
    }

    private data class ApiTextResponse(
        val text: String,
        val usedFallback: Boolean,
    )

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
        val quality: SearchQuality?,
        val audioTag: String?,
        val franchiseKey: String,
        val audioMergeKey: String,
    ) {
        fun toPayloadPost(): PayloadPostRef {
            return PayloadPostRef(
                id = id,
                rawTitle = rawTitle,
                imageSm = imageSm,
                declaredSeason = declaredSeason,
                audioTag = audioTag,
                postType = postType,
            )
        }
    }

    private data class NormalizedTitle(
        val displayTitle: String,
        val canonicalTitle: String,
        val franchiseTitle: String,
        val franchiseKey: String,
        val canonicalKey: String,
        val year: Int?,
        val season: Int?,
        val quality: SearchQuality?,
        val qualityTag: String?,
        val audioTag: String?,
    )

    private data class CirclePayload(
        val mediaType: String,
        val displayTitle: String,
        val franchiseTitle: String,
        val year: Int?,
        val activeSeason: Int?,
        val posts: List<PayloadPostRef>,
    )

    private data class PayloadPostRef(
        val id: Int,
        val rawTitle: String,
        val imageSm: String?,
        val declaredSeason: Int?,
        val audioTag: String?,
        val postType: String?,
    )

    private data class FetchedPost(
        val ref: PayloadPostRef,
        val data: Data,
        val movieLink: String?,
        val tvSeries: TvSeries?,
        val usedFallbackApi: Boolean,
    )

    private data class SeasonSlice(
        val fetchedPost: FetchedPost,
        val globalSeason: Int,
        val seasonName: String?,
        val episodes: List<EpisodeData>,
    )

    private data class EpisodeAccumulator(
        val season: Int,
        val episode: Int,
        var fallbackName: String,
        val sources: MutableList<StreamVariant> = mutableListOf(),
    )

    private data class StreamPayload(
        val variants: List<StreamVariant>,
    )

    private data class StreamVariant(
        val label: String,
        val url: String,
    )

    private data class AnimeMetaBundle(
        val selectedMedia: AniListMedia,
        val seasonChain: List<AniListMedia>,
        val aniZip: AniZipResponse?,
        val fribb: FribbAnimeEntry?,
        val posterUrl: String?,
        val backgroundPosterUrl: String?,
        val logoUrl: String?,
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
        val actors: List<Pair<Actor, String?>>?,
        val trailerUrl: String?,
        val showStatus: ShowStatus?,
    )

    private enum class TmdbImageKind {
        POSTER,
        BACKDROP,
        LOGO,
        PROFILE,
    }

    private fun String.toTvType(): TvType {
        return TvType.entries.firstOrNull { tvType -> tvType.name.equals(this, ignoreCase = true) } ?: TvType.Others
    }
}

    data class PageData(
        @JsonProperty("posts") val posts: List<Post> = emptyList(),
    )

    data class Post(
        @JsonProperty("id") val id: Int,
        @JsonProperty("type") val type: String,
        @JsonProperty("imageSm") val imageSm: String? = null,
        @JsonProperty("title") val title: String,
        @JsonProperty("name") val name: String? = null,
    )

    data class Data(
        @JsonProperty("type") val type: String,
        @JsonProperty("imageSm") val imageSm: String? = null,
        @JsonProperty("title") val title: String,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("metaData") val metaData: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("watchTime") val watchTime: String? = null,
    )

    data class TvSeries(
        @JsonProperty("content") val content: List<SeasonContent> = emptyList(),
    )

    data class SeasonContent(
        @JsonProperty("episodes") val episodes: List<EpisodeData> = emptyList(),
        @JsonProperty("seasonName") val seasonName: String? = null,
    )

    data class EpisodeData(
        @JsonProperty("link") val link: String,
        @JsonProperty("title") val title: String? = null,
    )

    data class Movies(
        @JsonProperty("content") val content: String? = null,
    )

    data class TmdbConfiguration(
        @JsonProperty("images") val images: TmdbImageConfig? = null,
    )

    data class TmdbImageConfig(
        @JsonProperty("secure_base_url") val secureBaseUrl: String? = null,
        @JsonProperty("poster_sizes") val posterSizes: List<String>? = null,
        @JsonProperty("backdrop_sizes") val backdropSizes: List<String>? = null,
        @JsonProperty("logo_sizes") val logoSizes: List<String>? = null,
        @JsonProperty("profile_sizes") val profileSizes: List<String>? = null,
    )

    data class TmdbSearchPage(
        @JsonProperty("results") val results: List<TmdbSearchResult> = emptyList(),
    )

    data class TmdbSearchResult(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
    ) {
        val displayTitle: String
            get() = title ?: name ?: originalTitle ?: originalName ?: ""
        val year: Int?
            get() = releaseDate?.take(4)?.toIntOrNull() ?: firstAirDate?.take(4)?.toIntOrNull()
    }

    data class TmdbMovieDetails(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("videos") val videos: TmdbVideos? = null,
        @JsonProperty("images") val images: TmdbImages? = null,
        @JsonProperty("external_ids") val externalIds: TmdbExternalIds? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null,
    )

    data class TmdbTvDetails(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("videos") val videos: TmdbVideos? = null,
        @JsonProperty("images") val images: TmdbImages? = null,
        @JsonProperty("external_ids") val externalIds: TmdbExternalIds? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null,
        @JsonProperty("status") val status: String? = null,
    )

    data class TmdbGenre(
        @JsonProperty("name") val name: String? = null,
    )

    data class TmdbVideos(
        @JsonProperty("results") val results: List<TmdbVideo> = emptyList(),
    )

    data class TmdbVideo(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("site") val site: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("official") val official: Boolean? = null,
    )

    data class TmdbImages(
        @JsonProperty("logos") val logos: List<TmdbLogo> = emptyList(),
    )

    data class TmdbLogo(
        @JsonProperty("file_path") val filePath: String? = null,
        @JsonProperty("iso_639_1") val iso6391: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("width") val width: Int? = null,
    )

    data class TmdbExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null,
    )

    data class TmdbCredits(
        @JsonProperty("cast") val cast: List<TmdbCast> = emptyList(),
    )

    data class TmdbCast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class AniListSearchResponse(
        @JsonProperty("data") val data: AniListSearchData? = null,
    )

    data class AniListSearchData(
        @JsonProperty("Page") val page: AniListPage? = null,
    )

    data class AniListPage(
        @JsonProperty("media") val media: List<AniListMedia> = emptyList(),
    )

    data class AniListByIdResponse(
        @JsonProperty("data") val data: AniListByIdData? = null,
    )

    data class AniListByIdData(
        @JsonProperty("Media") val media: AniListMedia? = null,
    )

    data class AniListMedia(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("idMal") val idMal: Int? = null,
        @JsonProperty("title") val title: AniListTitle? = null,
        @JsonProperty("synonyms") val synonyms: List<String?>? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("seasonYear") val seasonYear: Int? = null,
        @JsonProperty("startDate") val startDate: AniListDate? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("duration") val duration: Int? = null,
        @JsonProperty("averageScore") val averageScore: Int? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("genres") val genres: List<String?>? = null,
        @JsonProperty("trailer") val trailer: AniListTrailer? = null,
        @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
        @JsonProperty("relations") val relations: AniListRelations? = null,
    )

    data class AniListTitle(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null,
        @JsonProperty("userPreferred") val userPreferred: String? = null,
    )

    data class AniListDate(
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("month") val month: Int? = null,
        @JsonProperty("day") val day: Int? = null,
    )

    data class AniListTrailer(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("site") val site: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
    )

    data class AniListCoverImage(
        @JsonProperty("extraLarge") val extraLarge: String? = null,
        @JsonProperty("large") val large: String? = null,
    )

    data class AniListRelations(
        @JsonProperty("edges") val edges: List<AniListRelationEdge> = emptyList(),
    )

    data class AniListRelationEdge(
        @JsonProperty("relationType") val relationType: String = "",
        @JsonProperty("node") val node: AniListMedia = AniListMedia(),
    )

    data class AniZipResponse(
        @JsonProperty("images") val images: List<AniZipImage>? = null,
        @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>? = null,
        @JsonProperty("mappings") val mappings: AniZipMappings? = null,
    )

    data class AniZipImage(
        @JsonProperty("coverType") val coverType: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    data class AniZipMappings(
        @JsonProperty("anilist_id") val anilistId: Int? = null,
        @JsonProperty("mal_id") val malId: Int? = null,
        @JsonProperty("kitsu_id") val kitsuId: Int? = null,
        @JsonProperty("simkl_id") val simklId: Int? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("themoviedb_id") val tmdbId: AniZipTmdbId? = null,
    )

    data class AniZipTmdbId(
        @JsonProperty("movie") val movie: Int? = null,
        @JsonProperty("tv") val tv: Int? = null,
    )

    data class AniZipEpisode(
        @JsonProperty("title") val title: AniZipEpisodeTitle? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("summary") val summary: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("length") val length: Int? = null,
        @JsonProperty("airDate") val airDate: String? = null,
        @JsonProperty("airDateUtc") val airDateUtc: String? = null,
        @JsonProperty("rating") val rating: String? = null,
    )

    data class AniZipEpisodeTitle(
        @JsonProperty("en") val en: String? = null,
        @JsonProperty("ja") val ja: String? = null,
        @JsonProperty("x-jat") val xJat: String? = null,
    )

    data class FribbAnimeEntry(
        @JsonProperty("anilist_id") val anilistId: Int? = null,
        @JsonProperty("mal_id") val malId: Int? = null,
        @JsonProperty("kitsu_id") val kitsuId: Int? = null,
        @JsonProperty("simkl_id") val simklId: Int? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("themoviedb_id") val tmdbId: AniZipTmdbId? = null,
    )
}
