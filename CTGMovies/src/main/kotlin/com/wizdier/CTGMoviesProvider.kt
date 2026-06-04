package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder

/**
 * CTGMoviesProvider
 *
 * LOGIN SUPPORT
 * ─────────────
 * Set  storedCredentials  in the provider's settings page as:
 *   email:password
 * e.g.  john@example.com:mypassword
 *
 * The provider will POST those credentials to /api/auth/login the first time a
 * watch-page is requested, persist the session cookie in CloudStream's shared
 * cookie jar, and from then on hit the site's own /watch/ player directly —
 * giving you the site's real servers instead of the public TMDB embed fallbacks.
 *
 * The TMDB embed fallbacks (autoembed / vidsrc / 2embed) remain active as
 * additional sources regardless of whether you are logged in.
 *
 * BUILD-GRADLE NOTE
 * ─────────────────
 * Add this to android.defaultConfig in CTGMovies/build.gradle if not present:
 *   buildConfigField("String","TMDB_API","\"${System.getenv("TMDB_API") ?: ""}\"")
 */
class CTGMoviesProvider : MainAPI() {

    override var mainUrl            = "https://ctgmovies.com"
    override var name               = "CTG Movies"
    override val hasMainPage        = true
    override var lang               = "en"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Movie, TvType.TvSeries)

    // Set via provider settings as  email:password
    // CloudStream injects this from ProvidersInfoJson.credentials
    override var storedCredentials: String? = null

    // Whether we have successfully obtained a session cookie this run
    @Volatile private var sessionActive = false

    private val tmdbApiKey: String by lazy {
        try { com.wizdier.BuildConfig.TMDB_API } catch (_: Exception) { "" }
    }
    private val tmdbBase = "https://api.themoviedb.org/3"

    // ── Pagination ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "/movies?page=" to "Movies",
        "/tv?page="     to "TV Shows",
    )

    // ── Credential helpers ─────────────────────────────────────────────────────

    /** Parse storedCredentials string  "email:password" → Pair or null. */
    private fun parseCreds(): Pair<String, String>? {
        val raw = storedCredentials?.trim()?.takeIf { it.contains(":") } ?: return null
        val idx  = raw.indexOf(":")
        val email = raw.substring(0, idx).trim()
        val pass  = raw.substring(idx + 1).trim()
        if (email.isBlank() || pass.isBlank()) return null
        return email to pass
    }

    /**
     * POST email+password to the site's login endpoint.
     * The site is a Next.js app; its auth route is /api/auth/login.
     * On success the server sets a session cookie that app's cookie jar stores.
     * Returns true if the response body indicates success.
     */
    private suspend fun performLogin(email: String, password: String): Boolean {
        return runCatching {
            val body = JSONObject().apply {
                put("email",    email)
                put("password", password)
            }.toString()

            val resp = app.post(
                "$mainUrl/api/auth/login",
                requestBody = body.toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
                headers = mapOf(
                    "Content-Type"  to "application/json",
                    "Accept"        to "application/json",
                    "Referer"       to "$mainUrl/login",
                    "Origin"        to mainUrl,
                ),
            )
            // Accept any 2xx or a redirect back to the home page as success
            resp.isSuccessful || resp.code == 302
        }.getOrElse { false }
    }

    /**
     * Ensure we have an active session.  Idempotent — only logs in once per
     * provider lifecycle unless the session has been invalidated.
     */
    private suspend fun ensureSession() {
        if (sessionActive) return
        val (email, pass) = parseCreds() ?: return
        sessionActive = performLogin(email, pass)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun cleanUrl(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (s.startsWith("/_next/image?url=")) {
            val encoded = s.substringAfter("url=").substringBefore("&")
            return URLDecoder.decode(encoded, "UTF-8")
        }
        return if (s.startsWith("http")) s else "$mainUrl$s"
    }

    private fun parseQuality(text: String?): SearchQuality? {
        val t = text?.lowercase() ?: return null
        return when {
            t.contains("4k")    -> SearchQuality.HD
            t.contains("1080")  -> SearchQuality.HD
            t.contains("720")   -> SearchQuality.HD
            t.contains("webri") -> SearchQuality.WebRip
            t.contains("hd")    -> SearchQuality.HD
            t.contains("cam")   -> SearchQuality.Cam
            else                -> null
        }
    }

    private fun encodeData(
        title:    String,
        year:     Int?,
        isTv:     Boolean,
        season:   Int    = 1,
        episode:  Int    = 1,
        watchUrl: String = "",
    ): String = JSONObject().apply {
        put("title",    title)
        put("year",     year ?: 0)
        put("isTv",     isTv)
        put("season",   season)
        put("episode",  episode)
        put("watchUrl", watchUrl)
    }.toString()

    // ── Main page ──────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc   = app.get("$mainUrl${request.data}$page").document
        val items = parseListing(doc)
        return newHomePageResponse(
            list    = HomePageList(request.name, items),
            hasNext = items.isNotEmpty(),
        )
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=${query.replace(" ", "+")}").document
        return parseListing(doc)
    }

    private fun parseListing(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("a[href^='/movies/'], a[href^='/tv/']")
            .filter { it.attr("href").count { c -> c == '/' } >= 2 }
            .mapNotNull { el ->
                val href  = el.attr("href")
                val text  = el.text()
                val title = text.substringBeforeLast("(").trim()
                    .takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val qualityText = Regex("""(4k|1080p|720p|480p|webrip|hdts|cam)""",
                    RegexOption.IGNORE_CASE).find(text)?.value
                val img    = el.selectFirst("img")
                val poster = cleanUrl(
                    img?.attr("src") ?: img?.attr("data-src")
                    ?: img?.attr("srcset")?.split(" ")?.firstOrNull()
                )
                if (href.contains("/tv/")) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        posterUrl = poster; quality = parseQuality(qualityText)
                    }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        posterUrl = poster; quality = parseQuality(qualityText)
                    }
                }
            }
            .distinctBy { it.url }

    // ── Load detail page ───────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val absUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val doc    = app.get(absUrl).document

        val h1Raw     = doc.selectFirst("h1")?.text() ?: return null
        val yearMatch = Regex("""\((\d{4})\)\s*$""").find(h1Raw)
        val year      = yearMatch?.groupValues?.get(1)?.toIntOrNull()
        val title     = (if (yearMatch != null) h1Raw.substringBeforeLast("(") else h1Raw)
            .trim().takeIf { it.isNotBlank() } ?: return null

        val posterImg = doc.selectFirst("img[src*='tmdb.org']")
            ?: doc.selectFirst("img[src*='/_next/image']")
            ?: doc.selectFirst("img.object-cover")
        val posterUrl = cleanUrl(
            posterImg?.attr("src") ?: posterImg?.attr("srcset")?.split(" ")?.firstOrNull()
        )

        val meta = mutableMapOf<String, String>()
        doc.select("tr").forEach { tr ->
            val cells = tr.select("td")
            if (cells.size >= 2) meta[cells[0].text().trim()] = cells[1].text().trim()
        }

        val tags     = meta["Genre"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val actors   = meta["Stars"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.map { ActorData(Actor(it, null), roleString = "Actor") }
        val duration = meta["Runtime"]?.let { getDurationFromString(it) }

        var plot = doc.selectFirst(
            "h2:contains(Synopsis) + p, h3:contains(Synopsis) + p, section:contains(Synopsis) p"
        )?.text()
        if (plot.isNullOrBlank()) {
            plot = doc.select("p").map { it.text().trim() }
                .filter { it.length > 80 }.maxByOrNull { it.length }
        }

        val rating = Regex("""([0-9]+(?:\.[0-9]+)?)\s*/\s*10""")
            .find(doc.text())?.groupValues?.get(1)?.toDoubleOrNull()

        val isTv   = absUrl.contains("/tv/")
        val tvType = if (isTv) TvType.TvSeries else TvType.Movie

        if (!isTv) {
            val watchUrl = doc.selectFirst("a[href^='/watch/'][href*='type=movie']")
                ?.attr("href")?.let { "$mainUrl$it" } ?: ""
            val data = encodeData(title, year, isTv = false, watchUrl = watchUrl)
            return newMovieLoadResponse(title, absUrl, tvType, data) {
                this.posterUrl = posterUrl; this.year = year; this.plot = plot
                this.tags = tags; this.duration = duration; this.actors = actors
                runCatching { rating?.let { r -> score = Score.from10(r) } }
            }
        }

        val episodes = mutableListOf<Episode>()
        doc.select("li").forEach liLoop@{ li ->
            val seMatch   = Regex("""S(\d{1,2})E(\d{1,2})""", RegexOption.IGNORE_CASE)
                .find(li.text()) ?: return@liLoop
            val seasonNum = seMatch.groupValues[1].toInt()
            val epNum     = seMatch.groupValues[2].toInt()
            val watchHref = li.selectFirst("a[href^='/watch/']")?.attr("href") ?: return@liLoop
            val watchUrl  = "$mainUrl$watchHref"
            val epTitle   = (li.selectFirst("h3, h4, h5")?.text()?.trim()
                ?: li.selectFirst("img")?.attr("alt")?.trim()
                ?: "Episode $epNum").takeIf { it.isNotBlank() } ?: "Episode $epNum"
            val epThumb   = cleanUrl(li.selectFirst("img")?.attr("src")
                ?: li.selectFirst("img")?.attr("srcset")?.split(" ")?.firstOrNull())
            val epPlot    = li.select("p").lastOrNull()?.text()?.trim()?.takeIf { it.length > 10 }
            val airDate   = Regex("""\d{4}-\d{2}-\d{2}""").find(li.text())?.value
            val data      = encodeData(title, year, isTv = true, seasonNum, epNum, watchUrl)
            episodes.add(newEpisode(data) {
                this.name = epTitle; this.season = seasonNum; this.episode = epNum
                this.posterUrl = epThumb; this.description = epPlot; this.date = airDate
            })
        }
        if (episodes.isEmpty()) {
            var counter = 1
            doc.select("a[href^='/watch/'][href*='type=episode']").forEach { a ->
                val watchUrl = "$mainUrl${a.attr("href")}"
                val data     = encodeData(title, year, isTv = true, 1, counter, watchUrl)
                episodes.add(newEpisode(data) {
                    this.name = a.text().trim().takeIf { it.isNotBlank() } ?: "Episode $counter"
                    this.episode = counter; this.season = 1
                })
                counter++
            }
        }

        return newTvSeriesLoadResponse(title, absUrl, tvType, episodes.distinctBy { it.data }) {
            this.posterUrl = posterUrl; this.year = year; this.plot = plot
            this.tags = tags; this.actors = actors
            runCatching { rating?.let { r -> score = Score.from10(r) } }
        }
    }

    // ── Load links ─────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val json     = runCatching { JSONObject(data) }.getOrNull()
        val title    = json?.optString("title")?.takeIf { it.isNotBlank() } ?: data
        val year     = json?.optInt("year")?.takeIf { it > 0 }
        val isTv     = json?.optBoolean("isTv") ?: false
        val season   = json?.optInt("season")   ?: 1
        val episode  = json?.optInt("episode")  ?: 1
        val watchUrl = json?.optString("watchUrl")?.takeIf { it.isNotBlank() }

        var linksAdded = false

        // ── 1. Logged-in site player ───────────────────────────────────────────
        // If the user has set credentials, log in and scrape the real watch page.
        if (watchUrl != null && parseCreds() != null) {
            ensureSession()
            if (sessionActive) {
                linksAdded = scrapeWatchPage(watchUrl, callback) || linksAdded
            }
        }

        // ── 2. TMDB embed fallbacks ────────────────────────────────────────────
        val tmdbId = resolveTmdbId(title, year, isTv)
        if (tmdbId != null) {
            if (isTv) {
                embed(callback, "autoembed S${season}E${episode}",
                    "https://autoembed.cc/tv/tmdb/$tmdbId-$season-$episode")
                embed(callback, "vidsrc S${season}E${episode}",
                    "https://vidsrc.me/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode")
                embed(callback, "vidsrc.to S${season}E${episode}",
                    "https://vidsrc.to/embed/tv/$tmdbId/$season/$episode")
                embed(callback, "2embed S${season}E${episode}",
                    "https://www.2embed.cc/embedtv/$tmdbId&s=$season&e=$episode")
            } else {
                embed(callback, "autoembed",
                    "https://autoembed.cc/movie/tmdb/$tmdbId")
                embed(callback, "vidsrc",
                    "https://vidsrc.me/embed/movie?tmdb=$tmdbId")
                embed(callback, "vidsrc.to",
                    "https://vidsrc.to/embed/movie/$tmdbId")
                embed(callback, "2embed",
                    "https://www.2embed.cc/embed/$tmdbId")
            }
            linksAdded = true
        }

        // ── 3. Bare watch URL fallback (no credentials set) ───────────────────
        if (watchUrl != null && parseCreds() == null) {
            embed(callback, "CTGMovies (login required)", watchUrl)
            linksAdded = true
        }

        return linksAdded
    }

    // ── Private utilities ──────────────────────────────────────────────────────

    /**
     * Fetch the /watch/ page with the session cookie and extract playable links.
     * The watch page is a Next.js page; video sources appear as:
     *   – <iframe src="..."> embed players
     *   – <source src="..."> tags inside <video>
     *   – JSON __NEXT_DATA__ with nested src/url fields
     *   – Plain https://…m3u8 or …mp4 URL strings in the HTML
     */
    private suspend fun scrapeWatchPage(watchUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val response = runCatching {
            app.get(
                watchUrl,
                headers = mapOf("Referer" to mainUrl),
            )
        }.getOrNull() ?: return false

        // Bail early if we got redirected back to the login page
        if (response.url.contains("/login") || response.url.contains("/register")) {
            sessionActive = false   // session expired / login failed
            return false
        }

        val html    = response.text
        val doc     = response.document
        var found   = false

        // <video> sources
        doc.select("video source[src]").forEach { src ->
            val url = src.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            val abs = if (url.startsWith("http")) url else if (url.startsWith("//")) "https:$url" else "$mainUrl$url"
            embed(callback, "CTGMovies Direct", abs)
            found = true
        }

        // <iframe> embeds
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            val abs = if (src.startsWith("//")) "https:$src" else src
            if (!abs.contains("youtube") && !abs.contains("google")) {
                embed(callback, "CTGMovies Embed", abs)
                found = true
            }
        }

        // Bare m3u8/mp4 URLs in raw HTML
        val videoRegex = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4|mkv)(?:\?[^\s"'<>\\]*)?""")
        videoRegex.findAll(html).map { it.value }.distinct().forEach { url ->
            embed(callback, "CTGMovies Stream", url)
            found = true
        }

        // Next.js __NEXT_DATA__ JSON — look for nested file/src/url keys
        val nextData = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        if (nextData != null) {
            val urlInJson = Regex(""""(?:src|url|file|stream|source|hls|m3u8)":\s*"(https?://[^"]+)"""")
            urlInJson.findAll(nextData).map { it.groupValues[1] }.distinct().forEach { url ->
                if (!url.contains("image.tmdb") && !url.contains("/_next/")) {
                    embed(callback, "CTGMovies JSON", url)
                    found = true
                }
            }
        }

        return found
    }

    private fun embed(callback: (ExtractorLink) -> Unit, source: String, url: String) {
        callback.invoke(
            newExtractorLink(
                source = name,
                name   = source,
                url    = url,
                type   = ExtractorLinkType.VIDEO,
            ) {
                referer = mainUrl
                quality = Qualities.Unknown.value
            }
        )
    }

    private suspend fun resolveTmdbId(title: String, year: Int?, isTv: Boolean): Int? {
        if (tmdbApiKey.isBlank()) return null
        val type      = if (isTv) "tv" else "movie"
        val yearParam = if (year != null && year > 0)
            if (isTv) "&first_air_date_year=$year" else "&year=$year"
        else ""
        val apiUrl = "$tmdbBase/search/$type?api_key=$tmdbApiKey&query=${title.replace(" ", "+")}$yearParam"
        return runCatching {
            app.get(apiUrl).parsedSafe<TmdbSearchResult>()?.results?.firstOrNull()?.id
        }.getOrNull()
    }

    data class TmdbSearchResult(val results: List<TmdbItem>?)
    data class TmdbItem(val id: Int?)
}
