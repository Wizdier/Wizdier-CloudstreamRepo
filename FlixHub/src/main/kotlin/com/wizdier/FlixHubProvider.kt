package com.wizdier

import android.util.Log

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * FlixHub — Cloudstream extension for flixhub.net
 *
 * BDIX-accessible streaming site with movies and TV series.
 * Categories: Hollywood, Bollywood, South Indian, Drama, Action, Comedy, etc.
 *
 * URL patterns:
 *   • Movies:    https://flixhub.net/movies?category=<cat>&sort=<sort>&page=<n>
 *   • TV Series: https://flixhub.net/tv-series?category=<cat>&sort=<sort>&page=<n>
 *   • Watch:     https://flixhub.net/watch/movie/<slug>
 *                https://flixhub.net/watch/series/<slug>
 *   • Episodes:  https://flixhub.net/watch/episode/<episode-slug>
 *   • Stream:    https://flixhub.net/stream/episode/<episode-slug>
 *
 * Video sources:
 *   The watch page has a <video> element with a <source type="video/x-matroska">.
 *   The actual video URL is loaded via JS from /stream/episode/<slug> or
 *   /stream/movie/<slug> — we fetch that URL and extract the direct media URL.
 *
 * The site also uses TMDB for poster images (data-poster attribute).
 */
class FlixHubProvider : MainAPI() {
    override var mainUrl = "https://flixhub.net"
    override var name = "FlixHub"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )

    private val cfKiller = CloudflareKiller()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    // ═══════════════════════════════════════════════════════════════════════
    //  Main pages
    // ═══════════════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "movies?sort=latest" to "Latest Movies",
        "movies?sort=popular" to "Popular Movies",
        "movies?category=hollywood" to "Hollywood",
        "movies?category=bollywood" to "Bollywood",
        "movies?category=south-indian" to "South Indian",
        "movies?category=Animation" to "Animation",
        "tv-series?sort=latest" to "Latest TV Series",
        "tv-series?sort=popular" to "Popular TV Series",
        "movies?category=Drama" to "Drama Movies",
        "movies?category=Action" to "Action Movies",
        "movies?category=Comedy" to "Comedy Movies",
        "movies?category=Horror" to "Horror Movies",
        "movies?category=Romance" to "Romance Movies",
        "movies?category=Thriller" to "Thriller Movies",
        "movies?category=Adventure" to "Adventure Movies",
        "movies?category=Family" to "Family Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}&page=$page"
        val doc = fetchDocument(url) ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = parseCards(doc)
        val hasNext = doc.select("a[rel=next], a:contains(Next), .pagination a:contains(›)").isNotEmpty()
            || items.size >= 18
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Search
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val url = "$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val doc = fetchDocument(url) ?: return emptyList()
        return parseCards(doc)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Load
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDocument(url) ?: throw ErrorLoadingException("Failed to load $url")

        val isSeries = url.contains("/watch/series/") || url.contains("/watch/episode/")
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = extractPoster(doc)
        val plot = extractSynopsis(doc)
        val genres = extractGenres(doc)
        val year = extractYear(doc)

        return if (isSeries) {
            val episodes = parseEpisodes(doc)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        } else {
            val slug = url.substringAfter("/watch/movie/")
            val data = FlixHubLink(slug = slug, isMovie = true).toJson()
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Links
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val link = try { FlixHubLink.fromJson(data) } catch (_: Exception) { null } ?: return false

        val streamUrl = if (link.isMovie) {
            "$mainUrl/stream/movie/${link.slug}"
        } else {
            "$mainUrl/stream/episode/${link.slug}"
        }

        // Fetch the stream page — it should redirect to or contain the direct video URL
        return try {
            val res = app.get(streamUrl, headers = headers, timeout = 15_000)
            val body = res.text
            val finalUrl = res.url ?: streamUrl

            // Check if it's a direct media URL
            if (finalUrl.endsWith(".mkv") || finalUrl.endsWith(".mp4") ||
                finalUrl.contains(".mkv?") || finalUrl.contains(".mp4?")
            ) {
                callback(
                    newExtractorLink(
                        source = "FlixHub",
                        name = "FlixHub - Direct",
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = mainUrl
                        this.quality = getQualityFromUrl(finalUrl)
                    }
                )
                return true
            }

            // Check if the response body has a direct media URL
            val mediaUrls = extractMediaUrls(body, streamUrl)
            var found = false
            for (u in mediaUrls) {
                callback(
                    newExtractorLink(
                        source = "FlixHub",
                        name = "FlixHub - Direct",
                        url = u,
                        type = if (u.contains(".m3u8", true)) ExtractorLinkType.M3U8
                               else ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = mainUrl
                        this.quality = getQualityFromUrl(u)
                    }
                )
                found = true
            }

            // If no direct URL found, try loadExtractor on the stream URL
            if (!found) {
                runCatching {
                    loadExtractor(streamUrl, mainUrl, subtitleCallback) { link ->
                        callback(link)
                        found = true
                    }
                }
            }

            // Also try fetching the watch page and extracting from the <source> tag
            if (!found) {
                val watchUrl = if (link.isMovie) {
                    "$mainUrl/watch/movie/${link.slug}"
                } else {
                    "$mainUrl/watch/episode/${link.slug}"
                }
                val watchDoc = fetchDocument(watchUrl)
                if (watchDoc != null) {
                    val sourceTag = watchDoc.selectFirst("video source[src]")
                    if (sourceTag != null) {
                        val src = sourceTag.attr("src")
                        if (src.isNotBlank()) {
                            callback(
                                newExtractorLink(
                                    source = "FlixHub",
                                    name = "FlixHub - Direct",
                                    url = src,
                                    type = ExtractorLinkType.VIDEO,
                                ) {
                                    this.referer = mainUrl
                                    this.quality = getQualityFromUrl(src)
                                }
                            )
                            found = true
                        }
                    }
                    // Extract any media URLs from the watch page HTML
                    val watchHtml = watchDoc.outerHtml()
                    val urls = extractMediaUrls(watchHtml, watchUrl)
                    for (u in urls) {
                        callback(
                            newExtractorLink(
                                source = "FlixHub",
                                name = "FlixHub - Direct",
                                url = u,
                                type = if (u.contains(".m3u8", true)) ExtractorLinkType.M3U8
                                       else ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = mainUrl
                                this.quality = getQualityFromUrl(u)
                            }
                        )
                        found = true
                    }
                }
            }

            found
        } catch (e: Exception) {
            Log.d("FlixHub", "loadLinks failed: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HTML parsing helpers
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun fetchDocument(url: String): Document? {
        return try {
            val res = app.get(url, headers = headers, interceptor = cfKiller, timeout = 20_000)
            if (res.code !in 200..299) return null
            Jsoup.parse(res.text, url)
        } catch (e: Exception) {
            Log.d("FlixHub", "fetchDocument failed for $url: ${e.message}")
            null
        }
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        return doc.select("article.movie-card-final, article[data-watch-url]").mapNotNull { card ->
            val watchUrl = card.attr("data-watch-url")
                .ifBlank { card.selectFirst("a[href*=/watch/]")?.attr("href") }
                ?: return@mapNotNull null
            val title = card.selectFirst(".movie-card-browse-title, .movie-title, .card-overlay p")?.text()
                ?: card.selectFirst("a[aria-label]")?.attr("aria-label")
                ?: return@mapNotNull null
            val poster = card.selectFirst("img")?.let { img ->
                // Images use CSS background-image or data URIs; try to get a real URL
                val style = img.attr("style")
                val bgMatch = Regex("""background-image:url\(([^)]+)\)""").find(style)
                bgMatch?.groupValues?.get(1)?.replace("'", "")?.replace("\"", "")
                    ?: img.attr("data-src").ifBlank { null }
                    ?: img.attr("src").takeIf { it.startsWith("http") }
            }
            val isSeries = watchUrl.contains("/watch/series/")
            val type = if (isSeries) TvType.TvSeries else TvType.Movie

            if (isSeries) {
                newTvSeriesSearchResponse(title, watchUrl, type) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, watchUrl, type) {
                    this.posterUrl = poster
                }
            }
        }
    }

    private fun parseEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seenSlugs = mutableSetOf<String>()

        // Episode links: /watch/episode/<slug> or /stream/episode/<slug>
        val epLinks = doc.select("a[href*=/watch/episode/], a[href*=/stream/episode/]")
        for (link in epLinks) {
            val href = link.attr("href")
            val slug = href.substringAfter("/episode/").substringBefore("?").substringBefore("#")
            if (slug.isBlank() || slug in seenSlugs) continue
            seenSlugs.add(slug)

            val epText = link.text().trim()
            val epNum = Regex("""(?:Episode\s*)?(\d+)""", RegexOption.IGNORE_CASE)
                .find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: (seenSlugs.size)

            // Extract episode title if available
            val titleEl = link.selectFirst(".episode-title, .player-mobile-episode-title")
            val epTitle = titleEl?.text()?.takeIf { it.isNotBlank() }

            episodes.add(newEpisode(FlixHubLink(slug = slug, isMovie = false).toJson()) {
                this.name = if (epTitle != null) "Episode $epNum: $epTitle" else "Episode $epNum"
                this.episode = epNum
                this.season = 1 // Will be updated if season info is available
            })
        }

        // Also check for season tabs
        val seasonTabs = doc.select(".season-tab, .player-mobile-season-tab, [data-season]")
        if (seasonTabs.isNotEmpty()) {
            // If there are multiple seasons, try to assign episodes to seasons
            // For now, all episodes go to season 1 unless the page structure changes
        }

        return episodes.ifEmpty {
            // Fallback: if no episode links found, try the data-episode-slug attribute
            val epSlug = doc.selectFirst("#theaterPlayer")?.attr("data-episode-slug")
            if (epSlug != null && epSlug.isNotBlank()) {
                listOf(newEpisode(FlixHubLink(slug = epSlug, isMovie = false).toJson()) {
                    this.name = "Episode 1"
                    this.episode = 1
                    this.season = 1
                })
            } else {
                emptyList()
            }
        }
    }

    private fun extractPoster(doc: Document): String? {
        // Try data-poster on the video element (TMDB URL)
        val dataPoster = doc.selectFirst("video[data-poster]")?.attr("data-poster")
        if (!dataPoster.isNullOrBlank() && dataPoster.startsWith("http")) return dataPoster

        // Try img with TMDB src
        val tmdbImg = doc.selectFirst("img[src*=image.tmdb.org]")?.attr("src")
        if (!tmdbImg.isNullOrBlank()) return tmdbImg

        // Try poster class
        val posterImg = doc.selectFirst(".movie-card-poster img, .poster img")?.let { img ->
            img.attr("data-src").ifBlank { null } ?: img.attr("src").takeIf { it.startsWith("http") }
        }
        return posterImg
    }

    private fun extractSynopsis(doc: Document): String? {
        val synopsis = doc.selectFirst(".player-mobile-synopsis-wrap, .synopsis, .description, .overview")?.text()
            ?.replace("See more", "")?.trim()
        return synopsis?.takeIf { it.isNotBlank() }
    }

    private fun extractGenres(doc: Document): List<String>? {
        val genres = doc.select("a[href*=category=]").mapNotNull { el ->
            val href = el.attr("href")
            val cat = href.substringAfter("category=").substringBefore("&")
            val name = el.text().trim()
            name.takeIf { it.isNotBlank() && cat !in listOf("hollywood", "bollywood", "south-indian") }
        }.distinct()
        return genres.takeIf { it.isNotEmpty() }
    }

    private fun extractYear(doc: Document): Int? {
        val text = doc.text()
        return Regex("""\b(?:19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
    }

    private fun extractMediaUrls(html: String, baseUrl: String): LinkedHashSet<String> {
        val out = linkedSetOf<String>()
        // Direct media URLs
        Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv|\.webm)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m -> out.add(m.value.replace("&amp;", "&")) }
        // Relative URLs
        Regex("""["'](/(?:stream|storage|Data|video)/[^"']+\.(?:m3u8|mp4|mkv|webm)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val u = m.groupValues[1]
                out.add("$mainUrl$u")
            }
        return out
    }

    private fun getQualityFromUrl(url: String): Int {
        val n = url.lowercase()
        return when {
            "4k" in n || "2160" in n -> Qualities.P2160.value
            "1440" in n -> Qualities.P1440.value
            "1080" in n -> Qualities.P1080.value
            "720" in n -> Qualities.P720.value
            "480" in n -> Qualities.P480.value
            "360" in n -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Link data
    // ═══════════════════════════════════════════════════════════════════════

    private data class FlixHubLink(
        val slug: String,
        val isMovie: Boolean,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("slug", slug)
            put("is_movie", isMovie)
        }.toString()

        companion object {
            fun fromJson(s: String): FlixHubLink {
                val o = JSONObject(s)
                return FlixHubLink(
                    slug = o.optString("slug"),
                    isMovie = o.optBoolean("is_movie"),
                )
            }
        }
    }
}
