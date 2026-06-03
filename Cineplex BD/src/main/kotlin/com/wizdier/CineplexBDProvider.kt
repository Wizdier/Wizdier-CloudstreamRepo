package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CineplexBD : MainAPI() {
    override var mainUrl = "http://cineplexbd.net"
    override var name = "Cineplex BD"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )

    // Simkl sync works via IMDB mapping — Cloudstream handles it with the
    // user's logged-in account. No API keys needed at the plugin level.
    override val supportedSyncNames = setOfNotNull(
        SyncIdName.Anilist,
        SyncIdName.MyAnimeList,
        runCatching { SyncIdName.valueOf("Kitsu") }.getOrNull(),
        runCatching { SyncIdName.valueOf("Simkl") }.getOrNull(),
    )

    private val cfHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
    )

    // ----------------------------- Main page -----------------------------

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

        // ─── Scrape what the source gives us ───────────────────────────────
        val rawTitle = doc.selectFirst("h1, .movie-title, title")
            ?.text()?.replace(" — Watch", "")?.trim().orEmpty()

        val scrapedPlot = doc.selectFirst("p.leading-relaxed, #synopsis, .description")
            ?.text().orEmpty()

        val scrapedGenres = doc.select("span.chip:contains(,)").text()
            .takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: doc.select("div.ganre-wrapper a, .meta-cat, .genre a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }

        val director = doc.select("div.mt-4.text-sm:contains(Director:) span").text()
            .ifBlank { doc.select("a[href*='cast.php'][href*='Director']").text() }

        val posterEl = doc.selectFirst("img.poster, .tvCard img, .movie-poster img")
        val rawPoster = posterEl?.attr("data-src")?.ifBlank { null } ?: posterEl?.attr("src")
        val scrapedPoster = rawPoster?.let {
            if (it.startsWith("http")) it else "$mainUrl/${it.trimStart('/')}"
        }

        val scrapedYear = doc.select("span.chip").map { it.text() }
            .firstNotNullOfOrNull { txt -> Regex("\\d{4}").find(txt)?.value?.toIntOrNull() }
        val scrapedDuration = doc.select("span.chip").map { it.text() }
            .firstNotNullOfOrNull { txt -> parseDuration(txt) }

        val isSeries = absUrl.contains("watch.php")
        val looksLikeAnime = isAnimeContent(absUrl, scrapedGenres, rawTitle)

        // ─── Enrich (best-effort) ──────────────────────────────────────────
        // For series, ask the enricher for season 1 stills/overviews. (Most
        // listings are season-1; on multi-season shows we apply the per-season
        // TMDB lookup only to season 1's episodes — covers the common case
        // without burning extra requests on every load.)
        val meta = runCatching {
            if (looksLikeAnime) MetadataEnricher.enrichAnime(rawTitle, seasonHint = 1)
            else MetadataEnricher.enrichMovieOrTv(rawTitle, scrapedYear, seasonHint = 1)
        }.getOrNull() ?: MetadataEnricher.MetaInfo()

        val finalTitle = meta.title?.takeIf { it.isNotBlank() } ?: rawTitle.ifBlank { "Unknown" }
        val finalPoster = meta.posterUrl ?: scrapedPoster
        val finalBackdrop = meta.backdropUrl
        val finalPlot = buildString {
            append(meta.plot?.takeIf { it.isNotBlank() } ?: scrapedPlot)
            meta.originalTitle?.takeIf { it != finalTitle }?.let { append("\n\nOriginal title: ").append(it) }
        }.trim().ifBlank { null }
        val finalYear = meta.year ?: scrapedYear
        val finalTags = (meta.tags ?: scrapedGenres).takeIf { it.isNotEmpty() }
        val finalRating = meta.rating
        val finalLogo = meta.logoUrl
        val finalTrailer = meta.trailerUrl
        val recommendations = meta.recommendations.toSearchResponses()

        val movieType = if (looksLikeAnime || meta.isAnimeHint) TvType.AnimeMovie else TvType.Movie
        val seriesType = if (looksLikeAnime || meta.isAnimeHint) TvType.Anime else TvType.TvSeries

        return if (!isSeries) {
            val id = absUrl.substringAfter("id=").substringBefore("&")
            val dataUrl = "/player.php?id=$id"
            newMovieLoadResponse(finalTitle, absUrl, movieType, dataUrl) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.plot = finalPlot
                this.year = finalYear
                this.duration = scrapedDuration
                this.tags = finalTags
                runCatching { finalRating?.let { this.score = Score.from10(it) } }
                if (director.isNotBlank()) {
                    this.actors = listOf(
                        ActorData(Actor(director, null), roleString = "Director")
                    )
                }
                runCatching { if (recommendations.isNotEmpty()) this.recommendations = recommendations }
                runCatching { finalLogo?.let { this.logoUrl = it } }
                runCatching { finalTrailer?.let { addTrailer(it) } }
                runCatching { meta.imdbId?.let { addImdbId(it) } }
                runCatching { meta.malId?.let { addMalId(it) } }
                runCatching { meta.anilistId?.let { addAniListId(it) } }
                runCatching { meta.kitsuId?.let { addKitsuId(it) } }
            }
        } else {
            val seriesId = parseSeriesId(absUrl)
            val episodes = collectEpisodes(seriesId, absUrl, doc, meta)

            newTvSeriesLoadResponse(finalTitle, absUrl, seriesType, episodes) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.plot = finalPlot
                this.year = finalYear
                this.tags = finalTags
                runCatching { finalRating?.let { this.score = Score.from10(it) } }
                if (director.isNotBlank()) {
                    this.actors = listOf(
                        ActorData(Actor(director, null), roleString = "Director")
                    )
                }
                runCatching { if (recommendations.isNotEmpty()) this.recommendations = recommendations }
                runCatching { finalLogo?.let { this.logoUrl = it } }
                runCatching { finalTrailer?.let { addTrailer(it) } }
                runCatching { meta.imdbId?.let { addImdbId(it) } }
                runCatching { meta.malId?.let { addMalId(it) } }
                runCatching { meta.anilistId?.let { addAniListId(it) } }
                runCatching { meta.kitsuId?.let { addKitsuId(it) } }
            }
        }
    }

    // ─────────────────────── Episode collection ──────────────────────────
    //
    // Fixes for the "no episodes / coming soon" bug:
    //
    //  (1) Build the meta URL with the *same* key (id= vs series_id=) the page
    //      itself used — the backend treats them differently.
    //  (2) Parse the meta JSON manually with org.json so a minor schema
    //      variation (episode_number as int instead of string, or an `episodes`
    //      array instead of an object map) doesn't blow the whole loop.
    //  (3) Try every season we can discover, not just season=1 — we look at
    //      <select name=season>, <option>, [data-season] chips and a numeric
    //      query param fallback (?season=N up to 12) so missing UI doesn't hide
    //      content.
    //  (4) If `meta=1` returns zero episodes for every probed season, parse the
    //      page HTML for episode-link anchors as a fallback — the page itself
    //      always lists at least the published episodes.
    //  (5) If literally everything fails, emit a single placeholder episode
    //      that points at the watch URL itself, so the user gets a working
    //      play button instead of the "coming soon" empty state.
    private suspend fun collectEpisodes(
        seriesId: String,
        watchUrl: String,
        doc: Document,
        meta: MetadataEnricher.MetaInfo,
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seriesIdKey = if (watchUrl.contains("series_id=")) "series_id" else "id"

        // ── (3) collect every season hint we can find ─────────────────────
        val seasonHints = linkedSetOf<String>()
        doc.select("select[name=season] option, select#season option, select.season option")
            .forEach { opt ->
                opt.attr("value").takeIf { it.isNotBlank() }?.let(seasonHints::add)
            }
        doc.select("[data-season], a[href*='season='], li.season")
            .forEach { el ->
                el.attr("data-season").takeIf { it.isNotBlank() }?.let(seasonHints::add)
                Regex("""season=(\d+)""").find(el.attr("href"))?.groupValues?.get(1)
                    ?.let(seasonHints::add)
            }
        // Numeric chips with just a season number in their text
        doc.select(".seasons button, .seasons a, .season-list a").forEach { el ->
            Regex("""\d+""").find(el.text())?.value?.let(seasonHints::add)
        }
        if (seasonHints.isEmpty()) seasonHints += "1"

        // ── (1)(2) JSON meta endpoint, robust parsing ─────────────────────
        for (season in seasonHints) {
            val seasonInt = season.toIntOrNull() ?: continue
            val metaUrl = "$mainUrl/watch.php?$seriesIdKey=$seriesId&season=$season&meta=1"
            val text = runCatching { app.get(metaUrl, headers = cfHeaders).text }.getOrNull()
                ?: continue
            episodes += parseEpisodesFromMetaJson(text, seasonInt, meta.episodes)
        }

        // If the JSON path returned at least one episode, we're done.
        if (episodes.isNotEmpty()) {
            return episodes.dedupAndSort()
        }

        // ── (3-extra) numeric fallback when the page exposes no season UI ─
        for (s in 1..12) {
            val metaUrl = "$mainUrl/watch.php?$seriesIdKey=$seriesId&season=$s&meta=1"
            val text = runCatching { app.get(metaUrl, headers = cfHeaders).text }.getOrNull()
                ?: continue
            val parsed = parseEpisodesFromMetaJson(text, s, meta.episodes)
            if (parsed.isNotEmpty()) episodes += parsed
            // Stop if 3 consecutive empty seasons after we've already seen content
            if (episodes.isNotEmpty() && parsed.isEmpty() && s - (episodes.last().season ?: 0) >= 3) break
        }
        if (episodes.isNotEmpty()) return episodes.dedupAndSort()

        // ── (4) HTML fallback: scrape anchors on the watch page ───────────
        episodes += parseEpisodesFromWatchPage(doc, meta.episodes)
        if (episodes.isNotEmpty()) return episodes.dedupAndSort()

        // ── (5) last-resort placeholder so the play button is never absent ─
        episodes += newEpisode(watchUrl) {
            this.name = "Watch"
            this.season = 1
            this.episode = 1
        }
        return episodes
    }

    private fun parseEpisodesFromMetaJson(
        rawText: String,
        season: Int,
        tmdbEps: List<MetadataEnricher.EpisodeMeta>,
    ): List<Episode> {
        if (rawText.isBlank()) return emptyList()
        val root = runCatching { JSONObject(rawText) }.getOrNull() ?: return emptyList()

        val out = mutableListOf<Episode>()
        val episodesNode: Any? = root.opt("episodes") ?: root.opt("data") ?: root

        // Schema variant A: { "episodes": { "1": {…}, "2": {…} } }
        if (episodesNode is JSONObject) {
            val keys = episodesNode.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = episodesNode.optJSONObject(k) ?: continue
                out += buildEpisode(v, fallbackKey = k, season = season, tmdbEps = tmdbEps)
                    ?: continue
            }
        }
        // Schema variant B: { "episodes": [ {…}, {…} ] }
        else if (episodesNode is JSONArray) {
            for (i in 0 until episodesNode.length()) {
                val v = episodesNode.optJSONObject(i) ?: continue
                out += buildEpisode(v, fallbackKey = (i + 1).toString(), season = season, tmdbEps = tmdbEps)
                    ?: continue
            }
        }
        return out
    }

    private fun buildEpisode(
        v: JSONObject,
        fallbackKey: String,
        season: Int,
        tmdbEps: List<MetadataEnricher.EpisodeMeta>,
    ): Episode? {
        // path can be string, or under "url" / "src" / "file"
        val epPath = (v.optStringOrNull("path")
            ?: v.optStringOrNull("url")
            ?: v.optStringOrNull("src")
            ?: v.optStringOrNull("file"))
            ?: return null

        val rawName = v.optStringOrNull("title") ?: "Episode $fallbackKey"

        // episode_number can be int, string, or absent
        val epNum = v.optStringOrNull("episode_number")?.toIntOrNull()
            ?: v.optInt("episode_number", 0).takeIf { it != 0 }
            ?: fallbackKey.toIntOrNull()
            ?: Regex("(?i)E(\\d+)").find(rawName)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0

        val tmdbEp = tmdbEps.getOrNull(epNum - 1)

        return newEpisode(epPath) {
            this.name = tmdbEp?.name?.takeIf { it.isNotBlank() } ?: rawName
            this.season = season
            this.episode = epNum
            this.posterUrl = tmdbEp?.stillUrl
            this.description = tmdbEp?.overview
            runCatching { tmdbEp?.rating?.let { this.score = Score.from10(it) } }
            runCatching { tmdbEp?.airDate?.let { this.date = it } }
        }
    }

    private fun parseEpisodesFromWatchPage(
        doc: Document,
        tmdbEps: List<MetadataEnricher.EpisodeMeta>,
    ): List<Episode> {
        // Anchors that look like episodes: href contains ep= or episode= or
        // /Data/…, OR a class/attr that says episode.
        val anchors = doc.select(
            "a[href*='ep='], a[href*='episode='], a[href*='/Data/'], " +
                    "a.episode, a[data-episode], .episode-list a, .episodes a"
        )
        if (anchors.isEmpty()) return emptyList()

        val out = mutableListOf<Episode>()
        anchors.forEachIndexed { idx, a ->
            val href = a.attr("href").ifBlank { return@forEachIndexed }
            val name = a.text().ifBlank { "Episode ${idx + 1}" }
            val epNum = Regex("""(?i)(?:ep(?:isode)?[=\s]*|\bE)(\d+)""")
                .find(href + " " + name)?.groupValues?.get(1)?.toIntOrNull()
                ?: (idx + 1)
            val tmdbEp = tmdbEps.getOrNull(epNum - 1)
            out += newEpisode(href) {
                this.name = tmdbEp?.name?.takeIf { it.isNotBlank() } ?: name
                this.season = 1
                this.episode = epNum
                this.posterUrl = tmdbEp?.stillUrl
                this.description = tmdbEp?.overview
                runCatching { tmdbEp?.rating?.let { this.score = Score.from10(it) } }
                runCatching { tmdbEp?.airDate?.let { this.date = it } }
            }
        }
        return out
    }

    private fun List<Episode>.dedupAndSort(): List<Episode> =
        distinctBy { (it.season ?: 0) to (it.episode ?: 0) to (it.data) }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

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

        // Direct media URLs or player page paths: fetch the actual video
        if (url.endsWith(".mp4") || url.endsWith(".mkv") || url.endsWith(".m3u8")
            || url.contains("/Data/") || url.contains("/player.php")) {
            // For player.php, we need to fetch it to extract the video source
            val resolvedUrl = if (url.contains("/player.php")) {
                val playerHtml = app.get(url, headers = cfHeaders).text
                Regex("""const\s+videoSrc\s*=\s*["'](.*?)["']""")
                    .find(playerHtml)?.groupValues?.get(1)
                    ?.let { if (it.startsWith("http")) it else "$mainUrl/${it.trimStart('/')}" }
                    ?: url
            } else url

            val linkType = if (resolvedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            if (linkType == ExtractorLinkType.M3U8) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = resolvedUrl,
                    referer = "$mainUrl/",
                ).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Direct",
                        url = resolvedUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return true
        }

        val html = app.get(url, headers = cfHeaders).text
        val videoUrl = Regex("""const\s+videoSrc\s*=\s*["'](.*?)["']""")
            .find(html)?.groupValues?.get(1)

        var found = false

        if (!videoUrl.isNullOrBlank()) {
            val finalUrl = if (videoUrl.startsWith("http")) videoUrl
            else "$mainUrl/${videoUrl.trimStart('/')}"

            if (finalUrl.contains(".m3u8")) {
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

    private fun parseSeriesId(absUrl: String): String =
        if (absUrl.contains("series_id="))
            absUrl.substringAfter("series_id=").substringBefore("&")
        else absUrl.substringAfter("id=").substringBefore("&")

    private fun isAnimeContent(absUrl: String, genres: List<String>, title: String): Boolean {
        val lUrl = absUrl.lowercase()
        if (lUrl.contains("category=anime") || lUrl.contains("category=animation")) return true
        val g = genres.map { it.lowercase() }
        if (g.any { it.contains("anime") || it == "animation" || it.contains("cartoon") }) return true
        val t = title.lowercase()
        return t.contains("anime") || (t.contains("season ") && (t.contains("ova") || t.contains("ona")))
    }

    private fun List<MetadataEnricher.RecommendationItem>.toSearchResponses(): List<SearchResponse> =
        mapNotNull { r ->
            val isTv = r.mediaType == "tv"
            val href = "https://www.themoviedb.org/${r.mediaType}/${r.tmdbId}"
            if (isTv) {
                newTvSeriesSearchResponse(r.title, href, TvType.TvSeries) {
                    this.posterUrl = r.posterUrl
                    this.year = r.year
                }
            } else {
                newMovieSearchResponse(r.title, href, TvType.Movie) {
                    this.posterUrl = r.posterUrl
                    this.year = r.year
                }
            }
        }

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

        // Detect content type based on URL path and category hints
        val lcHref = href.lowercase()
        val lcTitle = title.lowercase()
        val isAnim = lcHref.contains("category=anime") || lcHref.contains("category=animation")
                || lcTitle.contains("anime") || lcTitle.contains("animation") || lcTitle.contains("cartoon")
        val isSeries = href.contains("watch.php") || href.contains("tview.php")
        val type = when {
            isAnim && isSeries -> TvType.Anime
            isAnim -> TvType.AnimeMovie
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }

        return when (type) {
            TvType.Anime, TvType.AnimeMovie -> newAnimeSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
            else -> newMovieSearchResponse(title, href, TvType.Movie) {
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

    // Local org.json null-safe helper (mirrors the one in MetadataEnricher).
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null
        else optString(key, "").takeIf { it.isNotBlank() && it != "null" }
}
