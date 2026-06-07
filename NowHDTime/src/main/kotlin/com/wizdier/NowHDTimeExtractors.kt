package com.wizdier

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

// ─────────────────────────────────────────────────────────────────────────────
//  JS p,a,c,k,e,d unpacker
// ─────────────────────────────────────────────────────────────────────────────
object JsUnpacker {
    fun unpack(packed: String?): String? {
        if (packed == null) return null
        return try {
            val m = Regex(
                """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.+?)',(\d+),(\d+),'([^']*)'""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            ).find(packed) ?: return null
            val payload  = m.groupValues[1]
            val radix    = m.groupValues[2].toIntOrNull() ?: return null
            val count    = m.groupValues[3].toIntOrNull() ?: return null
            val keywords = m.groupValues[4].split("|")
            if (keywords.size != count) return null
            var result = payload
            for (i in count - 1 downTo 0) {
                val rep = keywords.getOrNull(i) ?: continue
                if (rep.isNotBlank()) result = result.replace(Regex("\\b${Integer.toString(i, radix)}\\b"), rep)
            }
            result
        } catch (_: Exception) { null }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared helpers
// ─────────────────────────────────────────────────────────────────────────────
private fun deobfuscate(html: String): String {
    val packed = Regex(
        """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('[^']*'""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    ).find(html)
    return if (packed != null) "$html\n${JsUnpacker.unpack(packed.value) ?: ""}" else html
}

private fun qualityOf(url: String): Int = when {
    url.contains("2160") || url.contains("4k", ignoreCase = true) -> Qualities.UHD4K.value
    url.contains("1080")                                           -> Qualities.P1080.value
    url.contains("720")                                            -> Qualities.P720.value
    url.contains("480")                                            -> Qualities.P480.value
    else                                                           -> Qualities.Unknown.value
}

private val VIDEO_RE  = Regex("""["'](https?://[^"']+\.(?:mp4|m3u8|mkv|mpd)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
private val SOURCE_RE = Regex("""(?i)sources\s*[:=]\s*\[\s*\{[^}]*["']?(?:file|src)["']?\s*:\s*["']([^"']+)["']""")
private val SUB_RE    = Regex("""["'](https?://[^"']+\.(?:srt|vtt|ass)[^"']*)["']""", RegexOption.IGNORE_CASE)

// ─────────────────────────────────────────────────────────────────────────────
//  NowHDTime – catches the native player pages hosted on the same domain
//  (e.g. /movie/watch-xxx or /tv-show/watch-xxx when a server iframe points
//   back to the site itself)
// ─────────────────────────────────────────────────────────────────────────────
class NowHDTimeStreamExtractor : ExtractorApi() {
    override val name         = "NowHDTime"
    override val mainUrl      = NowHDTimeUtils.mainUrl
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val html     = app.get(url, referer = referer ?: mainUrl, timeout = 20).text
        val fullHtml = deobfuscate(html)

        for (pat in listOf(
            Regex("""(?i)(?:file|src|source|url|video_url)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8|mkv|mpd)[^"']*)["']"""),
            SOURCE_RE,
            Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        )) pat.findAll(fullHtml).forEach { m ->
            val v = m.groupValues[1]
            if (v.startsWith("http")) callback(ExtractorLink(name, name, v, url, qualityOf(v), v.contains(".m3u8")))
        }
        SUB_RE.findAll(fullHtml).forEach { callback(SubtitleFile("Auto", it.groupValues[1])) }

        // nested iframes
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val src = m.groupValues[1].let { if (it.startsWith("//")) "https:$it" else it }
            if (src.startsWith("http") && !src.contains("nowhdtime.com.bd"))
                try { loadExtractor(src, url, subtitleCallback, callback) } catch (_: Exception) {}
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Nontongo – the anime episode embed host used by NowHDTime
//  URL pattern: https://nontongo.win/anime/{anilistId}/{ep}/play
// ─────────────────────────────────────────────────────────────────────────────
class NontongoExtractor : ExtractorApi() {
    override val name         = "Nontongo"
    override val mainUrl      = "https://nontongo.win"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val html     = app.get(url, referer = referer ?: NowHDTimeUtils.mainUrl, timeout = 20).text
        val fullHtml = deobfuscate(html)

        // Look for video sources
        for (pat in listOf(SOURCE_RE, VIDEO_RE)) {
            pat.findAll(fullHtml).forEach { m ->
                val v = m.groupValues[1]
                if (v.startsWith("http")) callback(ExtractorLink(name, name, v, url, qualityOf(v), v.contains(".m3u8")))
            }
        }
        SUB_RE.findAll(fullHtml).forEach { callback(SubtitleFile("Auto", it.groupValues[1])) }

        // Follow any nested iframes
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val src = m.groupValues[1].let { if (it.startsWith("//")) "https:$it" else it }
            if (src.startsWith("http") && !src.contains("nontongo.win"))
                try { loadExtractor(src, url, subtitleCallback, callback) } catch (_: Exception) {}
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Dropload
// ─────────────────────────────────────────────────────────────────────────────
class DroploadExtractor : ExtractorApi() {
    override val name    = "Dropload"
    override val mainUrl = "https://dropload.io"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = deobfuscate(app.get(url, referer = referer, timeout = 20).text)
        VIDEO_RE.findAll(html).forEach { m ->
            val v = m.groupValues[1]
            if (!v.contains("dropload.io")) callback(ExtractorLink(name, name, v, url, qualityOf(v), v.contains(".m3u8")))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  VidHide
// ─────────────────────────────────────────────────────────────────────────────
class VidHideExtractor : ExtractorApi() {
    override val name    = "VidHide"
    override val mainUrl = "https://vidhide.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = deobfuscate(app.get(url, referer = referer, timeout = 20).text)
        for (pat in listOf(SOURCE_RE, VIDEO_RE)) pat.findAll(html).forEach { m ->
            val v = m.groupValues[1]
            if (v.startsWith("http") && !v.contains("vidhide")) callback(ExtractorLink(name, name, v, url, qualityOf(v), v.contains(".m3u8")))
        }
        Regex("""(?i)tracks\s*[:=]\s*\[([^\]]+)]""").find(html)?.let { tk ->
            Regex("""(?i)["'](?:file|src)["']\s*:\s*["']([^"']+\.(?:srt|vtt))["']""")
                .findAll(tk.groupValues[1]).forEach { callback(SubtitleFile("Auto", it.groupValues[1])) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  FileLions
// ─────────────────────────────────────────────────────────────────────────────
class FileLionsExtractor : ExtractorApi() {
    override val name    = "FileLions"
    override val mainUrl = "https://filelions.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = deobfuscate(app.get(url, referer = referer, timeout = 20).text)
        for (pat in listOf(SOURCE_RE, VIDEO_RE)) pat.findAll(html).forEach { m ->
            val v = m.groupValues[1]
            if (v.startsWith("http") && !v.contains("filelions")) callback(ExtractorLink(name, name, v, url, qualityOf(v), v.contains(".m3u8")))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  StreamWish
// ─────────────────────────────────────────────────────────────────────────────
class StreamWishExtractor : ExtractorApi() {
    override val name    = "StreamWish"
    override val mainUrl = "https://streamwish.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = deobfuscate(app.get(url, referer = referer, timeout = 20).text)
        for (pat in listOf(SOURCE_RE, VIDEO_RE)) pat.findAll(html).forEach { m ->
            val v = m.groupValues[1]
            if (v.startsWith("http")) callback(ExtractorLink(name, name, v, url, qualityOf(v), v.contains(".m3u8")))
        }
        SUB_RE.findAll(html).forEach { callback(SubtitleFile("Auto", it.groupValues[1])) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  DoodStream
// ─────────────────────────────────────────────────────────────────────────────
class DoodExtractor : ExtractorApi() {
    override val name    = "DoodStream"
    override val mainUrl = "https://dood.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val norm = url.replace(Regex("dood\\.(to|la|li|re|pm|sh|yt|watch|stream|cx)"), "dood.to")
        val html = app.get(norm, referer = referer, timeout = 20).text

        // Dood uses a JS token construction: /pass_md5/... + random suffix
        val pass = Regex("""\$\.get\('/pass_md5/([^']+)'""").find(html)?.groupValues?.get(1) ?: return
        val token = Regex("""token=([^&"'\s]+)""").find(html)?.groupValues?.get(1) ?: ""
        try {
            val md5 = app.get("$mainUrl/pass_md5/$pass", referer = norm, timeout = 15).text
            if (md5.startsWith("http")) {
                val final = "$md5${(1..10).map { ('a'..'z').random() }.joinToString("")}?token=$token"
                callback(ExtractorLink(name, name, final, norm, Qualities.Unknown.value, false))
            }
        } catch (_: Exception) {}
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  MixDrop
// ─────────────────────────────────────────────────────────────────────────────
class MixdropExtractor : ExtractorApi() {
    override val name    = "MixDrop"
    override val mainUrl = "https://mixdrop.co"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val norm = url.replace(Regex("mixdrop\\.(to|club|bz|ch|gl|ag|sx)"), "mixdrop.co")
        val html = deobfuscate(app.get(norm, referer = referer, timeout = 20).text)
        Regex("""(?i)(?:wurl|surl|wvideo)\s*[:=]\s*["']([^"']+)["']""").find(html)?.let { m ->
            var v = m.groupValues[1]
            if (v.startsWith("//")) v = "https:$v"
            callback(ExtractorLink(name, name, v, norm, Qualities.Unknown.value, false))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Voe
// ─────────────────────────────────────────────────────────────────────────────
class VoeExtractor : ExtractorApi() {
    override val name    = "Voe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer, timeout = 20).text
        // HLS first
        Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.let {
            callback(ExtractorLink(name, name, it.groupValues[1], url, Qualities.Unknown.value, true)); return
        }
        // MP4
        Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.let {
            callback(ExtractorLink(name, name, it.groupValues[1], url, Qualities.Unknown.value, false)); return
        }
        // Base64
        Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""").find(html)?.let { m ->
            try {
                val dec = String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT))
                if (dec.startsWith("http")) callback(ExtractorLink(name, name, dec, url, Qualities.Unknown.value, dec.contains(".m3u8")))
            } catch (_: Exception) {}
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Upstream
// ─────────────────────────────────────────────────────────────────────────────
class UpstreamExtractor : ExtractorApi() {
    override val name    = "Upstream"
    override val mainUrl = "https://upstream.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = deobfuscate(app.get(url, referer = referer, timeout = 20).text)
        VIDEO_RE.findAll(html).forEach { m ->
            val v = m.groupValues[1]
            if (!v.contains("upstream.to")) callback(ExtractorLink(name, name, v, url, qualityOf(v), v.contains(".m3u8")))
        }
    }
}
ometimes uses this)
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