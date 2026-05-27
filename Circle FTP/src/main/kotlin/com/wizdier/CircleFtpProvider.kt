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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

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

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val anilistApi = "https://graphql.anilist.co"
    private val tmdbKey = "0b2d522346f5ecbafa42ae4b0141c774"
    private val tmdbImageBase = "https://image.tmdb.org/t/p/w500"
    private val tmdbBackdropBase = "https://image.tmdb.org/t/p/original"

    private val tmdbMetaCache = Collections.synchronizedMap(mutableMapOf<String, TmdbMeta?>())
    private val animeMetaCache = Collections.synchronizedMap(mutableMapOf<String, ResolvedAnimeMeta>())
    private val kitsuMetaCache = Collections.synchronizedMap(mutableMapOf<String, KitsuMeta?>())

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

    private fun stripAudioTags(title: String): String {
        return title.replace(Regex("""(?i)\b(hindi|english|tamil|telugu|malayalam|kannada|bengali|marathi|dual audio|multi audio|dubbed|subbed|eng\+jap)\b"""), "")
            .replace(Regex("""[\[\(\]-]"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }
    
    private fun extractAudioTag(title: String): String {
        val t = title.lowercase()
        return when {
            t.contains("dual audio") -> "Dual"
            t.contains("multi audio") -> "Multi"
            t.contains("hindi") && t.contains("english") -> "Dual"
            t.contains("dubbed") -> "Dub"
            t.contains("hindi") -> "Hin"
            t.contains("english") -> "Eng"
            t.contains("subbed") -> "Sub"
            else -> "Src"
        }
    }

    private fun isDubTitle(title: String): Boolean {
        val t = title.lowercase()
        return listOf("dubbed", "dual audio", "multi audio", "eng+jap", "english+japanese", "hindi", "english").any { t.contains(it) }
    }

    private fun isAnimeContent(categories: List<Category>?, title: String, tags: String? = null): Boolean {
        val t = title.lowercase()
        val tagList = tags?.lowercase() ?: ""
        if (categories?.any { it.id == 21 } == true) return true
        if (tagList.contains("anime") || t.contains("anime")) return true
        return false
    }

    private suspend fun fetchPostJson(postId: Int): String? {
        return try {
            val res = app.get("$mainApiUrl/api/posts/$postId", verify = false, cacheTime = 60)
            if (res.isSuccessful) res.text else app.get("$apiUrl/api/posts/$postId", verify = false, cacheTime = 60).text
        } catch (_: Exception) {
            try { app.get("$apiUrl/api/posts/$postId", verify = false, cacheTime = 60).text } catch (_: Exception) { null }
        }
    }

    private suspend fun getAniListMeta(title: String, retryCount: Int = 2): AniListMeta? {
        repeat(retryCount) { attempt ->
            try {
                val query = """
                    query (${'$'}search: String) {
                      Media(search: ${'$'}search, type: ANIME) {
                        id idMal format
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
                              id idMal format
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
                val body = toJson(mapOf("query" to query, "variables" to mapOf("search" to title)))
                    .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
                val res = app.post(anilistApi, requestBody = body, headers = mapOf("Content-Type" to "application/json"), cacheTime = 3600)
                return AppUtils.parseJson<AniListResponse>(res.text).data?.Media
            } catch (_: Exception) {
                if (attempt == retryCount - 1) return null
                delay(150)
            }
        }
        return null
    }

    private suspend fun getAniListMetaById(id: Int): AniListMeta? {
        return try {
            val query = """
                query (${'$'}id: Int) {
                  Media(id: ${'$'}id, type: ANIME) {
                    id idMal format
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
                          id idMal format
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
            val body = toJson(mapOf("query" to query, "variables" to mapOf("id" to id)))
                .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            val res = app.post(anilistApi, requestBody = body, headers = mapOf("Content-Type" to "application/json"), cacheTime = 86400)
            AppUtils.parseJson<AniListResponse>(res.text).data?.Media
        } catch (_: Exception) { null }
    }

    private suspend fun searchMalId(title: String): Int? = try {
        val res = app.get("https://api.jikan.moe/v4/anime?q=${URLEncoder.encode(title, "UTF-8")}&limit=1", cacheTime = 3600)
        JSONObject(res.text).optJSONArray("data")?.optJSONObject(0)?.optInt("mal_id")
    } catch (_: Exception) { null }

    private suspend fun getAniZipMeta(anilistId: Int): AniZipMeta? = try {
        val json = JSONObject(app.get("https://api.ani.zip/mappings?anilist_id=$anilistId", cacheTime = 86400).text)
        val m = json.optJSONObject("mappings")
        AniZipMeta(m?.optString("themoviedb_id"), m?.optString("kitsu_id"), m?.optInt("mal_id"), m?.optInt("simkl_id"))
    } catch (_: Exception) { null }

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
    
    private suspend fun fetchTmdbImages(tmdbId: Int?, isSeries: Boolean): Pair<String?, String?> {
        if (tmdbId == null) return null to null
        return try {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(app.get("$tmdbApi/$type/$tmdbId/images?api_key=$tmdbKey", cacheTime = 86400).text)
            
            val logos = json.optJSONArray("logos")
            var logoUrl: String? = null
            if (logos != null) {
                for (i in 0 until logos.length()) {
                    val l = logos.getJSONObject(i)
                    val path = l.optString("file_path")
                    if (path.isNotBlank() && (l.optString("iso_639_1") == "en" || l.optString("iso_639_1").isEmpty())) {
                        if (!path.endsWith(".svg")) {
                            logoUrl = "$tmdbImageBase$path"
                            break
                        }
                    }
                }
            }
            
            val backdrops = json.optJSONArray("backdrops")
            var backdropUrl: String? = null
            if (backdrops != null && backdrops.length() > 0) {
                val b = backdrops.getJSONObject(0)
                backdropUrl = "$tmdbBackdropBase${b.optString("file_path")}"
            }
            
            logoUrl to backdropUrl
        } catch (_: Exception) { null to null }
    }

    private suspend fun getTmdbMeta(title: String, year: Int?, isSeries: Boolean): TmdbMeta? {
        return try {
            val type = if (isSeries) "tv" else "movie"
            val yearParam = year?.let { if (isSeries) "&first_air_date_year=$it" else "&year=$it" } ?: ""
            val url = "$tmdbApi/search/$type?api_key=$tmdbKey&query=${URLEncoder.encode(title, "UTF-8")}$yearParam&language=en-US"
            val first = AppUtils.parseJson<TmdbSearchResponse>(app.get(url, cacheTime = 86400).text).results?.firstOrNull() ?: return null
            
            val (logo, backdrop) = fetchTmdbImages(first.id, isSeries)
            var imdbId: String? = null
            try {
                val detail = JSONObject(app.get("$tmdbApi/$type/${first.id}?api_key=$tmdbKey&append_to_response=external_ids", cacheTime = 86400).text)
                imdbId = detail.optJSONObject("external_ids")?.optString("imdb_id")
            } catch (_: Exception) {}
            
            TmdbMeta(
                poster = first.posterPath?.let { "$tmdbImageBase$it" },
                backdrop = first.backdropPath?.let { "$tmdbBackdropBase$it" } ?: backdrop,
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
            val json = JSONObject(app.get("$tmdbApi/$type/$tmdbId/videos?api_key=$tmdbKey", cacheTime = 86400).text)
            val results = json.optJSONArray("results") ?: return null
            var key: String? = null
            for (priority in listOf("Trailer", "Teaser", "Clip", "Featurette")) {
                for (i in 0 until results.length()) {
                    val v = results.getJSONObject(i)
                    if (v.optString("site").equals("YouTube", true) && v.optString("type").equals(priority, true)) {
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

    private suspend fun getTmdbMetaCached(title: String, year: Int?, isSeries: Boolean): TmdbMeta? {
        val key = "${isSeries}|${year ?: 0}|${title.lowercase()}"
        if (tmdbMetaCache.containsKey(key)) return tmdbMetaCache[key]
        val value = getTmdbMeta(title, year, isSeries)
        tmdbMetaCache[key] = value
        return value
    }

    private suspend fun getKitsuMetaCached(title: String): KitsuMeta? {
        val key = title.lowercase()
        if (kitsuMetaCache.containsKey(key)) return kitsuMetaCache[key]
        val value = getKitsuMeta(title)
        kitsuMetaCache[key] = value
        return value
    }

    private fun selectedSeasonIndex(url: String): Int? {
        return Regex("""[?&]season=(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun cleanApiUrl(url: String): String {
        return url.substringBefore("?")
    }

    private fun titleForSeason(baseTitle: String, seasonName: String?, seasonNumber: Int): String {
        val base = stripAudioTags(cleanTitle(baseTitle))
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

    private fun stripSeasonSuffixForAnime(title: String): String {
        return cleanTitle(title)
            .replace(Regex("""(?i)\banime\b"""), "")
            .replace(Regex("""(?i)\bseason\s*\d+\b"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
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
            img.optString("original").takeIf { it.isNotBlank() } ?: img.optString("large").takeIf { it.isNotBlank() }
        }
        val cover = attr.optJSONObject("coverImage")?.let { img ->
            img.optString("original").takeIf { it.isNotBlank() } ?: img.optString("large").takeIf { it.isNotBlank() }
        }
        KitsuMeta(
            id = id,
            title = resolvedTitle,
            poster = poster,
            cover = cover,
            synopsis = attr.optString("synopsis").takeIf { it.isNotBlank() },
            averageRating = attr.optString("averageRating").toDoubleOrNull()
        )
    } catch (_: Exception) { null }

    private suspend fun getKitsuMetaById(id: String): KitsuMeta? = try {
        val res = app.get(
            "https://kitsu.io/api/edge/anime/$id",
            headers = mapOf("Accept" to "application/vnd.api+json"),
            cacheTime = 86400
        )
        val attr = JSONObject(res.text).optJSONObject("data")?.optJSONObject("attributes") ?: return null
        val titles = attr.optJSONObject("titles")
        val resolvedTitle = titles?.optString("en")?.takeIf { it.isNotBlank() }
            ?: titles?.optString("en_jp")?.takeIf { it.isNotBlank() }
            ?: attr.optString("canonicalTitle").takeIf { it.isNotBlank() }
        val poster = attr.optJSONObject("posterImage")?.let { img ->
            img.optString("original").takeIf { it.isNotBlank() } ?: img.optString("large").takeIf { it.isNotBlank() }
        }
        val cover = attr.optJSONObject("coverImage")?.let { img ->
            img.optString("original").takeIf { it.isNotBlank() } ?: img.optString("large").takeIf { it.isNotBlank() }
        }
        KitsuMeta(
            id = id,
            title = resolvedTitle,
            poster = poster,
            cover = cover,
            synopsis = attr.optString("synopsis").takeIf { it.isNotBlank() },
            averageRating = attr.optString("averageRating").toDoubleOrNull()
        )
    } catch (_: Exception) { null }

    private suspend fun resolveAnimeMetaCached(title: String, year: Int? = null, isSeries: Boolean = true, seasonNumber: Int = 1): ResolvedAnimeMeta {
        val key = "${isSeries}|${year ?: 0}|${title.lowercase()}|$seasonNumber"
        animeMetaCache[key]?.let { return it }
        val value = resolveAnimeMeta(title, year, isSeries, seasonNumber)
        animeMetaCache[key] = value
        return value
    }

    private suspend fun resolveAnimeMeta(title: String, year: Int?, isSeries: Boolean, seasonNumber: Int): ResolvedAnimeMeta {
        val cleaned = stripSeasonSuffixForAnime(stripAudioTags(title))
        val baseTmdbMeta = getTmdbMetaCached(cleaned, year, isSeries)
        
        var aniList = getAniListMeta(cleaned) ?: getAniListMeta(title)
        
        // Accurate Tracker Sync: Follow the sequel graph to find Season 2, Season 3 precisely.
        if (seasonNumber > 1 && aniList != null) {
            var currentSeason = 1
            while (currentSeason < seasonNumber) {
                val sequelId = aniList?.relations?.edges?.firstOrNull { 
                    it.relationType.equals("SEQUEL", ignoreCase = true) 
                }?.node?.id
                
                if (sequelId != null) {
                    aniList = getAniListMetaById(sequelId)
                    currentSeason++
                } else {
                    // Fallback to exact string match query
                    val exactList = getAniListMeta(titleForSeason(cleaned, null, seasonNumber))
                    if (exactList != null) aniList = exactList
                    break
                }
            }
        }

        val aniZip = aniList?.id?.let { getAniZipMeta(it) }
        val malId = aniList?.idMal ?: aniZip?.malId ?: searchMalId(cleaned)
        val zipFromMal = if (aniZip == null && malId != null) getAniZipByMalId(malId) else null
        
        val kitsuId = aniZip?.kitsuid ?: zipFromMal?.kitsuId
        val kitsu = kitsuId?.let { getKitsuMetaById(it) } ?: getKitsuMetaCached(cleaned)
        
        val tmdbIdFromZip = aniZip?.themoviedbId?.toIntOrNull() ?: zipFromMal?.tmdbId?.toIntOrNull() ?: baseTmdbMeta?.tmdbId
        val (logoUrl, backdropUrl) = fetchTmdbImages(tmdbIdFromZip, isSeries)
        val trailerUrl = fetchTmdbTrailer(tmdbIdFromZip, isSeries) ?: getAniListTrailerUrl(aniList?.trailer)
        
        val displayTitle = aniList?.title?.english ?: aniList?.title?.romaji ?: kitsu?.title ?: cleaned
        return ResolvedAnimeMeta(
            title = displayTitle,
            poster = aniList?.coverImage?.extraLarge ?: aniList?.coverImage?.large ?: kitsu?.poster ?: baseTmdbMeta?.poster,
            background = aniList?.bannerImage ?: backdropUrl ?: kitsu?.cover ?: baseTmdbMeta?.backdrop,
            plot = aniList?.description?.replace(Regex("<[^>]*>"), "") ?: kitsu?.synopsis ?: baseTmdbMeta?.overview,
            score100 = aniList?.averageScore ?: kitsu?.averageRating?.toInt(),
            tags = aniList?.genres,
            trailer = trailerUrl,
            anilistEpisodes = aniList?.streamingEpisodes,
            logoUrl = logoUrl ?: baseTmdbMeta?.logoUrl,
            actors = aniList?.characters?.edges?.mapNotNull {
                it.node?.name?.full?.let { actorName -> ActorData(Actor(actorName, it.node.image?.large)) }
            },
            anilistId = aniList?.id,
            malId = malId,
            kitsuId = kitsuId ?: kitsu?.id,
            simklId = aniZip?.simklId ?: zipFromMal?.simklId,
            imdbId = baseTmdbMeta?.imdbId
        )
    }

    private suspend fun createMergedSearchResult(posts: List<Post>): SearchResponse? {
        if (posts.isEmpty()) return null
        val first = posts.first()
        val rawTitle = stripAudioTags(first.name?.ifBlank { first.title } ?: first.title ?: "")
        if (rawTitle.isBlank()) return null
        
        val ids = posts.joinToString(",") { it.id.toString() }
        val url = "$mainUrl/content/$ids"
        val quality = getSearchQuality((first.quality ?: first.title ?: "").lowercase())
        val isSeries = first.type == "series"
        val isAnime = isAnimeContent(first.categories, first.title ?: "", first.tags)
        val fallbackPoster = first.imageSm?.let { "$mainApiUrl/uploads/$it" }
        val year = selectUntilNonInt(first.year)
        val cachedPoster = if (isAnime) {
            animeMetaCache["${isSeries}|${year ?: 0}|${rawTitle.lowercase()}|1"]?.poster
        } else {
            tmdbMetaCache["${isSeries}|${year ?: 0}|${cleanTitle(rawTitle).lowercase()}"]?.poster
        }
        val hasDub = posts.any { isDubTitle(it.title ?: "") }
        
        return if (isAnime) {
            newAnimeSearchResponse(rawTitle, url, if (isSeries) TvType.Anime else TvType.AnimeMovie) {
                this.posterUrl = cachedPoster ?: fallbackPoster
                this.quality = quality
                addDubStatus(dubExist = hasDub, subExist = true)
            }
        } else if (isSeries) {
            newTvSeriesSearchResponse(rawTitle, url, TvType.TvSeries) {
                this.posterUrl = cachedPoster ?: fallbackPoster
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(rawTitle, url, TvType.Movie) {
                this.posterUrl = cachedPoster ?: fallbackPoster
                this.quality = quality
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = try {
            app.get("$mainApiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10", verify = false, cacheTime = 60).text
        } catch (_: Exception) {
            try { app.get("$apiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10", verify = false, cacheTime = 60).text } catch(_: Exception) { "{}" }
        }
        
        val posts = try { AppUtils.parseJson<PageData>(json).posts ?: emptyList() } catch(_: Exception) { emptyList() }
        val groupedPosts = posts.groupBy {
            val clean = stripAudioTags(it.name?.ifBlank { it.title } ?: it.title ?: "").lowercase()
            "$clean|${it.type}|${selectUntilNonInt(it.year) ?: 0}"
        }.values.toList()
        
        val home = coroutineScope {
            groupedPosts.map { group -> async { createMergedSearchResult(group) } }
                .awaitAll()
                .filterNotNull()
        }
        return newHomePageResponse(request.name, home, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = try {
            app.get("$mainApiUrl/api/posts?searchTerm=$query&order=desc", verify = false, cacheTime = 60).text
        } catch (_: Exception) {
            try { app.get("$apiUrl/api/posts?searchTerm=$query&order=desc", verify = false, cacheTime = 60).text } catch(_: Exception) { "{}" }
        }
        
        val posts = try { AppUtils.parseJson<PageData>(json).posts ?: emptyList() } catch(_: Exception) { emptyList() }
        val groupedPosts = posts.groupBy {
            val clean = stripAudioTags(it.name?.ifBlank { it.title } ?: it.title ?: "").lowercase()
            "$clean|${it.type}|${selectUntilNonInt(it.year) ?: 0}"
        }.values.toList()
        
        return coroutineScope {
            groupedPosts.map { group -> async { createMergedSearchResult(group) } }
                .awaitAll()
                .filterNotNull()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val idString = cleanApiUrl(url).substringAfterLast("/").substringBefore("?")
        val postIds = idString.split(",").mapNotNull { it.trim().toIntOrNull() }.distinct()
        
        if (postIds.isEmpty()) throw ErrorLoadingException("No matching IDs found for $url")
        val allData = mutableListOf<Triple<Int, Data, String>>()
        for (id in postIds) {
            val jsonText = fetchPostJson(id) ?: continue
            try {
                val pData = AppUtils.parseJson<Data>(jsonText)
                allData.add(Triple(id, pData, jsonText))
            } catch (e: Exception) {
                // Ignore broken entries gracefully
            }
        }
        
        if (allData.isEmpty()) throw ErrorLoadingException("Failed to load metadata for these IDs.")
        val primaryData = allData.first().second
        val baseTitle = stripAudioTags(primaryData.name?.ifBlank { primaryData.title } ?: primaryData.title ?: "")
        val fallbackPoster = primaryData.image?.let { "$apiUrl/uploads/$it" }
        val year = selectUntilNonInt(primaryData.year)
        val isAnime = isAnimeContent(primaryData.categories, baseTitle, primaryData.tags)
        val selectedSeason = selectedSeasonIndex(url)
        val isDubbedGlobal = allData.any { isDubTitle(it.second.title ?: "") }

        if (primaryData.type == "singleVideo") {
            val duration = getDurationFromString(primaryData.watchTime)
            
            val links = mutableListOf<Pair<String, String>>()
            for ((_, pData, jsonText) in allData) {
                val movie = try { AppUtils.parseJson<Movies>(jsonText) } catch(_: Exception) { null }
                val linkStr = movie?.content ?: continue
                val link = if (linkStr.contains(mainApiUrl)) linkStr else linkToIp(linkStr)
                val audioTag = extractAudioTag(pData.title ?: "")
                links.add(audioTag to link)
            }
            
            val episodeData = links.joinToString("%%%") { "${it.first}|||${it.second}" }
            
            val episodesData = mutableListOf<Episode>()
            episodesData.add(newEpisode(episodeData) {
                this.name = "Movie"
            })

            if (isAnime) {
                val meta = resolveAnimeMetaCached(baseTitle, year, false, 1)
                return newAnimeLoadResponse(meta.title.ifBlank { baseTitle }, url, TvType.AnimeMovie) {
                    this.posterUrl = meta.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta.background ?: fallbackPoster
                    this.year = year
                    this.plot = meta.plot ?: primaryData.metaData
                    this.tags = meta.tags
                    this.score = Score.from100(meta.score100)
                    this.duration = duration
                    this.actors = meta.actors
                    this.logoUrl = meta.logoUrl
                    meta.trailer?.let { addTrailer(it) }
                    meta.anilistId?.let { addAniListId(it) }
                    meta.malId?.let { addMalId(it) }
                    meta.kitsuId?.let { addKitsuId(it) }
                    meta.simklId?.let { addSimklId(it) }
                    meta.imdbId?.let { addImdbId(it) }
                    addEpisodes(if (isDubbedGlobal) DubStatus.Dubbed else DubStatus.Subbed, episodesData)
                }
            } else {
                val meta = getTmdbMetaCached(cleanTitle(baseTitle), year, false)
                val trailer = fetchTmdbTrailer(meta?.tmdbId, false)
                val actors = fetchTmdbActors(meta?.tmdbId, false)
                return newMovieLoadResponse(baseTitle, url, TvType.Movie, episodesData) {
                    this.posterUrl = meta?.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta?.backdrop ?: fallbackPoster
                    this.year = year
                    this.plot = meta?.overview ?: primaryData.metaData
                    this.score = Score.from10(meta?.rating)
                    this.duration = duration
                    this.actors = actors
                    this.logoUrl = meta?.logoUrl
                    trailer?.let { addTrailer(it) }
                    meta?.imdbId?.let { addImdbId(it) }
                }
            }
        } else {
            val targetSeasonIndex = selectedSeason ?: 0
            val seasonNumberForMeta = targetSeasonIndex + 1
            
            if (isAnime) {
                val primaryTvSeries = try { AppUtils.parseJson<TvSeries>(allData.first().third) } catch(_: Exception) { null }
                val allSeasons = primaryTvSeries?.content ?: emptyList()
                val metaTitle = titleForSeason(baseTitle, allSeasons.getOrNull(targetSeasonIndex)?.seasonName, seasonNumberForMeta)
                val meta = resolveAnimeMetaCached(metaTitle, year, true, seasonNumberForMeta)
                
                val recommendations = allSeasons.mapIndexedNotNull { index, season ->
                    if (index == targetSeasonIndex) return@mapIndexedNotNull null
                    val recSeasonNumber = index + 1
                    val recTitle = titleForSeason(baseTitle, season.seasonName, recSeasonNumber)
                    val recUrl = "$mainUrl/content/$idString?season=$index"
                    newAnimeSearchResponse(recTitle, recUrl, TvType.Anime) {
                        this.posterUrl = meta.poster ?: fallbackPoster 
                    }
                }
                
                val episodesMap = mutableMapOf<Int, Pair<String?, MutableList<Pair<String, String>>>>()
                for ((_, pData, jsonText) in allData) {
                    val tvSeries = try { AppUtils.parseJson<TvSeries>(jsonText) } catch(_: Exception) { null } ?: continue
                    val seasonToLoad = tvSeries.content?.getOrNull(targetSeasonIndex) ?: continue
                    val audioTag = extractAudioTag(pData.title ?: "")
                    
                    val episodes = seasonToLoad.episodes ?: emptyList()
                    for ((idx, ep) in episodes.withIndex()) {
                        val linkStr = ep.link ?: continue
                        val link = if (linkStr.contains(mainApiUrl)) linkStr else linkToIp(linkStr)
                        val entry = episodesMap.getOrPut(idx) { ep.title?.takeIf { it.isNotBlank() } to mutableListOf() }
                        entry.second.add(audioTag to link)
                    }
                }

                val episodesData = mutableListOf<Episode>()
                val sortedEpisodes = episodesMap.entries.sortedBy { it.key }
                for (entryData in sortedEpisodes) {
                    val idx = entryData.key
                    val (epTitle, linksList) = entryData.value
                    val aniEp = meta.anilistEpisodes?.getOrNull(idx)
                    val episodeData = linksList.joinToString("%%%") { "${it.first}|||${it.second}" }
                    
                    episodesData.add(newEpisode(episodeData) {
                        this.episode = idx + 1
                        // Set season = 1. Trackers treat isolated AniList anime sequel IDs as exactly "Season 1" of themselves.
                        this.season = 1
                        this.name = aniEp?.title ?: epTitle ?: "Episode ${idx + 1}"
                        this.posterUrl = aniEp?.thumbnail
                    })
                }

                return newAnimeLoadResponse(meta.title.ifBlank { metaTitle }, url, TvType.Anime) {
                    this.posterUrl = meta.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta.background ?: fallbackPoster
                    this.year = year
                    this.plot = meta.plot ?: primaryData.metaData
                    this.tags = meta.tags
                    this.score = Score.from100(meta.score100)
                    this.actors = meta.actors
                    this.logoUrl = meta.logoUrl
                    this.recommendations = recommendations
                    meta.trailer?.let { addTrailer(it) }
                    meta.anilistId?.let { addAniListId(it) }
                    meta.malId?.let { addMalId(it) }
                    meta.kitsuId?.let { addKitsuId(it) }
                    meta.simklId?.let { addSimklId(it) }
                    meta.imdbId?.let { addImdbId(it) }
                    addEpisodes(if (isDubbedGlobal) DubStatus.Dubbed else DubStatus.Subbed, episodesData)
                }
            } else {
                val meta = getTmdbMetaCached(cleanTitle(baseTitle), year, true)
                val tmdbId = meta?.tmdbId
                val trailer = fetchTmdbTrailer(tmdbId, true)
                val actors = fetchTmdbActors(tmdbId, true)
                
                val episodesMap = mutableMapOf<Pair<Int, Int>, Pair<String?, MutableList<Pair<String, String>>>>()
                for ((_, pData, jsonText) in allData) {
                    val tvSeries = try { AppUtils.parseJson<TvSeries>(jsonText) } catch(_: Exception) { null } ?: continue
                    val audioTag = extractAudioTag(pData.title ?: "")
                    var seasonNum = 0
                    
                    for (season in tvSeries.content ?: emptyList()) {
                        seasonNum++
                        val epList = season.episodes ?: emptyList()
                        for ((idx, ep) in epList.withIndex()) {
                            val linkStr = ep.link ?: continue
                            val link = if (linkStr.contains(mainApiUrl)) linkStr else linkToIp(linkStr)
                            val epKey = seasonNum to idx
                            val entry = episodesMap.getOrPut(epKey) { ep.title?.takeIf { it.isNotBlank() } to mutableListOf() }
                            entry.second.add(audioTag to link)
                        }
                    }
                }

                val tmdbSeasonCache = mutableMapOf<Int, List<TmdbEpisode>>()
                val episodesData = mutableListOf<Episode>()
                
                val sortedTvEpisodes = episodesMap.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
                for (entryData in sortedTvEpisodes) {
                    val (seasonNum, idx) = entryData.key
                    val (epTitle, linksList) = entryData.value
                    
                    if (tmdbId != null && !tmdbSeasonCache.containsKey(seasonNum)) {
                        tmdbSeasonCache[seasonNum] = fetchTmdbSeasonEpisodes(tmdbId, seasonNum)
                    }
                    val tmdbEp = tmdbSeasonCache[seasonNum]?.getOrNull(idx)
                    val episodeData = linksList.joinToString("%%%") { "${it.first}|||${it.second}" }
                    
                    episodesData.add(newEpisode(episodeData) {
                        this.episode = idx + 1
                        this.season = seasonNum
                        this.name = tmdbEp?.name ?: epTitle ?: "Episode ${idx + 1}"
                        this.posterUrl = tmdbEp?.stillPath?.let { "$tmdbImageBase$it" }
                        this.description = tmdbEp?.overview
                        tmdbEp?.airDate?.let { addDate(it) }
                    })
                }

                return newTvSeriesLoadResponse(baseTitle, url, TvType.TvSeries, episodesData) {
                    this.posterUrl = meta?.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta?.backdrop ?: fallbackPoster
                    this.year = year
                    this.plot = meta?.overview ?: primaryData.metaData
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
        if (data.contains("|||")) {
            val links = data.split("%%%")
            for (linkItem in links) {
                val parts = linkItem.split("|||")
                if (parts.size >= 2) {
                    val tag = parts[0]
                    val url = parts[1]
                    val sourceName = if (tag.isBlank() || tag == "Src") this.name else "${this.name} [$tag]"
                    
                    callback.invoke(
                        newExtractorLink(
                            source = sourceName,
                            name = sourceName,
                            url = url,
                            type = if (url.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "" // Blank prevents BDIX FTP blocks
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } else {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = data,
                    type = if (data.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "" // Blank prevents BDIX FTP blocks
                    this.quality = Qualities.Unknown.value
                }
            )
        }
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

    data class PageData(val posts: List<Post>?)
    data class KitsuMeta(val id: String?, val title: String?, val poster: String?, val cover: String?, val synopsis: String?, val averageRating: Double?)
    data class ResolvedAnimeMeta(val title: String, val poster: String?, val background: String?, val plot: String?, val score100: Int?, val tags: List<String>?, val trailer: String?, val anilistEpisodes: List<AniListStreamingEpisode>?, val logoUrl: String?, val actors: List<ActorData>?, val anilistId: Int?, val malId: Int?, val kitsuId: String?, val simklId: Int?, val imdbId: String?)
    data class Post(val id: Int, val type: String?, val imageSm: String?, val title: String?, val name: String? = null, val image: String? = null, val cover: String? = null, val quality: String? = null, val year: String? = null, val tags: String? = null, val categories: List<Category>? = null)
    data class Data(val type: String?, val imageSm: String?, val title: String?, val image: String?, val metaData: String?, val name: String?, val quality: String?, val year: String?, val watchTime: String?, val categories: List<Category>?, val tags: String? = null)
    data class Category(val id: Int, val name: String?)
    data class TvSeries(val content: List<Content>?)
    data class Content(val episodes: List<EpisodeData>?, val seasonName: String?)
    data class EpisodeData(val link: String?, val title: String?)
    data class Movies(val content: String?)
    data class AniListResponse(val data: AniListData?)
    data class AniListData(val Media: AniListMeta?)
    data class AniListMeta(val id: Int?, val idMal: Int?, val format: String?, val coverImage: AniListCoverImage?, val bannerImage: String?, val averageScore: Int?, val genres: List<String>?, val description: String?, val title: AniListTitle?, val trailer: AniListTrailer?, val streamingEpisodes: List<AniListStreamingEpisode>?, val episodes: Int?, val characters: AniListCharacterConnection?, val relations: AniListRelationConnection?)
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
    data class AniZipMeta(val themoviedbId: String?, val kitsuid: String?, val malId: Int?, val simklId: Int?)
    data class AniZipFull(val anilistId: Int?, val malId: Int?, val kitsuId: String?, val simklId: Int?, val tmdbId: String?)
    data class TmdbSearchResponse(val results: List<TmdbResult>?)
    data class TmdbResult(val id: Int?, val posterPath: String?, val backdropPath: String?, val voteAverage: Double?, val overview: String?)
    data class TmdbMeta(val poster: String?, val backdrop: String?, val rating: Double?, val overview: String?, val logoUrl: String?, val imdbId: String?, val tmdbId: Int?)
    data class TmdbEpisode(val name: String?, val overview: String?, val stillPath: String?, val airDate: String?, val rating: Double?)
}
