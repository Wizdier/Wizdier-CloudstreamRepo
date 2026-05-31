package com.redowan

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// ─────────────────────────────────────────────────────────────────────────────
//  CircleFtpProvider  –  Full-featured overhaul
//  Features:
//   • TMDB metadata (movies, live-action TV, cartoons) + logo + backdrop
//   • AniList metadata (anime series / movies / OVAs) + banner
//   • AniZip for per-season tracking IDs
//   • MAL / AniList / Kitsu / Simkl sync tracking injection
//   • Title sanitisation (quality / audio tag stripping)
//   • Dual-Audio / Multi-Audio card de-duplication
//   • Anime-only season chained recommendations
//   • Trailer fetching (TMDB → YouTube URL)
//   • Async parallel metadata fetching
// ─────────────────────────────────────────────────────────────────────────────

class CircleFtpProvider : MainAPI() {

    // ── URLs ──────────────────────────────────────────────────────────────────
    override var mainUrl      = "http://new.circleftp.net"
    private var mainApiUrl    = "http://new.circleftp.net:5000"
    private val apiUrl        = "http://15.1.1.50:5000"

    // NOTE: Inject TMDB_API key at build time via BuildConfig:
    //   buildConfigField("String", "TMDB_API_KEY", "\"${System.getenv("TMDB_API") ?: ""}\"")
    // AniList public GraphQL does NOT need a key for read queries.
    private val tmdbApiKey    = BuildConfig.TMDB_API_KEY
    private val tmdbBase      = "https://api.themoviedb.org/3"
    private val tmdbImageBase = "https://image.tmdb.org/t/p"
    private val aniListApi    = "https://graphql.anilist.co"
    private val aniZipApi     = "https://api.ani.zip/mappings"

    // ── Provider metadata ────────────────────────────────────────────────────
    override var name                 = "(BDIX) Circle FTP"
    override var lang                 = "bn"
    override val hasMainPage          = true
    override val hasDownloadSupport   = true
    override val hasQuickSearch       = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie,
        TvType.Cartoon, TvType.AsianDrama, TvType.Documentary, TvType.OVA, TvType.Others
    )

    private val CATEGORY_ANIME_SERIES = "21"
    private val CATEGORY_ANIM_MOVIES  = "1"

    override val mainPage = mainPageOf(
        "80"  to "Featured",
        "6"   to "English Movies",
        "9"   to "English & Foreign TV Series",
        "22"  to "Dubbed TV Series",
        "2"   to "Hindi Movies",
        "5"   to "Hindi TV Series",
        "238" to "Indian TV Show",
        "7"   to "English & Foreign Hindi Dubbed Movies",
        "8"   to "Foreign Language Movies",
        "3"   to "South Indian Dubbed Movies",
        "4"   to "South Indian Movies",
        CATEGORY_ANIM_MOVIES  to "Animation Movies",
        CATEGORY_ANIME_SERIES to "Anime Series",
        "85"  to "Documentary",
        "15"  to "WWE"
    )

    // ═════════════════════════════════════════════════════════════════════════
    //  TITLE SANITISATION ENGINE
    // ═════════════════════════════════════════════════════════════════════════

    private fun sanitiseTitle(raw: String): String {
        var title = raw
        title = title.replace(Regex("""\[[^\]]*\]"""), " ")
        title = title.replace(Regex("""\([^)]*\)"""), " ")
        title = title.replace(Regex("""\{[^}]*\}"""), " ")

        val qualityPattern = Regex(
            """(?i)\b(blu[.\s-]?ray|bdrip|brrip|web[.\s-]?dl|webrip|web[.\s-]?rip|hdrip|hdtv|hd[.\s-]?cam|hdcam|hdts|hdtc|dvd[.\s-]?rip|dvdscr|dvd|cam[.\s-]?rip|camrip|telesync|telecine|4k|2160p|1080p|1080i|720p|480p|360p|x264|x265|h\.?264|h\.?265|hevc|avc|xvid|divx|aac|ac3|dd5\.1|dts|mp3|flac|10bit|10-bit|hdr|sdr|remux|proper|extended|remastered|season\s*\d+|s\d{2}e\d{2}|e\d{2,3})\b"""
        )
        title = title.replace(qualityPattern, " ")

        val audioPattern = Regex(
            """(?i)\b(dual[\s.-]?audio|multi[\s.-]?audio|dubbed|subbed|hindi[\s.-]?dubbed|english[\s.-]?dubbed|hindi|bengali|tamil|telugu|malayalam|kannada|english|japanese|korean|chinese|french|german|spanish)\b"""
        )
        title = title.replace(audioPattern, " ")

        title = title.replace(Regex("""[-_.]+"""), " ")
        title = title.replace(Regex("""\s{2,}"""), " ")
        return title.trim()
    }

    private fun canonicalKey(raw: String): String =
        sanitiseTitle(raw).lowercase().replace(Regex("""\W+"""), "")

    private fun extractAudioTag(raw: String): String? {
        val lower = raw.lowercase()
        return when {
            "multi audio"  in lower -> "Multi-Audio"
            "dual audio"   in lower -> "Dual-Audio"
            "hindi dubbed" in lower -> "Hindi-Dubbed"
            "dubbed"       in lower -> "Dubbed"
            "subbed"       in lower -> "Subbed"
            else                    -> null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ANIME DETECTION
    // ═════════════════════════════════════════════════════════════════════════

    private fun isAnimeCategory(categoryId: String) =
        categoryId == CATEGORY_ANIME_SERIES || categoryId == CATEGORY_ANIM_MOVIES

    private fun looksLikeAnime(post: Post): Boolean {
        val lower      = post.title.lowercase()
        val categories = post.categories ?: emptyList()
        return categories.contains(21) || categories.contains(1) ||
               "anime" in lower || "ova" in lower
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HOME PAGE
    // ═════════════════════════════════════════════════════════════════════════

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json  = fetchWithFallback(
            "$mainApiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10"
        )
        val posts = parseJson<PageData>(json).posts
        val home  = consolidateAndFilter(posts, isAnime = isAnimeCategory(request.data))
            .mapNotNull { group -> toSearchResult(group) }
        return newHomePageResponse(request.name, home, true)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SEARCH
    // ═════════════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        val json  = fetchWithFallback("$mainApiUrl/api/posts?searchTerm=$query&order=desc")
        val posts = parseJson<PageData>(json).posts
        return consolidateAndFilter(posts, isAnime = false)
            .mapNotNull { group -> toSearchResult(group) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  AUDIO CONSOLIDATION + ANIME SEASON FILTER
    // ═════════════════════════════════════════════════════════════════════════

    private fun consolidateAndFilter(posts: List<Post>, isAnime: Boolean): List<PostGroup> {
        val grouped = LinkedHashMap<String, PostGroup>()
        for (post in posts) {
            val key      = canonicalKey(post.title)
            val existing = grouped[key]
            if (existing == null) {
                grouped[key] = PostGroup(
                    primary    = post,
                    companions = mutableListOf(),
                    audioTag   = extractAudioTag(post.title)
                )
            } else {
                (existing.companions as MutableList).add(post)
            }
        }

        if (isAnime) {
            val seasonFiltered = LinkedHashMap<String, PostGroup>()
            for ((_, group) in grouped) {
                val baseKey       = stripSeasonSuffix(canonicalKey(group.primary.title))
                val season        = detectSeasonNumber(group.primary.title)
                val current       = seasonFiltered[baseKey]
                if (current == null) {
                    seasonFiltered[baseKey] = group
                } else {
                    val currentSeason = detectSeasonNumber(current.primary.title)
                    if (season < currentSeason) seasonFiltered[baseKey] = group
                }
            }
            return seasonFiltered.values.toList()
        }
        return grouped.values.toList()
    }

    private fun stripSeasonSuffix(key: String): String =
        key.replace(Regex("""(?i)(season\s*\d+|s\d{1,2})$"""), "").trim()

    private fun detectSeasonNumber(title: String): Int =
        Regex("""(?i)(?:season\s*|s)(\d{1,2})\b""").find(title)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1

    // ═════════════════════════════════════════════════════════════════════════
    //  toSearchResult
    // ═════════════════════════════════════════════════════════════════════════

    private fun toSearchResult(group: PostGroup): SearchResponse? {
        val post = group.primary
        if (post.type != "singleVideo" && post.type != "series") return null

        val cleanTitle = sanitiseTitle(post.title)
        val isAnime    = looksLikeAnime(post)
        val tvType     = when {
            isAnime && post.type == "singleVideo" -> TvType.AnimeMovie
            isAnime                               -> TvType.Anime
            post.type == "singleVideo"            -> TvType.Movie
            else                                  -> TvType.TvSeries
        }

        return newAnimeSearchResponse(cleanTitle, "$mainUrl/content/${post.id}", tvType) {
            this.posterUrl = "$mainApiUrl/uploads/${post.imageSm}"
            val check      = post.title.lowercase()
            this.quality   = getSearchQuality(check)
            addDubStatus(
                dubExist = "dubbed" in check || "dual audio" in check || "multi audio" in check,
                subExist = false
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOAD
    // ═════════════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val rawJson  = fetchWithFallback(url.replace("$mainUrl/content/", "$mainApiUrl/api/posts/"))
        val useMain  = rawJson.contains(mainApiUrl)
        val loadData = parseJson<Data>(rawJson)

        val rawTitle   = loadData.title
        val cleanTitle = sanitiseTitle(rawTitle)
        val poster     = "$apiUrl/uploads/${loadData.image}"
        val year       = selectUntilNonInt(loadData.year)
        val cats       = loadData.categories ?: emptyList()
        val isAnime    = cats.contains(21) || cats.contains(1)

        // ── MOVIE ─────────────────────────────────────────────────────────
        if (loadData.type == "singleVideo") {
            val movieUrl = parseJson<Movies>(rawJson).content
            val link     = if (useMain) movieUrl ?: "" else linkToIp(movieUrl)
            val duration = getDurationFromString(loadData.watchTime)

            return if (isAnime) {
                // ── Anime Movie ──────────────────────────────────────────
                val aniMeta = fetchAniListMeta(cleanTitle, isMovie = true)
                val aniZip  = aniMeta?.idAniList?.let { id -> fetchAniZip(id) }

                val trailerUrl = aniMeta?.trailer?.let { t ->
                    if (t.site == "youtube" && t.id != null)
                        "https://www.youtube.com/watch?v=${t.id}" else null
                }

                newMovieLoadResponse(
                    name    = aniMeta?.title?.english ?: aniMeta?.title?.romaji ?: cleanTitle,
                    url     = url,
                    type    = TvType.AnimeMovie,
                    dataUrl = link
                ) {
                    this.posterUrl           = aniMeta?.coverImage?.extraLarge ?: poster
                    this.backgroundPosterUrl = aniMeta?.bannerImage
                    this.year                = aniMeta?.startDate?.year ?: year
                    this.plot                = aniMeta?.description?.stripHtml() ?: loadData.metaData
                    this.duration            = duration
                    this.tags                = aniMeta?.genres
                }.also { response ->
                    aniMeta?.idAniList?.let { id -> response.addAniListId(id) }
                    aniMeta?.idMal?.let     { id -> response.addMalId(id) }
                    aniZip?.mappings?.kitsu_id?.toIntOrNull()?.let { id -> response.addKitsuId(id) }
                    aniZip?.mappings?.simkl_id?.let               { id -> response.addSimklId(id) }
                    trailerUrl?.let { t -> response.addTrailer(t) }
                }

            } else {
                // ── Standard Movie ───────────────────────────────────────
                val tmdb       = fetchTmdbMovie(cleanTitle, year)
                val trailerUrl = fetchTmdbTrailerUrl(tmdb?.id, isTv = false)

                newMovieLoadResponse(
                    name    = tmdb?.title ?: cleanTitle,
                    url     = url,
                    type    = TvType.Movie,
                    dataUrl = link
                ) {
                    this.posterUrl           = tmdb?.poster_path?.let { p -> "$tmdbImageBase/w500$p" } ?: poster
                    this.backgroundPosterUrl = tmdb?.backdrop_path?.let { p -> "$tmdbImageBase/w1280$p" }
                    this.logoUrl             = fetchTmdbLogo(tmdb?.id, isTv = false)
                    this.year                = tmdb?.release_date?.take(4)?.toIntOrNull() ?: year
                    this.plot                = tmdb?.overview ?: loadData.metaData
                    this.duration            = tmdb?.runtime ?: duration
                    // score replaces the deprecated rating field (0–100 scale)
                    this.score               = tmdb?.vote_average?.times(10)?.toInt()
                }.also { response ->
                    trailerUrl?.let { t -> response.addTrailer(t) }
                }
            }
        }

        // ── TV SERIES / ANIME SERIES ──────────────────────────────────────
        val tvData       = parseJson<TvSeries>(rawJson)
        val companionIds = extractCompanionIds(url)

        // Fetch companion audio-variant season data in parallel
        val companionBlocks: List<Pair<String, List<EpisodeData>>> = coroutineScope {
            companionIds.map { cId ->
                async {
                    try {
                        val cJson = fetchWithFallback("$mainApiUrl/api/posts/$cId")
                        val cData = parseJson<TvSeries>(cJson)
                        val tag   = extractAudioTag(parseJson<Data>(cJson).title) ?: "Alt-Audio"
                        val eps   = cData.content.flatMap { season -> season.episodes }
                        Pair(tag, eps)
                    } catch (_: Exception) { null }
                }
            }.mapNotNull { deferred -> deferred.await() }
        }

        val primaryAudioTag = if (companionBlocks.isNotEmpty()) extractAudioTag(rawTitle) else null

        val episodesData = mutableListOf<Episode>()
        var seasonNum    = 0
        tvData.content.forEachIndexed { sIdx, season ->
            seasonNum++
            var episodeNum = 0
            season.episodes.forEach { epData ->
                episodeNum++
                val baseLink = if (useMain) epData.link else linkToIp(epData.link)

                val companionLinks = companionBlocks.mapIndexedNotNull { cIdx, (cTag, cEps) ->
                    val flatIdx = (sIdx * season.episodes.size) + episodeNum - 1
                    cEps.getOrNull(flatIdx)?.let { ep ->
                        val lnk = if (useMain) ep.link else linkToIp(ep.link)
                        AudioVariant(cTag, lnk)
                    }
                }

                val episodeLinkData = EpisodeLinkData(
                    primary    = baseLink,
                    primaryTag = primaryAudioTag,
                    companions = companionLinks
                )

                // FIX: use AppUtils.mapper.writeValueAsString() instead of AppUtils.toJson()
                val dataJson = AppUtils.mapper.writeValueAsString(episodeLinkData)

                episodesData.add(
                    newEpisode(dataJson) {
                        this.name    = epData.title.ifBlank { null }
                        this.episode = episodeNum
                        this.season  = seasonNum
                    }
                )
            }
        }

        // ── Async metadata + return ───────────────────────────────────────
        return if (isAnime) {
            val currentSeason = detectSeasonNumber(rawTitle)

            // Run AniList + AniZip concurrently
            val (aniMeta, aniZip) = coroutineScope {
                val aniDeferred = async { fetchAniListMeta(cleanTitle, isMovie = false) }
                val meta        = aniDeferred.await()
                val zip         = meta?.idAniList?.let { id -> fetchAniZip(id) }
                Pair(meta, zip)
            }

            val allSeasonRels = aniMeta?.relations?.edges
                ?.filter { edge -> edge.relationType == "SEQUEL" || edge.relationType == "PREQUEL" }
                ?.mapNotNull { edge -> edge.node }
                ?: emptyList()

            val recommendations = buildAnimeSeasonRecommendations(
                relations     = allSeasonRels,
                currentAniId  = aniMeta?.idAniList,
                originalTitle = cleanTitle,
                currentSeason = currentSeason
            )

            val trailerUrl = aniMeta?.trailer?.let { t ->
                if (t.site == "youtube" && t.id != null)
                    "https://www.youtube.com/watch?v=${t.id}" else null
            }

            // FIX: newAnimeLoadResponse called directly on MainAPI receiver — not inside coroutineScope
            newAnimeLoadResponse(
                name     = aniMeta?.title?.english ?: aniMeta?.title?.romaji ?: cleanTitle,
                url      = url,
                type     = TvType.Anime,
                episodes = mapOf(
                    DubStatus.Dubbed to episodesData,
                    DubStatus.Subbed to emptyList()
                )
            ) {
                this.posterUrl           = aniMeta?.coverImage?.extraLarge ?: poster
                this.backgroundPosterUrl = aniMeta?.bannerImage
                this.year                = aniMeta?.startDate?.year ?: year
                this.plot                = aniMeta?.description?.stripHtml() ?: loadData.metaData
                this.tags                = aniMeta?.genres
                this.recommendations     = recommendations
            }.also { response ->
                val aniListId = aniMeta?.idAniList
                val malId     = aniZip?.mappings?.mal_id ?: aniMeta?.idMal
                val kitsuId   = aniZip?.mappings?.kitsu_id?.toIntOrNull()
                val simklId   = aniZip?.mappings?.simkl_id

                aniListId?.let { id -> response.addAniListId(id) }
                malId?.let     { id -> response.addMalId(id) }
                kitsuId?.let   { id -> response.addKitsuId(id) }
                simklId?.let   { id -> response.addSimklId(id) }
                trailerUrl?.let { t -> response.addTrailer(t) }
            }

        } else {
            // Live-action TV / Cartoons
            val (tmdb, trailerUrl) = coroutineScope {
                val tmdbDeferred = async { fetchTmdbTv(cleanTitle, year) }
                val meta         = tmdbDeferred.await()
                val trailer      = fetchTmdbTrailerUrl(meta?.id, isTv = true)
                Pair(meta, trailer)
            }

            val seriesType = if (cats.contains(1)) TvType.Cartoon else TvType.TvSeries

            newTvSeriesLoadResponse(
                name     = tmdb?.name ?: cleanTitle,
                url      = url,
                type     = seriesType,
                episodes = episodesData
            ) {
                this.posterUrl           = tmdb?.poster_path?.let { p -> "$tmdbImageBase/w500$p" } ?: poster
                this.backgroundPosterUrl = tmdb?.backdrop_path?.let { p -> "$tmdbImageBase/w1280$p" }
                this.logoUrl             = fetchTmdbLogo(tmdb?.id, isTv = true)
                this.year                = tmdb?.first_air_date?.take(4)?.toIntOrNull() ?: year
                this.plot                = tmdb?.overview ?: loadData.metaData
            }.also { response ->
                trailerUrl?.let { t -> response.addTrailer(t) }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  loadLinks  –  Decode multi-audio episode data and emit all variants
    // ═════════════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val linkData     = parseJson<EpisodeLinkData>(data)
            val primaryLabel = if (linkData.primaryTag != null) "FTP [${linkData.primaryTag}]" else name

            callback.invoke(newExtractorLink(source = name, name = primaryLabel, url = linkData.primary))

            linkData.companions.forEach { variant ->
                callback.invoke(newExtractorLink(source = name, name = "FTP [${variant.tag}]", url = variant.url))
            }
            true
        } catch (_: Exception) {
            callback.invoke(newExtractorLink(source = name, name = name, url = data))
            true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ANIME SEASON RECOMMENDATION CHAIN
    // ═════════════════════════════════════════════════════════════════════════

    private fun buildAnimeSeasonRecommendations(
        relations: List<AniListRelationNode>,
        currentAniId: Int?,
        originalTitle: String,
        currentSeason: Int
    ): List<AnimeSearchResponse> {
        return relations
            .filter { node -> node.id != currentAniId }
            .mapIndexed { idx, node ->
                val seasonLabel = node.title?.english
                    ?: node.title?.romaji
                    ?: "$originalTitle Season ${currentSeason + idx + 1}"
                newAnimeSearchResponse(
                    name = seasonLabel,
                    url  = "$mainUrl/anilist/${node.id}",
                    type = TvType.Anime
                ) {
                    this.posterUrl = node.coverImage?.large
                }
            }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TMDB HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun fetchTmdbMovie(title: String, year: Int?): TmdbMovieDetail? {
        return try {
            val yearStr = if (year != null) "&year=$year" else ""
            val search  = app.get(
                "$tmdbBase/search/movie?api_key=$tmdbApiKey&query=${title.encodeUrl()}$yearStr",
                verify = false
            )
            val first   = parseJson<TmdbSearchResult<TmdbMovieSummary>>(search.text).results.firstOrNull()
                          ?: return null
            parseJson<TmdbMovieDetail>(
                app.get("$tmdbBase/movie/${first.id}?api_key=$tmdbApiKey", verify = false).text
            )
        } catch (_: Exception) { null }
    }

    private suspend fun fetchTmdbTv(title: String, year: Int?): TmdbTvDetail? {
        return try {
            val yearStr = if (year != null) "&first_air_date_year=$year" else ""
            val search  = app.get(
                "$tmdbBase/search/tv?api_key=$tmdbApiKey&query=${title.encodeUrl()}$yearStr",
                verify = false
            )
            val first   = parseJson<TmdbSearchResult<TmdbTvSummary>>(search.text).results.firstOrNull()
                          ?: return null
            parseJson<TmdbTvDetail>(
                app.get("$tmdbBase/tv/${first.id}?api_key=$tmdbApiKey", verify = false).text
            )
        } catch (_: Exception) { null }
    }

    private suspend fun fetchTmdbLogo(tmdbId: Int?, isTv: Boolean): String? {
        if (tmdbId == null) return null
        return try {
            val endpoint = if (isTv) "tv" else "movie"
            val images   = parseJson<TmdbImages>(
                app.get("$tmdbBase/$endpoint/$tmdbId/images?api_key=$tmdbApiKey", verify = false).text
            )
            val logo = images.logos?.firstOrNull { img -> img.iso_639_1 == "en" }
                       ?: images.logos?.firstOrNull()
            logo?.file_path?.let { p -> "$tmdbImageBase/w500$p" }
        } catch (_: Exception) { null }
    }

    private suspend fun fetchTmdbTrailerUrl(tmdbId: Int?, isTv: Boolean): String? {
        if (tmdbId == null) return null
        return try {
            val endpoint = if (isTv) "tv" else "movie"
            val videos   = parseJson<TmdbVideos>(
                app.get("$tmdbBase/$endpoint/$tmdbId/videos?api_key=$tmdbApiKey", verify = false).text
            ).results ?: emptyList()
            val trailer  = videos.firstOrNull { v ->
                v.site == "YouTube" && v.type == "Trailer" && v.official == true
            } ?: videos.firstOrNull { v -> v.site == "YouTube" && v.type == "Trailer" }
            trailer?.key?.let { k -> "https://www.youtube.com/watch?v=$k" }
        } catch (_: Exception) { null }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ANILIST HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun fetchAniListMeta(title: String, isMovie: Boolean): AniListMedia? {
        val gqlQuery = """
            query (${"$"}search: String) {
              Media(search: ${"$"}search, type: ANIME, sort: SEARCH_MATCH) {
                id idMal
                title { romaji english native }
                description(asHtml: false)
                startDate { year month day }
                coverImage { extraLarge large medium }
                bannerImage
                genres
                trailer { id site }
                relations {
                  edges {
                    relationType
                    node {
                      id
                      title { romaji english }
                      coverImage { large }
                      format
                    }
                  }
                }
              }
            }
        """.trimIndent()
        return try {
            val resp = app.post(
                aniListApi,
                json    = mapOf("query" to gqlQuery, "variables" to mapOf("search" to title)),
                verify  = false,
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
            )
            parseJson<AniListResponse>(resp.text).data?.Media
        } catch (_: Exception) { null }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ANIZIP HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun fetchAniZip(aniListId: Int): AniZipResult? {
        return try {
            parseJson<AniZipResult>(
                app.get("$aniZipApi?anilist_id=$aniListId", verify = false).text
            )
        } catch (_: Exception) { null }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  NETWORK UTILITIES
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun fetchWithFallback(url: String): String {
        return try {
            app.get(url, verify = false, cacheTime = 60).text
        } catch (_: Exception) {
            app.get(url.replace(mainApiUrl, apiUrl), verify = false, cacheTime = 60).text
        }
    }

    private fun linkToIp(data: String?): String {
        if (data == null) return ""
        return when {
            "index.circleftp.net"  in data -> data.replace("index.circleftp.net",  "15.1.4.2")
            "index2.circleftp.net" in data -> data.replace("index2.circleftp.net", "15.1.4.5")
            "index1.circleftp.net" in data -> data.replace("index1.circleftp.net", "15.1.4.9")
            "ftp3.circleftp.net"   in data -> data.replace("ftp3.circleftp.net",   "15.1.4.7")
            "ftp4.circleftp.net"   in data -> data.replace("ftp4.circleftp.net",   "15.1.1.5")
            "ftp5.circleftp.net"   in data -> data.replace("ftp5.circleftp.net",   "15.1.1.15")
            "ftp6.circleftp.net"   in data -> data.replace("ftp6.circleftp.net",   "15.1.2.3")
            "ftp7.circleftp.net"   in data -> data.replace("ftp7.circleftp.net",   "15.1.4.8")
            "ftp8.circleftp.net"   in data -> data.replace("ftp8.circleftp.net",   "15.1.2.2")
            "ftp9.circleftp.net"   in data -> data.replace("ftp9.circleftp.net",   "15.1.2.12")
            "ftp10.circleftp.net"  in data -> data.replace("ftp10.circleftp.net",  "15.1.4.3")
            "ftp11.circleftp.net"  in data -> data.replace("ftp11.circleftp.net",  "15.1.2.6")
            "ftp12.circleftp.net"  in data -> data.replace("ftp12.circleftp.net",  "15.1.2.1")
            "ftp13.circleftp.net"  in data -> data.replace("ftp13.circleftp.net",  "15.1.1.18")
            "ftp15.circleftp.net"  in data -> data.replace("ftp15.circleftp.net",  "15.1.4.12")
            "ftp17.circleftp.net"  in data -> data.replace("ftp17.circleftp.net",  "15.1.3.8")
            else -> data
        }
    }

    private fun extractCompanionIds(url: String): List<Int> {
        val param = Regex("""[?&]companions=([\d,]+)""").find(url)?.groupValues?.get(1)
            ?: return emptyList()
        return param.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    private fun String.encodeUrl() = java.net.URLEncoder.encode(this, "UTF-8")
    private fun String.stripHtml() = replace(Regex("<[^>]*>"), "").replace("&amp;", "&").trim()

    // ═════════════════════════════════════════════════════════════════════════
    //  MISC HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private fun selectUntilNonInt(string: String?): Int? =
        string?.let { Regex("^\\d+").find(it)?.value?.toIntOrNull() }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val lower = check?.lowercase() ?: return null
        return when {
            "webrip" in lower || "web-dl" in lower                          -> SearchQuality.WebRip
            "bluray" in lower                                                -> SearchQuality.BlueRay
            "hdts" in lower || "hdcam" in lower || "hdtc" in lower          -> SearchQuality.HdCam
            "dvd" in lower                                                   -> SearchQuality.DVD
            "camrip" in lower                                                -> SearchQuality.CamRip
            "cam" in lower                                                   -> SearchQuality.Cam
            "hdrip" in lower || "hdtv" in lower || "hd" in lower            -> SearchQuality.HD
            "telesync" in lower                                              -> SearchQuality.Telesync
            "telecine" in lower                                              -> SearchQuality.Telecine
            else                                                             -> null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DATA MODELS  –  CircleFTP
    // ═════════════════════════════════════════════════════════════════════════

    data class PageData(val posts: List<Post>)

    data class Post(
        val id: Int,
        val type: String,
        val imageSm: String,
        val title: String,
        val name: String?,
        val categories: List<Int>? = null
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
        val watchTime: String?,
        val categories: List<Int>? = null
    )

    data class TvSeries(val content: List<Content>)
    data class Content(val episodes: List<EpisodeData>, val seasonName: String)
    data class EpisodeData(val link: String, val title: String)
    data class Movies(val content: String?)

    data class PostGroup(
        val primary:    Post,
        val companions: List<Post>,
        val audioTag:   String?
    )

    data class EpisodeLinkData(
        val primary:    String,
        val primaryTag: String?,
        val companions: List<AudioVariant>
    )

    data class AudioVariant(val tag: String, val url: String)

    // ═════════════════════════════════════════════════════════════════════════
    //  DATA MODELS  –  TMDB
    // ═════════════════════════════════════════════════════════════════════════

    data class TmdbSearchResult<T>(val results: List<T>)
    data class TmdbMovieSummary(val id: Int, val title: String)
    data class TmdbTvSummary(val id: Int, val name: String)

    data class TmdbMovieDetail(
        val id: Int?,
        val title: String?,
        val overview: String?,
        val release_date: String?,
        val runtime: Int?,
        val vote_average: Double?,
        val poster_path: String?,
        val backdrop_path: String?
    )

    data class TmdbTvDetail(
        val id: Int?,
        val name: String?,
        val overview: String?,
        val first_air_date: String?,
        val poster_path: String?,
        val backdrop_path: String?
    )

    data class TmdbImages(val logos: List<TmdbImage>?)
    data class TmdbImage(val file_path: String, val iso_639_1: String?)
    data class TmdbVideos(val results: List<TmdbVideo>?)
    data class TmdbVideo(val key: String?, val site: String?, val type: String?, val official: Boolean?)

    // ═════════════════════════════════════════════════════════════════════════
    //  DATA MODELS  –  AniList
    // ═════════════════════════════════════════════════════════════════════════

    data class AniListResponse(val data: AniListData?)
    data class AniListData(val Media: AniListMedia?)

    data class AniListMedia(
        val id: Int?,
        val idMal: Int?,
        val title: AniListTitle?,
        val description: String?,
        val startDate: AniListDate?,
        val coverImage: AniListCoverImage?,
        val bannerImage: String?,
        val genres: List<String>?,
        val trailer: AniListTrailer?,
        val relations: AniListRelations?
    ) {
        val idAniList: Int? get() = id
    }

    data class AniListTitle(val romaji: String?, val english: String?, val native: String?)
    data class AniListDate(val year: Int?, val month: Int?, val day: Int?)
    data class AniListCoverImage(val extraLarge: String?, val large: String?, val medium: String?)
    data class AniListTrailer(val id: String?, val site: String?)
    data class AniListRelations(val edges: List<AniListEdge>?)
    data class AniListEdge(val relationType: String?, val node: AniListRelationNode?)
    data class AniListRelationNode(
        val id: Int,
        val title: AniListTitle?,
        val coverImage: AniListCoverImage?,
        val format: String?
    )

    // ═════════════════════════════════════════════════════════════════════════
    //  DATA MODELS  –  AniZip
    // ═════════════════════════════════════════════════════════════════════════

    data class AniZipResult(val mappings: AniZipMappings?)
    data class AniZipMappings(
        val mal_id: Int?,
        val kitsu_id: String?,
        val simkl_id: Int?,
        val anilist_id: Int?
    )
}
