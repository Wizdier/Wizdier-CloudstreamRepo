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

// =============================================================================
// CircleFtpProvider — Refactored: Unified Home/Search + Chained Recommendations
//                     + Dynamic AniZip ID mapping + Episode/Metadata/Audio fixes
// =============================================================================

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
    // Constants
    // -------------------------------------------------------------------------

    private val animeCategories         = setOf(21)
    private val possiblyAnimeCategories = setOf(1)

    private val anilistApi      = "https://graphql.anilist.co"
    private val tmdbApi         = "https://api.themoviedb.org/3"
    private val tmdbKey         = "0b2d522346f5ecbafa42ae4b0141c774"
    private val tmdbImageBase   = "https://image.tmdb.org/t/p/w500"
    private val tmdbBackdropBase = "https://image.tmdb.org/t/p/original"   // ← full-res for backgroundPosterUrl

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

    // -------------------------------------------------------------------------
    // Thread-safe in-memory caches
    // -------------------------------------------------------------------------

    private val tmdbMetaCache: MutableMap<String, TmdbMeta?> =
        Collections.synchronizedMap(mutableMapOf())

    private val animeMetaCache: MutableMap<String, ResolvedAnimeMeta> =
        Collections.synchronizedMap(mutableMapOf())

    private val kitsuMetaCache: MutableMap<String, KitsuMeta?> =
        Collections.synchronizedMap(mutableMapOf())

    private val postTvDataCache: MutableMap<Int, TvSeries?> =
        Collections.synchronizedMap(mutableMapOf())

    // Key: anilistId → fully-resolved AniZip cross-references (per-season IDs)
    private val aniZipFullCache: MutableMap<Int, AniZipFull?> =
        Collections.synchronizedMap(mutableMapOf())

    // =========================================================================
    // SECTION 1 — Title / quality helpers
    // =========================================================================

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

    private fun stripSeasonSuffixForAnime(title: String): String =
        cleanTitle(title)
            .replace(Regex("""(?i)\banime\b"""), "")
            .replace(Regex("""(?i)\bseason\s*\d+\b"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

    private fun titleForSeason(baseTitle: String, seasonName: String?, seasonNumber: Int): String {
        val base   = cleanTitle(baseTitle)
            .replace(Regex("""(?i)\banime\b"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        val season = seasonName?.trim().orEmpty()
        val genericSeason = season.isBlank()
            || season.equals("Season $seasonNumber", true)
            || season.matches(Regex("""(?i)season\s*\d+"""))
        return when {
            seasonNumber <= 1  -> base
            !genericSeason && !base.contains(season, ignoreCase = true) -> "$base: $season"
            else               -> "$base Season $seasonNumber"
        }
    }

    private fun isDubTitle(title: String): Boolean {
        val t = title.lowercase()
        return listOf("dubbed", "dual audio", "multi audio", "eng+jap", "english+japanese").any { t.contains(it) }
    }

    private fun selectUntilNonInt(string: String?): Int? =
        string?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val c = check?.lowercase() ?: return null
        return when {
            c.contains("webrip") || c.contains("web-dl") -> SearchQuality.WebRip
            c.contains("bluray")                         -> SearchQuality.BlueRay
            c.contains("hdts") || c.contains("hdcam") || c.contains("hdtc") -> SearchQuality.HdCam
            c.contains("dvd")                            -> SearchQuality.DVD
            c.contains("cam")                            -> SearchQuality.Cam
            c.contains("camrip") || c.contains("rip")    -> SearchQuality.CamRip
            c.contains("hdrip") || c.contains("hd") || c.contains("hdtv") -> SearchQuality.HD
            c.contains("telesync")                       -> SearchQuality.Telesync
            c.contains("telecine")                       -> SearchQuality.Telecine
            else                                         -> null
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

    // =========================================================================
    // SECTION 2 — URL / IP helpers
    // =========================================================================

    private fun makeContentUrl(postId: Int, seasonIndex: Int? = null): String =
        if (seasonIndex == null) "$mainUrl/content/$postId"
        else "$mainUrl/content/$postId?season=$seasonIndex"

    private fun selectedSeasonIndex(url: String): Int? =
        Regex("""[?&]season=(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun cleanApiUrl(url: String): String = url.substringBefore("?")

    private fun linkToIp(data: String?): String {
        if (data == null) return ""
        return when {
            data.contains("index.circleftp.net")  -> data.replace("index.circleftp.net",  "15.1.4.2")
            data.contains("index2.circleftp.net") -> data.replace("index2.circleftp.net", "15.1.4.5")
            data.contains("index1.circleftp.net") -> data.replace("index1.circleftp.net", "15.1.4.9")
            data.contains("ftp3.circleftp.net")   -> data.replace("ftp3.circleftp.net",   "15.1.4.7")
            data.contains("ftp4.circleftp.net")   -> data.replace("ftp4.circleftp.net",   "15.1.1.5")
            data.contains("ftp5.circleftp.net")   -> data.replace("ftp5.circleftp.net",   "15.1.1.15")
            data.contains("ftp6.circleftp.net")   -> data.replace("ftp6.circleftp.net",   "15.1.2.3")
            data.contains("ftp7.circleftp.net")   -> data.replace("ftp7.circleftp.net",   "15.1.4.8")
            data.contains("ftp8.circleftp.net")   -> data.replace("ftp8.circleftp.net",   "15.1.2.2")
            data.contains("ftp9.circleftp.net")   -> data.replace("ftp9.circleftp.net",   "15.1.2.12")
            data.contains("ftp10.circleftp.net")  -> data.replace("ftp10.circleftp.net",  "15.1.4.3")
            data.contains("ftp11.circleftp.net")  -> data.replace("ftp11.circleftp.net",  "15.1.2.6")
            data.contains("ftp12.circleftp.net")  -> data.replace("ftp12.circleftp.net",  "15.1.2.1")
            data.contains("ftp13.circleftp.net")  -> data.replace("ftp13.circleftp.net",  "15.1.1.18")
            data.contains("ftp15.circleftp.net")  -> data.replace("ftp15.circleftp.net",  "15.1.4.12")
            data.contains("ftp17.circleftp.net")  -> data.replace("ftp17.circleftp.net",  "15.1.3.8")
            else -> data
        }
    }

    // =========================================================================
    // SECTION 3 — Network / API fetchers
    // =========================================================================

    // --- AniList --------------------------------------------------------------

    private suspend fun getAniListMeta(title: String, retryCount: Int = 2): AniListMeta? {
        repeat(retryCount) { attempt ->
            try {
                val query = buildAniListQuery(byId = false)
                val body  = mapOf("query" to query, "variables" to mapOf("search" to title))
                    .toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
                val res   = app.post(anilistApi, requestBody = body,
                    headers = mapOf("Content-Type" to "application/json"), cacheTime = 3600)
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
        val body  = mapOf("query" to query, "variables" to mapOf("id" to id))
            .toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        val res   = app.post(anilistApi, requestBody = body,
            headers = mapOf("Content-Type" to "application/json"), cacheTime = 86400)
        AppUtils.parseJson<AniListResponse>(res.text).data?.Media
    } catch (_: Exception) { null }

    /** Shared GraphQL query builder — avoids duplicating two near-identical strings. */
    private fun buildAniListQuery(byId: Boolean): String {
        val param  = if (byId) "\$id: Int"    else "\$search: String"
        val lookup = if (byId) "id: \$id"     else "search: \$search"
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
            "youtube"    -> "https://www.youtube.com/watch?v=${trailer.id}"
            "dailymotion"-> "https://www.dailymotion.com/video/${trailer.id}"
            else         -> null
        }
    }

    // --- AniZip (dynamic cross-service ID resolver) ---------------------------

    /**
     * Primary AniZip lookup by AniList ID.
     * Results are cached to avoid redundant HTTP calls on multi-season chains.
     * All per-season tracking IDs (MAL, Kitsu, Simkl, TMDB) come exclusively
     * from this function — no legacy sequential-sequel guessing.
     */
    private suspend fun getAniZipFullCached(anilistId: Int): AniZipFull? {
        if (aniZipFullCache.containsKey(anilistId)) return aniZipFullCache[anilistId]
        val value = try {
            val json = JSONObject(
                app.get("https://api.ani.zip/mappings?anilist_id=$anilistId", cacheTime = 86400).text
            )
            val m = json.optJSONObject("mappings")
            AniZipFull(
                anilistId = anilistId,
                malId    = m?.optInt("mal_id")?.takeIf  { it != 0 },
                kitsuId  = m?.optString("kitsu_id")?.takeIf { it.isNotBlank() },
                simklId  = m?.optInt("simkl_id")?.takeIf { it != 0 },
                tmdbId   = m?.optString("themoviedb_id")?.takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) { null }
        aniZipFullCache[anilistId] = value
        return value
    }

    private suspend fun getAniZipByMalId(malId: Int): AniZipFull? = try {
        val json = JSONObject(
            app.get("https://api.ani.zip/mappings?mal_id=$malId", cacheTime = 86400).text
        )
        val m = json.optJSONObject("mappings")
        AniZipFull(
            anilistId = json.optInt("anilist_id").takeIf { it != 0 },
            malId     = malId,
            kitsuId   = m?.optString("kitsu_id")?.takeIf { it.isNotBlank() },
            simklId   = m?.optInt("simkl_id")?.takeIf { it != 0 },
            tmdbId    = m?.optString("themoviedb_id")?.takeIf { it.isNotBlank() }
        )
    } catch (_: Exception) { null }

    private suspend fun getAniZipByTmdbId(tmdbId: Int): AniZipFull? = try {
        val json = JSONObject(
            app.get("https://api.ani.zip/mappings?themoviedb_id=$tmdbId", cacheTime = 86400).text
        )
        val m = json.optJSONObject("mappings")
        AniZipFull(
            anilistId = json.optInt("anilist_id").takeIf { it != 0 },
            malId     = m?.optInt("mal_id")?.takeIf { it != 0 },
            kitsuId   = m?.optString("kitsu_id")?.takeIf { it.isNotBlank() },
            simklId   = m?.optInt("simkl_id")?.takeIf { it != 0 },
            tmdbId    = tmdbId.toString()
        )
    } catch (_: Exception) { null }

    private suspend fun getAniZipByKitsuId(kitsuId: String): AniZipFull? = try {
        val json = JSONObject(
            app.get("https://api.ani.zip/mappings?kitsu_id=$kitsuId", cacheTime = 86400).text
        )
        val m = json.optJSONObject("mappings")
        AniZipFull(
            anilistId = json.optInt("anilist_id").takeIf { it != 0 },
            malId     = m?.optInt("mal_id")?.takeIf { it != 0 },
            kitsuId   = kitsuId,
            simklId   = m?.optInt("simkl_id")?.takeIf { it != 0 },
            tmdbId    = m?.optString("themoviedb_id")?.takeIf { it.isNotBlank() }
        )
    } catch (_: Exception) { null }

    /**
     * AniZip episode-title lookup.
     * Returns a map of (1-based episode number) → localized title string.
     * Used to fill blank episode titles during episode generation.
     */
    private suspend fun getAniZipEpisodeTitles(anilistId: Int): Map<Int, String> = try {
        val json = JSONObject(
            app.get("https://api.ani.zip/mappings?anilist_id=$anilistId", cacheTime = 86400).text
        )
        val episodes = json.optJSONObject("episodes") ?: return emptyMap()
        val map = mutableMapOf<Int, String>()
        episodes.keys().forEach { key ->
            val epNum  = key.toIntOrNull() ?: return@forEach
            val epObj  = episodes.optJSONObject(key) ?: return@forEach
            val titles = epObj.optJSONObject("title")
            val title  = titles?.optString("en")?.takeIf { it.isNotBlank() }
                ?: titles?.optString("x-jat")?.takeIf { it.isNotBlank() }
                ?: epObj.optString("title")?.takeIf { it.isNotBlank() }
            if (title != null) map[epNum] = title
        }
        map
    } catch (_: Exception) { emptyMap() }

    // --- Jikan (MAL fallback) -------------------------------------------------

    private suspend fun searchMalId(title: String): Int? = try {
        val res = app.get(
            "https://api.jikan.moe/v4/anime?q=${URLEncoder.encode(title, "UTF-8")}&limit=1",
            cacheTime = 3600
        )
        JSONObject(res.text).optJSONArray("data")?.optJSONObject(0)?.optInt("mal_id")
    } catch (_: Exception) { null }

    // --- Kitsu ----------------------------------------------------------------

    private suspend fun getKitsuMeta(title: String): KitsuMeta? = try {
        val res   = app.get(
            "https://kitsu.io/api/edge/anime?filter[text]=${URLEncoder.encode(title, "UTF-8")}",
            headers = mapOf("Accept" to "application/vnd.api+json"),
            cacheTime = 3600
        )
        val first = JSONObject(res.text).optJSONArray("data")?.optJSONObject(0) ?: return null
        val id    = first.optString("id").takeIf { it.isNotBlank() }
        val attr  = first.optJSONObject("attributes")
            ?: return KitsuMeta(id, null, null, null, null, null)
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
            id            = id,
            title         = resolvedTitle,
            poster        = poster,
            cover         = cover,
            synopsis      = attr.optString("synopsis").takeIf { it.isNotBlank() },
            averageRating = attr.optString("averageRating").toDoubleOrNull()
        )
    } catch (_: Exception) { null }

    private suspend fun getKitsuMetaCached(title: String): KitsuMeta? {
        val key = title.lowercase()
        if (kitsuMetaCache.containsKey(key)) return kitsuMetaCache[key]
        val value = getKitsuMeta(title)
        kitsuMetaCache[key] = value
        return value
    }

    // --- TMDB -----------------------------------------------------------------

    private suspend fun getTmdbMeta(title: String, year: Int?, isSeries: Boolean): TmdbMeta? = try {
        val type      = if (isSeries) "tv" else "movie"
        val yearParam = year?.let { if (isSeries) "&first_air_date_year=$it" else "&year=$it" } ?: ""
        val url       = "$tmdbApi/search/$type?api_key=$tmdbKey&query=${URLEncoder.encode(title, "UTF-8")}$yearParam&language=en-US"
        val first     = AppUtils.parseJson<TmdbSearchResponse>(
            app.get(url, cacheTime = 86400).text
        ).results?.firstOrNull() ?: return null
        val logo      = fetchTmdbLogo(first.id, isSeries)
        var imdbId: String? = null
        try {
            val detail = JSONObject(
                app.get("$tmdbApi/$type/${first.id}?api_key=$tmdbKey&append_to_response=external_ids", cacheTime = 86400).text
            )
            imdbId = detail.optJSONObject("external_ids")?.optString("imdb_id")
        } catch (_: Exception) {}
        TmdbMeta(
            poster   = first.posterPath?.let   { "$tmdbImageBase$it" },
            backdrop = first.backdropPath?.let { "$tmdbBackdropBase$it" }, // ← full-res original
            rating   = first.voteAverage,
            overview = first.overview,
            logoUrl  = logo,
            imdbId   = imdbId,
            tmdbId   = first.id
        )
    } catch (_: Exception) { null }

    private suspend fun getTmdbMetaCached(title: String, year: Int?, isSeries: Boolean): TmdbMeta? {
        val key = "${isSeries}|${year ?: 0}|${title.lowercase()}"
        if (tmdbMetaCache.containsKey(key)) return tmdbMetaCache[key]
        val value = getTmdbMeta(title, year, isSeries)
        tmdbMetaCache[key] = value
        return value
    }

    /**
     * Fetches the highest-resolution available backdrop for `backgroundPosterUrl`.
     * Priority: TMDB backdrop (original) → AniList banner → AniList cover → poster fallback.
     */
    private fun resolveBackdrop(tmdbMeta: TmdbMeta?, aniListMeta: AniListMeta?, fallback: String?): String? =
        tmdbMeta?.backdrop                              // tmdbBackdropBase = /original
            ?: aniListMeta?.bannerImage
            ?: aniListMeta?.coverImage?.extraLarge
            ?: tmdbMeta?.poster
            ?: fallback

    private suspend fun fetchTmdbLogo(tmdbId: Int?, isSeries: Boolean): String? {
        if (tmdbId == null) return null
        return try {
            val type  = if (isSeries) "tv" else "movie"
            val json  = JSONObject(app.get("$tmdbApi/$type/$tmdbId/images?api_key=$tmdbKey", cacheTime = 86400).text)
            val logos = json.optJSONArray("logos") ?: return null
            for (i in 0 until logos.length()) {
                val l    = logos.getJSONObject(i)
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
            val type    = if (isSeries) "tv" else "movie"
            val json    = JSONObject(app.get("$tmdbApi/$type/$tmdbId/videos?api_key=$tmdbKey&language=en-US", cacheTime = 86400).text)
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
        val json = JSONObject(
            app.get("$tmdbApi/tv/$tmdbId/season/$seasonNum?api_key=$tmdbKey&language=en-US", cacheTime = 86400).text
        )
        val arr = json.optJSONArray("episodes") ?: return emptyList()
        List(arr.length()) { i ->
            val ep = arr.getJSONObject(i)
            TmdbEpisode(
                name      = ep.optString("name"),
                overview  = ep.optString("overview"),
                stillPath = ep.optString("still_path").takeIf { it.isNotBlank() },
                airDate   = ep.optString("air_date"),
                rating    = ep.optDouble("vote_average")
            )
        }
    } catch (_: Exception) { emptyList() }

    private suspend fun fetchTmdbActors(tmdbId: Int?, isSeries: Boolean): List<ActorData>? {
        if (tmdbId == null) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(app.get("$tmdbApi/$type/$tmdbId/credits?api_key=$tmdbKey", cacheTime = 86400).text)
            val cast = json.optJSONArray("cast") ?: return null
            (0 until minOf(cast.length(), 20)).mapNotNull { i ->
                val p   = cast.getJSONObject(i)
                val n   = p.optString("name")
                val img = p.optString("profile_path").takeIf { it.isNotBlank() }?.let { "$tmdbImageBase$it" }
                if (n.isNotBlank()) ActorData(Actor(n, img)) else null
            }
        } catch (_: Exception) { null }
    }

    // --- Server data ----------------------------------------------------------

    private suspend fun fetchPostResponse(postId: Int) = try {
        app.get("$mainApiUrl/api/posts/$postId", verify = false, cacheTime = 60)
    } catch (_: Exception) {
        app.get("$apiUrl/api/posts/$postId", verify = false, cacheTime = 60)
    }

    private suspend fun fetchPostTvData(postId: Int): TvSeries? = try {
        fetchPostResponse(postId).parsed<TvSeries>()
    } catch (_: Exception) { null }

    private suspend fun fetchPostTvDataCached(postId: Int): TvSeries? {
        if (postTvDataCache.containsKey(postId)) return postTvDataCache[postId]
        val value = fetchPostTvData(postId)
        postTvDataCache[postId] = value
        return value
    }

    // =========================================================================
    // SECTION 4 — Anime meta resolver (REFACTORED: fully dynamic AniZip)
    //
    // ⚠️  LEGACY REMOVED: getAniListMetaForSeason() and its sequential-sequel
    //     walk (sequelOf() → getAniListMetaById() loop) are deleted.  Per-season
    //     IDs now come exclusively from AniZip via getAniZipFullCached(), which
    //     stores verified MAL/Kitsu/Simkl/TMDB mappings for every season.
    // =========================================================================

    private suspend fun resolveAnimeMeta(title: String, year: Int? = null, isSeries: Boolean = true): ResolvedAnimeMeta {
        val cleaned = cleanTitle(title)
            .replace(Regex("""(?i)\banime\b"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        // 1. Try TMDB first — gives us a tmdbId we can feed to AniZip
        val tmdbMeta   = getTmdbMeta(cleaned, year, isSeries)
        val tmdbZip    = tmdbMeta?.tmdbId?.let { getAniZipByTmdbId(it) }

        // 2. Resolve AniList node (prefer ID from AniZip→TMDB path; fall back to text search)
        var aniList = tmdbZip?.anilistId?.let { getAniListMetaById(it) }
            ?: getAniListMeta(cleaned)
            ?: getAniListMeta(title)

        // 3. AniZip canonical lookup via AniList ID (cached)
        var aniZip = aniList?.id?.let { getAniZipFullCached(it) }

        // 4. Kitsu fallback chain
        val kitsu       = getKitsuMetaCached(cleaned) ?: getKitsuMetaCached(title)
        val zipFromKitsu = kitsu?.id?.let { getAniZipByKitsuId(it) }
        if (aniList == null) aniList = zipFromKitsu?.anilistId?.let { getAniListMetaById(it) }

        // 5. MAL fallback chain
        val mal = aniList?.idMal
            ?: aniZip?.malId ?: tmdbZip?.malId ?: zipFromKitsu?.malId
            ?: searchMalId(cleaned)
        val zipFromMal = if ((aniList == null || aniZip == null) && mal != null)
            getAniZipByMalId(mal) else null
        if (aniList == null) aniList = zipFromMal?.anilistId?.let { getAniListMetaById(it) }
        if (aniZip  == null && aniList?.id != null) aniZip = getAniZipFullCached(aniList.id!!)

        // 6. Best TMDB ID across all zip sources
        val tmdbFromZip = aniZip?.tmdbId?.toIntOrNull()
            ?: tmdbZip?.tmdbId?.toIntOrNull()
            ?: zipFromKitsu?.tmdbId?.toIntOrNull()
            ?: zipFromMal?.tmdbId?.toIntOrNull()

        val finalTmdb = tmdbMeta ?: tmdbFromZip?.let { id ->
            TmdbMeta(null, null, null, null, fetchTmdbLogo(id, isSeries), null, id)
        }

        val displayTitle = aniList?.title?.english
            ?: aniList?.title?.romaji
            ?: kitsu?.title
            ?: cleaned

        return ResolvedAnimeMeta(
            title      = displayTitle,
            poster     = aniList?.coverImage?.extraLarge
                ?: aniList?.coverImage?.large
                ?: kitsu?.poster
                ?: finalTmdb?.poster,
            background = resolveBackdrop(finalTmdb, aniList, null),
            plot       = aniList?.description?.replace(Regex("<[^>]*>"), "")
                ?: kitsu?.synopsis
                ?: finalTmdb?.overview,
            score100   = aniList?.averageScore ?: kitsu?.averageRating?.toInt(),
            tags       = aniList?.genres,
            trailer    = fetchTmdbTrailer(finalTmdb?.tmdbId, isSeries)
                ?: getAniListTrailerUrl(aniList?.trailer),
            anilistEpisodes = aniList?.streamingEpisodes,
            logoUrl    = finalTmdb?.logoUrl ?: fetchTmdbLogo(tmdbFromZip, isSeries),
            actors     = aniList?.characters?.edges?.mapNotNull {
                it.node?.name?.full?.let { n -> ActorData(Actor(n, it.node.image?.large)) }
            },
            anilistId  = aniList?.id ?: tmdbZip?.anilistId ?: zipFromKitsu?.anilistId ?: zipFromMal?.anilistId,
            malId      = aniList?.idMal ?: aniZip?.malId ?: tmdbZip?.malId ?: zipFromKitsu?.malId ?: zipFromMal?.malId ?: mal,
            kitsuId    = aniZip?.kitsuId ?: tmdbZip?.kitsuId ?: zipFromKitsu?.kitsuId ?: zipFromMal?.kitsuId ?: kitsu?.id,
            simklId    = aniZip?.simklId ?: tmdbZip?.simklId ?: zipFromKitsu?.simklId ?: zipFromMal?.simklId,
            imdbId     = finalTmdb?.imdbId
        )
    }

    private suspend fun resolveAnimeMetaCached(title: String, year: Int? = null, isSeries: Boolean = true): ResolvedAnimeMeta {
        val key = "${isSeries}|${year ?: 0}|${title.lowercase()}"
        animeMetaCache[key]?.let { return it }
        val value = resolveAnimeMeta(title, year, isSeries)
        animeMetaCache[key] = value
        return value
    }

    /**
     * Per-season AniZip resolution.
     *
     * Given a season's AniList ID (obtained from the SEQUEL relation chain
     * already present in the first season's AniList payload), returns verified
     * tracking IDs.  No sequential guessing — the chain is walked once via
     * AniList relations, then every season's IDs are confirmed through AniZip.
     */
    private suspend fun resolveSeasonIds(
        seasonAnilistId: Int?
    ): SeasonIds {
        if (seasonAnilistId == null) return SeasonIds()
        val zip = getAniZipFullCached(seasonAnilistId)
        return SeasonIds(
            anilistId = seasonAnilistId,
            malId     = zip?.malId,
            kitsuId   = zip?.kitsuId,
            simklId   = zip?.simklId
        )
    }

    /** Walk the AniList SEQUEL chain to find the AniList ID for season N (1-based). */
    private suspend fun anilistIdForSeason(baseMeta: ResolvedAnimeMeta, seasonNumber: Int): Int? {
        if (seasonNumber <= 1) return baseMeta.anilistId
        var currentId = baseMeta.anilistId ?: return null
        repeat(seasonNumber - 1) {
            val node = getAniListMetaById(currentId) ?: return null
            val sequel = node.relations?.edges
                ?.firstOrNull { it.relationType.equals("SEQUEL", ignoreCase = true) && it.node != null }
                ?.node ?: return null
            currentId = sequel.id ?: return null
        }
        return currentId
    }

    // =========================================================================
    // SECTION 5 — Audio deduplication helper
    //
    // Groups file entries that differ only by audio flavour (Dual/Multi/Eng/Jap)
    // into a single canonical link. The audio label is preserved in the source
    // name returned to loadLinks so the user still sees which track is playing.
    // =========================================================================

    /**
     * Given raw episode file objects, collapses entries that map to the same
     * episode number but differ only in audio tag.  The first variant
     * encountered per episode wins as the canonical link; duplicates are
     * silently dropped to prevent duplicate cards.
     *
     * If the server already deduplicates (single EpisodeData per episode) this
     * function is a no-op pass-through.
     */
    private fun deduplicateAudioVariants(episodes: List<EpisodeData>): List<EpisodeData> {
        // Strip audio / quality suffixes then deduplicate by cleaned title
        val audioRegex = Regex("""(?i)\b(dual[- ]?audio|multi[- ]?audio|eng\+jap|dub|sub)\b""")
        val seen       = mutableMapOf<String, EpisodeData>()
        for (ep in episodes) {
            val canonKey = ep.title
                .replace(audioRegex, "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
                .lowercase()
            seen.putIfAbsent(canonKey, ep) // first variant wins
        }
        return seen.values.toList()
    }

    // =========================================================================
    // SECTION 6 — Search result builders
    // =========================================================================

    private suspend fun createSearchResult(
        post: Post,
        overrideTitle: String? = null,
        seasonIndex: Int? = null,
        posterOverride: String? = null
    ): SearchResponse? {
        val rawTitle     = overrideTitle ?: post.name?.ifBlank { post.title } ?: post.title
        val url          = makeContentUrl(post.id, seasonIndex)
        val quality      = getSearchQuality((post.quality ?: post.title).lowercase())
        val isSeries     = post.type == "series"
        val isAnime      = isAnimeContent(post.categories, post.title) || post.title.contains("anime", true)
        val fallbackPost = "$mainApiUrl/uploads/${post.imageSm}"
        val year         = selectUntilNonInt(post.year)

        val cachedAnimePoster = animeMetaCache["${isSeries}|${year ?: 0}|${rawTitle.lowercase()}"]?.poster
        val cachedTmdbPoster  = tmdbMetaCache["${isSeries}|${year ?: 0}|${cleanTitle(rawTitle).lowercase()}"]?.poster

        return if (isAnime) {
            newAnimeSearchResponse(rawTitle, url, if (isSeries) TvType.Anime else TvType.AnimeMovie) {
                this.posterUrl = posterOverride ?: cachedAnimePoster ?: fallbackPost
                this.quality   = quality
                addDubStatus(dubExist = isDubTitle(post.title), subExist = true)
            }
        } else if (isSeries) {
            newTvSeriesSearchResponse(rawTitle, url, TvType.TvSeries) {
                this.posterUrl = posterOverride ?: cachedTmdbPoster ?: fallbackPost
                this.quality   = quality
            }
        } else {
            newMovieSearchResponse(rawTitle, url, TvType.Movie) {
                this.posterUrl = posterOverride ?: cachedTmdbPoster ?: fallbackPost
                this.quality   = quality
            }
        }
    }

    /**
     * REFACTORED: Unified Home & Search layout.
     *
     * • Anime series → always returns exactly ONE card pointing to Season 1
     *   (seasonIndex = 0).  Subsequent seasons are surfaced only inside `load()`
     *   as chained recommendations, never as duplicate grid cards.
     *
     * • Non-anime TV series with multiple seasons → unchanged behaviour
     *   (one card per season in grid).
     *
     * • Movies / single-season series → unchanged.
     */
    private suspend fun toSearchResults(post: Post): List<SearchResponse> {
        val isAnime  = isAnimeContent(post.categories, post.title) || post.title.contains("anime", true)
        val baseTitle = post.name?.ifBlank { post.title } ?: post.title

        if (post.type == "series" && isAnime) {
            // Single card for Season 1 — no duplicates on home/search grid
            return listOfNotNull(
                createSearchResult(
                    post          = post,
                    overrideTitle = stripSeasonSuffixForAnime(baseTitle),
                    seasonIndex   = 0
                )
            )
        }

        if (post.type == "series") {
            val tvData  = fetchPostTvDataCached(post.id)
            val seasons = tvData?.content.orEmpty()
            if (seasons.size > 1) {
                return seasons.mapIndexedNotNull { index, season ->
                    createSearchResult(
                        post          = post,
                        overrideTitle = titleForSeason(baseTitle, season.seasonName, index + 1),
                        seasonIndex   = index
                    )
                }
            }
        }

        return listOfNotNull(createSearchResult(post))
    }

    // =========================================================================
    // SECTION 7 — Main page / search overrides
    // =========================================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = try {
            app.get("$mainApiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10", verify = false, cacheTime = 60)
        } catch (_: Exception) {
            app.get("$apiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10",    verify = false, cacheTime = 60)
        }
        val home = coroutineScope {
            AppUtils.parseJson<PageData>(json.text).posts
                .map { post -> async { toSearchResults(post) } }
                .flatMap { it.await() }
        }
        return newHomePageResponse(request.name, home, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = try {
            app.get("$mainApiUrl/api/posts?searchTerm=$query&order=desc", verify = false, cacheTime = 60)
        } catch (_: Exception) {
            app.get("$apiUrl/api/posts?searchTerm=$query&order=desc",    verify = false, cacheTime = 60)
        }
        return coroutineScope {
            AppUtils.parseJson<PageData>(json.text).posts
                .map { post -> async { toSearchResults(post) } }
                .flatMap { it.await() }
        }
    }

    // =========================================================================
    // SECTION 8 — load() — REFACTORED
    //
    // Changes vs. original:
    //  1. getAniListMetaForSeason() REMOVED — replaced with anilistIdForSeason()
    //     + resolveSeasonIds() (pure AniZip, no sequential guessing).
    //  2. Episode titles now cross-reference AniZip episode map + AniList
    //     streamingEpisodes canvas to eliminate blank fields.
    //  3. backgroundPosterUrl uses resolveBackdrop() which enforces full-res
    //     TMDB /original for all paths.
    //  4. Anime recommendations are chained: only OTHER seasons appear there,
    //     preventing the current season from recommending itself.
    //  5. Audio deduplication applied before episode generation.
    // =========================================================================

    override suspend fun load(url: String): LoadResponse {
        val json = try {
            app.get(cleanApiUrl(url).replace("$mainUrl/content/", "$mainApiUrl/api/posts/"), verify = false, cacheTime = 60)
        } catch (_: Exception) {
            app.get(cleanApiUrl(url).replace("$mainUrl/content/", "$apiUrl/api/posts/"),    verify = false, cacheTime = 60)
        }
        val urlCheck      = json.url.contains(mainApiUrl)
        val loadData      = AppUtils.parseJson<Data>(json.text)
        val title         = loadData.name?.ifBlank { loadData.title } ?: loadData.title
        val cleanedTitle  = cleanTitle(title)
        val fallbackPoster = "$apiUrl/uploads/${loadData.image}"
        val year          = selectUntilNonInt(loadData.year)
        val isAnime       = isAnimeContent(loadData.categories, title)
        val selectedSeason = selectedSeasonIndex(url)

        // ------------------------------------------------------------------
        // A) Single-video / Movie path
        // ------------------------------------------------------------------
        if (loadData.type == "singleVideo") {
            val movieUrl = json.parsed<Movies>().content
            val link     = if (urlCheck) movieUrl else linkToIp(movieUrl)
            val duration = getDurationFromString(loadData.watchTime)

            return if (isAnime) {
                val meta = resolveAnimeMetaCached(cleanedTitle, year, false)
                newAnimeLoadResponse(meta.title.ifBlank { title }, url, TvType.AnimeMovie) {
                    this.posterUrl           = meta.poster ?: fallbackPoster
                    this.backgroundPosterUrl = resolveBackdrop(null, null, meta.background ?: meta.poster ?: fallbackPoster)
                    this.year     = year
                    this.plot     = meta.plot ?: loadData.metaData
                    this.tags     = meta.tags
                    this.score    = Score.from100(meta.score100)
                    this.duration = duration
                    this.actors   = meta.actors
                    this.logoUrl  = meta.logoUrl
                    meta.trailer?.let { addTrailer(it) }
                    addAniListId(meta.anilistId)
                    addMalId(meta.malId)
                    meta.kitsuId?.let { addKitsuId(it) }
                    meta.simklId?.let { addSimklId(it) }
                    meta.imdbId?.let  { addImdbId(it) }
                    addEpisodes(if (isDubTitle(title)) DubStatus.Dubbed else DubStatus.Subbed,
                        listOf(newEpisode(link ?: "")))
                }
            } else {
                val meta    = getTmdbMetaCached(cleanedTitle, year, false)
                val trailer = fetchTmdbTrailer(meta?.tmdbId, false)
                val actors  = fetchTmdbActors(meta?.tmdbId, false)
                newMovieLoadResponse(title, url, TvType.Movie, link) {
                    this.posterUrl           = meta?.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta?.backdrop ?: meta?.poster ?: fallbackPoster
                    this.year     = year
                    this.plot     = meta?.overview ?: loadData.metaData
                    this.score    = Score.from10(meta?.rating)
                    this.duration = duration
                    this.actors   = actors
                    this.logoUrl  = meta?.logoUrl
                    trailer?.let { addTrailer(it) }
                    meta?.imdbId?.let { addImdbId(it) }
                }
            }
        }

        // ------------------------------------------------------------------
        // B) TV-series path
        // ------------------------------------------------------------------
        val tvData = json.parsed<TvSeries>()

        return if (isAnime) {
            // ----------------------------------------------------------------
            // B1) Anime multi-season
            // ----------------------------------------------------------------
            val allSeasons      = tvData.content
            val targetIndex     = selectedSeason ?: 0
            val currentSeasonData = allSeasons.getOrNull(targetIndex) ?: allSeasons.first()
            val realSeasonNumber  = targetIndex + 1
            val metaTitle         = titleForSeason(title, currentSeasonData.seasonName, realSeasonNumber)

            // Fetch base meta (Season 1) — used for fallback poster/plot/tags
            val baseMeta = resolveAnimeMetaCached(cleanedTitle, year, true)

            // ── Dynamic per-season ID resolution via AniZip ──────────────
            // Walk the SEQUEL chain to get this season's AniList ID, then
            // confirm all tracking IDs through AniZip (no guessing).
            val seasonAnilistId = anilistIdForSeason(baseMeta, realSeasonNumber)
            val seasonIds       = resolveSeasonIds(seasonAnilistId)

            // Fetch season-specific meta if we have a distinct AniList ID
            val seasonAniListNode = seasonAnilistId
                ?.takeIf { it != baseMeta.anilistId || realSeasonNumber == 1 }
                ?.let { getAniListMetaById(it) }

            // Season-specific TMDB backdrop (full-res)
            val seasonTmdbId = getAniZipFullCached(seasonAnilistId ?: 0)?.tmdbId?.toIntOrNull()
            val seasonTmdbMeta = seasonTmdbId?.let { id ->
                TmdbMeta(
                    poster   = null,
                    backdrop = null,
                    rating   = null,
                    overview = null,
                    logoUrl  = fetchTmdbLogo(id, true),
                    imdbId   = null,
                    tmdbId   = id
                )
            }

            // ── AniZip episode-title map for blank-field elimination ──────
            val aniZipEpTitles: Map<Int, String> =
                if (seasonAnilistId != null) getAniZipEpisodeTitles(seasonAnilistId)
                else emptyMap()

            // Display-level meta for the current season
            val targetMeta = when {
                realSeasonNumber > 1 && seasonAnilistId != null && seasonAnilistId != baseMeta.anilistId ->
                    resolveAnimeMetaCached(metaTitle, year, true)
                else -> baseMeta
            }

            // ── Audio deduplication ───────────────────────────────────────
            val dedupedEpisodes = deduplicateAudioVariants(currentSeasonData.episodes)

            // ── Episode generation ────────────────────────────────────────
            val episodesData = dedupedEpisodes.mapIndexed { idx, ep ->
                val link    = if (urlCheck) ep.link else linkToIp(ep.link)
                val epNum   = idx + 1

                // Title priority: AniZip localized → AniList streaming canvas
                // → server title (stripped) → generic fallback
                val aniZipTitle  = aniZipEpTitles[epNum]
                val aniListTitle = targetMeta.anilistEpisodes?.getOrNull(idx)?.title
                val serverTitle  = ep.title
                    .replace(Regex("(?i)Episode\\s*\\d+"), "")
                    .trim()
                    .ifBlank { null }
                val epTitle = aniZipTitle
                    ?: aniListTitle
                    ?: serverTitle
                    ?: "Episode $epNum"

                // Thumbnail: AniList streaming episode canvas → season poster
                val thumbnail = targetMeta.anilistEpisodes?.getOrNull(idx)?.thumbnail
                    ?: targetMeta.poster

                newEpisode(link) {
                    this.episode  = epNum
                    this.season   = 1   // Flattened per-season scope for accurate sync
                    this.name     = epTitle
                    this.posterUrl = thumbnail
                }
            }

            // ── Chained recommendations (other seasons) ───────────────────
            val recommendationItems: List<SearchResponse> = allSeasons
                .mapIndexedNotNull { index, season ->
                    if (index == targetIndex) return@mapIndexedNotNull null
                    val sNum   = index + 1
                    val sTitle = titleForSeason(title, season.seasonName, sNum)
                    newAnimeSearchResponse(sTitle, makeContentUrl(loadData.id, index), TvType.Anime) {
                        this.posterUrl = targetMeta.poster ?: fallbackPoster
                        addDubStatus(dubExist = isDubTitle(title), subExist = true)
                    }
                }

            newAnimeLoadResponse(targetMeta.title.ifBlank { metaTitle }, url, TvType.Anime) {
                this.posterUrl           = targetMeta.poster ?: fallbackPoster
                this.backgroundPosterUrl = resolveBackdrop(seasonTmdbMeta, seasonAniListNode, targetMeta.background ?: fallbackPoster)
                this.year    = year
                this.plot    = targetMeta.plot ?: loadData.metaData
                this.tags    = targetMeta.tags
                this.score   = Score.from100(targetMeta.score100)
                this.actors  = targetMeta.actors
                this.logoUrl = targetMeta.logoUrl ?: seasonTmdbMeta?.logoUrl

                targetMeta.trailer?.let { addTrailer(it) }

                // Per-season verified tracking IDs from AniZip
                addAniListId(seasonIds.anilistId ?: baseMeta.anilistId)
                addMalId(seasonIds.malId ?: baseMeta.malId)
                (seasonIds.kitsuId  ?: baseMeta.kitsuId)?.let  { addKitsuId(it) }
                (seasonIds.simklId  ?: baseMeta.simklId)?.let  { addSimklId(it) }
                baseMeta.imdbId?.let { addImdbId(it) }

                this.recommendations = recommendationItems
                addEpisodes(if (isDubTitle(title)) DubStatus.Dubbed else DubStatus.Subbed, episodesData)
            }

        } else {
            // ----------------------------------------------------------------
            // B2) Non-anime TV series (unchanged logic, backdrop upgraded)
            // ----------------------------------------------------------------
            val meta    = getTmdbMetaCached(cleanedTitle, year, true)
            val tmdbId  = meta?.tmdbId
            val trailer = fetchTmdbTrailer(tmdbId, true)
            val actors  = fetchTmdbActors(tmdbId, true)

            val episodesData = mutableListOf<Episode>()
            var seasonNum = 0
            tvData.content.forEach { season ->
                seasonNum++
                val tmdbEpisodes = tmdbId?.let { fetchTmdbSeasonEpisodes(it, seasonNum) } ?: emptyList()
                val dedupedEps   = deduplicateAudioVariants(season.episodes)
                dedupedEps.forEachIndexed { idx, ep ->
                    val link    = if (urlCheck) ep.link else linkToIp(ep.link)
                    val tmdbEp  = tmdbEpisodes.getOrNull(idx)
                    episodesData.add(newEpisode(link) {
                        this.episode   = idx + 1
                        this.season    = seasonNum
                        this.name      = tmdbEp?.name?.takeIf { it.isNotBlank() } ?: ep.title.ifBlank { "Episode ${idx + 1}" }
                        this.posterUrl = tmdbEp?.stillPath?.let { "$tmdbImageBase$it" } ?: meta?.poster
                        this.description = tmdbEp?.overview
                        tmdbEp?.airDate?.let { addDate(it) }
                    })
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                this.posterUrl           = meta?.poster ?: fallbackPoster
                this.backgroundPosterUrl = meta?.backdrop ?: meta?.poster ?: fallbackPoster   // /original
                this.year    = year
                this.plot    = meta?.overview ?: loadData.metaData
                this.score   = Score.from10(meta?.rating)
                this.actors  = actors
                this.logoUrl = meta?.logoUrl
                trailer?.let { addTrailer(it) }
                meta?.imdbId?.let { addImdbId(it) }
            }
        }
    }

    // =========================================================================
    // SECTION 9 — loadLinks
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Audio flavour is preserved in the source label so the player UI
        // remains descriptive, while deduplicateAudioVariants() prevents
        // duplicate episode cards upstream.
        val sourceLabel = when {
            data.contains("dual",  ignoreCase = true) -> "FTP [Dual-Audio]"
            data.contains("multi", ignoreCase = true) -> "FTP [Multi-Audio]"
            else                                       -> this.name
        }
        callback.invoke(newExtractorLink(source = sourceLabel, name = sourceLabel, url = data))
        return true
    }

    // =========================================================================
    // SECTION 10 — Data classes
    // =========================================================================

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

    /** Lightweight per-season tracking ID bundle resolved via AniZip. */
    data class SeasonIds(
        val anilistId: Int? = null,
        val malId: Int?     = null,
        val kitsuId: String? = null,
        val simklId: Int?   = null
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

    data class Data(
        val id: Int,
        val type: String,
        val imageSm: String,
        val title: String,
        val image: String,
        val metaData: String?,
        val name: String?,
        val quality: String?,
        val year: String?,
        val watchTime: String?,
        val categories: List<Category>?
    )

    data class Category(val id: Int, val name: String?)
    data class TvSeries(val content: List<Content>)
    data class Content(val episodes: List<EpisodeData>, val seasonName: String)
    data class EpisodeData(val link: String, val title: String)
    data class Movies(val content: String?)

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
        val trailer: AniListTrailer?,
        val streamingEpisodes: List<AniListStreamingEpisode>?,
        val episodes: Int?,
        val characters: AniListCharacterConnection?,
        val relations: AniListRelationConnection?
    )
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

    /** Unified AniZip cross-service mapping. */
    data class AniZipFull(
        val anilistId: Int?,
        val malId: Int?,
        val kitsuId: String?,
        val simklId: Int?,
        val tmdbId: String?
    )

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
        val backdrop: String?,    // always sourced from /original endpoint
        val rating: Double?,
        val overview: String?,
        val logoUrl: String?,
        val imdbId: String?,
        val tmdbId: Int?
    )
    data class TmdbEpisode(
        val name: String?,
        val overview: String?,
        val stillPath: String?,
        val airDate: String?,
        val rating: Double?
    )
}