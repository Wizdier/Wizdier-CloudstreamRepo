package com.wizdier

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.api.Log
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Mediaserver (http://103.225.94.27/mediaserver) — a plain server-rendered
 * WordPress site (streamTube theme), no login. Reverse-engineered live
 * 2026-07-25:
 *
 *   • ONE flat post type: /index.php/video/<id>/ — movies and single
 *     series episodes ("One Piece S01E08") are all sibling posts; the site
 *     has no series grouping, so every post loads as a Movie response.
 *   • Search: /index.php/?s={q} → normal WP search, card grid with
 *     h2.post-meta__title a.
 *   • Player: inline <video-js … data-settings="JSON"> — the JSON (HTML-
 *     entity-escaped; Jsoup attr() decodes it) carries sources[] with
 *     direct {src, type:"video/mp4"} entries. Poster also in that JSON.
 *   • Sources verified: HTTP 206 partial content, video/mp4.
 *
 * NOTE: the host is a raw IP inside a BD/ISP network — playback reachability
 * can depend on the viewer's own ISP route (typical for BDIX hosts).
 */
class MediaserverProvider : MainAPI() {
    override var mainUrl = "http://103.225.94.27/mediaserver"
    override var name = "Mediaserver"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie
    )

    companion object {
        private val YEAR_RE = Regex("""\b(19\d{2}|20\d{2})\b""")
        private val MEDIA_URL_RE = Regex(
            """https?://[^\s"'<>\\]+\.(?:mp4|mkv|m3u8|webm|m4v)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Videos",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = runCatching { app.get(request.data).document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        return newHomePageResponse(request.name, parseCards(doc), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = runCatching { app.get("$mainUrl/index.php/?s=$q").document }.getOrNull()
            ?: return emptyList()
        return parseCards(doc)
    }

    /** Cards appear as `h2.post-meta__title a` (search/grid) and as
     *  `article a.post-permalink` + bg-image divs (homepage hero). Handle
     *  both, deduped by URL. */
    private fun parseCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        doc.select("h2.post-meta__title a[href], h2.post-title a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val title = a.text().trim()
            if (href.isBlank() || title.isBlank() || out.containsKey(href)) return@forEach
            val card = a.closest(".post-meta") ?: a.closest("article") ?: a.parent()
            val poster = posterFrom(card)
            out[href] = newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
        doc.select("a.post-permalink[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val title = a.attr("title").trim()
            if (href.isBlank() || title.isBlank() || out.containsKey(href)) return@forEach
            val card = a.closest("article") ?: a.parent()
            val poster = posterFrom(card)
            out[href] = newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
        return out.values.toList()
    }

    private fun posterFrom(card: Element?): String? {
        if (card == null) return null
        card.selectFirst("img")?.let { img ->
            val src = img.attr("data-src").trim().ifBlank { img.attr("src").trim() }
            if (src.isNotBlank() && !src.startsWith("data:")) return src
        }
        // Hero cards use style="background-image: url(...)" on .post-thumbnail.
        (card.selectFirst(".post-thumbnail[style*=\"url(\"]") ?: card).let { el ->
            val style = el?.attr("style").orEmpty()
            val m = Regex("""url\((['"]?)(.+?)\1\)""").find(style)
            val u = m?.groupValues?.get(2)?.trim()
            if (!u.isNullOrBlank() && u.startsWith("http")) return u
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url).document }.getOrNull() ?: return null
        val title = doc.selectFirst("h1.post-title")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" | ")?.trim()?.ifBlank { null }
            ?: doc.title().substringBefore(" | ").trim().ifBlank { null }
            ?: return null
        val poster = playerSettings(doc)?.optString("poster")?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
        val plot = doc.selectFirst(".entry-content p")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }
        val year = YEAR_RE.find(title)?.value?.toIntOrNull()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = runCatching { app.get(data).document }.getOrNull() ?: return false
        val emitted = LinkedHashSet<String>()
        var any = false

        // 1) Primary: the inline video-js player settings JSON.
        doc.select("video-js[data-settings]").forEach { vj ->
            val settings = runCatching { JSONObject(vj.attr("data-settings")) }.getOrNull()
                ?: return@forEach
            val sources = settings.optJSONArray("sources") ?: JSONArray()
            for (i in 0 until sources.length()) {
                val s = sources.optJSONObject(i) ?: continue
                val src = s.optString("src").trim()
                if (src.isBlank() || !emitted.add(src)) continue
                if (emitMedia(src, callback)) any = true
            }
        }

        // 2) Plain <video><source src=…> fallback.
        doc.select("video source[src], video[src]").forEach { el ->
            val src = el.absUrl("src").ifBlank { el.attr("src") }.trim()
            if (src.isBlank() || !emitted.add(src)) return@forEach
            if (emitMedia(src, callback)) any = true
        }

        // 3) Regex last resort over the raw HTML (players that lazy-init).
        if (!any) {
            MEDIA_URL_RE.findAll(doc.outerHtml()).forEach { m ->
                val src = m.value
                if (src.contains("/wp-content/uploads/")) return@forEach // posters/thumbs
                if (!emitted.add(src)) return@forEach
                if (emitMedia(src, callback)) any = true
            }
        }
        return any
    }

    private fun playerSettings(doc: org.jsoup.nodes.Document): JSONObject? =
        doc.select("video-js[data-settings]").firstOrNull()?.let { vj ->
            runCatching { JSONObject(vj.attr("data-settings")) }.getOrNull()
        }

    private suspend fun emitMedia(src: String, callback: (ExtractorLink) -> Unit): Boolean {
        val isHls = src.contains(".m3u8", ignoreCase = true)
        return try {
            callback(
                newExtractorLink(
                    name,
                    name,
                    src,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(src)
                }
            )
            true
        } catch (t: Throwable) {
            Log.d("Mediaserver", "emit: ${t.message}")
            false
        }
    }
}
