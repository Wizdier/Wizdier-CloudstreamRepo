package com.wizdier

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
<<<<<<< HEAD
import com.lagradost.cloudstream3.network.CloudflareKiller
=======
>>>>>>> FETCH_HEAD
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64

/**
 * WizstreamSources — bundled multi-source resolver.
 *
<<<<<<< HEAD
 * Each resolver is a 1:1 port of the parser used by the corresponding
 * standalone extension, so behaviour matches exactly.
=======
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
 * The HTML parsers below are 1:1 ports of the parsers used by the
 * standalone extensions (CineplexBDProvider, FTPBDProvider,
 * CircleFtpProvider, CTGMoviesProvider) — the selectors and JSON paths
 * match exactly so behaviour is identical.
 *
 * Concurrency: all 4 sites are queried in parallel, bounded by a 4-permit
 * semaphore. Per-site HTTP requests are sequential (one search → one detail
 * fetch → one extraction pass) so we don't hammer any single host.
>>>>>>> FETCH_HEAD
 */
object WizstreamSources {

    private const val TAG = "WizstreamSources"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

<<<<<<< HEAD
=======
    // ───────────────────────────────────────────────────────────────────────
    //  Public entry point
    // ───────────────────────────────────────────────────────────────────────

>>>>>>> FETCH_HEAD
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

<<<<<<< HEAD
    /**
     * Title similarity score (Jaccard token overlap, 0..1). Used as a
     * secondary check after exact-match normalised comparison.
     */
=======
>>>>>>> FETCH_HEAD
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

<<<<<<< HEAD
    /**
     * Ported from FTPBDProvider.cleanMediaTitle + normalizedTitle. Strips
     * `[Hindi Dubbed]`, `Season N`, etc. from titles before comparison so
     * anime like "One Piece [Hindi Dubbed]" matches the search query
     * "One Piece".
     */
    internal fun String.cleanMediaTitle(): String =
        replace(Regex("\\s*[–-]\\s*\\[[^]]+]\\s*$"), "")
            .replace(Regex("\\[[^]]+]"), "")
            .replace(Regex("(?i)\\b(hindi|dubbed|dual audio|multi audio|season \\d+|eng sub|bengali|korean|english|japanese|subbed)\\b"), "")
            .trim()
            .ifBlank { this }

    internal fun String.normaliseTitle(): String =
        cleanMediaTitle()
            .lowercase()
=======
    internal fun String.normaliseTitle(): String =
        lowercase()
>>>>>>> FETCH_HEAD
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    internal fun encodeUrl(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

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

    internal fun resolveAbs(baseUrl: String, maybeRelative: String): String {
        val u = maybeRelative.trim().replace("&amp;", "&")
        if (u.isBlank()) return u
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        val base = baseUrl.trimEnd('/')
        return if (u.startsWith("/")) "$base$u" else "$base/$u"
    }

<<<<<<< HEAD
=======
    /**
     * Generic "extract every media-looking URL from a HTML page" routine.
     * Ported from FTPBDProvider.loadLinksFromPage + CineplexBDProvider.loadLinks.
     * Returns a de-duped LinkedHashSet of resolved absolute URLs.
     */
>>>>>>> FETCH_HEAD
    internal fun extractMediaUrlsFromHtml(html: String, baseUrl: String): LinkedHashSet<String> {
        val out = linkedSetOf<String>()
        val doc: Document = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull()
            ?: return out

<<<<<<< HEAD
=======
        // <video src> / <source src> / <a href=.m3u8|.mp4|.mkv>
>>>>>>> FETCH_HEAD
        doc.select(
            "video[src], source[src], a[href*='.m3u8'], a[href*='.mp4'], a[href*='.mkv'], " +
                "a[href*='.webm'], a[href*='.m4v']"
        ).forEach { el ->
            val src = el.attr("src").ifBlank { el.attr("href") }
            if (src.isNotBlank()) out += resolveAbs(baseUrl, src)
        }

<<<<<<< HEAD
        // iframe srcs — many vid hosts wrap the actual video in an iframe.
        doc.select("iframe[src]").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                out += resolveAbs(baseUrl, src)
            }
        }

=======
        // data-url="..." attributes (FTPBD pattern)
>>>>>>> FETCH_HEAD
        Regex("""(?i)data-url=["']([^"']+)["']""")
            .findAll(html)
            .map { it.groupValues[1] }
            .forEach { raw ->
                if (raw.isNotBlank() && isDirectMedia(raw)) out += resolveAbs(baseUrl, raw)
            }

<<<<<<< HEAD
=======
        // HLS player sources
>>>>>>> FETCH_HEAD
        Regex("""(?i)<source[^>]+src=["']([^"']+?\.m3u8[^"']*)["']""")
            .findAll(html).forEach { m -> out += resolveAbs(baseUrl, m.groupValues[1]) }
        Regex("""(?i)hls\.loadSource\(["']([^"']+?\.m3u8[^"']*)["']\)""")
            .findAll(html).forEach { m -> out += resolveAbs(baseUrl, m.groupValues[1]) }
<<<<<<< HEAD
        Regex("""(?i)(?:const|var)\s+videoSrc\s*=\s*["'](.*?)["']""")
=======
        // CineplexBD's videoSrc JS pattern
        Regex("""(?i)const\s+videoSrc\s*=\s*["'](.*?)["']""")
>>>>>>> FETCH_HEAD
            .findAll(html).forEach { m ->
                val u = m.groupValues[1]
                if (u.isNotBlank() && (u.startsWith("http") || isDirectMedia(u)))
                    out += resolveAbs(baseUrl, u)
            }

<<<<<<< HEAD
=======
        // Catch-all regex for raw media URLs anywhere in the HTML.
>>>>>>> FETCH_HEAD
        Regex(
            """https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv|\.webm|\.avi|\.mov|\.m4v)(?:\?[^\s"'<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { m ->
            out += m.value.replace("&amp;", "&")
        }

        return out
    }

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

<<<<<<< HEAD
=======
    // ════════════════════════════════════════════════════════════════════════
    //  Resolver interface
    // ════════════════════════════════════════════════════════════════════════

>>>>>>> FETCH_HEAD
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
<<<<<<< HEAD
    //  Parser ported from CineplexBDProvider.kt — uses /player.php?id=$id
    //  indirection (movies) and watch.php?…&meta=1 JSON endpoint (TV).
=======
    //  Parser ported from CineplexBDProvider.kt — uses the exact same
    //  selectors + the `watch.php?…&meta=1` JSON endpoint for episodes.
>>>>>>> FETCH_HEAD
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
<<<<<<< HEAD
            // 1. Search via search.php — same selectors as CineplexBDProvider.
=======
            // 1. Search — uses the same search.php endpoint + selectors as
            //    CineplexBDProvider.parseAndGroupSearchItems.
>>>>>>> FETCH_HEAD
            val searchUrl = "$SITE/search.php?q=${encodeUrl(title)}&page=1"
            val html = runCatching {
                app.get(searchUrl, headers = HEADERS, timeout = 12_000).text
            }.getOrNull() ?: return false

            val doc = Jsoup.parse(html, searchUrl)
<<<<<<< HEAD
            val candidates = mutableListOf<Pair<String, String>>()
=======
            val candidates = mutableListOf<Pair<String, String>>() // (url, title)
            // EXACT same selector as CineplexBDProvider.kt:646-649
>>>>>>> FETCH_HEAD
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
<<<<<<< HEAD
=======

                // Same title selectors as CineplexBDProvider.Element.toSearchItem
>>>>>>> FETCH_HEAD
                val titleEl = a.selectFirst(
                    "span.truncate, div.text-sm, div.cp-title, h2, .card-title, .title"
                )
                val candTitle = titleEl?.text()?.ifBlank {
                    a.selectFirst("img")?.attr("alt")
                } ?: return@forEach
                if (candTitle.isBlank()) return@forEach
                candidates += absHref to candTitle
            }

            if (candidates.isEmpty()) return false

            // Filter: prefer series page for TV, view page for movie.
            val filtered = candidates.filter { (u, _) ->
                if (isMovie) !u.contains("watch.php?series_id") && !u.contains("tview.php")
                else u.contains("watch.php") || u.contains("tview.php")
            }.ifEmpty { candidates }

<<<<<<< HEAD
            // Exact-normalised match first, then fall back to highest similarity.
            val qNorm = title.normaliseTitle()
            val best = filtered.firstOrNull { (_, ct) -> ct.normaliseTitle() == qNorm }
                ?: filtered.maxByOrNull { (_, ct) -> titleSimilarity(ct, title) }
                ?: return false
            // Require a minimum similarity to avoid false positives.
            if (titleSimilarity(best.second, title) < 0.4) return false

            val srcLabel = "$labelPrefix • $LABEL"

            // ── MOVIE PATH ──────────────────────────────────────────────────
            // CineplexBDProvider.load() builds the data URL as
            //   /player.php?id=$id
            // then loadLinks fetches /player.php?id=$id which contains the
            // actual video embed (HLS player sources).
            if (isMovie) {
                val id = best.first.substringAfter("id=").substringBefore("&")
                val playerUrl = "$SITE/player.php?id=$id"
                val playerHtml = runCatching {
                    app.get(playerUrl, headers = HEADERS, timeout = 15_000).text
                }.getOrNull() ?: return false
                val mediaUrls = extractMediaUrlsFromHtml(playerHtml, playerUrl)
                var any = false
                mediaUrls.forEach { u ->
                    if (emitDirect(app, u, srcLabel, playerUrl, HEADERS, subtitleCallback, callback)) any = true
                }
                // Also try the view.php page itself (sometimes the player is inline).
                if (!any) {
                    runCatching {
                        app.get(best.first, headers = HEADERS, timeout = 15_000).text
                    }.getOrNull()?.let { viewHtml ->
                        val urls2 = extractMediaUrlsFromHtml(viewHtml, best.first)
                        urls2.forEach { u ->
                            if (emitDirect(app, u, srcLabel, best.first, HEADERS, subtitleCallback, callback)) any = true
                        }
                    }
=======
            val best = filtered.maxByOrNull { (_, ct) -> titleSimilarity(ct, title) }
                ?: return false
            if (titleSimilarity(best.second, title) < 0.4) return false

            val srcLabel = "$labelPrefix • $LABEL"
            val seriesIdKey = if (best.first.contains("series_id=")) "series_id" else "id"
            val seriesIdVal = if (seriesIdKey == "series_id") {
                best.first.substringAfter("series_id=").substringBefore("&")
            } else {
                best.first.substringAfter("id=").substringBefore("&")
            }

            // 2. Movies: load the view.php page directly and extract media URLs.
            if (isMovie) {
                val detailHtml = runCatching {
                    app.get(best.first, headers = HEADERS, timeout = 15_000).text
                }.getOrNull() ?: return false
                val mediaUrls = extractMediaUrlsFromHtml(detailHtml, best.first)
                var any = false
                mediaUrls.forEach { u ->
                    if (emitDirect(app, u, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)) any = true
>>>>>>> FETCH_HEAD
                }
                return any
            }

<<<<<<< HEAD
            // ── TV PATH ─────────────────────────────────────────────────────
            // Use the watch.php?…&season=N&meta=1 JSON endpoint that
            // CineplexBDProvider.parseEpisodesFromMetaJson uses. Each
            // episode object has a `path` field pointing to a /Data/… direct
            // media URL OR a /player.php?id=… indirection.
            val seriesIdKey = if (best.first.contains("series_id=")) "series_id" else "id"
            val seriesIdVal = if (seriesIdKey == "series_id") {
                best.first.substringAfter("series_id=").substringBefore("&")
            } else {
                best.first.substringAfter("id=").substringBefore("&")
            }
=======
            // 3. TV: use the watch.php?…&season=N&meta=1 JSON endpoint that
            //    CineplexBDProvider uses. Each episode object has a `path`
            //    field pointing to a /Data/… direct media URL or watch.php
            //    page that we then resolve.
>>>>>>> FETCH_HEAD
            val seasonToUse = season ?: 1
            val metaUrl = "$SITE/watch.php?$seriesIdKey=$seriesIdVal&season=$seasonToUse&meta=1"
            val metaText = runCatching {
                app.get(metaUrl, headers = HEADERS, timeout = 12_000).text
            }.getOrNull() ?: return false

<<<<<<< HEAD
            val root = runCatching { JSONObject(metaText) }.getOrNull() ?: return false
            val episodesNode: Any? = root.opt("episodes") ?: root.opt("data") ?: root

            // Collect all episode paths so we can also try neighbouring
            // episodes if the exact episode number isn't found.
            val allPaths = mutableListOf<Pair<Int, String>>() // (epNum, path)
            when (episodesNode) {
                is JSONObject -> {
                    val keys = episodesNode.keys()
=======
            // Parse the JSON — supports both {episodes: {1: {…}}} and
            // {episodes: [{…}, …]} shapes (CineplexBDProvider.parseEpisodesFromMetaJson).
            val root = runCatching { JSONObject(metaText) }.getOrNull() ?: return false
            val episodesNode: Any? = root.opt("episodes") ?: root.opt("data") ?: root
            val epPath: String? = when (episodesNode) {
                is JSONObject -> {
                    // Find the episode by number, fall back to index.
                    val keys = episodesNode.keys()
                    var path: String? = null
>>>>>>> FETCH_HEAD
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = episodesNode.optJSONObject(k) ?: continue
                        val epNum = v.optStringOrNullCp("episode_number")?.toIntOrNull()
                            ?: v.optInt("episode_number", 0).takeIf { it != 0 }
                            ?: k.toIntOrNull()
                            ?: 0
<<<<<<< HEAD
                        val path = v.optStringOrNullCp("path") ?: v.optStringOrNullCp("url")
                            ?: v.optStringOrNullCp("src") ?: v.optStringOrNullCp("file")
                        if (path != null) allPaths += epNum to path
                    }
                }
                is JSONArray -> {
                    for (i in 0 until episodesNode.length()) {
                        val v = episodesNode.optJSONObject(i) ?: continue
                        val epNum = v.optStringOrNullCp("episode_number")?.toIntOrNull()
                            ?: v.optInt("episode_number", 0).takeIf { it != 0 }
                            ?: (i + 1)
                        val path = v.optStringOrNullCp("path") ?: v.optStringOrNullCp("url")
                            ?: v.optStringOrNullCp("src") ?: v.optStringOrNullCp("file")
                        if (path != null) allPaths += epNum to path
                    }
                }
            }

            val epToUse = episode ?: 1
            // Find the exact episode, else fall back to the first one.
            val matchPath = allPaths.firstOrNull { it.first == epToUse }?.second
                ?: allPaths.firstOrNull()?.second
                ?: return false

            // The path can be:
            //   • /Data/…/movie.mkv  → direct media URL
            //   • /view.php?id=… or /player.php?id=… → indirection page
            val absPath = resolveAbs(SITE, matchPath)
            return when {
                isDirectMedia(absPath) ->
                    emitDirect(app, absPath, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)
                absPath.contains("player.php") || absPath.contains("view.php") -> {
                    val playerHtml = runCatching {
                        app.get(absPath, headers = HEADERS, timeout = 15_000).text
                    }.getOrNull() ?: return false
                    val mediaUrls = extractMediaUrlsFromHtml(playerHtml, absPath)
                    var any = false
                    mediaUrls.forEach { u ->
                        if (emitDirect(app, u, srcLabel, absPath, HEADERS, subtitleCallback, callback)) any = true
                    }
                    any
                }
                else -> emitDirect(app, absPath, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)
            }
=======
                        if (epNum == episode) {
                            path = v.optStringOrNullCp("path") ?: v.optStringOrNullCp("url")
                                ?: v.optStringOrNullCp("src") ?: v.optStringOrNullCp("file")
                            break
                        }
                    }
                    path
                }
                is JSONArray -> {
                    val idx = (episode ?: 1) - 1
                    val v = episodesNode.optJSONObject(idx)
                    v?.optStringOrNullCp("path") ?: v?.optStringOrNullCp("url")
                        ?: v?.optStringOrNullCp("src") ?: v?.optStringOrNullCp("file")
                }
                else -> null
            }

            if (epPath != null) {
                // The episode path is normally a /Data/… direct media URL.
                val absEp = resolveAbs(SITE, epPath)
                return emitDirect(app, absEp, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)
            }

            // Fallback: scrape the watch.php page for any media URLs.
            val watchHtml = runCatching {
                app.get(best.first, headers = HEADERS, timeout = 15_000).text
            }.getOrNull() ?: return false
            val mediaUrls = extractMediaUrlsFromHtml(watchHtml, best.first)
            var any = false
            mediaUrls.forEach { u ->
                if (emitDirect(app, u, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)) any = true
            }
            return any
>>>>>>> FETCH_HEAD
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 2: FTPBD  (https://ftpbd.net)
<<<<<<< HEAD
    //  Parser ported from FTPBDProvider.kt — uses exact-clean-title match
    //  (cleanMediaTitle + normalizedTitle) for accurate anime resolution.
=======
    //  Parser ported from FTPBDProvider.kt — uses the exact same
    //  `.site-main .jws-post-item` selectors + `/movie/` `/tv_shows/` paths.
>>>>>>> FETCH_HEAD
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
<<<<<<< HEAD
            // FTPBD has separate post_type for movies vs tv_shows. Anime
            // lives under tv_shows with "Anime" in the title or /tv_shows/
            // path. For anime we search tv_shows first then movies.
=======
            // Search movies + tv concurrently. Use the EXACT selectors from
            // FTPBDProvider.parseCardItems: `.site-main .jws-post-item, .site-main .post-inner`
>>>>>>> FETCH_HEAD
            val postTypes = if (isMovie) listOf("movies") else listOf("tv_shows", "movies")
            val expectedPaths = if (isMovie) listOf("/movie/") else listOf("/tv_shows/", "/movie/")

            val candidates = mutableListOf<Pair<String, String>>()
            coroutineScope {
                postTypes.mapIndexed { idx, postType ->
                    async(Dispatchers.IO) {
                        val url = "$SITE/?s=${encodeUrl(title)}&post_type=$postType"
                        runCatching {
                            val doc = app.get(url, headers = HEADERS, timeout = 12_000).document
                            // EXACT selector from FTPBDProvider.parseCardItems
                            doc.select(".site-main .jws-post-item, .site-main .post-inner")
                                .forEach { card ->
<<<<<<< HEAD
=======
                                    // EXACT selector from FTPBDProvider.Element.toSearchItem
>>>>>>> FETCH_HEAD
                                    val a = card.selectFirst("a[href*='${expectedPaths[idx]}']") ?: return@forEach
                                    val href = a.absUrl("href").ifBlank { a.attr("href") }
                                    if (href.isBlank() || !href.contains(expectedPaths[idx])) return@forEach
                                    val t = card.selectFirst("h6 a, h5 a, h4 a, .post-title a")?.text()?.trim()
                                        ?: a.attr("title").trim().ifBlank { null }
                                        ?: a.text().trim()
                                    if (t.isNotBlank() && !t.equals("Play Now", true)) {
                                        synchronized(candidates) { candidates += href to t }
                                    }
                                }
                        }
                    }
                }.awaitAll()
            }

            if (candidates.isEmpty()) return false

<<<<<<< HEAD
            // ── TITLE MATCHING ──────────────────────────────────────────────
            // Use FTPBDProvider's exact-normalised match first (cleanMediaTitle +
            // normalizedTitle). This is critical for anime — without stripping
            // "[Hindi Dubbed]" / "Season 1", "One Piece" would match the wrong
            // series. Fall back to Jaccard similarity only if no exact match.
            val qNorm = title.normaliseTitle()
            val best = candidates
                .distinctBy { it.first }
                .firstOrNull { (_, ct) -> ct.normaliseTitle() == qNorm }
                ?: candidates.distinctBy { it.first }
                    .maxByOrNull { (_, ct) -> titleSimilarity(ct, title) }
=======
            val best = candidates
                .distinctBy { it.first }
                .maxByOrNull { (_, ct) -> titleSimilarity(ct, title) }
>>>>>>> FETCH_HEAD
                ?: return false
            if (titleSimilarity(best.second, title) < 0.4) return false

            val srcLabel = "$labelPrefix • $LABEL"
            val detailHtml = runCatching {
                app.get(best.first, headers = HEADERS, timeout = 15_000).text
            }.getOrNull() ?: return false

<<<<<<< HEAD
            // For TV: find the episode's permalink via /episodes/ path.
            val episodePageUrl: String? = if (!isMovie && season != null && episode != null) {
                pickEpisodePageUrl(detailHtml, best.first, season, episode)
=======
            // For TV: find the episode's permalink via season= filter.
            // FTPBD exposes season navigation via `.select-seasion .dropdown-item` and
            // `a[href*='season=']` (see FTPBDProvider.seasonNumbers).
            val episodePageUrl: String? = if (!isMovie && season != null && episode != null) {
                pickEpisodePageUrl(detailHtml, best.first, season, episode, app)
>>>>>>> FETCH_HEAD
            } else {
                null
            }

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

<<<<<<< HEAD
        private fun pickEpisodePageUrl(
=======
        /**
         * Pick the episode URL for FTPBD. FTPBD has two patterns:
         *  1. `a[href*='season=N']` — season filter links
         *  2. `.select-seasion .dropdown-item[data-index]` — dropdown items
         *
         * After picking the season, look for episode anchors with `?season=N&ep=M`
         * or `/episodes/` paths.
         */
        private suspend fun pickEpisodePageUrl(
>>>>>>> FETCH_HEAD
            detailHtml: String,
            detailUrl: String,
            season: Int,
            episode: Int,
<<<<<<< HEAD
        ): String? {
            val doc = Jsoup.parse(detailHtml, detailUrl)
=======
            app: Requests,
        ): String? {
            val doc = Jsoup.parse(detailHtml, detailUrl)
            // Look for episode anchors — FTPBD uses /episodes/ slug for episode pages.
>>>>>>> FETCH_HEAD
            val anchors = doc.select(
                "a[href*='/episodes/'], a[href*='episode='], a[href*='ep='], " +
                    ".episode a, .episodes a, .episode-list a"
            )
<<<<<<< HEAD
=======
            // First try exact season+episode match.
>>>>>>> FETCH_HEAD
            val exact = anchors.firstOrNull { a ->
                val href = a.attr("href")
                (href.contains("season=$season") || href.contains("s=$season")) &&
                    (href.contains("episode=$episode") || href.contains("ep=$episode"))
            }
            val href = exact?.attr("href")?.ifBlank { null }
                ?: anchors.firstOrNull { a ->
                    val href = a.attr("href")
                    val epNum = Regex("""(?i)(?:ep(?:isode)?[=\s]*|\bE)(\d+)""")
                        .find(href + " " + a.text())?.groupValues?.get(1)?.toIntOrNull()
                    epNum == episode
                }?.attr("href")?.ifBlank { null }
                ?: return null
            return resolveAbs(detailUrl, href)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 3: Circle FTP  (http://new.circleftp.net)
<<<<<<< HEAD
    //  Parser ported from CircleFtpProvider.kt. Key insight from
    //  CircleFtpProvider.load():
    //    • For movies (postObj.type == "singleVideo"), the URL is at
    //      postObj.optString("content") — a STRING, not an array.
    //    • For TV/anime, postObj.optJSONArray("content") returns a
    //      JSONArray of seasons, where content[seasonIndex].episodes[epIndex].link
    //      is the URL. Season index is 0-based (season-1).
    //    • URLs are encoded as "circleftp://movie?data=<base64>" or
    //      "circleftp://episode?data=<base64>" payloads, where the base64
    //      decodes to a JSON array of {url, audio?} objects.
=======
    //  Parser ported from CircleFtpProvider.kt — uses the same
    //  /api/posts?searchTerm=… search + /api/posts/$id detail +
    //  content[season-1].episodes[epIndex].link structure.
>>>>>>> FETCH_HEAD
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
<<<<<<< HEAD
=======
            // 1. Search posts — uses the exact same endpoint as CircleFtpProvider.
>>>>>>> FETCH_HEAD
            val searchText = fetchWithFallback(
                app,
                primary = "$PRIMARY_API/api/posts?searchTerm=${encodeUrl(title)}&order=desc",
                fallback = "$FALLBACK_API/api/posts?searchTerm=${encodeUrl(title)}&order=desc",
            ) ?: return false

            val postsArr = runCatching { JSONObject(searchText).optJSONArray("posts") }
                .getOrNull() ?: return false
            if (postsArr.length() == 0) return false

<<<<<<< HEAD
=======
            // 2. Pick best match by title similarity.
>>>>>>> FETCH_HEAD
            var bestId: Int = -1
            var bestScore = 0.0
            for (i in 0 until postsArr.length()) {
                val p = postsArr.optJSONObject(i) ?: continue
                val ptitle = p.optString("title").ifBlank { p.optString("name") ?: "" }
                if (ptitle.isBlank()) continue
                val score = titleSimilarity(ptitle, title)
                if (score > bestScore) {
                    bestScore = score
                    bestId = p.optInt("id", -1)
                }
            }
            if (bestId < 0 || bestScore < 0.4) return false

<<<<<<< HEAD
=======
            // 3. Fetch post details — same endpoint as CircleFtpProvider.getPostDetails.
>>>>>>> FETCH_HEAD
            val detailText = fetchWithFallback(
                app,
                primary = "$PRIMARY_API/api/posts/$bestId",
                fallback = "$FALLBACK_API/api/posts/$bestId",
            ) ?: return false
            val detail = runCatching { JSONObject(detailText) }.getOrNull() ?: return false

            val srcLabel = "$labelPrefix • $LABEL"
            val mediaUrls = linkedSetOf<String>()

<<<<<<< HEAD
            val postType = detail.optString("type")
            if (postType == "singleVideo") {
                // ── MOVIE PATH ──────────────────────────────────────────────
                // postObj.optString("content") returns the direct media URL
                // as a STRING. (Matches CircleFtpProvider line 1230.)
                val movieUrl = detail.optString("content").takeIf { it.isNotBlank() }
                if (movieUrl != null) mediaUrls += movieUrl
                // Also check common alternative field names.
                detail.optString("url").takeIf { it.isNotBlank() }?.let { mediaUrls += it }
                detail.optString("file").takeIf { it.isNotBlank() }?.let { mediaUrls += it }
                detail.optString("src").takeIf { it.isNotBlank() }?.let { mediaUrls += it }
            } else {
                // ── TV/ANIME PATH ───────────────────────────────────────────
                // content[season-1].episodes[episode-1].link
=======
            // 4. Extract media URLs from the post detail.
            // Circle FTP's response shape (from CircleFtpProvider):
            //   postObj.type == "singleVideo" → movie/direct video
            //   postObj.content[season-1].episodes[epIndex].link → episode
            // The `link` field is a "movie?data=<base64>" / "episode?data=<base64>" URL
            // OR a raw media URL.
            val postType = detail.optString("type")
            if (postType == "singleVideo") {
                // Movie: look for a direct URL field.
                detail.optString("url").takeIf { it.isNotBlank() }?.let { mediaUrls += it }
                detail.optString("file").takeIf { it.isNotBlank() }?.let { mediaUrls += it }
                detail.optString("src").takeIf { it.isNotBlank() }?.let { mediaUrls += it }
                detail.optString("link").takeIf { it.isNotBlank() }?.let { mediaUrls += it }
            } else {
                // TV/Anime: navigate content[season-1].episodes[epIndex].link
                // EXACT same logic as CircleFtpProvider lines 1303-1327.
>>>>>>> FETCH_HEAD
                val contentArray = detail.optJSONArray("content")
                val seasonIdx = (season ?: 1) - 1
                val seasonObj = contentArray?.optJSONObject(seasonIdx)
                val episodesArray = seasonObj?.optJSONArray("episodes")
                if (episodesArray != null && episode != null) {
                    val epObj = episodesArray.optJSONObject(episode - 1)
                    val link = epObj?.optString("link")
                    if (link != null && link.isNotEmpty()) {
                        mediaUrls += link
                    }
                }
<<<<<<< HEAD
                // Fallback: if no specific episode matched, dump all episode
                // links from all seasons.
=======
                // If no specific episode matched, dump all episode links from
                // all seasons (helps when season/episode numbering is off).
>>>>>>> FETCH_HEAD
                if (mediaUrls.isEmpty() && contentArray != null) {
                    for (si in 0 until contentArray.length()) {
                        val so = contentArray.optJSONObject(si) ?: continue
                        val eps = so.optJSONArray("episodes") ?: continue
                        for (ei in 0 until eps.length()) {
                            val eo = eps.optJSONObject(ei) ?: continue
                            val link = eo.optString("link")
                            if (link.isNotEmpty()) mediaUrls += link
                        }
                    }
                }
            }

<<<<<<< HEAD
            // Direct download links array (sometimes present).
=======
            // 5. Also pick up any direct download links array.
>>>>>>> FETCH_HEAD
            detail.optJSONArray("downloadLinks")?.let { dlArr ->
                for (i in 0 until dlArr.length()) {
                    val dl = dlArr.optJSONObject(i) ?: continue
                    val u = dl.optString("url").ifBlank { dl.optString("link") }
                    if (u.isNotBlank()) mediaUrls += u
                }
            }

<<<<<<< HEAD
            var any = false
            mediaUrls.forEach { u ->
=======
            // 6. Emit
            var any = false
            mediaUrls.forEach { u ->
                // Circle FTP encodes TV/movie URLs as "movie?data=<base64>" /
                // "episode?data=<base64>" — base64 of a JSON array of {url,audio}.
                // This is handled by CircleFtpProvider.loadLinks (lines 1507-1561).
>>>>>>> FETCH_HEAD
                if (u.contains("movie?data=") || u.contains("episode?data=") ||
                    u.contains("circleftp://")
                ) {
                    if (emitCircleFtpEncoded(u, srcLabel, callback)) any = true
                } else if (emitDirect(app, u, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)) {
                    any = true
                }
            }
            return any
        }

<<<<<<< HEAD
=======
        /**
         * Decode Circle FTP's base64-encoded episode link arrays.
         * The `data` segment is a base64-encoded JSON array of
         * `{url: "…", audio: "…?"}` objects — emit each URL directly.
         */
>>>>>>> FETCH_HEAD
        private suspend fun emitCircleFtpEncoded(
            data: String,
            sourceLabel: String,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val raw = data.substringAfter("data=").substringBefore("&")
                .substringBefore(" ").trim()
            if (raw.isBlank()) return false
<<<<<<< HEAD
=======
            // Strip any "circleftp://" prefix that might be present.
>>>>>>> FETCH_HEAD
            val cleaned = raw.removePrefix("circleftp://")
            val jsonStr = runCatching {
                String(Base64.getDecoder().decode(cleaned))
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
<<<<<<< HEAD
    //  Resolver 4: CTGMovies  (https://ctgmovies.com)
    //  Already worked in v2 — unchanged.
=======
    //  Resolver 4: CTGMovies  (https://ctgmovies.com, API at cockpit)
    //  Already worked in v1 — kept mostly as-is but tightened:
    //   • Search "movies", "tv", "anime" concurrently.
    //   • Best match by title similarity.
    //   • Detail via /api/v1/<kind>/<id>.
    //   • Episode links via seasons[].episodes[].links[].
>>>>>>> FETCH_HEAD
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
            val kinds = if (isMovie) listOf("movies", "tv", "anime") else listOf("tv", "anime", "movies")
            val params = mapOf("search" to title)
            val candidates = mutableListOf<Triple<String, String, String>>()

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

            val best = candidates
                .maxByOrNull { (_, _, ct) -> titleSimilarity(ct, title) }
                ?: return false
            if (titleSimilarity(best.third, title) < 0.4) return false

            val detailText = apiGet(app, "/${best.first}/${encodeUrl(best.second)}", emptyMap())
            val detail = runCatching { JSONObject(detailText) }.getOrNull() ?: return false

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

<<<<<<< HEAD
=======
            // TV/anime: try seasons[].episodes[].links[]
>>>>>>> FETCH_HEAD
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

            val r1 = runCatching {
                app.get(primary, headers = HEADERS, timeout = 10_000)
            }.getOrNull()
            if (r1 != null && r1.code in 200..299 && r1.text.isNotBlank()) return r1.text

            val fallback = "$SITE/api/v1$p$qs"
            val r2 = runCatching {
                app.get(fallback, headers = HEADERS, timeout = 10_000)
            }.getOrNull()
            if (r2 != null && r2.code in 200..299 && r2.text.isNotBlank()) return r2.text

            return ""
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
<<<<<<< HEAD
//  Shared JSON helpers
=======
//  Shared JSON helpers (file-level so all resolvers can use them)
>>>>>>> FETCH_HEAD
// ─────────────────────────────────────────────────────────────────────────────

internal fun JSONObject.optStringOrNullCp(k: String): String? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").trim().takeIf { it.isNotBlank() && it != "null" }
