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
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.api.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        TvType.OVA,
        TvType.Others
    )

    // Supported Sync Trackers: MAL, AniList, Kitsu, and Simkl (Issue 2)
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

    private fun selectUntilNonInt(string: String?): Int? {
        return string?.let { Regex("^.*?(?=\\D|$)").find(it)?.value?.toIntOrNull() }
    }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val lowercaseCheck = check?.lowercase()
        if (lowercaseCheck != null) {
            return when {
                lowercaseCheck.contains("webrip") || lowercaseCheck.contains("web-dl") -> SearchQuality.WebRip
                lowercaseCheck.contains("bluray") -> SearchQuality.BlueRay
                lowercaseCheck.contains("hdts") || lowercaseCheck.contains("hdcam") || lowercaseCheck.contains(
                    "hdtc"
                ) -> SearchQuality.HdCam

                lowercaseCheck.contains("dvd") -> SearchQuality.DVD
                lowercaseCheck.contains("cam") -> SearchQuality.Cam
                lowercaseCheck.contains("camrip") || lowercaseCheck.contains("rip") -> SearchQuality.CamRip
                lowercaseCheck.contains("hdrip") || lowercaseCheck.contains("hd") || lowercaseCheck.contains(
                    "hdtv"
                ) -> SearchQuality.HD

                lowercaseCheck.contains("telesync") -> SearchQuality.Telesync
                lowercaseCheck.contains("telecine") -> SearchQuality.Telecine
                else -> null
            }
        }
        return null
    }

    companion object {
        private val metadataCache = ConcurrentHashMap<String, MetadataInfo>()

        data class GroupedPostInfo(
            val id: Int,
            val originalTitle: String,
            val audio: String?
        )

        data class GroupedUrlData(
            val posts: List<GroupedPostInfo>,
            val cleanedTitle: String,
            val isAnime: Boolean,
            val selectedSeason: Int? = null
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
            val simklId: Int? = null,
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
                            audio = pObj.optString("audio", null)
                        )
                    )
                }
                return GroupedUrlData(
                    posts = postsList,
                    cleanedTitle = obj.getString("cleanedTitle"),
                    isAnime = obj.getBoolean("isAnime"),
                    selectedSeason = if (obj.has("selectedSeason")) obj.getInt("selectedSeason") else null
                )
            } catch (_: Exception) {
                return null
            }
        }

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

            val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
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

                    val resText = app.post("https://graphql.anilist.co", requestBody = requestBody).text
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
                        val engTitle = bestMedia.optJSONObject("title")?.optString("english")
                        val romajiTitle = bestMedia.optJSONObject("title")?.optString("romaji")
                        val nativeTitle = bestMedia.optJSONObject("title")?.optString("native")
                        val poster = bestMedia.optJSONObject("coverImage")?.optString("extraLarge")
                            ?: bestMedia.optJSONObject("coverImage")?.optString("large")
                        val banner = bestMedia.optString("bannerImage", null)
                        val plot = bestMedia.optString("description", null)
                        val score = bestMedia.optDouble("averageScore", 0.0).takeIf { it > 0.0 }
                        
                        var kitsuId: String? = null
                        var simklId: Int? = null
                        var tmdbId: Int? = null
                        var imdbId: String? = null
                        
                        try {
                            val aniZipText = app.get("https://api.ani.zip/mappings?anilist_id=$aniId").text
                            val aniZip = JSONObject(aniZipText)
                            val mappings = aniZip.optJSONObject("mappings")
                            kitsuId = mappings?.optString("kitsu_id", null)
                            val rawTmdb = mappings?.optString("themoviedb_id", null)
                            tmdbId = rawTmdb?.toIntOrNull()
                        } catch (_: Exception) {}

                        if (tmdbId == null) {
                            try {
                                val searchUrl = "https://api.themoviedb.org/3/search/tv?api_key=98ae14df2b8d8f8f8136499daf79f0e0&query=${URLEncoder.encode(title, "UTF-8")}"
                                val searchJson = JSONObject(app.get(searchUrl).text)
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
                            try {
                                logoUrl = fetchTmdbLogoUrl("https://api.themoviedb.org/3", "98ae14df2b8d8f8f8136499daf79f0e0", TvType.TvSeries, tmdbId, "en")
                            } catch (_: Exception) {}

                            try {
                                val seasonUrl = "https://api.themoviedb.org/3/tv/$tmdbId/season/$targetSeason?api_key=98ae14df2b8d8f8f8136499daf79f0e0"
                                val seasonRes = JSONObject(app.get(seasonUrl).text)
                                val epArr = seasonRes.optJSONArray("episodes")
                                if (epArr != null) {
                                    for (i in 0 until epArr.length()) {
                                        val epObj = epArr.getJSONObject(i)
                                        val stillPath = epObj.optString("still_path", "")
                                        val stillUrl = if (stillPath.isNotEmpty() && stillPath != "null") "https://image.tmdb.org/t/p/original$stillPath" else null
                                        epList.add(
                                            EpisodeMetadata(
                                                name = epObj.optString("name", null),
                                                overview = epObj.optString("overview", null),
                                                stillPath = stillUrl,
                                                rating = epObj.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
                                            )
                                        )
                                    }
                                }
                            } catch (_: Exception) {}
                            
                            try {
                                val tvDetailsUrl = "https://api.themoviedb.org/3/tv/$tmdbId?api_key=98ae14df2b8d8f8f8136499daf79f0e0&append_to_response=external_ids,videos"
                                val tvDetails = JSONObject(app.get(tvDetailsUrl).text)
                                val extIds = tvDetails.optJSONObject("external_ids")
                                imdbId = extIds?.optStringSafe("imdb_id") ?: tvDetails.optStringSafe("imdb_id")
                                
                                val videos = tvDetails.optJSONObject("videos")?.optJSONArray("results")
                                if (videos != null) {
                                    for (i in 0 until videos.length()) {
                                        val video = videos.getJSONObject(i)
                                        if (video.optString("type") == "Trailer" && video.optString("site") == "YouTube") {
                                            trailerUrl = "https://www.youtube.com/watch?v=${video.getString("key")}"
                                            break
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }

                        // Parse both the character avatar and voice artist avatar & names from AniList (Feature 1!)
                        val charObj = bestMedia.optJSONObject("characters")
                        val edgesArr = charObj?.optJSONArray("edges")
                        if (edgesArr != null) {
                            for (i in 0 until edgesArr.length()) {
                                val edge = edgesArr.getJSONObject(i)
                                val nodeObj = edge.optJSONObject("node")
                                val charName = nodeObj?.optJSONObject("name")?.optString("full", "") ?: ""
                                val charImg = nodeObj?.optJSONObject("image")?.optString("large", null)
                                
                                val vaArr = edge.optJSONArray("voiceActors")
                                if (vaArr != null && vaArr.length() > 0) {
                                    val va = vaArr.getJSONObject(0)
                                    val vaName = va.optJSONObject("name")?.optString("full", "") ?: ""
                                    val vaImage = va.optJSONObject("image")?.optString("large", null)
                                    if (vaName.isNotEmpty()) {
                                        castList.add(ActorMetadata(vaName, charName, vaImage, charImg))
                                    }
                                }
                            }
                        }

                        if (malId != null && simklId == null) {
                            try {
                                val simklRes = app.get("https://api.simkl.com/search/id?mal=$malId&client_id=1285090f70f69a53235b91b984d7a8e7e10b106093849ea267e1a681c62fbc04").text
                                val simklArr = JSONArray(simklRes)
                                if (simklArr.length() > 0) {
                                    val ids = simklArr.getJSONObject(0).optJSONObject("ids")
                                    simklId = ids?.optInt("simkl_id") ?: ids?.optInt("simkl")
                                }
                            } catch (_: Exception) {}
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
                            simklId = simklId,
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

                    val searchResText = app.get(searchUrl).text
                    val searchJson = JSONObject(searchResText)
                    val results = searchJson.optJSONArray("results")
                    val firstResult = results?.optJSONObject(0)

                    if (firstResult != null) {
                        val tmdbId = firstResult.optInt("id")
                        val mediaType = firstResult.optString("media_type") // "movie" or "tv"
                        
                        val detailsUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbId?api_key=98ae14df2b8d8f8f8136499daf79f0e0&append_to_response=alternative_titles,credits,external_ids,videos,recommendations,images"
                        val detailsText = app.get(detailsUrl).text
                        val details = JSONObject(detailsText)

                        val displayTitle = details.optString("title", details.optString("name", title))
                        val posterPath = details.optString("poster_path", null)
                        val backdropPath = details.optString("backdrop_path", null)
                        val overview = details.optString("overview", null)
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

                        var logoUrl: String? = null
                        try {
                            logoUrl = fetchTmdbLogoUrl("https://api.themoviedb.org/3", "98ae14df2b8d8f8f8136499daf79f0e0", if (mediaType == "movie") TvType.Movie else TvType.TvSeries, tmdbId, "en")
                        } catch (_: Exception) { }

                        val extIds = details.optJSONObject("external_ids")
                        val imdbId = extIds?.optStringSafe("imdb_id") ?: details.optStringSafe("imdb_id")
                        var simklId: Int? = null
                        if (imdbId != null) {
                            try {
                                val simklRes = app.get("https://api.simkl.com/search/id?imdb=$imdbId&client_id=1285090f70f69a53235b91b984d7a8e7e10b106093849ea267e1a681c62fbc04").text
                                val simklArr = JSONArray(simklRes)
                                if (simklArr.length() > 0) {
                                    val ids = simklArr.getJSONObject(0).optJSONObject("ids")
                                    simklId = ids?.optInt("simkl_id") ?: ids?.optInt("simkl")
                                }
                            } catch (_: Exception) {}
                        }

                        val epList = mutableListOf<EpisodeMetadata>()
                        var seasonPoster: String? = null
                        var seasonPlot: String? = null
                        if (mediaType == "tv") {
                            try {
                                val seasonUrl = "https://api.themoviedb.org/3/tv/$tmdbId/season/$targetSeason?api_key=98ae14df2b8d8f8f8136499daf79f0e0"
                                val seasonRes = JSONObject(app.get(seasonUrl).text)
                                val sPosterPath = seasonRes.optString("poster_path", null)
                                if (sPosterPath != null) {
                                    seasonPoster = "https://image.tmdb.org/t/p/original$sPosterPath"
                                }
                                seasonPlot = seasonRes.optString("overview", null)

                                val epArr = seasonRes.optJSONArray("episodes")
                                if (epArr != null) {
                                    for (i in 0 until epArr.length()) {
                                        val epObj = epArr.getJSONObject(i)
                                        val stillPath = epObj.optString("still_path", "")
                                        val stillUrl = if (stillPath.isNotEmpty() && stillPath != "null") "https://image.tmdb.org/t/p/original$stillPath" else null
                                        epList.add(
                                            EpisodeMetadata(
                                                name = epObj.optString("name", null),
                                                overview = epObj.optString("overview", null),
                                                stillPath = stillUrl,
                                                rating = epObj.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
                                            )
                                        )
                                    }
                                }
                            } catch (_: Exception) {}
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
                                val role = castObj.optString("character", null)
                                val profilePath = castObj.optString("profile_path", "")
                                val imageUrl = if (profilePath.isNotEmpty() && profilePath != "null") "https://image.tmdb.org/t/p/original$profilePath" else null
                                if (name.isNotEmpty()) {
                                    castList.add(ActorMetadata(name, role, imageUrl))
                                }
                            }
                        }

                        val metadata = MetadataInfo(
                            title = displayTitle,
                            origTitle = details.optString("original_title", details.optString("original_name", null)),
                            posterUrl = seasonPoster ?: poster,
                            backdropUrl = backdrop,
                            plot = seasonPlot ?: overview,
                            rating = rating,
                            year = year,
                            trailerUrl = trailerUrl,
                            logoUrl = logoUrl,
                            simklId = simklId,
                            imdbId = imdbId,
                            originalLanguage = details.optString("original_language", null),
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
        
        val postsList = mutableListOf<Post>()
        try {
            val jsonObj = JSONObject(json.text)
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
                            name = pObj.optStringSafe("name"), // Fixed the copy-paste bug (jsonObj.optStringSafe -> pObj.optStringSafe!)
                            categories = pObj.optJSONArray("categories")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("CircleFtp", "Failed to parse page posts JSON: ${e.message}")
        }

        val home = groupAndMapPosts(postsList)
        return newHomePageResponse(request.name, home, true)
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
        
        val postsList = mutableListOf<Post>()
        try {
            val jsonObj = JSONObject(json.text)
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
            Log.e("CircleFtp", "Failed to parse search posts JSON: ${e.message}")
        }

        return groupAndMapPosts(postsList)
    }

    // High performance post grouping and instant search result builder (Problem 4: instant mapping!)
    private suspend fun groupAndMapPosts(posts: List<Post>): List<SearchResponse> = coroutineScope {
        val grouped = posts.groupBy { post ->
            val (cleanedTitle, _) = cleanFtpTitle(post.name, post.title)
            cleanedTitle.lowercase().trim()
        }

        grouped.values.map { postGroup ->
            val mainPost = postGroup.first()
            val (cleanedTitle, _) = cleanFtpTitle(mainPost.name, mainPost.title)
            
            // Highly accurate anime detection mapping (Issue 2)
            val isAnime = isPostAnime(mainPost.categories, mainPost.title)

            val postsInfo = postGroup.map { post ->
                val (_, audio) = cleanFtpTitle(post.name, post.title)
                GroupedPostInfo(post.id, post.title, audio)
            }

            val groupedData = GroupedUrlData(
                posts = postsInfo,
                cleanedTitle = cleanedTitle,
                isAnime = isAnime
            )

            val groupedUrl = encodeGroupedUrl(groupedData)
            val posterUrl = "$mainApiUrl/uploads/${mainPost.imageSm}"
            val checkTitleLower = mainPost.title.lowercase()
            val quality = getSearchQuality(checkTitleLower)

            val responseType = if (isAnime) TvType.Anime else TvType.Movie

            newAnimeSearchResponse(cleanedTitle, groupedUrl, responseType) {
                this.posterUrl = posterUrl
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
        }
    }

    private suspend fun getPostDetails(id: Int): JSONObject {
        val jsonText = try {
            app.get(
                "$mainApiUrl/api/posts/$id",
                verify = false,
                cacheTime = 60
            ).text
        } catch (_: Exception) {
            app.get(
                "$apiUrl/api/posts/$id",
                verify = false,
                cacheTime = 60
            ).text
        }
        return JSONObject(jsonText)
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
                    isAnime = isAnime
                )
            } else {
                throw ErrorLoadingException("Invalid URL: $url")
            }
        }

        val group = groupedData!!
        val cleanedTitle = group.cleanedTitle
        val selectedSeason = group.selectedSeason ?: 1

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
        val (metadata, isAnime) = fetchUnifiedMetadata(cleanedTitle, selectedSeason, group.isAnime)

        val finalTitle = metadata.title
        val poster = metadata.posterUrl ?: postsDetails.first().first.let { "$apiUrl/uploads/${it.optString("image")}" }
        val backdrop = metadata.backdropUrl
        val plot = metadata.plot ?: postsDetails.first().first.optString("metaData", "")
        val year = metadata.year ?: selectUntilNonInt(postsDetails.first().first.optString("year"))
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
                            newAnimeSearchResponse(otherSeasonTitle, otherUrl, tvType) {
                                this.posterUrl = otherPoster
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
                
                for (sIndex in 0 until maxSeasons) {
                    val sNum = sIndex + 1
                    
                    val sMetadata = fetchMetadata(cleanedTitle, isAnime = isAnime, season = sNum)
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
                        this.actors = actorsList
                        this.tags = metadata.genres
                        
                        // Trackers mapping (Problem 2: Non-anime has only Simkl via IMDb ID)
                        try { metadata.imdbId?.let { addImdbId(it) } } catch(_: Throwable){}
                        try { trailer?.let { addTrailer(it) } catch(_: Throwable){}
                        try { logo?.let { this.logoUrl = it } } catch(_: Throwable){}
                    }
                }
            }
        }
    }

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
        if (data.contains("movie?data=")) {
            val base64Data = data.substringAfter("movie?data=")
            val jsonStr = String(Base64.getDecoder().decode(base64Data))
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val linkObj = arr.getJSONObject(i)
                val url = linkObj.getString("url")
                val audio = linkObj.optString("audio", null)
                val nameWithAudio = if (audio != null) "$name [$audio]" else name
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = nameWithAudio,
                        url = url
                    )
                )
            }
            return true
        } else if (data.contains("episode?data=")) {
            val base64Data = data.substringAfter("episode?data=")
            val jsonStr = String(Base64.getDecoder().decode(base64Data))
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val linkObj = arr.getJSONObject(i)
                val url = linkObj.getString("url")
                val audio = linkObj.optString("audio", null)
                val nameWithAudio = if (audio != null) "$name [$audio]" else name
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = nameWithAudio,
                        url = url
                    )
                )
            }
            return true
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = data
            )
        )
        return true
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