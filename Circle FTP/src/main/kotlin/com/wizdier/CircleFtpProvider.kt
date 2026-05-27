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


    private fun isDubTitle(title: String): Boolean {
        val t = title.lowercase()
        return listOf("dubbed", "dual audio", "multi audio", "eng+jap", "english+japanese").any { t.contains(it) }
    }

    private fun makeContentUrl(postId: Int, seasonIndex: Int? = null): String {
        return if (seasonIndex == null) {
            "$mainUrl/content/$postId"
        } else {
            "$mainUrl/content/$postId?season=$seasonIndex"
        }
    }

    private fun selectedSeasonIndex(url: String): Int? {
        return Regex("""[?&]season=(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun cleanApiUrl(url: String): String {
        return url.substringBefore("?")
    }

    private fun titleForSeason(baseTitle: String, seasonName: String?, seasonNumber: Int): String {
        val base = cleanTitle(baseTitle)
            .replace(Regex("""(?i)\banime\b"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        val season = seasonName?.trim().orEmpty()
        val genericSeason = season.isBlank() || season.equals("Season $seasonNumber", true) || season.matches(Regex("""(?i)season\s*\d+"""))

        return when {
            seasonNumber <= 1 -> base
            !genericSeason && !base.contains(season, ignoreCase = true) -> "$base: $season"
            else -> "$base Season $seasonNumber"
        }
    }

    private suspend fun fetchPostResponse(postId: Int) = try {
        app.get("$mainApiUrl/api/posts/$postId", verify = false, cacheTime = 60)
    } catch (_: Exception) {
        app.get("$apiUrl/api/posts/$postId", verify = false, cacheTime = 60)
    }

    private suspend fun fetchPostTvData(postId: Int): TvSeries? = try {
        fetchPostResponse(postId).parsed<TvSeries>()
    } catch (_: Exception) {
        null
    }

    private suspend fun getKitsuMeta(title: String): KitsuMeta? = try {
        val res = app.get(
            "https://kitsu.io/api/edge/anime?filter[text]=${URLEncoder.encode(title, "UTF-8")}",
            headers = mapOf("Accept" to "application/vnd.api+json"),
            cacheTime = 3600
        )
        val first = JSONObject(res.text).optJSONArray("data")?.optJSONObject(0) ?: return null
        val id = first.optString("id").takeIf { it.isNotBlank() }
        val attr = first.optJSONObject("attributes") ?: return KitsuMeta(id, null, null, null, null, null)
        val titles = attr.optJSONObject("titles")
        val resolvedTitle = titles?.optString("en")?.takeIf { it.isNotBlank() }
            ?: titles?.optString("en_jp")?.takeIf { it.isNotBlank() }
            ?: attr.optString("canonicalTitle").takeIf { it.isNotBlank() }
        val poster = attr.optJSONObject("posterImage")?.let { img ->
            img.optString("original").takeIf { it.isNotBlank() }
                ?: img.optString("large").takeIf { it.isNotBlank() }
        }
        val cover = attr.optJSONObject("coverImage")?.let { img ->
            img.optString("original").takeIf { it.isNotBlank() }
                ?: img.optString("large").takeIf { it.isNotBlank() }
        }
        KitsuMeta(
            id = id,
            title = resolvedTitle,
            poster = poster,
            cover = cover,
            synopsis = attr.optString("synopsis").takeIf { it.isNotBlank() },
            averageRating = attr.optString("averageRating").toDoubleOrNull()
        )
    } catch (_: Exception) {
        null
    }

    private suspend fun getAniZipByKitsuId(kitsuId: String): AniZipFull? = try {
        val json = JSONObject(app.get("https://api.ani.zip/mappings?kitsu_id=$kitsuId", cacheTime = 86400).text)
        val m = json.optJSONObject("mappings")
        AniZipFull(
            anilistId = json.optInt("anilist_id").takeIf { it != 0 },
            malId = m?.optInt("mal_id")?.takeIf { it != 0 },
            kitsuId = kitsuId,
            simklId = m?.optInt("simkl_id")?.takeIf { it != 0 },
            tmdbId = m?.optString("themoviedb_id")?.takeIf { it.isNotBlank() }
        )
    } catch (_: Exception) {
        null
    }

    private suspend fun getAniZipByMalId(malId: Int): AniZipFull? = try {
        val json = JSONObject(app.get("https://api.ani.zip/mappings?mal_id=$malId", cacheTime = 86400).text)
        val m = json.optJSONObject("mappings")
        AniZipFull(
            anilistId = json.optInt("anilist_id").takeIf { it != 0 },
            malId = malId,
            kitsuId = m?.optString("kitsu_id")?.takeIf { it.isNotBlank() },
            simklId = m?.optInt("simkl_id")?.takeIf { it != 0 },
            tmdbId = m?.optString("themoviedb_id")?.takeIf { it.isNotBlank() }
        )
    } catch (_: Exception) {
        null
    }

    private suspend fun resolveAnimeMeta(title: String, year: Int? = null, isSeries: Boolean = true): ResolvedAnimeMeta {
        val cleaned = cleanTitle(title)
            .replace(Regex("""(?i)\banime\b"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        val tmdbMeta = getTmdbMeta(cleaned, year, isSeries)
        val tmdbZip = tmdbMeta?.tmdbId?.let { getAniZipByTmdbId(it) }
        var aniList = tmdbZip?.anilistId?.let { getAniListMetaById(it) }
            ?: getAniListMeta(cleaned)
            ?: getAniListMeta(title)

        var aniZip = aniList?.id?.let { getAniZipMeta(it) }
        var kitsu = getKitsuMeta(cleaned) ?: getKitsuMeta(title)
        var zipFromKitsu = kitsu?.id?.let { getAniZipByKitsuId(it) }

        if (aniList == null) {
            aniList = zipFromKitsu?.anilistId?.let { getAniListMetaById(it) }
        }

        val mal = aniList?.idMal ?: aniZip?.malId ?: tmdbZip?.malId ?: zipFromKitsu?.malId ?: searchMalId(cleaned)
        val zipFromMal = if ((aniList == null || aniZip == null) && mal != null) getAniZipByMalId(mal) else null

        if (aniList == null) {
            aniList = zipFromMal?.anilistId?.let { getAniListMetaById(it) }
        }
        if (aniZip == null && aniList?.id != null) {
            aniZip = getAniZipMeta(aniList.id)
        }

        val tmdbFromZip = aniZip?.themoviedbId?.toIntOrNull()
            ?: tmdbZip?.tmdbId?.toIntOrNull()
            ?: zipFromKitsu?.tmdbId?.toIntOrNull()
            ?: zipFromMal?.tmdbId?.toIntOrNull()

        val finalTmdb = tmdbMeta ?: tmdbFromZip?.let { id ->
            TmdbMeta(
                poster = null,
                backdrop = null,
                rating = null,
                overview = null,
                logoUrl = fetchTmdbLogo(id, isSeries),
                imdbId = null,
                tmdbId = id
            )
        }

        val displayTitle = aniList?.title?.english
            ?: aniList?.title?.romaji
            ?: kitsu?.title
            ?: cleaned

        return ResolvedAnimeMeta(
            title = displayTitle,
            poster = aniList?.coverImage?.extraLarge
                ?: aniList?.coverImage?.large
                ?: kitsu?.poster
                ?: finalTmdb?.poster,
            background = aniList?.bannerImage
                ?: kitsu?.cover
                ?: finalTmdb?.backdrop,
            plot = aniList?.description?.replace(Regex("<[^>]*>"), "")
                ?: kitsu?.synopsis
                ?: finalTmdb?.overview,
            score100 = aniList?.averageScore ?: kitsu?.averageRating?.toInt(),
            tags = aniList?.genres,
            trailer = fetchTmdbTrailer(finalTmdb?.tmdbId, isSeries) ?: getAniListTrailerUrl(aniList?.trailer),
            anilistEpisodes = aniList?.streamingEpisodes,
            logoUrl = finalTmdb?.logoUrl ?: fetchTmdbLogo(tmdbFromZip, isSeries),
            actors = aniList?.characters?.edges?.mapNotNull {
                it.node?.name?.full?.let { actorName -> ActorData(Actor(actorName, it.node.image?.large)) }
            },
            anilistId = aniList?.id ?: tmdbZip?.anilistId ?: zipFromKitsu?.anilistId ?: zipFromMal?.anilistId,
            malId = aniList?.idMal ?: aniZip?.malId ?: tmdbZip?.malId ?: zipFromKitsu?.malId ?: zipFromMal?.malId ?: mal,
            kitsuId = aniZip?.kitsuid ?: tmdbZip?.kitsuId ?: zipFromKitsu?.kitsuId ?: zipFromMal?.kitsuId ?: kitsu?.id,
            simklId = aniZip?.simklId ?: tmdbZip?.simklId ?: zipFromKitsu?.simklId ?: zipFromMal?.simklId,
            imdbId = finalTmdb?.imdbId
        )
    }

    private suspend fun createSearchResult(
        post: Post,
        overrideTitle: String? = null,
        seasonIndex: Int? = null,
        posterOverride: String? = null
    ): SearchResponse? {
        val rawTitle = overrideTitle ?: post.name?.ifBlank { post.title } ?: post.title
        val url = makeContentUrl(post.id, seasonIndex)
        val quality = getSearchQuality((post.quality ?: post.title).lowercase())
        val isSeries = post.type == "series"
        val isAnime = isAnimeContent(post.categories, post.title) || post.title.contains("anime", true)
        val fallbackPoster = "$mainApiUrl/uploads/${post.imageSm}"

        return if (isAnime) {
            val poster = posterOverride ?: resolveAnimeMeta(rawTitle, selectUntilNonInt(post.year), isSeries).poster ?: fallbackPoster
            newAnimeSearchResponse(rawTitle, url, if (isSeries) TvType.Anime else TvType.AnimeMovie) {
                this.posterUrl = poster
                this.quality = quality
                addDubStatus(dubExist = isDubTitle(post.title), subExist = true)
            }
        } else if (isSeries) {
            val meta = getTmdbMeta(cleanTitle(rawTitle), selectUntilNonInt(post.year), true)
            newTvSeriesSearchResponse(rawTitle, url, TvType.TvSeries) {
                this.posterUrl = meta?.poster ?: fallbackPoster
                this.quality = quality
            }
        } else {
            val meta = getTmdbMeta(cleanTitle(rawTitle), selectUntilNonInt(post.year), false)
            newMovieSearchResponse(rawTitle, url, TvType.Movie) {
                this.posterUrl = meta?.poster ?: fallbackPoster
                this.quality = quality
            }
        }
    }

    private suspend fun toSearchResults(post: Post): List<SearchResponse> {
        val isAnime = isAnimeContent(post.categories, post.title) || post.title.contains("anime", true)
        if (post.type == "series" && isAnime) {
            val tvData = fetchPostTvData(post.id)
            val seasons = tvData?.content.orEmpty()
            if (seasons.size > 1) {
                val baseTitle = post.name?.ifBlank { post.title } ?: post.title
                return seasons.mapIndexedNotNull { index, season ->
                    val seasonNumber = index + 1
                    val seasonTitle = titleForSeason(baseTitle, season.seasonName, seasonNumber)
                    val meta = resolveAnimeMeta(seasonTitle, selectUntilNonInt(post.year), true)
                    createSearchResult(
                        post = post,
                        overrideTitle = meta.title.takeIf { it.isNotBlank() } ?: seasonTitle,
                        seasonIndex = index,
                        posterOverride = meta.poster
                    )
                }
            }
        }

        return listOfNotNull(createSearchResult(post))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = try {
            app.get("$mainApiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10", verify = false, cacheTime = 60)
        } catch (_: Exception) {
            app.get("$apiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10", verify = false, cacheTime = 60)
        }
        val home = mutableListOf<SearchResponse>()
        AppUtils.parseJson<PageData>(json.text).posts.forEach { post ->
            home.addAll(toSearchResults(post))
        }
        return newHomePageResponse(request.name, home, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = try {
            app.get("$mainApiUrl/api/posts?searchTerm=$query&order=desc", verify = false, cacheTime = 60)
        } catch (_: Exception) {
            app.get("$apiUrl/api/posts?searchTerm=$query&order=desc", verify = false, cacheTime = 60)
        }
        val results = mutableListOf<SearchResponse>()
        AppUtils.parseJson<PageData>(json.text).posts.forEach { post ->
            results.addAll(toSearchResults(post))
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val json = try {
            app.get(cleanApiUrl(url).replace("$mainUrl/content/", "$mainApiUrl/api/posts/"), verify = false, cacheTime = 60)
        } catch (_: Exception) {
            app.get(cleanApiUrl(url).replace("$mainUrl/content/", "$apiUrl/api/posts/"), verify = false, cacheTime = 60)
        }
        val urlCheck = json.url.contains(mainApiUrl)
        val loadData = AppUtils.parseJson<Data>(json.text)
        val title = loadData.name?.ifBlank { loadData.title } ?: loadData.title
        val cleanedTitle = cleanTitle(title)
        val fallbackPoster = "$apiUrl/uploads/${loadData.image}"
        val year = selectUntilNonInt(loadData.year)
        val isAnime = isAnimeContent(loadData.categories, title)
        val selectedSeason = selectedSeasonIndex(url)

        if (loadData.type == "singleVideo") {
            val movieUrl = json.parsed<Movies>().content
            val link = if (urlCheck) movieUrl else linkToIp(movieUrl)
            val duration = getDurationFromString(loadData.watchTime)

            return if (isAnime) {
                val meta = resolveAnimeMeta(cleanedTitle, year, false)

                newAnimeLoadResponse(meta.title.ifBlank { title }, url, TvType.AnimeMovie) {
                    this.posterUrl = meta.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta.background ?: fallbackPoster
                    this.year = year
                    this.plot = meta.plot ?: loadData.metaData
                    this.tags = meta.tags
                    this.score = Score.from100(meta.score100)
                    this.duration = duration
                    this.actors = meta.actors
                    this.logoUrl = meta.logoUrl
                    meta.trailer?.let { addTrailer(it) }
                    addAniListId(meta.anilistId)
                    addMalId(meta.malId)
                    meta.kitsuId?.let { addKitsuId(it) }
                    meta.simklId?.let { addSimklId(it) }
                    meta.imdbId?.let { addImdbId(it) }
                    addEpisodes(if (isDubTitle(title)) DubStatus.Dubbed else DubStatus.Subbed, listOf(newEpisode(link ?: "")))
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
                val allSeasons = tvData.content
                val seasonsToUse = selectedSeason?.let { idx -> allSeasons.getOrNull(idx)?.let { listOf(idx to it) } }
                    ?: allSeasons.mapIndexed { idx, season -> idx to season }
                val metaTitle = selectedSeason?.let { idx ->
                    titleForSeason(title, allSeasons.getOrNull(idx)?.seasonName, idx + 1)
                } ?: cleanedTitle
                val meta = resolveAnimeMeta(metaTitle, year, true)

                val episodesData = mutableListOf<Episode>()
                seasonsToUse.forEach { (seasonIndex, season) ->
                    val realSeasonNumber = seasonIndex + 1
                    val episodeSeasonNumber = if (selectedSeason != null) 1 else realSeasonNumber
                    season.episodes.forEachIndexed { idx, ep ->
                        val link = if (urlCheck) ep.link else linkToIp(ep.link)
                        val aniEp = meta.anilistEpisodes?.getOrNull(idx)
                        episodesData.add(newEpisode(link) {
                            this.episode = idx + 1
                            this.season = episodeSeasonNumber
                            this.name = aniEp?.title ?: ep.title
                            this.posterUrl = aniEp?.thumbnail
                        })
                    }
                }

                newAnimeLoadResponse(meta.title.ifBlank { metaTitle }, url, TvType.Anime) {
                    this.posterUrl = meta.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta.background ?: fallbackPoster
                    this.year = year
                    this.plot = meta.plot ?: loadData.metaData
                    this.tags = meta.tags
                    this.score = Score.from100(meta.score100)
                    this.actors = meta.actors
                    this.logoUrl = meta.logoUrl
                    meta.trailer?.let { addTrailer(it) }
                    addAniListId(meta.anilistId)
                    addMalId(meta.malId)
                    meta.kitsuId?.let { addKitsuId(it) }
                    meta.simklId?.let { addSimklId(it) }
                    meta.imdbId?.let { addImdbId(it) }
                    addEpisodes(if (isDubTitle(title)) DubStatus.Dubbed else DubStatus.Subbed, episodesData)
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

    data class KitsuMeta(
        val id: String?,
        val title: String?,
        val poster: String?,
        val cover: String?,
        val synopsis: String?,
        val averageRating: Double?
    )

    data class ResolvedAnimeMeta(
        val title: String,
        val poster: String?,
        val background: String?,
        val plot: String?,
        val score100: Int?,
        val tags: List<String>?,
        val trailer: String?,
        val anilistEpisodes: List<AniListStreamingEpisode>?,
        val logoUrl: String?,
        val actors: List<ActorData>?,
        val anilistId: Int?,
        val malId: Int?,
        val kitsuId: String?,
        val simklId: Int?,
        val imdbId: String?
    )

    data class Post(
        val id: Int,
        val type: String,
        val imageSm: String,
        val title: String,
        val name: String? = null,
        val image: String? = null,
        val cover: String? = null,
        val quality: String? = null,
        val year: String? = null,
        val tags: String? = null,
        val categories: List<Category>? = null
    )
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