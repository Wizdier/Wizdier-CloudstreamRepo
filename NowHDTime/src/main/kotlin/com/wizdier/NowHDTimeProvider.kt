package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class NowHDTimeProvider : MainAPI() {

    override var mainUrl = "https://nowhdtime.to"
    override var name = "NowHDTime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val instantLinkLoading = true

    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Latest Movies",
        "$mainUrl/tv-shows" to "Latest TV Shows",
        "$mainUrl/trending" to "Trending Now",
        "$mainUrl/anime" to "Anime",
    )

    // ── Home ─────────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = when (request.data) {
            "$mainUrl/movies" -> app.get("$mainUrl/movies", timeout = 30).document
            "$mainUrl/tv-shows" -> app.get("$mainUrl/tv-shows", timeout = 30).document
            "$mainUrl/trending" -> app.get("$mainUrl/trending", timeout = 30).document
            "$mainUrl/anime" -> app.get("$mainUrl/anime", timeout = 30).document
            else -> app.get(mainUrl, timeout = 30).document
        }
        val items = parseCardItems(doc)
        return newHomePageResponse(
            list = HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = false),
            hasNext = page < 10
        )
    }

    // ── Search ───────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            results.addAll(parseCardItems(
                app.get("$mainUrl/search?query=${query.replace(" ", "+")}", timeout = 30).document
            ))
        } catch (_: Exception) {}
        return results.distinctBy { it.url }
    }

    // ── Load ─────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 30).document
        val title = extractTitle(doc)
        val poster = extractPoster(doc)
        val plot = extractPlot(doc)
        val year = extractYear(doc)
        val rating = extractRating(doc)
        val tags = extractGenres(doc)
        val actors = extractCast(doc)
        val trailer = extractTrailer(doc)
        val dur = extractDuration(doc)
        val recs = if (url.contains("/anime/")) extractAnimeRecs(doc) else extractRecs(doc)
        return when {
            url.contains("/anime/") -> {
                val eps = extractEpisodes(doc, url)
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster; this.year = year; this.plot = plot
                    this.tags = tags; @Suppress("DEPRECATION_ERROR") this.rating = rating
                    this.duration = dur; this.recommendations = recs
                    addEpisodes(DubStatus.Subbed, eps); addActors(actors); addTrailer(trailer)
                }
            }
            url.contains("/tv-show/") -> {
                val eps = extractEpisodes(doc, url)
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                    this.posterUrl = poster; this.year = year; this.plot = plot
                    this.tags = tags; @Suppress("DEPRECATION_ERROR") this.rating = rating
                    this.recommendations = recs; addActors(actors); addTrailer(trailer)
                }
            }
            else -> newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.year = year; this.plot = plot
                this.tags = tags; @Suppress("DEPRECATION_ERROR") this.rating = rating
                this.duration = dur; this.recommendations = recs
                addActors(actors); addTrailer(trailer)
            }
        }
    }

    // ── Load Links ───────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|", limit = 4)
        return when {
            parts.size >= 4 && parts[0] == "EPISODE" -> {
                val tmdbId = parts[1]; val season = parts[2].toIntOrNull(); val ep = parts[3].toIntOrNull()
                fetchEmbedUrls(tmdbId, season, ep, data, subtitleCallback, callback)
            }
            else -> {
                // Movie: scrape players from the page HTML, pass to loadExtractor
                val doc = app.get(data, timeout = 30).document
                val players = parsePlayers(doc.html())
                var found = false
                players.forEach { embedUrl ->
                    try { loadExtractor(embedUrl, data, subtitleCallback, callback); found = true } catch (_: Exception) {}
                }
                found
            }
        }
    }

    private fun parsePlayers(source: String): List<String> {
        val urls = mutableListOf<String>()
        Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").findAll(source).forEach { m ->
            val u = m.groupValues[1].replace("\\/", "/")
            if (u.startsWith("http") && u !in urls) urls.add(u)
        }
        return urls
    }

    private suspend fun fetchEmbedUrls(
        tmdbId: String, season: Int?, episode: Int?,
        referer: String, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val endpoints = if (season != null && episode != null) {
            listOf(
                "https://vidsrc.to/embed/tv/$tmdbId/$season/$episode",
                "https://vidsrc.net/embed/tv/$tmdbId/$season/$episode",
                "https://www.2embed.cc/embedtv/$tmdbId&s=$season&e=$episode",
                "https://multiembed.mov/?video_id=$tmdbId&tmdb=1&s=$season&e=$episode",
            )
        } else {
            listOf(
                "https://vidsrc.to/embed/movie/$tmdbId",
                "https://vidsrc.net/embed/movie/$tmdbId",
                "https://www.2embed.cc/embed/$tmdbId",
                "https://multiembed.mov/?video_id=$tmdbId&tmdb=1",
            )
        }
        for (u in endpoints) {
            try { loadExtractor(u, referer, subtitleCallback, callback); found = true } catch (_: Exception) {}
        }
        return found
    }

    // ── Listing Parser ───────────────────────────────────────────────
    private fun parseCardItems(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val linkPattern = Regex("/(movie|tv-show|anime)/watch-[^\"'\\s]+")
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            val match = linkPattern.find(href) ?: return@forEach
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            val typeStr = match.groupValues[1]
            val img = a.selectFirst("img")
            val poster = fixUrl(img?.attr("src")?.ifBlank { img?.attr("data-src") })
            val title = img?.attr("alt")?.trim()?.ifBlank { null }
                ?: a.selectFirst("h2,h3,h4,.title,[class*=title]")?.text()?.trim()
                ?: a.attr("title").trim().ifBlank { null }
                ?: a.text().trim().take(80)
            if (title.isBlank() || title.length < 2) return@forEach
            val year = extractYearFromText(title)
            val clean = cleanTitle(title)
            val tvType = when (typeStr) {
                "tv-show" -> TvType.TvSeries; "anime" -> TvType.Anime; else -> TvType.Movie
            }
            results.add(when (tvType) {
                TvType.TvSeries -> newTvSeriesSearchResponse(clean, fullUrl, tvType) { this.posterUrl = poster; this.year = year }
                TvType.Anime -> newAnimeSearchResponse(clean, fullUrl, tvType) { this.posterUrl = poster; this.year = year }
                else -> newMovieSearchResponse(clean, fullUrl, tvType) { this.posterUrl = poster; this.year = year }
            })
        }
        return results.distinctBy { it.url }
    }

    // ── Metadata Extractors ──────────────────────────────────────────
    private fun extractTitle(doc: Document): String {
        doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { return cleanTitle(it) }
        doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }?.let { return cleanTitle(it) }
        return doc.title().substringBefore("|").substringBefore("-").trim()
    }
    private fun extractPoster(doc: Document): String? {
        doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { return fixUrl(it) }
        doc.selectFirst("img[src*='tmdb.org/t/p/']")?.let { return fixUrl(it.attr("src").ifBlank { it.attr("data-src") }) }
        return null
    }
    private fun extractPlot(doc: Document): String? {
        doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()?.takeIf { it.length > 20 }?.let { return it }
        return null
    }
    private fun extractYear(doc: Document): Int? {
        doc.selectFirst("meta[property='og:title']")?.attr("content")?.let { extractYearFromText(it)?.let { return it } }
        return extractYearFromText(extractTitle(doc))
    }
    private fun extractRating(doc: Document): Int? {
        Regex("(\\d+\\.?\\d*)\\s*/\\s*10").find(doc.text())?.groupValues?.get(1)?.toDoubleOrNull()?.let { return (it * 1000).toInt() }
        return null
    }
    private fun extractGenres(doc: Document): List<String> {
        val g = mutableListOf<String>()
        doc.select("a[href*='/genre/'], .genre a, .genres a").forEach { val t = it.text().trim(); if (t.isNotBlank()) g.add(t) }
        return g.distinct().take(8)
    }
    private fun extractCast(doc: Document): List<Actor> {
        val a = mutableListOf<Actor>()
        doc.select("[class*=cast] a, .featured-cast a, .cast a").forEach { el ->
            val name = el.selectFirst("h3,h4,.name,p")?.text()?.trim() ?: el.text().trim()
            val img = el.selectFirst("img")?.let { fixUrl(it.attr("src").ifBlank { it.attr("data-src") }) }
            if (name.isNotBlank() && name.length < 80) a.add(Actor(name, img))
        }
        return a.distinctBy { it.name }.take(20)
    }
    private fun extractTrailer(doc: Document): String? {
        doc.selectFirst("iframe[src*='youtube'],iframe[src*='youtu.be']")?.attr("src")?.let { return if (it.startsWith("//")) "https:$it" else it }
        Regex("(?:youtube\\.com/embed/|youtu\\.be/)([A-Za-z0-9_-]{11})").find(doc.html())?.groupValues?.get(1)?.let { return "https://www.youtube.com/watch?v=$it" }
        return null
    }
    private fun extractDuration(doc: Document): Int? {
        Regex("(\\d+)h\\s*(\\d+)m").find(doc.text())?.let { m -> return (m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0) }
        Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE).find(doc.text())?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }
    private fun extractRecs(doc: Document): List<SearchResponse> {
        val recs = mutableListOf<SearchResponse>()
        doc.select("a[href*='watch-']").forEach { a ->
            if (recs.size >= 12) return@forEach
            val href = a.attr("href")
            if (!href.contains("/movie/") && !href.contains("/tv-show/")) return@forEach
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            val img = a.selectFirst("img")
            val title = a.selectFirst("h3,h4,.title")?.text()?.trim() ?: img?.attr("alt")?.trim() ?: return@forEach
            val poster = fixUrl(img?.attr("src")?.ifBlank { img?.attr("data-src") })
            if (href.contains("/tv-show/"))
                recs.add(newTvSeriesSearchResponse(cleanTitle(title), fullUrl, TvType.TvSeries) { this.posterUrl = poster })
            else
                recs.add(newMovieSearchResponse(cleanTitle(title), fullUrl, TvType.Movie) { this.posterUrl = poster })
        }
        return recs.distinctBy { it.url }.take(12)
    }
    private fun extractAnimeRecs(doc: Document): List<SearchResponse> =
        extractRecs(doc).filter { it.url.contains("/anime/") }.take(12)

    // ── TMDB ID ──────────────────────────────────────────────────────
    private fun extractTmdbId(html: String): String? {
        Regex("/(?:movie|tv)/(\\d+)").find(html)?.groupValues?.get(1)?.let { return it }
        Regex("\"tmdbId\"\\s*:\\s*\"?(\\d+)\"?").find(html)?.groupValues?.get(1)?.let { return it }
        Regex("data-tmdb-id=\"(\\d+)\"").find(html)?.groupValues?.get(1)?.let { return it }
        return null
    }

    // ── Episodes ─────────────────────────────────────────────────────
    private fun extractEpisodes(doc: Document, baseUrl: String): List<Episode> {
        val eps = mutableListOf<Episode>()
        val tmdbId = extractTmdbId(doc.html())
        doc.select("[data-episode-id]").forEach { el ->
            val season = el.attr("data-season").toIntOrNull() ?: 1
            val epNum = el.attr("data-episode").toIntOrNull() ?: (eps.size + 1)
            val name = el.selectFirst("h3,h4,.ep-title,[class*=title],.name")?.text()?.trim() ?: "Episode $epNum"
            val poster = el.selectFirst("img")?.let { fixUrl(it.attr("src").ifBlank { it.attr("data-src") }) }
            val plot = el.selectFirst("p,.overview,.description,[class*=desc]")?.text()?.trim()
            val rt = el.selectFirst("[class*=runtime],[class*=duration]")?.text()?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val data = "EPISODE|${tmdbId ?: "0"}|$season|$epNum"
            eps.add(newEpisode(data) {
                this.name = name; this.season = season; this.episode = epNum
                this.posterUrl = poster; this.description = plot; this.runTime = rt
            })
        }
        return eps.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    // ── Helpers ──────────────────────────────────────────────────────
    private fun cleanTitle(title: String): String =
        title.replace(Regex("\\s*\\(\\d{4}\\)\\s*"), " ")
            .replace(Regex("(?i)\\s*(1080p|720p|480p|2160p|4k|hd|web-dl|bluray)\\s*"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun extractYearFromText(text: String): Int? =
        Regex("\\b(19|20)\\d{2}\\b").find(text)?.value?.toIntOrNull()

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            !url.startsWith("http") -> "https://$url"
            else -> url
        }
    }
}
