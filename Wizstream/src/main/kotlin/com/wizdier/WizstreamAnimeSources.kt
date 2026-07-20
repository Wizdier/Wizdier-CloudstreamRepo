package com.wizdier

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream

/**
 * WizstreamAnimeSources — bundled anime-specific source resolver.
 *
 * Adds 7 anime-focused streaming sources on top of the BDIX resolvers in
 * WizstreamSources:
 *
 *   1. AniZone     — https://anizone.to              (direct .m3u8 in <media-player>)
 *   2. Mkissa      — https://mkissa.to               (AllAnime API + AA-CRYPTO bypass)
 *   3. Miruro      — https://www.miruro.to           (secure-pipe API + AniList)
 *   4. AniChi      — https://anichi.to               (mapper.nekostream.site API)
 *   5. UniqueStream— https://anime.uniquestream.net  (open FastAPI, signed HLS)
 *   6. AniNeko     — https://anineko.to             (server-video embeds → direct HLS)
 *   7. ReANIME     — https://reanime.to             (SvelteKit __data.json scan)
 *
 * All are invoked in parallel from `resolveAnime()`. Each returns
 * `true` on the first playable link it emits; the aggregator returns true
 * if ANY source produced a link.
 *
 * The resolvers accept both `anilistId` and `malId` so they can short-circuit
 * the search step when the calling provider already has them — that's the
 * fast path used by WizstreamAnimeProvider.
 */
object WizstreamAnimeSources {

    private const val TAG = "WizstreamAnimeSources"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    suspend fun resolveAnime(
        app: Requests,
        title: String,
        anilistId: Int?,
        malId: Int?,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        labelPrefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        if (title.isBlank() && anilistId == null && malId == null) {
            return@coroutineScope false
        }

        val sources = listOf(
            AniZoneResolver,
            MkissaResolver,
            MiruroResolver,
            AniChiResolver,
            UniqueStreamResolver,
            AniNekoResolver,
            ReAnimeResolver,
        )

        val gate = Semaphore(4)
        val jobs = sources.map { src ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        src.resolve(
                            app = app,
                            title = title,
                            anilistId = anilistId,
                            malId = malId,
                            isMovie = isMovie,
                            season = season,
                            episode = episode,
                            labelPrefix = labelPrefix,
                            subtitleCallback = subtitleCallback,
                            callback = callback,
                        )
                    }.onFailure {
                        Log.d(TAG, "${src::class.simpleName} failed: ${it.message}")
                    }.getOrDefault(false)
                }
            }
        }
        jobs.awaitAll().any { it }
    }

    internal interface AnimeSourceResolver {
        suspend fun resolve(
            app: Requests,
            title: String,
            anilistId: Int?,
            malId: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean
    }

    // ───────────────────────────────────────────────────────────────────────
    //  Shared helpers
    // ───────────────────────────────────────────────────────────────────────

    internal fun encodeUrl(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    internal fun urlSafeB64Encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    internal fun urlSafeB64Decode(s: String): ByteArray {
        val padded = s.padEnd((s.length + 3) / 4 * 4, '=')
        return Base64.getUrlDecoder().decode(padded)
    }

    internal fun urlSafeB64DecodeText(s: String): String? = runCatching {
        String(urlSafeB64Decode(s))
    }.getOrNull()

    /** Decode the Miruro pipe response: URL-safe base64 → GZIP → JSON. */
    internal fun decodePipeResponse(s: String): JSONObject? = runCatching {
        val compressed = urlSafeB64Decode(s.trim())
        GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader().use { it.readText() }
    }.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Encode JSON payload for Miruro pipe. */
    internal fun encodePipeRequest(payload: JSONObject): String =
        urlSafeB64Encode(payload.toString().toByteArray())

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

    internal fun qualityFromLabel(s: String?): Int {
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

    /** Normalised title for fuzzy matching. */
    internal fun String.normaliseTitle(): String =
        lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    /** Jaccard token-overlap similarity 0..1. */
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

    internal suspend fun emitHlsOrMp4(
        url: String,
        sourceLabel: String,
        referer: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val u = url.trim()
        if (u.isBlank()) return false
        return try {
            if (u.contains(".m3u8", ignoreCase = true)) {
                M3u8Helper.generateM3u8(
                    source = sourceLabel,
                    streamUrl = u,
                    referer = referer,
                    headers = headers,
                ).forEach(callback)
                true
            } else if (u.contains(".mp4", ignoreCase = true) ||
                u.contains(".mkv", ignoreCase = true) ||
                u.contains(".webm", ignoreCase = true)
            ) {
                callback(
                    newExtractorLink(
                        source = sourceLabel,
                        name = "$sourceLabel - Direct",
                        url = u,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = referer
                        this.quality = qualityFromLabel(u)
                    }
                )
                true
            } else if (u.contains(".mpd", ignoreCase = true)) {
                callback(
                    newExtractorLink(
                        source = sourceLabel,
                        name = "$sourceLabel - DASH",
                        url = u,
                        type = ExtractorLinkType.DASH,
                    ) {
                        this.referer = referer
                        this.quality = qualityFromLabel(u)
                    }
                )
                true
            } else {
                var found = false
                runCatching {
                    loadExtractor(u, referer, subtitleCallback) { link ->
                        callback(link.relabel(sourceLabel, "$sourceLabel — ${link.name}"))
                        found = true
                    }
                }
                found
            }
        } catch (t: Throwable) {
            Log.d(TAG, "emitHlsOrMp4 failed for $u: ${t.message}")
            false
        }
    }


    // ───────────────────────────────────────────────────────────────────────
    //  Link-quality gate (v19)
    //
    //  Every DIRECT media URL we are about to hand to the player gets a
    //  quick range-probe first:
    //    • HTTP 200-299 whose body does NOT look like HTML  → keep
    //    • HTTP 4xx/5xx, or an HTML error page              → DROP
    //      (these are exactly the links that produce player
    //      HTTP 2004 "bad response code" / 3003 "unparseable" errors)
    //    • timeouts, TLS issues, odd status codes           → keep
    //      (inconclusive — the user's network may reach it fine)
    //
    //  Candidates are probed in parallel (3 at a time) so the check adds
    //  roughly one extra second instead of serialising per link.
    // ───────────────────────────────────────────────────────────────────────

    internal data class MediaCandidate(
        val url: String,
        val sourceLabel: String,
        val name: String,
        val referer: String,
        val headers: Map<String, String> = emptyMap(),
        val forceHls: Boolean = false,
        val quality: Int = Qualities.Unknown.value,
    )

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** true = looks playable · false = definitely dead · null = inconclusive. */
    internal fun probePlayable(
        url: String,
        referer: String,
        headers: Map<String, String>,
    ): Boolean? {
        return try {
            val rb = Request.Builder().url(url)
                .header("Range", "bytes=0-511")
                .header("User-Agent", UA)
                .header("Referer", referer)
            headers.forEach { (k, v) -> rb.header(k, v) }
            probeClient.newCall(rb.build()).execute().use { resp ->
                when (resp.code) {
                    in 200..299 -> {
                        val sniff = ByteArray(512)
                        val n = try {
                            resp.body?.byteStream()?.read(sniff) ?: -1
                        } catch (t: Throwable) { -1 }
                        if (n <= 0) {
                            null
                        } else {
                            val head = String(sniff, 0, n, Charsets.UTF_8)
                                .trimStart().lowercase()
                            // An HTML page handed to ExoPlayer is a guaranteed
                            // 3003 parse error — drop it right here.
                            if (head.startsWith("<!doctype") || head.startsWith("<html") ||
                                (head.startsWith("{") && "\"error\"" in head)
                            ) false else true
                        }
                    }
                    400, 401, 402, 403, 404, 405, 410, 451,
                    500, 501, 502, 503, 504 -> false
                    else -> null
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Probe (drop only definitively dead links), then emit everything left. */
    internal suspend fun emitMediaCandidates(
        candidates: List<MediaCandidate>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        val uniq = candidates.distinctBy { it.url }
        if (uniq.isEmpty()) return@coroutineScope false
        val gate = Semaphore(3)
        val checked = uniq.map { c ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    c to withTimeoutOrNull(8_000) { probePlayable(c.url, c.referer, c.headers) }
                }
            }
        }.awaitAll()
        var any = false
        for ((c, verdict) in checked) {
            if (verdict == false) {
                Log.d(TAG, "dropping dead link ${c.name}: ${c.url.take(80)}")
                continue
            }
            if (emitOneCandidate(c, callback)) any = true
        }
        any
    }

    private suspend fun emitOneCandidate(
        c: MediaCandidate,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val isFile = Regex("""\.(mp4|mkv|webm)([?#]|$)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(c.url)
        val isDash = c.url.contains(".mpd", ignoreCase = true)
        val isHls = c.forceHls || c.url.contains(".m3u8", ignoreCase = true)
        return try {
            when {
                isHls && !isFile -> {
                    val variants = runCatching {
                        M3u8Helper.generateM3u8(
                            source = c.sourceLabel,
                            streamUrl = c.url,
                            referer = c.referer,
                            headers = c.headers,
                        )
                    }.getOrNull()
                    if (!variants.isNullOrEmpty()) {
                        variants.forEach { l ->
                            val q = if (l.quality > 0) " · ${l.quality}p" else ""
                            callback(l.relabel(c.sourceLabel, c.name + q))
                        }
                    } else {
                        callback(
                            newExtractorLink(
                                source = c.sourceLabel,
                                name = c.name,
                                url = c.url,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = c.referer
                                this.quality = c.quality
                            }
                        )
                    }
                    true
                }
                isDash -> {
                    callback(
                        newExtractorLink(
                            source = c.sourceLabel,
                            name = c.name,
                            url = c.url,
                            type = ExtractorLinkType.DASH,
                        ) {
                            this.referer = c.referer
                            this.quality = c.quality
                        }
                    )
                    true
                }
                isFile -> {
                    callback(
                        newExtractorLink(
                            source = c.sourceLabel,
                            name = c.name,
                            url = c.url,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = c.referer
                            this.quality =
                                if (c.quality > 0) c.quality else qualityFromLabel(c.url)
                        }
                    )
                    true
                }
                else -> false
            }
        } catch (t: Throwable) {
            Log.d(TAG, "emitOneCandidate failed for ${c.url.take(80)}: ${t.message}")
            false
        }
    }

    /**
     * Dean-Edwards JS unpacker — `eval(function(p,a,c,k,e,d){...}('…',N,N,'k|e|y|s'))`.
     * OtakuHG / OtakuVid embeds hide their .m3u8 inside one of these.
     */
    internal fun unpackPackedJs(html: String): String? {
        val m = Regex("""\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        val p = m.groupValues[1]
        val a = m.groupValues[2].toIntOrNull() ?: return null
        val c = m.groupValues[3].toIntOrNull() ?: return null
        val k = m.groupValues[4].split('|')
        val digs = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        fun baseN(num: Int, b: Int): String {
            if (num == 0) return "0"
            var x = num
            var out = ""
            while (x > 0) { out = digs[x % b] + out; x /= b }
            return out
        }
        val map = HashMap<String, String>(c)
        if (a > 1 && c < 5000) {
            for (i in 0 until c) {
                val key = baseN(i, a)
                map[key] = k.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
            }
        }
        return runCatching {
            Regex("""\b\w+\b""").replace(p) { mr -> map[mr.value] ?: mr.value }
        }.getOrNull()
    }

    /** First plausible media URL in a (possibly unpacked) embed page. */
    internal fun findMediaUrlIn(text: String): String? {
        return Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
            .find(text)?.value?.trim()
            ?: Regex("""(?:sources?|file)\s*[:=]\s*[\[{]?\s*["'](https?://[^"']+)["']""")
                .find(text)?.groupValues?.getOrNull(1)?.trim()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 1: AniZone  (https://anizone.to)
    //
    //  Pattern: Browse the public /anime list page (no Cloudflare challenge
    //  on it), pick the best title match, then load
    //    /anime/{8-char-id}/{episode}
    //  and regex-extract the .m3u8 URL from the Vidstack <media-player> tag.
    //
    //  Verified sample:
    //    <media-player src="https://seiryuu.vid-cdn.xyz/{uuid}/master.m3u8" ...>
    //
    //  Direct m3u8 — no iframe, no third-party host. Subtitle .ass tracks
    //  are also exposed as <track> elements on the same page.
    // ════════════════════════════════════════════════════════════════════════

    internal object AniZoneResolver : AnimeSourceResolver {
        private const val SITE = "https://anizone.to"
        private const val LABEL = "AniZone"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            anilistId: Int?,
            malId: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            if (title.isBlank()) return false
            val srcLabel = "$labelPrefix • $LABEL"

            // 1. (v19) Try the direct search endpoint first — the /anime?q=
            //    Cloudflare challenge only triggers for some UAs, and when it
            //    doesn't challenge this is far more reliable than paging the
            //    browse list (which earlier missed anything not in the first
            //    ~90 catalogue entries).
            val candidates = mutableListOf<Pair<String, String>>() // (8-char-id, title)
            runCatching {
                val searchUrl = "$SITE/anime?q=${encodeUrl(title)}"
                val html = app.get(searchUrl, headers = HEADERS, timeout = 12_000).text
                Jsoup.parse(html, searchUrl).select("a[href~=/anime/[a-z0-9]{8}$]").forEach { a ->
                    val id = Regex("""/anime/([a-z0-9]{8})""").find(a.attr("href"))?.groupValues?.getOrNull(1)
                        ?: return@forEach
                    val t = a.selectFirst("img")?.attr("alt")?.trim() ?: a.text().trim()
                    if (t.isNotBlank() && id.isNotBlank()) candidates += id to t
                }
            }
            // Fall back to paging the public /anime browse list.
            for (page in 1..3) {
                val listUrl = "$SITE/anime?page=$page"
                val html = runCatching {
                    app.get(listUrl, headers = HEADERS, timeout = 12_000).text
                }.getOrNull() ?: continue
                val doc = Jsoup.parse(html, listUrl)
                doc.select("a[href~=/anime/[a-z0-9]{8}$]").forEach { a ->
                    val href = a.attr("href")
                    val id = Regex("""/anime/([a-z0-9]{8})""").find(href)?.groupValues?.getOrNull(1)
                        ?: return@forEach
                    val t = a.selectFirst("img")?.attr("alt")?.trim()
                        ?: a.text().trim()
                        ?: return@forEach
                    if (t.isNotBlank() && id.isNotBlank()) candidates += id to t
                }
                if (candidates.size >= 30) break
            }
            if (candidates.isEmpty()) return false
            if (candidates.isEmpty()) return false

            // 2. Pick the best title match.
            val qNorm = title.normaliseTitle()
            val best = candidates
                .distinctBy { it.first }
                .firstOrNull { (_, ct) -> ct.normaliseTitle() == qNorm }
                ?: candidates.distinctBy { it.first }
                    .maxByOrNull { (_, ct) -> titleSimilarity(ct, title) }
                ?: return false
            if (titleSimilarity(best.second, title) < 0.5) return false

            val animeId = best.first
            val epNum = episode ?: 1

            // 3. Fetch the episode page and extract the .m3u8 from
            //    <media-player src="…">.
            val epUrl = "$SITE/anime/$animeId/$epNum"
            val html = runCatching {
                app.get(epUrl, headers = HEADERS, timeout = 15_000).text
            }.getOrNull() ?: return false

            val mediaSrc = Regex(
                """<media-player[^>]*\ssrc=["']([^"']+\.m3u8[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)?.trim()
            if (mediaSrc.isNullOrBlank()) return false

            // 4. Subtitle tracks — same page has <track src="…/subtitles/0_en.ass">.
            Jsoup.parse(html, epUrl).select("track[src]").forEach { el ->
                val src = el.attr("src").trim()
                if (src.isNotBlank() && src != "data:,") {
                    val abs = if (src.startsWith("http")) src else "$SITE/${src.trimStart('/')}"
                    val label = el.attr("label").ifBlank { el.attr("srclang") }.ifBlank { "Subtitle" }
                    subtitleCallback(SubtitleFile(label, abs))
                }
            }

            // (v19) Emit EVERY <media-player> source on the page (some
            // episodes carry several hosts) instead of just the first match —
            // and probe each one so dead hosts never reach the player.
            val mediaSrcs = Regex("""<media-player[^>]*\ssrc=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                .findAll(html).map { it.groupValues[1].trim() }.distinct().toList()
            val cands = mediaSrcs.map { src ->
                MediaCandidate(
                    url = src,
                    sourceLabel = srcLabel,
                    name = srcLabel,
                    referer = epUrl,
                    headers = HEADERS,
                )
            }
            return emitMediaCandidates(cands, subtitleCallback, callback)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 2: Mkissa  (https://mkissa.to)
    //
    //  Mkissa proxies the AllAnime player, so we bypass mkissa entirely and
    //  hit the AllAnime Apollo persisted-query API at api.allanime.day.
    //  This returns direct .m3u8 / .mp4 URLs — no iframe parsing needed.
    //
    //  Three persisted-query hashes are used:
    //    • search  (9343797c…)
    //    • show    (73d998d2…) — episode count
    //    • episode (5f1a64b7…) — stream source URLs
    //
    //  Required headers:
    //    Referer: https://allmanga.to/
    //    Origin:  https://allmanga.to
    // ════════════════════════════════════════════════════════════════════════

    internal object MkissaResolver : AnimeSourceResolver {
        // (v19) Mkissa is a re-skin of AllAnime on its OWN api origin
        // (api.mkissa.net — sending tokens to api.allanime.day is what used
        // to produce AA_CRYPTO_STALE). Episode streams are protected by the
        // "AA crypto" scheme: a persisted-query GET that must carry an
        // `aaReq` AES-256-GCM token. The key material rotates every ~3 days
        // and is bootstrapped live from the mkissa.to front page:
        //
        //   window.__aaCrypto = {"epoch":N,"switchAt":Ms,"graceMs":Ms,"partB":"<b64>"}
        //   app chunk → crypto chunk holds ONE 64-hex "mask"
        //   AES key = mask XOR base64decode(partB)
        //   aaReq   = b64( 0x01 ‖ iv ‖ AES-GCM(key, iv, payload) )
        //   payload = {"v":1,"ts":bucketMs,"epoch":N,"buildId":"B","qh":"<sha256>"}
        //   iv      = sha256("N:B:qh:bucketMs")[0:12]   (5-minute ts bucket)
        //
        // Fallback chain: live bootstrap → anipy-cli keygen.json mirror →
        // embedded snapshot. On AA_CRYPTO_* errors we re-bootstrap once.
        private const val SITE = "https://mkissa.to"
        private const val API = "https://api.mkissa.net/api"
        private const val CLOCK_BASE = "https://allanime.day"
        private const val CDN_IMMUTABLE = "https://cdn.allanime.day/all/mk/_app/immutable/"
        private const val KEYGEN_URL =
            "https://raw.githubusercontent.com/sdaqo/anipy-cli/refs/heads/key-gen/scripts/keygen/keygen.json"
        private const val LABEL = "Mkissa"
        private const val SEARCH_HASH =
            "a24c500a1b765c68ae1d8dd85174931f661c71369c89b92b88b75a725afc471c"
        private const val FALLBACK_EPOCH = 6884L
        private const val FALLBACK_KEY_HEX =
            "f34fa715e2958b8c1ebc6efa4d089acd8f196d8b83d4b6201586c00c8a52e4a8"
        private const val FALLBACK_QH =
            "f4662f4b7510b26795dd53ef824a0bf1740fbbc5d1273fab18222ac831bca8d0"
        private const val FALLBACK_BUILD = "51"
        private const val STATIC_KEY = "Xot36i3lK3:v1"

        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Origin" to SITE,
            "Accept" to "application/json, text/plain, */*",
        )

        private data class AaCrypto(
            val epoch: Long,
            val key: ByteArray,
            val queryHash: String,
            val buildId: String,
        )

        @Volatile private var cachedCrypto: AaCrypto? = null
        @Volatile private var cryptoCachedAt: Long = 0L
        private const val CRYPTO_TTL = 30 * 60 * 1000L

        override suspend fun resolve(
            app: Requests,
            title: String,
            anilistId: Int?,
            malId: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            if (title.isBlank()) return false
            val srcLabel = "$labelPrefix • $LABEL"
            val epToUse = episode ?: 1

            // 1. Search (POST — plain GET trips the Cloudflare challenge).
            val searchBody = JSONObject().apply {
                put("variables", JSONObject().apply {
                    put("search", JSONObject().apply {
                        put("allowAdult", false)
                        put("allowUnknown", false)
                        put("query", title)
                    })
                    put("limit", 26)
                    put("page", 1)
                    put("translationType", "sub")
                    put("countryOrigin", "ALL")
                })
                put("extensions", """{"persistedQuery":{"version":1,"sha256Hash":"$SEARCH_HASH"}}""")
            }.toString()
            val searchResp = postJson(app, searchBody) ?: return false
            val edges = searchResp.optJSONObject("data")?.optJSONObject("shows")
                ?.optJSONArray("edges") ?: return false
            if (edges.length() == 0) return false

            // 2. Best title match; availableEpisodes rides along on the edge
            //    so no separate show query is needed.
            data class Show(val id: String, val name: String, val sub: Int, val dub: Int)
            val shows = (0 until edges.length()).mapNotNull { i ->
                val e = edges.optJSONObject(i) ?: return@mapNotNull null
                val id = e.optStringOrNull("_id") ?: return@mapNotNull null
                val nm = e.optStringOrNull("name") ?: return@mapNotNull null
                val av = e.optJSONObject("availableEpisodes")
                Show(id, nm, av?.optInt("sub", 0) ?: 0, av?.optInt("dub", 0) ?: 0)
            }
            val qNorm = title.normaliseTitle()
            val best = shows.firstOrNull { it.name.normaliseTitle() == qNorm }
                ?: shows.maxByOrNull { titleSimilarity(it.name, title) }
                ?: return false
            if (titleSimilarity(best.name, title) < 0.5) return false

            val wantDub = best.dub >= epToUse
            if (best.sub < epToUse && !wantDub) return false

            // 3. Episode sourceUrls for sub AND dub (dub only when it has
            //    this episode — saves an API call and halves rate-limit risk).
            var crypto = ensureCrypto(app) ?: return false
            val cands = mutableListOf<MediaCandidate>()
            var any = false

            val langs = buildList {
                if (best.sub >= epToUse) add("sub" to false)
                if (wantDub) add("dub" to true)
            }
            for ((tt, isDub) in langs) {
                val epJson = fetchEpisode(app, crypto, best.id, tt, epToUse, attempt = 0)
                    ?: continue
                val srcs = epJson.optJSONObject("episode")?.optJSONArray("sourceUrls")
                    ?: continue
                for (i in 0 until srcs.length()) {
                    val src = srcs.optJSONObject(i) ?: continue
                    val rawUrl = src.optStringOrNull("sourceUrl") ?: continue
                    val serverName = src.optStringOrNull("sourceName")
                        ?: src.optStringOrNull("source") ?: "Server"
                    val dubTag = if (isDub) " · DUB" else ""
                    val label = "$srcLabel · $serverName$dubTag"
                    when {
                        // Direct stream endpoint bypass (rare)
                        rawUrl.contains("tools.fast4speed") -> {
                            cands += MediaCandidate(
                                url = rawUrl, sourceLabel = srcLabel, name = label,
                                referer = SITE, headers = HEADERS, forceHls = true,
                            )
                        }
                        // Third-party iframe hosts (ok.ru, streamsb, …)
                        rawUrl.startsWith("http") -> {
                            runCatching {
                                loadExtractor(rawUrl, "$SITE/", subtitleCallback) { link ->
                                    callback(link.relabel(srcLabel, "$label — ${link.name}"))
                                    any = true
                                }
                            }
                        }
                        // "--" XOR-encoded internal paths → clock.json link list
                        rawUrl.startsWith("--") -> {
                            val path = xorDecodePath(rawUrl)
                            if (path.isBlank()) continue
                            resolveClock(app, path, srcLabel, label, cands)
                        }
                    }
                }
            }
            if (emitMediaCandidates(cands, subtitleCallback, callback)) any = true
            return any
        }

        // ── AA crypto bootstrap ─────────────────────────────────────────────

        private suspend fun ensureCrypto(app: Requests): AaCrypto? {
            cachedCrypto?.let {
                if (System.currentTimeMillis() - cryptoCachedAt < CRYPTO_TTL) return it
            }
            return bootstrapCrypto(app)?.also {
                cachedCrypto = it
                cryptoCachedAt = System.currentTimeMillis()
            } ?: cachedCrypto
        }

        private suspend fun bootstrapCrypto(app: Requests): AaCrypto? {
            // 1. Live bootstrap straight from mkissa.to.
            runCatching {
                val html = app.get("$SITE/", headers = HEADERS, timeout = 12_000).text
                val aaRaw = Regex("""window\.__aaCrypto\s*=\s*(\{.*?\})""")
                    .find(html)?.groupValues?.getOrNull(1)
                if (aaRaw != null) {
                    val aa = JSONObject(aaRaw)
                    val partB = aa.optStringOrNull("partB")
                    val epoch = if (aa.has("epoch")) aa.optLong("epoch") else null
                    val switchAt = aa.optLong("switchAt", 0L)
                    val graceMs = aa.optLong("graceMs", 0L)
                    val materialDead = switchAt > 0 &&
                        System.currentTimeMillis() >= switchAt + graceMs
                    if (partB != null && epoch != null) {
                        val appChunk = Regex("""_app/immutable/(entry/app\.[^"']+\.js)""")
                            .find(html)?.groupValues?.getOrNull(1)
                        if (appChunk != null) {
                            val appJs = app.get(
                                CDN_IMMUTABLE + appChunk, headers = HEADERS, timeout = 12_000
                            ).text
                            val chunks = Regex("""\s*["']\.\./(chunks/[A-Za-z0-9_\-]+\.js)["']""")
                                .findAll(appJs).map { it.groupValues[1] }.toList()
                            for (chunk in chunks) {
                                val js = runCatching {
                                    app.get(CDN_IMMUTABLE + chunk, headers = HEADERS, timeout = 12_000).text
                                }.getOrNull() ?: continue
                                if ("__aaCrypto" !in js) continue
                                val masks = Regex("""[0-9a-f]{64}""")
                                    .findAll(js).map { it.value }.toList()
                                if (masks.size != 1) continue
                                val buildId = Regex("""xr\s*=\s*[^,;?]*\?\s*"(\d+)"\s*:""")
                                    .find(js)?.groupValues?.getOrNull(1) ?: FALLBACK_BUILD
                                val qh = resolveQueryHash(js) ?: FALLBACK_QH
                                if (materialDead) continue
                                val key = xorBytes(
                                    hexToBytes(masks[0]),
                                    Base64.getDecoder().decode(partB),
                                )
                                if (key.size == 32) {
                                    return AaCrypto(epoch, key, qh, buildId)
                                }
                            }
                        }
                    }
                }
            }.onFailure { Log.d(TAG, "Mkissa live bootstrap failed: ${it.message}") }

            // 2. anipy-cli keygen.json mirror (CI-updated every few hours).
            runCatching {
                val kg = JSONObject(
                    app.get(KEYGEN_URL, headers = mapOf("User-Agent" to UA), timeout = 10_000).text
                )
                val key = hexToBytes(kg.optString("key"))
                val qh = kg.optStringOrNull("query_hash")
                val epoch = if (kg.has("epoch")) kg.optLong("epoch") else null
                if (key.size == 32 && qh != null && epoch != null) {
                    return AaCrypto(epoch, key, qh, FALLBACK_BUILD)
                }
            }

            // 3. Embedded snapshot — last resort, may itself be stale.
            return AaCrypto(
                FALLBACK_EPOCH,
                hexToBytes(FALLBACK_KEY_HEX),
                FALLBACK_QH,
                FALLBACK_BUILD,
            )
        }

        /** sha256 of the fully-resolved episode-sources GraphQL template. */
        private fun resolveQueryHash(js: String): String? {
            var template = Regex("""(\nquery\([^`]*)`""").findAll(js)
                .map { it.groupValues[1] }
                .firstOrNull { "sourceUrls" in it && "episode(" in it }
                ?: return null
            fun resolve(tmpl: String, depth: Int): String {
                if (depth > 6) return tmpl
                var out = tmpl
                for (m in Regex("""\$\{([^}]+)\}""").findAll(tmpl)) {
                    val name = m.groupValues[1]
                    val rep: String = if (name.endsWith("()")) {
                        Regex("""\b""" + Regex.escape(name.dropLast(2)) +
                            """\s*=\s*\w+\s*=>\s*\w+\s*\?\s*`[^`]*`\s*:\s*`([^`]*)`""")
                            .find(js)?.groupValues?.getOrNull(1) ?: ""
                    } else {
                        Regex("""\b""" + Regex.escape(name) + """\s*=\s*`([^`]*)`""")
                            .find(js)?.groupValues?.getOrNull(1)?.let { resolve(it, depth + 1) } ?: ""
                    }
                    out = out.replace("\${" + name + "}", rep)
                }
                return out
            }
            template = resolve(template, 0)
            if ("\${" in template) return null
            return sha256Hex(template.toByteArray(Charsets.UTF_8))
        }

        // ── token + episode fetch ───────────────────────────────────────────

        private fun buildAaReq(c: AaCrypto): String {
            val ts = System.currentTimeMillis() / 300_000L * 300_000L
            val payload = """{"v":1,"ts":$ts,"epoch":${c.epoch},""" +
                """"buildId":"${c.buildId}","qh":"${c.queryHash}"}"""
            val iv = sha256("${c.epoch}:${c.buildId}:${c.queryHash}:$ts")
                .copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(c.key, "AES"),
                GCMParameterSpec(128, iv),
            )
            val ct = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(byteArrayOf(1) + iv + ct)
        }

        private suspend fun fetchEpisode(
            app: Requests,
            c: AaCrypto,
            showId: String,
            translationType: String,
            ep: Int,
            attempt: Int,
        ): JSONObject? {
            val variables = """{"showId":"$showId","translationType":"$translationType",""" +
                """"episodeString":"$ep"}"""
            val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"${c.queryHash}"},""" +
                """"aaReq":"${buildAaReq(c)}"}"""
            val url = "$API?variables=${encodeUrl(variables)}&extensions=${encodeUrl(extensions)}"
            val resp = runCatching {
                app.get(url, headers = HEADERS, timeout = 15_000)
            }.getOrNull() ?: return null
            if (resp.code !in 200..299 || resp.text.isBlank()) return null
            val json = runCatching { JSONObject(resp.text) }.getOrNull() ?: return null

            val err = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
            if (!err.isNullOrBlank()) {
                when {
                    err.startsWith("Too many requests") && attempt < 2 -> {
                        val wait = Regex("""(\d+)\s*seconds?""").find(err)
                            ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 5L
                        delay((wait + 1).coerceAtMost(9L) * 1000L)
                        return fetchEpisode(app, c, showId, translationType, ep, attempt + 1)
                    }
                    err.startsWith("AA_CRYPTO") && attempt < 1 -> {
                        // Stale material — rebuild from scratch and retry once.
                        cachedCrypto = null
                        val fresh = ensureCrypto(app) ?: return null
                        if (fresh === c) return null
                        return fetchEpisode(app, fresh, showId, translationType, ep, attempt + 1)
                    }
                    else -> return null
                }
            }
            var data = json.optJSONObject("data") ?: return null
            data.optStringOrNull("tobeparsed")?.let { tbp ->
                data = decryptToBeParsed(tbp, c) ?: return null
            }
            return data
        }

        /** tobeparsed = b64( 0x01 ‖ iv(12) ‖ ciphertext ‖ gcmTag(16) ). */
        private fun decryptToBeParsed(tbp: String, c: AaCrypto): JSONObject? {
            val raw = runCatching { Base64.getDecoder().decode(tbp) }.getOrNull()
                ?: return null
            if (raw.size < 1 + 12 + 16 + 1) return null
            val iv = raw.copyOfRange(1, 13)
            val ctAndTag = raw.copyOfRange(13, raw.size)
            for (candidate in listOf(c.key, STATIC_KEY.toByteArray(Charsets.UTF_8))) {
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(candidate, "AES"),
                        GCMParameterSpec(128, iv),
                    )
                    val plain = cipher.doFinal(ctAndTag)
                    return JSONObject(String(plain, Charsets.UTF_8))
                } catch (t: Throwable) {
                    // try the next key
                }
            }
            return null
        }

        // ── clock.json link resolution ──────────────────────────────────────

        /** "--" prefixed sources: hex pairs XOR 0x38, then clock→clock.json. */
        private fun xorDecodePath(enc: String): String {
            val hex = enc.removePrefix("--")
            if (hex.length % 2 != 0) return ""
            return runCatching {
                buildString(hex.length / 2) {
                    var i = 0
                    while (i < hex.length) {
                        append((hex.substring(i, i + 2).toInt(16) xor 56).toChar())
                        i += 2
                    }
                }.replace("clock", "clock.json")
            }.getOrDefault("")
        }

        /** GET the clock.json link list on allanime.day + harvest candidates. */
        private suspend fun resolveClock(
            app: Requests,
            path: String,
            srcLabel: String,
            label: String,
            out: MutableList<MediaCandidate>,
        ) {
            val body = runCatching {
                app.get(
                    CLOCK_BASE + path,
                    headers = mapOf("User-Agent" to UA, "Referer" to "$SITE/"),
                    timeout = 12_000,
                ).text
            }.getOrNull() ?: return
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return
            val links = json.optJSONArray("links") ?: return
            for (i in 0 until links.length()) {
                val l = links.optJSONObject(i) ?: continue
                var link = l.optStringOrNull("link") ?: continue
                val linkRef = l.optJSONObject("headers")?.optStringOrNull("Referer")
                    ?: CLOCK_BASE
                if ("repackager.wixmp.com" in link) {
                    // Comma-joined quality ladder — rebuild one URL per quality.
                    link = link.substringBefore(".urlset")
                        .replace("repackager.wixmp.com/", "")
                    val parts = link.split(",")
                    if (parts.size >= 3) {
                        for (qi in 1 until parts.size - 1) {
                            val qual = parts[qi].trim()
                            if (qual.isBlank()) continue
                            val u = parts[0] + qual + parts.last()
                            out += MediaCandidate(
                                url = u, sourceLabel = srcLabel,
                                name = "$label · $qual",
                                referer = linkRef, forceHls = true,
                                quality = qualityFromLabel(qual),
                            )
                        }
                        continue
                    }
                }
                out += MediaCandidate(
                    url = link, sourceLabel = srcLabel, name = label,
                    referer = linkRef,
                    forceHls = !link.contains(".mp4", ignoreCase = true),
                    quality = qualityFromLabel(l.optStringOrNull("resolutionStr")),
                )
            }
        }

        // ── small utilities ─────────────────────────────────────────────────

        private suspend fun postJson(app: Requests, body: String): JSONObject? {
            val resp = runCatching {
                app.post(
                    API,
                    headers = HEADERS,
                    requestBody = body.toRequestBody("application/json".toMediaTypeOrNull()),
                    timeout = 15_000,
                )
            }.getOrNull() ?: return null
            if (resp.code !in 200..299 || resp.text.isBlank()) return null
            return runCatching { JSONObject(resp.text) }.getOrNull()
        }

        private fun sha256(input: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

        private fun sha256Hex(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b)
                .joinToString("") { "%02x".format(it) }

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

        private fun xorBytes(a: ByteArray, b: ByteArray): ByteArray =
            ByteArray(minOf(a.size, b.size)) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 3: Miruro  (https://www.miruro.to)
    //
    //  Search via AniList GraphQL (no Cloudflare there). For episodes and
    //  stream URLs, use Miruro's `/api/secure/pipe?e={base64(json)}` endpoint.
    //  The response is URL-safe base64 → GZIP → JSON.
    //
    //  The pipe payload is:
    //    {"path":"episodes","method":"GET","query":{"anilistId":X},"body":null,"version":"0.1.0"}
    //
    //  For sources, use path="sources" with query
    //  {episodeId, provider, category, anilistId}.
    //
    //  Provider priority: zoro > animepahe > gogoanime > kiwi.
    //
    //  Cloudflare: strict on the home page. The /api/secure/pipe endpoint
    //  sometimes works without cf_clearance from datacenter IPs; if it
    //  doesn't, the user can use a CloudflareKiller interceptor at the
    //  provider level. Here we attempt the direct request.
    // ════════════════════════════════════════════════════════════════════════

    internal object MiruroResolver : AnimeSourceResolver {
        private const val SITE = "https://www.miruro.to"
        private const val PIPE = "$SITE/api/secure/pipe"
        private const val ANILIST_GQL = "https://graphql.anilist.co"
        private const val LABEL = "Miruro"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin" to SITE,
            "Referer" to "$SITE/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
        )
        private val ANILIST_HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        )
        private val PROVIDER_PRIORITY = listOf("zoro", "animepahe", "gogoanime", "kiwi")

        override suspend fun resolve(
            app: Requests,
            title: String,
            anilistId: Int?,
            malId: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            // 1. Resolve the AniList ID — use the one passed in directly,
            //    otherwise search AniList by title.
            val aid = anilistId ?: searchAnilistId(app, title) ?: return false
            val srcLabel = "$labelPrefix • $LABEL"
            val epToUse = episode ?: 1
            val category = "sub"

            // 2. Fetch the raw episode list for this AniList ID.
            val epPayload = JSONObject().apply {
                put("path", "episodes")
                put("method", "GET")
                put("query", JSONObject().apply { put("anilistId", aid) })
                put("body", JSONObject.NULL)
                put("version", "0.1.0")
            }
            val epRespText = pipeGet(app, epPayload) ?: return false
            val providers = epRespText.optJSONObject("providers") ?: return false

            // 3. Collect episode IDs from every provider, prioritising zoro.
            //    Each episode has `id` (which may be a base64-encoded
            //    "id:category" pair) and `number`.
            val epCandidates = mutableListOf<Pair<String, String>>() // (provider, episodeId)
            val sortedProviderKeys = providers.keys().asSequence().toList().sortedBy { p ->
                PROVIDER_PRIORITY.indexOf(p.lowercase()).let { if (it == -1) PROVIDER_PRIORITY.size else it }
            }
            for (providerKey in sortedProviderKeys) {
                val pobj = providers.optJSONObject(providerKey) ?: continue
                val epsObj = pobj.optJSONObject("episodes") ?: continue
                val catArr = epsObj.optJSONArray(category) ?: continue
                for (i in 0 until catArr.length()) {
                    val ep = catArr.optJSONObject(i) ?: continue
                    val num = ep.optInt("number", 0)
                    if (num != epToUse) continue
                    val rawId = ep.optString("id").ifBlank { continue }
                    val sourceId = if (rawId.startsWith("watch/")) rawId else normalizeEpisodeId(rawId)
                    epCandidates += providerKey to sourceId
                }
                if (epCandidates.isNotEmpty()) break
            }
            if (epCandidates.isEmpty()) {
                // Movie fallback — try a synthesised episode id "movie-1".
                if (isMovie) {
                    epCandidates += "kiwi" to "movie-1"
                } else return false
            }

            // 4. For each candidate, fetch the stream URLs.
            var any = false
            for ((provider, epId) in epCandidates) {
                val encodedEpId = if (epId.startsWith("watch/")) {
                    // Direct watch path — fetch via pipe with the path itself.
                    val directPayload = JSONObject().apply {
                        put("path", epId)
                        put("method", "GET")
                        put("query", JSONObject.NULL)
                        put("body", JSONObject.NULL)
                        put("version", "0.1.0")
                    }
                    val direct = pipeGet(app, directPayload)
                    if (direct != null && hasPlayableStreams(direct)) {
                        emitStreams(direct, srcLabel, provider, subtitleCallback, callback)
                        any = true
                    }
                    continue
                } else {
                    urlSafeB64Encode(epId.toByteArray())
                }

                val srcPayload = JSONObject().apply {
                    put("path", "sources")
                    put("method", "GET")
                    put("query", JSONObject().apply {
                        put("episodeId", encodedEpId)
                        put("provider", provider)
                        put("category", category)
                        put("anilistId", aid)
                    })
                    put("body", JSONObject.NULL)
                    put("version", "0.1.0")
                }
                val srcResp = pipeGet(app, srcPayload) ?: continue
                if (emitStreams(srcResp, srcLabel, provider, subtitleCallback, callback)) {
                    any = true
                }
            }
            return any
        }

        private fun normalizeEpisodeId(value: String): String {
            val decoded = urlSafeB64DecodeText(value)
            return if (decoded != null && ":" in decoded) decoded else value
        }

        private fun hasPlayableStreams(node: JSONObject): Boolean {
            return findFirstArray(node, "streams", "sources")?.let { arr ->
                (0 until arr.length()).any { i ->
                    arr.optJSONObject(i)?.let { streamUrl(it) } != null
                }
            } == true
        }

        private fun findFirstArray(node: JSONObject, vararg names: String): JSONArray? {
            for (name in names) {
                node.optJSONArray(name)?.let { return it }
            }
            val keys = node.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = node.opt(k)
                if (v is JSONArray) return v
                if (v is JSONObject) findFirstArray(v, *names)?.let { return it }
            }
            return null
        }

        private fun streamUrl(node: JSONObject): String? {
            return node.optStringOrNull("url") ?: node.optStringOrNull("file")
                ?: node.optStringOrNull("stream")
                ?: node.optJSONObject("source")?.optStringOrNull("url")
                ?: node.optJSONObject("source")?.optStringOrNull("file")
        }

        private suspend fun emitStreams(
            node: JSONObject,
            sourceLabel: String,
            provider: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            var found = false
            // Subtitles
            findFirstArray(node, "subtitles", "tracks")?.let { subs ->
                for (i in 0 until subs.length()) {
                    val s = subs.optJSONObject(i) ?: continue
                    val url = s.optStringOrNull("file") ?: s.optStringOrNull("url") ?: continue
                    val label = s.optStringOrNull("label") ?: s.optStringOrNull("lang")
                        ?: s.optStringOrNull("language") ?: "Subtitle"
                    subtitleCallback(SubtitleFile(label, url))
                }
            }
            // Streams
            findFirstArray(node, "streams", "sources")?.let { streams ->
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    val url = streamUrl(s) ?: continue
                    val qLabel = s.optStringOrNull("quality") ?: s.optStringOrNull("label")
                        ?: s.optStringOrNull("resolution") ?: "Auto"
                    val type = s.optStringOrNull("type")?.lowercase()
                    val link = newExtractorLink(
                        source = sourceLabel,
                        name = "$sourceLabel ${provider.uppercase()} $qLabel",
                        url = url,
                        type = when {
                            type == "dash" || type == "mpd" || url.contains(".mpd", true) -> ExtractorLinkType.DASH
                            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                            else -> ExtractorLinkType.VIDEO
                        },
                    ) {
                        this.referer = SITE
                        this.quality = qualityFromLabel(qLabel).takeIf { it != Qualities.Unknown.value }
                            ?: Qualities.Unknown.value
                        this.headers = mapOf("Referer" to "$SITE/", "Origin" to SITE)
                    }
                    callback(link)
                    found = true
                }
            }
            return found
        }

        private suspend fun pipeGet(app: Requests, payload: JSONObject): JSONObject? {
            val encoded = encodePipeRequest(payload)
            val url = "$PIPE?e=${encodeUrl(encoded)}"
            val r = runCatching {
                app.get(url, headers = HEADERS, timeout = 12_000)
            }.getOrNull() ?: return null
            if (r.code !in 200..299 || r.text.isBlank()) return null
            // Cloudflare challenge body is HTML, not base64 — short-circuit.
            if (r.text.contains("cloudflare", ignoreCase = true) ||
                r.text.contains("<!DOCTYPE", ignoreCase = true)) return null
            return decodePipeResponse(r.text)
        }

        private suspend fun searchAnilistId(app: Requests, query: String): Int? {
            val gql = """
                query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
                  Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
                      id
                      title { romaji english native }
                    }
                  }
                }
            """.trimIndent()
            val body = JSONObject().apply {
                put("query", gql)
                put("variables", JSONObject().apply {
                    put("search", query)
                    put("page", 1)
                    put("perPage", 5)
                })
            }.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val res = runCatching {
                app.post(ANILIST_GQL, headers = ANILIST_HEADERS, requestBody = body, timeout = 10_000)
            }.getOrNull() ?: return null
            if (res.code !in 200..299) return null
            val mediaArr = runCatching {
                JSONObject(res.text).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
            }.getOrNull() ?: return null
            // Pick the best title match.
            var bestId: Int? = null
            var bestScore = 0.0
            for (i in 0 until mediaArr.length()) {
                val m = mediaArr.optJSONObject(i) ?: continue
                val id = m.optInt("id", 0).takeIf { it != 0 } ?: continue
                val titles = m.optJSONObject("title")
                val t = titles?.optStringOrNull("english") ?: titles?.optStringOrNull("romaji")
                    ?: titles?.optStringOrNull("native") ?: continue
                val score = titleSimilarity(t, query)
                if (score > bestScore) {
                    bestScore = score
                    bestId = id
                }
            }
            return if (bestScore >= 0.5) bestId else null
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 4: AniChi  (https://anichi.to)
    //
    //  AniChi uses the KuAnime framework. The watch page loads an external
    //  mapper at mapper.nekostream.site that returns server tokens keyed by
    //  MAL ID. Each token is fed to /ajax/server?get={token} which returns
    //  a URL — either:
    //    • https://mewcdn.online/player/plyr.php#{base64(m3u8_url)} — decode
    //      the fragment to get the raw .m3u8 (fast path)
    //    • https://megaplay.buzz/stream/… — needs an iframe extractor
    //
    //  If we have a MAL ID, we use the mapper directly — no search needed.
    //  Otherwise, we scrape /filter?keyword= for the slug-id.
    // ════════════════════════════════════════════════════════════════════════

    internal object AniChiResolver : AnimeSourceResolver {
        private const val SITE = "https://anichi.to"
        private const val MAPPER = "https://mapper.nekostream.site/api"
        private const val LABEL = "AniChi"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
        )
        private val AJAX_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            anilistId: Int?,
            malId: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val srcLabel = "$labelPrefix • $LABEL"
            val epToUse = episode ?: 1

            // 1. Find the anime's slug-id on AniChi by searching the title.
            //    (The slug-id is the URL identifier like "naruto-eybxz" —
            //    needed to build the watch URL.)
            val slugId = findAniChiSlugId(app, title) ?: return false

            // 2. Fetch the watch page for the specific episode. The watch
            //    page contains `data-mal`, `data-slug`, `data-timestamp`
            //    attrs on the episode <a> elements, which we feed to the
            //    mapper API.
            val watchUrl = "$SITE/watch/$slugId/ep-$epToUse"
            val watchHtml = runCatching {
                app.get(watchUrl, headers = HEADERS, timeout = 12_000).text
            }.getOrNull() ?: return false

            // Extract MAL ID, episode slug, and timestamp from the watch page.
            // If multiple episodes are listed, pick the one matching epToUse.
            val epAnchor = Regex(
                """<a[^>]*data-num=["']?$epToUse["']?[^>]*>""",
                RegexOption.IGNORE_CASE
            ).find(watchHtml)
            val epAttrs = epAnchor?.value ?: ""

            val malIdToUse = malId
                ?: Regex("""data-mal=["']?(\d+)["']?""").find(epAttrs)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""data-mal-id=["']?(\d+)["']?""").find(watchHtml)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return false

            val epSlug = Regex("""data-slug=["']?([^"'\s>]+)["']?""").find(epAttrs)?.groupValues?.getOrNull(1)
                ?: epToUse.toString()
            val epTimestamp = Regex("""data-timestamp=["']?(\d+)["']?""").find(epAttrs)?.groupValues?.getOrNull(1)
                ?: (System.currentTimeMillis() / 1000).toString()

            // 3. Call the mapper API with (mal_id, episode_slug, timestamp).
            val mapperUrl = "$MAPPER/mal/$malIdToUse/${encodeUrl(epSlug)}/$epTimestamp"
            val mapperResp = runCatching {
                app.get(mapperUrl, headers = AJAX_HEADERS, timeout = 12_000).text
            }.getOrNull() ?: return false
            val mapperJson = runCatching { JSONObject(mapperResp) }.getOrNull() ?: return false

            // 4. The mapper returns server tokens keyed by server name
            //    (e.g. "Kiwi-Stream", "Vidstream", "Vibe-Stream").
            //    Each entry has {"sub":{"url":"<token>"}, "dub":{"url":"<token>"}}.
            // (v19) Take BOTH sub and dub tokens — previously only "sub" was
            // read, so AniChi dubs never appeared. Dub tokens are labelled.
            val tokens = mutableListOf<Triple<String, String, Boolean>>() // (serverName, token, isDub)
            val keys = mapperJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == "status") continue
                val serverObj = mapperJson.optJSONObject(k) ?: continue
                val serverName = k.trim().trimEnd('-').trim()
                val subToken = serverObj.optJSONObject("sub")?.optStringOrNull("url")
                val dubToken = serverObj.optJSONObject("dub")?.optStringOrNull("url")
                if (!subToken.isNullOrBlank()) tokens += Triple(serverName, subToken, false)
                if (!dubToken.isNullOrBlank()) tokens += Triple(serverName, dubToken, true)
            }
            if (tokens.isEmpty()) return false

            // 5. For each token, call /ajax/server?get={token} to get the
            //    iframe URL. If it's a mewcdn.online URL with a base64
            //    fragment, decode it directly to a .m3u8.
            val anichiCands = mutableListOf<MediaCandidate>()
            var any = false
            for ((serverName, token, isDub) in tokens) {
                val serverLabel = "$srcLabel · $serverName" + (if (isDub) " · DUB" else "")
                val serverUrl = "$SITE/ajax/server?get=${encodeUrl(token)}"
                val serverResp = runCatching {
                    app.get(serverUrl, headers = AJAX_HEADERS, timeout = 10_000).text
                }.getOrNull() ?: continue
                val serverJson = runCatching { JSONObject(serverResp) }.getOrNull() ?: continue
                val result = serverJson.optJSONObject("result") ?: continue
                val iframeUrl = result.optStringOrNull("url") ?: continue

                // Fast path: mewcdn.online/player/plyr.php#{base64(m3u8)}
                if (iframeUrl.contains("mewcdn.online") && iframeUrl.contains("#")) {
                    val b64 = iframeUrl.substringAfter("#").substringBefore("&").trim()
                    val decoded = urlSafeB64DecodeText(b64) ?: continue
                    if (decoded.contains(".m3u8", true)) {
                        anichiCands += MediaCandidate(
                            url = decoded,
                            sourceLabel = serverLabel,
                            name = serverLabel,
                            referer = iframeUrl,
                            headers = mapOf("Referer" to iframeUrl, "User-Agent" to UA),
                        )
                    }
                    continue
                }

                // Slow path: megaplay.buzz/stream/… — try the generic
                // extractor (Cloudstream's loadExtractor) which may or may
                // not support megaplay. If it doesn't, we silently skip.
                if (iframeUrl.startsWith("http")) {
                    runCatching {
                        loadExtractor(iframeUrl, "$SITE/", subtitleCallback) { link ->
                            callback(link.relabel(serverLabel, "$serverLabel — ${link.name}"))
                            any = true
                        }
                    }
                }
            }
            if (emitMediaCandidates(anichiCands, subtitleCallback, callback)) any = true
            return any
        }

        /** Search AniChi by title and return the slug-id (e.g., "naruto-eybxz"). */
        private suspend fun findAniChiSlugId(app: Requests, title: String): String? {
            if (title.isBlank()) return null
            val searchUrl = "$SITE/filter?keyword=${encodeUrl(title)}"
            val html = runCatching {
                app.get(searchUrl, headers = HEADERS, timeout = 12_000).text
            }.getOrNull() ?: return null
            val doc = Jsoup.parse(html, searchUrl)
            val candidates = mutableListOf<Pair<String, String>>() // (slug-id, title)
            // Primary selector: <a class="name d-title" href="/anime/{slug-id}">
            doc.select("a.name.d-title, a.d-title").forEach { a ->
                val href = a.attr("href").ifBlank { return@forEach }
                val slugId = Regex("""/anime/([a-z0-9\-]+)""").find(href)?.groupValues?.getOrNull(1)
                    ?: return@forEach
                val t = a.attr("data-en").ifBlank { a.attr("data-jp") }.ifBlank { a.text() }
                if (t.isNotBlank() && slugId.isNotBlank()) candidates += slugId to t
            }
            // Fallback: any <a href="/anime/…"> with an <img alt="…">
            if (candidates.isEmpty()) {
                doc.select("a[href~=/anime/[a-z0-9]+-[a-z0-9]{5}$]").forEach { a ->
                    val href = a.attr("href")
                    val slugId = Regex("""/anime/([a-z0-9\-]+)""").find(href)?.groupValues?.getOrNull(1)
                        ?: return@forEach
                    val t = a.selectFirst("img")?.attr("alt")?.ifBlank { null } ?: a.text()
                    if (t.isNotBlank() && slugId.isNotBlank()) candidates += slugId to t
                }
            }
            if (candidates.isEmpty()) return null
            val qNorm = title.normaliseTitle()
            val best = candidates.firstOrNull { (_, t) -> t.normaliseTitle() == qNorm }
                ?: candidates.maxByOrNull { (_, t) -> titleSimilarity(t, title) }
                ?: return null
            return if (titleSimilarity(best.second, title) >= 0.5) best.first else null
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 5: UniqueStream  (https://anime.uniquestream.net)
    //
    //  Public FastAPI backend (full schema at /api/v1/openapi.json):
    //    GET /api/v1/search?query=…           → series[] / episodes[]
    //    GET /api/v1/series/{contentId}       → seasons[] (arcs, each with
    //                                           content_id + episode_count)
    //    GET /api/v1/season/{id}/episodes     → [{content_id, episode_number}]
    //    GET /api/v1/episode/{id}/media/hls/{sub|dub}
    //        → {hls:{locale, playlist, subtitles[], hard_subs[{locale,playlist}]}}
    //
    //  The playlists are signed HLS masters on mediacache.cc — direct links,
    //  no extractor games. Seasons are story ARCS (e.g. "East Blue (1-61)"),
    //  so we map Cloudstream's absolute episode number onto the right arc.
    // ════════════════════════════════════════════════════════════════════════

    internal object UniqueStreamResolver : AnimeSourceResolver {
        private const val SITE = "https://anime.uniquestream.net"
        private const val API = "$SITE/api/v1"
        private const val LABEL = "UniqueStream"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json",
            "Referer" to "$SITE/",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            anilistId: Int?,
            malId: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            if (title.isBlank()) return false
            val srcLabel = "$labelPrefix • $LABEL"
            val epToUse = episode ?: 1

            // 1. Search.
            val search = getJson(
                app, "$API/search?query=${encodeUrl(title)}&limit=5"
            ) ?: return false
            val seriesArr = search.optJSONArray("series")

            data class SMatch(val cid: String, val title: String)
            val series = (0 until (seriesArr?.length() ?: 0)).mapNotNull { i ->
                val o = seriesArr?.optJSONObject(i) ?: return@mapNotNull null
                val cid = o.optStringOrNull("content_id") ?: return@mapNotNull null
                val t = o.optStringOrNull("title") ?: return@mapNotNull null
                SMatch(cid, t)
            }
            val qNorm = title.normaliseTitle()
            val bestSeries = series.firstOrNull { it.title.normaliseTitle() == qNorm }
                ?: series.maxByOrNull { titleSimilarity(it.title, title) }

            // 2. Track down the episode content_id.
            var epContentId: String? = null
            if (bestSeries != null && titleSimilarity(bestSeries.title, title) >= 0.5) {
                epContentId = findEpisodeContentId(app, bestSeries.cid, epToUse)
            }
            if (epContentId == null && isMovie) {
                // Movies may also surface as bare "episodes" in the search payload.
                val epsArr = search.optJSONArray("episodes")
                val movieHits = (0 until (epsArr?.length() ?: 0)).mapNotNull { i ->
                    val o = epsArr?.optJSONObject(i) ?: return@mapNotNull null
                    val cid = o.optStringOrNull("content_id") ?: return@mapNotNull null
                    val t = o.optStringOrNull("title") ?: return@mapNotNull null
                    SMatch(cid, t)
                }
                epContentId = (movieHits.firstOrNull { it.title.normaliseTitle() == qNorm }
                    ?: movieHits.maxByOrNull { titleSimilarity(it.title, title) }
                        )?.takeIf { titleSimilarity(it.title, title) >= 0.5 }?.cid
            }
            if (epContentId.isNullOrBlank()) return false

            // 3. Ask for both language variants; dedupe identical playlists.
            val cands = mutableListOf<MediaCandidate>()
            val seenPlaylists = mutableSetOf<String>()
            val seenSubs = mutableSetOf<String>()
            for ((tt, tag) in listOf("sub" to " · SUB", "dub" to " · DUB")) {
                val media = getJson(app, "$API/episode/$epContentId/media/hls/$tt") ?: continue
                val hls = media.optJSONObject("hls") ?: continue
                hls.optStringOrNull("playlist")?.let { pl ->
                    if (seenPlaylists.add(pl)) {
                        cands += MediaCandidate(
                            url = pl, sourceLabel = srcLabel, name = "$srcLabel$tag",
                            referer = "$SITE/", headers = HEADERS, forceHls = true,
                        )
                    }
                }
                hls.optJSONArray("subtitles")?.let { subs ->
                    for (i in 0 until subs.length()) {
                        val sub = subs.optJSONObject(i) ?: continue
                        val su = sub.optStringOrNull("url")
                            ?: sub.optStringOrNull("src")
                            ?: sub.optStringOrNull("file") ?: continue
                        if (seenSubs.add(su)) {
                            val subName = sub.optStringOrNull("label")
                                ?: sub.optStringOrNull("locale")
                                ?: sub.optStringOrNull("lang") ?: "Subtitle"
                            subtitleCallback(SubtitleFile(subName, su))
                        }
                    }
                }
                hls.optJSONArray("hard_subs")?.let { hard ->
                    for (i in 0 until hard.length()) {
                        val hs = hard.optJSONObject(i) ?: continue
                        val pl = hs.optStringOrNull("playlist") ?: continue
                        if (!seenPlaylists.add(pl)) continue
                        val short = (hs.optStringOrNull("locale") ?: "??")
                            .substringBefore("-").uppercase()
                        cands += MediaCandidate(
                            url = pl, sourceLabel = srcLabel,
                            name = "$srcLabel · Hardsub $short$tag",
                            referer = "$SITE/", headers = HEADERS, forceHls = true,
                        )
                    }
                }
                // DUB call frequently returns the very same ja-JP playlist;
                // if sub already produced it there's nothing more to harvest.
            }
            return emitMediaCandidates(cands, subtitleCallback, callback)
        }

        /** Walk the arc-seasons in order and map absolute ep → (season, local ep). */
        private suspend fun findEpisodeContentId(app: Requests, seriesCid: String, ep: Int): String? {
            val detail = getJson(app, "$API/series/$seriesCid") ?: return null
            val seasonsArr = detail.optJSONArray("seasons") ?: return null
            data class Season(val cid: String, val number: Int, val count: Int)
            val seasons = (0 until seasonsArr.length()).mapNotNull { i ->
                val o = seasonsArr.optJSONObject(i) ?: return@mapNotNull null
                val cid = o.optStringOrNull("content_id") ?: return@mapNotNull null
                val count = o.optInt("episode_count",
                    o.optInt("episodes_count", o.optInt("episodes", 0)))
                if (count <= 0) return@mapNotNull null
                Season(cid, o.optInt("season_number", i + 1), count)
            }.sortedBy { it.number }
            var acc = 0
            for (sz in seasons) {
                if (ep <= acc + sz.count) {
                    val localEp = ep - acc
                    // API caps limit at 20 — jump straight to the right page.
                    val page = (localEp - 1) / 20 + 1
                    val eps = getJson(app, "$API/season/${sz.cid}/episodes?page=$page&limit=20")
                        ?: return null
                    // Endpoint returns a bare JSON array (getJson wraps it).
                    val arr = eps.optJSONArray("episodes") ?: return null
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val num = o.optDouble("episode_number",
                            o.optDouble("episode", Double.NaN))
                        if (!num.isNaN() && num.toInt() == localEp) {
                            return o.optStringOrNull("content_id")
                        }
                    }
                    return null
                }
                acc += sz.count
            }
            return null
        }

        private suspend fun getJson(app: Requests, url: String): JSONObject? {
            val resp = runCatching {
                app.get(url, headers = HEADERS, timeout = 15_000)
            }.getOrNull() ?: return null
            if (resp.code !in 200..299 || resp.text.isBlank()) return null
            val t = resp.text.trim()
            if (t.startsWith("[")) {
                // Bare array — wrap it so the caller can uniform-parse.
                return runCatching { JSONObject().put("episodes", JSONArray(t)) }.getOrNull()
            }
            return runCatching { JSONObject(t) }.getOrNull()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 6: AniNeko  (https://anineko.to)
    //
    //  /ajax/search?q=… → [{title, url:"/watch/{slug}"}] (X-Requested-With).
    //  The episode page embeds every server directly in the HTML as
    //    <button class="… server-video" data-video="{embedUrl}" data-tab="tab_N">
    //  tab_0 = hard-sub, tab_1 = soft-sub (URL carries a ?sub=/?caption_1=
    //  subtitle on cdn.anizara.store — we hand it to the player as a real
    //  subtitle track), tab_2 = dub.
    //
    //  Embeds:
    //    • vivibebe.site/{id}            → vivibebe.site/public/stream/{id}/master.m3u8
    //    • otakuhg.site / otakuvid.online→ Dean-Edwards-packed page; unpack
    //                                      and scrape the signed .m3u8
    //    • anything else                 → Cloudstream loadExtractor
    // ════════════════════════════════════════════════════════════════════════

    internal object AniNekoResolver : AnimeSourceResolver {
        private const val SITE = "https://anineko.to"
        private const val LABEL = "AniNeko"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        private val AJAX_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            anilistId: Int?,
            malId: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            if (title.isBlank()) return false
            val srcLabel = "$labelPrefix • $LABEL"
            val epToUse = episode ?: 1

            // 1. Ajax search → watch slug.
            val searchBody = runCatching {
                app.get(
                    "$SITE/ajax/search?q=${encodeUrl(title)}",
                    headers = AJAX_HEADERS, timeout = 12_000,
                ).text
            }.getOrNull() ?: return false
            val results = runCatching { JSONObject(searchBody) }.getOrNull()
                ?.optJSONArray("results") ?: return false
            data class Hit(val slug: String, val title: String)
            val hits = (0 until results.length()).mapNotNull { i ->
                val o = results.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optStringOrNull("title") ?: return@mapNotNull null
                val slug = o.optStringOrNull("url")
                    ?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                Hit(slug, t)
            }
            val qNorm = title.normaliseTitle()
            val best = hits.firstOrNull { it.title.normaliseTitle() == qNorm }
                ?: hits.maxByOrNull { titleSimilarity(it.title, title) }
                ?: return false
            if (titleSimilarity(best.title, title) < 0.5) return false

            // 2. Episode page → server buttons.
            val epUrl = "$SITE/watch/${best.slug}/ep-$epToUse"
            val html = runCatching {
                app.get(epUrl, headers = HEADERS, timeout = 15_000).text
            }.getOrNull() ?: return false
            val buttons = Regex("""<button[^>]*server-video[^>]*>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).mapNotNull { m ->
                    val tag = m.value
                    val dv = Regex("""data-video="([^"]+)"""").find(tag)
                        ?.groupValues?.getOrNull(1)?.trim() ?: return@mapNotNull null
                    val tab = Regex("""data-tab="([^"]+)"""").find(tag)
                        ?.groupValues?.getOrNull(1)?.trim() ?: "tab_0"
                    dv to tab
                }.distinctBy { it.first }.toList()
            if (buttons.isEmpty()) return false

            // 3. Resolve every server into a direct playable candidate.
            val cands = mutableListOf<MediaCandidate>()
            val seenSubs = mutableSetOf<String>()
            var any = false
            for ((rawEmbed, tab) in buttons) {
                val kind = when (tab) {
                    "tab_2" -> "DUB"
                    "tab_1" -> "SUB"
                    else -> "Hardsub"
                }
                val host = runCatching {
                    java.net.URI(rawEmbed).host?.removePrefix("www.") ?: ""
                }.getOrDefault("")
                val hostName = host.substringBefore(".")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    .ifBlank { "Server" }
                val label = "$srcLabel · $hostName · $kind"

                // Subtitle riding on the embed URL's query string.
                for (qk in listOf("sub", "caption_1", "c1_file")) {
                    Regex("""[?&]$qk=([^&]+)""").find(rawEmbed)?.groupValues?.getOrNull(1)
                        ?.let { enc ->
                            val su = runCatching {
                                URLDecoder.decode(enc, Charsets.UTF_8.name())
                            }.getOrNull() ?: return@let
                            if (su.startsWith("http") && seenSubs.add(su)) {
                                subtitleCallback(SubtitleFile("English", su))
                            }
                        }
                }
                val embed = rawEmbed.substringBefore("?")

                when {
                    host.contains("vivibebe") -> {
                        val id = embed.trimEnd('/').substringAfterLast('/')
                        if (id.isBlank()) continue
                        cands += MediaCandidate(
                            url = "https://vivibebe.site/public/stream/$id/master.m3u8",
                            sourceLabel = label, name = label,
                            referer = embed, headers = HEADERS,
                        )
                    }
                    host.isNotBlank() -> {
                        val embHtml = runCatching {
                            app.get(
                                embed,
                                headers = mapOf(
                                    "User-Agent" to UA,
                                    "Referer" to "$SITE/",
                                ),
                                timeout = 12_000,
                            ).text
                        }.getOrNull()
                        var stream: String? = null
                        if (!embHtml.isNullOrBlank()) {
                            stream = findMediaUrlIn(embHtml)
                            if (stream == null && "eval(function(p,a,c,k,e" in embHtml) {
                                stream = unpackPackedJs(embHtml)?.let { findMediaUrlIn(it) }
                            }
                        }
                        if (!stream.isNullOrBlank()) {
                            cands += MediaCandidate(
                                url = stream, sourceLabel = label, name = label,
                                referer = embed, headers = HEADERS,
                                forceHls = !stream.contains(".mp4", ignoreCase = true),
                            )
                        } else {
                            // Unknown host — let Cloudstream's extractors try.
                            runCatching {
                                loadExtractor(embed, "$SITE/", subtitleCallback) { link ->
                                    callback(link.relabel(label, "$label — ${link.name}"))
                                    any = true
                                }
                            }
                        }
                    }
                }
            }
            if (emitMediaCandidates(cands, subtitleCallback, callback)) any = true
            return any
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 7: ReANIME  (https://reanime.to)
    //
    //  Open SvelteKit API:
    //    GET /api/v1/search?q=… → [{anime_id:"slug", title:{english,romaji,native}}]
    //    GET /watch/{slug}/__data.json?ep=N → devalue flat-array page state,
    //        which (once the site has seeded an episode) embeds the stream
    //        URLs. We scan the resolved JSON text for any direct media URL.
    //
    //  Honest note: at the time of writing the site answers every episode
    //  with an EMPTY source folder ("No streaming servers available"), so
    //  this resolver quietly returns false until they flip the switch —
    //  at which point it starts working with no code change.
    // ════════════════════════════════════════════════════════════════════════

    internal object ReAnimeResolver : AnimeSourceResolver {
        private const val SITE = "https://reanime.to"
        private const val LABEL = "ReANIME"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Accept" to "application/json, text/plain, */*",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            anilistId: Int?,
            malId: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            if (title.isBlank()) return false
            val srcLabel = "$labelPrefix • $LABEL"
            val epToUse = episode ?: 1

            // 1. Search the open API.
            val body = runCatching {
                app.get(
                    "$SITE/api/v1/search?q=${encodeUrl(title)}&limit=5",
                    headers = HEADERS, timeout = 12_000,
                ).text
            }.getOrNull() ?: return false
            val results = runCatching { JSONObject(body) }.getOrNull()
                ?.optJSONArray("results") ?: return false
            data class Hit(val slug: String, val names: List<String>)
            val hits = (0 until results.length()).mapNotNull { i ->
                val o = results.optJSONObject(i) ?: return@mapNotNull null
                val slug = o.optStringOrNull("anime_id") ?: return@mapNotNull null
                val t = o.optJSONObject("title")
                val names = listOfNotNull(
                    t?.optStringOrNull("english"),
                    t?.optStringOrNull("romaji"),
                    t?.optStringOrNull("native"),
                )
                if (names.isEmpty()) return@mapNotNull null
                Hit(slug, names)
            }
            val qNorm = title.normaliseTitle()
            fun Hit.bestSim(): Double = names.maxOf { titleSimilarity(it, title) }
            val best = hits.firstOrNull { h -> h.names.any { it.normaliseTitle() == qNorm } }
                ?: hits.maxByOrNull { it.bestSim() }
                ?: return false
            if (best.bestSim() < 0.5) return false

            // 2. SvelteKit data payload for the episode — may be sizeable.
            val watchUrl = "$SITE/watch/${best.slug}"
            val dataJson = runCatching {
                app.get(
                    "$watchUrl/__data.json?ep=$epToUse",
                    headers = HEADERS, timeout = 15_000,
                ).text
            }.getOrNull() ?: return false

            // 3. Pull every direct media URL out of the flat devalue payload.
            val urls = Regex("""https?://[^"\s]+?\.(?:m3u8|mp4)(?:\?[^"\s]*)?""")
                .findAll(dataJson).map { it.value }.distinct().toList()
            if (urls.isEmpty()) return false

            val cands = urls.map { u ->
                MediaCandidate(
                    url = u, sourceLabel = srcLabel, name = srcLabel,
                    referer = watchUrl, headers = HEADERS,
                    forceHls = !u.contains(".mp4", ignoreCase = true),
                )
            }
            return emitMediaCandidates(cands, subtitleCallback, callback)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Internal null-safe helpers for org.json
// ─────────────────────────────────────────────────────────────────────────────

internal fun JSONObject.optStringOrNull(k: String): String? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").trim().takeIf { it.isNotBlank() && it != "null" }
