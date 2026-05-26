package com.wizdier

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.getDurationFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
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

    private val tmdbApiKey = "0b2d522346f5ecbafa42ae4b0141c774"
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val tmdbImageBaseUrl = "https://image.tmdb.org/t/p"

    private val animeCategoryIds = setOf("80", "1")

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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val json = try {
            app.get(
                "$mainApiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10",
                verify = false,
                cacheTime = 60
            )
        } catch (_: Exception) {
            app.get(
                "$apiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10",
                verify = false,
                cacheTime = 60
            )
        }
        val home = parseJson<PageData>(json.text).posts.mapNotNull { post ->
            toSearchResult(post, request.data)
        }
        return newHomePageResponse(request.name, home, true)
    }

    private fun toSearchResult(post: Post, categoryId: String? = null): SearchResponse? {
        if (post.type == "singleVideo" || post.type == "series") {
            val tvType = when {
                categoryId in animeCategoryIds -> TvType.Anime
                post.title.contains("anime", ignoreCase = true) -> TvType.Anime
                post.title.contains("cartoon", ignoreCase = true) -> TvType.Cartoon
                post.title.contains("animation", ignoreCase = true) -> TvType.Cartoon
                categoryId == "1" -> TvType.Cartoon
                post.type == "singleVideo" -> TvType.Movie
                else -> TvType.TvSeries
            }

            return newAnimeSearchResponse(post.title, "$mainUrl/content/${post.id}", tvType) {
                this.posterUrl = "$mainApiUrl/uploads/${post.imageSm}"
                val check = post.title.lowercase()
                this.quality = getSearchQuality(check)
                addDubStatus(
                    dubExist = when {
                        "dubbed" in check -> true
                        "dual audio" in check -> true
                        "multi audio" in check -> true
                        else -> false
                    },
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
                verify = false,
                cacheTime = 60
            )
        } catch (_: Exception) {
            app.get(
                "$apiUrl/api/posts?searchTerm=$query&order=desc",
                verify = false,
                cacheTime = 60
            )
        }
        return parseJson<PageData>(json.text).posts.mapNotNull { post ->
            toSearchResult(post)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val json = try {
            app.get(
                url.replace("$mainUrl/content/", "$mainApiUrl/api/posts/"),
                verify = false,
                cacheTime = 60
            )
        } catch (_: Exception) {
            app.get(
                url.replace("$mainUrl/content/", "$apiUrl/api/posts/"),
                verify = false,
                cacheTime = 60
            )
        }
        val urlCheck = json.url.contains(mainApiUrl)
        val loadData = parseJson<Data>(json.text)
        val providerTitle = loadData.title
        val providerPoster = "$apiUrl/uploads/${loadData.image}"
        val providerDescription = loadData.metaData
        val year = selectUntilNonInt(loadData.year)

        val isAnime = providerTitle.contains("anime", ignoreCase = true) ||
                providerTitle.contains("Animation", ignoreCase = true) &&
                (loadData.type == "series" || !providerTitle.contains("movie", ignoreCase = true))

        // ──────────────────────────────────────────────
        // Fetch enriched metadata from TMDB and AniList
        // ──────────────────────────────────────────────
        val tmdbData = fetchTmdbMetadata(providerTitle, year, loadData.type)
        val animeData = if (isAnime) resolveAnimeMetadata(providerTitle) else null

        // ── Title: AniList English > AniList Romaji > TMDB > Provider ──
        val enrichedTitle = when {
            animeData?.titleEnglish != null -> animeData.titleEnglish
            animeData?.titleRomaji != null -> animeData.titleRomaji
            tmdbData?.title != null -> tmdbData.title
            else -> providerTitle
        }

        // ── Poster: AniList cover (anime) > TMDB poster > Provider poster ──
        val finalPoster = when {
            isAnime && !animeData?.coverImage.isNullOrBlank() -> animeData.coverImage
            !tmdbData?.posterUrl.isNullOrBlank() -> tmdbData.posterUrl
            else -> providerPoster
        }

        // ── Background: AniList banner (anime) > TMDB backdrop ──
        val backgroundPoster = when {
            isAnime && !animeData?.bannerImage.isNullOrBlank() -> animeData.bannerImage
            !tmdbData?.backdropUrl.isNullOrBlank() -> tmdbData.backdropUrl
            else -> null
        }

        // ── Logo: TMDB only ──
        val logoUrl = tmdbData?.logoUrl

        // ── Plot: AniList (anime) > TMDB > Provider ──
        val enrichedPlot = when {
            isAnime && !animeData?.description.isNullOrBlank() -> animeData.description
            !tmdbData?.overview.isNullOrBlank() -> tmdbData.overview
            else -> providerDescription
        }

        // ── Year: AniList > TMDB > Provider ──
        val enrichedYear = animeData?.year ?: tmdbData?.year ?: year

        // ── Duration: TMDB > Provider ──
        val enrichedDuration = tmdbData?.duration ?: getDurationFromString(loadData.watchTime)

        // ── Genres: AniList (anime) > TMDB ──
        val genres = when {
            isAnime -> animeData?.genres ?: tmdbData?.genres
            else -> tmdbData?.genres
        }

        // ── Rating: AniList averageScore (anime, 0-100) > TMDB (0-100) ──
        val rating = when {
            isAnime && animeData?.averageScore != null -> animeData.averageScore
            tmdbData?.rating != null -> tmdbData.rating
            else -> null
        }

        // ── Actors: TMDB only ──
        val actors = tmdbData?.actors

        // ── Tracker IDs ──
        val malId = animeData?.malId
        val aniListId = animeData?.aniListId
        val imdbId = tmdbData?.imdbId
        val tmdbId = tmdbData?.tmdbId

        // ──────────────────────────────────────────────
        // Build LoadResponse
        // ──────────────────────────────────────────────
        if (loadData.type == "singleVideo") {
            val movieData = parseJson<Movies>(json.text)
            val movieUrl = movieData.content
            val link = if (urlCheck) movieUrl else linkToIp(movieUrl)
            val duration = getDurationFromString(loadData.watchTime)

            val movieType = when {
                isAnime -> TvType.AnimeMovie
                providerTitle.contains("cartoon", ignoreCase = true) -> TvType.Cartoon
                providerTitle.contains("animation", ignoreCase = true) -> TvType.Cartoon
                else -> TvType.Movie
            }

            return newMovieLoadResponse(enrichedTitle, url, movieType, link) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = backgroundPoster
                try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                this.year = enrichedYear
                this.plot = enrichedPlot
                this.duration = enrichedDuration ?: duration
                this.score = rating?.let { Score.from10(it.toDouble() / 10) }
                this.tags = genres
                actors?.let { addActors(it) }

                // ── Tracker IDs ──
                addMalId(malId)
                addAniListId(aniListId)
                // IMDB ID enables Simkl tracking (Simkl resolves via IMDB)
                try {
                    if (!imdbId.isNullOrBlank()) {
                        // Try the standard CS3 method if available
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val addImdbId = LoadResponse.Companion::class.java
                                .getMethod("addImdbId", String::class.java)
                            addImdbId.invoke(this, imdbId)
                        } catch (_: Throwable) {
                            // Fallback: store in the LoadResponse imdbId field directly
                            try {
                                val field = this.javaClass.superclass
                                    .getDeclaredField("imdbId")
                                field.isAccessible = true
                                field.set(this, imdbId)
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}

                // TMDB ID
                try {
                    if (tmdbId != null) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val addTmdbId = LoadResponse.Companion::class.java
                                .getMethod("addTmdbId", Int::class.javaPrimitiveType)
                            addTmdbId.invoke(this, tmdbId)
                        } catch (_: Throwable) {
                            try {
                                val field = this.javaClass.superclass
                                    .getDeclaredField("tmdbId")
                                field.isAccessible = true
                                field.set(this, tmdbId)
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
            }
        } else {
            val tvData = parseJson<TvSeries>(json.text)
            val episodesData = mutableListOf<Episode>()
            var seasonNum = 0
            tvData.content.forEach { season ->
                seasonNum++
                var episodeNum = 0
                season.episodes.forEach {
                    episodeNum++
                    val episodeUrl = it.link
                    val link = if (urlCheck) episodeUrl else linkToIp(episodeUrl)
                    episodesData.add(
                        newEpisode(link) {
                            this.episode = episodeNum
                            this.season = seasonNum
                            this.name =
                                it.title.takeIf { t -> t.isNotBlank() } ?: "Episode $episodeNum"
                        }
                    )
                }
            }

            val tvType = when {
                isAnime -> TvType.Anime
                providerTitle.contains("cartoon", ignoreCase = true) -> TvType.Cartoon
                providerTitle.contains("animation", ignoreCase = true) -> TvType.Cartoon
                else -> TvType.TvSeries
            }

            return newTvSeriesLoadResponse(enrichedTitle, url, tvType, episodesData) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = backgroundPoster
                try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                this.year = enrichedYear
                this.plot = enrichedPlot
                this.score = rating?.let { Score.from10(it.toDouble() / 10) }
                this.tags = genres
                actors?.let { addActors(it) }

                // ── Tracker IDs ──
                addMalId(malId)
                addAniListId(aniListId)
                // IMDB ID enables Simkl tracking
                try {
                    if (!imdbId.isNullOrBlank()) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val addImdbId = LoadResponse.Companion::class.java
                                .getMethod("addImdbId", String::class.java)
                            addImdbId.invoke(this, imdbId)
                        } catch (_: Throwable) {
                            try {
                                val field = this.javaClass.superclass
                                    .getDeclaredField("imdbId")
                                field.isAccessible = true
                                field.set(this, imdbId)
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}

                // TMDB ID
                try {
                    if (tmdbId != null) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val addTmdbId = LoadResponse.Companion::class.java
                                .getMethod("addTmdbId", Int::class.javaPrimitiveType)
                            addTmdbId.invoke(this, tmdbId)
                        } catch (_: Throwable) {
                            try {
                                val field = this.javaClass.superclass
                                    .getDeclaredField("tmdbId")
                                field.isAccessible = true
                                field.set(this, tmdbId)
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    // ════════════════════════════════════════════════════════
    //  TMDB Metadata Fetcher (enhanced with IMDB + TMDB IDs)
    // ════════════════════════════════════════════════════════
    private suspend fun fetchTmdbMetadata(
        title: String,
        year: Int?,
        type: String?
    ): TmdbMetadata? {
        return try {
            val searchType = if (type == "singleVideo") "movie" else "tv"
            val yearQuery = year?.let { "&year=$it" } ?: ""

            val searchResponse = app.get(
                "$tmdbBaseUrl/search/$searchType?api_key=$tmdbApiKey&query=${title.encodeUri()}&language=en-US&page=1$yearQuery",
                verify = false,
                cacheTime = 3600
            )

            val searchResults = parseJson<TmdbSearchResponse>(searchResponse.text)
            val bestMatch = searchResults.results.firstOrNull() ?: return null

            val tmdbId = bestMatch.id
            val tmdbTitle = bestMatch.title ?: bestMatch.name

            val detailsResponse = app.get(
                "$tmdbBaseUrl/$searchType/$tmdbId?api_key=$tmdbApiKey&language=en-US&append_to_response=credits,images,external_ids",
                verify = false,
                cacheTime = 3600
            )

            val details = parseJson<TmdbDetails>(detailsResponse.text)

            val cast = details.credits?.cast?.take(10)?.map {
                Actor(it.name, it.profilePath?.let { p -> "$tmdbImageBaseUrl/w185$p" }) to it.character
            }

            val logoPath = details.images?.logos?.firstOrNull {
                it.iso6391 == "en" || it.iso6391 == null
            }?.filePath

            TmdbMetadata(
                tmdbId = tmdbId,
                imdbId = details.externalIds?.imdbId,
                title = tmdbTitle,
                posterUrl = bestMatch.posterPath?.let { "$tmdbImageBaseUrl/w500$it" },
                backdropUrl = bestMatch.backdropPath?.let { "$tmdbImageBaseUrl/original$it" },
                logoUrl = logoPath?.let { "$tmdbImageBaseUrl/w500$it" },
                overview = details.overview,
                year = details.releaseDate?.take(4)?.toIntOrNull()
                    ?: details.firstAirDate?.take(4)?.toIntOrNull(),
                duration = details.runtime ?: details.episodeRunTime?.firstOrNull(),
                genres = details.genres?.map { it.name },
                rating = details.voteAverage?.let { (it * 10).toInt() },
                actors = cast
            )
        } catch (e: Exception) {
            null
        }
    }

    // ════════════════════════════════════════════════════════
    //  AniList Metadata Fetcher (enriched with full info)
    //  Also provides MAL ID (needed for Kitsu cross-ref)
    // ════════════════════════════════════════════════════════
    private suspend fun resolveAnimeMetadata(title: String): AnimeMetadata? {
        // Escape special characters in title for safe GraphQL string interpolation
        val safeTitle = title.escapeGraphQL()

        val query = """
            query {
                Page(perPage: 1) {
                    media(search: "$safeTitle", type: ANIME) {
                        id
                        idMal
                        title { romaji english native }
                        coverImage { large extraLarge }
                        bannerImage
                        description(asHtml: false)
                        synonyms
                        startDate { year month day }
                        format
                        genres
                        averageScore
                        episodes
                        duration
                        studios(isMain: true) { nodes { name } }
                        siteUrl
                    }
                }
            }
        """.trimIndent()

        return try {
            val response = app.post(
                "https://graphql.anilist.co",
                verify = false,
                headers = mapOf("Content-Type" to "application/json"),
                data = mapOf("query" to query)
            )
            val result = parseJson<AniListSearchResult>(response.text)
            val media = result?.data?.page?.media?.firstOrNull() ?: return null

            // Clean HTML from description
            val cleanDescription = media.description
                ?.replace(Regex("<[^>]*>"), "")
                ?.replace(Regex("\\n\\s*"), "\n")
                ?.trim()

            AnimeMetadata(
                aniListId = media.id,
                malId = media.idMal,
                titleEnglish = media.title?.english,
                titleRomaji = media.title?.romaji,
                titleNative = media.title?.native,
                coverImage = media.coverImage?.extraLarge ?: media.coverImage?.large,
                bannerImage = media.bannerImage,
                description = cleanDescription,
                year = media.startDate?.year,
                averageScore = media.averageScore,
                genres = media.genres,
                episodeCount = media.episodes,
                episodeDuration = media.duration,
                studios = media.studios?.nodes?.mapNotNull { it?.name },
                anilistUrl = media.siteUrl
            )
        } catch (e: Exception) {
            null
        }
    }

    // ════════════════════════════════════════════════════════
    //  Helper Utilities
    // ════════════════════════════════════════════════════════
    private fun String.encodeUri(): String = URLEncoder.encode(this, "UTF-8")

    /** Escapes backslashes and double-quotes for safe GraphQL string interpolation. */
    private fun String.escapeGraphQL(): String =
        this.replace("\\", "\\\\").replace("\"", "\\\"")

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
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = data
            )
        )
        return true
    }

    private fun selectUntilNonInt(string: String?): Int? {
        return string?.let {
            Regex("^.*?(?=\\D|\$)").find(it)?.value?.toIntOrNull()
        }
    }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val lowercaseCheck = check?.lowercase()
        if (lowercaseCheck != null) {
            return when {
                lowercaseCheck.contains("webrip") || lowercaseCheck.contains("web-dl") -> SearchQuality.WebRip
                lowercaseCheck.contains("bluray") -> SearchQuality.BlueRay
                lowercaseCheck.contains("hdts") || lowercaseCheck.contains("hdcam") || lowercaseCheck.contains("hdtc") -> SearchQuality.HdCam
                lowercaseCheck.contains("dvd") -> SearchQuality.DVD
                lowercaseCheck.contains("cam") -> SearchQuality.Cam
                lowercaseCheck.contains("camrip") || lowercaseCheck.contains("rip") -> SearchQuality.CamRip
                lowercaseCheck.contains("hdrip") || lowercaseCheck.contains("hd") || lowercaseCheck.contains("hdtv") -> SearchQuality.HD
                lowercaseCheck.contains("telesync") -> SearchQuality.Telesync
                lowercaseCheck.contains("telecine") -> SearchQuality.Telecine
                else -> null
            }
        }
        return null
    }

    // ════════════════════════════════════════════════════════
    //  Data Classes — Provider API
    // ════════════════════════════════════════════════════════
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

    // ════════════════════════════════════════════════════════
    //  Data Classes — Enriched Metadata
    // ════════════════════════════════════════════════════════

    /** Holds enriched metadata fetched from AniList GraphQL. */
    data class AnimeMetadata(
        val aniListId: Int?,
        val malId: Int?,
        val titleEnglish: String?,
        val titleRomaji: String?,
        val titleNative: String?,
        val coverImage: String?,
        val bannerImage: String?,
        val description: String?,
        val year: Int?,
        val averageScore: Int?,      // 0-100
        val genres: List<String>?,
        val episodeCount: Int?,
        val episodeDuration: Int?,    // minutes per episode
        val studios: List<String>?,
        val anilistUrl: String?
    )

    /** Holds enriched metadata fetched from TMDB API. */
    data class TmdbMetadata(
        val tmdbId: Int?,
        val imdbId: String?,          // "tt1234567" — enables Simkl tracking
        val title: String?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val logoUrl: String?,
        val overview: String?,
        val year: Int?,
        val duration: Int?,           // minutes
        val genres: List<String>?,
        val rating: Int?,             // 0-100 scale
        val actors: List<Pair<Actor, String?>>?
    )

    // ════════════════════════════════════════════════════════
    //  Data Classes — TMDB API Responses
    // ════════════════════════════════════════════════════════
    data class TmdbSearchResponse(val results: List<TmdbSearchResult>)
    data class TmdbSearchResult(
        val id: Int,
        val posterPath: String?,
        val backdropPath: String?,
        val title: String?,
        val name: String?,
        val releaseDate: String?,
        val firstAirDate: String?
    )
    data class TmdbDetails(
        val overview: String?,
        val runtime: Int?,
        val episodeRunTime: List<Int>?,
        val releaseDate: String?,
        val firstAirDate: String?,
        val voteAverage: Double?,
        val genres: List<TmdbGenre>?,
        val credits: TmdbCredits?,
        val images: TmdbImages?,
        val externalIds: TmdbExternalIds?
    )
    data class TmdbGenre(val name: String)
    data class TmdbCredits(val cast: List<TmdbCast>)
    data class TmdbCast(
        val name: String,
        val profilePath: String?,
        val character: String?
    )
    data class TmdbImages(val logos: List<TmdbLogo>?)
    data class TmdbLogo(val filePath: String?, val iso6391: String?)
    data class TmdbExternalIds(val imdbId: String?)

    // ════════════════════════════════════════════════════════
    //  Data Classes — AniList GraphQL Responses
    // ════════════════════════════════════════════════════════
    data class AniListSearchResult(val data: AniListData?)
    data class AniListData(val page: AniListPage?)
    data class AniListPage(val media: List<AniListMedia>?)
    data class AniListMedia(
        val id: Int,
        val idMal: Int?,
        val title: AniListTitle?,
        val coverImage: AniListCoverImage?,
        val bannerImage: String?,
        val description: String?,
        val synonyms: List<String>?,
        val startDate: AniListDate?,
        val format: String?,
        val genres: List<String>?,
        val averageScore: Int?,
        val episodes: Int?,
        val duration: Int?,
        val studios: AniListStudioConnection?,
        val siteUrl: String?
    )
    data class AniListTitle(
        val romaji: String?,
        val english: String?,
        val native: String?
    )
    data class AniListCoverImage(val large: String?, val extraLarge: String?)
    data class AniListDate(val year: Int?, val month: Int?, val day: Int?)
    data class AniListStudioConnection(val nodes: List<AniListStudioNode?>?)
    data class AniListStudioNode(val name: String?)
}