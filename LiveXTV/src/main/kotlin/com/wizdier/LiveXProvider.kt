package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.Requests
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

// ─────────────────────────────────────────────────────────────────────────────
// LiveXProvider — Cloudstream extension for https://livextv.live
//
// livextv.live is a free live-sports streaming front-end. Its catalogue and
// stream metadata come from a JSON REST API at `livextv-backend.onrender.com`:
//
//   GET /api/sports           → list of sport categories (15 categories)
//   GET /api/matches/all      → all matches (99+ at time of writing), each
//                                with a `sources[]` array of provider entries
//                                (admin, delta, echo, golf)
//   GET /api/matches/live     → currently-live matches
//   GET /api/stream/{src}/{id} → per-source stream list, returns array of:
//                                { id, streamNo, language, hd, embedUrl, source, viewers }
//
// The `embedUrl` points to `https://embed.st/embed/{src}/{id}/{streamNo}` —
// an obfuscated JW Player page that decodes a token at runtime and loads
// the actual m3u8 from `lbNN.strmd.st/secure/<token>/rtmp/stream/<slug>_<quality>/<n>/playlist.m3u8`.
//
// Architecture
// ------------
// • mainPage:        18 sections — Live Now · Popular · All Matches · plus
//                    one per sport category (15 categories from /api/sports).
// • search:          In-memory filter on the cached match list (title + category).
// • load:            Match info card (title, category, date, source count,
//                    stream breakdown by provider).
// • loadLinks:       Fetches /api/stream/{src}/{id} for every source the match
//                    exposes, then for each returned `embedUrl`:
//                      - Tries CloudStream's built-in `loadExtractor()` first
//                        (handles VidPlay, MegaCloud, Mp4Upload, etc., and
//                        will fetch the embed.st page and decode the m3u8
//                        via its iframe interceptor).
//                      - Falls back to surfacing the raw embedUrl as an
//                        iframe-style link so the user can open it in
//                        CloudStream's WebView if needed.
//
// Caching
// -------
// Match list cached for 5 minutes (TtlCache) — sports schedules change
// frequently but not minute-by-minute. Per-source stream lists are NOT
// cached because they include live viewer counts and may rotate.
// ─────────────────────────────────────────────────────────────────────────────
class LiveXProvider : MainAPI() {
    override var mainUrl = "https://livextv.live"
    override var name = "LiveXTV"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override var lang = "en"

    override val supportedTypes: Set<TvType> = setOf(TvType.Live)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

    private val apiUrl = "https://livextv-backend.onrender.com"

    private val client: Requests by lazy {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }
        val okBuilder = okhttp3.OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
        Requests(okBuilder.build())
    }

    // ─────────────────────────── HTTP helpers ────────────────────────────

    private suspend fun fetchJson(
        url: String,
        headers: Map<String, String> = baseHeaders,
        timeout: Long = 15_000L,
    ): JSONObject? = LiveXConcurrent.retry {
        val res = client.get(url, headers = headers, timeout = timeout)
        if (res.code !in 200..299 || res.text.isBlank()) return@retry null
        runCatching { JSONObject(res.text) }.getOrNull()
    }

    // ─────────────────────────── Match list ──────────────────────────────

    private val matchCache = LiveXConcurrent.TtlCache<String, List<Match>>(ttlMs = 5 * 60 * 1000L)
    private val sportsCache = LiveXConcurrent.TtlCache<String, List<Sport>>(ttlMs = 30 * 60 * 1000L)

    private suspend fun fetchMatches(): List<Match> {
        matchCache.get("all")?.let { return it }
        val json = fetchJson("$apiUrl/api/matches/all") ?: return emptyList()
        val arr = json.optJSONArray("data") ?: return emptyList()
        val matches = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.toMatch()
        }
        matchCache.put("all", matches)
        return matches
    }

    private suspend fun fetchLiveMatches(): List<Match> {
        val json = fetchJson("$apiUrl/api/matches/live") ?: return emptyList()
        val arr = json.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.toMatch()
        }
    }

    private suspend fun fetchSports(): List<Sport> {
        sportsCache.get("all")?.let { return it }
        val json = fetchJson("$apiUrl/api/sports") ?: return emptyList()
        val arr = json.optJSONArray("data") ?: return emptyList()
        val sports = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Sport(
                id = o.optString("id"),
                name = o.optString("name"),
            )
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        sportsCache.put("all", sports)
        return sports
    }

    private suspend fun fetchStreams(source: String, id: String): List<StreamEntry> {
        val json = fetchJson("$apiUrl/api/stream/$source/$id") ?: return emptyList()
        val arr = json.optJSONArray("data") ?: run {
            return@fetchStreams runCatching {
                JSONArray(json.toString())
            }.getOrNull()?.let { arr2 ->
                (0 until arr2.length()).mapNotNull { i ->
                    arr2.optJSONObject(i)?.toStreamEntry()
                }
            } ?: emptyList()
        }
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.toStreamEntry()
        }
    }

    // ─────────────────────────── Main page ───────────────────────────────

    override val mainPage = mainPageOf(
        "live" to "🔴 Live Now",
        "popular" to "⭐ Popular",
        "all" to "All Matches",
        "football" to "⚽ Football",
        "basketball" to "🏀 Basketball",
        "american-football" to "🏈 American Football",
        "hockey" to "🏒 Hockey",
        "baseball" to "⚾ Baseball",
        "motor-sports" to "🏎️ Motor Sports",
        "fight" to "🥊 Fight (UFC, Boxing)",
        "tennis" to "🎾 Tennis",
        "rugby" to "🏉 Rugby",
        "golf" to "⛳ Golf",
        "billiards" to "🎱 Billiards",
        "afl" to "🏈 AFL",
        "darts" to "🎯 Darts",
        "cricket" to "🏏 Cricket",
        "other" to "🎯 Other",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val data = request.data
        val items: List<SearchResponse>

        when {
            data == "live" -> {
                items = fetchLiveMatches().map { it.toSearchResponse() }
                return newHomePageResponse(
                    HomePageList(request.name, items, isHorizontalImages = true),
                    hasNext = false,
                )
            }
            data == "all" -> {
                val all = fetchMatches()
                val perPage = 60
                val from = (page - 1) * perPage
                val to = minOf(from + perPage, all.size)
                val pageItems = if (from < all.size) all.subList(from, to) else emptyList()
                return newHomePageResponse(
                    HomePageList(request.name, pageItems.map { it.toSearchResponse() }, isHorizontalImages = true),
                    hasNext = to < all.size,
                )
            }
            data == "popular" -> {
                items = fetchMatches()
                    .filter { it.popular }
                    .map { it.toSearchResponse() }
                return newHomePageResponse(
                    HomePageList(request.name, items, isHorizontalImages = true),
                    hasNext = false,
                )
            }
            else -> {
                val cat = data
                items = fetchMatches()
                    .filter { it.category == cat }
                    .map { it.toSearchResponse() }
                val perPage = 60
                val from = (page - 1) * perPage
                val to = minOf(from + perPage, items.size)
                val pageItems = if (from < items.size) items.subList(from, to) else emptyList()
                return newHomePageResponse(
                    HomePageList(request.name, pageItems, isHorizontalImages = true),
                    hasNext = to < items.size,
                )
            }
        }
    }

    // ─────────────────────────── Search ──────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return fetchMatches()
            .filter {
                it.title.lowercase().contains(q) ||
                        it.category.replace('-', ' ').lowercase().contains(q)
            }
            .map { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ─────────────────────────── Load ────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val matchId = url.substringAfter("match=").substringBefore("&").substringBefore("#").trim()
        if (matchId.isEmpty()) return null

        val all = fetchMatches()
        val match = all.find { it.id == matchId } ?: return null

        val allStreams = LiveXConcurrent.parallelMapNotNull(match.sources) { src ->
            runCatching { fetchStreams(src.source, src.id) }.getOrNull()
        }.flatten()

        val payload = JSONObject()
            .put("matchId", matchId)
            .toString()

        val sportName = fetchSports().find { it.id == match.category }?.name
            ?: match.category.replace('-', ' ').replaceFirstChar { it.uppercase() }

        return newMovieLoadResponse(match.title, url, TvType.Live, payload) {
            this.plot = buildString {
                append("Live sports stream on LiveXTV.")
                append("\n\nSport: ").append(sportName)
                if (match.date > 0) {
                    val dt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm z", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getDefault()
                    }
                    append("\nScheduled: ").append(dt.format(java.util.Date(match.date)))
                }
                if (match.popular) append("\n⭐ Popular match")
                if (match.sources.isNotEmpty()) {
                    append("\n\nSources: ").append(match.sources.size).append(" provider(s)")
                    append(" → ").append(allStreams.size).append(" stream(s) available")
                    append("\nTap a source to start playback. HD streams are tagged.")
                }
            }
            this.tags = listOf(sportName)
        }
    }

    // ─────────────────────────── Load links ──────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return false
        val matchId = root.optString("matchId").ifBlank { return false }

        val all = fetchMatches()
        val match = all.find { it.id == matchId } ?: return false
        if (match.sources.isEmpty()) return false

        val streamsBySource = LiveXConcurrent.parallelMapNotNull(match.sources) { src ->
            val streams = runCatching { fetchStreams(src.source, src.id) }.getOrNull() ?: return@parallelMapNotNull null
            src to streams
        }

        var foundAny = false
        for ((src, streams) in streamsBySource) {
            for (stream in streams) {
                val embedUrl = stream.embedUrl
                if (embedUrl.isBlank()) continue

                val providerLabel = src.source.replaceFirstChar { it.uppercase() }
                val hdTag = if (stream.hd) " HD" else ""
                val langTag = if (stream.language.isNotBlank()) " • ${stream.language}" else ""
                val viewersTag = if (stream.viewers > 0) " • ${stream.viewers} watching" else ""
                val label = "$name • $providerLabel • Server ${stream.streamNo}$hdTag$langTag$viewersTag"

                runCatching {
                    loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                }

                callback(
                    newExtractorLink(
                        source = label,
                        name = label,
                        url = embedUrl,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = if (stream.hd) Qualities.P1080.value else Qualities.P720.value
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to userAgent,
                        )
                    }
                )
                foundAny = true
            }
        }

        return foundAny
    }

    // ─────────────────────────── Parsers ─────────────────────────────────

    private fun JSONObject.toMatch(): Match? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = optString("title").takeIf { it.isNotBlank() } ?: return null
        val category = optString("category").ifBlank { "other" }
        val date = optLong("date", 0L)
        val popular = optBoolean("popular", false)
        val sourcesArr = optJSONArray("sources") ?: JSONArray()
        val sources = (0 until sourcesArr.length()).mapNotNull { i ->
            val s = sourcesArr.optJSONObject(i) ?: return@mapNotNull null
            MatchSource(
                source = s.optString("source").ifBlank { return@mapNotNull null },
                id = s.optString("id").ifBlank { return@mapNotNull null },
            )
        }
        return Match(id, title, category, date, popular, sources)
    }

    private fun JSONObject.toStreamEntry(): StreamEntry? {
        val embedUrl = optString("embedUrl").takeIf { it.isNotBlank() } ?: return null
        return StreamEntry(
            streamNo = optInt("streamNo", 1),
            language = optString("language").orEmpty(),
            hd = optBoolean("hd", false),
            viewers = optInt("viewers", 0),
            embedUrl = embedUrl,
            source = optString("source").orEmpty(),
        )
    }

    private fun Match.toSearchResponse(): SearchResponse {
        val url = "$mainUrl/?match=${id.encodeUrl()}"
        return newMovieSearchResponse(title, url, TvType.Live) {
            posterUrl = null
        }
    }

    private fun String.encodeUrl(): String =
        URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    // ─────────────────────────── Small types ─────────────────────────────

    private data class Sport(val id: String, val name: String)

    private data class Match(
        val id: String,
        val title: String,
        val category: String,
        val date: Long,
        val popular: Boolean,
        val sources: List<MatchSource>,
    )

    private data class MatchSource(val source: String, val id: String)

    private data class StreamEntry(
        val streamNo: Int,
        val language: String,
        val hd: Boolean,
        val viewers: Int,
        val embedUrl: String,
        val source: String,
    )
}