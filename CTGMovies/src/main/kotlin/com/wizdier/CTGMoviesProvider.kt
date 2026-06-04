package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder

class CTGMoviesProvider : MainAPI() {
    override var mainUrl = "https://ctgmovies.com"
    override var name = "CTG Movies"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "/movies?page=" to "Movies",
        "/tv?page=" to "TV Shows",
    )

    private fun cleanUrl(url: String?): String? {
        val clean = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (clean.startsWith("/_next/image?url=")) {
            return URLDecoder.decode(clean.substringAfter("url=").substringBefore("&"), "UTF-8")
        }
        return if (clean.startsWith("http")) clean else "$mainUrl$clean"
    }

    private fun getSearchQuality(qStr: String?): SearchQuality? {
        val q = qStr?.lowercase() ?: return null
        if (q.contains("4k")) return SearchQuality.HD
        if (q.contains("1080")) return SearchQuality.HD
        if (q.contains("720")) return SearchQuality.HD
        if (q.contains("web")) return SearchQuality.WebRip
        if (q.contains("hd")) return SearchQuality.HD
        if (q.contains("cam")) return SearchQuality.Cam
        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl${request.data}$page"
        val doc = app.get(url).document

        val items = doc.select("a[href*='/movies/'], a[href*='/tv/']").mapNotNull { element ->
            val title = element.selectFirst("h3")?.text()?.substringBefore("(")?.trim() ?: return@mapNotNull null
            val href = element.attr("href")
            val img = element.selectFirst("img")
            val posterUrl = cleanUrl(img?.attr("src") ?: img?.attr("srcset")?.split(" ")?.firstOrNull())
            
            val isTv = href.contains("/tv/")
            val qualityEl = element.selectFirst("span.absolute.top-2.right-2")?.text()

            if (isTv) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.quality = getSearchQuality(qualityEl)
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.quality = getSearchQuality(qualityEl)
                }
            }
        }.distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val doc = app.get(url).document

        return doc.select("a[href*='/movies/'], a[href*='/tv/']").mapNotNull { element ->
            val title = element.selectFirst("h3")?.text()?.substringBefore("(")?.trim() ?: return@mapNotNull null
            val href = element.attr("href")
            val img = element.selectFirst("img")
            val posterUrl = cleanUrl(img?.attr("src") ?: img?.attr("srcset")?.split(" ")?.firstOrNull())
            
            val qualityEl = element.selectFirst("span.absolute.top-2.right-2")?.text()

            val isTv = href.contains("/tv/")
            if (isTv) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.quality = getSearchQuality(qualityEl)
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.quality = getSearchQuality(qualityEl)
                }
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val absUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val doc = app.get(absUrl).document

        val title = doc.selectFirst("h1")?.text()?.substringBefore("(")?.trim() ?: return null
        
        val posterImg = doc.selectFirst("img[alt*='${title.take(5)}']") ?: doc.selectFirst("img.object-cover")
        val posterUrl = cleanUrl(posterImg?.attr("src") ?: posterImg?.attr("srcset")?.split(" ")?.firstOrNull())

        var desc = doc.selectFirst("h3:contains(Synopsis) + p")?.text()
        if (desc.isNullOrBlank()) {
             desc = doc.select("p").maxByOrNull { it.text().length }?.text()
        }

        val metadata = mutableMapOf<String, String>()
        doc.select("tr").forEach { tr ->
            val tds = tr.select("td")
            if (tds.size >= 2) {
                metadata[tds[0].text().trim()] = tds[1].text().trim()
            }
        }

        val year = metadata["Year"]?.toIntOrNull()
        val tags = metadata["Genre"]?.split(",")?.map { it.trim() }
        val actors = metadata["Stars"]?.split(",")?.map { ActorData(Actor(it.trim(), null), roleString = "Actor") }
        val duration = metadata["Runtime"]?.let { getDurationFromString(it) }

        val ratingMatch = Regex("([0-9.]+)\\s*/\\s*10").find(doc.text())
        val rating = ratingMatch?.groupValues?.get(1)?.toDoubleOrNull()

        val isTv = absUrl.contains("/tv/")
        val tvType = if (isTv) TvType.TvSeries else TvType.Movie

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            
            doc.select("li.border").forEach { li ->
                val epWatchLink = li.selectFirst("a[href*='/watch/']")?.attr("href") ?: return@forEach
                
                val epText = li.text()
                val match = Regex("S(\\d+)E(\\d+)").find(epText)
                var season = 1
                var epNum = 1
                if (match != null) {
                    season = match.groupValues[1].toInt()
                    epNum = match.groupValues[2].toInt()
                }

                val epImg = li.selectFirst("img")
                val epTitle = epImg?.attr("alt")?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                val epThumb = cleanUrl(epImg?.attr("src"))
                
                val epPlot = li.select("p").lastOrNull()?.text()

                episodes.add(
                    newEpisode(epWatchLink) {
                        this.name = epTitle
                        this.season = season
                        this.episode = epNum
                        this.posterUrl = epThumb
                        this.description = epPlot
                    }
                )
            }
            
            if (episodes.isEmpty()) {
                var fallbackEpNum = 1
                doc.select("a[href*='/watch/']").forEach { a ->
                    val epHref = a.attr("href")
                    if (epHref.contains("type=episode")) {
                        episodes.add(
                            newEpisode(epHref) {
                                this.name = "Episode $fallbackEpNum"
                                this.episode = fallbackEpNum
                                fallbackEpNum++
                            }
                        )
                    }
                }
            }
            
            return newTvSeriesLoadResponse(title, absUrl, tvType, episodes.distinctBy { it.data }) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = desc
                this.tags = tags
                this.actors = actors
                runCatching { rating?.let { this.score = Score.from10(it) } }
            }
        } else {
            val watchLink = doc.selectFirst("a[href*='/watch/']")?.attr("href") ?: absUrl
            return newMovieLoadResponse(title, absUrl, tvType, watchLink) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = desc
                this.tags = tags
                this.duration = duration
                this.actors = actors
                runCatching { rating?.let { this.score = Score.from10(it) } }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val absUrl = if (data.startsWith("http")) data else "$mainUrl$data"
        val response = app.get(absUrl)
        val doc = response.document

        val iframes = doc.select("iframe").mapNotNull { it.attr("src") }
        for (iframe in iframes) {
            val iframeUrl = if (iframe.startsWith("//")) "https:$iframe" else iframe
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Iframe",
                    url = iframeUrl,
                    referer = this.mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = iframeUrl.contains("m3u8")
                )
            )
        }
        
        val htmlText = response.text
        val videoRegex = Regex("(https?://[^\"]+\\.(?:m3u8|mp4|mkv))")
        videoRegex.findAll(htmlText).forEach { match ->
            val link = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = link,
                    referer = this.mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = link.contains("m3u8")
                )
            )
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Web Player (Requires Login)",
                url = absUrl,
                referer = this.mainUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = false
            )
        )

        return true
    }
}
