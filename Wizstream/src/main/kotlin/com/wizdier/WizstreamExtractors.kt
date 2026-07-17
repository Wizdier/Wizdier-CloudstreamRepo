package com.wizdier

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray

/**
 * Wizstream custom extractors for vid embed hosts that Cloudstream's
 * built-in extractor registry doesn't cover.
 *
 * Hosts handled here:
 *   • VsEmbed.ru        — the backend that vidsrc.to / vidsrc.mov /
 *                         vidsrc-embed.su / vidsrc.me all redirect to.
 *                         Cloudflare-protected; we use CloudflareKiller.
 *   • TwoEmbedCc        — 2embed.cc iframe embed. The first iframe points
 *                         to streamsrcs.2embed.cc which contains the actual
 *                         HLS source.
 *   • SmashyStream      — embed.smashystream.com iframe aggregator.
 *   • VidFast           — vidfast.pro Next.js app.
 *
 * Registration happens in WizstreamPlugin.load() via registerExtractorAPI().
 *
 * Implementation notes:
 *   • `getUrl` is `suspend` — we can call `app.get(...)`,
 *     `M3u8Helper.generateM3u8(...)`, and `loadExtractor(...)` directly.
 *   • The HTTP client is the top-level `app` val from
 *     `com.lagradost.cloudstream3` (re-exported by app).
 *   • `newExtractorLink(...)` is also `suspend` (the initializer lambda is
 *     a suspend lambda) so we call it from inside `getUrl`.
 */

// ════════════════════════════════════════════════════════════════════════
//  VsEmbed.ru — handles vidsrc.to / vidsrc.mov / vidsrc-embed.su / vidsrc.me
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
            val res = app.get(
                url, headers = headers, interceptor = cfKiller, timeout = 20_000
            )
            if (res.code !in 200..299) return@runCatching
            val html = res.text
            val doc = Jsoup.parse(html, url)

            // Pattern 1: <script>containsData(sources:)</script>
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
//  2Embed.cc
// ════════════════════════════════════════════════════════════════════════

class TwoEmbedCcExtractor : ExtractorApi() {
    override val name = "2Embed"
    override val mainUrl = "https://www.2embed.cc"
    override val requiresReferer = true

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
            val res = app.get(url, headers = headers, timeout = 15_000)
            if (res.code !in 200..299) return@runCatching
            val html = res.text

            // Find the iframe pointing to streamsrcs.2embed.cc
            val doc = Jsoup.parse(html, url)
            val iframeSrc = doc.selectFirst("iframe#iframesrc")?.attr("data-src")
                ?: doc.selectFirst("iframe[src*='streamsrcs.2embed.cc']")?.attr("src")
                ?: doc.selectFirst("iframe[src]")?.attr("src")

            if (iframeSrc != null) {
                val absIframe = if (iframeSrc.startsWith("http")) iframeSrc
                                else "${url.substringBeforeLast("/")}/${iframeSrc.trimStart('/')}"
                fetchStreamSrcs(absIframe, subtitleCallback, callback)
            }

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
            val res = app.get(iframeUrl, headers = headers, timeout = 15_000)
            if (res.code !in 200..299) return@runCatching
            extractSourcesFromHtml(res.text, iframeUrl, subtitleCallback, callback)
        }
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

        // <video><source src="…">
        val doc = Jsoup.parse(html, pageUrl)
        doc.select("video source[src], video[src]").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank()) {
                if (src.contains(".m3u8", true)) {
                    M3u8Helper.generateM3u8(
                        source = "2Embed",
                        streamUrl = src,
                        referer = pageUrl,
                        headers = mapOf("User-Agent" to UA, "Referer" to pageUrl),
                    ).forEach(callback)
                } else {
                    callback(
                        newExtractorLink(
                            source = "2Embed",
                            name = "2Embed - Direct",
                            url = src,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = pageUrl
                        }
                    )
                }
            }
        }

        // Subtitle tracks
        doc.select("track[src]").forEach { el ->
            val src = el.attr("src")
            val label = el.attr("label").ifBlank { el.attr("srclang") }.ifBlank { "Sub" }
            if (src.isNotBlank()) {
                val absSrc = if (src.startsWith("http")) src
                             else "${pageUrl.substringBeforeLast("/")}/${src.trimStart('/')}"
                subtitleCallback(SubtitleFile(label, absSrc))
            }
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
            val res = app.get(url, headers = headers, timeout = 15_000)
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
//  VidFast.pro
// ════════════════════════════════════════════════════════════════════════

class VidFastExtractor : ExtractorApi() {
    override val name = "VidFast"
    override val mainUrl = "https://vidfast.pro"
    override val requiresReferer = true

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
            val res = app.get(url, headers = headers, timeout = 15_000)
            if (res.code !in 200..299) return@runCatching
            val html = res.text

            // Look for any direct m3u8/mp4 URL in the Next.js __NEXT_DATA__ blob.
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

            // If we find iframes, recurse.
            val doc = Jsoup.parse(html, url)
            doc.select("iframe[src]").forEach { el ->
                val src = el.attr("src")
                if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                    val abs = if (src.startsWith("//")) "https:$src" else src
                    runCatching {
                        loadExtractor(abs, url, subtitleCallback, callback)
                    }
                }
            }
        }.onFailure { Log.d("VidFast", "getUrl failed for $url: ${it.message}") }
    }
}
