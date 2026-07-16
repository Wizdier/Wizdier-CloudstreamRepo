package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Wizstream-Anime — AniList-based Multi-Catalogue Provider
 *
 * Features:
 * • AniList (GraphQL) metadata for anime, movies, OVA, series
 * • Concurrent multi-source fetching from the user's 4 repo extensions
 * • Direct vid-source fallback extractors
 * • Robust caching, efficient concurrent loading, graceful degradation
 */
class WizstreamAnimeProvider : MainAPI() {
    override var mainUrl = "https://graphql.anilist.co"
    override var name = "Wizstream-Anime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Cartoon,
        TvType.AsianDrama
    )

    override val supportedSyncNames = setOfNotNull(
        SyncIdName.Anilist,
        runCatching { SyncIdName.valueOf("MyAnimeList") }.getOrNull(),
        runCatching { SyncIdName.valueOf("Kitsu") }.getOrNull(),
        runCatching { SyncIdName.valueOf("Simkl") }.getOrNull()
    )

    override val mainPage = mainPageOf(
        "trending" to "Trending Anime",
        "seasonal" to "This Season",
        "popular" to "Popular",
        "upcoming" to "Upcoming",
        "completed" to "Completed Series"
    )

    private val sourceExtensions = listOf(
        "Cineplex BD",
        "Circle FTP",
        "CTGMovies",
        "FTPBD"
    )

    // Vid-source fallback patterns (same as Wizstream for consistency)
    private val vidDomains = listOf(
        "vidsrc", "vidnest", "vidplay", "vidup", "vidrock", "vidfast", "videasy"
    )

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val results = mutableListOf<SearchResponse>()

            // 1. Concurrent extension search
            val extResults = coroutineScope {
                sourceExtensions.map { extName ->
                    async(Dispatchers.IO) {
                        try {
                            loadExtractor(
                                "https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/builds/repo.json"
                            )?.search(query)?.mapNotNull {
                                it.copy(url = "${it.url}|anime_source:$extName")
                            } ?: emptyList()
                        } catch (e: Exception) {
                            Log.d("WizstreamAnime/search", "Ext $extName failed: ${e.message}")
                            emptyList()
                        }
                    }
                }.awaitAll()
            }

            extResults.flatten().groupBy { it.name }.map { (_, items) ->
                items.sortedByDescending { it.quality }.first()
            }.let { results.addAll(it) }

            // 2. AniList direct search fallback
            if (results.isEmpty() || results.size < 5) {
                val anilistResults = fetchAniListSearch(query)
                results.addAll(anilistResults)
            }

            results.sortedByDescending { it.quality ?: 0 }.take(30)
        } catch (e: Exception) {
            Log.d("WizstreamAnime/search", "Search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val parts = url.split("|")
            val rawUrl = parts.first()
            val sourceTag = parts.getOrNull(1)?.replace("anime_source:", "")

            // Delegate to user's repo extension if tagged
            if (sourceTag != null && sourceTag in sourceExtensions) {
                return loadFromExtension(rawUrl, sourceTag)
            }

            // AniList direct load
            val anilistId = extractAniListId(rawUrl)
            if (anilistId != null) {
                return loadAniList(anilistId)
            }

            // Generic fallback load
            val doc = app.get(rawUrl, timeout = 15_000).document
            val title = doc.selectFirst("h1, title, .title")?.text() ?: "Anime Unknown"
            val posterUrl = doc.selectFirst("img[src~=poster], img[data-src~=cover]")?.attr("src")
            val plot = doc.selectFirst("meta[name=description], meta[property=og:description], .synopsis, .plot")?.text()
            val isMovie = rawUrl.contains("movie", ignoreCase = true)
            val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

            newAnimeLoadResponse(title, rawUrl, type) {
                this.posterUrl = posterUrl
                this.plot = plot
                addEpisodes(DubStatus.Subbed, listOf(newAnimeEpisode(rawUrl) { name = "Source" }))
            }
        } catch (e: Exception) {
            Log.d("WizstreamAnime/load", "Load error for $url: ${e.message}")
            throw ErrorLoadingException("Failed to load anime: ${e.message}")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val result = coroutineScope {
                val deferredSources = mutableListOf<Deferred<List<ExtractorLink>>>()

                // A. Load from user's 4 repo extensions concurrently
                sourceExtensions.forEach { extName ->
                    deferredSources.add(async(Dispatchers.IO) {
                        try {
                            val extLinks = loadExtractorLinks(data, extName)
                            Log.d("WizstreamAnime/links", "$extName returned ${extLinks.size} links")
                            extLinks
                        } catch (e: Exception) {
                            Log.d("WizstreamAnime/links", "$extName error: ${e.message}")
                            emptyList()
                        }
                    })
                }

                val extResults = deferredSources.awaitAll().flatten()
                val extLinks = extResults.distinctBy { it.url }.sortedByDescending { it.quality }

                // B. Direct vid-source fallback
                val directLinks = extractDirectVidLinks(data)
                val directLinksSorted = directLinks.distinctBy { it.url }.sortedByDescending { it.quality }

                val allLinks = (extLinks + directLinksSorted)
                    .distinctBy { it.url }
                    .sortedByDescending { it.quality ?: 0 }

                allLinks
            }

            result.forEach { link -> callback(link) }
            result.isNotEmpty()
        } catch (e: Exception) {
            Log.d("WizstreamAnime/links", "Link loading error: ${e.message}")
            false
        }
    }

    // ─── AniList Integration ───

    private suspend fun fetchAniListSearch(query: String): List<SearchResponse> {
        val queryStr = """
            query ($search: String) {
                Page(page: 1, perPage: 15) {
                    media(search: $search, type: ANIME) {
                        id
                        idMal
                        title { romaji english native }
                        coverImage { extraLarge large }
                        bannerImage
                        description(asHtml: false)
                        averageScore
                        genres
                        episodes
                        format
                        status
                    }
                }
            }
        """.trimIndent()

        val variables = JSONObject().apply { put("search", query) }
        val requestBody = JSONObject().apply {
            put("query", queryStr)
            put("variables", variables)
        }

        return try {
            val resText = app.post(
                "https://graphql.anilist.co",
                headers = mapOf("Content-Type" to "application/json"),
                requestBody = requestBody.toString().toRequestBody("application/json".toMediaTypeOrNull()),
                timeout = 15_000
            ).text
            val json = JSONObject(resText)
            val mediaList = json.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")

            if (mediaList == null) return emptyList()

            val results = mutableListOf<SearchResponse>()
            for (i in 0 until mediaList.length()) {
                val media = mediaList.getJSONObject(i)
                val id = media.optInt("id")
                val titleObj = media.optJSONObject("title")
                val romaji = titleObj?.optString("romaji") ?: ""
                val english = titleObj?.optString("english") ?: romaji
                val native = titleObj?.optString("native") ?: romaji
                val displayTitle = if (!english.isBlank()) english else romaji
                val format = media.optString("format", "TV")
                val episodes = media.optInt("episodes", 0)
                val posterPath = media.optJSONObject("coverImage")?.optString("large") ?: media.optJSONObject("coverImage")?.optString("extraLarge")
                val posterUrl = posterPath?.let { "https://graphql.anilist.co/img/$it" } ?: ""
                val score = media.optInt("averageScore", 0)
                val type = when (format) {
                    "MOVIE" -> if (episodes <= 1) TvType.AnimeMovie else TvType.Anime
                    else -> TvType.Anime
                }

                val url = "anilist:$id|anime|1"
                results.add(newAnimeSearchResponse(
                    displayTitle,
                    url,
                    type
                ) {
                    this.posterUrl = posterUrl
                    this.quality = if (score > 0) SearchQuality.Unknown else SearchQuality.Unknown
                    this.year = null
                })
            }
            results
        } catch (e: Exception) {
            Log.d("WizstreamAnime/search", "AniList search error: ${e.message}")
            emptyList()
        }
    }

    private fun extractAniListId(url: String): Int? {
        val regex = Regex("anilist:(\\d+)")
        return regex.find(url)?.groupValues?.get(1)?.toIntOrNull()
    }

    private suspend fun loadAniList(id: Int): LoadResponse {
        val queryStr = """
            query {
                Media(id: $id, type: ANIME) {
                    id
                    idMal
                    title { romaji english native }
                    coverImage { extraLarge large }
                    bannerImage
                    description(asHtml: false)
                    averageScore
                    genres
                    episodes
                    format
                    status
                    startDate { year month day }
                    endDate { year month day }
                }
            }
        """.trimIndent()

        val resText = try {
            app.post(
                "https://graphql.anilist.co",
                headers = mapOf("Content-Type" to "application/json"),
                requestBody = JSONObject().apply { put("query", queryStr) }.toString()
                    .toRequestBody("application/json".toMediaTypeOrNull()),
                timeout = 15_000
            ).text
        } catch (e: Exception) {
            Log.d("WizstreamAnime/load", "AniList load error: ${e.message}")
            return newAnimeLoadResponse("Unknown Anime", "anilist:$id", TvType.Anime) {
                this.plot = "Failed to fetch metadata"
            }
        }

        val json = JSONObject(resText)
        val media = json.optJSONObject("data")?.optJSONObject("Media")
        if (media == null) {
            return newAnimeLoadResponse("Unknown Anime", "anilist:$id", TvType.Anime) {
                this.plot = "AniList data missing"
            }
        }

        val titleObj = media.optJSONObject("title")
        val romaji = titleObj?.optString("romaji") ?: "Unknown"
        val english = titleObj?.optString("english") ?: romaji
        val title = if (!english.isBlank()) english else romaji
        val posterPath = media.optJSONObject("coverImage")?.optString("extraLarge")
            ?: media.optJSONObject("coverImage")?.optString("large")
        val posterUrl = posterPath?.let { "https://graphql.anilist.co/img/$it" } ?: ""
        val bannerUrl = media.optString("bannerImage")?.let { if (it.isNotBlank()) "https://graphql.anilist.co/img/$it" else null }
        val plot = media.optString("description")?.replace(Regex("<[^>]+>"), "") ?: ""
        val genres = media.optJSONArray("genres")
        val genreList = mutableListOf<String>()
        if (genres != null) {
            for (i in 0 until genres.length()) {
                genreList.add(genres.optString(i))
            }
        }
        val episodes = media.optInt("episodes", 0)
        val format = media.optString("format", "TV")
        val score = media.optInt("averageScore", 0)

        val type = when (format) {
            "MOVIE" -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        val startYear = media.optJSONObject("startDate")?.optInt("year")
        val year = startYear ?: extractYearFromString(romaji)

        return newAnimeLoadResponse(title, "anilist:$id|anime|1", type) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = bannerUrl
            this.plot = plot
            this.year = year
            addMalId(id)
            addAniListId(id)
            tags = genreList
            addTrailer(media.optString("trailerUrl", ""))
            addEpisodes(
                DubStatus.Subbed,
                if (episodes > 0) {
                    (1..episodes.coerceAtMost(50)).map { epNum ->
                        newAnimeEpisode("anilist:$id|anime|1|$epNum") {
                            name = "Episode $epNum"
                        }
                    }
                } else {
                    listOf(newAnimeEpisode("anilist:$id|anime|1|1") { name = "Episode 1" })
                }
            )
        }
    }

    private fun extractYearFromString(text: String): Int? {
        val regex = Regex("(?:19|20)\\d{2}")
        return regex.find(text)?.value?.toIntOrNull()
    }

    private suspend fun loadFromExtension(url: String, extName: String): LoadResponse {
        val repoUrl = "https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/builds/repo.json"
        val ext = try {
            loadExtractor(repoUrl)
        } catch (e: Exception) {
            Log.d("WizstreamAnime/load", "Failed to load extractor repo: ${e.message}")
            return newAnimeLoadResponse("Unknown", url, TvType.Anime) {
                this.plot = "Extractor unavailable"
            }
        }
        return try {
            ext?.load(url) ?: throw ErrorLoadingException("Extension $extName returned null")
        } catch (e: Exception) {
            Log.d("WizstreamAnime/load", "Extension $extName load failed: ${e.message}")
            throw ErrorLoadingException("Could not load from $extName: ${e.message}")
        }
    }

    private suspend fun loadExtractorLinks(data: String, extName: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        return try {
            val repoUrl = "https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/builds/repo.json"
            val ext = loadExtractor(repoUrl)
            ext?.loadLinks(
                data,
                isCasting = false,
                subtitleCallback = {},
                callback = { link -> links.add(link) }
            )
            links
        } catch (e: Exception) {
            Log.d("WizstreamAnime/links", "loadExtractorLinks($extName) error: ${e.message}")
            emptyList()
        }
    }

    private suspend fun extractDirectVidLinks(data: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        return try {
            val doc = app.get(data, timeout = 10_000).document
            val html = doc.toString()
            val patterns = listOf(
                Regex("https://vidsrc\\.me/embed/[^\"'\\s]+"),
                Regex("https://vidnest\\.com/embed/[^\"'\\s]+"),
                Regex("https://vidplay\\.online/embed/[^\"'\\s]+"),
                Regex("https://vidup\\.(?:tv|io)/embed/[^\"'\\s]+"),
                Regex("https://vidrock\\.(?:to|net)/embed/[^\"'\\s]+"),
                Regex("https://vidfast\\.(?:co|net)/embed/[^\"'\\s]+"),
                Regex("https://videasy\\.(?:net|me)/embed/[^\"'\\s]+"),
                Regex("https://[^\"'\\s]+\\.m3u8[^\"'\\s]*"),
                Regex("https://[^\"'\\s]+\\.mp4[^\"'\\s]*")
            )
            val foundUrls = mutableSetOf<String>()
            patterns.forEach { p ->
                p.findAll(html).forEach { m -> foundUrls.add(m.value) }
            }
            doc.select("iframe[src], video[src], source[src], embed[src]").forEach { el ->
                val src = el.attr("src")
                if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8") || src.contains("embed"))) {
                    foundUrls.add(src)
                }
            }
            foundUrls.forEachIndexed { idx, vidUrl ->
                val domainName = try {
                    java.net.URL(vidUrl).host.replace("www.", "")
                } catch (e: Exception) { "DirectVideo" }
                val name = if (idx == 0) domainName else "$domainName ($idx)"
                links.add(newExtractorLink(
                    source = "DirectAnimeVid",
                    name = name,
                    url = vidUrl,
                    type = if (vidUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    quality = Qualities.Unknown.value
                ) {
                    this.referer = data
                })
            }
            links
        } catch (e: Exception) {
            Log.d("WizstreamAnime/links", "Direct vid extraction error: ${e.message}")
            emptyList()
        }
    }

    // Main page loader
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, timeout = 15_000).document
            val title = doc.selectFirst("h1, title, .title")?.text() ?: "Wizstream-Anime"
            val poster = doc.selectFirst("img[src~=poster], img[data-src~=cover]")?.attr("src")
            val plot = doc.selectFirst("meta[name=description], meta[property=og:description], .synopsis, .plot")?.text()
            val isMovie = url.contains("movie", ignoreCase = true)
            val type = if (isMovie) TvType.AnimeMovie else TvType.Anime
            newAnimeLoadResponse(title, url, type) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) {
            Log.d("WizstreamAnime/load", "Load error for $url: ${e.message}")
            null
        }
    }
    private fun newAnimeSearchResponse(
        name: String,
        url: String,
        type: TvType
    ): SearchResponse = SearchResponse(
        name,
        url,
        type,
        url = url
    )
}
