package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

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

    override val supportedSyncNames = setOfNotNull(
        runCatching { SyncIdName.valueOf("Simkl") }.getOrNull(),
    )

    companion object {
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p"

        // TTL cache for TMDB metadata so repeat loads return instantly.
        private val tmdbMetaCache = FTPBDConcurrent.TtlCache<String, TmdbMeta>(ttlMs = 10 * 60 * 1000L)
    }

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

    private data class FtpSearchItem(
        val title: String,
        val url: String,
        val type: TvType,
        val poster: String?,
        val year: Int?,
        val sourceLabel: String?,
    )

    private data class GroupedFtpItem(
        val url: String,
        val label: String?,
    )

    private data class LinkItem(
        val url: String,
        val label: String? = null,
    )

    private data class EpisodeWithLabel(
        val episode: Episode,
        val label: String?,
    )

    private data class TmdbEpisodeMeta(
        val name: String? = null,
        val overview: String? = null,
        val stillUrl: String? = null,
        val rating: Double? = null,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val doc = app.get(url, headers = headers, timeout = 10_000).document
        val expected = if (request.data == "tv_shows") "/tv_shows/" else "/movie/"
        val items = parseCards(doc, expected)
        val hasNext = doc.select("a.next, .page-numbers.next, a[href*='/page/${page + 1}/']").isNotEmpty() || items.isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = q.encodeUrl()
        // Search movies and TV concurrently — halves search latency.
        val items = coroutineScope {
            val movies = async {
                runCatching {
                    parseCardItems(app.get("$mainUrl/?s=$encoded&post_type=movies", headers = headers, timeout = 10_000).document, "/movie/")
                }.getOrDefault(emptyList())
            }
            val tv = async {
                runCatching {
                    parseCardItems(app.get("$mainUrl/?s=$encoded&post_type=tv_shows", headers = headers, timeout = 10_000).document, "/tv_shows/")
                }.getOrDefault(emptyList())
            }
            movies.await() + tv.await()
        }
        return groupSearchItems(items)
    }

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        if (!name.name.equals("Simkl", ignoreCase = true)) return null
        val syncId = id.trim().removeSuffix("/").substringAfterLast("/").substringBefore("?")
        if (syncId.isBlank()) return null
        val simkl = fetchSimklDetails(syncId) ?: return null
        val title = simkl.optStringOrNull("title") ?: return null
        val year = simkl.optIntOrNull("year")
        val type = simkl.optStringOrNull("type")?.lowercase().orEmpty()
        val order = if (type == "movie") listOf("/movie/" to "movies") else listOf("/tv_shows/" to "tv_shows", "/movie/" to "movies")
        for ((path, postType) in order) {
            findFtpbdUrlByTitle(title, year, path, postType)?.let { return it }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        decodeGroupedItems(url)?.let { grouped ->
            return loadGrouped(url, grouped)
        }

        val fixedUrl = url.toAbsoluteUrl()
        val doc = app.get(fixedUrl, headers = headers, timeout = 10_000).document
        return when {
            fixedUrl.contains("/tv_shows/") -> loadSeries(fixedUrl, doc)
            fixedUrl.contains("/episodes/") -> loadEpisodeAsMovie(fixedUrl, doc)
            else -> loadMovie(fixedUrl, doc)
        }
    }

    private suspend fun loadGrouped(url: String, items: List<GroupedFtpItem>): LoadResponse? {
        val loaded = items.distinctBy { it.url }.mapNotNull { item ->
            val fixed = item.url.toAbsoluteUrl()
            val doc = runCatching { app.get(fixed, headers = headers).document }.getOrNull()
            doc?.let { Triple(fixed, it, item.label) }
        }
        if (loaded.isEmpty()) return null
        val primaryUrl = loaded.first().first
        return when {
            primaryUrl.contains("/tv_shows/") -> loadGroupedSeries(primaryUrl, loaded)
            primaryUrl.contains("/episodes/") -> loadEpisodeAsMovie(primaryUrl, loaded.first().second)
            else -> loadGroupedMovie(primaryUrl, loaded)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        decodeLinkGroup(data)?.let { links ->
            var any = false
            links.distinctBy { it.url }.forEach { link ->
                if (loadLinksFromPage(link.url, link.label, subtitleCallback, callback)) any = true
            }
            return any
        }
        return loadLinksFromPage(data, null, subtitleCallback, callback)
    }

    private suspend fun loadLinksFromPage(
        data: String,
        label: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sourcePage = data.toAbsoluteUrl()
        if (sourcePage.contains(".m3u8", true)) {
            val display = buildShortSourceName(label, sourcePage, 1)
            collectM3u8Subtitles(sourcePage, mainUrl, subtitleCallback)
            M3u8Helper.generateM3u8(
                source = display,
                streamUrl = sourcePage,
                referer = mainUrl,
                headers = headers
            ).forEach(callback)
            return true
        }
        if (sourcePage.looksLikeMedia()) {
            val display = buildShortSourceName(label, sourcePage, 1)
            callback(
                newExtractorLink(
                    source = display,
                    name = "$display - Direct",
                    url = sourcePage,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    referer = mainUrl
                    quality = getQualityFromName(display)
                }
            )
            return true
        }

        val html = runCatching { app.get(sourcePage, headers = headers, timeout = 15_000).text }.getOrNull() ?: return false
        val urls = linkedSetOf<String>()
        val parsed = Jsoup.parse(html, sourcePage)
        collectSubtitles(parsed, html, sourcePage, subtitleCallback)

        // HLS player sources used by the site.
        Regex("""(?i)<source[^>]+src=["']([^"']+?\.m3u8[^"']*)["']""")
            .findAll(html)
            .mapTo(urls) { it.groupValues[1].htmlDecode().toAbsoluteUrl(sourcePage) }
        Regex("""(?i)hls\.loadSource\(["']([^"']+?\.m3u8[^"']*)["']\)""")
            .findAll(html)
            .mapTo(urls) { it.groupValues[1].htmlDecode().toAbsoluteUrl(sourcePage) }

        // Direct download links are normally hidden in data-url attributes.
        Regex("""(?i)data-url=["']([^"']+)["']""")
            .findAll(html)
            .map { it.groupValues[1].htmlDecode() }
            .filter { it.looksLikeMedia() }
            .mapTo(urls) { it.toAbsoluteUrl(sourcePage) }

        parsed.select("video[src], source[src], a[href*='.m3u8'], a[href*='.mp4'], a[href*='.mkv']")
            .forEach { el ->
                val src = el.attr("src").ifBlank { el.attr("href") }
                if (src.isNotBlank() && src.looksLikeMedia()) urls += src.toAbsoluteUrl(sourcePage)
            }

        // Extra fallback for any direct media URL in page scripts/HTML.
        Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv|\.webm|\.avi|\.mov|\.m4v)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapTo(urls) { it.value.htmlDecode().toAbsoluteUrl(sourcePage) }

        var found = false
        val seenNames = mutableMapOf<String, Int>()
        urls.distinct().forEachIndexed { index, link ->
            val clean = link.trim()
            if (clean.isBlank()) return@forEachIndexed
            val baseDisplay = buildShortSourceName(label, clean, index + 1)
            val count = (seenNames[baseDisplay] ?: 0) + 1
            seenNames[baseDisplay] = count
            val display = if (count > 1) "$baseDisplay #$count" else baseDisplay
            val quality = getQualityFromName(display)

            if (clean.contains(".m3u8", ignoreCase = true)) {
                collectM3u8Subtitles(clean, sourcePage, subtitleCallback)
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
                        source = display,
                        name = "$display - Direct",
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

    // ───────────────────────── TMDB / Simkl metadata ─────────────────────────

    private data class TmdbMeta(
        val plot: String? = null,
        val backdropUrl: String? = null,
        val logoUrl: String? = null,
        val year: Int? = null,
        val runtime: Int? = null,
        val tags: List<String>? = null,
        val rating: Double? = null,
        val trailerUrl: String? = null,
        val imdbId: String? = null,
        val simklId: Int? = null,
        val actors: List<ActorData>? = null,
        val episodesBySeason: Map<Int, List<TmdbEpisodeMeta>> = emptyMap(),
    )

    private suspend fun fetchTmdbMeta(
        title: String,
        mediaType: String,
        siteYear: Int? = null,
        seasonHints: Set<Int> = emptySet(),
    ): TmdbMeta {
        val tmdbId = findTmdbId(title.cleanMediaTitle(), mediaType, siteYear) ?: return TmdbMeta()

        val cacheKey = "$mediaType|$tmdbId|${siteYear}|${seasonHints.sorted()}"
        tmdbMetaCache.get(cacheKey)?.let { return it }

        val detail = tmdbJson(
            "/$mediaType/$tmdbId",
            mapOf(
                "append_to_response" to "credits,external_ids,images,videos",
                "include_image_language" to "en,null"
            )
        ) ?: return TmdbMeta()

        val date = detail.optStringOrNull(if (mediaType == "movie") "release_date" else "first_air_date")
        val runtime = if (mediaType == "movie") {
            detail.optIntOrNull("runtime")
        } else {
            detail.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }
        }
        val imdbId = detail.optJSONObject("external_ids")?.optStringOrNull("imdb_id")
            ?: detail.optStringOrNull("imdb_id")
        val logo = detail.optJSONObject("images")
            ?.optJSONArray("logos")
            ?.bestTmdbImage("file_path", preferLogo = true)
            ?.let { tmdbImg("w500", it) }
        val actors = detail.optJSONObject("credits")?.optJSONArray("cast")?.toActors()
        val targetSeasons = seasonHints.takeIf { it.isNotEmpty() } ?: setOf(1)
        val episodesBySeason = if (mediaType == "tv") {
            val map = linkedMapOf<Int, List<TmdbEpisodeMeta>>()
            for (season in targetSeasons) {
                map[season] = fetchTmdbSeasonEpisodes(tmdbId, season)
            }
            map
        } else emptyMap()

        val result = TmdbMeta(
            plot = detail.optStringOrNull("overview"),
            backdropUrl = detail.optStringOrNull("backdrop_path")?.let { tmdbImg("w1280", it) },
            logoUrl = logo ?: imdbId?.let { "https://live.metahub.space/logo/medium/$it/img" },
            year = yearFromDate(date),
            runtime = runtime,
            tags = detail.optJSONArray("genres")?.toStringList("name"),
            rating = detail.optDoubleOrNull("vote_average"),
            trailerUrl = detail.optJSONObject("videos")?.optJSONArray("results")?.bestYoutubeTrailer(),
            imdbId = imdbId,
            simklId = fetchSimklId(imdbId, mediaType),
            actors = actors,
            episodesBySeason = episodesBySeason,
        )
        tmdbMetaCache.put(cacheKey, result)
        return result
    }

    private suspend fun fetchTmdbSeasonEpisodes(tmdbId: Int, season: Int): List<TmdbEpisodeMeta> {
        val root = tmdbJson("/tv/$tmdbId/season/$season") ?: return emptyList()
        val arr = root.optJSONArray("episodes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val ep = arr.optJSONObject(i) ?: return@mapNotNull null
            TmdbEpisodeMeta(
                name = ep.optStringOrNull("name"),
                overview = ep.optStringOrNull("overview"),
                stillUrl = ep.optStringOrNull("still_path")?.let { tmdbImg("w300", it) },
                rating = ep.optDoubleOrNull("vote_average"),
            )
        }
    }

    private suspend fun findTmdbId(title: String, mediaType: String, year: Int?): Int? {
        if (title.isBlank()) return null
        val query = buildMap<String, Any?> {
            put("query", title)
            put("include_adult", false)
            if (year != null) put(if (mediaType == "movie") "year" else "first_air_date_year", year)
        }
        return tmdbJson("/search/$mediaType", query)
            ?.optJSONArray("results")
            ?.optJSONObject(0)
            ?.optInt("id")
            ?.takeIf { it != 0 }
    }

    private suspend fun tmdbJson(path: String, query: Map<String, Any?> = emptyMap()): JSONObject? = runCatching {
        val params = query + mapOf("api_key" to TMDB_KEY, "language" to "en-US")
        val res = app.get(TMDB_API + path + queryString(params), headers = headers, timeout = 8000)
        if (res.code in 200..299) JSONObject(res.text) else null
    }.getOrNull()

    private suspend fun fetchSimklId(imdbId: String?, mediaType: String): Int? {
        if (imdbId.isNullOrBlank()) return null
        val simklType = if (mediaType == "movie") "movies" else "tv"
        return fetchSimklObject(simklType, imdbId)
            ?.optJSONObject("ids")
            ?.optInt("simkl")
            ?.takeIf { it != 0 }
    }

    private suspend fun fetchSimklDetails(syncId: String): JSONObject? =
        fetchSimklObject("movies", syncId) ?: fetchSimklObject("tv", syncId)

    private suspend fun fetchSimklObject(type: String, id: String): JSONObject? = runCatching {
        val res = app.get(
            "https://api.simkl.com/$type/${id.encodeUrl()}?client_id=%20&extended=full",
            headers = headers + mapOf("Accept" to "application/json"),
            timeout = 8000
        )
        if (res.code !in 200..299) return@runCatching null
        val text = res.text.trim()
        if (!text.startsWith("{")) return@runCatching null
        JSONObject(text)
    }.getOrNull()

    private suspend fun findFtpbdUrlByTitle(title: String, year: Int?, expectedPath: String, postType: String): String? = runCatching {
        val doc = app.get("$mainUrl/?s=${title.encodeUrl()}&post_type=$postType", headers = headers).document
        val normalized = title.normalizedTitle()
        val cards = parseCards(doc, expectedPath)
        cards.firstOrNull { result ->
            result.name.cleanMediaTitle().normalizedTitle() == normalized
        }?.url ?: cards.firstOrNull()?.url
    }.getOrNull()

    private fun queryString(query: Map<String, Any?>): String {
        val params = query.entries.filter { it.value != null }
            .joinToString("&") { (k, v) -> "${k.encodeUrl()}=${v.toString().encodeUrl()}" }
        return if (params.isBlank()) "" else "?$params"
    }

    private fun tmdbImg(size: String, path: String): String = "$TMDB_IMG/$size$path"

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
        val siteActors = doc.select(".jws-meta-director a[href*='/person/']").mapNotNull { a ->
            val actor = a.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ActorData(Actor(actor, null), roleString = "")
        }.distinctBy { it.actor.name }
        val meta = fetchTmdbMeta(title, "movie", year)
        val actors = siteActors.ifEmpty { meta.actors ?: emptyList() }

        return newMovieLoadResponse(title, url, tvType, url) {
            posterUrl = poster
            backgroundPosterUrl = meta.backdropUrl ?: background
            this.plot = plot ?: meta.plot
            this.year = year ?: meta.year
            this.duration = duration ?: meta.runtime
            this.tags = tags.takeIf { it.isNotEmpty() } ?: meta.tags
            runCatching { if (actors.isNotEmpty()) this.actors = actors }
            runCatching { (doc.rating() ?: meta.rating)?.let { score = Score.from10(it) } }
            runCatching { meta.trailerUrl?.let { addTrailer(it) } }
            runCatching { meta.logoUrl?.let { logoUrl = it } }
            addSyncIds(meta)
        }
    }

    private suspend fun loadGroupedMovie(
        url: String,
        loaded: List<Triple<String, Document, String?>>,
    ): LoadResponse {
        val doc = loaded.first().second
        val title = doc.mainTitle()
        val poster = doc.poster()
        val background = doc.background()
        val plot = doc.plot()
        val year = doc.selectFirst(".video-years")?.text()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        val duration = parseDuration(doc.selectFirst(".video-time")?.text())
        val tags = doc.select(".jws-category a[href*='movies_cat']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val tvType = if (tags.any { it.contains("anime", ignoreCase = true) }) TvType.AnimeMovie else TvType.Movie
        val siteActors = doc.select(".jws-meta-director a[href*='/person/']").mapNotNull { a ->
            val actor = a.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ActorData(Actor(actor, null), roleString = "")
        }.distinctBy { it.actor.name }
        val meta = fetchTmdbMeta(title, "movie", year)
        val actors = siteActors.ifEmpty { meta.actors ?: emptyList() }
        val data = encodeLinkGroup(loaded.map { LinkItem(it.first, it.third) })

        return newMovieLoadResponse(title, url, tvType, data) {
            posterUrl = poster
            backgroundPosterUrl = meta.backdropUrl ?: background
            this.plot = plot ?: meta.plot
            this.year = year ?: meta.year
            this.duration = duration ?: meta.runtime
            this.tags = tags.takeIf { it.isNotEmpty() } ?: meta.tags
            runCatching { if (actors.isNotEmpty()) this.actors = actors }
            runCatching { (doc.rating() ?: meta.rating)?.let { score = Score.from10(it) } }
            runCatching { meta.trailerUrl?.let { addTrailer(it) } }
            runCatching { meta.logoUrl?.let { logoUrl = it } }
            addSyncIds(meta)
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

        val base = url.trimEnd('/')
        val meta = fetchTmdbMeta(title, "tv", year, seasons.toSet())
        // Fetch every season's episode page concurrently.
        val rawEpisodes = coroutineScope {
            seasons.map { season ->
                async<List<Episode>?> {
                    val epDoc = runCatching {
                        app.get("$base/episodes/?season=$season", headers = headers, timeout = 10_000).document
                    }.getOrNull() ?: if (season == 1) doc else return@async null
                    parseEpisodes(epDoc, season, meta.episodesBySeason[season])
                }
            }.awaitAll().filterNotNull().flatten()
        }.toMutableList()
        if (rawEpisodes.isEmpty()) rawEpisodes += parseEpisodes(doc, 1, meta.episodesBySeason[1])

        val episodes = rawEpisodes.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = meta.backdropUrl ?: background
            this.plot = plot ?: meta.plot
            this.year = year ?: meta.year
            this.tags = tags.takeIf { it.isNotEmpty() } ?: meta.tags
            runCatching { meta.actors?.let { actors = it } }
            runCatching { (doc.rating() ?: meta.rating)?.let { score = Score.from10(it) } }
            runCatching { meta.trailerUrl?.let { addTrailer(it) } }
            runCatching { meta.logoUrl?.let { logoUrl = it } }
            addSyncIds(meta)
        }
    }

    private suspend fun loadGroupedSeries(
        url: String,
        loaded: List<Triple<String, Document, String?>>,
    ): LoadResponse {
        val doc = loaded.first().second
        val title = doc.mainTitle()
        val poster = doc.poster()
        val background = doc.background()
        val plot = doc.plot()
        val year = doc.selectFirst(".video-years")?.text()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        val tags = doc.select(".jws-category a[href*='tv_shows_cat']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val allSeasons = loaded.flatMap { seasonNumbers(it.second) }.distinct().ifEmpty { listOf(1) }
        val meta = fetchTmdbMeta(title, "tv", year, allSeasons.toSet())

        // Fetch each source's seasons concurrently across sources.
        val allEpisodes = coroutineScope {
            loaded.map { (seriesUrl, seriesDoc, label) ->
                async {
                    val seasons = seasonNumbers(seriesDoc).ifEmpty { listOf(1) }
                    val base = seriesUrl.trimEnd('/')
                    val eps = mutableListOf<EpisodeWithLabel>()
                    for (season in seasons) {
                        val epDoc = runCatching {
                            app.get("$base/episodes/?season=$season", headers = headers, timeout = 10_000).document
                        }.getOrNull() ?: if (season == 1) seriesDoc else continue
                        parseEpisodes(epDoc, season, meta.episodesBySeason[season]).forEach { ep ->
                            eps += EpisodeWithLabel(ep, label)
                        }
                    }
                    eps
                }
            }.awaitAll().flatten()
        }.toMutableList()
        if (allEpisodes.isEmpty()) {
            parseEpisodes(doc, 1, meta.episodesBySeason[1]).forEach { ep ->
                allEpisodes += EpisodeWithLabel(ep, loaded.first().third)
            }
        }
        val episodes = allEpisodes.groupBy { (it.episode.season ?: 1) to (it.episode.episode ?: 0) }
            .map { (key, eps) ->
                val first = eps.first().episode
                val linkItems = eps.map { LinkItem(it.episode.data, it.label) }.distinctBy { it.url }
                val data = if (linkItems.size > 1) encodeLinkGroup(linkItems) else first.data
                newEpisode(data) {
                    name = first.name
                    season = key.first
                    episode = key.second
                    posterUrl = first.posterUrl
                    description = first.description
                }
            }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = meta.backdropUrl ?: background
            this.plot = plot ?: meta.plot
            this.year = year ?: meta.year
            this.tags = tags.takeIf { it.isNotEmpty() } ?: meta.tags
            runCatching { meta.actors?.let { actors = it } }
            runCatching { (doc.rating() ?: meta.rating)?.let { score = Score.from10(it) } }
            runCatching { meta.trailerUrl?.let { addTrailer(it) } }
            runCatching { meta.logoUrl?.let { logoUrl = it } }
            addSyncIds(meta)
        }
    }

    private fun parseEpisodes(
        doc: Document,
        season: Int,
        tmdbEpisodes: List<TmdbEpisodeMeta>? = null,
    ): List<Episode> {
        val out = mutableListOf<Episode>()
        doc.select(".jws-pisodes_advanced-item, .episodes-content .post-inner").forEachIndexed { index, item ->
            val a = item.selectFirst("a[href*='/episodes/']") ?: return@forEachIndexed
            val href = a.absUrl("href").ifBlank { a.attr("href").toAbsoluteUrl() }
            if (href.isBlank()) return@forEachIndexed
            val epNum = item.selectFirst(".episodes-number")?.text()?.toIntOrNull()
                ?: Regex("(?i)S\\d+E(\\d+)").find(item.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: (index + 1)
            val tmdbEp = tmdbEpisodes?.getOrNull(epNum - 1)
            val title = tmdbEp?.name?.takeIf { it.isNotBlank() }
                ?: item.selectFirst("h6 a, h5 a, h4 a")?.text()?.trim()
                ?: a.text().trim().ifBlank { "Episode $epNum" }
            val image = item.selectFirst("img")?.imgUrl()
            val desc = item.selectFirst(".jws-description")?.text()?.trim()
            val runtime = parseDuration(item.selectFirst(".time")?.text())
            out += newEpisode(href) {
                name = title
                this.season = season
                episode = epNum
                posterUrl = tmdbEp?.stillUrl ?: image
                description = tmdbEp?.overview?.takeIf { it.isNotBlank() } ?: desc
                runTime = runtime
                runCatching { tmdbEp?.rating?.let { score = Score.from10(it) } }
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

    private fun parseCards(doc: Document, expectedPath: String): List<SearchResponse> =
        groupSearchItems(parseCardItems(doc, expectedPath))

    private fun parseCardItems(doc: Document, expectedPath: String): List<FtpSearchItem> =
        doc.select(".site-main .jws-post-item, .site-main .post-inner")
            .mapNotNull { it.toSearchItem(expectedPath) }
            .distinctBy { it.url }

    private fun groupSearchItems(items: List<FtpSearchItem>): List<SearchResponse> =
        items.groupBy { item ->
            val titleKey = item.title.cleanMediaTitle().normalizedTitle()
            val year = item.year
            // Merge only when BOTH name and year are common. Missing year stays separate.
            if (year != null) "$titleKey|$year|${item.type}" else "$titleKey|${item.url}|${item.type}"
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

    private fun Element.toSearchItem(expectedPath: String): FtpSearchItem? {
        val a = selectFirst("a[href*='$expectedPath']") ?: return null
        val href = a.absUrl("href").ifBlank { a.attr("href").toAbsoluteUrl() }
        if (href.isBlank() || !href.contains(expectedPath)) return null
        val title = (selectFirst("h6 a, h5 a, h4 a, .post-title a")?.text()?.trim()
            ?: a.attr("title").trim().ifBlank { null }
            ?: a.text().trim()).trim()
        if (title.isBlank() || title.equals("Play Now", true)) return null
        val poster = selectFirst("img")?.imgUrl()
        val fullText = text() + " " + title + " " + href
        val year = selectFirst(".video-years, .year")?.text()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
            ?: extractYear(fullText)
        val type = when {
            expectedPath.contains("tv_shows") -> TvType.TvSeries
            text().contains("Anime", ignoreCase = true) || href.contains("anime", ignoreCase = true) -> TvType.AnimeMovie
            else -> TvType.Movie
        }
        return FtpSearchItem(
            title = title.cleanMediaTitle(),
            url = href,
            type = type,
            poster = poster,
            year = year,
            sourceLabel = buildQualityInitials(fullText),
        )
    }

    private fun encodeGroupedItems(items: List<FtpSearchItem>): String {
        val arr = JSONArray()
        val seenLabels = mutableMapOf<String, Int>()
        items.distinctBy { it.url }.forEachIndexed { index, item ->
            val baseLabel = item.sourceLabel ?: "Source ${index + 1}"
            val seen = (seenLabels[baseLabel] ?: 0) + 1
            seenLabels[baseLabel] = seen
            val label = if (seen > 1) "$baseLabel #$seen" else baseLabel
            arr.put(JSONObject().apply {
                put("url", item.url)
                put("label", label)
            })
        }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(arr.toString().toByteArray())
        return "load?data=$encoded"
    }

    private fun decodeGroupedItems(url: String): List<GroupedFtpItem>? = runCatching {
        if (!url.contains("load?data=")) return@runCatching null
        val encoded = url.substringAfter("load?data=").substringBefore("&")
        val arr = JSONArray(String(Base64.getUrlDecoder().decode(encoded)))
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val itemUrl = obj.optStringOrNull("url") ?: return@mapNotNull null
            GroupedFtpItem(itemUrl, obj.optStringOrNull("label"))
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun encodeLinkGroup(links: List<LinkItem>): String {
        val arr = JSONArray()
        val seenLabels = mutableMapOf<String, Int>()
        links.distinctBy { it.url }.forEachIndexed { index, link ->
            val baseLabel = link.label ?: buildQualityInitials(link.url) ?: "Source ${index + 1}"
            val seen = (seenLabels[baseLabel] ?: 0) + 1
            seenLabels[baseLabel] = seen
            val label = if (seen > 1) "$baseLabel #$seen" else baseLabel
            arr.put(JSONObject().apply {
                put("url", link.url)
                put("label", label)
            })
        }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(arr.toString().toByteArray())
        return "ftpbd://links?data=$encoded"
    }

    private fun decodeLinkGroup(data: String): List<LinkItem>? = runCatching {
        if (!data.startsWith("ftpbd://links?data=")) return@runCatching null
        val encoded = data.substringAfter("ftpbd://links?data=")
        val arr = JSONArray(String(Base64.getUrlDecoder().decode(encoded)))
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val itemUrl = obj.optStringOrNull("url") ?: return@mapNotNull null
            LinkItem(itemUrl, obj.optStringOrNull("label"))
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

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

    private fun seasonNumbers(doc: Document): List<Int> =
        doc.select(".select-seasion .dropdown-item[data-index], a[href*='season=']")
            .mapNotNull { el ->
                el.attr("data-index").toIntOrNull()?.plus(1)
                    ?: Regex("season=(\\d+)").find(el.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(\\d+)").find(el.text())?.value?.toIntOrNull()
            }
            .filter { it > 0 }
            .distinct()

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
                val subUrl = raw.toAbsoluteUrl(baseUrl)
                if (subUrl.isBlank() || !seen.add(subUrl)) return@forEach
                val label = el.attr("label")
                    .ifBlank { el.attr("srclang") }
                    .ifBlank { el.text() }
                    .ifBlank { subtitleLabelFromUrl(subUrl) }
                subtitleCallback(newSubtitleFile(label, subUrl))
            }

        Regex("""https?://[^\s"'<>]+\.(?:srt|vtt|ass)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.htmlDecode() }
            .forEach { raw ->
                val subUrl = raw.toAbsoluteUrl(baseUrl)
                if (subUrl.isNotBlank() && seen.add(subUrl)) {
                    subtitleCallback(newSubtitleFile(subtitleLabelFromUrl(subUrl), subUrl))
                }
            }
        Regex("""["']([^"']+\.(?:srt|vtt|ass)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1].htmlDecode() }
            .forEach { raw ->
                val subUrl = raw.toAbsoluteUrl(baseUrl)
                if (subUrl.isNotBlank() && seen.add(subUrl)) {
                    subtitleCallback(newSubtitleFile(subtitleLabelFromUrl(subUrl), subUrl))
                }
            }
    }

    private suspend fun collectM3u8Subtitles(
        manifestUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val manifestHeaders = headers + ("Referer" to referer)
        val manifest = runCatching { app.get(manifestUrl, headers = manifestHeaders).text }.getOrNull() ?: return
        Regex("""#EXT-X-MEDIA:([^\r\n]+)""", RegexOption.IGNORE_CASE)
            .findAll(manifest)
            .forEach { match ->
                val attrs = match.groupValues[1]
                if (!attrs.contains("TYPE=SUBTITLES", ignoreCase = true)) return@forEach
                val rawUri = parseM3u8Attribute(attrs, "URI") ?: return@forEach
                val subUrl = rawUri.toAbsoluteUrl(manifestUrl)
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

    private fun buildShortSourceName(label: String?, finalUrl: String, index: Int): String {
        val actualQuality = buildQualityInitials(finalUrl)
        val groupLabel = label?.cleanSourceLabel()?.takeUnless { it.startsWith("Source ", true) }
        val finalLabel = actualQuality ?: groupLabel ?: "Source $index"
        return "$name • $finalLabel"
    }

    private fun buildQualityInitials(vararg hints: String?): String? {
        val raw = hints.filterNotNull().joinToString(" ")
            .replace("%20", " ")
            .replace("_", " ")
            .replace("-", " ")
        if (raw.isBlank()) return null
        fun has(pattern: String): Boolean = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val parts = linkedSetOf<String>()
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
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
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

    private fun extractYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
            .findAll(text)
            .mapNotNull { it.value.toIntOrNull() }
            .firstOrNull { it in 1900..2035 }
    }

    private fun LoadResponse.addSyncIds(meta: TmdbMeta) {
        runCatching { meta.imdbId?.takeIf { it.isNotBlank() }?.let { addImdbId(it) } }
        runCatching { meta.simklId?.let { addSimklId(it) } }
    }

    private fun JSONArray.toStringList(key: String): List<String> =
        (0 until length()).mapNotNull { i -> optJSONObject(i)?.optStringOrNull(key) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun JSONArray.bestTmdbImage(key: String, preferLogo: Boolean = false): String? {
        if (length() == 0) return null
        val candidates = (0 until length()).mapNotNull { i -> optJSONObject(i) }
            .sortedWith(
                compareByDescending<JSONObject> {
                    val lang = it.optStringOrNull("iso_639_1")
                    if (lang == "en") 3 else if (lang == null) 2 else 1
                }.thenByDescending { it.optDoubleOrNull("vote_average") ?: 0.0 }
            )
        val picked = if (preferLogo) {
            candidates.firstOrNull { it.optDoubleOrNull("aspect_ratio")?.let { ratio -> ratio >= 1.5 } == true }
                ?: candidates.firstOrNull()
        } else candidates.firstOrNull()
        return picked?.optStringOrNull(key)
    }

    private fun JSONArray.bestYoutubeTrailer(): String? {
        val videos = (0 until length()).mapNotNull { i -> optJSONObject(i) }
            .filter { it.optStringOrNull("site")?.equals("YouTube", ignoreCase = true) == true }
        val picked = videos.firstOrNull {
            it.optStringOrNull("type")?.equals("Trailer", ignoreCase = true) == true &&
                    it.optStringOrNull("official")?.equals("true", ignoreCase = true) == true
        } ?: videos.firstOrNull { it.optStringOrNull("type")?.equals("Trailer", ignoreCase = true) == true }
            ?: videos.firstOrNull()
        return picked?.optStringOrNull("key")?.let { "https://youtu.be/$it" }
    }

    private fun JSONArray.toActors(limit: Int = 20): List<ActorData> =
        (0 until length()).mapNotNull { i ->
            val cast = optJSONObject(i) ?: return@mapNotNull null
            val name = cast.optStringOrNull("name") ?: cast.optStringOrNull("original_name") ?: return@mapNotNull null
            val profile = cast.optStringOrNull("profile_path")?.let { tmdbImg("w185", it) }
            val character = cast.optStringOrNull("character")
            ActorData(Actor(name, profile), roleString = character ?: "")
        }.take(limit)

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null
        else optString(key, "").trim().takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null
        else optString(key, "").toIntOrNull() ?: optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null
        else optString(key, "").toDoubleOrNull() ?: optDouble(key, Double.NaN).takeIf { !it.isNaN() }

    private fun yearFromDate(date: String?): Int? =
        date?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

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

    private fun String.toAbsoluteUrl(baseUrl: String = mainUrl): String = when {
        startsWith("//") -> baseUrl.substringBefore("://", "https") + ":$this"
        startsWith("http", ignoreCase = true) -> this
        startsWith("/") -> mainUrl + this
        baseUrl == mainUrl -> "$mainUrl/${trimStart('/')}"
        else -> baseUrl.substringBeforeLast("/", mainUrl).trimEnd('/') + "/${trimStart('/')}"
    }

    private fun String.cleanSourceLabel(): String =
        replace(Regex("(?i)\\b(?:web series|movies?|series|tv)\\b"), " ")
            .replace(Regex("\\s*/\\s*"), "/")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '/', '-', '•')

    private fun String.cleanMediaTitle(): String =
        replace(Regex("\\s*[–-]\\s*\\[[^]]+]\\s*$"), "")
            .replace(Regex("\\[[^]]+]"), "")
            .replace(Regex("(?i)\\b(hindi|dubbed|dual audio|season \\d+)\\b"), "")
            .trim()
            .ifBlank { this }

    private fun String.normalizedTitle(): String =
        cleanMediaTitle()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun String.encodeUrl(): String = URLEncoder.encode(this, "UTF-8")

    private fun String.htmlDecode(): String = Jsoup.parse(this).text()
}
