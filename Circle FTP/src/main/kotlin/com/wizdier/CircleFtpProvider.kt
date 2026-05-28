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
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * CircleFtpProvider — Unified Home/Search + Chained Recommendations
 * Implementation: Dynamic AniZip ID mapping + Episode/Metadata/Audio refinement.
 */
class CircleFtpProvider : MainAPI() {

    override var mainUrl    = "http://new.circleftp.net"
    private var mainApiUrl  = "http://new.circleftp.net:5000"
    private val apiUrl      = "http://15.1.1.50:5000"
    override var name       = "Circle FTP"
    override var lang       = "bn"
    override val hasMainPage         = true
    override val hasDownloadSupport  = true
    override val hasQuickSearch      = false
    override val hasChromecastSupport = true

    override val supportedSyncNames = setOf(
        SyncIdName.Anilist, SyncIdName.MyAnimeList, SyncIdName.Kitsu,
        SyncIdName.Simkl, SyncIdName.Imdb, SyncIdName.Trakt
    )

    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie,
        TvType.Cartoon, TvType.AsianDrama, TvType.Documentary, TvType.OVA, TvType.Others
    )

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
        "1"   to "Animation Movies",
        "21"  to "Anime Series",
        "85"  to "Documentary",
        "15"  to "WWE"
    )

    // -------------------------------------------------------------------------
    // Constants & Caches
    // -------------------------------------------------------------------------
    private val animeCategories = setOf(21)
    private val possiblyAnimeCategories = setOf(1)
    private val anilistApi = "https://graphql.anilist.co"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = "0b2d522346f5ecbafa42ae4b0141c774"
    private val tmdbImageBase = "https://image.tmdb.org/t/p/w500"
    private val tmdbBackdropBase = "https://image.tmdb.org/t/p/original"

    private val animeKeywords = listOf("anime", "naruto", "dragon ball", "one piece", "attack on titan", "demon slayer", "my hero academia", "boruto", "bleach", "fairy tail", "pokemon", "digimon", "sailor moon", "tokyo ghoul", "death note", "cowboy bebop", "fullmetal alchemist", "hunter x hunter", "jojo", "violet evergarden", "aot", "snk", "jujutsu kaisen", "chainsaw man")
    private val nonAnimeKeywords = listOf("cartoon", "animation", "animated", "pixar", "disney", "dreamworks")

    private val tmdbMetaCache: MutableMap<String, TmdbMeta?> = Collections.synchronizedMap(mutableMapOf())
    private val animeMetaCache: MutableMap<String, ResolvedAnimeMeta> = Collections.synchronizedMap(mutableMapOf())
    private val kitsuMetaCache: MutableMap<String, KitsuMeta?> = Collections.synchronizedMap(mutableMapOf())
    private val postTvDataCache: MutableMap<Int, TvSeries?> = Collections.synchronizedMap(mutableMapOf())
    private val aniZipFullCache: MutableMap<Int, AniZipFull?> = Collections.synchronizedMap(mutableMapOf())

    private val ipMap = mapOf(
        "index.circleftp.net"  to "15.1.4.2",
        "index2.circleftp.net" to "15.1.4.5",
        "index1.circleftp.net" to "15.1.4.9",
        "ftp3.circleftp.net"   to "15.1.4.7",
        "ftp4.circleftp.net"   to "15.1.1.5",
        "ftp5.circleftp.net"   to "15.1.1.15",
        "ftp6.circleftp.net"   to "15.1.2.3",
        "ftp7.circleftp.net"   to "15.1.4.8",
        "ftp8.circleftp.net"   to "15.1.2.2",
        "ftp9.circleftp.net"   to "15.1.2.12",
        "ftp10.circleftp.net"  to "15.1.4.3",
        "ftp11.circleftp.net"  to "15.1.2.6",
        "ftp12.circleftp.net"  to "15.1.2.1",
        "ftp13.circleftp.net"  to "15.1.1.18",
        "ftp15.circleftp.net"  to "15.1.4.12",
        "ftp17.circleftp.net"  to "15.1.3.8"
    )

    // =========================================================================
    // SECTION 1 — Helpers
    // =========================================================================

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("""(?i)S\d{1,2}E\d{1,2}"""), "")
            .replace(Regex("""(?i)season\s*\d+|episode\s*\d+"""), "")
            .replace(Regex("""(?i)\b(hindi|english|tamil|telugu|malayalam|kannada|bengali|marathi|dual audio|multi audio|dubbed|subbed|uncut|extended director'?s? cut)\b"""), "")
            .replace(Regex("""(?i)\b(web[- ]?dl|webrip|bluray|hdrip|brrip|dvdrip|hdtv|hdcam|hdts|camrip|hdtc|hq|hd|uhd)\b"""), "")
            .replace(Regex("""(?i)\b(1080p|720p|480p|2160p|4k|hevc|x264|x265|10bit|hdr|dv)\b"""), "")
            .replace(Regex("""[\[\(].*?[\]\)]"""), "")
            .replace(Regex("""[:\-–—]"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    private fun stripSeasonSuffixForAnime(title: String): String =
        cleanTitle(title).replace(Regex("""(?i)\banime\b"""), "").replace(Regex("""(?i)\bseason\s*\d+\b"""), "").replace(Regex("""\s{2,}"""), " ").trim()

    private fun titleForSeason(baseTitle: String, seasonName: String?, seasonNumber: Int): String {
        val base = cleanTitle(baseTitle).replace(Regex("""(?i)\banime\b"""), "").replace(Regex("""\s{2,}"""), " ").trim()
        val season = seasonName?.trim().orEmpty()
        val genericSeason = season.isBlank() || season.equals("Season $seasonNumber", true) || season.matches(Regex("""(?i)season\s*\d+"""))
        return when {
            seasonNumber <= 1 -> base
            !genericSeason && !base.contains(season, ignoreCase = true) -> "$base: $season"
            else -> "$base Season $seasonNumber"
        }
    }

    private fun isDubTitle(title: String): Boolean = 
        listOf("dubbed", "dual audio", "multi audio", "eng+jap", "english+japanese").any { title.lowercase().contains(it) }

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

    private fun isAnimeContent(categories: List<Category>?, title: String): Boolean {
        val t = title.lowercase()
        if (categories?.any { it.id in animeCategories } == true) return true
        if (categories?.any { it.id in possiblyAnimeCategories } == true) {
            if (nonAnimeKeywords.any { t.contains(it) }) return false
            return true
        }
        return animeKeywords.any { t.contains(it) }
    }

    private fun makeContentUrl(postId: Int, seasonIndex: Int? = null): String =
        if (seasonIndex == null) "$mainUrl/content/$postId" else "$mainUrl/content/$postId?season=$seasonIndex"

    private fun selectedSeasonIndex(url: String): Int? = Regex("""[?&]season=(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun cleanApiUrl(url: String): String = url.substringBefore("?")

    private fun linkToIp(data: String?): String {
        if (data == null) return ""
        ipMap.forEach { (domain, ip) ->
            if (data.contains(domain)) return data.replace(domain, ip)
        }
        return data
    }

    // =========================================================================
    // SECTION 2 — Network & API
    // =========================================================================

    private suspend fun getAniListMeta(title: String, retryCount: Int = 2): AniListMeta? {
        repeat(retryCount) { attempt ->
            try {
                val query = buildAniListQuery(byId = false)
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

    private suspend fun getAniListMetaById(id: Int): AniListMeta? = try {
        val query = buildAniListQuery(byId = true)
        val body = mapOf("query" to query, "variables" to mapOf("id" to id))
            .toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        val res = app.post(anilistApi, requestBody = body, headers = mapOf("Content-Type" to "application/json"), cacheTime = 86400)
        AppUtils.parseJson<AniListResponse>(res.text).data?.Media
    } catch (_: Exception) { null }

    private fun buildAniListQuery(byId: Boolean): String {
        val param = if (byId) "\$id: Int" else "\$search: String"
        val lookup = if (byId) "id: \$id" else "search: \$search"
        return """
            query ($param) {
              Media($lookup, type: ANIME) {
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
                relations {
                  edges {
                    relationType
                    node {
                      id idMal
                      coverImage { extraLarge large }
                      bannerImage averageScore genres
                      description(asHtml: false)
                      title { romaji english }
                      trailer { id site thumbnail }
                      streamingEpisodes { title thumbnail url site }
                      episodes
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }

    private fun getAniListTrailerUrl(trailer: AniListTrailer?): String? {
        if (trailer?.id == null) return null
        return when (trailer.site?.lowercase()) {
            "youtube" -> "https://www.youtube.com/watch?v=${trailer.id}"
            "dailymotion" -> "https://www.dailymotion.com/video/${trailer.id}"
            else -> null
        }
    }

    private suspend fun getAniZipFullCached(anilistId: Int): AniZipFull? {
        if (aniZipFullCache.containsKey(anilistId)) return aniZipFullCache[anilistId]
        val value = try {
            val json = JSONObject(app.get("https://api.ani.zip/mappings?anilist_id=$anilistId", cacheTime = 86400).text)
            val m = json.optJSONObject("mappings")
            AniZipFull(
                anilistId = anilistId,
                malId = m?.optInt("mal_id")?.takeIf { it != 0 },
                kitsuId = m?.optString("kitsu_id")?.takeIf { it.isNotBlank() },
                simklId = m?.optInt("simkl_id")?.takeIf { it != 0 },
                tmdbId = m?.optString("themoviedb_id")?.takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) { null }
        aniZipFullCache[anilistId] = value
        return value
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
    } catch (_: Exception) { null }

    private suspend fun getAniZipByTmdbId(tmdbId: Int): AniZipFull? = try {
        val json = JSONObject(app.get("https://api.ani.zip/mappings?themoviedb_id=$tmdbId", cacheTime = 86400).text)
        val m = json.optJSONObject("mappings")
        AniZipFull(
            anilistId = json.optInt("anilist_id").takeIf { it != 0 },
            malId = m?.optInt("mal_id")?.takeIf { it != 0 },
            kitsuId = m?.optString("kitsu_id")?.takeIf { it.isNotBlank() },
            simklId = m?.optInt("simkl_id")?.takeIf { it != 0 },
            tmdbId = tmdbId.toString()
        )
    } catch (_: Exception) { null }

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
    } catch (_: Exception) { null }

    private suspend fun getAniZipEpisodeTitles(anilistId: Int): Map<Int, String> = try {
        val json = JSONObject(app.get("https://api.ani.zip/mappings?anilist_id=$anilistId", cacheTime = 86400).text)
        val episodes = json.optJSONObject("episodes") ?: return emptyMap()
        val map = mutableMapOf<Int, String>()
        episodes.keys().forEach { key ->
            val epNum = key.toIntOrNull() ?: return@forEach
            val epObj = episodes.optJSONObject(key) ?: return@forEach
            val titles = epObj.optJSONObject("title")
            val title = titles?.optString("en")?.takeIf { it.isNotBlank() }
                ?: titles?.optString("x-jat")?.takeIf { it.isNotBlank() }
                ?: epObj.optString("title")?.takeIf { it.isNotBlank() }
            if (title != null) map[epNum] = title
        }
        map
    } catch (_: Exception) { emptyMap() }

    private suspend fun searchMalId(title: String): Int? = try {
        val res = app.get("https://api.jikan.moe/v4/anime?q=${URLEncoder.encode(title, "UTF-8")}&limit=1", cacheTime = 3600)
        JSONObject(res.text).optJSONArray("data")?.optJSONObject(0)?.optInt("mal_id")
    } catch (_: Exception) { null }

    private suspend fun getKitsuMeta(title: String): KitsuMeta? = try {
        val res = app.get("https://kitsu.io/api/edge/anime?filter[text]=${URLEncoder.encode(title, "UTF-8")}", headers = mapOf("Accept" to "application/vnd.api+json"), cacheTime = 3600)
        val first = JSONObject(res.text).optJSONArray("data")?.optJSONObject(0) ?: return null
        val id = first.optString("id").takeIf { it.isNotBlank() }
        val attr = first.optJSONObject("attributes") ?: return KitsuMeta(id, null, null, null, null, null)
        val titles = attr.optJSONObject("titles")
        val resolvedTitle = titles?.optString("en")?.takeIf { it.isNotBlank() } ?: titles?.optString("en_jp")?.takeIf { it.isNotBlank() } ?: attr.optString("canonicalTitle").takeIf { it.isNotBlank() }
        val poster = attr.optJSONObject("posterImage")?.let { it.optString("original").takeIf { s -> s.isNotBlank() } ?: it.optString("large") }
        val cover = attr.optJSONObject("coverImage")?.let { it.optString("original").takeIf { s -> s.isNotBlank() } ?: it.optString("large") }
        KitsuMeta(id, resolvedTitle, poster, cover, attr.optString("synopsis").takeIf { it.isNotBlank() }, attr.optString("averageRating").toDoubleOrNull())
    } catch (_: Exception) { null }

    private suspend fun getKitsuMetaCached(title: String): KitsuMeta? {
        val key = title.lowercase()
        return kitsuMetaCache.getOrPut(key) { getKitsuMeta(title) }
    }

    private suspend fun getTmdbMeta(title: String, year: Int?, isSeries: Boolean): TmdbMeta? = try {
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
        TmdbMeta(first.posterPath?.let { "$tmdbImageBase$it" }, first.backdropPath?.let { "$tmdbBackdropBase$it" }, first.voteAverage, first.overview, logo, imdbId, first.id)
    } catch (_: Exception) { null }

    private suspend fun getTmdbMetaCached(title: String, year: Int?, isSeries: Boolean): TmdbMeta? {
        val key = "${isSeries}|${year ?: 0}|${title.lowercase()}"
        return tmdbMetaCache.getOrPut(key) { getTmdbMeta(title, year, isSeries) }
    }

    private fun resolveBackdrop(tmdbMeta: TmdbMeta?, aniListMeta: AniListMeta?, fallback: String?): String? =
        tmdbMeta?.backdrop ?: aniListMeta?.bannerImage ?: aniListMeta?.coverImage?.extraLarge ?: tmdbMeta?.poster ?: fallback

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

    private suspend fun fetchTmdbTrailer(tmdbId: Int?, isSeries: Boolean): String? {
        if (tmdbId == null) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(app.get("$tmdbApi/$type/$tmdbId/videos?api_key=$tmdbKey&language=en-US", cacheTime = 86400).text)
            val results = json.optJSONArray("results") ?: return null
            var key: String? = null
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

    private suspend fun fetchTmdbSeasonEpisodes(tmdbId: Int, seasonNum: Int): List<TmdbEpisode> = try {
        val json = JSONObject(app.get("$tmdbApi/tv/$tmdbId/season/$seasonNum?api_key=$tmdbKey&language=en-US", cacheTime = 86400).text)
        val arr = json.optJSONArray("episodes") ?: return emptyList()
        List(arr.length()) { i ->
            val ep = arr.getJSONObject(i)
            TmdbEpisode(ep.optString("name"), ep.optString("overview"), ep.optString("still_path").takeIf { it.isNotBlank() }, ep.optString("air_date"), ep.optDouble("vote_average"))
        }
    } catch (_: Exception) { emptyList() }

    private suspend fun fetchTmdbActors(tmdbId: Int?, isSeries: Boolean): List<ActorData>? {
        if (tmdbId == null) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(app.get("$tmdbApi/$type/$tmdbId/credits?api_key=$tmdbKey", cacheTime = 86400).text)
            val cast = json.optJSONArray("cast") ?: return null
            (0 until minOf(cast.length(), 20)).mapNotNull { i ->
                val p = cast.getJSONObject(i)
                val n = p.optString("name")
                val img = p.optString("profile_path").takeIf { it.isNotBlank() }?.let { "$tmdbImageBase$it" }
                if (n.isNotBlank()) ActorData(Actor(n, img)) else null
            }
        } catch (_: Exception) { null }
    }

    private suspend fun fetchPostResponse(postId: Int) = try {
        app.get("$mainApiUrl/api/posts/$postId", verify = false, cacheTime = 60)
    } catch (_: Exception) {
        app.get("$apiUrl/api/posts/$postId", verify = false, cacheTime = 60)
    }

    private suspend fun fetchPostTvData(postId: Int): TvSeries? = try {
        fetchPostResponse(postId).parsed<TvSeries>()
    } catch (_: Exception) { null }

    private suspend fun fetchPostTvDataCached(postId: Int): TvSeries? = postTvDataCache.getOrPut(postId) { fetchPostTvData(postId) }

    // =========================================================================
    // SECTION 3 — Anime Meta Resolver
    // =========================================================================

    private suspend fun resolveAnimeMeta(title: String, year: Int? = null, isSeries: Boolean = true): ResolvedAnimeMeta {
        val cleaned = stripSeasonSuffixForAnime(title)
        val tmdbMeta = getTmdbMeta(cleaned, year, isSeries)
        val tmdbZip = tmdbMeta?.tmdbId?.let { getAniZipByTmdbId(it) }
        var aniList = tmdbZip?.anilistId?.let { getAniListMetaById(it) } ?: getAniListMeta(cleaned) ?: getAniListMeta(title)
        var aniZip = aniList?.id?.let { getAniZipFullCached(it) }
        val kitsu = getKitsuMetaCached(cleaned) ?: getKitsuMetaCached(title)
        val zipFromKitsu = kitsu?.id?.let { getAniZipByKitsuId(it) }
        if (aniList == null) aniList = zipFromKitsu?.anilistId?.let { getAniListMetaById(it) }
        val mal = aniList?.idMal ?: aniZip?.malId ?: tmdbZip?.malId ?: zipFromKitsu?.malId ?: searchMalId(cleaned)
        val zipFromMal = if ((aniList == null || aniZip == null) && mal != null) getAniZipByMalId(mal) else null
        if (aniList == null) aniList = zipFromMal?.anilistId?.let { getAniListMetaById(it) }
        if (aniZip == null && aniList?.id != null) aniZip = getAniZipFullCached(aniList.id!!)
        val tmdbFromZip = aniZip?.tmdbId?.toIntOrNull() ?: tmdbZip?.tmdbId?.toIntOrNull() ?: zipFromKitsu?.tmdbId?.toIntOrNull() ?: zipFromMal?.tmdbId?.toIntOrNull()
        val finalTmdb = tmdbMeta ?: tmdbFromZip?.let { id -> TmdbMeta(null, null, null, null, fetchTmdbLogo(id, isSeries), null, id) }
        val displayTitle = aniList?.title?.english ?: aniList?.title?.romaji ?: kitsu?.title ?: cleaned
        return ResolvedAnimeMeta(
            title = displayTitle,
            poster = aniList?.coverImage?.extraLarge ?: aniList?.coverImage?.large ?: kitsu?.poster ?: finalTmdb?.poster,
            background = resolveBackdrop(finalTmdb, aniList, null),
            plot = aniList?.description?.replace(Regex("<[^>]*>"), "") ?: kitsu?.synopsis ?: finalTmdb?.overview,
            score100 = aniList?.averageScore ?: kitsu?.averageRating?.toInt(),
            tags = aniList?.genres,
            trailer = fetchTmdbTrailer(finalTmdb?.tmdbId, isSeries) ?: getAniListTrailerUrl(aniList?.trailer),
            anilistEpisodes = aniList?.streamingEpisodes,
            logoUrl = finalTmdb?.logoUrl ?: fetchTmdbLogo(tmdbFromZip, isSeries),
            actors = aniList?.characters?.edges?.mapNotNull { it.node?.name?.full?.let { n -> ActorData(Actor(n, it.node.image?.large)) } },
            anilistId = aniList?.id ?: tmdbZip?.anilistId ?: zipFromKitsu?.anilistId ?: zipFromMal?.anilistId,
            malId = aniList?.idMal ?: aniZip?.malId ?: tmdbZip?.malId ?: zipFromKitsu?.malId ?: zipFromMal?.malId ?: mal,
            kitsuId = aniZip?.kitsuId ?: tmdbZip?.kitsuId ?: zipFromKitsu?.kitsuId ?: zipFromMal?.kitsuId ?: kitsu?.id,
            simklId = aniZip?.simklId ?: tmdbZip?.simklId ?: zipFromKitsu?.simklId ?: zipFromMal?.simklId,
            imdbId = finalTmdb?.imdbId
        )
    }

    private suspend fun resolveAnimeMetaCached(title: String, year: Int? = null, isSeries: Boolean = true): ResolvedAnimeMeta {
        val key = "${isSeries}|${year ?: 0}|${title.lowercase()}"
        return animeMetaCache.getOrPut(key) { resolveAnimeMeta(title, year, isSeries) }
    }

    private suspend fun resolveSeasonIds(seasonAnilistId: Int?): SeasonIds {
        if (seasonAnilistId == null) return SeasonIds()
        val zip = getAniZipFullCached(seasonAnilistId)
        return SeasonIds(seasonAnilistId, zip?.malId, zip?.kitsuId, zip?.simklId)
    }

    private suspend fun anilistIdForSeason(baseMeta: ResolvedAnimeMeta, seasonNumber: Int): Int? {
        if (seasonNumber <= 1) return baseMeta.anilistId
        var currentId = baseMeta.anilistId ?: return null
        repeat(seasonNumber - 1) {
            val node = getAniListMetaById(currentId) ?: return null
            val sequel = node.relations?.edges?.firstOrNull { it.relationType.equals("SEQUEL", ignoreCase = true) && it.node != null }?.node ?: return null
            currentId = sequel.id ?: return null
        }
        return currentId
    }

    private fun deduplicateAudioVariants(episodes: List<EpisodeData>): List<EpisodeData> {
        val audioRegex = Regex("""(?i)\b(dual[- ]?audio|multi[- ]?audio|eng\+jap|dub|sub)\b""")
        val seen = mutableMapOf<String, EpisodeData>()
        for (ep in episodes) {
            val canonKey = ep.title.replace(audioRegex, "").replace(Regex("""\s{2,}"""), " ").trim().lowercase()
            seen.putIfAbsent(canonKey, ep)
        }
        return seen.values.toList()
    }

    // =========================================================================
    // SECTION 4 — Search & Home
    // =========================================================================

    private suspend fun createSearchResult(post: Post, overrideTitle: String? = null, seasonIndex: Int? = null, posterOverride: String? = null): SearchResponse? {
        val rawTitle = overrideTitle ?: post.name?.ifBlank { post.title } ?: post.title
        val url = makeContentUrl(post.id, seasonIndex)
        val quality = getSearchQuality((post.quality ?: post.title).lowercase())
        val isSeries = post.type == "series"
        val isAnime = isAnimeContent(post.categories, post.title) || post.title.contains("anime", true)
        val fallbackPost = "$mainApiUrl/uploads/${post.imageSm}"
        val year = selectUntilNonInt(post.year)
        val cachedAnimePoster = animeMetaCache["${isSeries}|${year ?: 0}|${rawTitle.lowercase()}"]?.poster
        val cachedTmdbPoster = tmdbMetaCache["${isSeries}|${year ?: 0}|${cleanTitle(rawTitle).lowercase()}"]?.poster

        return if (isAnime) {
            newAnimeSearchResponse(rawTitle, url, if (isSeries) TvType.Anime else TvType.AnimeMovie) {
                this.posterUrl = posterOverride ?: cachedAnimePoster ?: fallbackPost
                this.quality = quality
                addDubStatus(dubExist = isDubTitle(post.title), subExist = true)
            }
        } else if (isSeries) {
            newTvSeriesSearchResponse(rawTitle, url, TvType.TvSeries) {
                this.posterUrl = posterOverride ?: cachedTmdbPoster ?: fallbackPost
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(rawTitle, url, TvType.Movie) {
                this.posterUrl = posterOverride ?: cachedTmdbPoster ?: fallbackPost
                this.quality = quality
            }
        }
    }

    private suspend fun toSearchResults(post: Post): List<SearchResponse> {
        val isAnime = isAnimeContent(post.categories, post.title) || post.title.contains("anime", true)
        val baseTitle = post.name?.ifBlank { post.title } ?: post.title
        if (post.type == "series" && isAnime) {
            return listOfNotNull(createSearchResult(post, overrideTitle = stripSeasonSuffixForAnime(baseTitle), seasonIndex = 0))
        }
        if (post.type == "series") {
            val tvData = fetchPostTvDataCached(post.id)
            val seasons = tvData?.content.orEmpty()
            if (seasons.size > 1) {
                return seasons.mapIndexedNotNull { index, season ->
                    createSearchResult(post, overrideTitle = titleForSeason(baseTitle, season.seasonName, index + 1), seasonIndex = index)
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
        val home = coroutineScope {
            AppUtils.parseJson<PageData>(json.text).posts.map { post -> async { toSearchResults(post) } }.flatMap { it.await() }
        }
        return newHomePageResponse(request.name, home, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = try {
            app.get("$mainApiUrl/api/posts?searchTerm=$query&order=desc", verify = false, cacheTime = 60)
        } catch (_: Exception) {
            app.get("$apiUrl/api/posts?searchTerm=$query&order=desc", verify = false, cacheTime = 60)
        }
        return coroutineScope {
            AppUtils.parseJson<PageData>(json.text).posts.map { post -> async { toSearchResults(post) } }.flatMap { it.await() }
        }
    }

    // =========================================================================
    // SECTION 5 — Loading Content
    // =========================================================================

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
                val meta = resolveAnimeMetaCached(cleanedTitle, year, false)
                newAnimeLoadResponse(meta.title.ifBlank { title }, url, TvType.AnimeMovie) {
                    this.posterUrl = meta.poster ?: fallbackPoster
                    this.backgroundPosterUrl = resolveBackdrop(null, null, meta.background ?: meta.poster ?: fallbackPoster)
                    this.year = year
                    this.plot = meta.plot ?: loadData.metaData
                    this.tags = meta.tags
                    this.score = Score.from100(meta.score100)
                    this.duration = duration
                    this.actors = meta.actors
                    this.logoUrl = meta.logoUrl
                    meta.trailer?.let { addTrailer(it) }
                    addAniListId(meta.anilistId); addMalId(meta.malId)
                    meta.kitsuId?.let { addKitsuId(it) }; meta.simklId?.let { addSimklId(it) }; meta.imdbId?.let { addImdbId(it) }
                    addEpisodes(if (isDubTitle(title)) DubStatus.Dubbed else DubStatus.Subbed, listOf(newEpisode(link ?: "")))
                }
            } else {
                val meta = getTmdbMetaCached(cleanedTitle, year, false)
                val trailer = fetchTmdbTrailer(meta?.tmdbId, false)
                val actors = fetchTmdbActors(meta?.tmdbId, false)
                newMovieLoadResponse(title, url, TvType.Movie, link) {
                    this.posterUrl = meta?.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta?.backdrop ?: meta?.poster ?: fallbackPoster
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
        }

        val tvData = json.parsed<TvSeries>()
        return if (isAnime) {
            val allSeasons = tvData.content
            val targetIndex = selectedSeason ?: 0
            val currentSeasonData = allSeasons.getOrNull(targetIndex) ?: allSeasons.first()
            val realSeasonNumber = targetIndex + 1
            val metaTitle = titleForSeason(title, currentSeasonData.seasonName, realSeasonNumber)
            val baseMeta = resolveAnimeMetaCached(cleanedTitle, year, true)
            val seasonAnilistId = anilistIdForSeason(baseMeta, realSeasonNumber)
            val seasonIds = resolveSeasonIds(seasonAnilistId)
            val seasonAniListNode = seasonAnilistId?.takeIf { it != baseMeta.anilistId || realSeasonNumber == 1 }?.let { getAniListMetaById(it) }
            val seasonTmdbId = getAniZipFullCached(seasonAnilistId ?: 0)?.tmdbId?.toIntOrNull()
            val seasonTmdbMeta = seasonTmdbId?.let { id -> TmdbMeta(null, null, null, null, fetchTmdbLogo(id, true), null, id) }
            val aniZipEpTitles = if (seasonAnilistId != null) getAniZipEpisodeTitles(seasonAnilistId) else emptyMap()
            val targetMeta = if (realSeasonNumber > 1 && seasonAnilistId != null && seasonAnilistId != baseMeta.anilistId) resolveAnimeMetaCached(metaTitle, year, true) else baseMeta
            val dedupedEpisodes = deduplicateAudioVariants(currentSeasonData.episodes)
            val episodeStripRegex = Regex("(?i)Episode\\s*\\d+")
            val episodesData = dedupedEpisodes.mapIndexed { idx, ep ->
                val link = if (urlCheck) ep.link else linkToIp(ep.link)
                val epNum = idx + 1
                val epTitle = aniZipEpTitles[epNum] ?: targetMeta.anilistEpisodes?.getOrNull(idx)?.title ?: ep.title.replace(episodeStripRegex, "").trim().ifBlank { null } ?: "Episode $epNum"
                val thumbnail = targetMeta.anilistEpisodes?.getOrNull(idx)?.thumbnail ?: targetMeta.poster
                newEpisode(link) { this.episode = epNum; this.season = 1; this.name = epTitle; this.posterUrl = thumbnail }
            }
            val recommendationItems = allSeasons.mapIndexedNotNull { index, season ->
                if (index == targetIndex) return@mapIndexedNotNull null
                val sNum = index + 1
                val sTitle = titleForSeason(title, season.seasonName, sNum)
                newAnimeSearchResponse(sTitle, makeContentUrl(loadData.id, index), TvType.Anime) {
                    this.posterUrl = targetMeta.poster ?: fallbackPoster
                    addDubStatus(dubExist = isDubTitle(title), subExist = true)
                }
            }
            newAnimeLoadResponse(targetMeta.title.ifBlank { metaTitle }, url, TvType.Anime) {
                this.posterUrl = targetMeta.poster ?: fallbackPoster
                this.backgroundPosterUrl = resolveBackdrop(seasonTmdbMeta, seasonAniListNode, targetMeta.background ?: fallbackPoster)
                this.year = year
                this.plot = targetMeta.plot ?: loadData.metaData
                this.tags = targetMeta.tags
                this.score = Score.from100(targetMeta.score100)
                this.actors = targetMeta.actors
                this.logoUrl = targetMeta.logoUrl ?: seasonTmdbMeta?.logoUrl
                targetMeta.trailer?.let { addTrailer(it) }
                addAniListId(seasonIds.anilistId ?: baseMeta.anilistId)
                addMalId(seasonIds.malId ?: baseMeta.malId)
                (seasonIds.kitsuId ?: baseMeta.kitsuId)?.let { addKitsuId(it) }
                (seasonIds.simklId ?: baseMeta.simklId)?.let { addSimklId(it) }
                baseMeta.imdbId?.let { addImdbId(it) }
                this.recommendations = recommendationItems
                addEpisodes(if (isDubTitle(title)) DubStatus.Dubbed else DubStatus.Subbed, episodesData)
            }
        } else {
            val meta = getTmdbMetaCached(cleanedTitle, year, true)
            val tmdbId = meta?.tmdbId
            val trailer = fetchTmdbTrailer(tmdbId, true)
            val actors = fetchTmdbActors(tmdbId, true)
            val episodesData = mutableListOf<Episode>()
            var seasonNum = 0
            tvData.content.forEach { season ->
                seasonNum++
                val tmdbEpisodes = tmdbId?.let { fetchTmdbSeasonEpisodes(it, seasonNum) } ?: emptyList()
                val dedupedEps = deduplicateAudioVariants(season.episodes)
                dedupedEps.forEachIndexed { idx, ep ->
                    val link = if (urlCheck) ep.link else linkToIp(ep.link)
                    val tmdbEp = tmdbEpisodes.getOrNull(idx)
                    episodesData.add(newEpisode(link) {
                        this.episode = idx + 1; this.season = seasonNum
                        this.name = tmdbEp?.name?.takeIf { it.isNotBlank() } ?: ep.title.ifBlank { "Episode ${idx + 1}" }
                        this.posterUrl = tmdbEp?.stillPath?.let { "$tmdbImageBase$it" } ?: meta?.poster
                        this.description = tmdbEp?.overview
                        tmdbEp?.airDate?.let { addDate(it) }
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                this.posterUrl = meta?.poster ?: fallbackPoster
                this.backgroundPosterUrl = meta?.backdrop ?: meta?.poster ?: fallbackPoster
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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val sourceLabel = when {
            data.contains("dual", ignoreCase = true) -> "FTP [Dual-Audio]"
            data.contains("multi", ignoreCase = true) -> "FTP [Multi-Audio]"
            else -> this.name
        }
        callback.invoke(newExtractorLink(source = sourceLabel, name = sourceLabel, url = data))
        return true
    }

    // =========================================================================
    // Data Classes
    // =========================================================================
    data class PageData(val posts: List<Post>)
    data class KitsuMeta(val id: String?, val title: String?, val poster: String?, val cover: String?, val synopsis: String?, val averageRating: Double?)
    data class ResolvedAnimeMeta(val title: String, val poster: String?, val background: String?, val plot: String?, val score100: Int?, val tags: List<String>?, val trailer: String?, val anilistEpisodes: List<AniListStreamingEpisode>?, val logoUrl: String?, val actors: List<ActorData>?, val anilistId: Int?, val malId: Int?, val kitsuId: String?, val simklId: Int?, val imdbId: String?)
    data class SeasonIds(val anilistId: Int? = null, val malId: Int? = null, val kitsuId: String? = null, val simklId: Int? = null)
    data class Post(val id: Int, val type: String, val imageSm: String, val title: String, val name: String? = null, val image: String? = null, val cover: String? = null, val quality: String? = null, val year: String? = null, val tags: String? = null, val categories: List<Category>? = null)
    data class Data(val id: Int, val type: String, val imageSm: String, val title: String, val image: String, val metaData: String?, val name: String?, val quality: String?, val year: String?, val watchTime: String?, val categories: List<Category>?)
    data class Category(val id: Int, val name: String?)
    data class TvSeries(val content: List<Content>)
    data class Content(val episodes: List<EpisodeData>, val seasonName: String)
    data class EpisodeData(val link: String, val title: String)
    data class Movies(val content: String?)
    data class AniListResponse(val data: AniListData?)
    data class AniListData(val Media: AniListMeta?)
    data class AniListMeta(val id: Int?, val idMal: Int?, val coverImage: AniListCoverImage?, val bannerImage: String?, val averageScore: Int?, val genres: List<String>?, val description: String?, val title: AniListTitle?, val trailer: AniListTrailer?, val streamingEpisodes: List<AniListStreamingEpisode>?, val episodes: Int?, val characters: AniListCharacterConnection?, val relations: AniListRelationConnection?)
    data class AniListCoverImage(val extraLarge: String?, val large: String?)
    data class AniListTitle(val romaji: String?, val english: String?)
    data class AniListTrailer(val id: String?, val site: String?, val thumbnail: String?)
    data class AniListStreamingEpisode(val title: String?, val thumbnail: String?, val url: String?, val site: String?)
    data class AniListCharacterConnection(val edges: List<AniListCharacterEdge>?)
    data class AniListRelationConnection(val edges: List<AniListRelationEdge>?)
    data class AniListRelationEdge(val relationType: String?, val node: AniListMeta?)
    data class AniListCharacterEdge(val node: AniListCharacterNode?, val role: String?)
    data class AniListCharacterNode(val name: AniListCharacterName?, val image: AniListCharacterImage?)
    data class AniListCharacterName(val full: String?)
    data class AniListCharacterImage(val large: String?)
    data class AniZipFull(val anilistId: Int?, val malId: Int?, val kitsuId: String?, val simklId: Int?, val tmdbId: String?)
    data class TmdbSearchResponse(val results: List<TmdbResult>?)
    data class TmdbResult(val id: Int?, val posterPath: String?, val backdropPath: String?, val voteAverage: Double?, val overview: String?)
    data class TmdbMeta(val poster: String?, val backdrop: String?, val rating: Double?, val overview: String?, val logoUrl: String?, val imdbId: String?, val tmdbId: Int?)
    data class TmdbEpisode(val name: String?, val overview: String?, val stillPath: String?, val airDate: String?, val rating: Double?)
}