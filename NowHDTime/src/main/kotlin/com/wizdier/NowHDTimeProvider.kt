package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class NowHDTimeProvider : MainAPI() {

    override var mainUrl = "https://nowhdtime.to"
    override var name = "NowHDTime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Latest Movies",
        "$mainUrl/tv-shows" to "Latest TV Shows",
        "$mainUrl/trending" to "Trending Now",
        "$mainUrl/anime" to "Anime",
    )

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

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            results.addAll(parseCardItems(
                app.get("$mainUrl/search?query=${query.replace(" ", "+")}", timeout = 30).document
            ))
        } catch (_: Exception) {}
        if (results.isEmpty()) {
            try {
                results.addAll(parseCardItems(
                    app.get("$mainUrl/trending", timeout = 30).document
                ).filter { it.name.contains(query, ignoreCase = true) })
            } catch (_: Exception) {}
        }
        return results.distinctBy { it.url }
    }

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
                val tvId = extractTvShowId(doc)
                val eps = extractEpisodes(doc, tvId, url)
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster; this.year = year; this.plot = plot
                    this.tags = tags; @Suppress("DEPRECATION_ERROR") this.rating = rating
                    this.duration = dur; this.recommendations = recs
                    addEpisodes(DubStatus.Subbed, eps); addActors(actors); addTrailer(trailer)
                }
            }
            url.contains("/tv-show/") -> {
                val tvId = extractTvShowId(doc)
                val eps = extractEpisodes(doc, tvId, url)
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|", limit = 4)
        return when {
            parts.size >= 4 && parts[0] == "EPISODE" ->
                loadEpisodeLinks(parts[1], parts[2], parts[3], data, subtitleCallback, callback)
            else -> loadMovieLinks(data, subtitleCallback, callback)
        }
    }

    private suspend fun loadMovieLinks(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(url, timeout = 30).document
        val players = parsePlayers(doc.html())
        if (players.isEmpty()) return false
        var found = false
        players.forEach { embedUrl ->
            try { loadExtractor(embedUrl, url, subtitleCallback, callback); found = true } catch (_: Exception) {}
        }
        return found
    }

    private suspend fun loadEpisodeLinks(
        tvShowId: String, season: String, episode: String, referer: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val csrfDoc = app.get(mainUrl, timeout = 15).document
        val csrf = csrfDoc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return false
        val resp = app.post("$mainUrl/episode-details",
            data = mapOf("tv_show_id" to tvShowId, "season" to season, "episode" to episode),
            headers = mapOf("Content-Type" to "application/json", "X-CSRF-TOKEN" to csrf),
            referer = referer.ifBlank { mainUrl }, timeout = 20
        ).text
        val players = parsePlayers(resp)
        if (players.isEmpty()) return false
        var found = false
        players.forEach { embedUrl ->
            try { loadExtractor(embedUrl, referer, subtitleCallback, callback); found = true } catch (_: Exception) {}
        }
        return found
    }

    private fun parsePlayers(source: String): List<String> {
        val urls = mutableListOf<String>()
        Regex(""""url"\s*:\s*"([^"]+)"""").findAll(source).forEach { m ->
            val u = m.groupValues[1].replace("\\/", "/")
            if (u.startsWith("http") && u !in urls) urls.add(u)
        }
        return urls
    }

    private fun parseCardItems(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val linkPattern = Regex("""/(movie|tv-show|anime)/watch-[^"'\s]+""")
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

    private fun extractTitle(doc: Document): String {
        doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { return cleanTitle(it) }
        doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }?.let { return cleanTitle(it) }
        return doc.title().substringBefore("|").substringBefore("-").trim()
    }

    private fun extractPoster(doc: Document): String? {
        doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { return fixUrl(it) }
        doc.selectFirst("img[src*='tmdb.org/t/p/']")?.let { return fixUrl(it.attr("src").ifBlank { it.attr("data-src") }) }
        doc.selectFirst(".poster img, [class*=poster] img, .cover img")?.let { return fixUrl(it.attr("src").ifBlank { it.attr("data-src") }) }
        return null
    }

    private fun extractPlot(doc: Document): String? {
        doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()?.takeIf { it.length > 20 }?.let { return it }
        doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.length > 20 }?.let { return it }
        for (sel in listOf(".overview p, .synopsis p, .description p, [class*=overview] p")) {
            doc.selectFirst(sel)?.text()?.trim()?.takeIf { it.length > 30 }?.let { return it }
        }
        return null
    }

    private fun extractYear(doc: Document): Int? {
        doc.selectFirst("meta[property='og:title']")?.attr("content")?.let { extractYearFromText(it)?.let { y -> return y } }
        doc.selectFirst("[class*=year], time[datetime], .date, .release-date")?.let { el ->
            extractYearFromText(el.text().ifBlank { el.attr("datetime") })?.let { return it }
        }
        return extractYearFromText(extractTitle(doc))
    }

    private fun extractRating(doc: Document): Int? {
        val p = Regex("""(\d+\.?\d*)\s*/\s*10""")
        doc.select(".rating,.score,[class*=rating],[class*=score],.vote-average").forEach { el ->
            p.find(el.text())?.groupValues?.get(1)?.toDoubleOrNull()?.let { return (it * 1000).toInt() }
        }
        return null
    }

    private fun extractGenres(doc: Document): List<String> {
        val g = mutableListOf<String>()
        doc.select("a[href*='/genre/'], .genre a, .genres a, a[href*='genre']").forEach { val t = it.text().trim(); if (t.isNotBlank() && t.length < 30) g.add(t) }
        return g.distinct().take(8)
    }

    private fun extractCast(doc: Document): List<Actor> {
        val a = mutableListOf<Actor>()
        doc.select(".cast-list li, .cast a, .actors a, .starring a, [class*=cast] a, .featured-cast a").forEach { el ->
            val name = el.selectFirst("h3,h4,.name,p,.actor-name")?.text()?.trim() ?: el.text().trim()
            val img = el.selectFirst("img")?.let { fixUrl(it.attr("src").ifBlank { it.attr("data-src") }) }
            if (name.isNotBlank() && name.length < 80) a.add(Actor(name, img))
        }
        return a.distinctBy { it.name }.take(20)
    }

    private fun extractTrailer(doc: Document): String? {
        doc.selectFirst("iframe[src*='youtube'],iframe[src*='youtu.be']")?.attr("src")?.let { return if (it.startsWith("//")) "https:$it" else it }
        Regex("""(?:youtube\.com/embed/|youtu\.be/)([A-Za-z0-9_-]{11})""").find(doc.html())?.groupValues?.get(1)?.let { return "https://www.youtube.com/watch?v=$it" }
        return null
    }

    private fun extractDuration(doc: Document): Int? {
        for (sel in listOf("[class*=duration],[class*=runtime],.runtime,time")) {
            doc.selectFirst(sel)?.text()?.let { txt ->
                Regex("""(\d+)h\s*(\d+)m""").find(txt)?.let { m ->
                    val h = m.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
                    val min = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                    if (h + min > 0) return h * 60 + min
                }
                Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE).find(txt)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
                Regex("""(\d+)\s*[mM]""").find(txt)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun extractRecs(doc: Document): List<SearchResponse> {
        val recs = mutableListOf<SearchResponse>()
        doc.select("a[href*='/movie/'], a[href*='/tv-show/']").forEach { a ->
            if (recs.size >= 12) return@forEach
            val href = a.attr("href"); if (!href.contains("watch-")) return@forEach
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            val img = a.selectFirst("img")
            val title = a.selectFirst("h3,h4,.title,[class*=title]")?.text()?.trim() ?: img?.attr("alt")?.trim() ?: a.text().trim().take(60)
            if (title.isBlank() || title.length < 2) return@forEach
            val poster = fixUrl(img?.attr("src")?.ifBlank { img?.attr("data-src") })
            val type = if (href.contains("/tv-show/")) TvType.TvSeries else TvType.Movie
            recs.add(when (type) {
                TvType.TvSeries -> newTvSeriesSearchResponse(cleanTitle(title), fullUrl, type) { this.posterUrl = poster }
                else -> newMovieSearchResponse(cleanTitle(title), fullUrl, type) { this.posterUrl = poster }
            })
        }
        return recs.distinctBy { it.url }.take(12)
    }

    private fun extractAnimeRecs(doc: Document): List<SearchResponse> =
        extractRecs(doc).filter { it.url.contains("/anime/") }.take(12)

    private fun extractTvShowId(doc: Document): String? {
        doc.selectFirst("[data-tv-show-id]")?.attr("data-tv-show-id")?.let { return it }
        Regex("""toggleEpisodeSources\((\d+)""").find(doc.html())?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun extractEpisodes(doc: Document, tvShowId: String?, baseUrl: String): List<Episode> {
        val eps = mutableListOf<Episode>()
        doc.select("[data-episode-id]").forEach { el ->
            val season = el.attr("data-season").toIntOrNull() ?: 1
            val epNum = el.attr("data-episode").toIntOrNull() ?: (eps.size + 1)
            val name = el.selectFirst("h3,h4,.ep-title,[class*=title],.name")?.text()?.trim() ?: "Episode $epNum"
            val poster = el.selectFirst("img")?.let { fixUrl(it.attr("src").ifBlank { it.attr("data-src") }) }
            val plot = el.selectFirst("p,.overview,.description,[class*=desc]")?.text()?.trim()
            val rt = el.selectFirst("[class*=runtime],[class*=duration]")?.text()?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            eps.add(newEpisode("EPISODE|${tvShowId ?: "0"}|$season|$epNum") {
                this.name = name; this.season = season; this.episode = epNum
                this.posterUrl = poster; this.description = plot; this.runTime = rt
            })
        }
        return eps.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    private fun cleanTitle(title: String): String =
        title.replace(Regex("""\s*\(\d{4}\)\s*"""), " ")
            .replace(Regex("""(?i)\s*(web-dl|webrip|bluray|bdrip|hdtv|hdrip|dvdrip|camrip|hdcam|1080p|720p|480p|2160p|4k)\s*"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

    private fun extractYearFromText(text: String): Int? =
        Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()

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
