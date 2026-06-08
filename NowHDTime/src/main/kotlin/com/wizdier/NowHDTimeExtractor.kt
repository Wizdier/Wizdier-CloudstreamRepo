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
 * Catch-all extractor — handles embed pages from any domain to find
 * direct video URLs (mp4, m3u8, etc.), nested iframes, and subtitle tracks.
 */
class NowHDTimeExtractor : ExtractorApi() {
    override val name = "NowHDTime"
    override val mainUrl = "https://"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer ?: "https://nowhdtime.to", timeout = 20).text
        val fullHtml = deobfuscate(html)

        // Direct video URLs
        val videoRegex = Regex(
            """["'](https?://[^"']+\.(?:mp4|m3u8|mkv|mpd|webm)(?:\?[^"']*)?)["']""",
            RegexOption.IGNORE_CASE
        )
        videoRegex.findAll(fullHtml).forEach { m ->
            val vUrl = m.groupValues[1]
            if (vUrl.startsWith("http")) {
                callback(
                    newExtractorLink(
                        source = name, name = name, url = vUrl,
                        type = if (vUrl.contains(".m3u8", ignoreCase = true))
                            ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = qualityFromUrl(vUrl)
                    }
                )
            }
        }

        // JS source/file keys
        val jsRegex = Regex(
            """(?i)(?:file|src|source|url|video_url)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8|mkv|mpd|webm)(?:\?[^"']*)?)["']"""
        )
        jsRegex.findAll(fullHtml).forEach { m ->
            val vUrl = m.groupValues[1]
            if (vUrl.startsWith("http")) {
                callback(
                    newExtractorLink(
                        source = name, name = name, url = vUrl,
                        type = if (vUrl.contains(".m3u8", ignoreCase = true))
                            ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = qualityFromUrl(vUrl)
                    }
                )
            }
        }

        // Nested iframes
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                var src = m.groupValues[1]
                if (src.startsWith("//")) src = "https:$src"
                if (src.startsWith("http")) {
                    try { loadExtractor(src, url, subtitleCallback, callback) } catch (_: Exception) {}
                }
            }

        // Subtitle tracks
        Regex("""["'](https?://[^"']+\.(?:srt|vtt|ass)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(fullHtml).forEach { m ->
                subtitleCallback(SubtitleFile("Auto", m.groupValues[1]))
            }
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
        val decoded = decodeBase64(html) ?: ""
        return "$html\n$packed\n$decoded"
    }

    private fun unpackJs(source: String): String? {
        val re = Regex(
            """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.+?)',(\d+),(\d+),'([^']*)'""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val m = re.find(source) ?: return null
        return try {
            val payload = m.groupValues[1]
            val radix = m.groupValues[2].toIntOrNull() ?: return null
            val count = m.groupValues[3].toIntOrNull() ?: return null
            val keywords = m.groupValues[4].split("|")
            if (keywords.size != count) return null
            var result = payload
            for (i in count - 1 downTo 0) {
                keywords.getOrNull(i)?.takeIf { it.isNotBlank() }?.let {
                    result = result.replace(Regex("\\b${Integer.toString(i, radix)}\\b"), it)
                }
            }
            result
        } catch (_: Exception) { null }
    }

    private fun decodeBase64(html: String): String? {
        val sb = StringBuilder()
        Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""").findAll(html).forEach { m ->
            try {
                sb.appendLine(String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT)))
            } catch (_: Exception) {}
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }
}
