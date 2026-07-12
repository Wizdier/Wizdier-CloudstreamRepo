package com.wizdier.anistream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log

// ═══════════════════════════════════════════════════════════════
//  CUSTOM EXTRACTOR APIs
//  Kwik + Pahe — needed for Animepahe sources.
//  Adapted from CineStream (github.com/SaurabhKaperwan/CSX).
// ═══════════════════════════════════════════════════════════════

/**
 * Kwik — direct video extractor for kwik.cx links.
 * Unpacks the obfuscated JS to find the m3u8 URL.
 */
class Kwik : ExtractorApi() {
    override val name = "Kwik"
    override val mainUrl = "https://kwik.cx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer)
        val script = res.document.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return
        val unpacked = getAndUnpack(script)
        val m3u8 = Regex("source=\\s*'(.*?m3u8.*?)'").find(unpacked)?.groupValues?.getOrNull(1) ?: return
        callback.invoke(
            newExtractorLink(name, name, m3u8, type = ExtractorLinkType.M3U8) {
                this.referer = url
            }
        )
    }
}

/**
 * Pahe — resolves pahe.win redirect links to kwik.cx direct video.
 * Decrypts the obfuscated form parameters, POSTs to get the redirect
 * location, and emits the final video URL.
 */
class Pahe : ExtractorApi() {
    override val name = "Pahe"
    override val mainUrl = "https://pahe.win"
    override val requiresReferer = true

    private val kwikParamsRegex = Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""")
    private val kwikDUrl = Regex("action=\"([^\"]+)\"")
    private val kwikDToken = Regex("value=\"([^\"]+)\"")

    private fun decrypt(fullString: String, key: String, v1: Int, v2: Int): String {
        val keyIndexMap = key.withIndex().associate { it.value to it.index }
        val sb = StringBuilder()
        var i = 0
        val toFind = key[v2]
        while (i < fullString.length) {
            val nextIndex = fullString.indexOf(toFind, i)
            val decodedCharStr = buildString {
                for (j in i until nextIndex) {
                    append(keyIndexMap[fullString[j]] ?: -1)
                }
            }
            i = nextIndex + 1
            sb.append((decodedCharStr.toInt(v2) - v1).toChar())
        }
        return sb.toString()
    }

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val kwikUrl = "https://" + app.get("$url/i", allowRedirects = false)
            .headers["location"]!!.substringAfterLast("https://")

        val fContent = app.get(kwikUrl, mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer.toString(),
        ))
        val fContentString = fContent.text

        val match = kwikParamsRegex.find(fContentString) ?: return
        val (fullString, key, v1, v2) = match.destructured
        val decrypted = decrypt(fullString, key, v1.toInt(), v2.toInt())
        val uri = kwikDUrl.find(decrypted)?.destructured?.component1() ?: return
        val tok = kwikDToken.find(decrypted)?.destructured?.component1() ?: return

        // Retry the POST until we get a 302 redirect (kwik sometimes 419s).
        var code = 419; var tries = 0; var location = ""
        while (code != 302 && tries < 20) {
            val response = app.post(uri,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to fContent.url),
                data = mapOf("_token" to tok),
                allowRedirects = false
            )
            code = response.code
            if (code == 302) location = response.headers["location"] ?: ""
            tries++
        }
        if (location.isBlank()) return

        callback.invoke(
            newExtractorLink(name, name, location) {
                this.referer = "https://kwik.cx/"
            }
        )
    }
}
