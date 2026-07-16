package com.wizdier

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.getDurationFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.api.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

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
        TvType.OVA
    )

    override val supportedSyncNames = setOfNotNull(
        SyncIdName.Anilist,
        SyncIdName.MyAnimeList,
        runCatching { SyncIdName.valueOf("Kitsu") }.getOrNull(),
        runCatching { SyncIdName.valueOf("Simkl") }.getOrNull()
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

    private suspend fun getSeasonPoster(title: String, isAnime: Boolean, season: Int): String? {
        val meta = Companion.fetchMetadata(title, isAnime, season)
        return meta?.posterUrl
    }

    private fun selectUntilNonInt(string: String?): Int? =
        string?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val c = check?.lowercase() ?: return null
        // Ordered most-specific → least-specific so e.g. "HDCAM" matches HdCam
        // before the bare "cam" clause fires.
        return when {
            c.contains("webrip") || c.contains("web-dl") -> SearchQuality.WebRip
            c.contains("bluray")                          -> SearchQuality.BlueRay
            c.contains("hdts") || c.contains("hdcam") || c.contains("hdtc") -> SearchQuality.HdCam
            c.contains("camrip") || (c.contains("rip") && !c.contains("webrip")) -> SearchQuality.CamRip
            c.contains("dvd")                             -> SearchQuality.DVD
            c.contains("telesync")                        -> SearchQuality.Telesync
            c.contains("telecine")                        -> SearchQuality.Telecine
            c.contains("hdrip") || c.contains("hdtv") || c.contains("hd") -> SearchQuality.HD
            c.contains("cam")                             -> SearchQuality.Cam
            else -> null
        }
    }

    companion object {
        fun JSONObject.optStringOrNull(key: String): String? {
            if (isNull(key)) return null
            return optString(key, "").takeIf { it.isNotBlank() && it != "null" }
        }

        // TTL + LRU cache for metadata lookups. Previously this was a bare
        // ConcurrentHashMap with no eviction, which leaked memory forever:
        // every distinct title+season combo stayed in the map for the lifetime
        // of the process. The bounded TTL cache here auto-expires entries
        // after 10 minutes AND evicts the oldest entries when the cache
        // exceeds 256 entries, so memory stays flat even on long sessions.
        //
        // NOTE: Kotlin forbids `const val` inside an anonymous object, so we
        // use plain `val` — the values are compile-time constants in intent
        // but the compiler treats them as instance fields. The JIT inlines
        // them at call sites anyway, so there's no perf hit.
        private val metadataCache = object {
            private val TTL_MS = 10 * 60 * 1000L
            private val MAX_ENTRIES = 256
            private val store = ConcurrentHashMap<String, Pair<MetadataInfo, Long>>()
            private var sweepCounter = 0L

            operator fun get(key: String): MetadataInfo? {
                val (value, ts) = store[key] ?: return null
                if (System.currentTimeMillis() - ts > TTL_MS) {
                    store.remove(key)
                    return null
                }
                return value
            }

            operator fun set(key: String, value: MetadataInfo) {
                store[key] = value to System.currentTimeMillis()
                if (++sweepCounter % 32 == 0L) {
                    val now = System.currentTimeMillis()
                    store.entries.removeAll { (_, v) -> now - v.second > TTL_MS }
                    if (store.size > MAX_ENTRIES) {
                        store.entries
                            .sortedBy { it.value.second }
                            .take(store.size - MAX_ENTRIES)
                            .forEach { (k, _) -> store.remove(k) }
                    }
                }
            }
        }

        data class GroupedPostInfo(
            val id: Int,
            val originalTitle: String,
            val audio: String?
        )

        data class GroupedUrlData(
            val posts: List<GroupedPostInfo>,
            val cleanedTitle: String,
            val isAnime: Boolean,
            val selectedSeason: Int? = null,
            val year: Int? = null
        )

        data class EpisodeMetadata(
            val name: String?,
            val overview: String?,
            val stillPath: String?,
            val rating: Double?
        )

        data class ActorMetadata(
            val name: String,
            val role: String?,
            val image: String?,
            val charImage: String? = null
        )

        data class MetadataInfo(
            val title: String,
            val origTitle: String?,
            val posterUrl: String?,
            val backdropUrl: String?,
            val plot: String?,
            val rating: Double?,
            val year: Int?,
            val trailerUrl: String?,
            val logoUrl: String?,
            val malId: Int? = null,
            val anilistId: Int? = null,
            val kitsuId: String? = null,
            val imdbId: String? = null,
            val originalLanguage: String? = null,
            val genres: List<String>? = null,
            val genreIds: List<Int>? = null,
            val episodes: List<EpisodeMetadata>? = null,
            val actors: List<ActorMetadata>? = null
        )

        // Utility to encode GroupedUrlData into a relative URL string (prevents absolute schema pre-pending errors in Cloudstream!)
        fun encodeGroupedUrl(data: GroupedUrlData): String {
            val obj = JSONObject()
            val arr = JSONArray()
            for (p in data.posts) {
                val pObj = JSONObject()
                pObj.put("id", p.id)
                pObj.put("title", p.originalTitle)
                if (p.audio != null) pObj.put("audio", p.audio)
                arr.put(pObj)
            }
            obj.put("posts", arr)
            obj.put("cleanedTitle", data.cleanedTitle)
            obj.put("isAnime", data.isAnime)
            if (data.selectedSeason != null) {
                obj.put("selectedSeason", data.selectedSeason)
            }
            if (data.year != null) {
                obj.put("year", data.year)
            }
            val base64Data = Base64.getEncoder().encodeToString(obj.toString().toByteArray())
            return "load?data=$base64Data"
        }

        // Utility to decode GroupedUrlData from any incoming URL containing the data payload
        fun decodeGroupedUrl(url: String): GroupedUrlData? {
            try {
                if (!url.contains("load?data=")) return null
                val base64Data = url.substringAfter("load?data=")
                val jsonStr = String(Base64.getDecoder().decode(base64Data))
                val obj = JSONObject(jsonStr)
                val arr = obj.getJSONArray("posts")
                val postsList = mutableListOf<GroupedPostInfo>()
                for (i in 0 until arr.length()) {
                    val pObj = arr.getJSONObject(i)
                    postsList.add(
                        GroupedPostInfo(
                            id = pObj.getInt("id"),
                            originalTitle = pObj.getString("title"),
                            audio = pObj.optStringOrNull("audio")
                        )
                    )
                }
                return GroupedUrlData(
                    posts = postsList,
                    cleanedTitle = obj.getString("cleanedTitle"),
                    isAnime = obj.getBoolean("isAnime"),
                    selectedSeason = if (obj.has("selectedSeason")) obj.getInt("selectedSeason") else null,
                    year = if (obj.has("year")) obj.optInt("year").takeIf { it > 0 } else null
                )
            } catch (_: Exception) {
                return null
            }
        }

        fun extractYear(text: String?): Int? {
            if (text.isNullOrBlank()) return null
            return Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
                .findAll(text)
                .mapNotNull { it.value.toIntOrNull() }
                .firstOrNull { it in 1900..2035 }
        }

        fun normalizedGroupTitle(title: String): String =
            title.lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")

        // Clean titles: Uses the pre-cleaned, pre-sorted "name" field from CircleFTP directly! (Problem 1)
        fun cleanFtpTitle(postName: String?, postTitle: String): Pair<String, String?> {
            // Extract audio tags if any from the raw file title
            val audioRegex = Regex("(?i)\\b(dual[- ]?audio|multi[- ]?audio|dubbed|hindi[- ]?dubbed|eng[- ]?sub|bengali|hindi|dual|multi)\\b")
            val audioMatches = audioRegex.findAll(postTitle).map { it.value.trim() }.toList()
            val audioTag = if (audioMatches.isNotEmpty()) {
                audioMatches.joinToString(" ").uppercase()
            } else null

            // Use the beautifully sorted "name" field directly. If null or blank, fallback to cleaned title.
            var cleaned = postName?.trim().orEmpty()
            if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true) || cleaned.lowercase() == "null") {
                cleaned = postTitle.replace(Regex("\\.[a-zA-Z0-9]{2,4}$"), "") // strip extension first
                    .replace(".", " ")
                    .replace("_", " ")
                    .replace("-", " ")
                    .replace(Regex("\\b(19|20)\\d{2}\\b"), "")
                    .trim()
            }

            // Capitalize title words nicely
            cleaned = cleaned.split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

            return Pair(cleaned, audioTag)
        }

        // Fetch logo url using TMDB images
        private suspend fun fetchTmdbLogoUrl(
            tmdbAPI: String,
            apiKey: String,
            type: TvType,
            tmdbId: Int?,
            appLangCode: String?
        ): String? {
            if (tmdbId == null) return null

            val url = if (type == TvType.Movie)
                "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
            else
                "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

            val json = runCatching { JSONObject(app.get(url, timeout = 8_000).text) }.getOrNull() ?: return null
            val logos = json.optJSONArray("logos") ?: return null
            if (logos.length() == 0) return null

            val lang = appLangCode?.trim()?.lowercase()?.substringBefore("-")

            fun path(o: JSONObject) = o.optString("file_path")
            fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
            fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"

            var svgFallback: JSONObject? = null

            for (i in 0 until logos.length()) {
                val logo = logos.optJSONObject(i) ?: continue
                val p = path(logo)
                if (p.isBlank()) continue

                val l = logo.optString("iso_639_1").trim().lowercase()
                if (l == lang) {
                    if (!isSvg(logo)) return urlOf(logo)
                    if (svgFallback == null) svgFallback = logo
                }
            }
            svgFallback?.let { return urlOf(it) }

            var best: JSONObject? = null
            var bestSvg: JSONObject? = null

            fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0

            fun better(a: JSONObject?, b: JSONObject): Boolean {
                if (a == null) return true
                val aAvg = a.optDouble("vote_average", 0.0)
                val aCnt = a.optInt("vote_count", 0)
                val bAvg = b.optDouble("vote_average", 0.0)
                val bCnt = b.optInt("vote_count", 0)
                return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
            }

            for (i in 0 until logos.length()) {
                val logo = logos.optJSONObject(i) ?: continue
                if (!voted(logo)) continue

                if (isSvg(logo)) {
                    if (bestSvg == null || better(bestSvg, logo)) bestSvg = logo
                } else {
                    if (best == null || better(best, logo)) best = logo
                }
            }

            best?.let { return urlOf(it) }
            bestSvg?.let { return urlOf(it) }

            return null
        }

        // Roman numerals and anime-specific season keyword lists to match the exact season (Problem 5)
        fun getSeasonKeywords(season: Int): List<String> {
            return when (season) {
                1 -> listOf("Season 1", " 1", " 1st", " I")
                2 -> listOf("Season 2", " 2", " 2nd", " II")
                3 -> listOf("Season 3", " 3", " 3rd", " III")
                4 -> listOf("Season 4", " 4", " 4th", " IV")
                5 -> listOf("Season 5", " 5", " 5th", " V")
                6 -> listOf("Season 6", " 6", " 6th", " VI")
                7 -> listOf("Season 7", " 7", " 7th", " VII")
                8 -> listOf("Season 8", " 8", " 8th", " VIII")
                else -> emptyList()
            }
        }

        // Strict Alphanumeric Title Similarity Matcher (Issue 1)
        fun isTitleSimilar(searchTitle: String, matchTitle: String): Boolean {
            val sClean = searchTitle.lowercase().replace(Regex("[^a-z0-9]"), "").trim()
            val mClean = matchTitle.lowercase().replace(Regex("[^a-z0-9]"), "").trim()
            if (sClean.isEmpty() || mClean.isEmpty()) return false
            
            if (sClean == mClean) return true
            
            if (sClean.length <= 6) {
                return sClean == mClean
            }
            
            if (mClean.contains(sClean)) {
                val ratio = mClean.length.toDouble() / sClean.length.toDouble()
                if (ratio <= 3.5) return true
            }
            if (sClean.contains(mClean)) {
                val ratio = sClean.length.toDouble() / mClean.length.toDouble()
                if (ratio <= 3.5) return true
            }
            
            return false
        }

        // Core Metadata Fetcher from TMDB (for movies/TV) and AniList (for Anime)
        suspend fun fetchMetadata(title: String, isAnime: Boolean, season: Int? = null): MetadataInfo? {
            val targetSeason = season ?: 1
            val cacheKey = "${title.lowercase()}_${isAnime}_$targetSeason"
            metadataCache[cacheKey]?.let { return it }

            try {
                if (isAnime) {
                    val query = """
                        query (${'$'}search: String) {
                          Page(page: 1, perPage: 10) {
                            media(search: ${'$'}search, type: ANIME) {
                              id
                              idMal
                              title {
                                romaji
                                english
                                native
                              }
                              coverImage {
                                extraLarge
                                large
                              }
                              bannerImage
                              description
                              averageScore
                              genres
                              episodes
                              characters(page: 1, perPage: 10) {
                                edges {
                                  node {
                                    name {
                                      full
                                    }
                                    image {
                                      large
                                    }
                                  }
                                  voiceActors(language: JAPANESE) {
                                    name {
                                      full
                                    }
                                    image {
                                      large
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                    """.trimIndent()

                    val variablesObj = JSONObject()
                    variablesObj.put("search", title)

                    val requestObj = JSONObject()
                    requestObj.put("query", query)
                    requestObj.put("variables", variablesObj)

                    val requestBody = requestObj.toString().toRequestBody("application/json".toMediaTypeOrNull())

                    val resText = app.post("https://graphql.anilist.co", requestBody = requestBody, timeout = 10_000).text
                    val json = JSONObject(resText)
                    val mediaList = json.optJSONObject("data")
                        ?.optJSONObject("Page")
                        ?.optJSONArray("media")

                    var bestMedia: JSONObject? = null
                    if (mediaList != null && mediaList.length() > 0) {
                        if (targetSeason == 1) {
                            for (i in 0 until mediaList.length()) {
                                val media = mediaList.getJSONObject(i)
                                val eng = media.optJSONObject("title")?.optString("english", "")?.lowercase() ?: ""
                                val rom = media.optJSONObject("title")?.optString("romaji", "")?.lowercase() ?: ""
                                
                                // Strict verification that AniList match actually corresponds to searched title (Issue 1)
                                if (!isTitleSimilar(title, eng) && !isTitleSimilar(title, rom)) {
                                    continue
                                }

                                if (eng.contains("season 2") || eng.contains("season 3") || eng.contains("season 4") ||
                                    rom.contains("season 2") || rom.contains("season 3") || rom.contains("season 4") ||
                                    eng.contains(" ii") || eng.contains(" iii") || rom.contains(" ii") || rom.contains(" iii")) {
                                    continue
                                }
                                bestMedia = media
                                break
                            }
                            if (bestMedia == null) {
                                val fallback = mediaList.getJSONObject(0)
                                val eng = fallback.optJSONObject("title")?.optString("english", "") ?: ""
                                val rom = fallback.optJSONObject("title")?.optString("romaji", "") ?: ""
                                if (isTitleSimilar(title, eng) || isTitleSimilar(title, rom)) {
                                    bestMedia = fallback
                                }
                            }
                        } else {
                            val keywords = getSeasonKeywords(targetSeason)
                            for (i in 0 until mediaList.length()) {
                                val media = mediaList.getJSONObject(i)
                                val eng = media.optJSONObject("title")?.optString("english", "")?.lowercase() ?: ""
                                val rom = media.optJSONObject("title")?.optString("romaji", "")?.lowercase() ?: ""
                                
                                if (!isTitleSimilar(title, eng) && !isTitleSimilar(title, rom)) {
                                    continue
                                }

                                val matches = keywords.any { kw -> eng.contains(kw.lowercase()) || rom.contains(kw.lowercase()) }
                                if (matches) {
                                    bestMedia = media
                                    break
                                }
                            }
                            
                            // High-Intelligence Season Fallback algorithm (Issue 1: Fallback downwards to highest available season!)
                            if (bestMedia == null && targetSeason > 1) {
                                for (s in (targetSeason - 1) downTo 1) {
                                    val subKeywords = getSeasonKeywords(s)
                                    for (i in 0 until mediaList.length()) {
                                        val media = mediaList.getJSONObject(i)
                                        val eng = media.optJSONObject("title")?.optString("english", "")?.lowercase() ?: ""
                                        val rom = media.optJSONObject("title")?.optString("romaji", "")?.lowercase() ?: ""
                                        
                                        if (!isTitleSimilar(title, eng) && !isTitleSimilar(title, rom)) {
                                            continue
                                        }

                                        val matches = subKeywords.any { kw -> eng.contains(kw.lowercase()) || rom.contains(kw.lowercase()) }
                                        if (matches) {
                                            bestMedia = media
                                            break
                                        }
                                    }
                                    if (bestMedia != null) break
                                }
                            }
                            
                            if (bestMedia == null) {
                                val fallback = mediaList.getJSONObject(0)
                                val eng = fallback.optJSONObject("title")?.optString("english", "") ?: ""
                                val rom = fallback.optJSONObject("title")?.optString("romaji", "") ?: ""
                                if (isTitleSimilar(title, eng) || isTitleSimilar(title, rom)) {
                                    bestMedia = fallback
                                }
                            }
                        }
                    }

                    if (bestMedia != null) {
                        val aniId = bestMedia.optInt("id")
                        val malId = bestMedia.optInt("idMal").takeIf { it != 0 }
                        val engTitle = bestMedia.optJSONObject("title")?.optStringOrNull("english")
                        val romajiTitle = bestMedia.optJSONObject("title")?.optString("romaji")
                        val nativeTitle = bestMedia.optJSONObject("title")?.optString("native")
                        val poster = bestMedia.optJSONObject("coverImage")?.optString("extraLarge")
                            ?: bestMedia.optJSONObject("coverImage")?.optString("large")
                        val banner = bestMedia.optString("bannerImage")
                        val plot = bestMedia.optStringOrNull("description")
                        val score = bestMedia.optDouble("averageScore", 0.0).takeIf { it > 0.0 }
                        
                        var kitsuId: String? = null
                        var tmdbId: Int? = null
                        var imdbId: String? = null
                        
                        try {
                            val aniZipText = app.get("https://api.ani.zip/mappings?anilist_id=$aniId", timeout = 8_000).text
                            val aniZip = JSONObject(aniZipText)
                            val mappings = aniZip.optJSONObject("mappings")
                            kitsuId = mappings?.optStringOrNull("kitsu_id")
                            val rawTmdb = mappings?.optStringOrNull("themoviedb_id")
                            tmdbId = rawTmdb?.toIntOrNull()
                        } catch (_: Exception) {}

                        if (tmdbId == null) {
                            try {
                                val searchUrl = "https://api.themoviedb.org/3/search/tv?api_key=98ae14df2b8d8f8f8136499daf79f0e0&query=${URLEncoder.encode(title, "UTF-8")}"
                                val searchJson = JSONObject(app.get(searchUrl, timeout = 8_000).text)
                                val results = searchJson.optJSONArray("results")
                                if (results != null && results.length() > 0) {
                                    tmdbId = results.getJSONObject(0).optInt("id")
                                }
                            } catch (_: Exception) {}
                        }

                        var logoUrl: String? = null
                        val epList = mutableListOf<EpisodeMetadata>()
                        val castList = mutableListOf<ActorMetadata>()
                        var trailerUrl: String? = null
                        if (tmdbId != null) {
                            // Run logo, season-episodes, and tv-details fetches
                            // concurrently instead of sequentially — cuts metadata
                            // wall-clock time by ~3×.
                            val (logoResult, epsResult, detailsResult) = coroutineScope {
                                val logoA = async {
                                    runCatching {
                                        fetchTmdbLogoUrl("https://api.themoviedb.org/3", "98ae14df2b8d8f8f8136499daf79f0e0", TvType.TvSeries, tmdbId, "en")
                                    }.getOrNull()
                                }
                                val epsA = async {
                                    runCatching {
                                        val seasonUrl = "https://api.themoviedb.org/3/tv/$tmdbId/season/$targetSeason?api_key=98ae14df2b8d8f8f8136499daf79f0e0"
                                        val seasonRes = JSONObject(app.get(seasonUrl, timeout = 8_000).text)
                                        val epArr = seasonRes.optJSONArray("episodes")
                                        val list = mutableListOf<EpisodeMetadata>()
                                        if (epArr != null) {
                                            for (i in 0 until epArr.length()) {
                                                val epObj = epArr.getJSONObject(i)
                                                val stillPath = epObj.optString("still_path", "")
                                                val stillUrl = if (stillPath.isNotEmpty() && stillPath != "null") "https://image.tmdb.org/t/p/original$stillPath" else null
                                                list.add(
                                                    EpisodeMetadata(
                                                        name = epObj.optStringOrNull("name"),
                                                        overview = epObj.optStringOrNull("overview"),
                                                        stillPath = stillUrl,
                                                        rating = epObj.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
                                                    )
                                                )
                                            }
                                        }
                                        list
                                    }.getOrDefault(emptyList())
                                }
                                val detailsA = async {
                                    runCatching {
                                        val tvDetailsUrl = "https://api.themoviedb.org/3/tv/$tmdbId?api_key=98ae14df2b8d8f8f8136499daf79f0e0&append_to_response=external_ids,videos"
                                        val tvDetails = JSONObject(app.get(tvDetailsUrl, timeout = 8_000).text)
                                        val extIds = tvDetails.optJSONObject("external_ids")
                                        val id = extIds?.optStringSafe("imdb_id") ?: tvDetails.optStringSafe("imdb_id")
                                        var tr: String? = null
                                        val videos = tvDetails.optJSONObject("videos")?.optJSONArray("results")
                                        if (videos != null) {
                                            for (i in 0 until videos.length()) {
                                                val video = videos.getJSONObject(i)
                                                if (video.optString("type") == "Trailer" && video.optString("site") == "YouTube") {
                                                    tr = "https://www.youtube.com/watch?v=${video.getString("key")}"
                                                    break
                                                }
                                            }
                                        }
                                        Pair(id, tr)
                                    }.getOrNull()
                                }
                                Triple(logoA.await(), epsA.await(), detailsA.await())
                            }
                            logoUrl = logoResult
                            epList.addAll(epsResult)
                            detailsResult?.let { (id, tr) ->
                                imdbId = id
                                trailerUrl = tr
                            }
                        }

                        // Parse both the character avatar and voice artist avatar & names from AniList (Feature 1!)
                        val charObj = bestMedia.optJSONObject("characters")
                        val edgesArr = charObj?.optJSONArray("edges")
                        if (edgesArr != null) {
                            for (i in 0 until edgesArr.length()) {
                                val edge = edgesArr.getJSONObject(i)
                                val nodeObj = edge.optJSONObject("node")
                                val charName = nodeObj?.optJSONObject("name")?.optString("full", "") ?: ""
                                val charImg = nodeObj?.optJSONObject("image")?.optStringOrNull("large")
                                
                                val vaArr = edge.optJSONArray("voiceActors")
                                if (vaArr != null && vaArr.length() > 0) {
                                    val va = vaArr.getJSONObject(0)
                                    val vaName = va.optJSONObject("name")?.optString("full", "") ?: ""
                                    val vaImage = va.optJSONObject("image")?.optStringOrNull("large")
                                    if (vaName.isNotEmpty()) {
                                        castList.add(ActorMetadata(vaName, charName, vaImage, charImg))
                                    }
                                }
                            }
                        }



                        val genres = mutableListOf<String>()
                        val genresArr = bestMedia.optJSONArray("genres")
                        if (genresArr != null) {
                            for (i in 0 until genresArr.length()) {
                                genres.add(genresArr.getString(i))
                            }
                        }

                        val metadata = MetadataInfo(
                            title = engTitle ?: romajiTitle ?: nativeTitle ?: title,
                            origTitle = romajiTitle ?: nativeTitle,
                            posterUrl = poster,
                            backdropUrl = banner,
                            plot = plot,
                            rating = score,
                            year = null,
                            trailerUrl = trailerUrl,
                            logoUrl = logoUrl,
                            malId = malId,
                            anilistId = aniId,
                            kitsuId = kitsuId,
                            imdbId = imdbId,
                            genres = genres,
                            episodes = epList,
                            actors = castList
                        )
                        metadataCache[cacheKey] = metadata
                        return metadata
                    }
                } else {
                    val yearMatch = Regex("\\b(19|20)\\d{2}\\b").find(title)
                    val extractedYear = yearMatch?.value?.toIntOrNull()
                    val cleanSearchTitle = title.replace(Regex("\\b(19|20)\\d{2}\\b"), "").trim()

                    val searchUrl = "https://api.themoviedb.org/3/search/multi?api_key=98ae14df2b8d8f8f8136499daf79f0e0&query=${URLEncoder.encode(cleanSearchTitle, "UTF-8")}" +
                            if (extractedYear != null) "&year=$extractedYear" else ""

                    val searchResText = app.get(searchUrl, timeout = 8_000).text
                    val searchJson = JSONObject(searchResText)
                    val results = searchJson.optJSONArray("results")
                    val firstResult = results?.optJSONObject(0)

                    if (firstResult != null) {
                        val tmdbId = firstResult.optInt("id")
                        val mediaType = firstResult.optString("media_type") // "movie" or "tv"

                        // Fire off the details call AND (for TV) the per-season
                        // episodes call concurrently. The previous code awaited
                        // them sequentially, so metadata wall-clock time was
                        // 2× a single TMDB round-trip. Now it's 1×.
                        val (detailsText, seasonText) = coroutineScope {
                            val detailsA = async {
                                runCatching {
                                    app.get(
                                        "https://api.themoviedb.org/3/$mediaType/$tmdbId?api_key=98ae14df2b8d8f8f8136499daf79f0e0&append_to_response=alternative_titles,credits,external_ids,videos,recommendations,images",
                                        timeout = 8_000
                                    ).text
                                }.getOrNull()
                            }
                            val seasonA = if (mediaType == "tv") async {
                                runCatching {
                                    app.get(
                                        "https://api.themoviedb.org/3/tv/$tmdbId/season/$targetSeason?api_key=98ae14df2b8d8f8f8136499daf79f0e0",
                                        timeout = 8_000
                                    ).text
                                }.getOrNull()
                            } else null
                            Pair(detailsA.await(), seasonA?.await())
                        }
                        val details = detailsText?.let { runCatching { JSONObject(it) }.getOrNull() }
                            ?: return null

                        val displayTitle = details.optString("title", details.optString("name", title))
                        val posterPath = details.optStringOrNull("poster_path")
                        val backdropPath = details.optStringOrNull("backdrop_path")
                        val overview = details.optStringOrNull("overview")
                        val rating = details.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
                        
                        val releaseDate = details.optString("release_date", details.optString("first_air_date", ""))
                        val year = releaseDate.split("-").firstOrNull()?.toIntOrNull()

                        val poster = if (posterPath != null) "https://image.tmdb.org/t/p/original$posterPath" else null
                        val backdrop = if (backdropPath != null) "https://image.tmdb.org/t/p/original$backdropPath" else null

                        var trailerUrl: String? = null
                        val videos = details.optJSONObject("videos")?.optJSONArray("results")
                        if (videos != null) {
                            for (i in 0 until videos.length()) {
                                val video = videos.getJSONObject(i)
                                if (video.optString("type") == "Trailer" && video.optString("site") == "YouTube") {
                                    trailerUrl = "https://www.youtube.com/watch?v=${video.getString("key")}"
                                    break
                                }
                            }
                        }

                        // Logo: the details call already fetched /images via
                        // append_to_response, so reuse it instead of issuing a
                        // second /images request. This saves one round-trip per
                        // load on TV series and movies.
                        val logoUrl: String? = details.optJSONObject("images")
                            ?.optJSONArray("logos")
                            ?.let { logos -> pickBestTmdbLogo(logos, "en") }

                        val extIds = details.optJSONObject("external_ids")
                        val imdbId = extIds?.optStringSafe("imdb_id") ?: details.optStringSafe("imdb_id")


                        val epList = mutableListOf<EpisodeMetadata>()
                        var seasonPoster: String? = null
                        var seasonPlot: String? = null
                        if (mediaType == "tv" && seasonText != null) {
                            runCatching {
                                val seasonRes = JSONObject(seasonText)
                                val sPosterPath = seasonRes.optStringOrNull("poster_path")
                                if (sPosterPath != null) {
                                    seasonPoster = "https://image.tmdb.org/t/p/original$sPosterPath"
                                }
                                seasonPlot = seasonRes.optStringOrNull("overview")

                                val epArr = seasonRes.optJSONArray("episodes")
                                if (epArr != null) {
                                    for (i in 0 until epArr.length()) {
                                        val epObj = epArr.getJSONObject(i)
                                        val stillPath = epObj.optString("still_path", "")
                                        val stillUrl = if (stillPath.isNotEmpty() && stillPath != "null") "https://image.tmdb.org/t/p/original$stillPath" else null
                                        epList.add(
                                            EpisodeMetadata(
                                                name = epObj.optStringOrNull("name"),
                                                overview = epObj.optStringOrNull("overview"),
                                                stillPath = stillUrl,
                                                rating = epObj.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        val genres = mutableListOf<String>()
                        val genreIds = mutableListOf<Int>()
                        val genresArr = details.optJSONArray("genres")
                        if (genresArr != null) {
                            for (i in 0 until genresArr.length()) {
                                val gObj = genresArr.getJSONObject(i)
                                genres.add(gObj.optString("name"))
                                genreIds.add(gObj.optInt("id"))
                            }
                        }

                        // Parse actors for TV Shows and Movies
                        val castList = mutableListOf<ActorMetadata>()
                        val castArr = details.optJSONObject("credits")?.optJSONArray("cast")
                        if (castArr != null) {
                            for (i in 0 until castArr.length()) {
                                val castObj = castArr.getJSONObject(i)
                                val name = castObj.optString("name", "")
                                val role = castObj.optStringOrNull("character")
                                val profilePath = castObj.optString("profile_path", "")
                                val imageUrl = if (profilePath.isNotEmpty() && profilePath != "null") "https://image.tmdb.org/t/p/original$profilePath" else null
                                if (name.isNotEmpty()) {
                                    castList.add(ActorMetadata(name, role, imageUrl))
                                }
                            }
                        }

                        val metadata = MetadataInfo(
                            title = displayTitle,
                            origTitle = details.optStringOrNull("original_title") ?: details.optStringOrNull("original_name"),
                            posterUrl = seasonPoster ?: poster,
                            backdropUrl = backdrop,
                            plot = seasonPlot ?: overview,
                            rating = rating,
                            year = year,
                            trailerUrl = trailerUrl,
                            logoUrl = logoUrl,
                            imdbId = imdbId,
                            originalLanguage = details.optStringOrNull("original_language"),
                            kitsuId = null,
                            anilistId = null,
                            malId = null,
                            genres = genres,
                            genreIds = genreIds,
                            episodes = epList,
                            actors = castList
                        )
                        metadataCache[cacheKey] = metadata
                        return metadata
                    }
                }
            } catch (e: Exception) {
                Log.e("CircleFtpMetadata", "Failed to fetch metadata for $title: ${e.message}")
            }

            return null
        }

        // Unified High-Intelligence Metadata Routing Engine (Problem 2 & 5)
        suspend fun fetchUnifiedMetadata(title: String, season: Int, isAnime: Boolean): Pair<MetadataInfo, Boolean> {
            if (isAnime) {
                val animeMeta = fetchMetadata(title, isAnime = true, season = season)
                if (animeMeta != null && animeMeta.malId != null) {
                    return Pair(animeMeta, true)
                }
            }
            
            val movieMeta = fetchMetadata(title, isAnime = false, season = season)
            if (movieMeta != null) {
                val resolvedAnime = isAnime || (movieMeta.genres?.contains("16") == true && 
                              (movieMeta.originalLanguage in setOf("ja", "zh", "ko") || 
                               movieMeta.origTitle?.contains(Regex("[\\u3000-\\u303f\\u3040-\\u309f\\u30a0-\\u30ff\\uff00-\\uff9f\\u4e00-\\u9faf\\u3400-\\u4dbf]")) == true || 
                               movieMeta.title.contains("anime", true)))
                if (resolvedAnime) {
                    val retryAnime = fetchMetadata(movieMeta.title, isAnime = true, season = season)
                    if (retryAnime != null) {
                        return Pair(retryAnime, true)
                    }
                }
                return Pair(movieMeta, resolvedAnime)
            }
            
            return Pair(
                MetadataInfo(
                    title = title,
                    origTitle = null,
                    posterUrl = null,
                    backdropUrl = null,
                    plot = null,
                    rating = null,
                    year = null,
                    trailerUrl = null,
                    logoUrl = null
                ),
                isAnime
            )
        }

        fun JSONObject.optStringSafe(key: String): String? {
            if (this.isNull(key)) return null
            val value = this.optString(key)
            if (value == "null" || value.trim().lowercase() == "null") return null
            return value
        }

        /**
         * Pick the best TMDB logo from a `logos` JSON array. Prefers logos in
         * the user's language, then non-SVG logos (better compatibility), then
         * the highest-voted logo. Mirrors the behaviour of `fetchTmdbLogoUrl`
         * but operates on an already-fetched array, so callers that already
         * pulled `images` via `append_to_response` don't need a second HTTP
         * round-trip.
         */
        private fun pickBestTmdbLogo(logos: JSONArray, preferredLang: String?): String? {
            if (logos.length() == 0) return null
            val lang = preferredLang?.trim()?.lowercase()?.substringBefore("-")
            var enSvg: JSONObject? = null
            var anyNonSvg: JSONObject? = null
            var anySvg: JSONObject? = null

            for (i in 0 until logos.length()) {
                val l = logos.optJSONObject(i) ?: continue
                val path = l.optString("file_path", "")
                if (path.isBlank()) continue
                val lLang = l.optString("iso_639_1").trim().lowercase()
                val isSvg = path.endsWith(".svg", true)
                if (lLang == lang && !isSvg) {
                    return "https://image.tmdb.org/t/p/w500$path"
                }
                if (lLang == lang && isSvg && enSvg == null) enSvg = l
                if (!isSvg && anyNonSvg == null) anyNonSvg = l
                if (isSvg && anySvg == null) anySvg = l
            }
            return (enSvg ?: anyNonSvg ?: anySvg)
                ?.optString("file_path")
                ?.takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w500$it" }
        }

        // Highly accurate, zero-network anime detection using category mapping (Issue 2)
        fun isPostAnime(categoriesArr: JSONArray?, postTitle: String): Boolean {
            val titleLower = postTitle.lowercase()
            if (titleLower.contains("anime") || titleLower.contains("animation") || titleLower.contains("cartoon")) {
                return true
            }
            if (categoriesArr != null) {
                for (i in 0 until categoriesArr.length()) {
                    val catObj = categoriesArr.getJSONObject(i)
                    val catId = catObj.optInt("id")
                    val catName = catObj.optString("name", "").lowercase()
                    if (catId == 21 || catId == 1 || catName.contains("anime") || catName.contains("animation") || catName.contains("cartoon")) {
                        return true
                    }
                }
            }
            return false
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val text = CircleFtpHttp.fetchWithFallback(
            primary = "$mainApiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10",
            fallback = "$apiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10"
        ) ?: ""
        
        val postsList = parsePostsJson(text)
        val home = groupAndMapPosts(postsList)
        return newHomePageResponse(request.name, home, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val text = CircleFtpHttp.fetchWithFallback(
            primary = "$mainApiUrl/api/posts?searchTerm=$query&order=desc",
            fallback = "$apiUrl/api/posts?searchTerm=$query&order=desc"
        ) ?: ""
        val postsList = parsePostsJson(text)
        return groupAndMapPosts(postsList)
    }

    /** Shared JSON→Post parser used by both getMainPage and search. */
    private fun parsePostsJson(text: String): List<Post> {
        if (text.isBlank()) return emptyList()
        val postsList = mutableListOf<Post>()
        try {
            val jsonObj = JSONObject(text)
            val postsArr = jsonObj.optJSONArray("posts")
            if (postsArr != null) {
                for (i in 0 until postsArr.length()) {
                    val pObj = postsArr.getJSONObject(i)
                    postsList.add(
                        Post(
                            id = pObj.getInt("id"),
                            type = pObj.getString("type"),
                            imageSm = pObj.getString("imageSm"),
                            title = pObj.getString("title"),
                            name = pObj.optStringSafe("name"),
                            categories = pObj.optJSONArray("categories")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("CircleFtp", "Failed to parse posts JSON: ${e.message}")
        }
        return postsList
    }

    // High performance post grouping and instant search result builder (Problem 4: instant mapping!)
    private suspend fun groupAndMapPosts(posts: List<Post>): List<SearchResponse> = coroutineScope {
        val grouped = posts.groupBy { post ->
            val (cleanedTitle, _) = cleanFtpTitle(post.name, post.title)
            val isAnime = isPostAnime(post.categories, post.title)
            val year = extractYear(post.title) ?: extractYear(post.name)

            // Important: same-name movies/remakes must not merge unless their
            // year also matches (e.g. Dune 1984 vs Dune 2021). Multi-season
            // anime intentionally keeps the old title-only grouping so the
            // season-splitting logic below continues to work exactly as before.
            buildString {
                append(normalizedGroupTitle(cleanedTitle))
                if (!isAnime && year != null) append("|").append(year)
            }
        }

        grouped.values.map { postGroup ->
            val mainPost = postGroup.first()
            val (cleanedTitle, _) = cleanFtpTitle(mainPost.name, mainPost.title)
            
            // Highly accurate anime detection mapping (Issue 2)
            val isAnime = isPostAnime(mainPost.categories, mainPost.title)
            val groupYear = if (!isAnime) {
                postGroup.mapNotNull { extractYear(it.title) ?: extractYear(it.name) }.distinct().singleOrNull()
            } else null

            val postsInfo = postGroup.map { post ->
                val (_, audio) = cleanFtpTitle(post.name, post.title)
                GroupedPostInfo(post.id, post.title, audio)
            }

            val groupedData = GroupedUrlData(
                posts = postsInfo,
                cleanedTitle = cleanedTitle,
                isAnime = isAnime,
                year = groupYear
            )

            val groupedUrl = encodeGroupedUrl(groupedData)
            val posterUrl = if (mainPost.imageSm.isNotBlank() && mainPost.imageSm != "null") "$mainApiUrl/uploads/${mainPost.imageSm}" else null
            val checkTitleLower = mainPost.title.lowercase()
            val quality = getSearchQuality(checkTitleLower)

            val responseType = when {
                isAnime -> if (mainPost.type == "singleVideo") TvType.AnimeMovie else TvType.Anime
                mainPost.type == "singleVideo" -> TvType.Movie
                else -> TvType.TvSeries
            }

            if (isAnime) {
                newAnimeSearchResponse(cleanedTitle, groupedUrl, responseType) {
                    this.posterUrl = posterUrl
                    this.year = groupYear
                    this.quality = quality
                    addDubStatus(      
                        dubExist = when {
                            "dubbed" in checkTitleLower -> true      
                            "dual audio" in checkTitleLower -> true      
                            "multi audio" in checkTitleLower -> true
                            else -> false      
                        },      
                        subExist = false      
                    )
                }
            } else if (responseType == TvType.TvSeries) {
                newTvSeriesSearchResponse(cleanedTitle, groupedUrl, responseType) {
                    this.posterUrl = posterUrl
                    this.year = groupYear
                    this.quality = quality
                }
            } else {
                newMovieSearchResponse(cleanedTitle, groupedUrl, responseType) {
                    this.posterUrl = posterUrl
                    this.year = groupYear
                    this.quality = quality
                }
            }
        }
    }

    private suspend fun getPostDetails(id: Int): JSONObject {
        val text = CircleFtpHttp.fetchWithFallback(
            primary = "$mainApiUrl/api/posts/$id",
            fallback = "$apiUrl/api/posts/$id"
        ) ?: throw ErrorLoadingException("Failed to load post $id")
        return JSONObject(text)
    }

    override suspend fun load(url: String): LoadResponse {
        val urlCheck = !url.contains(apiUrl)

        var groupedData = decodeGroupedUrl(url)
        if (groupedData == null) {
            val id = url.substringAfterLast("/").toIntOrNull()
            if (id != null) {
                val detailsObj = getPostDetails(id)
                val title = detailsObj.optString("title")
                val name = detailsObj.optStringSafe("name")
                
                val cats = detailsObj.optJSONArray("categories")
                val isAnime = isPostAnime(cats, title)

                val (cleanedTitle, audio) = cleanFtpTitle(name, title)
                groupedData = GroupedUrlData(
                    posts = listOf(GroupedPostInfo(id, title, audio)),
                    cleanedTitle = cleanedTitle,
                    isAnime = isAnime,
                    year = if (!isAnime) extractYear(title) else null
                )
            } else {
                throw ErrorLoadingException("Invalid URL: $url")
            }
        }

        val group = groupedData
        val cleanedTitle = group.cleanedTitle
        val selectedSeason = group.selectedSeason ?: 1
        val metadataTitle = group.year?.let { "$cleanedTitle $it" } ?: cleanedTitle

        val postsDetails = coroutineScope {
            group.posts.map { post ->
                async {
                    try {
                        getPostDetails(post.id) to post.audio
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        if (postsDetails.isEmpty()) {
            throw ErrorLoadingException("Failed loading details for $cleanedTitle")
        }

        // Fetch high-intelligence metadata and auto-detect isAnime! (Problem 2 & 5)
        val (metadata, isAnime) = fetchUnifiedMetadata(metadataTitle, selectedSeason, group.isAnime)

        val finalTitle = metadata.title
        val poster = metadata.posterUrl ?: postsDetails.first().first.optStringOrNull("image")?.let { "$apiUrl/uploads/$it" }
        val backdrop = metadata.backdropUrl
        val plot = metadata.plot ?: postsDetails.first().first.optString("metaData", "")
        val year = metadata.year ?: group.year ?: selectUntilNonInt(postsDetails.first().first.optString("year"))
        val rating = metadata.rating
        val trailer = metadata.trailerUrl
        val logo = metadata.logoUrl

        val mainPostObj = postsDetails.first().first
        val mainPostType = mainPostObj.optString("type")

        val tvType = if (isAnime) {
            if (mainPostType == "singleVideo") TvType.AnimeMovie else TvType.Anime
        } else {
            if (mainPostType == "singleVideo") TvType.Movie else TvType.TvSeries
        }

        val recommendationsList = mutableListOf<SearchResponse>()

        // Map ActorMetadata to Cloudstream's ActorData (Actors Metadata!) (Supports both Character avatar & Voice Actor avatar!)
        val actorsList = metadata.actors?.map { actor ->
            if (actor.charImage != null) {
                // For Anime: Load Character name/avatar and Voice Artist name/avatar (Features 1 & 2!)
                ActorData(
                    actor = Actor(actor.role ?: "Unknown Character", actor.charImage),
                    roleString = "Voice Artist",
                    voiceActor = Actor(actor.name, actor.image)
                )
            } else {
                // For TV/Movies
                ActorData(
                    actor = Actor(actor.name, actor.image),
                    roleString = actor.role
                )
            }
        } ?: emptyList()

        if (mainPostType == "singleVideo") {
            val movieLinks = mutableListOf<JSONObject>()
            for ((postObj, audio) in postsDetails) {
                val movieUrl = postObj.optString("content")
                if (movieUrl.isNotEmpty()) {
                    val link = if (urlCheck) movieUrl else linkToIp(movieUrl)
                    val linkObj = JSONObject()
                    linkObj.put("url", link)
                    if (audio != null) linkObj.put("audio", audio)
                    movieLinks.add(linkObj)
                }
            }

            val movieDataString = "circleftp://movie?data=" + Base64.getEncoder().encodeToString(movieLinks.toString().toByteArray())
            val duration = getDurationFromString(mainPostObj.optString("watchTime"))

            return newMovieLoadResponse(finalTitle, url, tvType, movieDataString) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.duration = duration
                this.score = rating?.let { Score.from10(it) }
                this.recommendations = recommendationsList
                this.actors = actorsList
                this.tags = metadata.genres
                
                // Trackers mapping (Problem 2: Non-anime must NOT have MAL, Alist, Kitsu. Simkl is on for all via IMDb!)
                try { metadata.imdbId?.let { addImdbId(it) } } catch(_: Throwable){}
                if (isAnime) {
                    try { metadata.malId?.let { addMalId(it) } } catch(_: Throwable){}
                    try { metadata.anilistId?.let { addAniListId(it) } } catch(_: Throwable){}
                    try { metadata.kitsuId?.let { addKitsuId(it) } } catch(_: Throwable){}
                }
                try { trailer?.let { addTrailer(it) } } catch(_: Throwable){}
                try { logo?.let { this.logoUrl = it } } catch(_: Throwable){}
            }
        } else {
            var maxSeasons = 0
            for ((postObj, _) in postsDetails) {
                val contentArray = postObj.optJSONArray("content")
                if (contentArray != null && contentArray.length() > maxSeasons) {
                    maxSeasons = contentArray.length()
                }
            }

            // Problem 3: Only separate multi seasonal animes, let regular TV series be stacked!
            if (isAnime && maxSeasons > 1) {
                // Separated season logic for anime (Features 5 & 6)
                for (otherSeason in 1..maxSeasons) {
                    if (otherSeason != selectedSeason) {
                        val otherGroup = group.copy(selectedSeason = otherSeason)
                        val otherUrl = encodeGroupedUrl(otherGroup)
                        val otherSeasonTitle = "$cleanedTitle - Season $otherSeason"
                        val otherPoster = getSeasonPoster(cleanedTitle, isAnime, otherSeason) ?: poster

                        recommendationsList.add(
                            if (isAnime) {
                                newAnimeSearchResponse(otherSeasonTitle, otherUrl, tvType) {
                                    this.posterUrl = otherPoster
                                }
                            } else if (tvType == TvType.TvSeries) {
                                newTvSeriesSearchResponse(otherSeasonTitle, otherUrl, tvType) {
                                    this.posterUrl = otherPoster
                                }
                            } else {
                                newMovieSearchResponse(otherSeasonTitle, otherUrl, tvType) {
                                    this.posterUrl = otherPoster
                                }
                            }
                        )
                    }
                }

                val episodesData = mutableListOf<Episode>()
                val episodeCounts = postsDetails.map { (postObj, _) ->
                    val contentArray = postObj.optJSONArray("content")
                    val seasonObj = contentArray?.optJSONObject(selectedSeason - 1)
                    val episodesArray = seasonObj?.optJSONArray("episodes")
                    episodesArray?.length() ?: 0
                }
                val maxEpisodes = episodeCounts.maxOrNull() ?: 0

                for (epIndex in 0 until maxEpisodes) {
                    val epNum = epIndex + 1
                    val epLinks = mutableListOf<JSONObject>()

                    for ((postObj, audio) in postsDetails) {
                        val contentArray = postObj.optJSONArray("content")
                        val seasonObj = contentArray?.optJSONObject(selectedSeason - 1)
                        val episodesArray = seasonObj?.optJSONArray("episodes")
                        val epObj = episodesArray?.optJSONObject(epIndex)
                        val epLink = epObj?.optString("link")
                        if (epLink != null && epLink.isNotEmpty()) {
                            val link = if (urlCheck) epLink else linkToIp(epLink)
                            val linkObj = JSONObject()
                            linkObj.put("url", link)
                            if (audio != null) linkObj.put("audio", audio)
                            epLinks.add(linkObj)
                        }
                    }

                    val epDataString = "circleftp://episode?data=" + Base64.getEncoder().encodeToString(epLinks.toString().toByteArray())
                    
                    // Fetch episode specific metadata from TMDB if available! (Issue 4)
                    val epMeta = metadata.episodes?.getOrNull(epIndex)
                    val epName = epMeta?.name ?: "Episode $epNum"
                    val epOverview = epMeta?.overview
                    val epStill = epMeta?.stillPath

                    episodesData.add(
                        newEpisode(epDataString) {
                            this.episode = epNum
                            this.season = selectedSeason
                            this.name = epName
                            this.description = epOverview
                            this.posterUrl = epStill
                        }
                    )
                }

                return newAnimeLoadResponse(finalTitle, url, tvType) {
                    addEpisodes(DubStatus.Subbed, episodesData)
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = plot
                    this.score = rating?.let { Score.from10(it) }
                    this.recommendations = recommendationsList
                    this.actors = actorsList
                    this.tags = metadata.genres
                    
                    // Trackers mapping (Problem 2: Anime has all tracking)
                    try { metadata.malId?.let { addMalId(it) } } catch(_: Throwable){}
                    try { metadata.anilistId?.let { addAniListId(it) } } catch(_: Throwable){}
                    try { metadata.kitsuId?.let { addKitsuId(it) } } catch(_: Throwable){}
                    try { metadata.imdbId?.let { addImdbId(it) } } catch(_: Throwable){}
                    try { trailer?.let { addTrailer(it) } } catch(_: Throwable){}
                    try { logo?.let { this.logoUrl = it } } catch(_: Throwable){}
                }
            } else {
                // Stacked season logic for non-anime TV series OR single-season anime (Problem 3)
                val episodesData = mutableListOf<Episode>()

                // Prefetch all seasons' metadata concurrently — avoids N sequential
                // TMDB round-trips on multi-season shows.
                val seasonMetadataMap = if (maxSeasons > 0) {
                    coroutineScope {
                        (1..maxSeasons).map { sNum ->
                            async { sNum to fetchMetadata(cleanedTitle, isAnime = isAnime, season = sNum) }
                        }.awaitAll().toMap()
                    }
                } else emptyMap()

                for (sIndex in 0 until maxSeasons) {
                    val sNum = sIndex + 1

                    val sMetadata = seasonMetadataMap[sNum]
                    val sEpisodes = sMetadata?.episodes

                    val sEpisodeCounts = postsDetails.map { (postObj, _) ->
                        val contentArray = postObj.optJSONArray("content")
                        val seasonObj = contentArray?.optJSONObject(sIndex)
                        val episodesArray = seasonObj?.optJSONArray("episodes")
                        episodesArray?.length() ?: 0
                    }
                    val maxSEpisodes = sEpisodeCounts.maxOrNull() ?: 0
                    
                    for (epIndex in 0 until maxSEpisodes) {
                        val epNum = epIndex + 1
                        val epLinks = mutableListOf<JSONObject>()
                        
                        for ((postObj, audio) in postsDetails) {
                            val contentArray = postObj.optJSONArray("content")
                            val seasonObj = contentArray?.optJSONObject(sIndex)
                            val episodesArray = seasonObj?.optJSONArray("episodes")
                            val epObj = episodesArray?.optJSONObject(epIndex)
                            val epLink = epObj?.optString("link")
                            if (epLink != null && epLink.isNotEmpty()) {
                                        val link = if (urlCheck) epLink else linkToIp(epLink)
                                val linkObj = JSONObject()
                                linkObj.put("url", link)
                                if (audio != null) linkObj.put("audio", audio)
                                epLinks.add(linkObj)
                            }
                        }
                        
                        val epDataString = "circleftp://episode?data=" + Base64.getEncoder().encodeToString(epLinks.toString().toByteArray())
                        
                        val epMeta = sEpisodes?.getOrNull(epIndex)
                        val epName = epMeta?.name ?: "Episode $epNum"
                        val epOverview = epMeta?.overview
                        val epStill = epMeta?.stillPath

                        episodesData.add(
                            newEpisode(epDataString) {
                                this.episode = epNum
                                this.season = sNum
                                this.name = epName
                                this.description = epOverview
                                this.posterUrl = epStill
                            }
                        )
                    }
                }

                if (isAnime) {
                    return newAnimeLoadResponse(finalTitle, url, tvType) {
                        addEpisodes(DubStatus.Subbed, episodesData)
                        this.posterUrl = poster
                        this.backgroundPosterUrl = backdrop
                        this.year = year
                        this.plot = plot
                        this.score = rating?.let { Score.from10(it) }
                        this.actors = actorsList
                        this.tags = metadata.genres
                        
                        // Trackers mapping (Problem 2: Anime has all tracking)
                        try { metadata.malId?.let { addMalId(it) } } catch(_: Throwable){}
                        try { metadata.anilistId?.let { addAniListId(it) } } catch(_: Throwable){}
                        try { metadata.kitsuId?.let { addKitsuId(it) } } catch(_: Throwable){}
                        try { metadata.imdbId?.let { addImdbId(it) } } catch(_: Throwable){}
                        try { trailer?.let { addTrailer(it) } } catch(_: Throwable){}
                        try { logo?.let { this.logoUrl = it } } catch(_: Throwable){}
                    }
                } else {
                    return newTvSeriesLoadResponse(finalTitle, url, tvType, episodesData) {
                        this.posterUrl = poster
                        this.backgroundPosterUrl = backdrop
                        this.year = year
                        this.plot = plot
                        this.score = rating?.let { Score.from10(it) }
                        this.recommendations = recommendationsList
                        this.actors = actorsList
                        this.tags = metadata.genres
                        
                        // Trackers mapping (Problem 2: Non-anime has only Simkl via IMDb ID)
                        try { metadata.imdbId?.let { addImdbId(it) } } catch(_: Throwable){}
                        try { trailer?.let { addTrailer(it) } } catch(_: Throwable){}
                        try { logo?.let { this.logoUrl = it } } catch(_: Throwable){}
                    }
                }
            }
        }
    }

    /**
     * Map of CDN hostname → raw IP. Used to swap public DNS names for their
     * BDIX-routable IPs when the user is on a network that blocks the
     * hostname but allows the IP. Kept as a single ordered map so adding a
     * new mirror is a one-line edit instead of another `when` branch.
     */
    private val cdnHostToIp: List<Pair<String, String>> = listOf(
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
        "ftp17.circleftp.net"  to "15.1.3.8",
    )

    private fun linkToIp(data: String?): String {
        if (data.isNullOrEmpty()) return ""
        // First-match wins, mirroring the old `when` chain's precedence.
        for ((host, ip) in cdnHostToIp) {
            if (host in data) return data.replace(host, ip)
        }
        return data
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Both movie and episode payloads use the same JSON shape: an array of
        // {url, audio?} objects, base64-encoded after a small prefix. We
        // extract the prefix to detect quality on the resulting URL, then
        // share a single emit loop. The previous implementation had two
        // near-identical branches that always drifted out of sync.
        val prefix = when {
            data.contains("movie?data=")   -> "movie?data="
            data.contains("episode?data=") -> "episode?data="
            else -> {
                // Raw URL — emit it as a single direct link.
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = data
                    ) {
                        this.quality = getQualityFromName(data)
                    }
                )
                return true
            }
        }

        val jsonStr = runCatching {
            String(Base64.getDecoder().decode(data.substringAfter(prefix)))
        }.getOrNull() ?: return false

        val arr = runCatching { JSONArray(jsonStr) }.getOrNull() ?: return false
        if (arr.length() == 0) return false

        var found = false
        for (i in 0 until arr.length()) {
            val linkObj = arr.optJSONObject(i) ?: continue
            val url = linkObj.optStringOrNull("url") ?: continue
            val audio = linkObj.optStringOrNull("audio")
            val nameWithAudio = if (audio != null) "$name [$audio]" else name
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = nameWithAudio,
                    url = url
                ) {
                    // Quality detection from the URL (e.g. ...1080p.mkv → 1080p).
                    this.quality = getQualityFromName(url)
                }
            )
            found = true
        }
        return found
    }

    data class PageData(
        val posts: List<Post>
    )

    data class Post(
        val id: Int,
        val type: String,
        val imageSm: String,
        val title: String,
        val name: String?,
        val categories: JSONArray? = null
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

    data class TvSeries(
        val content: List<Content>,
    )

    data class Content(
        val episodes: List<EpisodeData>,
        val seasonName: String
    )

    data class EpisodeData(
        val link: String,
        val title: String
    )

    data class Movies(
        val content: String?
    )
}