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
 * Adds 3 anime-focused streaming sources on top of the BDIX resolvers in
 * WizstreamSources (v89 — user request: keep ONLY AniNeko from the old
 * roster, add KickAssAnime + AnimeX):
 *
 *   1. AniNeko      — https://anineko.to   (server-video embeds → direct HLS)
 *   2. KickAssAnime — https://kaa.lt        (open Nuxt JSON API → CatStream HLS)
 *   3. AnimeX       — https://animex.one    (GraphQL id-map + pp.animex.one HLS)
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
        ).filter { WizstreamSources.WizSourcePrefs.isEnabled(it.toggleId) }

        val gate = Semaphore(4)
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
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

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
}

// ─────────────────────────────────────────────────────────────────────────────
//  Internal null-safe helpers for org.json
// ─────────────────────────────────────────────────────────────────────────────

internal fun JSONObject.optStringOrNull(k: String): String? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").trim().takeIf { it.isNotBlank() && it != "null" }
