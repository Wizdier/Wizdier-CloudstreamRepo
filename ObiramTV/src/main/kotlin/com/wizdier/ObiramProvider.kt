package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.Requests
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

// ─────────────────────────────────────────────────────────────────────────────
// ObiramProvider — Cloudstream extension for https://obiramtvlive.pages.dev
//
// obiramtvlive.pages.dev is a Bengali live-TV streaming front-end. Its channel
// catalogue is served as a single M3U playlist from a Cloudflare Worker at
// `https://playlist.emonsa4.workers.dev/playlist.m3u` — 400+ channels with
// multi-server redundancy via `#EXT-X-MEDIA` entries.
//
// Architecture
// ------------
// • mainPage:        12 categorised sections (All, Sports, News, Bangla,
//                    Hindi, English, Movies, Kids, Music, Entertainment,
//                    Religious, BDIX). Categories are derived heuristically
//                    from channel-name keywords since the upstream playlist
//                    has no `group-title` attribute.
// • search:          In-memory filter on the cached channel list.
// • load:            Channel info card (poster, name, server count, category
//                    tags, plot with playback hints).
// • loadLinks:       Surfaces every server (primary + alternatives) as a
//                    separate link. Direct-CDN URLs are auto-wrapped through
//                    the iptvtest068 CORS proxy (`?streamUrl=<encoded>`) so
//                    they play instead of 403-ing. Worker URLs (crichdproxy,
//                    obiram.emonsa4) are passed through directly because they
//                    already work natively.
//
// Stream URL handling (the fix for HTTP 403)
// ------------------------------------------
// The site's own player uses hls.js + mpegts.js and falls back to a CORS
// proxy for "sensitive" URLs (token=, expire=, roarzone, edge). However,
// many direct-CDN URLs (akamai, cloudfront, aynaott) ALSO 403 without the
// proxy. We use a smarter classification:
//
//   1. `crichdproxy.saemon068.workers.dev/play?id=<slug>`  → direct play
//      (returns m3u8 with token-protected childworker URLs that ExoPlayer
//       follows natively).
//   2. `obiram.emonsa4.workers.dev/live/<id>.ts`           → direct play
//      (returns video/mp2t MPEG-TS livestream; ExoPlayer handles via the
//       M3U8 ExtractorLinkType which triggers its MPEG-TS demuxer).
//   3. Other `*.workers.dev` URLs                          → direct play
//      (already proxy-handled upstream).
//   4. Direct CDN URLs (akamai, cloudfront, gpcdn, aynaott
//      with token=, etc.)                                  → wrap through
//      `https://iptvtest068.emonsa4.workers.dev/?streamUrl=<encoded>`.
//      The proxy returns a rewritten m3u8 with all sub-segment URLs
//      re-proxied, so the entire stream plays through the worker.
//
// Every server is still surfaced as a separate link so the user can
// manually switch if one is slow. The proxy-wrapped versions are listed
// AFTER the direct version so users see "the way the site plays it" first,
// then a guaranteed-working fallback.
// ─────────────────────────────────────────────────────────────────────────────
class ObiramProvider : MainAPI() {
    override var mainUrl = "https://obiramtvlive.pages.dev"
    override var name = "ObiramTV"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override var lang = "bn"

    override val supportedTypes: Set<TvType> = setOf(TvType.Live)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

    private val playlistUrl = "https://playlist.emonsa4.workers.dev/playlist.m3u"

    /** Cloudflare Worker CORS proxy used to wrap direct-CDN URLs that
     *  403 without proxying. The worker expects `?streamUrl=<url-encoded>`. */
    private val corsProxy = "https://iptvtest068.emonsa4.workers.dev/?streamUrl="

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

    // ─────────────────────────── Playlist fetch ──────────────────────────

    private val playlistCache = ObiramConcurrent.TtlCache<String, List<Channel>>(ttlMs = 10 * 60 * 1000L)

    private suspend fun fetchPlaylist(): List<Channel> {
        playlistCache.get("all")?.let { return it }
        val url = "$playlistUrl?t=${System.currentTimeMillis()}"
        val txt = ObiramConcurrent.retry {
            val res = client.get(url, headers = baseHeaders, timeout = 20_000L)
            if (res.code !in 200..299 || res.text.isBlank() || !res.text.startsWith("#EXTM3U")) {
                return@retry null
            }
            res.text
        } ?: return emptyList()

        val channels = parseM3U(txt).mapIndexed { idx, ch -> ch.copy(index = idx) }
        playlistCache.put("all", channels)
        return channels
    }

    private fun parseM3U(text: String): List<Channel> {
        data class Builder(var name: String = "", var logo: String = "", val servers: MutableList<Server> = mutableListOf())
        val out = mutableListOf<Channel>()
        var current: Builder? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF:") -> {
                    current?.let { b -> out.add(Channel(-1, b.name, b.logo, b.servers.toList())) }
                    val logo = Regex("""tvg-logo="([^"]*)"""").find(line)?.groupValues?.getOrNull(1).orEmpty()
                    val name = line.substringAfter(",", "").trim()
                    current = Builder(name = name.ifBlank { "Unknown Channel" }, logo = logo)
                }
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val sName = Regex("""NAME="([^"]+)"""").find(line)?.groupValues?.getOrNull(1) ?: continue
                    val sUrl = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1) ?: continue
                    current?.servers?.add(Server(sName, sUrl))
                }
                line.startsWith("#") -> {
                    // Other directives — ignore
                }
                line.startsWith("http") -> {
                    current?.let { b ->
                        b.servers.add(0, Server("Server 1", line))
                        out.add(Channel(-1, b.name, b.logo, b.servers.toList()))
                        current = null
                    }
                }
            }
        }
        return out.distinctBy { it.name + "|" + it.servers.firstOrNull()?.url }
    }

    // ─────────────────────────── Categorisation ──────────────────────────

    private fun categorise(name: String): Set<String> {
        val n = name.lowercase()
        val cats = mutableSetOf<String>()

        val sportsKeywords = listOf(
            "sports", "sport", "cricket", "football", "soccer", "tennis",
            "fifa", "world cup", "premier league", "la liga", "serie a",
            "bundesliga", "champions league", "ipl", "bpl", "nba", "nfl",
            "ufc", "wwe", "wrestling", "boxing", "f1", "motogp", "espn",
            "sky sports", "bt sport", "bein", "ptv sports", "t sports", "tsports",
            "gazi tv", "willow",
        )
        val matchTeams = listOf(
            "england", "australia", "india", "pakistan", "bangladesh",
            "new zealand", "south africa", "west indies", "sri lanka",
            "zimbabwe", "ireland", "afghanistan",
        )
        if (sportsKeywords.any { n.contains(it) } ||
            (n.contains(" vs ") && matchTeams.any { n.contains(it) })) {
            cats.add("Sports")
        }

        val newsKeywords = listOf(
            "news", "sangbad", "protidin", "akhon",
            "al jazeera", "bbc world", "cnn", "rt ", "france 24", "dw ",
            "sky news", "abc news", "ndtv", "republic", "times now", "wion",
            "india today",
        )
        if (newsKeywords.any { n.contains(it) }) cats.add("News")

        val banglaKeywords = listOf(
            "btv", "bangla", "somoy", "channel i", "atn", "etv", "maasranga",
            "deepto", "gaan", "ekattor", "boishakhi", "mytv", "gazi tv",
            "t sports", "tsports", "banglavision", "asian tv", "duronto",
            "desh tv", "independent tv", "jamuna", "nagorik", "rabee", "rtv",
        )
        if (banglaKeywords.any { n.contains(it) }) cats.add("Bangla")

        val hindiKeywords = listOf(
            "hindi", "star plus", "star jalsa", "sony", "zee", "colors",
            "sab tv", "set max", "star gold", "utv", "andtv", "doordarshan",
            "dd national", "rishtey", "star bharat",
        )
        if (hindiKeywords.any { n.contains(it) }) cats.add("Hindi")

        val englishKeywords = listOf(
            "english", "bbc", "cnn", "fox", "abc", "nbc", "cbs", "hbo",
            "showtime", "tnt", "amc", "fx", "usa", "syfy", "tbs", "spike",
            "trutv", "lifetime", "hallmark", "discovery", "nat geo",
            "national geographic", "animal planet", "tlc", "food network",
            "history channel", "hollywood", "mgm",
        )
        if (englishKeywords.any { n.contains(it) } ||
            (n.contains("cinema") && !n.contains("bangla"))) cats.add("English")

        val movieKeywords = listOf(
            "movie", "cinema", "film", "hd movie", "hbo", "star gold",
            "set max", "zee cinema", "sony max", "movies now", "romedy",
            "fx movie",
        )
        if (movieKeywords.any { n.contains(it) }) cats.add("Movies")

        val kidsKeywords = listOf(
            "kids", "kidz", "cartoon", "disney", "nick", "pogo", "animax",
            "boomerang", "baby tv", "discovery kids", "sonic",
        )
        if (kidsKeywords.any { n.contains(it) }) cats.add("Kids")

        val musicKeywords = listOf(
            "music", "mtv", "vh1", "channel v", "9xm", "b4u music", "zoom",
            "bangla music", "music india", "mtv beats",
        )
        if (musicKeywords.any { n.contains(it) } && !n.contains("mtv beats") ||
            n.contains("mtv beats") || n.contains("gaan")) {
            cats.add("Music")
        }

        val religiousKeywords = listOf(
            "peace tv", "islam", "quran", "madina", "makkah", "iqra", "noor",
            "hindu", "darshan", "bhajan", "sanskar", "astha", "god", "faith",
            "religion",
        )
        if (religiousKeywords.any { n.contains(it) }) cats.add("Religious")

        val bdixKeywords = listOf(
            "bdix", "sam-online", "agni", "dhakaflix", "discovery net",
            "circlebroadband", "crazyhd", "dhaka live",
        )
        if (bdixKeywords.any { n.contains(it) }) cats.add("BDIX")

        cats.add("All")
        if (cats.size == 1) cats.add("Entertainment")
        return cats
    }

    // ─────────────────────────── Stream URL routing ──────────────────────

    /** Decide how to handle a given stream URL. Returns:
     *  - DirectPlay(url) — pass through to CloudStream as-is
     *  - ProxyWrap(url)  — wrap through the iptvtest068 CORS proxy
     *
     *  Worker URLs (crichdproxy, obiram.emonsa4, childworker) are passed
     *  through directly because they typically work natively. Direct-CDN
     *  URLs (cloudfront, akamai, gpcdn, aynaott, roarzone, etc.) 403
     *  without proxying, so they get wrapped through the CORS proxy. */
    private sealed class StreamRoute {
        abstract val url: String
        data class DirectPlay(override val url: String) : StreamRoute()
        data class ProxyWrap(override val url: String) : StreamRoute()
    }

    private fun routeUrl(url: String): StreamRoute {
        // Cloudflare Worker URLs that already proxy upstream — pass through.
        // Matches: crichdproxy.saemon068.workers.dev, childworkerN.emonsa98.workers.dev,
        //          iptvtest068.emonsa4.workers.dev (already proxied).
        // Note: obiram.emonsa4.workers.dev/live/*.ts URLs are IP-locked and
        // frequently 403 from non-whitelisted IPs — they're included here as
        // direct-play because (a) they work for users on whitelisted networks
        // and (b) the auto-failover to the next server handles the rest.
        if (url.contains(".workers.dev/")) {
            return StreamRoute.DirectPlay(url)
        }
        // Direct CDN URLs (cloudfront, akamai, gpcdn, aynaott, roarzone, etc.)
        // 403 without proxying — wrap through iptvtest068.
        return StreamRoute.ProxyWrap(corsProxy + URLEncoder.encode(url, "UTF-8"))
    }

    /** True if the URL points to a `.ts` MPEG-TS livestream (ExoPlayer needs
     *  M3U8 type to engage its MPEG-TS demuxer for these). */
    private fun isMpegTs(url: String): Boolean =
        url.endsWith(".ts") || url.contains("/live/") && url.contains(".ts")

    /** True if the URL returns an HLS playlist (master.m3u8, /play?id=,
     *  chunklist.m3u8, index.m3u8). */
    private fun isHls(url: String): Boolean =
        url.contains(".m3u8") || url.contains("/play?") || url.contains("master") ||
                url.contains("chunklist") || url.contains("index.m3u8")

    /** Detect quality hint from URL patterns. */
    private fun qualityFromUrl(url: String): Int = when {
        url.contains("2160") || url.contains("4k") -> Qualities.P2160.value
        url.contains("1080") -> Qualities.P1080.value
        url.contains("720") -> Qualities.P720.value
        url.contains("540") || url.contains("480") -> Qualities.P480.value
        url.contains("360") || url.contains("240") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    /** Human-readable label for the URL's source type — helps the user pick
     *  the best server in the source picker. */
    private fun sourceKindLabel(url: String): String = when {
        url.contains("crichdproxy") -> "CrichHD Proxy"
        url.contains("obiram.emonsa4") -> "Obiram Worker"
        url.contains("iptvtest068") -> "CORS Proxy"
        url.contains("childworker") -> "Child Worker"
        url.contains("akamaized") -> "Akamai CDN"
        url.contains("cloudfront") -> "CloudFront CDN"
        url.contains("gpcdn") -> "GPCDN"
        url.contains("aynaott") -> "Ayna OTT"
        url.contains("roarzone") -> "Roarzone"
        url.contains(".ts") -> "MPEG-TS Stream"
        else -> "Direct Stream"
    }

    // ─────────────────────────── Main page ───────────────────────────────

    override val mainPage = mainPageOf(
        "All" to "All Channels",
        "Sports" to "Sports",
        "News" to "News",
        "Bangla" to "Bangla",
        "Hindi" to "Hindi Entertainment",
        "English" to "English",
        "Movies" to "Movies",
        "Kids" to "Kids",
        "Music" to "Music",
        "Entertainment" to "Entertainment",
        "Religious" to "Religious",
        "BDIX" to "BDIX",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val all = fetchPlaylist()
        val cat = request.data
        val filtered = if (cat == "All") all else all.filter { cat in categorise(it.name) }

        val perPage = 60
        val from = (page - 1) * perPage
        val to = minOf(from + perPage, filtered.size)
        val pageItems = if (from < filtered.size) filtered.subList(from, to) else emptyList()

        val items = pageItems.map { it.toSearchResponse() }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = to < filtered.size,
        )
    }

    // ─────────────────────────── Search ──────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val all = fetchPlaylist()
        return all
            .filter { it.name.lowercase().contains(q) }
            .map { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ─────────────────────────── Load ────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfter("channel=").substringBefore("&").substringBefore("#").trim()
        val idx = slug.removePrefix("obiram-").toIntOrNull() ?: return null

        val all = fetchPlaylist()
        if (idx < 0 || idx >= all.size) return null
        val ch = all[idx]

        val payload = JSONObject()
            .put("index", idx)
            .toString()

        val cats = categorise(ch.name).minus("All").toList()
        val workingServers = ch.servers.count { !it.url.isBlank() }

        return newMovieLoadResponse(ch.name, url, TvType.Live, payload) {
            posterUrl = ch.logo.ifBlank { null }
            backgroundPosterUrl = ch.logo.ifBlank { null }
            plot = buildString {
                append("Live TV channel from Obiram TV (অবিরাম টিভি).")
                if (workingServers > 1) {
                    append("\n\n")
                    append(workingServers).append(" servers available — ")
                    append("if one fails or shows 'HTTP 403', pick the next from the source list. ")
                    append("Worker-based servers (CrichHD Proxy, Obiram Worker) are most reliable; ")
                    append("direct-CDN servers auto-route through the CORS proxy.")
                }
                if (cats.isNotEmpty()) {
                    append("\n\nCategories: ").append(cats.joinToString(" • "))
                }
            }
            tags = cats
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
        val idx = root.optInt("index", -1)
        if (idx < 0) return false

        val all = fetchPlaylist()
        if (idx >= all.size) return false
        val ch = all[idx]

        if (ch.servers.isEmpty()) return false

        // Surface every server as a separate link so CloudStream's player
        // can fall through them if one fails. For each server, the routing
        // logic decides whether to play directly (worker URLs) or wrap
        // through the iptvtest068 CORS proxy (direct-CDN URLs that 403).
        //
        // For obiram.emonsa4 .ts URLs (which are IP-locked and frequently
        // 403), we emit BOTH the direct URL AND a proxy-wrapped version so
        // the user has a fallback if the direct version is geo-blocked.
        for (server in ch.servers) {
            val rawUrl = server.url
            if (rawUrl.isBlank()) continue

            val route = routeUrl(rawUrl)
            val playUrl = when (route) {
                is StreamRoute.DirectPlay -> route.url
                is StreamRoute.ProxyWrap -> route.url
            }
            val kindLabel = sourceKindLabel(playUrl)
            val isProxied = route is StreamRoute.ProxyWrap
            val label = "$name • ${server.name} • $kindLabel${if (isProxied) " (Proxied)" else ""}"

            callback(
                newExtractorLink(
                    source = label,
                    name = label,
                    url = playUrl,
                    type = if (isMpegTs(playUrl) || isHls(playUrl)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = qualityFromUrl(playUrl)
                    this.headers = baseHeaders + ("Referer" to "$mainUrl/")
                }
            )

            // For obiram.emonsa4 .ts URLs (IP-locked), also emit a proxy-wrapped
            // fallback so the user has a working option if direct fails.
            if (rawUrl.contains("obiram.emonsa4.workers.dev") && rawUrl.endsWith(".ts")) {
                val fallbackUrl = corsProxy + URLEncoder.encode(rawUrl, "UTF-8")
                val fallbackLabel = "$name • ${server.name} • CORS Proxy (Fallback)"
                callback(
                    newExtractorLink(
                        source = fallbackLabel,
                        name = fallbackLabel,
                        url = fallbackUrl,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = qualityFromUrl(fallbackUrl)
                        this.headers = baseHeaders + ("Referer" to "$mainUrl/")
                    }
                )
            }
        }
        return true
    }

    // ─────────────────────────── Parsers ─────────────────────────────────

    private fun Channel.toSearchResponse(): SearchResponse {
        val url = "$mainUrl/?channel=obiram-$index"
        return newMovieSearchResponse(name, url, TvType.Live) {
            posterUrl = logo.ifBlank { null }
        }
    }

    // ─────────────────────────── URL helpers ─────────────────────────────

    private fun String.encodeUrl(): String =
        URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    // ─────────────────────────── Small types ─────────────────────────────

    private data class Channel(
        val index: Int,
        val name: String,
        val logo: String,
        val servers: List<Server>,
    )

    private data class Server(
        val name: String,
        val url: String,
    )
}