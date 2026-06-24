package com.wizdier

import android.content.SharedPreferences
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
// NTVProvider — Cloudstream extension for https://ntv.cx
//
// ntv.cx is a live-sports streaming aggregator with 6 server brands:
//   Kobra · Raptor · Falcon · Phoenix · Titan · Viper
//
// Each server has its own match list at /api/get-matches?server=<name>&type=both.
// The user can toggle individual servers in Settings — disabled servers are
// hidden from the main page, search, and category lists.
//
// Stream extraction strategy (old-TV-friendly):
// ────────────────────────────────────────────────────────────
// • Falcon:  sources[].url = "https://hesgoaler.com/cine.php?id=<m3u8-url>"
//            The `id` query parameter IS the direct m3u8 URL. We decode it
//            and pass it straight to ExoPlayer — NO WebView needed. This is
//            the recommended server for TVs with outdated Android WebView.
//
// • Kobra:  sources[] = [{source, id}] (same as LiveXTV). The embed URL is
//            https://embed.st/embed/<source>/<id>/1 — an obfuscated JW Player
//            page. We try loadExtractor() first; if that fails (old WebView),
//            we also surface the ntv.cx watch page as a fallback iframe link.
//
// • Raptor: sources[].url = embedindia.st embed page. Same strategy as Kobra.
// • Phoenix: sources[].url = dlhd.pk stream page. Same strategy.
// • Titan:  sources[].url = cdnlivetv.tv player page. Same strategy.
// • Viper:  sources[].url = sansat.link / glisco.link page. Same strategy.
//
// For all non-Falcon servers, we surface BOTH:
//   1. The extracted m3u8 (if loadExtractor succeeds)
//   2. The raw embed URL as a fallback link (user can open in browser)
// ─────────────────────────────────────────────────────────────────────────────
class NTVProvider(
    private val prefs: SharedPreferences? = null,
) : MainAPI() {

    override var mainUrl = "https://ntv.cx"
    override var name = "NTVStream"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override var lang = "en"
    override val supportedTypes: Set<TvType> = setOf(TvType.Live)

    // ─────────────────────────── Settings ────────────────────────────────

    companion object {
        const val PREF_FILE = "NTVStream"
        const val PREF_KOBRA   = "server_kobra"
        const val PREF_RAPTOR  = "server_raptor"
        const val PREF_FALCON  = "server_falcon"
        const val PREF_PHOENIX = "server_phoenix"
        const val PREF_TITAN   = "server_titan"
        const val PREF_VIPER   = "server_viper"
    }

    /** The 6 server brands, in display order. */
    private val allServers = listOf(
        ServerBrand("kobra",   "Kobra",   "🐍"),
        ServerBrand("raptor",  "Raptor",  "🦅"),
        ServerBrand("falcon",  "Falcon",  "🦉"),
        ServerBrand("phoenix", "Phoenix", "🔥"),
        ServerBrand("titan",   "Titan",   "⚡"),
        ServerBrand("viper",   "Viper",   "🐉"),
    )

    /** Returns true if the given server is enabled in settings (default: all on). */
    private fun isServerEnabled(serverId: String): Boolean {
        val key = when (serverId) {
            "kobra"   -> PREF_KOBRA
            "raptor"  -> PREF_RAPTOR
            "falcon"  -> PREF_FALCON
            "phoenix" -> PREF_PHOENIX
            "titan"   -> PREF_TITAN
            "viper"   -> PREF_VIPER
            else -> return false
        }
        return prefs?.getBoolean(key, true) ?: true
    }

    /** The list of enabled servers, in display order. */
    private val enabledServers: List<ServerBrand>
        get() = allServers.filter { isServerEnabled(it.id) }

    // ─────────────────────────── HTTP ────────────────────────────────────

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/",
    )

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

    private suspend fun fetchJson(url: String, timeout: Long = 15_000L): JSONObject? =
        NTVConcurrent.retry {
            val res = client.get(url, headers = baseHeaders, timeout = timeout)
            if (res.code !in 200..299 || res.text.isBlank()) return@retry null
            // The API sometimes prefixes the JSON with a blank line — trim it.
            val txt = res.text.trim()
            if (!txt.startsWith("{")) return@retry null
            runCatching { JSONObject(txt) }.getOrNull()
        }

    // ─────────────────────────── Match cache ─────────────────────────────

    private val matchCache = NTVConcurrent.TtlCache<String, List<Match>>(ttlMs = 3 * 60 * 1000L)

    private suspend fun fetchMatches(server: String): List<Match> {
        matchCache.get(server)?.let { return it }
        val json = fetchJson("$mainUrl/api/get-matches?server=$server&type=both") ?: return emptyList()
        val all = json.optJSONArray("all") ?: JSONArray()
        val matches = (0 until all.length()).mapNotNull { i ->
            all.optJSONObject(i)?.toMatch(server)
        }
        matchCache.put(server, matches)
        return matches
    }

    // ─────────────────────────── Main page ───────────────────────────────

    override val mainPage by lazy {
        val sections = mutableListOf<Pair<String, String>>()
        // "Live Now" aggregates across all enabled servers
        sections.add("live" to "🔴 Live Now")
        // One section per enabled server
        for (s in enabledServers) {
            sections.add("server:${s.id}" to "${s.emoji} ${s.name}")
        }
        mainPageOf(*sections.toTypedArray())
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data

        if (data == "live") {
            // Aggregate live matches across all enabled servers
            val allLive = NTVConcurrent.parallelMapNotNull(enabledServers) { srv ->
                val matches = fetchMatches(srv.id)
                matches.filter { it.live }
            }.flatten()
            return newHomePageResponse(
                HomePageList(request.name, allLive.map { it.toSearchResponse() }, isHorizontalImages = true),
                hasNext = false,
            )
        }

        if (data.startsWith("server:")) {
            val serverId = data.removePrefix("server:")
            val matches = fetchMatches(serverId)
            val perPage = 60
            val from = (page - 1) * perPage
            val to = minOf(from + perPage, matches.size)
            val pageItems = if (from < matches.size) matches.subList(from, to) else emptyList()
            return newHomePageResponse(
                HomePageList(request.name, pageItems.map { it.toSearchResponse() }, isHorizontalImages = true),
                hasNext = to < matches.size,
            )
        }

        return newHomePageResponse(emptyList())
    }

    // ─────────────────────────── Search ──────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return NTVConcurrent.parallelMapNotNull(enabledServers) { srv ->
            val json = fetchJson(
                "$mainUrl/api/search?q=${q.encodeUrl()}&server=${srv.id}"
            ) ?: return@parallelMapNotNull null
            val arr = json.optJSONArray("data") ?: return@parallelMapNotNull null
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.toMatch(srv.id)?.toSearchResponse()
            }
        }.flatten()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ─────────────────────────── Load ────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        // URL format: https://ntv.cx/?ntv=<server>:<matchId>
        val payload = url.substringAfter("?ntv=").substringBefore("&").substringBefore("#").trim()
        val parts = payload.split(":", limit = 2)
        if (parts.size != 2) return null
        val serverId = parts[0]
        val matchId = parts[1]

        val matches = fetchMatches(serverId)
        val match = matches.find { it.id == matchId } ?: return null

        val serverBrand = allServers.find { it.id == serverId }?.name ?: serverId

        val data = JSONObject()
            .put("server", serverId)
            .put("matchId", matchId)
            .toString()

        return newMovieLoadResponse(match.title, url, TvType.Live, data) {
            posterUrl = match.posterUrl
            plot = buildString {
                append("Live sports stream from NTVStream ($serverBrand server).")
                if (match.tournament.isNotBlank()) {
                    append("\n\nTournament: ").append(match.tournament)
                }
                if (match.category.isNotBlank()) {
                    append("\nCategory: ").append(match.category)
                }
                if (match.date > 0) {
                    val dt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm z", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getDefault()
                    }
                    append("\nScheduled: ").append(dt.format(java.util.Date(match.date)))
                }
                if (match.live) append("\n\n🔴 LIVE NOW")
                if (match.sources.isNotEmpty()) {
                    append("\n\nSources: ").append(match.sources.size).append(" stream(s) available")
                    if (serverId == "falcon") {
                        append("\n\n💡 Falcon server uses direct m3u8 extraction — no WebView needed, works on older TVs.")
                    } else {
                        append("\n\n⚠️ This server may require a modern WebView. If streams don't load, try the Falcon server instead.")
                    }
                }
            }
            tags = listOf(serverBrand, match.category).filter { it.isNotBlank() }
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
        val serverId = root.optString("server").ifBlank { return false }
        val matchId = root.optString("matchId").ifBlank { return false }

        val matches = fetchMatches(serverId)
        val match = matches.find { it.id == matchId } ?: return false
        if (match.sources.isEmpty()) return false

        var foundAny = false
        val serverName = allServers.find { it.id == serverId }?.name ?: serverId

        for (source in match.sources) {
            val sourceUrl = source.url
            val sourceName = source.source
            val channelName = source.channelName

            // ── FALCON: Direct m3u8 extraction (old-TV-friendly) ────────
            if (serverId == "falcon" && sourceUrl.contains("hesgoaler.com/cine.php?id=")) {
                val m3u8Url = extractFalconM3u8(sourceUrl)
                if (m3u8Url != null && m3u8Url.startsWith("http")) {
                    val label = buildLabel(serverName, sourceName, channelName, "Direct HD")
                    callback(
                        newExtractorLink(
                            source = label,
                            name = label,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.referer = "https://hesgoaler.com/"
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf(
                                "Referer" to "https://hesgoaler.com/",
                                "User-Agent" to userAgent,
                            )
                        }
                    )
                    foundAny = true
                    continue
                }
            }

            // ── KOBRA: Construct embed.st URL from {source, id} ─────────
            if (serverId == "kobra" && sourceUrl.isBlank()) {
                // Kobra sources have no `url` field — construct the embed URL
                val embedUrl = "https://embed.st/embed/${source.source}/${source.id}/1"
                val label = buildLabel(serverName, sourceName, channelName, "Embed")

                // Try loadExtractor (uses WebView if available)
                runCatching {
                    loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                }

                // Also surface as a fallback iframe link
                callback(
                    newExtractorLink(
                        source = label,
                        name = label,
                        url = embedUrl,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent)
                    }
                )
                foundAny = true
                continue
            }

            // ── ALL OTHER SERVERS: Surface the embed URL ────────────────
            if (sourceUrl.isNotBlank()) {
                val label = buildLabel(serverName, sourceName, channelName, if (sourceUrl.contains(".m3u8")) "Direct" else "Embed")

                // Try loadExtractor (uses WebView if available)
                runCatching {
                    loadExtractor(sourceUrl, "$mainUrl/", subtitleCallback, callback)
                }

                // Also surface as a fallback link
                val isM3u8 = sourceUrl.contains(".m3u8")
                callback(
                    newExtractorLink(
                        source = label,
                        name = label,
                        url = sourceUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent)
                    }
                )
                foundAny = true
            }
        }

        return foundAny
    }

    /** Decode the m3u8 URL from a Falcon hesgoaler.com embed URL.
     *  The `id` query parameter is a URL-encoded direct m3u8 URL. */
    private fun extractFalconM3u8(embedUrl: String): String? {
        return try {
            val parsed = java.net.URI(embedUrl)
            val params = parsed.rawQuery?.split("&")?.associate {
                val (k, v) = it.split("=", limit = 2)
                k to java.net.URLDecoder.decode(v, "UTF-8")
            } ?: emptyMap()
            params["id"]
        } catch (_: Exception) { null }
    }

    /** Build a clean, readable source label for the CloudStream picker. */
    private fun buildLabel(
        serverName: String,
        sourceName: String,
        channelName: String,
        qualityTag: String,
    ): String {
        val parts = mutableListOf<String>()
        parts.add("$name • $serverName")
        if (channelName.isNotBlank()) parts.add(channelName)
        else if (sourceName.isNotBlank()) parts.add(sourceName)
        parts.add(qualityTag)
        return parts.joinToString(" • ")
    }

    // ─────────────────────────── Parsers ─────────────────────────────────

    private fun JSONObject.toMatch(serverId: String): Match? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = optString("title").takeIf { it.isNotBlank() } ?: return null
        val category = optString("category").ifBlank { "" }
        val tournament = optString("tournament").ifBlank { "" }
        val date = optLong("date", 0L)
        val popular = optBoolean("popular", false)
        val live = optBoolean("live", false) ||
                optString("status").equals("live", true)

        // Poster — may be a relative path like /api/images/proxy/...
        val posterRaw = optString("poster").ifBlank { "" }
        val posterUrl = when {
            posterRaw.startsWith("http") -> posterRaw
            posterRaw.startsWith("/") -> "$mainUrl$posterRaw"
            else -> ""
        }

        // Sources — different schemas per server
        val sourcesArr = optJSONArray("sources") ?: JSONArray()
        val sources = (0 until sourcesArr.length()).mapNotNull { i ->
            val s = sourcesArr.optJSONObject(i) ?: return@mapNotNull null
            MatchSource(
                source = s.optString("source").ifBlank { "" },
                id = s.optString("id").ifBlank { "" },
                url = s.optString("url").ifBlank { "" },
                channelName = s.optString("channelName").ifBlank { "" },
            )
        }

        return Match(id, title, serverId, category, tournament, date, popular, live, posterUrl, sources)
    }

    private fun Match.toSearchResponse(): SearchResponse {
        val url = "$mainUrl/?ntv=$server:$id"
        val pUrl = if (posterUrl.isNotBlank()) posterUrl else null
        return newMovieSearchResponse(title, url, TvType.Live) {
            posterUrl = pUrl
        }
    }

    // ─────────────────────────── Helpers ─────────────────────────────────

    private fun String.encodeUrl(): String =
        URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    // ─────────────────────────── Types ───────────────────────────────────

    data class ServerBrand(val id: String, val name: String, val emoji: String)

    data class Match(
        val id: String,
        val title: String,
        val server: String,
        val category: String,
        val tournament: String,
        val date: Long,
        val popular: Boolean,
        val live: Boolean,
        val posterUrl: String,
        val sources: List<MatchSource>,
    )

    data class MatchSource(
        val source: String,
        val id: String,
        val url: String,
        val channelName: String,
    )
}
