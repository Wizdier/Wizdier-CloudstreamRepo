package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

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

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Latest Movies",
        "$mainUrl/tv-shows" to "Latest TV Shows",
        "$mainUrl/trending" to "Trending Now",
        "$mainUrl/anime" to "Anime",
        "$mainUrl/movies?page=" to "More Movies",
        "$mainUrl/tv-shows?page=" to "More TV Shows"
    )

    // ── Home ─────────────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.endsWith("?page=") -> "${request.data}$page"
            page > 1 -> "${request.data}?page=$page"
            else -> request.data
        }
        val doc = app.get(url, timeout = 30).document
        val items = if (request.data.contains("/anime")) parseAnimePage(doc)
        else parseMovieTvPage(doc)
        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext(doc, page)
        )
    }

    // ── Search ───────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val enc = query.replace(" ", "+")
        try {
            val doc = app.get("$mainUrl/search?query=$enc", timeout = 30).document
            results.addAll(parseMovieTvPage(doc))
            results.addAll(parseAnimePage(doc))
        } catch (_: Exception) {}
        if (results.isEmpty()) {
            try {
                val aDoc = app.get("$mainUrl/anime?q=$enc", timeout = 30).document
                results.addAll(parseAnimePage(aDoc))
            } catch (_: Exception) {}
        }
        return results.distinctBy { it.url }
    }

    // ── Load ─────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        return when {
            url.contains("/anime/") && !url.contains("/watch/") -> loadAnime(url)
            url.contains("/tv-show/") -> loadTvShow(url)
            else -> loadMovie(url)
        }
    }

    private suspend fun loadMovie(url: String): LoadResponse {
        val doc = app.get(url, timeout = 30).document
        val title = extractTitle(doc)
        val poster = bestImage(doc)
        val plot = extractPlot(doc)
        val year = extractYear(doc)
        val rating = extractRating(doc)
        val dur = extractDuration(doc)
        val tags = NowHDTimeUtils.extractTags(doc).ifEmpty { extractGenres(doc) }
        val actors = NowHDTimeUtils.extractActors(doc)
        val trailer = extractTrailer(doc)
        val recs = extractRecs(doc)
        val ids = NowHDTimeUtils.extractTrackingIds(doc)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.rating = rating
            this.duration = dur
            this.recommendations = recs
            addActors(actors.map { it.actor })
            addTrailer(trailer)
            addImdbId(ids.imdbId)
            addMalId(ids.malId)
            addAniListId(ids.anilistId)
            addKitsuId(ids.kitsuId)
        }
    }

    private suspend fun loadTvShow(url: String): LoadResponse {
        val doc = app.get(url, timeout = 30).document
        val title = extractTitle(doc)
        val poster = bestImage(doc)
        val plot = extractPlot(doc)
        val year = extractYear(doc)
        val rating = extractRating(doc)
        val tags = NowHDTimeUtils.extractTags(doc).ifEmpty { extractGenres(doc) }
        val actors = NowHDTimeUtils.extractActors(doc)
        val trailer = extractTrailer(doc)
        val recs = extractRecs(doc)
        val ids = NowHDTimeUtils.extractTrackingIds(doc)
        val eps = extractTvEpisodes(doc)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.rating = rating
            this.recommendations = recs
            addActors(actors.map { it.actor })
            addTrailer(trailer)
            addImdbId(ids.imdbId)
        }
    }

    private suspend fun loadAnime(url: String): LoadResponse {
        val doc = app.get(url, timeout = 30).document
        val title = extractTitle(doc)
        val poster = bestImage(doc)
        val plot = extractPlot(doc)
        val year = extractYear(doc)
        val rating = extractRating(doc)
        val tags = NowHDTimeUtils.extractTags(doc).ifEmpty { extractGenres(doc) }
        val actors = NowHDTimeUtils.extractActors(doc)
        val trailer = extractTrailer(doc)
        val recs = extractAnimeRecs(doc)
        val ids = NowHDTimeUtils.extractTrackingIds(doc)
        val eps = extractAnimeEpisodes(doc, ids.anilistId)

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.rating = rating
            this.recommendations = recs
            addEpisodes(DubStatus.Subbed, eps)
            addActors(actors.map { it.actor })
            addTrailer(trailer)
            addMalId(ids.malId)
            addAniListId(ids.anilistId)
            addKitsuId(ids.kitsuId)
        }
    }

    // ── Links ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, timeout = 30).document
        val html = doc.html()
        var found = false

        // iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = NowHDTimeUtils.fixUrl(iframe.attr("src")) ?: return@forEach
            if (src.startsWith("http")) {
                try { loadExtractor(src, data, subtitleCallback, callback); found = true }
                catch (_: Exception) {}
            }
        }

        // embedded video URLs
        NowHDTimeUtils.extractEmbeds(html).forEach { embedUrl ->
            try {
                if (NowHDTimeUtils.isDirectVideo(embedUrl)) {
                    callback(newExtractorLink(
                        source = name, name = name, url = embedUrl,
                        type = if (embedUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                        else ExtractorLinkType.VIDEO
                    ) { this.referer = data; this.quality = NowHDTimeUtils.qualityFromString(embedUrl) })
                } else {
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                }
                found = true
            } catch (_: Exception) {}
        }

        // server buttons
        doc.select("button[data-src], a[data-src], [data-embed]").forEach { el ->
            val src = NowHDTimeUtils.fixUrl(
                el.attr("data-src").ifBlank { el.attr("data-embed").ifBlank { el.attr("href") } }
            ) ?: return@forEach
            if (src.startsWith("http")) {
                try { loadExtractor(src, data, subtitleCallback, callback); found = true }
                catch (_: Exception) {}
            }
        }

        // subtitles
        NowHDTimeUtils.extractSubtitles(html).forEach { (label, subUrl) ->
            subtitleCallback(SubtitleFile(label, subUrl))
        }

        return found
    }

    // ── Parsers ──────────────────────────────────────────────────────────
    private fun parseMovieTvPage(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val sels = listOf(
            "a[href*='/movie/']", "a[href*='/tv-show/']",
            ".movie-card a", ".content-card a", ".poster-card a"
        )
        for (sel in sels) doc.select(sel).forEach { a ->
            try {
                val href = a.attr("href")
                val fullUrl = NowHDTimeUtils.fixUrl(href) ?: return@forEach
                if (!fullUrl.contains("/movie/") && !fullUrl.contains("/tv-show/")) return@forEach
                val img = a.selectFirst("img")
                val title = a.selectFirst("h2,h3,h4,.title")?.text()?.trim()
                    ?: img?.attr("alt")?.trim() ?: a.attr("title").trim()
                if (title.isBlank()) return@forEach
                val poster = NowHDTimeUtils.fixUrl(img?.attr("src")?.ifBlank { img.attr("data-src") })
                val year = NowHDTimeUtils.extractYear(a.selectFirst("[class*=year],.year")?.text())
                val clean = NowHDTimeUtils.cleanTitle(title)
                if (fullUrl.contains("/tv-show/"))
                    results.add(newTvSeriesSearchResponse(clean, fullUrl, TvType.TvSeries) { this.posterUrl = poster; this.year = year })
                else
                    results.add(newMovieSearchResponse(clean, fullUrl, TvType.Movie) { this.posterUrl = poster; this.year = year })
            } catch (_: Exception) {}
        }
        return results.distinctBy { it.url }
    }

    private fun parseAnimePage(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        doc.select("a[href*='/anime/']").forEach { a ->
            try {
                val href = a.attr("href")
                if (href.contains("/watch/")) return@forEach
                val fullUrl = NowHDTimeUtils.fixUrl(href) ?: return@forEach
                val img = a.selectFirst("img")
                val title = a.selectFirst("h2,h3,h4,.title")?.text()?.trim()
                    ?: img?.attr("alt")?.trim() ?: a.attr("title").trim()
                if (title.isBlank() || title.length < 2) return@forEach
                val poster = NowHDTimeUtils.fixUrl(img?.attr("src")?.ifBlank { img.attr("data-src") })
                val year = NowHDTimeUtils.extractYear(title)
                results.add(newAnimeSearchResponse(NowHDTimeUtils.cleanTitle(title), fullUrl, TvType.Anime) { this.posterUrl = poster; this.year = year })
            } catch (_: Exception) {}
        }
        return results.distinctBy { it.url }
    }

    // ── Episodes ─────────────────────────────────────────────────────────
    private fun extractTvEpisodes(doc: Document): List<Episode> {
        val eps = mutableListOf<Episode>()
        doc.select(".season, [class*=season], .episodes-list").forEach { container ->
            val sNum = NowHDTimeUtils.extractSeasonNum(container.selectFirst("h3,.season-title")?.text() ?: "") ?: 1
            container.select("a[href*='/episode/'], a[href*='/watch/'], .episode-item a").forEach { ep ->
                val href = ep.attr("href").ifBlank { ep.selectFirst("a")?.attr("href") } ?: return@forEach
                val epUrl = NowHDTimeUtils.fixUrl(href) ?: return@forEach
                val txt = ep.selectFirst(".ep-title,.title,span")?.text()?.trim() ?: ep.text().trim()
                val epNum = NowHDTimeUtils.extractEpNum(txt) ?: NowHDTimeUtils.extractEpNum(href) ?: eps.size + 1
                eps.add(newEpisode(epUrl) {
                    this.name = txt.ifBlank { "Episode $epNum" }
                    this.season = sNum
                    this.episode = epNum
                })
            }
        }
        if (eps.isEmpty()) doc.select("a[href*='episode'], a[href*='ep-']").forEachIndexed { i, ep ->
            val epUrl = NowHDTimeUtils.fixUrl(ep.attr("href")) ?: return@forEachIndexed
            val epNum = NowHDTimeUtils.extractEpNum(ep.attr("href")) ?: (i + 1)
            eps.add(newEpisode(epUrl) { this.name = "Episode $epNum"; this.episode = epNum })
        }
        return eps.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    private fun extractAnimeEpisodes(doc: Document, anilistId: Int?): List<Episode> {
        val eps = mutableListOf<Episode>()
        if (anilistId != null) {
            doc.select("a[href*='/anime/$anilistId/'], .episode-link, .ep-item").forEach { ep ->
                val href = ep.attr("href")
                val epUrl = if (href.startsWith("http")) href
                else if (href.startsWith("/")) "$mainUrl$href"
                else return@forEach
                val epNum = NowHDTimeUtils.extractEpNum(href) ?: NowHDTimeUtils.extractEpNum(ep.text()) ?: eps.size + 1
                eps.add(newEpisode(epUrl) { this.name = ep.text().trim().ifBlank { "Episode $epNum" }; this.episode = epNum })
            }
        }
        if (eps.isEmpty()) doc.select(".episode a, [class*=episode] a, a[href*='/watch/']").forEach { ep ->
            val epUrl = NowHDTimeUtils.fixUrl(ep.attr("href")) ?: return@forEach
            val epNum = NowHDTimeUtils.extractEpNum(ep.attr("href")) ?: eps.size + 1
            eps.add(newEpisode(epUrl) { this.name = "Episode $epNum"; this.episode = epNum })
        }
        return eps.distinctBy { it.data }.sortedBy { it.episode ?: 0 }
    }

    // ── Metadata helpers ─────────────────────────────────────────────────
    private fun extractTitle(doc: Document): String =
        doc.selectFirst("h1, h2.title")?.text()?.let { NowHDTimeUtils.cleanTitle(it) } ?: doc.title().substringBefore("|").trim()

    private fun bestImage(doc: Document): String? {
        listOf(
            doc.selectFirst("meta[property='og:image']")?.attr("content"),
            doc.selectFirst(".poster img, .cover img, [class*=poster] img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
        ).forEach { NowHDTimeUtils.fixUrl(it)?.let { u -> return u } }
        return null
    }

    private fun extractPlot(doc: Document): String? {
        for (sel in listOf(".synopsis p,.overview p,.description p,.plot p", ".synopsis,.overview,.description,.plot",
            "meta[name='description']", "meta[property='og:description']")) {
            val el = doc.selectFirst(sel) ?: continue
            val t = if (sel.startsWith("meta")) el.attr("content") else el.text()
            if (t.length > 30) return t.trim()
        }
        return null
    }

    private fun extractYear(doc: Document): Int? {
        doc.selectFirst("[class*=year], time[datetime], .date")?.let { el ->
            NowHDTimeUtils.extractYear(el.text().ifBlank { el.attr("datetime") })?.let { return it }
        }
        return NowHDTimeUtils.extractYear(doc.selectFirst("meta[name='keywords']")?.attr("content"))
    }

    private fun extractRating(doc: Document): Int? {
        doc.selectFirst(".score,.rating,[class*=score],[class*=rating]")?.text()?.let {
            NowHDTimeUtils.extractRating(it)?.let { r -> return r }
        }; return null
    }

    private fun extractDuration(doc: Document): Int? {
        doc.selectFirst("[class*=duration],[class*=runtime],time")?.text()?.let {
            NowHDTimeUtils.parseDuration(it)?.let { d -> return d }
        }
        doc.select("span,li,p").forEach { el ->
            val t = el.text()
            if (t.contains("min", true) || t.contains(" h ", true))
                NowHDTimeUtils.parseDuration(t)?.let { return it }
        }; return null
    }

    private fun extractGenres(doc: Document): List<String> {
        val g = mutableListOf<String>()
        doc.select("a[href*='/genre/'], .genre a, .genres a").forEach { g.add(it.text().trim()) }
        doc.selectFirst("[class*=genre]")?.text()?.split("·",",","/","|")?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach { g.add(it) }
        return g.distinct().take(8)
    }

    private fun extractTrailer(doc: Document): String? {
        doc.selectFirst("iframe[src*='youtube'],iframe[src*='youtu.be']")?.attr("src")?.let {
            return if (it.startsWith("//")) "https:$it" else it
        }
        Regex("(?:youtube\\.com/embed/|youtu\\.be/)([A-Za-z0-9_-]{11})").find(doc.html())?.groupValues?.get(1)?.let {
            return "https://www.youtube.com/watch?v=$it"
        }; return null
    }

    private fun extractRecs(doc: Document): List<SearchResponse> {
        val recs = mutableListOf<SearchResponse>()
        doc.select("[class*=similar] a,[class*=related] a,[class*=recommend] a,.more-like-this a").forEach { a ->
            val href = a.attr("href")
            if (!href.contains("/movie/") && !href.contains("/tv-show/")) return@forEach
            val url2 = NowHDTimeUtils.fixUrl(href) ?: return@forEach
            val img = a.selectFirst("img")
            val title2 = a.selectFirst("h3,h4,.title")?.text()?.trim() ?: img?.attr("alt")?.trim() ?: return@forEach
            val poster = NowHDTimeUtils.fixUrl(img?.attr("src"))
            if (href.contains("/tv-show/")) recs.add(newTvSeriesSearchResponse(title2, url2, TvType.TvSeries) { this.posterUrl = poster })
            else recs.add(newMovieSearchResponse(title2, url2, TvType.Movie) { this.posterUrl = poster })
        }
        return recs.distinctBy { it.url }.take(12)
    }

    private fun extractAnimeRecs(doc: Document): List<SearchResponse> {
        val recs = mutableListOf<SearchResponse>()
        doc.select("a[href*='/anime/']").filter { !it.attr("href").contains("/watch/") }.take(12).forEach { a ->
            val url2 = NowHDTimeUtils.fixUrl(a.attr("href")) ?: return@forEach
            val img = a.selectFirst("img")
            val title2 = a.selectFirst("h3,h4,.title")?.text()?.trim() ?: img?.attr("alt")?.trim() ?: return@forEach
            val poster = NowHDTimeUtils.fixUrl(img?.attr("src"))
            recs.add(newAnimeSearchResponse(title2, url2, TvType.Anime) { this.posterUrl = poster })
        }
        return recs.distinctBy { it.url }
    }

    private fun hasNext(doc: Document, page: Int): Boolean {
        if (doc.selectFirst("a.next,.next a,.pagination .next,[rel='next']") != null) return true
        return page < 20
    }
}