package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class NowHDTime : MainAPI() {
    override var mainUrl = "https://nowhdtime.com.bd"
    override var name = "NowHDTime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
    )

    override val supportedSyncNames = setOfNotNull(
        SyncIdName.Anilist,
        runCatching { SyncIdName.valueOf("Simkl") }.getOrNull(),
    )

    companion object {
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p"
    }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/|Latest Movies|/movie/" to "Recent Movies",
        "$mainUrl/|Latest TV Shows|/tv-show/" to "Recent Series",
        "$mainUrl/anime|Currently Airing|/anime/" to "Recent Anime",
        "$mainUrl/trending|Trending Content|/movie/" to "Trending Movies",
        "$mainUrl/trending|Trending Content|/tv-show/" to "Trending Series",
        "$mainUrl/anime|Trending|/anime/" to "Trending Anime",
        "$mainUrl/movies?page=|ALL|/movie/" to "Popular Movies",
        "$mainUrl/tv-shows?page=|ALL|/tv-show/" to "Popular Series",
        "$mainUrl/anime|All-Time Popular|/anime/" to "Popular Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val parts = request.data.split("|")
        val base = parts.getOrNull(0) ?: request.data
        val section = parts.getOrNull(1) ?: "ALL"
        val expected = parts.getOrNull(2) ?: when {
            base.contains("/tv-shows") -> "/tv-show/"
            base.contains("/anime") -> "/anime/"
            else -> "/movie/"
        }
        val url = if (base.endsWith("page=")) base + page else base
        val doc = app.get(url, headers = headers).document
        val items = if (section == "ALL") parseCards(doc, expected) else parseCardsInSection(doc, section, expected)
        val hasNext = section == "ALL" && (doc.select("a[rel=next], a:contains(Next), a[href*='page=${page + 1}']").isNotEmpty() || items.isNotEmpty())
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val out = mutableListOf<SearchResponse>()

        // Site API is fast when available. HTML fallbacks make search resilient
        // if the endpoint changes or returns malformed data.
        runCatching {
            val raw = app.get("$mainUrl/api/search?q=${q.encodeUrl()}", headers = headers).text
            val root = JSONObject(raw)
            root.optJSONArray("movies")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.toApiSearch(false)?.let(out::add)
            }
            root.optJSONArray("tvShows")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.toApiSearch(true)?.let(out::add)
            }
        }
        if (out.isEmpty()) {
            runCatching { out += parseCards(app.get("$mainUrl/movies?search=${q.encodeUrl()}", headers = headers).document, "/movie/") }
            runCatching { out += parseCards(app.get("$mainUrl/tv-shows?search=${q.encodeUrl()}", headers = headers).document, "/tv-show/") }
            runCatching { out += parseCards(app.get("$mainUrl/anime?search=${q.encodeUrl()}", headers = headers).document, "/anime/") }
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.substringBefore("?").trimEnd('/')
        val doc = app.get(url, headers = headers).document
        return when {
            cleanUrl.contains("/anime/") && !cleanUrl.contains("/watch/") -> loadAnime(url, doc)
            cleanUrl.contains("/anime/") && cleanUrl.contains("/watch/") -> loadAnimeEpisodeAsMovie(url, doc)
            cleanUrl.contains("/tv-show/") -> loadTv(url, doc)
            cleanUrl.contains("/movie/") -> loadMovie(url, doc)
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = runCatching { JSONObject(data) }.getOrNull()
        val pageUrl = payload?.optStringOrNull("url")
        val sources = linkedSetOf<String>()

        payload?.optJSONArray("players")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i)
                .takeIf { it.isNotBlank() && !it.startsWith("magnet:", ignoreCase = true) }
                ?.let(sources::add)
        }
        payload?.optStringOrNull("direct")
            ?.takeIf { !it.startsWith("magnet:", ignoreCase = true) }
            ?.let(sources::add)

        if (!pageUrl.isNullOrBlank()) {
            val fresh = runCatching { app.get(pageUrl, headers = headers).text }.getOrNull()
            if (fresh != null) sources += extractPlayers(fresh)
                .filterNot { it.type.equals("torrent", true) || it.url?.startsWith("magnet:", true) == true }
                .mapNotNull { it.url }
            if (fresh != null) sources += extractIframeSources(fresh, pageUrl)
        }

        val tmdbId = payload?.optStringOrNull("tmdbId")
        val mediaType = payload?.optStringOrNull("mediaType")
        val season = payload?.optIntOrNull("season")
        val episode = payload?.optIntOrNull("episode")
        if (sources.none { it.startsWith("http") }) {
            if (mediaType == "tv" && !tmdbId.isNullOrBlank() && season != null && episode != null) {
                sources += fallbackTvEmbeds(tmdbId, season, episode)
            } else if (mediaType == "movie" && !tmdbId.isNullOrBlank()) {
                sources += fallbackMovieEmbeds(tmdbId)
            }
        }

        // Some servers wrap the real player inside a first iframe. Add those
        // nested player URLs, but still let extractors resolve them properly.
        sources.toList().forEach { src ->
            if (src.startsWith("http") && !src.isDirectVideo() && !src.contains(".m3u8", true)) {
                runCatching { app.get(src, headers = headers, timeout = 8000).text }
                    .getOrNull()
                    ?.let { sources += extractIframeSources(it, src) }
            }
        }

        var found = false
        sources.distinct().forEach { raw ->
            val source = raw.htmlDecode().trim()
            if (source.isBlank()) return@forEach
            if (source.startsWith("magnet:", ignoreCase = true)) return@forEach

            // NHDAPI is the site's primary/local server. Resolve it directly
            // instead of returning an HTML embed page to Cloudstream.
            val nhdDirect = resolveNhdApiSource(source)
            if (!nhdDirect.isNullOrBlank()) {
                M3u8Helper.generateM3u8(
                    source = "$name - Local",
                    streamUrl = nhdDirect,
                    referer = source,
                    headers = headers
                ).forEach(callback)
                found = true
                return@forEach
            }

            val mirroredDirect = resolveMirrorWithNhdApi(source, mediaType, tmdbId, season, episode)
            if (!mirroredDirect.isNullOrBlank()) {
                M3u8Helper.generateM3u8(
                    source = "$name - ${source.hostName()}",
                    streamUrl = mirroredDirect,
                    referer = source,
                    headers = headers
                ).forEach(callback)
                found = true
                return@forEach
            }

            if (source.contains(".m3u8", ignoreCase = true)) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = source,
                    referer = pageUrl ?: mainUrl,
                    headers = headers
                ).forEach(callback)
                found = true
                return@forEach
            }
            if (source.isDirectVideo()) {
                callback(newExtractorLink(name, "${name} - ${source.hostName()}", source, ExtractorLinkType.VIDEO) {
                    referer = pageUrl ?: mainUrl
                    quality = getQualityFromName(source)
                })
                found = true
                return@forEach
            }
            runCatching {
                loadExtractor(source, pageUrl ?: mainUrl, subtitleCallback) {
                    callback(it)
                    found = true
                }
            }
            // Never expose raw embed HTML pages as playable video links; that
            // is what produced HTTP 200/403-style playback errors in the app.
        }
        return found
    }

    // ───────────────────────────── Load builders ─────────────────────────────

    private suspend fun loadMovie(url: String, doc: Document): LoadResponse {
        val siteTitle = doc.titleFromPage()
        val sitePoster = doc.posterFromPage()
        val sitePlot = doc.plotFromPage()
        val siteYear = doc.yearFromPage()
        val players = extractPlayers(doc.html())
        val tmdbId = players.firstNotNullOfOrNull { it.url?.extractTmdbId("movie") }
        val meta = fetchTmdbMeta(tmdbId, "movie", siteTitle, siteYear)
        val data = JSONObject()
            .put("url", url)
            .put("mediaType", "movie")
            .put("tmdbId", meta.tmdbId?.toString() ?: tmdbId.orEmpty())
            .put("players", JSONArray(players.filterNot { it.type.equals("torrent", true) || it.url?.startsWith("magnet:", true) == true }.mapNotNull { it.url }))
            .toString()

        return newMovieLoadResponse(siteTitle, url, TvType.Movie, data) {
            posterUrl = sitePoster
            backgroundPosterUrl = meta.backdropUrl ?: doc.backdropFromPage()
            plot = sitePlot ?: meta.plot
            year = siteYear ?: meta.year
            duration = meta.runtime
            tags = meta.tags
            runCatching { meta.rating?.let { score = Score.from10(it) } }
            runCatching { meta.actors?.let { actors = it } }
            runCatching { meta.logoUrl?.let { logoUrl = it } }
            runCatching { meta.trailerUrl?.let { addTrailer(it) } }
            addSyncIds(meta)
        }
    }

    private suspend fun loadTv(url: String, doc: Document): LoadResponse {
        val siteTitle = doc.titleFromPage()
        val sitePoster = doc.posterFromPage()
        val sitePlot = doc.plotFromPage()
        val siteYear = doc.yearFromPage()
        val tvId = doc.selectFirst("[data-tv-show-id]")?.attr("data-tv-show-id")
            ?: Regex("openReportModal\\('tv_show',\\s*(\\d+)").find(doc.html())?.groupValues?.getOrNull(1)
        // data-tv-show-id is NowHDTime's local id, not TMDB. Search TMDB by
        // clean title/year to avoid bad embeds like tv/1/...
        val meta = fetchTmdbMeta(null, "tv", siteTitle, siteYear)
        val tmdbId = meta.tmdbId?.toString() ?: tvId.orEmpty()
        val episodes = parseTvEpisodes(doc, tmdbId)

        return newTvSeriesLoadResponse(siteTitle, url, TvType.TvSeries, episodes) {
            posterUrl = sitePoster
            backgroundPosterUrl = meta.backdropUrl ?: doc.backdropFromPage()
            plot = sitePlot ?: meta.plot
            year = siteYear ?: meta.year
            tags = meta.tags
            runCatching { meta.rating?.let { score = Score.from10(it) } }
            runCatching { meta.actors?.let { actors = it } }
            runCatching { meta.logoUrl?.let { logoUrl = it } }
            runCatching { meta.trailerUrl?.let { addTrailer(it) } }
            addSyncIds(meta)
        }
    }

    private suspend fun loadAnime(url: String, doc: Document): LoadResponse {
        val siteTitle = doc.titleFromPage()
        val anilistId = Regex("/anime/(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val meta = fetchAnimeMeta(anilistId, siteTitle)
        val episodes = parseAnimeEpisodes(doc, url, meta)
        val tvType = if (episodes.size <= 1 && doc.select("a[href*='/watch/']").isEmpty()) TvType.AnimeMovie else TvType.Anime

        return if (tvType == TvType.AnimeMovie) {
            val data = JSONObject()
                .put("url", url)
                .put("mediaType", "anime")
                .put("direct", url)
                .toString()
            newMovieLoadResponse(siteTitle, url, TvType.AnimeMovie, data) {
                posterUrl = doc.posterFromPage() ?: meta.posterUrl
                backgroundPosterUrl = meta.backdropUrl ?: doc.backdropFromPage()
                plot = doc.plotFromPage() ?: meta.plot
                year = doc.yearFromPage() ?: meta.year
                tags = meta.tags
                runCatching { meta.rating?.let { score = Score.from10(it) } }
                runCatching { meta.logoUrl?.let { logoUrl = it } }
                runCatching { meta.trailerUrl?.let { addTrailer(it) } }
                addSyncIds(meta)
                runCatching { anilistId?.let { addAniListId(it) } }
            }
        } else {
            newAnimeLoadResponse(siteTitle, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                posterUrl = doc.posterFromPage() ?: meta.posterUrl
                backgroundPosterUrl = meta.backdropUrl ?: doc.backdropFromPage()
                plot = doc.plotFromPage() ?: meta.plot
                year = doc.yearFromPage() ?: meta.year
                tags = meta.tags
                runCatching { meta.rating?.let { score = Score.from10(it) } }
                runCatching { meta.logoUrl?.let { logoUrl = it } }
                runCatching { meta.trailerUrl?.let { addTrailer(it) } }
                addSyncIds(meta)
                runCatching { anilistId?.let { addAniListId(it) } }
            }
        }
    }

    private suspend fun loadAnimeEpisodeAsMovie(url: String, doc: Document): LoadResponse {
        val title = doc.titleFromPage()
        val iframe = extractIframeSources(doc.html(), url).firstOrNull()
        val data = JSONObject().put("url", url).put("direct", iframe ?: url).toString()
        return newMovieLoadResponse(title, url, TvType.AnimeMovie, data) {
            posterUrl = doc.posterFromPage()
            backgroundPosterUrl = doc.backdropFromPage()
            plot = doc.plotFromPage()
        }
    }

    // ───────────────────────────── Metadata ─────────────────────────────

    private data class Player(val type: String?, val name: String?, val url: String?, val server: String?)

    private data class Meta(
        val tmdbId: Int? = null,
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
        val posterUrl: String? = null,
    )

    private suspend fun fetchTmdbMeta(tmdbIdHint: String?, mediaType: String, title: String, year: Int?): Meta {
        val id = tmdbIdHint?.toIntOrNull() ?: findTmdbId(title, mediaType, year) ?: return Meta()
        val detail = tmdbJson("/$mediaType/$id", mapOf("append_to_response" to "credits,external_ids,images,videos", "include_image_language" to "en,null"))
            ?: return Meta(tmdbId = id)
        val dateKey = if (mediaType == "movie") "release_date" else "first_air_date"
        val runtime = if (mediaType == "movie") detail.optIntOrNull("runtime")
        else detail.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }
        val imdbId = detail.optJSONObject("external_ids")?.optStringOrNull("imdb_id") ?: detail.optStringOrNull("imdb_id")
        val logo = detail.optJSONObject("images")?.optJSONArray("logos")?.bestImage("file_path")?.let { tmdbImg("w500", it) }
        return Meta(
            tmdbId = id,
            plot = detail.optStringOrNull("overview"),
            backdropUrl = detail.optStringOrNull("backdrop_path")?.let { tmdbImg("w1280", it) },
            logoUrl = logo ?: imdbId?.let { "https://live.metahub.space/logo/medium/$it/img" },
            year = yearFromDate(detail.optStringOrNull(dateKey)),
            runtime = runtime,
            tags = detail.optJSONArray("genres")?.toStringList("name"),
            rating = detail.optDoubleOrNull("vote_average"),
            trailerUrl = detail.optJSONObject("videos")?.optJSONArray("results")?.bestYoutubeTrailer(),
            imdbId = imdbId,
            simklId = fetchSimklId(imdbId, mediaType),
            actors = detail.optJSONObject("credits")?.optJSONArray("cast")?.toActors(),
            posterUrl = detail.optStringOrNull("poster_path")?.let { tmdbImg("w500", it) },
        )
    }

    private suspend fun fetchAnimeMeta(anilistId: Int?, title: String): Meta {
        var tmdbId: String? = null
        if (anilistId != null) {
            runCatching {
                val mappings = JSONObject(app.get("https://api.ani.zip/mappings?anilist_id=$anilistId", headers = headers).text)
                    .optJSONObject("mappings")
                tmdbId = mappings?.optStringOrNull("themoviedb_id")
            }
        }
        return fetchTmdbMeta(tmdbId, "tv", title, null).let { meta ->
            if (meta.tmdbId != null) meta else Meta(posterUrl = null)
        }
    }

    private suspend fun findTmdbId(title: String, mediaType: String, year: Int?): Int? {
        val q = title.cleanMediaTitle()
        val params = mutableMapOf<String, Any?>("query" to q, "include_adult" to false)
        if (year != null) params[if (mediaType == "movie") "year" else "first_air_date_year"] = year
        return tmdbJson("/search/$mediaType", params)?.optJSONArray("results")?.optJSONObject(0)?.optInt("id")?.takeIf { it != 0 }
    }

    private suspend fun tmdbJson(path: String, query: Map<String, Any?>): JSONObject? = runCatching {
        val params = query + mapOf("api_key" to TMDB_KEY, "language" to "en-US")
        val res = app.get(TMDB_API + path + queryString(params), headers = headers, timeout = 8000)
        if (res.code in 200..299) JSONObject(res.text) else null
    }.getOrNull()

    private suspend fun fetchSimklId(imdbId: String?, mediaType: String): Int? {
        if (imdbId.isNullOrBlank()) return null
        val type = if (mediaType == "movie") "movies" else "tv"
        return runCatching {
            val res = app.get("https://api.simkl.com/$type/${imdbId.encodeUrl()}?client_id=%20&extended=full", headers = headers + mapOf("Accept" to "application/json"), timeout = 8000)
            if (res.code !in 200..299) return@runCatching null
            JSONObject(res.text).optJSONObject("ids")?.optInt("simkl")?.takeIf { it != 0 }
        }.getOrNull()
    }

    // ───────────────────────────── Parsers ─────────────────────────────

    private fun parseCards(doc: Document, expected: String): List<SearchResponse> =
        doc.select(cardSelector(expected))
            .mapNotNull { it.toSearchResult(expected) }
            .distinctBy { it.url }

    private fun parseCardsInSection(doc: Document, heading: String, expected: String): List<SearchResponse> {
        val section = doc.select("section").firstOrNull { sec ->
            sec.selectFirst("h1,h2,h3")?.text()?.trim()?.equals(heading, ignoreCase = true) == true
        } ?: return parseCards(doc, expected)
        return section.select(cardSelector(expected))
            .mapNotNull { it.toSearchResult(expected) }
            .distinctBy { it.url }
    }

    private fun cardSelector(expected: String): String = when {
        expected.contains("tv-show") -> ".movie-card:has(a[href*='/tv-show/watch-'])"
        expected == "/anime/" -> ".movie-card:has(a[href^='https://nowhdtime.com.bd/anime/']), .movie-card:has(a[href^='/anime/'])"
        else -> ".movie-card:has(a[href*='/movie/watch-'])"
    }

    private fun Element.toSearchResult(expected: String): SearchResponse? {
        val a = when {
            expected.contains("tv-show") -> selectFirst("a[href*='/tv-show/watch-']")
            expected == "/anime/" -> selectFirst("a[href^='https://nowhdtime.com.bd/anime/']:not([href*='/watch/']), a[href^='/anime/']:not([href*='/watch/'])")
            else -> selectFirst("a[href*='/movie/watch-']")
        } ?: return null
        val href = a.absUrl("href").ifBlank { a.attr("href").toAbsoluteUrl() }
        if (href.isBlank() || !href.contains(expected)) return null
        if (expected == "/anime/" && href.contains("/watch/")) return null

        val img = selectFirst("img[src*='/w500/'], img[src*='anilistcdn'], img")
        val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("h3, h2, h4, .title")?.text()?.trim()
            ?: a.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        if (title.equals("Watch", true) || title.equals("More", true) || title.equals("Info", true)) return null

        val poster = img?.imgUrl()
        val year = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)").find(text())?.value?.toIntOrNull()
        return when {
            expected.contains("tv-show") -> newTvSeriesSearchResponse(title.cleanCardTitle(), href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
            }
            expected == "/anime/" -> newAnimeSearchResponse(title.cleanCardTitle(), href, TvType.Anime) {
                posterUrl = poster
                this.year = year
            }
            else -> newMovieSearchResponse(title.cleanCardTitle(), href, TvType.Movie) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    private fun JSONObject.toApiSearch(tv: Boolean): SearchResponse? {
        val title = optStringOrNull("title") ?: optStringOrNull("name") ?: return null
        val url = optStringOrNull("url")?.toAbsoluteUrl() ?: return null
        val poster = optStringOrNull("poster") ?: optStringOrNull("image")
        return if (tv) newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
        else newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
    }

    private fun parseTvEpisodes(doc: Document, tmdbId: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        doc.select("[data-episode-id][data-season][data-episode]").forEach { el ->
            val season = el.attr("data-season").toIntOrNull() ?: 1
            val episode = el.attr("data-episode").toIntOrNull() ?: 1
            val name = el.selectFirst("h3, h4, .font-semibold")?.text()?.trim()
                ?: Regex("EP\\s*\\d+\\s*(.+)").find(el.text())?.groupValues?.getOrNull(1)?.trim()
                ?: "Episode $episode"
            val poster = el.selectFirst("img")?.imgUrl()
            val data = JSONObject()
                .put("mediaType", "tv")
                .put("tmdbId", tmdbId)
                .put("season", season)
                .put("episode", episode)
                .put("players", JSONArray(fallbackTvEmbeds(tmdbId, season, episode)))
                .toString()
            episodes += newEpisode(data) {
                this.name = name.cleanEpisodeTitle()
                this.season = season
                this.episode = episode
                this.posterUrl = poster
            }
        }
        return episodes.distinctBy { (it.season ?: 1) to (it.episode ?: 0) }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    private fun parseAnimeEpisodes(doc: Document, showUrl: String, meta: Meta): List<Episode> {
        val showId = Regex("/anime/(\\d+)").find(showUrl)?.groupValues?.getOrNull(1) ?: return emptyList()
        val anchors = doc.select("a[href*='/anime/$showId/watch/']")
        return anchors.mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").toAbsoluteUrl() }
            val ep = Regex("/watch/(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            newEpisode(JSONObject().put("url", href).put("direct", href).toString()) {
                name = "Episode $ep"
                season = 1
                episode = ep
            }
        }.distinctBy { it.episode }.sortedBy { it.episode ?: 0 }
    }

    private fun extractPlayers(html: String): List<Player> {
        val raw = Regex("const\\s+players\\s*=\\s*(\\[.*?]);", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.getOrNull(1)
            ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Player>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out += Player(
                type = obj.optStringOrNull("type"),
                name = obj.optStringOrNull("name"),
                url = obj.optStringOrNull("url"),
                server = obj.optStringOrNull("server"),
            )
        }
        return out
    }

    private fun extractIframeSources(html: String, baseUrl: String = mainUrl): List<String> =
        Regex("""(?i)<iframe[^>]+src=["']([^"']+)["']""").findAll(html)
            .map { it.groupValues[1].htmlDecode().toAbsoluteUrl(baseUrl) }
            .filter { it.startsWith("http") }
            .toList()

    private suspend fun resolveNhdApiSource(url: String): String? {
        val movieId = Regex("(?i)(?:nhdapi\\.com|player\\.nhdapi\\.com)/embed/movie/(\\d+)").find(url)
            ?.groupValues?.getOrNull(1)
        if (!movieId.isNullOrBlank()) return fetchNhdApiStream("movie", movieId, null, null)

        val tv = Regex("(?i)(?:nhdapi\\.com|player\\.nhdapi\\.com)/embed/tv/(\\d+)/(\\d+)/(\\d+)").find(url)
        if (tv != null) {
            return fetchNhdApiStream(
                type = "tv",
                id = tv.groupValues[1],
                season = tv.groupValues[2].toIntOrNull(),
                episode = tv.groupValues[3].toIntOrNull()
            )
        }
        return null
    }

    private suspend fun resolveMirrorWithNhdApi(
        url: String,
        mediaType: String?,
        tmdbId: String?,
        season: Int?,
        episode: Int?
    ): String? {
        val host = url.hostName().lowercase()
        val isMirror = host.contains("vidnest") || host.contains("videasy")
        if (!isMirror || tmdbId.isNullOrBlank()) return null
        return if (mediaType == "tv" && season != null && episode != null) {
            fetchNhdApiStream("tv", tmdbId, season, episode)
        } else if (mediaType == "movie") {
            fetchNhdApiStream("movie", tmdbId, null, null)
        } else null
    }

    private suspend fun fetchNhdApiStream(type: String, id: String, season: Int?, episode: Int?): String? = runCatching {
        val tokenRes = app.post(
            "https://player.nhdapi.com/api/token",
            headers = mapOf(
                "User-Agent" to headers["User-Agent"].orEmpty(),
                "Origin" to "https://player.nhdapi.com",
                "Referer" to "https://player.nhdapi.com/embed/$type/$id",
                "X-Content-Id" to id,
                "Content-Type" to "application/json",
            ),
            json = mapOf("ipv4" to "")
        )
        if (tokenRes.code !in 200..299) return@runCatching null
        val tokenJson = JSONObject(tokenRes.text)
        val token = tokenJson.optStringOrNull("token") ?: return@runCatching null
        val secureId = tokenJson.optStringOrNull("secureId") ?: id
        val endpoint = if (type == "tv" && season != null && episode != null) {
            "https://player.nhdapi.com/api/tv?id=$secureId&season=$season&episode=$episode"
        } else {
            "https://player.nhdapi.com/api/movie?id=$secureId"
        }
        val sourceRes = app.get(
            endpoint,
            headers = mapOf(
                "User-Agent" to headers["User-Agent"].orEmpty(),
                "Referer" to "https://player.nhdapi.com/embed/$type/$id",
                "X-API-Token" to token,
                "X-Client-IPv4" to "",
            )
        )
        if (sourceRes.code !in 200..299) return@runCatching null
        val encrypted = JSONObject(sourceRes.text)
        val decrypted = decryptNhdApiPayload(encrypted) ?: return@runCatching null
        val root = JSONObject(decrypted)
        if (root.optString("status") != "success") return@runCatching null
        root.optJSONObject("stream")?.optStringOrNull("hls_streaming")
            ?: root.optJSONObject("stream")?.optStringOrNull("url")
    }.getOrNull()

    private fun decryptNhdApiPayload(obj: JSONObject): String? = runCatching {
        val iv = Base64.getDecoder().decode(obj.optStringOrNull("iv") ?: return@runCatching null)
        val tag = Base64.getDecoder().decode(obj.optStringOrNull("tag") ?: return@runCatching null)
        val data = Base64.getDecoder().decode(obj.optStringOrNull("data") ?: return@runCatching null)
        val combined = ByteArray(data.size + tag.size)
        System.arraycopy(data, 0, combined, 0, data.size)
        System.arraycopy(tag, 0, combined, data.size, tag.size)
        val key = MessageDigest.getInstance("SHA-256").digest("Z9#rL!v2K*5qP&7mXw".toByteArray())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(combined), Charsets.UTF_8)
    }.getOrNull()

    private fun fallbackMovieEmbeds(id: String): List<String> = listOf(
        "https://nhdapi.com/embed/movie/$id",
        "https://vidnest.fun/movie/$id",
        "https://player.videasy.net/movie/$id",
    )

    private fun fallbackTvEmbeds(id: String, season: Int, episode: Int): List<String> = listOf(
        "https://nhdapi.com/embed/tv/$id/$season/$episode",
        "https://vidnest.fun/tv/$id/$season/$episode",
        "https://player.videasy.net/tv/$id/$season/$episode",
    )

    // ───────────────────────────── Helpers ─────────────────────────────

    private fun LoadResponse.addSyncIds(meta: Meta) {
        runCatching { meta.imdbId?.takeIf { it.isNotBlank() }?.let { addImdbId(it) } }
        runCatching { meta.simklId?.let { addSimklId(it) } }
    }

    private fun Document.titleFromPage(): String =
        selectFirst("h1")?.text()?.trim()?.cleanCardTitle()?.takeIf { it.isNotBlank() }
            ?: selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - Watch")?.trim()
            ?: title().substringBefore("|").substringBefore("-").trim().ifBlank { "Untitled" }

    private fun Document.posterFromPage(): String? =
        selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: selectFirst("img[src*='image.tmdb.org'], img[src*='anilist']")?.imgUrl()

    private fun Document.backdropFromPage(): String? =
        selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.contains("/w1280/") }

    private fun Document.plotFromPage(): String? =
        selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() && !it.contains("Watch movies", true) }
            ?: selectFirst("p.text-gray-300, .overview, .description")?.text()?.trim()?.takeIf { it.isNotBlank() }

    private fun Document.yearFromPage(): Int? =
        Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)").find(text())?.value?.toIntOrNull()

    private fun Element.imgUrl(): String? =
        attr("data-src").ifBlank { attr("src") }.takeIf { it.isNotBlank() }?.toAbsoluteUrl()

    private fun String.extractTmdbId(type: String): String? =
        Regex("(?i)(?:embed/)?$type/(\\d+)").find(this)?.groupValues?.getOrNull(1)

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key, "").trim().takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optString(key, "").toIntOrNull() ?: optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optString(key, "").toDoubleOrNull() ?: optDouble(key, Double.NaN).takeIf { !it.isNaN() }

    private fun JSONArray.toStringList(key: String): List<String> =
        (0 until length()).mapNotNull { i -> optJSONObject(i)?.optStringOrNull(key) }.distinct()

    private fun JSONArray.bestImage(key: String): String? =
        (0 until length()).mapNotNull { i -> optJSONObject(i) }
            .sortedWith(compareByDescending<JSONObject> { if (it.optStringOrNull("iso_639_1") == "en") 2 else 1 }
                .thenByDescending { it.optDoubleOrNull("vote_average") ?: 0.0 })
            .firstOrNull()?.optStringOrNull(key)

    private fun JSONArray.bestYoutubeTrailer(): String? {
        val vids = (0 until length()).mapNotNull { i -> optJSONObject(i) }
            .filter { it.optStringOrNull("site")?.equals("YouTube", true) == true }
        val picked = vids.firstOrNull { it.optStringOrNull("type")?.equals("Trailer", true) == true } ?: vids.firstOrNull()
        return picked?.optStringOrNull("key")?.let { "https://youtu.be/$it" }
    }

    private fun JSONArray.toActors(limit: Int = 20): List<ActorData> =
        (0 until length()).mapNotNull { i ->
            val cast = optJSONObject(i) ?: return@mapNotNull null
            val actorName = cast.optStringOrNull("name") ?: return@mapNotNull null
            val profile = cast.optStringOrNull("profile_path")?.let { tmdbImg("w185", it) }
            val role = cast.optStringOrNull("character")
            ActorData(Actor(actorName, profile), roleString = role ?: "")
        }.take(limit)

    private fun queryString(query: Map<String, Any?>): String {
        val params = query.entries.filter { it.value != null }
            .joinToString("&") { (k, v) -> "${k.encodeUrl()}=${v.toString().encodeUrl()}" }
        return if (params.isBlank()) "" else "?$params"
    }

    private fun tmdbImg(size: String, path: String): String = "$TMDB_IMG/$size$path"

    private fun yearFromDate(date: String?): Int? = date?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

    private fun String.cleanCardTitle(): String = replace("NEW", "").replace(Regex("\\s+"), " ").trim()

    private fun String.cleanMediaTitle(): String =
        cleanCardTitle().replace(Regex("\\[[^]]+]"), "").replace(Regex("(?i)\\b(season \\d+|hindi|dubbed|dual audio|multi audio)\\b"), "").trim().ifBlank { this }

    private fun String.cleanEpisodeTitle(): String = replace(Regex("(?i)^EP\\s*\\d+\\s*"), "").trim().ifBlank { this }

    private fun String.hostName(): String = substringAfter("://").substringBefore("/").ifBlank { this }

    private fun String.isDirectVideo(): Boolean {
        val clean = substringBefore("?").lowercase()
        return clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm") ||
                clean.endsWith(".avi") || clean.endsWith(".mov") || clean.endsWith(".m4v")
    }

    private fun qualityLabelFromUrl(url: String): String? =
        Regex("(?i)(2160p|1440p|1080p|720p|480p|360p|4k)").find(url)?.value

    private fun String.toAbsoluteUrl(baseUrl: String = mainUrl): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http") || startsWith("magnet:") -> this
        startsWith("/") -> baseUrl.substringBefore("://").plus("://").plus(baseUrl.substringAfter("://").substringBefore("/")).plus(this)
        else -> baseUrl.trimEnd('/') + "/" + this
    }

    private fun String.encodeUrl(): String = URLEncoder.encode(this, "UTF-8")

    private fun String.htmlDecode(): String = org.jsoup.Jsoup.parse(this).text()
}
