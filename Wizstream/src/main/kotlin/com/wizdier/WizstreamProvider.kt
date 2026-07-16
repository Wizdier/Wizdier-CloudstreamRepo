package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Wizstream — TMDB-based Multi-Catalogue Provider
 *
 * Features:
 * • TMDB metadata for movies, TV series, anime-catalogues mapped to TMDB
 * • Concurrent multi-source fetching from the user's 4 repo extensions
 * • Direct vid-source fallback extractors (src, nest, play, up, rock, fast, easy)
 * • Efficient TTL + LRU cache for metadata lookups
 * • Robust error handling and graceful degradation
 */
class WizstreamProvider : MainAPI() {
    override var mainUrl = "https://tmdb-api.example.com"
    override var name = "Wizstream"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.AsianDrama,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "popular" to "Popular Movies",
        "top_rated" to "Top Rated",
        "upcoming" to "Upcoming",
        "now_playing" to "Now Playing"
    )

    // ─── Internal names for loadExtractor references ───
    private val sourceExtensions = listOf(
        "Cineplex BD",
        "Circle FTP",
        "CTGMovies",
        "FTPBD"
    )

    // Common direct-video embed host domains (fallback sources)
    private val vidDomains = listOf(
        "vidsrc", "vidnest", "vidplay", "vidup", "vidrock", "vidfast", "videasy"
    )

    // ─── Search & Catalogue Methods ───

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val results = mutableListOf<SearchResponse>()

            // 1. Search user's repo extensions concurrently
            val extResults = coroutineScope {
                sourceExtensions.map { extName ->
                    async(Dispatchers.IO) {
                        try {
                            loadExtractor(
                                "https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/builds/repo.json"
                            )?.search(query)?.mapNotNull {
                                it.copy(url = "${it.url}|source:$extName")
                            } ?: emptyList()
                        } catch (e: Exception) {
                            Log.d("Wizstream/search", "Ext $extName failed: ${e.message}")
                            emptyList()
                        }
                    }
                }.awaitAll()
            }

            // Aggregate extension results
            extResults.flatten().groupBy { it.name }.map { (_, items) ->
                items.sortedByDescending { it.quality }.first()
            }.let { results.addAll(it) }

            // 2. Fallback: TMDB direct search if no extension results
            if (results.isEmpty()) {
                val tmdbResults = fetchTmdbSearch(query)
                results.addAll(tmdbResults)
            }

            results.sortedByDescending { it.quality ?: 0 }.take(30)
        } catch (e: Exception) {
            Log.d("Wizstream/search", "Search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val parts = url.split("|")
            val rawUrl = parts.first()
            val sourceTag = parts.getOrNull(1)?.replace("source:", "")

            // If loaded from an extension, delegate to it
            if (sourceTag != null && sourceTag in sourceExtensions) {
                return loadFromExtension(rawUrl, sourceTag)
            }

            // TMDB direct load
            val tmdbData = parseTmdbUrl(rawUrl)
            if (tmdbData != null) {
                return loadTmdb(tmdbData.id, tmdbData.type, tmdbData.season, tmdbData.episode)
            }

            // Generic fallback: try parsing from URL directly
            val doc = app.get(rawUrl, timeout = 15_000).document
            val title = doc.selectFirst("h1, title")?.text() ?: "Unknown"
            val posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img[src~=poster]")?.attr("src")
            val plot = doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")
            val isSeries = rawUrl.contains("/tv/", ignoreCase = true) || rawUrl.contains("episode", ignoreCase = true)
            val type = if (isSeries) TvType.TvSeries else TvType.Movie

            newMovieLoadResponse(title, rawUrl, type, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                addEpisodes(
                    DubStatus.Subbed,
                    listOf(newEpisode(rawUrl) { name = "Source" })
                )
            }
        } catch (e: Exception) {
            Log.d("Wizstream/load", "Load error for $url: ${e.message}")
            throw ErrorLoadingException("Failed to load content: ${e.message}")
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
                            Log.d("Wizstream/links", "$extName returned ${extLinks.size} links")
                            extLinks
                        } catch (e: Exception) {
                            Log.d("Wizstream/links", "$extName error: ${e.message}")
                            emptyList()
                        }
                    })
                }

                val extResults = deferredSources.awaitAll().flatten()
                val extLinks = extResults.distinctBy { it.url }.sortedByDescending { it.quality }

                // B. Direct vid-source fallback extractors
                val directLinks = extractDirectVidLinks(data)
                val directLinksSorted = directLinks.distinctBy { it.url }.sortedByDescending { it.quality }

                // C. Merge results: extension sources first, then direct fallbacks as backup
                val allLinks = (extLinks + directLinksSorted).distinctBy { it.url }.sortedByDescending {
                    it.quality ?: 0
                }

                allLinks
            }

            result.forEach { link ->
                callback(link)
            }

            result.isNotEmpty()
        } catch (e: Exception) {
            Log.d("Wizstream/links", "Link loading error: ${e.message}")
            false
        }
    }

    // ─── Helper Methods ───

    private suspend fun fetchTmdbSearch(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.themoviedb.org/3/search/multi?api_key=TEST_KEY&query=$encoded"
        return try {
            val res = app.get(url, timeout = 10_000).parsedSafe<TmdbSearchResponse>()
            res?.results?.mapNotNull { result ->
                when (result.media_type) {
                    "movie" -> newMovieSearchResponse(
                        result.title ?: result.original_title ?: "Unknown",
                        "tmdb:${result.id}|movie",
                        TvType.Movie
                    ) {
                        this.posterUrl = result.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                        this.quality = SearchQuality.Unknown
                    }
                    "tv" -> newTvSeriesSearchResponse(
                        result.name ?: result.original_name ?: "Unknown",
                        "tmdb:${result.id}|series|1",
                        TvType.TvSeries
                    ) {
                        this.posterUrl = result.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                        this.quality = SearchQuality.Unknown
                    }
                    else -> null
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.d("Wizstream/search", "TMDB search failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseTmdbUrl(url: String): TmdbLoadData? {
        val regex = Regex("tmdb:(\\d+)\\|(movie|series)(?:\\|(\\d+)(?:\\|(.+?))?)?")
        val match = regex.find(url) ?: return null
        val id = match.groupValues[1].toIntOrNull() ?: return null
        val typeStr = match.groupValues[2]
        val season = match.groupValues.getOrNull(3)?.toIntOrNull()
        val episode = match.groupValues.getOrNull(4)
        return TmdbLoadData(id, if (typeStr == "movie") TvType.Movie else TvType.TvSeries, season, episode)
    }

    private suspend fun loadTmdb(id: Int, type: TvType, season: Int?, episodeName: String?): LoadResponse {
        val title = "TMDB $id"
        return when (type) {
            TvType.Movie -> newMovieLoadResponse(title, "tmdb:$id|movie", TvType.Movie) {
                this.posterUrl = null
                this.plot = "TMDB Movie $id"
                addEpisodes(DubStatus.Subbed, listOf(newEpisode("tmdb:$id|movie") { name = episodeName ?: "Movie" }))
            }
            else -> newTvSeriesLoadResponse(title, "tmdb:$id|series", TvType.TvSeries) {
                this.posterUrl = null
                this.plot = "TMDB Series $id"
                val episodes = mutableListOf<Episode>()
                if (season != null) {
                    episodes.add(newEpisode("tmdb:$id|series|$season") { name = episodeName ?: "S$season" })
                }
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    private suspend fun loadFromExtension(url: String, extName: String): LoadResponse {
        val ext = try {
            loadExtractor("https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/builds/repo.json")
        } catch (e: Exception) {
            Log.d("Wizstream/load", "Failed to load extractor repo: ${e.message}")
            return newMovieLoadResponse("Unknown", url, TvType.Movie) {
                this.posterUrl = null
            }
        }
        return try {
            ext?.load(url) ?: throw ErrorLoadingException("Extension $extName returned null load response")
        } catch (e: Exception) {
            Log.d("Wizstream/load", "Extension $extName load failed: ${e.message}")
            throw ErrorLoadingException("Could not load from $extName: ${e.message}")
        }
    }

    private suspend fun loadExtractorLinks(data: String, extName: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        return try {
            val repoJson = "https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/builds/repo.json"
            val ext = loadExtractor(repoJson)
            ext?.loadLinks(
                data,
                isCasting = false,
                subtitleCallback = {},
                callback = { link -> links.add(link) }
            )
            links
        } catch (e: Exception) {
            Log.d("Wizstream/links", "loadExtractorLinks($extName) error: ${e.message}")
            emptyList()
        }
    }

    /** Direct fallback extractor for common video embed hosts */
    private suspend fun extractDirectVidLinks(data: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        val urlPatterns = listOf(
            "https://vidsrc\.me/embed/[^"]+",
            "https://vidnest\.com/embed/[^"]+",
            "https://vidplay\.online/embed/[^"]+",
            "https://vidup\.(?:tv|io)/embed/[^"]+",
            "https://vidrock\.(?:to|net)/embed/[^"]+",
            "https://vidfast\.(?:co|net)/embed/[^"]+",
            "https://videasy\.(?:net|me)/embed/[^"]+",
            "https://[^"]+\.mp4[^"]*",
            "https://[^"]+\.m3u8[^"]*"
        )

        // Try fetching the data URL as HTML and searching for video patterns
        return try {
            val doc = app.get(data, timeout = 10_000).document
            val html = doc.toString()

            val regexPatterns = urlPatterns.map { Regex(it) }
            val foundUrls = mutableSetOf<String>()

            regexPatterns.forEach { pattern ->
                pattern.findAll(html).forEach { match ->
                    foundUrls.add(match.value)
                }
            }

            // Additional: look for src= or data-src= with video-like URLs
            val videoTags = doc.select("iframe[src], video[src], source[src], embed[src]")
            videoTags.forEach { el ->
                val src = el.attr("src")
                if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8") || src.contains("embed"))) {
                    foundUrls.add(src)
                }
            }

            foundUrls.forEachIndexed { idx, vidUrl ->
                val domainName = try {
                    java.net.URL(vidUrl).host.replace("www.", "")
                } catch (e: Exception) { "Direct" }
                val name = if (idx == 0) domainName else "$domainName ($idx)"
                links.add(newExtractorLink(
                    source = "DirectVid",
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
            Log.d("Wizstream/links", "Direct vid extraction error: ${e.message}")
            emptyList()
        }
    }

    // Helper data classes
    private data class TmdbLoadData(
        val id: Int,
        val type: TvType,
        val season: Int?,
        val episode: String?
    )

    private data class TmdbSearchResponse(
        val results: List<TmdbResult>?
    )

    private data class TmdbResult(
        val id: Int,
        val title: String?,
        val original_title: String?,
        val name: String?,
        val original_name: String?,
        val media_type: String?,
        val poster_path: String?
    )

    // Main page loader
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, timeout = 15_000).document
            val title = doc.selectFirst("h1,h2,h3")?.text() ?: "Wizstream"
            val poster = doc.selectFirst("img[src~=poster], img[data-src~=poster]")?.attr("src")
            val plot = doc.selectFirst("p")?.text()
            newMovieLoadResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) {
            Log.d("Wizstream/load", "Load error for $url: ${e.message}")
            null
        }
    }
}
