package com.wizdier

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class NowHDTimeExtractor : ExtractorApi() {
    override val name = "NowHDTime"
    override val mainUrl = "https://"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer ?: "https://nowhdtime.to", timeout = 20).text
        val full = deobfuscate(html)
        var found = false

        // Direct video
        Regex("""["'](https?://[^"']+\.(?:mp4|m3u8|mkv|mpd|webm)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
            .findAll(full).forEach { m ->
                val u = m.groupValues[1]
                if (u.startsWith("http")) {
                    callback(newExtractorLink(name, name, u,
                        if (u.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) { this.referer = url; this.quality = quality(u) })
                    found = true
                }
            }

        // JS source keys
        if (!found) {
            Regex("""(?i)(?:file|src|source|url|video_url)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8|mkv|mpd)(?:\?[^"']*)?)["']""")
                .findAll(full).forEach { m ->
                    val u = m.groupValues[1]
                    if (u.startsWith("http")) {
                        callback(newExtractorLink(name, name, u,
                            if (u.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) { this.referer = url; this.quality = quality(u) })
                        found = true
                    }
                }
        }

        // Subtitle tracks
        Regex("""["'](https?://[^"']+\.(?:srt|vtt|ass)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(full).forEach { m -> subtitleCallback(SubtitleFile("Auto", m.groupValues[1])) }
        Regex("""<track[^>]+src=["']([^"']+)["'][^>]*(?:label=["']([^"']*)["'])?[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val u = m.groupValues[1]; val l = m.groupValues[2].ifBlank { "Auto" }
                val fixed = if (u.startsWith("//")) "https:$u" else if (u.startsWith("/")) "https://$u" else u
                if (fixed.startsWith("http")) subtitleCallback(SubtitleFile(l, fixed))
            }
    }

    private fun quality(url: String): Int {
        val l = url.lowercase()
        return when {
            l.contains("2160") || l.contains("4k") -> Qualities.P2160.value
            l.contains("1080") || l.contains("fhd") -> Qualities.P1080.value
            l.contains("720") || l.contains("hd") -> Qualities.P720.value
            l.contains("480") -> Qualities.P480.value
            l.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun deobfuscate(h: String): String {
        val u = unpackJs(h) ?: ""; val d = decode64(h) ?: ""; return "$h\n$u\n$d"
    }

    private fun unpackJs(s: String): String? {
        val re = Regex("""eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.+?)',(\d+),(\d+),'([^']*)'""", setOf(RegexOption.DOT_MATCHES_ALL))
        val m = re.find(s) ?: return null
        return try {
            val payload = m.groupValues[1]; val radix = m.groupValues[2].toIntOrNull() ?: return null
            val count = m.groupValues[3].toIntOrNull() ?: return null
            val kw = m.groupValues[4].split("|"); if (kw.size != count) return null
            var r = payload
            for (i in count - 1 downTo 0) kw.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { r = r.replace(Regex("\\b${Integer.toString(i, radix)}\\b"), it) }
            r
        } catch (_: Exception) { null }
    }

    private fun decode64(h: String): String? {
        val sb = StringBuilder()
        Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""").findAll(h).forEach { m ->
            try { sb.appendLine(String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT))) } catch (_: Exception) {}
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }
}
