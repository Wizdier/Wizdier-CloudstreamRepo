package com.redowan

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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
        TvType.Others
    )

    private val tmdbApiKey = "YOUR_TMDB_API_KEY"
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
        val home = parseJson<<PageData>(json.text).posts.mapNotNull { post ->
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

    override suspend fun search(query: String): List<<SearchResponse> {
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
        return parseJson<<PageData>(json.text).posts.mapNotNull { post ->
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
        val title = loadData.title
        val poster = "$apiUrl/uploads/${loadData.image}"
        val description = loadData.metaData
        val year = selectUntilNonInt(loadData.year)

        val tmdbData = fetchTmdbMetadata(title, year, loadData.type)

        val finalPoster = tmdbData?.posterUrl ?: poster
        val backgroundPoster = tmdbData?.backdropUrl
        val logoUrl = tmdbData?.logoUrl
        val enrichedPlot = tmdbData?.overview ?: description
        val enrichedYear = tmdbData?.year ?: year
        val enrichedDuration = tmdbData?.duration ?: getDurationFromString(loadData.watchTime)
        val genres = tmdbData?.genres
        val rating = tmdbData?.rating
        val actors = tmdbData?.actors?.map { 
            ActorData(Actor(it.name, it.profilePath), roleString = it.character) 
        }

        val isAnime = title.contains("anime", ignoreCase = true) ||
                title.contains("Animation", ignoreCase = true) &&
                (loadData.type == "series" || !title.contains("movie", ignoreCase = true))

        var malId: Int? = null
        var aniListId: Int? = null

        if (isAnime) {
            val ids = resolveAnimeIds(title)
            malId = ids.first
            aniListId = ids.second
        }

        if (loadData.type == "singleVideo") {
            val movieData = json.parsed<Movies>()
            val movieUrl = movieData.content
            val link = if(urlCheck) movieUrl else linkToIp(movieUrl)
            val duration = getDurationFromString(loadData.watchTime)

            val movieType = when {
                isAnime -> TvType.AnimeMovie
                title.contains("cartoon", ignoreCase = true) -> TvType.Cartoon
                title.contains("animation", ignoreCase = true) -> TvType.Cartoon
                else -> TvType.Movie
            }

            return newMovieLoadResponse(title, url, movieType, link) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = backgroundPoster
                try { this.logoUrl = logoUrl } catch(_: Throwable) {}
                this.year = enrichedYear
                this.plot = enrichedPlot
                this.duration = enrichedDuration ?: duration
                this.score = rating?.let { Score.from10(it.toDouble() / 10) }
                this.tags = genres
                actors?.let { addActors(it) }
                addMalId(malId)
                addAniListId(aniListId)
            }
        } else {
            val tvData = json.parsed<TvSeries>()
            val episodesData = mutableListOf<<Episode>()
            var seasonNum = 0
            tvData.content.forEach { season ->
                seasonNum++
                var episodeNum = 0
                season.episodes.forEach {
                    episodeNum++
                    val episodeUrl = it.link
                    val link = if(urlCheck) episodeUrl else linkToIp(episodeUrl)
                    episodesData.add(
                        newEpisode(link){
                            this.episode = episodeNum
                            this.season = seasonNum
                            this.name = it.title.takeIf { t -> t.isNotBlank() } ?: "Episode $episodeNum"
                        }
                    )
                }
            }

            val tvType = when {
                isAnime -> TvType.Anime
                title.contains("cartoon", ignoreCase = true) -> TvType.Cartoon
                title.contains("animation", ignoreCase = true) -> TvType.Cartoon
                else -> TvType.TvSeries
            }

            return newTvSeriesLoadResponse(title, url, tvType, episodesData) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = backgroundPoster
                try { this.logoUrl = logoUrl } catch(_: Throwable) {}
                this.year = enrichedYear
                this.plot = enrichedPlot
                this.score = rating?.let { Score.from10(it.toDouble() / 10) }
                this.tags = genres
                actors?.let { addActors(it) }
                addMalId(malId)
                addAniListId(aniListId)
            }
        }
    }

    private suspend fun fetchTmdbMetadata(title: String, year: Int?, type: String?): TmdbMetadata? {
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

            val detailsResponse = app.get(
                "$tmdbBaseUrl/$searchType/$tmdbId?api_key=$tmdbApiKey&language=en-US&append_to_response=credits,images,external_ids",
                verify = false,
                cacheTime = 3600
            )

            val details = parseJson<TmdbDetails>(detailsResponse.text)

            val cast = details.credits?.cast?.take(10)?.map {
                Actor(it.name, it.profilePath?.let { p -> "$tmdbImageBaseUrl/w185$p" })
            }

            val logoPath = details.images?.logos?.firstOrNull {
                it.iso6391 == "en" || it.iso6391 == null
            }?.filePath

            TmdbMetadata(
                posterUrl = bestMatch.posterPath?.let { "$tmdbImageBaseUrl/w500$it" },
                backdropUrl = bestMatch.backdropPath?.let { "$tmdbImageBaseUrl/original$it" },
                logoUrl = logoPath?.let { "$tmdbImageBaseUrl/w500$it" },
                overview = details.overview,
                year = details.releaseDate?.take(4)?.toIntOrNull() ?: details.firstAirDate?.take(4)?.toIntOrNull(),
                duration = details.runtime ?: details.episodeRunTime?.firstOrNull(),
                genres = details.genres?.map { it.name },
                rating = details.voteAverage?.let { (it * 10).toInt() },
                actors = cast
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveAnimeIds(title: String): Pair<Int?, Int?> {
        val query = """
            query {
                Page(perPage: 5) {
                    media(search: "$title", type: ANIME) {
                        id
                        idMal
                        title { romaji english }
                    }
                }
            }
        """.trimIndent()

        val result = try {
            val response = app.post(
                "https://graphql.anilist.co",
                verify = false,
                headers = mapOf("Content-Type" to "application/json"),
                data = mapOf("query" to query)
            )
            parseJson<<AniListSearchResult>(response.text)
        } catch (e: Exception) {
            null
        }

        val media = result?.data?.page?.media?.firstOrNull()
        val malId = media?.idMal
        val aniListId = media?.id

        return malId to aniListId
    }

    private fun String.encodeUri(): String = URLEncoder.encode(this, "UTF-8")

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
        }
        else return ""
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
        return string?.let { Regex("^.*?(?=\\D|$)").find(it)?.value?.toIntOrNull() }
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

    data class PageData(val posts: List<<Post>)
    data class Post(val id: Int, val type: String, val imageSm: String, val title: String, val name: String?)
    data class Data(val type: String, val imageSm: String, val title: String, val image: String, val metaData: String?, val name: String, val quality: String?, val year: String?, val watchTime: String?)
    data class TvSeries(val content: List<<Content>)
    data class Content(val episodes: List<<EpisodeData>, val seasonName: String)
    data class EpisodeData(val link: String, val title: String)
    data class Movies(val content: String?)

    data class TmdbMetadata(
        val posterUrl: String?,
        val backdropUrl: String?,
        val logoUrl: String?,
        val overview: String?,
        val year: Int?,
        val duration: Int?,
        val genres: List<String>?,
        val rating: Int?,
        val actors: List<<Actor>?
    )

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
    data class TmdbCast(val name: String, val profilePath: String?, val character: String?)
    data class TmdbImages(val logos: List<TmdbLogo>?)
    data class TmdbLogo(val filePath: String?, val iso6391: String?)
    data class TmdbExternalIds(val imdbId: String?)

    data class AniListSearchResult(val data: AniListData?)
    data class AniListData(val page: AniListPage?)
    data class AniListPage(val media: List <AniListMedia>?)
    data class AniListMedia(val id: Int, val idMal: Int?)
}