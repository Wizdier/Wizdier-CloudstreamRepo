package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup

// ═══════════════════════════════════════════════════════════════════════════
//  NtvProvider — Shared provider for all NTV server extensions.
//
//  Each server (Kobra, Raptor, Falcon, Phoenix, Viper) gets its own
//  Cloudstream extension that instantiates this class with its server ID.
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
    override val supportedTypes = setOf(TvType.Live, TvType.Others, TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Referer" to "https://ntv.cx/",
        "Origin" to "https://ntv.cx",
    )

    private val categoryLabels = mapOf(
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

    // ── Main page ────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "live" to "🔴 LIVE NOW",
        "today" to "📅 Today",
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
            ?: return newHomePageResponse(HomePageList(request.name, emptyList(), isHorizontalImages = false), false)

        val now = System.currentTimeMillis()
        val all = (data.optJSONArray("live") ?: data.optJSONArray("all"))?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
        } ?: emptyList()

        val liveList = (data.optJSONArray("live"))?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
        } ?: emptyList()

        val nonLiveList = (data.optJSONArray("nonLive"))?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
        } ?: emptyList()

        val items = when (request.data) {
            "live" -> liveList.mapNotNull { it.toSearchResponse() }
            "today" -> {
                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }
                val todayStart = cal.timeInMillis
                val todayEnd = todayStart + 24 * 60 * 60 * 1000
                nonLiveList.filter { m ->
                    val d = m.optLong("date", 0)
                    d in todayStart..todayEnd
                }.mapNotNull { it.toSearchResponse() }
            }
            "upcoming" -> nonLiveList.filter { it.optLong("date", 0) > now }
                .mapNotNull { it.toSearchResponse() }
            else -> {
                val cat = request.data
                (liveList + nonLiveList).filter { m ->
                    m.optString("category").equals(cat, true)
                }.mapNotNull { it.toSearchResponse() }
            }
        }

        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), items.isNotEmpty())
    }

    // ── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val data = runCatching { fetchMatches() }.getOrNull() ?: return emptyList()
        val q = query.trim().lowercase()
        val all = mutableListOf<JSONObject>()
        data.optJSONArray("live")?.let { arr ->
            (0 until arr.length()).forEach { i -> arr.optJSONObject(i)?.let(all::add) }
        }
        data.optJSONArray("nonLive")?.let { arr ->
            (0 until arr.length()).forEach { i -> arr.optJSONObject(it)?.let(all::add) }
        }
        return all.filter { m ->
            val title = m.optString("title").lowercase()
            val category = m.optString("category").lowercase()
            title.contains(q) || category.contains(q)
        }.mapNotNull { it.toSearchResponse() }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val matchId = url.substringAfterLast("/").substringBefore("?")
        val watchUrl = "$mainUrl/watch/$serverId/$matchId"

        val data = runCatching { fetchMatches() }.getOrNull()
        val matchObj = data?.let { d ->
            val all = mutableListOf<JSONObject>()
            d.optJSONArray("live")?.let { arr ->
                (0 until arr.length()).forEach { i -> arr.optJSONObject(i)?.let(all::add) }
            }
            d.optJSONArray("nonLive")?.let { arr ->
                (0 until arr.length()).forEach { i -> arr.optJSONObject(i)?.let(all::add) }
            }
            all.firstOrNull { it.optString("id") == matchId }
        }

        val title = matchObj?.optString("title") ?: matchId
        val isLive = matchObj?.optBoolean("live", false) ?: false
        val category = matchObj?.optString("category") ?: "other"
        val date = matchObj?.optLong("date", 0) ?: 0

        // Poster from API or team badges
        val poster = matchObj?.optString("poster")?.takeIf { it.isNotBlank() }?.let { "$mainUrl$it" }
            ?: matchObj?.optJSONObject("teams")?.optJSONObject("home")?.optString("badge")
                ?.takeIf { it.isNotBlank() }?.let { "$mainUrl/api/images/proxy/$it" }

        // Plot with team info
        val plot = buildString {
            val teams = matchObj?.optJSONObject("teams")
            val home = teams?.optJSONObject("home")
            val away = teams?.optJSONObject("away")
            if (home != null && away != null) {
                append("${home.optString("name")} vs ${away.optString("name")}\n\n")
            }
            append("Category: ${categoryLabels[category] ?: category}\n")
            append("Server: $displayName\n")
            if (date > 0) {
                val sdf = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm z", java.util.Locale.ENGLISH)
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
        // data is the watch URL: https://ntv.cx/watch/{server}/{matchId}
        val watchUrl = data.takeIf { it.startsWith("http") }
            ?: "$mainUrl/watch/$serverId/$data"

        val html = runCatching {
            app.get(watchUrl, headers = headers, timeout = 15_000).text
        }.getOrNull() ?: return false

        val doc = Jsoup.parse(html)

        // Parse all source options from the <select> dropdown
        val sources = doc.select("#sourceSelect option, select[class*=source] option, select[class*=watch] option")
            .mapNotNull { opt ->
                val embedPath = opt.attr("value").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = opt.text().trim().ifBlank { "Stream" }
                val embedUrl = if (embedPath.startsWith("http")) embedPath else "$mainUrl$embedPath"
                embedUrl to label
            }
            .distinctBy { it.first }

        if (sources.isEmpty()) return false

        var found = false
        for ((embedUrl, label) in sources) {
            // Try loadExtractor on the embed URL — Cloudstream's built-in extractors
            // handle most iframe-based players (Clappr, HLS.js, etc.)
            runCatching {
                loadExtractor(embedUrl, "$mainUrl/", subtitleCallback) { link ->
                    callback(link)
                    found = true
                }
            }

            // If loadExtractor didn't resolve, fetch the embed page and extract
            // any direct m3u8/mp4 URLs from it
            if (!found) {
                runCatching {
                    val embedHtml = app.get(embedUrl, headers = headers, timeout = 12_000).text
                    extractDirectStreams(embedHtml, embedUrl, label, subtitleCallback, callback) { found = true }
                }
            }

            // Small delay between sources to avoid rate-limiting (prevents 403/3003)
            if (!found) { kotlinx.coroutines.delay(300) }
        }

        return found
    }

    // ── Direct stream extraction from embed HTML ─────────────────────────────

    private suspend fun extractDirectStreams(
        html: String,
        baseUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        onFound: () -> Unit,
    ) {
        // Extract m3u8 URLs from the embed page
        val m3u8Pattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
        val mp4Pattern = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""", RegexOption.IGNORE_CASE)

        val urls = linkedSetOf<String>()
        m3u8Pattern.findAll(html).forEach { urls.add(it.value.replace("\\/", "/")) }
        mp4Pattern.findAll(html).forEach { urls.add(it.value.replace("\\/", "/")) }

        // Also check for encoded URLs in JS variables
        Regex("""source[s]?\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { m ->
                val u = m.groupValues[1]
                if (u.contains("m3u8", true) || u.contains("mp4", true)) urls.add(u.replace("\\/", "/"))
            }

        // Check for iframe sources (nested embeds)
        val iframes = Regex("""<iframe[^>]+src=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()

        // Process iframe embeds via loadExtractor
        for (iframe in iframes) {
            val absUrl = when {
                iframe.startsWith("http") -> iframe
                iframe.startsWith("//") -> "https:$iframe"
                else -> "$mainUrl$iframe"
            }
            runCatching {
                loadExtractor(absUrl, baseUrl, subtitleCallback) { link ->
                    callback(link)
                    onFound()
                }
            }
        }

        // Process direct URLs
        for (url in urls) {
            val cleanUrl = url.trim().replace("&amp;", "&")
            if (cleanUrl.isBlank()) continue

            if (cleanUrl.contains(".m3u8", true)) {
                callback(
                    newExtractorLink(
                        source = "$name • $label",
                        name = label,
                        url = cleanUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = headers
                        this.quality = getQualityFromName(label)
                    }
                )
                onFound()
            } else if (cleanUrl.contains(".mp4", true)) {
                callback(
                    newExtractorLink(
                        source = "$name • $label",
                        name = label,
                        url = cleanUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = headers
                        this.quality = getQualityFromName(label)
                    }
                )
                onFound()
            }
        }
    }

    // ── API helper ───────────────────────────────────────────────────────────

    private suspend fun fetchMatches(): JSONObject {
        val res = app.get(
            "$mainUrl/api/get-matches?server=$serverId&type=both",
            headers = headers,
            timeout = 15_000
        )
        return JSONObject(res.text)
    }

    // ── JSON → SearchResponse ────────────────────────────────────────────────

    private fun JSONObject.toSearchResponse(): SearchResponse? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = optString("title").takeIf { it.isNotBlank() } ?: return null
        val isLive = optBoolean("live", false)
        val category = optString("category")
        val date = optLong("date", 0)

        // Poster: use match poster, or team badge
        val poster = optString("poster").takeIf { it.isNotBlank() }?.let { "$mainUrl$it" }
            ?: optJSONObject("teams")?.optJSONObject("home")?.optString("badge")
                ?.takeIf { it.isNotBlank() }?.let { "$mainUrl/api/images/proxy/$it" }

        val displayTitle = if (isLive) "🔴 $title" else title
        val url = "$mainUrl/watch/$serverId/$id"

        return newMovieSearchResponse(displayTitle, url, TvType.Live) {
            this.posterUrl = poster
        }
    }
}
