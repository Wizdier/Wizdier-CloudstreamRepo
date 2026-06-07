package com.wizdier

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

// ══════════════════════════════════════
//  JS Unpacker Utility
// ══════════════════════════════════════
object JsUnpacker {
    fun unpack(packed: String?): String? {
        if (packed == null) return null
        return try {
            val match = Regex(
                """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.+?)',(\d+),(\d+),'([^']*)'""",
                RegexOption.DOT_MATCHES_ALL
            ).find(packed) ?: return null

            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toIntOrNull() ?: return null
            val count = match.groupValues[3].toIntOrNull() ?: return null
            val keywords = match.groupValues[4].split("|")

            if (keywords.size != count) return null

            var result = payload
            for (i in count - 1 downTo 0) {
                val replacement = keywords.getOrNull(i) ?: continue
                if (replacement.isNotBlank()) {
                    val key = Integer.toString(i, radix)
                    result = result.replace(Regex("\\b$key\\b"), replacement)
                }
            }
            result
        } catch (_: Exception) {
            null
        }
    }
}

// ══════════════════════════════════════
//  Helper: Unpack HTML if packed JS exists
// ══════════════════════════════════════
private fun unpackIfPacked(html: String): String {
    val packedMatch = Regex(
        """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('[^']*'""",
        RegexOption.DOT_MATCHES_ALL
    ).find(html)
    val unpacked = if (packedMatch != null) {
        JsUnpacker.unpack(packedMatch.value) ?: ""
    } else ""
    return "$html\n$unpacked"
}

private fun detectQualityFromUrl(url: String): Int {
    return when {
        url.contains("2160") || url.contains("4k", ignoreCase = true) -> Qualities.UHD4K.value
        url.contains("1080") -> Qualities.P1080.value
        url.contains("720") -> Qualities.P720.value
        url.contains("480") -> Qualities.P480.value
        url.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

private val videoUrlRegex = Regex(
    """["'](https?://[^"']+\.(?:mp4|m3u8|mkv|mpd)[^"']*)["']""",
    RegexOption.IGNORE_CASE
)

private val sourcesRegex = Regex(
    """(?i)sources\s*[:=]\s*\[\s*\{[^}]*["']?(?:file|src)["']?\s*:\s*["']([^"']+)["']"""
)

private val subtitleRegex = Regex(
    """["'](https?://[^"']+\.(?:srt|vtt|ass)[^"']*)["']""",
    RegexOption.IGNORE_CASE
)

// ══════════════════════════════════════
//  NowHDTime Internal Stream Extractor
// ══════════════════════════════════════
class NowHDTimeStreamExtractor : ExtractorApi() {
    override val name = "NowHDTime Stream"
    override val mainUrl = "https://nowhdtime.com.bd"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer ?: mainUrl, timeout = 20).text
        val fullHtml = unpackIfPacked(html)

        val playerPatterns = listOf(
            Regex("""(?i)(?:file|src|source|url|video_url)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8|mkv|mpd)[^"']*)["']"""),
            sourcesRegex,
            Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?i)player\.src\s*\(\s*\{[^}]*src\s*:\s*["']([^"']+)["']"""),
        )

        for (pattern in playerPatterns) {
            pattern.findAll(fullHtml).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.startsWith("http")) {
                    callback.invoke(
                        ExtractorLink(
                            source = name, name = name, url = videoUrl,
                            referer = url, quality = detectQualityFromUrl(videoUrl),
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                    )
                }
            }
        }

        subtitleRegex.findAll(fullHtml).forEach { match ->
            subtitleCallback.invoke(SubtitleFile("Auto", match.groupValues[1]))
        }

        // Nested iframes
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val src = match.groupValues[1].let { if (it.startsWith("//")) "https:$it" else it }
            if (src.startsWith("http") && !src.contains("nowhdtime.com.bd")) {
                try { loadExtractor(src, url, subtitleCallback, callback) } catch (_: Exception) {}
            }
        }
    }
}

// ══════════════════════════════════════
//  Dropload
// ══════════════════════════════════════
class NowHDTimeDroploadExtractor : ExtractorApi() {
    override val name = "Dropload"
    override val mainUrl = "https://dropload.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer, timeout = 20).text
        val fullHtml = unpackIfPacked(html)

        videoUrlRegex.findAll(fullHtml).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (!videoUrl.contains("dropload.io")) {
                callback.invoke(
                    ExtractorLink(
                        source = name, name = name, url = videoUrl,
                        referer = url, quality = detectQualityFromUrl(videoUrl),
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            }
        }
    }
}

// ══════════════════════════════════════
//  VidHide
// ══════════════════════════════════════
class NowHDTimeVidhideExtractor : ExtractorApi() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhide.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer, timeout = 20).text
        val fullHtml = unpackIfPacked(html)

        listOf(sourcesRegex, videoUrlRegex).forEach { pattern ->
            pattern.findAll(fullHtml).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.startsWith("http") && !videoUrl.contains("vidhide.com")) {
                    callback.invoke(
                        ExtractorLink(
                            source = name, name = name, url = videoUrl,
                            referer = url, quality = detectQualityFromUrl(videoUrl),
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                    )
                }
            }
        }

        // Subtitles
        Regex("""(?i)tracks\s*[:=]\s*\[([^\]]+)]""").find(fullHtml)?.let { trackMatch ->
            Regex("""(?i)["'](?:file|src)["']\s*:\s*["']([^"']+\.(?:srt|vtt))["']""")
                .findAll(trackMatch.groupValues[1]).forEach { sub ->
                    subtitleCallback.invoke(SubtitleFile("Auto", sub.groupValues[1]))
                }
        }
    }
}

// ══════════════════════════════════════
//  FileLions
// ══════════════════════════════════════
class NowHDTimeFilelionsExtractor : ExtractorApi() {
    override val name = "FileLions"
    override val mainUrl = "https://filelions.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer, timeout = 20).text
        val fullHtml = unpackIfPacked(html)

        videoUrlRegex.findAll(fullHtml).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (!videoUrl.contains("filelions")) {
                callback.invoke(
                    ExtractorLink(
                        source = name, name = name, url = videoUrl,
                        referer = url, quality = detectQualityFromUrl(videoUrl),
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            }
        }
    }
}

// ══════════════════════════════════════
//  StreamWish
// ══════════════════════════════════════
class NowHDTimeStreamwishExtractor : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer, timeout = 20).text
        val fullHtml = unpackIfPacked(html)

        sourcesRegex.findAll(fullHtml).forEach { match ->
            val videoUrl = match.groupValues[1]
            callback.invoke(
                ExtractorLink(
                    source = name, name = name, url = videoUrl,
                    referer = url, quality = detectQualityFromUrl(videoUrl),
                    isM3u8 = videoUrl.contains(".m3u8"),
                    headers = mapOf("Referer" to url)
                )
            )
        }

        // Fallback
        videoUrlRegex.findAll(fullHtml).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (!videoUrl.contains("streamwish")) {
                callback.invoke(
                    ExtractorLink(
                        source = name, name = "$name Alt", url = videoUrl,
                        referer = url, quality = detectQualityFromUrl(videoUrl),
                        isM3u8 = videoUrl.contains(".m3u8"),
                        headers = mapOf("Referer" to url)
                    )
                )
            }
        }

        subtitleRegex.findAll(fullHtml).forEach { match ->
            subtitleCallback.invoke(SubtitleFile("Auto", match.groupValues[1]))
        }
    }
}

// ══════════════════════════════════════
//  DoodStream
// ══════════════════════════════════════
class NowHDTimeDoodExtractor : ExtractorApi() {
    override val name = "DoodStream"
    override val mainUrl = "https://dood.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = url
            .replace(Regex("dood\\.(la|so|pm|wf|cx|sh|watch)"), "dood.to")
            .replace("doodstream.com", "dood.to")
            .replace("/d/", "/e/")

        val html = app.get(normalizedUrl, timeout = 20).text

        val passMd5 = Regex("/pass_md5/[^'\"]+").find(html)?.value ?: return
        val passMd5Url = "$mainUrl$passMd5"

        val tokenResp = app.get(passMd5Url, referer = normalizedUrl, timeout = 15).text

        if (tokenResp.startsWith("http")) {
            val token = "zUEJhGXlQ"
            val finalUrl = "${tokenResp}${token}?token=${passMd5.substringAfterLast("/")}&expiry=${System.currentTimeMillis()}"

            callback.invoke(
                ExtractorLink(
                    source = name, name = name, url = finalUrl,
                    referer = normalizedUrl, quality = Qualities.Unknown.value,
                    isM3u8 = false,
                    headers = mapOf("Referer" to normalizedUrl, "Range" to "bytes=0-")
                )
            )
        }
    }
}

// ══════════════════════════════════════
//  MixDrop
// ══════════════════════════════════════
class NowHDTimeMixdropExtractor : ExtractorApi() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = url.replace(
            Regex("mixdrop\\.(to|club|bz|ch|gl|ag|sx)"), "mixdrop.co"
        )

        val html = app.get(normalizedUrl, referer = referer, timeout = 20).text
        val unpacked = unpackIfPacked(html)

        Regex("""(?i)(?:wurl|surl|wvideo)\s*[:=]\s*["']([^"']+)["']""").find(unpacked)?.let { match ->
            var videoUrl = match.groupValues[1]
            if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"

            callback.invoke(
                ExtractorLink(
                    source = name, name = name, url = videoUrl,
                    referer = normalizedUrl, quality = Qualities.Unknown.value,
                    isM3u8 = false
                )
            )
        }
    }
}

// ══════════════════════════════════════
//  Voe
// ══════════════════════════════════════
class NowHDTimeVoeExtractor : ExtractorApi() {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer, timeout = 20).text

        // HLS
        Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.let {
            callback.invoke(
                ExtractorLink(
                    source = name, name = name, url = it.groupValues[1],
                    referer = url, quality = Qualities.Unknown.value, isM3u8 = true
                )
            )
            return
        }

        // MP4
        Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.let {
            callback.invoke(
                ExtractorLink(
                    source = name, name = name, url = it.groupValues[1],
                    referer = url, quality = Qualities.Unknown.value, isM3u8 = false
                )
            )
            return
        }

        // Base64 decode
        Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""").find(html)?.let { match ->
            try {
                val decoded = String(
                    android.util.Base64.decode(match.groupValues[1], android.util.Base64.DEFAULT)
                )
                if (decoded.startsWith("http")) {
                    callback.invoke(
                        ExtractorLink(
                            source = name, name = name, url = decoded,
                            referer = url, quality = Qualities.Unknown.value,
                            isM3u8 = decoded.contains(".m3u8")
                        )
                    )
                }
            } catch (_: Exception) {}
        }
    }
}

// ══════════════════════════════════════
//  Upstream
// ══════════════════════════════════════
class NowHDTimeUpstreamExtractor : ExtractorApi() {
    override val name = "Upstream"
    override val mainUrl = "https://upstream.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer, timeout = 20).text
        val fullHtml = unpackIfPacked(html)

        videoUrlRegex.findAll(fullHtml).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (!videoUrl.contains("upstream.to")) {
                callback.invoke(
                    ExtractorLink(
                        source = name, name = name, url = videoUrl,
                        referer = url, quality = detectQualityFromUrl(videoUrl),
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            }
        }
    }
}                   name = name,
                    url = mp4Url,
                    referer = url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false
                )
            )
        }

        // Base64 encoded source (Voe sometimes uses this)
        val b64Pattern = Pattern.compile("atob\\([\"']([A-Za-z0-9+/=]+)[\"']\\)", Pattern.CASE_INSENSITIVE)
        val b64Matcher = b64Pattern.matcher(html)
        if (b64Matcher.find()) {
            try {
                val decoded = String(android.util.Base64.decode(b64Matcher.group(1), android.util.Base64.DEFAULT))
                if (decoded.startsWith("http")) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = decoded,
                            referer = url,
                            quality = Qualities.Unknown.value,
                            isM3u8 = decoded.contains(".m3u8")
                        )
                    )
                }
            } catch (_: Exception) {}
        }
    }
}

// ===== Upstream Extractor =====
class NowHDTimeUpstreamExtractor : ExtractorApi() {
    override val name = "Upstream"
    override val mainUrl = "https://upstream.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer, timeout = 20)
        val html = response.text

        val unpackedHtml = try {
            val packedPattern = Pattern.compile("eval\\(function\\(p,a,c,k,e,[dr]\\)\\{.*?\\}\\('[^']*'", Pattern.DOTALL)
            val packedMatcher = packedPattern.matcher(html)
            if (packedMatcher.find()) {
                JsUnpacker.unpack(packedMatcher.group(0) ?: "") ?: html
            } else html
        } catch (_: Exception) { html }

        val fullHtml = "$html\n$unpackedHtml"

        val sourcePattern = Pattern.compile("[\"'](https?://[^\"']+\\.(?:mp4|m3u8)[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
        val sourceMatcher = sourcePattern.matcher(fullHtml)
        while (sourceMatcher.find()) {
            val videoUrl = sourceMatcher.group(1) ?: continue
            if (!videoUrl.contains("upstream.to")) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        referer = url,
                        quality = Qualities.Unknown.value,
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            }
        }
    }
}

// ===== JavaScript Unpacker Utility =====
object JsUnpacker {
    fun unpack(packed: String?): String? {
        if (packed == null) return null
        try {
            val pattern = Pattern.compile(
                "eval\\(function\\(p,a,c,k,e,[dr]\\)\\{.*?\\}\\('(.+?)',(\\d+),(\\d+),'([^']*)'",
                Pattern.DOTALL
            )
            val matcher = pattern.matcher(packed)
            if (!matcher.find()) return null

            val payload = matcher.group(1) ?: return null
            val radix = matcher.group(2)?.toIntOrNull() ?: return null
            val count = matcher.group(3)?.toIntOrNull() ?: return null
            val keywords = matcher.group(4)?.split("|") ?: return null

            if (keywords.size != count) return null

            var result = payload
            for (i in count - 1 downTo 0) {
                val replacement = keywords.getOrNull(i) ?: continue
                if (replacement.isNotBlank()) {
                    val key = Integer.toString(i, radix)
                    result = result.replace(Regex("\\b$key\\b"), replacement)
                }
            }

            return result
        } catch (e: Exception) {
            return null
        }
    }
}