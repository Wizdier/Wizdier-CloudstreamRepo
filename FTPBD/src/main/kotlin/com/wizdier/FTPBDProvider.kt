package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FTPBD : MainAPI() {
    override var mainUrl = "https://ftpbd.net"
    override var name = "FTPBD"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override var lang = "bn"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AnimeMovie,
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "movie" to "Latest Movies",
        "tv_shows" to "Latest TV Shows",
        "movies_cat/bollywood" to "Bollywood",
        "movies_cat/south-indian-movies" to "South Indian Movies",
        "movies_cat/english-movies" to "English Movies",
        "movies_cat/anime" to "Anime Movies",
        "movies_cat/action" to "Action",
        "movies_cat/horror" to "Horror",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val doc = app.get(url, headers = headers).document
        val expected = if (request.data == "tv_shows") "/tv_shows/" else "/movie/"
        val items = parseCards(doc, expected)
        val hasNext = doc.select("a.next, .page-numbers.next, a[href*='/page/${page + 1}/']").isNotEmpty() || items.isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = q.encodeUrl()
        val out = mutableListOf<SearchResponse>()
        runCatching {
            val doc = app.get("$mainUrl/?s=$encoded&post_type=movies", headers = headers).document
            out += parseCards(doc, "/movie/")
        }
        runCatching {
            val doc = app.get("$mainUrl/?s=$encoded&post_type=tv_shows", headers = headers).document
            out += parseCards(doc, "/tv_shows/")
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = url.toAbsoluteUrl()
        val doc = app.get(fixedUrl, headers = headers).document
        return when {
            fixedUrl.contains("/tv_shows/") -> loadSeries(fixedUrl, doc)
            fixedUrl.contains("/episodes/") -> loadEpisodeAsMovie(fixedUrl, doc)
            else -> loadMovie(fixedUrl, doc)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sourcePage = data.toAbsoluteUrl()
        val html = runCatching { app.get(sourcePage, headers = headers).text }.getOrNull() ?: return false
        val urls = linkedSetOf<String>()

        // HLS player sources used by the site.
        Regex("""(?i)<source[^>]+src=["']([^"']+?\.m3u8[^"']*)["']""")
            .findAll(html)
            .mapTo(urls) { it.groupValues[1].htmlDecode().toAbsoluteUrl() }
        Regex("""(?i)hls\.loadSource\(["']([^"']+?\.m3u8[^"']*)["']\)""")
            .findAll(html)
            .mapTo(urls) { it.groupValues[1].htmlDecode().toAbsoluteUrl() }

        // Direct download links are normally hidden in data-url attributes.
        Regex("""(?i)data-url=["']([^"']+)["']""")
            .findAll(html)
            .map { it.groupValues[1].htmlDecode() }
            .filter { it.looksLikeMedia() }
            .mapTo(urls) { it.toAbsoluteUrl() }

        // Extra fallback for any direct media URL in page scripts/HTML.
        Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv|\.webm|\.avi|\.mov|\.m4v)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapTo(urls) { it.value.htmlDecode().toAbsoluteUrl() }

        var found = false
        urls.distinct().forEach { link ->
            val clean = link.trim()
            if (clean.isBlank()) return@forEach
            val quality = getQualityFromName(clean)
            val display = buildString {
                append(name)
                qualityFromUrl(clean)?.let { append(" [$it]") }
                if (clean.contains("server", ignoreCase = true)) append(" - FTP")
            }
            if (clean.contains(".m3u8", ignoreCase = true)) {
                M3u8Helper.generateM3u8(
                    source = display,
                    streamUrl = clean,
                    referer = sourcePage,
                    headers = headers
                ).forEach(callback)
                found = true
            } else if (clean.looksLikeMedia()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = display,
                        url = clean,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        referer = sourcePage
                        this.quality = quality
                    }
                )
                found = true
            } else {
                runCatching {
                    loadExtractor(clean, sourcePage, subtitleCallback) {
                        callback(it)
                        found = true
                    }
                }
            }
        }
        return found
    }

    // ───────────────────────── Load builders ─────────────────────────

    private suspend fun loadMovie(url: String, doc: Document): LoadResponse {
        val title = doc.mainTitle()
        val poster = doc.poster()
        val background = doc.background()
        val plot = doc.plot()
        val year = doc.selectFirst(".video-years")?.text()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        val duration = parseDuration(doc.selectFirst(".video-time")?.text())
        val tags = doc.select(".jws-category a[href*='movies_cat']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val tvType = if (tags.any { it.contains("anime", ignoreCase = true) }) TvType.AnimeMovie else TvType.Movie
        val actors = doc.select(".jws-meta-director a[href*='/person/']").mapNotNull { a ->
            val actor = a.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ActorData(Actor(actor, null), roleString = "")
        }.distinctBy { it.actor.name }

        return newMovieLoadResponse(title, url, tvType, url) {
            posterUrl = poster
            backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.duration = duration
            this.tags = tags.takeIf { it.isNotEmpty() }
            runCatching { if (actors.isNotEmpty()) this.actors = actors }
            runCatching { doc.rating()?.let { score = Score.from10(it) } }
        }
    }

    private suspend fun loadEpisodeAsMovie(url: String, doc: Document): LoadResponse {
        val showTitle = doc.selectFirst(".jws-title h1 a, .jws-title .h3 a, a.tv-shows-link")?.text()?.trim()
        val epTitle = doc.selectFirst(".jws-title h6:last-child")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title().substringBefore(" – FTPBD")
        return newMovieLoadResponse(showTitle?.let { "$it - $epTitle" } ?: epTitle, url, TvType.TvSeries, url) {
            posterUrl = doc.poster()
            backgroundPosterUrl = doc.background()
            plot = doc.plot()
            runCatching { doc.rating()?.let { score = Score.from10(it) } }
        }
    }

    private suspend fun loadSeries(url: String, doc: Document): LoadResponse {
        val title = doc.mainTitle()
        val poster = doc.poster()
        val background = doc.background()
        val plot = doc.plot()
        val year = doc.selectFirst(".video-years")?.text()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        val tags = doc.select(".jws-category a[href*='tv_shows_cat']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val seasons = doc.select(".select-seasion .dropdown-item[data-index], a[href*='season=']")
            .mapNotNull { el ->
                el.attr("data-index").toIntOrNull()?.plus(1)
                    ?: Regex("season=(\\d+)").find(el.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(\\d+)").find(el.text())?.value?.toIntOrNull()
            }
            .filter { it > 0 }
            .distinct()
            .ifEmpty { listOf(1) }

        val episodes = mutableListOf<Episode>()
        val base = url.trimEnd('/')
        for (season in seasons) {
            val epDoc = runCatching {
                app.get("$base/episodes/?season=$season", headers = headers).document
            }.getOrNull() ?: if (season == 1) doc else continue
            episodes += parseEpisodes(epDoc, season)
        }
        if (episodes.isEmpty()) episodes += parseEpisodes(doc, 1)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            posterUrl = poster
            backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.tags = tags.takeIf { it.isNotEmpty() }
            runCatching { doc.rating()?.let { score = Score.from10(it) } }
        }
    }

    private fun parseEpisodes(doc: Document, season: Int): List<Episode> {
        val out = mutableListOf<Episode>()
        doc.select(".jws-pisodes_advanced-item, .episodes-content .post-inner").forEachIndexed { index, item ->
            val a = item.selectFirst("a[href*='/episodes/']") ?: return@forEachIndexed
            val href = a.absUrl("href").ifBlank { a.attr("href").toAbsoluteUrl() }
            if (href.isBlank()) return@forEachIndexed
            val epNum = item.selectFirst(".episodes-number")?.text()?.toIntOrNull()
                ?: Regex("(?i)S\\d+E(\\d+)").find(item.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: (index + 1)
            val title = item.selectFirst("h6 a, h5 a, h4 a")?.text()?.trim()
                ?: a.text().trim().ifBlank { "Episode $epNum" }
            val image = item.selectFirst("img")?.imgUrl()
            val desc = item.selectFirst(".jws-description")?.text()?.trim()
            val runtime = parseDuration(item.selectFirst(".time")?.text())
            out += newEpisode(href) {
                name = title
                this.season = season
                episode = epNum
                posterUrl = image
                description = desc
                runTime = runtime
            }
        }
        return out.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    // ───────────────────────── Parsers/helpers ─────────────────────────

    private fun pageUrl(path: String, page: Int): String {
        val clean = path.trim('/').ifBlank { "movie" }
        return if (page <= 1) "$mainUrl/$clean/" else "$mainUrl/$clean/page/$page/"
    }

    private fun parseCards(doc: Document, expectedPath: String): List<SearchResponse> {
        return doc.select(".site-main .jws-post-item, .site-main .post-inner")
            .mapNotNull { it.toSearchResult(expectedPath) }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(expectedPath: String): SearchResponse? {
        val a = selectFirst("a[href*='$expectedPath']") ?: return null
        val href = a.absUrl("href").ifBlank { a.attr("href").toAbsoluteUrl() }
        if (href.isBlank() || !href.contains(expectedPath)) return null
        val title = (selectFirst("h6 a, h5 a, h4 a, .post-title a")?.text()?.trim()
            ?: a.attr("title").trim().ifBlank { null }
            ?: a.text().trim()).trim()
        if (title.isBlank() || title.equals("Play Now", true)) return null
        val poster = selectFirst("img")?.imgUrl()
        val year = selectFirst(".video-years, .year")?.text()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        return if (expectedPath.contains("tv_shows")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
            }
        } else {
            val type = if (text().contains("Anime", ignoreCase = true) || href.contains("anime", ignoreCase = true)) TvType.AnimeMovie else TvType.Movie
            newMovieSearchResponse(title, href, type) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    private fun Document.mainTitle(): String =
        selectFirst("h1.jws-title, .jws-title h1, h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: title().substringBefore(" – FTPBD").trim().ifBlank { "Untitled" }

    private fun Document.poster(): String? =
        selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: selectFirst(".poster img, .post-media img, img.attachment-630x400")?.imgUrl()

    private fun Document.background(): String? {
        val style = selectFirst(".jws-banners[style*='background-image']")?.attr("style")
        return style?.substringAfter("url(")?.substringBefore(")")?.trim(' ', '\'', '"')?.takeIf { it.isNotBlank() }
    }

    private fun Document.plot(): String? =
        selectFirst(".js-content p")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst(".jws-description p, .jws-description")?.text()?.replace("Show More", "")?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

    private fun Document.rating(): Double? =
        selectFirst(".jws-raring-number")?.text()?.let { Regex("\\d+(?:\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }

    private fun Element.imgUrl(): String? =
        attr("data-src").ifBlank { attr("data-lazy-src") }.ifBlank { attr("src") }
            .takeIf { it.isNotBlank() }
            ?.toAbsoluteUrl()

    private fun parseDuration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val cleaned = text.lowercase()
        val h = Regex("(\\d+)\\s*h").find(cleaned)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)\\s*m").find(cleaned)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(\\d+)\\s*mins?").find(cleaned)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0
        val total = h * 60 + m
        return total.takeIf { it > 0 }
    }

    private fun String.looksLikeMedia(): Boolean {
        val lower = substringBefore("?").lowercase()
        return lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".mkv") ||
                lower.endsWith(".webm") || lower.endsWith(".avi") || lower.endsWith(".mov") ||
                lower.endsWith(".m4v")
    }

    private fun qualityFromUrl(url: String): String? =
        Regex("(?i)(2160p|1440p|1080p|720p|480p|360p|4k)").find(url)?.value

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http") -> this
        startsWith("/") -> mainUrl + this
        else -> "$mainUrl/$this"
    }

    private fun String.encodeUrl(): String = URLEncoder.encode(this, "UTF-8")

    private fun String.htmlDecode(): String = Jsoup.parse(this).text()
}
