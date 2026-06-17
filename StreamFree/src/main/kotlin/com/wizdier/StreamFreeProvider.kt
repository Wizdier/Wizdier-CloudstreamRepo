package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class StreamFreeProvider : MainAPI() {
    override var mainUrl = "https://streamfree.app"
    override var name = "StreamFree"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override var lang = "en"
    override val supportedTypes: Set<TvType> = setOf(TvType.Live)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "*/*"
    )

    private val homePageList = listOf(
        MainPageData("all", "All Live Streams"),
        MainPageData("soccer", "Soccer"),
        MainPageData("basketball", "Basketball"),
        MainPageData("baseball", "Baseball"),
        MainPageData("combat", "Fight / MMA / UFC"),
        MainPageData("racing", "Motorsport / F1"),
        MainPageData("tennis", "Tennis"),
        MainPageData("cricket", "Cricket")
    )

    override val mainPage = mainPageOf(*homePageList.map { Pair(it.data, it.name) }.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = runCatching {
            app.get(
                "$mainUrl/api/carousel-streams",
                headers = baseHeaders + ("Referer" to "$mainUrl/"),
                timeout = 15000
            )
        }.getOrNull() ?: return newHomePageResponse(emptyList())

        if (res.code !in 200..299 || res.text.isBlank()) return newHomePageResponse(emptyList())
        val streams = JSONObject(res.text).optJSONArray("streams") ?: JSONArray()
        val list = mutableListOf<SearchResponse>()

        for (i in 0 until streams.length()) {
            val obj = streams.optJSONObject(i) ?: continue
            val category = obj.optString("category", "").lowercase()
            if (request.data == "all" || request.data == category) {
                obj.toSearchResponse()?.let(list::add)
            }
        }

        return newHomePageResponse(
            HomePageList(request.name, list, isHorizontalImages = true),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()

        val res = runCatching {
            app.get(
                "$mainUrl/api/carousel-streams",
                headers = baseHeaders + ("Referer" to "$mainUrl/"),
                timeout = 15000
            )
        }.getOrNull() ?: return emptyList()

        if (res.code !in 200..299 || res.text.isBlank()) return emptyList()
        val streams = JSONObject(res.text).optJSONArray("streams") ?: JSONArray()
        val list = mutableListOf<SearchResponse>()

        for (i in 0 until streams.length()) {
            val obj = streams.optJSONObject(i) ?: continue
            val name = obj.optString("name", "")
            if (name.lowercase().contains(q)) {
                obj.toSearchResponse()?.let(list::add)
            }
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.substringBefore("?")
        val category = cleanUrl.substringAfter("/player/").substringBefore("/")
        val streamKey = cleanUrl.substringAfterLast("/")

        val res = runCatching {
            app.get(
                "$mainUrl/api/carousel-streams",
                headers = baseHeaders + ("Referer" to "$mainUrl/"),
                timeout = 15000
            )
        }.getOrNull() ?: return null

        var title = "Live Stream"
        var poster: String? = null
        var date: Long? = null

        if (res.code in 200..299 && res.text.isNotBlank()) {
            val streams = JSONObject(res.text).optJSONArray("streams") ?: JSONArray()
            for (i in 0 until streams.length()) {
                val obj = streams.optJSONObject(i) ?: continue
                if (obj.optString("stream_key") == streamKey) {
                    title = obj.optString("name", title)
                    poster = obj.optStringOrNull("thumbnail_url")?.toAbsoluteUrl()
                    date = obj.optLong("match_timestamp", 0L).takeIf { it > 0L }
                    break
                }
            }
        }

        val data = JSONObject()
            .put("category", category)
            .put("streamKey", streamKey)
            .toString()

        return newMovieLoadResponse(title, url, TvType.Live, data) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = buildString {
                append("Live Sports Streaming")
                date?.let { append("\nScheduled: ").append(java.util.Date(it * 1000).toString()) }
                append("\nCategory: ").append(category.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() })
            }
            tags = listOf(name, category)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return false
        val category = root.optString("category") ?: return false
        val streamKey = root.optString("streamKey") ?: return false

        val playerUrl = "$mainUrl/player/$category/$streamKey"
        val embedUrl = "$mainUrl/embed/$category/$streamKey?quality=720p&category=$category"

        // 1. Load the embed frame HTML with player page Referer
        val embedRes = runCatching {
            app.get(embedUrl, headers = baseHeaders + ("Referer" to playerUrl), timeout = 15000)
        }.getOrNull() ?: return false

        if (embedRes.code !in 200..299 || embedRes.text.isBlank()) return false
        val html = embedRes.text

        // 2. Extract _0x quality tokens
        val tokensString = Regex("""const\s+_0x\s*=\s*(\{.*?\});""").find(html)?.groupValues?.getOrNull(1) ?: return false
        val tokens = JSONObject(tokensString)

        // 3. Fetch stream routing key from the direct JSON API
        val streamKeyUrl = "$mainUrl/get-stream-key/$streamKey"
        val keyRes = runCatching {
            app.get(streamKeyUrl, headers = baseHeaders + ("Referer" to embedUrl), timeout = 12000)
        }.getOrNull() ?: return false

        if (keyRes.code !in 200..299 || keyRes.text.isBlank()) return false
        val keyData = JSONObject(keyRes.text)

        val serverName = keyData.optString("server_name", "origin")
        val finalStreamKey = keyData.optString("stream_key", streamKey)

        var found = false

        // 4. Assemble direct .m3u8 urls for each quality available
        listOf("540p", "720p", "1080p", "2160p").forEach { quality ->
            val p = tokens.optJSONObject(quality) ?: return@forEach
            val path = if (serverName != "origin") {
                "/live-cdn/$finalStreamKey$quality/index.m3u8"
            } else {
                "/live/$finalStreamKey$quality/index.m3u8"
            }
            val m3u8Url = "$mainUrl$path?_t=${p.optString("_t")}&_e=${p.optString("_e")}&_n=${p.optString("_n")}"

            val label = "$name • $quality"
            callback(
                newExtractorLink(
                    source = label,
                    name = label,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = embedUrl
                    this.quality = when (quality) {
                        "2160p" -> Qualities.P2160.value
                        "1080p" -> Qualities.P1080.value
                        "720p" -> Qualities.P720.value
                        else -> Qualities.P480.value
                    }
                    this.headers = baseHeaders + ("Referer" to embedUrl)
                }
            )
            found = true
        }

        return found
    }

    private fun JSONObject.toSearchResponse(): SearchResponse? {
        val streamKey = optStringOrNull("stream_key") ?: return null
        val category = optStringOrNull("category") ?: return null
        val title = optStringOrNull("name") ?: streamKey
        val poster = optStringOrNull("thumbnail_url")?.toAbsoluteUrl()
        val url = "$mainUrl/player/$category/$streamKey"

        return newMovieSearchResponse(title, url, TvType.Live) {
            posterUrl = poster
        }
    }

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("http") -> this
        startsWith("/") -> "$mainUrl$this"
        else -> "$mainUrl/$this"
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        optString(name, "").trim().takeIf { it.isNotBlank() }
}
