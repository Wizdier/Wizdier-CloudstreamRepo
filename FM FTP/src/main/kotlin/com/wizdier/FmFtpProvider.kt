package com.wizdier

import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.api.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * FM FTP (https://fmftp.net) — React SPA backed by its public "Cinefy" REST
 * API at /api/, plus nginx autoindex file listings for the actual media
 * files. Reverse-engineered live 2026-07-25:
 *
 *   • GET /api/search?search={q}      → BARE JSON array, movies AND shows
 *        mixed; item.Library.type == "TV_SHOW" marks series, movies carry
 *        "file_path". (NOTE: the movies/tv-shows list endpoints IGNORE a
 *        ?search= param — only /api/search searches.)
 *   • GET /api/movies?page=N&limit=M  → {total, pages, data:[…]} paged list
 *   • GET /api/movies/{id}            → detail; "url" = public file path
 *        (contains RAW SPACES → percent-encode before use)
 *   • GET /api/tv-shows/{id}?fields=episodes → detail + "episodes" array
 *        (season_number, episode_number, name, still_path). This is the
 *        ONLY working episodes endpoint — /seasons/ and /episodes/ sub-
 *        endpoints are server bugs (500 "Unknown column 'NaN'") or 404.
 *   • Show detail "url" = public DIRECTORY path → nginx autoindex HTML
 *        lists "Season N <quality>/" folders holding files named
 *        "Title (Year) - SxxEyy - Name.mkv".
 *
 * Verified: sample movie + episode files both answer HTTP 206 (seekable,
 * direct playback) with no auth.
 */
class FmFtpProvider : MainAPI() {
    override var mainUrl = "https://fmftp.net"
    private val api = "$mainUrl/api"
    override var name = "FM FTP"
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
        private const val TMDB_IMG = "https://image.tmdb.org/t/p"
        private val VIDEO_EXT = listOf(".mp4", ".mkv", ".avi", ".m4v", ".mov", ".webm", ".ts")
        private val SUB_EXT = listOf(".srt", ".vtt", ".ass", ".ssa")
        // (v2) Display-title cleaner: quality/rip/dub-language tails are
        // stripped so cards read "Avatar", not "Avatar 2009 1080p BluRay
        // Hindi Dubbed". Cut only happens when ≥3 chars of head remain, so
        // legit titles beginning with a language word survive untouched
        // ("Hindi Medium" is a real film).
        private val JUNK_TAIL_RE = Regex(
            """(?i)\b(480p|576p|720p|1080p|2160p|4k|uhd|hdrip|webrip|web\s?-?\s?dl|bluray|""" +
                """bdrip|brrip|hdtc|hdts|cam|camrip|dvdrip|dvdscr|x264|x265|hevc|h\s?\.?\s?264|""" +
                """h\s?\.?\s?265|av1|aac|ac3|eac3|dts|mp3|esub|msub|subs?|10bit|8bit|dub(bed)?|""" +
                """dual\s?-?\s?audio|multi\s?-?\s?audio|hindi|english|bengali|bangla|tamil|""" +
                """telugu|korean|japanese|uncut|extended|repack|proper|imax)\b[\s\S]*$"""
        )
        private val YEAR_RE = Regex("""(?<!\d)(19\d{2}|20\d{2})(?!\d)""")
        private val SEP_RE = Regex("""[()\[\]{}.,:_\-!'·]""")
        private val WS_RE = Regex("""\s+""")
        // (v3) Episode-coordinate tokens from the synthetic epdata URL —
        // hoisted out of loadLinks so each playback doesn't recompile them.
        private val EPDATA_S_RE = Regex("""(?:^|&)s=(\d+)""")
        private val EPDATA_E_RE = Regex("""(?:^|&)e=(\d+)""")
    }

    /** Site title → bare display title (junk tail + year removed, separators
     *  normalised). Returns null when nothing usable remains. */
    private fun cleanDisplayTitle(raw: String): String? {
        var t = raw.trim()
        if (t.isBlank()) return null
        JUNK_TAIL_RE.find(t)?.let { m ->
            if (m.range.first >= 3) t = t.substring(0, m.range.first)
        }
        YEAR_RE.find(t)?.let { m -> t = t.replace(m.value, " ") }
        t = t.replace(SEP_RE, " ").replace(WS_RE, " ").trim()
        return t.ifBlank { null }
    }

    override val mainPage = mainPageOf(
        "$api/movies?limit=24&page=" to "Latest Movies",
        "$api/tv-shows?limit=24&page=" to "Latest TV Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // (v3) was a bare app.get().text — any network wobble here became an
        // error banner on the user's HOME SCREEN. Now: retried + graceful.
        val text = getText(request.data + page)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val data = json.optJSONArray("data") ?: JSONArray()
        val items = (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.toSearchResponse()
        }
        val pages = json.optInt("pages", 0)
        return newHomePageResponse(request.name, items, hasNext = page < pages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val text = getText("$api/search?search=$q") ?: return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.toSearchResponse()
        }
    }

    private fun JSONObject.toSearchResponse(): SearchResponse? {
        val id = optInt("id", 0)
        if (id == 0) return null
        val lib = optJSONObject("Library")
        val isShow = (lib?.optString("type") == "TV_SHOW") ||
            (!has("file_path") && has("path"))
        val rawTitle = optString("title").trim()
        // (v2) bare display title on cards too — no year/quality/dub junk.
        val title = cleanDisplayTitle(rawTitle) ?: rawTitle.ifBlank { null } ?: return null
        val yr = optInt("year", 0).takeIf { it > 0 }
        val poster = optString("poster_path").trim()
            .takeIf { it.isNotBlank() }?.let { "$TMDB_IMG/w500$it" }
        val url = if (isShow) "$api/tv-shows/$id" else "$api/movies/$id"
        return if (isShow) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = yr
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = yr
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return if (url.contains("/tv-shows/")) loadShow(url) else loadMovie(url)
    }

    private suspend fun loadMovie(url: String): LoadResponse? {
        val d = fetchJson(url) ?: return null
        val rawTitle = d.optString("title").trim().ifBlank { null } ?: return null
        val title = cleanDisplayTitle(rawTitle) ?: rawTitle
        // (v2) TMDB enrichment: FM FTP details already carry the TMDB id +
        // IMDb id, so enrichment starts from an exact ID lookup — no title
        // searching, no mismatch risk. Everything TMDB-side is a fallback
        // for the site's own fields, never a replacement of catalogue facts.
        val tmdbId = d.optInt("tmdb_id", 0).takeIf { it > 0 }
        val imdbDirect = d.optString("imdb_id").trim().ifBlank { null }
        val tmdb = tmdbId?.let { Tmdb.byId(false, it) }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = tmdbImg(d.optString("poster_path"), "w500") ?: tmdb?.poster
            this.backgroundPosterUrl = tmdbImg(d.optString("backdrop_path"), "original")
                ?: tmdb?.backdrop
            this.plot = d.optString("overview").trim().ifBlank { null } ?: tmdb?.plot
            this.year = d.optInt("year", 0).takeIf { it > 0 } ?: tmdb?.year
            this.tags = tmdb?.genres?.takeIf { it.isNotEmpty() }
                ?: d.optString("genre").split(",").map { it.trim() }.filter { it.isNotBlank() }
            val rating = d.optDouble("online_rating", 0.0).takeIf { it > 0.0 }
                ?: tmdb?.rating
            this.score = rating?.let { Score.from10(it) }
            this.duration = tmdb?.runtime
            tmdb?.actors?.takeIf { it.isNotEmpty() }?.let { this.actors = it }
            try { tmdb?.logo?.let { this.logoUrl = it } } catch (_: Throwable) {}
            try { (imdbDirect ?: tmdb?.imdbId)?.let { addImdbId(it) } } catch (_: Throwable) {}
            val yt = d.optString("trailer").trim().ifBlank { null }
                ?.let { "https://www.youtube.com/watch?v=$it" } ?: tmdb?.trailer
            try { yt?.let { addTrailer(it) } } catch (_: Throwable) {}
        }
    }

    private suspend fun loadShow(url: String): LoadResponse? {
        // ?fields=episodes is the SPA's own call — the only working way to
        // enumerate episodes (the /seasons and /episodes REST sub-paths are
        // broken server-side).
        val d = fetchJson("$url?fields=episodes") ?: return null
        val rawTitle = d.optString("title").trim().ifBlank { null } ?: return null
        val title = cleanDisplayTitle(rawTitle) ?: rawTitle
        val tmdbId = d.optInt("tmdb_id", 0).takeIf { it > 0 }
        val imdbDirect = d.optString("imdb_id").trim().ifBlank { null }
        val tmdb = tmdbId?.let { Tmdb.byId(true, it) }

        // Collect visible episodes first so we know which TMDB seasons to pull.
        data class RawEp(val s: Int, val e: Int, val siteName: String?, val siteStill: String?)
        val rawEps = mutableListOf<RawEp>()
        val arr = d.optJSONArray("episodes") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val ep = arr.optJSONObject(i) ?: continue
            if (ep.optString("status") != "VISIBLE") continue
            val s = ep.optInt("season_number", -1)
            val e = ep.optInt("episode_number", -1)
            if (s < 0 || e < 0) continue
            rawEps += RawEp(
                s, e,
                ep.optString("name").trim().ifBlank { null },
                ep.optString("still_path").trim().ifBlank { null }
            )
        }

        // (v2) Fetch every referenced TMDB season in parallel (cached), so
        // each episode gains synopsis + canonical still + TMDB title.
        val seasonMaps: Map<Int, Map<Int, Tmdb.EpMeta>> = if (tmdb != null) {
            val wanted = rawEps.map { it.s }.distinct()
            coroutineScope {
                wanted.map { s -> async { s to Tmdb.season(tmdb.id, s) } }
                    .map { it.await() }
                    .toMap()
            }
        } else emptyMap()

        val episodes = mutableListOf<Episode>()
        rawEps.forEach { raw ->
            val meta = seasonMaps[raw.s]?.get(raw.e)
            val epUrl = "$url/epdata?s=${raw.s}&e=${raw.e}"
            episodes += newEpisode(epUrl) {
                this.name = meta?.name ?: raw.siteName ?: "Episode ${raw.e}"
                this.season = raw.s
                this.episode = raw.e
                this.description = meta?.overview
                this.posterUrl = tmdbImg(raw.siteStill, "w500") ?: meta?.still
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = tmdbImg(d.optString("poster_path"), "w500") ?: tmdb?.poster
            this.backgroundPosterUrl = tmdbImg(d.optString("backdrop_path"), "original")
                ?: tmdb?.backdrop
            this.plot = d.optString("overview").trim().ifBlank { null } ?: tmdb?.plot
            this.year = d.optInt("year", 0).takeIf { it > 0 } ?: tmdb?.year
            this.tags = tmdb?.genres?.takeIf { it.isNotEmpty() }
                ?: d.optString("genre").split(",").map { it.trim() }.filter { it.isNotBlank() }
            val rating = d.optDouble("online_rating", 0.0).takeIf { it > 0.0 }
                ?: tmdb?.rating
            this.score = rating?.let { Score.from10(it) }
            tmdb?.actors?.takeIf { it.isNotEmpty() }?.let { this.actors = it }
            try { tmdb?.logo?.let { this.logoUrl = it } } catch (_: Throwable) {}
            try { (imdbDirect ?: tmdb?.imdbId)?.let { addImdbId(it) } } catch (_: Throwable) {}
            val yt = d.optString("trailer").trim().ifBlank { null }
                ?.let { "https://www.youtube.com/watch?v=$it" } ?: tmdb?.trailer
            try { yt?.let { addTrailer(it) } } catch (_: Throwable) {}
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("/epdata?")) {
            val base = data.substringBefore("/epdata?")
            val query = data.substringAfter("/epdata?")
            val s = EPDATA_S_RE.find(query)?.groupValues?.get(1)?.toIntOrNull()
                ?: return false
            val e = EPDATA_E_RE.find(query)?.groupValues?.get(1)?.toIntOrNull()
                ?: return false
            val d = fetchJson(base) ?: return false
            val dir = d.optString("url").trim()
            if (dir.isBlank()) return false
            return emitEpisodeFiles(dir, s, e, subtitleCallback, callback)
        }

        // Movie: the detail "url" IS the playable file path.
        val d = fetchJson(data) ?: return false
        val rel = d.optString("url").trim()
        if (rel.isBlank()) return false
        val abs = mainUrl + encodePath(rel)
        callback(
            newExtractorLink(name, name, abs, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = getQualityFromName(rel)
            }
        )
        return true
    }

    /**
     * Walk the show's autoindex directory to find the requested episode's
     * file(s). Layout: <show>/Season N <quality?>/…SxxEyy… .mkv — season
     * folders often carry the QUALITY tag (folded into the link quality).
     */
    private suspend fun emitEpisodeFiles(
        dir: String,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = mainUrl + encodePath(dir.trimEnd('/') + "/")
        val seasonRe = Regex("""(?i)season[\s._-]*0*""" + season + """(\D|$)""")
        val epRe = Regex("""(?i)S0*""" + season + """E0*""" + episode + """(\D|$)""")
        val eOnlyRe = Regex("""(?i)(\s|\.|_|-|^)E0*""" + episode + """(\D|$)""")

        val topDoc = fetchDoc(base) ?: return false
        val topLinks = indexLinks(topDoc)

        val seasonDirs = topLinks
            .filter { it.first.endsWith("/") }
            .filter { seasonRe.containsMatchIn(decode(it.first)) }

        var any = false
        var sawAnySxxEyy = false

        // 1) Matching season folders — emit only SxxEyy matches inside.
        seasonDirs.forEach { (href, _) ->
            val folderName = decode(href).trimEnd('/')
            val folderUrl = base + href
            val doc = fetchDoc(folderUrl) ?: return@forEach
            indexLinks(doc)
                .filter { !it.first.endsWith("/") }
                .forEach { (fileHref, _) ->
                    val decoded = decode(fileHref)
                    if (!epRe.containsMatchIn(decoded)) return@forEach
                    val abs = folderUrl + fileHref
                    if (SUB_EXT.any { decoded.endsWith(it, ignoreCase = true) }) {
                        runSafe { subtitleCallback(newSubtitleFile("[FM FTP] Subtitle", abs)) }
                        return@forEach
                    }
                    if (!VIDEO_EXT.any { decoded.endsWith(it, ignoreCase = true) }) return@forEach
                    sawAnySxxEyy = true
                    runSafe {
                        callback(
                            newExtractorLink(name, name, abs, ExtractorLinkType.VIDEO) {
                                this.referer = "$mainUrl/"
                                // Quality usually lives on the SEASON FOLDER
                                // ("Season 1 1080p"), not the filename.
                                this.quality = getQualityFromName("$folderName/$decoded")
                            }
                        )
                    }
                    any = true
                }
        }

        // 2) Loose fallback: if nothing SxxEyy-shaped matched (unusual
        // naming), scan every file we listed for a bare "E05" token —
        // including shows archived flat in the show root (no season dirs).
        if (!sawAnySxxEyy) {
            val flatCandidates = mutableListOf<Triple<String, String, String>>()
            topLinks.filter { !it.first.endsWith("/") }
                .forEach { flatCandidates += Triple(base, it.first, decode(it.first)) }
            topLinks.filter { it.first.endsWith("/") }
                .filter { seasonRe.containsMatchIn(decode(it.first)) }
                .forEach { (href, _) ->
                    val folderUrl = base + href
                    fetchDoc(folderUrl)?.let { doc ->
                        indexLinks(doc).filter { !it.first.endsWith("/") }
                            .forEach { flatCandidates += Triple(folderUrl, it.first, decode(it.first)) }
                    }
                }
            flatCandidates.forEach { (folderUrl, href, decoded) ->
                if (!eOnlyRe.containsMatchIn(decoded)) return@forEach
                if (!VIDEO_EXT.any { decoded.endsWith(it, ignoreCase = true) }) return@forEach
                runSafe {
                    callback(
                        newExtractorLink(name, name, folderUrl + href, ExtractorLinkType.VIDEO) {
                            this.referer = "$mainUrl/"
                            this.quality = getQualityFromName(decoded)
                        }
                    )
                }
                any = true
            }
        }
        return any
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** href → text pairs from an nginx autoindex page; "../" skipped. */
    private fun indexLinks(doc: org.jsoup.nodes.Document): List<Pair<String, String>> =
        doc.select("a[href]")
            .mapNotNull { a ->
                val href = a.attr("href").trim()
                if (href.isBlank() || href.startsWith("../") || href.startsWith("?") ||
                    href.startsWith("/")) null
                else href to a.text().trim()
            }
            .distinctBy { it.first }

    // (v3) One bounded retry on network failure. The SPA host sleeps its
    // disks, so a first hit routinely stalls into a dropped connection; a
    // single 500 ms-spaced second attempt turns most of those hard blanks
    // into slow-but-working loads. Non-2xx codes are NOT retried (a 404 is
    // final) — only transport-level failures are.
    private suspend fun getText(url: String): String? {
        repeat(2) { attempt ->
            val resp = runCatching { app.get(url) }.getOrNull()
            if (resp != null && resp.code in 200..299) return resp.text
            if (attempt == 0) kotlinx.coroutines.delay(500)
        }
        return null
    }

    private suspend fun fetchJson(url: String): JSONObject? {
        val text = getText(url)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private suspend fun fetchDoc(url: String): org.jsoup.nodes.Document? {
        val text = getText(url) ?: return null
        // A junk-200 (SPA shell returned for a missing dir) has no anchors.
        return runCatching { Jsoup.parse(text, url) }.getOrNull()
    }

    private fun encodePath(p: String): String = buildString(p.length + 16) {
        for (c in p) {
            when (c) {
                ' ' -> append("%20")
                '#' -> append("%23")
                '?' -> append("%3F")
                '%' -> append("%25")
                else -> append(c)
            }
        }
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun tmdbImg(path: String?, size: String): String? =
        path?.trim()?.takeIf { it.isNotBlank() }?.let { "$TMDB_IMG/$size$it" }

    private inline fun runSafe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.d("FmFtp", "emit: ${t.message}") }
    }

    // ── (v2) TMDB enrichment ──────────────────────────────────────────────
    /**
     * Thin TMDB client for full metadata pages (cast with photos, title
     * logo, runtime, genre names, trailer, IMDb id, per-episode synopses
     * + stills). Same public API key and field set the Circle FTP
     * extension uses. Everything is a SOFT fallback: any TMDB failure
     * silently leaves the site's own metadata in place. Cached per process
     * so paging through content never re-hits the API.
     */
    private object Tmdb {
        private const val API = "https://api.themoviedb.org/3"
        private const val KEY = "98ae14df2b8d8f8f8136499daf79f0e0"
        private const val IMG = "https://image.tmdb.org/t/p"
        private val metaCache = ConcurrentHashMap<String, Meta>()
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

        /** Exact lookup — FM FTP details tell us the TMDB id directly. */
        suspend fun byId(isTv: Boolean, tmdbId: Int): Meta? {
            val key = (if (isTv) "tv:" else "movie:") + tmdbId
            metaCache[key]?.let { return it }
            // Only successful fetches are cached (ConcurrentHashMap rejects
            // nulls) — a transient TMDB outage therefore never poisons the
            // session, it just retries on the next load.
            val meta = fetchMeta(if (isTv) "tv" else "movie", tmdbId)
            if (meta != null) metaCache[key] = meta
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
                val root = JSONObject(text)
                val arr = root.optJSONArray("episodes") ?: JSONArray()
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

                // Trailer: official YouTube trailer first, any trailer second.
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

                // Title logo: best English non-SVG → any, w500.
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
                val logoUrl = logo?.let { "$IMG/w500$it" }

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
                    logo = logoUrl,
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
