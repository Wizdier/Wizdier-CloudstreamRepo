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
        tmdbId: Int? = null,
        imdbId: String? = null,
    ): Boolean = coroutineScope {
        if (title.isBlank() && tmdbId == null && imdbId == null) {
            return@coroutineScope false
        }

        val sources = listOf(
            CineplexBdResolver,
            FtpBdResolver,
            CircleFtpResolver,
            CtgMoviesResolver,
            CinebyResolver,
            BingrResolver,
            MoonflixResolver,
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
                            tmdbId = tmdbId,
                            imdbId = imdbId,
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

    // ═══════════════════════════════════════════════════════════════════════
    //  Title-identity matching  (v18)
    //
    //  The BDIX search APIs return loose matches: searching "Scream" returns
    //  every Scream franchise entry plus anything else with "scream" in the
    //  title. Wizstream must attach links for THE movie/series TMDB says the
    //  user opened — nothing else — so posts are checked for *identity*,
    //  not token overlap:
    //    • identity tokens = title tokens with quality/rip/codec/lang junk,
    //      SxxEyy/Season markers, sizes and years stripped, roman numerals
    //      unified with digits ("VI" == "6")
    //    • a post matches when its identity tokens EQUAL the query's
    //      (stop-words ignored) — or differ only by edition keywords — AND
    //      the year (when both sides have one) is within ±1 of the TMDB year
    //
    //  This kills "the whole franchise in one video item" while still
    //  allowing several posts of the SAME film (multiple encodes/cuts).
    // ═══════════════════════════════════════════════════════════════════════

    internal val IDENTITY_JUNK_REGEX = Regex(
        "(?i)\\b(480p|576p|540p|720p|900p|1080p|1440p|2160p|4320p|[48]k|uhd|fhd|qhd|" +
            "blu[- ]?ray|bluray|bdremux|bdrip|brrip|web[- ]?dl|webdl|webrip|hdrip|" +
            "hd[- ]?rip|hdtv|pdtv|dvdrip|dvdscr|hdcam|hdts|hqcam|telesync|telecine|" +
            "camrip|screener|predvd|x264|x265|h\\.?26[45]|hevc|avc|av1|vp9|xvid|divx|" +
            "(?:8|10|12)[- ]?bit|hdr10\\+?|hdr|dolby[ -]?vision|sdr|" +
            "aac|ac3|e[- ]?ac3|ddp?[- ]?5\\.1|ddp?[- ]?7\\.1|dts|truehd|atmos|mp3|flac|" +
            "[257]\\.[01]|mkv|mp4|avi|mov|wmv|m4v|mpg|mpeg|hmulti|multi[- ]?audio|" +
            "dual[- ]?audio|dubbed|dub|subbed|subs?|esubs?|korsub|hc|" +
            "hindi|bengali|bangla|english|urdu|tamil|telugu|malayalam|kannada|nepali|" +
            "korean|japanese|chinese|french|german|spanish|portuguese|italian|russian|" +
            "arabic|turkish|persian|farsi|thai|" +
            "proper|repack|internal|limited|r5|nf|amzn|atvp|hulu|dsnp|hotstar|" +
            "open[- ]?matte|uncensored|readnfo|itunes|hybrid)\\b"
    )

    internal val IDENTITY_STOPWORDS = setOf("the", "a", "an", "of")

    // Edition/cut words that do NOT make a film a different film.
    internal val EDITION_TOKENS = setOf(
        "extended", "directors", "director", "cut", "final", "unrated", "uncut",
        "remastered", "remaster", "theatrical", "edition", "imax", "restored",
        "definitive", "ultimate", "anniversary", "special", "redux"
    )

    private val ROMAN_EQUIV = mapOf(
        "i" to "1", "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5",
        "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10",
    )

    internal data class TitleMeta(val tokens: Set<String>, val year: Int?)

    internal fun String.toTitleMeta(): TitleMeta {
        var t = this
        val yr = Regex("\\b(19|20)\\d{2}\\b").find(t)?.value?.toIntOrNull()
        t = t
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("(?i)\\b(19|20)\\d{2}\\b"), " ")
            .replace(IDENTITY_JUNK_REGEX, " ")
            .replace(Regex("(?i)\\bseason\\s*\\d{1,2}\\b"), " ")
            .replace(Regex("(?i)\\bs\\d{1,2}\\s*e\\d{1,3}\\b"), " ")
            .replace(Regex("(?i)\\bs\\d{1,2}\\b"), " ")
            .replace(Regex("(?i)\\be\\d{1,3}\\b"), " ")
            .replace(Regex("(?i)\\b(?:ep|episode)\\s*\\d{1,3}\\b"), " ")
            .replace(Regex("(?i)\\b\\d+(?:[.,]\\d+)?\\s*(?:gb|mb|tb)\\b"), " ")
        val toks = t.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { ROMAN_EQUIV[it] ?: it }
            .toSet()
        return TitleMeta(toks, yr)
    }

    /**
     * True when [postTitle] is the SAME movie/series as the TMDB item
     * ([queryTitle]/[queryYear]); false for franchise siblings, remakes with
     * a different year, and unrelated partial matches. A post may add pure
     * edition/cut keywords ("Extended Cut") and still be the same film, but
     * any other extra token ("2", "VI", "Queens", "Collection") marks a
     * DIFFERENT film.
     */
    internal fun isSameMediaTitle(postTitle: String, queryTitle: String, queryYear: Int?): Boolean {
        val q = queryTitle.toTitleMeta()
        val p = postTitle.toTitleMeta()
        if (q.tokens.isEmpty() || p.tokens.isEmpty()) return false
        // Year gate (Scream 1996 vs Scream 2022 — identical titles, different films).
        if (q.year != null && p.year != null && kotlin.math.abs(q.year - p.year) > 1) return false
        val missing = q.tokens - p.tokens - IDENTITY_STOPWORDS
        val extra = p.tokens - q.tokens - IDENTITY_STOPWORDS
        if (missing.isEmpty() && extra.isEmpty()) return true
        if (missing.isEmpty() && extra.all { it in EDITION_TOKENS }) return true
        return false
    }

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
            tmdbId: Int? = null,
            imdbId: String? = null,
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
            tmdbId: Int?,
            imdbId: String?,
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
            //
            // FIX (v12): For movies, ONLY include /view.php URLs — NOT
            // /watch.php URLs. /watch.php?id=X is a SERIES page (for series
            // that use `id` instead of `series_id`), not a movie page. The
            // old filter `!u.contains("watch.php?series_id")` still allowed
            // /watch.php?id=X, which caused the title matcher to pick a
            // series page as the "best match" for a movie query. The resolver
            // then fetched /player.php?id=<series_id>, which returned a
            // series episode page instead of a movie player — extraction
            // failed and no links were emitted.
            val filtered = candidates.filter { (u, _) ->
                if (isMovie) u.contains("view.php") && !u.contains("watch")
                else u.contains("watch.php") || u.contains("tview.php")
            }.ifEmpty { candidates }

            // Exact-normalised match first, then fall back to highest similarity.
            // FIX (v18): identity matching. The old exact-then-Jaccard pick
            // with a hard 0.4 cutoff silently rejected legitimate matches
            // whenever the card title carried extras the TMDB title lacks
            // (year, quality, "Bengali Dubbed", ...) — which is why
            // CineplexBD looked dead inside Wizstream while the standalone
            // (no auto-match gate, the user picks by hand) worked fine.
            // Multiple candidates of the same film (quality variants) are
            // still tried in best-similarity order.
            val identityMatches = filtered.filter { (_, ct) -> isSameMediaTitle(ct, title, year) }
            val best = identityMatches.maxByOrNull { (_, ct) -> titleSimilarity(ct, title) }
                ?: run {
                    Log.d(TAG, "CineplexBD: no identity match for '$title' (year=$year)")
                    return false
                }

            val srcLabel = "$labelPrefix • $LABEL"

            // ── MOVIE PATH ──────────────────────────────────────────────────
            // CineplexBDProvider.load() builds the data URL as
            //   /player.php?id=$id
            // then loadLinks fetches /player.php?id=$id which contains the
            // actual video embed. The player page may use one of:
            //   • Old style:  const videoSrc = "….m3u8|mp4|mkv"
            //   • Legacy:     <source src="…">
            //   • Quetta:     data-quetta-video-id="qv_xxx_xxx" (loaded via JS)
            //
            // We capture cookies from the response and forward them as a
            // Cookie header — this is required for protected video URLs and
            // matches the Aniyomi CineplexBD extension's pattern.
            if (isMovie) {
                // Defensive check: if the best match is NOT a /view.php URL
                // (e.g. it's a /watch.php series page), bail out — fetching
                // /player.php?id=<series_id> would return a series page, not
                // a movie player, and extraction would fail silently.
                if (!best.first.contains("view.php")) return false

                val id = best.first.substringAfter("id=").substringBefore("&")
                if (id.isBlank()) return false
                val playerUrl = "$SITE/player.php?id=$id"
                val playerResp = runCatching {
                    app.get(playerUrl, headers = HEADERS, timeout = 15_000)
                }.getOrNull() ?: return false
                val playerHtml = playerResp.text

                // Forward Set-Cookie values + Referer to downstream requests.
                // NiceResponse.cookies is a Map<String, String> of cookie
                // name → value, already deduplicated by the cookie jar.
                val cookieHeader = try {
                    playerResp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                } catch (_: Throwable) { "" }
                val videoHeaders = HEADERS.toMutableMap().apply {
                    if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
                    put("Referer", playerUrl)
                }.toMap()

                val mediaUrls = extractMediaUrlsFromHtml(playerHtml, playerUrl)
                var any = false
                mediaUrls.forEach { u ->
                    if (emitDirect(app, u, srcLabel, playerUrl, videoHeaders, subtitleCallback, callback)) any = true
                }

                // ── Quetta player extraction ──────────────────────────────
                // If no direct sources were found but the page contains a
                // data-quetta-video-id, try the Quetta API endpoints.
                if (!any) {
                    val quettaId: String? = Regex(
                        """data-quetta-video-id=["']?(qv_[a-z0-9_]+)["']?""",
                        RegexOption.IGNORE_CASE
                    ).find(playerHtml)?.groupValues?.getOrNull(1)
                    if (quettaId != null && quettaId.isNotBlank()) {
                        if (emitQuettaVideo(app, quettaId, playerUrl, videoHeaders, srcLabel, subtitleCallback, callback)) {
                            any = true
                        }
                    }
                }

                // ── download.php fallback ─────────────────────────────────
                // /download.php?id=<id> often has a direct <a href="/Data/…">
                // link we can scrape.
                if (!any) {
                    val dlUrl = "$SITE/download.php?id=$id"
                    runCatching {
                        val dlHtml = app.get(dlUrl, headers = videoHeaders, timeout = 15_000).text
                        val dlUrls = extractMediaUrlsFromHtml(dlHtml, dlUrl)
                        dlUrls.forEach { u ->
                            if (emitDirect(app, u, srcLabel, dlUrl, videoHeaders, subtitleCallback, callback)) any = true
                        }
                    }
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
            //   • Quetta player page → data-quetta-video-id="qv_…"
            val absPath = resolveAbs(SITE, matchPath)
            return when {
                isDirectMedia(absPath) ->
                    emitDirect(app, absPath, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)
                absPath.contains("player.php") || absPath.contains("view.php") -> {
                    val playerResp = runCatching {
                        app.get(absPath, headers = HEADERS, timeout = 15_000)
                    }.getOrNull() ?: return false
                    val playerHtml = playerResp.text

                    // Forward cookies + Referer to downstream video requests.
                    val cookieHeader = try {
                        playerResp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    } catch (_: Throwable) { "" }
                    val videoHeaders = HEADERS.toMutableMap().apply {
                        if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
                        put("Referer", absPath)
                    }.toMap()

                    val mediaUrls = extractMediaUrlsFromHtml(playerHtml, absPath)
                    var any = false
                    mediaUrls.forEach { u ->
                        if (emitDirect(app, u, srcLabel, absPath, videoHeaders, subtitleCallback, callback)) any = true
                    }

                    // Quetta player fallback for TV episodes.
                    if (!any) {
                        val quettaId: String? = Regex(
                            """data-quetta-video-id=["']?(qv_[a-z0-9_]+)["']?""",
                            RegexOption.IGNORE_CASE
                        ).find(playerHtml)?.groupValues?.getOrNull(1)
                        if (quettaId != null && quettaId.isNotBlank()) {
                            if (emitQuettaVideo(app, quettaId, absPath, videoHeaders, srcLabel, subtitleCallback, callback)) {
                                any = true
                            }
                        }
                    }

                    // download.php fallback for TV episodes.
                    if (!any && absPath.contains("player.php")) {
                        val id = absPath.substringAfter("id=", "").substringBefore("&")
                        if (id.isNotBlank()) {
                            val dlUrl = "$SITE/download.php?id=$id"
                            runCatching {
                                val dlHtml = app.get(dlUrl, headers = videoHeaders, timeout = 15_000).text
                                val dlUrls = extractMediaUrlsFromHtml(dlHtml, dlUrl)
                                dlUrls.forEach { u ->
                                    if (emitDirect(app, u, srcLabel, dlUrl, videoHeaders, subtitleCallback, callback)) any = true
                                }
                            }
                        }
                    }
                    any
                }
                else -> emitDirect(app, absPath, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)
            }
        }

        /**
         * Try multiple candidate Quetta API endpoints to resolve a
         * `data-quetta-video-id` to a playable URL.
         *
         * The actual Quetta API endpoint is embedded in a JS file loaded by
         * player.php and isn't publicly documented. We try the most common
         * shapes used by similar player frameworks.
         */
        private suspend fun emitQuettaVideo(
            app: Requests,
            quettaId: String,
            playerUrl: String,
            videoHeaders: Map<String, String>,
            sourceLabel: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val candidates = listOf(
                "https://vidquettaplayer.com/api/?id=$quettaId",
                "https://vidquettaplayer.com/api/source/$quettaId",
                "https://vidquettaplayer.com/Quetta/?id=$quettaId",
                "https://quetta.com/api/?id=$quettaId",
                "https://api.quetta.io/v1/video/$quettaId",
                "$SITE/api/quetta/?id=$quettaId",
                "$SITE/Quetta/api/?id=$quettaId",
            )
            for (apiUrl in candidates) {
                val resp = runCatching {
                    app.get(apiUrl, headers = videoHeaders, timeout = 10_000)
                }.getOrNull() ?: continue
                if (resp.code !in 200..299 || resp.text.isBlank()) continue
                // Skip HTML responses (likely 404 pages or CF challenge pages).
                if (resp.text.startsWith("<") || resp.text.contains("<!DOCTYPE", true)) continue
                val json = runCatching { JSONObject(resp.text) }.getOrNull() ?: continue
                val mediaUrls = linkedSetOf<String>()
                // Try common JSON shapes.
                json.optJSONObject("data")?.let { dataObj ->
                    dataObj.optStringOrNullCp("src")?.let { mediaUrls += it }
                    dataObj.optStringOrNullCp("url")?.let { mediaUrls += it }
                    dataObj.optStringOrNullCp("file")?.let { mediaUrls += it }
                    dataObj.optJSONArray("sources")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val s = arr.optJSONObject(i) ?: continue
                            s.optStringOrNullCp("file")?.let { mediaUrls += it }
                            s.optStringOrNullCp("src")?.let { mediaUrls += it }
                            s.optStringOrNullCp("url")?.let { mediaUrls += it }
                        }
                    }
                }
                json.optStringOrNullCp("url")?.let { mediaUrls += it }
                json.optStringOrNullCp("src")?.let { mediaUrls += it }
                json.optJSONArray("sources")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        s.optStringOrNullCp("file")?.let { mediaUrls += it }
                        s.optStringOrNullCp("src")?.let { mediaUrls += it }
                        s.optStringOrNullCp("url")?.let { mediaUrls += it }
                    }
                }
                if (mediaUrls.isEmpty()) continue
                var any = false
                for (u in mediaUrls) {
                    val abs = if (u.startsWith("http")) u else "$SITE/${u.trimStart('/')}"
                    if (emitDirect(app, abs, sourceLabel, playerUrl, videoHeaders, subtitleCallback, callback)) {
                        any = true
                    }
                }
                if (any) return true
            }
            return false
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
            tmdbId: Int?,
            imdbId: String?,
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
            // (v20) REVERTED to the pre-v18 fuzzy pick — the v18 identity+year
            // gate was too strict for FTPBD's catalogue (bad/absent years),
            // which made titles that used to resolve silently disappear.
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
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
        )

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
            tmdbId: Int?,
            imdbId: String?,
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

            // 2. Collect all posts, then keep only the ones that ARE the
            //    requested TMDB item.
            //
            //    FIX (v18): v17 collected EVERY post the search API returned
            //    and the "4b" zero-token check only dropped posts with no
            //    shared word — so "Scream" pulled in Scream 2/3/4/VI and
            //    anything else containing "scream", and their links were all
            //    merged into one video item. Posts are now identity-matched
            //    (tokens + year) against the TMDB title; franchise siblings
            //    and unrelated matches are dropped, while multiple posts of
            //    the SAME film (different encodes / cuts / audio variants)
            //    are kept. If NOTHING matches, we emit nothing from Circle
            //    FTP — wrong links are worse than missing links.
            val identityFiltered = mutableListOf<Pair<Int, String>>()
            //
            //    FIX (v17): The previous code filtered posts by title similarity
            //    (score >= 0.5 AND pNorm.contains(qNorm)) which was TOO STRICT.
            //    This caused short-titled movies like "Dune", "Joker", "Inception"
            //    to be rejected because their post titles often have extra words
            //    (year, quality, "Extended Edition") that diluted the Jaccard
            //    similarity score. Only long titles like "Lord of the Rings:
            //    The Return of the King" had enough tokens to pass the filter.
            //
            //    The standalone CircleFtpProvider does NOT filter by similarity
            //    at all — it groups ALL posts returned by the search API and
            //    lets the user pick. We now do the same: collect every post
            //    the API returns, then filter by `type` after fetching details.
            //
            //    The search API itself does the relevance filtering server-side,
            //    so we trust its results. If the API returns 0 posts, we bail.
            for (i in 0 until postsArr.length()) {
                val p = postsArr.optJSONObject(i) ?: continue
                val ptitle = p.optString("title").ifBlank { p.optString("name") ?: "" }
                if (ptitle.isBlank()) continue
                // Year hint for the identity check: title year first, then the
                // post's date fields (API rows often carry them).
                val postYear = Regex("\\b(19|20)\\d{2}\\b").find(ptitle)?.value?.toIntOrNull()
                    ?: p.optStringOrNullCp("date")?.take(4)?.toIntOrNull()
                    ?: p.optStringOrNullCp("created_at")?.take(4)?.toIntOrNull()
                    ?: p.optStringOrNullCp("upload_date")?.take(4)?.toIntOrNull()
                val effectiveYear = year ?: postYear
                if (isSameMediaTitle(ptitle, title, effectiveYear)) {
                    identityFiltered += p.optInt("id", -1) to ptitle
                }
            }
            val matchingPostIds = identityFiltered
            if (matchingPostIds.isEmpty()) {
                Log.d(TAG, "CircleFTP: no identity match for '$title' (year=$year) — skipping")
                return false
            }

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

            // 4. Filter posts by type ONLY for movies. For TV/anime, keep ALL
            //    posts (the pre-v11 behaviour that was working). This is
            //    safer because:
            //      • For movies (isMovie=true): we need only singleVideo
            //        posts, so filtering is correct.
            //      • For TV/anime (isMovie=false): the type field might be
            //        inconsistent or missing for some posts. Filtering here
            //        risks excluding valid posts. The TV path already
            //        handles both singleVideo and non-singleVideo posts
            //        gracefully (it checks content JSONArray vs String).
            val typeFiltered = if (isMovie) {
                postDetails.filter { it.optString("type", "") == "singleVideo" }
            } else {
                postDetails
            }
            if (typeFiltered.isEmpty()) return false

            // (v18) identity matching happened before the detail fetches —
            // every post here is the requested title, so no relevance
            // re-check (and no "keep everything" fallback) is needed.
            val filteredDetails = typeFiltered
            if (filteredDetails.isEmpty()) return false

            // 5. Route to movie or TV/anime path based on the user's request.
            if (isMovie) {
                // ── MOVIE PATH ──────────────────────────────────────────────
                // postObj.optString("content") returns the direct media URL
                // as a STRING for singleVideo posts. (Matches
                // CircleFtpProvider line 1230.)
                //
                // We use `detail.opt("content")` and check `is String`
                // to avoid emitting JSON-array-as-string garbage (which
                // happens if a post is mis-typed as singleVideo but has
                // a content array).
                filteredDetails.forEach { detail ->
                    val contentField = detail.opt("content")
                    if (contentField is String) {
                        contentField.takeIf { it.isNotBlank() }?.let { mediaUrls += it }
                    }
                    // Some posts put the URL in `url` / `file` / `src` instead.
                    detail.optStringOrNullCp("url")?.let { mediaUrls += it }
                    detail.optStringOrNullCp("file")?.let { mediaUrls += it }
                    detail.optStringOrNullCp("src")?.let { mediaUrls += it }
                }
            } else {
                // ── TV/ANIME PATH ───────────────────────────────────────────
                // Each post has content[seasonIndex].episodes[epIndex].link
                // We collect links from ALL matching posts for the requested
                // (season, episode) — this gives the user multiple audio
                // variants (subbed/dual/hindi-dubbed) just like the
                // standalone CircleFtpProvider does.
                //
                // Multi-season handling (FIX v11):
                //   • If a post has content[N] where N >= season, use
                //     content[season-1].episodes[episode-1].link
                //   • If a post is a season-specific separate post (e.g.
                //     "One Piece Season 2"), its content[] array usually
                //     has just 1 entry for that season. We use content[0]
                //     ONLY IF the post title's season number matches the
                //     requested season — otherwise we'd return the wrong
                //     season's episode.
                //   • If the post title has no "Season N" / "S<N>" marker
                //     AND content[season-1] doesn't exist, fall back to
                //     content[0] (single-season anime case).
                val seasonToUse = season ?: 1
                val episodeToUse = episode ?: 1

                filteredDetails.forEach { detail ->
                    val contentArray = detail.optJSONArray("content")
                    if (contentArray == null || contentArray.length() == 0) return@forEach

                    // Determine the post's season number from its title.
                    // "One Piece Season 2" → 2; "One Piece S3" → 3; "One Piece" → null.
                    val postTitleStr = detail.optString("title", "")
                        .ifBlank { detail.optString("name", "") }
                    val titleSeasonNum = extractSeasonFromTitle(postTitleStr)

                    // Try content[season-1] first (standard multi-season layout).
                    var seasonObj = contentArray.optJSONObject(seasonToUse - 1)
                    var episodesArray = seasonObj?.optJSONArray("episodes")

                    // Fallback: if content[season-1] doesn't exist or has no
                    // episodes, try content[0] — but ONLY when:
                    //   • The post title explicitly says "Season N" matching
                    //     the requested season (so content[0] IS the
                    //     requested season), OR
                    //   • The post title has no "Season N" marker (so this
                    //     is likely a single-season post).
                    if (episodesArray == null || episodesArray.length() == 0) {
                        val canFallbackToContent0 = titleSeasonNum == null || titleSeasonNum == seasonToUse
                        if (canFallbackToContent0) {
                            seasonObj = contentArray.optJSONObject(0)
                            episodesArray = seasonObj?.optJSONArray("episodes")
                        }
                    }

                    if (episodesArray != null && episodeToUse in 1..episodesArray.length()) {
                        val epObj = episodesArray.optJSONObject(episodeToUse - 1)
                        val link = epObj?.optStringOrNullCp("link")
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
                    filteredDetails.forEach { detail ->
                        val contentArray = detail.optJSONArray("content") ?: return@forEach
                        for (si in 0 until contentArray.length()) {
                            val so = contentArray.optJSONObject(si) ?: continue
                            val eps = so.optJSONArray("episodes") ?: continue
                            for (ei in 0 until eps.length()) {
                                val eo = eps.optJSONObject(ei) ?: continue
                                val link = eo.optStringOrNullCp("link") ?: continue
                                if (link.isNotEmpty()) mediaUrls += link
                            }
                        }
                    }
                }
            }

            // 6. Also pick up any direct download links arrays.
            filteredDetails.forEach { detail ->
                detail.optJSONArray("downloadLinks")?.let { dlArr ->
                    for (i in 0 until dlArr.length()) {
                        val dl = dlArr.optJSONObject(i) ?: continue
                        val u = dl.optStringOrNullCp("url") ?: dl.optStringOrNullCp("link")
                        if (u != null && u.isNotBlank()) mediaUrls += u
                    }
                }
            }

            // 7. Emit. Apply linkToIp to swap *.circleftp.net hostnames
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
                    if (emitDirect(app, resolvedUrl, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)) {
                        any = true
                    }
                }
            }
            return any
        }

        /**
         * Extract a season number from a post title.
         * Returns null if the title has no season marker.
         *
         * Examples:
         *   "One Piece Season 2" → 2
         *   "One Piece S3"       → 3
         *   "One Piece Season 2 [Hindi Dubbed]" → 2
         *   "One Piece"          → null
         */
        private fun extractSeasonFromTitle(title: String): Int? {
            // Match "Season N" or "SeasonN" (case-insensitive).
            Regex("(?i)\\bseason\\s*(\\d+)\\b").find(title)?.let {
                return it.groupValues.getOrNull(1)?.toIntOrNull()
            }
            // Match "S<N>" but not "S" alone or "s03e15" (which is episode).
            // We require a word boundary before S and the number to be 1-2 digits.
            Regex("(?i)\\bS(\\d{1,2})\\b").find(title)?.let {
                return it.groupValues.getOrNull(1)?.toIntOrNull()
            }
            return null
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
            // CRITICAL: verify=false and cacheTime=60 are REQUIRED for the
            // CircleFTP API to work — the standalone CircleFtpHttp uses
            // these exact flags. Without verify=false, the API returns
            // empty/error responses on BDIX networks. Without cacheTime,
            // every request hits the server (no caching).
            val a = runCatching {
                app.get(primary, headers = HEADERS, verify = false, cacheTime = 60, timeout = 10_000)
            }.getOrNull()
            if (a != null && a.code in 200..299 && a.text.isNotBlank()) return a.text
            val b = runCatching {
                app.get(fallback, headers = HEADERS, verify = false, cacheTime = 60, timeout = 10_000)
            }.getOrNull()
            if (b != null && b.code in 200..299 && b.text.isNotBlank()) return b.text
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
            tmdbId: Int?,
            imdbId: String?,
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

            // (v20) REVERTED to the pre-v18 fuzzy pick — CTG's API often has
            // missing/mismatched years, so the v18 identity+year gate rejected
            // posts that used to resolve fine.
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

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 5: Cineby  (https://www.cineby.at)
    //
    //  Ported from the Aniyomi Cineby extension (Wizdier-AniRepo).
    //  Cineby is a Next.js SPA that delegates video resolution to a
    //  Videasy/Insertunit backend at api.speedracelight.com.
    //
    //  Three-step resolution flow:
    //    1. GET /seed?mediaId={tmdbId} → {seed, ttlMs}
    //    2. GET /{serverPath}/sources-with-title?...&enc=2&seed={seed}
    //       → encrypted ciphertext (or {"error":"STREAMCRYPTO_SEED_INVALID"})
    //    3. POST enc-dec.app/api/dec-videasy {text, id, seed}
    //       → {sources:[{url,quality}], subtitles:[...]}
    //
    //  8 servers on api.speedracelight.com:
    //    Neon    (neon2)     — Original audio, movies + TV
    //    Yoru    (cdn)       — Original audio, movies + TV (v19), may have 4K
    //    Breach  (m4uhd)     — Original audio, movies + TV
    //    Vyse    (hdmovie)   — English quality filter
    //    Killjoy (meine)     — German audio
    //    Fade    (hdmovie)   — Hindi quality filter
    //    Omen    (lamovie)   — Spanish audio
    //    Raze    (superflix) — Portuguese audio
    //
    //  CRITICAL for HTTP 2004/3003 prevention:
    //    • Send Referer+Origin headers on EVERY request to api.speedracelight.com
    //    • Set ExtractorLink.referer = "https://www.cineby.at/" on emitted links
    //    • Filter out non-http URLs and HTML bodies before emitting (prevents 3003)
    //    • Force-rewrite http:// → https:// (prevents 2007 cleartext error)
    //    • Title must be DOUBLE percent-encoded
    //    • Always pass &enc=2 and &seed=...
    // ════════════════════════════════════════════════════════════════════════

    internal object CinebyResolver : SourceResolver {
        private const val SITE = "https://www.cineby.at"
        private const val API_BASE = "https://api.speedracelight.com"
        private const val DECRYPT_API = "https://enc-dec.app/api/dec-videasy"
        private const val TMDB_PROXY = "https://db.speedracelight.com/3"
        private const val LABEL = "Cineby"

        // TMDB API key — same as the one used in WizstreamProvider.
        // Used only for the year lookup when ctx.year is null.
        private const val TMDB_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"

        private val API_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Origin" to SITE,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
        )

        private data class CinebyServer(
            val displayName: String,
            val path: String,
            val language: String? = null,
            val movieOnly: Boolean = false,
            val audioLabel: String? = null,
            val qualityFilter: String? = null,
        )

        private val SERVERS = listOf(
            CinebyServer("Neon",    "neon2",    audioLabel = "Original"),
            // (v19) Yoru serves TV series too — verified against the live API
            // (mediaType=tv returns sources+subtitles). The old movieOnly flag
            // was the only reason it never appeared for series.
            CinebyServer("Yoru",    "cdn",      audioLabel = "Original"),
            CinebyServer("Breach",  "m4uhd",    audioLabel = "Original"),
            CinebyServer("Vyse",    "hdmovie",  qualityFilter = "English", audioLabel = "Original"),
            CinebyServer("Killjoy", "meine",    language = "german", audioLabel = "German"),
            CinebyServer("Fade",    "hdmovie",  qualityFilter = "Hindi",  audioLabel = "Hindi"),
            CinebyServer("Omen",    "lamovie",  audioLabel = "Spanish"),
            CinebyServer("Raze",    "superflix",audioLabel = "Portuguese"),
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
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            // Cineby REQUIRES a TMDB ID — without it we can't call the API.
            if (tmdbId == null) return false
            val srcLabel = "$labelPrefix • $LABEL"
            val seasonId = if (isMovie) "1" else (season?.toString() ?: "1")
            val episodeId = if (isMovie) "1" else (episode?.toString() ?: "1")
            val mediaType = if (isMovie) "movie" else "tv"

            // If year is null, look it up via TMDB proxy so the backend can
            // disambiguate (improves hit-rate).
            val yearStr = year?.toString() ?: fetchYear(app, tmdbId, mediaType) ?: ""

            // If IMDB ID wasn't provided, look it up via TMDB external_ids.
            val imdbIdStr = imdbId?.takeIf { it.isNotBlank() }
                ?: fetchImdbId(app, tmdbId, mediaType) ?: ""

            // ── Fetch the seed ONCE per media item ──────────────────────────
            // The seed URL is the same for ALL servers (same apiBase + mediaId),
            // so fetching it 8 times (once per server) is wasteful and triggers
            // rate-limiting on api.speedracelight.com. Fetch it once and reuse.
            // The seed has a 30-second TTL — plenty for 8 sequential server calls.
            val seed = fetchSeed(app, tmdbId)
            if (seed.isBlank()) {
                Log.d(TAG, "Cineby: seed fetch failed for tmdbId=$tmdbId")
                return false
            }

            // Hit all eligible servers in parallel, but with bounded concurrency
            // (3 at a time) to avoid rate-limiting on api.speedracelight.com.
            // The "first works, rest fail" bug was caused by firing 24 requests
            // (8 seeds + 8 sources + 8 decrypts) simultaneously — the backend
            // rate-limited the IP after the first batch.
            val eligible = SERVERS.filter { !it.movieOnly || isMovie }
            val gate = Semaphore(3)
            var any = false
            coroutineScope {
                eligible.map { server ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            runCatching {
                                resolveOneServer(
                                    app, server, tmdbId, seed, title, yearStr, imdbIdStr,
                                    mediaType, seasonId, episodeId,
                                    srcLabel, subtitleCallback, callback,
                                )
                            }.onFailure {
                                Log.d(TAG, "Cineby: server ${server.displayName} failed: ${it.message}")
                            }.getOrDefault(false)
                        }
                    }
                }.awaitAll().forEach { if (it) any = true }
            }
            if (!any) {
                Log.d(TAG, "Cineby: all servers failed for tmdbId=$tmdbId title=$title")
            }
            return any
        }

        /**
         * Fetch the seed ONCE per media item. The seed is valid for 30 seconds
         * and is the same for all 8 servers (same apiBase + mediaId).
         * Uses cacheTime=0 to prevent Cloudstream from caching the seed
         * response across different media items.
         */
        private suspend fun fetchSeed(app: Requests, tmdbId: Int): String {
            val seedUrl = "$API_BASE/seed?mediaId=$tmdbId"
            val resp = runCatching {
                app.get(seedUrl, headers = API_HEADERS, cacheTime = 0, timeout = 10_000)
            }.getOrNull() ?: return ""
            if (resp.code !in 200..299 || resp.text.isBlank()) return ""
            return runCatching {
                JSONObject(resp.text).optString("seed", "")
            }.getOrDefault("")
        }

        private suspend fun resolveOneServer(
            app: Requests,
            server: CinebyServer,
            tmdbId: Int,
            seed: String,
            title: String,
            yearStr: String,
            imdbIdStr: String,
            mediaType: String,
            seasonId: String,
            episodeId: String,
            srcLabel: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            // Index each server as its own source group in the picker.
            // This gives the user a clean hierarchy:
            //   Wizstream • Cineby · Neon
            //     ├── 1080p · Original audio
            //     └── 720p · Original audio
            //   Wizstream • Cineby · Yoru
            //     ├── 2160p · 4K · Original audio
            //     └── 1080p · Original audio
            //   ...
            val serverSourceLabel = "$srcLabel · ${server.displayName}"

            // Step 1 (seed) is already done by the caller — reuse the same
            // seed for all 8 servers to avoid 8x rate-limit on the seed endpoint.

            // Step 2: Fetch encrypted sources.
            val titleDoubled = doubleEncode(title)
            val srcUrl = buildString {
                append(API_BASE).append('/').append(server.path).append("/sources-with-title")
                append("?title=").append(titleDoubled)
                append("&mediaType=").append(mediaType)
                append("&year=").append(encodeUrl(yearStr))
                append("&episodeId=").append(episodeId)
                append("&seasonId=").append(seasonId)
                append("&tmdbId=").append(tmdbId)
                append("&enc=2")
                append("&seed=").append(encodeUrl(seed))
                if (imdbIdStr.isNotBlank()) append("&imdbId=").append(encodeUrl(imdbIdStr))
                server.language?.let { append("&language=").append(it) }
            }
            val srcResp = runCatching {
                app.get(srcUrl, headers = API_HEADERS, cacheTime = 0, timeout = 15_000)
            }.getOrNull() ?: return false
            if (srcResp.code !in 200..299 || srcResp.text.isBlank()) return false
            val encryptedText = srcResp.text
            // Detect error JSON — skip if it's an error.
            if (encryptedText.trimStart().startsWith("{") &&
                (encryptedText.contains("\"error\"") || encryptedText.contains("STREAMCRYPTO_SEED_INVALID"))
            ) {
                return false
            }

            // Step 3: Decrypt via enc-dec.app.
            val decBody = JSONObject().apply {
                put("text", encryptedText)
                put("id", tmdbId.toString())
                put("seed", seed)
            }.toString()
            val decResp = runCatching {
                app.post(
                    DECRYPT_API,
                    headers = API_HEADERS + ("Content-Type" to "application/json"),
                    requestBody = decBody.toRequestBody("application/json".toMediaTypeOrNull()),
                    cacheTime = 0,
                    timeout = 15_000,
                )
            }.getOrNull() ?: return false
            if (decResp.code !in 200..299 || decResp.text.isBlank()) return false
            val decJson = runCatching { JSONObject(decResp.text) }.getOrNull() ?: return false
            val result = decJson.optJSONObject("result") ?: return false

            // Step 4: Emit sources + subtitles.
            val subtitles = result.optJSONArray("subtitles")
            if (subtitles != null) {
                for (i in 0 until subtitles.length()) {
                    val s = subtitles.optJSONObject(i) ?: continue
                    val subUrl = s.optStringOrNullCp("url") ?: s.optStringOrNullCp("file")
                        ?: s.optStringOrNullCp("src") ?: continue
                    val rawSubLabel = s.optStringOrNullCp("language") ?: s.optStringOrNullCp("label")
                        ?: s.optStringOrNullCp("lang") ?: s.optStringOrNullCp("name") ?: "Subtitle"
                    // Prefix subtitle label with the server name so the user
                    // can see which subtitles came from which server. This is
                    // important because Cloudstream merges ALL subtitles from
                    // ALL sources into one list — without the prefix the user
                    // can't tell which subtitle matches their chosen source.
                    // Example: "[Neon] English", "[Neon] Japanese",
                    //           "[Yoru] English", "[Killjoy] German"
                    val subLabel = "[${server.displayName}] $rawSubLabel"
                    subtitleCallback(SubtitleFile(subLabel, subUrl))
                }
            }

            var any = false
            val sourcesArr = result.optJSONArray("sources")
            if (sourcesArr != null) {
                for (i in 0 until sourcesArr.length()) {
                    val s = sourcesArr.optJSONObject(i) ?: continue
                    val url = s.optStringOrNullCp("url") ?: continue
                    // FILTER: prevent 3003 — only emit real http(s) URLs.
                    if (!url.startsWith("http")) continue
                    // Force https to prevent 2007 cleartext error.
                    val safeUrl = if (url.startsWith("http://")) {
                        url.replaceFirst("http://", "https://")
                    } else url
                    val quality = s.optStringOrNullCp("quality") ?: "Auto"

                    // Apply server's qualityFilter (Vyse=English, Fade=Hindi).
                    if (server.qualityFilter != null &&
                        !quality.equals(server.qualityFilter, ignoreCase = true)
                    ) continue

                    val name = buildLabel(server, quality, srcLabel)
                    if (emitTaggedMedia(app, safeUrl, serverSourceLabel, name, callback)) {
                        any = true
                    } else if (safeUrl.contains(".m3u8", true)) {
                        // Use M3u8Helper to expand master playlist → per-quality links.
                        // CRITICAL: pass Referer+Origin headers to prevent 2004.
                        M3u8Helper.generateM3u8(
                            source = serverSourceLabel,
                            streamUrl = safeUrl,
                            referer = "$SITE/",
                            headers = API_HEADERS,
                        ).forEach { link ->
                            callback(
                                newExtractorLink(
                                    source = serverSourceLabel,
                                    name = name,
                                    url = link.url,
                                    type = ExtractorLinkType.M3U8,
                                ) {
                                    this.referer = "$SITE/"
                                    this.quality = link.quality
                                    this.headers = API_HEADERS
                                }
                            )
                            any = true
                        }
                    } else if (safeUrl.contains(".mp4", true) ||
                        safeUrl.contains(".mkv", true) ||
                        safeUrl.contains(".webm", true)
                    ) {
                        callback(
                            newExtractorLink(
                                source = serverSourceLabel,
                                name = name,
                                url = safeUrl,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = "$SITE/"
                                this.quality = qualityFromName(safeUrl)
                                this.headers = API_HEADERS
                            }
                        )
                        any = true
                    } else if (safeUrl.contains(".mpd", true)) {
                        callback(
                            newExtractorLink(
                                source = serverSourceLabel,
                                name = name,
                                url = safeUrl,
                                type = ExtractorLinkType.DASH,
                            ) {
                                this.referer = "$SITE/"
                                this.quality = qualityFromName(safeUrl)
                                this.headers = API_HEADERS
                            }
                        )
                        any = true
                    }
                    // Else: skip — the URL is likely a non-media resource.
                }
            } else {
                // Legacy shape 1: {url:"...m3u8", subtitles:[...]}
                val singleUrl = result.optStringOrNullCp("url")
                if (singleUrl != null && singleUrl.startsWith("http")) {
                    val safeUrl = if (singleUrl.startsWith("http://")) {
                        singleUrl.replaceFirst("http://", "https://")
                    } else singleUrl
                    val name = buildLabel(server, "Auto", srcLabel)
                    if (safeUrl.contains(".m3u8", true)) {
                        M3u8Helper.generateM3u8(
                            source = serverSourceLabel,
                            streamUrl = safeUrl,
                            referer = "$SITE/",
                            headers = API_HEADERS,
                        ).forEach { link ->
                            callback(
                                newExtractorLink(
                                    source = serverSourceLabel,
                                    name = name,
                                    url = link.url,
                                    type = ExtractorLinkType.M3U8,
                                ) {
                                    this.referer = "$SITE/"
                                    this.quality = link.quality
                                    this.headers = API_HEADERS
                                }
                            )
                            any = true
                        }
                    }
                } else {
                    // Legacy shape 2: {streams:{"1080p":"url","720p":"url"}, subtitles:[...]}
                    val streams = result.optJSONObject("streams")
                    if (streams != null) {
                        val keys = streams.keys()
                        while (keys.hasNext()) {
                            val q = keys.next()
                            val url = streams.optString(q, "").ifBlank { continue }
                            if (!url.startsWith("http")) continue
                            val safeUrl = if (url.startsWith("http://")) {
                                url.replaceFirst("http://", "https://")
                            } else url
                            val name = buildLabel(server, q, srcLabel)
                            if (safeUrl.contains(".m3u8", true)) {
                                M3u8Helper.generateM3u8(
                                    source = serverSourceLabel,
                                    streamUrl = safeUrl,
                                    referer = "$SITE/",
                                    headers = API_HEADERS,
                                ).forEach { link ->
                                    callback(
                                        newExtractorLink(
                                            source = serverSourceLabel,
                                            name = name,
                                            url = link.url,
                                            type = ExtractorLinkType.M3U8,
                                        ) {
                                            this.referer = "$SITE/"
                                            this.quality = link.quality
                                            this.headers = API_HEADERS
                                        }
                                    )
                                    any = true
                                }
                            } else {
                                callback(
                                    newExtractorLink(
                                        source = serverSourceLabel,
                                        name = name,
                                        url = safeUrl,
                                        type = INFER_TYPE,
                                    ) {
                                        this.referer = "$SITE/"
                                        this.quality = qualityFromName(q)
                                        this.headers = API_HEADERS
                                    }
                                )
                                any = true
                            }
                        }
                    }
                }
            }
            return any
        }

        /**
         * Build the link NAME (not source) for a Cineby ExtractorLink.
         * The `source` field is set to `serverSourceLabel` (e.g.,
         * "Wizstream • Cineby · Neon") which groups all links from the
         * same server together. The `name` field only contains quality +
         * audio info, so the picker shows a clean hierarchy:
         *
         *   Wizstream • Cineby · Neon
         *     ├── 1080p · Original audio
         *     └── 720p · Original audio
         */

        // ── Codec probing (v18) ─────────────────────────────────────────────
        // Some Cineby mirrors serve HEVC / AV1 / 10-bit encodes. Many smart
        // TVs have no hardware decoder for those, so ExoPlayer dies with
        // error 4003 (DECODING_FAILED) while phones happily software-decode.
        // The API response doesn't say which codec a source uses, so we
        // fetch the HLS master / DASH MPD (one small request — it replaces
        // the fetch M3u8Helper would do anyway) and probe MP4 headers, then
        // tag every emitted link: "· H.264" is TV-safe, "· HEVC ⚠" / "· AV1 ⚠"
        // tells the user to pick another server/quality on TV.

        private fun codecTagFromCodecs(codecs: String?): String {
            if (codecs.isNullOrBlank()) return ""
            val c = codecs.lowercase()
            val v = when {
                c.contains("hvc1") || c.contains("hev1") || c.contains("dvh1") -> " · HEVC ⚠"
                c.contains("av01") -> " · AV1 ⚠"
                c.contains("vp09") || c.contains("vp9") -> " · VP9 ⚠"
                c.contains("avc1") || c.contains("avc3") -> " · H.264"
                else -> ""
            }
            val a = when {
                c.contains("ec-3") || c.contains("ec3") -> " · EAC3"
                c.contains("ac-3") -> " · AC3"
                else -> ""
            }
            return v + a
        }

        private data class HlsVariant(val url: String, val height: Int, val codecTag: String)

        /** Fetch an HLS master playlist and parse its variant streams. Empty
         *  list returned → not a master playlist (or unreachable). */
        private suspend fun parseHlsMaster(app: Requests, masterUrl: String): List<HlsVariant> {
            val text = runCatching {
                app.get(masterUrl, headers = API_HEADERS, cacheTime = 0, timeout = 12_000).text
            }.getOrNull() ?: return emptyList()
            if (!text.startsWith("#EXTM3U") || !text.contains("#EXT-X-STREAM-INF")) {
                return emptyList()
            }
            val out = mutableListOf<HlsVariant>()
            val lines = text.lines().map { it.trim() }
            var i = 0
            while (i < lines.size) {
                val l = lines[i]
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val codecs = Regex("CODECS=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
                    val height = Regex("RESOLUTION=\\d+x(\\d+)").find(l)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val uri = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") }
                    if (uri != null) {
                        out += HlsVariant(resolveAbs(masterUrl, uri), height, codecTagFromCodecs(codecs))
                    }
                }
                i++
            }
            return out
        }

        /** Probe the first bytes of a progressive MP4 for its ftyp brands /
         *  sample-entry fourccs to guess the video codec. */
        private suspend fun probeMp4Codec(app: Requests, url: String): String {
            val resp = runCatching {
                app.get(
                    url,
                    headers = API_HEADERS + ("Range" to "bytes=0-65535"),
                    cacheTime = 0,
                    timeout = 10_000,
                )
            }.getOrNull() ?: return ""
            if (resp.code !in 200..299 && resp.code != 206) return ""
            val body = resp.text
            val rx = Regex("hvc1|hev1|dvh1|av01|vp09|avc1|avc3")
            val found = rx.find(body)?.value ?: return ""
            return codecTagFromCodecs(found)
        }

        /** Emit one HLS/DASH/progressive link with codec tagging. */
        private suspend fun emitTaggedMedia(
            app: Requests,
            safeUrl: String,
            serverSourceLabel: String,
            name: String,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            when {
                safeUrl.contains(".m3u8", true) -> {
                    val variants = parseHlsMaster(app, safeUrl)
                    if (variants.isEmpty()) {
                        // Media playlist (no variants) or unreachable — emit as-is.
                        callback(
                            newExtractorLink(
                                source = serverSourceLabel,
                                name = name,
                                url = safeUrl,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = "$SITE/"
                                this.quality = qualityFromName(safeUrl)
                                this.headers = API_HEADERS
                            }
                        )
                        return true
                    }
                    variants.distinctBy { Pair(it.url, it.codecTag) }.forEach { v ->
                        callback(
                            newExtractorLink(
                                source = serverSourceLabel,
                                name = name + v.codecTag,
                                url = v.url,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = "$SITE/"
                                this.quality = if (v.height > 0) v.height else qualityFromName(safeUrl)
                                this.headers = API_HEADERS
                            }
                        )
                    }
                    return true
                }
                safeUrl.contains(".mpd", true) -> {
                    // Fetch the MPD text once to extract codec info.
                    val mpd = runCatching {
                        app.get(safeUrl, headers = API_HEADERS, cacheTime = 0, timeout = 12_000).text
                    }.getOrNull().orEmpty()
                    val videoCodecs = Regex("codecs=\"([^\"]+)\"").findAll(mpd)
                        .map { it.groupValues[1] }
                        .firstOrNull { codecTagFromCodecs(it).isNotBlank() }
                    val tag = codecTagFromCodecs(videoCodecs)
                    callback(
                        newExtractorLink(
                            source = serverSourceLabel,
                            name = name + tag,
                            url = safeUrl,
                            type = ExtractorLinkType.DASH,
                        ) {
                            this.referer = "$SITE/"
                            this.quality = qualityFromName(safeUrl)
                            this.headers = API_HEADERS
                        }
                    )
                    return true
                }
                safeUrl.contains(".mp4", true) || safeUrl.contains(".mkv", true) ||
                    safeUrl.contains(".webm", true) -> {
                    val tag = if (safeUrl.contains(".mp4", true)) probeMp4Codec(app, safeUrl) else ""
                    callback(
                        newExtractorLink(
                            source = serverSourceLabel,
                            name = name + tag,
                            url = safeUrl,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = "$SITE/"
                            this.quality = qualityFromName(safeUrl)
                            this.headers = API_HEADERS
                        }
                    )
                    return true
                }
            }
            return false
        }

        private fun buildLabel(server: CinebyServer, quality: String, srcLabel: String): String {
            val parts = mutableListOf<String>()
            // Skip quality if it's just the language name (avoid duplicate
            // with the audioLabel below).
            val isLangQuality = quality.equals(server.audioLabel, ignoreCase = true) ||
                (server.qualityFilter != null && quality.equals(server.qualityFilter, ignoreCase = true))
            if (!isLangQuality && quality.isNotBlank() && quality != "Auto") {
                parts += quality
            }
            // If quality is "Auto" or a language name, show "Auto" as fallback
            // so the user sees something useful.
            if (parts.isEmpty() && (quality == "Auto" || isLangQuality)) {
                parts += "Auto"
            }
            server.audioLabel?.let { parts += "$it audio" }
            // If we have no parts at all, return a minimal label.
            if (parts.isEmpty()) parts += "Stream"
            return parts.joinToString(" · ")
        }

        // ── TMDB helpers (use the keyless proxy first, fall back to TMDB direct) ──

        private suspend fun fetchYear(app: Requests, tmdbId: Int, mediaType: String): String? {
            // Try the keyless proxy first.
            val proxyUrl = "$TMDB_PROXY/$mediaType/$tmdbId?language=en-US"
            val proxyResp = runCatching {
                app.get(proxyUrl, headers = API_HEADERS, cacheTime = 0, timeout = 8_000)
            }.getOrNull()
            if (proxyResp != null && proxyResp.code in 200..299 && proxyResp.text.isNotBlank()) {
                val json = runCatching { JSONObject(proxyResp.text) }.getOrNull()
                if (json != null) {
                    val dateStr = if (mediaType == "movie") {
                        json.optStringOrNullCp("release_date")
                    } else {
                        json.optStringOrNullCp("first_air_date")
                    }
                    dateStr?.substringBefore("-")?.takeIf { it.length == 4 }?.let { return it }
                }
            }
            // Fall back to TMDB direct with API key.
            val directUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbId?api_key=$TMDB_KEY&language=en-US"
            val directResp = runCatching {
                app.get(directUrl, headers = API_HEADERS, cacheTime = 0, timeout = 8_000)
            }.getOrNull() ?: return null
            if (directResp.code !in 200..299 || directResp.text.isBlank()) return null
            val json = runCatching { JSONObject(directResp.text) }.getOrNull() ?: return null
            val dateStr = if (mediaType == "movie") {
                json.optStringOrNullCp("release_date")
            } else {
                json.optStringOrNullCp("first_air_date")
            }
            return dateStr?.substringBefore("-")?.takeIf { it.length == 4 }
        }

        private suspend fun fetchImdbId(app: Requests, tmdbId: Int, mediaType: String): String? {
            // Try the keyless proxy with append_to_response=external_ids.
            val proxyUrl = "$TMDB_PROXY/$mediaType/$tmdbId?append_to_response=external_ids&language=en-US"
            val proxyResp = runCatching {
                app.get(proxyUrl, headers = API_HEADERS, cacheTime = 0, timeout = 8_000)
            }.getOrNull()
            if (proxyResp != null && proxyResp.code in 200..299 && proxyResp.text.isNotBlank()) {
                val json = runCatching { JSONObject(proxyResp.text) }.getOrNull()
                json?.optJSONObject("external_ids")?.optStringOrNullCp("imdb_id")?.let { return it }
            }
            // Fall back to TMDB direct.
            val directUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbId" +
                "?api_key=$TMDB_KEY&append_to_response=external_ids&language=en-US"
            val directResp = runCatching {
                app.get(directUrl, headers = API_HEADERS, cacheTime = 0, timeout = 8_000)
            }.getOrNull() ?: return null
            if (directResp.code !in 200..299 || directResp.text.isBlank()) return null
            val json = runCatching { JSONObject(directResp.text) }.getOrNull() ?: return null
            return json.optJSONObject("external_ids")?.optStringOrNullCp("imdb_id")
        }

        // ── Percent-encoding helpers (must match the Aniyomi implementation) ──

        private const val HEX = "0123456789ABCDEF"

        private fun pctEncode(s: String): String {
            val bytes = s.toByteArray(Charsets.UTF_8)
            val out = StringBuilder(bytes.size * 3)
            for (raw in bytes) {
                val c = raw.toInt() and 0xFF
                val unreserved = (c in 0x30..0x39) || (c in 0x41..0x5A) || (c in 0x61..0x7A) ||
                    c == 0x2D || c == 0x2E || c == 0x5F || c == 0x7E
                if (unreserved) {
                    out.append(c.toChar())
                } else {
                    out.append('%')
                    out.append(HEX[(c ushr 4) and 0x0F])
                    out.append(HEX[c and 0x0F])
                }
            }
            return out.toString()
        }

        private fun doubleEncode(s: String): String = pctEncode(pctEncode(s))
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 6: Bingr  (https://bingr.one)
    //
    //  Recon (2026-07-22): Vite SPA backed by one anonymous JSON API:
    //    POST https://api.bingr.one/api/stream
    //    body  {"srv": <serverId>, "t": "movie"|"tv", "id": <tmdbId>,
    //           "query": {"title":…, "year":…, "season":…, "episode":…}}
    //    resp  {"scraperName": "Sirius",
    //           "sources":   [{"url","quality","language","type","label"}],
    //           "subtitles": []}
    //  Seven active scraper servers (three more exist but are flagged
    //  `comingSoon` server-side and return nothing):
    //    s11 Sirius · s10 Elysium · s1 Miller · s2 Mann · s3 Edmunds ·
    //    s4 Luna · s5 Aditya
    //  Returned media URLs are already proxied (filmu.in / workers.dev) and
    //  embed whatever referer the origin host needs — dead variants are
  //  common, so every link is verified with a probe before it is emitted.
    // ════════════════════════════════════════════════════════════════════════

    internal object BingrResolver : SourceResolver {
        private const val SITE = "https://bingr.one"
        private const val API = "https://api.bingr.one/api"
        private const val LABEL = "Bingr"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Origin" to SITE,
            "Accept" to "application/json, text/plain, */*",
        )

        private val SERVERS = listOf(
            "s11" to "Sirius",
            "s10" to "Elysium",
            "s1" to "Miller",
            "s2" to "Mann",
            "s3" to "Edmunds",
            "s4" to "Luna",
            "s5" to "Aditya",
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
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            if (tmdbId == null) return false
            val srcLabel = "$labelPrefix • $LABEL"
            val mediaType = if (isMovie) "movie" else "tv"

            val query = JSONObject().apply {
                put("title", title)
                year?.let { put("year", it) }
                if (!isMovie) {
                    put("season", season ?: 1)
                    put("episode", episode ?: 1)
                }
            }

            var any = false
            val gate = Semaphore(3)
            coroutineScope {
                SERVERS.map { (srvId, srvName) ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            runCatching {
                                resolveOneServer(
                                    app, srvId, srvName, mediaType, tmdbId,
                                    query, srcLabel, subtitleCallback, callback,
                                )
                            }.getOrDefault(false)
                        }
                    }
                }.awaitAll().forEach { if (it) any = true }
            }
            return any
        }

        private suspend fun resolveOneServer(
            app: Requests,
            srvId: String,
            srvName: String,
            mediaType: String,
            tmdbId: Int,
            query: JSONObject,
            srcLabel: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val body = JSONObject().apply {
                put("srv", srvId)
                put("t", mediaType)
                put("id", tmdbId.toString())
                put("query", query)
            }.toString()
            val resp = runCatching {
                app.post(
                    "$API/stream",
                    headers = HEADERS + ("Content-Type" to "application/json"),
                    requestBody = body.toRequestBody("application/json".toMediaTypeOrNull()),
                    cacheTime = 0,
                    timeout = 20_000,
                )
            }.getOrNull() ?: return false
            if (resp.code !in 200..299 || resp.text.isBlank()) return false
            val json = runCatching { JSONObject(resp.text) }.getOrNull() ?: return false

            val serverSourceLabel = "$srcLabel · $srvName"
            var any = false

            // Subtitles ride the top-level array.
            val subs = json.optJSONArray("subtitles")
            if (subs != null) {
                for (i in 0 until subs.length()) {
                    val s = subs.optJSONObject(i) ?: continue
                    val subUrl = s.optStringOrNullCp("url") ?: s.optStringOrNullCp("file")
                        ?: s.optStringOrNullCp("src") ?: continue
                    if (!subUrl.startsWith("http")) continue
                    val rawLabel = s.optStringOrNullCp("label") ?: s.optStringOrNullCp("language")
                        ?: s.optStringOrNullCp("lang") ?: "Subtitle"
                    subtitleCallback(SubtitleFile("[$srvName] $rawLabel", subUrl))
                }
            }

            val sources = json.optJSONArray("sources") ?: return any
            for (i in 0 until sources.length()) {
                val s = sources.optJSONObject(i) ?: continue
                val url = s.optStringOrNullCp("url") ?: continue
                if (!url.startsWith("http")) continue
                val quality = s.optStringOrNullCp("quality") ?: "Auto"
                val language = s.optStringOrNullCp("language")
                val langTag = when {
                    language.isNullOrBlank() || language.equals("Original", true) -> ""
                    language.equals("English", true) -> " · English"
                    else -> " · $language"
                }
                val type = s.optStringOrNullCp("type") ?: ""
                val isHls = url.contains(".m3u8", true) ||
                    type.contains("mpegurl", true) || type.contains("m3u8", true)
                val isMp4 = !isHls && (url.contains(".mp4", true) ||
                    type.contains("mp4", true))

                // Probe before emitting — Bingr scrapers return dead variants
                // often; a 403/404/5xx link must never reach the player.
                when {
                    isHls -> {
                        val masterText = runCatching {
                            app.get(url, headers = HEADERS + ("Range" to "bytes=0-16384"),
                                cacheTime = 0, timeout = 12_000)
                        }.getOrNull()
                        if (masterText == null || masterText.code !in 200..299 && masterText.code != 206) continue
                        val text = masterText.text
                        if (!text.contains("#EXTM3U")) continue
                        val codecTag = codecTagFromCodecs(
                            Regex("""CODECS=\"([^\"]+)\"""").find(text)?.groupValues?.get(1)
                        )
                        callback(
                            newExtractorLink(
                                source = serverSourceLabel,
                                name = "$quality$langTag$codecTag",
                                url = url,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = "$SITE/"
                                this.quality = qualityFromName(quality)
                                this.headers = HEADERS
                            }
                        )
                        any = true
                    }
                    isMp4 -> {
                        val probe = runCatching {
                            app.get(url, headers = HEADERS + ("Range" to "bytes=0-511"),
                                cacheTime = 0, timeout = 12_000)
                        }.getOrNull()
                        if (probe == null || probe.code !in 200..299 && probe.code != 206) continue
                        callback(
                            newExtractorLink(
                                source = serverSourceLabel,
                                name = "$quality$langTag",
                                url = url,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = "$SITE/"
                                this.quality = qualityFromName(quality)
                                this.headers = HEADERS
                            }
                        )
                        any = true
                    }
                    else -> continue // unknown container — skip (prevents 3003)
                }
            }
            return any
        }

        private fun codecTagFromCodecs(codecs: String?): String {
            if (codecs.isNullOrBlank()) return ""
            val c = codecs.lowercase()
            return when {
                c.contains("hvc1") || c.contains("hev1") || c.contains("dvh1") -> " · HEVC ⚠"
                c.contains("av01") -> " · AV1 ⚠"
                c.contains("vp09") || c.contains("vp9") -> " · VP9 ⚠"
                c.contains("avc1") || c.contains("avc3") -> " · H.264"
                else -> ""
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 7: Moonflix  (https://moonflix.website)
    //
    //  Recon (2026-07-22): Lovable/Vite SPA. Playback happens on
    //  player.moonflix.website, which queries three anonymous Railway
    //  backends (all TMDB-keyed, no auth, no cookies):
    //    CH = https://confident-harmony-production-0578.up.railway.app
    //         /movie/{id}  ·  /tv/{id}/{season}/{episode}
    //         → {streams: [{language, available, qualities:
    //                       [{resolution, codec, raw_url}]}], subtitles: []}
    //         Multi-audio direct mp4 ladder (English/Hindi/Original/…).
    //         ALSO: /subtitles/movie/{id} · /subtitles/tv/{id}/{s}/{e}
    //         (a bare JSON array or {"subtitles": [...]} — handle both).
    //    HV = https://hvhyu-production.up.railway.app  (same paths)
    //         → {streams: [{quality, url(.m3u8), type: "hls"}]}
    //    SE = https://series-production-5c1c.up.railway.app   (TV only)
    //         → {sources: [{name: "VIP 1"/"LUL 2"/…, url, proxy_url}]}
    //  Live-verified limitations: CH's CDN (hakunaymatata) reverse-proxies
    //  behind a cache layer that denies datacenter IPs and rate-limits hard,
    //  so we canary-probe it ONCE with the site's own player referer and
  //  emit the whole ladder only when the user's network can actually eat it.
    // ════════════════════════════════════════════════════════════════════════

    internal object MoonflixResolver : SourceResolver {
        private const val SITE = "https://moonflix.website"
        private const val PLAYER = "https://player.moonflix.website"
        private const val LABEL = "Moonflix"
        private const val API_CH = "https://confident-harmony-production-0578.up.railway.app"
        private const val API_HV = "https://hvhyu-production.up.railway.app"
        private const val API_SE = "https://series-production-5c1c.up.railway.app"

        private val HEADERS = mapOf("User-Agent" to UA)
        private val PLAYER_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$PLAYER/",
            "Origin" to PLAYER,
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
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            if (tmdbId == null) return false
            val srcLabel = "$labelPrefix • $LABEL"
            val sNum = if (isMovie) 1 else (season ?: 1)
            val eNum = if (isMovie) 1 else (episode ?: 1)
            val mediaPath = if (isMovie) "movie/$tmdbId" else "tv/$tmdbId/$sNum/$eNum"

            var any = false
            coroutineScope {
                val chJob = async(Dispatchers.IO) {
                    runCatching {
                        fetchCh(app, mediaPath, srcLabel, callback)
                    }.getOrDefault(false)
                }
                val hvJob = async(Dispatchers.IO) {
                    runCatching {
                        fetchHv(app, mediaPath, srcLabel, callback)
                    }.getOrDefault(false)
                }
                val seJob = async(Dispatchers.IO) {
                    if (isMovie) return@async false
                    runCatching {
                        fetchSe(app, tmdbId, sNum, eNum, srcLabel, callback)
                    }.getOrDefault(false)
                }
                val subJob = async(Dispatchers.IO) {
                    runCatching { fetchSubs(app, mediaPath, subtitleCallback) }
                }
                listOf(chJob, hvJob, seJob).awaitAll().forEach { if (it) any = true }
                subJob.await()
            }
            return any
        }

        // ── CH backend: multi-language direct mp4 ladder ────────────────────

        private suspend fun fetchCh(
            app: Requests,
            mediaPath: String,
            srcLabel: String,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val json = getJson(app, "$API_CH/$mediaPath") ?: return false
            val streams = json.optJSONArray("streams") ?: return false
            if (streams.length() == 0) return false

            data class ChLink(val lang: String, val resolution: String, val codec: String, val url: String)
            val links = mutableListOf<ChLink>()
            for (i in 0 until streams.length()) {
                val st = streams.optJSONObject(i) ?: continue
                if (!st.optBoolean("available", false)) continue
                val lang = st.optStringOrNullCp("language") ?: continue
                val qualities = st.optJSONArray("qualities") ?: continue
                for (q in 0 until qualities.length()) {
                    val qual = qualities.optJSONObject(q) ?: continue
                    val raw = qual.optStringOrNullCp("raw_url") ?: continue
                    if (!raw.startsWith("http")) continue
                    links += ChLink(
                        lang = lang,
                        resolution = qual.optStringOrNullCp("resolution") ?: "Auto",
                        codec = qual.optStringOrNullCp("codec") ?: "",
                        url = raw,
                    )
                }
            }
            if (links.isEmpty()) return false

            // Canary probe — CH's CDN (squid "web cache") hard-denies
            // datacenter IPs with 403 and rate-limits aggressively, so we
            // must NOT fire one probe per link. One probe on the first link:
            // reachable → the user's network is good, emit the whole ladder;
            // denied    → drop the group entirely (clean absence, no 2004s).
            val canary = runCatching {
                app.get(
                    links.first().url,
                    headers = PLAYER_HEADERS + ("Range" to "bytes=0-511"),
                    cacheTime = 0, timeout = 12_000,
                )
            }.getOrNull() ?: return false
            if (canary.code !in 200..299 && canary.code != 206) return false

            var any = false
            for (l in links) {
                val pretty = l.lang.replaceFirstChar { it.uppercase() }
                val codecTag = chCodecTag(l.codec)
                callback(
                    newExtractorLink(
                        source = "$srcLabel · $pretty",
                        name = "${l.resolution}$codecTag",
                        url = l.url,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = "$PLAYER/"
                        this.quality = qualityFromName(l.resolution)
                        this.headers = PLAYER_HEADERS
                    }
                )
                any = true
            }
            return any
        }

        private fun chCodecTag(codec: String): String {
            val c = codec.lowercase()
            return when {
                c.contains("265") || c.contains("hevc") || c.contains("hvc1") -> " · HEVC ⚠"
                c.contains("av1") -> " · AV1 ⚠"
                c.contains("vp9") -> " · VP9 ⚠"
                c.contains("264") || c.contains("avc") -> " · H.264"
                else -> ""
            }
        }

        // ── HV backend: per-quality HLS ──────────────────────────────────────

        private suspend fun fetchHv(
            app: Requests,
            mediaPath: String,
            srcLabel: String,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val json = getJson(app, "$API_HV/$mediaPath") ?: return false
            val streams = json.optJSONArray("streams") ?: return false
            var any = false
            val serverSourceLabel = "$srcLabel · HDGhar"
            for (i in 0 until streams.length()) {
                val st = streams.optJSONObject(i) ?: continue
                val url = st.optStringOrNullCp("url") ?: continue
                if (!url.startsWith("http")) continue
                val quality = st.optStringOrNullCp("quality") ?: "Auto"
                if (url.contains(".m3u8", true)) {
                    // Probe the master (single GET doubles as the codec probe).
                    val master = runCatching {
                        app.get(url, headers = HEADERS, cacheTime = 0, timeout = 12_000)
                    }.getOrNull()
                    if (master == null || master.code !in 200..299 && master.code != 206 ||
                        !master.text.contains("#EXTM3U")
                    ) continue
                    val codecTag = chCodecTagFromCodecs(
                        Regex("""CODECS=\"([^\"]+)\"""").find(master.text)?.groupValues?.get(1)
                    )
                    callback(
                        newExtractorLink(
                            source = serverSourceLabel,
                            name = "$quality$codecTag",
                            url = url,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.referer = "$PLAYER/"
                            this.quality = qualityFromName(quality)
                            this.headers = HEADERS
                        }
                    )
                    any = true
                } else {
                    callback(
                        newExtractorLink(
                            source = serverSourceLabel,
                            name = quality,
                            url = url,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = "$PLAYER/"
                            this.quality = qualityFromName(quality)
                            this.headers = HEADERS
                        }
                    )
                    any = true
                }
            }
            return any
        }

        private fun chCodecTagFromCodecs(codecs: String?): String {
            if (codecs.isNullOrBlank()) return ""
            val c = codecs.lowercase()
            return when {
                c.contains("hvc1") || c.contains("hev1") || c.contains("dvh1") -> " · HEVC ⚠"
                c.contains("av01") -> " · AV1 ⚠"
                c.contains("vp09") || c.contains("vp9") -> " · VP9 ⚠"
                c.contains("avc1") || c.contains("avc3") -> " · H.264"
                else -> ""
            }
        }

        // ── SE backend: named VIP/LUL HLS servers (TV only) ─────────────────

        private suspend fun fetchSe(
            app: Requests,
            tmdbId: Int,
            seasonNum: Int,
            episodeNum: Int,
            srcLabel: String,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val json = getJson(app, "$API_SE/tv/$tmdbId/$seasonNum/$episodeNum") ?: return false
            val sources = json.optJSONArray("sources") ?: return false
            var any = false
            for (i in 0 until sources.length()) {
                val st = sources.optJSONObject(i) ?: continue
                val name = st.optStringOrNullCp("name") ?: "Server"
                // The site itself prefers the proxied URL (bypasses origin
                // referer checks); raw url is the fallback.
                val url = st.optStringOrNullCp("proxy_url")
                    ?: st.optStringOrNullCp("url") ?: continue
                if (!url.startsWith("http")) continue
                if (!url.contains(".m3u8", true) && !url.contains("proxy?url=", true)) continue
                val probe = runCatching {
                    app.get(url, headers = HEADERS + ("Range" to "bytes=0-8192"),
                        cacheTime = 0, timeout = 12_000)
                }.getOrNull()
                if (probe == null || probe.code !in 200..299 && probe.code != 206) continue
                if (probe.text.isNotBlank() && !probe.text.startsWith("#EXTM3U") &&
                    !probe.text.contains("mpegurl", true) && probe.text.length < 40
                ) continue
                callback(
                    newExtractorLink(
                        source = "$srcLabel · $name",
                        name = "Auto · HLS",
                        url = url,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = "$PLAYER/"
                        this.quality = qualityFromName(url)
                        this.headers = HEADERS
                    }
                )
                any = true
            }
            return any
        }

        // ── Subtitles (CH) ──────────────────────────────────────────────────

        private suspend fun fetchSubs(
            app: Requests,
            mediaPath: String,
            subtitleCallback: (SubtitleFile) -> Unit,
        ) {
            val resp = runCatching {
                app.get("$API_CH/subtitles/$mediaPath", headers = HEADERS,
                    cacheTime = 0, timeout = 15_000)
            }.getOrNull() ?: return
            if (resp.code !in 200..299 || resp.text.isBlank()) return
            val trimmed = resp.text.trimStart()
            // Shape is either a bare array or {"subtitles": [...]}.
            val arr = when {
                trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
                trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrNull()
                    ?.optJSONArray("subtitles")
                else -> null
            } ?: return
            var emitted = 0
            for (i in 0 until arr.length()) {
                if (emitted >= 12) break
                val s = arr.optJSONObject(i) ?: continue
                val url = s.optStringOrNullCp("url") ?: s.optStringOrNullCp("file")
                    ?: s.optStringOrNullCp("src") ?: continue
                if (!url.startsWith("http")) continue
                val lang = s.optStringOrNullCp("lang") ?: s.optStringOrNullCp("language")
                    ?: s.optStringOrNullCp("label") ?: "Unknown"
                subtitleCallback(SubtitleFile("[Moonflix] ${lang.uppercase()}", url))
                emitted++
            }
        }

        private suspend fun getJson(app: Requests, url: String): JSONObject? {
            val resp = runCatching {
                app.get(url, headers = HEADERS, cacheTime = 0, timeout = 20_000)
            }.getOrNull() ?: return null
            if (resp.code !in 200..299 || resp.text.isBlank()) return null
            return runCatching { JSONObject(resp.text) }.getOrNull()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared JSON helpers
// ─────────────────────────────────────────────────────────────────────────────

internal fun JSONObject.optStringOrNullCp(k: String): String? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").trim().takeIf { it.isNotBlank() && it != "null" }
