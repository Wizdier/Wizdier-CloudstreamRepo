package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

// re-export for convenience — newSubtitleFile is suspend in current API;
// this wrapper normalises the call site so we never forget the suspend context.
private suspend fun newSubtitleFileSafe(label: String, url: String): SubtitleFile =
    com.lagradost.cloudstream3.newSubtitleFile(label, url)

// ─────────────────────────────────────────────────────────────────────────────
// AnikotoProvider — Cloudstream extension for https://anikototv.to
//
// Architecture
// ------------
// • mainPage:        Latest Updated · New Release · Most Viewed · Trending ·
//                    Subbed · Dubbed · Movies · Upcoming · Genre filters
// • search:          /filter?keyword=...  (HTML, paginated)
// • load:            /watch/<slug>  (HTML page → metadata + episodes)
// • episodes:        GET /ajax/episode/list/<id>?vrf=   (server accepts empty vrf)
// • loadLinks:       1) /ajax/server/list?servers=<data-ids>   (HTML: <li data-link-id>)
//                    2) /ajax/server?get=<link-id>             (JSON: result.url = embed)
//                    3) https://mapper.nekostream.site/api/mal/<malId>/<slug>/<ts>
//                       → extra servers (Vidstream / Kiwi-Stream / vibe-Stream)
// • vidtube extract: GET https://vidtube.site/stream/getSourcesNew?id=<id>&type=<sub|dub>
//                    → JSON {sources.file (m3u8), tracks[] (subtitles)}
//
// The site uses the same Gogoanime/Aniwatch template family. The "vrf" param
// is *not* validated server-side (the JS obfuscator's RC4 is dead code), so
// we send `vrf=` empty — confirmed working in production traffic.
// ─────────────────────────────────────────────────────────────────────────────
class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikototv.to"
    override var name = "AnikotoTV"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override var lang = "en"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.TvSeries,
    )

    override val supportedSyncNames = setOf(
        SyncIdName.Anilist,
        SyncIdName.MyAnimeList,
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/",
    )

    private val ajaxHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "en-US,en;q=0.9",
        "X-Requested-With" to "XMLHttpRequest",
    )

    // Custom SSL-ignoring client so TLS handshake failures on older Android
    // TVs (expired Let's Encrypt roots) never take the whole provider down.
    private val client: Requests by lazy {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }
        val okBuilder = okhttp3.OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
        Requests(okBuilder.build())
    }

    // ─────────────────────────── Helper fetch ────────────────────────────

    private suspend fun fetchHtml(
        url: String,
        headers: Map<String, String> = baseHeaders,
        timeout: Long = 20_000L,
    ): String? = AnikotoConcurrent.retry {
        val res = client.get(url, headers = headers, timeout = timeout)
        if (res.code !in 200..299 || res.text.isBlank()) return@retry null
        res.text
    }

    private suspend fun fetchJson(
        url: String,
        headers: Map<String, String> = ajaxHeaders,
        timeout: Long = 15_000L,
    ): JSONObject? = AnikotoConcurrent.retry {
        val res = client.get(url, headers = headers, timeout = timeout)
        if (res.code !in 200..299 || res.text.isBlank()) return@retry null
        runCatching { JSONObject(res.text) }.getOrNull()
    }

    // ─────────────────────────── Main page ───────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updated" to "Latest Updated",
        "$mainUrl/new-release" to "New Release",
        "$mainUrl/most-viewed" to "Most Viewed",
        "$mainUrl/filter?sort=trending" to "Trending",
        "$mainUrl/filter?subtype=sub&sort=recently_updated" to "Subbed Recent",
        "$mainUrl/filter?subtype=dub&sort=recently_updated" to "Dubbed Recent",
        "$mainUrl/filter?type[]=movie&sort=recently_updated" to "Anime Movies",
        "$mainUrl/filter?status[]=upcoming&sort=recently_added" to "Upcoming",
        "$mainUrl/filter?genre[]=action&sort=recently_updated" to "Action",
        "$mainUrl/filter?genre[]=fantasy&sort=recently_updated" to "Fantasy",
        "$mainUrl/filter?genre[]=comedy&sort=recently_updated" to "Comedy",
        "$mainUrl/filter?genre[]=drama&sort=recently_updated" to "Drama",
        "$mainUrl/filter?genre[]=romance&sort=recently_updated" to "Romance",
        "$mainUrl/filter?genre[]=sci-fi&sort=recently_updated" to "Sci-Fi",
        "$mainUrl/filter?genre[]=slice-of-life&sort=recently_updated" to "Slice of Life",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = if (request.data.contains("?")) {
            "${request.data}&page=$page"
        } else {
            "${request.data}?page=$page"
        }
        val html = fetchHtml(url) ?: return newHomePageResponse(emptyList())
        val doc = Jsoup.parse(html)
        val items = parseCardGrid(doc)

        val hasNext = doc.select("ul.pagination li.active + li a").isNotEmpty() ||
                doc.select("a.page-link:matches(Next|»|›)").isNotEmpty() ||
                (page == 1 && items.size >= 18)

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext,
        )
    }

    // ─────────────────────────── Search ──────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = "$mainUrl/filter?keyword=${q.encodeUrl()}"
        val html = fetchHtml(url) ?: return emptyList()
        return parseCardGrid(Jsoup.parse(html))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ─────────────────────────── Load detail ─────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.substringBefore("?").removeSuffix("/")
        val html = fetchHtml(cleanUrl) ?: return null
        val doc = Jsoup.parse(html)

        // Slug → /watch/<slug>
        val slug = cleanUrl.substringAfter("/watch/").substringBefore("/").ifBlank { null }

        // Title + JP title
        val titleEl = doc.selectFirst("h1.title.d-title")
        val jpName = titleEl?.attr("data-jp")?.trim().orEmpty()
        val title = titleEl?.text()?.trim()?.ifBlank { jpName } ?: slug?.replace('-', ' ') ?: "Anime"

        // Poster
        val poster = doc.selectFirst("div.binfo div.poster img")?.absUrl("src")
            ?: doc.selectFirst("div.poster img")?.absUrl("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        // Alt names ("Title1;Title2;Title3")
        val altNames = doc.selectFirst("div.names")?.text()?.split(';', ',')
            ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        // Synopsis
        val synopsis = doc.selectFirst("div.synopsis div.content")?.text()?.trim()
            ?: doc.selectFirst("div.synopsis")?.text()?.trim()

        // Metadata
        val meta = doc.selectFirst("div.binfo, div.bmeta, div.info")
        val genres = meta?.select("div:contains(Genres) a, div.meta div:contains(Genres) a")
            ?.map { it.text().trim() }?.filter { it.isNotEmpty() }
            ?: doc.select("div.bmeta a[href*=/genre/]").map { it.text().trim() }.filter { it.isNotEmpty() }

        val type = meta?.selectFirst("div:contains(Type) span")?.text()?.trim()
            ?: doc.selectFirst("div.meta div:contains(Type) span")?.text()?.trim()
            ?: "TV"

        val yearText = run {
            val premiered = doc.selectFirst("div:contains(Premiered) a")?.text()?.trim().orEmpty()
            val aired = doc.selectFirst("div:contains(Aired) span")?.text()?.trim().orEmpty()
            // Extract a 4-digit year from SPRING 2027 / 2027-04 etc.
            val yearRegex = Regex("(19|20)\\d{2}")
            (yearRegex.find(premiered)?.value ?: yearRegex.find(aired)?.value)?.toIntOrNull()
        }

        val status = doc.selectFirst("div:contains(Status) span a, div:contains(Status) a")?.text()?.trim()
            ?: doc.selectFirst("div:contains(Status) span")?.text()?.trim()
            ?: ""

        val episodesCount = doc.selectFirst("div:contains(Episodes) span")?.text()?.trim()?.toIntOrNull()
        // "24 min" → 24 (seconds: 24 * 60)
        val durationSec: Int? = runCatching {
            val txt = doc.selectFirst("div:contains(Duration) span")?.text()?.trim().orEmpty()
            Regex("(\\d+)").find(txt)?.value?.toIntOrNull()?.let { it * 60 }
        }.getOrNull()
        val studios = meta?.select("div:contains(Studios) a, div:contains(Studios) span a")
            ?.map { it.text().trim() }?.filter { it.isNotEmpty() }
            ?: doc.select("div.bmeta a[href*=/studio/]").map { it.text().trim() }.filter { it.isNotEmpty() }

        // MAL id (often displayed as "MAL: 21" — and also surfaced via the
        // mapper.nekostream.site API on each episode link, but the detail page
        // value is more reliable for adding the sync id).
        val malId = runCatching {
            val txt = doc.selectFirst("div:contains(MAL) span")?.text()?.trim()
            txt?.toIntOrNull()
        }.getOrNull()

        // Score (the site exposes its own community rating)
        val ratingValue = doc.selectFirst("span[itemprop=ratingValue]")?.text()?.trim()?.toDoubleOrNull()
            ?: doc.selectFirst("div.brating span.value span")?.text()?.trim()?.toDoubleOrNull()

        // Anime numeric ID (data-tip on the poster) — needed for the
        // episode-list AJAX endpoint. Multiple posters may exist; we want
        // the one tied to the main detail block.
        val animeId = doc.selectFirst("div.binfo div.poster [data-tip], div.ani.poster[data-tip], #w-info [data-tip]")
            ?.attr("data-tip")?.trim()
            ?: slug?.let { resolveAnimeIdFromSlug(it) }

        // ── Build episode list ─────────────────────────────────────────
        val episodes = if (!animeId.isNullOrEmpty()) {
            fetchEpisodes(animeId, slug ?: "")
        } else {
            emptyList()
        }

        // Single-episode (movie / single OVA) → produce a Movie response so
        // Cloudstream shows the watch button directly; otherwise TvSeries/Anime.
        val isMovie = type.equals("Movie", true) ||
                type.equals("Special", true) && episodes.size == 1 ||
                episodes.size == 1

        val tvType = when {
            isMovie && type.equals("Movie", true) -> TvType.AnimeMovie
            isMovie -> TvType.Movie
            else -> TvType.Anime
        }

        val metaJson = if (episodes.isEmpty()) {
            // No episodes yet (e.g. "Not Yet Aired") — still let user save the page
            JSONObject().put("slug", slug ?: "").put("animeId", animeId ?: "").toString()
        } else {
            JSONObject().put("slug", slug ?: "").put("animeId", animeId ?: "").toString()
        }

        return if (isMovie && episodes.isNotEmpty()) {
            newMovieLoadResponse(title, url, tvType, episodes.first().data) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = synopsis
                this.year = yearText
                this.tags = genres
                this.duration = durationSec
                runCatching { ratingValue?.let { this.score = Score.from10(it) } }
                this.recommendations = parseRecommendations(doc)
                if (malId != null) addMalId(malId)
            }
        } else {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = buildString {
                    synopsis?.let { append(it) }
                    if (altNames.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append("Other names: ").append(altNames.joinToString(", "))
                    }
                    if (status.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("Status: ").append(status)
                    }
                    if (studios.isNotEmpty()) {
                        if (isNotEmpty()) append("\n")
                        append("Studio: ").append(studios.joinToString(", "))
                    }
                    if (episodesCount != null) {
                        if (isNotEmpty()) append("\n")
                        append("Episodes: ").append(episodesCount)
                    }
                    if (durationSec != null) {
                        if (isNotEmpty()) append("\n")
                        append("Duration: ").append(durationSec / 60).append(" min")
                    }
                }
                this.year = yearText
                this.tags = genres
                runCatching { ratingValue?.let { this.score = Score.from10(it) } }
                this.recommendations = parseRecommendations(doc)
                if (malId != null) addMalId(malId)
            }
        }
    }

    // ─────────────────────────── Episode list ────────────────────────────

    private val episodeListCache = AnikotoConcurrent.TtlCache<String, List<Episode>>(ttlMs = 5 * 60 * 1000L)

    private suspend fun fetchEpisodes(animeId: String, slug: String): List<Episode> {
        val cacheKey = "$animeId:$slug"
        episodeListCache.get(cacheKey)?.let { return it }

        val url = "$mainUrl/ajax/episode/list/$animeId?vrf="
        val json = fetchJson(url, headers = ajaxHeaders + ("Referer" to "$mainUrl/watch/$slug"))
            ?: return emptyList()

        val html = json.optString("result").ifBlank { return emptyList() }
        val doc = Jsoup.parse(html)

        // Each episode <li> contains an <a> with data-* attributes. Some pages
        // wrap episodes in range groups (display:none); collect them all.
        val items = doc.select("ul.ep-range li a[data-ids], ul.ep-range li a[data-id]")
        val episodes = items.map { a ->
            val num = a.attr("data-num").trim().ifBlank { a.attr("data-slug") }
            val epTitle = a.parent()?.attr("title")?.takeIf { it.isNotBlank() }
                ?: "Episode $num"
            val dataIds = a.attr("data-ids")
            val mal = a.attr("data-mal").trim().ifBlank { null }
            val timestamp = a.attr("data-timestamp").trim().ifBlank { null }
            val epSlug = a.attr("data-slug").trim().ifBlank { num }
            val hasSub = a.attr("data-sub") == "1"
            val hasDub = a.attr("data-dub") == "1"

            val payload = JSONObject()
                .put("slug", slug)
                .put("epSlug", epSlug)
                .put("num", num)
                .put("ids", dataIds)
                .put("mal", mal ?: JSONObject.NULL)
                .put("timestamp", timestamp ?: JSONObject.NULL)
                .put("sub", hasSub)
                .put("dub", hasDub)
                .toString()

            newEpisode(payload) {
                this.name = epTitle
                this.episode = num.toIntOrNull()
            }
        }

        episodeListCache.put(cacheKey, episodes)
        return episodes
    }

    /** When the page somehow lacks data-tip, fall back to scraping the ID
     *  from the watch URL via the public episode AJAX. */
    private suspend fun resolveAnimeIdFromSlug(slug: String): String? {
        val url = "$mainUrl/watch/$slug"
        val html = fetchHtml(url) ?: return null
        val doc = Jsoup.parse(html)
        return doc.selectFirst("[data-tip]")?.attr("data-tip")?.trim()
    }

    // ─────────────────────────── Load links ──────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return false
        val slug = root.optString("slug")
        val ids = root.optString("ids").ifBlank { return false }
        val malId = root.optString("mal").takeIf { it.isNotBlank() && it != "null" }
        val timestamp = root.optString("timestamp").takeIf { it.isNotBlank() && it != "null" }
        val epSlug = root.optString("epSlug").ifBlank { root.optString("num") }
        val wantSub = root.optBoolean("sub", true)
        val wantDub = root.optBoolean("dub", false)

        val animeReferer = "$mainUrl/watch/$slug"
        val episodeReferer = "$mainUrl/watch/$slug/ep-$epSlug"

        var foundAny = false

        // ── 1. Anikoto native server list ──────────────────────────────
        // The <li> entries are grouped under <div class="type" data-type="sub|dub">.
        val serverListHtml = fetchHtml(
            "$mainUrl/ajax/server/list?servers=$ids",
            headers = ajaxHeaders + ("Referer" to episodeReferer),
        )
        val servers = mutableListOf<ServerEntry>()
        if (!serverListHtml.isNullOrBlank()) {
            val sdoc = Jsoup.parse(serverListHtml)
            for (typeDiv in sdoc.select("div.type[data-type]")) {
                val kind = typeDiv.attr("data-type") // sub | dub
                for (li in typeDiv.select("li[data-link-id]")) {
                    val linkId = li.attr("data-link-id").trim()
                    if (linkId.isEmpty()) continue
                    val label = li.text().trim().ifBlank { "Server" }
                    servers.add(ServerEntry(linkId, kind, label))
                }
            }
        }

        // Resolve each link-id → embed URL in parallel
        val resolved = AnikotoConcurrent.parallelMapNotNull(servers) { sv ->
            val json = fetchJson(
                "$mainUrl/ajax/server?get=${sv.linkId}",
                headers = ajaxHeaders + ("Referer" to episodeReferer),
            ) ?: return@parallelMapNotNull null
            val embed = json.optJSONObject("result")?.optString("url")?.takeIf { it.isNotBlank() }
                ?: return@parallelMapNotNull null
            ResolvedServer(sv, embed)
        }

        // Process each resolved embed (parallel for speed)
        val processed = AnikotoConcurrent.parallelMapNotNull(resolved.distinctBy { it.embed }) { rs ->
            runCatching {
                processEmbed(rs, wantSub, wantDub, animeReferer, subtitleCallback, callback)
            }.getOrNull() ?: false
        }
        if (processed.any { it }) foundAny = true

        // ── 2. mapper.nekostream.site — extra sources ──────────────────
        if (malId != null && timestamp != null) {
            val mapperUrl = "https://mapper.nekostream.site/api/mal/$malId/$epSlug/$timestamp"
            val mapperJson = fetchJson(
                mapperUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "Referer" to animeReferer,
                ),
            )
            if (mapperJson != null) {
                val mapperServers = mutableListOf<ServerEntry>()
                for (key in mapperJson.keys()) {
                    if (key == "status") continue
                    val entry = mapperJson.optJSONObject(key) ?: continue
                    val displayName = when (key.lowercase()) {
                        "gogoanime" -> "Vidstream"
                        "anivibe" -> "vibe-Stream"
                        "animepahe" -> "Kiwi-Stream"
                        else -> key.replaceFirstChar { it.uppercase() }
                    }
                    if (wantSub) {
                        entry.optJSONObject("sub")?.optString("url")?.takeIf { it.isNotBlank() }
                            ?.let { mapperServers.add(ServerEntry(it, "sub", displayName)) }
                    }
                    if (wantDub) {
                        entry.optJSONObject("dub")?.optString("url")?.takeIf { it.isNotBlank() }
                            ?.let { mapperServers.add(ServerEntry(it, "dub", displayName)) }
                    }
                }

                // Mapper URLs are link-ids (base64-encoded), need to go through /ajax/server?get=
                val mapperResolved = AnikotoConcurrent.parallelMapNotNull(mapperServers) { sv ->
                    val json = fetchJson(
                        "$mainUrl/ajax/server?get=${sv.linkId}",
                        headers = ajaxHeaders + ("Referer" to episodeReferer),
                    ) ?: return@parallelMapNotNull null
                    val embed = json.optJSONObject("result")?.optString("url")?.takeIf { it.isNotBlank() }
                        ?: return@parallelMapNotNull null
                    ResolvedServer(sv.copy(label = sv.label), embed)
                }

                val mapperProcessed = AnikotoConcurrent.parallelMapNotNull(mapperResolved.distinctBy { it.embed }) { rs ->
                    runCatching {
                        processEmbed(rs, wantSub, wantDub, animeReferer, subtitleCallback, callback)
                    }.getOrNull() ?: false
                }
                if (mapperProcessed.any { it }) foundAny = true
            }
        }

        return foundAny
    }

    /** Process one resolved embed URL — handles vidtube.site natively and
     *  delegates everything else to CloudStream's extractor registry. */
    private suspend fun processEmbed(
        rs: ResolvedServer,
        wantSub: Boolean,
        wantDub: Boolean,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val embed = rs.embed
        val host = runCatching { java.net.URI(embed).host }.getOrNull() ?: return false

        // ── vidtube.site → call getSourcesNew directly ─────────────────
        if (host.contains("vidtube.site") || host.contains("megaplay")) {
            return extractVidTube(embed, rs.sv.kind, referer, subtitleCallback, callback)
        }

        // ── Fallback: try CloudStream's built-in extractor registry ────
        // This handles MegaCloud, VidPlay, Mp4Upload, StreamWish, Streamtape,
        // Filemoon, etc. — each returns one or more ExtractorLink objects.
        val extracted = runCatching {
            loadExtractor(embed, referer, subtitleCallback, callback)
        }.isSuccess
        if (extracted) return true

        // ── Last resort: surface the raw embed as an iframe link so the
        //    user at least sees something playable in a WebView.
        callback(
            newExtractorLink(
                source = "${name} • ${rs.sv.label}",
                name = "${name} • ${rs.sv.label}",
                url = embed,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Referer" to referer, "User-Agent" to userAgent)
            }
        )
        return true
    }

    /** VidTube embed extractor — calls the public getSourcesNew endpoint
     *  to retrieve the master m3u8 and any VTT subtitle tracks. */
    private suspend fun extractVidTube(
        embedUrl: String,
        kind: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Need to fetch the embed page to grab data-id (numeric file id)
        val embedHtml = fetchHtml(
            embedUrl,
            headers = baseHeaders + ("Referer" to referer),
        ) ?: return false

        val doc = Jsoup.parse(embedHtml)
        val playerId = doc.selectFirst("#megaplay-player[data-id]")?.attr("data-id")?.trim()
            ?: return false
        val pageType = doc.selectFirst("script:containsData(type:)")?.data()
            ?.let { Regex("type\\s*:\\s*['\"]([^'\"]+)['\"]").find(it)?.groupValues?.getOrNull(1) }
            ?: if (kind == "dub") "dub" else "sub"

        // The page exposes settings.cid / settings.cidu — include them so
        // the API doesn't reject the request as a hot-link.
        val cid = doc.selectFirst("script:containsData(cid :)")?.data()
            ?.let { Regex("cid\\s*:\\s*['\"]([^'\"]+)['\"]").find(it)?.groupValues?.getOrNull(1) }
            .orEmpty()

        val sourcesUrl = "https://vidtube.site/stream/getSourcesNew?id=$playerId&type=$pageType" +
                (if (cid.isNotBlank()) "&cid=$cid" else "")

        val res = client.get(
            sourcesUrl,
            headers = ajaxHeaders + ("Referer" to embedUrl),
            timeout = 15_000L,
        )
        if (res.code !in 200..299 || res.text.isBlank()) return false
        val json = runCatching { JSONObject(res.text) }.getOrNull() ?: return false

        val master = json.optJSONObject("sources")?.optString("file")?.takeIf { it.isNotBlank() }
            ?: return false

        // Push all subtitle tracks
        json.optJSONArray("tracks")?.let { tracks ->
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                val file = t.optString("file").takeIf { it.isNotBlank() } ?: continue
                val label = t.optString("label").ifBlank { "Subtitle ${i + 1}" }
                subtitleCallback(newSubtitleFileSafe(label, file))
            }
        }

        // The master playlist itself contains per-quality variants; we surface
        // it as a single M3U8 link and let CloudStream's HLS player pick.
        callback(
            newExtractorLink(
                source = "$name • VidTube ${pageType.uppercase()}",
                name = "$name • VidTube ${pageType.uppercase()}",
                url = master,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = "https://vidtube.site/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Referer" to "https://vidtube.site/",
                    "User-Agent" to userAgent,
                    "Origin" to "https://vidtube.site",
                )
            }
        )
        return true
    }

    // ─────────────────────────── Parsers ─────────────────────────────────

    private fun parseCardGrid(doc: Document): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SearchResponse>()

        // Two card layouts coexist on the site:
        //   1. Home page compact:  div.ani.items > div.item > div.ani.poster[data-tip] > a[href] > img[src,alt]
        //   2. Listing page wide:  div.item > div.inner > div.ani.poster.tip[data-tip] > a[href] > img[src,alt]
        val cards = doc.select(
            "div.ani.poster[data-tip], div.ani.poster.tip[data-tip], div.poster[data-tip]"
        )
        for (card in cards) {
            val a = card.selectFirst("a[href*=/watch/]") ?: continue
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val cleanHref = href.substringBefore("/ep-").substringBefore("?")
            if (!seen.add(cleanHref)) continue

            val img = a.selectFirst("img")
            val title = img?.attr("alt")?.trim()
                ?: card.selectFirst(".name, .d-title, .title")?.text()?.trim()
                ?: a.selectFirst(".name, .d-title, .title")?.text()?.trim()
                ?: continue
            val poster = img?.absUrl("src") ?: img?.attr("src") ?: img?.attr("data-src")

            // Episode-count / type metadata
            val meta = card.selectFirst(".meta")
            val totalEps = meta?.selectFirst(".total span")?.text()?.trim()?.toIntOrNull()
            val typeLabel = meta?.selectFirst(".right")?.text()?.trim()
            val isMovie = typeLabel.equals("Movie", true)

            val tvType = when (typeLabel?.lowercase()) {
                "movie" -> TvType.AnimeMovie
                "tv" -> TvType.Anime
                "ona", "ova", "special" -> TvType.Anime
                else -> TvType.Anime
            }

            out.add(
                if (isMovie) {
                    newMovieSearchResponse(title, cleanHref, tvType) {
                        this.posterUrl = poster
                    }
                } else {
                    newAnimeSearchResponse(title, cleanHref, tvType) {
                        this.posterUrl = poster
                    }
                }
            )
        }
        return out
    }

    private fun parseRecommendations(doc: Document): List<SearchResponse> {
        // The detail page sidebar lists "Trending" / "Related" — surface those
        // as recommendations so Cloudstream shows them in the detail screen.
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (a in doc.select("aside.sidebar a.item[href*=/watch/], #watch-order a[href*=/watch/], .scaff.side a[href*=/watch/]")) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val clean = href.substringBefore("/ep-").substringBefore("?")
            if (!seen.add(clean)) continue
            val name = a.selectFirst(".name, .d-title, .title")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim() ?: continue
            val poster = a.selectFirst("img")?.absUrl("src")
            out.add(
                newAnimeSearchResponse(name, clean, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
            if (out.size >= 18) break
        }
        return out
    }

    // ─────────────────────────── Small types ─────────────────────────────

    private data class ServerEntry(
        val linkId: String,
        val kind: String, // "sub" | "dub"
        val label: String,
    )

    private data class ResolvedServer(
        val sv: ServerEntry,
        val embed: String,
    )

    // ─────────────────────────── URL helpers ─────────────────────────────

    private fun String.encodeUrl(): String =
        URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}