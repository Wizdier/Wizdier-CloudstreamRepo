package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

    private val tmdbMetaCache: MutableMap<String, TmdbMeta?> =
        Collections.synchronizedMap(mutableMapOf())

    private val animeMetaCache: MutableMap<String, ResolvedAnimeMeta> =
        Collections.synchronizedMap(mutableMapOf())

    private val kitsuMetaCache: MutableMap<String, KitsuMeta?> =
        Collections.synchronizedMap(mutableMapOf())

    private val postTvDataCache: MutableMap<Int, TvSeries?> =
        Collections.synchronizedMap(mutableMapOf())

    private val aniZipFullCache: MutableMap<Int, AniZipFull?> =
        Collections.synchronizedMap(mutableMapOf())

    // ─── String / Title Helpers ──────────────────────────────────────────────

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
        val base = cleanTitle(baseTitle)
            .replace(Regex("""(?i)\banime\b"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        val season = seasonName?.trim().orEmpty()
        val genericSeason = season.isBlank()
            || season.equals("Season $seasonNumber", true)
            || season.matches(Regex("""(?i)season\s*\d+"""))

        return when {
            seasonNumber <= 1 -> base
            !genericSeason && !base.contains(season, ignoreCase = true) -> "$base: $season"
            else -> "$base Season $seasonNumber"
        }
    }

    private fun extractSeasonNumberFromTitle(title: String): Int {
        return Regex("""(?i)\bseason\s*(\d+)\b""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)\bs(\d+)\b""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 1
    }

    private fun isDubTitle(title: String): Boolean {
        val t = title.lowercase()
        return listOf("dubbed", "dual audio", "multi audio", "eng+jap", "english+japanese", "hindi").any { kw -> t.contains(kw) }
    }

    private fun selectUntilNonInt(string: String?): Int? =
        string?.let { s -> Regex("""\d{4}""").find(s)?.value?.toIntOrNull() }

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
        if (categories?.any { cat -> cat.id in animeCategories } == true) return true
        if (categories?.any { cat -> cat.id in possiblyAnimeCategories } == true) {
            if (nonAnimeKeywords.any { kw -> t.contains(kw) }) return false
            return true
        }
        return animeKeywords.any { kw -> t.contains(kw) }
    }

    // ─── URL Construction / Parsing ─────────────────────────────────────────

    private fun makeContentUrl(
        postId: Int,
        seasonIndex: Int? = null,
        groupedPostIds: List<Int> = emptyList()
    ): String {
        val query = mutableListOf<String>()
        if (seasonIndex != null) query += "season=$seasonIndex"
        val variants = groupedPostIds.distinct().filter { variantId -> variantId != postId }
        if (variants.isNotEmpty()) query += "variants=${variants.joinToString(",")}"
        return if (query.isEmpty()) "$mainUrl/content/$postId"
        else "$mainUrl/content/$postId?${query.joinToString("&")}"
    }

    private fun selectedSeasonIndex(url: String): Int? =
        Regex("""[?&]season=(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun contentPostId(url: String): Int? =
        Regex("""/content/(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun groupedVariantPostIds(url: String): List<Int> {
        val baseId = contentPostId(url)
        val variantIds = Regex("""[?&]variants=([^&]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.split(",")
            ?.mapNotNull { v -> v.trim().toIntOrNull() }
            .orEmpty()
        return listOfNotNull(baseId).plus(variantIds).distinct()
    }

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

    // ─── Stream Variant Helpers ──────────────────────────────────────────────

    private data class MergeKey(val title: String, val type: String, val year: Int?)

    data class StreamVariant(
        val url: String,
        val audio: String? = null,
        val sourceTitle: String? = null
    )

    private data class MergedEpisodeData(
        val episodeNumber: Int,
        val title: String?,
        val variants: List<StreamVariant>
    )

    private fun extractAudioTag(title: String?): String? {
        val value = title?.lowercase() ?: return null
        return when {
            value.contains("dual audio") || value.contains("dual-audio") -> "Dual Audio"
            value.contains("multi audio") || value.contains("multi-audio") -> "Multi Audio"
            value.contains("hindi dubbed") || value.contains("hindi dub") -> "Hindi Dubbed"
            Regex("""\bhindi\b""").containsMatchIn(value) -> "Hindi"
            Regex("""\benglish\b|\beng\b""").containsMatchIn(value) -> "English"
            Regex("""\bjapanese\b|\bjap\b|\bjpn\b""").containsMatchIn(value) -> "Japanese"
            Regex("""\btamil\b""").containsMatchIn(value) -> "Tamil"
            Regex("""\btelugu\b""").containsMatchIn(value) -> "Telugu"
            Regex("""\bmalayalam\b""").containsMatchIn(value) -> "Malayalam"
            Regex("""\bkannada\b""").containsMatchIn(value) -> "Kannada"
            Regex("""\bbengali\b|\bbangla\b""").containsMatchIn(value) -> "Bengali"
            value.contains("dubbed") || Regex("""\bdub\b""").containsMatchIn(value) -> "Dubbed"
            value.contains("subbed") || Regex("""\bsub\b""").containsMatchIn(value) -> "Subbed"
            else -> null
        }
    }

    private fun normalizeMergeTitle(title: String): String {
        var v = title
        v = v.replace(
            Regex("""(?i)\b(dual[- ]?audio|multi[- ]?audio|hindi dubbed|hindi dub|dubbed|subbed|hindi|english|japanese|tamil|telugu|malayalam|kannada|bengali|bangla)\b"""),
            ""
        )
        v = v.replace(
            Regex("""(?i)\b(web[- ]?dl|webrip|bluray|hdrip|brrip|dvdrip|hdtv|hdcam|hdts|camrip|hdtc|hq|hd|uhd|1080p|720p|480p|2160p|4k|hevc|x264|x265|10bit|hdr|dv)\b"""),
            ""
        )
        v = v.replace(Regex("""[\[\(][\s\-_,]*[\]\)]"""), "")
        v = v.replace(Regex("""[\[\(].*?[\]\)]"""), "")
        v = v.replace(Regex("""[:\-–—]"""), " ")
        v = v.replace(Regex("""\s{2,}"""), " ")
        return v.trim().lowercase()
    }

    private fun mergeKeyForPost(post: Post): MergeKey {
        val rawTitle = post.name?.ifBlank { post.title } ?: post.title
        val isAnime = isAnimeContent(post.categories, post.title) || post.title.contains("anime", true)
        val cleanedTitle = if (isAnime) {
            cleanTitle(rawTitle).lowercase()
        } else {
            normalizeMergeTitle(rawTitle)
        }
        val isSeries = post.type.lowercase() == "series"
        return MergeKey(
            title = cleanedTitle,
            type = post.type.lowercase(),
            year = if (isSeries && isAnime) null else selectUntilNonInt(post.year)
        )
    }

    private fun extractEpisodeNumber(title: String?): Int? {
        if (title.isNullOrBlank()) return null
        return Regex("""(?i)\b(?:episode|ep|e)\s*\.?\s*(\d{1,4})\b""")
            .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun encodeStreamVariants(variants: List<StreamVariant>): String {
        val unique = variants.filter { v -> v.url.isNotBlank() }.distinctBy { v -> v.url }
        return if (unique.size == 1) unique.first().url else unique.toJson()
    }

    private fun parseStreamVariants(data: String): List<StreamVariant> {
        val trimmed = data.trim()
        if (!trimmed.startsWith("[")) return listOf(StreamVariant(url = data))
        return try {
            val array = JSONArray(trimmed)
            List(array.length()) { idx ->
                val item = array.getJSONObject(idx)
                StreamVariant(
                    url = item.optString("url"),
                    audio = item.optString("audio").takeIf { a -> a.isNotBlank() },
                    sourceTitle = item.optString("sourceTitle").takeIf { s -> s.isNotBlank() }
                )
            }.filter { v -> v.url.isNotBlank() }
        } catch (_: Exception) {
            listOf(StreamVariant(url = data))
        }
    }

    private fun sourceLabelForVariant(variant: StreamVariant): String {
        val audio = variant.audio ?: extractAudioTag(variant.sourceTitle)
        return if (audio.isNullOrBlank()) name else "$name [$audio]"
    }

    // ─── AniList API ─────────────────────────────────────────────────────────

    private suspend fun getAniListMeta(title: String, retryCount: Int = 2): AniListMeta? {
        repeat(retryCount) { attempt ->
            try {
                val query = buildAniListQuery(byId = false)
                val body = mapOf("query" to query, "variables" to mapOf("search" to title))
                    .toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
                val res = app.post(
                    anilistApi,
                    requestBody = body,
                    headers = mapOf("Content-Type" to "application/json"),
                    cacheTime = 3600
                )
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
        val res = app.post(
            anilistApi,
            requestBody = body,
            headers = mapOf("Content-Type" to "application/json"),
            cacheTime = 86400
        )
        AppUtils.parseJson<AniListResponse>(res.text).data?.Media
    } catch (_: Exception) {
        null
    }

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
        val trailerId = trailer?.id?.takeIf { tid -> tid.isNotBlank() } ?: return null
        if (trailerId.startsWith("http://") || trailerId.startsWith("https://")) return trailerId
        return when (trailer.site?.lowercase()) {
            "youtube"     -> "https://www.youtube.com/watch?v=$trailerId"
            "dailymotion" -> "https://www.dailymotion.com/video/$trailerId"
            else          -> "https://www.youtube.com/watch?v=$trailerId"
        }
    }

    // ─── AniZip API ──────────────────────────────────────────────────────────

    private fun parseAniZip(
        json: JSONObject,
        fallbackAnilistId: Int? = null,
        fallbackMalId: Int? = null,
        fallbackKitsuId: String? = null,
        fallbackTmdbId: String? = null
    ): AniZipFull {
        val m = json.optJSONObject("mappings")
        return AniZipFull(
            anilistId = fallbackAnilistId ?: json.optInt("anilist_id").takeIf { n -> n != 0 } ?: m?.optInt("anilist_id")?.takeIf { n -> n != 0 },
            malId     = fallbackMalId     ?: m?.optInt("mal_id")?.takeIf { n -> n != 0 },
            kitsuId   = fallbackKitsuId   ?: m?.optString("kitsu_id")?.takeIf { s -> s.isNotBlank() },
            simklId   = m?.optInt("simkl_id")?.takeIf { n -> n != 0 },
            tmdbId    = fallbackTmdbId    ?: m?.optString("themoviedb_id")?.takeIf { s -> s.isNotBlank() }
        )
    }

    private suspend fun getAniZipFullCached(anilistId: Int): AniZipFull? {
        if (aniZipFullCache.containsKey(anilistId)) return aniZipFullCache[anilistId]
        val value = try {
            val json = JSONObject(app.get("https://api.ani.zip/mappings?anilist_id=$anilistId", cacheTime = 86400).text)
            parseAniZip(json, fallbackAnilistId = anilistId)
        } catch (_: Exception) { null }
        aniZipFullCache[anilistId] = value
        return value
    }

    private suspend fun getAniZipByMalId(malId: Int): AniZipFull? = try {
        val json = JSONObject(app.get("https://api.ani.zip/mappings?mal_id=$malId", cacheTime = 86400).text)
        parseAniZip(json, fallbackMalId = malId)
    } catch (_: Exception) { null }

    private suspend fun getAniZipByTmdbId(tmdbId: Int): AniZipFull? = try {
        val json = JSONObject(app.get("https://api.ani.zip/mappings?themoviedb_id=$tmdbId", cacheTime = 86400).text)
        parseAniZip(json, fallbackTmdbId = tmdbId.toString())
    } catch (_: Exception) { null }

    private suspend fun getAniZipByKitsuId(kitsuId: String): AniZipFull? = try {
        val json = JSONObject(app.get("https://api.ani.zip/mappings?kitsu_id=$kitsuId", cacheTime = 86400).text)
        parseAniZip(json, fallbackKitsuId = kitsuId)
    } catch (_: Exception) { null }

    private suspend fun getAniZipEpisodeTitles(anilistId: Int): Map<Int, String> = try {
        val json = JSONObject(app.get("https://api.ani.zip/mappings?anilist_id=$anilistId", cacheTime = 86400).text)
        val episodes = json.optJSONObject("episodes") ?: return emptyMap()
        val map = mutableMapOf<Int, String>()
        episodes.keys().forEach { key ->
            val epNum = key.toIntOrNull() ?: return@forEach
            val epObj = episodes.optJSONObject(key) ?: return@forEach
            val titles = epObj.optJSONObject("title")
            val epTitle = titles?.optString("en")?.takeIf { s -> s.isNotBlank() }
                ?: titles?.optString("x-jat")?.takeIf { s -> s.isNotBlank() }
                ?: epObj.optString("title")?.takeIf { s -> s.isNotBlank() }
            if (epTitle != null) map[epNum] = epTitle
        }
        map
    } catch (_: Exception) { emptyMap() }

    // ─── Kitsu / MAL API ─────────────────────────────────────────────────────

    private suspend fun searchMalId(title: String): Int? = try {
        val res = app.get(
            "https://api.jikan.moe/v4/anime?q=${URLEncoder.encode(title, "UTF-8")}&limit=1",
            cacheTime = 3600
        )
        JSONObject(res.text).optJSONArray("data")?.optJSONObject(0)?.optInt("mal_id")
    } catch (_: Exception) { null }

    private suspend fun getKitsuMeta(title: String): KitsuMeta? = try {
        val res = app.get(
            "https://kitsu.io/api/edge/anime?filter[text]=${URLEncoder.encode(title, "UTF-8")}",
            headers = mapOf("Accept" to "application/vnd.api+json"),
            cacheTime = 3600
        )
        val first = JSONObject(res.text).optJSONArray("data")?.optJSONObject(0) ?: return null
        val id = first.optString("id").takeIf { s -> s.isNotBlank() }
        val attr = first.optJSONObject("attributes") ?: return KitsuMeta(id, null, null, null, null, null)
        val titles = attr.optJSONObject("titles")
        val resolvedTitle = titles?.optString("en")?.takeIf { s -> s.isNotBlank() }
            ?: titles?.optString("en_jp")?.takeIf { s -> s.isNotBlank() }
            ?: attr.optString("canonicalTitle").takeIf { s -> s.isNotBlank() }
        val poster = attr.optJSONObject("posterImage")?.let { img ->
            img.optString("original").takeIf { s -> s.isNotBlank() }
                ?: img.optString("large").takeIf { s -> s.isNotBlank() }
        }
        val cover = attr.optJSONObject("coverImage")?.let { img ->
            img.optString("original").takeIf { s -> s.isNotBlank() }
                ?: img.optString("large").takeIf { s -> s.isNotBlank() }
        }
        KitsuMeta(
            id = id,
            title = resolvedTitle,
            poster = poster,
            cover = cover,
            synopsis = attr.optString("synopsis").takeIf { s -> s.isNotBlank() },
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

    // ─── TMDB API ────────────────────────────────────────────────────────────

    private suspend fun getTmdbMeta(title: String, year: Int?, isSeries: Boolean): TmdbMeta? = try {
        val type = if (isSeries) "tv" else "movie"
        val yearParam = year?.let { y -> if (isSeries) "&first_air_date_year=$y" else "&year=$y" } ?: ""
        val searchUrl = "$tmdbApi/search/$type?api_key=$tmdbKey&query=${URLEncoder.encode(title, "UTF-8")}$yearParam&language=en-US"
        val first = AppUtils.parseJson<TmdbSearchResponse>(
            app.get(searchUrl, cacheTime = 86400).text
        ).results?.firstOrNull() ?: return null
        val logo = fetchTmdbLogo(first.id, isSeries)
        var imdbId: String? = null
        var backdrop = first.backdropPath?.let { p -> "$tmdbBackdropBase$p" }
        try {
            val detail = JSONObject(
                app.get("$tmdbApi/$type/${first.id}?api_key=$tmdbKey&append_to_response=external_ids", cacheTime = 86400).text
            )
            imdbId = detail.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { s -> s.isNotBlank() }
            val detailBackdrop = detail.optString("backdrop_path").takeIf { s -> s.isNotBlank() }
            if (detailBackdrop != null) backdrop = "$tmdbBackdropBase$detailBackdrop"
        } catch (_: Exception) { }
        TmdbMeta(
            poster   = first.posterPath?.let { p -> "$tmdbImageBase$p" },
            backdrop = backdrop,
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

    private fun resolveBackdrop(tmdbMeta: TmdbMeta?, aniListMeta: AniListMeta?, fallback: String?): String? =
        tmdbMeta?.backdrop
            ?: aniListMeta?.bannerImage
            ?: fallback
            ?: tmdbMeta?.poster
            ?: aniListMeta?.coverImage?.extraLarge

    private suspend fun fetchTmdbBackdrop(tmdbId: Int?, isSeries: Boolean): String? {
        if (tmdbId == null) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(
                app.get("$tmdbApi/$type/$tmdbId?api_key=$tmdbKey&language=en-US", cacheTime = 86400).text
            )
            json.optString("backdrop_path").takeIf { p -> p.isNotBlank() }?.let { p -> "$tmdbBackdropBase$p" }
        } catch (_: Exception) { null }
    }

    private suspend fun fetchTmdbLogo(tmdbId: Int?, isSeries: Boolean): String? {
        if (tmdbId == null) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(
                app.get("$tmdbApi/$type/$tmdbId/images?api_key=$tmdbKey", cacheTime = 86400).text
            )
            val logos = json.optJSONArray("logos") ?: return null
            for (i in 0 until logos.length()) {
                val logo = logos.getJSONObject(i)
                val path = logo.optString("file_path")
                val lang = logo.optString("iso_639_1")
                if (path.isNotBlank() && (lang == "en" || lang.isEmpty()) && !path.endsWith(".svg")) {
                    return "$tmdbImageBase$path"
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private suspend fun fetchTmdbTrailer(tmdbId: Int?, isSeries: Boolean): String? {
        if (tmdbId == null) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(
                app.get("$tmdbApi/$type/$tmdbId/videos?api_key=$tmdbKey&language=en-US", cacheTime = 86400).text
            )
            val results = json.optJSONArray("results") ?: return null
            val videos = List(results.length()) { idx -> results.getJSONObject(idx) }
            val preferred = videos.firstOrNull { v ->
                v.optString("site").equals("YouTube", true) &&
                    v.optString("type").equals("Trailer", true) &&
                    v.optBoolean("official")
            } ?: videos.firstOrNull { v ->
                v.optString("site").equals("YouTube", true) &&
                    v.optString("type").equals("Trailer", true)
            } ?: videos.firstOrNull { v ->
                v.optString("site").equals("YouTube", true) &&
                    v.optString("type").equals("Teaser", true)
            } ?: videos.firstOrNull { v ->
                v.optString("site").equals("Dailymotion", true)
            }
            val key = preferred?.optString("key")?.takeIf { k -> k.isNotBlank() } ?: return null
            when (preferred.optString("site").lowercase()) {
                "youtube"     -> "https://www.youtube.com/watch?v=$key"
                "dailymotion" -> "https://www.dailymotion.com/video/$key"
                else          -> "https://www.youtube.com/watch?v=$key"
            }
        } catch (_: Exception) { null }
    }

    private suspend fun fetchTmdbSeasonEpisodes(tmdbId: Int, seasonNum: Int): List<TmdbEpisode> = try {
        val json = JSONObject(
            app.get("$tmdbApi/tv/$tmdbId/season/$seasonNum?api_key=$tmdbKey&language=en-US", cacheTime = 86400).text
        )
        val arr = json.optJSONArray("episodes") ?: return emptyList()
        List(arr.length()) { idx ->
            val ep = arr.getJSONObject(idx)
            TmdbEpisode(
                name      = ep.optString("name"),
                overview  = ep.optString("overview"),
                stillPath = ep.optString("still_path").takeIf { s -> s.isNotBlank() },
                airDate   = ep.optString("air_date"),
                rating    = ep.optDouble("vote_average")
            )
        }
    } catch (_: Exception) { emptyList() }

    private suspend fun fetchTmdbActors(tmdbId: Int?, isSeries: Boolean): List<ActorData>? {
        if (tmdbId == null) return null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(
                app.get("$tmdbApi/$type/$tmdbId/credits?api_key=$tmdbKey", cacheTime = 86400).text
            )
            val cast = json.optJSONArray("cast") ?: return null
            (0 until minOf(cast.length(), 20)).mapNotNull { idx ->
                val person = cast.getJSONObject(idx)
                val actorName = person.optString("name")
                val image = person.optString("profile_path").takeIf { s -> s.isNotBlank() }
                    ?.let { p -> "$tmdbImageBase$p" }
                if (actorName.isNotBlank()) ActorData(Actor(actorName, image)) else null
            }
        } catch (_: Exception) { null }
    }

    // ─── Server / Post Fetching ───────────────────────────────────────────────

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

    // ─── Anime Meta Resolution ───────────────────────────────────────────────

    private suspend fun resolveAnimeMeta(title: String, year: Int? = null, isSeries: Boolean = true): ResolvedAnimeMeta {
        val cleaned = cleanTitle(title)
            .replace(Regex("""(?i)\banime\b"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        val tmdbMeta = getTmdbMeta(cleaned, year, isSeries)
        val tmdbZip = tmdbMeta?.tmdbId?.let { tmdbId -> getAniZipByTmdbId(tmdbId) }

        var aniList: AniListMeta? = tmdbZip?.anilistId?.let { alId -> getAniListMetaById(alId) }
            ?: getAniListMeta(cleaned)
            ?: getAniListMeta(title)

        var aniZip: AniZipFull? = aniList?.id?.let { alId -> getAniZipFullCached(alId) }

        val kitsu = getKitsuMetaCached(cleaned) ?: getKitsuMetaCached(title)
        val zipFromKitsu = kitsu?.id?.let { kId -> getAniZipByKitsuId(kId) }

        if (aniList == null) aniList = zipFromKitsu?.anilistId?.let { alId -> getAniListMetaById(alId) }

        val mal: Int? = aniList?.idMal
            ?: aniZip?.malId
            ?: tmdbZip?.malId
            ?: zipFromKitsu?.malId
            ?: searchMalId(cleaned)

        val zipFromMal = if ((aniList == null || aniZip == null) && mal != null) getAniZipByMalId(mal) else null

        if (aniList == null) aniList = zipFromMal?.anilistId?.let { alId -> getAniListMetaById(alId) }

        val safeAniListId = aniList?.id
        if (aniZip == null && safeAniListId != null) aniZip = getAniZipFullCached(safeAniListId)

        val tmdbFromZip: Int? = aniZip?.tmdbId?.toIntOrNull()
            ?: tmdbZip?.tmdbId?.toIntOrNull()
            ?: zipFromKitsu?.tmdbId?.toIntOrNull()
            ?: zipFromMal?.tmdbId?.toIntOrNull()

        val finalTmdb: TmdbMeta? = tmdbMeta ?: tmdbFromZip?.let { tId ->
            TmdbMeta(
                poster   = null,
                backdrop = fetchTmdbBackdrop(tId, isSeries),
                rating   = null,
                overview = null,
                logoUrl  = fetchTmdbLogo(tId, isSeries),
                imdbId   = null,
                tmdbId   = tId
            )
        }

        val displayTitle = aniList?.title?.english
            ?: aniList?.title?.romaji
            ?: kitsu?.title
            ?: cleaned

        return ResolvedAnimeMeta(
            title           = displayTitle,
            poster          = aniList?.coverImage?.extraLarge ?: aniList?.coverImage?.large ?: kitsu?.poster ?: finalTmdb?.poster,
            background      = resolveBackdrop(finalTmdb, aniList, kitsu?.cover),
            plot            = aniList?.description?.replace(Regex("<[^>]*>"), "") ?: kitsu?.synopsis ?: finalTmdb?.overview,
            score100        = aniList?.averageScore ?: kitsu?.averageRating?.toInt(),
            tags            = aniList?.genres,
            trailer         = fetchTmdbTrailer(finalTmdb?.tmdbId, isSeries)
                ?: getAniListTrailerUrl(aniList?.trailer),
            anilistEpisodes = aniList?.streamingEpisodes,
            logoUrl         = finalTmdb?.logoUrl ?: fetchTmdbLogo(tmdbFromZip, isSeries),
            actors          = aniList?.characters?.edges?.mapNotNull { edge ->
                val charName  = edge.node?.name?.full
                val charImage = edge.node?.image?.large
                charName?.let { n -> ActorData(Actor(n, charImage)) }
            },
            anilistId = aniList?.id ?: tmdbZip?.anilistId ?: zipFromKitsu?.anilistId ?: zipFromMal?.anilistId,
            malId     = aniList?.idMal ?: aniZip?.malId ?: tmdbZip?.malId ?: zipFromKitsu?.malId ?: zipFromMal?.malId ?: mal,
            kitsuId   = aniZip?.kitsuId ?: tmdbZip?.kitsuId ?: zipFromKitsu?.kitsuId ?: zipFromMal?.kitsuId ?: kitsu?.id,
            simklId   = aniZip?.simklId ?: tmdbZip?.simklId ?: zipFromKitsu?.simklId ?: zipFromMal?.simklId,
            imdbId    = finalTmdb?.imdbId
        )
    }

    private suspend fun resolveAnimeMetaCached(title: String, year: Int? = null, isSeries: Boolean = true): ResolvedAnimeMeta {
        val key = "${isSeries}|${year ?: 0}|${title.lowercase()}"
        animeMetaCache[key]?.let { cached -> return cached }
        val value = resolveAnimeMeta(title, year, isSeries)
        animeMetaCache[key] = value
        return value
    }

    // ─── Per-Season Anime Resolution ─────────────────────────────────────────

    private data class SeasonAnimeResolution(
        val meta: ResolvedAnimeMeta,
        val ids: SeasonIds,
        val aniListNode: AniListMeta?,
        val aniZip: AniZipFull?
    )

    private fun animeSeasonSearchTitles(baseTitle: String, seasonName: String?, seasonNumber: Int): List<String> {
        val base = stripSeasonSuffixForAnime(baseTitle)
        val cleanedSeasonName = seasonName?.trim()?.takeIf { s -> s.isNotBlank() }
        val genericSeasonName = cleanedSeasonName == null ||
            cleanedSeasonName.equals("Season $seasonNumber", true) ||
            cleanedSeasonName.matches(Regex("""(?i)season\s*\d+"""))

        val titles = mutableListOf<String>()
        if (!genericSeasonName && cleanedSeasonName != null) {
            titles += "$base: $cleanedSeasonName"
            titles += "$base $cleanedSeasonName"
            titles += cleanedSeasonName
        }
        titles += "$base Season $seasonNumber"
        titles += "$base Part $seasonNumber"
        titles += "$base ${seasonNumber}th Season"
        return titles.map { t -> cleanTitle(t) }.filter { t -> t.isNotBlank() }.distinct()
    }

    private suspend fun resolvedAnimeMetaFromAniListNode(
        aniList: AniListMeta,
        aniZip: AniZipFull?,
        fallback: ResolvedAnimeMeta,
        isSeries: Boolean
    ): ResolvedAnimeMeta {
        val tmdbId = aniZip?.tmdbId?.toIntOrNull()
        val tmdbMeta: TmdbMeta? = tmdbId?.let { tId ->
            TmdbMeta(
                poster   = null,
                backdrop = fetchTmdbBackdrop(tId, isSeries),
                rating   = null,
                overview = null,
                logoUrl  = fetchTmdbLogo(tId, isSeries),
                imdbId   = null,
                tmdbId   = tId
            )
        }

        val displayTitle = aniList.title?.english ?: aniList.title?.romaji ?: fallback.title

        return ResolvedAnimeMeta(
            title           = displayTitle,
            poster          = aniList.coverImage?.extraLarge ?: aniList.coverImage?.large ?: fallback.poster,
            background      = resolveBackdrop(tmdbMeta, aniList, fallback.background),
            plot            = aniList.description?.replace(Regex("<[^>]*>"), "") ?: fallback.plot,
            score100        = aniList.averageScore ?: fallback.score100,
            tags            = aniList.genres ?: fallback.tags,
            trailer         = fetchTmdbTrailer(tmdbId, isSeries)
                ?: getAniListTrailerUrl(aniList.trailer)
                ?: fallback.trailer,
            anilistEpisodes = aniList.streamingEpisodes ?: fallback.anilistEpisodes,
            logoUrl         = tmdbMeta?.logoUrl ?: fallback.logoUrl,
            actors          = aniList.characters?.edges?.mapNotNull { edge ->
                val charName  = edge.node?.name?.full
                val charImage = edge.node?.image?.large
                charName?.let { n -> ActorData(Actor(n, charImage)) }
            } ?: fallback.actors,
            anilistId = aniList.id,
            malId     = aniZip?.malId ?: aniList.idMal,
            kitsuId   = aniZip?.kitsuId,
            simklId   = aniZip?.simklId,
            imdbId    = fallback.imdbId
        )
    }

    private suspend fun resolveAnimeSeasonDynamically(
        baseTitle: String,
        seasonName: String?,
        seasonNumber: Int,
        year: Int?,
        baseMeta: ResolvedAnimeMeta
    ): SeasonAnimeResolution {
        if (seasonNumber <= 1) {
            val node = baseMeta.anilistId?.let { alId -> getAniListMetaById(alId) }
            val zip  = baseMeta.anilistId?.let { alId -> getAniZipFullCached(alId) }
            return SeasonAnimeResolution(
                meta = baseMeta,
                ids  = SeasonIds(
                    anilistId = baseMeta.anilistId,
                    malId     = zip?.malId ?: baseMeta.malId,
                    kitsuId   = zip?.kitsuId ?: baseMeta.kitsuId,
                    simklId   = zip?.simklId ?: baseMeta.simklId
                ),
                aniListNode = node,
                aniZip      = zip
            )
        }

        // Strategy 1: follow AniList SEQUEL relation chain
        var relationNode: AniListMeta? = null
        val baseNode = baseMeta.anilistId?.let { alId -> getAniListMetaById(alId) }
        if (baseNode != null) {
            var current: AniListMeta? = baseNode
            repeat(seasonNumber - 1) {
                val next = current?.relations?.edges
                    ?.firstOrNull { edge -> edge.relationType.equals("SEQUEL", ignoreCase = true) }
                    ?.node
                current = next
            }
            relationNode = current
        }

        var relationZip: AniZipFull? = null
        val relationId = relationNode?.id
        if (relationId != null && relationId != baseMeta.anilistId) {
            relationZip = getAniZipFullCached(relationId)
        }

        if (relationNode != null && relationId != null && relationId != baseMeta.anilistId) {
            val hasEpisodes = (relationNode.episodes ?: 0) > 0 || (relationNode.streamingEpisodes?.size ?: 0) > 0
            if (hasEpisodes) {
                val seasonMeta = resolvedAnimeMetaFromAniListNode(relationNode, relationZip, baseMeta, true)
                return SeasonAnimeResolution(
                    meta = seasonMeta,
                    ids  = SeasonIds(
                        anilistId = relationNode.id,
                        malId     = relationZip?.malId ?: relationNode.idMal,
                        kitsuId   = relationZip?.kitsuId,
                        simklId   = relationZip?.simklId
                    ),
                    aniListNode = relationNode,
                    aniZip      = relationZip
                )
            }
        }

        // Strategy 2: parallel candidate title search (fallback)
        val candidateTitles = animeSeasonSearchTitles(baseTitle, seasonName, seasonNumber)

        val candidates: List<Pair<AniListMeta?, AniZipFull?>> = coroutineScope {
            candidateTitles.map { candidateTitle ->
                async {
                    val aniListNode: AniListMeta? = getAniListMeta(candidateTitle)
                    val aniZip: AniZipFull? = aniListNode?.id?.let { alId -> getAniZipFullCached(alId) }
                    Pair(aniListNode, aniZip)
                }
            }.awaitAll()
        }

        val selected = candidates.firstOrNull { (aniListNode, aniZip) ->
            aniListNode?.id != null &&
                aniListNode.id != baseMeta.anilistId &&
                (
                    aniZip?.malId    != null ||
                    aniZip?.kitsuId  != null ||
                    aniZip?.simklId  != null ||
                    aniZip?.tmdbId   != null ||
                    aniListNode.idMal != null
                )
        }

        if (selected != null) {
            val selectedNode = selected.first
            val selectedZip  = selected.second
            if (selectedNode != null) {
                val seasonMeta = resolvedAnimeMetaFromAniListNode(selectedNode, selectedZip, baseMeta, true)
                return SeasonAnimeResolution(
                    meta = seasonMeta,
                    ids  = SeasonIds(
                        anilistId = selectedNode.id,
                        malId     = selectedZip?.malId ?: selectedNode.idMal,
                        kitsuId   = selectedZip?.kitsuId,
                        simklId   = selectedZip?.simklId
                    ),
                    aniListNode = selectedNode,
                    aniZip      = selectedZip
                )
            }
        }

        // Graceful fallback: clear IDs so we don't tag a different season with S1 IDs
        return SeasonAnimeResolution(
            meta = baseMeta,
            ids  = SeasonIds(),
            aniListNode = null,
            aniZip      = null
        )
    }

    // ─── Episode Stream Merging ───────────────────────────────────────────────

    private fun mergeEpisodeStreamsForSeason(
        contents: List<Content>,
        urlChecks: List<Boolean>,
        sourceTitles: List<String>
    ): List<MergedEpisodeData> {
        val variantsByEpisode = linkedMapOf<Int, MutableList<StreamVariant>>()
        val titleByEpisode    = linkedMapOf<Int, String?>()

        contents.forEachIndexed { contentIndex, content ->
            content.episodes.forEachIndexed { episodeIndex, episodeData ->
                val episodeNumber = extractEpisodeNumber(episodeData.title) ?: (episodeIndex + 1)
                val link = if (urlChecks.getOrNull(contentIndex) == true) episodeData.link
                           else linkToIp(episodeData.link)

                val audio = extractAudioTag(episodeData.title)
                    ?: extractAudioTag(sourceTitles.getOrNull(contentIndex))

                variantsByEpisode.getOrPut(episodeNumber) { mutableListOf() }
                    .add(StreamVariant(url = link, audio = audio, sourceTitle = episodeData.title))

                titleByEpisode.putIfAbsent(episodeNumber, episodeData.title)
            }
        }

        return variantsByEpisode.entries.map { entry ->
            MergedEpisodeData(
                episodeNumber = entry.key,
                title         = titleByEpisode[entry.key],
                variants      = entry.value.distinctBy { v -> v.url }
            )
        }
    }

    // ─── Search Result Builders ───────────────────────────────────────────────

    private suspend fun createSearchResult(
        post: Post,
        overrideTitle: String? = null,
        seasonIndex: Int? = null,
        posterOverride: String? = null,
        groupedPostIds: List<Int> = emptyList()
    ): SearchResponse? {
        val rawTitle = overrideTitle ?: post.name?.ifBlank { post.title } ?: post.title
        val url = makeContentUrl(post.id, seasonIndex, groupedPostIds)
        val quality = getSearchQuality((post.quality ?: post.title).lowercase())
        val isSeries = post.type == "series"
        val isAnime = isAnimeContent(post.categories, post.title) || post.title.contains("anime", true)
        val fallbackPoster = "$mainApiUrl/uploads/${post.imageSm}"
        val year = selectUntilNonInt(post.year)

        val cleanedForCache = cleanTitle(rawTitle).lowercase()
        val cachedAnimePoster = animeMetaCache["${isSeries}|${year ?: 0}|$cleanedForCache"]?.poster
        val cachedTmdbPoster  = tmdbMetaCache["${isSeries}|${year ?: 0}|$cleanedForCache"]?.poster

        return if (isAnime) {
            newAnimeSearchResponse(rawTitle, url, if (isSeries) TvType.Anime else TvType.AnimeMovie) {
                this.posterUrl = posterOverride ?: cachedAnimePoster ?: fallbackPoster
                this.quality   = quality
                addDubStatus(dubExist = isDubTitle(post.title), subExist = true)
            }
        } else if (isSeries) {
            newTvSeriesSearchResponse(rawTitle, url, TvType.TvSeries) {
                this.posterUrl = posterOverride ?: cachedTmdbPoster ?: fallbackPoster
                this.quality   = quality
            }
        } else {
            newMovieSearchResponse(rawTitle, url, TvType.Movie) {
                this.posterUrl = posterOverride ?: cachedTmdbPoster ?: fallbackPoster
                this.quality   = quality
            }
        }
    }

    /**
     * Anime series = ONE base tile (season index 0). Everything else stays stacked as a single tile.
     */
    private suspend fun toSearchResults(post: Post, groupedPostIds: List<Int> = emptyList()): List<SearchResponse> {
        val isAnime   = isAnimeContent(post.categories, post.title) || post.title.contains("anime", true)
        val baseTitle = post.name?.ifBlank { post.title } ?: post.title

        return if (post.type == "series" && isAnime) {
            listOfNotNull(
                createSearchResult(
                    post          = post,
                    overrideTitle = stripSeasonSuffixForAnime(baseTitle),
                    seasonIndex   = 0,
                    groupedPostIds = groupedPostIds
                )
            )
        } else {
            listOfNotNull(
                createSearchResult(
                    post           = post,
                    overrideTitle  = baseTitle,
                    seasonIndex    = null,
                    groupedPostIds = groupedPostIds
                )
            )
        }
    }

    private suspend fun createMergedSearchResult(posts: List<Post>): List<SearchResponse> {
        if (posts.isEmpty()) return emptyList()
        val isAnimeGroup = posts.all { isAnimeContent(it.categories, it.title) || it.title.contains("anime", true) }
        val sortedPosts = if (isAnimeGroup) {
            posts.sortedBy { post -> extractSeasonNumberFromTitle(post.name ?: post.title) }
        } else {
            posts
        }
        val primary    = sortedPosts.first()
        val groupedIds = sortedPosts.map { p -> p.id }.distinct()
        return toSearchResults(primary, groupedIds)
    }

    // ─── MainAPI Overrides ────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = try {
            app.get("$mainApiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10", verify = false, cacheTime = 60)
        } catch (_: Exception) {
            app.get("$apiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10", verify = false, cacheTime = 60)
        }

        val home = coroutineScope {
            AppUtils.parseJson<PageData>(json.text).posts
                .groupBy { post -> mergeKeyForPost(post) }
                .values
                .map { groupedPosts -> async { createMergedSearchResult(groupedPosts) } }
                .awaitAll()
                .flatten()
        }

        return newHomePageResponse(request.name, home, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val json = try {
            app.get("$mainApiUrl/api/posts?searchTerm=$encodedQuery&order=desc", verify = false, cacheTime = 60)
        } catch (_: Exception) {
            app.get("$apiUrl/api/posts?searchTerm=$encodedQuery&order=desc", verify = false, cacheTime = 60)
        }

        return coroutineScope {
            AppUtils.parseJson<PageData>(json.text).posts
                .groupBy { post -> mergeKeyForPost(post) }
                .values
                .map { groupedPosts -> async { createMergedSearchResult(groupedPosts) } }
                .awaitAll()
                .flatten()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val postIds = groupedVariantPostIds(url)

        val responses = coroutineScope {
            postIds.map { postId -> async { fetchPostResponse(postId) } }.awaitAll()
        }

        val primaryResponse = responses.first()
        val urlChecks       = responses.map { r -> r.url.contains(mainApiUrl) }
        val loadDataList    = responses.map { r -> AppUtils.parseJson<Data>(r.text) }
        val loadData        = loadDataList.first()

        val title        = loadData.name?.ifBlank { loadData.title } ?: loadData.title
        val cleanedTitle = cleanTitle(title)
        val fallbackPoster = "$apiUrl/uploads/${loadData.image}"
        val year         = selectUntilNonInt(loadData.year)
        val isAnime      = isAnimeContent(loadData.categories, title)
        val selectedSeason = selectedSeasonIndex(url)

        // ── Single-file movie / OVA ───────────────────────────────────────────

        if (loadData.type == "singleVideo") {

            val movieVariants = responses.mapIndexedNotNull { index, response ->
                val data = loadDataList.getOrNull(index) ?: return@mapIndexedNotNull null
                if (data.type != "singleVideo") return@mapIndexedNotNull null
                val movieUrl = response.parsed<Movies>().content ?: return@mapIndexedNotNull null
                val link = if (urlChecks.getOrNull(index) == true) movieUrl else linkToIp(movieUrl)
                StreamVariant(
                    url         = link,
                    audio       = extractAudioTag(data.title) ?: extractAudioTag(data.name),
                    sourceTitle = data.name ?: data.title
                )
            }

            val link     = encodeStreamVariants(movieVariants)
            val duration = getDurationFromString(loadData.watchTime)

            return if (isAnime) {
                val meta    = resolveAnimeMetaCached(cleanedTitle, year, false)
                val trailer = meta.trailer

                newAnimeLoadResponse(meta.title.ifBlank { title }, url, TvType.AnimeMovie) {
                    this.posterUrl            = meta.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta.background ?: meta.poster ?: fallbackPoster
                    this.year                = year
                    this.plot                = meta.plot ?: loadData.metaData
                    this.tags                = meta.tags
                    this.score               = Score.from100(meta.score100)
                    this.duration            = duration
                    this.actors              = meta.actors
                    this.logoUrl             = meta.logoUrl

                    trailer?.let { t -> addTrailer(t) }

                    addAniListId(meta.anilistId)
                    addMalId(meta.malId)
                    meta.kitsuId?.let { k -> addKitsuId(k) }
                    meta.simklId?.let { s -> addSimklId(s) }
                    meta.imdbId?.let { i -> addImdbId(i) }

                    addEpisodes(if (isDubTitle(title)) DubStatus.Dubbed else DubStatus.Subbed, listOf(newEpisode(link)))
                }

            } else {
                val meta    = getTmdbMetaCached(cleanedTitle, year, false)
                val trailer = fetchTmdbTrailer(meta?.tmdbId, false)
                val actors  = fetchTmdbActors(meta?.tmdbId, false)

                newMovieLoadResponse(title, url, TvType.Movie, link) {
                    this.posterUrl            = meta?.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta?.backdrop ?: meta?.poster ?: fallbackPoster
                    this.year                = year
                    this.plot                = meta?.overview ?: loadData.metaData
                    this.score               = Score.from10(meta?.rating)
                    this.duration            = duration
                    this.actors              = actors
                    this.logoUrl             = meta?.logoUrl

                    trailer?.let { t -> addTrailer(t) }

                    meta?.imdbId?.let { i -> addImdbId(i) }
                }
            }
        }

        // ── Series (Anime or regular TV) ─────────────────────────────────────

        val tvData = primaryResponse.parsed<TvSeries>()

        return if (isAnime) {

            val allParsedTv: List<TvSeries?> = responses.map { r ->
                try { r.parsed<TvSeries>() } catch (_: Exception) { null }
            }

            val isStacked = (tvData.content.size > 1)

            val allSeasons: List<Content>
            val seasonVariants: List<List<Content>>

            if (isStacked) {
                allSeasons = tvData.content
                seasonVariants = List(allSeasons.size) { sIdx ->
                    allParsedTv.mapNotNull { tv -> tv?.content?.getOrNull(sIdx) }
                }
            } else {
                allSeasons = allParsedTv.mapNotNull { tv -> tv?.content?.firstOrNull() }
                seasonVariants = allSeasons.map { content -> listOf(content) }
            }

            val targetIndex = selectedSeason ?: 0
            val currentSeasonData = allSeasons.getOrNull(targetIndex) ?: allSeasons.firstOrNull()
                ?: return newAnimeLoadResponse(title, url, TvType.Anime) {}

            val realSeasonNumber = targetIndex + 1

            val metaTitle    = titleForSeason(title, currentSeasonData.seasonName, realSeasonNumber)
            val baseMeta     = resolveAnimeMetaCached(cleanedTitle, year, true)

            val seasonResolution = resolveAnimeSeasonDynamically(
                baseTitle    = title,
                seasonName   = currentSeasonData.seasonName,
                seasonNumber = realSeasonNumber,
                year         = year,
                baseMeta     = baseMeta
            )

            val targetMeta        = seasonResolution.meta
            val seasonIds         = seasonResolution.ids
            val seasonAniListNode = seasonResolution.aniListNode
            val seasonAniZip      = seasonResolution.aniZip

            val seasonTmdbId    = seasonAniZip?.tmdbId?.toIntOrNull()

            val seasonTmdbMeta: TmdbMeta? = seasonTmdbId?.let { tId ->
                TmdbMeta(
                    poster   = null,
                    backdrop = fetchTmdbBackdrop(tId, true),
                    rating   = null,
                    overview = null,
                    logoUrl  = fetchTmdbLogo(tId, true),
                    imdbId   = null,
                    tmdbId   = tId
                )
            }

            val aniZipEpTitles: Map<Int, String> = seasonIds.anilistId
                ?.let { alId -> getAniZipEpisodeTitles(alId) }
                ?: emptyMap()

            val slotContents = seasonVariants.getOrNull(targetIndex) ?: emptyList()

            val sourceTitles = if (isStacked) {
                loadDataList.map { d -> d.name ?: d.title }
            } else {
                listOf(loadDataList.getOrNull(targetIndex)?.let { d -> d.name ?: d.title } ?: title)
            }
            val slotUrlChecks = if (isStacked) {
                urlChecks
            } else {
                listOf(urlChecks.getOrNull(targetIndex) ?: false)
            }

            val mergedEpisodes = mergeEpisodeStreamsForSeason(slotContents, slotUrlChecks, sourceTitles)

            val episodesData: List<Episode> = mergedEpisodes.mapIndexed { idx, mergedEpisode ->
                val epNum        = mergedEpisode.episodeNumber
                val aniZipTitle  = aniZipEpTitles[epNum]
                val aniListTitle = targetMeta.anilistEpisodes?.getOrNull(idx)?.title
                val serverTitle  = mergedEpisode.title
                    ?.replace(Regex("(?i)Episode\\s*\\d+"), "")
                    ?.trim()?.ifBlank { null }

                val epTitle    = aniZipTitle ?: aniListTitle ?: serverTitle ?: "Episode $epNum"
                val thumbnail  = targetMeta.anilistEpisodes?.getOrNull(idx)?.thumbnail ?: targetMeta.poster

                newEpisode(encodeStreamVariants(mergedEpisode.variants)) {
                    this.episode  = epNum
                    this.season   = 1
                    this.name     = epTitle
                    this.posterUrl = thumbnail
                }
            }

            val recommendationItems: List<SearchResponse> = allSeasons.mapIndexedNotNull { index, _ ->
                if (index == targetIndex) return@mapIndexedNotNull null
                val seasonNum   = index + 1
                val seasonTitle = titleForSeason(title, allSeasons.getOrNull(index)?.seasonName, seasonNum)
                val recUrl = if (isStacked) {
                    makeContentUrl(loadData.id, index, postIds)
                } else {
                    makeContentUrl(postIds.getOrNull(index) ?: loadData.id, 0, emptyList())
                }
                newAnimeSearchResponse(
                    seasonTitle,
                    recUrl,
                    TvType.Anime
                ) {
                    this.posterUrl = targetMeta.poster ?: fallbackPoster
                    addDubStatus(dubExist = isDubTitle(title), subExist = true)
                }
            }

            val seasonTrailer: String? = fetchTmdbTrailer(seasonTmdbMeta?.tmdbId, true)
                ?: getAniListTrailerUrl(seasonAniListNode?.trailer)
                ?: targetMeta.trailer

            val idsForCurrentSeason = if (realSeasonNumber == 1) {
                SeasonIds(
                    anilistId = seasonIds.anilistId ?: baseMeta.anilistId,
                    malId     = seasonIds.malId     ?: baseMeta.malId,
                    kitsuId   = seasonIds.kitsuId   ?: baseMeta.kitsuId,
                    simklId   = seasonIds.simklId   ?: baseMeta.simklId
                )
            } else {
                seasonIds
            }

            newAnimeLoadResponse(targetMeta.title.ifBlank { metaTitle }, url, TvType.Anime) {
                this.posterUrl = targetMeta.poster ?: fallbackPoster
                this.backgroundPosterUrl = resolveBackdrop(
                    tmdbMeta     = seasonTmdbMeta,
                    aniListMeta  = seasonAniListNode,
                    fallback     = targetMeta.background ?: targetMeta.poster ?: fallbackPoster
                )
                this.year    = year
                this.plot    = targetMeta.plot ?: loadData.metaData
                this.tags    = targetMeta.tags
                this.score   = Score.from100(targetMeta.score100)
                this.actors  = targetMeta.actors
                this.logoUrl = targetMeta.logoUrl ?: seasonTmdbMeta?.logoUrl

                seasonTrailer?.let { t -> addTrailer(t) }

                addAniListId(idsForCurrentSeason.anilistId)
                addMalId(idsForCurrentSeason.malId)
                idsForCurrentSeason.kitsuId?.let { k -> addKitsuId(k) }
                idsForCurrentSeason.simklId?.let { s -> addSimklId(s) }
                baseMeta.imdbId?.let { i -> addImdbId(i) }

                this.recommendations = recommendationItems

                addEpisodes(
                    if (isDubTitle(title)) DubStatus.Dubbed else DubStatus.Subbed,
                    episodesData
                )
            }

        } else {

            // ── Regular TV Series ────────────────────────────────────────────
            val meta    = getTmdbMetaCached(cleanedTitle, year, true)
            val tmdbId  = meta?.tmdbId
            val trailer = fetchTmdbTrailer(tmdbId, true)
            val actors  = fetchTmdbActors(tmdbId, true)

            val allSeriesVariants: List<TvSeries> = responses.mapNotNull { response ->
                try { response.parsed<TvSeries>() } catch (_: Exception) { null }
            }

            val sourceTitles = loadDataList.map { d -> d.name ?: d.title }
            val episodesData = mutableListOf<Episode>()

            tvData.content.forEachIndexed { seasonIndex, _ ->
                val seasonNum     = seasonIndex + 1
                val tmdbEpisodes  = tmdbId?.let { tId -> fetchTmdbSeasonEpisodes(tId, seasonNum) } ?: emptyList()
                val seasonContents = allSeriesVariants.mapNotNull { series -> series.content.getOrNull(seasonIndex) }
                val mergedEpisodes = mergeEpisodeStreamsForSeason(seasonContents, urlChecks, sourceTitles)

                mergedEpisodes.forEachIndexed { idx, mergedEpisode ->
                    val tmdbEp = tmdbEpisodes.getOrNull(idx)
                    episodesData.add(
                        newEpisode(encodeStreamVariants(mergedEpisode.variants)) {
                            this.episode     = mergedEpisode.episodeNumber
                            this.season      = seasonNum
                            this.name        = tmdbEp?.name?.takeIf { n -> n.isNotBlank() }
                                ?: mergedEpisode.title?.ifBlank { null }
                                ?: "Episode ${mergedEpisode.episodeNumber}"
                            this.posterUrl   = tmdbEp?.stillPath?.let { p -> "$tmdbImageBase$p" } ?: meta?.poster
                            this.description = tmdbEp?.overview
                            tmdbEp?.airDate?.let { d -> addDate(d) }
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                this.posterUrl            = meta?.poster ?: fallbackPoster
                this.backgroundPosterUrl = meta?.backdrop ?: meta?.poster ?: fallbackPoster
                this.year                = year
                this.plot                = meta?.overview ?: loadData.metaData
                this.score               = Score.from10(meta?.rating)
                this.actors              = actors
                this.logoUrl             = meta?.logoUrl

                trailer?.let { t -> addTrailer(t) }

                meta?.imdbId?.let { i -> addImdbId(i) }
            }

        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val variants = parseStreamVariants(data)
        variants.forEach { variant ->
            val sourceLabel = sourceLabelForVariant(variant)
            callback.invoke(newExtractorLink(source = sourceLabel, name = sourceLabel, url = variant.url))
        }
        return variants.isNotEmpty()
    }

    // ─── Data Models ─────────────────────────────────────────────────────────

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

    data class SeasonIds(
        val anilistId: Int? = null,
        val malId: Int? = null,
        val kitsuId: String? = null,
        val simklId: Int? = null
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
        val backdrop: String?,
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