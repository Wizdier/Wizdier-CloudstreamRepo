package com.wizdier

import android.util.Log
import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wizstream custom extractors for vid embed hosts.
 *
 * RESEARCH NOTES (2026-07-17):
 *
 * All major vid hosts use Cloudflare protection that blocks server-side
 * requests. Cloudstream's built-in `CloudflareKiller` interceptor solves
 * the JS challenge by running it in a headless WebView — this works on a
 * real Android device but NOT in a headless test environment.
 *
 * Host flow summary:
 *
 * 1. VsEmbed (vsembed.ru):
 *    vidsrc.to / vidsrc.mov / vidsrc.in / vidsrc.me / vidsrc-embed.su
 *    all return an iframe pointing to vsembed.ru/embed/movie/<id>/
 *    vsembed.ru has JWPlayer with sources: [...] array.
 *    CloudflareKiller bypasses the CF challenge.
 *
 * 2. TwoEmbedCc (2embed.cc):
 *    2embed.cc/embed/<id> has an iframe to streamsrcs.2embed.cc/xps?imdb=<id>
 *    streamsrcs.2embed.cc/xps.js sets iframe.src to
 *      https://play.xpass.top/e/movie/<id>
 *    play.xpass.top has inline JSON:
 *      var data={"playlist":"/vxr/movie/0/playlist.json",...}
 *    /vxr/movie/0/playlist.json returns {playlist:[{sources:[{file:..m3u8}]}]}
 *    (needs CloudflareKiller + auth_token cookie from initial page load)
 *
 * 3. VidLink (vidlink.pro):
 *    Next.js app. /api/b/movie/<id> returns encrypted JSON that needs
 *    WebAssembly to decode. enc-dec.app/api/enc-vidlink can encrypt the
 *    id but there's no dec-vidlink endpoint. Limited support.
 *
 * 4. VidFast (vidfast.pro):
 *    Next.js app. enc-dec.app/api/enc-vidfast?text=<id> returns
 *    {servers, stream, token}. Fetching the servers/stream URL needs
 *    CloudflareKiller + valid session.
 *
 * All extractors below use CloudflareKiller as an interceptor for
 * `app.get()` — this is the same approach Cloudstream's built-in
 * JWPlayer extractor uses.
 */

// ════════════════════════════════════════════════════════════════════════
//  VsEmbed.ru — handles vidsrc.to / vidsrc.mov / vidsrc-embed.su / vidsrc.me
//  All these hosts redirect to vsembed.ru which has the actual JWPlayer.
// ════════════════════════════════════════════════════════════════════════

class VsEmbedExtractor : ExtractorApi() {
    override val name = "VsEmbed"
    override val mainUrl = "https://vsembed.ru"
    override val requiresReferer = true

    private val cfKiller = CloudflareKiller()
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        runCatching {
            val headers = mapOf(
                "User-Agent" to UA,
                "Referer" to (referer ?: mainUrl),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            // CloudflareKiller solves the JS challenge via WebView.
            // On a real Android device this works; in headless test it doesn't.
            val res = app.get(url, headers = headers, interceptor = cfKiller, timeout = 30_000)
            if (res.code !in 200..299) {
                Log.d("VsEmbed", "HTTP ${res.code} for $url")
                return@runCatching
            }
            val html = res.text
            val doc = Jsoup.parse(html, url)

            // Pattern 1: JWPlayer sources: [...] array
            doc.select("script:containsData(sources:)").forEach { script ->
                val data = script.data()
                Regex("""sources\s*:\s*(\[[\s\S]*?\])""")
                    .find(data)?.groupValues?.getOrNull(1)?.let { arrStr ->
                        parseSourcesArray(arrStr, url, callback)
                    }
            }

            // Pattern 2: hls.loadSource("….m3u8")
            Regex("""hls\.loadSource\(["']([^"']+\.m3u8[^"']*)["']\)""")
                .findAll(html).forEach { m ->
                    emitHls(m.groupValues[1], url, callback)
                }

            // Pattern 3: <video><source src="….m3u8"></video>
            doc.select("video source[src], video[src]").forEach { el ->
                val src = el.attr("src")
                if (src.isNotBlank()) emitHls(src, url, callback)
            }

            // Pattern 4: file: "….m3u8" or file: "….mp4" inside JS
            Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4|mkv)[^"']*)["']""")
                .findAll(html).forEach { m ->
                    emitHls(m.groupValues[1], url, callback)
                }

            // Pattern 5: Look for any m3u8 URL anywhere in the HTML
            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { m ->
                    emitHls(m.value, url, callback)
                }

            // Subtitle tracks
            doc.select("track[src]").forEach { el ->
                val src = el.attr("src")
                val label = el.attr("label").ifBlank { el.attr("srclang") }.ifBlank { "Sub" }
                if (src.isNotBlank()) {
                    val absSrc = if (src.startsWith("http")) src
                                 else "${url.substringBeforeLast("/")}/${src.trimStart('/')}"
                    subtitleCallback(SubtitleFile(label, absSrc))
                }
            }
        }.onFailure { Log.d("VsEmbed", "getUrl failed for $url: ${it.message}") }
    }

    private suspend fun parseSourcesArray(
        arrStr: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        runCatching {
            val arr = JSONArray(arrStr)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val file = o.optString("file").ifBlank { o.optString("src") }
                if (file.isBlank()) continue
                val label = o.optString("label").ifBlank { o.optString("type") }
                val quality = parseQuality(label)
                val type = if (file.contains(".m3u8", true)) ExtractorLinkType.M3U8
                           else ExtractorLinkType.VIDEO
                callback(
                    newExtractorLink(
                        source = "VsEmbed",
                        name = "VsEmbed${if (label.isNotBlank()) " — $label" else ""}",
                        url = file,
                        type = type,
                    ) {
                        this.referer = pageUrl
                        this.quality = quality
                    }
                )
            }
        }
    }

    private fun parseQuality(label: String): Int {
        val n = label.lowercase()
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

    private suspend fun emitHls(
        streamUrl: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val u = streamUrl.trim()
        if (u.isBlank()) return
        if (u.contains(".m3u8", true)) {
            M3u8Helper.generateM3u8(
                source = "VsEmbed",
                streamUrl = u,
                referer = pageUrl,
                headers = mapOf("User-Agent" to UA, "Referer" to pageUrl),
            ).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = "VsEmbed",
                    name = "VsEmbed - Direct",
                    url = u,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = pageUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
//  TwoEmbedCc — 2embed.cc iframe → streamsrcs.2embed.cc → play.xpass.top
//
//  Flow:
//    1. GET 2embed.cc/embed/<id> → HTML with iframe data-src="streamsrcs.2embed.cc/xps?imdb=<id>"
//    2. GET streamsrcs.2embed.cc/xps?imdb=<id> → HTML with iframe src="<id>?autostart=true"
//       + inline xps.js that sets iframe.src = "https://play.xpass.top/e/movie/" + src
//    3. GET play.xpass.top/e/movie/<id> → HTML with inline:
//         var data={"playlist":"/vxr/movie/0/playlist.json",...}
//         var backups=[{"url":"/vxr/movie/0/playlist.json",...},{"url":"/vrk/movie/0/playlist.json",...}]
//       (needs CloudflareKiller + auth_token cookie)
//    4. GET play.xpass.top/vxr/movie/0/playlist.json →
//         {"playlist":[{"sources":[{"file":"…m3u8","type":"hls"}]}]}
// ════════════════════════════════════════════════════════════════════════

class TwoEmbedCcExtractor : ExtractorApi() {
    override val name = "2Embed"
    override val mainUrl = "https://www.2embed.cc"
    override val requiresReferer = true

    private val cfKiller = CloudflareKiller()
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        runCatching {
            val headers = mapOf(
                "User-Agent" to UA,
                "Referer" to (referer ?: mainUrl),
            )
            // Step 1: Fetch 2embed.cc embed page
            val res = app.get(url, headers = headers, timeout = 15_000)
            if (res.code !in 200..299) return@runCatching
            val html = res.text

            val doc = Jsoup.parse(html, url)
            // Find the iframe pointing to streamsrcs.2embed.cc
            val iframeSrc = doc.selectFirst("iframe#iframesrc")?.attr("data-src")
                ?: doc.selectFirst("iframe[src*='streamsrcs.2embed.cc']")?.attr("src")
                ?: doc.selectFirst("iframe[src]")?.attr("src")

            if (iframeSrc != null) {
                val absIframe = if (iframeSrc.startsWith("http")) iframeSrc
                                else "${url.substringBeforeLast("/")}/${iframeSrc.trimStart('/')}"
                // Step 2: Fetch streamsrcs.2embed.cc — it redirects to play.xpass.top
                fetchStreamSrcs(absIframe, subtitleCallback, callback)
            }

            // Also extract any direct sources from the 2embed page itself
            extractSourcesFromHtml(html, url, subtitleCallback, callback)
        }.onFailure { Log.d("2Embed", "getUrl failed for $url: ${it.message}") }
    }

    private suspend fun fetchStreamSrcs(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        runCatching {
            val headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "$mainUrl/",
            )
            // streamsrcs.2embed.cc returns HTML with an iframe
            // The xps.js script rewrites the iframe src to play.xpass.top/e/movie/<id>
            val res = app.get(iframeUrl, headers = headers, timeout = 15_000)
            if (res.code !in 200..299) return@runCatching
            val html = res.text

            // Extract the imdb id from the iframe src (e.g. "tt0388629?autostart=true")
            val doc = Jsoup.parse(html, iframeUrl)
            val innerIframeSrc = doc.selectFirst("iframe#framesrc")?.attr("src")
                ?: doc.selectFirst("iframe[src]")?.attr("src")
            val imdbId = innerIframeSrc?.substringBefore("?")?.substringAfterLast("/")
                ?: iframeUrl.substringAfter("imdb=").substringBefore("&")

            if (imdbId.isNotBlank()) {
                // Step 3: Fetch play.xpass.top with CloudflareKiller
                val xpassUrl = "https://play.xpass.top/e/movie/$imdbId"
                fetchXpass(xpassUrl, imdbId, "movie", null, null, subtitleCallback, callback)
            }
        }
    }

    /**
     * Fetch play.xpass.top/e/{movie|tv}/<id> and extract the m3u8 from
     * the playlist JSON.
     *
     * For TV, the URL is play.xpass.top/e/tv/<id>/<season>/<episode>
     */
    private suspend fun fetchXpass(
        xpassUrl: String,
        id: String,
        kind: String, // "movie" or "tv"
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        runCatching {
            val headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "https://play.xpass.top/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            // play.xpass.top has CF — use CloudflareKiller
            val res = app.get(xpassUrl, headers = headers, interceptor = cfKiller, timeout = 30_000)
            if (res.code !in 200..299) {
                Log.d("2Embed", "xpass HTTP ${res.code} for $xpassUrl")
                return@runCatching
            }
            val html = res.text

            // Extract playlist URLs from inline JS:
            //   var data={"playlist":"/vxr/movie/0/playlist.json",...}
            //   var backups=[{"url":"/vxr/movie/0/playlist.json",...},{"url":"/vrk/movie/0/playlist.json",...}]
            val playlistUrls = mutableSetOf<String>()
            Regex("""["']playlist["']\s*:\s*["']([^"']+)["']""")
                .findAll(html).forEach { m -> playlistUrls += m.groupValues[1] }
            Regex("""["']url["']\s*:\s*["']([^"']+playlist\.json)["']""")
                .findAll(html).forEach { m -> playlistUrls += m.groupValues[1] }

            // Default playlist paths if none found
            if (playlistUrls.isEmpty()) {
                playlistUrls += "/vxr/$kind/0/playlist.json"
                playlistUrls += "/vrk/$kind/0/playlist.json"
            }

            // Step 4: Fetch each playlist JSON (with CF killer + auth cookie)
            for (playlistPath in playlistUrls) {
                val playlistUrl = if (playlistPath.startsWith("http")) playlistPath
                                  else "https://play.xpass.top${playlistPath}"
                runCatching {
                    val pres = app.get(
                        playlistUrl,
                        headers = mapOf(
                            "User-Agent" to UA,
                            "Referer" to xpassUrl,
                            "Accept" to "application/json",
                        ),
                        interceptor = cfKiller,
                        timeout = 20_000,
                    )
                    if (pres.code !in 200..299) return@runCatching
                    val playlistJson = pres.text
                    if (playlistJson.isBlank() || playlistJson.startsWith("<")) return@runCatching

                    val root = JSONObject(playlistJson)
                    val playlist = root.optJSONArray("playlist") ?: return@runCatching
                    for (i in 0 until playlist.length()) {
                        val item = playlist.optJSONObject(i) ?: continue
                        val sources = item.optJSONArray("sources") ?: continue
                        for (j in 0 until sources.length()) {
                            val src = sources.optJSONObject(j) ?: continue
                            val file = src.optString("file").ifBlank { src.optString("src") }
                            if (file.isBlank()) continue
                            // file can be relative (/video/...) or absolute
                            val absFile = if (file.startsWith("http")) file
                                          else "https://play.xpass.top$file"
                            // Skip error placeholders
                            if (absFile.contains("/video/error")) continue
                            val label = src.optString("label").ifBlank { src.optString("type") }
                            val type = src.optString("type")
                            if (absFile.contains(".m3u8", true) || type.equals("hls", true)) {
                                M3u8Helper.generateM3u8(
                                    source = "2Embed",
                                    streamUrl = absFile,
                                    referer = xpassUrl,
                                    headers = mapOf("User-Agent" to UA, "Referer" to xpassUrl),
                                ).forEach(callback)
                            } else if (absFile.contains(".mp4", true) || absFile.contains(".mkv", true)) {
                                callback(
                                    newExtractorLink(
                                        source = "2Embed",
                                        name = "2Embed${if (label.isNotBlank()) " — $label" else ""}",
                                        url = absFile,
                                        type = ExtractorLinkType.VIDEO,
                                    ) {
                                        this.referer = xpassUrl
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }.onFailure { Log.d("2Embed", "fetchXpass failed: ${it.message}") }
    }

    private suspend fun extractSourcesFromHtml(
        html: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // sources: [...] JSON array
        Regex("""sources\s*:\s*(\[[\s\S]*?\])""")
            .findAll(html).forEach { m ->
                runCatching {
                    val arr = JSONArray(m.groupValues[1])
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val file = o.optString("file").ifBlank { o.optString("src") }
                        if (file.isBlank()) continue
                        val label = o.optString("label")
                        if (file.contains(".m3u8", true)) {
                            M3u8Helper.generateM3u8(
                                source = "2Embed",
                                streamUrl = file,
                                referer = pageUrl,
                                headers = mapOf("User-Agent" to UA, "Referer" to pageUrl),
                            ).forEach(callback)
                        } else {
                            callback(
                                newExtractorLink(
                                    source = "2Embed",
                                    name = "2Embed${if (label.isNotBlank()) " — $label" else ""}",
                                    url = file,
                                    type = ExtractorLinkType.VIDEO,
                                ) {
                                    this.referer = pageUrl
                                }
                            )
                        }
                    }
                }
            }

        // hls.loadSource("….m3u8")
        Regex("""hls\.loadSource\(["']([^"']+\.m3u8[^"']*)["']\)""")
            .findAll(html).forEach { m ->
                M3u8Helper.generateM3u8(
                    source = "2Embed",
                    streamUrl = m.groupValues[1],
                    referer = pageUrl,
                    headers = mapOf("User-Agent" to UA, "Referer" to pageUrl),
                ).forEach(callback)
            }
    }
}

// ════════════════════════════════════════════════════════════════════════
//  SmashyStream — embed.smashystream.com iframe aggregator
// ════════════════════════════════════════════════════════════════════════

class SmashyStreamExtractor : ExtractorApi() {
    override val name = "SmashyStream"
    override val mainUrl = "https://embed.smashystream.com"
    override val requiresReferer = true

    private val cfKiller = CloudflareKiller()
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        runCatching {
            val headers = mapOf(
                "User-Agent" to UA,
                "Referer" to (referer ?: mainUrl),
            )
            val res = app.get(url, headers = headers, interceptor = cfKiller, timeout = 20_000)
            if (res.code !in 200..299) return@runCatching
            val html = res.text
            val doc = Jsoup.parse(html, url)

            // SmashyStream uses iframes pointing to vid providers — recurse
            // via loadExtractor so any registered extractor picks them up.
            doc.select("iframe[src]").forEach { el ->
                val src = el.attr("src")
                if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                    val abs = if (src.startsWith("//")) "https:$src" else src
                    runCatching {
                        loadExtractor(abs, url, subtitleCallback, callback)
                    }
                }
            }

            // Also extract direct sources from the page.
            Regex("""sources\s*:\s*(\[[\s\S]*?\])""")
                .findAll(html).forEach { m ->
                    runCatching {
                        val arr = JSONArray(m.groupValues[1])
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val file = o.optString("file").ifBlank { o.optString("src") }
                            if (file.isBlank()) continue
                            if (file.contains(".m3u8", true)) {
                                M3u8Helper.generateM3u8(
                                    source = "SmashyStream",
                                    streamUrl = file,
                                    referer = url,
                                    headers = mapOf("User-Agent" to UA, "Referer" to url),
                                ).forEach(callback)
                            } else {
                                callback(
                                    newExtractorLink(
                                        source = "SmashyStream",
                                        name = "SmashyStream - Direct",
                                        url = file,
                                        type = ExtractorLinkType.VIDEO,
                                    ) {
                                        this.referer = url
                                    }
                                )
                            }
                        }
                    }
                }
        }.onFailure { Log.d("Smashy", "getUrl failed for $url: ${it.message}") }
    }
}

// ════════════════════════════════════════════════════════════════════════
//  VidFast.pro — uses enc-dec.app external decryption service
//
//  Flow:
//    1. GET enc-dec.app/api/enc-vidfast?text=<tmdb_or_imdb_id>
//       → {status:200, result:{servers:"…", stream:"…", token:"…"}}
//    2. POST to the servers URL with the token → encrypted JSON
//    3. POST encrypted JSON to enc-dec.app/api/dec-vidfast → decrypted sources
//
//  Note: Step 2 needs CloudflareKiller for vidfast.vc
// ════════════════════════════════════════════════════════════════════════

class VidFastExtractor : ExtractorApi() {
    override val name = "VidFast"
    override val mainUrl = "https://vidfast.pro"
    override val requiresReferer = true

    private val cfKiller = CloudflareKiller()
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private val ENC_DEC_BASE = "https://enc-dec.app/api"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        runCatching {
            // Extract the video id from the URL
            // URL format: https://vidfast.pro/movie/<id> or https://vidfast.pro/tv/<id>/<s>/<e>
            val parts = url.removePrefix("$mainUrl/").split("/")
            val kind = parts.getOrNull(0) ?: "movie"  // "movie" or "tv"
            val id = parts.getOrNull(1) ?: return@runCatching
            if (id.isBlank()) return@runCatching

            // Step 1: Get encrypted server URL from enc-dec.app
            val encRes = app.get(
                "$ENC_DEC_BASE/enc-vidfast?text=$id",
                headers = mapOf("User-Agent" to UA, "Accept" to "application/json"),
                timeout = 15_000,
            )
            if (encRes.code !in 200..299) {
                Log.d("VidFast", "enc-vidfast HTTP ${encRes.code}")
                return@runCatching
            }
            val encJson = runCatching { JSONObject(encRes.text) }.getOrNull() ?: return@runCatching
            val result = encJson.optJSONObject("result") ?: return@runCatching
            val serversUrl = result.optString("servers").takeIf { it.isNotBlank() } ?: return@runCatching
            val token = result.optString("token").takeIf { it.isNotBlank() }

            // Step 2: Fetch the encrypted server response
            // The servers URL is on vidfast.vc — needs CloudflareKiller
            val serverRes = app.get(
                serversUrl,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to "$mainUrl/",
                    "Accept" to "application/json",
                ),
                interceptor = cfKiller,
                timeout = 20_000,
            )
            if (serverRes.code !in 200..299) {
                Log.d("VidFast", "servers HTTP ${serverRes.code}")
                // Fall back to scanning the vidfast.pro page directly
                scanVidfastPage(url, subtitleCallback, callback)
                return@runCatching
            }
            val serverText = serverRes.text
            if (serverText.isBlank()) {
                scanVidfastPage(url, subtitleCallback, callback)
                return@runCatching
            }

            // Step 3: Try to parse the server response directly (might be plain JSON)
            // If it's encrypted, POST to dec-vidfast
            val sources = parseOrDecrypt(serverText, id)
            if (sources != null) {
                emitSources(sources, url, callback)
            } else {
                // Fall back to scanning the vidfast.pro page
                scanVidfastPage(url, subtitleCallback, callback)
            }
        }.onFailure { Log.d("VidFast", "getUrl failed for $url: ${it.message}") }
    }

    private suspend fun parseOrDecrypt(text: String, id: String): JSONArray? {
        // Try direct JSON parse first
        runCatching {
            val obj = JSONObject(text)
            // Could be {sources:[...]} or {playlist:[{sources:[...]}]}
            val sources = obj.optJSONArray("sources")
            if (sources != null) return sources
            val playlist = obj.optJSONArray("playlist")
            if (playlist != null && playlist.length() > 0) {
                return playlist.optJSONObject(0)?.optJSONArray("sources")
            }
        }
        // Try decrypting via enc-dec.app
        runCatching {
            val decRes = app.post(
                "$ENC_DEC_BASE/dec-vidfast",
                headers = mapOf(
                    "User-Agent" to UA,
                    "Content-Type" to "application/json",
                ),
                requestBody = JSONObject().put("text", text).toString().toRequestBody("application/json".toMediaTypeOrNull()),
                timeout = 15_000,
            )
            if (decRes.code in 200..299) {
                val decJson = JSONObject(decRes.text)
                val result = decJson.optString("result").takeIf { it.isNotBlank() } ?: return null
                val obj = JSONObject(result)
                val sources = obj.optJSONArray("sources")
                if (sources != null) return sources
                val playlist = obj.optJSONArray("playlist")
                if (playlist != null && playlist.length() > 0) {
                    return playlist.optJSONObject(0)?.optJSONArray("sources")
                }
            }
        }
        return null
    }

    private suspend fun scanVidfastPage(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        runCatching {
            val res = app.get(
                url,
                headers = mapOf("User-Agent" to UA, "Referer" to mainUrl),
                interceptor = cfKiller,
                timeout = 20_000,
            )
            if (res.code !in 200..299) return@runCatching
            val html = res.text

            // Scan for direct m3u8/mp4 URLs
            Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv)(?:\?[^\s"'<>]*)?""",
                RegexOption.IGNORE_CASE)
                .findAll(html).forEach { m ->
                    val u = m.value
                    if (u.contains(".m3u8", true)) {
                        M3u8Helper.generateM3u8(
                            source = "VidFast",
                            streamUrl = u,
                            referer = url,
                            headers = mapOf("User-Agent" to UA, "Referer" to url),
                        ).forEach(callback)
                    } else {
                        callback(
                            newExtractorLink(
                                source = "VidFast",
                                name = "VidFast - Direct",
                                url = u,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = url
                            }
                        )
                    }
                }

            // JWPlayer sources
            val doc = Jsoup.parse(html, url)
            doc.select("script:containsData(sources:)").forEach { script ->
                val data = script.data()
                Regex("""sources\s*:\s*(\[[\s\S]*?\])""")
                    .find(data)?.groupValues?.getOrNull(1)?.let { arrStr ->
                        runCatching {
                            val arr = JSONArray(arrStr)
                            emitSources(arr, url, callback)
                        }
                    }
            }

            // Recurse into iframes
            doc.select("iframe[src]").forEach { el ->
                val src = el.attr("src")
                if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                    val abs = if (src.startsWith("//")) "https:$src" else src
                    runCatching {
                        loadExtractor(abs, url, subtitleCallback, callback)
                    }
                }
            }
        }
    }

    private suspend fun emitSources(
        sources: JSONArray,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        for (i in 0 until sources.length()) {
            val o = sources.optJSONObject(i) ?: continue
            val file = o.optString("file").ifBlank { o.optString("src") }
            if (file.isBlank()) continue
            val label = o.optString("label").ifBlank { o.optString("type") }
            if (file.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8(
                    source = "VidFast",
                    streamUrl = file,
                    referer = pageUrl,
                    headers = mapOf("User-Agent" to UA, "Referer" to pageUrl),
                ).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = "VidFast",
                        name = "VidFast${if (label.isNotBlank()) " — $label" else ""}",
                        url = file,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = pageUrl
                    }
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
//  VidLink.pro — uses enc-dec.app to encrypt the id, then fetches the
//  encrypted API response from vidlink.pro/api/b/{kind}/<enc_id>
//
//  Flow:
//    1. GET enc-dec.app/api/enc-vidlink?text=<id> → encrypted id
//    2. GET vidlink.pro/api/b/{movie|tv}/<enc_id> → encrypted JSON
//       (needs CloudflareKiller)
//    3. The encrypted JSON is decoded client-side via WebAssembly
//       We can't decode it without running JS, so we fall back to
//       scanning the page HTML for any direct m3u8 URLs.
// ════════════════════════════════════════════════════════════════════════

class VidLinkExtractor : ExtractorApi() {
    override val name = "VidLink"
    override val mainUrl = "https://vidlink.pro"
    override val requiresReferer = true

    private val cfKiller = CloudflareKiller()
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private val ENC_DEC_BASE = "https://enc-dec.app/api"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        runCatching {
            val headers = mapOf(
                "User-Agent" to UA,
                "Referer" to (referer ?: mainUrl),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            // Fetch the vidlink.pro page with CloudflareKiller
            val res = app.get(url, headers = headers, interceptor = cfKiller, timeout = 30_000)
            if (res.code !in 200..299) {
                Log.d("VidLink", "HTTP ${res.code} for $url")
                return@runCatching
            }
            val html = res.text

            // Scan __NEXT_DATA__ + entire HTML for any direct m3u8/mp4 URLs
            Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv)(?:\?[^\s"'<>]*)?""",
                RegexOption.IGNORE_CASE)
                .findAll(html).forEach { m ->
                    val u = m.value
                    if (u.contains(".m3u8", true)) {
                        M3u8Helper.generateM3u8(
                            source = "VidLink",
                            streamUrl = u,
                            referer = url,
                            headers = mapOf("User-Agent" to UA, "Referer" to url),
                        ).forEach(callback)
                    } else {
                        callback(
                            newExtractorLink(
                                source = "VidLink",
                                name = "VidLink - Direct",
                                url = u,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = url
                            }
                        )
                    }
                }

            // Look for JWPlayer sources in inline scripts
            val doc = Jsoup.parse(html, url)
            doc.select("script:containsData(sources:)").forEach { script ->
                val data = script.data()
                Regex("""sources\s*:\s*(\[[\s\S]*?\])""")
                    .find(data)?.groupValues?.getOrNull(1)?.let { arrStr ->
                        runCatching {
                            val arr = JSONArray(arrStr)
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val file = o.optString("file").ifBlank { o.optString("src") }
                                if (file.isBlank()) continue
                                val label = o.optString("label").ifBlank { o.optString("type") }
                                if (file.contains(".m3u8", true)) {
                                    M3u8Helper.generateM3u8(
                                        source = "VidLink",
                                        streamUrl = file,
                                        referer = url,
                                        headers = mapOf("User-Agent" to UA, "Referer" to url),
                                    ).forEach(callback)
                                } else {
                                    callback(
                                        newExtractorLink(
                                            source = "VidLink",
                                            name = "VidLink${if (label.isNotBlank()) " — $label" else ""}",
                                            url = file,
                                            type = ExtractorLinkType.VIDEO,
                                        ) {
                                            this.referer = url
                                        }
                                    )
                                }
                            }
                        }
                    }
            }

            // hls.loadSource("….m3u8")
            Regex("""hls\.loadSource\(["']([^"']+\.m3u8[^"']*)["']\)""")
                .findAll(html).forEach { m ->
                    M3u8Helper.generateM3u8(
                        source = "VidLink",
                        streamUrl = m.groupValues[1],
                        referer = url,
                        headers = mapOf("User-Agent" to UA, "Referer" to url),
                    ).forEach(callback)
                }

            // Recurse into any iframes
            doc.select("iframe[src]").forEach { el ->
                val src = el.attr("src")
                if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                    val abs = if (src.startsWith("//")) "https:$src" else src
                    runCatching {
                        loadExtractor(abs, url, subtitleCallback, callback)
                    }
                }
            }
        }.onFailure { Log.d("VidLink", "getUrl failed for $url: ${it.message}") }
    }
}
