package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTraktId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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

    // Explicit anime categories - only true anime content
    private val animeCategories = setOf(21) // Only "Anime Series" category is explicitly anime
    // Categories that might be anime but need title verification
    private val possiblyAnimeCategories = setOf(1) // Animation Movies - needs title check
    private val anilistApi = "https://graphql.anilist.co"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = BuildConfig.TMDB_API
    private val tmdbImageBase = "https://image.tmdb.org/t/p/w500"
    private val tmdbBackdropBase = "https://image.tmdb.org/t/p/original"
    private val malApi = "https://api.myanimelist.net/v2"

    // Common anime title keywords
    private val animeKeywords = listOf(
        "anime", "naruto", "dragon ball", "one piece", "attack on titan",
        "demon slayer", "my hero academia", "boruto", "bleach", "fairy tail",
        "pokemon", "digimon", "sailor moon", "tokyo ghoul", "death note",
        "cowboy bebop", "fullmetal alchemist", "hunter x hunter", "jojo",
        "violet evergarden", "aot", "snk", "jujutsu kaisen", "chainsaw man",
        "spy x family", "dandadan", "kaiju no. 8", "blue lock", "solo leveling"
    )

    // Common non-anime keywords
    private val nonAnimeKeywords = listOf(
        "cartoon", "animation", "animated", "pixar", "disney", "dreamworks"
    )

    // ── AniList: Search for anime metadata + IDs (with retry) ─────────────────
    private suspend fun getAniListMeta(title: String, retryCount: Int = 2): AniListMeta? {
        repeat(retryCount) { attempt ->
            try {
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
                        title { romaji english }
                      }
                    }
                """.trimIndent()

                val body = mapOf(
                    "query" to query,
                    "variables" to mapOf("search" to title)
                ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

                val response = app.post(
                    anilistApi,
                    requestBody = body,
                    headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
                    cacheTime = 60
                )
                return AppUtils.parseJson<AniListResponse>(response.text).data?.Media
            } catch (_: Exception) {
                if (attempt == retryCount - 1) return null
                kotlinx.coroutines.delay(100L * (attempt + 1))
            }
        }
        return null
    }

    // ── MAL: Fallback search for MAL ID (with retry) ──────────────────────────
    private suspend fun searchMalId(title: String, retryCount: Int = 2): Int? {
        repeat(retryCount) { attempt ->
            try {
                val response = app.get(
                    "https://api.jikan.moe/v4/anime?q=${URLEncoder.encode(title, "UTF-8")}&limit=1",
                    cacheTime = 60
                )
                val json = JSONObject(response.text)
                val data = json.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    return data.optJSONObject(0)?.optInt("mal_id")
                }
            } catch (_: Exception) {
                // Try alternative MAL API
                try {
                    val response = app.get(
                        "https://myanimelist.p.rapidapi.com/anime/search/${URLEncoder.encode(title, "UTF-8")}/1",
                        headers = mapOf(
                            "X-RapidAPI-Key" to "demo",
                            "X-RapidAPI-Host" to "myanimelist.p.rapidapi.com"
                        ),
                        cacheTime = 60
                    )
                    val json = JSONObject(response.text)
                    val malId = json.optInt("mal_id", -1)
                    if (malId > 0) return malId
                } catch (_: Exception) {}
            }
            if (attempt < retryCount - 1) kotlinx.coroutines.delay(100L * (attempt + 1))
        }
        return null
    }

    // ── Smart anime detection ────────────────────────────────────────────────
    private fun isAnimeContent(categories: List<Category>?, title: String): Boolean {
        val titleLower = title.lowercase()
        if (categories?.any { it.id in animeCategories } == true) return true
        if (categories?.any { it.id in possiblyAnimeCategories } == true) {
            if (nonAnimeKeywords.any { titleLower.contains(it) }) return false
            if (animeKeywords.any { titleLower.contains(it) }) return true
            return true
        }
        return false
    }

    // ── ani.zip: AniList ID → TMDB, Kitsu, MAL, Simkl IDs (with retry) ──────
    private suspend fun getAniZipMeta(anilistId: Int, retryCount: Int = 2): AniZipMeta? {
        repeat(retryCount) { attempt ->
            try {
                val json = JSONObject(
                    app.get("https://api.ani.zip/mappings?anilist_id=$anilistId", cacheTime = 60).text
                )
                val m = json.optJSONObject("mappings") ?: return null
                return AniZipMeta(
                    themoviedbId = m.optString("themoviedb_id", null),
                    kitsuid = m.optString("kitsu_id", null),
                    malId = if (m.has("mal_id")) m.optInt("mal_id") else null,
                    simklId = if (m.has("simkl_id")) m.optInt("simkl_id") else null
                )
            } catch (_: Exception) {
                if (attempt == retryCount - 1) return null
                kotlinx.coroutines.delay(100L * (attempt + 1))
            }
        }
        return null
    }

    // ── Kitsu: Fallback search (with retry) ───────────────────────────────
    private suspend fun searchKitsu(title: String, retryCount: Int = 2): String? {
        repeat(retryCount) { attempt ->
            try {
                val response = app.get(
                    "https://kitsu.io/api/edge/anime?filter[text]=${title.replace(" ", "%20")}",
                    headers = mapOf("Accept" to "application/vnd.api+json"),
                    cacheTime = 60
                )
                return JSONObject(response.text)
                    .getJSONArray("data")
                    .optJSONObject(0)
                    ?.getString("id")
            } catch (_: Exception) {
                if (attempt == retryCount - 1) return null
                kotlinx.coroutines.delay(100L * (attempt + 1))
            }
        }
        return null
    }

    // ── Simkl: Fallback search (with retry) ──────────────────────────────
    private suspend fun searchSimkl(title: String, retryCount: Int = 2): Int? {
        repeat(retryCount) { attempt ->
            try {
                val response = app.get(
                    "https://api.simkl.com/search/anime?q=${title.replace(" ", "%20")}&client_id=YOUR_CLIENT_ID",
                    cacheTime = 60
                )
                val arr = org.json.JSONArray(response.text)
                if (arr.length() > 0) return arr.getJSONObject(0).optJSONObject("show")
                    ?.optJSONObject("ids")?.optInt("simkl") else null
            } catch (_: Exception) {
                if (attempt == retryCount - 1) return null
                kotlinx.coroutines.delay(100L * (attempt + 1))
            }
        }
        return null
    }

    // ── TMDB: Fetch logo for title replacement ───────────────────────────────
    private suspend fun fetchTmdbLogo(tmdbId: Int?, isSeries: Boolean): String? {
        if (tmdbId == null || tmdbKey.isBlank()) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(
                app.get("$tmdbApi/$type/$tmdbId/images?api_key=$tmdbKey", cacheTime = 60).text
            )
            val logos = json.optJSONArray("logos") ?: return null
            if (logos.length() == 0) return null

            var svgFallback: String? = null
            for (i in 0 until logos.length()) {
                val logo = logos.optJSONObject(i) ?: continue
                val path = logo.optString("file_path")
                if (path.isBlank()) continue
                val lang = logo.optString("iso_639_1", "")
                if (lang == "en" || lang.isEmpty()) {
                    if (!path.endsWith(".svg", true)) return "$tmdbImageBase$path"
                    if (svgFallback == null) svgFallback = path
                }
            }
            svgFallback?.let { "$tmdbImageBase$it" }
        } catch (_: Exception) { null }
    }

    // ── TMDB: Search and fetch logo for anime (fallback when ani.zip fails) ─
    private suspend fun fetchTmdbLogoForAnime(title: String, isSeries: Boolean): String? {
        if (tmdbKey.isBlank()) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val url = "$tmdbApi/search/$type?api_key=$tmdbKey&query=${URLEncoder.encode(title, "UTF-8")}&language=en-US&page=1"
            val first = AppUtils.parseJson<TmdbSearchResponse>(
                app.get(url, cacheTime = 60).text
            ).results?.firstOrNull() ?: return null
            fetchTmdbLogo(first.id, isSeries)
        } catch (_: Exception) { null }
    }

    // ── TMDB: Search for poster/backdrop/overview and get TMDB ID ─────────────
    private suspend fun getTmdbMeta(title: String, year: Int?, isSeries: Boolean): TmdbMeta? {
        if (tmdbKey.isBlank()) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val url = "$tmdbApi/search/$type?api_key=$tmdbKey&query=${URLEncoder.encode(title, "UTF-8")}&language=en-US&page=1"
            val first = AppUtils.parseJson<TmdbSearchResponse>(
                app.get(url, cacheTime = 60).text
            ).results?.firstOrNull() ?: return null

            val logoUrl = fetchTmdbLogo(first.id, isSeries)

            var imdbId: String? = null
            if (first.id != null) {
                try {
                    val detailUrl = "$tmdbApi/$type/${first.id}?api_key=$tmdbKey&language=en-US&append_to_response=external_ids"
                    val detail = JSONObject(app.get(detailUrl, cacheTime = 60).text)
                    imdbId = detail.optJSONObject("external_ids")?.optString("imdb_id", null)
                } catch (_: Exception) {}
            }

            TmdbMeta(
                poster = first.posterPath?.let { "$tmdbImageBase$it" },
                backdrop = first.backdropPath?.let { "$tmdbBackdropBase$it" },
                rating = first.voteAverage,
                overview = first.overview,
                logoUrl = logoUrl,
                imdbId = imdbId,
                tmdbId = first.id
            )
        } catch (_: Exception) { null }
    }

    // ── Trakt: Search for Trakt ID (for non-anime) ───────────────────────────
    private suspend fun searchTrakt(title: String, isSeries: Boolean): Int? {
        return try {
            val type = if (isSeries) "shows" else "movies"
            val response = app.get(
                "https://api.trakt.tv/search/${type}?query=${URLEncoder.encode(title, "UTF-8")}",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "trakt-api-version" to "2",
                    "trakt-api-key" to "YOUR_TRAKT_CLIENT_ID"
                ),
                cacheTime = 60
            )
            val json = JSONObject(response.text)
            json.optJSONArray("results")?.optJSONObject(0)?.optInt("trakt")
        } catch (_: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = try {
            app.get("$mainApiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10", verify = false, cacheTime = 60)
        } catch (_: Exception) {
            app.get("$apiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10", verify = false, cacheTime = 60)
        }
        val home = AppUtils.parseJson<PageData>(json.text).posts.mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request.name, home, true)
    }

    private fun toSearchResult(post: Post): SearchResponse? {
        if (post.type == "singleVideo" || post.type == "series") {
            return newAnimeSearchResponse(post.title, "$mainUrl/content/${post.id}", TvType.Movie) {
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
            app.get("$mainApiUrl/api/posts?searchTerm=$query&order=desc", verify = false, cacheTime = 60)
        } catch (_: Exception) {
            app.get("$apiUrl/api/posts?searchTerm=$query&order=desc", verify = false, cacheTime = 60)
        }
        return AppUtils.parseJson<PageData>(json.text).posts.mapNotNull { toSearchResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val json = try {
            app.get(url.replace("$mainUrl/content/", "$mainApiUrl/api/posts/"), verify = false, cacheTime = 60)
        } catch (_: Exception) {
            app.get(url.replace("$mainUrl/content/", "$apiUrl/api/posts/"), verify = false, cacheTime = 60)
        }
        val urlCheck = json.url.contains(mainApiUrl)
        val loadData = AppUtils.parseJson<Data>(json.text)
        val title = loadData.name?.ifBlank { loadData.title } ?: loadData.title
        val fallbackPoster = "$apiUrl/uploads/${loadData.image}"
        val year = selectUntilNonInt(loadData.year)
        val isAnime = isAnimeContent(loadData.categories, title)

        if (loadData.type == "singleVideo") {
            val movieUrl = json.parsed<Movies>().content
            val link = if (urlCheck) movieUrl else linkToIp(movieUrl)
            val duration = getDurationFromString(loadData.watchTime)

            return if (isAnime) {
                val meta = getAniListMeta(title)
                val aniZip = if (meta?.id != null) getAniZipMeta(meta.id) else null
                val tmdbId = aniZip?.themoviedbId?.toIntOrNull()

                // Get logo - try TMDB ID first, then fallback to search
                var logoUrl: String? = null
                if (tmdbId != null) {
                    logoUrl = fetchTmdbLogo(tmdbId, isSeries = false)
                }
                if (logoUrl == null) {
                    logoUrl = fetchTmdbLogoForAnime(title, isSeries = false)
                }

                // Get MAL ID - from AniList first, then from ani.zip, then fallback to search
                val malId = meta?.idMal ?: aniZip?.malId ?: searchMalId(title)
                val kitsuId = aniZip?.kitsuid ?: searchKitsu(title)
                val simklId = aniZip?.simklId ?: searchSimkl(title)

                newAnimeLoadResponse(title, url, TvType.AnimeMovie) {
                    this.posterUrl = meta?.coverImage?.extraLarge ?: meta?.coverImage?.large ?: fallbackPoster
                    this.backgroundPosterUrl = meta?.bannerImage ?: fallbackPoster
                    this.year = year
                    this.plot = meta?.description?.replace(Regex("<[^>]*>"), "") ?: loadData.metaData
                    this.tags = meta?.genres
                    this.score = Score.from100(meta?.averageScore)
                    this.duration = duration
                    this.logoUrl = logoUrl
                    addAniListId(meta?.id)
                    addMalId(malId)
                    kitsuId?.let { addKitsuId(it) }
                    simklId?.let { addSimklId(it) }
                    try { addImdbId(null) } catch (_: Throwable) {}
                    addEpisodes(DubStatus.None, listOf(newEpisode(link ?: "")))
                }
            } else {
                val meta = getTmdbMeta(title, year, isSeries = false)

                newMovieLoadResponse(title, url, TvType.Movie, link) {
                    this.posterUrl = meta?.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta?.backdrop ?: fallbackPoster
                    this.year = year
                    this.plot = meta?.overview ?: loadData.metaData
                    this.score = Score.from10(meta?.rating)
                    this.duration = duration
                    this.logoUrl = meta?.logoUrl
                    // Non-anime: add IMDB sync, try to get Trakt ID
                    val traktId = searchTrakt(title, isSeries = false)
                    try { addAniListId(null) } catch (_: Throwable) {}
                    try { addMalId(null) } catch (_: Throwable) {}
                    try { addKitsuId(null as String?) } catch (_: Throwable) {}
                    try { addSimklId(null) } catch (_: Throwable) {}
                    try { addImdbId(meta?.imdbId) } catch (_: Throwable) {}
                    try { traktId?.let { addTraktId(it) } } catch (_: Throwable) {}
                }
            }
        } else {
            val tvData = json.parsed<TvSeries>()
            val episodesData = mutableListOf<Episode>()
            var seasonNum = 0
            tvData.content.forEach { season ->
                seasonNum++
                var episodeNum = 0
                season.episodes.forEach {
                    episodeNum++
                    val link = if (urlCheck) it.link else linkToIp(it.link)
                    episodesData.add(newEpisode(link) {
                        this.episode = episodeNum
                        this.season = seasonNum
                    })
                }
            }

            return if (isAnime) {
                val meta = getAniListMeta(title)
                val aniZip = if (meta?.id != null) getAniZipMeta(meta.id) else null
                val tmdbId = aniZip?.themoviedbId?.toIntOrNull()

                // Get logo - try TMDB ID first, then fallback to search
                var logoUrl: String? = null
                if (tmdbId != null) {
                    logoUrl = fetchTmdbLogo(tmdbId, isSeries = true)
                }
                if (logoUrl == null) {
                    logoUrl = fetchTmdbLogoForAnime(title, isSeries = true)
                }

                // Get MAL ID - from AniList first, then from ani.zip, then fallback to search
                val malId = meta?.idMal ?: aniZip?.malId ?: searchMalId(title)
                val kitsuId = aniZip?.kitsuid ?: searchKitsu(title)
                val simklId = aniZip?.simklId ?: searchSimkl(title)

                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = meta?.coverImage?.extraLarge ?: meta?.coverImage?.large ?: fallbackPoster
                    this.backgroundPosterUrl = meta?.bannerImage ?: fallbackPoster
                    this.year = year
                    this.plot = meta?.description?.replace(Regex("<[^>]*>"), "") ?: loadData.metaData
                    this.tags = meta?.genres
                    this.score = Score.from100(meta?.averageScore)
                    this.logoUrl = logoUrl
                    addAniListId(meta?.id)
                    addMalId(malId)
                    kitsuId?.let { addKitsuId(it) }
                    simklId?.let { addSimklId(it) }
                    try { addImdbId(null) } catch (_: Throwable) {}
                    addEpisodes(DubStatus.Subbed, episodesData)
                }
            } else {
                val meta = getTmdbMeta(title, year, isSeries = true)

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                    this.posterUrl = meta?.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta?.backdrop ?: fallbackPoster
                    this.year = year
                    this.plot = meta?.overview ?: loadData.metaData
                    this.score = Score.from10(meta?.rating)
                    this.logoUrl = meta?.logoUrl
                    // Non-anime: add IMDB sync, try to get Trakt ID
                    val traktId = searchTrakt(title, isSeries = true)
                    try { addAniListId(null) } catch (_: Throwable) {}
                    try { addMalId(null) } catch (_: Throwable) {}
                    try { addKitsuId(null as String?) } catch (_: Throwable) {}
                    try { addSimklId(null) } catch (_: Throwable) {}
                    try { addImdbId(meta?.imdbId) } catch (_: Throwable) {}
                    try { traktId?.let { addTraktId(it) } } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun linkToIp(data: String?): String {
        if (data != null) {
            return when {
                data.contains("index.circleftp.net") -> data.replace("index.circleftp.net", "15.1.4.2")
                data.contains("index2.circleftp.net") -> data.replace("index2.circleftp.net", "15.1.4.5")
                data.contains("index1.circleftp.net") -> data.replace("index1.circleftp.net", "15.1.4.9")
                data.contains("ftp3.circleftp.net") -> data.replace("ftp3.circleftp.net", "15.1.4.7")
                data.contains("ftp4.circleftp.net") -> data.replace("ftp4.circleftp.net", "15.1.1.5")
                data.contains("ftp5.circleftp.net") -> data.replace("ftp5.circleftp.net", "15.1.1.15")
                data.contains("ftp6.circleftp.net") -> data.replace("ftp6.circleftp.net", "15.1.2.3")
                data.contains("ftp7.circleftp.net") -> data.replace("ftp7.circleftp.net", "15.1.4.8")
                data.contains("ftp8.circleftp.net") -> data.replace("ftp8.circleftp.net", "15.1.2.2")
                data.contains("ftp9.circleftp.net") -> data.replace("ftp9.circleftp.net", "15.1.2.12")
                data.contains("ftp10.circleftp.net") -> data.replace("ftp10.circleftp.net", "15.1.4.3")
                data.contains("ftp11.circleftp.net") -> data.replace("ftp11.circleftp.net", "15.1.2.6")
                data.contains("ftp12.circleftp.net") -> data.replace("ftp12.circleftp.net", "15.1.2.1")
                data.contains("ftp13.circleftp.net") -> data.replace("ftp13.circleftp.net", "15.1.1.18")
                data.contains("ftp15.circleftp.net") -> data.replace("ftp15.circleftp.net", "15.1.4.12")
                data.contains("ftp17.circleftp.net") -> data.replace("ftp17.circleftp.net", "15.1.3.8")
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
        callback.invoke(newExtractorLink(source = this.name, name = this.name, url = data))
        return true
    }

    private fun selectUntilNonInt(string: String?): Int? {
        return string?.let { Regex("""^.*?(?=\D|$)""").find(it)?.value?.toIntOrNull() }
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

    // ── Data classes ──────────────────────────────────────────────────────────

    data class PageData(val posts: List<Post>)
    data class Post(val id: Int, val type: String, val imageSm: String, val title: String, val name: String?)
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

    // AniList
    data class AniListResponse(val data: AniListData?)
    data class AniListData(val Media: AniListMeta?)
    data class AniListMeta(
        val id: Int?, val idMal: Int?,
        val coverImage: AniListCoverImage?, val bannerImage: String?,
        val averageScore: Int?, val genres: List<String>?,
        val description: String?, val title: AniListTitle?
    )
    data class AniListCoverImage(val extraLarge: String?, val large: String?)
    data class AniListTitle(val romaji: String?, val english: String?)

    // ani.zip
    data class AniZipMeta(
        val themoviedbId: String?,
        val kitsuid: String?,
        val malId: Int?,
        val simklId: Int?
    )

    // TMDB
    data class TmdbSearchResponse(val results: List<TmdbResult>?)
    data class TmdbResult(
        val id: Int?, val posterPath: String?, val backdropPath: String?,
        val voteAverage: Double?, val overview: String?
    )
    data class TmdbMeta(
        val poster: String?, val backdrop: String?,
        val rating: Double?, val overview: String?,
        val logoUrl: String?, val imdbId: String?, val tmdbId: Int?
    )
}