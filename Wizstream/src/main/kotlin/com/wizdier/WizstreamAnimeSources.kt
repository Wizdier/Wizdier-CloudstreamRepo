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
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * WizstreamAnimeSources — bundled anime-specific source resolver.
 *
 * Adds 9 anime-focused streaming sources on top of the BDIX resolvers in
 * WizstreamSources (v89 — user request: keep ONLY AniNeko from the old
 * roster, add KickAssAnime + AnimeX; v91 — add aniwaves.ru; v92 — add every
 * solvable entry from everythingmoe.com's anime streaming top-20):
 *
 *   1. AniNeko      — https://anineko.to              (server-video embeds → direct HLS)
 *   2. KickAssAnime — https://kaa.lt                   (open Nuxt JSON API → CatStream HLS)
 *   3. AnimeX       — https://animex.one               (GraphQL id-map + pp.animex.one HLS)
 *   4. Aniwaves     — https://aniwaves.ru              (ajax API → Byse attest+AES-GCM HLS)
 *   5. Anikoto      — https://anikototv.to             (ajax API → MegaPlay HLS)      [#1]
 *   6. AniZone      — https://anizone.to               (Livewire → self-hosted HLS)   [#7]
 *   7. AnimeStream  — https://anime.uniquestream.net   (open JSON API → signed HLS)   [#9]
 *   8. AniBD        — https://anibd.app                (BD site, AniList-id JSON API) [#16]
 *   9. AniDB.app    — https://anidb.app                (JSON API → self-hosted HLS)   [#6]
 * (ranks = everythingmoe.com anime streaming section; remaining top-20
 *  entries were excluded — see CHANGELOG's v92 row for the reasons:
 *  Cloudflare walls, official/DRM services, or verified stream-less.)
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

    // (v90) Hoisted Patterns — normaliseTitle/titleSimilarity run per
    // candidate across every resolver fan-out; findMediaUrlIn per embed.
    internal object RxA {
        val NON_ALNUM_RE = Regex("[^a-z0-9]+")
        val WS_SPLIT_RE = Regex("\\s+")
        val WORD_TOKEN_RE = Regex("""\b\w+\b""")
        val M3U8_URL_RE = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
        val SRC_FILE_URL_RE = Regex("""(?:sources?|file)\s*[:=]\s*[\[{]?\s*["'](https?://[^"']+)["']""")
    }


    private const val TAG = "WizstreamAnimeSources"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    suspend fun resolveAnime(
        app: Requests,
        title: String,
        altTitle: String? = null,
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

        // (v68) per-source toggles apply here too (shared WizSourcePrefs
        // prefs object; keys "wiz_src_<id>").
        val sources = listOf(
            AniNekoResolver,
            KaaResolver,
            AnimexResolver,
            AniwavesResolver,
            // (v92, user: "add every possible source from everythingmoe.com's
            // anime top-20") five new arrivals from that ranking — every
            // reachable entry whose stream pipeline could be solved live.
            AnikotoResolver,
            AnizoneResolver,
            AnimeStreamResolver,
            AnibdResolver,
            AnidbResolver,
        ).filter { WizstreamSources.WizSourcePrefs.isEnabled(it.toggleId) }

        val gate = Semaphore(5)
        val jobs = sources.map { src ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        src.resolve(
                            app = app,
                            title = title,
                            altTitle = altTitle,
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
        /** (v68) toggle identity — same class-name derivation as the shared
         *  engine (AniNekoResolver → "anineko"), shared pref namespace. */
        val toggleId: String
            get() = WizstreamSources.wizToggleId(this)

        suspend fun resolve(
            app: Requests,
            title: String,
            altTitle: String?,
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
            .replace(RxA.NON_ALNUM_RE, " ")
            .trim()
            .replace(RxA.WS_SPLIT_RE, " ")

    /** Similarity of a candidate against BOTH our title aliases (v29). */
    internal fun bestTitleSim(candidate: String, title: String, altTitle: String?): Double =
        maxOf(
            titleSimilarity(candidate, title),
            altTitle?.let { titleSimilarity(candidate, it) } ?: 0.0,
        )

    /** Exact-normalised match against either alias. */
    internal fun matchesEitherTitle(candidate: String, title: String, altTitle: String?): Boolean {
        val n = candidate.normaliseTitle()
        return n == title.normaliseTitle() || (altTitle != null && n == altTitle.normaliseTitle())
    }

    /** Jaccard token-overlap similarity 0..1. */
    internal fun titleSimilarity(a: String, b: String): Double {
        val ax = a.normaliseTitle()
        val bx = b.normaliseTitle()
        if (ax == bx) return 1.0
        if (ax.isEmpty() || bx.isEmpty()) return 0.0
        val ta = ax.split(RxA.WS_SPLIT_RE).toSet()
        val tb = bx.split(RxA.WS_SPLIT_RE).toSet()
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
                        // (v90c) HEADER parity with the ladder path: when the
                        // app's M3u8Helper can't expand the master (older
                        // Cloudstream builds — exactly the user's Android-TV
                        // case — refetch it WITHOUT the headers map), the link
                        // must still carry every header the backend demanded.
                        // This branch previously stripped them, so header-
                        // gated hosts (yuki/MegaPlay, beep, sora…) 403'd on
                        // playback → HTTP 2004 on TV while gateless Mimi
                        // kept working — matching the report exactly.
                        callback(
                            newExtractorLink(
                                source = c.sourceLabel,
                                name = c.name,
                                url = c.url,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = c.referer
                                this.quality = c.quality
                                if (c.headers.isNotEmpty()) this.headers = c.headers
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
                            if (c.headers.isNotEmpty()) this.headers = c.headers
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
                            if (c.headers.isNotEmpty()) this.headers = c.headers
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
            RxA.WORD_TOKEN_RE.replace(p) { mr -> map[mr.value] ?: mr.value }
        }.getOrNull()
    }

    /** First plausible media URL in a (possibly unpacked) embed page. */
    internal fun findMediaUrlIn(text: String): String? {
        return RxA.M3U8_URL_RE
            .find(text)?.value?.trim()
            ?: RxA.SRC_FILE_URL_RE
                .find(text)?.groupValues?.getOrNull(1)?.trim()
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
            altTitle: String?,
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

            // 1. Ajax search → watch slug (both aliases — v29).
            data class Hit(val slug: String, val title: String)
            suspend fun doSearch(q: String): List<Hit> {
                val searchBody = runCatching {
                    app.get(
                        "$SITE/ajax/search?q=${encodeUrl(q)}",
                        headers = AJAX_HEADERS, timeout = 12_000,
                    ).text
                }.getOrNull() ?: return emptyList()
                val results = runCatching { JSONObject(searchBody) }.getOrNull()
                    ?.optJSONArray("results") ?: return emptyList()
                return (0 until results.length()).mapNotNull { i ->
                    val o = results.optJSONObject(i) ?: return@mapNotNull null
                    val t = o.optStringOrNull("title") ?: return@mapNotNull null
                    val slug = o.optStringOrNull("url")
                        ?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    Hit(slug, t)
                }
            }
            fun List<Hit>.pickBest() = firstOrNull { matchesEitherTitle(it.title, title, altTitle) }
                ?: maxByOrNull { bestTitleSim(it.title, title, altTitle) }
                    ?.takeIf { bestTitleSim(it.title, title, altTitle) >= 0.5 }
            var best = doSearch(title).pickBest()
            if (best == null && !altTitle.isNullOrBlank() && !altTitle.equals(title, true)) {
                best = doSearch(altTitle).pickBest()
            }
            if (best == null) return false

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
    //  Resolver 2: KickAssAnime  (https://kaa.lt)   — v89
    //
    //  The HTML pages sit behind a Cloudflare challenge, but the whole Nuxt
    //  JSON API answers to a plain User-Agent — so we never touch HTML:
    //    POST /api/search {"query":"…"} → [{slug, title, title_en, year, …}]
    //    GET  /api/show/{slug}          → {…, watch_uri:"/{slug}/{latestEpSlug}"}
    //    GET  /api/show/{slug}/episode/{epSlug}
    //        → {episode_number, prev_ep_slug,
    //           servers:[{name, shortName, src:"https://krussdomi.com/cat-player/player?id=…"}]}
    //
    //  Episode slugs are only discoverable by walking the prev_ep_slug linked
    //  list backwards from the latest episode (their /api/episodes endpoint
    //  returns just the newest NUMBERS, no slugs — useless). Cost: 1 request
  //  when the wanted episode IS the latest, +1 per hop back (cap MAX_WALK,
    //  logged when the chain doesn't contain the episode).
    //
    //  The CatStream player page is a static Astro island whose `props`
    //  attribute holds devalue-encoded JSON with
    //    "manifest":[0,"//bl.krussdomi.com/playlist/<hash>/master.m3u8"]
    //  plus subtitle entries whose src URLs carry a "https:///" triple-slash
    //  typo that we normalise. Emission is probe-free (v83 doctrine): the
    //  master playlist goes straight through the shared M3u8Helper ladder.
    // ════════════════════════════════════════════════════════════════════════

    internal object KaaResolver : AnimeSourceResolver {
        private const val SITE = "https://kaa.lt"
        private const val LABEL = "KAA"
        private const val MAX_WALK = 130
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Accept" to "application/json, text/plain, */*",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            altTitle: String?,
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
            val targetEp = if (isMovie) 1 else (episode ?: 1)

            // 1. Search — both aliases, sim-gated (no AniList mapping exists).
            data class Hit(val slug: String, val title: String, val titleNative: String?)
            suspend fun doSearch(q: String): List<Hit> {
                val body = wizRetryOnce("kaa search") {
                    runCatching {
                        app.post(
                            "$SITE/api/search",
                            headers = HEADERS + ("Content-Type" to "application/json"),
                            requestBody = JSONObject().put("query", q).toString()
                                .toRequestBody("application/json".toMediaTypeOrNull()),
                            cacheTime = 0,
                            timeout = 12_000,
                        ).text
                    }.getOrNull()
                } ?: return emptyList()
                val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val slug = o.optStringOrNull("slug") ?: return@mapNotNull null
                    val t = o.optStringOrNull("title_en") ?: o.optStringOrNull("title")
                        ?: return@mapNotNull null
                    Hit(slug, t, o.optStringOrNull("title"))
                }
            }
            fun simOf(h: Hit): Double = maxOf(
                bestTitleSim(h.title, title, altTitle),
                h.titleNative?.let { bestTitleSim(it, title, altTitle) } ?: 0.0,
            )
            fun List<Hit>.pickBest() = firstOrNull {
                matchesEitherTitle(it.title, title, altTitle) ||
                    (it.titleNative != null &&
                        matchesEitherTitle(it.titleNative, title, altTitle))
            } ?: maxByOrNull { simOf(it) }?.takeIf { simOf(it) >= 0.5 }
            // Their matcher is phrase-strict (the FULL title "Jujutsu Kaisen:
            // The Culling Game Part 1" returns ZERO while "jujutsu kaisen"
            // lists every cours entry) — so each alias walks a query ladder
            // (full → first-two tokens → last-two tokens → first-three) until
            // one comes back non-empty; the sim gate picks the right entry.
            fun queryLadder(q: String): List<String> {
                val toks = q.normaliseTitle().split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                val out = mutableListOf(q)
                if (toks.size >= 3) out += toks.take(2).joinToString(" ")
                if (toks.size >= 3) out += toks.takeLast(2).joinToString(" ")
                if (toks.size >= 5) out += toks.take(3).joinToString(" ")
                return out.distinct()
            }
            suspend fun searchAlias(q: String): List<Hit> {
                for (cand in queryLadder(q)) {
                    val hits = doSearch(cand)
                    if (hits.isNotEmpty()) return hits
                }
                return emptyList()
            }
            var best = searchAlias(title).pickBest()
            if (best == null && !altTitle.isNullOrBlank() && !altTitle.equals(title, true)) {
                best = searchAlias(altTitle).pickBest()
            }
            if (best == null) return false

            // 2. Show payload → LATEST episode slug = the walking start point.
            val show = wizRetryOnce("kaa show") {
                runCatching {
                    app.get(
                        "$SITE/api/show/${best.slug}",
                        headers = HEADERS, timeout = 12_000,
                    ).text
                }.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
            } ?: return false
            val watchUri = show.optStringOrNull("watch_uri") ?: return false
            val parts = watchUri.trim('/').split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return false
            val showSlug = parts[parts.size - 2]
            var epSlug = parts.last()

            // 3. Back-walk the prev_ep_slug chain to episode_number == target.
            suspend fun fetchEp(slug: String): JSONObject? = runCatching {
                app.get(
                    "$SITE/api/show/$showSlug/episode/$slug",
                    headers = HEADERS, timeout = 12_000,
                ).text
            }.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
            var epJson: JSONObject? = null
            var hops = 0
            while (hops <= MAX_WALK) {
                val cur = fetchEp(epSlug) ?: return false
                val num = cur.optDouble("episode_number", -1.0)
                if ((isMovie && hops == 0) || num == targetEp.toDouble()) {
                    epJson = cur
                    break
                }
                if (num >= 0.0 && num < targetEp.toDouble()) {
                    Log.d(TAG, "kaa: ep $targetEp below chain floor ($num) for ${best.slug}")
                    return false
                }
                epSlug = cur.optStringOrNull("prev_ep_slug") ?: run {
                    Log.d(TAG, "kaa: ep $targetEp not in chain for ${best.slug}")
                    return false
                }
                hops++
            }
            if (epJson == null) {
                Log.d(TAG, "kaa: walk cap hit hunting ep $targetEp of ${best.slug}")
                return false
            }

            // 4. Every server page → devalue-props manifest (+ subtitles).
            val servers = epJson.optJSONArray("servers") ?: return false
            val manifestRe = Regex(""""manifest"\s*:\s*(?:\[\s*\d+\s*,\s*)?"([^"]+)""")
            val subSrcRe = Regex(
                """"src"\s*:\s*(?:\[\s*\d+\s*,\s*)?"(https?:/+[^"]+\.(?:srt|vtt|ass))""",
                RegexOption.IGNORE_CASE,
            )
            val subNameRe = Regex(""""name"\s*:\s*(?:\[\s*\d+\s*,\s*)?"([^"]+)""")
            val subLangRe = Regex(""""language"\s*:\s*(?:\[\s*\d+\s*,\s*)?"([^"]+)""")
            val seenSrc = mutableSetOf<String>()
            val seenSubs = mutableSetOf<String>()
            val cands = mutableListOf<MediaCandidate>()
            var any = false
            for (i in 0 until servers.length()) {
                val srv = servers.optJSONObject(i) ?: continue
                val src = srv.optStringOrNull("src") ?: continue
                if (!src.startsWith("http") || !seenSrc.add(src)) continue
                val short = srv.optStringOrNull("shortName")
                    ?: srv.optStringOrNull("name") ?: "Server"
                val label = "$srcLabel · $short"

                val pageRaw = wizRetryOnce("kaa player $short") {
                    runCatching {
                        app.get(src, headers = HEADERS, timeout = 12_000).text
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                }
                // The Astro-island props attribute arrives RAW on some edges
                // and HTML-ESCAPED (&quot;) on others — normalise once and
                // regex the unescaped text either way.
                val page = pageRaw
                    ?.replace("&quot;", "\"")
                    ?.replace("&#39;", "'")
                    ?.replace("&amp;", "&")
                var manifest: String? = null
                if (!page.isNullOrBlank()) {
                    manifest = manifestRe.find(page)?.groupValues?.getOrNull(1)?.let { m ->
                        when {
                            m.startsWith("//") -> "https:$m"
                            m.startsWith("/") -> "https://krussdomi.com$m"
                            else -> m
                        }
                    }
                    subSrcRe.findAll(page).forEach { sm ->
                        val raw = sm.groupValues.getOrNull(1) ?: return@forEach
                        val su = if (raw.startsWith("https")) {
                            "https://" + raw.removePrefix("https:").trimStart('/')
                        } else {
                            "http://" + raw.removePrefix("http:").trimStart('/')
                        }
                        if (seenSubs.add(su)) {
                            // Each sub object is {language,name,src} — so the
                            // CLOSEST preceding name/language is this track's.
                            val w0 = maxOf(0, sm.range.first - 240)
                            val preceding = page.substring(w0, sm.range.first)
                            val subName = (
                                subNameRe.findAll(preceding).lastOrNull()
                                    ?: subLangRe.findAll(preceding).lastOrNull()
                                )?.groupValues?.getOrNull(1)
                                ?.replaceFirstChar { c ->
                                    if (c.isLowerCase()) c.titlecase() else c.toString()
                                } ?: "Track"
                            subtitleCallback(SubtitleFile(subName, su))
                        }
                    }
                }
                when {
                    !manifest.isNullOrBlank() -> cands += MediaCandidate(
                        url = manifest, sourceLabel = label, name = label,
                        referer = src, forceHls = true,
                    )
                    !page.isNullOrBlank() -> {
                        val stream = findMediaUrlIn(page)
                        if (!stream.isNullOrBlank()) {
                            cands += MediaCandidate(
                                url = stream, sourceLabel = label, name = label,
                                referer = src,
                                forceHls = !stream.contains(".mp4", ignoreCase = true),
                            )
                        } else {
                            runCatching {
                                loadExtractor(src, "$SITE/", subtitleCallback) { link ->
                                    callback(link.relabel(label, "$label — ${link.name}"))
                                    any = true
                                }
                            }
                        }
                    }
                }
            }
            for (c in cands.distinctBy { it.url }) {
                if (emitOneCandidate(c, callback)) any = true
            }
            Log.d(TAG, "kaa: served ${best.slug} ep=$targetEp hops=$hops links=${cands.size}")
            return any
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 3: AnimeX  (https://animex.one)   — v89
    //
    //  Fully open server-to-server API (no Cloudflare turnstile, no auth —
    //  verified live v89):
    //    POST https://graphql.animex.one/graphql   FastSearch → items:
    //        {id:"<slug>-<rand>", anilistId, malId, titleRomaji, titleEnglish}
    //    GET  https://pp.animex.one/rest/api/servers?id={id}&epNum={N}
    //        → {subProviders:[{id,default,tip}], dubProviders:[…]}
    //    GET  https://pp.animex.one/rest/api/sources?id={id}&providerId={p}
    //             &epNum={N}&type=sub|dub
    //        → {sources:[{url,quality:"auto"|"1080p",…}], tracks:[{url,label,
    //          lang,default}], headers:{Referer|Origin:…}}
    //
    //  Stream URLs are DIRECT m3u8s ("auto" = master ladder; named qualities
    //  are per-quality playlists). The `headers` object is REQUIRED for
  //  playback (each backend hotlink-gates by its own Referer/Origin), so it
    //  is attached to every emitted link instead of guessing. Streams are
  //  deduped by URL (their "kiwi" backend currently serves the same file as
    //  "mimi"). Matching is cours-safe: exact anilistId hit first — AnimeX
    //  files cours entries under the same AniList ids we resolve — with the
    //  title-similarity gate as fallback.
    // ════════════════════════════════════════════════════════════════════════

    internal object AnimexResolver : AnimeSourceResolver {
        private const val SITE = "https://animex.one"
        private const val GQL = "https://graphql.animex.one/graphql"
        private const val PP = "https://pp.animex.one"
        private const val LABEL = "AnimeX"
        private const val FAST_SEARCH_QUERY =
            "query FastSearch(\$query: String, \$limit: Int, \$includeAdult: Boolean) { " +
            "catalogAnime(filter: { query: \$query, includeAdult: \$includeAdult }, limit: \$limit) { " +
            "items { id anilistId malId titleRomaji titleEnglish } } }"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Accept" to "application/json, text/plain, */*",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            altTitle: String?,
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
            val targetEp = if (isMovie) 1 else (episode ?: 1)

            // 1. FastSearch → internal id (exact anilistId match, sim fallback).
            data class Hit(val id: String, val anilistId: Int?, val title: String)
            suspend fun doSearch(q: String): List<Hit> {
                val payload = JSONObject()
                    .put("query", FAST_SEARCH_QUERY)
                    .put(
                        "variables",
                        JSONObject()
                            .put("query", q)
                            .put("limit", 8)
                            .put("includeAdult", false),
                    ).toString()
                val body = wizRetryOnce("animex search") {
                    runCatching {
                        app.post(
                            GQL,
                            headers = HEADERS + ("Content-Type" to "application/json"),
                            requestBody = payload
                                .toRequestBody("application/json".toMediaTypeOrNull()),
                            cacheTime = 0,
                            timeout = 15_000,
                        ).text
                    }.getOrNull()
                } ?: return emptyList()
                val items = runCatching { JSONObject(body) }.getOrNull()
                    ?.optJSONObject("data")?.optJSONObject("catalogAnime")
                    ?.optJSONArray("items") ?: return emptyList()
                return (0 until items.length()).mapNotNull { i ->
                    val o = items.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optStringOrNull("id") ?: return@mapNotNull null
                    val al = if (o.has("anilistId") && !o.isNull("anilistId")) {
                        o.optInt("anilistId")
                    } else null
                    val t = o.optStringOrNull("titleEnglish")
                        ?: o.optStringOrNull("titleRomaji") ?: return@mapNotNull null
                    Hit(id, al, t)
                }
            }
            fun List<Hit>.pick(): Hit? =
                firstOrNull { it.anilistId != null && it.anilistId == anilistId }
                    ?: maxByOrNull { bestTitleSim(it.title, title, altTitle) }
                        ?.takeIf { bestTitleSim(it.title, title, altTitle) >= 0.5 }
            // (v90c) EXACT-ID SHORT-CIRCUIT: AnimeX's schema exposes a
            // direct handle — anime(anilistId:) — so when the AniList
            // entry id is already known (the common case) one exact call
            // replaces the search + similarity dance entirely and can
            // never land on a sibling (verified live 2026-07-31:
            // anilist 110277 → "attack-on-titan-final-season-5ugtv").
            // The search ladder below stays as the fallback for id-less
            // contexts.
            suspend fun directByAnilist(aid: Int): Hit? {
                val q = "query(\$aid: Int) { anime(anilistId: \$aid) { " +
                    "id anilistId malId titleRomaji titleEnglish } }"
                val payload = JSONObject()
                    .put("query", q)
                    .put("variables", JSONObject().put("aid", aid))
                    .toString()
                val body = wizRetryOnce("animex by-anilist") {
                    runCatching {
                        app.post(
                            GQL,
                            headers = HEADERS + ("Content-Type" to "application/json"),
                            requestBody = payload
                                .toRequestBody("application/json".toMediaTypeOrNull()),
                            cacheTime = 0,
                            timeout = 15_000,
                        ).text
                    }.getOrNull()
                } ?: return null
                val o = runCatching { JSONObject(body) }.getOrNull()
                    ?.optJSONObject("data")?.optJSONObject("anime") ?: return null
                val id = o.optStringOrNull("id") ?: return null
                val t = o.optStringOrNull("titleEnglish")
                    ?: o.optStringOrNull("titleRomaji") ?: return null
                return Hit(id, aid, t)
            }
            var best = anilistId?.let { aid -> directByAnilist(aid) }
            if (best == null) best = doSearch(title).pick()
            if (best == null && !altTitle.isNullOrBlank() && !altTitle.equals(title, true)) {
                best = doSearch(altTitle).pick()
            }
            if (best == null) return false

            // 2. Server list for this episode (subs first; dubs only when the
            //    episode has no subs at all).
            val serversBody = wizRetryOnce("animex servers") {
                runCatching {
                    app.get(
                        "$PP/rest/api/servers?id=${best.id}&epNum=$targetEp",
                        headers = HEADERS, timeout = 15_000,
                    ).text
                }.getOrNull()
            } ?: return false
            val serversJson = runCatching { JSONObject(serversBody) }.getOrNull()
                ?: return false
            fun providerIds(key: String): List<String> {
                val arr = serversJson.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optStringOrNull("id")
                }
            }
            val subIds = providerIds("subProviders")
            val plan = if (subIds.isNotEmpty()) {
                subIds.map { it to false }
            } else {
                providerIds("dubProviders").map { it to true }
            }
            if (plan.isEmpty()) return false

            // 3. Each provider → direct sources + tracks + per-stream headers.
            val seenSubs = mutableSetOf<String>()
            val seenStream = mutableSetOf<String>()
            var any = false
            for ((pid, isDub) in plan) {
                val body = wizRetryOnce("animex src $pid") {
                    runCatching {
                        app.get(
                            "$PP/rest/api/sources?id=${best.id}&providerId=$pid" +
                                "&epNum=$targetEp&type=${if (isDub) "dub" else "sub"}",
                            headers = HEADERS, timeout = 15_000,
                        ).text
                    }.getOrNull()
                } ?: continue
                val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
                val apiHeaders = mutableMapOf<String, String>()
                json.optJSONObject("headers")?.let { h ->
                    h.keys().forEach { k ->
                        h.optStringOrNull(k)?.let { v -> apiHeaders[k] = v }
                    }
                }
                // (v90c) Header superset — backends answering with only an
                // Origin (owocdn/anidb-app today) can still hotlink-gate on
                // Referer deeper in the chain (the HTTP 2004 class the user
                // hit on TV). Send both unless the response already dictated
                // the pair (sora's kaa.lt Referer is required as-is and
                // arrives in the map untouched).
                apiHeaders.putIfAbsent("Referer", "$SITE/")
                apiHeaders.putIfAbsent("Origin", SITE)
                val referer = apiHeaders["Referer"] ?: "$SITE/"
                val pname = pid.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                }
                val label = "$srcLabel · $pname${if (isDub) " · DUB" else ""}"

                json.optJSONArray("sources")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        val url = s.optStringOrNull("url") ?: continue
                        if (!seenStream.add(url)) continue
                        val qLabel = s.optStringOrNull("quality")
                        if (qLabel == null || qLabel.equals("auto", ignoreCase = true)) {
                            // Master playlist → shared M3u8Helper ladder.
                            if (emitOneCandidate(
                                    MediaCandidate(
                                        url = url, sourceLabel = label, name = label,
                                        referer = referer, headers = apiHeaders,
                                        forceHls = true,
                                    ),
                                    callback,
                                )
                            ) any = true
                        } else {
                            // Per-quality playlist — emit as-is.
                            callback(
                                newExtractorLink(
                                    source = label,
                                    name = "$label · $qLabel",
                                    url = url,
                                    type = ExtractorLinkType.M3U8,
                                ) {
                                    this.referer = referer
                                    this.quality = qualityFromLabel(qLabel)
                                    this.headers = apiHeaders
                                }
                            )
                            any = true
                        }
                    }
                }

                // Subtitle tracks — default-flagged language first.
                val tracks = json.optJSONArray("tracks")
                if (tracks != null) {
                    (0 until tracks.length()).mapNotNull { i ->
                        val t = tracks.optJSONObject(i) ?: return@mapNotNull null
                        val u = t.optStringOrNull("url") ?: return@mapNotNull null
                        Triple(
                            t.optStringOrNull("label") ?: t.optStringOrNull("lang") ?: "Track",
                            u,
                            t.optBoolean("default", false),
                        )
                    }.sortedByDescending { it.third }.forEach { (tLabel, tUrl, _) ->
                        if (seenSubs.add(tUrl)) subtitleCallback(SubtitleFile(tLabel, tUrl))
                    }
                }
            }
            Log.d(TAG, "animex: served ${best.id} ep=$targetEp providers=${plan.size}")
            return any
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 4: Aniwaves  (https://aniwaves.ru)   — v91 (user request)
    //
    //  Aniwave/9anime-template clone whose ajax API answers WITHOUT any VRF:
    //    GET  /ajax/anime/search?keyword=…         → JSON{result{html items}}
    //    GET  /ajax/episode/list/{animeId}         → JSON{result: episode HTML}
    //    GET  /ajax/server/list?servers={id}&eps={n} → JSON{result: server HTML}
    //    GET  /ajax/sources?id={urlenc(linkId)}&asi=1&autoPlay=0
    //        → JSON{result:{url(embed), server, skip_data, sources[], tracks[]}}
    //
    //  Servers (sv-id): 4=Vidplay→play.echovideo.ru (171 KB runtime-obfuscated
    //  JWPlayer blob — no static API, passed to loadExtractor only),
    //  2=DGHG→DoodStream+Turnstile (same, unsupported), and
    //  1=BYFMS → a Byse-family host ({host}/e/{code}) which WE IMPLEMENT FULLY:
    //    1. POST {host}/api/videos/access/challenge            → {challenge_id, nonce}
    //    2. ECDSA P-256 keygen, sign(nonce,SHA-256,P1363 r‖s) → JWK pubkey →
    //       POST {host}/api/videos/access/attest {signature, public_key, client…}
    //       → {viewer_id, device_id, token}   (device attestation)
    //    3. POST {host}/api/videos/{code}/embed/playback {fingerprint:{token}}
    //       → on {"error":"captcha_required"} run the POW branch and retry with
    //         X-Captcha-Token: { verify: {pow_token, solution} ← solve
    //         leadingZeroBits(gr(nonce+":"+s)) ≥ difficulty } (difficulty≈12 —
    //         gr is their bundled ChaCha-style hash, ported 1:1 below)
    //    4. envelope {version, key_parts[30], iv, payload} → AES-256-GCM key =
    //       b64url(parts[version-1] + parts[(31-version)-1]) → {sources,tracks}
    //  Live-proven end-to-end 2026-08-01 (Naruto ep1 → 720p HLS master 200).
    // ════════════════════════════════════════════════════════════════════════
    internal object AniwavesResolver : AnimeSourceResolver {
        private const val SITE = "https://aniwaves.ru"
        private const val LABEL = "Aniwaves"
        private const val POW_DEADLINE_MS = 20_000L
        private val AJAX_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/home",
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
        )
        private val PAGE_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/home",
        )

        /** Byse (BYFMS) player API. Textbook port of videoPagesBundle-*.js. */
        private object Byse {
            private val EMBED_CODE_RE = Regex("""^(https?://[^/]+)/e/([A-Za-z0-9]+)""")

            // ── POW hash `gr` — ChaCha-style 512-word sponge, ported 1:1 ──
            private fun rotl(x: Int, r: Int): Int = (x shl r) or (x ushr (32 - r))

            @Suppress("LocalVariableName")
            private fun ye(t: IntArray) {
                t[0] = t[0] + t[1]; t[3] = rotl(t[3] xor t[0], 16)
                t[2] = t[2] + t[3]; t[1] = rotl(t[1] xor t[2], 12)
                t[0] = t[0] + t[1]; t[3] = rotl(t[3] xor t[0], 8)
                t[2] = t[2] + t[3]; t[1] = rotl(t[1] xor t[2], 7)
            }

            private fun gr(data: ByteArray): IntArray {
                val e = intArrayOf(0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt())
                for (b in data) {
                    e[0] = e[0] + (b.toInt() and 0xFF)
                    e[0] = rotl(e[0], 7)
                    ye(e)
                }
                repeat(8) { ye(e) }
                val r = IntArray(512)
                for (i in 0 until 512) { ye(e); r[i] = e[0] xor e[2] }
                repeat(2) {
                    for (s in 0 until 512) {
                        val a = r[s] and 511
                        var c = r[s] + r[a]
                        c = rotl(c, 13)
                        c = c xor (r[(s + 1) and 511] * 0x9E3779B1.toInt())
                        r[s] = c
                        e[0] = e[0] xor c
                        ye(e)
                    }
                }
                val o = 512 / 8
                val n = IntArray(8)
                for (i in 0 until 8) {
                    ye(e)
                    var s = e[0]
                    for (k in 0 until o) {
                        val d = r[i * o + k]
                        s += d
                        s = rotl(s, 5)
                        s = s xor (d * 0x85EBCA77.toInt())
                    }
                    n[i] = s xor e[2]
                }
                return n
            }

            private fun leadingZeroBits(words: IntArray): Int {
                var acc = 0
                for (w in words) {
                    if (w == 0) acc += 32 else return acc + Integer.numberOfLeadingZeros(w)
                }
                return acc
            }

            /** Their `Er` solver: smallest s with lzb(gr("nonce:s")) ≥ difficulty. */
            internal fun solvePow(nonce: String, difficulty: Int, deadlineMs: Long): String? {
                if (difficulty <= 0) return "0"
                val prefix = "$nonce:"
                val t0 = android.os.SystemClock.elapsedRealtime()
                var s = 0
                while (true) {
                    val d = gr((prefix + s).toByteArray(Charsets.US_ASCII))
                    if (leadingZeroBits(d) >= difficulty) return s.toString()
                    s++
                    if (s > 8_000_000 || android.os.SystemClock.elapsedRealtime() - t0 > deadlineMs) return null
                }
            }

            // ── ECDSA helpers ────────────────────────────────────────────
            private fun b64u(b: ByteArray): String =
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)

            private fun b64u32(v: java.math.BigInteger): String {
                val raw = v.toByteArray()
                val out = ByteArray(32)
                val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size)
                          else raw
                System.arraycopy(src, 0, out, 32 - src.size, src.size)
                return b64u(out)
            }

            /** DER (ASN.1 SEQ of two INTEGERs) → P1363 r‖s fixed 64 bytes. */
            internal fun derToP1363(der: ByteArray): ByteArray {
                var i = 0
                fun readLen(): Int {
                    var n = der[i++].toInt() and 0xFF
                    if (n and 0x80 != 0) {
                        val bytes = n and 0x7F
                        n = 0
                        repeat(bytes) { n = (n shl 8) or (der[i++].toInt() and 0xFF) }
                    }
                    return n
                }
                require(der[i++].toInt() == 0x30) { "bad DER: no sequence" }
                readLen() // total length
                val vals = Array(2) { ByteArray(0) }
                for (v in 0..1) {
                    require(der[i++].toInt() == 0x02) { "bad DER: no integer" }
                    val len = readLen()
                    var raw = der.copyOfRange(i, i + len); i += len
                    if (raw.size > 1 && raw[0].toInt() == 0) raw = raw.copyOfRange(1, raw.size)
                    vals[v] = raw
                }
                val out = ByteArray(64)
                for (v in 0..1) {
                    val src = vals[v]
                    val take = minOf(32, src.size)
                    System.arraycopy(src, src.size - take, out, v * 32 + (32 - take), take)
                }
                return out
            }

            internal fun b64d(s: String): ByteArray =
                java.util.Base64.getUrlDecoder().decode(s)

            /**
             * Full BYFMS chain for resolved embed `{host}/e/{code}`.
             * Returns decrypted playback JSON {sources, tracks, poster_url} or null.
             */
            internal suspend fun playbackSources(
                app: Requests,
                embedUrl: String,
                embedReferer: String,
            ): JSONObject? {
                val m = EMBED_CODE_RE.find(embedUrl) ?: return null
                val host = m.groupValues[1]
                val code = m.groupValues[2]
                fun hdrs(extra: Map<String, String> = emptyMap()): Map<String, String> =
                    mapOf(
                        "User-Agent" to UA,
                        "Referer" to embedReferer,
                        "X-Requested-With" to "XMLHttpRequest",
                        "X-Embed-Origin" to SITE,
                        "X-Embed-Referer" to embedReferer,
                        "X-Embed-Parent" to SITE,
                    ) + extra

                // 1+2 — device attestation (challenge → keygen → sign → attest)
                val chJson = runCatching {
                    app.post(
                        "$host/api/videos/access/challenge",
                        headers = hdrs(),
                        requestBody = "{}".toRequestBody("application/json".toMediaTypeOrNull()),
                        timeout = 15_000,
                    ).text
                }.getOrNull() ?: return null
                val ch = runCatching { JSONObject(chJson) }.getOrNull() ?: return null
                val challengeId = ch.optStringOrNull("challenge_id") ?: return null
                val nonce = ch.optStringOrNull("nonce") ?: return null

                val attestJson = runCatching {
                    val kp = java.security.KeyPairGenerator.getInstance("EC").apply {
                        initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
                    }.generateKeyPair()
                    val pub = kp.public as java.security.interfaces.ECPublicKey
                    val jwk = JSONObject()
                        .put("kty", "EC").put("crv", "P-256")
                        .put("x", b64u32(pub.w.affineX)).put("y", b64u32(pub.w.affineY))
                        .put("ext", true)
                        .put("key_ops", org.json.JSONArray().put("verify"))
                    val der = java.security.Signature.getInstance("SHA256withECDSA").run {
                        initSign(kp.private)
                        update(nonce.toByteArray(Charsets.US_ASCII))
                        sign()
                    }
                    val sigB64 = b64u(derToP1363(der))
                    val body = JSONObject()
                        .put("viewer_id", "").put("device_id", "")
                        .put("challenge_id", challengeId).put("nonce", nonce)
                        .put("signature", sigB64)
                        .put("public_key", jwk)
                        .put("client", JSONObject()
                            .put("user_agent", UA)
                            .put("languages", org.json.JSONArray().put("en-US").put("en"))
                            .put("timezone", "America/New_York")
                            .put("hardware_concurrency", 8)
                            .put("pixel_ratio", 2.75)
                            .put("screen_width", 1080).put("screen_height", 2400)
                            .put("color_depth", 24)
                            .put("mobile", true).put("platform", "Android")
                            .put("brands", org.json.JSONArray().put(
                                JSONObject().put("brand", "Chromium").put("version", "124")
                            ))
                        )
                        .put("storage", JSONObject())
                        .put("attributes", JSONObject().put("entropy", "low"))
                    app.post(
                        "$host/api/videos/access/attest",
                        headers = hdrs(),
                        requestBody = body.toString()
                            .toRequestBody("application/json".toMediaTypeOrNull()),
                        timeout = 15_000,
                    ).text
                }.getOrNull() ?: return null
                val att = runCatching { JSONObject(attestJson) }.getOrNull() ?: return null
                val devToken = att.optStringOrNull("token") ?: return null
                val viewerId = att.optStringOrNull("viewer_id") ?: ""
                val deviceId = att.optStringOrNull("device_id") ?: ""

                val cookie = "byse_viewer_id=$viewerId; byse_device_id=$deviceId"
                suspend fun postPlayback(capToken: String?): JSONObject? {
                    val body = "{\"fingerprint\":{\"token\":" + JSONObject.quote(devToken) + "}}"
                    val h = hdrs(mapOf("Cookie" to cookie)) +
                        (capToken?.let { mapOf("X-Captcha-Token" to it) } ?: emptyMap())
                    val txt = runCatching {
                        app.post(
                            "$host/api/videos/$code/embed/playback",
                            headers = h,
                            requestBody = body.toRequestBody("application/json".toMediaTypeOrNull()),
                            timeout = 15_000,
                        ).text
                    }.getOrNull() ?: return null
                    return runCatching { JSONObject(txt) }.getOrNull()
                }

                // 3 — playback (POW branch only when the site demands a captcha)
                var pb = postPlayback(null)
                if (pb?.optStringOrNull("error") == "captcha_required") {
                    val capJson = runCatching {
                        app.post(
                            "$host/api/videos/$code/embed/captcha",
                            headers = hdrs(mapOf("Cookie" to cookie)),
                            requestBody = "{}".toRequestBody("application/json".toMediaTypeOrNull()),
                            timeout = 15_000,
                        ).text
                    }.getOrNull() ?: return null
                    val cap = runCatching { JSONObject(capJson) }.getOrNull() ?: return null
                    val powNonce = cap.optStringOrNull("pow_nonce") ?: return null
                    val powDiff = cap.optInt("pow_difficulty", 0)
                    val powToken = cap.optStringOrNull("pow_token") ?: return null
                    val solution = solvePow(powNonce, powDiff, POW_DEADLINE_MS)
                        ?: return null
                    val verJson = runCatching {
                        app.post(
                            "$host/api/videos/$code/embed/captcha/verify",
                            headers = hdrs(mapOf("Cookie" to cookie)),
                            requestBody = JSONObject()
                                .put("pow_token", powToken)
                                .put("solution", solution)
                                .toString()
                                .toRequestBody("application/json".toMediaTypeOrNull()),
                            timeout = 15_000,
                        ).text
                    }.getOrNull() ?: return null
                    val ver = runCatching { JSONObject(verJson) }.getOrNull() ?: return null
                    if (ver.optStringOrNull("status") != "ok") return null
                    val capToken = ver.optStringOrNull("token") ?: return null
                    pb = postPlayback(capToken)
                }
                val env = pb?.optJSONObject("playback") ?: return null

                // 4 — AES-256-GCM envelope decrypt (their La/ws/ks):
                //     key = b64d(parts[v-1] ++ parts[(31-v)-1]), v = envelope version
                val partsArr = env.optJSONArray("key_parts") ?: return null
                val version = env.optStringOrNull("version")?.trim()?.toIntOrNull() ?: return null
                val b = 31 - version
                val count = partsArr.length()
                if (version < 1 || b < 1 || version > count || b > count) return null
                val key = runCatching {
                    b64d(partsArr.optString(version - 1)) + b64d(partsArr.optString(b - 1))
                }.getOrNull() ?: return null
                val iv = runCatching { b64d(env.optStringOrNull("iv") ?: return null) }
                    .getOrNull() ?: return null
                val ct = runCatching { b64d(env.optStringOrNull("payload") ?: return null) }
                    .getOrNull() ?: return null
                val plain = runCatching {
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        javax.crypto.Cipher.DECRYPT_MODE,
                        javax.crypto.spec.SecretKeySpec(key, "AES"),
                        javax.crypto.spec.GCMParameterSpec(128, iv),
                    )
                    cipher.doFinal(ct)
                }.getOrNull() ?: return null
                return runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull()
            }
        }

        override suspend fun resolve(
            app: Requests,
            title: String,
            altTitle: String?,
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

            // ── 1. Search (suggest endpoint first, /filter grid as widening) ──
            data class Hit(val slug: String, val en: String, val jp: String)
            fun bestOf(hits: List<Hit>): Hit? {
                fun sim(h: Hit) = maxOf(
                    bestTitleSim(h.en, title, altTitle),
                    bestTitleSim(h.jp, title, altTitle),
                )
                return hits.firstOrNull {
                    matchesEitherTitle(it.en, title, altTitle) ||
                        matchesEitherTitle(it.jp, title, altTitle)
                } ?: hits.maxByOrNull { sim(it) }?.takeIf { sim(it) >= 0.5 }
            }
            suspend fun searchSuggest(q: String): List<Hit> {
                val txt = runCatching {
                    app.get(
                        "$SITE/ajax/anime/search?keyword=${encodeUrl(q)}",
                        headers = AJAX_HEADERS, timeout = 12_000,
                    ).text
                }.getOrNull() ?: return emptyList()
                val html = runCatching { JSONObject(txt) }
                    .getOrNull()?.optJSONObject("result")
                    ?.optStringOrNull("html") ?: return emptyList()
                return SUGGEST_ITEM_RE.findAll(html).mapNotNull { m ->
                    val slug = m.groupValues[1].trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val jp = unescapeHtml(m.groupValues[2])
                    val en = unescapeHtml(m.groupValues[3])
                    if (en.isBlank() && jp.isBlank()) null else Hit(slug, en, jp)
                }.toList()
            }
            suspend fun searchFilter(q: String): List<Hit> {
                val html = runCatching {
                    app.get(
                        "$SITE/filter?keyword=${encodeUrl(q)}",
                        headers = PAGE_HEADERS, timeout = 12_000,
                    ).text
                }.getOrNull() ?: return emptyList()
                return FILTER_ITEM_RE.findAll(html).mapNotNull { m ->
                    val slug = m.groupValues[1].trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val jp = unescapeHtml(m.groupValues[2])
                    val en = unescapeHtml(m.groupValues[3])
                    if (en.isBlank() && jp.isBlank()) null else Hit(slug, en, jp)
                }.toList()
            }
            var best: Hit? = null
            for (q in listOfNotNull(title.takeIf { it.isNotBlank() },
                altTitle?.takeIf { !it.isNullOrBlank() && !it.equals(title, true) })) {
                best = bestOf(searchSuggest(q))
                if (best == null) best = bestOf(searchFilter(q))
                if (best != null) break
            }
            if (best == null) return false

            // ── 2. Episode list — anime id is the slug's trailing digits; the
            //       endpoint ignores vrf entirely (server-side no-op), but we
            //       still retry once with the page's data-id if the naive id
            //       ever stops working. ──
            suspend fun episodeHtml(animeId: String): String? {
                val txt = runCatching {
                    app.get(
                        "$SITE/ajax/episode/list/$animeId",
                        headers = AJAX_HEADERS, timeout = 12_000,
                    ).text
                }.getOrNull() ?: return null
                return runCatching { JSONObject(txt) }.getOrNull()
                    ?.takeIf { it.optInt("status", 0) == 200 }
                    ?.optStringOrNull("result")
            }
            var animeId = best.slug.substringAfterLast('-').takeIf { it.all { c -> c.isDigit() } } ?: ""
            var epHtml = if (animeId.isNotBlank()) episodeHtml(animeId) else null
            if (epHtml == null) {
                val watchPage = runCatching {
                    app.get("$SITE/watch/${best.slug}", headers = PAGE_HEADERS, timeout = 12_000).text
                }.getOrNull() ?: return false
                animeId = Regex("""data-id="(\d+)"""").find(watchPage)
                    ?.groupValues?.getOrNull(1) ?: return false
                epHtml = episodeHtml(animeId) ?: return false
            }
            val epA = EP_ITEM_RE.findAll(epHtml).firstOrNull { m ->
                m.groupValues[2].toDoubleOrNull()?.let { it == epToUse.toDouble() } == true
            } ?: run {
                Log.d(TAG, "aniwaves: ep $epToUse not in list for ${best.slug} (id=$animeId)")
                return false
            }
            val ids = unescapeHtml(epA.groupValues[1]) // "{animeId}&eps={n}"
            val epNumLabel = epA.groupValues[2].toDoubleOrNull()
                ?.let { if (it == kotlin.math.floor(it)) it.toInt().toString() else it.toString() }
                ?: epToUse.toString()

            // ── 3. Server list (sub + dub blocks). `ids` is already
            //       "{animeId}&eps={n}" — the site appends it RAW to the query
            //       (encoding & would collapse eps into the servers value). ──
            val srvTxt = runCatching {
                app.get(
                    "$SITE/ajax/server/list?servers=$ids",
                    headers = AJAX_HEADERS, timeout = 12_000,
                ).text
            }.getOrNull() ?: return false
            val srvHtml = runCatching { JSONObject(srvTxt) }.getOrNull()
                ?.takeIf { it.optInt("status", 0) == 200 }
                ?.optStringOrNull("result") ?: return false

            data class Server(val kind: String, val svId: Int, val linkId: String, val name: String)
            val servers = mutableListOf<Server>()
            for (block in SERVER_BLOCK_RE.findAll(srvHtml)) {
                val kind = block.groupValues[1].uppercase()
                for (li in SERVER_ITEM_RE.findAll(block.groupValues[2])) {
                    servers += Server(
                        kind = kind,
                        svId = li.groupValues[1].toIntOrNull() ?: -1,
                        linkId = li.groupValues[2],
                        name = li.groupValues[3].trim(),
                    )
                }
            }
            if (servers.isEmpty()) return false

            // ── 4. Resolve each server: BYFMS = full Byse pipeline; others =
            //       opportunistic loadExtractor on the embed URL. ──
            val cands = mutableListOf<MediaCandidate>()
            val seenSubs = mutableSetOf<String>()
            var any = false
            for (srv in servers) {
                val label = "$srcLabel · ${srv.name} · ${srv.kind}"
                val srcJson = runCatching {
                    app.get(
                        "$SITE/ajax/sources?id=${encodeUrl(srv.linkId)}&asi=1&autoPlay=0",
                        headers = AJAX_HEADERS, timeout = 12_000,
                    ).text
                }.getOrNull() ?: continue
                val result = runCatching { JSONObject(srcJson) }.getOrNull()
                    ?.takeIf { it.optInt("status", 0) == 200 }
                    ?.optJSONObject("result") ?: continue

                // Direct sources array (some servers ship straight files).
                val direct = result.optJSONArray("sources")
                if (direct != null && direct.length() > 0) {
                    (0 until direct.length()).forEach { i ->
                        val o = direct.optJSONObject(i) ?: return@forEach
                        val u = o.optStringOrNull("file") ?: o.optStringOrNull("url")
                            ?: return@forEach
                        cands += MediaCandidate(
                            url = u, sourceLabel = label, name = label,
                            referer = "$SITE/",
                            quality = qualityFromLabel(o.optStringOrNull("label") ?: u),
                            forceHls = u.contains(".m3u8", true),
                        )
                    }
                }
                val tracks = result.optJSONArray("tracks")
                if (tracks != null) {
                    (0 until tracks.length()).forEach { i ->
                        val o = tracks.optJSONObject(i) ?: return@forEach
                        val u = o.optStringOrNull("file") ?: o.optStringOrNull("url")
                            ?: return@forEach
                        val tLabel = o.optStringOrNull("label") ?: o.optStringOrNull("lang") ?: "Sub"
                        if (seenSubs.add(u)) subtitleCallback(SubtitleFile(tLabel, u))
                    }
                }

                val embed = result.optStringOrNull("url") ?: continue
                // BYFMS only — sv 4 (echovideo blob) and sv 2 (DGHG Dood)
                // have /e/{code}-shaped URLs too but are NOT Byse backends;
                // those fall through to loadExtractor below.
                if (srv.svId == 1 || srv.name.equals("BYFMS", ignoreCase = true)) {
                    // BYFMS — full attested playback chain.
                    val info = runCatching {
                        Byse.playbackSources(app, embed, "$SITE/watch/${best.slug}/ep-$epNumLabel")
                    }.getOrNull()
                    val bSources = info?.optJSONArray("sources")
                    var got = false
                    if (bSources != null) {
                        (0 until bSources.length()).forEach { i ->
                            val o = bSources.optJSONObject(i) ?: return@forEach
                            val u = o.optStringOrNull("url") ?: return@forEach
                            got = true
                            cands += MediaCandidate(
                                url = u, sourceLabel = label, name = label,
                                referer = embed,
                                quality = o.optInt("height", 0).takeIf { it > 0 }
                                    ?: qualityFromLabel(o.optStringOrNull("label") ?: u),
                                forceHls = true,
                            )
                        }
                    }
                    val bTracks = info?.optJSONArray("tracks")
                    if (bTracks != null) {
                        (0 until bTracks.length()).forEach { i ->
                            val o = bTracks.optJSONObject(i) ?: return@forEach
                            val u = o.optStringOrNull("url") ?: return@forEach
                            val tl = o.optStringOrNull("title") ?: o.optStringOrNull("language") ?: "Sub"
                            if (seenSubs.add(u)) subtitleCallback(SubtitleFile(tl, u))
                        }
                    }
                    if (!got) Log.d(TAG, "aniwaves: byse chain dry for $label")
                } else {
                    // Vidplay (echovideo blob) / DGHG (Dood+Turnstile) — give
                    // Cloudstream's built-in extractors the embed URL, quiet on miss.
                    runCatching {
                        loadExtractor(embed, "$SITE/", subtitleCallback) { link ->
                            callback(link.relabel(label, "$label — ${link.name}"))
                            any = true
                        }
                    }
                }
            }
            if (emitMediaCandidates(cands, subtitleCallback, callback)) any = true
            if (any) Log.d(TAG, "aniwaves: served ${best.slug} ep=$epNumLabel sv=${servers.size}")
            return any
        }

        private val SUGGEST_ITEM_RE = Regex(
            """<a class="item" href="/watch/([^"]+)">.*?<div class="name d-title" data-jp="([^"]*)">([^<]*)</div>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val FILTER_ITEM_RE = Regex(
            """<a class="name d-title" href="/watch/([^"]+)" data-jp="([^"]*)">([^<]*)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val EP_ITEM_RE = Regex(
            """<a href="/watch/[^"]*" data-ids="([^"]+)" data-num="([^"]+)"""",
        )
        private val SERVER_BLOCK_RE = Regex(
            """<div class="type" data-type="(sub|dub)"[^>]*>(.*?)(?=<div class="type" data-type=|$)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val SERVER_ITEM_RE = Regex(
            """<li[^>]*data-sv-id="(\d+)"[^>]*data-link-id="([^"]+)"[^>]*>\s*([^<]+?)\s*</li>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private fun unescapeHtml(s: String): String = s
            .replace("&amp;", "&").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
            .trim()
    }


    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 5: Anikoto  (https://anikototv.to)   — v92
    //
    //  Rank #1 on everythingmoe.com's anime list. Same "aniwave-template"
    //  family as Aniwaves but WITHOUT the VRF step; live-verified end to end:
    //    GET /ajax/anime/search?keyword={q}   → {result:{html: <a class="item"
    //                                             href="https://anikototv.to/watch/{slug}">…}}
    //    GET /watch/{slug}                    → data-id="{numeric show id}"
    //    GET /ajax/episode/list/{id}          → {result: html}  ep anchors carry
    //                                             data-num + data-ids="{230-char blob}"
    //    GET /ajax/server/list?servers={blob} → {result: html}  rows grouped in
    //                                             <div class="type" data-type="sub|hsub">
    //                                             as <li data-sv-id=… data-link-id=…>NAME</li>
    //    GET /ajax/server?get={link-id}       → {result:{url:"https://megaplay.buzz/stream/s-N/{mid}/{type}"}}
    //    GET https://megaplay.buzz/stream/getSources?id={mid}   (Referer = the
    //        /stream page)                    → {sources:{file: m3u8}, tracks:[…]}
    //  The .m3u8 sits on megap.kotocdn.site and is Referer/Origin-gated to
    //  https://megaplay.buzz — the emitted link carries both headers (same
  //  truth as the AnimeX yuki rows, covered by the header-parity engine).
    //  Non-MegaPlay server urls (Vidstream-2, VidPlay-1…) go through
    //  Cloudstream's own extractor registry.
    // ════════════════════════════════════════════════════════════════════════

    internal object AnikotoResolver : AnimeSourceResolver {
        private const val SITE = "https://anikototv.to"
        private const val LABEL = "Anikoto"
        private val AJAX_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/home",
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
        )
        private val PAGE_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/home",
        )

        private val SUGGEST_ITEM_RE = Regex(
            """<a class="item" href="(?:https?://anikototv\.to)?/watch/([^"]+)"[^>]*>.*?<div class="name d-title" data-jp="([^"]*)">([^<]*)</div>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val SHOW_ID_RE = Regex("""data-id="(\d+)"""")
        private val EP_ANCHOR_RE = Regex("""<a href="#"([^>]*)>""")
        private val ATTR_NUM_RE = Regex("""data-num="(\d+)"""")
        private val ATTR_IDS_RE = Regex("""data-ids="([^"]+)"""")
        private val SERVER_TYPE_BLOCK_RE = Regex(
            """<div class="type" data-type="(sub|dub|hsub)"[^>]*>(.*?)(?=<div class="type" data-type=|$)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val SERVER_ROW_RE = Regex(
            """<li[^>]*data-sv-id="([^"]+)"[^>]*data-link-id="([^"]+)"[^>]*>(.*?)</li>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val MP_ID_RE = Regex("""/(\d+)/(?:sub|hsub|dub)""")
        private val TAG_RE = Regex("""<[^>]+>""")
        private fun unescapeHtml(s: String): String = s
            .replace("&amp;", "&").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
            .trim()

        override suspend fun resolve(
            app: Requests,
            title: String,
            altTitle: String?,
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

            // 1. Ajax search → /watch/{slug} (both title aliases).
            data class Hit(val slug: String, val title: String)
            suspend fun doSearch(q: String): List<Hit> {
                val body = runCatching {
                    app.get(
                        "$SITE/ajax/anime/search?keyword=${encodeUrl(q)}",
                        headers = AJAX_HEADERS, timeout = 12_000,
                    ).text
                }.getOrNull() ?: return emptyList()
                val html = runCatching { JSONObject(body) }
                    .getOrNull()?.optJSONObject("result")
                    ?.optStringOrNull("html") ?: return emptyList()
                return SUGGEST_ITEM_RE.findAll(html).mapNotNull { m ->
                    val slug = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val en = unescapeHtml(m.groupValues.getOrNull(3).orEmpty())
                    val jp = unescapeHtml(m.groupValues.getOrNull(2).orEmpty())
                    Hit(slug, en.ifBlank { jp })
                }.toList()
            }
            fun List<Hit>.pickBest() = firstOrNull { matchesEitherTitle(it.title, title, altTitle) }
                ?: maxByOrNull { bestTitleSim(it.title, title, altTitle) }
                    ?.takeIf { bestTitleSim(it.title, title, altTitle) >= 0.5 }
            var best = doSearch(title).pickBest()
            if (best == null && !altTitle.isNullOrBlank() && !altTitle.equals(title, true)) {
                best = doSearch(altTitle).pickBest()
            }
            if (best == null) return false

            // 2. Watch page → NUMERIC show id (the slug's alnum suffix is NOT it).
            val watchUrl = "$SITE/watch/${best.slug}"
            val watchHtml = runCatching {
                app.get(watchUrl, headers = PAGE_HEADERS, timeout = 15_000).text
            }.getOrNull() ?: return false
            val showId = SHOW_ID_RE.find(watchHtml)?.groupValues?.getOrNull(1)
                ?: return false

            // 3. Episode list → this episode's opaque data-ids blob.
            val epsHtml = runCatching {
                JSONObject(
                    app.get(
                        "$SITE/ajax/episode/list/$showId",
                        headers = AJAX_HEADERS, timeout = 12_000,
                    ).text
                ).optStringOrNull("result")
            }.getOrNull() ?: return false
            val idsBlob = EP_ANCHOR_RE.findAll(epsHtml).firstNotNullOfOrNull { m ->
                val attrs = m.groupValues[1]
                val num = ATTR_NUM_RE.find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (num == epToUse) ATTR_IDS_RE.find(attrs)?.groupValues?.getOrNull(1)
                else null
            } ?: return false

            // 4. Server list for this episode.
            val serversHtml = runCatching {
                JSONObject(
                    app.get(
                        "$SITE/ajax/server/list?servers=${encodeUrl(idsBlob)}",
                        headers = AJAX_HEADERS, timeout = 12_000,
                    ).text
                ).optStringOrNull("result")
            }.getOrNull() ?: return false
            data class Srv(val name: String, val linkId: String, val kind: String)
            val servers = mutableListOf<Srv>()
            for (bm in SERVER_TYPE_BLOCK_RE.findAll(serversHtml)) {
                val kind = when (bm.groupValues[1].lowercase()) {
                    "dub" -> "DUB"
                    "hsub" -> "Hardsub"
                    else -> "SUB"
                }
                for (rm in SERVER_ROW_RE.findAll(bm.groupValues[2])) {
                    val name = unescapeHtml(TAG_RE.replace(rm.groupValues[3], ""))
                        .ifBlank { "Server" }
                    servers += Srv(name, rm.groupValues[2], kind)
                }
            }
            if (servers.isEmpty()) return false

            // 5. Resolve each server row into a playable candidate.
            val cands = mutableListOf<MediaCandidate>()
            val seenSubs = mutableSetOf<String>()
            var any = false
            for (srv in servers.distinctBy { it.linkId }.take(5)) {
                val resJson = runCatching {
                    JSONObject(
                        app.get(
                            "$SITE/ajax/server?get=${encodeUrl(srv.linkId)}",
                            headers = AJAX_HEADERS, timeout = 12_000,
                        ).text
                    )
                }.getOrNull() ?: continue
                if (resJson.optInt("status", 0) != 200) continue
                val streamUrl = resJson.optJSONObject("result")
                    ?.optStringOrNull("url") ?: continue
                val label = "$srcLabel · ${srv.name} · ${srv.kind}"
                val host = runCatching {
                    java.net.URI(streamUrl).host?.removePrefix("www.") ?: ""
                }.getOrDefault("")

                if (host.contains("megaplay.buzz")) {
                    val mid = MP_ID_RE.find(streamUrl)?.groupValues?.getOrNull(1)
                    if (mid == null) {
                        runCatching {
                            loadExtractor(streamUrl, watchUrl, subtitleCallback) { link ->
                                callback(link.relabel(label, "$label — ${link.name}"))
                                any = true
                            }
                        }
                        continue
                    }
                    val mpHeaders = mapOf(
                        "User-Agent" to UA,
                        "Referer" to streamUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                    )
                    val mpJson = runCatching {
                        app.get(
                            "https://megaplay.buzz/stream/getSources?id=$mid",
                            headers = mpHeaders, timeout = 12_000,
                        ).text
                    }.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val file = mpJson?.optJSONObject("sources")?.optStringOrNull("file")
                        ?: mpJson?.optJSONArray("sources")?.optJSONObject(0)
                            ?.optStringOrNull("file")
                    if (file != null) {
                        mpJson?.optJSONArray("tracks")?.let { arr ->
                            for (i in 0 until arr.length()) {
                                val t = arr.optJSONObject(i) ?: continue
                                val u = t.optStringOrNull("file") ?: continue
                                if (!u.startsWith("http")) continue
                                val lang = t.optStringOrNull("label") ?: "Subtitle"
                                if (seenSubs.add(u)) subtitleCallback(SubtitleFile(lang, u))
                            }
                        }
                        cands += MediaCandidate(
                            url = file,
                            sourceLabel = label, name = label,
                            referer = streamUrl,
                            headers = mapOf(
                                "Referer" to streamUrl,
                                "Origin" to "https://megaplay.buzz",
                            ),
                        )
                    }
                } else {
                    runCatching {
                        loadExtractor(streamUrl, watchUrl, subtitleCallback) { link ->
                            callback(link.relabel(label, "$label — ${link.name}"))
                            any = true
                        }
                    }
                }
            }
            if (emitMediaCandidates(cands, subtitleCallback, callback)) any = true
            if (any) Log.d(TAG, "anikoto: served ${best.slug} ep=$epToUse sv=${servers.size}")
            return any
        }
    }


    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 6: AniZone  (https://anizone.to)   — v92
    //
    //  Rank #7 on everythingmoe.com. Laravel+Livewire, self-hosted multi-
    //  audio HLS. Live-verified end to end:
    //    GET /anime                → meta[name=csrf-token] + pages.anime-index
    //                                Livewire wire:snapshot (search model)
    //    POST /livewire/update     (X-CSRF-TOKEN + session Cookie — BOTH are
    //                                required; either one alone = 419)
    //         {components:[{calls:[],snapshot,updates:{search:q}}]}
    //                           → effects.html cards: getTitle(this.anmTitles,
    //                               '<en title>') … wire:key="a-<id8>"
    //    GET /anime/<id8>          → episode hrefs /anime/<id8>/<num>
    //    GET /anime/<id8>/<num>    → <media-player src="…/master.m3u8"> plus
    //                                <track …ass/srt> subtitles (EN forced,
    //                                EN, SDH + 8 more languages)
    //  The m3u8 (suzaku.xin-cdn.xyz) is a master with ja+en audio groups and
    //  360p→1080p video ladders; cookies/csrf ride ONE fresh session per
    //  resolve (no jar kept between resolves).
    // ════════════════════════════════════════════════════════════════════════

    internal object AnizoneResolver : AnimeSourceResolver {
        private const val SITE = "https://anizone.to"
        private const val LABEL = "AniZone"
        private val PAGE_HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )

        private val CSRF_RE = Regex("""<meta name="csrf-token" content="([^"]+)"""")
        private val SNAPSHOT_RE = Regex("""wire:snapshot="([^"]+)"""")
        private val CARD_RE = Regex(
            """getTitle\(this\.anmTitles, '((?:[^'\\]|\\.)*)'\)[\s\S]{0,400}?wire:key="a-([a-z0-9]{8})"""",
        )
        private val UNICODE_ESC_RE = Regex("""\\u([0-9a-fA-F]{4})""")
        private val EP_HREF_RE_TEMPLATE = """/anime/%s/(\d+)"""
        private val MEDIA_PLAYER_RE = Regex("""<media-player[^>]*src="([^"]+)"""")
        private val TRACK_RE = Regex("""<track\s+([^>]+?)/?\s*>""")

        private fun String.unescUni(): String =
            UNICODE_ESC_RE.replace(this) { m ->
                m.groupValues[1].toIntOrNull(16)?.let { Char(it).toString() } ?: m.value
            }.replace("\\'", "'")

        private fun attrVal(attrs: String, name: String): String? {
            val q = Regex("""$name\s*=\s*"([^"]*)"""").find(attrs)
            if (q != null) return q.groupValues[1]
            return Regex("""$name\s*=\s*([^\s>]+)""").find(attrs)?.groupValues?.getOrNull(1)
        }

        override suspend fun resolve(
            app: Requests,
            title: String,
            altTitle: String?,
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

            // Livewire needs a cookie-backed session + matching CSRF token on
            // the SAME request pair: raw OkHttp, no jar, manual Cookie header.
            val client = OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(16, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

            var cookieHeader = ""
            var csrf = ""
            var snapshot = ""
            runCatching {
                val rb = Request.Builder().url("$SITE/anime")
                PAGE_HEADERS.forEach { (k, v) -> rb.header(k, v) }
                client.newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return false
                    cookieHeader = resp.headers("Set-Cookie").mapNotNull {
                        it.substringBefore(";").takeIf { kv -> kv.contains("=") }
                    }.joinToString("; ")
                    val html = resp.body?.string().orEmpty()
                    csrf = CSRF_RE.find(html)?.groupValues?.getOrNull(1).orEmpty()
                    for (sm in SNAPSHOT_RE.findAll(html)) {
                        val raw = sm.groupValues[1]
                        if (raw.contains("anime-index")) {
                            snapshot = raw
                                .replace("&quot;", "\"")
                                .replace("&#039;", "'")
                                .replace("&amp;", "&")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                            break
                        }
                    }
                }
            }.onFailure { return false }
            if (csrf.isBlank() || snapshot.isBlank()) return false

            data class Hit(val id8: String, val title: String)
            fun doSearch(q: String): List<Hit> {
                val payload = JSONObject().put(
                    "components",
                    JSONArray().put(
                        JSONObject()
                            .put("calls", JSONArray())
                            .put("snapshot", snapshot)
                            .put("updates", JSONObject().put("search", q))
                    )
                ).toString()
                val body = payload.toRequestBody(
                    "application/json".toMediaTypeOrNull()
                )
                val rb = Request.Builder().url("$SITE/livewire/update").post(body)
                PAGE_HEADERS.forEach { (k, v) -> rb.header(k, v) }
                rb.header("Content-Type", "application/json")
                rb.header("X-CSRF-TOKEN", csrf)
                rb.header("X-Livewire", "true")
                rb.header("Referer", "$SITE/anime")
                if (cookieHeader.isNotBlank()) rb.header("Cookie", cookieHeader)
                val respBody = runCatching {
                    client.newCall(rb.build()).execute().use { resp ->
                        if (!resp.isSuccessful) null else resp.body?.string()
                    }
                }.getOrNull() ?: return emptyList()
                val effectsHtml = runCatching {
                    JSONObject(respBody).optJSONArray("components")
                        ?.optJSONObject(0)?.optJSONObject("effects")
                        ?.optStringOrNull("html")
                }.getOrNull() ?: return emptyList()
                return CARD_RE.findAll(effectsHtml).mapNotNull { m ->
                    val t = m.groupValues.getOrNull(1)?.unescUni()?.takeIf { it.isNotBlank() }
                    val id = m.groupValues.getOrNull(2)
                    if (t != null && !id.isNullOrBlank()) Hit(id, t) else null
                }.toList()
            }
            fun List<Hit>.pickBest() = firstOrNull { matchesEitherTitle(it.title, title, altTitle) }
                ?: maxByOrNull { bestTitleSim(it.title, title, altTitle) }
                    ?.takeIf { bestTitleSim(it.title, title, altTitle) >= 0.5 }
            var best = doSearch(title).pickBest()
            if (best == null && !altTitle.isNullOrBlank() && !altTitle.equals(title, true)) {
                best = doSearch(altTitle).pickBest()
            }
            if (best == null) return false

            // Series page: confirm the wanted episode row exists.
            val seriesUrl = "$SITE/anime/${best.id8}"
            val seriesHtml = runCatching {
                val rb = Request.Builder().url(seriesUrl)
                PAGE_HEADERS.forEach { (k, v) -> rb.header(k, v) }
                client.newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }.getOrNull() ?: return false
            val epRe = Regex(EP_HREF_RE_TEMPLATE.format(best.id8))
            val epNums = epRe.findAll(seriesHtml)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .toSortedSet()
            if (epNums.isNotEmpty() && epToUse !in epNums) return false

            // Episode page: direct master.m3u8 + subtitle tracks in markup.
            val epUrl = "$SITE/anime/${best.id8}/$epToUse"
            val epHtml = runCatching {
                val rb = Request.Builder().url(epUrl)
                PAGE_HEADERS.forEach { (k, v) -> rb.header(k, v) }
                client.newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }.getOrNull() ?: return false
            val master = MEDIA_PLAYER_RE.find(epHtml)?.groupValues?.getOrNull(1)
                ?.takeIf { it.startsWith("http") } ?: return false
            val seenSubs = mutableSetOf<String>()
            for (tm in TRACK_RE.findAll(epHtml)) {
                val attrs = tm.groupValues[1]
                val src = attrVal(attrs, "src")?.takeIf { it.startsWith("http") } ?: continue
                if (!src.contains("subtitles/", ignoreCase = true) &&
                    !src.contains(".ass", ignoreCase = true) &&
                    !src.contains(".srt", ignoreCase = true) &&
                    !src.contains(".vtt", ignoreCase = true)
                ) continue
                val lang = attrVal(attrs, "label")
                    ?: attrVal(attrs, "srclang")
                    ?: "Subtitle"
                if (seenSubs.add("$lang|$src")) subtitleCallback(SubtitleFile(lang, src))
            }
            val label = "$srcLabel · Suzaku · HLS"
            return emitMediaCandidates(
                listOf(
                    MediaCandidate(
                        url = master,
                        sourceLabel = label, name = label,
                        referer = epUrl, headers = PAGE_HEADERS,
                    )
                ),
                subtitleCallback, callback,
            )
        }
    }


    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 7: AnimeStream  (https://anime.uniquestream.net)   — v92
    //
    //  Rank #9 on everythingmoe.com ("AnimeStream"). Nuxt front over a clean
    //  open JSON API; live-verified to a playing master.m3u8 (bare-fetch 200):
    //    GET /api/v1/search?query={q}&t=all&limit=8
    //            → {series:[{content_id,title,image,seasons_count,…}]}
    //    GET /api/v1/series/{content_id}
    //            → {seasons:[{content_id,season_number,episode_count,mal_id}]}
    //    GET /api/v1/season/{season_id}/episodes?page=&limit=&order_by=asc
    //            → [{episode_number,content_id,title,audio_locales[…]}]
    //    GET /api/v1/episode/{ep_id}/media/hls/ja-JP
    //            → {hls:{playlist: "https://get.mediacache.cc/…/master.m3u8?sign=…"},
    //               versions:{hls:[{locale:"en-US",playlist:…}, …]}}
    //  One media call yields BOTH the original-audio master and every dub
  //  locale; we emit ja-JP as SUB and (when present) en-US as DUB. Signed
    //  playlists (get.mediacache.cc) play without cookies; Referer kept.
    // ════════════════════════════════════════════════════════════════════════

    internal object AnimeStreamResolver : AnimeSourceResolver {
        private const val SITE = "https://anime.uniquestream.net"
        private const val API = "$SITE/api/v1"
        private const val LABEL = "AnimeStream"
        private val JSON_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Accept" to "application/json, text/plain, */*",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            altTitle: String?,
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

            // 1. Search → series content_id.
            data class Hit(val cid: String, val title: String, val type: String)
            suspend fun doSearch(q: String): List<Hit> {
                val body = runCatching {
                    app.get(
                        "$API/search?query=${encodeUrl(q)}&t=all&limit=8&suggest=0",
                        headers = JSON_HEADERS, timeout = 12_000,
                    ).text
                }.getOrNull() ?: return emptyList()
                val arr = runCatching { JSONObject(body) }
                    .getOrNull()?.optJSONArray("series") ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val cid = o.optStringOrNull("content_id") ?: return@mapNotNull null
                    val t = o.optStringOrNull("title") ?: return@mapNotNull null
                    Hit(cid, t, o.optStringOrNull("type") ?: "show")
                }
            }
            fun List<Hit>.pickBest() = firstOrNull { matchesEitherTitle(it.title, title, altTitle) }
                ?: maxByOrNull { bestTitleSim(it.title, title, altTitle) }
                    ?.takeIf { bestTitleSim(it.title, title, altTitle) >= 0.5 }
            var best = doSearch(title).pickBest()
            if (best == null && !altTitle.isNullOrBlank() && !altTitle.equals(title, true)) {
                best = doSearch(altTitle).pickBest()
            }
            if (best == null) return false

            // 2. Season rows (season_number match preferred).
            data class Season(val cid: String, val num: Int)
            val seriesJson = runCatching {
                JSONObject(
                    app.get("$API/series/${best.cid}", headers = JSON_HEADERS, timeout = 12_000).text
                )
            }.getOrNull() ?: return false
            val seasonsArr = seriesJson.optJSONArray("seasons")
            val seasons = mutableListOf<Season>()
            if (seasonsArr != null) {
                for (i in 0 until seasonsArr.length()) {
                    val o = seasonsArr.optJSONObject(i) ?: continue
                    val cid = o.optStringOrNull("content_id") ?: continue
                    seasons += Season(cid, o.optInt("season_number", 1))
                }
            }
            val seasonToUse = when {
                seasons.isEmpty() -> return false
                isMovie -> seasons[0]
                season != null -> seasons.firstOrNull { it.num == season } ?: seasons[0]
                else -> seasons.firstOrNull { it.num == 1 } ?: seasons[0]
            }

            // 3. Episode rows (paged) → episode content_id.
            var epCid: String? = null
            var page = 1
            while (page <= 5 && epCid == null) {
                val epsBody = runCatching {
                    app.get(
                        "$API/season/${seasonToUse.cid}/episodes?page=$page&limit=300&order_by=asc",
                        headers = JSON_HEADERS, timeout = 12_000,
                    ).text
                }.getOrNull() ?: break
                val arr = runCatching { JSONArray(epsBody) }.getOrNull() ?: break
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val num = o.optDouble("episode_number", Double.NaN)
                    val cid = o.optStringOrNull("content_id") ?: continue
                    if (!num.isNaN() && num.toInt() == epToUse) { epCid = cid; break }
                }
                if (epCid == null && arr.length() < 300) break
                page++
            }
            if (epCid == null) return false

            // 4. Media call: original master + locale versions in ONE payload.
            val mediaJson = runCatching {
                JSONObject(
                    app.get(
                        "$API/episode/$epCid/media/hls/ja-JP",
                        headers = JSON_HEADERS, timeout = 12_000,
                    ).text
                )
            }.getOrNull() ?: return false
            val cands = mutableListOf<MediaCandidate>()
            val orig = mediaJson.optJSONObject("hls")?.optStringOrNull("playlist")
            if (orig != null) {
                val label = "$srcLabel · ja-JP · SUB"
                cands += MediaCandidate(
                    url = orig, sourceLabel = label, name = label,
                    referer = "$SITE/watch/$epCid", headers = JSON_HEADERS,
                )
            }
            // English dub rides versions.hls; other locales are skipped
            // (9+ near-duplicate rows would spam the link sheet).
            mediaJson.optJSONObject("versions")?.optJSONArray("hls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optStringOrNull("locale") != "en-US") continue
                    val pl = o.optStringOrNull("playlist") ?: continue
                    val label = "$srcLabel · en-US · DUB"
                    cands += MediaCandidate(
                        url = pl, sourceLabel = label, name = label,
                        referer = "$SITE/watch/$epCid", headers = JSON_HEADERS,
                    )
                }
            }
            val ok = emitMediaCandidates(cands, subtitleCallback, callback)
            if (ok) Log.d(TAG, "animestream: served ${best.cid} s=${seasonToUse.num} ep=$epToUse")
            return ok
        }
    }


    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 8: AniBD  (https://anibd.app)   — v92
    //
    //  Rank #16 on everythingmoe.com — the only BANGLADESHI entry (the same
    //  operator family runs several wpeng*.my3gg*.top fronts). WordPress on
    //  the front, but the useful bits are three open JSON APIs keyed directly
    //  by the AniList id we already hold:
    //    GET https://epeng.animeapps.top/api2.php?epid={anilistId}
    //            → [{id, server_name:"S-sub", server_data:[{name:"01", slug, link}]}]
    //    GET https://epeng.animeapps.top/apilink.php?data={link}
    //            → [{server:"SR"|"SB", link:"https://playeng.animeapps.top/…/play2.php?id=…"}]
    //  The play2.php player is Cloudflare-gated against datacenter IPs
  //  (sandboxed probes got a 301 to cloud.google.com), so the final HTML
    //  player fetch runs on the USER's device at resolve time: we parse its
    //  m3u8/mp4 with the shared scraper, recurse one nested iframe, and fall
  //  back to loadExtractor. If the trap still wins, the rows just vanish —
    //  no half-broken links. API chain itself is live-verified; player HTML
    //  could NOT be verified from the datacenter sandbox.
    // ════════════════════════════════════════════════════════════════════════

    internal object AnibdResolver : AnimeSourceResolver {
        private const val SITE = "https://anibd.app"
        private const val EP_API = "https://epeng.animeapps.top/api2.php?epid="
        private const val LINK_API = "https://epeng.animeapps.top/apilink.php?data="
        private const val LABEL = "AniBD"
        private val JSON_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Accept" to "application/json, text/plain, */*",
        )
        private val PAGE_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
        )
        private val IFRAME_SRC_RE = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        override suspend fun resolve(
            app: Requests,
            title: String,
            altTitle: String?,
            anilistId: Int?,
            malId: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            // Their episode API is keyed by AniList id and nothing else —
            // without it there is no viable path in.
            if (anilistId == null || anilistId <= 0) return false
            val srcLabel = "$labelPrefix • $LABEL"
            val epToUse = episode ?: 1

            val serversArr = runCatching {
                JSONArray(app.get("$EP_API$anilistId", headers = JSON_HEADERS, timeout = 12_000).text)
            }.getOrNull() ?: return false
            if (serversArr.length() == 0) return false

            data class Ep(val name: String, val slug: String, val link: String)
            data class Srv(val id: Int, val name: String, val kind: String, val eps: List<Ep>)
            val servers = mutableListOf<Srv>()
            for (i in 0 until serversArr.length()) {
                val sv = serversArr.optJSONObject(i) ?: continue
                val sdata = sv.optJSONArray("server_data") ?: continue
                val sName = sv.optStringOrNull("server_name") ?: "Server"
                val kind = if (sName.contains("dub", true)) "DUB" else "SUB"
                val eps = (0 until sdata.length()).mapNotNull { j ->
                    val e = sdata.optJSONObject(j) ?: return@mapNotNull null
                    val nm = e.optStringOrNull("name") ?: return@mapNotNull null
                    val sg = e.optStringOrNull("slug") ?: nm
                    val lk = e.optStringOrNull("link") ?: return@mapNotNull null
                    Ep(nm, sg, lk)
                }
                if (eps.isNotEmpty()) servers += Srv(sv.optInt("id", i), sName, kind, eps)
            }
            if (servers.isEmpty()) return false
            fun pickEp(eps: List<Ep>): Ep? {
                val byName = eps.firstOrNull { e ->
                    e.name.trimStart('0').ifBlank { "0" } == epToUse.toString() ||
                        e.slug.trimStart('0').ifBlank { "0" } == epToUse.toString()
                }
                return byName ?: eps.getOrNull(epToUse - 1) ?: eps.firstOrNull()
            }

            val cands = mutableListOf<MediaCandidate>()
            var any = false
            for (srv in servers.take(3)) {
                val ep = pickEp(srv.eps) ?: continue
                val linksArr = runCatching {
                    JSONArray(
                        app.get("$LINK_API${encodeUrl(ep.link)}", headers = JSON_HEADERS, timeout = 12_000).text
                    )
                }.getOrNull() ?: continue
                for (i in 0 until linksArr.length()) {
                    val o = linksArr.optJSONObject(i) ?: continue
                    val svName = o.optStringOrNull("server") ?: srv.name
                    val playUrl = o.optStringOrNull("link") ?: continue
                    if (!playUrl.startsWith("http")) continue
                    val label = "$srcLabel · $svName · ${srv.kind}"
                    // Final player html — Cloudflare may 301 us away from
                    // datacenters; on a real device this is the working path.
                    val playHtml = runCatching {
                        app.get(playUrl, headers = PAGE_HEADERS, timeout = 12_000).text
                    }.getOrNull()
                    var stream: String? = null
                    if (!playHtml.isNullOrBlank() &&
                        "cloud.google.com" !in playHtml && "Moved Permanently" !in playHtml
                    ) {
                        stream = findMediaUrlIn(playHtml)
                            ?: IFRAME_SRC_RE.find(playHtml)?.groupValues?.getOrNull(1)
                                ?.takeIf { it.startsWith("http") }
                                ?.let { inner ->
                                    runCatching {
                                        app.get(
                                            inner,
                                            headers = mapOf("User-Agent" to UA, "Referer" to playUrl),
                                            timeout = 12_000,
                                        ).text
                                    }.getOrNull()?.let { findMediaUrlIn(it) ?: inner.takeIf {
                                        h -> h.contains("stream") || h.contains("play") }
                                    }
                                }
                    }
                    if (!stream.isNullOrBlank()) {
                        cands += MediaCandidate(
                            url = stream, sourceLabel = label, name = label,
                            referer = playUrl, headers = PAGE_HEADERS,
                            forceHls = !stream.contains(".mp4", ignoreCase = true),
                        )
                    } else {
                        runCatching {
                            loadExtractor(playUrl, SITE, subtitleCallback) { link ->
                                callback(link.relabel(label, "$label — ${link.name}"))
                                any = true
                            }
                        }
                    }
                }
            }
            if (emitMediaCandidates(cands, subtitleCallback, callback)) any = true
            if (any) Log.d(TAG, "anibd: served anilist=$anilistId ep=$epToUse")
            return any
        }
    }


    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 9: AniDB.app  (https://anidb.app)   — v92
    //
    //  Rank #6 on everythingmoe.com (community clone of the classic catalog
    //  name, NOT anidb.net). Alpine front with open JSON endpoints, self-
    //  hosted HLS (hls.anidb.app) — live-verified to a playing master:
    //    GET /search/suggestions?q={q} → HTML cards <a href="/anime/{slug}-{numId}">
    //                                         with <img alt="{title}">
    //    GET /api/frontend/anime/{numId}/episodes → {episodes:[{id,number,…}]}
    //    GET /api/frontend/episode/{epId}/languages
    //        → {languages:[{code:"eng"|"jpn", name, embed_url:"https://anidb.app/embed/{token}"}]}
    //    GET embed_url                 → jwplayer setup.sources[0].file =
    //                                     "https://hls.anidb.app/stream/{token}/master.m3u8"
    //  english = DUB, japanese = SUB; both emitted when offered. The bare
    //  master fetch was 200 without cookies; Referer kept for safety.
    // ════════════════════════════════════════════════════════════════════════

    internal object AnidbResolver : AnimeSourceResolver {
        private const val SITE = "https://anidb.app"
        private const val LABEL = "AniDB"
        private val PAGE_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
        )
        private val JSON_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Accept" to "application/json, text/plain, */*",
        )

        private val CARD_RE = Regex(
            """<a href="(?:https?://anidb\.app)?/anime/([a-z0-9-]+?)-(\d+)"[^>]*>[\s\S]{0,400}?<img[^>]*alt="([^"]*)"""",
        )
        private val JW_FILE_RE = Regex("""file\s*:\s*'(https?://[^']+\.m3u8[^']*)'""")

        override suspend fun resolve(
            app: Requests,
            title: String,
            altTitle: String?,
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

            // 1. Suggestions search → numeric anime id.
            data class Hit(val slugId: String, val numId: String, val title: String)
            suspend fun doSearch(q: String): List<Hit> {
                val html = runCatching {
                    app.get(
                        "$SITE/search/suggestions?q=${encodeUrl(q)}",
                        headers = PAGE_HEADERS, timeout = 12_000,
                    ).text
                }.getOrNull() ?: return emptyList()
                return CARD_RE.findAll(html).mapNotNull { m ->
                    val slug = m.groupValues.getOrNull(1) ?: return@mapNotNull null
                    val num = m.groupValues.getOrNull(2) ?: return@mapNotNull null
                    val t = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
                        ?: slug.replace('-', ' ')
                    Hit("$slug-$num", num, t)
                }.toList()
            }
            fun List<Hit>.pickBest() = firstOrNull { matchesEitherTitle(it.title, title, altTitle) }
                ?: maxByOrNull { bestTitleSim(it.title, title, altTitle) }
                    ?.takeIf { bestTitleSim(it.title, title, altTitle) >= 0.5 }
            var best = doSearch(title).pickBest()
            if (best == null && !altTitle.isNullOrBlank() && !altTitle.equals(title, true)) {
                best = doSearch(altTitle).pickBest()
            }
            if (best == null) return false

            // 2. Episodes.
            val epsJson = runCatching {
                JSONObject(
                    app.get(
                        "$SITE/api/frontend/anime/${best.numId}/episodes",
                        headers = JSON_HEADERS, timeout = 12_000,
                    ).text
                )
            }.getOrNull() ?: return false
            val epsArr = epsJson.optJSONArray("episodes") ?: return false
            var epId: Int = -1
            for (i in 0 until epsArr.length()) {
                val o = epsArr.optJSONObject(i) ?: continue
                if (o.optInt("number", -1) == epToUse) { epId = o.optInt("id", -1); break }
            }
            if (epId <= 0) return false

            // 3. Language rows (eng → DUB, jpn → SUB).
            val langsJson = runCatching {
                JSONObject(
                    app.get(
                        "$SITE/api/frontend/episode/$epId/languages",
                        headers = JSON_HEADERS, timeout = 12_000,
                    ).text
                )
            }.getOrNull() ?: return false
            val langsArr = langsJson.optJSONArray("languages") ?: return false
            val seriesUrl = "$SITE/anime/${best.slugId}"
            val cands = mutableListOf<MediaCandidate>()
            for (i in 0 until langsArr.length()) {
                val o = langsArr.optJSONObject(i) ?: continue
                val code = o.optStringOrNull("code") ?: continue
                val langName = o.optStringOrNull("name") ?: code
                val embedUrl = o.optStringOrNull("embed_url") ?: continue
                val kind = when (code.lowercase()) {
                    "eng" -> "DUB"
                    "jpn" -> "SUB"
                    else -> langName.uppercase().take(3)
                }
                val embedHtml = runCatching {
                    app.get(
                        embedUrl,
                        headers = mapOf("User-Agent" to UA, "Referer" to seriesUrl),
                        timeout = 12_000,
                    ).text
                }.getOrNull() ?: continue
                val master = JW_FILE_RE.find(embedHtml)?.groupValues?.getOrNull(1) ?: continue
                val label = "$srcLabel · $langName · $kind"
                cands += MediaCandidate(
                    url = master, sourceLabel = label, name = label,
                    referer = embedUrl, headers = PAGE_HEADERS,
                )
            }
            val ok = emitMediaCandidates(cands, subtitleCallback, callback)
            if (ok) Log.d(TAG, "anidb.app: served ${best.slugId} ep=$epToUse")
            return ok
        }
    }

}

// ─────────────────────────────────────────────────────────────────────────────
//  Internal null-safe helpers for org.json
// ─────────────────────────────────────────────────────────────────────────────

internal fun JSONObject.optStringOrNull(k: String): String? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").trim().takeIf { it.isNotBlank() && it != "null" }
