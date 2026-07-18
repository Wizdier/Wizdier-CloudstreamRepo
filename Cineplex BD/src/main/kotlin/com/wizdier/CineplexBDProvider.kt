package com.wizdier

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
        val doc = app.get(url, headers = cfHeaders, timeout = 10_000).document

        val items = parseAndGroupSearchItems(doc)
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
        val doc = app.get(url, headers = cfHeaders, timeout = 10_000).document
        return parseAndGroupSearchItems(doc)
    }

    // ----------------------------- Load ----------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val groupedItems = decodeGroupedItems(url)
        val primaryItem = groupedItems?.firstOrNull() ?: GroupedLoadItem(url = url, label = null)
        val allLoadItems = groupedItems ?: listOf(primaryItem)
        val primaryUrl = primaryItem.url
        val absUrl = if (primaryUrl.startsWith("http")) primaryUrl else mainUrl + primaryUrl
        val doc = app.get(absUrl, headers = cfHeaders, timeout = 10_000).document

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

        val scrapedYear = doc.select("span.chip:matches(\\d{4})").text()
            .let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        val scrapedDuration = doc.select("span.chip:matches(\\d+h \\d+m)").text()
            .let { parseDuration(it) }

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
        val actorsList = buildList {
            if (director.isNotBlank()) add(ActorData(Actor(director, null), roleString = "Director"))
            meta.actors.forEach { actor ->
                add(ActorData(Actor(actor.name, actor.imageUrl), roleString = actor.role ?: ""))
            }
        }.distinctBy { it.actor.name }

        val movieType = if (looksLikeAnime || meta.isAnimeHint) TvType.AnimeMovie else TvType.Movie
        val seriesType = if (looksLikeAnime || meta.isAnimeHint) TvType.Anime else TvType.TvSeries

        return if (!isSeries) {
            val playerLinks = allLoadItems.mapNotNull { item ->
                val absolute = if (item.url.startsWith("http")) item.url else mainUrl + item.url
                val id = absolute.substringAfter("id=", "").substringBefore("&")
                    .takeIf { it.isNotBlank() && it != absolute }
                id?.let { LinkItem(url = "/player.php?id=$it", label = item.label) }
            }.distinctBy { it.url }
            val dataUrl = if (playerLinks.size > 1) {
                encodeLinkGroupItems(playerLinks)
            } else {
                playerLinks.firstOrNull()?.url ?: absUrl
            }
            newMovieLoadResponse(finalTitle, absUrl, movieType, dataUrl) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.plot = finalPlot
                this.year = finalYear
                this.duration = scrapedDuration
                this.tags = finalTags
                runCatching { finalRating?.let { this.score = Score.from10(it) } }
                if (actorsList.isNotEmpty()) this.actors = actorsList
                runCatching { if (recommendations.isNotEmpty()) this.recommendations = recommendations }
                runCatching { finalLogo?.let { this.logoUrl = it } }
                runCatching { finalTrailer?.let { addTrailer(it) } }
                runCatching { meta.imdbId?.let { addImdbId(it) } }
                runCatching { meta.simklId?.let { addSimklId(it) } }
                runCatching { meta.malId?.let { addMalId(it) } }
                runCatching { meta.anilistId?.let { addAniListId(it) } }
                runCatching { meta.kitsuId?.let { addKitsuId(it) } }
            }
        } else {
            val episodes = collectEpisodesForGroup(allLoadItems, absUrl, doc, meta)

            newTvSeriesLoadResponse(finalTitle, absUrl, seriesType, episodes) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.plot = finalPlot
                this.year = finalYear
                this.tags = finalTags
                runCatching { finalRating?.let { this.score = Score.from10(it) } }
                if (actorsList.isNotEmpty()) this.actors = actorsList
                runCatching { if (recommendations.isNotEmpty()) this.recommendations = recommendations }
                runCatching { finalLogo?.let { this.logoUrl = it } }
                runCatching { finalTrailer?.let { addTrailer(it) } }
                runCatching { meta.imdbId?.let { addImdbId(it) } }
                runCatching { meta.simklId?.let { addSimklId(it) } }
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
    private data class EpisodeWithLabel(
        val episode: Episode,
        val label: String?,
    )

    private suspend fun collectEpisodesForGroup(
        items: List<GroupedLoadItem>,
        primaryAbsUrl: String,
        primaryDoc: Document,
        meta: MetadataEnricher.MetaInfo,
    ): List<Episode> {
        // Fetch each source's page concurrently.
        val all = coroutineScope {
            items.distinctBy { it.url }.mapIndexed { index, item ->
                async {
                    val abs = if (item.url.startsWith("http")) item.url else mainUrl + item.url
                    val doc = if (index == 0 && abs == primaryAbsUrl) primaryDoc
                    else runCatching { app.get(abs, headers = cfHeaders, timeout = 10_000).document }.getOrNull()
                    if (doc == null) return@async emptyList()
                    val seriesId = parseSeriesId(abs)
                    collectEpisodes(seriesId, abs, doc, meta).map { ep -> EpisodeWithLabel(ep, item.label) }
                }
            }.awaitAll().flatten()
        }.toMutableList()
        if (all.isEmpty()) return listOf(
            newEpisode(primaryAbsUrl) {
                name = "Watch"
                season = 1
                episode = 1
            }
        )

        return all.groupBy { (it.episode.season ?: 1) to (it.episode.episode ?: 1) }
            .map { (key, eps) ->
                val first = eps.first().episode
                val linkItems = eps.map { LinkItem(url = it.episode.data, label = it.label) }
                    .distinctBy { it.url }
                val data = if (linkItems.size > 1) encodeLinkGroupItems(linkItems) else first.data
                newEpisode(data) {
                    name = first.name
                    season = key.first
                    episode = key.second
                    posterUrl = first.posterUrl
                    description = first.description
                }
            }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 1 }))
    }

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
            val text = runCatching { app.get(metaUrl, headers = cfHeaders, timeout = 10_000).text }.getOrNull()
                ?: continue
            episodes += parseEpisodesFromMetaJson(text, seasonInt, meta.episodes)
        }

        // If the JSON path returned at least one episode, we're done.
        if (episodes.isNotEmpty()) {
            return episodes.dedupAndSort()
        }

        // ── (3-extra) numeric fallback when the page exposes no season UI ─
        // Fire all 12 candidate seasons concurrently (bounded by
        // parallelMapNotNull's internal semaphore). The previous code did
        // these sequentially, so a missing-season page could cost up to 12
        // serial round-trips. Now they share one burst of latency.
        val numericSeasonResults = CineplexConcurrent.parallelMapNotNull((1..12).toList()) { s ->
            val metaUrl = "$mainUrl/watch.php?$seriesIdKey=$seriesId&season=$s&meta=1"
            val text = runCatching { app.get(metaUrl, headers = cfHeaders, timeout = 10_000).text }.getOrNull()
                ?: return@parallelMapNotNull null
            val parsed = parseEpisodesFromMetaJson(text, s, meta.episodes)
            if (parsed.isEmpty()) null else s to parsed
        }
        for ((_, eps) in numericSeasonResults) episodes += eps
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
    ): Boolean = loadLinksWithLabel(data, null, isCasting, subtitleCallback, callback)

    private suspend fun loadLinksWithLabel(
        data: String,
        label: String?,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        decodeLinkGroup(data)?.let { links ->
            var any = false
            links.distinctBy { it.url }.forEach { link ->
                val mergedLabel = link.label ?: label
                if (loadLinksWithLabel(link.url, mergedLabel, isCasting, subtitleCallback, callback)) any = true
            }
            return any
        }

        val url = when {
            data.startsWith("http") -> data
            data.startsWith("/") -> mainUrl + data
            else -> "$mainUrl/$data"
        }

        val explicitSourceName = buildStreamSourceName(label, url)
        if (url.contains(".m3u8", true)) {
            collectM3u8Subtitles(url, "$mainUrl/", subtitleCallback)
            M3u8Helper.generateM3u8(
                source = explicitSourceName,
                streamUrl = url,
                referer = "$mainUrl/",
                headers = cfHeaders
            ).forEach(callback)
            return true
        }
        if (url.endsWith(".mp4", true) || url.endsWith(".mkv", true) || url.contains("/Data/")) {
            callback.invoke(
                newExtractorLink(
                    source = explicitSourceName,
                    name = "$explicitSourceName - Direct",
                    url = url,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(url)
                }
            )
            return true
        }

        val html = app.get(url, headers = cfHeaders, timeout = 15_000).text
        val sources = linkedSetOf<String>()

        Regex("""const\s+videoSrc\s*=\s*["'](.*?)["']""")
            .find(html)?.groupValues?.getOrNull(1)?.let(sources::add)

        val parsed = org.jsoup.Jsoup.parse(html, url)
        collectSubtitles(parsed, html, url, subtitleCallback)

        parsed.select("video[src], source[src], a[href*='.m3u8'], a[href*='.mp4'], a[href*='.mkv']")
            .forEach { el ->
                val src = el.attr("src").ifBlank { el.attr("href") }
                if (src.isNotBlank()) sources += src
            }

        Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv|\.webm|\.avi|\.m4v)(?:/index\.m3u8|\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapTo(sources) { it.value.replace("&amp;", "&") }

        var found = false
        sources.map { it.toAbsoluteMediaUrl(url) }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { finalUrl ->
                val sourceName = buildStreamSourceName(label, finalUrl, url)
                when {
                    finalUrl.contains(".m3u8", ignoreCase = true) -> {
                        collectM3u8Subtitles(finalUrl, url, subtitleCallback)
                        M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = finalUrl,
                            referer = url,
                            headers = cfHeaders
                        ).forEach(callback)
                        found = true
                    }
                    finalUrl.isDirectVideoUrl() || finalUrl.contains("/Data/", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                source = sourceName,
                                name = "$sourceName - Direct",
                                url = finalUrl,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = url
                                this.quality = getQualityFromName(finalUrl)
                            }
                        )
                        found = true
                    }
                    else -> {
                        runCatching {
                            loadExtractor(finalUrl, url, subtitleCallback) {
                                callback(it)
                                found = true
                            }
                        }
                    }
                }
            }

        // Current movie pages are Quetta-backed and only expose a
        // data-quetta-video-id. The official download endpoint is the stable
        // media redirect; player.php itself is never emitted as a video URL.
        if (!found && url.contains("player.php")) {
            val id = url.substringAfter("id=", "").substringBefore("&")
            if (id.isNotBlank()) {
                val downloadUrl = "$mainUrl/download.php?id=$id"
                callback.invoke(newExtractorLink(
                    source = explicitSourceName,
                    name = "$explicitSourceName - Download",
                    url = downloadUrl,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = url
                    this.quality = getQualityFromName(downloadUrl)
                })
                found = true
            }
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

    private data class SearchItem(
        val title: String,
        val url: String,
        val type: TvType,
        val poster: String?,
        val year: Int?,
        val sourceLabel: String?,
    )

    private data class GroupedLoadItem(
        val url: String,
        val label: String?,
    )

    private data class LinkItem(
        val url: String,
        val label: String? = null,
    )

    private fun parseAndGroupSearchItems(doc: Document): List<SearchResponse> {
        val items = doc.select(
            "a[href*='view.php'], a[href*='watch.php'], a[href*='tview.php'], " +
                    ".movie-card a, a:has(.poster), a:has(img[src*='uploads'])"
        ).mapNotNull { it.toSearchItem() }
            .distinctBy { it.url }

        return items.groupBy { item ->
            val normalized = item.title.normalizedTitleKey()
            val year = item.year
            // Only merge when BOTH normalized name and year are common. If the
            // year is missing, keep the item separate to avoid false merges.
            if (year != null) "$normalized|$year|${item.type}" else "$normalized|${item.url}|${item.type}"
        }.values.mapNotNull { group ->
            val first = group.firstOrNull() ?: return@mapNotNull null
            val groupUrl = if (group.size > 1) encodeGroupedItems(group) else first.url
            if (first.type == TvType.TvSeries) {
                newTvSeriesSearchResponse(first.title, groupUrl, TvType.TvSeries) {
                    posterUrl = first.poster
                    year = first.year
                }
            } else {
                newMovieSearchResponse(first.title, groupUrl, first.type) {
                    posterUrl = first.poster
                    year = first.year
                }
            }
        }
    }

    private fun Element.toSearchItem(): SearchItem? {
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

        val cardText = text() + " " + title + " " + href
        val year = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
            .find(cardText)?.value?.toIntOrNull()
        val sourceLabel = buildSourceLabel(cardText)

        return SearchItem(
            title = title.cleanDisplayTitle(),
            url = href,
            type = type,
            poster = poster,
            year = year,
            sourceLabel = sourceLabel,
        )
    }

    private fun encodeGroupedItems(items: List<SearchItem>): String {
        val arr = JSONArray()
        val seenLabels = mutableMapOf<String, Int>()
        items.distinctBy { it.url }.forEachIndexed { index, item ->
            val baseLabel = item.sourceLabel ?: buildSourceLabel(item.url) ?: "Source ${index + 1}"
            val seen = (seenLabels[baseLabel] ?: 0) + 1
            seenLabels[baseLabel] = seen
            val label = if (seen > 1) "$baseLabel #$seen" else baseLabel
            arr.put(JSONObject().apply {
                put("url", item.url)
                put("label", label)
            })
        }
        val encoded = Base64.encodeToString(arr.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "load?data=$encoded"
    }

    private fun decodeGroupedItems(url: String): List<GroupedLoadItem>? = runCatching {
        if (!url.contains("load?data=")) return@runCatching null
        val data = url.substringAfter("load?data=").substringBefore("&")
        val arr = JSONArray(String(Base64.decode(data, Base64.URL_SAFE)))
        (0 until arr.length()).mapNotNull { i ->
            val raw = arr.opt(i)
            when (raw) {
                is JSONObject -> raw.optStringOrNull("url")?.let { GroupedLoadItem(it, raw.optStringOrNull("label")) }
                is String -> raw.takeIf { it.isNotBlank() }?.let { GroupedLoadItem(it, null) }
                else -> null
            }
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun encodeLinkGroupItems(links: List<LinkItem>): String {
        val arr = JSONArray()
        val seenLabels = mutableMapOf<String, Int>()
        links.distinctBy { it.url }.forEachIndexed { index, link ->
            val baseLabel = link.label ?: buildSourceLabel(link.url) ?: "Source ${index + 1}"
            val seen = (seenLabels[baseLabel] ?: 0) + 1
            seenLabels[baseLabel] = seen
            val label = if (seen > 1) "$baseLabel #$seen" else baseLabel
            arr.put(JSONObject().apply {
                put("url", link.url)
                put("label", label)
            })
        }
        val encoded = Base64.encodeToString(arr.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "cineplexbd://links?data=$encoded"
    }

    private fun decodeLinkGroup(data: String): List<LinkItem>? = runCatching {
        if (!data.startsWith("cineplexbd://links?data=")) return@runCatching null
        val encoded = data.substringAfter("cineplexbd://links?data=")
        val arr = JSONArray(String(Base64.decode(encoded, Base64.URL_SAFE)))
        (0 until arr.length()).mapNotNull { i ->
            val raw = arr.opt(i)
            when (raw) {
                is JSONObject -> raw.optStringOrNull("url")?.let { LinkItem(it, raw.optStringOrNull("label")) }
                is String -> raw.takeIf { it.isNotBlank() }?.let { LinkItem(it, null) }
                else -> null
            }
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun buildStreamSourceName(label: String?, vararg hints: String?): String {
        val cleanLabel = label?.cleanSourceLabel()?.takeIf { it.isNotBlank() }
        val derivedLabel = buildSourceLabel(hints.filterNotNull().joinToString(" "))
        val finalLabel = when {
            cleanLabel == null -> derivedLabel
            derivedLabel == null -> cleanLabel
            cleanLabel.contains(derivedLabel, ignoreCase = true) -> cleanLabel
            derivedLabel.contains(cleanLabel, ignoreCase = true) -> derivedLabel
            else -> "$derivedLabel $cleanLabel".cleanSourceLabel()
        }
        return finalLabel?.let { "$name • $it" } ?: name
    }

    private suspend fun collectM3u8Subtitles(
        manifestUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val manifestHeaders = cfHeaders + ("Referer" to referer)
        val manifest = runCatching { app.get(manifestUrl, headers = manifestHeaders, timeout = 10_000).text }.getOrNull()
            ?: return
        Regex("""#EXT-X-MEDIA:([^\r\n]+)""", RegexOption.IGNORE_CASE)
            .findAll(manifest)
            .forEach { match ->
                val attrs = match.groupValues[1]
                if (!attrs.contains("TYPE=SUBTITLES", ignoreCase = true)) return@forEach
                val rawUri = parseM3u8Attribute(attrs, "URI") ?: return@forEach
                val subUrl = rawUri.toAbsoluteMediaUrl(manifestUrl)
                val label = parseM3u8Attribute(attrs, "NAME")
                    ?: parseM3u8Attribute(attrs, "LANGUAGE")
                    ?: subtitleLabelFromUrl(subUrl)
                subtitleCallback(newSubtitleFile(label, subUrl))
            }
    }

    private fun parseM3u8Attribute(attrs: String, key: String): String? =
        Regex("""$key=(?:"([^"]*)"|([^,]*))""", RegexOption.IGNORE_CASE)
            .find(attrs)
            ?.let { match ->
                match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: match.groupValues.getOrNull(2)
            }
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private suspend fun collectSubtitles(
        parsed: Document,
        html: String,
        baseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val seen = linkedSetOf<String>()

        parsed.select("track[src], a[href*='.srt'], a[href*='.vtt'], a[href*='.ass']")
            .forEach { el ->
                val raw = el.attr("src").ifBlank { el.attr("href") }.ifBlank { return@forEach }
                val subUrl = raw.toAbsoluteMediaUrl(baseUrl)
                if (subUrl.isBlank() || !seen.add(subUrl)) return@forEach
                val label = el.attr("label")
                    .ifBlank { el.attr("srclang") }
                    .ifBlank { el.text() }
                    .ifBlank { subtitleLabelFromUrl(subUrl) }
                subtitleCallback(newSubtitleFile(label, subUrl))
            }

        Regex("""https?://[^\s"'<>]+\.(?:srt|vtt|ass)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .forEach { raw ->
                val subUrl = raw.toAbsoluteMediaUrl(baseUrl)
                if (subUrl.isNotBlank() && seen.add(subUrl)) {
                    subtitleCallback(newSubtitleFile(subtitleLabelFromUrl(subUrl), subUrl))
                }
            }

        Regex("""["']([^"']+\.(?:srt|vtt|ass)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1].replace("&amp;", "&") }
            .forEach { raw ->
                val subUrl = raw.toAbsoluteMediaUrl(baseUrl)
                if (subUrl.isNotBlank() && seen.add(subUrl)) {
                    subtitleCallback(newSubtitleFile(subtitleLabelFromUrl(subUrl), subUrl))
                }
            }
    }

    private fun subtitleLabelFromUrl(url: String): String {
        val file = url.substringBefore("?").substringAfterLast('/').replace("%20", " ")
        return when {
            file.contains("bangla", true) || file.contains("bengali", true) || file.contains("ben", true) -> "Bangla"
            file.contains("english", true) || file.contains("eng", true) -> "English"
            file.contains("hindi", true) || file.contains("hin", true) -> "Hindi"
            else -> "Subtitle"
        }
    }

    private fun buildSourceLabel(vararg hints: String?): String? {
        val raw = hints.filterNotNull().joinToString(" ")
            .replace("%20", " ")
            .replace("_", " ")
            .replace("-", " ")
        if (raw.isBlank()) return null

        fun has(pattern: String): Boolean = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val parts = linkedSetOf<String>()

        // Keep names short: only the source's own quality/variant initials, not
        // every category/language text that exists elsewhere on the page.
        if (has("\\b3d\\b")) parts += "3D"
        if (has("\\b(?:4k|2160p|uhd)\\b")) {
            parts += "4K"
        } else {
            Regex("(?i)\\b(1080|720|576|540|480|360)p\\b")
                .findAll(raw)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull()
                ?.let { parts += "${it}p" }
        }

        if (parts.isEmpty() && has("\\bHD\\b")) parts += "HD"

        // Only add release/codec initials when no resolution was found. This
        // keeps the source row compact while still differentiating WEB-DL-only
        // or BluRay-only entries.
        if (parts.isEmpty()) {
            listOf(
                "WEB-DL" to "(?i)\\bweb[- ]?dl\\b",
                "WEBRip" to "(?i)\\bwebrip\\b",
                "BluRay" to "(?i)\\b(?:bluray|blu ray|brrip)\\b",
                "HDRip" to "(?i)\\bhdrip\\b",
                "HEVC" to "(?i)\\b(?:hevc|x265|h265)\\b",
                "10bit" to "(?i)\\b10[- ]?bit\\b",
            ).firstOrNull { (_, pattern) -> has(pattern) }?.let { (short, _) -> parts += short }
        }

        return parts.joinToString(" ").cleanSourceLabel().takeIf { it.isNotBlank() }
    }

    private fun String.cleanSourceLabel(): String =
        replace(Regex("(?i)\\b(?:web series|movies?|series|tv)\\b"), " ")
            .replace(Regex("\\s*/\\s*"), "/")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '/', '-', '•')

    private fun String.normalizedTitleKey(): String = cleanDisplayTitle()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun String.cleanDisplayTitle(): String =
        replace(Regex("(?i)\\b(1080p|720p|480p|2160p|4k|web[- ]?dl|webrip|bluray|hdrip|x264|x265|hevc|10bit|dual[- ]?audio|hindi[- ]?dubbed|dubbed|esub)\\b"), " ")
            .replace(Regex("\\[[^]]+]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.toAbsoluteMediaUrl(baseUrl: String = mainUrl): String {
        val value = trim().replace("\\/", "/").replace("&amp;", "&")
        if (value.isBlank()) return value
        if (value.startsWith("//")) return baseUrl.substringBefore("://", "http") + ":$value"
        if (value.startsWith("http", ignoreCase = true)) return value
        // Resolve against the document URL rather than string-appending. This
        // is essential for movie player.php?id=… pages with relative sources.
        return runCatching { java.net.URI(baseUrl).resolve(value).toString() }
            .getOrElse { if (value.startsWith("/")) mainUrl + value else mainUrl + "/" + value }
    }

    private fun String.isDirectVideoUrl(): Boolean {
        val clean = substringBefore("?").lowercase()
        return clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm") ||
                clean.endsWith(".avi") || clean.endsWith(".m4v") || clean.endsWith(".mov")
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
