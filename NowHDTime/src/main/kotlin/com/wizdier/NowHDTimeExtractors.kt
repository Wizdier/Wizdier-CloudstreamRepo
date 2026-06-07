package com.wizdier

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

// ═══════════════════════════════════════════════════════════════════════════════
// JavaScript p,a,c,k,e,d Unpacker
// ═══════════════════════════════════════════════════════════════════════════════

object JsUnpacker {

    private val PACKED_REGEX = Regex(
        """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.+?)',(\d+),(\d+),'([^']*)'""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    fun unpack(packed: String?): String? {
        if (packed == null) return null

        return try {
            val match = PACKED_REGEX.find(packed) ?: return null

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

    fun containsPacked(html: String): Boolean {
        return html.contains("eval(function(p,a,c,k,e,")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Shared Extractor Helpers
// ═══════════════════════════════════════════════════════════════════════════════

private fun deobfuscate(html: String): String {
    if (!JsUnpacker.containsPacked(html)) return html

    val packedMatch = Regex(
        """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('[^']*'""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    ).find(html)

    val unpacked = packedMatch?.let { JsUnpacker.unpack(it.value) } ?: ""
    return "$html\n$unpacked"
}

private fun qualityOf(url: String): Int {
    val lower = url.lowercase()
    return when {
        lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
        lower.contains("1080") -> Qualities.P1080.value
        lower.contains("720") -> Qualities.P720.value
        lower.contains("480") -> Qualities.P480.value
        lower.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

private val VIDEO_REGEX = Regex(
    """["'](https?://[^"']+\.(?:mp4|m3u8|mkv|mpd)(?:\?[^"']*)?)["']""",
    RegexOption.IGNORE_CASE
)

private val SOURCE_REGEX = Regex(
    """(?i)sources\s*[:=]\s*\[\s*\{[^}]*["']?(?:file|src)["']?\s*:\s*["']([^"']+)["']"""
)

private val SUB_REGEX = Regex(
    """["'](https?://[^"']+\.(?:srt|vtt|ass)[^"']*)["']""",
    RegexOption.IGNORE_CASE
)

// ═══════════════════════════════════════════════════════════════════════════════
// NowHDTime Native Stream Extractor
// ═══════════════════════════════════════════════════════════════════════════════

class NowHDTimeStreamExtractor : ExtractorApi() {
    override val name = "NowHDTime"
    override val mainUrl = NowHDTimeUtils.mainUrl
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer ?: mainUrl, timeout = 20).text
        val fullHtml = deobfuscate(html)

        // Extract video sources
        listOf(
            Regex("""(?i)(?:file|src|source|url|video_url)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8|mkv|mpd)[^"']*)["']"""),
            SOURCE_REGEX,
            VIDEO_REGEX
        ).forEach { pattern ->
            pattern.findAll(fullHtml).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.startsWith("http")) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = qualityOf(videoUrl)
                        }
                    )
                }
            }
        }

        // Extract subtitles
        SUB_REGEX.findAll(fullHtml).forEach { match ->
            subtitleCallback(SubtitleFile("Auto", match.groupValues[1]))
        }

        // Process nested iframes
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { match ->
                var src = match.groupValues[1]
                if (src.startsWith("//")) src = "https:$src"
                if (src.startsWith("http") && !src.contains("nowhdtime.com.bd")) {
                    try {
                        loadExtractor(src, url, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Nontongo Extractor (Anime episodes via AniList)
// ═══════════════════════════════════════════════════════════════════════════════

class NontongoExtractor : ExtractorApi() {
    override val name = "Nontongo"
    override val mainUrl = "https://nontongo.win"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer ?: NowHDTimeUtils.mainUrl, timeout = 20).text
        val fullHtml = deobfuscate(html)

        listOf(SOURCE_REGEX, VIDEO_REGEX).forEach { pattern ->
            pattern.findAll(fullHtml).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.startsWith("http")) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = qualityOf(videoUrl)
                        }
                    )
                }
            }
        }

        SUB_REGEX.findAll(fullHtml).forEach { match ->
            subtitleCallback(SubtitleFile("Auto", match.groupValues[1]))
        }

        // Nested iframes
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { match ->
                var src = match.groupValues[1]
                if (src.startsWith("//")) src = "https:$src"
                if (src.startsWith("http") && !src.contains("nontongo.win")) {
                    try {
                        loadExtractor(src, url, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Dropload Extractor
// ═══════════════════════════════════════════════════════════════════════════════

class DroploadExtractor : ExtractorApi() {
    override val name = "Dropload"
    override val mainUrl = "https://dropload.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = deobfuscate(app.get(url, referer = referer, timeout = 20).text)

        VIDEO_REGEX.findAll(html).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (!videoUrl.contains("dropload.io")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = qualityOf(videoUrl)
                    }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// VidHide Extractor
// ═══════════════════════════════════════════════════════════════════════════════

class VidHideExtractor : ExtractorApi() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhide.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Handle multiple domains
        val normalizedUrl = url.replace(Regex("vidhide\\.(to|pro|cc)"), "vidhide.com")
        val html = deobfuscate(app.get(normalizedUrl, referer = referer, timeout = 20).text)

        listOf(SOURCE_REGEX, VIDEO_REGEX).forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.startsWith("http") && !videoUrl.contains("vidhide")) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = normalizedUrl
                            this.quality = qualityOf(videoUrl)
                        }
                    )
                }
            }
        }

        // Extract subtitle tracks
        Regex("""(?i)tracks\s*[:=]\s*\[([^\]]+)\]""").find(html)?.let { tracksMatch ->
            Regex("""(?i)["'](?:file|src)["']\s*:\s*["']([^"']+\.(?:srt|vtt))["']""")
                .findAll(tracksMatch.groupValues[1]).forEach { match ->
                    subtitleCallback(SubtitleFile("Auto", match.groupValues[1]))
                }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// FileLions Extractor
// ═══════════════════════════════════════════════════════════════════════════════

class FileLionsExtractor : ExtractorApi() {
    override val name = "FileLions"
    override val mainUrl = "https://filelions.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = url.replace(Regex("filelions\\.(live|online|site)"), "filelions.to")
        val html = deobfuscate(app.get(normalizedUrl, referer = referer, timeout = 20).text)

        listOf(SOURCE_REGEX, VIDEO_REGEX).forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.startsWith("http") && !videoUrl.contains("filelions")) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = normalizedUrl
                            this.quality = qualityOf(videoUrl)
                        }
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// StreamWish Extractor
// ═══════════════════════════════════════════════════════════════════════════════

class StreamWishExtractor : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = url.replace(Regex("streamwish\\.(com|site)"), "streamwish.to")
        val html = deobfuscate(app.get(normalizedUrl, referer = referer, timeout = 20).text)

        listOf(SOURCE_REGEX, VIDEO_REGEX).forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.startsWith("http")) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = normalizedUrl
                            this.quality = qualityOf(videoUrl)
                        }
                    )
                }
            }
        }

        SUB_REGEX.findAll(html).forEach { match ->
            subtitleCallback(SubtitleFile("Auto", match.groupValues[1]))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DoodStream Extractor
// ═══════════════════════════════════════════════════════════════════════════════

class DoodExtractor : ExtractorApi() {
    override val name = "DoodStream"
    override val mainUrl = "https://dood.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Normalize URL to primary domain
        val normalizedUrl = url.replace(
            Regex("dood\\.(to|la|li|re|pm|sh|yt|watch|stream|cx|ws|one)"),
            "dood.to"
        )

        val html = app.get(normalizedUrl, referer = referer, timeout = 20).text

        // Extract pass_md5 path
        val passPath = Regex("""\$\.get\('/pass_md5/([^']+)'""")
            .find(html)?.groupValues?.get(1) ?: return

        // Extract token
        val token = Regex("""token=([^&"'\s]+)""")
            .find(html)?.groupValues?.get(1) ?: ""

        try {
            val md5Response = app.get(
                "$mainUrl/pass_md5/$passPath",
                referer = normalizedUrl,
                timeout = 15
            ).text

            if (md5Response.startsWith("http")) {
                // Generate random string for URL suffix
                val randomSuffix = (1..10).map { ('a'..'z').random() }.joinToString("")
                val finalUrl = "$md5Response$randomSuffix?token=$token"

                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = normalizedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MixDrop Extractor
// ═══════════════════════════════════════════════════════════════════════════════

class MixdropExtractor : ExtractorApi() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.co"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = url.replace(
            Regex("mixdrop\\.(to|club|bz|ch|gl|ag|sx|nu)"),
            "mixdrop.co"
        )

        val html = deobfuscate(app.get(normalizedUrl, referer = referer, timeout = 20).text)

        // Extract wurl or MDCore video URL
        Regex("""(?i)(?:wurl|surl|wvideo|MDCore\.v)\s*[:=]\s*["']([^"']+)["']""")
            .find(html)?.let { match ->
                var videoUrl = match.groupValues[1]
                if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"

                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = normalizedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Voe Extractor
// ═══════════════════════════════════════════════════════════════════════════════

class VoeExtractor : ExtractorApi() {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer, timeout = 20).text

        // Try HLS first
        Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.let { match ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = match.groupValues[1],
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

        // Try MP4
        Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.let { match ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = match.groupValues[1],
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

        // Try Base64 encoded URL
        Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""").find(html)?.let { match ->
            try {
                val decoded = String(android.util.Base64.decode(match.groupValues[1], android.util.Base64.DEFAULT))
                if (decoded.startsWith("http")) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = decoded,
                            type = if (decoded.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } catch (_: Exception) {}
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Upstream Extractor
// ═══════════════════════════════════════════════════════════════════════════════

class UpstreamExtractor : ExtractorApi() {
    override val name = "Upstream"
    override val mainUrl = "https://upstream.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = deobfuscate(app.get(url, referer = referer, timeout = 20).text)

        VIDEO_REGEX.findAll(html).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (!videoUrl.contains("upstream.to")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = qualityOf(videoUrl)
                    }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Generic M3U8/MP4 Extractor (Fallback)
// ═══════════════════════════════════════════════════════════════════════════════

class GenericM3u8Extractor : ExtractorApi() {
    override val name = "Direct"
    override val mainUrl = "https://"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Only handle direct video URLs
        if (!NowHDTimeUtils.isDirectVideo(url)) return

        callback(
            newExtractorLink(
                source = name,
                name = "Direct Stream",
                url = url,
                type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer ?: ""
                this.quality = qualityOf(url)
            }
        )
    }
}