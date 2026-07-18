package com.wizdier

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
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
 * Each resolver is a 1:1 port of the parser used by the corresponding
 * standalone extension, so behaviour matches exactly.
 */
object WizstreamSources {

    private const val TAG = "WizstreamSources"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

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
            FlixHubResolver,
            CinebyResolver,
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

    /**
     * Title similarity score (Jaccard token overlap, 0..1). Used as a
     * secondary check after exact-match normalised comparison.
     */
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

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 5: FlixHub  (https://flixhub.net)
    //  BDIX-accessible streaming site with movies and TV series.
    //  Stream URL: https://flixhub.net/stream/movie/<slug> or
    //              https://flixhub.net/stream/episode/<slug>
    // ════════════════════════════════════════════════════════════════════════

    internal object FlixHubResolver : SourceResolver {
        private const val SITE = "https://flixhub.net"
        private const val LABEL = "FlixHub"
        private val cfKiller = CloudflareKiller()
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
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
            val srcLabel = "$labelPrefix • $LABEL"

            // 1. Search FlixHub (needs CloudflareKiller)
            val searchUrl = "$SITE/search?q=${encodeUrl(title)}"
            val html = runCatching {
                app.get(searchUrl, headers = HEADERS, interceptor = cfKiller, timeout = 20_000).text
            }.getOrNull() ?: return false

            val doc = Jsoup.parse(html, searchUrl)
            val cards = doc.select("article.movie-card-final, article[data-watch-url]")

            // 2. Find the best matching card
            var bestUrl: String? = null
            var bestScore = 0.0
            for (card in cards) {
                val watchUrl = card.attr("data-watch-url")
                    .ifBlank { card.selectFirst("a[href*=/watch/]")?.attr("href") }
                    ?: continue
                val cardTitle = card.selectFirst(".movie-card-browse-title, .movie-title, .card-overlay p")?.text()
                    ?: card.selectFirst("a[aria-label]")?.attr("aria-label")
                    ?: continue

                // Check if it's the right type (movie vs series)
                val isSeries = watchUrl.contains("/watch/series/")
                if (isMovie && isSeries) continue
                if (!isMovie && !isSeries) continue

                val score = titleSimilarity(cardTitle, title)
                if (score > bestScore) {
                    bestScore = score
                    bestUrl = watchUrl
                }
            }

            if (bestUrl == null || bestScore < 0.4) return false

            // 3. Fetch the watch page to find stream URL
            val watchHtml = runCatching {
                app.get(bestUrl, headers = HEADERS, interceptor = cfKiller, timeout = 20_000).text
            }.getOrNull() ?: return false
            val watchDoc = Jsoup.parse(watchHtml, bestUrl)

            if (isMovie) {
                // Movie: extract data-download-url or construct stream URL
                val slug = bestUrl.substringAfter("/watch/movie/")
                val streamUrl = watchDoc.selectFirst("[data-download-url]")?.attr("data-download-url")
                    ?: "$SITE/stream/movie/$slug"

                callback(
                    newExtractorLink(
                        source = srcLabel,
                        name = srcLabel,
                        url = streamUrl,
                        type = INFER_TYPE,
                    ) {
                        this.referer = SITE
                        this.quality = qualityFromName(streamUrl)
                    }
                )
                return true
            } else {
                // TV Series: find episode links
                val epLinks = watchDoc.select("a[href*=/watch/episode/]")
                val seenSlugs = mutableSetOf<String>()
                var found = false

                for (link in epLinks) {
                    val href = link.attr("href")
                    val epSlug = href.substringAfter("/episode/").substringBefore("?").substringBefore("#")
                    if (epSlug.isBlank() || epSlug in seenSlugs) continue
                    seenSlugs.add(epSlug)

                    val epText = link.text().trim()
                    val epNum = Regex("""(?:Episode\s*)?(\d+)""", RegexOption.IGNORE_CASE)
                        .find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: seenSlugs.size

                    if (epNum == (episode ?: 1)) {
                        val streamUrl = "$SITE/stream/episode/$epSlug"
                        callback(
                            newExtractorLink(
                                source = srcLabel,
                                name = "$srcLabel - Episode $epNum",
                                url = streamUrl,
                                type = INFER_TYPE,
                            ) {
                                this.referer = SITE
                                this.quality = qualityFromName(streamUrl)
                            }
                        )
                        found = true
                    }
                }

                // Fallback: use the current episode from the player
                if (!found) {
                    val currentEpSlug = watchDoc.selectFirst("#theaterPlayer")?.attr("data-episode-slug")
                    if (currentEpSlug != null && currentEpSlug.isNotBlank()) {
                        val streamUrl = "$SITE/stream/episode/$currentEpSlug"
                        callback(
                            newExtractorLink(
                                source = srcLabel,
                                name = srcLabel,
                                url = streamUrl,
                                type = INFER_TYPE,
                            ) {
                                this.referer = SITE
                                this.quality = qualityFromName(streamUrl)
                            }
                        )
                        found = true
                    }
                }

                return found
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 6: Cineby  (https://www.cineby.at)
    //  Uses api.speedracelight.com + enc-dec.app decryption.
    //  Flow: search TMDB → get tmdbId → fetch seed → fetch encrypted sources →
    //        decrypt via enc-dec.app → emit m3u8/mp4 URLs
    // ════════════════════════════════════════════════════════════════════════

    internal object CinebyResolver : SourceResolver {
        private const val SITE = "https://www.cineby.at"
        private const val API_BASE = "https://api.speedracelight.com"
        private const val DEC_API = "https://enc-dec.app/api/dec-videasy"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"
        private const val LABEL = "Cineby"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Origin" to SITE,
            "Accept" to "application/json, text/plain, */*",
        )

        // Cineby servers (from CinebyExtractor.kt)
        private val SERVERS = listOf(
            Triple("neon2", "Cineby-Neon", null as String?),
            Triple("cdn", "Cineby-CDN", null),
            Triple("m4uhd", "Cineby-Breach", null),
            Triple("hdmovie", "Cineby-Vyse", "English"),
            Triple("hdmovie", "Cineby-Fade", "Hindi"),
            Triple("meine", "Cineby-Killjoy", "german"),
            Triple("lamovie", "Cineby-Omen", null),
            Triple("superflix", "Cineby-Raze", null),
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
            val srcLabel = "$labelPrefix • $LABEL"
            val mediaType = if (isMovie) "movie" else "tv"

            // 1. Search TMDB for the title to get tmdbId
            val tmdbId = fetchTmdbId(app, title, year, mediaType) ?: return false

            // 2. For each enabled server, fetch + decrypt sources
            var found = false
            for ((serverPath, serverLabel, qualityFilter) in SERVERS) {
                runCatching {
                    val result = fetchCinebySources(app, serverPath, tmdbId, title, year, isMovie, season, episode, qualityFilter)
                    if (result != null) {
                        val sourcesArr = result.optJSONArray("sources")
                        if (sourcesArr != null) {
                            for (i in 0 until sourcesArr.length()) {
                                val src = sourcesArr.optJSONObject(i) ?: continue
                                val url = src.optString("url").ifBlank { src.optString("file") }
                                if (url.isBlank()) continue
                                val quality = src.optString("quality") ?: ""

                                if (url.contains(".m3u8", true)) {
                                    M3u8Helper.generateM3u8(
                                        source = "$srcLabel • $serverLabel",
                                        streamUrl = url,
                                        referer = SITE,
                                        headers = HEADERS,
                                    ).forEach { el ->
                                        callback(el)
                                        found = true
                                    }
                                } else {
                                    callback(
                                        newExtractorLink(
                                            source = "$srcLabel • $serverLabel",
                                            name = "$srcLabel • $serverLabel - $quality",
                                            url = url,
                                            type = INFER_TYPE,
                                        ) {
                                            this.referer = SITE
                                            this.quality = qualityFromName(quality)
                                        }
                                    )
                                    found = true
                                }
                            }
                        }
                    }
                }
            }
            return found
        }

        private suspend fun fetchTmdbId(app: Requests, title: String, year: Int?, mediaType: String): Int? {
            val params = mapOf(
                "api_key" to TMDB_KEY,
                "query" to title,
                "language" to "en-US",
            ).let { m ->
                if (year != null) m + ("year" to year.toString()) else m
            }
            val url = "$TMDB_API/search/$mediaType?" + params.entries.joinToString("&") { (k, v) ->
                "${encodeUrl(k)}=${encodeUrl(v)}"
            }
            val res = runCatching {
                app.get(url, headers = mapOf("Accept" to "application/json"), timeout = 10_000)
            }.getOrNull() ?: return null
            if (res.code !in 200..299) return null
            val json = runCatching { JSONObject(res.text) }.getOrNull() ?: return null
            val results = json.optJSONArray("results") ?: return null
            return results.optJSONObject(0)?.optInt("id", 0)?.takeIf { it > 0 }
        }

        private suspend fun fetchCinebySources(
            app: Requests,
            server: String,
            tmdbId: Int,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            qualityFilter: String?,
        ): JSONObject? {
            val mediaType = if (isMovie) "movie" else "tv"
            val seasonId = if (isMovie) "1" else (season ?: 1).toString()
            val episodeId = if (isMovie) "1" else (episode ?: 1).toString()

            // Step 1: Fetch seed
            val seedUrl = "$API_BASE/seed?mediaId=$tmdbId"
            val seedRes = runCatching {
                app.get(seedUrl, headers = HEADERS, timeout = 15_000)
            }.getOrNull() ?: return null
            val seed = runCatching {
                JSONObject(seedRes.text).optString("seed", "")
            }.getOrDefault("")

            // Step 2: Fetch encrypted sources
            val doubleEncodedTitle = encodeUrl(encodeUrl(title))
            val sourcesUrl = "$API_BASE/$server/sources-with-title" +
                "?title=$doubleEncodedTitle" +
                "&mediaType=$mediaType" +
                "&year=${year ?: ""}" +
                "&episodeId=$episodeId" +
                "&seasonId=$seasonId" +
                "&tmdbId=$tmdbId" +
                "&enc=2" +
                (if (seed.isNotBlank()) "&seed=$seed" else "") +
                (if (qualityFilter != null) "&language=$qualityFilter" else "")

            val encRes = runCatching {
                app.get(sourcesUrl, headers = HEADERS, timeout = 15_000)
            }.getOrNull() ?: return null
            val encText = encRes.text
            if (encText.isBlank() || encText.startsWith("{\"error")) return null

            // Step 3: Decrypt via enc-dec.app
            val decBody = JSONObject().apply {
                put("text", encText)
                put("id", tmdbId.toString())
                if (seed.isNotBlank()) put("seed", seed)
            }.toString()
            val decRes = runCatching {
                app.post(
                    DEC_API,
                    headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                    requestBody = decBody.toRequestBody("application/json".toMediaTypeOrNull()),
                    timeout = 15_000,
                )
            }.getOrNull() ?: return null
            val decJson = runCatching { JSONObject(decRes.text) }.getOrNull() ?: return null
            val result = decJson.optJSONObject("result") ?: return null

            // Apply quality filter if needed
            if (qualityFilter != null) {
                val sources = result.optJSONArray("sources")
                if (sources != null) {
                    val filtered = JSONArray()
                    for (i in 0 until sources.length()) {
                        val s = sources.optJSONObject(i) ?: continue
                        if (s.optString("quality", "").equals(qualityFilter, ignoreCase = true)) {
                            filtered.put(s)
                        }
                    }
                    return JSONObject().apply {
                        put("sources", filtered)
                        put("subtitles", result.optJSONArray("subtitles") ?: JSONArray())
                    }
                }
            }
            return result
        }
    }


    // ─────────────────────────────────────────────────────────────────────────────

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

    internal fun extractMediaUrlsFromHtml(html: String, baseUrl: String): LinkedHashSet<String> {
        val out = linkedSetOf<String>()
        val doc: Document = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull()
            ?: return out

        doc.select(
            "video[src], source[src], a[href*='.m3u8'], a[href*='.mp4'], a[href*='.mkv'], " +
                "a[href*='.webm'], a[href*='.m4v']"
        ).forEach { el ->
            val src = el.attr("src").ifBlank { el.attr("href") }
            if (src.isNotBlank()) out += resolveAbs(baseUrl, src)
        }

        // iframe srcs — many vid hosts wrap the actual video in an iframe.
        doc.select("iframe[src]").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                out += resolveAbs(baseUrl, src)
            }
        }

        Regex("""(?i)data-url=["']([^"']+)["']""")
            .findAll(html)
            .map { it.groupValues[1] }
            .forEach { raw ->
                if (raw.isNotBlank() && isDirectMedia(raw)) out += resolveAbs(baseUrl, raw)
            }

        Regex("""(?i)<source[^>]+src=["']([^"']+?\.m3u8[^"']*)["']""")
            .findAll(html).forEach { m -> out += resolveAbs(baseUrl, m.groupValues[1]) }
        Regex("""(?i)hls\.loadSource\(["']([^"']+?\.m3u8[^"']*)["']\)""")
            .findAll(html).forEach { m -> out += resolveAbs(baseUrl, m.groupValues[1]) }
        Regex("""(?i)(?:const|var)\s+videoSrc\s*=\s*["'](.*?)["']""")
            .findAll(html).forEach { m ->
                val u = m.groupValues[1]
                if (u.isNotBlank() && (u.startsWith("http") || isDirectMedia(u)))
                    out += resolveAbs(baseUrl, u)
            }

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
    //  Parser ported from CineplexBDProvider.kt — uses /player.php?id=$id
    //  indirection (movies) and watch.php?…&meta=1 JSON endpoint (TV).
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
            // 1. Search via search.php — same selectors as CineplexBDProvider.
            val searchUrl = "$SITE/search.php?q=${encodeUrl(title)}&page=1"
            val html = runCatching {
                app.get(searchUrl, headers = HEADERS, timeout = 12_000).text
            }.getOrNull() ?: return false

            val doc = Jsoup.parse(html, searchUrl)
            val candidates = mutableListOf<Pair<String, String>>()
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

            if (candidates.isEmpty()) return false

            // Filter: prefer series page for TV, view page for movie.
            val filtered = candidates.filter { (u, _) ->
                if (isMovie) !u.contains("watch.php?series_id") && !u.contains("tview.php")
                else u.contains("watch.php") || u.contains("tview.php")
            }.ifEmpty { candidates }

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
                }
                return any
            }

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
            val seasonToUse = season ?: 1
            val metaUrl = "$SITE/watch.php?$seriesIdKey=$seriesIdVal&season=$seasonToUse&meta=1"
            val metaText = runCatching {
                app.get(metaUrl, headers = HEADERS, timeout = 12_000).text
            }.getOrNull() ?: return false

            val root = runCatching { JSONObject(metaText) }.getOrNull() ?: return false
            val episodesNode: Any? = root.opt("episodes") ?: root.opt("data") ?: root

            // Collect all episode paths so we can also try neighbouring
            // episodes if the exact episode number isn't found.
            val allPaths = mutableListOf<Pair<Int, String>>() // (epNum, path)
            when (episodesNode) {
                is JSONObject -> {
                    val keys = episodesNode.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = episodesNode.optJSONObject(k) ?: continue
                        val epNum = v.optStringOrNullCp("episode_number")?.toIntOrNull()
                            ?: v.optInt("episode_number", 0).takeIf { it != 0 }
                            ?: k.toIntOrNull()
                            ?: 0
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
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 2: FTPBD  (https://ftpbd.net)
    //  Parser ported from FTPBDProvider.kt — uses exact-clean-title match
    //  (cleanMediaTitle + normalizedTitle) for accurate anime resolution.
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
            // FTPBD has separate post_type for movies vs tv_shows. Anime
            // lives under tv_shows with "Anime" in the title or /tv_shows/
            // path. For anime we search tv_shows first then movies.
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
                ?: return false
            if (titleSimilarity(best.second, title) < 0.4) return false

            val srcLabel = "$labelPrefix • $LABEL"
            val detailHtml = runCatching {
                app.get(best.first, headers = HEADERS, timeout = 15_000).text
            }.getOrNull() ?: return false

            // For TV: find the episode's permalink via /episodes/ path.
            val episodePageUrl: String? = if (!isMovie && season != null && episode != null) {
                pickEpisodePageUrl(detailHtml, best.first, season, episode)
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

        private fun pickEpisodePageUrl(
            detailHtml: String,
            detailUrl: String,
            season: Int,
            episode: Int,
        ): String? {
            val doc = Jsoup.parse(detailHtml, detailUrl)
            val anchors = doc.select(
                "a[href*='/episodes/'], a[href*='episode='], a[href*='ep='], " +
                    ".episode a, .episodes a, .episode-list a"
            )
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
    // ════════════════════════════════════════════════════════════════════════

    internal object CircleFtpResolver : SourceResolver {
        private const val SITE = "http://new.circleftp.net"
        private const val PRIMARY_API = "http://new.circleftp.net:5000"
        private const val FALLBACK_API = "http://15.1.1.50:5000"
        private const val LABEL = "CircleFTP"
        // No custom headers — the standalone CircleFTP extension doesn't use any.
        // Passing custom headers (especially Referer from a different origin)
        // can cause the API to reject requests.

        /**
         * Map of CDN hostname → raw IP. Ported 1:1 from
         * CircleFtpProvider.cdnHostToIp (lines 1479-1496).
         *
         * Circle FTP serves media from `*.circleftp.net` hostnames that
         * only resolve on BDIX networks. When the user is on BDIX, the
         * hostname DNS lookup fails but the underlying IP is reachable.
         * We swap the hostname for the IP in the URL so the request goes
         * through. (Matches CircleFtpProvider.linkToIp behaviour.)
         */
        private val cdnHostToIp: List<Pair<String, String>> = listOf(
            "index.circleftp.net"  to "15.1.4.2",
            "index2.circleftp.net" to "15.1.4.5",
            "index1.circleftp.net" to "15.1.4.9",
            "ftp3.circleftp.net"   to "15.1.4.7",
            "ftp4.circleftp.net"   to "15.1.1.5",
            "ftp5.circleftp.net"   to "15.1.1.15",
            "ftp6.circleftp.net"   to "15.1.2.3",
            "ftp7.circleftp.net"   to "15.1.4.8",
            "ftp8.circleftp.net"   to "15.1.2.2",
            "ftp9.circleftp.net"   to "15.1.2.12",
            "ftp10.circleftp.net"  to "15.1.4.3",
            "ftp11.circleftp.net"  to "15.1.2.6",
            "ftp12.circleftp.net"  to "15.1.2.1",
            "ftp13.circleftp.net"  to "15.1.1.18",
            "ftp15.circleftp.net"  to "15.1.4.12",
            "ftp17.circleftp.net"  to "15.1.3.8",
        )

        /** Swap any *.circleftp.net hostname in the URL for its BDIX IP. */
        private fun linkToIp(data: String?): String {
            if (data.isNullOrEmpty()) return ""
            for ((host, ip) in cdnHostToIp) {
                if (host in data) return data.replace(host, ip)
            }
            return data
        }

        /**
         * Detect whether a Circle FTP post is anime (vs regular TV/movie).
         * Ported from CircleFtpProvider.isPostAnime (lines 980-996).
         */
        private fun isPostAnime(categoriesArr: org.json.JSONArray?, postTitle: String): Boolean {
            val titleLower = postTitle.lowercase()
            if (titleLower.contains("anime") || titleLower.contains("animation") ||
                titleLower.contains("cartoon")
            ) return true
            if (categoriesArr != null) {
                for (i in 0 until categoriesArr.length()) {
                    val catObj = categoriesArr.optJSONObject(i) ?: continue
                    val catId = catObj.optInt("id")
                    val catName = catObj.optString("name", "").lowercase()
                    if (catId == 21 || catId == 1 || catName.contains("anime") ||
                        catName.contains("animation") || catName.contains("cartoon")
                    ) return true
                }
            }
            return false
        }

        /**
         * Clean a Circle FTP post title to extract the base anime/show name
         * + any audio tag. Ported from CircleFtpProvider.cleanFtpTitle.
         *
         * Returns (cleanedTitle, audioTag?) — e.g.
         *   "One Piece [Hindi Dubbed]" → ("One Piece", "HINDI DUBBED")
         *   "Naruto Season 2"          → ("Naruto Season 2", null)
         */
        private fun cleanFtpTitle(postName: String?, postTitle: String): Pair<String, String?> {
            val audioRegex = Regex("(?i)\\b(dual[- ]?audio|multi[- ]?audio|dubbed|hindi[- ]?dubbed|eng[- ]?sub|bengali|hindi|dual|multi)\\b")
            val audioMatches = audioRegex.findAll(postTitle).map { it.value.trim() }.toList()
            val audioTag = if (audioMatches.isNotEmpty()) {
                audioMatches.joinToString(" ").uppercase()
            } else null

            var cleaned = postName?.trim().orEmpty()
            if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true)) {
                cleaned = postTitle.replace(Regex("\\.[a-zA-Z0-9]{2,4}$"), "")
                    .replace(".", " ")
                    .replace("_", " ")
                    .replace("-", " ")
                    .replace(Regex("\\b(19|20)\\d{2}\\b"), "")
                    .trim()
            }
            cleaned = cleaned.split(" ").joinToString(" ") {
                it.replaceFirstChar { c -> c.uppercase() }
            }
            return Pair(cleaned, audioTag)
        }

        /** Normalised title for grouping. Ported from CircleFtpProvider.normalizedGroupTitle. */
        private fun normalizedGroupTitle(title: String): String =
            title.lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")

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
            // 1. Search posts — fetch all results, not just the best one.
            val searchText = fetchWithFallback(
                app,
                primary = "$PRIMARY_API/api/posts?searchTerm=${encodeUrl(title)}&order=desc",
                fallback = "$FALLBACK_API/api/posts?searchTerm=${encodeUrl(title)}&order=desc",
            ) ?: return false

            val postsArr = runCatching { JSONObject(searchText).optJSONArray("posts") }
                .getOrNull() ?: return false
            if (postsArr.length() == 0) return false

            // 2. Collect ALL posts whose title is a strong match for the
            //    query. This handles BOTH multi-season cases:
            //      • One post with content[N] for season N  (e.g. "One Piece" with 15 seasons)
            //      • Separate posts per season              (e.g. "One Piece S2", "One Piece S3")
            //    By collecting all matching posts we cover both, just like
            //    CircleFtpProvider.groupAndMapPosts groups all same-named posts.
            val qNorm = title.normaliseTitle()
            val matchingPostIds = mutableListOf<Pair<Int, String>>() // (id, title)
            for (i in 0 until postsArr.length()) {
                val p = postsArr.optJSONObject(i) ?: continue
                val ptitle = p.optString("title").ifBlank { p.optString("name") ?: "" }
                if (ptitle.isBlank()) continue
                val pNorm = ptitle.normaliseTitle()
                // Exact normalised match: include always.
                if (pNorm == qNorm) {
                    matchingPostIds += p.optInt("id", -1) to ptitle
                    continue
                }
                // Title contains query (e.g. "One Piece Season 2" contains "one piece")
                // AND similarity ≥ 0.5 — include as a season-specific post.
                val score = titleSimilarity(ptitle, title)
                if (score >= 0.5 && pNorm.contains(qNorm)) {
                    matchingPostIds += p.optInt("id", -1) to ptitle
                }
            }
            if (matchingPostIds.isEmpty()) return false

            val srcLabel = "$labelPrefix • $LABEL"
            val mediaUrls = linkedSetOf<String>()

            // 3. Fetch ALL matching post details concurrently. Each post's
            //    detail JSON contains:
            //      • type == "singleVideo"  → movie/direct video
            //      • type != "singleVideo"  → content[season-1].episodes[ep-1].link
            //    We aggregate links from EVERY matching post so users get
            //    multiple audio variants (subbed/dual/hindi) for the same ep.
            val postDetails = coroutineScope {
                matchingPostIds.distinctBy { it.first }.map { (id, _) ->
                    async(Dispatchers.IO) {
                        if (id < 0) return@async null
                        val text = fetchWithFallback(
                            app,
                            primary = "$PRIMARY_API/api/posts/$id",
                            fallback = "$FALLBACK_API/api/posts/$id",
                        ) ?: return@async null
                        runCatching { JSONObject(text) }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            if (postDetails.isEmpty()) return false

            // 4. Determine if this is a movie or TV/anime based on the
            //    primary post's type. If ANY post is singleVideo, treat as
            //    movie and collect direct URLs from all of them.
            val isMoviePath = postDetails.any { it.optString("type") == "singleVideo" }

            if (isMoviePath) {
                // ── MOVIE PATH ──────────────────────────────────────────────
                // postObj.optString("content") returns the direct media URL
                // as a STRING. (Matches CircleFtpProvider line 1230.)
                postDetails.forEach { detail ->
                    detail.optString("content").takeIf { it.isNotBlank() }?.let { mediaUrls += it }
                    detail.optString("url").takeIf { it.isNotBlank() }?.let { mediaUrls += it }
                    detail.optString("file").takeIf { it.isNotBlank() }?.let { mediaUrls += it }
                    detail.optString("src").takeIf { it.isNotBlank() }?.let { mediaUrls += it }
                }
            } else {
                // ── TV/ANIME PATH ───────────────────────────────────────────
                // Each post has content[seasonIndex].episodes[epIndex].link
                // We collect links from ALL matching posts for the requested
                // (season, episode) — this gives the user multiple audio
                // variants (subbed/dual/hindi-dubbed) just like the
                // standalone CircleFtpProvider does.
                //
                // Multi-season handling:
                //   • If a post has content[N] where N >= season, use
                //     content[season-1].episodes[episode-1].link
                //   • If a post is a season-specific separate post (e.g.
                //     "One Piece Season 2"), its content[] array usually
                //     has just 1 entry for that season — we use content[0]
                //     as the requested season's episodes.
                val seasonToUse = season ?: 1
                val episodeToUse = episode ?: 1

                postDetails.forEach { detail ->
                    val contentArray = detail.optJSONArray("content")
                    if (contentArray == null || contentArray.length() == 0) return@forEach

                    // Try content[season-1] first (standard multi-season layout).
                    var seasonObj = contentArray.optJSONObject(seasonToUse - 1)
                    var episodesArray = seasonObj?.optJSONArray("episodes")

                    // Fallback: if content[season-1] doesn't exist or has no
                    // episodes, try content[0] — this handles separate-post-
                    // per-season where the post's only season is at index 0.
                    if (episodesArray == null || episodesArray.length() == 0) {
                        seasonObj = contentArray.optJSONObject(0)
                        episodesArray = seasonObj?.optJSONArray("episodes")
                    }

                    if (episodesArray != null && episodeToUse in 1..episodesArray.length()) {
                        val epObj = episodesArray.optJSONObject(episodeToUse - 1)
                        val link = epObj?.optString("link")
                        if (link != null && link.isNotEmpty()) {
                            mediaUrls += link
                        }
                    }
                }

                // Last-resort fallback: if no exact episode matched across
                // all posts, dump ALL episode links from ALL seasons of ALL
                // posts. The user will see a long list of episode links but
                // at least something will play.
                if (mediaUrls.isEmpty()) {
                    postDetails.forEach { detail ->
                        val contentArray = detail.optJSONArray("content") ?: return@forEach
                        for (si in 0 until contentArray.length()) {
                            val so = contentArray.optJSONObject(si) ?: return@forEach
                            val eps = so.optJSONArray("episodes") ?: return@forEach
                            for (ei in 0 until eps.length()) {
                                val eo = eps.optJSONObject(ei) ?: continue
                                val link = eo.optString("link")
                                if (link.isNotEmpty()) mediaUrls += link
                            }
                        }
                    }
                }
            }

            // 5. Also pick up any direct download links arrays.
            postDetails.forEach { detail ->
                detail.optJSONArray("downloadLinks")?.let { dlArr ->
                    for (i in 0 until dlArr.length()) {
                        val dl = dlArr.optJSONObject(i) ?: continue
                        val u = dl.optString("url").ifBlank { dl.optString("link") }
                        if (u.isNotBlank()) mediaUrls += u
                    }
                }
            }

            // 6. Emit. Apply linkToIp to swap *.circleftp.net hostnames
            //    for BDIX IPs so media URLs are reachable on BDIX networks.
            var any = false
            mediaUrls.forEach { u ->
                // Circle FTP encodes TV/movie URLs as
                // "circleftp://movie?data=<base64>" or
                // "circleftp://episode?data=<base64>" — base64 of a JSON
                // array of {url, audio?} objects. (Handled by
                // CircleFtpProvider.loadLinks lines 1507-1561.)
                if (u.contains("movie?data=") || u.contains("episode?data=") ||
                    u.contains("circleftp://")
                ) {
                    if (emitCircleFtpEncoded(u, srcLabel, callback)) any = true
                } else {
                    // Apply linkToIp for raw URLs containing circleftp.net hosts.
                    val resolvedUrl = linkToIp(u)
                    if (emitDirect(app, resolvedUrl, srcLabel, "$SITE/", emptyMap(), subtitleCallback, callback)) {
                        any = true
                    }
                }
            }
            return any
        }

        private suspend fun emitCircleFtpEncoded(
            data: String,
            sourceLabel: String,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val raw = data.substringAfter("data=").substringBefore("&")
                .substringBefore(" ").trim()
            if (raw.isBlank()) return false
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
                // Apply linkToIp — the encoded URL may contain a
                // circleftp.net hostname that needs BDIX IP swap.
                val resolvedUrl = linkToIp(u)
                val audio = o.optString("audio").takeIf { it.isNotBlank() }
                val name = if (audio != null) "$sourceLabel [$audio]" else sourceLabel
                val link = newExtractorLink(
                    source = sourceLabel,
                    name = name,
                    url = resolvedUrl,
                    type = INFER_TYPE,
                ) {
                    this.referer = SITE
                    this.quality = qualityFromName(resolvedUrl)
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
            // Match the standalone CircleFtpHttp.fetchWithFallback:
            // - No custom headers (the API doesn't expect any)
            // - verify = false (disables SSL verification for BDIX IPs)
            // - cacheTime = 60 (enables Cloudstream's HTTP cache)
            // - retries = 2 per mirror with jittered backoff
            for (mirror in listOf(primary, fallback)) {
                repeat(2) { attempt ->
                    val result = runCatching {
                        app.get(mirror, verify = false, cacheTime = 60, timeout = 20_000L)
                    }.getOrNull()
                    if (result != null && result.code in 200..299 && result.text.isNotBlank()) {
                        return result.text
                    }
                    if (attempt < 1) {
                        kotlinx.coroutines.delay(300L * (1 shl attempt))
                    }
                }
            }
            return null
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 4: CTGMovies  (https://ctgmovies.com)
    //  Already worked in v2 — unchanged.
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
//  Shared JSON helpers
// ─────────────────────────────────────────────────────────────────────────────

internal fun JSONObject.optStringOrNullCp(k: String): String? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").trim().takeIf { it.isNotBlank() && it != "null" }
