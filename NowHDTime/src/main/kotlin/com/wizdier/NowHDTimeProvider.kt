package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class NowHDTimeProvider : MainAPI() {

    override var mainUrl         = NowHDTimeUtils.mainUrl
    override var name            = NowHDTimeUtils.name
    override var lang            = "bn"
    override val hasMainPage     = true
    override val hasDownloadSupport = true
    override val hasQuickSearch  = false

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    // ── Home page categories ──────────────────────────────────────────────────
    // The site has pages that return HTML grids — we scrape each section.
    // The anime section uses a completely different URL scheme (AniList-based).
    override val mainPage = mainPageOf(
        "$mainUrl/movies"    to "Latest Movies",
        "$mainUrl/tv-shows"  to "Latest TV Shows",
        "$mainUrl/trending"  to "Trending Now",
        "$mainUrl/anime"     to "Anime",
        // Paginated via ?page=N  (movies / tv-shows / trending)
        "$mainUrl/movies?page="   to "More Movies",
        "$mainUrl/tv-shows?page=" to "More TV Shows"
    )

    // ── Home page loader ──────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // For paginated sections use ?page=N suffix; others just fetch as-is on page 1
        val url = when {
            request.data.endsWith("?page=") -> "${request.data}$page"
            page > 1 -> "${request.data}?page=$page"
            else     -> request.data
        }
        val doc = app.get(url, timeout = 30).document
        val items = if (request.data.contains("/anime")) {
            parseAnimePage(doc)
        } else {
            parseMovieTvPage(doc)
        }
        return newHomePageResponse(
            list    = HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext(doc, page)
        )
    }

    // ── Search ────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        // Search movies + TV + anime in parallel
        val searchUrl = "$mainUrl/search?query=${query.replace(" ", "+")}"
        try {
            val doc = app.get(searchUrl, timeout = 30).document
            results.addAll(parseMovieTvPage(doc))
            results.addAll(parseAnimePage(doc))
        } catch (_: Exception) {}
        // Also try /anime with a search param
        if (results.isEmpty()) {
            try {
                val animeDoc = app.get("$mainUrl/anime?q=${query.replace(" ", "+")}", timeout = 30).document
                results.addAll(parseAnimePage(animeDoc))
            } catch (_: Exception) {}
        }
        return results.distinctBy { it.url }
    }

    // ── Detail loader ─────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        return when {
            url.contains("/anime/") && !url.contains("/watch/") -> loadAnime(url)
            url.contains("/tv-show/")                           -> loadTvShow(url)
            else                                                -> loadMovie(url)
        }
    }

    // ── Movie detail ──────────────────────────────────────────────────────────
    private suspend fun loadMovie(url: String): LoadResponse {
        val doc   = app.get(url, timeout = 30).document
        val title = doc.selectFirst("h1")?.text()?.let { NowHDTimeUtils.cleanTitle(it) }
            ?: doc.title()
        val poster = bestImage(doc)
        val plot   = extractPlot(doc)
        val year   = extractYear(doc)
        val rating = extractRating(doc)
        val tags   = NowHDTimeUtils.extractTags(doc).ifEmpty { extractGenres(doc) }
        val dur    = extractDuration(doc)
        val actors = NowHDTimeUtils.extractActors(doc)
        val trailer= extractTrailer(doc)
        val recs   = extractRecs(doc)
        val ids    = NowHDTimeUtils.extractTrackingIds(doc)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl      = poster
            this.year           = year
            this.plot           = plot
            this.tags           = tags
            this.rating         = rating
            this.duration       = dur
            this.recommendations = recs
            addActors(actors)
            addTrailer(trailer)
            addImdbId(ids.imdbId)
            addMalId(ids.malId)
            addAniListId(ids.anilistId)
            addKitsuId(ids.kitsuId)
        }
    }

    // ── TV Show detail ────────────────────────────────────────────────────────
    private suspend fun loadTvShow(url: String): LoadResponse {
        val doc     = app.get(url, timeout = 30).document
        val rawTitle= doc.selectFirst("h1")?.text() ?: doc.title()
        val title   = NowHDTimeUtils.cleanTitle(rawTitle)
        val poster  = bestImage(doc)
        val plot    = extractPlot(doc)
        val year    = extractYear(doc)
        val rating  = extractRating(doc)
        val tags    = NowHDTimeUtils.extractTags(doc).ifEmpty { extractGenres(doc) }
        val dur     = extractDuration(doc)
        val actors  = NowHDTimeUtils.extractActors(doc)
        val trailer = extractTrailer(doc)
        val recs    = extractRecs(doc)
        val ids     = NowHDTimeUtils.extractTrackingIds(doc)
        val episodes= extractTvEpisodes(doc, url)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = plot
            this.tags            = tags
            this.rating          = rating
            this.duration        = dur
            this.recommendations = recs
            addActors(actors)
            addTrailer(trailer)
            addImdbId(ids.imdbId)
            addMalId(ids.malId)
            addAniListId(ids.anilistId)
            addKitsuId(ids.kitsuId)
        }
    }

    // ── Anime detail ──────────────────────────────────────────────────────────
    // URL: https://nowhdtime.com.bd/anime/{anilistId}
    private suspend fun loadAnime(url: String): LoadResponse {
        val doc    = app.get(url, timeout = 30).document
        val rawTitle = doc.selectFirst("h1")?.text() ?: doc.title()
        val title  = NowHDTimeUtils.cleanTitle(rawTitle)
        val poster = bestImage(doc)
        val plot   = extractPlot(doc)
        val year   = extractYear(doc)
        val ratingText = doc.selectFirst(".score, .rating, [class*=score], [class*=rating]")?.text()
        val rating = NowHDTimeUtils.extractRating(ratingText)
        val tags   = NowHDTimeUtils.extractTags(doc).ifEmpty { extractGenres(doc) }
        val trailer= extractTrailer(doc)
        val recs   = extractAnimeRecs(doc)

        // Extract AniList ID from URL  /anime/182300
        val anilistId = Regex("/anime/(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()

        // MAL / Kitsu / Simkl from embedded links or meta
        val ids    = NowHDTimeUtils.extractTrackingIds(doc)
        val finalAnilist = anilistId ?: ids.anilistId

        // Build episode list from the numbered buttons on the page
        val episodes = extractAnimeEpisodes(doc, url, anilistId)

        // Seasons list — the site lists related seasons
        // We embed them as recommendations if multi-season
        val type = if (title.contains("movie", ignoreCase = true) || episodes.size == 1) TvType.AnimeMovie else TvType.Anime

        return if (type == TvType.AnimeMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = plot
                this.tags      = tags
                this.rating    = rating
                this.recommendations = recs
                addTrailer(trailer)
                addAniListId(finalAnilist)
                addMalId(ids.malId)
                addKitsuId(ids.kitsuId)
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = plot
                this.tags      = tags
                this.rating    = rating
                this.recommendations = recs
                addEpisodes(DubStatus.Subbed, episodes)
                addTrailer(trailer)
                addAniListId(finalAnilist)
                addMalId(ids.malId)
                addKitsuId(ids.kitsuId)
            }
        }
    }

    // ── loadLinks ─────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // Anime episode watch page: https://nowhdtime.com.bd/anime/{id}/watch/{ep}
        if (data.contains("/anime/") && data.contains("/watch/")) {
            found = loadAnimeLinks(data, subtitleCallback, callback) || found
        }

        // Always also load from the actual page (movies / tv shows have embedded players)
        found = loadPageLinks(data, subtitleCallback, callback) || found

        return found
    }

    // ── Anime episode link extraction ─────────────────────────────────────────
    private suspend fun loadAnimeLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val doc = try { app.get(data, timeout = 30).document } catch (_: Exception) { return false }

        // The anime watch page embeds a link like https://nontongo.win/anime/{id}/{ep}/play
        // visible as an <a> tag or iframe
        val nontongoUrl = Regex("""https://nontongo\.win/anime/\d+/\d+/play""").find(doc.html())?.value
        if (nontongoUrl != null) {
            try {
                if (loadExtractor(nontongoUrl, data, subtitleCallback, callback)) found = true
            } catch (_: Exception) {}
        }

        // Also grab any iframes on the watch page
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
            if (src.startsWith("http")) {
                try {
                    if (loadExtractor(src, data, subtitleCallback, callback)) found = true
                } catch (_: Exception) {}
            }
        }

        // Direct video links in JS
        NowHDTimeUtils.extractEmbeds(doc.html()).filter { it != data }.forEach { embedUrl ->
            if (isDirectVideo(embedUrl)) {
                callback(ExtractorLink(name, "$name Direct", embedUrl, data, Qualities.Unknown.value, embedUrl.contains(".m3u8")))
                found = true
            } else {
                try { if (loadExtractor(embedUrl, data, subtitleCallback, callback)) found = true } catch (_: Exception) {}
            }
        }
        return found
    }

    // ── Generic page link extraction (movie / TV / fallback) ─────────────────
    private suspend fun loadPageLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val doc  = try { app.get(data, timeout = 30).document } catch (_: Exception) { return false }
        val html = doc.html()

        // 1. All iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
            if (src.startsWith("http") && !src.contains(mainUrl)) {
                try { if (loadExtractor(src, data, subtitleCallback, callback)) found = true }
                catch (_: Exception) {}
            }
        }

        // 2. Embed URLs in page JS/HTML
        for (embedUrl in NowHDTimeUtils.extractEmbeds(html)) {
            if (embedUrl.contains(mainUrl)) continue
            try {
                if (isDirectVideo(embedUrl)) {
                    callback(ExtractorLink(name, "$name Direct", embedUrl, data,
                        NowHDTimeUtils.qualityFromString(embedUrl), embedUrl.contains(".m3u8")))
                    found = true
                } else {
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) found = true
                }
            } catch (_: Exception) {}
        }

        // 3. Explicit Watch server buttons
        // The site shows tabs: "Local", "Server1", "Server2"
        // They load different players. Buttons trigger JS — we scrape data-* attributes.
        doc.select("[data-src], [data-url], [data-embed], [data-iframe]").forEach { el ->
            val src = el.attr("data-src").ifBlank { el.attr("data-url") }
                .ifBlank { el.attr("data-embed") }.ifBlank { el.attr("data-iframe") }
            if (src.startsWith("http") && !src.contains(mainUrl)) {
                try { if (loadExtractor(src, data, subtitleCallback, callback)) found = true }
                catch (_: Exception) {}
            }
        }

        // 4. JS patterns for file/source
        listOf(
            Regex("""(?i)(?:file|src|source|video_url|stream_url)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8|mkv)[^"']*)["']"""),
            Regex("""(?i)sources\s*[:=]\s*\[\s*\{[^}]*["']?(?:file|src)["']?\s*:\s*["']([^"']+)["']""")
        ).forEach { pat ->
            pat.findAll(html).forEach { m ->
                val v = m.groupValues[1]
                if (v.startsWith("http")) {
                    callback(ExtractorLink(name, name, v, data,
                        NowHDTimeUtils.qualityFromString(v), v.contains(".m3u8")))
                    found = true
                }
            }
        }

        // 5. atob() base64 encoded sources
        Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""").findAll(html).forEach { m ->
            try {
                val dec = String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT))
                if (dec.startsWith("http")) {
                    if (isDirectVideo(dec)) {
                        callback(ExtractorLink(name, "$name Decoded", dec, data,
                            NowHDTimeUtils.qualityFromString(dec), dec.contains(".m3u8")))
                        found = true
                    } else {
                        try { if (loadExtractor(dec, data, subtitleCallback, callback)) found = true }
                        catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        // 6. Subtitles
        doc.select("track[src], track[data-src]").forEach { track ->
            val src = track.attr("src").ifBlank { track.attr("data-src") }
            val label = track.attr("label").ifBlank { track.attr("srclang").ifBlank { "Unknown" } }
            if (src.isNotBlank()) subtitleCallback(SubtitleFile(label, NowHDTimeUtils.fixUrl(src) ?: src))
        }

        return found
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Parsing helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /** Parse the movie/TV show grid pages (standard TMDB-styled cards) */
    private fun parseMovieTvPage(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // Cards are <a href="..."> wrapping an image + title text
        // The site structure shows: div with a > img, h3/h4 or title text
        val cardSelectors = listOf(
            "a[href*='/movie/watch-']",
            "a[href*='/tv-show/watch-']"
        )
        for (selector in cardSelectors) {
            doc.select(selector).forEach { a ->
                val href = a.attr("href")
                if (href.isBlank() || href == mainUrl) return@forEach
                val img   = a.selectFirst("img")
                val title = a.selectFirst("h3, h4, .title, p")?.text()?.trim()
                    ?: img?.attr("alt")?.trim()
                    ?: a.attr("title").trim()
                if (title.isBlank()) return@forEach
                val poster = NowHDTimeUtils.fixUrl(
                    img?.attr("src")?.ifBlank { img.attr("data-src") } ?: ""
                )
                val year   = NowHDTimeUtils.extractYear(title)
                val ratingEl = a.parent()?.selectFirst("[class*=rating], [class*=score]")?.text()
                val rating = NowHDTimeUtils.extractRating(ratingEl)

                val isTv = href.contains("/tv-show/") || title.contains("season", ignoreCase = true)
                if (isTv) {
                    results.add(newTvSeriesSearchResponse(NowHDTimeUtils.cleanTitle(title), href, TvType.TvSeries) {
                        this.posterUrl = poster; this.year = year
                    })
                } else {
                    results.add(newMovieSearchResponse(NowHDTimeUtils.cleanTitle(title), href, TvType.Movie) {
                        this.posterUrl = poster; this.year = year
                    })
                }
            }
        }

        // Fallback: generic content cards if the specific selectors found nothing
        if (results.isEmpty()) {
            doc.select("a[href]").filter { a ->
                val h = a.attr("href")
                (h.contains("/movie/") || h.contains("/tv-show/")) &&
                h.startsWith(mainUrl) && h != mainUrl
            }.forEach { a ->
                val href  = a.attr("href")
                val img   = a.selectFirst("img")
                val title = a.selectFirst("h3, h4, p, span, .title")?.text()?.trim()
                    ?: img?.attr("alt")?.trim()
                    ?: a.attr("title").trim()
                if (title.isBlank()) return@forEach
                val poster = NowHDTimeUtils.fixUrl(img?.attr("src")?.ifBlank { img.attr("data-src") } ?: "")
                val isTv   = href.contains("/tv-show/")
                val year   = NowHDTimeUtils.extractYear(title)
                if (isTv) {
                    results.add(newTvSeriesSearchResponse(NowHDTimeUtils.cleanTitle(title), href, TvType.TvSeries) {
                        this.posterUrl = poster; this.year = year
                    })
                } else {
                    results.add(newMovieSearchResponse(NowHDTimeUtils.cleanTitle(title), href, TvType.Movie) {
                        this.posterUrl = poster; this.year = year
                    })
                }
            }
        }

        return results.distinctBy { it.url }
    }

    /** Parse the /anime page — uses AniList ID based URLs */
    private fun parseAnimePage(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        doc.select("a[href*='/anime/']").filter { a ->
            val h = a.attr("href")
            h.matches(Regex(".*/anime/\\d+.*")) && !h.contains("/watch/")
        }.forEach { a ->
            val href  = a.attr("href").trimEnd('/')
            val img   = a.selectFirst("img")
            val title = a.selectFirst("h3, h4, p, .title, [class*=title]")?.text()?.trim()
                ?: img?.attr("alt")?.trim()
                ?: a.attr("title").trim()
                ?: a.text().trim()
            if (title.isBlank() || title.length < 2) return@forEach
            val poster = NowHDTimeUtils.fixUrl(img?.attr("src")?.ifBlank { img.attr("data-src") } ?: "")
            val year   = NowHDTimeUtils.extractYear(title)
            val ratingEl = a.parent()?.selectFirst("[class*=rating], [class*=score]")?.text()

            results.add(newAnimeSearchResponse(NowHDTimeUtils.cleanTitle(title), href, TvType.Anime) {
                this.posterUrl = poster
                this.year      = year
            })
        }
        return results.distinctBy { it.url }
    }

    /** Extract episode list from a TV show page. Episodes appear as clickable buttons/tabs. */
    private fun extractTvEpisodes(doc: Document, pageUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // Season tabs then episode list below
        // Structure: season selector + episode items with thumbnail + title + date
        var currentSeason = 1
        doc.select("[class*=season-tab], [class*=season-btn], li[data-season]").forEachIndexed { _, el ->
            val sText = el.text().trim()
            NowHDTimeUtils.extractSeasonNum(sText)?.let { currentSeason = it }
        }

        // Episode cards (img + title + links)
        doc.select("[class*=episode-item], [class*=ep-item], .episode-card").forEach { card ->
            val link   = card.selectFirst("a[href]")?.attr("href") ?: return@forEach
            val title  = card.selectFirst("h3, h4, .title, [class*=title]")?.text()?.trim()
                ?: card.selectFirst("a")?.attr("title")?.trim()
            val img    = card.selectFirst("img")?.attr("src")?.ifBlank { card.selectFirst("img")?.attr("data-src") }
            val epNum  = NowHDTimeUtils.extractEpNum(title ?: "")
                ?: NowHDTimeUtils.extractEpNum(link)
            val season = NowHDTimeUtils.extractSeasonNum(title ?: "")
                ?: NowHDTimeUtils.extractSeasonNum(link)
                ?: currentSeason
            val dateStr= card.selectFirst("time, .date, [class*=date]")?.attr("datetime")
                ?: card.selectFirst("time, .date, [class*=date]")?.text()

            episodes.add(newEpisode(link) {
                this.name        = title ?: "Episode ${epNum ?: episodes.size + 1}"
                this.season      = season
                this.episode     = epNum ?: episodes.size + 1
                this.posterUrl   = NowHDTimeUtils.fixUrl(img)
                this.date        = dateStr
            })
        }

        // Fallback: if specific selectors found nothing, look for links to episode pages
        if (episodes.isEmpty()) {
            doc.select("a[href*='episode'], a[href*='ep-']").filter { a ->
                val h = a.attr("href")
                h.startsWith(mainUrl) && h != pageUrl
            }.forEach { a ->
                val href  = a.attr("href")
                val text  = a.text().trim()
                val epNum = NowHDTimeUtils.extractEpNum(text) ?: NowHDTimeUtils.extractEpNum(href)
                val season= NowHDTimeUtils.extractSeasonNum(text) ?: NowHDTimeUtils.extractSeasonNum(href) ?: 1
                episodes.add(newEpisode(href) {
                    this.name    = text.ifBlank { "Episode ${epNum ?: episodes.size + 1}" }
                    this.season  = season
                    this.episode = epNum ?: episodes.size + 1
                })
            }
        }

        // Last resort: the page IS the episode (single episode movie-like)
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(pageUrl) {
                this.name = doc.selectFirst("h1")?.text()?.let { NowHDTimeUtils.cleanTitle(it) } ?: "Episode 1"
                this.season = 1
                this.episode = 1
            })
        }

        return episodes.distinctBy { "${it.season}-${it.episode}-${it.data}" }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /**
     * Extract anime episodes from an anime detail page.
     * The site lists numbered episode buttons linking to /anime/{id}/watch/{ep}.
     */
    private fun extractAnimeEpisodes(doc: Document, animeUrl: String, anilistId: Int?): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val idInUrl  = anilistId ?: Regex("/anime/(\\d+)").find(animeUrl)?.groupValues?.get(1)?.toIntOrNull()

        // Episode number buttons: <a href="/anime/182300/watch/1">1</a>
        doc.select("a[href*='/anime/'][href*='/watch/']").forEach { a ->
            val href  = a.attr("href")
            val epNum = Regex("/watch/(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: a.text().trim().toIntOrNull()
            if (epNum != null) {
                val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                episodes.add(newEpisode(fullHref) {
                    this.name    = "Episode $epNum"
                    this.season  = 1
                    this.episode = epNum
                })
            }
        }

        // If the episodes section is hidden / JS-rendered, fall back to total episode count
        if (episodes.isEmpty() && idInUrl != null) {
            val epCountText = doc.selectFirst("[class*=episode-count], .quick-info")?.text() ?: ""
            val total = Regex("(\\d+)\\s*(?:eps|episodes)", RegexOption.IGNORE_CASE)
                .find(epCountText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            for (i in 1..total) {
                episodes.add(newEpisode("$mainUrl/anime/$idInUrl/watch/$i") {
                    this.name    = "Episode $i"
                    this.season  = 1
                    this.episode = i
                })
            }
        }

        return episodes.distinctBy { "${it.season}-${it.episode}" }
            .sortedBy { it.episode }
    }

    /** Recommendations from "More Like This" section */
    private fun extractRecs(doc: Document): List<SearchResponse> {
        val recs = mutableListOf<SearchResponse>()
        doc.select("[class*=similar], [class*=related], [class*=recommend], .more-like-this").forEach { section ->
            section.select("a[href]").filter { a ->
                val h = a.attr("href")
                h.startsWith(mainUrl) && (h.contains("/movie/") || h.contains("/tv-show/") || h.contains("/anime/"))
            }.take(12).forEach { a ->
                val href  = a.attr("href")
                val img   = a.selectFirst("img")
                val title = a.selectFirst("h3, h4, p, .title")?.text()?.trim()
                    ?: img?.attr("alt")?.trim()
                    ?: a.attr("title").trim()
                if (title.isBlank()) return@forEach
                val poster= NowHDTimeUtils.fixUrl(img?.attr("src") ?: "")
                val year  = NowHDTimeUtils.extractYear(title)
                when {
                    href.contains("/anime/") ->
                        recs.add(newAnimeSearchResponse(NowHDTimeUtils.cleanTitle(title), href, TvType.Anime) { this.posterUrl = poster; this.year = year })
                    href.contains("/tv-show/") ->
                        recs.add(newTvSeriesSearchResponse(NowHDTimeUtils.cleanTitle(title), href, TvType.TvSeries) { this.posterUrl = poster; this.year = year })
                    else ->
                        recs.add(newMovieSearchResponse(NowHDTimeUtils.cleanTitle(title), href, TvType.Movie) { this.posterUrl = poster; this.year = year })
                }
            }
        }
        return recs.distinctBy { it.url }.take(12)
    }

    /** Anime-specific recommendations (related / sequel / prequel tiles) */
    private fun extractAnimeRecs(doc: Document): List<SearchResponse> {
        val recs = mutableListOf<SearchResponse>()
        doc.select("a[href*='/anime/']").filter { a ->
            val h = a.attr("href")
            h.matches(Regex(".*/anime/\\d+.*")) && !h.contains("/watch/")
        }.take(12).forEach { a ->
            val href  = a.attr("href")
            val img   = a.selectFirst("img")
            val title = a.selectFirst("h3, h4, p, .title")?.text()?.trim()
                ?: img?.attr("alt")?.trim()
                ?: a.attr("title").trim()
            if (title.isBlank() || title.length < 2) return@forEach
            val poster= NowHDTimeUtils.fixUrl(img?.attr("src") ?: "")
            val year  = NowHDTimeUtils.extractYear(title)
            recs.add(newAnimeSearchResponse(NowHDTimeUtils.cleanTitle(title), href, TvType.Anime) {
                this.posterUrl = poster; this.year = year
            })
        }
        return recs.distinctBy { it.url }.take(12)
    }

    // ── Metadata extraction helpers ───────────────────────────────────────────

    private fun bestImage(doc: Document): String? {
        // Prefer backdrop/banner first, then poster
        val candidates = listOf(
            doc.selectFirst("meta[property='og:image']")?.attr("content"),
            doc.selectFirst(".backdrop img, .banner img, .hero-image img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            },
            doc.selectFirst(".poster img, .cover img, [class*=poster] img, [class*=cover] img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            },
            doc.selectFirst("article img, .content img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }
        )
        for (c in candidates) {
            val url = NowHDTimeUtils.fixUrl(c)
            if (!url.isNullOrBlank()) return url
        }
        return null
    }

    private fun extractPlot(doc: Document): String? {
        // Check structured plot elements first
        for (sel in listOf(".synopsis p, .overview p, .description p, .plot p",
                           ".synopsis, .overview, .description, .plot",
                           "meta[name='description']", "meta[property='og:description']")) {
            val el = doc.selectFirst(sel) ?: continue
            val text = if (sel.startsWith("meta")) el.attr("content") else el.text()
            if (text.length > 30) return text.trim()
        }
        // Last resort: first substantial <p>
        doc.select("p").forEach { p ->
            val t = p.text().trim()
            if (t.length > 80 && !t.lowercase().contains("download") && !t.contains("©")) return t
        }
        return null
    }

    private fun extractYear(doc: Document): Int? {
        val yearEl = doc.selectFirst("[class*=year], time[datetime], .date, .release-date")
        NowHDTimeUtils.extractYear(yearEl?.text() ?: yearEl?.attr("datetime"))?.let { return it }
        // og:description or title often carries the year
        val meta = doc.selectFirst("meta[name='keywords']")?.attr("content")
        return NowHDTimeUtils.extractYear(meta)
    }

    private fun extractRating(doc: Document): Int? {
        for (sel in listOf(".score, .rating, [class*=score], [class*=rating], .imdb-rating")) {
            val t = doc.selectFirst(sel)?.text()
            NowHDTimeUtils.extractRating(t)?.let { return it }
        }
        return null
    }

    private fun extractDuration(doc: Document): Int? {
        for (sel in listOf("[class*=duration], [class*=runtime], time")) {
            val t = doc.selectFirst(sel)?.text()
            NowHDTimeUtils.parseDuration(t)?.let { return it }
        }
        // title like "2h 3m" or "123 min" in metadata rows
        doc.select("span, li, p").forEach { el ->
            val t = el.text()
            if (t.contains("min", ignoreCase = true) || t.contains(" h ", ignoreCase = true)) {
                NowHDTimeUtils.parseDuration(t)?.let { return it }
            }
        }
        return null
    }

    private fun extractGenres(doc: Document): List<String> {
        val genres = mutableListOf<String>()
        doc.select("a[href*='/genre/'], a[href*='/category/'], .genre a, .genres a").forEach {
            val t = it.text().trim()
            if (t.isNotBlank()) genres.add(t)
        }
        // Also try "Comedy · Drama" type separators
        doc.selectFirst("[class*=genre], [class*=genres]")?.text()
            ?.split("·", ",", "/", "|")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.forEach { genres.add(it) }
        return genres.distinct().take(8)
    }

    private fun extractTrailer(doc: Document): String? {
        // YouTube embed
        doc.selectFirst("iframe[src*='youtube'], iframe[src*='youtu.be']")?.attr("src")?.let {
            return if (it.startsWith("//")) "https:$it" else it
        }
        // YouTube ID in JS or thumbnail
        Regex("""(?:youtube\.com/embed/|youtu\.be/)([A-Za-z0-9_-]{11})""")
            .find(doc.html())?.groupValues?.get(1)?.let { return "https://www.youtube.com/watch?v=$it" }
        return null
    }

    // ── Pagination ────────────────────────────────────────────────────────────
    private fun hasNext(doc: Document, page: Int): Boolean {
        // Check for explicit next button
        if (doc.selectFirst("a.next, .next a, .pagination .next, [rel='next'], li.next a") != null) return true
        // If a page was fetched and returned items, assume there might be more (up to page 20)
        return page < 20
    }

    private fun isDirectVideo(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(".mp4", ".mkv", ".avi", ".m3u8", ".mpd", ".webm", ".mov", ".ts").any { lower.contains(it) }
    }
}
