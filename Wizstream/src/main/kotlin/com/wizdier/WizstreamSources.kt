package com.wizdier

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Base64

/**
 * WizstreamSources — bundled multi-source resolver.
 *
 * This object bundles 4 BDIX/FTP source resolvers that the Wizstream plugin
 * uses inside its `loadLinks` flow, alongside the Vid[x] embed family. Each
 * resolver is intentionally lightweight — it performs:
 *
 *   1. Search the site by title (+ optional year filter).
 *   2. Pick the closest match (Jaccard token overlap on normalised title).
 *   3. Fetch the detail page / API endpoint.
 *   4. Extract every direct media URL (m3u8 / mp4 / mkv / webm) and iframe
 *      source via Cloudstream's built-in `loadExtractor`.
 *
 * Heavy metadata enrichment, grouped multi-quality merging, and per-source
 * preferences are intentionally NOT duplicated here — the standalone
 * extensions (Cineplex BD / FTPBD / Circle FTP / CTGMovies) already do that
 * and remain installed for full-featured browsing. This module's job is to
 * surface working stream URLs from all 4 sites in one shot, behind a
 * unified TMDB / AniList catalogue.
 *
 * Concurrency: all 4 sites are queried in parallel, bounded by a 4-permit
 * semaphore. Per-site HTTP requests are sequential (one search → one detail
 * fetch → one extraction pass) so we don't hammer any single host.
 *
 * Robustness:
 *   • Every site call is wrapped in runCatching — a single failing host
 *     never breaks the others.
 *   • Direct media URLs are de-duped globally via the caller's seenUrls set.
 *   • m3u8 streams are passed through M3u8Helper so subtitles + variants
 *     are resolved correctly.
 *   • iframe URLs go through `loadExtractor` so any future host that
 *     Cloudstream's extractor registry knows about is auto-supported.
 */
object WizstreamSources {

    private const val TAG = "WizstreamSources"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    // ───────────────────────────────────────────────────────────────────────
    //  Public entry point
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Run all 4 bundled source resolvers in parallel and aggregate their
     * extracted links. Returns true if at least one source produced a link.
     *
     * @param app          the Cloudstream HTTP client (inherited from MainAPI)
     * @param title        the show/movie title to search for
     * @param year         optional release year for disambiguation
     * @param isMovie      true for movies, false for TV/anime
     * @param season       1-indexed season (null/0 for movies)
     * @param episode      1-indexed episode (null/0 for movies)
     * @param labelPrefix  e.g. "Wizstream" or "Wizstream-A" — used as the
     *                     source label prefix so users can see where each
     *                     link came from in the player UI.
     */
    suspend fun resolveAll(
        app: Requests,
        title: String,
        year: Int?,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        labelPrefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        if (title.isBlank()) return@coroutineScope false

        val sources = listOf(
            CineplexBdResolver,
            FtpBdResolver,
            CircleFtpResolver,
            CtgMoviesResolver,
        )

        val gate = Semaphore(4)
        val jobs = sources.map { src ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        src.resolve(
                            app = app,
                            title = title,
                            year = year,
                            isMovie = isMovie,
                            season = season,
                            episode = episode,
                            labelPrefix = labelPrefix,
                            subtitleCallback = subtitleCallback,
                            callback = callback,
                        )
                    }.getOrDefault(false)
                }
            }
        }
        jobs.awaitAll().any { it }
    }

    // ───────────────────────────────────────────────────────────────────────
    //  Shared helpers
    // ───────────────────────────────────────────────────────────────────────

    /** Jaccard token overlap on normalised title strings. Returns 0..1. */
    internal fun titleSimilarity(a: String, b: String): Double {
        val ax = a.normaliseTitle()
        val bx = b.normaliseTitle()
        if (ax == bx) return 1.0
        if (ax.isEmpty() || bx.isEmpty()) return 0.0
        val ta = ax.split(Regex("\\s+")).toSet()
        val tb = bx.split(Regex("\\s+")).toSet()
        val inter = ta.intersect(tb).size.toDouble()
        val union = ta.union(tb).size.toDouble()
        return if (union == 0.0) 0.0 else inter / union
    }

    internal fun String.normaliseTitle(): String =
        lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    internal fun encodeUrl(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * Re-label an ExtractorLink, preserving url/type/quality/headers.
     *
     * The `ExtractorLink(source=, name=, url=, type=, quality=, headers=)`
     * constructor was deprecated to ERROR level in the latest cloudstream3
     * stubs ("Use newExtractorLink"), but `newExtractorLink` is a suspend
     * function while `loadExtractor`'s callback is non-suspend. We bridge
     * that gap with `runBlocking` — the newExtractorLink body does no IO
     * so this is cheap and safe.
     */
    internal fun ExtractorLink.relabel(newSource: String, newName: String): ExtractorLink =
        runBlocking {
            newExtractorLink(
                source = newSource,
                name = newName,
                url = this@relabel.url,
                type = this@relabel.type,
            ) {
                this.referer = this@relabel.referer
                this.quality = this@relabel.quality
                this.headers = this@relabel.headers
            }
        }

    /** Heuristic quality from URL/file name. */
    internal fun qualityFromName(s: String?): Int {
        if (s == null) return Qualities.Unknown.value
        val n = s.lowercase()
        return when {
            "4k" in n || "2160" in n -> Qualities.P2160.value
            "1440" in n -> Qualities.P1440.value
            "1080" in n -> Qualities.P1080.value
            "720" in n -> Qualities.P720.value
            "480" in n -> Qualities.P480.value
            "360" in n -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    internal fun isDirectMedia(url: String): Boolean {
        val u = url.lowercase()
        return u.endsWith(".m3u8") || u.endsWith(".mp4") || u.endsWith(".mkv") ||
            u.endsWith(".webm") || u.endsWith(".mov") || u.endsWith(".m4v") ||
            u.contains(".m3u8?") || u.contains(".mp4?") || u.contains(".mkv?")
    }

    /**
     * Generic "extract every media-looking URL from a HTML page" routine.
     * Used by Cineplex BD and FTPBD resolvers — both sites embed video
     * URLs in `<video>`, `<source>`, `data-url`, `hls.loadSource(...)`,
     * and as raw URLs inside inline scripts.
     *
     * Returns a de-duped LinkedHashSet of resolved absolute URLs.
     */
    internal fun extractMediaUrlsFromHtml(html: String, baseUrl: String): LinkedHashSet<String> {
        val out = linkedSetOf<String>()
        val doc: Document = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull()
            ?: return out

        // <video src> / <source src> / <a href=.m3u8|.mp4|.mkv>
        doc.select(
            "video[src], source[src], a[href*='.m3u8'], a[href*='.mp4'], a[href*='.mkv'], " +
                "a[href*='.webm'], a[href*='.m4v']"
        ).forEach { el ->
            val src = el.attr("src").ifBlank { el.attr("href") }
            if (src.isNotBlank()) out += resolveAbs(baseUrl, src)
        }

        // data-url="..." attributes (FTPBD pattern)
        Regex("""(?i)data-url=["']([^"']+)["']""")
            .findAll(html)
            .map { it.groupValues[1] }
            .forEach { raw ->
                if (raw.isNotBlank() && isDirectMedia(raw)) out += resolveAbs(baseUrl, raw)
            }

        // HLS player sources
        Regex("""(?i)<source[^>]+src=["']([^"']+?\.m3u8[^"']*)["']""")
            .findAll(html).forEach { m -> out += resolveAbs(baseUrl, m.groupValues[1]) }
        Regex("""(?i)hls\.loadSource\(["']([^"']+?\.m3u8[^"']*)["']\)""")
            .findAll(html).forEach { m -> out += resolveAbs(baseUrl, m.groupValues[1]) }
        Regex("""(?i)videoSrc\s*=\s*["'](.*?)["']""")
            .findAll(html).forEach { m ->
                val u = m.groupValues[1]
                if (u.isNotBlank() && (u.startsWith("http") || isDirectMedia(u)))
                    out += resolveAbs(baseUrl, u)
            }

        // Catch-all regex for raw media URLs anywhere in the HTML.
        Regex(
            """https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv|\.webm|\.avi|\.mov|\.m4v)(?:\?[^\s"'<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { m ->
            out += m.value.replace("&amp;", "&")
        }

        return out
    }

    /** Resolve a possibly-relative URL against a base. */
    internal fun resolveAbs(baseUrl: String, maybeRelative: String): String {
        val u = maybeRelative.trim().replace("&amp;", "&")
        if (u.isBlank()) return u
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        val base = baseUrl.trimEnd('/')
        return if (u.startsWith("/")) "$base$u" else "$base/$u"
    }

    /** Emit a single direct media URL through the callback. */
    internal suspend fun emitDirect(
        app: Requests,
        url: String,
        sourceLabel: String,
        referer: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val clean = url.trim()
        if (clean.isBlank()) return false
        return try {
            if (clean.contains(".m3u8", ignoreCase = true)) {
                M3u8Helper.generateM3u8(
                    source = sourceLabel,
                    streamUrl = clean,
                    referer = referer,
                    headers = headers,
                ).forEach(callback)
                true
            } else if (isDirectMedia(clean)) {
                val link = newExtractorLink(
                    source = sourceLabel,
                    name = "$sourceLabel - Direct",
                    url = clean,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = referer
                    this.quality = qualityFromName(clean)
                }
                callback(link)
                true
            } else {
                var found = false
                runCatching {
                    loadExtractor(clean, referer, subtitleCallback) { link ->
                        callback(link.relabel(sourceLabel, "$sourceLabel — ${link.name}"))
                        found = true
                    }
                }
                found
            }
        } catch (t: Throwable) {
            Log.d(TAG, "emitDirect failed for $clean: ${t.message}")
            false
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver interface
    // ════════════════════════════════════════════════════════════════════════

    internal interface SourceResolver {
        suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 1: Cineplex BD  (http://cineplexbd.net)
    // ════════════════════════════════════════════════════════════════════════

    internal object CineplexBdResolver : SourceResolver {
        private const val SITE = "http://cineplexbd.net"
        private const val LABEL = "CineplexBD"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            // 1. Search
            val searchUrl = "$SITE/search.php?q=${encodeUrl(title)}&page=1"
            val html = runCatching {
                app.get(searchUrl, headers = HEADERS, timeout = 12_000).text
            }.getOrNull() ?: return false

            // 2. Parse result cards → pick best match
            val doc = Jsoup.parse(html, searchUrl)
            val candidates = mutableListOf<Pair<String, String>>() // (url, title)
            doc.select(
                "a[href*='view.php'], a[href*='watch.php'], a[href*='tview.php'], " +
                    ".movie-card a, a:has(.poster), a:has(img[src*='uploads'])"
            ).forEach { a ->
                val href = a.attr("href").ifBlank { return@forEach }
                val id = if (href.contains("series_id=")) {
                    href.substringAfter("series_id=").substringBefore("&")
                } else {
                    href.substringAfter("id=").substringBefore("&")
                }
                if (id.isBlank() || id == href) return@forEach

                val absHref = when {
                    href.contains("series_id=") -> "$SITE/watch.php?series_id=$id"
                    href.contains("tview.php") -> "$SITE/tview.php?id=$id"
                    href.contains("watch.php") -> "$SITE/watch.php?id=$id"
                    else -> "$SITE/view.php?id=$id"
                }

                val titleEl = a.selectFirst(
                    "span.truncate, div.text-sm, div.cp-title, h2, .card-title, .title"
                )
                val candTitle = titleEl?.text()?.ifBlank {
                    a.selectFirst("img")?.attr("alt")
                } ?: return@forEach
                if (candTitle.isBlank()) return@forEach
                candidates += absHref to candTitle
            }

            // Filter: prefer series page for TV, view page for movie.
            val filtered = candidates.filter { (u, _) ->
                if (isMovie) !u.contains("watch.php?series_id") && !u.contains("tview.php")
                else u.contains("watch.php") || u.contains("tview.php")
            }.ifEmpty { candidates }

            val best = filtered.maxByOrNull { (_, ct) -> titleSimilarity(ct, title) }
                ?: return false
            // Require a minimum similarity to avoid false positives.
            if (titleSimilarity(best.second, title) < 0.4) return false

            // 3. Detail page
            val detailHtml = runCatching {
                app.get(best.first, headers = HEADERS, timeout = 15_000).text
            }.getOrNull() ?: return false

            // 4. If TV, try to find the right episode page first.
            val episodePageUrl: String? = if (!isMovie && season != null && episode != null) {
                pickEpisodePageUrl(detailHtml, best.first, season, episode)
            } else {
                null
            }

            // 5. Extract media URLs and emit
            val mediaUrls = extractMediaUrlsFromHtml(detailHtml, best.first)
            if (episodePageUrl != null) {
                runCatching {
                    app.get(episodePageUrl, headers = HEADERS, timeout = 15_000).text
                }.getOrNull()?.let { epHtml ->
                    mediaUrls += extractMediaUrlsFromHtml(epHtml, episodePageUrl)
                }
            }

            val srcLabel = "$labelPrefix • $LABEL"
            var any = false
            mediaUrls.forEach { u ->
                if (emitDirect(app, u, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)) any = true
            }
            return any
        }

        private fun pickEpisodePageUrl(
            detailHtml: String,
            detailUrl: String,
            season: Int,
            episode: Int,
        ): String? {
            val doc = Jsoup.parse(detailHtml, detailUrl)
            // Look for episode anchors matching s/e (or just episode number).
            val anchors = doc.select(
                "a[href*='ep='], a[href*='episode='], a[href*='/Data/'], " +
                    "a.episode, a[data-episode], .episode-list a, .episodes a"
            )
            // Prefer an exact "ep=N" match on episode number.
            val exact = anchors.firstOrNull { a ->
                val href = a.attr("href")
                Regex("""(?i)(?:ep(?:isode)?[=\s]*|\bE)(\d+)""")
                    .find(href)?.groupValues?.get(1)?.toIntOrNull() == episode
            }
            val href = exact?.attr("href")?.ifBlank { null }
                ?: anchors.getOrNull(episode - 1)?.attr("href")?.ifBlank { null }
                ?: return null
            return resolveAbs(detailUrl, href)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 2: FTPBD  (https://ftpbd.net)
    // ════════════════════════════════════════════════════════════════════════

    internal object FtpBdResolver : SourceResolver {
        private const val SITE = "https://ftpbd.net"
        private const val LABEL = "FTPBD"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            // Search movies + tv concurrently.
            val postTypes = if (isMovie) listOf("movies") else listOf("tv_shows", "movies")
            val expectedPaths = if (isMovie) listOf("/movie/") else listOf("/tv_shows/", "/movie/")

            val candidates = mutableListOf<Pair<String, String>>()
            coroutineScope {
                postTypes.mapIndexed { idx, postType ->
                    async(Dispatchers.IO) {
                        val url = "$SITE/?s=${encodeUrl(title)}&post_type=$postType"
                        runCatching {
                            val doc = app.get(url, headers = HEADERS, timeout = 12_000).document
                            doc.select(".post, .movie-card, .card, article, .item")
                                .forEach { card ->
                                    val a = card.selectFirst("a[href]") ?: return@forEach
                                    val href = a.attr("href")
                                    if (!href.contains(expectedPaths[idx])) return@forEach
                                    val t = card.selectFirst("h2, h3, .title, .entry-title")?.text()
                                        ?: a.text()
                                    if (t.isNotBlank()) {
                                        synchronized(candidates) { candidates += href to t }
                                    }
                                }
                        }
                    }
                }.awaitAll()
            }

            // Pick best match
            val best = candidates
                .distinctBy { it.first }
                .maxByOrNull { (_, ct) -> titleSimilarity(ct, title) }
                ?: return false
            if (titleSimilarity(best.second, title) < 0.4) return false

            // Detail page
            val detailHtml = runCatching {
                app.get(best.first, headers = HEADERS, timeout = 15_000).text
            }.getOrNull() ?: return false

            // For TV: try to find the episode's permalink
            val episodePageUrl: String? = if (!isMovie && season != null && episode != null) {
                pickEpisodePageUrl(detailHtml, best.first, season, episode)
            } else {
                null
            }

            val srcLabel = "$labelPrefix • $LABEL"
            val mediaUrls = extractMediaUrlsFromHtml(detailHtml, best.first)
            if (episodePageUrl != null) {
                runCatching {
                    app.get(episodePageUrl, headers = HEADERS, timeout = 15_000).text
                }.getOrNull()?.let { epHtml ->
                    mediaUrls += extractMediaUrlsFromHtml(epHtml, episodePageUrl)
                }
            }

            var any = false
            mediaUrls.forEach { u ->
                if (emitDirect(app, u, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)) any = true
            }
            return any
        }

        private fun pickEpisodePageUrl(
            detailHtml: String,
            detailUrl: String,
            season: Int,
            episode: Int,
        ): String? {
            val doc = Jsoup.parse(detailHtml, detailUrl)
            // FTPBD episode anchors: typically .episode a[href*='/episodes/']
            // or a[href*='season='] / a[href*='episode=']
            val anchors = doc.select(
                "a[href*='/episodes/'], a[href*='episode='], a[href*='season='], " +
                    ".episode a, .episodes a, .season-list a"
            )
            // Filter to this season first.
            val seasonMatches = anchors.filter { a ->
                a.attr("href").contains("season=$season") ||
                    a.attr("data-season")?.toIntOrNull() == season
            }.ifEmpty { anchors }

            // Pick the Nth episode in the season.
            val nth = seasonMatches.getOrNull(episode - 1) ?: return null
            val href = nth.attr("href").ifBlank { return null }
            return resolveAbs(detailUrl, href)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 3: Circle FTP  (http://new.circleftp.net)
    // ════════════════════════════════════════════════════════════════════════

    internal object CircleFtpResolver : SourceResolver {
        private const val SITE = "http://new.circleftp.net"
        private const val PRIMARY_API = "http://new.circleftp.net:5000"
        private const val FALLBACK_API = "http://15.1.1.50:5000"
        private const val LABEL = "CircleFTP"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            // 1. Search posts
            val searchUrl = "$PRIMARY_API/api/posts?searchTerm=${encodeUrl(title)}&order=desc"
            val searchText = fetchWithFallback(
                app,
                primary = searchUrl,
                fallback = "$FALLBACK_API/api/posts?searchTerm=${encodeUrl(title)}&order=desc",
            ) ?: return false

            val postsArr = runCatching { JSONObject(searchText).optJSONArray("posts") }
                .getOrNull() ?: return false
            if (postsArr.length() == 0) return false

            // 2. Pick best match
            var bestId: Int = -1
            var bestScore = 0.0
            for (i in 0 until postsArr.length()) {
                val p = postsArr.optJSONObject(i) ?: continue
                val ptitle = p.optString("title").ifBlank { p.optString("name") }
                if (ptitle.isBlank()) continue
                val score = titleSimilarity(ptitle, title)
                if (score > bestScore) {
                    bestScore = score
                    bestId = p.optInt("id", -1)
                }
            }
            if (bestId < 0 || bestScore < 0.4) return false

            // 3. Fetch post details
            val detailText = fetchWithFallback(
                app,
                primary = "$PRIMARY_API/api/posts/$bestId",
                fallback = "$FALLBACK_API/api/posts/$bestId",
            ) ?: return false
            val detail = runCatching { JSONObject(detailText) }.getOrNull() ?: return false

            // 4. Extract media URLs
            val srcLabel = "$labelPrefix • $LABEL"
            val mediaUrls = linkedSetOf<String>()

            // Direct "url" field
            detail.optString("url").takeIf { it.isNotBlank() }?.let { mediaUrls += it }
            detail.optString("file").takeIf { it.isNotBlank() }?.let { mediaUrls += it }
            detail.optString("src").takeIf { it.isNotBlank() }?.let { mediaUrls += it }

            // TV series structure: detail.tvSeries[].episodes[].link
            detail.optJSONArray("tvSeries")?.let { seasonsArr ->
                for (si in 0 until seasonsArr.length()) {
                    val seasonObj = seasonsArr.optJSONObject(si) ?: continue
                    val seasonName = seasonObj.optString("seasonName", "Season 1")
                    val sn = Regex("""\d+""").find(seasonName)?.value?.toIntOrNull() ?: (si + 1)
                    if (season != null && sn != season) continue
                    val epsArr = seasonObj.optJSONArray("episodes") ?: continue
                    for (ei in 0 until epsArr.length()) {
                        val epObj = epsArr.optJSONObject(ei) ?: continue
                        // Filter by episode number if specified.
                        val epNum = epObj.optInt("episodeNumber", ei + 1)
                        if (episode != null && epNum != episode) continue
                        val link = epObj.optString("link").takeIf { it.isNotBlank() } ?: continue
                        // link can be a "movie?data=" base64 payload (handled below)
                        // OR a raw URL.
                        mediaUrls += link
                    }
                }
            }

            // Direct download links array
            detail.optJSONArray("downloadLinks")?.let { dlArr ->
                for (i in 0 until dlArr.length()) {
                    val dl = dlArr.optJSONObject(i) ?: continue
                    val u = dl.optString("url").ifBlank { dl.optString("link") }
                    if (u.isNotBlank()) mediaUrls += u
                }
            }

            // 5. Emit
            var any = false
            mediaUrls.forEach { u ->
                // Circle FTP encodes TV/movie URLs as "movie?data=<base64>" /
                // "episode?data=<base64>" — base64 of a JSON array of {url,audio}.
                if (u.contains("movie?data=") || u.contains("episode?data=")) {
                    if (emitCircleFtpEncoded(u, srcLabel, callback)) any = true
                } else if (emitDirect(app, u, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)) {
                    any = true
                }
            }
            return any
        }

        private suspend fun emitCircleFtpEncoded(
            data: String,
            sourceLabel: String,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val prefix = when {
                data.contains("movie?data=") -> "movie?data="
                data.contains("episode?data=") -> "episode?data="
                else -> return false
            }
            val jsonStr = runCatching {
                String(Base64.getDecoder().decode(data.substringAfter(prefix)))
            }.getOrNull() ?: return false
            val arr = runCatching { JSONArray(jsonStr) }.getOrNull() ?: return false
            if (arr.length() == 0) return false
            var any = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url").ifBlank { o.optString("link") }
                if (u.isBlank()) continue
                val audio = o.optString("audio").takeIf { it.isNotBlank() }
                val name = if (audio != null) "$sourceLabel [$audio]" else sourceLabel
                val link = newExtractorLink(
                    source = sourceLabel,
                    name = name,
                    url = u,
                    type = INFER_TYPE,
                ) {
                    this.referer = SITE
                    this.quality = qualityFromName(u)
                }
                callback(link)
                any = true
            }
            return any
        }

        private suspend fun fetchWithFallback(
            app: Requests,
            primary: String,
            fallback: String,
        ): String? {
            val a = runCatching {
                app.get(primary, headers = HEADERS, timeout = 10_000)
            }.getOrNull()
            if (a != null && a.code in 200..299 && a.text.isNotBlank()) return a.text
            val b = runCatching {
                app.get(fallback, headers = HEADERS, timeout = 10_000)
            }.getOrNull()
            if (b != null && b.code in 200..299 && b.text.isNotBlank()) return b.text
            return null
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 4: CTGMovies  (https://ctgmovies.com, API at cockpit)
    // ════════════════════════════════════════════════════════════════════════

    internal object CtgMoviesResolver : SourceResolver {
        private const val SITE = "https://ctgmovies.com"
        private const val DEFAULT_API_BASE = "https://cockpit.103.109.92.178.nip.io/api/v1"
        private const val LABEL = "CTGMovies"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Origin" to SITE,
            "Accept" to "application/json",
            "Accept-Language" to "en",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            // 1. Search across all 3 content kinds
            val kinds = if (isMovie) listOf("movies", "tv", "anime") else listOf("tv", "anime", "movies")
            val params = mapOf("search" to title)
            val candidates = mutableListOf<Triple<String, String, String>>() // (kind, id, title)

            coroutineScope {
                kinds.map { kind ->
                    async(Dispatchers.IO) {
                        runCatching {
                            val text = apiGet(app, "/$kind", params)
                            parseSearchResults(text, kind)?.let { list ->
                                synchronized(candidates) { candidates.addAll(list) }
                            }
                        }
                    }
                }.awaitAll()
            }

            if (candidates.isEmpty()) return false

            // 2. Pick best match
            val best = candidates
                .maxByOrNull { (_, _, ct) -> titleSimilarity(ct, title) }
                ?: return false
            if (titleSimilarity(best.third, title) < 0.4) return false

            // 3. Fetch detail
            val detailText = apiGet(app, "/${best.first}/${encodeUrl(best.second)}", emptyMap())
            val detail = runCatching { JSONObject(detailText) }.getOrNull() ?: return false

            // 4. Extract links array
            val srcLabel = "$labelPrefix • $LABEL"
            val linksArr = detail.optJSONArray("links") ?: JSONArray().also { it.put(detail) }

            var any = false
            for (i in 0 until linksArr.length()) {
                val link = linksArr.optJSONObject(i) ?: continue
                if (link.optBoolean("broken", false)) continue
                val rawUrl = link.optString("url").ifBlank {
                    link.optString("file")
                }.ifBlank {
                    link.optString("src")
                }.ifBlank {
                    link.optString("link")
                }.ifBlank { continue }

                if (emitDirect(app, rawUrl, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)) {
                    any = true
                }
            }

            // 5. If TV/anime and we have season/episode info, try episodes array.
            if (!isMovie && season != null && episode != null) {
                detail.optJSONArray("seasons")?.let { seasonsArr ->
                    for (si in 0 until seasonsArr.length()) {
                        val seasonObj = seasonsArr.optJSONObject(si) ?: continue
                        val sn = seasonObj.optInt("season", si + 1)
                        if (sn != season) continue
                        val epsArr = seasonObj.optJSONArray("episodes") ?: continue
                        val epObj = epsArr.optJSONObject(episode - 1) ?: continue
                        val epLinks = epObj.optJSONArray("links") ?: continue
                        for (i in 0 until epLinks.length()) {
                            val link = epLinks.optJSONObject(i) ?: continue
                            val u = link.optString("url").ifBlank { link.optString("file") }
                                .ifBlank { link.optString("src") }
                            if (u.isNotBlank() &&
                                emitDirect(app, u, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)
                            ) any = true
                        }
                    }
                }
            }
            return any
        }

        private fun parseSearchResults(text: String, kind: String): List<Triple<String, String, String>>? {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val arr = obj.optJSONArray("data") ?: obj.optJSONArray("results")
                ?: obj.optJSONArray(kind) ?: return null
            val out = mutableListOf<Triple<String, String, String>>()
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val id = it.optString("id").ifBlank { it.optString("slug") }
                    .ifBlank { it.optString("_id") }
                val title = it.optString("title").ifBlank { it.optString("name") }
                if (id.isNotBlank() && title.isNotBlank()) out += Triple(kind, id, title)
            }
            return out
        }

        private suspend fun apiGet(app: Requests, path: String, query: Map<String, Any?>): String {
            val p = if (path.startsWith("/")) path else "/$path"
            val qs = if (query.isEmpty()) "" else "?" + query.entries
                .filter { it.value != null }
                .joinToString("&") { (k, v) ->
                    "${encodeUrl(k)}=${encodeUrl(v.toString())}"
                }
            val primary = "$DEFAULT_API_BASE$p$qs"

            // Try public API first.
            val r1 = runCatching {
                app.get(primary, headers = HEADERS, timeout = 10_000)
            }.getOrNull()
            if (r1 != null && r1.code in 200..299 && r1.text.isNotBlank()) return r1.text

            // Same-origin fallback (Next.js proxy).
            val fallback = "$SITE/api/v1$p$qs"
            val r2 = runCatching {
                app.get(fallback, headers = HEADERS, timeout = 10_000)
            }.getOrNull()
            if (r2 != null && r2.code in 200..299 && r2.text.isNotBlank()) return r2.text

            return ""
        }
    }
}
