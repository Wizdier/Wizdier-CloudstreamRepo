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
// • load:            Channel info card (poster, name, server count).
// • loadLinks:       Surfaces every server (primary + alternatives) as a
//                    separate M3U8 link so the user can pick the fastest.
//                    Both `.m3u8` and `.ts` URLs are passed through with the
//                    proper ExtractorLinkType.
//
// Stream types encountered in the wild
// -------------------------------------
//   1. Direct `.m3u8` (e.g. serieAleague.akamaized.net/.../master.m3u8)
//      → CloudStream plays natively.
//   2. Direct `.ts` (e.g. obiram.emonsa4.workers.dev/live/652421.ts)
//      → Marked as M3U8 (mpegts.js-style) — CloudStream's ExoPlayer handles.
//   3. crichdproxy.saemon068.workers.dev/play?id=<slug>
//      → Returns a redirecting m3u8 with token-protected childworker URLs.
//      CloudStream follows the redirect and plays the underlying m3u8.
//   4. token-protected m3u8 (e.g. tvsen6.aynaott.com/.../index.m3u8?e=...&token=...)
//      → Played directly with Referer header.
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

        return newMovieLoadResponse(ch.name, url, TvType.Live, payload) {
            posterUrl = ch.logo.ifBlank { null }
            backgroundPosterUrl = ch.logo.ifBlank { null }
            plot = buildString {
                append("Live TV channel from Obiram TV.")
                if (ch.servers.size > 1) {
                    append("\n\n").append(ch.servers.size).append(" servers available — pick the fastest from the source list.")
                }
                if (ch.servers.any { it.url.contains("workers.dev") }) {
                    append("\n\nSome servers proxy through Cloudflare Workers — these may take a moment to start but are usually the most reliable.")
                }
            }
            tags = categorise(ch.name).minus("All").toList()
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

        for (server in ch.servers) {
            val url = server.url
            val label = "$name • ${server.name} • ${ch.name}"
            val isM3U8 = url.contains(".m3u8") || url.contains("/play?") || url.contains("master")
            val isTs = url.endsWith(".ts") || url.contains("/live/")

            callback(
                newExtractorLink(
                    source = label,
                    name = label,
                    url = url,
                    type = if (isM3U8 || isTs) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = when {
                        url.contains("1080") -> Qualities.P1080.value
                        url.contains("720") -> Qualities.P720.value
                        url.contains("540") || url.contains("480") -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }
                    this.headers = baseHeaders + ("Referer" to "$mainUrl/")
                }
            )
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