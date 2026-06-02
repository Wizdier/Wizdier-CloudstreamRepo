package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element

class CineplexBD : MainAPI() {
    override var mainUrl = "http://cineplexbd.net"
    override var name = "Cineplex BD"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )

    private val cfHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
    )

    // ----------------------------- Main page -----------------------------

    // Each row → category endpoint or search by year/genre
    override val mainPage = mainPageOf(
        "$mainUrl/search.php?q=&year[]=2026&year[]=2025&page=" to "Latest (2025-2026)",
        "$mainUrl/category.php?category=English&page=" to "English Movies",
        "$mainUrl/category.php?category=Hindi&page=" to "Hindi Movies",
        "$mainUrl/category.php?category=Korean&page=" to "Korean Movies",
        "$mainUrl/category.php?category=Anime&page=" to "Anime",
        "$mainUrl/category.php?category=Animation&page=" to "Animation",
        "$mainUrl/tcategory.php?category=Web%20Series&page=" to "Web Series",
        "$mainUrl/tcategory.php?category=Korean%20Series&page=" to "Korean Series",
        "$mainUrl/tcategory.php?category=Hindi%20Series&page=" to "Hindi Series",
        "$mainUrl/tcategory.php?category=English%20Series&page=" to "English Series",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val doc = app.get(url, headers = cfHeaders).document

        val items = doc.select(
            "a[href*='view.php'], a[href*='watch.php'], a[href*='tview.php'], " +
                    ".movie-card a, a:has(.poster), a:has(img[src*='uploads'])"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            // Mirror the original "popular" filter that hides bangla/pakistani
            // ONLY for the Latest row (so dedicated category rows still show those titles).
            .let { list ->
                if (request.name.startsWith("Latest")) {
                    list.filterNot {
                        val t = it.name.lowercase()
                        t.contains("bangla") || t.contains("pakistani")
                    }
                } else list
            }

        val hasNext = doc.select(
            "ul.pagination li.active + li a, a:contains(Next), a:contains(»), a.next"
        ).isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    // ----------------------------- Search --------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?q=${query.encodeUrl()}&page=1"
        val doc = app.get(url, headers = cfHeaders).document
        return doc.select(
            "a[href*='view.php'], a[href*='watch.php'], a[href*='tview.php'], " +
                    ".movie-card a, a:has(.poster), a:has(img[src*='uploads'])"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    // ----------------------------- Load ----------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val absUrl = if (url.startsWith("http")) url else mainUrl + url
        val doc = app.get(absUrl, headers = cfHeaders).document

        val title = doc.selectFirst("h1, .movie-title, title")
            ?.text()?.replace(" — Watch", "")?.trim() ?: "Unknown"

        var description = doc.selectFirst("p.leading-relaxed, #synopsis, .description")
            ?.text().orEmpty()

        val genres = doc.select("span.chip:contains(,)").text().takeIf { it.isNotBlank() }
            ?.split(",")?.map { it.trim() }
            ?: doc.select("div.ganre-wrapper a, .meta-cat, .genre a").map { it.text() }

        val director = doc.select("div.mt-4.text-sm:contains(Director:) span").text()
            .ifBlank { doc.select("a[href*='cast.php'][href*='Director']").text() }

        val posterEl = doc.selectFirst("img.poster, .tvCard img, .movie-poster img")
        val rawPoster = posterEl?.attr("data-src")?.ifBlank { null } ?: posterEl?.attr("src")
        val poster = rawPoster?.let { if (it.startsWith("http")) it else "$mainUrl/${it.trimStart('/')}" }

        val year = doc.select("span.chip:matches(\\d{4})").text()
            .let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        val duration = doc.select("span.chip:matches(\\d+h \\d+m)").text()
            .let { parseDuration(it) }
        val langChip = doc.select("span.chip:contains(Lang:)").text()
        val score = doc.select("span.pill:contains(User Score:)").text()

        val extra = buildString {
            if (!langChip.isNullOrBlank()) append("\n").append(langChip)
            if (!score.isNullOrBlank()) append("\n").append(score)
        }
        if (extra.isNotBlank()) description = (description + extra).trim()

        // Decide movie vs TV based on the URL pattern (same logic as Aniyomi)
        val isSeries = absUrl.contains("watch.php")

        return if (!isSeries) {
            // Movie
            val id = absUrl.substringAfter("id=").substringBefore("&")
            val dataUrl = "/player.php?id=$id"
            newMovieLoadResponse(title, absUrl, TvType.Movie, dataUrl) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.duration = duration
                this.tags = genres
                addActors(listOf())
                if (director.isNotBlank()) {
                    this.actors = listOf(
                        ActorData(Actor(director, null), roleString = "Director")
                    )
                }
            }
        } else {
            // TV series
            val id = if (absUrl.contains("series_id=")) {
                absUrl.substringAfter("series_id=").substringBefore("&")
            } else {
                absUrl.substringAfter("id=").substringBefore("&")
            }

            val seasonOptions = doc.select("select[name=season] option")
            val seasons = if (seasonOptions.isNotEmpty()) {
                seasonOptions.map { it.attr("value") }
            } else listOf("1")

            val episodes = mutableListOf<Episode>()
            for (season in seasons) {
                runCatching {
                    val metaUrl = "$mainUrl/watch.php?id=$id&season=$season&meta=1"
                    val metaText = app.get(metaUrl, headers = cfHeaders).text
                    val meta = parseJson<MetaResponse>(metaText)
                    meta.episodes?.forEach { (key, ep) ->
                        val rawName = ep.title ?: "Episode $key"
                        val epPath = ep.path ?: return@forEach
                        val epNum = ep.episodeNumber?.toIntOrNull()
                            ?: key.toIntOrNull()
                            ?: Regex("(?i)E(\\d+)").find(rawName)?.groupValues?.get(1)?.toIntOrNull()
                            ?: 0
                        val seasonInt = season.toIntOrNull() ?: 1
                        episodes += newEpisode(epPath) {
                            this.name = rawName
                            this.season = seasonInt
                            this.episode = epNum
                        }
                    }
                }
            }

            // Sort by season, then episode
            episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            newTvSeriesLoadResponse(title, absUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
                if (director.isNotBlank()) {
                    this.actors = listOf(
                        ActorData(Actor(director, null), roleString = "Director")
                    )
                }
            }
        }
    }

    // ----------------------------- loadLinks -----------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = when {
            data.startsWith("http") -> data
            data.startsWith("/") -> mainUrl + data
            else -> "$mainUrl/$data"
        }

        // Direct file shortcut (mirrors the Aniyomi shortcut)
        if (url.endsWith(".mp4") || url.endsWith(".mkv") || url.contains("/Data/")) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Direct",
                    url = url,
                    type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        val html = app.get(url, headers = cfHeaders).text

        // 1) const videoSrc = "..."
        val videoUrl = Regex("""const\s+videoSrc\s*=\s*["'](.*?)["']""")
            .find(html)?.groupValues?.get(1)

        var found = false

        if (!videoUrl.isNullOrBlank()) {
            val finalUrl = if (videoUrl.startsWith("http")) videoUrl
            else "$mainUrl/${videoUrl.trimStart('/')}"

            if (finalUrl.contains(".m3u8")) {
                // HLS – CloudStream's M3u8Helper handles master playlist quality split natively
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = finalUrl,
                    referer = "$mainUrl/",
                ).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - Original",
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            found = true
        }

        // 2) Fallback: any <source> tag
        org.jsoup.Jsoup.parse(html)
            .select("source[src*='.mp4'], source[src*='.mkv'], source[src*='.m3u8'], source")
            .forEach {
                val src = it.attr("src")
                if (src.isBlank() || src == videoUrl) return@forEach
                val finalUrl = if (src.startsWith("http")) src
                else "$mainUrl/${src.trimStart('/')}"
                val type = if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - Fallback",
                        url = finalUrl,
                        type = type,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }

        return found
    }

    // ----------------------------- Helpers -------------------------------

    private fun Element.toSearchResult(): SearchResponse? {
        val rawUrl = attr("href").ifBlank { return null }

        val id = if (rawUrl.contains("series_id=")) {
            rawUrl.substringAfter("series_id=").substringBefore("&")
        } else {
            rawUrl.substringAfter("id=").substringBefore("&")
        }
        if (id.isBlank() || id == rawUrl) return null

        val href = when {
            rawUrl.contains("series_id=") -> "$mainUrl/watch.php?series_id=$id"
            rawUrl.contains("tview.php") -> "$mainUrl/tview.php?id=$id"
            rawUrl.contains("watch.php") -> "$mainUrl/watch.php?id=$id"
            else -> "$mainUrl/view.php?id=$id"
        }

        val titleEl = selectFirst("span.truncate, div.text-sm, div.cp-title, h2, .card-title, .title")
        val posterImg = selectFirst("img.poster, .tvCard img, img[class*='poster'], img[src*='uploads/']")
        val title = titleEl?.text() ?: posterImg?.attr("alt") ?: return null
        if (title == "Unknown Title" || title.isBlank()) return null

        var rawImg = posterImg?.attr("data-src")?.ifBlank { null }
            ?: posterImg?.attr("src")
            ?: selectFirst("img")?.attr("data-src")
            ?: selectFirst("img")?.attr("src")

        if (rawImg.isNullOrBlank()) {
            val style = selectFirst("div[style*='background-image']")?.attr("style")
            if (style != null && style.contains("url(")) {
                rawImg = style.substringAfter("url(").substringBefore(")")
                    .replace("'", "").replace("\"", "")
            }
        }
        val poster = rawImg?.let {
            if (it.startsWith("http")) it else "$mainUrl/${it.trimStart('/')}"
        }

        val type = when {
            href.contains("watch.php") -> TvType.TvSeries
            else -> TvType.Movie
        }

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    private fun parseDuration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val h = Regex("(\\d+)h").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)m").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = h * 60 + m
        return if (total > 0) total else null
    }

    // --------------- JSON model for /watch.php?...&meta=1 ----------------

    data class MetaResponse(
        @JsonProperty("episodes") val episodes: Map<String, EpisodeMeta>? = null,
    )

    data class EpisodeMeta(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("path") val path: String? = null,
        @JsonProperty("episode_number") val episodeNumber: String? = null,
    )
}
