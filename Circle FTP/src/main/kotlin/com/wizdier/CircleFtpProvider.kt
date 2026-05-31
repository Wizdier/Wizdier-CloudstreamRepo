package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder
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

    private val animeDetectionCache = mutableMapOf<String, Boolean>()
    private val aniListMediaCache = mutableMapOf<Int, AniListMedia>()
    private var tmdbConfigCache: TmdbConfiguration? = null

    // ==============================
    // Main Page & Search
    // ==============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val posts = fetchPosts("categoryExact=${request.data}&page=$page&order=desc&limit=10")
        val tvTypeHint = categoryTypeMap[request.data]
        val results = buildSearchResults(posts, tvTypeHint)
        return newHomePageResponse(request.name, results, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val posts = fetchPosts("searchTerm=${urlEncode(query)}&order=desc")
        return buildSearchResults(posts, null)
    }

    private suspend fun fetchPosts(params: String): List<Post> {
        val response = apiGet("/api/posts?$params", cacheTime = 60)
        return response.parsed<PageData>().posts
    }

    private suspend fun buildSearchResults(posts: List<Post>, tvTypeHint: TvType?): List<SearchResponse> = coroutineScope {
        val indexed = posts.mapNotNull { post ->
            if (post.type != "singleVideo" && post.type != "series") return@mapNotNull null
            val normalized = normalizeTitle(post.title)
            val resolvedType = tvTypeHint ?: inferType(post, normalized)
            IndexedPost(
                id = post.id, imageSm = post.imageSm, rawTitle = post.title,
                mediaType = resolvedType, postType = post.type,
                displayTitle = if (resolvedType == TvType.Anime || resolvedType == TvType.AnimeMovie || resolvedType == TvType.OVA)
                    normalized.franchiseTitle else normalized.displayTitle,
                franchiseTitle = normalized.franchiseTitle, year = normalized.year,
                declaredSeason = normalized.season, quality = normalized.quality,
                audioTag = normalized.audioTag, franchiseKey = normalized.franchiseKey
            )
        }

        val animeGroups = indexed.filter { it.mediaType == TvType.Anime || it.mediaType == TvType.AnimeMovie || it.mediaType == TvType.OVA }
            .groupBy { "${it.mediaType.name}:${it.franchiseKey}:${it.year ?: 0}" }
        val normalGroups = indexed.filterNot { it.mediaType == TvType.Anime || it.mediaType == TvType.AnimeMovie || it.mediaType == TvType.OVA }
            .groupBy { "${it.mediaType.name}:${it.franchiseKey}" }

        val results = mutableListOf<SearchResponse>()

        animeGroups.values.sortedBy { it.minOfOrNull { p -> p.id } ?: Int.MAX_VALUE }.forEach { group ->
            val baseline = group.minByOrNull { it.id } ?: return@forEach
            val posterUrl = buildPosterUrl(baseline.imageSm)
            val hasDub = group.any { it.audioTag?.contains("dub", ignoreCase = true) == true || it.audioTag?.contains("dual", ignoreCase = true) == true || it.audioTag?.contains("multi", ignoreCase = true) == true }
            val hasSub = group.any { it.audioTag?.contains("sub", ignoreCase = true) == true } || !hasDub
            val quality = group.mapNotNull { it.quality }.maxByOrNull { it.ordinal }

            results += newAnimeSearchResponse(baseline.franchiseTitle, buildLoadUrl(baseline, group), baseline.mediaType) {
                this.posterUrl = posterUrl
                this.year = baseline.year
                this.id = baseline.id
                this.quality = quality
                addDubStatus(dubExist = hasDub, subExist = hasSub)
            }
        }

        normalGroups.values.sortedBy { it.minOfOrNull { p -> p.id } ?: Int.MAX_VALUE }.forEach { group ->
            val primary = group.minByOrNull { it.id } ?: return@forEach
            val posterUrl = buildPosterUrl(primary.imageSm)
            val quality = group.mapNotNull { it.quality }.maxByOrNull { it.ordinal }
            val url = buildLoadUrl(primary, group)

            if (primary.postType == "series" || primary.mediaType == TvType.TvSeries || primary.mediaType == TvType.AsianDrama) {
                results += newTvSeriesSearchResponse(primary.displayTitle, url, primary.mediaType) {
                    this.posterUrl = posterUrl
                    this.year = primary.year
                    this.id = primary.id
                    this.quality = quality
                }
            } else {
                results += newMovieSearchResponse(primary.displayTitle, url, primary.mediaType) {
                    this.posterUrl = posterUrl
                    this.year = primary.year
                    this.id = primary.id
                    this.quality = quality
                }
            }
        }
        results
    }

    // Build load URL with related post IDs as query params
    private fun buildLoadUrl(primary: IndexedPost, group: List<IndexedPost>): String {
        if (group.size == 1) return "$mainUrl/content/${primary.id}"
        val relatedIds = group.map { it.id }.distinct().joinToString(",")
        return "$mainUrl/content/${primary.id}?related=$relatedIds&franchise=${urlEncode(primary.franchiseTitle)}&type=${primary.mediaType.name}"
    }

    // ==============================
    // LOAD
    // ==============================
    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val id = url.substringAfterLast("/").substringBefore("?").toIntOrNull()
            ?: throw ErrorLoadingException("Invalid URL: $url")
        val relatedIds = url.substringAfter("?", "").let { query ->
            Regex("""related=([^&]+)""").find(query)?.groupValues?.get(1)?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        }
        val franchise = Regex("""franchise=([^&]+)""").find(url)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val typeHint = Regex("""type=([^&]+)""").find(url)?.groupValues?.get(1)

        val allIds = (listOf(id) + relatedIds).distinct()
        val fetchedPosts = allIds.map { postId ->
            async { fetchPostDetails(postId) }
        }.awaitAll()

        val primaryPost = fetchedPosts.firstOrNull() ?: throw ErrorLoadingException("Post not found")
        val primaryData = primaryPost.data
        val resolvedType = typeHint?.toTvType() ?: inferTypeFromData(primaryData)

        // Anime path
        if (resolvedType == TvType.Anime || resolvedType == TvType.AnimeMovie || resolvedType == TvType.OVA) {
            return@coroutineScope loadAnimeContent(fetchedPosts, resolvedType, franchise)
        }

        // Standard path
        loadStandardContent(fetchedPosts, resolvedType, franchise)
    }

    private suspend fun loadAnimeContent(fetchedPosts: List<FetchedPost>, mediaType: TvType, franchise: String?): LoadResponse = coroutineScope {
        val isMovie = fetchedPosts.all { it.data.type == "singleVideo" }
        val primaryPost = fetchedPosts.first()

        if (isMovie) {
            val streamUrl = primaryPost.movieLink ?: throw ErrorLoadingException("No stream URL")
            val animeMeta = resolveAnimeMetadata(franchise ?: primaryPost.data.title, primaryPost.data.year?.selectUntilNonInt())
            return@coroutineScope newMovieLoadResponse(
                animeMeta?.selectedMedia?.preferredTitle() ?: primaryPost.data.bestTitle(),
                "$mainUrl/content/${primaryPost.id}", mediaType, streamUrl
            ) {
                this.posterUrl = animeMeta?.posterUrl ?: buildPosterUrl(primaryPost.data.image)
                this.backgroundPosterUrl = animeMeta?.backgroundPosterUrl ?: this.posterUrl
                this.logoUrl = animeMeta?.logoUrl
                this.plot = animeMeta?.selectedMedia?.cleanDescription() ?: primaryPost.data.metaData
                this.year = animeMeta?.selectedMedia?.resolvedYear() ?: primaryPost.data.year.selectUntilNonInt()
                this.duration = animeMeta?.selectedMedia?.duration ?: getDurationFromString(primaryPost.data.watchTime)
                this.tags = animeMeta?.selectedMedia?.genres?.filterNotNull()?.filter { it.isNotBlank() }
                addScore(animeMeta?.selectedMedia?.score())
                applyAnimeSyncData(this, animeMeta)
                applyAnimeTrailer(this, animeMeta)
            }
        }

        // TV series anime
        val allSeasons = fetchedPosts.flatMap { discoverSeasons(it) }
        val activeSeason = allSeasons.map { it.globalSeason }.distinct().minOrNull() ?: 1
        val activeSlices = allSeasons.filter { it.globalSeason == activeSeason }
        val animeMeta = resolveAnimeMetadata(franchise ?: primaryPost.data.title, primaryPost.data.year?.selectUntilNonInt())
        val episodes = mergeEpisodes(activeSlices, activeSeason, animeMeta?.aniZip)
        val displayName = animeMeta?.selectedMedia?.preferredTitle()?.ifBlank { primaryPost.data.bestTitle() }
            ?: primaryPost.data.bestTitle()

        val recommendations = allSeasons.map { it.globalSeason }.distinct().filter { it != activeSeason }.map { seasonNum ->
            newAnimeSearchResponse("$displayName Season $seasonNum", "$mainUrl/content/${primaryPost.id}?season=$seasonNum", mediaType) {
                this.posterUrl = animeMeta?.posterUrl
                this.year = animeMeta?.selectedMedia?.resolvedYear()
            }
        }

        return@coroutineScope newAnimeLoadResponse(displayName, "$mainUrl/content/${primaryPost.id}", mediaType) {
            addEpisodes(DubStatus.Dubbed, episodes)
            this.posterUrl = animeMeta?.posterUrl ?: buildPosterUrl(primaryPost.data.image)
            this.backgroundPosterUrl = animeMeta?.backgroundPosterUrl ?: this.posterUrl
            this.logoUrl = animeMeta?.logoUrl
            this.plot = animeMeta?.selectedMedia?.cleanDescription() ?: primaryPost.data.metaData
            this.year = animeMeta?.selectedMedia?.resolvedYear() ?: primaryPost.data.year.selectUntilNonInt()
            this.duration = animeMeta?.selectedMedia?.duration ?: getDurationFromString(primaryPost.data.watchTime)
            this.tags = animeMeta?.selectedMedia?.genres?.filterNotNull()?.filter { it.isNotBlank() }
            this.recommendations = recommendations
            this.engName = animeMeta?.selectedMedia?.title?.english
            this.japName = animeMeta?.selectedMedia?.title?.romaji ?: animeMeta?.selectedMedia?.title?.native
            addScore(animeMeta?.selectedMedia?.score())
            applyAnimeSyncData(this, animeMeta)
            applyAnimeTrailer(this, animeMeta)
        }
    }

    private suspend fun loadStandardContent(fetchedPosts: List<FetchedPost>, mediaType: TvType, franchise: String?): LoadResponse = coroutineScope {
        val primaryPost = fetchedPosts.first()
        val isSeries = !mediaType.isMovieType() && fetchedPosts.any { it.data.type == "series" }
        val tmdbMeta = resolveTmdbMetadata(mediaType, listOfNotNull(franchise, primaryPost.data.title).filter { it.isNotBlank() }, primaryPost.data.year?.selectUntilNonInt(), isSeries)

        if (!isSeries) {
            val streamUrl = primaryPost.movieLink ?: throw ErrorLoadingException("No stream URL")
            return@coroutineScope newMovieLoadResponse(
                tmdbMeta?.displayTitle ?: primaryPost.data.bestTitle(),
                "$mainUrl/content/${primaryPost.id}", mediaType, streamUrl
            ) {
                this.posterUrl = tmdbMeta?.posterUrl ?: buildPosterUrl(primaryPost.data.image)
                this.backgroundPosterUrl = tmdbMeta?.backgroundPosterUrl ?: this.posterUrl
                this.logoUrl = tmdbMeta?.logoUrl
                this.plot = tmdbMeta?.plot ?: primaryPost.data.metaData
                this.year = tmdbMeta?.year ?: primaryPost.data.year.selectUntilNonInt()
                this.duration = tmdbMeta?.duration ?: getDurationFromString(primaryPost.data.watchTime)
                this.tags = tmdbMeta?.genres
                addScore(tmdbMeta?.score)
                tmdbMeta?.imdbId?.let { addImdbId(it) }
                tmdbMeta?.tmdbId?.let { addTMDbId(it.toString()) }
                tmdbMeta?.actors?.let { addActors(it) }
                tmdbMeta?.trailerUrl?.let { addTrailer(it) }
            }
        }

        val seasonSlices = fetchedPosts.flatMap { discoverSeasons(it) }
        val episodes = mergeEpisodes(seasonSlices, null, null)
        val seasonNames = seasonSlices.map { it.globalSeason }.distinct().sorted().map { seasonNum ->
            SeasonData(season = seasonNum, name = "Season $seasonNum", displaySeason = seasonNum)
        }

        return@coroutineScope newTvSeriesLoadResponse(
            tmdbMeta?.displayTitle ?: primaryPost.data.bestTitle(),
            "$mainUrl/content/${primaryPost.id}", mediaType, episodes
        ) {
            this.posterUrl = tmdbMeta?.posterUrl ?: buildPosterUrl(primaryPost.data.image)
            this.backgroundPosterUrl = tmdbMeta?.backgroundPosterUrl ?: this.posterUrl
            this.logoUrl = tmdbMeta?.logoUrl
            this.plot = tmdbMeta?.plot ?: primaryPost.data.metaData
            this.year = tmdbMeta?.year ?: primaryPost.data.year.selectUntilNonInt()
            this.duration = tmdbMeta?.duration ?: getDurationFromString(primaryPost.data.watchTime)
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
    // Links
    // ==============================
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Try to parse as StreamPayload (multi-variant episode)
        val streamPayload = parseStreamPayload(data)
        if (streamPayload != null && streamPayload.variants.isNotEmpty()) {
            streamPayload.variants.distinctBy { it.url }.forEach { variant ->
                callback.invoke(newExtractorLink(source = name, name = variant.label, url = variant.url, type = ExtractorLinkType.VIDEO) {
                    this.quality = inferExtractorQuality(variant.label)
                })
            }
            return true
        }
        // Direct URL fallback
        callback.invoke(newExtractorLink(source = name, name = name, url = data, type = ExtractorLinkType.VIDEO))
        return true
    }

    // ==============================
    // Post Fetching
    // ==============================
    private suspend fun fetchPostDetails(id: Int): FetchedPost {
        val response = apiGet("/api/posts/$id", cacheTime = 60)
        val data = response.parsed<Data>()
        return if (data.type == "singleVideo") {
            val movieData = response.parsed<Movies>()
            FetchedPost(id = id, data = data, movieLink = normalizeStream(movieData.content, response.url), tvSeries = null)
        } else {
            FetchedPost(id = id, data = data, movieLink = null, tvSeries = response.parsed<TvSeries>())
        }
    }

    private fun discoverSeasons(fetchedPost: FetchedPost): List<SeasonSlice> {
        val tvSeries = fetchedPost.tvSeries ?: return emptyList()
        val declaredSeason = fetchedPost.data.title.let { normalizeTitle(it).season } ?: 1
        return tvSeries.content.mapIndexedNotNull { index, seasonContent ->
            val seasonFromFolder = parseSeasonNumber(seasonContent.seasonName)
            val globalSeason = when {
                seasonFromFolder != null -> seasonFromFolder
                tvSeries.content.size == 1 -> declaredSeason
                else -> declaredSeason + index
            }
            SeasonSlice(fetchedPost = fetchedPost, globalSeason = globalSeason, seasonName = seasonContent.seasonName, episodes = seasonContent.episodes)
        }
    }

    private fun mergeEpisodes(slices: List<SeasonSlice>, seasonNumber: Int?, aniZip: AniZipResponse?): List<Episode> {
        val episodeMap = linkedMapOf<String, EpisodeAccumulator>()
        slices.forEach { slice ->
            slice.episodes.forEachIndexed { index, epData ->
                val epNum = extractEpisodeNumber(epData.title) ?: index + 1
                val season = seasonNumber ?: slice.globalSeason
                val key = "$season-$epNum"
                val acc = episodeMap.getOrPut(key) { EpisodeAccumulator(season = season, episode = epNum, fallbackName = cleanupEpisodeTitle(epData.title, epNum)) }
                acc.sources += StreamVariant(label = buildSourceLabel(slice.fetchedPost.data), url = normalizeStream(epData.link, slice.fetchedPost.data.type))
            }
        }
        return episodeMap.values.sortedWith(compareBy({ it.season }, { it.episode })).map { acc ->
            val aniZipEp = aniZip?.episodes?.get(acc.episode.toString())
            newEpisode(StreamPayload(variants = acc.sources.distinctBy { it.url }).toJsonString()) {
                this.season = acc.season
                this.episode = acc.episode
                this.name = aniZipEp?.preferredTitle() ?: acc.fallbackName
                this.description = aniZipEp?.overview ?: aniZipEp?.summary
                this.posterUrl = aniZipEp?.image
                this.score = Score.from10(aniZipEp?.rating)
                this.runTime = ((aniZipEp?.runtime ?: aniZipEp?.length)?.times(60))
                addDate(aniZipEp?.airDateUtc ?: aniZipEp?.airDate)
            }
        }
    }

    // ==============================
    // Metadata Resolution
    // ==============================
    private suspend fun resolveTmdbMetadata(mediaType: TvType, queryTitles: List<String>, year: Int?, isSeries: Boolean): TmdbMetadata? {
        val normalizedQueries = queryTitles.map { normalizeTitle(it).franchiseTitle }.filter { it.isNotBlank() }.distinct()
        if (normalizedQueries.isEmpty()) return null
        val bestMatch = searchTmdbBestMatch(normalizedQueries, year, isSeries) ?: return null
        val config = getTmdbConfig()

        return if (isSeries) {
            val detailText = app.get("$tmdbApiBase/tv/${bestMatch.id}?api_key=$tmdbApiKey&language=en-US&append_to_response=videos,images,external_ids,credits,content_ratings", cacheTime = 43200).text
            val detail = parseTmdbTvDetails(JSONObject(detailText))
            TmdbMetadata(
                tmdbId = detail.id, imdbId = detail.externalIds?.imdbId,
                displayTitle = detail.name ?: detail.originalName ?: bestMatch.displayTitle,
                posterUrl = buildTmdbImageUrl(config, detail.posterPath, TmdbImageKind.POSTER),
                backgroundPosterUrl = buildTmdbImageUrl(config, detail.backdropPath, TmdbImageKind.BACKDROP),
                logoUrl = buildTmdbLogoUrl(config, detail.images?.logos.orEmpty()),
                plot = detail.overview, year = detail.firstAirDate?.take(4)?.toIntOrNull(),
                duration = detail.episodeRunTime?.firstOrNull(),
                genres = detail.genres?.mapNotNull { it.name },
                score = Score.from10(detail.voteAverage),
                actors = detail.credits?.cast.orEmpty().mapNotNull { cast ->
                    cast.name?.let { Actor(it, buildTmdbImageUrl(config, cast.profilePath, TmdbImageKind.PROFILE)) }?.let { it to cast.character }
                },
                trailerUrl = pickTmdbTrailerUrl(detail.videos?.results.orEmpty()),
                showStatus = detail.status.toTmdbShowStatus()
            )
        } else {
            val detailText = app.get("$tmdbApiBase/movie/${bestMatch.id}?api_key=$tmdbApiKey&language=en-US&append_to_response=videos,images,external_ids,credits,release_dates", cacheTime = 43200).text
            val detail = parseTmdbMovieDetails(JSONObject(detailText))
            TmdbMetadata(
                tmdbId = detail.id, imdbId = detail.externalIds?.imdbId,
                displayTitle = detail.title ?: detail.originalTitle ?: bestMatch.displayTitle,
                posterUrl = buildTmdbImageUrl(config, detail.posterPath, TmdbImageKind.POSTER),
                backgroundPosterUrl = buildTmdbImageUrl(config, detail.backdropPath, TmdbImageKind.BACKDROP),
                logoUrl = buildTmdbLogoUrl(config, detail.images?.logos.orEmpty()),
                plot = detail.overview, year = detail.releaseDate?.take(4)?.toIntOrNull(),
                duration = detail.runtime, genres = detail.genres?.mapNotNull { it.name },
                score = Score.from10(detail.voteAverage),
                actors = detail.credits?.cast.orEmpty().mapNotNull { cast ->
                    cast.name?.let { Actor(it, buildTmdbImageUrl(config, cast.profilePath, TmdbImageKind.PROFILE)) }?.let { it to cast.character }
                },
                trailerUrl = pickTmdbTrailerUrl(detail.videos?.results.orEmpty()), showStatus = null
            )
        }
    }

    private suspend fun searchTmdbBestMatch(queryTitles: List<String>, year: Int?, isSeries: Boolean): TmdbSearchResult? = coroutineScope {
        val endpoint = if (isSeries) "tv" else "movie"
        val candidates = queryTitles.take(3).map { queryTitle ->
            async {
                val url = "$tmdbApiBase/search/$endpoint?api_key=$tmdbApiKey&language=en-US&query=${urlEncode(queryTitle)}" +
                    if (year != null) (if (isSeries) "&first_air_date_year=$year" else "&year=$year") else ""
                val responseText = app.get(url, cacheTime = 43200).text
                parseTmdbSearchPage(JSONObject(responseText)).results
            }
        }.awaitAll().flatten()
        candidates.maxByOrNull { scoreTmdbCandidate(it, queryTitles, year) }
    }

    private suspend fun resolveAnimeMetadata(title: String, year: Int?): AnimeMetaBundle? = coroutineScope {
        val searchResults = searchAniList(title)
        val bestMatch = chooseBestAniListMatch(searchResults, listOf(title), year) ?: return@coroutineScope null
        val chain = buildAniListSeasonChain(bestMatch.id ?: return@coroutineScope null)
        val selectedMedia = if (chain.isNotEmpty()) chain.first() else bestMatch

        val aniZipDeferred = async { fetchAniZip(selectedMedia.id ?: return@async null) }
        val aniZip = aniZipDeferred.await()

        AnimeMetaBundle(
            selectedMedia = selectedMedia, seasonChain = chain, aniZip = aniZip,
            posterUrl = selectedMedia.coverImage?.extraLarge ?: selectedMedia.coverImage?.large,
            backgroundPosterUrl = selectedMedia.bannerImage ?: aniZip?.bestBackgroundUrl() ?: selectedMedia.coverImage?.extraLarge,
            logoUrl = aniZip?.bestLogoUrl()
        )
    }

    private suspend fun searchAniList(title: String): List<AniListMedia> {
        val query = """
            query (${'$'}search: String) {
              Page(page: 1, perPage: 10) {
                media(search: ${'$'}search, type: ANIME, sort: [SEARCH_MATCH, POPULARITY_DESC]) {
                  id idMal title { romaji english native userPreferred } synonyms format status seasonYear
                  startDate { year month day } episodes duration averageScore description(asHtml: false)
                  bannerImage genres trailer { id site thumbnail }
                  coverImage { extraLarge large }
                  relations { edges { relationType node { id idMal format seasonYear startDate { year month day }
                    title { romaji english native userPreferred } synonyms bannerImage coverImage { extraLarge large } } } }
                }
              }
            }
        """.trimIndent()
        val body = JSONObject().apply { put("query", query); put("variables", JSONObject().apply { put("search", title) }) }
        val responseText = app.post(aniListGraphQlUrl, json = body.toString(), cacheTime = 43200).text
        return parseAniListSearchResponse(JSONObject(responseText)).data?.page?.media.orEmpty()
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
                  title { romaji english native userPreferred } synonyms bannerImage coverImage { extraLarge large } } } }
              }
            }
        """.trimIndent()
        val body = JSONObject().apply { put("query", query); put("variables", JSONObject().apply { put("id", id) }) }
        val responseText = app.post(aniListGraphQlUrl, json = body.toString(), cacheTime = 43200).text
        val media = parseAniListByIdResponse(JSONObject(responseText)).data?.media
        if (media != null) aniListMediaCache[id] = media
        return media
    }

    private suspend fun buildAniListSeasonChain(seedId: Int): List<AniListMedia> {
        val visited = linkedSetOf<Int>()
        var earliest = fetchAniListById(seedId) ?: return emptyList()
        while (true) {
            val prequel = earliest.relations?.edges.orEmpty()
                .mapNotNull { if (it.relationType.equals("PREQUEL", ignoreCase = true)) it.node else null }
                .firstOrNull { it.id != null && isMainlineAnimeFormat(it.format) && !visited.contains(it.id!!) } ?: break
            val prequelMedia = fetchAniListById(prequel.id!!) ?: break
            if (prequelMedia.id == earliest.id) break
            earliest = prequelMedia
        }
        val chain = mutableListOf<AniListMedia>()
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
        val responseText = app.get("$aniZipUrl?anilist_id=$anilistId", cacheTime = 43200).text
        return parseAniZipResponse(JSONObject(responseText))
    }

    private suspend fun getTmdbConfig(): TmdbConfiguration {
        tmdbConfigCache?.let { return it }
        val responseText = app.get("$tmdbApiBase/configuration?api_key=$tmdbApiKey", cacheTime = 86400).text
        val config = parseTmdbConfiguration(JSONObject(responseText))
        tmdbConfigCache = config
        return config
    }

    // ==============================
    // Helpers
    // ==============================
    private suspend fun inferType(post: Post, normalized: NormalizedTitle): TvType {
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

    private suspend fun inferTypeFromData(data: Data): TvType {
        val titleCheck = data.title.lowercase()
        val isAnime = detectAnime(normalizeTitle(data.title).franchiseTitle, data.year?.selectUntilNonInt())
        return when {
            isAnime && data.type == "singleVideo" && titleCheck.contains("ova") -> TvType.OVA
            isAnime && data.type == "singleVideo" -> TvType.AnimeMovie
            isAnime -> TvType.Anime
            data.type == "series" -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private suspend fun detectAnime(title: String, year: Int?): Boolean {
        val cacheKey = "$title|${year ?: 0}"
        animeDetectionCache[cacheKey]?.let { return it }
        val detected = runCatching {
            val candidates = searchAniList(title)
            val best = chooseBestAniListMatch(candidates, listOf(title), year)
            best != null && scoreAniListCandidate(best, listOf(title), year) >= 60
        }.getOrDefault(false)
        animeDetectionCache[cacheKey] = detected
        return detected
    }

    private fun applyAnimeSyncData(loadResponse: LoadResponse, animeMeta: AnimeMetaBundle?) {
        val media = animeMeta?.selectedMedia ?: return
        loadResponse.addAniListId(media.id)
        loadResponse.addMalId(animeMeta.aniZip?.mappings?.malId ?: media.idMal)
        loadResponse.addKitsuId(animeMeta.aniZip?.mappings?.kitsuId)
        loadResponse.addSimklId(animeMeta.aniZip?.mappings?.simklId)
        val tmdbId = animeMeta.aniZip?.mappings?.tmdbId?.tv ?: animeMeta.aniZip?.mappings?.tmdbId?.movie
        tmdbId?.let { loadResponse.addTMDbId(it.toString()) }
        animeMeta.aniZip?.mappings?.imdbId?.let { if (it.isNotBlank()) loadResponse.addImdbId(it) }
    }

    private suspend fun applyAnimeTrailer(loadResponse: LoadResponse, animeMeta: AnimeMetaBundle?) {
        animeMeta?.selectedMedia?.trailer?.fullUrl()?.let { loadResponse.addTrailer(it) }
    }

    private fun buildPosterUrl(imagePath: String?): String? {
        return imagePath?.takeIf { it.isNotBlank() }?.let { "$mainApiUrl/uploads/$it" }
    }

    private fun buildSourceLabel(data: Data): String {
        val parts = mutableListOf("FTP")
        normalizeTitle(data.title).audioTag?.let { parts += "[$it]" }
        normalizeTitle(data.title).qualityTag?.let { parts += "[$it]" }
        return parts.joinToString(" ")
    }

    private fun normalizeStream(rawUrl: String?, dataType: String): String {
        return rawUrl ?: ""
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

    private fun scoreAniListCandidate(candidate: AniListMedia, queryTitles: List<String>, year: Int?): Int {
        val titles = candidate.allTitles()
        val candidateYear = candidate.resolvedYear()
        val titleScore = queryTitles.maxOfOrNull { queryTitle ->
            titles.maxOfOrNull { tokenScore(normalizeTitle(queryTitle).canonicalTitle, normalizeTitle(it).canonicalTitle) } ?: 0
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
        val leftTokens = left.lowercase().split(" ").filter { it.isNotBlank() }.toSet()
        val rightTokens = right.lowercase().split(" ").filter { it.isNotBlank() }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return if (left.equals(right, ignoreCase = true)) 100 else 0
        val overlap = leftTokens.intersect(rightTokens).size
        val exactBonus = if (left.equals(right, ignoreCase = true)) 100 else 0
        return exactBonus + (overlap * 20) - abs(leftTokens.size - rightTokens.size) * 4
    }

    private fun chooseBestAniListMatch(candidates: List<AniListMedia>, queryTitles: List<String>, year: Int?): AniListMedia? {
        return candidates.maxByOrNull { scoreAniListCandidate(it, queryTitles, year) }
    }

    private fun isMainlineAnimeFormat(format: String?): Boolean {
        return when (format?.uppercase()) { "TV", "TV_SHORT", "ONA", "OVA", "MOVIE" -> true; else -> false }
    }

    private fun String.toTvType(): TvType {
        return TvType.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: TvType.Others
    }

    private fun String?.toTmdbShowStatus(): ShowStatus? {
        return when (this?.uppercase()) { "ENDED" -> ShowStatus.Completed; "RETURNING SERIES", "IN PRODUCTION", "PLANNED", "PILOT" -> ShowStatus.Ongoing; else -> null }
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
    private fun AniListMedia.toShowStatus(): ShowStatus? = when (status?.uppercase()) { "FINISHED" -> ShowStatus.Completed; "RELEASING" -> ShowStatus.Ongoing; else -> null }
    private fun AniListTrailer.fullUrl(): String? = id?.let { when (site?.lowercase()) { "youtube" -> "https://www.youtube.com/watch?v=$it"; else -> null } }
    private fun AniZipResponse.bestLogoUrl(): String? = images.orEmpty().firstOrNull { it.coverType.equals("Clearlogo", ignoreCase = true) }?.url
    private fun AniZipResponse.bestBackgroundUrl(): String? = images.orEmpty().firstOrNull { it.coverType.equals("Fanart", ignoreCase = true) || it.coverType.equals("Banner", ignoreCase = true) }?.url
    private fun AniZipEpisode.preferredTitle(): String? = title?.en ?: title?.ja ?: title?.xJat

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun String?.selectUntilNonInt(): Int? = this?.let { Regex("""^.*?(?=\D|$)""").find(it)?.value?.toIntOrNull() }

    private fun parseSeasonNumber(value: String?): Int? {
        val cleaned = value.orEmpty()
        listOf(
            Regex("""(?:season|s)\s*0*(\d{1,2})""", RegexOption.IGNORE_CASE),
            Regex("""(\d{1,2})(?:st|nd|rd|th)?\s*season""", RegexOption.IGNORE_CASE),
            Regex("""part\s*0*(\d{1,2})""", RegexOption.IGNORE_CASE),
            Regex("""cour\s*0*(\d{1,2})""", RegexOption.IGNORE_CASE)
        ).forEach { regex ->
            regex.find(cleaned)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun extractEpisodeNumber(title: String?): Int? {
        val cleaned = title.orEmpty()
        listOf(
            Regex("""(?:episode|ep|e)\s*0*(\d{1,4})""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|\D)(\d{1,4})(?:\D|$)""")
        ).forEach { regex ->
            regex.find(cleaned)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun cleanupEpisodeTitle(title: String?, episodeNumber: Int): String {
        val cleaned = title.orEmpty()
            .replace(Regex("""(?:episode|ep)\s*0*$episodeNumber\s*[:-]?""", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ').replace('.', ' ')
            .replace(Regex("""\s+"""), " ").trim()
        return if (cleaned.isBlank()) "Episode $episodeNumber" else cleaned
    }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val lower = check?.lowercase() ?: return null
        return when {
            lower.contains("2160p") || lower.contains("4k") -> SearchQuality.FourK
            lower.contains("webrip") || lower.contains("web-dl") || lower.contains("webdl") -> SearchQuality.WebRip
            lower.contains("bluray") || lower.contains("blu-ray") -> SearchQuality.BlueRay
            lower.contains("hdts") || lower.contains("hdcam") || lower.contains("hdtc") -> SearchQuality.HdCam
            lower.contains("dvd") -> SearchQuality.DVD
            lower.contains("camrip") -> SearchQuality.CamRip
            lower.contains("cam") -> SearchQuality.Cam
            lower.contains("hdrip") || lower.contains("hdtv") -> SearchQuality.HD
            lower.contains("hdr") -> SearchQuality.HDR
            lower.contains("uhd") -> SearchQuality.UHD
            lower.contains("sdr") -> SearchQuality.SDR
            lower.contains("1080p") || lower.contains("720p") || lower.contains("hd") -> SearchQuality.HD
            lower.contains("telesync") -> SearchQuality.Telesync
            lower.contains("telecine") -> SearchQuality.Telecine
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
        val raw = rawTitle.replace('_', ' ').replace('.', ' ').replace(Regex("""\s+"""), " ").trim()
        val audioTag = when {
            Regex("""dual\s*audio""", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Dual-Audio"
            Regex("""multi\s*audio""", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Multi-Audio"
            Regex("""subbed""", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Subbed"
            Regex("""dubbed""", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Dubbed"
            Regex("""hindi""", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Hindi"
            Regex("""english""", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "English"
            else -> null
        }
        val quality = getSearchQuality(raw)
        val qualityTag = Regex("""(2160p|1080p|720p|480p|4k|bluray|blu-ray|web-dl|webrip|hdrip|hdr|uhd|sdr)""", RegexOption.IGNORE_CASE).find(raw)?.value?.uppercase()
        val year = Regex("""(?<!\d)(19\d{2}|20\d{2}|21\d{2})(?!\d)""").find(raw)?.value?.toIntOrNull()
        val season = parseSeasonNumber(raw)
        val noiseRegex = Regex("""
            (?ix)
            (\[.*?(dual\s*audio|multi\s*audio|dubbed|subbed|bluray|blu-ray|web[- ]?dl|webrip|hdrip|2160p|1080p|720p|480p|x264|x265|hevc|hdr|sdr|hindi|english).*?])|
            (\(.*?(dual\s*audio|multi\s*audio|dubbed|subbed|bluray|blu-ray|web[- ]?dl|webrip|hdrip|2160p|1080p|720p|480p|x264|x265|hevc|hdr|sdr|hindi|english).*?\))|
            \b(?:bluray|blu-ray|web[- ]?dl|webrip|hdrip|brrip|dvdrip|2160p|1080p|720p|480p|4k|x264|x265|h264|h265|hevc|aac|dts|ddp5\.1|dual\s*audio|multi\s*audio|dubbed|subbed|hindi|english|multi|audio|hdr|sdr)\b
        """.trimIndent())
        val cleanedDisplay = noiseRegex.replace(raw, " ").replace(Regex("""\s+"""), " ").replace(Regex("""[\[\](){}]"""), " ").trim()
        val franchiseTitle = cleanedDisplay
            .replace(Regex("""(?:season|s)\s*0*(\d{1,2})""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""(\d{1,2})(?:st|nd|rd|th)?\s*season""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""part\s*0*(\d{1,2})""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""cour\s*0*(\d{1,2})""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b(19\d{2}|20\d{2}|21\d{2})\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
            .ifBlank { cleanedDisplay.ifBlank { raw } }
        return NormalizedTitle(
            displayTitle = cleanedDisplay.ifBlank { raw }, canonicalTitle = cleanedDisplay.replace(Regex("""\s+"""), " ").trim(),
            franchiseTitle = franchiseTitle, franchiseKey = franchiseTitle.lowercase(), canonicalKey = cleanedDisplay.replace(Regex("""\s+"""), " ").trim().lowercase(),
            year = year, season = season, quality = quality, qualityTag = qualityTag, audioTag = audioTag
        )
    }



    private suspend fun apiGet(path: String, cacheTime: Int) = try {
        app.get("$mainApiUrl$path", verify = false, cacheTime = cacheTime)
    } catch (_: Exception) {
        app.get("$fallbackApiUrl$path", verify = false, cacheTime = cacheTime)
    }

    private fun parseStreamPayload(jsonStr: String): StreamPayload? {
        return runCatching {
            val json = JSONObject(jsonStr)
            val variantsArray = json.getJSONArray("variants")
            val variants = mutableListOf<StreamVariant>()
            for (i in 0 until variantsArray.length()) {
                val obj = variantsArray.getJSONObject(i)
                variants += StreamVariant(label = obj.getString("label"), url = obj.getString("url"))
            }
            StreamPayload(variants = variants)
        }.getOrNull()
    }

    // ==============================
    // JSON Parsers (org.json)
    // ==============================
    private fun parseTmdbConfiguration(json: JSONObject): TmdbConfiguration {
        val imagesObj = json.optJSONObject("images")
        return TmdbConfiguration(images = imagesObj?.let {
            TmdbImageConfig(
                secureBaseUrl = it.optString("secure_base_url", "").takeIf { s -> s.isNotEmpty() },
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
                title = obj.optString("title", "").takeIf { it.isNotEmpty() },
                name = obj.optString("name", "").takeIf { it.isNotEmpty() },
                originalTitle = obj.optString("original_title", "").takeIf { it.isNotEmpty() },
                originalName = obj.optString("original_name", "").takeIf { it.isNotEmpty() },
                releaseDate = obj.optString("release_date", "").takeIf { it.isNotEmpty() },
                firstAirDate = obj.optString("first_air_date", "").takeIf { it.isNotEmpty() }
            )
        })
    }

    private fun parseTmdbTvDetails(json: JSONObject): TmdbTvDetails {
        return TmdbTvDetails(
            id = json.optInt("id", 0).takeIf { it > 0 },
            name = json.optString("name", "").takeIf { it.isNotEmpty() },
            originalName = json.optString("original_name", "").takeIf { it.isNotEmpty() },
            overview = json.optString("overview", "").takeIf { it.isNotEmpty() },
            posterPath = json.optString("poster_path", "").takeIf { it.isNotEmpty() },
            backdropPath = json.optString("backdrop_path", "").takeIf { it.isNotEmpty() },
            firstAirDate = json.optString("first_air_date", "").takeIf { it.isNotEmpty() },
            episodeRunTime = json.optJSONArray("episode_run_time")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } },
            voteAverage = json.optDouble("vote_average").takeIf { !it.isNaN() },
            genres = json.optJSONArray("genres")?.let { arr -> (0 until arr.length()).map { TmdbGenre(name = arr.getJSONObject(it).optString("name", "").takeIf { n -> n.isNotEmpty() }) } },
            videos = parseTmdbVideos(json.optJSONObject("videos")),
            images = parseTmdbImages(json.optJSONObject("images")),
            externalIds = parseTmdbExternalIds(json.optJSONObject("external_ids")),
            credits = parseTmdbCredits(json.optJSONObject("credits")),
            status = json.optString("status", "").takeIf { it.isNotEmpty() }
        )
    }

    private fun parseTmdbMovieDetails(json: JSONObject): TmdbMovieDetails {
        return TmdbMovieDetails(
            id = json.optInt("id", 0).takeIf { it > 0 },
            title = json.optString("title", "").takeIf { it.isNotEmpty() },
            originalTitle = json.optString("original_title", "").takeIf { it.isNotEmpty() },
            overview = json.optString("overview", "").takeIf { it.isNotEmpty() },
            posterPath = json.optString("poster_path", "").takeIf { it.isNotEmpty() },
            backdropPath = json.optString("backdrop_path", "").takeIf { it.isNotEmpty() },
            releaseDate = json.optString("release_date", "").takeIf { it.isNotEmpty() },
            runtime = json.optInt("runtime", 0).takeIf { it > 0 },
            voteAverage = json.optDouble("vote_average").takeIf { !it.isNaN() },
            genres = json.optJSONArray("genres")?.let { arr -> (0 until arr.length()).map { TmdbGenre(name = arr.getJSONObject(it).optString("name", "").takeIf { n -> n.isNotEmpty() }) } },
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
            TmdbVideo(key = obj.optString("key", "").takeIf { it.isNotEmpty() }, site = obj.optString("site", "").takeIf { it.isNotEmpty() },
                type = obj.optString("type", "").takeIf { it.isNotEmpty() }, official = obj.optBoolean("official"))
        })
    }

    private fun parseTmdbImages(json: JSONObject?): TmdbImages? {
        if (json == null) return null
        val logosArray = json.optJSONArray("logos") ?: return TmdbImages()
        return TmdbImages(logos = (0 until logosArray.length()).map { i ->
            val obj = logosArray.getJSONObject(i)
            TmdbLogo(filePath = obj.optString("file_path", "").takeIf { it.isNotEmpty() }, iso6391 = obj.optString("iso_639_1", "").takeIf { it.isNotEmpty() },
                voteAverage = obj.optDouble("vote_average").takeIf { !it.isNaN() }, width = obj.optInt("width", 0).takeIf { it > 0 })
        })
    }

    private fun parseTmdbExternalIds(json: JSONObject?): TmdbExternalIds? {
        return json?.optString("imdb_id", "")?.takeIf { it.isNotEmpty() }?.let { TmdbExternalIds(imdbId = it) }
    }

    private fun parseTmdbCredits(json: JSONObject?): TmdbCredits? {
        if (json == null) return null
        val castArray = json.optJSONArray("cast") ?: return TmdbCredits()
        return TmdbCredits(cast = (0 until castArray.length()).map { i ->
            val obj = castArray.getJSONObject(i)
            TmdbCast(name = obj.optString("name", "").takeIf { it.isNotEmpty() }, character = obj.optString("character", "").takeIf { it.isNotEmpty() },
                profilePath = obj.optString("profile_path", "").takeIf { it.isNotEmpty() })
        })
    }

    private fun parseAniListSearchResponse(json: JSONObject): AniListSearchResponse {
        val dataObj = json.optJSONObject("data") ?: return AniListSearchResponse()
        val pageObj = dataObj.optJSONObject("Page") ?: return AniListSearchResponse()
        val mediaArray = pageObj.optJSONArray("media") ?: return AniListSearchResponse()
        return AniListSearchResponse(data = AniListSearchData(page = AniListPage(media = (0 until mediaArray.length()).map { parseAniListMedia(mediaArray.getJSONObject(it)) })))
    }

    private fun parseAniListByIdResponse(json: JSONObject): AniListByIdResponse {
        return AniListByIdResponse(data = AniListByIdData(media = json.optJSONObject("data")?.optJSONObject("Media")?.let { parseAniListMedia(it) }))
    }

    private fun parseAniListMedia(json: JSONObject): AniListMedia {
        return AniListMedia(
            id = json.optInt("id", 0).takeIf { it > 0 }, idMal = json.optInt("idMal", 0).takeIf { it > 0 },
            title = json.optJSONObject("title")?.let { AniListTitle(
                romaji = it.optString("romaji", "").takeIf { s -> s.isNotEmpty() }, english = it.optString("english", "").takeIf { s -> s.isNotEmpty() },
                native = it.optString("native", "").takeIf { s -> s.isNotEmpty() }, userPreferred = it.optString("userPreferred", "").takeIf { s -> s.isNotEmpty() }
            ) },
            synonyms = json.optJSONArray("synonyms")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } },
            format = json.optString("format", "").takeIf { it.isNotEmpty() }, status = json.optString("status", "").takeIf { it.isNotEmpty() },
            seasonYear = json.optInt("seasonYear", 0).takeIf { it > 0 },
            startDate = json.optJSONObject("startDate")?.let { AniListDate(year = it.optInt("year", 0).takeIf { y -> y > 0 }, month = it.optInt("month", 0).takeIf { y -> y > 0 }, day = it.optInt("day", 0).takeIf { y -> y > 0 }) },
            episodes = json.optInt("episodes", 0).takeIf { it > 0 }, duration = json.optInt("duration", 0).takeIf { it > 0 },
            averageScore = json.optInt("averageScore", 0).takeIf { it > 0 },
            description = json.optString("description", "").takeIf { it.isNotEmpty() },
            bannerImage = json.optString("bannerImage", "").takeIf { it.isNotEmpty() },
            genres = json.optJSONArray("genres")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } },
            trailer = json.optJSONObject("trailer")?.let { AniListTrailer(id = it.optString("id", "").takeIf { s -> s.isNotEmpty() }, site = it.optString("site", "").takeIf { s -> s.isNotEmpty() }, thumbnail = it.optString("thumbnail", "").takeIf { s -> s.isNotEmpty() }) },
            coverImage = json.optJSONObject("coverImage")?.let { AniListCoverImage(extraLarge = it.optString("extraLarge", "").takeIf { s -> s.isNotEmpty() }, large = it.optString("large", "").takeIf { s -> s.isNotEmpty() }) },
            relations = json.optJSONObject("relations")?.let { relObj ->
                val edgesArray = relObj.optJSONArray("edges") ?: return@let null
                AniListRelations(edges = (0 until edgesArray.length()).map { i ->
                    val edgeObj = edgesArray.getJSONObject(i)
                    AniListRelationEdge(
                        relationType = edgeObj.optString("relationType", ""),
                        node = edgeObj.optJSONObject("node")?.let { parseAniListMedia(it) } ?: AniListMedia()
                    )
                })
            }
        )
    }

    private fun parseAniZipResponse(json: JSONObject): AniZipResponse {
        return AniZipResponse(
            images = json.optJSONArray("images")?.let { arr -> (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AniZipImage(coverType = obj.optString("coverType", "").takeIf { it.isNotEmpty() }, url = obj.optString("url", "").takeIf { it.isNotEmpty() })
            } },
            episodes = json.optJSONObject("episodes")?.let { epObj ->
                val map = mutableMapOf<String, AniZipEpisode>()
                epObj.keys().forEach { key ->
                    val ep = epObj.getJSONObject(key)
                    map[key] = AniZipEpisode(
                        title = ep.optJSONObject("title")?.let { AniZipEpisodeTitle(en = it.optString("en", "").takeIf { s -> s.isNotEmpty() }, ja = it.optString("ja", "").takeIf { s -> s.isNotEmpty() }, xJat = it.optString("x-jat", "").takeIf { s -> s.isNotEmpty() }) },
                        overview = ep.optString("overview", "").takeIf { it.isNotEmpty() }, summary = ep.optString("summary", "").takeIf { it.isNotEmpty() },
                        image = ep.optString("image", "").takeIf { it.isNotEmpty() }, runtime = ep.optInt("runtime", 0).takeIf { it > 0 },
                        length = ep.optInt("length", 0).takeIf { it > 0 }, airDate = ep.optString("airDate", "").takeIf { it.isNotEmpty() },
                        airDateUtc = ep.optString("airDateUtc", "").takeIf { it.isNotEmpty() }, rating = ep.optString("rating", "").takeIf { it.isNotEmpty() }
                    )
                }
                map
            },
            mappings = json.optJSONObject("mappings")?.let { mapObj ->
                AniZipMappings(
                    anilistId = mapObj.optInt("anilist_id", 0).takeIf { it > 0 }, malId = mapObj.optInt("mal_id", 0).takeIf { it > 0 },
                    kitsuId = mapObj.optInt("kitsu_id", 0).takeIf { it > 0 }, simklId = mapObj.optInt("simkl_id", 0).takeIf { it > 0 },
                    imdbId = mapObj.optString("imdb_id", "").takeIf { it.isNotEmpty() },
                    tmdbId = mapObj.optJSONObject("themoviedb_id")?.let { AniZipTmdbId(movie = it.optInt("movie", 0).takeIf { v -> v > 0 }, tv = it.optInt("tv", 0).takeIf { v -> v > 0 }) }
                )
            }
        )
    }

    // ==============================
    // Data Classes
    // ==============================
    private data class IndexedPost(
        val id: Int, val imageSm: String?, val rawTitle: String, val mediaType: TvType,
        val postType: String, val displayTitle: String, val franchiseTitle: String,
        val year: Int?, val declaredSeason: Int?, val quality: SearchQuality?, val audioTag: String?, val franchiseKey: String
    )

    private data class NormalizedTitle(
        val displayTitle: String, val canonicalTitle: String, val franchiseTitle: String,
        val franchiseKey: String, val canonicalKey: String, val year: Int?, val season: Int?,
        val quality: SearchQuality?, val qualityTag: String?, val audioTag: String?
    )

    private data class FetchedPost(val id: Int, val data: Data, val movieLink: String?, val tvSeries: TvSeries?)
    private data class SeasonSlice(val fetchedPost: FetchedPost, val globalSeason: Int, val seasonName: String?, val episodes: List<EpisodeData>)
    private data class EpisodeAccumulator(val season: Int, val episode: Int, var fallbackName: String, val sources: MutableList<StreamVariant> = mutableListOf())
    private data class StreamPayload(val variants: List<StreamVariant>) {
        fun toJsonString(): String {
            val json = JSONObject()
            val arr = JSONArray()
            variants.forEach { arr.put(JSONObject().apply { put("label", it.label); put("url", it.url) }) }
            json.put("variants", arr)
            return json.toString()
        }
    }
    private data class StreamVariant(val label: String, val url: String)
    private data class AnimeMetaBundle(
        val selectedMedia: AniListMedia, val seasonChain: List<AniListMedia>, val aniZip: AniZipResponse?,
        val posterUrl: String?, val backgroundPosterUrl: String?, val logoUrl: String?
    )
    private data class TmdbMetadata(
        val tmdbId: Int?, val imdbId: String?, val displayTitle: String, val posterUrl: String?, val backgroundPosterUrl: String?,
        val logoUrl: String?, val plot: String?, val year: Int?, val duration: Int?, val genres: List<String>?,
        val score: Score?, val actors: List<Pair<Actor, String?>>?, val trailerUrl: String?, val showStatus: ShowStatus?
    )
    private enum class TmdbImageKind { POSTER, BACKDROP, LOGO, PROFILE }

    data class PageData(val posts: List<Post> = emptyList())
    data class Post(val id: Int, val type: String, val imageSm: String? = null, val title: String, val name: String? = null, val year: String? = null, val quality: String? = null)
    data class Data(val type: String, val imageSm: String? = null, val title: String, val image: String? = null, val metaData: String? = null, val name: String? = null, val quality: String? = null, val year: String? = null, val watchTime: String? = null)
    data class TvSeries(val content: List<SeasonContent> = emptyList())
    data class SeasonContent(val episodes: List<EpisodeData> = emptyList(), val seasonName: String? = null)
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

    data class AniListSearchResponse(val data: AniListSearchData? = null)
    data class AniListSearchData(val page: AniListPage? = null)
    data class AniListPage(val media: List<AniListMedia> = emptyList())
    data class AniListByIdResponse(val data: AniListByIdData? = null)
    data class AniListByIdData(val media: AniListMedia? = null)
    data class AniListMedia(val id: Int? = null, val idMal: Int? = null, val title: AniListTitle? = null, val synonyms: List<String?>? = null, val format: String? = null, val status: String? = null, val seasonYear: Int? = null, val startDate: AniListDate? = null, val episodes: Int? = null, val duration: Int? = null, val averageScore: Int? = null, val description: String? = null, val bannerImage: String? = null, val genres: List<String?>? = null, val trailer: AniListTrailer? = null, val coverImage: AniListCoverImage? = null, val relations: AniListRelations? = null)
    data class AniListTitle(val romaji: String? = null, val english: String? = null, val native: String? = null, val userPreferred: String? = null)
    data class AniListDate(val year: Int? = null, val month: Int? = null, val day: Int? = null)
    data class AniListTrailer(val id: String? = null, val site: String? = null, val thumbnail: String? = null)
    data class AniListCoverImage(val extraLarge: String? = null, val large: String? = null)
    data class AniListRelations(val edges: List<AniListRelationEdge> = emptyList())
    data class AniListRelationEdge(val relationType: String = "", val node: AniListMedia = AniListMedia())
    data class AniZipResponse(val images: List<AniZipImage>? = null, val episodes: Map<String, AniZipEpisode>? = null, val mappings: AniZipMappings? = null)
    data class AniZipImage(val coverType: String? = null, val url: String? = null)
    data class AniZipMappings(val anilistId: Int? = null, val malId: Int? = null, val kitsuId: Int? = null, val simklId: Int? = null, val imdbId: String? = null, val tmdbId: AniZipTmdbId? = null)
    data class AniZipTmdbId(val movie: Int? = null, val tv: Int? = null)
    data class AniZipEpisode(val title: AniZipEpisodeTitle? = null, val overview: String? = null, val summary: String? = null, val image: String? = null, val runtime: Int? = null, val length: Int? = null, val airDate: String? = null, val airDateUtc: String? = null, val rating: String? = null)
    data class AniZipEpisodeTitle(val en: String? = null, val ja: String? = null, val xJat: String? = null)
}
