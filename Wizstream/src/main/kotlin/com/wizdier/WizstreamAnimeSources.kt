package com.wizdier

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream

/**
 * WizstreamAnimeSources — bundled anime-specific source resolver.
 *
 * Adds 4 anime-focused streaming sources on top of the BDIX resolvers in
 * WizstreamSources:
 *
 *   1. AniZone     — https://anizone.to       (direct .m3u8 in <media-player>)
 *   2. Mkissa      — https://mkissa.to        (uses AllAnime API bypass)
 *   3. Miruro      — https://www.miruro.to    (secure-pipe API + AniList)
 *   4. AniChi      — https://anichi.to        (mapper.nekostream.site API)
 *
 * All four are invoked in parallel from `resolveAnime()`. Each returns
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

            // 1. Search via the /anime browse list (paginated). The /anime?q=
            //    search endpoint has a Cloudflare challenge; the /anime list
            //    page itself does not.
            val candidates = mutableListOf<Pair<String, String>>() // (8-char-id, title)
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

            return emitHlsOrMp4(
                url = mediaSrc,
                sourceLabel = srcLabel,
                referer = epUrl,
                headers = HEADERS,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
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
        private const val API = "https://api.allanime.day/api"
        private const val LABEL = "Mkissa"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "https://allmanga.to/",
            "Origin" to "https://allmanga.to",
            "Accept" to "application/json, text/plain, */*",
        )

        private const val HASH_SEARCH = "9343797cc3d9e3f444e2d3b7db9a84d759b816a4d84512ea72d079f85d5858fc"
        private const val HASH_SHOW = "73d998d209d6d8de325db91a8e3c363f8db0b4cd0e5e0eb28e939aef6c3c6a29"
        private const val HASH_EPISODE = "5f1a64b73793cc2234a389cf3a8f93ad82de7043017dd551f38f65b89daa65a0"

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

            // 1. Search AllAnime for the show ID.
            val searchVars = JSONObject().apply {
                put("search", JSONObject().apply {
                    put("allowAdult", false)
                    put("allowUnknown", false)
                    put("query", title)
                })
                put("limit", 20)
                put("page", 1)
                put("translationType", "sub")
                put("countryOrigin", "ALL")
            }
            val searchResp = apiGet(app, searchVars, HASH_SEARCH) ?: return false
            val edges = searchResp.optJSONObject("data")?.optJSONObject("shows")
                ?.optJSONArray("edges") ?: return false
            if (edges.length() == 0) return false

            // 2. Pick the best match.
            val candidates = (0 until edges.length()).mapNotNull { i ->
                val s = edges.optJSONObject(i) ?: return@mapNotNull null
                val id = s.optString("_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val t = s.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to t
            }
            val qNorm = title.normaliseTitle()
            val best = candidates.firstOrNull { (_, t) -> t.normaliseTitle() == qNorm }
                ?: candidates.maxByOrNull { (_, t) -> titleSimilarity(t, title) }
                ?: return false
            if (titleSimilarity(best.second, title) < 0.5) return false
            val showId = best.first

            // 3. Get the episode count (so we know which episode to request).
            val showVars = JSONObject().apply {
                put("showId", showId)
                put("translationType", "sub")
            }
            val showResp = apiGet(app, showVars, HASH_SHOW) ?: return false
            val showObj = showResp.optJSONObject("data")?.optJSONObject("show") ?: return false
            val lastEpStr = showObj.optJSONObject("lastEpisodeInfo")
                ?.optJSONObject("sub")?.optString("episodeString")?.ifBlank { null }
                ?: showObj.optInt("availableEpisodes", 1).toString()
            val maxEp = lastEpStr.toIntOrNull() ?: 1
            val epToUse = episode ?: 1
            if (epToUse > maxEp && maxEp > 0) return false

            // 4. Get stream URLs for the requested episode.
            val epVars = JSONObject().apply {
                put("showId", showId)
                put("translationType", "sub")
                put("episodeString", epToUse.toString())
            }
            val epResp = apiGet(app, epVars, HASH_EPISODE) ?: return false
            val epObj = epResp.optJSONObject("data")?.optJSONObject("episode") ?: return false
            val sourcesArr = epObj.optJSONArray("sourceUrls") ?: return false

            var any = false
            for (i in 0 until sourcesArr.length()) {
                val src = sourcesArr.optJSONObject(i) ?: continue
                val rawUrl = src.optString("sourceUrl").ifBlank { src.optString("url") }
                if (rawUrl.isBlank()) continue
                // AllAnime sometimes prefixes URLs with "encrypted://" — for
                // the public hashes we're using, most URLs are plaintext.
                val finalUrl = if (rawUrl.startsWith("encrypted://")) {
                    decryptAllAnimeUrl(rawUrl.removePrefix("encrypted://"))
                } else rawUrl
                if (finalUrl.isBlank()) continue
                val priority = src.optInt("priority", 0)
                val qLabel = if (priority > 0) "${priority}p" else null
                val name = if (qLabel != null) "$srcLabel — $qLabel" else "$srcLabel — Stream"
                val emitted = emitHlsOrMp4(
                    url = finalUrl,
                    sourceLabel = srcLabel,
                    referer = "https://allmanga.to/",
                    headers = HEADERS,
                    subtitleCallback = subtitleCallback,
                    callback = { link ->
                        callback(link.relabel(srcLabel, name))
                        any = true
                    },
                )
                if (emitted) any = true
            }
            return any
        }

        /**
         * AllAnime "encrypted://" URLs use a simple substitution cipher:
         * the URL is base64-encoded after applying a character rotation.
         * This is widely documented in open-source scrapers (Consumet, AMT).
         */
        private fun decryptAllAnimeUrl(enc: String): String {
            return runCatching {
                // The cipher uses these characters in order — the index of
                // each character in the encrypted string maps to the original
                val cipher = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
                val reversed = "abcdefghijklmnopqrstuvwxyz0123456789+ABCDEFGHIJKLMNOPQRSTUVWXYZ/"
                val mapped = enc.map { c ->
                    val idx = reversed.indexOf(c)
                    if (idx >= 0) cipher[idx] else c
                }.joinToString("")
                String(Base64.getDecoder().decode(mapped))
            }.getOrDefault("")
        }

        private suspend fun apiGet(
            app: Requests,
            variables: JSONObject,
            hash: String,
        ): JSONObject? {
            val ext = JSONObject().apply {
                put("persistedQuery", JSONObject().apply {
                    put("version", 1)
                    put("sha256Hash", hash)
                })
            }
            val url = "$API?variables=${encodeUrl(variables.toString())}" +
                "&extensions=${encodeUrl(ext.toString())}"
            val r = runCatching {
                app.get(url, headers = HEADERS, timeout = 12_000)
            }.getOrNull() ?: return null
            if (r.code !in 200..299 || r.text.isBlank()) return null
            return runCatching { JSONObject(r.text) }.getOrNull()
        }
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
            val tokens = mutableListOf<Pair<String, String>>() // (serverName, token)
            val keys = mapperJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == "status") continue
                val serverObj = mapperJson.optJSONObject(k) ?: continue
                val subObj = serverObj.optJSONObject("sub") ?: continue
                val token = subObj.optStringOrNull("url") ?: continue
                if (token.isNotBlank()) tokens += k to token
            }
            if (tokens.isEmpty()) return false

            // 5. For each token, call /ajax/server?get={token} to get the
            //    iframe URL. If it's a mewcdn.online URL with a base64
            //    fragment, decode it directly to a .m3u8.
            var any = false
            for ((serverName, token) in tokens) {
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
                        if (emitHlsOrMp4(
                                url = decoded,
                                sourceLabel = "$srcLabel — $serverName",
                                referer = iframeUrl,
                                headers = mapOf("Referer" to iframeUrl, "User-Agent" to UA),
                                subtitleCallback = subtitleCallback,
                                callback = callback,
                            )
                        ) any = true
                    }
                    continue
                }

                // Slow path: megaplay.buzz/stream/… — try the generic
                // extractor (Cloudstream's loadExtractor) which may or may
                // not support megaplay. If it doesn't, we silently skip.
                if (iframeUrl.startsWith("http")) {
                    runCatching {
                        loadExtractor(iframeUrl, "$SITE/", subtitleCallback) { link ->
                            callback(link.relabel("$srcLabel — $serverName", "$srcLabel — $serverName — ${link.name}"))
                            any = true
                        }
                    }
                }
            }
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
}

// ─────────────────────────────────────────────────────────────────────────────
//  Internal null-safe helpers for org.json
// ─────────────────────────────────────────────────────────────────────────────

internal fun JSONObject.optStringOrNull(k: String): String? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").trim().takeIf { it.isNotBlank() && it != "null" }
