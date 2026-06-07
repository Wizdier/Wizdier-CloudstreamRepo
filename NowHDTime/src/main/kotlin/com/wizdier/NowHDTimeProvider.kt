package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.jsoup.Jsoup
import java.util.regex.Pattern

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
        TvType.AsianDrama,
        TvType.Cartoon
    )

    // ===== Main Page Configuration =====
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Updates",
        "$mainUrl/category/bangla-dubbed-movie/page/" to "Bangla Dubbed Movies",
        "$mainUrl/category/bangla-movie/page/" to "Bangla Movies",
        "$mainUrl/category/bollywood/page/" to "Bollywood",
        "$mainUrl/category/hollywood/page/" to "Hollywood",
        "$mainUrl/category/south-indian-movie/page/" to "South Indian Movies",
        "$mainUrl/category/tamil-movie/page/" to "Tamil Movies",
        "$mainUrl/category/korean-movie/page/" to "Korean Movies",
        "$mainUrl/category/chinese-movie/page/" to "Chinese Movies",
        "$mainUrl/category/anime/page/" to "Anime",
        "$mainUrl/category/tv-series/page/" to "TV Series",
        "$mainUrl/category/web-series/page/" to "Web Series",
        "$mainUrl/category/korean-drama/page/" to "Korean Drama",
        "$mainUrl/category/turkish-drama/page/" to "Turkish Drama",
        "$mainUrl/category/cartoon/page/" to "Cartoon",
        "$mainUrl/category/dual-audio/page/" to "Dual Audio",
        "$mainUrl/category/18-plus/page/" to "18+ Movies"
    )

    // ===== Main Page Loading =====
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

    // ===== Parse Page Items =====
    private fun parsePageItems(document: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // Try multiple article/post selectors common in WordPress themes
        val articleSelectors = listOf(
            "article",
            ".post",
            ".item",
            ".movie-item",
            ".film-item",
            ".result-item",
            ".blog-post",
            ".entry",
            ".content-item",
            ".videos .video",
            ".movies .movie",
            ".items .item",
            ".post-item",
            ".card"
        )

        for (selector in articleSelectors) {
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                elements.forEach { element ->
                    parseArticleElement(element)?.let { results.add(it) }
                }
                if (results.isNotEmpty()) break
            }
        }

        // Fallback: parse all links with thumbnails
        if (results.isEmpty()) {
            document.select(".entry-content a, .post-content a, .content a").forEach { element ->
                val href = element.attr("href")
                if (href.startsWith(mainUrl) && href != mainUrl && !href.contains("/category/") && !href.contains("/tag/") && !href.contains("/page/")) {
                    val img = element.selectFirst("img")
                    if (img != null) {
                        val title = element.attr("title").ifBlank { img.attr("alt") }.ifBlank { element.text() }
                        val posterUrl = NowHDTimeUtils.fixPosterUrl(
                            img.attr("data-src").ifBlank { img.attr("data-lazy-src").ifBlank { img.attr("src") } }
                        )

                        if (title.isNotBlank()) {
                            val type = detectType(href, title)
                            val year = NowHDTimeUtils.extractYear(title)

                            val response = if (type == TvType.TvSeries || type == TvType.Anime || type == TvType.AsianDrama) {
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
                            results.add(response)
                        }
                    }
                }
            }
        }

        return results.distinctBy { it.url }
    }

    // ===== Parse Individual Article Element =====
    private fun parseArticleElement(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a[href]") ?: return null
        val href = linkElement.attr("href")

        if (href.isBlank() || !href.startsWith(mainUrl) || href == mainUrl ||
            href.contains("/category/") || href.contains("/tag/") || href.contains("/page/") ||
            href.contains("/author/")) return null

        // Title extraction with multiple fallbacks
        val title = element.selectFirst("h2 a, h3 a, .entry-title a, .post-title a, .title a")?.text()
            ?: element.selectFirst("h2, h3, .entry-title, .post-title, .title")?.text()
            ?: linkElement.attr("title")
            ?: element.selectFirst("img")?.attr("alt")
            ?: return null

        if (title.isBlank()) return null

        // Poster URL extraction with lazy-load support
        val posterUrl = NowHDTimeUtils.fixPosterUrl(
            element.selectFirst("img")?.let { img ->
                img.attr("data-src")
                    .ifBlank { img.attr("data-lazy-src") }
                    .ifBlank { img.attr("data-original") }
                    .ifBlank { img.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull() ?: "" }
                    .ifBlank { img.attr("src") }
            }
        )

        val qualityText = element.selectFirst(".quality, .qlty, span.quality, .badge")?.text()
        val quality = getSearchQualityFromText(qualityText)

        val year = NowHDTimeUtils.extractYear(title)
            ?: NowHDTimeUtils.extractYear(element.selectFirst(".year, .date, .entry-date, time")?.text() ?: "")

        val type = detectType(href, title)

        return if (type == TvType.TvSeries || type == TvType.Anime || type == TvType.AsianDrama || type == TvType.Cartoon) {
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

    // ===== Type Detection =====
    private fun detectType(url: String, title: String): TvType {
        val combined = "$url $title".lowercase()
        return when {
            combined.contains("series") || combined.contains("season") || combined.contains("episode") || combined.contains("ep-") -> TvType.TvSeries
            combined.contains("anime") -> TvType.Anime
            combined.contains("cartoon") || combined.contains("animation") -> TvType.Cartoon
            combined.contains("drama") || combined.contains("kdrama") || combined.contains("cdrama") || combined.contains("korean-drama") || combined.contains("turkish") -> TvType.AsianDrama
            combined.contains("web-series") || combined.contains("webseries") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    // ===== Search Quality =====
    private fun getSearchQualityFromText(quality: String?): SearchQuality? {
        if (quality.isNullOrBlank()) return null
        val q = quality.lowercase().trim()
        return when {
            q.contains("cam") || q.contains("hdcam") || q.contains("ts") -> SearchQuality.CamQuality
            q.contains("hdtc") || q.contains("hd-tc") -> SearchQuality.HdCam
            q.contains("webrip") || q.contains("web-dl") || q.contains("webdl") -> SearchQuality.WebRip
            q.contains("bluray") || q.contains("blu-ray") || q.contains("bdrip") -> SearchQuality.BlueRay
            q.contains("dvd") || q.contains("dvdrip") -> SearchQuality.SD
            q.contains("hdrip") || q.contains("hd") -> SearchQuality.HD
            else -> null
        }
    }

    // ===== Next Page Detection =====
    private fun hasNextPage(document: Document): Boolean {
        return document.selectFirst(
            "a.next, .next a, .pagination .next, .nav-next a, a:contains(Next), a:contains(›), a.page-numbers.next, li.next a"
        ) != null
    }

    // ===== Search =====
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl, timeout = 30).document
        return parsePageItems(document)
    }

    // ===== Load Content Page =====
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 30).document

        // ===== Title =====
        val rawTitle = document.selectFirst(
            "h1.entry-title, h1.post-title, h1.title, .entry-title, h1, .movie-title"
        )?.text() ?: document.title()

        val title = NowHDTimeUtils.cleanTitle(rawTitle)

        // ===== Poster =====
        val posterUrl = NowHDTimeUtils.fixPosterUrl(
            document.selectFirst(".entry-content img, .post-content img, .post-thumbnail img, article img, .featured-image img, .movie-poster img")?.let { img ->
                img.attr("data-src")
                    .ifBlank { img.attr("data-lazy-src") }
                    .ifBlank { img.attr("data-original") }
                    .ifBlank { img.attr("src") }
            } ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        // ===== Description/Plot =====
        val description = extractDescription(document)

        // ===== Year =====
        val year = NowHDTimeUtils.extractYear(rawTitle)
            ?: extractMetaField(document, listOf("Release", "Year", "Released"))?.let { NowHDTimeUtils.extractYear(it) }

        // ===== Tags/Genres =====
        val tags = NowHDTimeUtils.extractTags(document).ifEmpty {
            extractMetaField(document, listOf("Genre", "Genres", "Category"))?.split(",", "|", "/")?.map { it.trim() }?.filter { it.isNotBlank() }
        }

        // ===== Duration =====
        val duration = extractMetaField(document, listOf("Runtime", "Duration", "Time", "Length"))?.let {
            NowHDTimeUtils.parseDuration(it)
        }

        // ===== Rating =====
        val rating = extractMetaField(document, listOf("IMDB", "Rating", "IMDb Rating", "IMDB Rating"))?.let {
            NowHDTimeUtils.extractRating(it)
        }

        // ===== Actors =====
        val actors = NowHDTimeUtils.extractActors(document).ifEmpty {
            extractMetaField(document, listOf("Cast", "Stars", "Actors", "Starring"))?.split(",")?.map {
                ActorData(Actor(it.trim()))
            }
        }

        // ===== Director =====
        val director = extractMetaField(document, listOf("Director", "Directors", "Directed by"))

        // ===== Country =====
        val country = extractMetaField(document, listOf("Country", "Countries", "Origin"))

        // ===== Language =====
        val language = extractMetaField(document, listOf("Language", "Languages", "Audio"))

        // ===== Tracking IDs =====
        val trackingIds = NowHDTimeUtils.extractTrackingIds(document)

        // ===== Trailer =====
        val trailerUrl = extractTrailerUrl(document)

        // ===== Recommendations =====
        val recommendations = extractRecommendations(document)

        // ===== Determine Type =====
        val type = detectContentType(url, rawTitle, document)

        // ===== Build Recommendations String =====
        val fullDescription = buildString {
            if (!description.isNullOrBlank()) append(description)
            if (!director.isNullOrBlank()) append("\n\n🎬 Director: $director")
            if (!country.isNullOrBlank()) append("\n🌍 Country: $country")
            if (!language.isNullOrBlank()) append("\n🗣️ Language: $language")
        }.trim()

        // ===== Build Response =====
        return if (type == TvType.TvSeries || type == TvType.Anime || type == TvType.AsianDrama || type == TvType.Cartoon) {
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

    // ===== Extract Description =====
    private fun extractDescription(document: Document): String? {
        // Try structured synopsis/description elements first
        val synopsisSelectors = listOf(
            ".synopsis", ".description", ".plot", ".story",
            ".movie-description", ".movie-synopsis",
            ".entry-content > p:first-of-type",
            ".post-content > p:first-of-type",
            "meta[name=description]",
            "meta[property=og:description]"
        )

        for (selector in synopsisSelectors) {
            val element = document.selectFirst(selector)
            if (element != null) {
                val text = if (selector.startsWith("meta")) element.attr("content") else element.text()
                if (text.isNotBlank() && text.length > 20) return text.trim()
            }
        }

        // Try to find paragraph text in content area, excluding download links
        val contentArea = document.selectFirst(".entry-content, .post-content, article .content, .single-content")
        if (contentArea != null) {
            val paragraphs = contentArea.select("p")
            for (p in paragraphs) {
                val text = p.text().trim()
                // Skip paragraphs that look like metadata or download info
                if (text.length > 50 &&
                    !text.lowercase().contains("download") &&
                    !text.lowercase().contains("screenshot") &&
                    !text.lowercase().contains("click here") &&
                    !text.lowercase().contains("join telegram") &&
                    !text.lowercase().contains("file size")) {
                    return text
                }
            }
        }

        return null
    }

    // ===== Extract Meta Field =====
    private fun extractMetaField(document: Document, fieldNames: List<String>): String? {
        val contentArea = document.selectFirst(".entry-content, .post-content, article, .single-content, .movie-info, .info-content")
            ?: document

        for (fieldName in fieldNames) {
            // Try structured table/list selectors
            val selectors = listOf(
                "span:contains($fieldName)",
                "strong:contains($fieldName)",
                "b:contains($fieldName)",
                "li:contains($fieldName)",
                "p:contains($fieldName)",
                "div:contains($fieldName)",
                "td:contains($fieldName)",
                "th:contains($fieldName)",
                ".meta-item:contains($fieldName)"
            )

            for (selector in selectors) {
                val elements = contentArea.select(selector)
                for (element in elements) {
                    val text = element.text()
                    val pattern = Pattern.compile("$fieldName\\s*[:：]\\s*(.+)", Pattern.CASE_INSENSITIVE)
                    val matcher = pattern.matcher(text)
                    if (matcher.find()) {
                        val value = matcher.group(1)?.trim()
                        if (!value.isNullOrBlank()) return value
                    }

                    // Check next sibling or parent's next element
                    val nextSibling = element.nextElementSibling()
                    if (nextSibling != null) {
                        val siblingText = nextSibling.text().trim()
                        if (siblingText.isNotBlank() && siblingText.length < 200) return siblingText
                    }
                }
            }
        }

        return null
    }

    // ===== Extract Trailer URL =====
    private fun extractTrailerUrl(document: Document): String? {
        val html = document.html()

        // YouTube embed
        val ytPatterns = listOf(
            Pattern.compile("(?:youtube\\.com/embed/|youtu\\.be/|youtube\\.com/watch\\?v=)([a-zA-Z0-9_-]{11})"),
            Pattern.compile("youtube\\.com/embed/([a-zA-Z0-9_-]{11})")
        )

        for (pattern in ytPatterns) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                return "https://www.youtube.com/watch?v=${matcher.group(1)}"
            }
        }

        // Direct trailer link
        document.select("a[href*=trailer], a:contains(Trailer), .trailer a").firstOrNull()?.let {
            return it.attr("href")
        }

        return null
    }

    // ===== Extract Recommendations =====
    private fun extractRecommendations(document: Document): List<SearchResponse> {
        val recommendations = mutableListOf<SearchResponse>()

        val recSelectors = listOf(
            ".related-posts article",
            ".related-post .item",
            ".related-movies .item",
            ".recommendations .item",
            ".you-may-also-like .item",
            "#related-posts article",
            ".widget_related_entries li",
            ".yarpp-related li"
        )

        for (selector in recSelectors) {
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                elements.forEach { element ->
                    parseArticleElement(element)?.let { recommendations.add(it) }
                }
                break
            }
        }

        return recommendations.distinctBy { it.url }
    }

    // ===== Detect Content Type =====
    private fun detectContentType(url: String, title: String, document: Document): TvType {
        val combined = "$url $title ${document.text()}".lowercase()

        // Check for episode patterns in the page
        val hasEpisodes = document.select(
            "a:contains(Episode), a:contains(Ep ), .episode-list, .season-list, " +
            "a[href*='episode'], a[href*='season'], h2:contains(Episode), h3:contains(Episode), " +
            "h2:contains(Season), h3:contains(Season)"
        ).isNotEmpty()

        return when {
            hasEpisodes -> TvType.TvSeries
            combined.contains("anime") -> TvType.Anime
            combined.contains("cartoon") || combined.contains("animation") -> TvType.Cartoon
            combined.contains("series") || combined.contains("season") -> TvType.TvSeries
            combined.contains("drama") || combined.contains("kdrama") || combined.contains("korean drama") || combined.contains("turkish") -> TvType.AsianDrama
            combined.contains("web series") || combined.contains("web-series") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    // ===== Extract Episodes =====
    private suspend fun extractEpisodes(document: Document, pageUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // Method 1: Direct episode links in content
        val contentArea = document.selectFirst(".entry-content, .post-content, article, .single-content")
        if (contentArea != null) {
            // Look for episode links
            val episodeLinks = contentArea.select(
                "a[href*='episode'], a[href*='ep-'], a[href*='ep.'], " +
                "a:matchesOwn((?i)episode\\s*\\d+), a:matchesOwn((?i)ep\\s*\\d+), " +
                "a:matchesOwn((?i)e\\d+)"
            )

            if (episodeLinks.isNotEmpty()) {
                episodeLinks.forEach { link ->
                    val href = link.attr("href")
                    val text = link.text().trim()
                    if (href.startsWith("http") && text.isNotBlank()) {
                        val epNum = NowHDTimeUtils.extractEpisodeNumber(text)
                            ?: NowHDTimeUtils.extractEpisodeNumber(href)

                        val seasonNum = NowHDTimeUtils.extractSeasonNumber(text)
                            ?: NowHDTimeUtils.extractSeasonNumber(href)
                            ?: 1

                        episodes.add(
                            newEpisode(href) {
                                this.name = text
                                this.season = seasonNum
                                this.episode = epNum
                            }
                        )
                    }
                }
            }

            // Method 2: Season/Episode structure in headings
            if (episodes.isEmpty()) {
                val headings = contentArea.select("h2, h3, h4, strong, b")
                var currentSeason = 1
                var currentEpisode = 0

                headings.forEach { heading ->
                    val headingText = heading.text().trim()

                    // Check if this is a season heading
                    NowHDTimeUtils.extractSeasonNumber(headingText)?.let { currentSeason = it }

                    // Check if this heading contains episode info
                    if (headingText.contains("episode", ignoreCase = true) || headingText.contains("ep", ignoreCase = true)) {
                        NowHDTimeUtils.extractEpisodeNumber(headingText)?.let { currentEpisode = it }

                        // Find the next link after this heading
                        var nextElement = heading.nextElementSibling()
                        while (nextElement != null) {
                            val links = nextElement.select("a[href]")
                            if (links.isNotEmpty()) {
                                val firstLink = links.first()!!
                                val href = firstLink.attr("href")
                                if (href.startsWith("http")) {
                                    episodes.add(
                                        newEpisode(href) {
                                            this.name = headingText
                                            this.season = currentSeason
                                            this.episode = currentEpisode
                                        }
                                    )
                                    break
                                }
                            }
                            nextElement = nextElement.nextElementSibling()
                        }
                    }
                }
            }

            // Method 3: Links organized in groups (common pattern)
            if (episodes.isEmpty()) {
                val allLinks = contentArea.select("a[href]").filter { link ->
                    val href = link.attr("href")
                    href.startsWith("http") &&
                    !href.contains("nowhdtime.com.bd") &&
                    !href.contains("t.me") &&
                    !href.contains("facebook") &&
                    !href.contains("twitter") &&
                    !href.contains("instagram") &&
                    (href.contains("episode", ignoreCase = true) ||
                     href.contains("ep", ignoreCase = true) ||
                     link.text().contains("episode", ignoreCase = true) ||
                     link.text().contains("ep", ignoreCase = true))
                }

                allLinks.forEachIndexed { index, link ->
                    val href = link.attr("href")
                    val text = link.text().trim()
                    val epNum = NowHDTimeUtils.extractEpisodeNumber(text)
                        ?: NowHDTimeUtils.extractEpisodeNumber(href)
                        ?: (index + 1)

                    episodes.add(
                        newEpisode(href) {
                            this.name = text.ifBlank { "Episode $epNum" }
                            this.season = 1
                            this.episode = epNum
                        }
                    )
                }
            }
        }

        // Method 4: If page has internal episode pages, load them
        if (episodes.isEmpty()) {
            document.select("a[href*='${mainUrl}']").filter { link ->
                val href = link.attr("href")
                href != pageUrl && (href.contains("episode") || href.contains("ep-"))
            }.forEachIndexed { index, link ->
                val href = link.attr("href")
                val text = link.text().trim()
                val epNum = NowHDTimeUtils.extractEpisodeNumber(text)
                    ?: NowHDTimeUtils.extractEpisodeNumber(href)
                    ?: (index + 1)

                episodes.add(
                    newEpisode(href) {
                        this.name = text.ifBlank { "Episode $epNum" }
                        this.season = NowHDTimeUtils.extractSeasonNumber(text) ?: NowHDTimeUtils.extractSeasonNumber(href) ?: 1
                        this.episode = epNum
                    }
                )
            }
        }

        // If still no episodes found but it's detected as a series, add as single "episode"
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(pageUrl) {
                    this.name = "Full Content"
                    this.season = 1
                    this.episode = 1
                }
            )
        }

        return episodes.distinctBy { "${it.season}-${it.episode}-${it.data}" }.sortedWith(
            compareBy({ it.season }, { it.episode })
        )
    }

    // ===== Load Links =====
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, timeout = 30).document
        val html = document.html()
        var linksFound = false

        // ===== Method 1: Extract all iframes =====
        val iframes = document.select("iframe[src]")
        for (iframe in iframes) {
            val src = iframe.attr("src").let {
                if (it.startsWith("//")) "https:$it" else it
            }
            if (src.startsWith("http")) {
                try {
                    loadExtractor(src, mainUrl, subtitleCallback, callback)
                    linksFound = true
                } catch (e: Exception) {
                    // Try direct extraction
                    tryDirectExtraction(src, callback)?.let { linksFound = true }
                }
            }
        }

        // ===== Method 2: Extract embeds from HTML/JavaScript =====
        val embedUrls = NowHDTimeUtils.extractEmbedUrls(html)
        for (embedUrl in embedUrls) {
            try {
                if (!embedUrl.contains("nowhdtime.com.bd")) {
                    loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                    linksFound = true
                }
            } catch (e: Exception) {
                tryDirectExtraction(embedUrl, callback)?.let { linksFound = true }
            }
        }

        // ===== Method 3: Extract download/streaming links =====
        val downloadLinks = NowHDTimeUtils.extractDownloadLinks(document)
        for ((linkUrl, linkText) in downloadLinks) {
            try {
                val quality = NowHDTimeUtils.getQualityFromString(linkText)

                // Check if it's a direct video file
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
                    linksFound = true
                } else {
                    // Try extractor
                    loadExtractor(linkUrl, mainUrl, subtitleCallback, callback)
                    linksFound = true
                }
            } catch (e: Exception) {
                // Try following redirect
                try {
                    val redirectResponse = app.get(linkUrl, allowRedirects = false, timeout = 15)
                    val redirectUrl = redirectResponse.headers["Location"]
                    if (redirectUrl != null) {
                        if (isDirectVideoUrl(redirectUrl)) {
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = "$name - ${linkText.ifBlank { "Redirect" }}",
                                    url = redirectUrl,
                                    referer = mainUrl,
                                    quality = NowHDTimeUtils.getQualityFromString(linkText),
                                    isM3u8 = redirectUrl.contains(".m3u8")
                                )
                            )
                            linksFound = true
                        } else {
                            loadExtractor(redirectUrl, mainUrl, subtitleCallback, callback)
                            linksFound = true
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // ===== Method 4: Extract links from JavaScript variables =====
        extractJSLinks(html, callback, subtitleCallback)?.let { linksFound = true }

        // ===== Method 5: Check for player API endpoints =====
        extractPlayerAPILinks(document, data, callback, subtitleCallback)?.let { linksFound = true }

        // ===== Method 6: Extract all content area links as fallback =====
        if (!linksFound) {
            val contentLinks = document.select(".entry-content a[href], .post-content a[href], article a[href]")
            for (link in contentLinks) {
                val href = link.attr("href")
                if (href.startsWith("http") &&
                    !href.contains("nowhdtime.com.bd") &&
                    !href.contains("t.me") &&
                    !href.contains("facebook") &&
                    !href.contains("twitter") &&
                    !href.contains("instagram") &&
                    !href.contains("youtube")) {
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
                            linksFound = true
                        } else {
                            loadExtractor(href, mainUrl, subtitleCallback, callback)
                            linksFound = true
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // ===== Method 7: Subtitle extraction =====
        extractSubtitles(document, html, subtitleCallback)

        return linksFound
    }

    // ===== Helper: Direct Video URL Check =====
    private fun isDirectVideoUrl(url: String): Boolean {
        val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".m3u8", ".mpd", ".webm", ".mov", ".flv", ".wmv", ".ts")
        return videoExtensions.any { url.lowercase().contains(it) }
    }

    // ===== Helper: Try Direct Extraction =====
    private suspend fun tryDirectExtraction(url: String, callback: (ExtractorLink) -> Unit): Boolean? {
        return try {
            val response = app.get(url, timeout = 15, referer = mainUrl)
            val pageHtml = response.text

            val videoUrls = NowHDTimeUtils.extractEmbedUrls(pageHtml)
            var found = false
            for (videoUrl in videoUrls) {
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

    // ===== Helper: Extract JS Links =====
    private suspend fun extractJSLinks(
        html: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean? {
        var found = false

        // Look for common player configurations in JS
        val patterns = listOf(
            // file: "url" pattern (JW Player, Video.js, etc.)
            Pattern.compile("(?:file|src|source|video_url|stream_url|player_url|mp4|hls)\\s*[:=]\\s*[\"']([^\"']+\\.(mp4|m3u8|mkv|mpd)[^\"']*)[\"']", Pattern.CASE_INSENSITIVE),
            // sources: [{file: "url"}] pattern
            Pattern.compile("sources\\s*:\\s*\\[\\s*\\{[^}]*(?:file|src)\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            // JSON encoded URLs
            Pattern.compile("(?:\"file\"|\"src\"|\"url\"|\"source\"|\"video\")\\s*:\\s*\"(https?://[^\"]+)\"", Pattern.CASE_INSENSITIVE),
            // atob/base64 encoded
            Pattern.compile("atob\\([\"']([A-Za-z0-9+/=]+)[\"']\\)", Pattern.CASE_INSENSITIVE),
            // eval/decode patterns
            Pattern.compile("decodeURIComponent\\([\"']([^\"']+)[\"']\\)", Pattern.CASE_INSENSITIVE)
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(html)
            while (matcher.find()) {
                var url = matcher.group(1) ?: continue

                // Handle base64
                if (pattern.pattern().contains("atob")) {
                    try {
                        url = String(android.util.Base64.decode(url, android.util.Base64.DEFAULT))
                    } catch (_: Exception) { continue }
                }

                // Handle URL encoding
                if (pattern.pattern().contains("decodeURIComponent")) {
                    url = NowHDTimeUtils.decodeUrl(url)
                }

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
                } else if (url.startsWith("http")) {
                    try {
                        loadExtractor(url, mainUrl, subtitleCallback, callback)
                        found = true
                    } catch (_: Exception) {}
                }
            }
        }

        // Look for subtitle tracks in JS
        val subPattern = Pattern.compile("(?:tracks|subtitles|captions)\\s*[:=]\\s*\\[([^]]+)]", Pattern.CASE_INSENSITIVE)
        val subMatcher = subPattern.matcher(html)
        if (subMatcher.find()) {
            val tracksJson = subMatcher.group(1)
            val trackPattern = Pattern.compile("(?:file|src)\\s*:\\s*[\"']([^\"']+\\.(?:srt|vtt|ass|sub)[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
            val trackMatcher = trackPattern.matcher(tracksJson ?: "")
            while (trackMatcher.find()) {
                val subUrl = trackMatcher.group(1) ?: continue
                val langPattern = Pattern.compile("label\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                val langMatcher = langPattern.matcher(tracksJson ?: "")
                val lang = if (langMatcher.find()) langMatcher.group(1) ?: "Unknown" else "Unknown"

                subtitleCallback.invoke(
                    SubtitleFile(lang, subUrl)
                )
            }
        }

        return if (found) true else null
    }

    // ===== Helper: Extract Player API Links =====
    private suspend fun extractPlayerAPILinks(
        document: Document,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean? {
        var found = false
        val html = document.html()

        // Look for AJAX/API endpoints for player
        val ajaxPatterns = listOf(
            Pattern.compile("(?:ajax_url|api_url|player_api)\\s*[:=]\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\$\\.(?:ajax|get|post)\\s*\\(\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("fetch\\s*\\(\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        )

        for (pattern in ajaxPatterns) {
            val matcher = pattern.matcher(html)
            while (matcher.find()) {
                val apiUrl = matcher.group(1) ?: continue
                val fullApiUrl = when {
                    apiUrl.startsWith("//") -> "https:$apiUrl"
                    apiUrl.startsWith("/") -> "$mainUrl$apiUrl"
                    apiUrl.startsWith("http") -> apiUrl
                    else -> continue
                }

                try {
                    val apiResponse = app.get(fullApiUrl, referer = pageUrl, timeout = 15)
                    val apiHtml = apiResponse.text

                    // Try to parse JSON response
                    val urlPattern = Pattern.compile("\"(https?://[^\"]+\\.(?:mp4|m3u8|mkv|mpd)[^\"]*?)\"", Pattern.CASE_INSENSITIVE)
                    val urlMatcher = urlPattern.matcher(apiHtml)
                    while (urlMatcher.find()) {
                        val videoUrl = urlMatcher.group(1) ?: continue
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name - API Source",
                                url = videoUrl,
                                referer = pageUrl,
                                quality = NowHDTimeUtils.getQualityFromString(videoUrl),
                                isM3u8 = videoUrl.contains(".m3u8")
                            )
                        )
                        found = true
                    }

                    // Also try extracting embeds from API response
                    val apiEmbeds = NowHDTimeUtils.extractEmbedUrls(apiHtml)
                    for (embed in apiEmbeds) {
                        try {
                            loadExtractor(embed, pageUrl, subtitleCallback, callback)
                            found = true
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }

        // Check for WordPress admin-ajax.php patterns
        val adminAjaxPattern = Pattern.compile("admin-ajax\\.php", Pattern.CASE_INSENSITIVE)
        if (adminAjaxPattern.matcher(html).find()) {
            // Look for nonce and action values
            val noncePattern = Pattern.compile("(?:nonce|_wpnonce)\\s*[:=]\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
            val actionPattern = Pattern.compile("action\\s*[:=]\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)

            val nonceMatcher = noncePattern.matcher(html)
            val actionMatcher = actionPattern.matcher(html)

            if (nonceMatcher.find() && actionMatcher.find()) {
                val nonce = nonceMatcher.group(1)
                val action = actionMatcher.group(1)

                // Look for post ID
                val postIdPattern = Pattern.compile("(?:post_id|postid|id)\\s*[:=]\\s*[\"']?(\\d+)[\"']?", Pattern.CASE_INSENSITIVE)
                val postIdMatcher = postIdPattern.matcher(html)
                val postId = if (postIdMatcher.find()) postIdMatcher.group(1) else null

                if (postId != null && nonce != null && action != null) {
                    try {
                        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                        val ajaxResponse = app.post(
                            ajaxUrl,
                            data = mapOf(
                                "action" to action,
                                "nonce" to nonce,
                                "post_id" to postId
                            ),
                            referer = pageUrl,
                            timeout = 15
                        )

                        val responseText = ajaxResponse.text
                        val videoUrls = NowHDTimeUtils.extractEmbedUrls(responseText)
                        for (videoUrl in videoUrls) {
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
                                    loadExtractor(videoUrl, pageUrl, subtitleCallback, callback)
                                    found = true
                                }
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        return if (found) true else null
    }

    // ===== Subtitle Extraction =====
    private fun extractSubtitles(document: Document, html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        // Track elements
        document.select("track[src], track[data-src]").forEach { track ->
            val src = track.attr("src").ifBlank { track.attr("data-src") }
            val label = track.attr("label").ifBlank { track.attr("srclang").ifBlank { "Unknown" } }
            if (src.isNotBlank()) {
                subtitleCallback.invoke(SubtitleFile(label, NowHDTimeUtils.fixPosterUrl(src) ?: src))
            }
        }

        // Subtitle links in page
        val subExtensions = listOf(".srt", ".vtt", ".ass", ".sub", ".ssa")
        document.select("a[href]").forEach { link ->
            val href = link.attr("href")
            if (subExtensions.any { href.lowercase().contains(it) }) {
                val label = link.text().ifBlank { "Subtitle" }
                subtitleCallback.invoke(SubtitleFile(label, href))
            }
        }

        // Subtitle URLs in JavaScript
        val subJsPattern = Pattern.compile("[\"'](https?://[^\"']+\\.(?:srt|vtt|ass|sub|ssa)(?:\\?[^\"']*)?)[\"']", Pattern.CASE_INSENSITIVE)
        val subJsMatcher = subJsPattern.matcher(html)
        while (subJsMatcher.find()) {
            val subUrl = subJsMatcher.group(1) ?: continue
            subtitleCallback.invoke(SubtitleFile("Auto-detected", subUrl))
        }
    }
}