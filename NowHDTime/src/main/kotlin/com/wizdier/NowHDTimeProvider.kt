// NowHDTimeProvider.kt
package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class NowHDTimeProvider : MainAPI() {

    override var mainUrl              = "https://nowhdtime.com.bd"
    override var name                 = "NowHDTime"
    override val hasMainPage          = true
    override var lang                 = "bn"          // Bangla primary, multilingual content
    override val hasSearch            = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    // ─── Tracking ───────────────────────────────────────────────────────────
    override val supportedSyncNames = setOf(
        SyncIdName.MyAnimeList,
        SyncIdName.Anilist,
        SyncIdName.Kitsu,
        SyncIdName.Simkl,
    )

    companion object {
        private const val TMDB_IMG   = "https://image.tmdb.org/t/p/w500"
        private const val TMDB_WIDE  = "https://image.tmdb.org/t/p/w1280"

        /** Slugify a title for URL construction fallback */
        fun String.toSlug(): String =
            lowercase()
                .replace(Regex("[^a-z0-9\s-]"), "")
                .trim()
                .replace(Regex("\s+"), "-")

        fun Element.textClean(): String = text().trim()
    }

    // ─── Home Page ──────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/"               to "🔥 Trending Now",
        "$mainUrl/movies"         to "🎬 Latest Movies",
        "$mainUrl/tv-shows"       to "📺 TV Shows",
        "$mainUrl/movies?page="   to "🎥 More Movies",
        "$mainUrl/tv-shows?page=" to "📡 More TV Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.endsWith("?page=") -> request.data + page
            else                            -> request.data
        }

        val doc  = app.get(url).document
        val list = mutableListOf<SearchResponse>()

        // Home-page hero carousel items
        doc.select("div[data-slide]").forEach { slide ->
            slide.toSearchResult()?.let { list.add(it) }
        }

        // Standard movie / TV-show listing grid
        doc.select("a[href*='/movie/'], a[href*='/tv-show/']").forEach { a ->
            a.toSearchResultFromCard()?.let { if (it !in list) list.add(it) }
        }

        return newHomePageResponse(request.name, list, hasNext = list.size >= 20)
    }

    // ─── Search ─────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/search",
            params = mapOf("query" to query)
        ).document

        return doc.select("a[href*='/movie/'], a[href*='/tv-show/']")
            .mapNotNull { it.toSearchResultFromCard() }
            .distinctBy { it.url }
    }

    // ─── Load ───────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val doc  = app.get(url).document
        val isTv = url.contains("/tv-show/")

        // ── Common metadata ──────────────────────────────────────────────
        val title       = doc.selectFirst("h2")?.textClean()                    ?: return null
        val poster      = doc.selectFirst("img[src*='tmdb.org/t/p/w500']")?.attr("src")
        val backdrop    = doc.selectFirst("img[src*='tmdb.org/t/p/w1280']")?.attr("src")
        val year        = doc.selectFirst("span:contains(202), span:contains(201), span:contains(200), span:contains(199)")
                            ?.textClean()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val rating      = doc.selectFirst("span:contains(/10)")
                            ?.textClean()?.replace(Regex("[^\d.]"), "")?.toDoubleOrNull()
        val description = doc.selectFirst("div h3:contains(Overview) + p, p.overview")?.textClean()
        val genres      = doc.select("span.genre-tag, div.genres span, p span[class*='genre']")
                            .map { it.textClean() }.filter { it.isNotBlank() }
        val cast        = doc.select("div.cast div, div[class*='cast'] p")
                            .map { ActorData(Actor(it.textClean())) }
                            .filter { it.actor.name.isNotBlank() }
        val trailerUrl  = doc.selectFirst("a[href*='youtube.com'], a[href*='youtu.be']")?.attr("href")

        return if (isTv) {
            loadTvSeries(doc, url, title, poster, backdrop, year, rating, description, genres, cast, trailerUrl)
        } else {
            loadMovie(doc, url, title, poster, backdrop, year, rating, description, genres, cast, trailerUrl)
        }
    }

    // ─── Load Movie ─────────────────────────────────────────────────────────
    private suspend fun loadMovie(
        doc: Document,
        url: String,
        title: String,
        poster: String?,
        backdrop: String?,
        year: Int?,
        rating: Double?,
        description: String?,
        genres: List<String>,
        cast: List<ActorData>,
        trailerUrl: String?,
    ): MovieLoadResponse {
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl    = poster
            this.backgroundPosterUrl = backdrop
            this.year         = year
            this.rating       = rating?.times(1000)?.toInt()
            this.plot         = description
            this.tags         = genres
            addActors(cast)
            trailerUrl?.let { addTrailer(it) }
            // Sync IDs — attempt to parse TMDB ID from TMDB image URL for tracking lookup
            val tmdbId = poster?.substringAfter("/t/p/w500/")?.substringBefore(".")?.toIntOrNull()
            tmdbId?.let { syncData[SyncIdName.TMDB] = it.toString() }
        }
    }

    // ─── Load TV Series ─────────────────────────────────────────────────────
    private suspend fun loadTvSeries(
        doc: Document,
        url: String,
        title: String,
        poster: String?,
        backdrop: String?,
        year: Int?,
        rating: Double?,
        description: String?,
        genres: List<String>,
        cast: List<ActorData>,
        trailerUrl: String?,
    ): TvSeriesLoadResponse {
        val episodes = mutableListOf<Episode>()

        // Parse season / episode links
        val seasonBlocks = doc.select("div[class*='season'], section[class*='season']")

        if (seasonBlocks.isEmpty()) {
            // Flat episode list
            doc.select("a[href*='/episode/'], a[href*='/watch/']").forEachIndexed { idx, a ->
                episodes.add(
                    newEpisode(a.attr("href")) {
                        this.name    = a.textClean().ifBlank { "Episode ${idx + 1}" }
                        this.episode = idx + 1
                    }
                )
            }
        } else {
            seasonBlocks.forEachIndexed { sIdx, sBlock ->
                val seasonNum = sBlock.selectFirst("h3, h4, span")
                    ?.textClean()?.filter { it.isDigit() }?.toIntOrNull() ?: (sIdx + 1)
                sBlock.select("a[href]").forEachIndexed { eIdx, a ->
                    episodes.add(
                        newEpisode(fixUrl(a.attr("href"))) {
                            this.name    = a.textClean().ifBlank { "Episode ${eIdx + 1}" }
                            this.season  = seasonNum
                            this.episode = eIdx + 1
                        }
                    )
                }
            }
        }

        // If no episodes found from HTML, create a watchable entry from current URL
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) { this.name = title })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl           = poster
            this.backgroundPosterUrl = backdrop
            this.year                = year
            this.rating              = rating?.times(1000)?.toInt()
            this.plot                = description
            this.tags                = genres
            addActors(cast)
            trailerUrl?.let { addTrailer(it) }
        }
    }

    // ─── Load Links ─────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = app.get(data).document

        // Collect every potential video source from the page
        val sourceUrls = mutableSetOf<String>()

        // 1. Iframes / embeds in source containers
        doc.select("iframe[src], iframe[data-src]").forEach { el ->
            val src = el.attr("src").ifBlank { el.attr("data-src") }
            if (src.isNotBlank() && !src.startsWith("about:")) sourceUrls.add(src)
        }

        // 2. Data-url buttons / server selectors
        doc.select("[data-url], [data-src], [data-embed]").forEach { el ->
            listOf("data-url", "data-src", "data-embed").forEach { attr ->
                val v = el.attr(attr)
                if (v.isNotBlank() && v.startsWith("http")) sourceUrls.add(v)
            }
        }

        // 3. Script-embedded sources (VidSrc, etc.)
        doc.select("script").forEach { script ->
            val text = script.html()
            Regex("""(https?://[\w./-]+(?:embed|player|stream|vid|watch)[\w./?=&%-]*)""")
                .findAll(text)
                .forEach { sourceUrls.add(it.groupValues[1]) }
        }

        // 4. Direct video tags
        doc.select("source[src], video[src]").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank()) sourceUrls.add(src)
        }

        // 5. Known source patterns from NowHDTime
        val knownProviders = listOf(
            "vidsrc.to", "vidsrc.me", "vidsrc.xyz", "vidsrc.icu",
            "vidplay.online", "vidplay.site",
            "streamwish.com", "streamwish.to",
            "doodstream.com", "dood.la", "dood.re",
            "filemoon.sx", "filemoon.to",
            "streamtape.com",
            "upstream.to",
            "mixdrop.co",
            "embedr.cc",
            "emturbobit.eu",
            "gofile.io",
        )
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (knownProviders.any { href.contains(it) }) sourceUrls.add(href)
        }

        if (sourceUrls.isEmpty()) return false

        var found = false
        sourceUrls.forEach { srcUrl ->
            try {
                loadExtractor(fixUrl(srcUrl), data, subtitleCallback, callback)
                found = true
            } catch (_: Exception) { /* ignore single-source failures */ }
        }

        return found
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Parse a hero carousel slide into a SearchResponse */
    private fun Element.toSearchResult(): SearchResponse? {
        val a    = selectFirst("a[href]")        ?: return null
        val url  = fixUrl(a.attr("href"))        .takeIf { it.isNotBlank() } ?: return null
        val img  = selectFirst("img[src]")
        val name = selectFirst("h2")?.textClean() ?: img?.attr("alt")?.trim() ?: return null
        val year = selectFirst("span")?.textClean()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val type = if (url.contains("/tv-show/")) TvType.TvSeries else TvType.Movie

        return newAnimeSearchResponse(name, url, type) {
            this.posterUrl = img?.attr("src")
            addDubStatus(dubExist = false, subExist = true)
        }.also { it as SearchResponse }
    }

    /** Parse a grid card anchor into a SearchResponse */
    private fun Element.toSearchResultFromCard(): SearchResponse? {
        val url  = fixUrl(attr("href")).takeIf { it.isNotBlank() } ?: return null
        val img  = selectFirst("img[src]")
        val name = (selectFirst("p, h4, h3, h2, div > p")?.textClean()
            ?: img?.attr("alt")?.trim()
            ?: return null).let {
                // strip year suffixes e.g. " (2026)"
                it.replace(Regex("\s*\(\d{4}\)\s*$"), "").trim()
            }
        if (name.isBlank()) return null

        val year = selectFirst("span")?.textClean()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val type = if (url.contains("/tv-show/")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(name, url, type) {
            this.posterUrl = img?.attr("src")
            this.year      = year
        }
    }

    private fun fixUrl(url: String): String = when {
        url.startsWith("//")   -> "https:$url"
        url.startsWith("/")    -> "$mainUrl$url"
        url.startsWith("http") -> url
        else                   -> "$mainUrl/$url"
    }
}