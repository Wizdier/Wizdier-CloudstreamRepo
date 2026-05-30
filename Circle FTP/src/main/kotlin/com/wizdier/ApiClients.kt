package com.wizdier

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Holds all API-related methods and their LRU-bounded caches.
 * Instantiate once per [CircleFtpProvider] and delegate all external
 * API calls through this class.
 */
class CircleFtpApiClient(
    private val mainApiUrl: String,
    private val fallbackApiUrl: String
) {
    private val anilistApi = "https://graphql.anilist.co"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = BuildConfig.TMDB_API_KEY
    private val tmdbImageBase = "https://image.tmdb.org/t/p/w500"
    private val tmdbBackdropBase = "https://image.tmdb.org/t/p/original"

    // ─── Logging Helper ────────────────────────────────────────────────────

    private fun logError(method: String, e: Exception) {
        // Plain println keeps this plugin cross-platform compatible (no platform Log).
        println("${CircleFtpPatterns.LOG_TAG}: $method: ${e.message}")
    }

    // ─── LRU-bounded caches ────────────────────────────────────────────────

    private fun <K, V> lruMap(maxEntries: Int): MutableMap<K, V> =
        object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxEntries
        }

    private val tmdbMetaCache: MutableMap<String, TmdbMeta?> =
        Collections.synchronizedMap(lruMap(200))
    private val animeMetaCache: MutableMap<String, ResolvedAnimeMeta> =
        Collections.synchronizedMap(lruMap(200))
    private val kitsuMetaCache: MutableMap<String, KitsuMeta?> =
        Collections.synchronizedMap(lruMap(200))
    private val seasonResolutionCache: MutableMap<String, SeasonAnimeResolution> =
        Collections.synchronizedMap(lruMap(200))

    private val aniZipFullCache: MutableMap<Int, AniZipFull?> =
        Collections.synchronizedMap(lruMap(300))
    private val aniZipRawCache: MutableMap<Int, JSONObject> =
        Collections.synchronizedMap(lruMap(300))

    // TMDB per-endpoint caches — prevents re-fetching the same logo /
    // backdrop / trailer for the same tmdbId across different code paths.
    private val tmdbLogoCache: MutableMap<String, String?> =
        Collections.synchronizedMap(lruMap(200))
    private val tmdbBackdropCache: MutableMap<String, String?> =
        Collections.synchronizedMap(lruMap(200))
    private val tmdbTrailerCache: MutableMap<String, String?> =
        Collections.synchronizedMap(lruMap(200))

    // ─── AniList API ─────────────────────────────────────────────────────────

    suspend fun getAniListMeta(title: String, retryCount: Int = 2): AniListMeta? {
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
            } catch (e: Exception) {
                logError("getAniListMeta", e)
                if (attempt == retryCount - 1) return null
                kotlinx.coroutines.delay(150)
            }
        }
        return null
    }

    suspend fun getAniListMetaById(id: Int, retryCount: Int = 2): AniListMeta? {
        repeat(retryCount) { attempt ->
            try {
                val query = buildAniListQuery(byId = true)
                val body = mapOf("query" to query, "variables" to mapOf("id" to id))
                    .toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
                val res = app.post(
                    anilistApi,
                    requestBody = body,
                    headers = mapOf("Content-Type" to "application/json"),
                    cacheTime = 86400
                )
                return AppUtils.parseJson<AniListResponse>(res.text).data?.Media
            } catch (e: Exception) {
                logError("getAniListMetaById", e)
                if (attempt == retryCount - 1) return null
                kotlinx.coroutines.delay(300L * (attempt + 1))
            }
        }
        return null
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
                status
                nextAiringEpisode { airingAt timeUntilAiring episode }
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

    fun getAniListTrailerUrl(trailer: AniListTrailer?): String? {
        val trailerId = trailer?.id?.takeIf { tid -> tid.isNotBlank() } ?: return null
        if (trailerId.startsWith("https://") || trailerId.startsWith("https://")) return trailerId
        return when (trailer.site?.lowercase()) {
            "youtube" -> "https://www.youtube.com/watch?v=$trailerId"
            "dailymotion" -> "https://www.dailymotion.com/video/$trailerId"
            else -> "https://www.youtube.com/watch?v=$trailerId"
        }
    }

    /**
     * Formats the next airing episode information into a human-readable string.
     * Example output: "Episode 13 airs in 2d 5h (RELEASING)"
     * Returns null if the anime is not currently airing or the data is unavailable.
     */
    fun formatNextAiringInfo(
        nextAiring: AniListNextAiringEpisode?,
        status: String?
    ): String? {
        val next = nextAiring ?: return null
        val epNum = next.episode ?: return null
        val timeUntil = next.timeUntilAiring ?: return null
        val days = timeUntil / 86400
        val hours = (timeUntil % 86400) / 3600
        val minutes = (timeUntil % 3600) / 60
        val timeStr = buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0) append("${hours}h ")
            if (days == 0 && minutes > 0) append("${minutes}m")
        }.trim().ifBlank { "<1m" }
        val statusTag = when (status?.lowercase()) {
            "releasing" -> "RELEASING"
            "not_yet_released" -> "UPCOMING"
            else -> null
        }
        return if (statusTag != null) {
            "Episode $epNum airs in $timeStr ($statusTag)"
        } else {
            "Episode $epNum airs in $timeStr"
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
            anilistId = run {
                val fallback = fallbackAnilistId
                if (fallback != null) return@run fallback
                val fromJson = json.optInt("anilist_id", 0)
                if (fromJson != 0) return@run fromJson
                val fromMap = m?.optInt("anilist_id", 0) ?: 0
                if (fromMap != 0) return@run fromMap
                null
            },
            malId = run {
                val fallback = fallbackMalId
                if (fallback != null) return@run fallback
                val fromMap = m?.optInt("mal_id", 0) ?: 0
                if (fromMap != 0) return@run fromMap
                null
            },
            kitsuId = fallbackKitsuId ?: m?.optString("kitsu_id")?.takeIf { it.isNotBlank() },
            simklId = run {
                val fromMap = m?.optInt("simkl_id", 0) ?: 0
                if (fromMap != 0) return@run fromMap
                null
            },
            tmdbId = fallbackTmdbId ?: m?.optString("themoviedb_id")?.takeIf { it.isNotBlank() },
            traktId = run {
                val fromMap = m?.optInt("trakt_id", 0) ?: 0
                if (fromMap != 0) return@run fromMap
                null
            }
        )
    }

    suspend fun getAniZipFullCached(anilistId: Int, retryCount: Int = 2): AniZipFull? {
        aniZipFullCache[anilistId]?.let { return it }
        repeat(retryCount) { attempt ->
            try {
                val json = JSONObject(app.get("https://api.ani.zip/mappings?anilist_id=$anilistId", cacheTime = 86400).text)
                val value = parseAniZip(json, fallbackAnilistId = anilistId)
                aniZipFullCache[anilistId] = value
                aniZipRawCache[anilistId] = json
                return value
            } catch (e: Exception) {
                logError("getAniZipFullCached", e)
                if (attempt == retryCount - 1) return null
                kotlinx.coroutines.delay(300L * (attempt + 1))
            }
        }
        return null
    }

    suspend fun getAniZipByMalId(malId: Int, retryCount: Int = 2): AniZipFull? {
        repeat(retryCount) { attempt ->
            try {
                val json = JSONObject(app.get("https://api.ani.zip/mappings?mal_id=$malId", cacheTime = 86400).text)
                return parseAniZip(json, fallbackMalId = malId)
            } catch (e: Exception) {
                logError("getAniZipByMalId", e)
                if (attempt == retryCount - 1) return null
                kotlinx.coroutines.delay(300L * (attempt + 1))
            }
        }
        return null
    }

    suspend fun getAniZipByTmdbId(tmdbId: Int, retryCount: Int = 2): AniZipFull? {
        repeat(retryCount) { attempt ->
            try {
                val json = JSONObject(app.get("https://api.ani.zip/mappings?themoviedb_id=$tmdbId", cacheTime = 86400).text)
                return parseAniZip(json, fallbackTmdbId = tmdbId.toString())
            } catch (e: Exception) {
                logError("getAniZipByTmdbId", e)
                if (attempt == retryCount - 1) return null
                kotlinx.coroutines.delay(300L * (attempt + 1))
            }
        }
        return null
    }

    suspend fun getAniZipByKitsuId(kitsuId: String, retryCount: Int = 2): AniZipFull? {
        repeat(retryCount) { attempt ->
            try {
                val json = JSONObject(app.get("https://api.ani.zip/mappings?kitsu_id=$kitsuId", cacheTime = 86400).text)
                return parseAniZip(json, fallbackKitsuId = kitsuId)
            } catch (e: Exception) {
                logError("getAniZipByKitsuId", e)
                if (attempt == retryCount - 1) return null
                kotlinx.coroutines.delay(300L * (attempt + 1))
            }
        }
        return null
    }

    suspend fun getAniZipEpisodeTitles(anilistId: Int): Map<Int, String> = try {
        // Check the raw JSON cache first — avoids a redundant network call if
        // getAniZipFullCached already fetched this ID.
        val json = aniZipRawCache[anilistId]
            ?: JSONObject(app.get("https://api.ani.zip/mappings?anilist_id=$anilistId", cacheTime = 86400).text)
        val episodes = json.optJSONObject("episodes") ?: return emptyMap()
        val map = mutableMapOf<Int, String>()
        episodes.keys().forEach { key ->
            val epNum = key.toIntOrNull() ?: return@forEach
            val epObj = episodes.optJSONObject(key) ?: return@forEach
            val titles = epObj.optJSONObject("title")
            val epTitle = titles?.optString("en")?.takeIf { s -> s.isNotBlank() }
                ?: titles?.optString("x-jat")?.takeIf { s -> s.isNotBlank() }
                ?: epObj.optString("title").takeIf { s -> s.isNotBlank() }
            if (epTitle != null) map[epNum] = epTitle
        }
        map
    } catch (e: Exception) {
        logError("getAniZipEpisodeTitles", e)
        emptyMap()
    }

    // ─── Kitsu / MAL API ─────────────────────────────────────────────────────

    suspend fun searchMalId(title: String): Int? = try {
        val res = app.get(
            "https://api.jikan.moe/v4/anime?q=${URLEncoder.encode(title, "UTF-8")}&limit=1",
            cacheTime = 3600
        )
        JSONObject(res.text).optJSONArray("data")?.optJSONObject(0)?.optInt("mal_id")
    } catch (e: Exception) {
        logError("searchMalId", e)
        null
    }

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
    } catch (e: Exception) {
        logError("getKitsuMeta", e)
        null
    }

    suspend fun getKitsuMetaCached(title: String): KitsuMeta? {
        val key = title.lowercase()
        kitsuMetaCache[key]?.let { return it }
        val value = getKitsuMeta(title)
        kitsuMetaCache[key] = value
        return value
    }

    // ─── TMDB API ────────────────────────────────────────────────────────────

    suspend fun getTmdbMeta(title: String, year: Int?, isSeries: Boolean): TmdbMeta? = try {
        val type = if (isSeries) "tv" else "movie"
        val yearParam = year?.let { y -> if (isSeries) "&first_air_date_year=$y" else "&year=$y" } ?: ""
        val searchUrl = "$tmdbApi/search/$type?api_key=$tmdbKey&query=${URLEncoder.encode(title, "UTF-8")}$yearParam&language=en-US"
        val first = AppUtils.parseJson<TmdbSearchResponse>(
            app.get(searchUrl, cacheTime = 86400).text
        ).results?.firstOrNull() ?: return null

        val tmdbId = first.id ?: return null
        val logo = fetchTmdbLogo(tmdbId, isSeries)
        var imdbId: String? = null
        var backdrop = first.backdropPath?.let { p -> "$tmdbBackdropBase$p" }

        try {
            val detail = JSONObject(
                app.get("$tmdbApi/$type/$tmdbId?api_key=$tmdbKey&append_to_response=external_ids", cacheTime = 86400).text
            )
            imdbId = detail.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { s -> s.isNotBlank() }
            val detailBackdrop = detail.optString("backdrop_path").takeIf { s -> s.isNotBlank() }
            if (detailBackdrop != null) backdrop = "$tmdbBackdropBase$detailBackdrop"
        } catch (e: Exception) {
            logError("getTmdbMeta-detail", e)
        }

        TmdbMeta(
            poster = first.posterPath?.let { p -> "$tmdbImageBase$p" },
            backdrop = backdrop,
            rating = first.voteAverage,
            overview = first.overview,
            logoUrl = logo,
            imdbId = imdbId,
            tmdbId = tmdbId
        )
    } catch (e: Exception) {
        logError("getTmdbMeta", e)
        null
    }

    suspend fun getTmdbMetaCached(title: String, year: Int?, isSeries: Boolean): TmdbMeta? {
        val key = "${isSeries}|${year ?: 0}|${title.lowercase()}"
        tmdbMetaCache[key]?.let { return it }
        val value = getTmdbMeta(title, year, isSeries)
        tmdbMetaCache[key] = value
        return value
    }

    fun resolveBackdrop(tmdbMeta: TmdbMeta?, aniListMeta: AniListMeta?, fallback: String?): String? =
        tmdbMeta?.backdrop
            ?: aniListMeta?.bannerImage
            ?: fallback
            ?: tmdbMeta?.poster
            ?: aniListMeta?.coverImage?.extraLarge

    suspend fun fetchTmdbBackdrop(tmdbId: Int?, isSeries: Boolean): String? {
        if (tmdbId == null) return null
        val cacheKey = "$tmdbId|${if (isSeries) "tv" else "movie"}"
        tmdbBackdropCache[cacheKey]?.let { return it }
        val result = try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(
                app.get("$tmdbApi/$type/$tmdbId?api_key=$tmdbKey&language=en-US", cacheTime = 86400).text
            )
            json.optString("backdrop_path").takeIf { p -> p.isNotBlank() }?.let { p -> "$tmdbBackdropBase$p" }
        } catch (e: Exception) {
            logError("fetchTmdbBackdrop", e)
            null
        }
        tmdbBackdropCache[cacheKey] = result
        return result
    }

    suspend fun fetchTmdbLogo(tmdbId: Int?, isSeries: Boolean): String? {
        if (tmdbId == null) return null
        val cacheKey = "$tmdbId|${if (isSeries) "tv" else "movie"}"
        tmdbLogoCache[cacheKey]?.let { return it }
        val result = try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(
                app.get("$tmdbApi/$type/$tmdbId/images?api_key=$tmdbKey", cacheTime = 86400).text
            )
            val logos = json.optJSONArray("logos")
            if (logos == null) {
                null
            } else {
                // Collect all valid logos then pick the best one by language preference.
                // Prefer: English > Bengali (provider lang) > any language > no language.
                // SVG logos (vector) are preferred over PNG (raster) for sharpness.
                val preferredLangs = listOf("en", "bn")
                var bestMatch: String? = null
                var bestScore = -1  // Higher = better
                for (i in 0 until logos.length()) {
                    val logo = logos.getJSONObject(i)
                    val path = logo.optString("file_path").takeIf { p -> p.isNotBlank() } ?: continue
                    val lang = logo.optString("iso_639_1").orEmpty()
                    val isSvg = path.endsWith(".svg", ignoreCase = true)
                    val score = when {
                        lang in preferredLangs && isSvg -> 4
                        lang == "en" -> 3
                        lang == "bn" -> 2
                        lang.isNotBlank() -> 1
                        else -> 0
                    }
                    if (score > bestScore) {
                        bestScore = score
                        bestMatch = "$tmdbImageBase$path"
                    }
                }
                bestMatch
            }
        } catch (e: Exception) {
            logError("fetchTmdbLogo", e)
            null
        }
        tmdbLogoCache[cacheKey] = result
        return result
    }

    suspend fun fetchTmdbTrailer(tmdbId: Int?, isSeries: Boolean): String? {
        if (tmdbId == null) return null
        val cacheKey = "$tmdbId|${if (isSeries) "tv" else "movie"}"
        tmdbTrailerCache[cacheKey]?.let { return it }
        val result = try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(
                app.get("$tmdbApi/$type/$tmdbId/videos?api_key=$tmdbKey&language=en-US", cacheTime = 86400).text
            )
            val results = json.optJSONArray("results")
            if (results == null) {
                null
            } else {
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
                val key = preferred?.optString("key")?.takeIf { k -> k.isNotBlank() }
                if (preferred == null || key == null) {
                    null
                } else {
                    when (preferred.optString("site").lowercase()) {
                        "youtube" -> "https://www.youtube.com/watch?v=$key"
                        "dailymotion" -> "https://www.dailymotion.com/video/$key"
                        else -> "https://www.youtube.com/watch?v=$key"
                    }
                }
            }
        } catch (e: Exception) {
            logError("fetchTmdbTrailer", e)
            null
        }
        tmdbTrailerCache[cacheKey] = result
        return result
    }

    suspend fun fetchTmdbSeasonEpisodes(tmdbId: Int, seasonNum: Int): List<TmdbEpisode> = try {
        val json = JSONObject(
            app.get("$tmdbApi/tv/$tmdbId/season/$seasonNum?api_key=$tmdbKey&language=en-US", cacheTime = 86400).text
        )
        val arr = json.optJSONArray("episodes") ?: return emptyList()
        List(arr.length()) { idx ->
            val ep = arr.getJSONObject(idx)
            TmdbEpisode(
                name = ep.optString("name"),
                overview = ep.optString("overview"),
                stillPath = ep.optString("still_path").takeIf { s -> s.isNotBlank() },
                airDate = ep.optString("air_date"),
                rating = ep.optDouble("vote_average")
            )
        }
    } catch (e: Exception) {
        logError("fetchTmdbSeasonEpisodes", e)
        emptyList()
    }

    suspend fun fetchTmdbActors(tmdbId: Int?, isSeries: Boolean): List<ActorData>? {
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
        } catch (e: Exception) {
            logError("fetchTmdbActors", e)
            null
        }
    }

    // ─── Cross-reference helper (TMDB → AniZip) ────────────────────────────
    // Allows non-anime paths to attempt AniZip lookups by TMDB ID.  AniZip
    // primarily indexes anime, but some animation, Asian dramas, and other
    // cross-listed content also have entries.  Returns null gracefully when
    // no mapping exists.

    suspend fun getCrossRefIdsByTmdb(tmdbId: Int): AniZipFull? {
        return try {
            getAniZipByTmdbId(tmdbId)
        } catch (e: Exception) {
            logError("getCrossRefIdsByTmdb", e)
            null
        }
    }

    // ─── Server / Post Fetching ──────────────────────────────────────────────

    suspend fun fetchWithFallback(
        primaryBlock: suspend () -> String,
        fallbackBlock: suspend () -> String
    ): String {
        return try {
            primaryBlock()
        } catch (e: Exception) {
            logError("fetchWithFallback-primary", e)
            fallbackBlock()
        }
    }

    suspend fun fetchPostResponse(postId: Int) = try {
        app.get("$mainApiUrl/api/posts/$postId", cacheTime = 60)
    } catch (e: Exception) {
        logError("fetchPostResponse", e)
        app.get("$fallbackApiUrl/api/posts/$postId", cacheTime = 60)
    }

    // ─── Anime Meta Resolution ───────────────────────────────────────────────

    suspend fun resolveAnimeMeta(title: String, year: Int? = null, isSeries: Boolean = true): ResolvedAnimeMeta {
        val cleaned = cleanTitle(title)
            .replace(CircleFtpPatterns.RE_ANIME_WORD, "")
            .replace(CircleFtpPatterns.RE_MULTISPACE, " ")
            .trim()

        // ─── Parallel fetch: Kitsu, MAL, and TMDB are independent ──────────
        val initialResults = coroutineScope {
            val kitsuDeferred = async {
                val k = getKitsuMetaCached(cleaned) ?: getKitsuMetaCached(title)
                Pair(k, k?.id?.let { getAniZipByKitsuId(it) })
            }
            val malDeferred = async {
                val mId = searchMalId(cleaned) ?: searchMalId(title)
                Pair(mId, mId?.let { getAniZipByMalId(it) })
            }
            val tmdbDeferred = async {
                val t = getTmdbMeta(cleaned, year, isSeries)
                Pair(t, t?.tmdbId?.let { getAniZipByTmdbId(it) })
            }
            Triple(kitsuDeferred.await(), malDeferred.await(), tmdbDeferred.await())
        }

        val (kitsu, zipFromKitsu) = initialResults.first
        val (searchedMalId, searchedZipFromMal) = initialResults.second
        val (tmdbMeta, zipFromTmdb) = initialResults.third

        // Kitsu zip may provide a MAL ID, avoiding a separate MAL search hit
        val malId: Int? = zipFromKitsu?.malId ?: searchedMalId
        val zipFromMal: AniZipFull? = if (malId != null && malId != searchedMalId) {
            getAniZipByMalId(malId)
        } else {
            searchedZipFromMal
        }

        // ─── Build best AniZip from Kitsu/MAL/TMDB ─────────────────────────
        val bestAniZip: AniZipFull? = zipFromKitsu ?: zipFromMal ?: zipFromTmdb

        // ─── Priority 4: AniList (LAST RESORT) ─────────────────────────────
        var aniList: AniListMeta? = null
        var aniListId: Int? = bestAniZip?.anilistId
        if (aniListId != null) {
            aniList = getAniListMetaById(aniListId)
        }
        if (aniList == null) {
            aniList = getAniListMeta(cleaned) ?: getAniListMeta(title)
            aniListId = aniList?.id
        }

        val finalAniZip: AniZipFull? = if (aniListId != null && bestAniZip?.anilistId != aniListId) {
            getAniZipFullCached(aniListId)
        } else {
            bestAniZip
        }

        // ─── Resolve TMDB enrichment ───────────────────────────────────────
        val tmdbFromZip: Int? = finalAniZip?.tmdbId?.toIntOrNull()
            ?: zipFromTmdb?.tmdbId?.toIntOrNull()
            ?: zipFromKitsu?.tmdbId?.toIntOrNull()
            ?: zipFromMal?.tmdbId?.toIntOrNull()

        val finalTmdb: TmdbMeta? = tmdbMeta ?: tmdbFromZip?.let { tId ->
            TmdbMeta(
                poster = null,
                backdrop = fetchTmdbBackdrop(tId, isSeries),
                rating = null,
                overview = null,
                logoUrl = fetchTmdbLogo(tId, isSeries),
                imdbId = null,
                tmdbId = tId
            )
        }

        // ─── Build display metadata from best available source ─────────────
        val displayTitle = kitsu?.title
            ?: aniList?.title?.english
            ?: aniList?.title?.romaji
            ?: cleaned

        return ResolvedAnimeMeta(
            title = displayTitle,
            poster = kitsu?.poster ?: aniList?.coverImage?.extraLarge ?: aniList?.coverImage?.large ?: finalTmdb?.poster,
            background = resolveBackdrop(finalTmdb, aniList, kitsu?.cover),
            plot = aniList?.description?.replace(CircleFtpPatterns.RE_HTML_TAGS, "") ?: kitsu?.synopsis ?: finalTmdb?.overview,
            score100 = aniList?.averageScore ?: kitsu?.averageRating?.toInt(),
            tags = aniList?.genres,
            trailer = fetchTmdbTrailer(finalTmdb?.tmdbId, isSeries)
                ?: getAniListTrailerUrl(aniList?.trailer),
            anilistEpisodes = aniList?.streamingEpisodes,
            logoUrl = finalTmdb?.logoUrl,
            actors = aniList?.characters?.edges?.mapNotNull { edge ->
                val charName = edge.node?.name?.full
                val charImage = edge.node?.image?.large
                charName?.let { n -> ActorData(Actor(n, charImage)) }
            },
            anilistId = aniList?.id ?: finalAniZip?.anilistId ?: zipFromKitsu?.anilistId ?: zipFromMal?.anilistId ?: zipFromTmdb?.anilistId,
            malId = aniList?.idMal ?: finalAniZip?.malId ?: zipFromKitsu?.malId ?: zipFromMal?.malId ?: malId,
            kitsuId = finalAniZip?.kitsuId ?: zipFromKitsu?.kitsuId ?: zipFromMal?.kitsuId ?: zipFromTmdb?.kitsuId ?: kitsu?.id,
            simklId = finalAniZip?.simklId ?: zipFromKitsu?.simklId ?: zipFromMal?.simklId ?: zipFromTmdb?.simklId,
            traktId = finalAniZip?.traktId ?: zipFromKitsu?.traktId ?: zipFromMal?.traktId ?: zipFromTmdb?.traktId,
            imdbId = finalTmdb?.imdbId,
            nextAiringInfo = formatNextAiringInfo(aniList?.nextAiringEpisode, aniList?.status)
        )
    }

    suspend fun resolveAnimeMetaCached(title: String, year: Int? = null, isSeries: Boolean = true): ResolvedAnimeMeta {
        val key = "${isSeries}|${year ?: 0}|${title.lowercase()}"
        animeMetaCache[key]?.let { cached -> return cached }
        val value = resolveAnimeMeta(title, year, isSeries)
        animeMetaCache[key] = value
        return value
    }

    // ─── Per-Season Anime Resolution ─────────────────────────────────────────

    private fun animeSeasonSearchTitles(baseTitle: String, seasonName: String?, seasonNumber: Int): List<String> {
        val base = stripSeasonSuffixForAnime(baseTitle)
        val cleanedSeasonName = seasonName?.trim()?.takeIf { s -> s.isNotBlank() }
        val genericSeasonName = cleanedSeasonName == null ||
            cleanedSeasonName.equals("Season $seasonNumber", true) ||
            cleanedSeasonName.matches(CircleFtpPatterns.RE_SEASON_WORD)
        val titles = mutableListOf<String>()
        if (!genericSeasonName && cleanedSeasonName != null) {
            titles += "$base: $cleanedSeasonName"
            titles += "$base $cleanedSeasonName"
            titles += cleanedSeasonName
        }
        titles += "$base Season $seasonNumber"
        titles += "$base Part $seasonNumber"
        titles += "$base ${ordinal(seasonNumber)} Season"
        return titles.map { t -> cleanTitle(t) }.filter { t -> t.isNotBlank() }.distinct()
    }

    private suspend fun resolvedAnimeMetaFromAniListNode(
        aniList: AniListMeta,
        aniZip: AniZipFull?,
        fallback: ResolvedAnimeMeta,
        isSeries: Boolean
    ): ResolvedAnimeMeta {
        // ── TMDB enrichment ──────────────────────────────────────────────
        // Try AniZip TMDB ID first.  If AniZip hasn't mapped this season to
        // TMDB yet, fall back to a direct TMDB title search so logos and
        // backdrops still resolve for season 2+ entries.
        val searchTitle = aniList.title?.english ?: aniList.title?.romaji ?: fallback.title
        val tmdbFromZip = aniZip?.tmdbId?.toIntOrNull()
        val tmdbMeta: TmdbMeta? = if (tmdbFromZip != null) {
            TmdbMeta(
                poster = null,
                backdrop = fetchTmdbBackdrop(tmdbFromZip, isSeries),
                rating = null,
                overview = null,
                logoUrl = fetchTmdbLogo(tmdbFromZip, isSeries),
                imdbId = null,
                tmdbId = tmdbFromZip
            )
        } else {
            getTmdbMeta(searchTitle, null, isSeries)
        }

        val displayTitle = searchTitle
        return ResolvedAnimeMeta(
            title = displayTitle,
            poster = aniList.coverImage?.extraLarge ?: aniList.coverImage?.large ?: fallback.poster,
            background = resolveBackdrop(tmdbMeta, aniList, fallback.background),
            plot = aniList.description?.replace(CircleFtpPatterns.RE_HTML_TAGS, "") ?: fallback.plot,
            score100 = aniList.averageScore ?: fallback.score100,
            tags = aniList.genres ?: fallback.tags,
            trailer = fetchTmdbTrailer(tmdbMeta?.tmdbId, isSeries)
                ?: getAniListTrailerUrl(aniList.trailer)
                ?: fallback.trailer,
            anilistEpisodes = aniList.streamingEpisodes ?: fallback.anilistEpisodes,
            logoUrl = tmdbMeta?.logoUrl ?: fallback.logoUrl,
            actors = aniList.characters?.edges?.mapNotNull { edge ->
                val charName = edge.node?.name?.full
                val charImage = edge.node?.image?.large
                charName?.let { n -> ActorData(Actor(n, charImage)) }
            } ?: fallback.actors,
            anilistId = aniList.id,
            malId = aniZip?.malId ?: aniList.idMal,
            kitsuId = aniZip?.kitsuId,
            simklId = aniZip?.simklId,
            traktId = aniZip?.traktId,
            imdbId = tmdbMeta?.imdbId ?: fallback.imdbId,
            nextAiringInfo = formatNextAiringInfo(aniList.nextAiringEpisode, aniList.status)
        )
    }

    suspend fun resolveAnimeSeasonDynamically(
        baseTitle: String,
        seasonName: String?,
        seasonNumber: Int,
        year: Int?,
        baseMeta: ResolvedAnimeMeta
    ): SeasonAnimeResolution {
        // Season 1: baseMeta IS the correct resolved meta — return it directly.
        if (seasonNumber <= 1) {
            return SeasonAnimeResolution(
                meta = baseMeta,
                ids = SeasonIds(
                    anilistId = baseMeta.anilistId,
                    malId = baseMeta.malId,
                    kitsuId = baseMeta.kitsuId,
                    simklId = baseMeta.simklId,
                    traktId = baseMeta.traktId
                ),
                aniListNode = null,
                aniZip = null
            )
        }

        // Check cache first
        val cacheKey = "${baseMeta.anilistId ?: 0}|${baseMeta.malId ?: 0}|$seasonNumber"
        seasonResolutionCache[cacheKey]?.let { return it }

        // ── Season 2+: all IDs must be resolved fresh for THIS season only ──
        val candidateTitles = animeSeasonSearchTitles(baseTitle, seasonName, seasonNumber)

        // ─── Strategy A: Walk the AniList SEQUEL chain from Season 1 ───────
        var seasonAniListId: Int? = null
        var seasonAniList: AniListMeta? = null

        val baseAniListId: Int? = baseMeta.anilistId
        if (baseAniListId != null) {
            var currentNode: AniListMeta? = getAniListMetaById(baseAniListId)
            val hopsNeeded = seasonNumber - 1
            for (hop in 1..hopsNeeded) {
                val node = currentNode ?: break
                val sequelEdges = node.relations?.edges
                    ?.filter { edge ->
                        edge.relationType.equals("SEQUEL", ignoreCase = true)
                    }
                    ?.sortedByDescending { edge -> edge.node?.episodes ?: 0 }
                val bestSequelId = sequelEdges?.firstOrNull()?.node?.id
                val nextId = bestSequelId ?: run {
                    // SEQUEL edge missing — try weaker continuation signals
                    // (SPIN_OFF / SIDE_STORY often serve as de-facto
                    // continuations on AniList for multi-cour series).
                    // ALTERNATIVE is deliberately avoided — it means a
                    // completely different adaptation, not a sequel.
                    val weakEdges = node.relations?.edges?.filter { edge ->
                        val t = edge.relationType?.uppercase() ?: ""
                        t == "SPIN_OFF" || t == "SIDE_STORY" || t == "PARENT"
                    }
                    weakEdges
                        ?.sortedByDescending { edge -> edge.node?.episodes ?: 0 }
                        ?.firstOrNull()?.node?.id
                }
                if (nextId == null) {
                    currentNode = null
                    break
                }
                if (nextId == baseAniListId) {
                    currentNode = null
                    break
                }
                currentNode = getAniListMetaById(nextId)
            }
            val walkedNode = currentNode
            if (walkedNode != null && walkedNode.id != baseAniListId) {
                seasonAniList = walkedNode
                seasonAniListId = walkedNode.id
            }
        }

        // ─── Strategy A2: PREQUEL walk to anchor at Season 1, then re-walk ─
        if (seasonAniListId == null && baseAniListId != null) {
            val visitedIds = mutableSetOf<Int>()
            var anchorNode: AniListMeta? = getAniListMetaById(baseAniListId)
            while (true) {
                val node = anchorNode ?: break
                val prequelEdge = node.relations?.edges
                    ?.filter { edge ->
                        edge.relationType.equals("PREQUEL", ignoreCase = true)
                    }
                    ?.sortedByDescending { edge -> edge.node?.episodes ?: 0 }
                    ?.firstOrNull()
                val prequelId = prequelEdge?.node?.id
                if (prequelId == null || prequelId in visitedIds) break
                visitedIds.add(prequelId)
                if (prequelId == baseAniListId) break
                anchorNode = getAniListMetaById(prequelId)
            }
            val anchored = anchorNode
            if (anchored != null && anchored.id != baseAniListId) {
                var rewalkCurrent: AniListMeta? = anchored
                val rewalkVisited = mutableSetOf<Int?>(); rewalkVisited.add(anchored.id)
                for (hop in 1 until seasonNumber) {
                    val node = rewalkCurrent ?: break
                    val sequelEdge = node.relations?.edges
                        ?.filter { edge ->
                            edge.relationType.equals("SEQUEL", ignoreCase = true)
                        }
                        ?.sortedByDescending { edge -> edge.node?.episodes ?: 0 }
                        ?.firstOrNull()
                    val nextId = sequelEdge?.node?.id ?: run {
                        val weakEdges = node.relations?.edges?.filter { edge ->
                            val t = edge.relationType?.uppercase() ?: ""
                            t == "SPIN_OFF" || t == "SIDE_STORY" || t == "PARENT"
                        }
                        weakEdges
                            ?.sortedByDescending { edge -> edge.node?.episodes ?: 0 }
                            ?.firstOrNull()?.node?.id
                    }
                    if (nextId == null || nextId in rewalkVisited) {
                        rewalkCurrent = null
                        break
                    }
                    rewalkVisited.add(nextId)
                    rewalkCurrent = getAniListMetaById(nextId)
                }
                val rewalkedNode = rewalkCurrent
                if (rewalkedNode != null && rewalkedNode.id != baseAniListId) {
                    seasonAniList = rewalkedNode
                    seasonAniListId = rewalkedNode.id
                }
            }
        }

        // ─── Strategy B: Cross-DB title search (Kitsu + MAL + AniList) ────
        var seasonKitsu: KitsuMeta? = null
        var seasonKitsuZip: AniZipFull? = null
        var seasonMalId: Int? = null
        var seasonMalZip: AniZipFull? = null

        if (seasonAniListId == null) {
            val (kitsuResult, malResult) = coroutineScope {
                val kitsuDeferred = async {
                    var bestKitsu: KitsuMeta? = null
                    var bestKitsuZip: AniZipFull? = null
                    var confirmedKitsu: KitsuMeta? = null
                    var confirmedKitsuZip: AniZipFull? = null
                    for (kitsuCandidate in candidateTitles) {
                        val kitsuHit = getKitsuMetaCached(kitsuCandidate)
                            ?: getKitsuMeta(kitsuCandidate)
                        if (kitsuHit?.id != null) {
                            val kitsuZip = getAniZipByKitsuId(kitsuHit.id)
                            if (bestKitsu == null) {
                                bestKitsu = kitsuHit
                                bestKitsuZip = kitsuZip
                            }
                            if (kitsuZip?.anilistId != null || kitsuZip?.malId != null) {
                                confirmedKitsu = kitsuHit
                                confirmedKitsuZip = kitsuZip
                                break
                            }
                        }
                    }
                    Pair(confirmedKitsu ?: bestKitsu, confirmedKitsuZip ?: bestKitsuZip)
                }
                val malDeferred = async {
                    var bestMalId: Int? = null
                    var bestMalZip: AniZipFull? = null
                    var confirmedMalId: Int? = null
                    var confirmedMalZip: AniZipFull? = null
                    for (malCandidate in candidateTitles) {
                        val foundMalId = searchMalId(malCandidate)
                        if (foundMalId != null) {
                            val malZip = getAniZipByMalId(foundMalId)
                            if (bestMalId == null) {
                                bestMalId = foundMalId
                                bestMalZip = malZip
                            }
                            if (malZip?.anilistId != null) {
                                confirmedMalId = foundMalId
                                confirmedMalZip = malZip
                                break
                            }
                        }
                    }
                    Pair(confirmedMalId ?: bestMalId, confirmedMalZip ?: bestMalZip)
                }
                Pair(kitsuDeferred.await(), malDeferred.await())
            }

            seasonKitsu = kitsuResult.first
            seasonKitsuZip = kitsuResult.second
            seasonMalId = malResult.first
            seasonMalZip = malResult.second

            val crossDbAniListId = seasonKitsuZip?.anilistId ?: seasonMalZip?.anilistId
            if (crossDbAniListId != null) {
                seasonAniList = getAniListMetaById(crossDbAniListId)
                seasonAniListId = crossDbAniListId
            }

            if (seasonAniList == null) {
                for (titleCandidate in candidateTitles) {
                    val searchedNode = getAniListMeta(titleCandidate)
                    if (searchedNode != null) {
                        seasonAniList = searchedNode
                        seasonAniListId = searchedNode.id
                        break
                    }
                }
            }
        }

        // ─── Fetch AniZip keyed ONLY to this season's AniList ID ─────────
        val seasonAniZip: AniZipFull? = when {
            seasonAniListId != null -> getAniZipFullCached(seasonAniListId)
            seasonMalZip != null -> seasonMalZip
            seasonKitsuZip != null -> seasonKitsuZip
            else -> null
        }

        // ─── Build the per-season tracking IDs ───────────────────────────
        val resolvedSeasonIds = SeasonIds(
            anilistId = seasonAniListId,
            malId = seasonAniList?.idMal ?: seasonAniZip?.malId ?: seasonMalId,
            kitsuId = seasonKitsu?.id ?: seasonAniZip?.kitsuId,
            simklId = seasonAniZip?.simklId,
            traktId = seasonAniZip?.traktId
        )

        // ─── Build display metadata from the resolved season data ─────────
        val seasonMeta: ResolvedAnimeMeta = if (seasonAniList != null) {
            resolvedAnimeMetaFromAniListNode(
                aniList = seasonAniList,
                aniZip = seasonAniZip,
                fallback = baseMeta,
                isSeries = true
            )
        } else {
            ResolvedAnimeMeta(
                title = seasonKitsu?.title ?: titleForSeason(baseTitle, seasonName, seasonNumber),
                poster = seasonKitsu?.poster ?: baseMeta.poster,
                background = seasonKitsu?.cover ?: baseMeta.background,
                plot = seasonKitsu?.synopsis ?: baseMeta.plot,
                score100 = seasonKitsu?.averageRating?.toInt() ?: baseMeta.score100,
                tags = baseMeta.tags,
                trailer = baseMeta.trailer,
                anilistEpisodes = null,
                logoUrl = baseMeta.logoUrl,
                actors = baseMeta.actors,
                anilistId = resolvedSeasonIds.anilistId,
                malId = resolvedSeasonIds.malId,
                kitsuId = resolvedSeasonIds.kitsuId,
                simklId = resolvedSeasonIds.simklId,
                traktId = resolvedSeasonIds.traktId,
                imdbId = baseMeta.imdbId
            )
        }

        val result = SeasonAnimeResolution(
            meta = seasonMeta,
            ids = resolvedSeasonIds,
            aniListNode = seasonAniList,
            aniZip = seasonAniZip
        )

        seasonResolutionCache[cacheKey] = result
        return result
    }

    // ─── Cache access for the provider ──────────────────────────────────────

    fun getCachedAnimeMeta(key: String): ResolvedAnimeMeta? = animeMetaCache[key]
    fun getCachedTmdbMeta(key: String): TmdbMeta? = tmdbMetaCache[key]
}