package com.wizdier

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.util.regex.Pattern

// ===== Generic Stream Extractor for NowHDTime internal player =====
class NowHDTimeStreamExtractor : ExtractorApi() {
    override val name = "NowHDTime Stream"
    override val mainUrl = "https://nowhdtime.com.bd"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer ?: mainUrl, timeout = 20)
        val html = response.text

        // Extract video sources from the player page
        val patterns = listOf(
            Pattern.compile("(?:file|src|source|url)\\s*[:=]\\s*[\"'](https?://[^\"']+\\.(?:mp4|m3u8|mkv|mpd)[^\"']*)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("sources\\s*:\\s*\\[\\{[^}]*[\"']?(?:file|src)[\"']?\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<source[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("player\\.src\\s*\\(\\s*\\{[^}]*src\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("videoUrl\\s*[:=]\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(html)
            while (matcher.find()) {
                val videoUrl = matcher.group(1) ?: continue
                if (videoUrl.startsWith("http")) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            referer = url,
                            quality = detectQualityFromUrl(videoUrl),
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                    )
                }
            }
        }

        // Extract subtitles
        val subPattern = Pattern.compile("[\"'](https?://[^\"']+\\.(?:srt|vtt|ass)[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
        val subMatcher = subPattern.matcher(html)
        while (subMatcher.find()) {
            val subUrl = subMatcher.group(1) ?: continue
            subtitleCallback.invoke(SubtitleFile("Auto", subUrl))
        }

        // Check for iframes
        val iframePattern = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val iframeMatcher = iframePattern.matcher(html)
        while (iframeMatcher.find()) {
            val iframeSrc = iframeMatcher.group(1) ?: continue
            val fullUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            if (fullUrl.startsWith("http") && !fullUrl.contains("nowhdtime.com.bd")) {
                try {
                    loadExtractor(fullUrl, url, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }
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
}

// ===== Dropload Extractor =====
class NowHDTimeDroploadExtractor : ExtractorApi() {
    override val name = "Dropload"
    override val mainUrl = "https://dropload.io"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer, timeout = 20)
        val html = response.text

        // Extract packed/encoded JavaScript
        val packedPattern = Pattern.compile("eval\\(function\\(p,a,c,k,e,[dr]\\)\\{.*?\\}\\('([^']+)'", Pattern.DOTALL)
        val packedMatcher = packedPattern.matcher(html)

        val searchHtml = if (packedMatcher.find()) {
            try {
                JsUnpacker.unpack(packedMatcher.group(0) ?: html) ?: html
            } catch (_: Exception) { html }
        } else html

        val videoPattern = Pattern.compile("[\"'](https?://[^\"']+\\.(?:mp4|m3u8|mkv)[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
        val videoMatcher = videoPattern.matcher(searchHtml)
        while (videoMatcher.find()) {
            val videoUrl = videoMatcher.group(1) ?: continue
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

// ===== VidHide Extractor =====
class NowHDTimeVidhideExtractor : ExtractorApi() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhide.com"
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

        val sourcePatterns = listOf(
            Pattern.compile("sources\\s*[:=]\\s*\\[\\s*\\{[^}]*[\"']?(?:file|src)[\"']?\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[\"'](https?://[^\"']*\\.(?:mp4|m3u8)[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
        )

        for (pattern in sourcePatterns) {
            val matcher = pattern.matcher(fullHtml)
            while (matcher.find()) {
                val videoUrl = matcher.group(1) ?: continue
                if (videoUrl.startsWith("http") && !videoUrl.contains("vidhide.com")) {
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

        // Subtitles
        val trackPattern = Pattern.compile("tracks\\s*[:=]\\s*\\[([^]]+)]", Pattern.CASE_INSENSITIVE)
        val trackMatcher = trackPattern.matcher(fullHtml)
        if (trackMatcher.find()) {
            val tracks = trackMatcher.group(1) ?: ""
            val subFilePattern = Pattern.compile("[\"'](?:file|src)[\"']\\s*:\\s*[\"']([^\"']+\\.(?:srt|vtt))[\"']", Pattern.CASE_INSENSITIVE)
            val subFileMatcher = subFilePattern.matcher(tracks)
            while (subFileMatcher.find()) {
                val subUrl = subFileMatcher.group(1) ?: continue
                subtitleCallback.invoke(SubtitleFile("Auto", subUrl))
            }
        }
    }
}

// ===== FileLions Extractor =====
class NowHDTimeFilelionsExtractor : ExtractorApi() {
    override val name = "FileLions"
    override val mainUrl = "https://filelions.to"
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

        val videoPattern = Pattern.compile("[\"'](https?://[^\"']*\\.(?:mp4|m3u8|mkv)[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
        val videoMatcher = videoPattern.matcher(fullHtml)
        while (videoMatcher.find()) {
            val videoUrl = videoMatcher.group(1) ?: continue
            if (!videoUrl.contains("filelions")) {
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

// ===== StreamWish Extractor =====
class NowHDTimeStreamwishExtractor : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.to"
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

        // Extract m3u8 or mp4 sources
        val sourcePattern = Pattern.compile("sources\\s*[:=]\\s*\\[\\{[^}]*file\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val sourceMatcher = sourcePattern.matcher(fullHtml)
        while (sourceMatcher.find()) {
            val videoUrl = sourceMatcher.group(1) ?: continue
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    referer = url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = videoUrl.contains(".m3u8"),
                    headers = mapOf("Referer" to url)
                )
            )
        }

        // Fallback: any video URL
        val fallbackPattern = Pattern.compile("[\"'](https?://[^\"']+\\.(?:m3u8|mp4)[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
        val fallbackMatcher = fallbackPattern.matcher(fullHtml)
        while (fallbackMatcher.find()) {
            val videoUrl = fallbackMatcher.group(1) ?: continue
            if (!videoUrl.contains("streamwish")) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name Fallback",
                        url = videoUrl,
                        referer = url,
                        quality = Qualities.Unknown.value,
                        isM3u8 = videoUrl.contains(".m3u8"),
                        headers = mapOf("Referer" to url)
                    )
                )
            }
        }

        // Subtitles
        val subPattern = Pattern.compile("[\"'](https?://[^\"']+\\.(?:srt|vtt)[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
        val subMatcher = subPattern.matcher(fullHtml)
        while (subMatcher.find()) {
            subtitleCallback.invoke(SubtitleFile("Auto", subMatcher.group(1) ?: continue))
        }
    }
}

// ===== Dood Extractor =====
class NowHDTimeDoodExtractor : ExtractorApi() {
    override val name = "DoodStream"
    override val mainUrl = "https://dood.to"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Normalize Dood URLs
        val normalizedUrl = url
            .replace("dood.la", "dood.to")
            .replace("dood.so", "dood.to")
            .replace("dood.pm", "dood.to")
            .replace("dood.wf", "dood.to")
            .replace("dood.cx", "dood.to")
            .replace("dood.sh", "dood.to")
            .replace("dood.watch", "dood.to")
            .replace("doodstream.com", "dood.to")
            .replace("/d/", "/e/")

        val response = app.get(normalizedUrl, timeout = 20)
        val html = response.text

        // Extract the pass_md5 URL
        val md5Pattern = Pattern.compile("/pass_md5/[^'\"]+", Pattern.CASE_INSENSITIVE)
        val md5Matcher = md5Pattern.matcher(html)

        if (md5Matcher.find()) {
            val passMd5 = md5Matcher.group(0) ?: return
            val passMd5Url = "${mainUrl}${passMd5}"

            val tokenResponse = app.get(passMd5Url, referer = normalizedUrl, timeout = 15)
            val directUrl = tokenResponse.text

            if (directUrl.startsWith("http")) {
                val token = "zUEJhGXlQ"
                val finalUrl = "${directUrl}${token}?token=${passMd5.substringAfterLast("/")}&expiry=${System.currentTimeMillis()}"

                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = finalUrl,
                        referer = normalizedUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = false,
                        headers = mapOf(
                            "Referer" to normalizedUrl,
                            "Range" to "bytes=0-"
                        )
                    )
                )
            }
        }
    }
}

// ===== Mixdrop Extractor =====
class NowHDTimeMixdropExtractor : ExtractorApi() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.co"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val normalizedUrl = url
            .replace("mixdrop.to", "mixdrop.co")
            .replace("mixdrop.club", "mixdrop.co")
            .replace("mixdrop.bz", "mixdrop.co")
            .replace("mixdrop.ch", "mixdrop.co")
            .replace("mixdrop.gl", "mixdrop.co")
            .replace("mixdrop.ag", "mixdrop.co")

        val response = app.get(normalizedUrl, referer = referer, timeout = 20)
        val html = response.text

        // Mixdrop uses eval/packed JS
        val unpackedHtml = try {
            val packedPattern = Pattern.compile("eval\\(function\\(p,a,c,k,e,[dr]\\)\\{.*?\\}\\('[^']*'", Pattern.DOTALL)
            val packedMatcher = packedPattern.matcher(html)
            if (packedMatcher.find()) {
                JsUnpacker.unpack(packedMatcher.group(0) ?: "") ?: html
            } else html
        } catch (_: Exception) { html }

        // Look for MDCore.wurl or similar
        val wurlPattern = Pattern.compile("(?:wurl|surl|wvideo)\\s*[:=]\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val wurlMatcher = wurlPattern.matcher(unpackedHtml)
        if (wurlMatcher.find()) {
            var videoUrl = wurlMatcher.group(1) ?: return
            if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"

            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    referer = normalizedUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false
                )
            )
        }
    }
}

// ===== Voe Extractor =====
class NowHDTimeVoeExtractor : ExtractorApi() {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer, timeout = 20)
        val html = response.text

        // Voe uses HLS
        val hlsPattern = Pattern.compile("[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
        val hlsMatcher = hlsPattern.matcher(html)
        if (hlsMatcher.find()) {
            val hlsUrl = hlsMatcher.group(1) ?: return
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = hlsUrl,
                    referer = url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return
        }

        // MP4 fallback
        val mp4Pattern = Pattern.compile("[\"'](https?://[^\"']+\\.mp4[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
        val mp4Matcher = mp4Pattern.matcher(html)
        if (mp4Matcher.find()) {
            val mp4Url = mp4Matcher.group(1) ?: return
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
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