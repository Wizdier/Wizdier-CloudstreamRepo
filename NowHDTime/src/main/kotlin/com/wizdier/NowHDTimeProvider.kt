package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class NowHDTimeProvider : MainAPI() {
    override var mainUrl = NowHDTimeUtils.mainUrl
    override var name = NowHDTimeUtils.name
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    // ── Main Page Sections ──
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Updates",
        "$mainUrl/category/bangla-dubbed-movie/page/" to "Bangla Dubbed",
        "$mainUrl/category/bangla-movie/page/" to "Bangla Movies",
        "$mainUrl/category/bollywood/page/" to "Bollywood",
        "$mainUrl/category/hollywood/page/" to "Hollywood",
        "$mainUrl/category/south-indian-movie/page/" to "South Indian",
        "$mainUrl/category/tamil-movie/page/" to "Tamil Movies",
        "$mainUrl/category/korean-movie/page/" to "Korean Movies",
        "$mainUrl/category/chinese-movie/page/" to "Chinese Movies",
        "$mainUrl/category/anime/page/" to "Anime",
        "$mainUrl/category/tv-series/page/" to "TV Series",
        "$mainUrl/category/web-series/page/" to "Web Series",
        "$mainUrl/category/korean-drama/page/" to "Korean Drama",
        "$mainUrl/category/turkish-drama/page/" to "Turkish Drama",
        "$mainUrl/category/dual-audio/page/" to "Dual Audio"
    )

    // ── Main Page Loading ──
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page/"
        val document = app.get(url, timeout = 30).document
        val items = parsePageItems(document)
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = hasNextPage(document)
        )
    }

    // ── Parse Cards ──
    private fun parsePageItems(document: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        val selectors = listOf(
            "article", ".post", ".item", ".movie-item",
            ".result-item", ".entry", ".post-item", ".card"
        )

        for (selector in selectors) {
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                elements.forEach { el ->
                    parseArticle(el)?.let { results.add(it) }
                }
                if (results.isNotEmpty()) break
            }
        }

        // Fallback: thumbnail links
        if (results.isEmpty()) {
            document.select(".entry-content a, .post-content a, .content a").forEach { a ->
                val href = a.attr("href")
                if (href.startsWith(mainUrl) && href != mainUrl
                    && !href.contains("/category/") && !href.contains("/tag/")
                    && !href.contains("/page/")
                ) {
                    val img = a.selectFirst("img") ?: return@forEach
                    val title = a.attr("title").ifBlank { img.attr("alt") }.ifBlank { a.text() }
                    if (title.isBlank()) return@forEach

                    val posterUrl = NowHDTimeUtils.fixPosterUrl(
                        img.attr("data-src").ifBlank {
                            img.attr("data-lazy-src").ifBlank { img.attr("src") }
                        }
                    )
                    val type = detectType(href, title)
                    val year = NowHDTimeUtils.extractYear(title)

                    val resp = if (type == TvType.TvSeries || type == TvType.Anime || type == TvType.AsianDrama) {
                        newTvSeriesSearchResponse(title.trim(), href, type) {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    } else {
                        newMovieSearchResponse(title.trim(), href, type) {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    }
                    results.add(resp)
                }
            }
        }

        return results.distinctBy { it.url }
    }

    private fun parseArticle(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href]") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || !href.startsWith(mainUrl) || href == mainUrl
            || href.contains("/category/") || href.contains("/tag/")
            || href.contains("/page/") || href.contains("/author/")
        ) return null

        val title = element.selectFirst("h2 a, h3 a, .entry-title a, .post-title a, .title a")?.text()
            ?: element.selectFirst("h2, h3, .entry-title, .post-title, .title")?.text()
            ?: linkEl.attr("title")
            ?: element.selectFirst("img")?.attr("alt")
            ?: return null

        if (title.isBlank()) return null

        val posterUrl = NowHDTimeUtils.fixPosterUrl(
            element.selectFirst("img")?.let { img ->
                img.attr("data-src")
                    .ifBlank { img.attr("data-lazy-src") }
                    .ifBlank { img.attr("data-original") }
                    .ifBlank { img.attr("src") }
            }
        )

        val qualityText = element.selectFirst(".quality, .qlty, span.quality, .badge")?.text()
        val quality = getSearchQualityFromText(qualityText)
        val year = NowHDTimeUtils.extractYear(title)
            ?: NowHDTimeUtils.extractYear(
                element.selectFirst(".year, .date, .entry-date, time")?.text() ?: ""
            )

        val type = detectType(href, title)

        return if (type == TvType.TvSeries || type == TvType.Anime || type == TvType.AsianDrama) {
            newTvSeriesSearchResponse(title.trim(), href, type) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title.trim(), href, type) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = quality
            }
        }
    }

    private fun detectType(url: String, title: String): TvType {
        val c = "$url $title".lowercase()
        return when {
            c.contains("series") || c.contains("season") || c.contains("episode") || c.contains("web-series") -> TvType.TvSeries
            c.contains("anime") -> TvType.Anime
            c.contains("drama") || c.contains("kdrama") || c.contains("cdrama") || c.contains("korean-drama") || c.contains("turkish") -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }

    private fun getSearchQualityFromText(quality: String?): SearchQuality? {
        if (quality.isNullOrBlank()) return null
        val q = quality.lowercase().trim()
        return when {
            q.contains("cam") || q.contains("hdcam") || q.contains("ts") -> SearchQuality.CamQuality
            q.contains("hdtc") -> SearchQuality.HdCam
            q.contains("webrip") || q.contains("web-dl") -> SearchQuality.WebRip
            q.contains("bluray") || q.contains("blu-ray") -> SearchQuality.BlueRay
            q.contains("dvd") -> SearchQuality.SD
            q.contains("hdrip") || q.contains("hd") -> SearchQuality.HD
            else -> null
        }
    }

    private fun hasNextPage(document: Document): Boolean {
        return document.selectFirst(
            "a.next, .next a, .pagination .next, .nav-next a, a.page-numbers.next, li.next a"
        ) != null
    }

    // ── Search ──
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}", timeout = 30).document
        return parsePageItems(doc)
    }

    // ── Load Detail Page ──
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 30).document

        val rawTitle = document.selectFirst(
            "h1.entry-title, h1.post-title, h1.title, .entry-title, h1"
        )?.text() ?: document.title()

        val title = NowHDTimeUtils.cleanTitle(rawTitle)

        val posterUrl = NowHDTimeUtils.fixPosterUrl(
            document.selectFirst(
                ".entry-content img, .post-content img, .post-thumbnail img, article img"
            )?.let { img ->
                img.attr("data-src")
                    .ifBlank { img.attr("data-lazy-src") }
                    .ifBlank { img.attr("src") }
            } ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = extractDescription(document)
        val year = NowHDTimeUtils.extractYear(rawTitle)
            ?: extractMetaField(document, listOf("Release", "Year", "Released"))?.let {
                NowHDTimeUtils.extractYear(it)
            }

        val tags = NowHDTimeUtils.extractTags(document).ifEmpty {
            extractMetaField(document, listOf("Genre", "Genres"))
                ?.split(",", "|", "/")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
        }

        val duration = extractMetaField(document, listOf("Runtime", "Duration", "Time"))?.let {
            NowHDTimeUtils.parseDuration(it)
        }

        val rating = extractMetaField(document, listOf("IMDB", "Rating", "IMDb Rating"))?.let {
            NowHDTimeUtils.extractRating(it)
        }

        val actors = NowHDTimeUtils.extractActors(document).ifEmpty {
            extractMetaField(document, listOf("Cast", "Stars", "Starring"))
                ?.split(",")
                ?.map { ActorData(Actor(it.trim())) }
        }

        val director = extractMetaField(document, listOf("Director", "Directors"))
        val country = extractMetaField(document, listOf("Country", "Countries"))
        val language = extractMetaField(document, listOf("Language", "Languages"))

        val trackingIds = NowHDTimeUtils.extractTrackingIds(document)
        val trailerUrl = extractTrailerUrl(document)
        val recommendations = extractRecommendations(document)
        val type = detectContentType(url, rawTitle, document)

        val fullDescription = buildString {
            if (!description.isNullOrBlank()) append(description)
            if (!director.isNullOrBlank()) append("\n\n🎬 Director: $director")
            if (!country.isNullOrBlank()) append("\n🌍 Country: $country")
            if (!language.isNullOrBlank()) append("\n🗣️ Language: $language")
        }.trim().ifBlank { null }

        return if (type == TvType.TvSeries || type == TvType.Anime || type == TvType.AsianDrama) {
            val episodes = extractEpisodes(document, url)
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = fullDescription
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailerUrl)
                addImdbId(trackingIds.imdbId)
                addMalId(trackingIds.malId)
                addAniListId(trackingIds.anilistId)
            }
        } else {
            newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = fullDescription
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailerUrl)
                addImdbId(trackingIds.imdbId)
                addMalId(trackingIds.malId)
                addAniListId(trackingIds.anilistId)
            }
        }
    }

    private fun extractDescription(document: Document): String? {
        val synopsisSelectors = listOf(
            ".synopsis", ".description", ".plot", ".story",
            "meta[name=description]", "meta[property=og:description]"
        )
        for (selector in synopsisSelectors) {
            val el = document.selectFirst(selector)
            if (el != null) {
                val text = if (selector.startsWith("meta")) el.attr("content") else el.text()
                if (text.isNotBlank() && text.length > 20) return text.trim()
            }
        }

        val content = document.selectFirst(".entry-content, .post-content, article .content")
        if (content != null) {
            for (p in content.select("p")) {
                val text = p.text().trim()
                if (text.length > 50
                    && !text.lowercase().contains("download")
                    && !text.lowercase().contains("screenshot")
                    && !text.lowercase().contains("click here")
                    && !text.lowercase().contains("join telegram")
                ) {
                    return text
                }
            }
        }
        return null
    }

    private fun extractMetaField(document: Document, fieldNames: List<String>): String? {
        val content = document.selectFirst(
            ".entry-content, .post-content, article, .movie-info"
        ) ?: document

        for (fieldName in fieldNames) {
            val selectors = listOf(
                "span:contains($fieldName)", "strong:contains($fieldName)",
                "b:contains($fieldName)", "li:contains($fieldName)",
                "p:contains($fieldName)", "td:contains($fieldName)"
            )
            for (selector in selectors) {
                for (element in content.select(selector)) {
                    val text = element.text()
                    val match = Regex("(?i)$fieldName\\s*[:：]\\s*(.+)").find(text)
                    if (match != null) {
                        val value = match.groupValues[1].trim()
                        if (value.isNotBlank()) return value
                    }
                    val next = element.nextElementSibling()
                    if (next != null) {
                        val nextText = next.text().trim()
                        if (nextText.isNotBlank() && nextText.length < 200) return nextText
                    }
                }
            }
        }
        return null
    }

    private fun extractTrailerUrl(document: Document): String? {
        val html = document.html()
        Regex("(?:youtube\\.com/embed/|youtu\\.be/|youtube\\.com/watch\\?v=)([a-zA-Z0-9_-]{11})")
            .find(html)?.let {
                return "https://www.youtube.com/watch?v=${it.groupValues[1]}"
            }
        document.selectFirst("a[href*=trailer], a:contains(Trailer)")?.let {
            return it.attr("href")
        }
        return null
    }

    private fun extractRecommendations(document: Document): List<SearchResponse> {
        val recs = mutableListOf<SearchResponse>()
        val selectors = listOf(
            ".related-posts article", ".related-post .item",
            ".related-movies .item", ".recommendations .item",
            "#related-posts article", ".yarpp-related li"
        )
        for (selector in selectors) {
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                elements.forEach { el -> parseArticle(el)?.let { recs.add(it) } }
                break
            }
        }
        return recs.distinctBy { it.url }
    }

    private fun detectContentType(url: String, title: String, document: Document): TvType {
        val hasEpisodes = document.select(
            "a:contains(Episode), a:contains(Ep ), .episode-list, .season-list, " +
                    "a[href*='episode'], a[href*='season']"
        ).isNotEmpty()

        val c = "$url $title".lowercase()
        return when {
            hasEpisodes -> TvType.TvSeries
            c.contains("anime") -> TvType.Anime
            c.contains("series") || c.contains("season") -> TvType.TvSeries
            c.contains("drama") || c.contains("kdrama") || c.contains("turkish") -> TvType.AsianDrama
            c.contains("web series") || c.contains("web-series") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    @Suppress("unused")
    private suspend fun extractEpisodes(document: Document, pageUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val content = document.selectFirst(".entry-content, .post-content, article") ?: return listOf(
            newEpisode(pageUrl) {
                this.name = "Full Content"
                this.season = 1
                this.episode = 1
            }
        )

        // Method 1: episode links
        val epLinks = content.select(
            "a[href*='episode'], a[href*='ep-'], a[href*='ep.']"
        ) + content.select("a").filter { a ->
            a.text().contains(Regex("(?i)episode\\s*\\d+|ep\\s*\\d+"))
        }

        if (epLinks.isNotEmpty()) {
            epLinks.forEach { link ->
                val href = link.attr("href")
                val text = link.text().trim()
                if (href.startsWith("http") && text.isNotBlank()) {
                    val epNum = NowHDTimeUtils.extractEpisodeNumber(text)
                        ?: NowHDTimeUtils.extractEpisodeNumber(href)
                    val seasonNum = NowHDTimeUtils.extractSeasonNumber(text)
                        ?: NowHDTimeUtils.extractSeasonNumber(href)
                        ?: 1

                    episodes.add(newEpisode(href) {
                        this.name = text
                        this.season = seasonNum
                        this.episode = epNum
                    })
                }
            }
        }

        // Method 2: season headings with links below
        if (episodes.isEmpty()) {
            val headings = content.select("h2, h3, h4, strong, b")
            headings.forEach { heading ->
                val headingText = heading.text().trim()
                if (headingText.contains(Regex("(?i)episode|ep\\s*\\d"))) {
                    val epNum = NowHDTimeUtils.extractEpisodeNumber(headingText)
                    val seasonNum = NowHDTimeUtils.extractSeasonNumber(headingText) ?: 1
                    var next = heading.nextElementSibling()
                    while (next != null) {
                        val links = next.select("a[href]")
                        if (links.isNotEmpty()) {
                            val first = links.first()!!
                            val href = first.attr("href")
                            if (href.startsWith("http")) {
                                episodes.add(newEpisode(href) {
                                    this.name = headingText
                                    this.season = seasonNum
                                    this.episode = epNum
                                })
                                break
                            }
                        }
                        next = next.nextElementSibling()
                    }
                }
            }
        }

        // Method 3: internal episode pages
        if (episodes.isEmpty()) {
            document.select("a[href*='$mainUrl']").filter { link ->
                val href = link.attr("href")
                href != pageUrl && (href.contains("episode") || href.contains("ep-"))
            }.forEachIndexed { index, link ->
                val href = link.attr("href")
                val text = link.text().trim()
                val epNum = NowHDTimeUtils.extractEpisodeNumber(text)
                    ?: NowHDTimeUtils.extractEpisodeNumber(href)
                    ?: (index + 1)

                episodes.add(newEpisode(href) {
                    this.name = text.ifBlank { "Episode $epNum" }
                    this.season = NowHDTimeUtils.extractSeasonNumber(text)
                        ?: NowHDTimeUtils.extractSeasonNumber(href) ?: 1
                    this.episode = epNum
                })
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(pageUrl) {
                this.name = "Full Content"
                this.season = 1
                this.episode = 1
            })
        }

        return episodes.distinctBy { "${it.season}-${it.episode}-${it.data}" }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    // ── Load Links ──
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, timeout = 30).document
        val html = document.html()
        var found = false

        // 1. Iframes
        for (iframe in document.select("iframe[src]")) {
            val src = iframe.attr("src").let {
                if (it.startsWith("//")) "https:$it" else it
            }
            if (src.startsWith("http")) {
                try {
                    if (loadExtractor(src, mainUrl, subtitleCallback, callback)) found = true
                } catch (_: Exception) {
                    tryDirectExtraction(src, callback)?.let { found = true }
                }
            }
        }

        // 2. Embed URLs from HTML
        for (embedUrl in NowHDTimeUtils.extractEmbedUrls(html)) {
            if (!embedUrl.contains("nowhdtime.com.bd")) {
                try {
                    if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) found = true
                } catch (_: Exception) {
                    tryDirectExtraction(embedUrl, callback)?.let { found = true }
                }
            }
        }

        // 3. Download links
        for ((linkUrl, linkText) in NowHDTimeUtils.extractDownloadLinks(document)) {
            try {
                val quality = NowHDTimeUtils.getQualityFromString(linkText)
                if (isDirectVideoUrl(linkUrl)) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name - ${linkText.ifBlank { "Direct" }}",
                            url = linkUrl,
                            referer = mainUrl,
                            quality = quality,
                            isM3u8 = linkUrl.contains(".m3u8")
                        )
                    )
                    found = true
                } else {
                    if (loadExtractor(linkUrl, mainUrl, subtitleCallback, callback)) found = true
                }
            } catch (_: Exception) {
                try {
                    val resp = app.get(linkUrl, allowRedirects = false, timeout = 15)
                    val redir = resp.headers["Location"]
                    if (redir != null) {
                        if (isDirectVideoUrl(redir)) {
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = "$name - ${linkText.ifBlank { "Redirect" }}",
                                    url = redir,
                                    referer = mainUrl,
                                    quality = NowHDTimeUtils.getQualityFromString(linkText),
                                    isM3u8 = redir.contains(".m3u8")
                                )
                            )
                            found = true
                        } else {
                            if (loadExtractor(redir, mainUrl, subtitleCallback, callback)) found = true
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 4. JS sources
        extractJSLinks(html, callback, subtitleCallback)?.let { found = true }

        // 5. WP Ajax player endpoints
        extractPlayerAPILinks(document, data, callback, subtitleCallback)?.let { found = true }

        // 6. Fallback: all content-area links
        if (!found) {
            val contentLinks = document.select(
                ".entry-content a[href], .post-content a[href], article a[href]"
            )
            for (link in contentLinks) {
                val href = link.attr("href")
                if (href.startsWith("http")
                    && !href.contains("nowhdtime.com.bd")
                    && !href.contains("t.me")
                    && !href.contains("facebook")
                    && !href.contains("twitter")
                    && !href.contains("instagram")
                    && !href.contains("youtube")
                ) {
                    try {
                        if (isDirectVideoUrl(href)) {
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = "$name - ${link.text().ifBlank { "Link" }}",
                                    url = href,
                                    referer = mainUrl,
                                    quality = NowHDTimeUtils.getQualityFromString(link.text()),
                                    isM3u8 = href.contains(".m3u8")
                                )
                            )
                            found = true
                        } else {
                            if (loadExtractor(href, mainUrl, subtitleCallback, callback)) found = true
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 7. Subtitles
        extractSubtitles(document, html, subtitleCallback)

        return found
    }

    // ── Helpers ──
    private fun isDirectVideoUrl(url: String): Boolean {
        val exts = listOf(".mp4", ".mkv", ".avi", ".m3u8", ".mpd", ".webm", ".mov", ".ts")
        return exts.any { url.lowercase().contains(it) }
    }

    private suspend fun tryDirectExtraction(url: String, callback: (ExtractorLink) -> Unit): Boolean? {
        return try {
            val resp = app.get(url, timeout = 15, referer = mainUrl)
            val pageHtml = resp.text
            var found = false
            for (videoUrl in NowHDTimeUtils.extractEmbedUrls(pageHtml)) {
                if (isDirectVideoUrl(videoUrl)) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name - Direct",
                            url = videoUrl,
                            referer = url,
                            quality = Qualities.Unknown.value,
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                    )
                    found = true
                }
            }
            if (found) true else null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractJSLinks(
        html: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean? {
        var found = false

        val patterns = listOf(
            Regex("""(?i)(?:file|src|source|video_url|stream_url|mp4|hls)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8|mkv|mpd)[^"']*)["']"""),
            Regex("""(?i)sources\s*:\s*\[\s*\{[^}]*(?:file|src)\s*:\s*["']([^"']+)["']"""),
            Regex("""(?i)(?:"file"|"src"|"url"|"source"|"video")\s*:\s*"(https?://[^"]+)""""),
        )

        for (pattern in patterns) {
            pattern.findAll(html).forEach { match ->
                val url = match.groupValues[1]
                if (url.startsWith("http") && isDirectVideoUrl(url)) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name - JS Source",
                            url = url,
                            referer = mainUrl,
                            quality = NowHDTimeUtils.getQualityFromString(url),
                            isM3u8 = url.contains(".m3u8")
                        )
                    )
                    found = true
                }
            }
        }

        // Base64 encoded URLs
        Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""").findAll(html).forEach { match ->
            try {
                val decoded = String(
                    android.util.Base64.decode(match.groupValues[1], android.util.Base64.DEFAULT)
                )
                if (decoded.startsWith("http") && isDirectVideoUrl(decoded)) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name - Decoded",
                            url = decoded,
                            referer = mainUrl,
                            quality = NowHDTimeUtils.getQualityFromString(decoded),
                            isM3u8 = decoded.contains(".m3u8")
                        )
                    )
                    found = true
                }
            } catch (_: Exception) {}
        }

        // JS subtitle tracks
        Regex("""(?i)(?:tracks|subtitles|captions)\s*[:=]\s*\[([^\]]+)]""").find(html)?.let { trackMatch ->
            val tracks = trackMatch.groupValues[1]
            Regex("""(?i)(?:file|src)\s*:\s*["']([^"']+\.(?:srt|vtt|ass)[^"']*)["']""").findAll(tracks).forEach { sub ->
                val lang = Regex("""(?i)label\s*:\s*["']([^"']+)["']""").find(tracks)?.groupValues?.get(1)
                    ?: "Unknown"
                subtitleCallback.invoke(SubtitleFile(lang, sub.groupValues[1]))
            }
        }

        return if (found) true else null
    }

    private suspend fun extractPlayerAPILinks(
        document: Document,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean? {
        var found = false
        val html = document.html()

        // WordPress admin-ajax.php
        if (html.contains("admin-ajax.php", ignoreCase = true)) {
            val nonce = Regex("""(?i)(?:nonce|_wpnonce)\s*[:=]\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            val action = Regex("""(?i)action\s*[:=]\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            val postId = Regex("""(?i)(?:post_id|postid|id)\s*[:=]\s*["']?(\d+)["']?""").find(html)?.groupValues?.get(1)

            if (nonce != null && action != null && postId != null) {
                try {
                    val ajaxResp = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to action,
                            "nonce" to nonce,
                            "post_id" to postId
                        ),
                        referer = pageUrl,
                        timeout = 15
                    )

                    for (videoUrl in NowHDTimeUtils.extractEmbedUrls(ajaxResp.text)) {
                        try {
                            if (isDirectVideoUrl(videoUrl)) {
                                callback.invoke(
                                    ExtractorLink(
                                        source = name,
                                        name = "$name - WP Ajax",
                                        url = videoUrl,
                                        referer = pageUrl,
                                        quality = NowHDTimeUtils.getQualityFromString(videoUrl),
                                        isM3u8 = videoUrl.contains(".m3u8")
                                    )
                                )
                                found = true
                            } else {
                                if (loadExtractor(videoUrl, pageUrl, subtitleCallback, callback)) found = true
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }

        // Generic AJAX/fetch endpoints
        val apiPatterns = listOf(
            Regex("""(?i)(?:ajax_url|api_url|player_api)\s*[:=]\s*["']([^"']+)["']"""),
            Regex("""\$\.(?:ajax|get|post)\s*\(\s*["']([^"']+)["']"""),
            Regex("""fetch\s*\(\s*["']([^"']+)["']""")
        )
        for (pattern in apiPatterns) {
            pattern.findAll(html).forEach { match ->
                val apiUrl = match.groupValues[1]
                val fullUrl = when {
                    apiUrl.startsWith("//") -> "https:$apiUrl"
                    apiUrl.startsWith("/") -> "$mainUrl$apiUrl"
                    apiUrl.startsWith("http") -> apiUrl
                    else -> return@forEach
                }
                try {
                    val apiResp = app.get(fullUrl, referer = pageUrl, timeout = 15)
                    Regex(""""(https?://[^"]+\.(?:mp4|m3u8|mkv|mpd)[^"]*?)"""").findAll(apiResp.text).forEach { vidMatch ->
                        val videoUrl = vidMatch.groupValues[1]
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name - API",
                                url = videoUrl,
                                referer = pageUrl,
                                quality = NowHDTimeUtils.getQualityFromString(videoUrl),
                                isM3u8 = videoUrl.contains(".m3u8")
                            )
                        )
                        found = true
                    }
                } catch (_: Exception) {}
            }
        }

        return if (found) true else null
    }

    private fun extractSubtitles(document: Document, html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        // <track> elements
        document.select("track[src], track[data-src]").forEach { track ->
            val src = track.attr("src").ifBlank { track.attr("data-src") }
            val label = track.attr("label").ifBlank { track.attr("srclang").ifBlank { "Unknown" } }
            if (src.isNotBlank()) {
                subtitleCallback.invoke(SubtitleFile(label, NowHDTimeUtils.fixPosterUrl(src) ?: src))
            }
        }

        // Subtitle file links
        val subExts = listOf(".srt", ".vtt", ".ass", ".sub", ".ssa")
        document.select("a[href]").forEach { link ->
            val href = link.attr("href")
            if (subExts.any { href.lowercase().contains(it) }) {
                subtitleCallback.invoke(SubtitleFile(link.text().ifBlank { "Subtitle" }, href))
            }
        }

        // Subtitle URLs in JS
        Regex("""["'](https?://[^"']+\.(?:srt|vtt|ass|sub|ssa)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { match ->
                subtitleCallback.invoke(SubtitleFile("Auto", match.groupValues[1]))
            }
    }
}