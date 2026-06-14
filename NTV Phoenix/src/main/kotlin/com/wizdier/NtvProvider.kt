package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup

// ═══════════════════════════════════════════════════════════════════════════
//  NtvProvider — Shared live-sports provider for ntv.cx
//
//  Each NTV server (Kobra, Raptor, Falcon, Phoenix, Viper) gets its own
//  Cloudstream extension that instantiates this class with a unique server
//  ID and display name.
// ═══════════════════════════════════════════════════════════════════════════

class NtvProvider(
    private val serverId: String,
    private val displayName: String,
) : MainAPI() {

    override var mainUrl = "https://ntv.cx"
    override var name = displayName
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live, TvType.Others)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://ntv.cx/",
        "Origin" to "https://ntv.cx",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
    )

    // ── Category display names ───────────────────────────────────────────────

    private val sportIcons = mapOf(
        "football" to "⚽ Football",
        "basketball" to "🏀 Basketball",
        "baseball" to "⚾ Baseball",
        "hockey" to "🏒 Hockey",
        "tennis" to "🎾 Tennis",
        "fight" to "🥊 Fighting / UFC",
        "rugby" to "🏉 Rugby",
        "american-football" to "🏈 American Football",
        "motor-sports" to "🏎️ Motor Sports",
        "darts" to "🎯 Darts",
        "other" to "📡 Other Sports",
    )

    // ── Home page ────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "live" to "🔴 LIVE NOW",
        "today" to "📅 Today's Schedule",
        "upcoming" to "🗓️ Upcoming",
        "football" to "⚽ Football",
        "basketball" to "🏀 Basketball",
        "baseball" to "⚾ Baseball",
        "hockey" to "🏒 Hockey",
        "tennis" to "🎾 Tennis",
        "fight" to "🥊 Fighting / UFC",
        "rugby" to "🏉 Rugby",
        "american-football" to "🏈 American Football",
        "motor-sports" to "🏎️ Motor Sports",
        "darts" to "🎯 Darts",
        "other" to "📡 Other Sports",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = runCatching { fetchMatches() }.getOrNull()
            ?: return newHomePageResponse(
                HomePageList(request.name, emptyList(), isHorizontalImages = false), false
            )

        val live = data.toJsonArray("live")
        val nonLive = data.toJsonArray("nonLive")
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis
        val todayEnd = todayStart + 86_400_000L

        val items: List<SearchResponse> = when (request.data) {
            "live" -> live.mapNotNull { it.toSearch() }
            "today" -> nonLive.filter { m ->
                val d = m.optLong("date", 0)
                d in todayStart..todayEnd
            }.mapNotNull { it.toSearch() }
            "upcoming" -> nonLive.filter { it.optLong("date", 0) > now }
                .mapNotNull { it.toSearch() }
            else -> {
                val cat = request.data
                (live + nonLive).filter { m ->
                    m.optString("category").equals(cat, true)
                }.mapNotNull { it.toSearch() }
            }
        }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            items.isNotEmpty()
        )
    }

    // ── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val data = runCatching { fetchMatches() }.getOrNull() ?: return emptyList()
        val q = query.trim().lowercase()
        val all = data.toJsonArray("live") + data.toJsonArray("nonLive")
        return all.filter { m ->
            m.optString("title").lowercase().contains(q) ||
                    m.optString("category").lowercase().contains(q)
        }.mapNotNull { it.toSearch() }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val matchId = url.substringAfterLast("/").substringBefore("?")
        val watchUrl = "$mainUrl/watch/$serverId/$matchId"

        val data = runCatching { fetchMatches() }.getOrNull()
        val all = data?.let { d -> d.toJsonArray("live") + d.toJsonArray("nonLive") } ?: emptyList()
        val match = all.firstOrNull { it.optString("id") == matchId }

        val title = match?.optString("title")?.takeIf { it.isNotBlank() } ?: matchId
        val isLive = match?.optBoolean("live", false) ?: false
        val category = match?.optString("category") ?: "other"
        val date = match?.optLong("date", 0) ?: 0

        val poster = match?.optString("poster")?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            ?: match?.optJSONObject("teams")?.optJSONObject("home")
                ?.optString("badge")?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { "$mainUrl/api/images/proxy/$it" }

        val plot = buildString {
            val teams = match?.optJSONObject("teams")
            val home = teams?.optJSONObject("home")
            val away = teams?.optJSONObject("away")
            if (home != null && away != null) {
                append("${home.optString("name")} vs ${away.optString("name")}\n\n")
            }
            append("Sport: ${sportIcons[category] ?: category}\n")
            append("Server: $displayName\n")
            if (isLive) append("Status: 🔴 LIVE NOW\n")
            if (date > 0) {
                val sdf = java.text.SimpleDateFormat("EEE, dd MMM yyyy · HH:mm z", java.util.Locale.ENGLISH)
                sdf.timeZone = java.util.TimeZone.getDefault()
                append("Time: ${sdf.format(java.util.Date(date))}")
            }
        }.trim()

        return newMovieLoadResponse(title, watchUrl, TvType.Live, watchUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = if (isLive) listOf("🔴 LIVE", category) else listOf(category)
        }
    }

    // ── Load Links ───────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val watchUrl = if (data.startsWith("http")) data else "$mainUrl/watch/$serverId/$data"

        // Step 1: Fetch the watch page and parse the source dropdown
        val watchHtml = runCatching {
            app.get(watchUrl, headers = headers, timeout = 15_000).text
        }.getOrNull() ?: return false

        val doc = Jsoup.parse(watchHtml)

        // Each <option> has value="/embed?t=TOKEN" and a label
        val embedOptions = doc.select("#sourceSelect option, select option[value*=embed]")
            .mapNotNull { opt ->
                val path = opt.attr("value").trim()
                if (path.isBlank()) return@mapNotNull null
                val label = opt.text().trim().ifBlank { "Stream" }
                val fullUrl = if (path.startsWith("http")) path else "$mainUrl$path"
                fullUrl to label
            }
            .distinctBy { it.first }

        if (embedOptions.isEmpty()) return false

        var found = false
        for ((embedUrl, label) in embedOptions) {
            // Step 2: Fetch the ntv.cx embed page to find the actual player iframe (embed.st)
            val playerUrl = runCatching {
                val embedHtml = app.get(embedUrl, headers = headers, timeout = 12_000).text
                resolvePlayerUrl(embedHtml)
            }.getOrNull()

            // Step 3: Pass the real player URL to loadExtractor
            if (playerUrl != null) {
                runCatching {
                    loadExtractor(playerUrl, "$mainUrl/", subtitleCallback) { link ->
                        callback(link)
                        found = true
                    }
                }
            }

            // Also try loadExtractor directly on the ntv.cx embed URL
            // (Cloudstream's WebView fallback may resolve it)
            if (!found) {
                runCatching {
                    loadExtractor(embedUrl, "$mainUrl/", subtitleCallback) { link ->
                        callback(link)
                        found = true
                    }
                }
            }

            // Also try direct extraction from both pages
            if (!found) {
                runCatching {
                    val embedHtml = app.get(embedUrl, headers = headers, timeout = 12_000).text
                    extractFromEmbed(embedHtml, label, subtitleCallback, callback) { found = true }
                }
            }

            if (playerUrl != null && !found) {
                runCatching {
                    val playerHtml = app.get(playerUrl, headers = headers, timeout = 12_000).text
                    extractFromEmbed(playerHtml, label, subtitleCallback, callback) { found = true }
                }
            }

            if (!found) kotlinx.coroutines.delay(300)
        }
        return found
    }

    /** Extract the actual player iframe URL (embed.st) from the ntv.cx embed page. */
    private fun resolvePlayerUrl(embedHtml: String): String? {
        // Look for iframe src pointing to embed.st or other player hosts
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(embedHtml)
            .map { it.groupValues[1] }
            .forEach { src ->
                val abs = when {
                    src.startsWith("http") -> src
                    src.startsWith("//") -> "https:$src"
                    else -> "$mainUrl$src"
                }
                // embed.st is the main player host
                if (abs.contains("embed.st") || abs.contains("/embed/") || abs.contains("player")) {
                    return abs
                }
            }
        return null
    }

    private suspend fun extractFromEmbed(
        html: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        onFound: () -> Unit,
    ) {
        // 1. Direct m3u8 / mp4 in the page
        val streamUrls = linkedSetOf<String>()
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { streamUrls.add(it.value.replace("\\/", "/")) }
        Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { streamUrls.add(it.value.replace("\\/", "/")) }

        for (url in streamUrls) {
            val clean = url.replace("&amp;", "&").trim()
            if (clean.isBlank()) continue
            val type = if (clean.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink("$name • $label", label, clean, type) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(label)
                }
            )
            onFound()
        }

        // 2. Nested iframes → loadExtractor
        Regex("""<iframe[^>]+src=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .forEach { src ->
                val abs = when {
                    src.startsWith("http") -> src
                    src.startsWith("//") -> "https:$src"
                    else -> "$mainUrl$src"
                }
                runCatching {
                    loadExtractor(abs, "$mainUrl/", subtitleCallback) { link ->
                        callback(link)
                        onFound()
                    }
                }
            }
    }

    // ── API ──────────────────────────────────────────────────────────────────

    private suspend fun fetchMatches(): JSONObject {
        val res = app.get(
            "$mainUrl/api/get-matches?server=$serverId&type=both",
            headers = mapOf("User-Agent" to ua, "Accept" to "application/json"),
            timeout = 15_000
        )
        return JSONObject(res.text)
    }

    private fun JSONObject.toJsonArray(key: String): List<JSONObject> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    // ── JSON → SearchResponse ────────────────────────────────────────────────

    private fun JSONObject.toSearch(): SearchResponse? {
        val id = optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val title = optString("title").takeIf { it.isNotBlank() } ?: return null
        val isLive = optBoolean("live", false)
        val displayTitle = if (isLive) "🔴 $title" else title

        val poster = optString("poster").takeIf { it.isNotBlank() && it != "null" }
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            ?: optJSONObject("teams")?.optJSONObject("home")
                ?.optString("badge")?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { "$mainUrl/api/images/proxy/$it" }

        val url = "$mainUrl/watch/$serverId/$id"
        return newMovieSearchResponse(displayTitle, url, TvType.Live) {
            this.posterUrl = poster
        }
    }
}
