package com.wizdier

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

    // NOTE: Inject these at build time via BuildConfig (generated from GitHub Secrets)
    //       TMDB_API          → BuildConfig.TMDB_API_KEY
    //       ANILIST_CLIENT_ID → BuildConfig.ANILIST_CLIENT_ID
    // The AniList public GraphQL endpoint does NOT require a key for queries;
    // the client-id/secret are only needed if you later add OAuth mutations.
    private val tmdbApiKey    = BuildConfig.TMDB_API_KEY          // from TMDB_API secret
    private val tmdbBase      = "https://api.themoviedb.org/3"
    private val tmdbImageBase = "https://image.tmdb.org/t/p"
    private val aniListApi    = "https://graphql.anilist.co"
    private val aniZipApi     = "https://api.ani.zip/mappings"

    // ── Provider metadata ────────────────────────────────────────────────────
    override var name                 = "Circle FTP"
    override var lang                 = "bn"
    override val hasMainPage          = true
    override val hasDownloadSupport   = true
    override val hasQuickSearch       = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie,
        TvType.Cartoon, TvType.AsianDrama, TvType.Documentary, TvType.OVA, TvType.Others
    )

    // Category IDs used by the CircleFTP API
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

    /**
     * Strips quality flags, resolution tags, codec info, and audio descriptors
     * from a raw folder/file title so it can be used cleanly for UI display
     * and third-party API lookups.
     *
     * Examples of stripped tokens:
     *   Bluray, BDRip, WEB-DL, WEBRip, HDRip, HDTV, 1080p, 720p, 480p, 4K,
     *   x264, x265, HEVC, AAC, AC3, DD5.1, H.264,
     *   Dual Audio, Multi Audio, Dubbed, Subbed,
     *   Hindi, English, Bengali (when used as audio descriptors),
     *   [tags], (tags), {tags}, extra whitespace / dashes at the boundary.
     */
    private fun sanitiseTitle(raw: String): String {
        var title = raw

        // Remove content inside brackets that looks like a tag
        title = title.replace(Regex("""\[[^\]]*\]"""), " ")
        title = title.replace(Regex("""\([^)]*\)"""), " ")
        title = title.replace(Regex("""\{[^}]*\}"""), " ")

        // Quality / source flags (case-insensitive)
        val qualityPattern = Regex(
            """(?i)\b(blu[-.\s]?ray|bdrip|brrip|web[-.\s]?dl|webrip|web[-.\s]?rip|
               |hdrip|hdtv|hd[-.\s]?cam|hdcam|hdts|hdtc|dvd[-.\s]?rip|dvdscr|dvd|
               |cam[-.\s]?rip|camrip|telesync|telecine|ts\b|tc\b|
               |4k|2160p|1080p|1080i|720p|480p|360p|
               |x264|x265|h\.?264|h\.?265|hevc|avc|xvid|divx|
               |aac|ac3|dd5\.1|dts|mp3|flac|
               |10bit|10-bit|hdr|sdr|remux|proper|extended|remastered|
               |season\s*\d+|s\d{2}e\d{2}|e\d{2,3})\b""".trimMargin(),
            setOf(RegexOption.IGNORE_CASE)
        )
        title = title.replace(qualityPattern, " ")

        // Audio descriptor phrases
        val audioPattern = Regex(
            """(?i)\b(dual[\s.\-]?audio|multi[\s.\-]?audio|dubbed|subbed|
               |hindi[\s.\-]dubbed|english[\s.\-]dubbed|
               |hindi|bengali|tamil|telugu|Malayalam|kannada|
               |english|japanese|korean|chinese|french|german|spanish)\b""".trimMargin(),
            setOf(RegexOption.IGNORE_CASE)
        )
        title = title.replace(audioPattern, " ")

        // Collapse separators and extra whitespace
        title = title.replace(Regex("""[-_.]+"""), " ")
        title = title.replace(Regex("""\s{2,}"""), " ")
        return title.trim()
    }

    /**
     * Returns a canonical "base key" for a title that can be used to group
     * audio variants.  Lowercased + all non-alphanumeric collapsed.
     */
    private fun canonicalKey(raw: String): String =
        sanitiseTitle(raw).lowercase().replace(Regex("""\W+"""), "")

    /**
     * Extracts the audio tag present in a raw title string, e.g. "Dual Audio",
     * "Multi Audio", "Hindi Dubbed", etc.  Returns null if none detected.
     */
    private fun extractAudioTag(raw: String): String? {
        val lower = raw.lowercase()
        return when {
            "multi audio" in lower  -> "Multi-Audio"
            "dual audio" in lower   -> "Dual-Audio"
            "hindi dubbed" in lower -> "Hindi-Dubbed"
            "dubbed" in lower       -> "Dubbed"
            "subbed" in lower       -> "Subbed"
            else                    -> null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ANIME DETECTION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Determines whether a CircleFTP category ID corresponds to anime content.
     */
    private fun isAnimeCategory(categoryId: String) =
        categoryId == CATEGORY_ANIME_SERIES || categoryId == CATEGORY_ANIM_MOVIES

    /**
     * Heuristic check on a post title/type for anime content when category
     * info is not available (e.g., inside a search result).
     */
    private fun looksLikeAnime(post: Post): Boolean {
        val lower = post.title.lowercase()
        val categories = post.categories ?: emptyList()
        return categories.contains(21) || categories.contains(1) ||
               "anime" in lower || "ova" in lower
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HOME PAGE
    // ═════════════════════════════════════════════════════════════════════════

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = fetchWithFallback(
            "$mainApiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10"
        )
        val posts = parseJson<PageData>(json).posts

        // De-duplicate audio variants, then filter extra anime seasons
        val home = consolidateAndFilter(
            posts    = posts,
            isAnime  = isAnimeCategory(request.data)
        ).mapNotNull { group -> toSearchResult(group) }

        return newHomePageResponse(request.name, home, true)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SEARCH
    // ═════════════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        val json = fetchWithFallback(
            "$mainApiUrl/api/posts?searchTerm=$query&order=desc"
        )
        val posts = parseJson<PageData>(json).posts
        return consolidateAndFilter(posts = posts, isAnime = false)
            .mapNotNull { group -> toSearchResult(group) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  AUDIO CONSOLIDATION + ANIME SEASON FILTER
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Groups posts by their canonical title.  Within each group the primary
     * post is the first one; extras are tracked as audio-variant companions.
     *
     * For anime, only Season 1 (or the earliest numeric season) is kept in the
     * surface list; higher seasons are surfaced later via recommendations.
     */
    private fun consolidateAndFilter(
        posts: List<Post>,
        isAnime: Boolean
    ): List<PostGroup> {
        // Step 1 – group by canonical key (strips audio/quality tags)
        val grouped = LinkedHashMap<String, PostGroup>()
        for (post in posts) {
            val key = canonicalKey(post.title)
            val existing = grouped[key]
            if (existing == null) {
                grouped[key] = PostGroup(
                    primary    = post,
                    companions = mutableListOf(),
                    audioTag   = extractAudioTag(post.title)
                )
            } else {
                // Same canonical title → companion (different audio variant)
                (existing.companions as MutableList).add(post)
            }
        }

        // Step 2 – for anime, keep only the lowest-numbered season per base
        if (isAnime) {
            val seasonFiltered = LinkedHashMap<String, PostGroup>()
            for ((_, group) in grouped) {
                val baseKey = stripSeasonSuffix(canonicalKey(group.primary.title))
                val season  = detectSeasonNumber(group.primary.title)
                val current = seasonFiltered[baseKey]
                if (current == null) {
                    seasonFiltered[baseKey] = group
                } else {
                    val currentSeason = detectSeasonNumber(current.primary.title)
                    if (season < currentSeason) {
                        seasonFiltered[baseKey] = group
                    }
                    // Higher seasons are intentionally dropped here;
                    // they will reappear as recommendations inside load()
                }
            }
            return seasonFiltered.values.toList()
        }

        return grouped.values.toList()
    }

    /** Strips " Season N" / " S2" suffixes to get the base franchise key */
    private fun stripSeasonSuffix(key: String): String =
        key.replace(Regex("""(?i)(season\s*\d+|s\d{1,2})$"""), "").trim()

    /** Returns 1 if no season number is found */
    private fun detectSeasonNumber(title: String): Int {
        val match = Regex("""(?i)(?:season\s*|s)(\d{1,2})\b""").find(title)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  toSearchResult  –  Converts a PostGroup to a SearchResponse card
    // ═════════════════════════════════════════════════════════════════════════

    private fun toSearchResult(group: PostGroup): SearchResponse? {
        val post   = group.primary
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
            val check = post.title.lowercase()
            this.quality = getSearchQuality(check)
            addDubStatus(
                dubExist = "dubbed" in check || "dual audio" in check || "multi audio" in check,
                subExist = false
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOAD  –  Full detail page assembly
    // ═════════════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        // ── Fetch raw CircleFTP data ──────────────────────────────────────
        val rawJson  = fetchWithFallback(url.replace("$mainUrl/content/", "$mainApiUrl/api/posts/"))
        val useMain  = rawJson.contains(mainApiUrl)           // for link rewriting
        val loadData = parseJson<Data>(rawJson)

        val rawTitle   = loadData.title
        val cleanTitle = sanitiseTitle(rawTitle)
        val poster     = "$apiUrl/uploads/${loadData.image}"
        val year       = selectUntilNonInt(loadData.year)
        val isAnime    = (loadData.categories ?: emptyList()).let { cats ->
            cats.contains(21) || cats.contains(1)
        }

        // ── MOVIE ─────────────────────────────────────────────────────────
        if (loadData.type == "singleVideo") {
            val movieUrl  = parseJson<Movies>(rawJson).content
            val link      = if (useMain) movieUrl else linkToIp(movieUrl)
            val duration  = getDurationFromString(loadData.watchTime)

            // Async parallel: TMDB or AniList
            return coroutineScope {
                if (isAnime) {
                    val aniDeferred = async { fetchAniListMeta(cleanTitle, isMovie = true) }
                    val aniMeta     = aniDeferred.await()

                    newMovieLoadResponse(
                        name = aniMeta?.title?.english ?: aniMeta?.title?.romaji ?: cleanTitle,
                        url  = url,
                        type = TvType.AnimeMovie,
                        dataUrl = link ?: ""
                    ) {
                        this.posterUrl           = aniMeta?.coverImage?.extraLarge ?: poster
                        this.backgroundPosterUrl = aniMeta?.bannerImage
                        this.year                = aniMeta?.startDate?.year ?: year
                        this.plot                = aniMeta?.description?.stripHtml() ?: loadData.metaData
                        this.duration            = duration
                        this.tags                = aniMeta?.genres

                        // Tracking IDs
                        aniMeta?.idAniList?.let    { id -> addAniListId(id) }
                        aniMeta?.idMal?.let        { id -> addMalId(id) }

                        // AniZip for Kitsu/Simkl
                        aniMeta?.idAniList?.let { aniId ->
                            val zip = fetchAniZip(aniId)
                            zip?.mappings?.kitsu_id?.let   { id -> addKitsuId(id.toIntOrNull() ?: 0) }
                            zip?.mappings?.simkl_id?.let   { id -> addSimklId(id) }
                        }

                        // Trailer
                        aniMeta?.trailer?.let { trailer ->
                            if (trailer.site == "youtube" && trailer.id != null) {
                                addTrailer("https://www.youtube.com/watch?v=${trailer.id}")
                            }
                        }
                    }
                } else {
                    val tmdbDeferred = async { fetchTmdbMovie(cleanTitle, year) }
                    val tmdb         = tmdbDeferred.await()

                    newMovieLoadResponse(
                        name    = tmdb?.title ?: cleanTitle,
                        url     = url,
                        type    = TvType.Movie,
                        dataUrl = link ?: ""
                    ) {
                        this.posterUrl           = tmdb?.poster_path?.let { p -> "$tmdbImageBase/w500$p" } ?: poster
                        this.backgroundPosterUrl = tmdb?.backdrop_path?.let { p -> "$tmdbImageBase/w1280$p" }
                        this.logoUrl             = fetchTmdbLogo(tmdb?.id, isTv = false)
                        this.year                = tmdb?.release_date?.take(4)?.toIntOrNull() ?: year
                        this.plot                = tmdb?.overview ?: loadData.metaData
                        this.duration            = tmdb?.runtime ?: duration
                        this.rating              = tmdb?.vote_average?.times(10)?.toInt()

                        // Trailer
                        fetchTmdbTrailerUrl(tmdb?.id, isTv = false)?.let { t -> addTrailer(t) }
                    }
                }
            }
        }

        // ── TV SERIES / ANIME SERIES ──────────────────────────────────────
        val tvData       = parseJson<TvSeries>(rawJson)
        val episodesData = mutableListOf<Episode>()

        // Detect companion posts (audio variants) stored in the URL as extras
        // They are encoded as "?companions=id1,id2" appended by toSearchResult
        val companionIds = extractCompanionIds(url)

        // Build all episode lists: primary first, then companions
        val allSeasonBlocks = mutableListOf<Pair<String?, List<EpisodeData>>>()   // tag? to episodes
        tvData.content.forEach { season ->
            allSeasonBlocks.add(Pair(null, season.episodes))
        }
        // Fetch companion season data in parallel
        val companionBlocks = coroutineScope {
            companionIds.map { cId ->
                async {
                    try {
                        val cJson = fetchWithFallback("$mainApiUrl/api/posts/$cId")
                        val cData = parseJson<TvSeries>(cJson)
                        val tag   = extractAudioTag(parseJson<Data>(cJson).title)
                        cData.content.flatMap { it.episodes }.let { eps -> Pair(tag, eps) }
                    } catch (_: Exception) { null }
                }
            }.mapNotNull { it.await() }
        }
        allSeasonBlocks.addAll(companionBlocks)

        // Assign audio tags to primary if companions exist
        val primaryAudioTag = if (companionBlocks.isNotEmpty()) extractAudioTag(rawTitle) else null

        var seasonNum = 0
        tvData.content.forEachIndexed { sIdx, season ->
            seasonNum++
            var episodeNum = 0
            season.episodes.forEach { epData ->
                episodeNum++
                // Primary source link
                val baseLink = if (useMain) epData.link else linkToIp(epData.link)
                // Companion links for same episode position
                val companionLinks = companionBlocks.mapIndexedNotNull { cIdx, (cTag, cEps) ->
                    val ep = cEps.getOrNull((sIdx * season.episodes.size) + episodeNum - 1)
                    ep?.let { e ->
                        val lnk = if (useMain) e.link else linkToIp(e.link)
                        Pair(cTag ?: "Audio-${cIdx + 2}", lnk)
                    }
                }

                // Encode all links into the episode data URL as JSON
                val episodeLinkData = EpisodeLinkData(
                    primary     = baseLink ?: "",
                    primaryTag  = primaryAudioTag,
                    companions  = companionLinks.map { (t, l) -> AudioVariant(t, l) }
                )

                episodesData.add(
                    newEpisode(AppUtils.toJson(episodeLinkData)) {
                        this.name    = epData.title.ifBlank { null }
                        this.episode = episodeNum
                        this.season  = seasonNum
                    }
                )
            }
        }

        // ── Async metadata fetch ──────────────────────────────────────────
        return coroutineScope {
            if (isAnime) {
                val currentSeason  = detectSeasonNumber(rawTitle)
                val aniDeferred    = async { fetchAniListMeta(cleanTitle, isMovie = false) }
                val aniMeta        = aniDeferred.await()

                // Fetch AniZip for this specific season's tracking IDs
                val aniZipDeferred = async {
                    aniMeta?.idAniList?.let { id -> fetchAniZip(id) }
                }
                val aniZip = aniZipDeferred.await()

                // Build sibling seasons as recommendations
                val allSeasonRels  = aniMeta?.relations?.edges
                    ?.filter { edge -> edge.relationType == "SEQUEL" || edge.relationType == "PREQUEL" }
                    ?.mapNotNull { edge -> edge.node }
                    ?: emptyList()

                val recommendations = buildAnimeSeasonRecommendations(
                    relations      = allSeasonRels,
                    currentAniId   = aniMeta?.idAniList,
                    originalTitle  = cleanTitle,
                    currentSeason  = currentSeason
                )

                newAnimeLoadResponse(
                    name  = aniMeta?.title?.english ?: aniMeta?.title?.romaji ?: cleanTitle,
                    url   = url,
                    type  = TvType.Anime,
                    episodes = mapOf(
                        DubStatus.Dubbed  to episodesData.filter { it.season != null },
                        DubStatus.Subbed  to emptyList()
                    )
                ) {
                    this.posterUrl           = aniMeta?.coverImage?.extraLarge ?: poster
                    this.backgroundPosterUrl = aniMeta?.bannerImage
                    this.year                = aniMeta?.startDate?.year ?: year
                    this.plot                = aniMeta?.description?.stripHtml() ?: loadData.metaData
                    this.tags                = aniMeta?.genres
                    this.recommendations     = recommendations

                    // Tracking – season-specific IDs from AniZip
                    val aniListId = aniMeta?.idAniList
                    val malId     = aniZip?.mappings?.mal_id ?: aniMeta?.idMal
                    val kitsuId   = aniZip?.mappings?.kitsu_id?.toIntOrNull()
                    val simklId   = aniZip?.mappings?.simkl_id

                    aniListId?.let { id -> addAniListId(id) }
                    malId?.let     { id -> addMalId(id) }
                    kitsuId?.let   { id -> addKitsuId(id) }
                    simklId?.let   { id -> addSimklId(id) }

                    // Trailer
                    aniMeta?.trailer?.let { trailer ->
                        if (trailer.site == "youtube" && trailer.id != null) {
                            addTrailer("https://www.youtube.com/watch?v=${trailer.id}")
                        }
                    }
                }
            } else {
                // Live-action TV / Cartoons → TMDB
                val isTv         = true
                val tmdbDeferred = async { fetchTmdbTv(cleanTitle, year) }
                val tmdb         = tmdbDeferred.await()

                newTvSeriesLoadResponse(
                    name     = tmdb?.name ?: cleanTitle,
                    url      = url,
                    type     = if ((loadData.categories ?: emptyList()).contains(1))
                                   TvType.Cartoon else TvType.TvSeries,
                    episodes = episodesData
                ) {
                    this.posterUrl           = tmdb?.poster_path?.let { p -> "$tmdbImageBase/w500$p" } ?: poster
                    this.backgroundPosterUrl = tmdb?.backdrop_path?.let { p -> "$tmdbImageBase/w1280$p" }
                    this.logoUrl             = fetchTmdbLogo(tmdb?.id, isTv = isTv)
                    this.year                = tmdb?.first_air_date?.take(4)?.toIntOrNull() ?: year
                    this.plot                = tmdb?.overview ?: loadData.metaData

                    // Trailer
                    fetchTmdbTrailerUrl(tmdb?.id, isTv = isTv)?.let { t -> addTrailer(t) }
                }
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
        // Try to decode as EpisodeLinkData (TV/Anime episodes)
        return try {
            val linkData = parseJson<EpisodeLinkData>(data)

            // Emit primary source
            val primaryLabel = if (linkData.primaryTag != null)
                "FTP [${linkData.primaryTag}]" else name
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name   = primaryLabel,
                    url    = linkData.primary
                )
            )

            // Emit companion audio variants
            linkData.companions.forEach { variant ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = "FTP [${variant.tag}]",
                        url    = variant.url
                    )
                )
            }
            true
        } catch (_: Exception) {
            // Legacy / movie single-link path
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name   = name,
                    url    = data
                )
            )
            true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ANIME SEASON RECOMMENDATION CHAIN BUILDER
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Builds a list of [AnimeSearchResponse] objects for all sibling seasons
     * excluding the current one.  Each card encodes the AniList relation node
     * ID so that loading it re-runs full per-season metadata resolution.
     */
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
                    url  = "$mainUrl/anilist/${node.id}",   // resolved in load()
                    type = TvType.Anime
                ) {
                    this.posterUrl = node.coverImage?.large
                }
            }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TMDB API HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun fetchTmdbMovie(title: String, year: Int?): TmdbMovieDetail? {
        return try {
            val query   = title.encodeUrl()
            val yearStr = if (year != null) "&year=$year" else ""
            val search  = app.get(
                "$tmdbBase/search/movie?api_key=$tmdbApiKey&query=$query$yearStr",
                verify = false
            )
            val results = parseJson<TmdbSearchResult<TmdbMovieSummary>>(search.text).results
            val first   = results.firstOrNull() ?: return null
            val detail  = app.get(
                "$tmdbBase/movie/${first.id}?api_key=$tmdbApiKey",
                verify = false
            )
            parseJson<TmdbMovieDetail>(detail.text)
        } catch (_: Exception) { null }
    }

    private suspend fun fetchTmdbTv(title: String, year: Int?): TmdbTvDetail? {
        return try {
            val query   = title.encodeUrl()
            val yearStr = if (year != null) "&first_air_date_year=$year" else ""
            val search  = app.get(
                "$tmdbBase/search/tv?api_key=$tmdbApiKey&query=$query$yearStr",
                verify = false
            )
            val results = parseJson<TmdbSearchResult<TmdbTvSummary>>(search.text).results
            val first   = results.firstOrNull() ?: return null
            val detail  = app.get(
                "$tmdbBase/tv/${first.id}?api_key=$tmdbApiKey",
                verify = false
            )
            parseJson<TmdbTvDetail>(detail.text)
        } catch (_: Exception) { null }
    }

    /** Fetches the SVG/PNG clear-logo URL from TMDB images endpoint */
    private suspend fun fetchTmdbLogo(tmdbId: Int?, isTv: Boolean): String? {
        if (tmdbId == null) return null
        return try {
            val endpoint = if (isTv) "tv" else "movie"
            val resp     = app.get(
                "$tmdbBase/$endpoint/$tmdbId/images?api_key=$tmdbApiKey",
                verify = false
            )
            val images   = parseJson<TmdbImages>(resp.text)
            val logo     = images.logos?.firstOrNull { img -> img.iso_639_1 == "en" }
                           ?: images.logos?.firstOrNull()
            logo?.file_path?.let { p -> "$tmdbImageBase/w500$p" }
        } catch (_: Exception) { null }
    }

    /** Returns a full YouTube URL for the first official trailer found on TMDB */
    private suspend fun fetchTmdbTrailerUrl(tmdbId: Int?, isTv: Boolean): String? {
        if (tmdbId == null) return null
        return try {
            val endpoint = if (isTv) "tv" else "movie"
            val resp     = app.get(
                "$tmdbBase/$endpoint/$tmdbId/videos?api_key=$tmdbApiKey",
                verify = false
            )
            val videos   = parseJson<TmdbVideos>(resp.text).results ?: emptyList()
            val trailer  = videos.firstOrNull { v ->
                v.site == "YouTube" && v.type == "Trailer" && v.official == true
            } ?: videos.firstOrNull { v -> v.site == "YouTube" && v.type == "Trailer" }
            trailer?.key?.let { k -> "https://www.youtube.com/watch?v=$k" }
        } catch (_: Exception) { null }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ANILIST API HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun fetchAniListMeta(title: String, isMovie: Boolean): AniListMedia? {
        val mediaType = if (isMovie) "MOVIE" else "TV"
        val query = """
            query (${"$"}search: String, ${"$"}type: MediaType) {
              Media(search: ${"$"}search, type: ANIME, format: ${"$"}type, sort: SEARCH_MATCH) {
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
                json     = mapOf("query" to query, "variables" to mapOf("search" to title)),
                verify   = false,
                headers  = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
            )
            parseJson<AniListResponse>(resp.text).data?.Media
        } catch (_: Exception) { null }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ANIZIP API HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun fetchAniZip(aniListId: Int): AniZipResult? {
        return try {
            val resp = app.get("$aniZipApi?anilist_id=$aniListId", verify = false)
            parseJson<AniZipResult>(resp.text)
        } catch (_: Exception) { null }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  NETWORK UTILITIES
    // ═════════════════════════════════════════════════════════════════════════

    /** GET with automatic fallback from mainApiUrl to direct IP apiUrl */
    private suspend fun fetchWithFallback(url: String): String {
        return try {
            app.get(url, verify = false, cacheTime = 60).text
        } catch (_: Exception) {
            app.get(
                url.replace(mainApiUrl, apiUrl),
                verify    = false,
                cacheTime = 60
            ).text
        }
    }

    /** Rewrites hostname-based FTP links to direct IP addresses for BDIX routing */
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

    /** Extracts companion post IDs embedded as a query param in the detail URL */
    private fun extractCompanionIds(url: String): List<Int> {
        val param = Regex("""[?&]companions=([\d,]+)""").find(url)
            ?.groupValues?.get(1) ?: return emptyList()
        return param.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    private fun String.encodeUrl() =
        java.net.URLEncoder.encode(this, "UTF-8")

    private fun String.stripHtml() =
        this.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").trim()

    // ═════════════════════════════════════════════════════════════════════════
    //  MISC HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private fun selectUntilNonInt(string: String?): Int? =
        string?.let { Regex("^.*?(?=\\D|$)").find(it)?.value?.toIntOrNull() }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val lower = check?.lowercase() ?: return null
        return when {
            "webrip" in lower || "web-dl" in lower           -> SearchQuality.WebRip
            "bluray" in lower                                 -> SearchQuality.BlueRay
            "hdts" in lower || "hdcam" in lower || "hdtc" in lower -> SearchQuality.HdCam
            "dvd" in lower                                    -> SearchQuality.DVD
            "cam" in lower                                    -> SearchQuality.Cam
            "camrip" in lower || "rip" in lower               -> SearchQuality.CamRip
            "hdrip" in lower || "hd" in lower || "hdtv" in lower  -> SearchQuality.HD
            "telesync" in lower                               -> SearchQuality.Telesync
            "telecine" in lower                               -> SearchQuality.Telecine
            else                                              -> null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DATA MODELS  –  CircleFTP API
    // ═════════════════════════════════════════════════════════════════════════

    data class PageData(val posts: List<Post>)

    data class Post(
        val id: Int,
        val type: String,
        val imageSm: String,
        val title: String,
        val name: String?,
        val categories: List<Int>? = null          // CircleFTP category ID list
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

    // ── Audio-consolidation runtime models ───────────────────────────────────

    /** Groups a primary post with its audio-variant companions */
    data class PostGroup(
        val primary:    Post,
        val companions: List<Post>,
        val audioTag:   String?
    )

    /** Serialised into the episode data URL; decoded in loadLinks() */
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
    data class TmdbVideo(
        val key: String?,
        val site: String?,
        val type: String?,
        val official: Boolean?
    )

    // ═════════════════════════════════════════════════════════════════════════
    //  DATA MODELS  –  AniList GraphQL
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
        // Alias for clarity in tracking injection
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
