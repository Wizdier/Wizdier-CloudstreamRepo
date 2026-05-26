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

    private val animeCategories = setOf(21)
    private val possiblyAnimeCategories = setOf(1)

    private val anilistApi = "https://graphql.anilist.co"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = "0b2d522346f5ecbafa42ae4b0141c774"
    private val tmdbImageBase = "https://image.tmdb.org/t/p/w500"
    private val tmdbBackdropBase = "https://image.tmdb.org/t/p/original"

    private val animeKeywords = listOf(
        "anime", "naruto", "dragon ball", "one piece", "attack on titan",
        "demon slayer", "my hero academia", "boruto", "bleach", "fairy tail",
        "pokemon", "digimon", "sailor moon", "tokyo ghoul", "death note",
        "cowboy bebop", "fullmetal alchemist", "hunter x hunter", "jojo",
        "violet evergarden", "aot", "snk", "jujutsu kaisen", "chainsaw man"
    )

    private val nonAnimeKeywords = listOf(
        "cartoon", "animation", "animated", "pixar", "disney", "dreamworks"
    )

    // ── Clean title for better matching ─────────────────────────────────────
    private fun cleanTitle(title: String): String {
        var t = title
        t = t.replace(Regex("""(?i)S\d{1,2}E\d{1,2}"""), "")
        t = t.replace(Regex("""(?i)season\s*\d+|episode\s*\d+"""), "")
        t = t.replace(Regex("""(?i)\b(hindi|english|tamil|telugu|malayalam|kannada|bengali|marathi|dual audio|multi audio|dubbed|subbed|uncut|extended director'?s? cut)\b"""), "")
        t = t.replace(Regex("""(?i)\b(web[- ]?dl|webrip|bluray|hdrip|brrip|dvdrip|hdtv|hdcam|hdts|camrip|hdtc|hq|hd|uhd)\b"""), "")
        t = t.replace(Regex("""(?i)\b(1080p|720p|480p|2160p|4k|hevc|x264|x265|10bit|hdr|dv)\b"""), "")
        t = t.replace(Regex("""[\[\(].*?[\]\)]"""), "")
        t = t.replace(Regex("""[:\-–—]"""), " ")
        t = t.replace(Regex("""\s{2,}"""), " ")
        return t.trim()
    }

    // ── AniList ──────────────────────────────────────────────────────────────
    private suspend fun getAniListMeta(title: String, retryCount: Int = 2): AniListMeta? {
        repeat(retryCount) { attempt ->
            try {
                val query = """
                    query (${'$'}search: String) {
                      Media(search: ${'$'}search, type: ANIME) {
                        id idMal
                        coverImage { extraLarge large }
                        bannerImage averageScore genres
                        description(asHtml: false)
                        title { romaji english }
                        trailer { id site thumbnail }
                        streamingEpisodes { title thumbnail url site }
                        episodes
                        characters(sort:[ROLE,RELEVANCE], perPage:12) {
                          edges { role node { name{full} image{large} } }
                        }
                      }
                    }
                """.trimIndent()
                val body = mapOf("query" to query, "variables" to mapOf("search" to title))
                    .toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
                val res = app.post(anilistApi, requestBody = body, headers = mapOf("Content-Type" to "application/json"), cacheTime = 3600)
                return AppUtils.parseJson<AniListResponse>(res.text).data?.Media
            } catch (_: Exception) {
                if (attempt == retryCount - 1) return null
                kotlinx.coroutines.delay(150)
            }
        }
        return null
    }

    private suspend fun getAniListMetaById(id: Int): AniListMeta? {
        return try {
            val query = """
                query (${'$'}id: Int) {
                  Media(id: ${'$'}id, type: ANIME) {
                    id idMal
                    coverImage { extraLarge large }
                    bannerImage averageScore genres
                    description(asHtml: false)
                    title { romaji english }
                    trailer { id site thumbnail }
                    streamingEpisodes { title thumbnail url site }
                    episodes
                    characters(sort:[ROLE,RELEVANCE], perPage:12) {
                      edges { role node { name{full} image{large} } }
                    }
                  }
                }
            """.trimIndent()
            val body = mapOf("query" to query, "variables" to mapOf("id" to id))
                .toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            val res = app.post(anilistApi, requestBody = body, headers = mapOf("Content-Type" to "application/json"), cacheTime = 86400)
            AppUtils.parseJson<AniListResponse>(res.text).data?.Media
        } catch (_: Exception) { null }
    }

    private suspend fun searchMalId(title: String): Int? = try {
        val res = app.get("https://api.jikan.moe/v4/anime?q=${URLEncoder.encode(title, "UTF-8")}&limit=1", cacheTime = 3600)
        JSONObject(res.text).optJSONArray("data")?.optJSONObject(0)?.optInt("mal_id")
    } catch (_: Exception) { null }

    private fun isAnimeContent(categories: List<Category>?, title: String): Boolean {
        val t = title.lowercase()
        if (categories?.any { it.id in animeCategories } == true) return true
        if (categories?.any { it.id in possiblyAnimeCategories } == true) {
            if (nonAnimeKeywords.any { t.contains(it) }) return false
            return true
        }
        return animeKeywords.any { t.contains(it) }
    }

    // ── ani.zip ─────────────────────────────────────────────────────────────
    private suspend fun getAniZipMeta(anilistId: Int): AniZipMeta? = try {
        val json = JSONObject(app.get("https://api.ani.zip/mappings?anilist_id=$anilistId", cacheTime = 86400).text)
        val m = json.optJSONObject("mappings")
        AniZipMeta(m?.optString("themoviedb_id"), m?.optString("kitsu_id"), m?.optInt("mal_id"), m?.optInt("simkl_id"))
    } catch (_: Exception) { null }

    private suspend fun getAniZipByTmdbId(tmdbId: Int): AniZipFull? = try {
        val json = JSONObject(app.get("https://api.ani.zip/mappings?themoviedb_id=$tmdbId", cacheTime = 86400).text)
        val m = json.optJSONObject("mappings")
        AniZipFull(
            anilistId = json.optInt("anilist_id").takeIf { it != 0 },
            malId = m?.optInt("mal_id"),
            kitsuId = m?.optString("kitsu_id"),
            simklId = m?.optInt("simkl_id"),
            tmdbId = tmdbId.toString()
        )
    } catch (_: Exception) { null }

    private suspend fun searchKitsu(title: String): String? = try {
        val res = app.get("https://kitsu.io/api/edge/anime?filter[text]=${URLEncoder.encode(title, "UTF-8")}", headers = mapOf("Accept" to "application/vnd.api+json"), cacheTime = 3600)
        JSONObject(res.text).getJSONArray("data").optJSONObject(0)?.getString("id")
    } catch (_: Exception) { null }

    private suspend fun searchSimkl(title: String): Int? = try {
        val arr = org.json.JSONArray(app.get("https://api.simkl.com/search/anime?q=${URLEncoder.encode(title, "UTF-8")}&client_id=YOUR_CLIENT_ID", cacheTime = 3600).text)
        arr.optJSONObject(0)?.optJSONObject("show")?.optJSONObject("ids")?.optInt("simkl")
    } catch (_: Exception) { null }

    // ── TMDB ────────────────────────────────────────────────────────────────
    private suspend fun fetchTmdbLogo(tmdbId: Int?, isSeries: Boolean): String? {
        if (tmdbId == null) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(app.get("$tmdbApi/$type/$tmdbId/images?api_key=$tmdbKey", cacheTime = 86400).text)
            val logos = json.optJSONArray("logos") ?: return null
            for (i in 0 until logos.length()) {
                val l = logos.getJSONObject(i)
                val path = l.optString("file_path")
                if (path.isNotBlank() && (l.optString("iso_639_1") == "en" || l.optString("iso_639_1").isEmpty())) {
                    if (!path.endsWith(".svg")) return "$tmdbImageBase$path"
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private suspend fun getTmdbMeta(title: String, year: Int?, isSeries: Boolean): TmdbMeta? {
        return try {
            val type = if (isSeries) "tv" else "movie"
            val yearParam = year?.let { if (isSeries) "&first_air_date_year=$it" else "&year=$it" } ?: ""
            val url = "$tmdbApi/search/$type?api_key=$tmdbKey&query=${URLEncoder.encode(title, "UTF-8")}$yearParam&language=en-US"
            val first = AppUtils.parseJson<TmdbSearchResponse>(app.get(url, cacheTime = 86400).text).results?.firstOrNull() ?: return null
            val logo = fetchTmdbLogo(first.id, isSeries)
            var imdbId: String? = null
            try {
                val detail = JSONObject(app.get("$tmdbApi/$type/${first.id}?api_key=$tmdbKey&append_to_response=external_ids", cacheTime = 86400).text)
                imdbId = detail.optJSONObject("external_ids")?.optString("imdb_id")
            } catch (_: Exception) {}
            TmdbMeta(
                poster = first.posterPath?.let { "$tmdbImageBase$it" },
                backdrop = first.backdropPath?.let { "$tmdbBackdropBase$it" },
                rating = first.voteAverage,
                overview = first.overview,
                logoUrl = logo,
                imdbId = imdbId,
                tmdbId = first.id
            )
        } catch (_: Exception) { null }
    }

    private suspend fun fetchTmdbTrailer(tmdbId: Int?, isSeries: Boolean): String? {
        if (tmdbId == null) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(app.get("$tmdbApi/$type/$tmdbId/videos?api_key=$tmdbKey&language=en-US", cacheTime = 86400).text)
            val results = json.optJSONArray("results") ?: return null
            var key: String? = null
            // Prefer official trailer, then any trailer, then teaser
            for (priority in listOf("Trailer", "Teaser")) {
                for (i in 0 until results.length()) {
                    val v = results.getJSONObject(i)
                    if (v.optString("site") == "YouTube" && v.optString("type").equals(priority, true)) {
                        key = v.optString("key")
                        if (v.optBoolean("official")) break
                    }
                }
                if (key != null) break
            }
            key?.let { "https://www.youtube.com/watch?v=$it" }
        } catch (_: Exception) { null }
    }

    private suspend fun fetchTmdbSeasonEpisodes(tmdbId: Int, seasonNum: Int): List<TmdbEpisode> {
        return try {
            val json = JSONObject(app.get("$tmdbApi/tv/$tmdbId/season/$seasonNum?api_key=$tmdbKey&language=en-US", cacheTime = 86400).text)
            val arr = json.optJSONArray("episodes") ?: return emptyList()
            List(arr.length()) { i ->
                val ep = arr.getJSONObject(i)
                TmdbEpisode(ep.optString("name"), ep.optString("overview"), ep.optString("still_path").takeIf { it.isNotBlank() }, ep.optString("air_date"), ep.optDouble("vote_average"))
            }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun fetchTmdbActors(tmdbId: Int?, isSeries: Boolean): List<ActorData>? {
        if (tmdbId == null) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(app.get("$tmdbApi/$type/$tmdbId/credits?api_key=$tmdbKey", cacheTime = 86400).text)
            val cast = json.optJSONArray("cast") ?: return null
            (0 until minOf(cast.length(), 20)).mapNotNull { i ->
                val p = cast.getJSONObject(i)
                val name = p.optString("name")
                val img = p.optString("profile_path").takeIf { it.isNotBlank() }?.let { "$tmdbImageBase$it" }
                if (name.isNotBlank()) ActorData(Actor(name, img)) else null
            }
        } catch (_: Exception) { null }
    }

    private fun getAniListTrailerUrl(trailer: AniListTrailer?): String? {
        if (trailer?.id == null) return null
        return when (trailer.site?.lowercase()) {
            "youtube" -> "https://www.youtube.com/watch?v=${trailer.id}"
            "dailymotion" -> "https://www.dailymotion.com/video/${trailer.id}"
            else -> null
        }
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
        val url = "$mainUrl/content/${post.id}"
        val quality = getSearchQuality(post.title.lowercase())
        return if (post.type == "series") {
            newTvSeriesSearchResponse(post.title, url, TvType.TvSeries) {
                this.posterUrl = "$mainApiUrl/uploads/${post.imageSm}"
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(post.title, url, TvType.Movie) {
                this.posterUrl = "$mainApiUrl/uploads/${post.imageSm}"
                this.quality = quality
            }
        }
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
        val cleanedTitle = cleanTitle(title)
        val fallbackPoster = "$apiUrl/uploads/${loadData.image}"
        val year = selectUntilNonInt(loadData.year)
        val isAnime = isAnimeContent(loadData.categories, title)

        if (loadData.type == "singleVideo") {
            val movieUrl = json.parsed<Movies>().content
            val link = if (urlCheck) movieUrl else linkToIp(movieUrl)
            val duration = getDurationFromString(loadData.watchTime)

            return if (isAnime) {
                // ANIME MOVIE - Use TMDB -> ani.zip -> AniList for perfect sync
                val tmdbMeta = getTmdbMeta(cleanedTitle, year, false)
                val tmdbId = tmdbMeta?.tmdbId
                val aniZipFull = tmdbId?.let { getAniZipByTmdbId(it) }
                val meta = aniZipFull?.anilistId?.let { getAniListMetaById(it) } ?: getAniListMeta(cleanedTitle)
                val aniZip = if (meta?.id != null) getAniZipMeta(meta.id) else null

                val logoUrl = tmdbId?.let { fetchTmdbLogo(it, false) } ?: fetchTmdbLogo(aniZip?.themoviedbId?.toIntOrNull(), false)
                val trailer = fetchTmdbTrailer(tmdbId, false) ?: getAniListTrailerUrl(meta?.trailer)
                val actors = meta?.characters?.edges?.mapNotNull { it.node?.name?.full?.let { name -> ActorData(Actor(name, it.node.image?.large)) } }

                newAnimeLoadResponse(title, url, TvType.AnimeMovie) {
                    this.posterUrl = meta?.coverImage?.extraLarge ?: meta?.coverImage?.large ?: tmdbMeta?.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta?.bannerImage ?: tmdbMeta?.backdrop ?: fallbackPoster
                    this.year = year
                    this.plot = meta?.description?.replace(Regex("<[^>]*>"), "") ?: tmdbMeta?.overview ?: loadData.metaData
                    this.tags = meta?.genres
                    this.score = Score.from100(meta?.averageScore)
                    this.duration = duration
                    this.actors = actors
                    this.logoUrl = logoUrl
                    trailer?.let { addTrailer(it) }
                    addAniListId(meta?.id)
                    addMalId(meta?.idMal ?: aniZip?.malId ?: aniZipFull?.malId)
                    (aniZip?.kitsuid ?: aniZipFull?.kitsuId)?.let { addKitsuId(it) }
                    (aniZip?.simklId ?: aniZipFull?.simklId)?.let { addSimklId(it) }
                    tmdbMeta?.imdbId?.let { addImdbId(it) }
                    addEpisodes(DubStatus.None, listOf(newEpisode(link ?: "")))
                }
            } else {
                // MOVIE - TMDB only, no anime IDs
                val meta = getTmdbMeta(cleanedTitle, year, false)
                val trailer = fetchTmdbTrailer(meta?.tmdbId, false)
                val actors = fetchTmdbActors(meta?.tmdbId, false)

                newMovieLoadResponse(title, url, TvType.Movie, link) {
                    this.posterUrl = meta?.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta?.backdrop ?: fallbackPoster
                    this.year = year
                    this.plot = meta?.overview ?: loadData.metaData
                    this.score = Score.from10(meta?.rating)
                    this.duration = duration
                    this.actors = actors
                    this.logoUrl = meta?.logoUrl
                    trailer?.let { addTrailer(it) }
                    meta?.imdbId?.let { addImdbId(it) }
                }
            }
        } else {
            val tvData = json.parsed<TvSeries>()

            return if (isAnime) {
                // ANIME SERIES - Perfect sync via TMDB
                val tmdbMeta = getTmdbMeta(cleanedTitle, year, true)
                val tmdbId = tmdbMeta?.tmdbId
                val aniZipFull = tmdbId?.let { getAniZipByTmdbId(it) }
                val meta = aniZipFull?.anilistId?.let { getAniListMetaById(it) } ?: getAniListMeta(cleanedTitle)
                val aniZip = if (meta?.id != null) getAniZipMeta(meta.id) else null

                val logoUrl = tmdbId?.let { fetchTmdbLogo(it, true) }
                val trailer = fetchTmdbTrailer(tmdbId, true) ?: getAniListTrailerUrl(meta?.trailer)
                val actors = meta?.characters?.edges?.mapNotNull { it.node?.name?.full?.let { name -> ActorData(Actor(name, it.node.image?.large)) } }

                val episodesData = mutableListOf<Episode>()
                var seasonNum = 0
                tvData.content.forEach { season ->
                    seasonNum++
                    val tmdbEpisodes = tmdbId?.let { fetchTmdbSeasonEpisodes(it, seasonNum) } ?: emptyList()
                    season.episodes.forEachIndexed { idx, ep ->
                        val link = if (urlCheck) ep.link else linkToIp(ep.link)
                        val tmdbEp = tmdbEpisodes.getOrNull(idx)
                        val aniEp = meta?.streamingEpisodes?.getOrNull(idx)
                        episodesData.add(newEpisode(link) {
                            this.episode = idx + 1
                            this.season = seasonNum
                            this.name = tmdbEp?.name ?: aniEp?.title ?: ep.title
                            this.posterUrl = tmdbEp?.stillPath?.let { "$tmdbImageBase$it" } ?: aniEp?.thumbnail
                            this.description = tmdbEp?.overview
                            tmdbEp?.airDate?.let { addDate(it) }
                        })
                    }
                }

                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = meta?.coverImage?.extraLarge ?: meta?.coverImage?.large ?: tmdbMeta?.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta?.bannerImage ?: tmdbMeta?.backdrop ?: fallbackPoster
                    this.year = year
                    this.plot = meta?.description?.replace(Regex("<[^>]*>"), "") ?: tmdbMeta?.overview ?: loadData.metaData
                    this.tags = meta?.genres
                    this.score = Score.from100(meta?.averageScore)
                    this.actors = actors
                    this.logoUrl = logoUrl
                    trailer?.let { addTrailer(it) }
                    addAniListId(meta?.id)
                    addMalId(meta?.idMal ?: aniZip?.malId ?: aniZipFull?.malId)
                    (aniZip?.kitsuid ?: aniZipFull?.kitsuId)?.let { addKitsuId(it) }
                    (aniZip?.simklId ?: aniZipFull?.simklId)?.let { addSimklId(it) }
                    tmdbMeta?.imdbId?.let { addImdbId(it) }
                    addEpisodes(DubStatus.Subbed, episodesData)
                }
            } else {
                // TV SERIES - TMDB only, NO anime sync
                val meta = getTmdbMeta(cleanedTitle, year, true)
                val tmdbId = meta?.tmdbId
                val trailer = fetchTmdbTrailer(tmdbId, true)
                val actors = fetchTmdbActors(tmdbId, true)

                val episodesData = mutableListOf<Episode>()
                var seasonNum = 0
                tvData.content.forEach { season ->
                    seasonNum++
                    val tmdbEpisodes = tmdbId?.let { fetchTmdbSeasonEpisodes(it, seasonNum) } ?: emptyList()
                    season.episodes.forEachIndexed { idx, ep ->
                        val link = if (urlCheck) ep.link else linkToIp(ep.link)
                        val tmdbEp = tmdbEpisodes.getOrNull(idx)
                        episodesData.add(newEpisode(link) {
                            this.episode = idx + 1
                            this.season = seasonNum
                            this.name = tmdbEp?.name ?: ep.title
                            this.posterUrl = tmdbEp?.stillPath?.let { "$tmdbImageBase$it" }
                            this.description = tmdbEp?.overview
                            tmdbEp?.airDate?.let { addDate(it) }
                        })
                    }
                }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                    this.posterUrl = meta?.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta?.backdrop ?: fallbackPoster
                    this.year = year
                    this.plot = meta?.overview ?: loadData.metaData
                    this.score = Score.from10(meta?.rating)
                    this.actors = actors
                    this.logoUrl = meta?.logoUrl
                    trailer?.let { addTrailer(it) }
                    meta?.imdbId?.let { addImdbId(it) }
                }
            }
        }
    }

    private fun linkToIp(data: String?): String {
        if (data == null) return ""
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
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        callback.invoke(newExtractorLink(source = this.name, name = this.name, url = data))
        return true
    }

    private fun selectUntilNonInt(string: String?): Int? = string?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
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

    // ── Data classes ────────────────────────────────────────────────────────
    data class PageData(val posts: List<Post>)
    data class Post(val id: Int, val type: String, val imageSm: String, val title: String, val name: String?)
    data class Data(val type: String, val imageSm: String, val title: String, val image: String, val metaData: String?, val name: String?, val quality: String?, val year: String?, val watchTime: String?, val categories: List<Category>?)
    data class Category(val id: Int, val name: String?)
    data class TvSeries(val content: List<Content>)
    data class Content(val episodes: List<EpisodeData>, val seasonName: String)
    data class EpisodeData(val link: String, val title: String)
    data class Movies(val content: String?)
    data class AniListResponse(val data: AniListData?)
    data class AniListData(val Media: AniListMeta?)
    data class AniListMeta(val id: Int?, val idMal: Int?, val coverImage: AniListCoverImage?, val bannerImage: String?, val averageScore: Int?, val genres: List<String>?, val description: String?, val title: AniListTitle?, val trailer: AniListTrailer?, val streamingEpisodes: List<AniListStreamingEpisode>?, val episodes: Int?, val characters: AniListCharacterConnection?)
    data class AniListCoverImage(val extraLarge: String?, val large: String?)
    data class AniListTitle(val romaji: String?, val english: String?)
    data class AniListTrailer(val id: String?, val site: String?, val thumbnail: String?)
    data class AniListStreamingEpisode(val title: String?, val thumbnail: String?, val url: String?, val site: String?)
    data class AniListCharacterConnection(val edges: List<AniListCharacterEdge>?)
    data class AniListCharacterEdge(val node: AniListCharacterNode?, val role: String?)
    data class AniListCharacterNode(val name: AniListCharacterName?, val image: AniListCharacterImage?)
    data class AniListCharacterName(val full: String?)
    data class AniListCharacterImage(val large: String?)
    data class AniZipMeta(val themoviedbId: String?, val kitsuid: String?, val malId: Int?, val simklId: Int?)
    data class AniZipFull(val anilistId: Int?, val malId: Int?, val kitsuId: String?, val simklId: Int?, val tmdbId: String?)
    data class TmdbSearchResponse(val results: List<TmdbResult>?)
    data class TmdbResult(val id: Int?, val posterPath: String?, val backdropPath: String?, val voteAverage: Double?, val overview: String?)
    data class TmdbMeta(val poster: String?, val backdrop: String?, val rating: Double?, val overview: String?, val logoUrl: String?, val imdbId: String?, val tmdbId: Int?)
    data class TmdbEpisode(val name: String?, val overview: String?, val stillPath: String?, val airDate: String?, val rating: Double?)
}