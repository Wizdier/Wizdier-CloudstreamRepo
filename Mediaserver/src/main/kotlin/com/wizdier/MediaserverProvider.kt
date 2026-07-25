package com.wizdier

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import java.util.concurrent.ConcurrentHashMap

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
        // (v2) Display/search title cleaning: quality·rip·dub-language junk
        // tails + year are stripped so cards read the bare media title.
        // The cut needs ≥3 chars of head so legit titles that START with a
        // language word survive ("Hindi Medium").
        private val JUNK_TAIL_RE = Regex(
            """(?i)\b(480p|576p|720p|1080p|2160p|4k|uhd|hdrip|webrip|web\s?-?\s?dl|bluray|""" +
                """bdrip|brrip|hdtc|hdts|camrip|dvdrip|dvdscr|x264|x265|hevc|h\s?\.?\s?264|""" +
                """h\s?\.?\s?265|av1|aac|ac3|eac3|dts|esub|msub|10bit|dub(bed)?|dual\s?-?\s?audio|""" +
                """multi\s?-?\s?audio|hindi|english|bengali|bangla|tamil|telugu|korean|""" +
                """japanese|uncut|extended)\b[\s\S]*$"""
        )
        private val SEP_RE = Regex("""[()\[\]{}.,:_\-!'·]""")
        private val WS_RE = Regex("""\s+""")
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
            val rawTitle = a.text().trim()
            if (href.isBlank() || rawTitle.isBlank() || out.containsKey(href)) return@forEach
            // (v2) bare title on cards — quality/year/dub junk stripped.
            val title = cleanDisplayTitle(rawTitle) ?: rawTitle
            val card = a.closest(".post-meta") ?: a.closest("article") ?: a.parent()
            val poster = posterFrom(card)
            out[href] = newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
        doc.select("a.post-permalink[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val rawTitle = a.attr("title").trim()
            if (href.isBlank() || rawTitle.isBlank() || out.containsKey(href)) return@forEach
            val title = cleanDisplayTitle(rawTitle) ?: rawTitle
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
        // (v2) card detail page also shows the bare title; the RAW title
        // keeps feeding TMDB cleaning (it needs the SxxEyy/year context).
        val displayTitle = cleanDisplayTitle(title) ?: title
        val sitePoster = playerSettings(doc)?.optString("poster")?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
        val sitePlot = doc.selectFirst(".entry-content p")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }
        val siteYear = YEAR_RE.find(title)?.value?.toIntOrNull()

        // (v2) TMDB enrichment: the site carries no IDs, so we resolve by
        // cleaned title (SxxEyy posts search TMDB as TV, the rest as movies).
        // Soft-fallback everywhere: a TMDB outage never costs the page.
        val cleaned = cleanForTmdb(title)
        val tmdb = cleaned?.let { Tmdb.bySearch(it.isTv, it.title, it.year) }
        val epMeta = if (tmdb != null && cleaned?.s != null && cleaned.e != null) {
            Tmdb.season(tmdb.id, cleaned.s)[cleaned.e]
        } else null

        return newMovieLoadResponse(displayTitle, url, TvType.Movie, url) {
            this.posterUrl = tmdb?.poster ?: sitePoster
            this.backgroundPosterUrl = tmdb?.backdrop
            this.plot = epMeta?.overview ?: tmdb?.plot ?: sitePlot
            this.year = tmdb?.year ?: siteYear
            tmdb?.genres?.takeIf { it.isNotEmpty() }?.let { this.tags = it }
            this.score = tmdb?.rating?.let { Score.from10(it) }
            this.duration = epMeta?.runtime ?: tmdb?.runtime
            tmdb?.actors?.takeIf { it.isNotEmpty() }?.let { this.actors = it }
            try { tmdb?.logo?.let { this.logoUrl = it } } catch (_: Throwable) {}
            try { tmdb?.imdbId?.let { addImdbId(it) } } catch (_: Throwable) {}
            try { tmdb?.trailer?.let { addTrailer(it) } } catch (_: Throwable) {}
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

    // ── (v2) TMDB enrichment ──────────────────────────────────────────────

    private data class Cleaned(
        val title: String,
        val isTv: Boolean,
        val s: Int?,
        val e: Int?,
        val year: Int?
    )

    /** Strip the junk tail + year out of a raw post title; returns
     *  (bareTitle, year?). SxxEyy tokens survive (episode identity). */
    private fun bareTitleAndYear(t0: String): Pair<String, Int?> {
        var t = t0.trim()
        JUNK_TAIL_RE.find(t)?.let { m ->
            if (m.range.first >= 3) t = t.substring(0, m.range.first)
        }
        val year = YEAR_RE.find(t)?.value?.toIntOrNull()
        if (year != null) t = t.replace(year.toString(), " ")
        t = t.replace(SEP_RE, " ").replace(WS_RE, " ").trim()
        return t to year
    }

    /** Bare display title for cards/load pages (junk + year removed). */
    private fun cleanDisplayTitle(raw: String): String? =
        bareTitleAndYear(raw).first.ifBlank { null }

    /** "One Piece S01E08 Romance Dawn" → ("One Piece", tv, 1, 8);
     *  "SuperGirl 2026" → ("SuperGirl", movie, year 2026). */
    private fun cleanForTmdb(raw: String): Cleaned? {
        var t = raw.trim()
        var s: Int? = null
        var e: Int? = null
        var isTv = false
        val sxe = Regex("""(?i)(?:^|[^\d])S(\d{1,2})E(\d{1,3})(?!\d)""").find(t)
        if (sxe != null) {
            isTv = true
            s = sxe.groupValues[1].toIntOrNull()
            e = sxe.groupValues[2].toIntOrNull()
            t = t.substring(0, sxe.range.first)
        }
        val (bare, year) = bareTitleAndYear(t)
        if (bare.isBlank()) return null
        return Cleaned(bare, isTv, s, e, year)
    }

    /**
     * Thin TMDB client (full metadata: cast with photos, title logo, runtime,
     * genres, trailer, IMDb id; episode synopses/stills/runtime for SxxEyy
     * posts). Same public key/field set the Circle FTP extension uses.
     * Mediaserver has no IDs on the site, so lookup is search-first with a
     * ±1-year pick — miserable lookups (id 0) are cached too, so a bad/gone
     * match costs at most ONE network call per process. All enrichment is a
     * SOFT fallback over the site's own title/poster/plot.
     */
    private object Tmdb {
        private const val API = "https://api.themoviedb.org/3"
        private const val KEY = "98ae14df2b8d8f8f8136499daf79f0e0"
        private const val IMG = "https://image.tmdb.org/t/p"
        private val metaCache = ConcurrentHashMap<Int, Meta>()
        private val searchCache = ConcurrentHashMap<String, Int>()
        private val seasonCache = ConcurrentHashMap<Int, Map<Int, EpMeta>>()

        data class EpMeta(
            val name: String?,
            val overview: String?,
            val still: String?,
            val runtime: Int?
        )

        data class Meta(
            val id: Int,
            val poster: String?,
            val backdrop: String?,
            val plot: String?,
            val year: Int?,
            val rating: Double?,
            val runtime: Int?,
            val genres: List<String>,
            val trailer: String?,
            val logo: String?,
            val imdbId: String?,
            val actors: List<ActorData>
        )

        /** Title search → best-id (±1 on year when known) → exact details. */
        suspend fun bySearch(isTv: Boolean, title: String, year: Int?): Meta? {
            val type = if (isTv) "tv" else "movie"
            val cacheKey = type + ":" + title.lowercase() + ":y" + (year ?: 0)
            searchCache[cacheKey]?.let { cached ->
                return if (cached > 0) byId(isTv, cached) else null
            }
            val q = URLEncoder.encode(title, "UTF-8")
            val text = get("$API/search/$type?api_key=$KEY&query=$q&include_adult=false")
            var id = 0
            if (text != null) {
                runCatching {
                    val res = JSONObject(text).optJSONArray("results") ?: JSONArray()
                    var firstId = 0
                    var firstJaId = 0
                    for (i in 0 until minOf(res.length(), 6)) {
                        val r = res.optJSONObject(i) ?: continue
                        if (firstId == 0) firstId = r.optInt("id", 0)
                        if (firstJaId == 0 &&
                            r.optString("original_language") == "ja") {
                            firstJaId = r.optInt("id", 0)
                        }
                        if (year != null) {
                            val d = r.optString("release_date",
                                r.optString("first_air_date", ""))
                            val yr = d.split("-").firstOrNull()?.toIntOrNull()
                            if (yr != null && kotlin.math.abs(yr - year) <= 1) {
                                id = r.optInt("id", 0)
                                break
                            }
                        }
                    }
                    if (id == 0) {
                        // No year to disambiguate on. BDIX SxxEyy posts are
                        // anime-named ("One Piece S01E08") and TMDB ranks
                        // live-action remakes ABOVE the anime for identical
                        // titles — without this, anime posts would wear the
                        // wrong series' poster/plot. Only applies to yearless
                        // TV lookups; everything else takes TMDB's top hit.
                        id = if (isTv && year == null && firstJaId != 0) firstJaId else firstId
                    }
                }
            }
            searchCache[cacheKey] = id
            return if (id > 0) byId(isTv, id) else null
        }

        private suspend fun byId(isTv: Boolean, tmdbId: Int): Meta? {
            metaCache[tmdbId]?.let { return it }
            // Only successful fetches cached (ConcurrentHashMap rejects
            // nulls) — transient TMDB outages retry instead of poisoning.
            val meta = fetchMeta(if (isTv) "tv" else "movie", tmdbId)
            if (meta != null) metaCache[tmdbId] = meta
            return meta
        }

        /** Season-level episode metadata, cached one map per season. */
        suspend fun season(tmdbId: Int, seasonNumber: Int): Map<Int, EpMeta> {
            val key = tmdbId * 1000 + seasonNumber
            seasonCache[key]?.let { return it }
            val text = get("$API/tv/$tmdbId/season/$seasonNumber?api_key=$KEY") ?: run {
                seasonCache[key] = emptyMap()
                return emptyMap()
            }
            val out = mutableMapOf<Int, EpMeta>()
            runCatching {
                val arr = JSONObject(text).optJSONArray("episodes") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val ep = arr.optJSONObject(i) ?: continue
                    val num = ep.optInt("episode_number", -1)
                    if (num < 0) continue
                    out[num] = EpMeta(
                        name = str(ep, "name"),
                        overview = str(ep, "overview"),
                        still = str(ep, "still_path")?.let { "$IMG/w500$it" },
                        runtime = ep.optInt("runtime", 0).takeIf { it > 0 }
                    )
                }
            }
            seasonCache[key] = out
            return out
        }

        private suspend fun fetchMeta(type: String, id: Int): Meta? {
            val text = get(
                "$API/$type/$id?api_key=$KEY" +
                    "&append_to_response=credits,external_ids,videos,images"
            ) ?: return null
            return runCatching {
                val root = JSONObject(text)

                var trailer: String? = null
                var anyTrailer: String? = null
                val vids = root.optJSONObject("videos")?.optJSONArray("results")
                if (vids != null) {
                    for (i in 0 until vids.length()) {
                        val v = vids.optJSONObject(i) ?: continue
                        if (v.optString("type") != "Trailer" ||
                            v.optString("site") != "YouTube") continue
                        val u = "https://www.youtube.com/watch?v=" + v.optString("key")
                        if (anyTrailer == null) anyTrailer = u
                        if (v.optBoolean("official", false)) { trailer = u; break }
                    }
                }
                if (trailer == null) trailer = anyTrailer

                var logo: String? = null
                val logos = root.optJSONObject("images")?.optJSONArray("logos")
                if (logos != null) {
                    var enSvg: String? = null
                    var anyPng: String? = null
                    var anySvg: String? = null
                    for (i in 0 until logos.length()) {
                        val l = logos.optJSONObject(i) ?: continue
                        val path = str(l, "file_path") ?: continue
                        val isSvg = path.endsWith(".svg", true)
                        val lang = l.optString("iso_639_1").trim().lowercase()
                        if (lang == "en" && !isSvg) { logo = path; break }
                        if (lang == "en" && isSvg && enSvg == null) enSvg = path
                        if (!isSvg && anyPng == null) anyPng = path
                        if (isSvg && anySvg == null) anySvg = path
                    }
                    if (logo == null) logo = enSvg ?: anyPng ?: anySvg
                }

                val genres = mutableListOf<String>()
                root.optJSONArray("genres")?.let { ga ->
                    for (i in 0 until ga.length()) {
                        str(ga.optJSONObject(i), "name")?.let { genres += it }
                    }
                }

                val castOut = mutableListOf<ActorData>()
                root.optJSONObject("credits")?.optJSONArray("cast")?.let { ca ->
                    val limit = minOf(ca.length(), 20)
                    for (i in 0 until limit) {
                        val c = ca.optJSONObject(i) ?: continue
                        val nm = str(c, "name") ?: continue
                        castOut += ActorData(
                            actor = Actor(nm, str(c, "profile_path")?.let { "$IMG/w185$it" }),
                            roleString = str(c, "character")
                        )
                    }
                }

                val release = root.optString("release_date",
                    root.optString("first_air_date", ""))
                val runtime = root.optInt("runtime", 0).takeIf { it > 0 }
                    ?: root.optJSONArray("episode_run_time")?.let { ra ->
                        if (ra.length() > 0) ra.optInt(0, 0).takeIf { it > 0 } else null
                    }

                val ext = root.optJSONObject("external_ids")
                Meta(
                    id = id,
                    poster = str(root, "poster_path")?.let { "$IMG/w500$it" },
                    backdrop = str(root, "backdrop_path")?.let { "$IMG/original$it" },
                    plot = str(root, "overview"),
                    year = release.split("-").firstOrNull()?.toIntOrNull(),
                    rating = root.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
                    runtime = runtime,
                    genres = genres,
                    trailer = trailer,
                    logo = logo?.let { "$IMG/w500$it" },
                    imdbId = ext?.let { str(it, "imdb_id") } ?: str(root, "imdb_id"),
                    actors = castOut
                )
            }.getOrNull()
        }

        private fun str(o: JSONObject?, k: String): String? {
            if (o == null || !o.has(k) || o.isNull(k)) return null
            return o.optString(k, "").trim().takeIf { it.isNotBlank() && it != "null" }
        }

        private suspend fun get(url: String): String? {
            val resp = runCatching { app.get(url, timeout = 8_000) }.getOrNull()
                ?: return null
            if (resp.code !in 200..299 || resp.text.isBlank()) return null
            return resp.text
        }
    }
}
