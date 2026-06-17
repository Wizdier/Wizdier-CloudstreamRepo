package com.wizdier

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NTVStreamKobra : NTVStreamProvider("kobra", "NTVStream Kobra", true)
class NTVStreamRaptor : NTVStreamProvider("raptor", "NTVStream Raptor", false)
class NTVStreamFalcon : NTVStreamProvider("falcon", "NTVStream Falcon", false)
class NTVStreamPhoenix : NTVStreamProvider("phoenix", "NTVStream Phoenix", false)
class NTVStreamViper : NTVStreamProvider("viper", "NTVStream Viper", false)

abstract class NTVStreamProvider(
    private val serverId: String,
    private val providerName: String,
    private val supportsLiveSections: Boolean,
) : MainAPI() {
    final override var mainUrl = "https://ntv.cx"
    final override var name = providerName

    private val siteBases = listOf(
        "https://ntv.cx",
        "https://www.ntv.cx",
        "http://ntv.cx",
    )
    final override val hasMainPage = true
    final override val hasDownloadSupport = false
    final override val hasQuickSearch = true
    final override val hasChromecastSupport = true
    final override var lang = "en"
    private val liveTvType: TvType = TvType.Live
    final override val supportedTypes: Set<TvType> = setOf(TvType.Live)

    private data class WatchEmbed(
        val url: String,
        val label: String?,
    )

    private data class EventArtwork(
        val poster: String,
        val background: String? = null,
    )

    private val teamLogoCache = mutableMapOf<String, String?>()

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val webHeaders = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    private fun apiHeaders(base: String): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$base/",
        "Accept" to "application/json,text/plain,*/*",
    )

    final override val mainPage = mainPageOf(*serverMainPagePairs().toTypedArray())

    final override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val matches = fetchMatches()
        val filteredRaw = when {
            request.data == "all" -> matches
            request.data == "featured" -> matches.filter { it.optBoolean("popular", false) }.ifEmpty { matches }
            request.data == "live" -> matches.filter { it.isLiveEvent() }
            request.data.startsWith("cat:") -> {
                val cat = request.data.removePrefix("cat:")
                matches.filter { it.optString("category", "").equals(cat, true) || it.optString("tournament", "").equals(cat, true) }
            }
            else -> matches.filter { it.matchesSection(request.data) }
        }
        // Several upstream servers do not expose a reliable live flag and use
        // generic categories such as "Live Stream". Do not leave the Cloudstream
        // homepage with broken/empty rows; fall back to the full list.
        val filtered = filteredRaw.ifEmpty { if (request.data == "live" || !supportsLiveSections) matches else emptyList() }
        val pageSize = 40
        val from = ((page - 1).coerceAtLeast(0)) * pageSize
        val pageItems = filtered.drop(from).take(pageSize)
        val list = buildList {
            pageItems.forEach { event -> event.toSearchResponse()?.let(::add) }
        }
        return newHomePageResponse(
            HomePageList(request.name, list, isHorizontalImages = false),
            hasNext = filtered.size > from + pageSize
        )
    }

    final override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val root = apiJson("/api/search?q=${q.encodeUrl()}&server=$serverId") ?: return emptyList()
        val arr = root.optJSONArray("data") ?: JSONArray()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.toSearchResponse()?.let(out::add)
        }
        return out.distinctBy { it.url }
    }

    final override suspend fun load(url: String): LoadResponse? {
        val event = decodeEvent(url) ?: return null
        val eventId = event.optStringOrNull("id") ?: return null
        val fresh = findEvent(eventId) ?: event
        val title = cleanEventDisplayTitle(fresh.optStringOrNull("title") ?: "Live Event")
        val category = fresh.optStringOrNull("category") ?: "Sports"
        val tournament = fresh.optStringOrNull("tournament")
        val date = fresh.optLong("date", 0L).takeIf { it > 0L }
        val live = fresh.optBoolean("live", false) || fresh.optString("status", "").equals("live", true)
        val sources = fresh.optJSONArray("sources") ?: JSONArray()
        val artwork = eventArtwork(fresh)
        val data = JSONObject()
            .put("server", serverId)
            .put("eventId", eventId)
            .put("sources", sources)
            .toString()

        return newMovieLoadResponse(title, url, liveTvType, data) {
            posterUrl = artwork.poster
            backgroundPosterUrl = artwork.background ?: artwork.poster
            year = date?.let { yearFromMillis(it) }
            plot = buildString {
                append(if (live) "LIVE" else "Scheduled")
                date?.let { append(" â€¢ ").append(formatDate(it)) }
                append("\n")
                append("Server: ").append(serverLabel())
                append("\nSport: ").append(category)
                tournament?.takeIf { it.isNotBlank() && !it.equals(category, true) }?.let {
                    append("\nTournament: ").append(it)
                }
                append("\nSources: ").append(sources.length())
                val teams = teamLine(fresh)
                if (teams.isNotBlank()) append("\n\n").append(teams)
            }
            tags = buildList {
                add(serverLabel())
                add(category)
                tournament?.takeIf { it.isNotBlank() && !it.equals(category, true) }?.let(::add)
                add(if (live) "Live" else "Scheduled")
            }.distinct()
        }
    }

    final override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return false
        val eventId = root.optStringOrNull("eventId") ?: return false
        val sources = root.optJSONArray("sources") ?: JSONArray()
        val candidates = linkedMapOf<String, String>()

        val prioritized = prioritizeSources(sources)
        for (i in prioritized.indices) {
            val src = prioritized[i]
            val label = sourceLabel(src, i + 1)
            src.optStringOrNull("url")?.let { candidates[it.toAbsoluteUrl()] = label }
        }

        // Kobra keeps the real embed urls only in the watch page. For other
        // servers the API URLs are more direct and much faster; only fall back
        // to watch scraping when API sources are missing.
        val watchPath = "/watch/$serverId/$eventId"
        val watchUrl = mainUrl + watchPath
        if (serverId == "kobra" || candidates.isEmpty()) {
            val embeds = prioritizeWatchEmbeds(collectWatchEmbeds(watchPath))
            embeds.forEachIndexed { index, embed ->
                candidates.putIfAbsent(embed.url.toAbsoluteUrl(), embed.label ?: "${serverLabel()} ${index + 1}")
            }
        }

        var found = false
        val seen = linkedSetOf<String>()
        candidates.forEach { (rawUrl, label) ->
            if (rawUrl.isBlank() || !seen.add(rawUrl)) return@forEach
            if (resolveCandidate(rawUrl, label.cleanSourceLabel(), watchUrl, 0, seen, subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }

    private fun prioritizeSources(sources: JSONArray): List<JSONObject> {
        val all = (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
        if (all.size <= 24) return all
        val priorityWords = listOf(
            "itv", "sbs", "fox", "telemundo", "tsn", "ctv", "bein", "dazn",
            "npo", "rte", "srf", "sport tv", "tvp", "m6", "rai", "sky"
        )
        return all.sortedBy { src ->
            val text = (src.optString("channelName", "") + " " + src.optString("source", "")).lowercase()
            val index = priorityWords.indexOfFirst { it in text }
            if (index >= 0) index else 999
        }
    }

    private fun prioritizeWatchEmbeds(embeds: List<WatchEmbed>): List<WatchEmbed> {
        if (serverId != "kobra" || embeds.size <= 6) return embeds

        val priorityWords = listOf("admin", "delta", "itv", "fox", "telemundo", "echo", "golf")
        return embeds
            .sortedWith(
                compareByDescending<WatchEmbed> { it.label?.contains("[HD]", true) == true }
                    .thenBy { embed ->
                        val label = embed.label.orEmpty().lowercase()
                        priorityWords.indexOfFirst { it in label }.let { if (it >= 0) it else 999 }
                    }
                    .thenBy { embed ->
                        val label = embed.label.orEmpty().lowercase()
                        when {
                            "stream 1" in label -> 0
                            "stream 3" in label -> 1
                            else -> 2
                        }
                    }
            )
            .distinctBy { embed ->
                embed.label.orEmpty()
                    .replace(Regex("(?i)stream\\s*\\d+"), "")
                    .replace(Regex("(?i)\\[HD]"), "")
                    .cleanSourceLabel()
                    .lowercase()
            }
    }

    private suspend fun resolveCandidate(
        rawUrl: String,
        label: String,
        referer: String,
        depth: Int,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val url = rawUrl.toAbsoluteUrl(referer)
        val decodedMedia = mediaFromUrlParameter(url)
        if (decodedMedia != null && seen.add(decodedMedia)) {
            return emitMedia(decodedMedia, label, url, callback, subtitleCallback)
        }
        if (url.isMediaUrl()) return emitMedia(url, label, referer, callback, subtitleCallback)

        val hostPlayer = fetchKnownHostPlayer(url)
        if (hostPlayer != null && seen.add(hostPlayer)) {
            return resolveCandidate(hostPlayer, label, url, depth + 1, seen, subtitleCallback, callback)
        }
        if (depth > 4) return false

        var found = false

        if (isBrowserOnlyEmbed(url)) {
            if (resolveBrowserOnlyEmbed(url, label, referer, subtitleCallback, callback)) return true
        }

        val html = runCatching { app.get(url, headers = headersFor(referer), timeout = 12000).text }.getOrNull() ?: return false
        decodeProtectedConfigMedia(html)?.let { protectedMedia ->
            if (seen.add(protectedMedia) && emitMedia(protectedMedia, label, url, callback, subtitleCallback)) return true
        }
        extractAtobMedia(html).forEach { media ->
            if (seen.add(media) && emitMedia(media, label, url, callback, subtitleCallback)) found = true
        }
        if (found) return true
        val doc = Jsoup.parse(html, url)
        collectSubtitles(doc, html, url, subtitleCallback)

        val nested = linkedSetOf<String>()
        doc.select("iframe[src], video[src], source[src], a[href]").forEach { el ->
            val attr = el.attr("src").ifBlank { el.attr("href") }.htmlDecode()
            if (attr.isNotBlank()) nested += attr.toAbsoluteUrl(url)
        }
        Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv|\.webm|\.m4v)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapTo(nested) { it.value.htmlDecode() }
        Regex("""(?:file|source|src|url)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1].htmlDecode() }
            .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains("/embed", true) || it.contains("/stream", true) }
            .mapTo(nested) { it.toAbsoluteUrl(url) }

        for (next in nested) {
            if (next.isBlank() || !seen.add(next)) continue
            if (resolveCandidate(next, label, url, depth + 1, seen, subtitleCallback, callback)) found = true
        }
        if (found) return true

        // Last fallback only. Some generic extractors return stale/bad links for
        // DLHD/embed pages, so direct parsing above must win first.
        runCatching {
            loadExtractor(url, referer, subtitleCallback) {
                callback(it)
                found = true
            }
        }
        return found
    }

    private fun getCurrentActivity(): android.app.Activity? {
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activities = activitiesField.get(activityThread) as Map<*, *>
            for (activityRecord in activities.values) {
                val activityRecordClass = activityRecord::class.java
                val pausedField = activityRecordClass.getDeclaredField("paused").apply { isAccessible = true }
                if (!pausedField.getBoolean(activityRecord)) {
                    val activityField = activityRecordClass.getDeclaredField("activity").apply { isAccessible = true }
                    return activityField.get(activityRecord) as android.app.Activity
                }
            }
            null
        }.getOrNull() ?: com.lagradost.cloudstream3.CommonActivity.activity
    }

    private suspend fun resolveUsingActiveActivityWebView(
        url: String,
        referer: String,
        timeout: Long = 25000L
    ): String? {
        val activity = getCurrentActivity() ?: return null
        val deferred = CompletableDeferred<String?>()

        withContext(Dispatchers.Main) {
            val webView = android.webkit.WebView(activity).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = userAgent
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }

                // Add Javascript interface to intercept JS fetch/XHR requests (critical for hls.js / Clappr on Android TV)
                val jsBridge = object {
                    @android.webkit.JavascriptInterface
                    fun onUrlIntercepted(url: String) {
                        deferred.complete(url)
                    }
                }
                addJavascriptInterface(jsBridge, "JSBridge")

                webViewClient = object : android.webkit.WebViewClient() {
                    private fun injectBridge(view: android.webkit.WebView?) {
                        val js = """
                            (function() {
                                var originalFetch = window.fetch;
                                if (originalFetch) {
                                    window.fetch = function(input, init) {
                                        var url = (typeof input === 'string') ? input : (input && input.url);
                                        if (url && (url.indexOf('.m3u8') !== -1 || url.indexOf('strmd.st') !== -1)) {
                                            if (window.JSBridge) window.JSBridge.onUrlIntercepted(url);
                                        }
                                        return originalFetch.apply(this, arguments);
                                    };
                                }
                                var originalOpen = XMLHttpRequest.prototype.open;
                                if (originalOpen) {
                                    XMLHttpRequest.prototype.open = function(method, url) {
                                        if (url && (url.indexOf('.m3u8') !== -1 || url.indexOf('strmd.st') !== -1)) {
                                            if (window.JSBridge) window.JSBridge.onUrlIntercepted(url);
                                        }
                                        return originalOpen.apply(this, arguments);
                                    };
                                }
                                var tryPlay = function() {
                                    var p = document.getElementById('player');
                                    if (p) p.click();
                                    var playBtn = document.querySelector('.jw-display-icon-container, .clappr-play-button, video, .vjs-big-play-button');
                                    if (playBtn) {
                                        playBtn.click();
                                        var video = document.querySelector('video');
                                        if (video) {
                                            video.muted = true;
                                            video.play().catch(function(e) {});
                                        }
                                    }
                                };
                                tryPlay();
                                setInterval(tryPlay, 1000);
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }

                    override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        injectBridge(view)
                        super.onPageStarted(view, url, favicon)
                    }

                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        injectBridge(view)
                        super.onPageFinished(view, url)
                    }

                    override fun shouldInterceptRequest(
                        view: android.webkit.WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val reqUrl = request?.url?.toString().orEmpty()
                        if (reqUrl.contains(".m3u8", true) || reqUrl.contains("strmd.st", true)) {
                            deferred.complete(reqUrl)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onLoadResource(view: android.webkit.WebView?, url: String?) {
                        val reqUrl = url.orEmpty()
                        if (reqUrl.contains(".m3u8", true) || reqUrl.contains("strmd.st", true)) {
                            deferred.complete(reqUrl)
                        }
                        super.onLoadResource(view, url)
                    }

                    override fun onReceivedSslError(
                        view: android.webkit.WebView?,
                        handler: android.webkit.SslErrorHandler?,
                        error: android.net.http.SslError?
                    ) {
                        handler?.proceed()
                    }

                    override fun shouldOverrideUrlLoading(
                        view: android.webkit.WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        return true
                    }
                }
            }

            val root = activity.window.decorView as? android.view.ViewGroup
            val params = android.view.ViewGroup.LayoutParams(1, 1)
            root?.addView(webView, params)

            deferred.invokeOnCompletion {
                activity.runOnUiThread {
                    runCatching {
                        root?.removeView(webView)
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }

            val headers = headersFor(referer)
            webView.loadUrl(url, headers)
        }

        return withTimeoutOrNull(timeout) {
            deferred.await()
        }
    }

    private suspend fun resolveBrowserOnlyEmbed(
        url: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // 1. Try our high-compatibility active activity-attached WebView resolution first!
        val m3u8Url = resolveUsingActiveActivityWebView(url, referer)
        if (m3u8Url != null) {
            emitDirectM3u8(
                streamUrl = m3u8Url,
                label = label,
                referer = url,
                headers = mediaHeadersFor(m3u8Url, url),
                callback = callback
            )
            return true
        }

        // 2. Fall back to standard WebViewResolver (useOkhttp = false)
        var resolved = runCatching {
            WebViewResolver(
                interceptUrl = Regex("""(?i)(?:\.m3u8(?:\?|$)|strmd\.st/.+?\.m3u8)"""),
                additionalUrls = listOf(Regex("""(?i)(?:\.m3u8(?:\?|$)|strmd\.st/.+?\.m3u8)""")),
                userAgent = userAgent,
                useOkhttp = false,
                timeout = 45_000L,
            ).resolveUsingWebView(
                url = url,
                referer = referer,
                headers = headersFor(referer),
            )
        }.getOrNull()

        // 3. Fall back to WebViewResolver (useOkhttp = true)
        if (resolved == null || (resolved.first == null && resolved.second.isEmpty())) {
            resolved = runCatching {
                WebViewResolver(
                    interceptUrl = Regex("""(?i)(?:\.m3u8(?:\?|$)|strmd\.st/.+?\.m3u8)"""),
                    additionalUrls = listOf(Regex("""(?i)(?:\.m3u8(?:\?|$)|strmd\.st/.+?\.m3u8)""")),
                    userAgent = userAgent,
                    useOkhttp = true,
                    timeout = 45_000L,
                ).resolveUsingWebView(
                    url = url,
                    referer = referer,
                    headers = headersFor(referer),
                )
            }.getOrNull()
        }

        if (resolved == null) return false

        val requests = buildList {
            resolved.first?.let(::add)
            addAll(resolved.second)
        }
            .filter { it.url.toString().contains(".m3u8", true) }
            .distinctBy { it.url.toString() }
            .sortedWith(compareBy(
                { req -> if (req.url.toString().contains("/high/", true)) 0 else 1 },
                { req -> if (req.url.toString().contains("mono.m3u8", true)) 0 else 1 },
            ))
            .take(1)

        var found = false
        requests.forEach { req ->
            val streamUrl = req.url.toString()
            val refererHeader = req.header("Referer") ?: mediaRefererFor(streamUrl, url)
            val capturedHeaders = req.headers.names().associateWith { key -> req.header(key).orEmpty() }
            emitDirectM3u8(
                streamUrl = streamUrl,
                label = label,
                referer = refererHeader,
                headers = mediaHeadersFor(streamUrl, refererHeader) + capturedHeaders,
                callback = callback,
            )
            found = true
        }
        return found
    }

    private suspend fun emitDirectM3u8(
        streamUrl: String,
        label: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
    ) {
        val sourceName = "$name â€¢ ${label.ifBlank { qualityInitials(streamUrl) ?: "Stream" }}"
        callback(
            newExtractorLink(
                source = sourceName,
                name = sourceName,
                url = streamUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = referer
                this.quality = when {
                    streamUrl.contains("/high/", true) -> Qualities.P720.value
                    streamUrl.contains("/low/", true) -> Qualities.P480.value
                    else -> getQualityFromName(streamUrl)
                }
                this.headers = headers
            }
        )
    }

    private suspend fun emitMedia(
        url: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        val sourceName = "$name â€¢ ${label.ifBlank { qualityInitials(url) ?: "Stream" }}"
        val mediaReferer = mediaRefererFor(url, referer)
        if (url.contains(".m3u8", true)) {
            val mediaHeaders = mediaHeadersFor(url, mediaReferer)
            if (hostOf(url).lowercase().endsWith("strmd.st")) {
                emitDirectM3u8(url, label, mediaReferer, mediaHeaders, callback)
            } else {
                collectM3u8Subtitles(url, mediaReferer, subtitleCallback)
                M3u8Helper.generateM3u8(
                    source = sourceName,
                    streamUrl = url,
                    referer = mediaReferer,
                    headers = mediaHeaders,
                ).forEach(callback)
            }
        } else {
            callback(
                newExtractorLink(
                    source = sourceName,
                    name = "$sourceName - Direct",
                    url = url,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = mediaReferer
                    this.quality = getQualityFromName(url)
                }
            )
        }
        return true
    }

    private fun serverMainPagePairs(): List<Pair<String, String>> = when (serverId) {
        "kobra" -> listOf(
            "live" to "Live Now",
            "featured" to "Featured / Popular",
            "cat:football" to "Football",
            "cat:baseball" to "Baseball",
            "cat:basketball" to "Basketball",
            "cat:rugby" to "Rugby",
            "cat:american-football" to "American Football",
            "cat:fight" to "Fight / MMA",
            "cat:tennis" to "Tennis",
            "cat:other" to "Other Sports",
            "all" to "All Events",
        )
        "raptor" -> listOf(
            "all" to "All Events",
            "featured" to "Featured / Popular",
            "cat:Football" to "Football",
            "cat:Baseball" to "Baseball",
            "cat:Rugby" to "Rugby",
            "cat:American Football" to "American Football",
            "cat:Australian Football" to "Australian Football",
            "cat:Wrestling" to "Wrestling",
            "cat:Basketball" to "Basketball",
        )
        "falcon" -> listOf(
            "all" to "All Live Streams",
            "featured" to "Featured",
            "football" to "Football / World Cup",
            "other" to "Other Streams",
        )
        "phoenix" -> listOf(
            "all" to "All Events",
            "featured" to "Featured / Popular",
            "cat:All Soccer Events" to "Soccer",
            "cat:Upcoming Events" to "Upcoming Events",
            "cat:Baseball (MLB)" to "Baseball / MLB",
            "cat:Basketball" to "Basketball",
            "cat:TV Shows" to "TV Shows / 24-7",
            "cat:ATP - HALLE | ATP - LONDON | WTA - BERLIN | WTA - NOTTINGHAM" to "Tennis Live",
            "tennis" to "All Tennis",
            "motorsport" to "Motorsport",
            "combat" to "MMA / Boxing",
            "rugby" to "Rugby",
            "other" to "Other Sports",
        )
        "viper" -> listOf(
            "all" to "All Events",
            "featured" to "Featured / Popular",
            "cat:Football" to "Football",
            "cat:MMA" to "MMA",
            "cat:Boxing" to "Boxing",
            "cat:Motorsport" to "Motorsport",
            "cat:Rugby" to "Rugby",
            "cat:Hockey" to "Hockey",
            "cat:Basketball" to "Basketball",
        )
        else -> listOf("all" to "All Events")
    }

    private suspend fun fetchMatches(): List<JSONObject> {
        val root = apiJson("/api/get-matches?server=$serverId&type=both") ?: return emptyList()
        val out = linkedMapOf<String, JSONObject>()
        listOf("live", "nonLive", "all", "data").forEach { key ->
            val arr = root.optJSONArray(key) ?: return@forEach
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optStringOrNull("id") ?: continue
                out[id] = obj
            }
        }
        // Servers with supports_live=false return everything in all[]. For Kobra,
        // live/nonLive are authoritative but all can appear during cache warmup.
        if (!supportsLiveSections && out.isEmpty()) {
            root.optJSONArray("all")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out[it.optString("id", i.toString())] = it }
            }
        }
        return mergeSimilarEvents(out.values.toList()).sortedBy { it.optLong("date", Long.MAX_VALUE) }
    }

    private fun mergeSimilarEvents(events: List<JSONObject>): List<JSONObject> {
        val grouped = linkedMapOf<String, JSONObject>()
        events.forEach { event ->
            val title = event.optStringOrNull("title") ?: return@forEach
            val category = event.optString("category", "")
            val dateBucket = event.optLong("date", 0L).takeIf { it > 0L }?.let { it / (3 * 60 * 60 * 1000L) } ?: 0L
            val key = "${eventMergeKey(title)}|${category.lowercase()}|$dateBucket"
            val current = grouped[key]
            if (current == null) {
                grouped[key] = JSONObject(event.toString()).apply {
                    put("title", cleanEventDisplayTitle(title))
                }
            } else {
                val mergedSources = current.optJSONArray("sources") ?: JSONArray().also { current.put("sources", it) }
                val src = event.optJSONArray("sources")
                if (src != null) {
                    for (i in 0 until src.length()) {
                        val obj = src.optJSONObject(i) ?: continue
                        val sourceKey = obj.optString("url", obj.optString("id", i.toString()))
                        var exists = false
                        for (j in 0 until mergedSources.length()) {
                            val old = mergedSources.optJSONObject(j) ?: continue
                            if (old.optString("url", old.optString("id", "")) == sourceKey) {
                                exists = true
                                break
                            }
                        }
                        if (!exists) mergedSources.put(JSONObject(obj.toString()))
                    }
                }
                if (!current.optBoolean("live", false) && event.optBoolean("live", false)) current.put("live", true)
            }
        }
        return grouped.values.toList()
    }

    private suspend fun findEvent(id: String): JSONObject? =
        fetchMatches().firstOrNull { it.optString("id") == id }

    private suspend fun apiJson(path: String): JSONObject? {
        for (base in siteBases) {
            val root = runCatching {
                val res = app.get(base + path, headers = apiHeaders(base), timeout = 15000)
                if (res.code in 200..299) JSONObject(res.text.trim()) else null
            }.getOrNull()
            if (root != null) return root
        }
        return null
    }

    private suspend fun collectWatchEmbeds(watchPath: String): List<WatchEmbed> {
        var html: String? = null
        var watchUrl = mainUrl + watchPath
        for (base in siteBases) {
            val candidate = base + watchPath
            val text = runCatching { app.get(candidate, headers = webHeaders + ("Referer" to "$base/"), timeout = 12000).text }.getOrNull()
            if (!text.isNullOrBlank()) {
                html = text
                watchUrl = candidate
                break
            }
        }
        val page = html ?: return emptyList()
        val doc = Jsoup.parse(page, watchUrl)
        val out = linkedMapOf<String, WatchEmbed>()
        doc.select("select option, option[value*='/embed'], option[value*='/watch/']").forEach { el ->
            val value = el.attr("value").htmlDecode()
            if (value.isNotBlank() && (value.contains("/embed") || value.contains("/watch/") || value.contains("t="))) {
                val abs = value.toAbsoluteUrl(watchUrl)
                out[abs] = WatchEmbed(abs, el.text().cleanSourceLabel())
            }
        }
        doc.select("iframe[src*='/embed'], iframe[src]").forEach { el ->
            val value = el.attr("src").htmlDecode()
            if (value.isNotBlank()) {
                val abs = value.toAbsoluteUrl(watchUrl)
                out.putIfAbsent(abs, WatchEmbed(abs, "${serverLabel()} Player"))
            }
        }
        return out.values.toList()
    }

    private suspend fun JSONObject.toSearchResponse(): SearchResponse? {
        val title = optStringOrNull("title") ?: return null
        val url = encodeEvent(this)
        val artwork = eventArtwork(this)
        val date = optLong("date", 0L).takeIf { it > 0L }
        return newMovieSearchResponse(cleanEventDisplayTitle(title), url, liveTvType) {
            posterUrl = artwork.poster
            year = date?.let { yearFromMillis(it) }
        }
    }

    private fun encodeEvent(event: JSONObject): String {
        val slim = JSONObject()
            .put("server", serverId)
            .put("id", event.optString("id"))
            .put("title", event.optString("title"))
            .put("category", event.optString("category"))
            .put("tournament", event.optString("tournament"))
            .put("date", event.optLong("date", 0L))
            .put("live", event.optBoolean("live", false))
            .put("poster", event.optString("poster"))
            .put("sources", event.optJSONArray("sources") ?: JSONArray())
        val data = Base64.encodeToString(
            slim.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "load?data=$data"
    }

    private fun decodeEvent(url: String): JSONObject? = runCatching {
        if (!url.contains("load?data=")) return@runCatching null
        val data = url.substringAfter("load?data=").substringBefore("&")
        JSONObject(String(Base64.decode(data, Base64.URL_SAFE)))
    }.getOrNull()

    private suspend fun collectSubtitles(
        doc: Document,
        html: String,
        baseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val seen = linkedSetOf<String>()
        doc.select("track[src], a[href*='.srt'], a[href*='.vtt'], a[href*='.ass']").forEach { el ->
            val raw = el.attr("src").ifBlank { el.attr("href") }.htmlDecode()
            val subUrl = raw.toAbsoluteUrl(baseUrl)
            if (subUrl.isNotBlank() && seen.add(subUrl)) {
                val label = el.attr("label").ifBlank { el.attr("srclang") }.ifBlank { subtitleLabel(subUrl) }
                subtitleCallback(newSubtitleFile(label, subUrl))
            }
        }
        Regex("""https?://[^\s"'<>]+\.(?:srt|vtt|ass)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.htmlDecode() }
            .forEach { subUrl ->
                if (subUrl.isNotBlank() && seen.add(subUrl)) subtitleCallback(newSubtitleFile(subtitleLabel(subUrl), subUrl))
            }
    }

    private suspend fun collectM3u8Subtitles(
        manifestUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val text = runCatching { app.get(manifestUrl, headers = headersFor(referer), timeout = 10000).text }.getOrNull() ?: return
        Regex("""#EXT-X-MEDIA:([^\r\n]+)""", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            val attrs = match.groupValues[1]
            if (!attrs.contains("TYPE=SUBTITLES", true)) return@forEach
            val uri = parseM3u8Attribute(attrs, "URI") ?: return@forEach
            val subUrl = uri.toAbsoluteUrl(manifestUrl)
            val label = parseM3u8Attribute(attrs, "NAME") ?: parseM3u8Attribute(attrs, "LANGUAGE") ?: subtitleLabel(subUrl)
            subtitleCallback(newSubtitleFile(label, subUrl))
        }
    }

    private fun parseM3u8Attribute(attrs: String, key: String): String? =
        Regex("""$key=(?:"([^"]*)"|([^,]*))""", RegexOption.IGNORE_CASE)
            .find(attrs)
            ?.let { it.groupValues.getOrNull(1)?.takeIf { s -> s.isNotBlank() } ?: it.groupValues.getOrNull(2) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun mediaFromUrlParameter(url: String): String? = runCatching {
        val query = url.substringAfter("?", "")
        query.split("&").forEach { part ->
            val value = part.substringAfter("=", "")
            val decoded = URLDecoder.decode(value, "UTF-8")
            if (decoded.isMediaUrl()) return@runCatching decoded
        }
        null
    }.getOrNull()

    private fun extractAtobMedia(html: String): List<String> {
        val out = linkedSetOf<String>()
        Regex("""(?:window\.)?atob\(\s*['"]([^'"]+)['"]\s*\)""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { match ->
                val decoded = runCatching { decodeBase64Text(match.groupValues[1], latin1 = false) }.getOrNull()
                    ?: return@forEach
                if (decoded.isMediaUrl()) out += decoded
                Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv|\.webm|\.m4v)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
                    .findAll(decoded)
                    .mapTo(out) { it.value }
            }
        return out.toList()
    }

    private suspend fun fetchKnownHostPlayer(url: String): String? = runCatching {
        val host = hostOf(url).lowercase()
        val id = queryParam(url, "id") ?: return@runCatching null
        val apiUrl = when {
            host.endsWith("sansat.link") || host.endsWith("glisco.link") || host.endsWith("dabac.link") ->
                "${originOf(url)}/api/player.php?id=${id.encodeUrl()}"
            else -> return@runCatching null
        }
        val res = app.get(apiUrl, headers = headersFor(url), timeout = 12000)
        val text = res.text.trim()
        if (res.code !in 200..299 || !text.startsWith("{")) return@runCatching null
        JSONObject(text).optStringOrNull("url")?.toAbsoluteUrl(apiUrl)
    }.getOrNull()

    private fun decodeProtectedConfigMedia(html: String): String? = runCatching {
        var encoded = Regex("""window\[['"]([a-zA-Z0-9_]{16})['"]\]\s*=\s*['"]([^'"]{300,})['"]""")
            .find(html)?.groupValues?.getOrNull(2)
        if (encoded == null) {
            encoded = Regex("""window\._econfig\s*=\s*['"]([^'"]+)['"]""")
                .find(html)?.groupValues?.getOrNull(1)
        }
        if (encoded == null) return@runCatching null
        val first = decodeBase64Text(encoded, latin1 = true)
        val partsCount = 4
        val chunkSize = kotlin.math.ceil(first.length / partsCount.toDouble()).toInt()
        val chunks = (0 until partsCount).map { i ->
            first.substring((i * chunkSize).coerceAtMost(first.length), ((i + 1) * chunkSize).coerceAtMost(first.length))
        }
        val order = listOf(2, 0, 3, 1)
        val sortedParts = arrayOfNulls<String>(partsCount)
        for (i in 0 until partsCount) {
            val chunk = chunks[i]
            val cleaned = if (chunk.length > 3) chunk.substring(0, 3) + chunk.substring(4) else chunk
            sortedParts[order[i]] = decodeBase64Text(cleaned, latin1 = true)
        }
        val joined = sortedParts.filterNotNull().joinToString("")
        val json = decodeBase64Text(joined, latin1 = false)
        val obj = JSONObject(json)
        obj.optStringOrNull("stream_url_nop2p") ?: obj.optStringOrNull("stream_url")
    }.getOrNull()

    private fun decodeBase64Text(value: String, latin1: Boolean): String {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val bytes = Base64.decode(padded, Base64.DEFAULT)
        return if (latin1) String(bytes, Charsets.ISO_8859_1) else String(bytes, Charsets.UTF_8)
    }

    private fun sourceLabel(src: JSONObject, index: Int): String {
        val channel = src.optStringOrNull("channelName")
        val quality = qualityInitials(channel.orEmpty() + " " + src.optString("id", "") + " " + src.optString("url", ""))
        val base = channel ?: src.optStringOrNull("source") ?: "Source $index"
        return listOfNotNull(base.cleanSourceLabel(), quality).distinct().joinToString(" ").ifBlank { "Source $index" }
    }

    private fun teamLine(event: JSONObject): String {
        val teams = event.optJSONObject("teams") ?: return ""
        val home = teams.optJSONObject("home")?.optStringOrNull("name")
        val away = teams.optJSONObject("away")?.optStringOrNull("name")
        return listOfNotNull(home, away).joinToString(" vs ").takeIf { it.isNotBlank() } ?: ""
    }

    private fun headersFor(referer: String): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to referer,
        "Origin" to originOf(referer),
        "Accept" to "*/*",
    )

    private fun mediaRefererFor(mediaUrl: String, parentReferer: String): String {
        val host = hostOf(mediaUrl).lowercase()
        return if (host.endsWith("strmd.st") && parentReferer.contains("embed", true)) {
            originOf(parentReferer).trimEnd('/') + "/"
        } else parentReferer
    }

    private fun mediaHeadersFor(mediaUrl: String, referer: String): Map<String, String> {
        val host = hostOf(mediaUrl).lowercase()
        val base = headersFor(referer)
        return if (host.endsWith("strmd.st")) {
            base + mapOf(
                "Accept-Language" to "en-US,en;q=0.9",
                "DNT" to "1",
                "Sec-GPC" to "1",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
            )
        } else base
    }

    private fun originOf(url: String): String = runCatching {
        val clean = url.substringBefore("?")
        val proto = clean.substringBefore("://")
        val host = clean.substringAfter("://").substringBefore("/")
        "$proto://$host"
    }.getOrDefault(mainUrl)

    private fun hostOf(url: String): String = runCatching {
        url.substringAfter("://").substringBefore("/").substringBefore(":")
    }.getOrDefault("")

    private fun isBrowserOnlyEmbed(url: String): Boolean {
        val host = hostOf(url).lowercase()
        return host.endsWith(".st") || host.contains("embed")
    }

    private fun queryParam(url: String, key: String): String? =
        url.substringAfter("?", "")
            .split("&")
            .firstOrNull { it.substringBefore("=").equals(key, true) }
            ?.substringAfter("=", "")
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?.takeIf { it.isNotBlank() }

    private fun JSONObject.isLiveEvent(): Boolean =
        optBoolean("live", false) || optString("status", "").equals("live", true)

    private fun JSONObject.matchesSection(section: String): Boolean {
        val haystack = listOf(
            optString("category", ""),
            optString("tournament", ""),
            optString("title", ""),
        ).joinToString(" ").lowercase()
        return when (section) {
            "football" -> listOf("football", "soccer", "fifa", "world cup", "usl", "premier", "la liga").any { it in haystack }
            "baseball" -> listOf("baseball", "mlb").any { it in haystack }
            "basketball" -> listOf("basketball", "nba", "wnba").any { it in haystack }
            "combat" -> listOf("fight", "mma", "ufc", "boxing", "combat").any { it in haystack }
            "motorsport" -> listOf("motorsport", "nascar", "formula", "f1", "racing", "sprint cars").any { it in haystack }
            "rugby" -> "rugby" in haystack
            "hockey" -> listOf("hockey", "nhl", "ice hockey").any { it in haystack }
            "tennis" -> "tennis" in haystack
            "other" -> listOf("other", "ppv", "event", "live stream", "golf", "darts", "cricket", "volleyball", "horse", "sailing").any { it in haystack }
            else -> section.lowercase() in haystack
        }
    }

    private fun serverLabel(): String = serverId.replaceFirstChar { it.uppercase() }

    private suspend fun eventArtwork(event: JSONObject): EventArtwork {
        event.optStringOrNull("poster")?.toAbsoluteUrl()?.let { return EventArtwork(it, it) }

        val teams = event.optJSONObject("teams")
        val category = event.optStringOrNull("category") ?: event.optStringOrNull("tournament") ?: serverLabel()
        val home = teams?.optJSONObject("home")?.optStringOrNull("name")
        val away = teams?.optJSONObject("away")?.optStringOrNull("name")

        val homeLogo = home?.let { fetchTeamLogo(it, category) }
        val awayLogo = away?.let { fetchTeamLogo(it, category) }
        if (!homeLogo.isNullOrBlank()) return EventArtwork(homeLogo, awayLogo)
        if (!awayLogo.isNullOrBlank()) return EventArtwork(awayLogo, homeLogo)

        return EventArtwork(
            generatedPoster(
                title = cleanEventDisplayTitle(event.optStringOrNull("title") ?: "Live Sports"),
                category = category,
                live = event.isLiveEvent(),
            )
        )
    }

    private suspend fun fetchTeamLogo(teamName: String, category: String): String? {
        val sport = sportsDbSport(category)
        val cacheKey = "${teamName.lowercase()}|${sport.orEmpty().lowercase()}"
        if (teamLogoCache.containsKey(cacheKey)) return teamLogoCache[cacheKey]

        val found = runCatching {
            val root = JSONObject(
                app.get(
                    "https://www.thesportsdb.com/api/v1/json/3/searchteams.php?t=${teamName.encodeUrl()}",
                    headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json"),
                    timeout = 8000
                ).text
            )
            val arr = root.optJSONArray("teams") ?: return@runCatching null
            val normalizedTarget = teamName.normalizedAssetName()
            val candidates = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            val picked = candidates.firstOrNull { team ->
                val sameSport = sport == null || team.optStringOrNull("strSport")?.equals(sport, true) == true
                sameSport && team.optStringOrNull("strTeam")?.normalizedAssetName() == normalizedTarget
            } ?: candidates.firstOrNull { team ->
                sport == null || team.optStringOrNull("strSport")?.equals(sport, true) == true
            } ?: candidates.firstOrNull()
            picked?.optStringOrNull("strBadge")
                ?: picked?.optStringOrNull("strLogo")
                ?: picked?.optStringOrNull("strTeamBadge")
        }.getOrNull()?.toAbsoluteUrl()

        teamLogoCache[cacheKey] = found
        return found
    }

    private fun sportsDbSport(category: String): String? {
        val c = category.lowercase()
        return when {
            "football" in c || "soccer" in c -> "Soccer"
            "basketball" in c -> "Basketball"
            "baseball" in c || "mlb" in c -> "Baseball"
            "hockey" in c -> "Ice Hockey"
            "rugby" in c -> "Rugby"
            "volleyball" in c -> "Volleyball"
            "american football" in c -> "American Football"
            else -> null
        }
    }

    private fun generatedPoster(title: String, category: String, live: Boolean): String {
        // Cloudstream image loaders are not consistent with data: SVG posters,
        // so use a real HTTPS image endpoint for clean fallback posters.
        val headline = title.chunkedWords(maxChars = 16, maxLines = 4).joinToString("\n")
        val badge = if (live) "LIVE" else serverLabel().uppercase()
        val label = "$badge\n${category.uppercase().take(22)}\n\n$headline"
        val bg = if (live) "160B0B" else "0B1020"
        val fg = if (live) "FFDD55" else "FACC15"
        return "https://placehold.co/500x750/$bg/$fg/png?font=montserrat&text=${label.encodeUrl()}"
    }

    private fun qualityInitials(text: String): String? {
        val raw = text.replace("%20", " ").replace("_", " ").replace("-", " ")
        val parts = linkedSetOf<String>()
        if (Regex("(?i)\\b3d\\b").containsMatchIn(raw)) parts += "3D"
        if (Regex("(?i)\\b(?:4k|2160p|uhd)\\b").containsMatchIn(raw)) {
            parts += "4K"
        } else {
            Regex("(?i)\\b(1080|720|576|540|480|360)p\\b")
                .findAll(raw)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull()
                ?.let { parts += "${it}p" }
        }
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun subtitleLabel(url: String): String {
        val file = url.substringBefore("?").substringAfterLast('/').lowercase()
        return when {
            "eng" in file || "english" in file -> "English"
            "spa" in file || "spanish" in file -> "Spanish"
            "fre" in file || "french" in file -> "French"
            else -> "Subtitle"
        }
    }

    private fun formatDate(ms: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(ms))

    private fun yearFromMillis(ms: Long): Int? =
        SimpleDateFormat("yyyy", Locale.US).format(Date(ms)).toIntOrNull()

    private fun String.toAbsoluteUrl(baseUrl: String = mainUrl): String = when {
        startsWith("//") -> baseUrl.substringBefore("://", "https") + ":$this"
        startsWith("http", true) -> this
        startsWith("/") -> mainUrl + this
        baseUrl == mainUrl -> "$mainUrl/${trimStart('/')}"
        else -> baseUrl.substringBeforeLast("/", mainUrl).trimEnd('/') + "/${trimStart('/')}"
    }

    private fun String.isMediaUrl(): Boolean {
        val clean = substringBefore("?").lowercase()
        return clean.endsWith(".m3u8") || clean.endsWith(".mp4") || clean.endsWith(".mkv") ||
                clean.endsWith(".webm") || clean.endsWith(".m4v") || clean.endsWith(".mov") ||
                clean.endsWith(".avi")
    }

    private fun cleanEventDisplayTitle(title: String): String =
        title.replace(Regex("(?i)^\\s*(?:fifa\\s+)?world\\s+cup\\s+2026\\s+"), "")
            .replace(Regex("(?i)\\s+(?:eng|english|hd\\s*\\d*|hd\\d*|nl|swe|swa|jp|fr|es|cn\\s*hd|zh|uk|usa)\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { title.cleanTitle() }

    private fun eventMergeKey(title: String): String = cleanEventDisplayTitle(title)
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun String.normalizedAssetName(): String =
        lowercase()
            .replace(Regex("\\b(?:fc|cf|sc|afc|club|national|team)\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun String.cleanTitle(): String =
        replace(Regex("\\s+"), " ").trim()

    private fun String.cleanSourceLabel(): String =
        replace(Regex("(?i)\\b(?:server|stream|source)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '\u2022')
            .ifBlank { this }

    private fun String.xmlEscape(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun String.chunkedWords(maxChars: Int, maxLines: Int): List<String> {
        val words = split(Regex("\\s+")).filter { it.isNotBlank() }
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val next = if (current.isBlank()) word else "$current $word"
            if (next.length <= maxChars) {
                current = next
            } else {
                if (current.isNotBlank()) lines += current
                current = word
                if (lines.size >= maxLines) break
            }
        }
        if (current.isNotBlank() && lines.size < maxLines) lines += current
        return lines.ifEmpty { listOf(take(maxChars)) }
    }

    private fun String.encodeUrl(): String = URLEncoder.encode(this, "UTF-8")
    private fun String.htmlDecode(): String = Jsoup.parse(this).text()

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null
        else optString(key, "").trim().takeIf { it.isNotBlank() && it != "null" }
}