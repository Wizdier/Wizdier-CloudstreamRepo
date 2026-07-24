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
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.api.Log
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

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
    }

    override val mainPage = mainPageOf(
        "$api/movies?limit=24&page=" to "Latest Movies",
        "$api/tv-shows?limit=24&page=" to "Latest TV Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val text = app.get(request.data + page).text
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
        val text = app.get("$api/search?search=$q").text
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
        val title = optString("title").trim().ifBlank { null } ?: return null
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
        val title = d.optString("title").trim().ifBlank { null } ?: return null
        val poster = tmdbImg(d.optString("poster_path"), "w500")
        val backdrop = tmdbImg(d.optString("backdrop_path"), "original")
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = d.optString("overview").trim().ifBlank { null }
            this.year = d.optInt("year", 0).takeIf { it > 0 }
            this.tags = d.optString("genre").split(",").map { it.trim() }.filter { it.isNotBlank() }
            val rating = d.optDouble("online_rating", 0.0).takeIf { it > 0.0 }
            this.score = rating?.let { Score.from10(it) }
            val yt = d.optString("trailer").trim().ifBlank { null }
            try { yt?.let { addTrailer("https://www.youtube.com/watch?v=$it") } } catch (_: Throwable) {}
        }
    }

    private suspend fun loadShow(url: String): LoadResponse? {
        // ?fields=episodes is the SPA's own call — the only working way to
        // enumerate episodes (the /seasons and /episodes REST sub-paths are
        // broken server-side).
        val d = fetchJson("$url?fields=episodes") ?: return null
        val title = d.optString("title").trim().ifBlank { null } ?: return null
        val poster = tmdbImg(d.optString("poster_path"), "w500")
        val backdrop = tmdbImg(d.optString("backdrop_path"), "original")

        val episodes = mutableListOf<Episode>()
        val arr = d.optJSONArray("episodes") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val ep = arr.optJSONObject(i) ?: continue
            if (ep.optString("status") != "VISIBLE") continue
            val s = ep.optInt("season_number", -1)
            val e = ep.optInt("episode_number", -1)
            if (s < 0 || e < 0) continue
            val epUrl = "$url/epdata?s=$s&e=$e"
            episodes += newEpisode(epUrl) {
                this.name = ep.optString("name").trim().ifBlank { "Episode $e" }
                this.season = s
                this.episode = e
                this.posterUrl = tmdbImg(ep.optString("still_path"), "w500")
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = d.optString("overview").trim().ifBlank { null }
            this.year = d.optInt("year", 0).takeIf { it > 0 }
            this.tags = d.optString("genre").split(",").map { it.trim() }.filter { it.isNotBlank() }
            val rating = d.optDouble("online_rating", 0.0).takeIf { it > 0.0 }
            this.score = rating?.let { Score.from10(it) }
            val yt = d.optString("trailer").trim().ifBlank { null }
            try { yt?.let { addTrailer("https://www.youtube.com/watch?v=$it") } } catch (_: Throwable) {}
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
            val s = Regex("""(?:^|&)s=(\d+)""").find(query)?.groupValues?.get(1)?.toIntOrNull()
                ?: return false
            val e = Regex("""(?:^|&)e=(\d+)""").find(query)?.groupValues?.get(1)?.toIntOrNull()
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

    private suspend fun fetchJson(url: String): JSONObject? {
        val resp = runCatching { app.get(url) }.getOrNull() ?: return null
        if (resp.code !in 200..299 || resp.text.isBlank()) return null
        return runCatching { JSONObject(resp.text) }.getOrNull()
    }

    private suspend fun fetchDoc(url: String): org.jsoup.nodes.Document? {
        val resp = runCatching { app.get(url) }.getOrNull() ?: return null
        if (resp.code !in 200..299) return null
        // A junk-200 (SPA shell returned for a missing dir) has no anchors.
        return runCatching { Jsoup.parse(resp.text, url) }.getOrNull()
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
}
