package com.wizdier

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Generic extractor for NowHDTime embed pages.
 * Handles embedded iframes containing direct video URLs or nested embeds.
 */
class NowHDTimeExtractor : ExtractorApi() {
    override val name = "NowHDTime"
    override val mainUrl = "https://nowhdtime.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // For direct video URLs, pass through immediately
        if (isDirectVideoUrl(url)) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "Direct Stream",
                    url = url,
                    type = if (url.contains(".m3u8", ignoreCase = true))
                        ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer ?: mainUrl
                    this.quality = qualityFromUrl(url)
                }
            )
            return
        }

        // Fetch the embed page and extract videos
        val html = app.get(url, referer = referer ?: mainUrl, timeout = 20).text
        val fullHtml = deobfuscate(html)

        // Pattern 1: Direct video URLs in the page
        val videoPattern = Regex(
            """["'](https?://[^"']+\.(?:mp4|m3u8|mkv|mpd|webm)(?:\?[^"']*)?)["']""",
            RegexOption.IGNORE_CASE
        )
        videoPattern.findAll(fullHtml).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (videoUrl.startsWith("http")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8", ignoreCase = true))
                            ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = qualityFromUrl(videoUrl)
                    }
                )
            }
        }

        // Pattern 2: Source/file declarations in JS objects
        val sourcePattern = Regex(
            """(?i)(?:file|src|source|url|video_url)\s*[:=]\s*["'](https?://[^"']+)["']"""
        )
        sourcePattern.findAll(fullHtml).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (videoUrl.startsWith("http") && !videoUrl.contains("google") &&
                !videoUrl.contains("facebook") && !videoUrl.contains("youtube")
            ) {
                val isVideo = videoUrl.contains(".mp4", ignoreCase = true) ||
                        videoUrl.contains(".m3u8", ignoreCase = true) ||
                        videoUrl.contains(".mkv", ignoreCase = true) ||
                        videoUrl.contains(".mpd", ignoreCase = true)
                if (isVideo) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8", ignoreCase = true))
                                ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = qualityFromUrl(videoUrl)
                        }
                    )
                }
            }
        }

        // Pattern 3: Nested iframes
        val iframePattern = Regex(
            """<iframe[^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        iframePattern.findAll(html).forEach { match ->
            var src = match.groupValues[1]
            if (src.startsWith("//")) src = "https:$src"
            if (src.startsWith("http") && !src.contains("nowhdtime.to")) {
                try {
                    loadExtractor(src, url, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }

        // Pattern 4: Subtitle tracks
        val subPattern = Regex(
            """["'](https?://[^"']+\.(?:srt|vtt|ass)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        subPattern.findAll(fullHtml).forEach { match ->
            subtitleCallback(SubtitleFile("Auto", match.groupValues[1]))
        }

        val trackPattern = Regex(
            """<track[^>]+src=["']([^"']+)["'][^>]*(?:label=["']([^"']*)["'])?[^>]*>""",
            RegexOption.IGNORE_CASE
        )
        trackPattern.findAll(html).forEach { match ->
            val subUrl = match.groupValues[1]
            val label = match.groupValues[2].ifBlank { "Auto" }
            val fixedUrl = if (subUrl.startsWith("//")) "https:$subUrl"
            else if (subUrl.startsWith("/")) "$mainUrl$subUrl"
            else subUrl
            if (fixedUrl.startsWith("http")) {
                subtitleCallback(SubtitleFile(label, fixedUrl))
            }
        }
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".m3u8") ||
                lower.endsWith(".mkv") || lower.endsWith(".mpd") ||
                lower.endsWith(".webm") || lower.endsWith(".avi")
    }

    private fun qualityFromUrl(url: String): Int {
        val lower = url.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080") || lower.contains("fhd") -> Qualities.P1080.value
            lower.contains("720") || lower.contains("hd") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun deobfuscate(html: String): String {
        val packed = unpackJs(html) ?: ""
        val decoded = decodeBase64Strings(html)
        return "$html\n$packed\n$decoded"
    }

    private fun unpackJs(source: String): String? {
        val packedRegex = Regex(
            """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.+?)',(\d+),(\d+),'([^']*)'""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val match = packedRegex.find(source) ?: return null
        return try {
            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toIntOrNull() ?: return null
            val count = match.groupValues[3].toIntOrNull() ?: return null
            val keywords = match.groupValues[4].split("|")
            if (keywords.size != count) return null
            var result = payload
            for (i in count - 1 downTo 0) {
                val keyword = keywords.getOrNull(i) ?: continue
                if (keyword.isNotBlank()) {
                    val key = Integer.toString(i, radix)
                    result = result.replace(Regex("\\b$key\\b"), keyword)
                }
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBase64Strings(html: String): String {
        val sb = StringBuilder()
        val b64Pattern = Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""")
        b64Pattern.findAll(html).forEach { match ->
            try {
                val decoded = String(
                    android.util.Base64.decode(match.groupValues[1], android.util.Base64.DEFAULT)
                )
                sb.appendLine(decoded)
            } catch (_: Exception) {}
        }
        return sb.toString()
    }
}
