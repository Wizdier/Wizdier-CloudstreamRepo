package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTraktId
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

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

    override val supportedSyncNames = setOf(
        SyncIdName.Anilist,
        SyncIdName.MyAnimeList,
        SyncIdName.Kitsu,
        SyncIdName.Simkl,
        SyncIdName.Imdb,
        SyncIdName.Trakt
    )

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.AsianDrama,
        TvType.Documentary,
        TvType.OVA,
        TvType.Others
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

    // ══════════════════════════════════════════════════════════════════════════
    //  Config
    // ══════════════════════════════════════════════════════════════════════════

    // FIX #3 — Only category 21 (Anime Series) is anime for series detection.
    // Category 1 (Animation Movies) catches non-anime cartoons like
    // Family Guy, Simpsons, Avatar etc. which then wrongly triggers
    // Kitsu / MAL / AniList tracking.
    private val animeCategoryIds = setOf(21)

    private val anilistApi = "https://graphql.anilist.co"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = BuildConfig.TMDB_API
    private val tmdbImageBase = "https://image.tmdb.org/t/p/w500"
    private val tmdbBackdropBase = "https://image.tmdb.org/t/p/original"

    // ══════════════════════════════════════════════════════════════════════════
    //  FIX #2 — Title cleaning: strip noise for accurate AniList / TMDB matching
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * "Jujutsu Kaisen 0 (2022) [1080p] [HEVC] Dual Audio" → "Jujutsu Kaisen 0"
     * "One Piece S02E01 [720p] [WEBRip]"                    → "One Piece"
     */
    private fun cleanTitle(raw: String): String {
        return raw
            // Remove everything inside [square brackets] and (parentheses)
            .replace(Regex("""[\[\(][^]\)]*[\]\)]"""), "")
            // Remove resolution: 1080p, 720p, 480p, 2160p
            .replace(Regex("""\b\d{3,4}[piP]\b"""), "")
            // Remove codec / format tags
            .replace(
                Regex(
                    """(?i)\b(hevc|h\.?264|h\.?265|x264|x265|aac|ac3|ddp|dts|atmos|hdr|dv|sdr|web-?dl|web-?rip|bluray|blu-?ray|remux|proper|repack|hdcam|hdrip|camrip|dvdscr|pre\d+)\b"""
                ), ""
            )
            // Remove audio / language tags
            .replace(
                Regex(
                    """(?i)\b(dual\s*audio|multi\s*audio|japanese|english|hindi|korean|chinese|dubbed|subbed|uncut|uncensored|extended|theatrical|unrated)\b"""
                ), ""
            )
            // Remove season / episode markers
            .replace(Regex("""(?i)\b(s\d+|season\s*\d+|episode\s*\d+|ep\.?\s*\d+)\b"""), "")
            // Remove standalone 4-digit year (but keep year that is part of a title like "2001 A Space Odyssey")
            .replace(Regex("""\s+\(\d{4}\)"""), "")
            .replace(Regex("""\s+\d{4}\s"""), " ")
            // Collapse whitespace
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  AniList — search with CLEANED title + GraphQL variables (safe injection)
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun getAniListMeta(title: String): AniListMeta? {
        val cleaned = cleanTitle(title)
        if (cleaned.length < 2) return null
        return try {
            val query = """
                query (${'$'}search: String) {
                  Media(search: ${'$'}search, type: ANIME) {
                    id
                    idMal
                    coverImage { extraLarge large }
                    bannerImage
                    averageScore
                    genres
                    description(asHtml: false)
                    title { romaji english native }
                    synonyms
                    startDate { year }
                    format
                    episodes
                    studios(isMain: true) { nodes { name } }
                  }
                }
            """.trimIndent()

            val body = mapOf(
                "query" to query,
                "variables" to mapOf("search" to cleaned)
            ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

            val response = app.post(
                anilistApi,
                requestBody = body,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json"
                ),
                cacheTime = 3600
            )
            AppUtils.parseJson<AniListResponse>(response.text).data?.Media
        } catch (_: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ani.zip — AniList ID → TMDB, Kitsu, MAL, Simkl IDs
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun getAniZipMeta(anilistId: Int): AniZipMeta? {
        return try {
            val json = JSONObject(
                app.get(
                    "https://api.ani.zip/mappings?anilist_id=$anilistId",
                    cacheTime = 3600
                ).text
            )
            val m = json.optJSONObject("mappings") ?: return null
            AniZipMeta(
                themoviedbId = m.optString("themoviedb_id", null).ifBlank { null },
                kitsuid = m.optString("kitsu_id", null).ifBlank { null },
                malId = if (m.has("mal_id") && m.optString("mal_id").isNotBlank())
                    m.optInt("mal_id") else null,
                simklId = if (m.has("simkl_id") && m.optString("simkl_id").isNotBlank())
                    m.optInt("simkl_id") else null
            )
        } catch (_: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  FIX #1 — TMDB direct search for anime (fallback when ani.zip has no mapping)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Searches TMDB using the AniList English/Romaji title.
     * Tries "tv" first (most anime), then "movie" as fallback.
     * This bridges the gap when ani.zip has no TMDB mapping for an anime.
     */
    private suspend fun searchTmdbForAnime(
        anilistTitle: String?,
        isMovie: Boolean
    ): Int? {
        if (anilistTitle.isNullOrBlank() || tmdbKey.isBlank()) return null
        return try {
            // Anime series → try TV first.  Anime movies → try movie first.
            val types = if (isMovie) listOf("movie", "tv") else listOf("tv", "movie")
            for (type in types) {
                val url = "$tmdbApi/search/$type?api_key=$tmdbKey" +
                        "&query=${URLEncoder.encode(anilistTitle, "UTF-8")}" +
                        "&language=en-US&page=1"
                val results = AppUtils.parseJson<TmdbSearchResponse>(
                    app.get(url, cacheTime = 3600).text
                ).results?.firstOrNull()
                if (results?.id != null) return results.id
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TMDB — extract logo from a logos JSONArray
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Picks the best logo: English non-SVG > English SVG > any non-SVG > any SVG.
     * Returns full URL or null.
     */
    private fun extractLogoFromJson(logos: org.json.JSONArray?): String? {
        if (logos == null || logos.length() == 0) return null

        var enNonSvg: String? = null
        var enSvg: String? = null
        var anyNonSvg: String? = null
        var anySvg: String? = null

        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            val path = logo.optString("file_path", "")
            if (path.isBlank()) continue
            val lang = logo.optString("iso_639_1", "")
            val isEn = lang == "en" || lang.isEmpty()
            val isSvg = path.endsWith(".svg", true)

            when {
                isEn && !isSvg -> if (enNonSvg == null) enNonSvg = path
                isEn && isSvg -> if (enSvg == null) enSvg = path
                !isEn && !isSvg -> if (anyNonSvg == null) anyNonSvg = path
                else -> if (anySvg == null) anySvg = path
            }
        }

        return enNonSvg?.let { "$tmdbImageBase$it" }
            ?: enSvg?.let { "$tmdbImageBase$it" }
            ?: anyNonSvg?.let { "$tmdbImageBase$it" }
            ?: anySvg?.let { "$tmdbImageBase$it" }
    }

    /** Standalone TMDB logo fetch (used for anime when TMDB ID comes from ani.zip / fallback search). */
    private suspend fun fetchTmdbLogo(tmdbId: Int?, isSeries: Boolean): String? {
        if (tmdbId == null || tmdbKey.isBlank()) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(
                app.get("$tmdbApi/$type/$tmdbId/images?api_key=$tmdbKey", cacheTime = 3600).text
            )
            extractLogoFromJson(json.optJSONArray("logos"))
        } catch (_: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TMDB — search for non-anime (poster / backdrop / overview / logo / IMDB)
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun getTmdbMeta(
        title: String,
        year: Int?,
        isSeries: Boolean
    ): TmdbMeta? {
        if (tmdbKey.isBlank()) return null
        val cleaned = cleanTitle(title)
        if (cleaned.length < 2) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val searchUrl = "$tmdbApi/search/$type?api_key=$tmdbKey" +
                    "&query=${URLEncoder.encode(cleaned, "UTF-8")}" +
                    "&language=en-US&page=1"
            val first = AppUtils.parseJson<TmdbSearchResponse>(
                app.get(searchUrl, cacheTime = 3600).text
            ).results?.firstOrNull() ?: return null

            // Fetch details in one call: external_ids + images
            val detailUrl = "$tmdbApi/$type/${first.id}" +
                    "?api_key=$tmdbKey&language=en-US" +
                    "&append_to_response=external_ids,images"
            val detail = JSONObject(app.get(detailUrl, cacheTime = 3600).text)

            val imdbId = detail
                .optJSONObject("external_ids")
                ?.optString("imdb_id", null)
                ?.ifBlank { null }

            // Genres
            val genresArr = detail.optJSONArray("genres")
            val genres = if (genresArr != null) {
                (0 until genresArr.length()).mapNotNull {
                    genresArr.optJSONObject(it)?.optString("name")
                }.ifEmpty { null }
            } else null

            // Runtime
            val runtime = if (isSeries) {
                detail.optJSONArray("episode_run_time")?.optInt(0)
            } else {
                detail.optInt("runtime", 0).takeIf { it > 0 }
            }

            // Logo from images
            val logoUrl = extractLogoFromJson(
                detail.optJSONObject("images")?.optJSONArray("logos")
            )

            TmdbMeta(
                poster = first.posterPath?.let { "$tmdbImageBase$it" },
                backdrop = first.backdropPath?.let { "$tmdbBackdropBase$it" },
                rating = first.voteAverage,
                overview = first.overview,
                logoUrl = logoUrl,
                imdbId = imdbId,
                genres = genres,
                duration = runtime
            )
        } catch (_: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Kitsu — fallback search
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun searchKitsu(title: String): String? {
        val cleaned = cleanTitle(title)
        if (cleaned.length < 2) return null
        return try {
            val response = app.get(
                "https://kitsu.io/api/edge/anime?filter[text]=${URLEncoder.encode(cleaned, "UTF-8")}",
                headers = mapOf("Accept" to "application/vnd.api+json"),
                cacheTime = 3600
            )
            JSONObject(response.text)
                .getJSONArray("data")
                .optJSONObject(0)
                ?.getString("id")
        } catch (_: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Simkl — fallback search
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun searchSimkl(title: String): Int? {
        val cleaned = cleanTitle(title)
        if (cleaned.length < 2) return null
        return try {
            val response = app.get(
                "https://api.simkl.com/search/anime?q=${URLEncoder.encode(cleaned, "UTF-8")}&client_id=YOUR_CLIENT_ID",
                cacheTime = 3600
            )
            val arr = org.json.JSONArray(response.text)
            if (arr.length() > 0) arr.getJSONObject(0)
                .optJSONObject("show")
                ?.optJSONObject("ids")
                ?.optInt("simkl") else null
        } catch (_: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Main page + Search
    // ══════════════════════════════════════════════════════════════════════════

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val json = try {
            app.get(
                "$mainApiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10",
                verify = false, cacheTime = 60
            )
        } catch (_: Exception) {
            app.get(
                "$apiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10",
                verify = false, cacheTime = 60
            )
        }
        val home = AppUtils.parseJson<PageData>(json.text)
            .posts.mapNotNull { toSearchResult(it, request.data) }
        return newHomePageResponse(request.name, home, true)
    }

    private fun toSearchResult(post: Post, categoryId: String? = null): SearchResponse? {
        if (post.type == "singleVideo" || post.type == "series") {
            val tvType = when {
                categoryId == "21" -> TvType.Anime
                categoryId == "1" -> TvType.Cartoon
                post.title.contains("anime", ignoreCase = true) -> TvType.Anime
                post.title.contains("cartoon", ignoreCase = true) -> TvType.Cartoon
                post.type == "singleVideo" -> TvType.Movie
                else -> TvType.TvSeries
            }

            return newAnimeSearchResponse(
                post.title,
                "$mainUrl/content/${post.id}",
                tvType
            ) {
                this.posterUrl = "$mainApiUrl/uploads/${post.imageSm}"
                val check = post.title.lowercase()
                this.quality = getSearchQuality(check)
                addDubStatus(
                    dubExist = "dubbed" in check || "dual audio" in check || "multi audio" in check,
                    subExist = false
                )
            }
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = try {
            app.get(
                "$mainApiUrl/api/posts?searchTerm=$query&order=desc",
                verify = false, cacheTime = 60
            )
        } catch (_: Exception) {
            app.get(
                "$apiUrl/api/posts?searchTerm=$query&order=desc",
                verify = false, cacheTime = 60
            )
        }
        return AppUtils.parseJson<PageData>(json.text)
            .posts.mapNotNull { toSearchResult(it) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Load — the big one
    // ══════════════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val json = try {
            app.get(
                url.replace("$mainUrl/content/", "$mainApiUrl/api/posts/"),
                verify = false, cacheTime = 60
            )
        } catch (_: Exception) {
            app.get(
                url.replace("$mainUrl/content/", "$apiUrl/api/posts/"),
                verify = false, cacheTime = 60
            )
        }
        val urlCheck = json.url.contains(mainApiUrl)
        val loadData = AppUtils.parseJson<Data>(json.text)
        val providerTitle = loadData.name?.ifBlank { loadData.title } ?: loadData.title
        val fallbackPoster = "$apiUrl/uploads/${loadData.image}"
        val year = selectUntilNonInt(loadData.year)
        val isMovie = loadData.type == "singleVideo"

        // ── FIX #3: Anime detection ───────────────────────────────────────────
        // Series: only category 21 (Anime Series) → prevents Family Guy / Avatar
        //         from being treated as anime.
        // Movies: categories 1 + 21 → Ghibli / anime movies in Animation category
        //         are still detected; AniList search acts as confirmation since
        //         non-anime animation won't match on AniList.
        val isAnime = if (isMovie) {
            loadData.categories?.any { it.id in setOf(1, 21) } == true
        } else {
            loadData.categories?.any { it.id in animeCategoryIds } == true
        }

        // ── Fetch metadata ────────────────────────────────────────────────────
        // Anime: AniList → ani.zip → TMDB fallback
        // Non-anime: TMDB only (no AniList / Kitsu / Simkl)
        val aniMeta = if (isAnime) getAniListMeta(providerTitle) else null
        val aniZip = if (aniMeta?.id != null) getAniZipMeta(aniMeta.id) else null
        val tmdbMeta = getTmdbMeta(providerTitle, year, isSeries = !isMovie)

        // ── Resolve tracker IDs ──────────────────────────────────────────────
        // Anime gets AniList + MAL + Kitsu + Simkl
        // Non-anime gets IMDB + Trakt only
        val anilistId = aniMeta?.id
        val malId = aniMeta?.idMal ?: aniZip?.malId
        val kitsuId = if (isAnime) {
            aniZip?.kitsuid ?: searchKitsu(providerTitle)
        } else null
        val simklId = if (isAnime) {
            aniZip?.simklId ?: searchSimkl(providerTitle)
        } else null
        val imdbId = tmdbMeta?.imdbId

        // ── FIX #1: Anime logo resolution ────────────────────────────────────
        // Chain: ani.zip TMDB ID → search TMDB with AniList title → null
        val tmdbIdForLogo = if (isAnime) {
            aniZip?.themoviedbId?.toIntOrNull()
                ?: searchTmdbForAnime(
                    aniMeta?.title?.english
                        ?: aniMeta?.title?.romaji
                        ?: cleanTitle(providerTitle),
                    isMovie
                )
        } else null

        val logoUrl = if (isAnime) {
            fetchTmdbLogo(tmdbIdForLogo, isSeries = !isMovie)
        } else {
            tmdbMeta?.logoUrl
        }

        // ── Title enrichment (anime only) ───────────────────────────────────
        val displayTitle = if (isAnime) {
            aniMeta?.title?.english
                ?: aniMeta?.title?.romaji
                ?: providerTitle
        } else {
            providerTitle
        }

        // ── Poster ───────────────────────────────────────────────────────────
        val posterUrl = if (isAnime) {
            aniMeta?.coverImage?.extraLarge
                ?: aniMeta?.coverImage?.large
                ?: tmdbMeta?.poster
                ?: fallbackPoster
        } else {
            tmdbMeta?.poster ?: fallbackPoster
        }

        // ── Background ───────────────────────────────────────────────────────
        val backgroundUrl = if (isAnime) {
            aniMeta?.bannerImage ?: tmdbMeta?.backdrop
        } else {
            tmdbMeta?.backdrop
        }

        // ── Plot ─────────────────────────────────────────────────────────────
        val plot = if (isAnime) {
            aniMeta?.description
                ?.replace(Regex("<[^>]*>"), "")
                ?.replace(Regex("\\n{3,}"), "\n\n")
                ?.trim()
                ?: tmdbMeta?.overview
                ?: loadData.metaData
        } else {
            tmdbMeta?.overview ?: loadData.metaData
        }

        // ── Genres / Tags ────────────────────────────────────────────────────
        val tags = if (isAnime) {
            val list = mutableListOf<String>()
            aniMeta?.genres?.let { list.addAll(it) }
            aniMeta?.studios?.nodes
                ?.mapNotNull { it?.name }
                ?.let { list.addAll(it) }
            list.ifEmpty { null }
        } else {
            tmdbMeta?.genres
        }

        // ── Score ────────────────────────────────────────────────────────────
        val score = when {
            isAnime && aniMeta?.averageScore != null -> Score.from100(aniMeta.averageScore)
            tmdbMeta?.rating != null -> Score.from10(tmdbMeta.rating)
            else -> null
        }

        // ── Year ─────────────────────────────────────────────────────────────
        val enrichedYear = aniMeta?.startDate?.year ?: year

        // ── Build LoadResponse ───────────────────────────────────────────────
        if (isMovie) {
            val movieUrl = json.parsed<Movies>().content
            val link = if (urlCheck) movieUrl else linkToIp(movieUrl)
            val duration = getDurationFromString(loadData.watchTime)

            return if (isAnime) {
                newAnimeLoadResponse(displayTitle, url, TvType.AnimeMovie) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backgroundUrl
                    this.year = enrichedYear
                    this.plot = plot
                    this.tags = tags
                    this.score = score
                    this.duration = duration
                    try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                    // ── Tracker IDs (anime) ──
                    addAniListId(anilistId)
                    addMalId(malId)
                    try { addKitsuId(kitsuId) } catch (_: Throwable) {}
                    try { addSimklId(simklId) } catch (_: Throwable) {}
                    try { if (!imdbId.isNullOrBlank()) addImdbId(imdbId) } catch (_: Throwable) {}
                    addEpisodes(DubStatus.None, listOf(newEpisode(link ?: "")))
                }
            } else {
                newMovieLoadResponse(displayTitle, url, TvType.Movie, link) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backgroundUrl
                    this.year = enrichedYear
                    this.plot = plot
                    this.tags = tags
                    this.score = score
                    this.duration = duration
                    try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                    // ── Tracker IDs (non-anime: IMDB only) ──
                    try { if (!imdbId.isNullOrBlank()) addImdbId(imdbId) } catch (_: Throwable) {}
                }
            }
        } else {
            // ── TV Series / Anime Series ─────────────────────────────────────
            val tvData = json.parsed<TvSeries>()
            val episodesData = mutableListOf<Episode>()
            var seasonNum = 0
            tvData.content.forEach { season ->
                seasonNum++
                var episodeNum = 0
                season.episodes.forEach {
                    episodeNum++
                    val link = if (urlCheck) it.link else linkToIp(it.link)
                    episodesData.add(
                        newEpisode(link) {
                            this.episode = episodeNum
                            this.season = seasonNum
                        }
                    )
                }
            }

            return if (isAnime) {
                newAnimeLoadResponse(displayTitle, url, TvType.Anime) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backgroundUrl
                    this.year = enrichedYear
                    this.plot = plot
                    this.tags = tags
                    this.score = score
                    try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                    // ── Tracker IDs (anime) ──
                    addAniListId(anilistId)
                    addMalId(malId)
                    try { addKitsuId(kitsuId) } catch (_: Throwable) {}
                    try { addSimklId(simklId) } catch (_: Throwable) {}
                    try { if (!imdbId.isNullOrBlank()) addImdbId(imdbId) } catch (_: Throwable) {}
                    addEpisodes(DubStatus.Subbed, episodesData)
                }
            } else {
                newTvSeriesLoadResponse(displayTitle, url, TvType.TvSeries, episodesData) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backgroundUrl
                    this.year = enrichedYear
                    this.plot = plot
                    this.tags = tags
                    this.score = score
                    try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                    // ── Tracker IDs (non-anime: IMDB only) ──
                    try { if (!imdbId.isNullOrBlank()) addImdbId(imdbId) } catch (_: Throwable) {}
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Utilities
    // ══════════════════════════════════════════════════════════════════════════

    private fun linkToIp(data: String?): String {
        if (data != null) {
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
        } else return ""
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(source = this.name, name = this.name, url = data)
        )
        return true
    }

    private fun selectUntilNonInt(string: String?): Int? {
        return string?.let {
            Regex("""^.*?(?=\D|$)""").find(it)?.value?.toIntOrNull()
        }
    }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val c = check?.lowercase() ?: return null
        return when {
            c.contains("webrip") || c.contains("web-dl") -> SearchQuality.WebRip
            c.contains("bluray") -> SearchQuality.BlueRay
            c.contains("hdts") || c.contains("hdcam") || c.contains("hdtc") -> SearchQuality.HdCam
            c.contains("dvd") -> SearchQuality.DVD
            c.contains("cam") -> SearchQuality.Cam
            c.contains("camrip") || c.contains("rip") -> SearchQuality.CamRip
            c.contains("hdrip") || c.contains("hd") || c.contains("hdtv") -> SearchQuality.HD
            c.contains("telesync") -> SearchQuality.Telesync
            c.contains("telecine") -> SearchQuality.Telecine
            else -> null
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Data classes — Provider API
    // ══════════════════════════════════════════════════════════════════════════

    data class PageData(val posts: List<Post>)
    data class Post(
        val id: Int, val type: String, val imageSm: String,
        val title: String, val name: String?
    )
    data class Data(
        val type: String, val imageSm: String, val title: String, val image: String,
        val metaData: String?, val name: String?, val quality: String?,
        val year: String?, val watchTime: String?, val categories: List<Category>?
    )
    data class Category(val id: Int, val name: String?)
    data class TvSeries(val content: List<Content>)
    data class Content(val episodes: List<EpisodeData>, val seasonName: String)
    data class EpisodeData(val link: String, val title: String)
    data class Movies(val content: String?)

    // ══════════════════════════════════════════════════════════════════════════
    //  Data classes — AniList
    // ══════════════════════════════════════════════════════════════════════════

    data class AniListResponse(val data: AniListData?)
    data class AniListData(val Media: AniListMeta?)
    data class AniListMeta(
        val id: Int?,
        val idMal: Int?,
        val coverImage: AniListCoverImage?,
        val bannerImage: String?,
        val averageScore: Int?,
        val genres: List<String>?,
        val description: String?,
        val title: AniListTitle?,
        val synonyms: List<String>?,
        val startDate: AniListStartDate?,
        val format: String?,
        val episodes: Int?,
        val studios: AniListStudioConnection?
    )
    data class AniListCoverImage(val extraLarge: String?, val large: String?)
    data class AniListTitle(val romaji: String?, val english: String?, val native: String?)
    data class AniListStartDate(val year: Int?, val month: Int?, val day: Int?)
    data class AniListStudioConnection(val nodes: List<AniListStudioNode?>?)
    data class AniListStudioNode(val name: String?)

    // ══════════════════════════════════════════════════════════════════════════
    //  Data classes — ani.zip
    // ══════════════════════════════════════════════════════════════════════════

    data class AniZipMeta(
        val themoviedbId: String?,
        val kitsuid: String?,
        val malId: Int?,
        val simklId: Int?
    )

    // ══════════════════════════════════════════════════════════════════════════
    //  Data classes — TMDB
    // ══════════════════════════════════════════════════════════════════════════

    data class TmdbSearchResponse(val results: List<TmdbResult>?)
    data class TmdbResult(
        val id: Int?,
        val posterPath: String?,
        val backdropPath: String?,
        val voteAverage: Double?,
        val overview: String?
    )
    data class TmdbMeta(
        val poster: String?,
        val backdrop: String?,
        val rating: Double?,
        val overview: String?,
        val logoUrl: String?,
        val imdbId: String?,
        val genres: List<String>?,
        val duration: Int?
    )
}
