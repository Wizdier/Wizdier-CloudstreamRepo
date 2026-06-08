package com.nowhdtime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * NowHDTime provider for CloudStream3.
 *
 * Strategy:
 *  - Browse/search via nowhdtime.to HTML (server-rendered content lists, episode metadata)
 *  - Video sources: extract TMDB ID from page poster URLs, then query multiple
 *    well-known TMDB-ID-based embed providers (VidSrc, 2Embed, multiembed, embed.su, AutoEmbed)
 *    since the site's own player buttons are JS-injected with no static attributes.
 *
 * Data format passed through Episode.data / loadLinks:
 *   "movie::{tmdbId}"            for movies
 *   "tv::{tmdbId}::{s}::{e}"    for TV episodes
 */
class NowHDTimeProvider : MainAPI() {

    override var mainUrl              = "https://nowhdtime.to"
    override var name                 = "NowHDTime"
    override val hasMainPage          = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/movies?page="    to "Latest Movies",
        "$mainUrl/tv-shows?page="  to "Latest TV Shows",
        "$mainUrl/trending?page="  to "Trending",
        "$mainUrl/anime?page="     to "Anime",
    )

    // ────────────────────────────────────────────────────────────────────────
    // Embed providers — tried in order; all use TMDB IDs
    // ────────────────────────────────────────────────────────────────────────

    private data class EmbedProvider(
        val name: String,
        val movieUrl: (tmdb: String) -> String,
        val tvUrl:    (tmdb: String, season: Int, episode: Int) -> String,
    )

    private val embedProviders = listOf(
        EmbedProvider(
            name     = "VidSrc",
            movieUrl = { tmdb -> "https://vidsrc.xyz/embed/movie?tmdb=$tmdb" },
            tvUrl    = { tmdb, s, e -> "https://vidsrc.xyz/embed/tv?tmdb=$tmdb&season=$s&episode=$e" },
        ),
        EmbedProvider(
            name     = "VidSrc.me",
            movieUrl = { tmdb -> "https://vidsrc.me/embed/movie?tmdb=$tmdb" },
            tvUrl    = { tmdb, s, e -> "https://vidsrc.me/embed/tv?tmdb=$tmdb&season=$s&episode=$e" },
        ),
        EmbedProvider(
            name     = "2Embed",
            movieUrl = { tmdb -> "https://www.2embed.cc/embed/$tmdb" },
            tvUrl    = { tmdb, s, e -> "https://www.2embed.cc/embedtv/$tmdb&s=$s&e=$e" },
        ),
        EmbedProvider(
            name     = "2Embed Stream",
            movieUrl = { tmdb -> "https://www.2embed.stream/embed/movie/$tmdb" },
            tvUrl    = { tmdb, s, e -> "https://www.2embed.stream/embed/tv/$tmdb/$s/$e" },
        ),
        EmbedProvider(
            name     = "MultiEmbed",
            movieUrl = { tmdb -> "https://multiembed.mov/?video_id=$tmdb&tmdb=1" },
            tvUrl    = { tmdb, s, e -> "https://multiembed.mov/?video_id=$tmdb&tmdb=1&s=$s&e=$e" },
        ),
        EmbedProvider(
            name     = "Embed.su",
            movieUrl = { tmdb -> "https://embed.su/embed/movie/$tmdb" },
            tvUrl    = { tmdb, s, e -> "https://embed.su/embed/tv/$tmdb/$s/$e" },
        ),
        EmbedProvider(
            name     = "AutoEmbed",
            movieUrl = { tmdb -> "https://autoembed.cc/movie/tmdb-$tmdb" },
            tvUrl    = { tmdb, s, e -> "https://autoembed.cc/tv/tmdb-$tmdb-$s-$e" },
        ),
        EmbedProvider(
            name     = "VidLink",
            movieUrl = { tmdb -> "https://vidlink.pro/movie/$tmdb" },
            tvUrl    = { tmdb, s, e -> "https://vidlink.pro/tv/$tmdb/$s/$e" },
        ),
    )

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Extract TMDB ID from TMDB image URLs present on the page.
     * e.g. https://image.tmdb.org/t/p/w500/kydPBzuU4TSmDOXCkgAdVFF8sgU.jpg
     * The poster *path* (e.g. /kydPBzuU4TSmDOXCkgAdVFF8sgU.jpg) is not the ID;
     * the TMDB ID lives in <meta property="og:url"> as the numeric suffix of the slug,
     * or in og:image with a different path pattern.
     *
     * Fallback: try og:url / canonical for slug, then TMDB API lookup.
     */
    private fun extractTmdbId(doc: org.jsoup.nodes.Document): String? {
        // 1. Some sites embed tmdb id directly in a data attribute
        doc.selectFirst("[data-tmdb-id], [data-tmdb], [data-id]")
            ?.let { el ->
                el.attr("data-tmdb-id").ifBlank { el.attr("data-tmdb") }.ifBlank { el.attr("data-id") }
            }?.takeIf { it.matches(Regex("\\d{3,9}")) }
            ?.let { return it }

        // 2. Look in any script tag for tmdbId / tmdb_id assignment
        val scriptData = doc.select("script:not([src])").joinToString("\n") { it.data() }
        Regex("""(?:tmdb[_-]?id|tmdbId)['":\s]+(\d{3,9})""", RegexOption.IGNORE_CASE)
            .find(scriptData)?.groupValues?.get(1)
            ?.let { return it }

        // 3. Try meta tag (some sites embed it as a custom meta)
        doc.selectFirst("meta[name='tmdb-id'], meta[property='tmdb:id']")
            ?.attr("content")?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return null
    }

    /** Parse a TMDB image URL like https://image.tmdb.org/t/p/w500/abc.jpg → keep it as-is */
    private fun normalizePoster(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return if (raw.startsWith("http")) raw else fixUrl(raw)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Find the content anchor — must point to /movie/ or /tv-show/
        val anchor = selectFirst("a[href*='/movie/watch-'], a[href*='/tv-show/watch-']")
            ?: (if (tagName() == "a" && (attr("href").contains("/movie/watch-") ||
                attr("href").contains("/tv-show/watch-"))) this else null)
            ?: return null

        val href   = fixUrl(anchor.attr("href"))
        val title  = selectFirst("img[alt]")?.attr("alt")?.trim()
            ?.ifBlank { null }
            ?: selectFirst(".title, h3, h4, h5, [class*='title']")?.text()?.trim()
            ?: anchor.attr("title").ifBlank { anchor.text() }.trim()
        if (title.isBlank()) return null

        val poster = selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.let { normalizePoster(it) }

        val year = selectFirst("[class*='year'], .year")?.text()
            ?.let { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() }

        return if (href.contains("/movie/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster; this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster; this.year = year
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Main Page
    // ────────────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val doc = app.get(url).document

        // Grab all elements that could wrap a content card
        val results = doc.select(
            "article, li, div[class*='card'], div[class*='item'], div[class*='movie'], div[class*='show']"
        ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }.ifEmpty {
            // Flat anchor fallback
            doc.select("a[href*='/movie/watch-'], a[href*='/tv-show/watch-']")
                .mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        }

        return newHomePageResponse(request.name, results)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Search
    // ────────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search", params = mapOf("q" to query)).document
        val results = doc.select(
            "article, li, div[class*='card'], div[class*='item'], a[href*='/movie/watch-'], a[href*='/tv-show/watch-']"
        ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        if (results.isNotEmpty()) return results

        // Fallback endpoint variation
        val doc2 = app.get("$mainUrl/search?query=$query").document
        return doc2.select(
            "article, li, div[class*='card'], a[href*='/movie/watch-'], a[href*='/tv-show/watch-']"
        ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Load
    // ────────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc     = app.get(url).document
        val isMovie = url.contains("/movie/")

        val title   = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster  = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("[class*='poster'] img, .poster img")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val backdrop = doc.selectFirst("img[src*='w1280'], img[src*='original']")?.attr("src")
            ?: doc.selectFirst("[style*='background-image']")?.attr("style")
                ?.let { Regex("""url\(['"']?(.*?)['"']?\)""").find(it)?.groupValues?.get(1) }
        val plot    = doc.selectFirst("meta[name='description']")?.attr("content")
            ?: doc.selectFirst(".overview, [class*='plot'], [class*='synopsis']")?.text()

        // Rating: parse "8.0/10" or "8.0"
        val ratingRaw = doc.selectFirst("[class*='rating'], [class*='score']")?.text()
            ?.replace("/10", "")?.trim()
        val rating = ratingRaw?.toDoubleOrNull()

        val genres = doc.select("[class*='genre'] a, .genres a, .genre a")
            .map { it.text().trim() }.filter { it.isNotBlank() }

        val year = Regex("\\b(19|20)\\d{2}\\b")
            .find(doc.selectFirst(".info, [class*='meta'], [class*='detail']")?.text() ?: "")
            ?.value?.toIntOrNull()

        val actors = doc.select("[class*='cast'] img[alt], [class*='cast'] .name")
            .mapNotNull { el ->
                val n = el.attr("alt").ifBlank { el.text() }.trim()
                val img = if (el.tagName() == "img")
                    normalizePoster(el.attr("data-src").ifBlank { el.attr("src") })
                else null
                if (n.isBlank()) null else Actor(n, img)
            }

        val trailer = doc.select("iframe[src*='youtube'], a[href*='youtube.com/watch']")
            .firstOrNull()?.let { el ->
                val raw = el.attr("src").ifBlank { el.attr("href") }
                Regex("(?:v=|/embed/|youtu\\.be/)([A-Za-z0-9_-]{11})").find(raw)
                    ?.groupValues?.get(1)
                    ?.let { id -> "https://www.youtube.com/embed/$id" }
            }

        // ── Extract TMDB ID ──────────────────────────────────────────────────
        val tmdbId = extractTmdbId(doc)

        // Encode TMDB ID into the data string for loadLinks
        // For TV episodes we need season/episode — encode that at episode level below
        val linkData = if (isMovie) {
            if (tmdbId != null) "movie::$tmdbId" else url
        } else {
            // For the show itself we store "tv::{tmdbId}" — episodes append s/e below
            if (tmdbId != null) "tv::$tmdbId" else url
        }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, linkData) {
                this.posterUrl           = normalizePoster(poster)
                this.backgroundPosterUrl = normalizePoster(backdrop)
                this.plot                = plot
                this.score               = rating?.let { Score.from10(it) }
                this.tags                = genres
                this.year                = year
                addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        }

        // ── TV Series ────────────────────────────────────────────────────────
        val episodes = mutableListOf<Episode>()

        // Season tabs
        val seasonTabs = doc.select("ul[class*='season'] li, nav[class*='season'] a, [class*='seasons'] button")
        val seasonNumbers = if (seasonTabs.isNotEmpty()) {
            seasonTabs.mapNotNull { tab ->
                Regex("\\d+").find(tab.text())?.value?.toIntOrNull()
            }.distinct().sorted()
        } else listOf(1)

        // Episode rows — already rendered in static HTML
        // Try each season's section; fall back to all episode cards on the page
        val allEpElements = doc.select(
            "[class*='episode-item'], [class*='episode-card'], " +
            "div[class*='episode']:has(img), li[class*='episode']"
        )

        if (allEpElements.isNotEmpty()) {
            // Try to detect season from parent section or data attribute
            allEpElements.forEach { el ->
                val detectedSeason = el.attr("data-season").toIntOrNull()
                    ?: el.parents().firstOrNull { it.hasAttr("data-season") }
                        ?.attr("data-season")?.toIntOrNull()
                    ?: 1
                val epNum = el.attr("data-episode").toIntOrNull()
                    ?: el.selectFirst("span[class*='ep-num'], [class*='episode-number']")
                        ?.text()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

                val epTitle = el.selectFirst("h4, h5, .title, [class*='title']")?.text()?.trim()
                    ?: el.selectFirst("img[alt]")?.attr("alt")?.trim()
                val thumb   = el.selectFirst("img")?.let { img ->
                    normalizePoster(img.attr("data-src").ifBlank { img.attr("src") })
                }
                val desc    = el.selectFirst(".overview, [class*='desc']")?.text()?.trim()
                val epEpData = if (tmdbId != null)
                    "tv::$tmdbId::$detectedSeason::${epNum ?: 1}"
                else url

                episodes.add(newEpisode(epEpData) {
                    this.name        = epTitle ?: "Episode ${epNum ?: episodes.size + 1}"
                    this.season      = detectedSeason
                    this.episode     = epNum
                    this.posterUrl   = thumb
                    this.description = desc
                })
            }
        } else {
            // Ultra-fallback: build episode stubs from "EP 01" labels visible on page
            doc.select("*:contains(EP )").filter { el ->
                el.childrenSize() == 0 && Regex("EP\\s*\\d+").containsMatchIn(el.text())
            }.forEach { el ->
                val m = Regex("EP\\s*(\\d+)").find(el.text()) ?: return@forEach
                val epNum = m.groupValues[1].toInt()
                val season = 1
                val epData = if (tmdbId != null) "tv::$tmdbId::$season::$epNum" else url
                episodes.add(newEpisode(epData) {
                    this.name    = "Episode $epNum"
                    this.season  = season
                    this.episode = epNum
                })
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(if (tmdbId != null) "tv::$tmdbId::1::1" else url) {
                this.name = "Episode 1"; this.season = 1; this.episode = 1
            })
        }

        val tvType = if (genres.any { it.contains("anime", true) } ||
            doc.title().contains("anime", true)) TvType.Anime else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl           = normalizePoster(poster)
            this.backgroundPosterUrl = normalizePoster(backdrop)
            this.plot                = plot
            this.score               = rating?.let { Score.from10(it) }
            this.tags                = genres
            this.year                = year
            addActors(actors)
            trailer?.let { addTrailer(it) }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Load Links
    // ────────────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        when {
            // ── Structured data: "movie::{tmdbId}" ──────────────────────────
            data.startsWith("movie::") -> {
                val tmdbId = data.removePrefix("movie::")
                embedProviders.forEach { provider ->
                    safeApiCall {
                        val embedUrl = provider.movieUrl(tmdbId)
                        loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                        found = true
                    }
                }
            }

            // ── Structured data: "tv::{tmdbId}::{s}::{e}" ──────────────────
            data.startsWith("tv::") -> {
                val parts   = data.split("::")
                val tmdbId  = parts.getOrNull(1) ?: return false
                val season  = parts.getOrNull(2)?.toIntOrNull() ?: 1
                val episode = parts.getOrNull(3)?.toIntOrNull() ?: 1
                embedProviders.forEach { provider ->
                    safeApiCall {
                        val embedUrl = provider.tvUrl(tmdbId, season, episode)
                        loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                        found = true
                    }
                }
            }

            // ── Fallback: raw URL — try to scrape the page and/or use TMDB ID ─
            else -> {
                val pageUrl = data
                val doc = app.get(pageUrl).document
                val tmdbId = extractTmdbId(doc)
                val isMovie = pageUrl.contains("/movie/")

                if (tmdbId != null) {
                    embedProviders.forEach { provider ->
                        safeApiCall {
                            val embedUrl = if (isMovie)
                                provider.movieUrl(tmdbId)
                            else
                                provider.tvUrl(tmdbId, 1, 1)
                            loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                            found = true
                        }
                    }
                }

                // Also scrape the page for any visible iframe / data-src
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src").ifBlank { return@forEach }
                    safeApiCall { loadExtractor(fixUrl(src), pageUrl, subtitleCallback, callback) }
                    found = true
                }
                doc.select("[data-src], [data-iframe], [data-embed]").forEach { el ->
                    val src = el.attr("data-src")
                        .ifBlank { el.attr("data-iframe") }
                        .ifBlank { el.attr("data-embed") }
                        .ifBlank { return@forEach }
                    safeApiCall { loadExtractor(fixUrl(src), pageUrl, subtitleCallback, callback) }
                    found = true
                }

                // JS blob scanning
                val scripts = doc.select("script:not([src])").joinToString("\n") { it.data() }
                found = found or extractFromScripts(scripts, pageUrl, subtitleCallback, callback)
            }
        }

        return found
    }

    private suspend fun extractFromScripts(
        scripts: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false

        // HLS
        Regex("""(?:file|src|source)['":\s]+['"]([^'"]+\.m3u8[^'"]*)['"]""")
            .findAll(scripts).map { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .forEach { url ->
                callback(newExtractorLink(name, name, url, type = ExtractorLinkType.M3U8) {
                    this.referer = referer; this.quality = Qualities.Unknown.value
                })
                found = true
            }

        // MP4
        Regex("""(?:file|src|source)['":\s]+['"]([^'"]+\.mp4[^'"]*)['"]""")
            .findAll(scripts).map { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .forEach { url ->
                callback(newExtractorLink(name, name, url, type = ExtractorLinkType.VIDEO) {
                    this.referer = referer; this.quality = Qualities.Unknown.value
                })
                found = true
            }

        // Embedded external URLs in JS
        Regex("""['"]( https?://(?!image\.tmdb)[^'">\s]{15,})['"]""")
            .findAll(scripts).map { it.groupValues[1].trim() }
            .filter { it.startsWith("http") && !it.contains(mainUrl) }
            .take(5) // cap to avoid noise
            .forEach { url ->
                safeApiCall { loadExtractor(url, referer, subtitleCallback, callback) }
                found = true
            }

        return found
    }
}
