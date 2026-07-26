package com.wizdier

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncRepo
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * WizstreamAnimeProvider — AniList catalog + multi-source resolver.
 */

// ─── File-level constants & helpers ───
private const val ANILIST_ENDPOINT = "https://graphql.anilist.co"
private const val A_IMG = "https://image.tmdb.org/t/p"
private const val A_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

private data class PageCfg(
    val sort: List<String>,
    val season: String? = null,
    val seasonYear: Int? = null,
    val status: String? = null,
)

private fun String?.aToTmdbImg(size: String): String? =
    this?.takeIf { it.isNotBlank() && it != "null" }?.let { "$A_IMG/$size$it" }

private fun aParseAirDate(s: String?): Long? {
    if (s == null) return null
    val p = s.split("-")
    if (p.size != 3) return null
    val y = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    val d = p[2].toIntOrNull() ?: return null
    return runCatching {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.clear(); c.set(y, m - 1, d, 0, 0, 0); c.timeInMillis
    }.getOrNull()
}

private fun JSONArray.aToActors(limit: Int = 15): List<ActorData> =
    (0 until length()).mapNotNull { i ->
        val c = optJSONObject(i) ?: return@mapNotNull null
        val name = c.aOptStr("name") ?: c.aOptStr("original_name")
            ?: return@mapNotNull null
        val profile = c.aOptStr("profile_path").aToTmdbImg("w185")
        val role = c.aOptStr("character")
        ActorData(Actor(name, profile), roleString = role ?: "")
    }.take(limit)

private fun aPickLogo(logos: JSONArray?): String? {
    if (logos == null || logos.length() == 0) return null
    var enSvg: String? = null; var anyPng: String? = null
    for (i in 0 until logos.length()) {
        val l = logos.optJSONObject(i) ?: continue
        val p = l.optString("file_path").takeIf { it.isNotBlank() } ?: continue
        val lang = l.optString("iso_639_1").trim().lowercase()
        val isSvg = p.endsWith(".svg", true)
        val url = "$A_IMG/w500$p"
        when {
            lang == "en" && !isSvg -> return url
            lang == "en" && isSvg && enSvg == null -> enSvg = url
            !isSvg && anyPng == null -> anyPng = url
        }
    }
    return enSvg ?: anyPng
}

private fun aPickTrailer(videos: JSONArray?): String? {
    if (videos == null) return null
    var official: String? = null; var any: String? = null
    for (i in 0 until videos.length()) {
        val v = videos.optJSONObject(i) ?: continue
        if (!v.optString("site").equals("YouTube", true)) continue
        val key = v.aOptStr("key") ?: continue
        when {
            v.optString("type").equals("Trailer", true) && v.optBoolean("official") && official == null ->
                official = "https://www.youtube.com/watch?v=$key"
            v.optString("type").equals("Trailer", true) && any == null ->
                any = "https://www.youtube.com/watch?v=$key"
        }
    }
    return official ?: any
}

private fun JSONObject.aOptStr(k: String): String? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").trim().takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject.aOptInt(k: String): Int? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").toIntOrNull()
        ?: optInt(k, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

private fun JSONObject.aOptDbl(k: String): Double? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").toDoubleOrNull()
        ?: optDouble(k, Double.NaN).takeIf { !it.isNaN() }

private fun ExtractorLink.aRelabel(newSource: String, newName: String): ExtractorLink =
    kotlinx.coroutines.runBlocking {
        newExtractorLink(
            source = newSource,
            name = newName,
            url = this@aRelabel.url,
            type = this@aRelabel.type,
        ) {
            this.referer = this@aRelabel.referer
            this.quality = this@aRelabel.quality
            this.headers = this@aRelabel.headers
        }
    }

private fun currentSeasonFilter(): Pair<String, Int> {
    val cal = java.util.Calendar.getInstance()
    val month = cal.get(java.util.Calendar.MONTH)
    val year = cal.get(java.util.Calendar.YEAR)
    val season = when {
        month in 2..4 -> "SPRING"
        month in 5..7 -> "SUMMER"
        month in 8..10 -> "FALL"
        else -> "WINTER"
    }
    return season to year
}

private fun nextSeasonFilter(): Pair<String, Int> {
    val cal = java.util.Calendar.getInstance()
    val month = cal.get(java.util.Calendar.MONTH)
    var year = cal.get(java.util.Calendar.YEAR)
    val season = when {
        month in 2..4 -> "SUMMER"
        month in 5..7 -> "FALL"
        month in 8..10 -> "WINTER".also { year += 1 }
        else -> "SPRING"
    }
    return season to year
}

class WizstreamAnimeProvider : MainAPI() {

    override var mainUrl = "https://anilist.co"
    // (v56) THE canonical anime source name — the unified Wizstream no
    // longer bundles an AniList catalogue, so this pure module owns it.
    override var name = "Wizstream-Anime"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Cartoon,
        TvType.AsianDrama,
    )
    override val supportedSyncNames = setOfNotNull(
        SyncIdName.Anilist,
        SyncIdName.MyAnimeList,
        runCatching { SyncIdName.valueOf("Kitsu") }.getOrNull(),
        runCatching { SyncIdName.valueOf("Simkl") }.getOrNull(),
        runCatching { SyncIdName.valueOf("Imdb") }.getOrNull(),
        )

    companion object {
        private const val TAG = "WizstreamAnime"

        private val META_CACHE = ConcurrentHashMap<Int, Pair<Long, AniDetail>>()
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        private data class VidHost(
            val label: String,
            val movie: (String) -> String,
            val tv: (String, Int, Int) -> String,
        )

        private val VID_HOSTS: List<VidHost> = listOf(
            // ── Verified-reachable Vid[x] family (7 named hosts) ────────────
            // Each host was HTTP-tested on 2026-07-17 — see Wizstream-SOURCES.md.
            // For anime we pass imdb/tmdb/anilist ids; not all hosts will resolve
            // every id type but the loadExtractor call degrades gracefully.
            VidHost("VidSrc",
                { id -> "https://vidsrc.to/embed/movie/$id" },
                { id, s, e -> "https://vidsrc.to/embed/tv/$id/$s/$e" }),
            VidHost("VidNest",
                { id -> "https://vidsrc.mov/embed/movie/$id" },
                { id, s, e -> "https://vidsrc.mov/embed/tv/$id/$s/$e" }),
            VidHost("VidPlay",
                { id -> "https://vidplay.site/embed/movie/$id" },
                { id, s, e -> "https://vidplay.site/embed/tv/$id/$s/$e" }),
            VidHost("VidUp",
                { id -> "https://vidlink.pro/movie/$id" },
                { id, s, e -> "https://vidlink.pro/tv/$id/$s/$e" }),
            VidHost("VidRock",
                { id -> "https://vidrock.ru/embed/movie?imdb=$id" },
                { id, s, e -> "https://vidrock.ru/embed/tv?imdb=$id&season=$s&episode=$e" }),
            VidHost("VidFast",
                { id -> "https://vidfast.pro/movie/$id" },
                { id, s, e -> "https://vidfast.pro/tv/$id/$s/$e" }),
            VidHost("VidEasy",
                { id -> "https://www.2embed.cc/embed/$id" },
                { id, s, e -> "https://www.2embed.cc/embedtv/$id&s=$s&e=$e" }),
            // ── Other verified hosts (kept for breadth) ────────────────────
            VidHost("MultiEmbed",
                { id -> "https://multiembed.mov/?video_id=$id&tmdb=1" },
                { id, s, e -> "https://multiembed.mov/?video_id=$id&tmdb=1&s=$s&e=$e" }),
            VidHost("SuperEmbed",
                { id -> "https://getsuperembed.link/?video_id=$id" },
                { id, s, e -> "https://getsuperembed.link/?video_id=$id&season=$s&episode=$e" }),
            VidHost("DatabaseGdrive",
                { id -> "https://databasegdriveplayer.co/player.php?imdb=$id" },
                { id, s, e -> "https://databasegdriveplayer.co/player.php?type=series&imdb=$id&season=$s&episode=$e" }),
            VidHost("VidAPI",
                { id -> "https://vidapi.ru/embed/movie/$id" },
                { id, s, e -> "https://vidapi.ru/embed/tv/$id/$s/$e" }),
            VidHost("VAPlayer",
                { id -> "https://vaplayer.ru/embed/movie/$id" },
                { id, s, e -> "https://vaplayer.ru/embed/tv/$id/$s/$e" }),
            VidHost("ApiPlayer",
                { id -> "https://apiplayer.ru/embed/movie/$id" },
                { id, s, e -> "https://apiplayer.ru/embed/tv/$id/$s/$e" }),
            // Anime-specific
            VidHost("AllManga",
                { id -> "https://allmanga.to/manga/$id" },
                { id, _, e -> "https://allmanga.to/streaming/anicdn.php?anime_id=$id&ep=$e" }),
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Main pages
    // ═══════════════════════════════════════════════════════════════════════

    private val defaultMainPages = mainPageOf(
        "trending" to "Trending Anime",
        "airing" to "Airing Now (Ongoing Series)",
        "popular" to "Popular This Season",
        "top" to "Top Rated Anime",
        "upcoming" to "Upcoming (Next Season)",
        "alltime" to "All-Time Popular",
    )

    // (v45) Personal AniList rows — they appear ONLY while the user is
    // logged into AniList inside Cloudstream (Settings → Accounts). The
    // getter is evaluated on every home open, so logging in or out takes
    // effect without a restart.
    private val userMainPages = mainPageOf(
        "my_watching" to "⏯ Watching — My AniList",
        "my_planning" to "📋 Plan to Watch — My AniList",
    )

    override val mainPage: List<MainPageData>
        get() = if (anilistAuthToken() != null || anilistLoginName() != null) {
            userMainPages + defaultMainPages
        } else {
            defaultMainPages
        }

    /** Access token for the Cloudstream-logged-in AniList account (null
     *  when logged out). Reads the app's account store via SyncRepo — the
     *  repo-based auth layer exists only in Cloudstream ≥ 4.8, so on older
     *  app builds the class is absent, NoClassDefFoundError is caught here,
     *  and we fall back to the username path (see below). */
    private fun anilistAuthToken(): String? = runCatching {
        SyncRepo(AccountManager.aniListApi).authToken()?.accessToken
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** (v46) App-generation-proof login detection. `loginInfo()` exists in
     *  BOTH the pre-4.8 auth layer and the current one (compile-time
     *  deprecated, but present at runtime) — invoked via reflection so this
     *  compiles against either stub. Returns the AniList username when
     *  logged in, else null. A username lets us fetch the list through
     *  AniList's PUBLIC profile endpoint — no token needed for the
     *  (default-public) list. */
    private fun anilistLoginName(): String? = runCatching {
        val api = AccountManager.aniListApi
        val info = api.javaClass.getMethod("loginInfo").invoke(api)
            ?: return@runCatching null
        (info.javaClass.getMethod("getName").invoke(info) as? String)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // (v45) Personal AniList rows — served from the logged-in account.
        if (request.data == "my_watching" || request.data == "my_planning") {
            return myAnilistPage(page, request)
        }
        val perPage = 30
        val cfg: PageCfg = when (request.data) {
            "trending" -> PageCfg(sort = listOf("TRENDING_DESC"))
            "airing" -> PageCfg(sort = listOf("POPULARITY_DESC"), status = "RELEASING")
            "popular" -> currentSeasonFilter().let { PageCfg(sort = listOf("POPULARITY_DESC"), season = it.first, seasonYear = it.second) }
            "top" -> PageCfg(sort = listOf("SCORE_DESC"))
            "upcoming" -> nextSeasonFilter().let { PageCfg(sort = listOf("POPULARITY_DESC"), season = it.first, seasonYear = it.second, status = "NOT_YET_RELEASED") }
            "alltime" -> PageCfg(sort = listOf("POPULARITY_DESC"))
            else -> PageCfg(sort = listOf("TRENDING_DESC"))
        }

        val variables = JSONObject().apply {
            put("page", page); put("perPage", perPage)
            put("sort", JSONArray(cfg.sort))
            put("type", "ANIME")
            cfg.season?.let { put("season", it); put("seasonYear", cfg.seasonYear) }
            cfg.status?.let { put("status", it) }
        }
        val query = """
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: [MediaSort], ${'$'}season: MediaSeason, ${'$'}seasonYear: Int, ${'$'}status: MediaStatus, ${'$'}type: MediaType) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage total }
                media(sort: ${'$'}sort, season: ${'$'}season, seasonYear: ${'$'}seasonYear, status: ${'$'}status, type: ${'$'}type, isAdult: false) {
                  id idMal title { romaji english native }
                  coverImage { extraLarge large }
                  bannerImage
                  episodes format season seasonYear
                  averageScore
                  genres
                  startDate { year }
                  status
                }
              }
            }
        """.trimIndent()
        val resp = anilistQuery(query, variables)
        val media = resp?.optJSONObject("Page")?.optJSONArray("media") ?: JSONArray()
        val hasNext = resp?.optJSONObject("Page")?.optJSONObject("pageInfo")?.optBoolean("hasNextPage") == true
        val items = (0 until media.length()).mapNotNull { i ->
            val m = media.optJSONObject(i) ?: return@mapNotNull null
            mediaToSearch(m)
        }
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), hasNext)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Search
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val variables = JSONObject().apply {
            put("page", 1); put("perPage", 30); put("search", q); put("type", "ANIME")
        }
        val gql = """
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}search: String, ${'$'}type: MediaType) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(search: ${'$'}search, type: ${'$'}type, isAdult: false) {
                  id idMal title { romaji english native }
                  coverImage { extraLarge large }
                  bannerImage episodes format averageScore
                  genres startDate { year } status
                }
              }
            }
        """.trimIndent()
        val resp = anilistQuery(gql, variables) ?: return emptyList()
        val media = resp.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()
        return (0 until media.length()).mapNotNull { i ->
            media.optJSONObject(i)?.let { mediaToSearch(it) }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Load
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val id = parseAnilistUrl(url)
            ?: throw ErrorLoadingException("Invalid AniList URL: $url")

        val detail = fetchAniDetail(id)
            ?: throw ErrorLoadingException("Could not load AniList media $id")

        // (v58) PURE-AniList module behaves like a normal AniList client:
        // whichever entry was opened is exactly what the page shows — its
        // own title, its own episodes (entry-local numbering), its own
        // tracking ids. The franchise walk below still runs, but only to
        // compute the invisible site-season offset every episode row
        // carries in its LinkContext (so CircleFTP-style merged season
        // packs — e.g. the site's "AoT Season 3 = 22" — resolve correctly
        // even from a Part-2 entry page).
        val title = detail.title
        val episodes = detail.episodes
        val type = when (detail.format) {
            "MOVIE" -> if (episodes <= 1) TvType.AnimeMovie else TvType.Anime
            // (v59) Story-special entries (AoT's "Kanketsu-hen Zenpen" =
            // The Final Chapters 1) must keep a PER-ENTRY episode page so
            // their rows ride the recursively-attached stacked numbers
            // (S4E29) down to the CircleFTP season tab — as a "Movie" row
            // there'd be no season/episode to map.
            "SPECIAL" -> TvType.Anime
            "OVA", "ONA" -> TvType.OVA
            "MUSIC" -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        val imdbId = detail.imdbId
        val tmdbId = detail.tmdbId

        // (v60) Stacked-season episode totals per this chain — needed to
        // prove an AniList streaming feed is season-aligned before slicing
        // it (see the ep-row notes below).
        val seasonTotals = detail.members.groupBy { it.siteSeason }
            .mapValues { (_, ms) -> ms.sumOf { m -> m.episodes } }

        val epList = when (type) {
            TvType.AnimeMovie -> listOf(newEpisode(LinkContext(
                anilistId = id, imdbId = imdbId, tmdbId = tmdbId, malId = detail.malId,
                season = null, episode = null, title = title, altTitle = detail.altTitle,
                isMovie = true, year = detail.year,
                franchiseTitles = detail.franchiseTitles,
                dub = DubStatus.Subbed,
            ).toJson()) { name = "Movie" })
            else -> detail.members
                // (v58) Per-entry pages: only the opened entry's own
                // episodes are displayed (entry-local numbering, like any
                // AniList client). The STACKED season/episode still ride
                // in the LinkContext — BDIX season packs need them.
                .filter { it.id == id }
                .flatMap { m ->
                // (v58) Display = entry-local; data = stacked. The visible
                // row numbers/titles follow the opened AniList entry, while
                // every row's LinkContext ALSO carries the STACKED
                // season+episode (cours parts continue their parent
                // season's numbering — Part 2 ep 1 = packed Season-3 ep 13)
                // for the BDIX season packs, PLUS the owning entry's id +
                // entry-local episode for the anime-web sources.
                (1..m.episodes).map { localEp ->
                    val stackedEp = m.seasonStart + localEp - 1
                    // (v59) Episode meta fills (with the user's blessing)
                    // from the catalogue CircleFTP mirrors — the recursive
                    // WizEpisodeTable resolves this stacked slot to TMDB's
                    // canon row, cours folds and tail-absorbed specials
                    // alike. Catalogue/pages stay 100% AniList.
                    val epMeta = detail.epTable[m.siteSeason]?.get(stackedEp)
                    // (v53, healed v59, HARDENED v60) AniList titles
                    // first — but only feeds whose shape PROVES alignment
                    // with this entry's rows:
                    //   • entry-scoped (±3): use as-is;
                    //   • exactly the stacked-season total (v59's cours
                    //     case: a genuine 22-title Season-3 feed on the
                    //     Part-2 entry): slice the entry's window.
                    // The v59 window accepted ANY long list — but AoT's
                    // entries carry Crunchyroll's SERIES-wide feed (S1E1
                    // at index 0), so Season-3 pages printed Season-1
                    // titles ("Primal Desires" on S3 Part 2). Those get
                    // rejected now and the shared episode table's title
                    // (correct per stacked slot) shows instead.
                    val sEpList = m.streamEps.let { list ->
                        val seasonTotal = seasonTotals[m.siteSeason] ?: m.episodes
                        when {
                            list.size <= m.episodes + 3 -> list
                            list.size == seasonTotal ->
                                list.subList(m.seasonStart - 1, m.seasonStart - 1 + m.episodes)
                            else -> emptyList()
                        }
                    }
                    val sEp = sEpList.getOrNull(localEp - 1)
                    newEpisode(LinkContext(
                        anilistId = m.id, imdbId = imdbId, tmdbId = tmdbId, malId = m.malId,
                        season = m.siteSeason, episode = stackedEp, entryEpisode = localEp,
                        title = m.title, altTitle = m.altTitle,
                        isMovie = false, year = detail.year,
                        sourceSeason = m.siteSeason,
                        franchiseTitles = detail.franchiseTitles,
                        tmdbSeason = epMeta?.tmdbSeason,
                        tmdbEpisode = epMeta?.tmdbEpisode,
                        dub = DubStatus.Subbed,
                    ).toJson()) {
                        name = sEp?.title ?: epMeta?.name ?: "Episode $localEp"
                        season = 1
                        episode = localEp
                        posterUrl = sEp?.thumb ?: epMeta?.stillUrl ?: detail.posterUrl
                        description = epMeta?.overview
                        runCatching { epMeta?.score?.let { score = Score.from10(it) } }
                        runTime = epMeta?.runtime
                        this.date = epMeta?.airDate
                    }
                }
            }
        }

        val recs = detail.recommendations.mapNotNull { m -> mediaToSearch(m) }

        return if (type == TvType.AnimeMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, epList.first().data) {
                this.posterUrl = detail.posterUrl
                this.backgroundPosterUrl = detail.backdropUrl
                this.plot = detail.plot
                this.year = detail.year
                this.tags = detail.tags
                this.recommendations = recs
                runCatching { detail.rating?.let { score = Score.from10(it) } }
                runCatching { detail.actors?.let { this.actors = it } }
                runCatching { detail.trailerUrl?.let { addTrailer(it) } }
                runCatching { detail.logoUrl?.let { this.logoUrl = it } }
                runCatching { imdbId?.let { addImdbId(it) } }
                runCatching { detail.malId?.let { addMalId(it) } }
                runCatching { detail.kitsuId?.let { addKitsuId(it) } }
                runCatching { addAniListId(id) }
                runCatching { detail.simklId?.let { addSimklId(it) } }
            }
        } else {
            newAnimeLoadResponse(title, url, type) {
                this.posterUrl = detail.posterUrl
                this.backgroundPosterUrl = detail.backdropUrl
                this.plot = detail.plot
                this.year = detail.year
                this.tags = detail.tags
                this.recommendations = recs
                runCatching { detail.rating?.let { score = Score.from10(it) } }
                runCatching { detail.actors?.let { this.actors = it } }
                runCatching { detail.trailerUrl?.let { addTrailer(it) } }
                runCatching { detail.logoUrl?.let { this.logoUrl = it } }
                runCatching { imdbId?.let { addImdbId(it) } }
                runCatching { detail.malId?.let { addMalId(it) } }
                runCatching { detail.kitsuId?.let { addKitsuId(it) } }
                addAniListId(id)
                runCatching { detail.simklId?.let { addSimklId(it) } }
                addEpisodes(DubStatus.Subbed, epList)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Links
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        val ctx = try { LinkContext.fromJson(data) } catch (_: Exception) { null }
            ?: return@coroutineScope false

        val idList = buildList {
            ctx.imdbId?.let { add(it) }
            ctx.tmdbId?.let { add(it.toString()) }
            add(ctx.anilistId.toString())
        }.distinct()

        val seenUrls = Collections.newSetFromMap<String>(ConcurrentHashMap())
        val seenSubs = Collections.newSetFromMap<String>(ConcurrentHashMap())
        val gate = Semaphore(8)
        var anyFound = false

        val jobs = VID_HOSTS.flatMap { host ->
            idList.map { id ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        // (v48) ctx already carries STACKED numbering.
                        // (v59) …but a tail-absorbed story-special ALSO
                        // carries its canonical TMDB location (stacked
                        // S4E29 = TMDB S0E36): TMDB-indexed embed hosts
                        // must be pointed there, BDIX/anime-web resolvers
                        // keep reading the stacked rows.
                        val seasonForSources = ctx.tmdbSeason ?: ctx.sourceSeason ?: ctx.season
                        val episodeForHost = ctx.tmdbEpisode ?: ctx.episode
                        val embedUrl = if (ctx.isMovie || seasonForSources == null || episodeForHost == null) {
                            host.movie(id)
                        } else {
                            host.tv(id, seasonForSources, episodeForHost)
                        }
                        try {
                            val before = anyFound
                            val embedReferer = runCatching {
                                "https://" + java.net.URL(embedUrl).host + "/"
                            }.getOrDefault("https://")
                            loadExtractor(
                                embedUrl,
                                embedReferer,
                                { sub ->
                                    if (seenSubs.add(sub.url)) subtitleCallback(sub)
                                }
                            ) { link ->
                                val urlStr = link.url.trim()
                                if (urlStr.isBlank() || !seenUrls.add(urlStr)) return@loadExtractor
                                val newSource = "Wizstream-A • ${host.label}"
                                val newName = "${host.label} — ${link.name}".trimEnd('—', ' ')
                                callback(link.aRelabel(newSource, newName))
                                anyFound = true
                            }
                        } catch (_: Throwable) {
                            // Host is probably down or blocked — skip silently.
                        }
                    }
                }
            }
        }

        // ── Bundled BDIX source resolvers ────────────────────────────────
        // AniList anime often has English-romaji titles that the BDIX
        // sites (Cineplex BD / FTPBD / Circle FTP / CTGMovies) index
        // reasonably well via their anime categories. WizstreamSources
        // runs all 4 sites in parallel and emits any matches it finds.
        // Pass tmdbId+imdbId so CinebyResolver can call the Cineby API
        // (which requires a TMDB ID for its /seed endpoint).
        val sourceJob = async(Dispatchers.IO) {
            runCatching {
                WizstreamSources.resolveAll(
                    app = app,
                    title = ctx.title ?: "",
                    year = ctx.year,
                    isMovie = ctx.isMovie,
                    // (v48) STACKED numbering — exactly how CircleFTP mega
                    // posts and TMDB file the episodes.
                    season = ctx.sourceSeason ?: ctx.season,
                    episode = ctx.episode,
                    labelPrefix = "Wizstream-A",
                    subtitleCallback = { sub ->
                        if (seenSubs.add(sub.url)) subtitleCallback(sub)
                    },
                    callback = { link ->
                        val normalized = link.url.trim()
                        if (normalized.isNotBlank() && seenUrls.add(normalized)) {
                            callback(link)
                            anyFound = true
                        }
                    },
                    tmdbId = ctx.tmdbId,
                    imdbId = ctx.imdbId,
                    altTitle = ctx.altTitle,
                    // (v45) franchise-root titles from the prequel walk —
                    // the search key that actually exists on BDIX sites
                    // for sequel-season entries.
                    extraAltTitles = ctx.franchiseTitles ?: emptyList(),
                    // (v60) Gated fallback for sites that post cours splits
                    // as separate one-bucket posts: stacked 13-22 can't
                    // land there; the entry's own 1-10 can.
                    entryEpisode = ctx.entryEpisode,
                )
            }.getOrDefault(false)
        }

        // ── Anime-focused source resolvers ──────────────────────────────
        // AniZone, Allmanga (AllAnime-family persisted API), AniChi
        // (via mapper.nekostream.site). These are dedicated anime streaming
        // sites that complement the BDIX sources above. AniChi uses the MAL
        // ID directly via the mapper API — no search needed.
        val animeSourceJob = async(Dispatchers.IO) {
            runCatching {
                WizstreamAnimeSources.resolveAnime(
                    app = app,
                    title = ctx.title ?: "",
                    altTitle = ctx.altTitle,
                    // (v48) OWNING entry's ids/titles + its ENTRY-LOCAL
                    // episode — anime-web sites mirror AniList's split
                    // entries ("AoT S3 Part 2" is its own show there with
                    // episodes 1..10), so they must NOT see stacked
                    // numbering. `season` keeps its historical meaning of
                    // "the entry's own season" (1) since these sites file
                    // each entry as a single show.
                    anilistId = ctx.anilistId,
                    malId = ctx.malId,
                    isMovie = ctx.isMovie,
                    season = 1,
                    episode = ctx.entryEpisode ?: ctx.episode,
                    labelPrefix = "Wizstream-A",
                    subtitleCallback = { sub ->
                        if (seenSubs.add(sub.url)) subtitleCallback(sub)
                    },
                    callback = { link ->
                        val normalized = link.url.trim()
                        if (normalized.isNotBlank() && seenUrls.add(normalized)) {
                            callback(link)
                            anyFound = true
                        }
                    },
                )
            }.getOrDefault(false)
        }

        jobs.awaitAll()
        sourceJob.await()
        animeSourceJob.await()
        anyFound
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  AniList + mapping helpers
    // ═══════════════════════════════════════════════════════════════════════

    private data class AniDetail(
        val title: String,
        val altTitle: String? = null,
        val year: Int?,
        val plot: String?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val logoUrl: String?,
        val rating: Double?,
        val tags: List<String>?,
        val imdbId: String?,
        val tmdbId: Int?,
        val malId: Int?,
        val simklId: Int?,
        val kitsuId: String?,
        val episodes: Int,
        val format: String?,
        val actors: List<ActorData>?,
        val trailerUrl: String?,
        // (v48) The stacked franchise: every chain member root→leaf with
        // its siteSeason/seasonStart resolved (single-entry shows carry
        // just the opened member — the page renders exactly as before).
        val members: List<FranchiseMember> = emptyList(),
        // (v59) Recursive episode-table rows per STACKED season →
        // stacked ep (replaces v48's season-meta map; same catalogue
        // CircleFTP mirrors, site-tail specials included).
        val epTable: Map<Int, Map<Int, WizEpisodeTable.EpRow>> = emptyMap(),
        val recommendations: List<JSONObject>,
        // (v45) English+romaji titles of every counted prequel ancestor,
        // walk order (franchise root LAST). BDIX sites file multi-season
        // anime under the root title — these are the resolver search keys
        // that fix sequel-entry resolution.
        val franchiseTitles: List<String> = emptyList(),
    )

    private fun parseAnilistUrl(url: String): Int? {
        // Accept every form this extension has ever emitted or stored:
        //   wiz://anilist/<id>                               (≤v51)
        //   https://anilist.co/anime/<id>                    (v52+)
        //   https://anilist.co/wiz://anilist/<id>            (app-side
        //     fixUrl artifact seen on v51 error screens)
        val m = Regex("wiz://anilist/(\\d+)").find(url)
            ?: Regex("/anime/(\\d+)").find(url)
            ?: Regex("anilist/(\\d+)").find(url)
            ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun mediaToSearch(m: JSONObject): SearchResponse? {
        val id = m.optInt("id", 0).takeIf { it != 0 } ?: return null
        val titles = m.optJSONObject("title")
        val title = titles?.aOptStr("english")
            ?: titles?.aOptStr("romaji")
            ?: titles?.aOptStr("native")
            ?: return null
        val cover = m.optJSONObject("coverImage")?.aOptStr("extraLarge")
            ?: m.optJSONObject("coverImage")?.aOptStr("large")
        val format = m.aOptStr("format") ?: "TV"
        val year = m.optJSONObject("startDate")?.optInt("year")?.takeIf { it != 0 }
        val tvType = when {
            format == "MOVIE" -> TvType.AnimeMovie
            format == "OVA" || format == "ONA" -> TvType.OVA
            else -> TvType.Anime
        }
        // (v52) Real anilist.co URLs: the app resolves clicked cards by
        // provider URL-prefix too (fallback when the name lookup fails),
        // which wiz:// custom-scheme URLs could never satisfy.
        return when (tvType) {
            TvType.AnimeMovie -> newMovieSearchResponse(title, "https://anilist.co/anime/$id", TvType.AnimeMovie) {
                this.posterUrl = cover; this.year = year
            }
            TvType.OVA -> newAnimeSearchResponse(title, "https://anilist.co/anime/$id", TvType.OVA) {
                this.posterUrl = cover; this.year = year
            }
            else -> newAnimeSearchResponse(title, "https://anilist.co/anime/$id", TvType.Anime) {
                this.posterUrl = cover; this.year = year
            }
        }
    }

    // (v59) Cached ani.zip id-map lookup (TMDB show id) for FRANCHISE
    // entries other than the opened one — the opened entry's own lookup
    // is inline in fetchAniDetail; this exists so a cours part / special
    // without a mapping of its own can still reach the show's TMDB id
    // via the franchise root (roots are the reliably-mapped entries).
    private val aniZipTmdbCache = HashMap<Int, Int?>()
    private suspend fun fetchAniZipTmdbId(anilistId: Int): Int? {
        if (aniZipTmdbCache.containsKey(anilistId)) return aniZipTmdbCache[anilistId]
        val found = runCatching {
            val text = app.get("https://api.ani.zip/mappings?anilist_id=$anilistId",
                headers = mapOf("User-Agent" to A_UA), timeout = 8000).text
            JSONObject(text).optJSONObject("mappings")
                ?.aOptStr("themoviedb_id")?.toIntOrNull()
        }.getOrNull()
        aniZipTmdbCache[anilistId] = found
        return found
    }

    // (v61) Per-entry-correct Kitsu id — resolved through Kitsu's OWN
    // external-id mapping, keyed by the entry's authoritative MAL id (the
    // AniList idMal field). One request, cached by malId; the Kitsu
    // include=item inlines the anime resource, so no follow-up hop.
    // Returns null when Kitsu simply has no mapping for the entry.
    private val kitsuByMalCache = HashMap<Int, String?>()
    private suspend fun fetchKitsuIdByMal(malId: Int): String? {
        if (malId <= 0) return null
        if (kitsuByMalCache.containsKey(malId)) return kitsuByMalCache[malId]
        val found = runCatching {
            val url = "https://kitsu.io/api/edge/mappings" +
                "?filter%5BexternalSite%5D=myanimelist%2Fanime" +
                "&filter%5BexternalId%5D=$malId&include=item"
            val text = app.get(url, headers = mapOf(
                "User-Agent" to A_UA,
                "Accept" to "application/vnd.api+json",
            ), timeout = 8000).text
            val included = JSONObject(text).optJSONArray("included")
            included?.let { arr ->
                (0 until arr.length())
                    .mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { it.optString("type") == "anime" }
                    ?.optString("id")?.takeIf { s -> s.isNotBlank() }
            }
        }.getOrNull()
        kitsuByMalCache[malId] = found
        return found
    }

    // (v61) Viewer id for logged-in list fetching (MediaListCollection
    // rejects userName:null, so the token path needs userId).
    private suspend fun fetchViewerId(token: String): Int? = runCatching {
        anilistQuery("query { Viewer { id } }", JSONObject(), token)
            ?.optJSONObject("Viewer")?.optInt("id", 0)?.takeIf { it != 0 }
    }.getOrNull()

    private suspend fun fetchAniDetail(id: Int): AniDetail? {
        val now = System.currentTimeMillis()
        META_CACHE[id]?.let { (ts, cached) ->
            if (now - ts < CACHE_TTL_MS) return cached
        }

        val gql = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id idMal
                title { romaji english native userPreferred }
                coverImage { extraLarge large }
                bannerImage
                description(asHtml: false)
                averageScore meanScore
                genres tags { name }
                episodes duration format status season seasonYear
                nextAiringEpisode { episode }
                streamingEpisodes { title thumbnail }
                startDate { year month day }
                endDate { year month day }
                trailer { id site }
                characters(sort: [ROLE, RELEVANCE], perPage: 25) {
                  edges {
                    role
                    node { id name { full } image { large } }
                    voiceActorsJapanese: voiceActors(language: JAPANESE, sort: [RELEVANCE]) { name { full } image { large } }
                  }
                }
                relations { edges { node { id type format episodes idMal title { english romaji native } coverImage { large } streamingEpisodes { title thumbnail } } relationType } }
                recommendations(sort: [RATING_DESC], perPage: 15) {
                  nodes { mediaRecommendation { id idMal title { romaji english native } coverImage { extraLarge large } bannerImage episodes format averageScore genres startDate { year } status } }
                }
              }
            }
        """.trimIndent()
        val variables = JSONObject().put("id", id)
        val resp = anilistQuery(gql, variables) ?: return null
        val media = resp.optJSONObject("Media") ?: return null

        val titles = media.optJSONObject("title")
        val title = titles?.aOptStr("english")
            ?: titles?.aOptStr("romaji")
            ?: titles?.aOptStr("native")
            ?: return null
        // (v29) The best *alternative* title for source-site searches.
        // Romaji first — the AllAnime family ("Sousou no Frieren", "1P")
        // indexes romaji while our primary title is English.
        val altTitle = sequenceOf(
            titles?.aOptStr("romaji"),
            titles?.aOptStr("english"),
            titles?.aOptStr("userPreferred"),
            titles?.aOptStr("native"),
        ).filterNotNull().firstOrNull { !it.equals(title, ignoreCase = true) }
        val cover = media.optJSONObject("coverImage")?.aOptStr("extraLarge")
            ?: media.optJSONObject("coverImage")?.aOptStr("large")
        val banner = media.aOptStr("bannerImage")
        val plot = media.aOptStr("description")
            ?.replace(Regex("<[^>]+>"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        val score = media.optInt("averageScore", 0).takeIf { it > 0 }?.let { it / 10.0 }
        // (v28) AniList's `episodes` is NULL for long-running airing shows
        // (One Piece, Detective Conan, …) — the old `?: 12` silently cut
        // those to a single cour. Take the best-known total from every
        // signal AniList offers: final count, next airing episode − 1, or
        // the streamingEpisodes list length; only then fall back to 12.
        // (v47) …but the streamingEpisodes list is NOT entry-scoped: for
        // split cours (AoT S3 = 12 eps) Hulu/Crunchyroll attach the WHOLE
        // temporada's 25-episode list, and maxOf() then inflated the page
        // to 25 rows ("episode count stayed 25"). AniList's own total is
        // authoritative whenever it exists; the fallbacks are ONLY for
        // shows whose final count AniList genuinely doesn't know yet.
        val anilistTotal = media.aOptInt("episodes")?.takeIf { it > 0 } ?: 0
        val nextAiring = media.optJSONObject("nextAiringEpisode")
            ?.aOptInt("episode")?.minus(1) ?: 0
        val streamingCount = media.optJSONArray("streamingEpisodes")?.length() ?: 0
        val episodes = if (anilistTotal > 0) anilistTotal
            else maxOf(nextAiring, streamingCount, 12)
        val format = media.aOptStr("format")
        val year = media.optJSONObject("startDate")?.optInt("year")?.takeIf { it != 0 }
        val genres = media.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { it.isNotBlank() } }
        }
        val trailerObj = media.optJSONObject("trailer")
        val trailerUrl = trailerObj?.aOptStr("site")
            ?.takeIf { it.equals("youtube", true) }
            ?.let { "https://www.youtube.com/watch?v=${trailerObj.optString("id")}" }

        var imdbId: String? = null
        var tmdbId: Int? = null
        var kitsuId: String? = null
        runCatching {
            val text = app.get("https://api.ani.zip/mappings?anilist_id=$id",
                headers = mapOf("User-Agent" to A_UA), timeout = 8000).text
            val mapJson = JSONObject(text)
            val mappings = mapJson.optJSONObject("mappings")
            imdbId = mappings?.aOptStr("imdb_id")
            tmdbId = mappings?.aOptStr("themoviedb_id")?.toIntOrNull()
            kitsuId = mappings?.aOptStr("kitsu_id")
        }

        // (v55, PURE ANILIST) AniList itself supplies all metadata — the
        // ani.zip call above is only an ID-MAP for the link engines
        // (imdb/tmdb/kitsu ids), never a TMDB metadata fetch. The old
        // imdb-id-via-TMDB fallback fetch is intentionally gone.

        var backdropUrl: String? = banner
        // (v58) Title logo without TMDB: MetaHub's logo CDN, keyed by the
        // IMDb id from the ani.zip id-map. Not every title has one — when
        // MetaHub has none, the app simply shows no logo.
        var logoUrl: String? =
            imdbId?.let { "https://live.metahub.space/logo/medium/$it/img" }
        // Cast data — extract from AniList (characters + voice actors).
        // TMDB credits are NOT used for anime cast because the user wants
        // anime cast (voice actors + characters) which TMDB doesn't have.
        var actors: List<ActorData>? = null
        var simklId: Int? = null
        val recs = mutableListOf<JSONObject>()
        media.optJSONObject("recommendations")?.optJSONArray("nodes")?.let { nodes ->
            for (i in 0 until nodes.length()) {
                nodes.optJSONObject(i)?.optJSONObject("mediaRecommendation")?.let(recs::add)
            }
        }

        // ── Extract cast from AniList (NOT TMDB) ──────────────────────────
        // AniList's `characters.edges[]` provides:
        //   • node.id / node.name.full / node.image.large — character
        //   • voiceActorsJapanese[0]         — best-match Japanese VA
        // Cloudstream ActorData: actor = character (main avatar),
        // voiceActor = Japanese voice actor (secondary avatar).
        // (v31) 25 MAIN+SUPPORTING characters.
        // (v45) JAPANESE-CAST-ONLY, per user request: no English VA is
        //   fetched or displayed. Each character renders EXACTLY ONE card
        //   (repeated AniList edges merged by node id) with the JA VA as
        //   dual avatar; characters with no JA VA on AniList still get
        //   their character card (no VA avatar) — never an EN substitute.
        actors = media.optJSONObject("characters")?.optJSONArray("edges")?.let { edges ->
            class CharAgg(
                val name: String,
                val image: String?,
                var role: String,
                var jaVa: Actor?,
            )
            val byChar = LinkedHashMap<String, CharAgg>()
            for (i in 0 until edges.length()) {
                val edge = edges.optJSONObject(i) ?: continue
                val node = edge.optJSONObject("node") ?: continue
                val charName = node.optJSONObject("name")?.aOptStr("full") ?: continue
                val charId = node.optInt("id", 0)
                val key = if (charId > 0) "id:$charId" else "nm:" + charName.lowercase()
                val agg = byChar.getOrPut(key) {
                    CharAgg(
                        charName,
                        node.optJSONObject("image")?.aOptStr("large"),
                        edge.aOptStr("role")?.lowercase()
                            ?.replaceFirstChar { it.uppercase() } ?: "Main",
                        null,
                    )
                }
                if (agg.role != "Main") {
                    val r = edge.aOptStr("role")?.lowercase()
                        ?.replaceFirstChar { it.uppercase() }
                    if (r == "Main") agg.role = r
                }
                if (agg.jaVa == null) {
                    edge.optJSONArray("voiceActorsJapanese")?.optJSONObject(0)?.let { va ->
                        va.optJSONObject("name")?.aOptStr("full")?.let { vaName ->
                            agg.jaVa = Actor(vaName, va.optJSONObject("image")?.aOptStr("large"))
                        }
                    }
                }
            }
            val out = mutableListOf<ActorData>()
            byChar.values.forEach { agg ->
                out += if (agg.jaVa == null) {
                    ActorData(actor = Actor(agg.name, agg.image), roleString = agg.role)
                } else {
                    ActorData(
                        actor = Actor(agg.name, agg.image),
                        roleString = agg.role,
                        voiceActor = agg.jaVa,
                    )
                }
            }
            out.ifEmpty { null }
        }

        // (v48) ── Stacked franchise assembly ("like CircleFTP") ──────────
        // CircleFTP files a multi-season anime as ONE page with site
        // seasons ("Attack on Titan (2013-) — Season 1..4", cours parts
        // merged into their parent pack: Season 3 holds all 22 episodes).
        // Wizstream-Anime now builds the SAME shape: walk the AniList
        // prequel chain to the root, walk the sequel chain forward, fold
        // cours parts ("… Part 2") into their parent stacked season, and
        // emit ONE stacked page. Every row carries the STACKED (site/TMDB)
        // season+episode for BDIX/TMDB/embed lookups PLUS the owning
        // AniList entry's id + entry-local episode for the anime-web
        // sources (those mirror AniList's per-entry split). Single-entry
        // shows degrade gracefully to their plain one-season page.
        val openedMember = FranchiseMember(
            id = id,
            title = title,
            altTitle = altTitle,
            episodes = episodes,
            malId = media.optInt("idMal", 0).takeIf { it != 0 },
            format = format,
            streamEps = media.toStreamEps(),
        )
        val (members, franchiseTitles) = if (format == "MOVIE") {
            listOf(openedMember) to emptyList()
        } else {
            franchiseChain(
                id, media.optJSONObject("relations")?.optJSONArray("edges"), openedMember
            )
        }
        // Fold into stacked (site-style) seasons. A cours part joins the
        // season its direct prequel opened and continues its numbering
        // (AoT S3=12 rows, S3 Part 2 continues at 13 → stacked 22).
        // (v59) Recursive tail-attach for story-special members (and for
        // the opened entry itself when it's a special): they fold INTO
        // the season they follow, continuing its numbering sequentially —
        // "Kanketsu-hen Zenpen" → S4E29, "Kouhen" → S4E30, exactly the
        // rows CircleFTP's Season-4 tab ends with and TMDB's canonical
        // map (S0E36/E37) holds the metadata for. Generalizes to every
        // anime whose chain ends in long-form specials.
        var seasonCounter = 0
        var nextStart = 1
        members.forEach { m ->
            when {
                m.format !in franchiseBroadcastFormats -> {
                    if (seasonCounter == 0) {
                        seasonCounter = 1
                        m.siteSeason = 1
                        m.seasonStart = 1
                    } else {
                        m.siteSeason = seasonCounter
                        m.seasonStart = nextStart
                    }
                }
                titlePartNumber(m.title) == null || seasonCounter == 0 -> {
                    seasonCounter++
                    m.siteSeason = seasonCounter
                    m.seasonStart = 1
                }
                else -> {
                    m.siteSeason = seasonCounter
                    m.seasonStart = nextStart
                }
            }
            nextStart = m.seasonStart + m.episodes
        }

        // (v59, per user decision) EPISODE meta fills from the catalogue
        // CircleFTP mirrors: the recursive WizEpisodeTable maps every
        // stacked (siteSeason, stackedEp) — cours folds AND tail-absorbed
        // story-specials alike — onto TMDB's canon row, carrying real
        // titles, descriptions, stills, runtimes, air dates. Everything
        // else here stays 100% AniList (this table touches ONLY the
        // per-episode fields AniList fundamentally can't answer — AniList
        // exposes no per-episode description). The show's TMDB id comes
        // from the ani.zip id-map of the opened entry, falling back to
        // the franchise-root entry's map (roots are the reliably-mapped
        // entries; cour parts/specials often have none).
        val showTmdbId = tmdbId?.takeIf { it > 0 }
            ?: members.firstOrNull()?.let { root -> fetchAniZipTmdbId(root.id) }
        val showTable = if (format == "MOVIE") null
        else showTmdbId?.let { id ->
            runCatching { WizEpisodeTable.table(app, id) }.getOrNull()
        }
        val epTable: Map<Int, Map<Int, WizEpisodeTable.EpRow>> =
            showTable?.seasons ?: emptyMap()
        // (v61) High-quality LANDSCAPE art: AniList's banner stays first
        // (it's the AniList asset), but most entries simply don't have
        // one — the header then renders empty. The shared mapper already
        // fetched the TMDB show page; its w1280 backdrop fills the gap.
        if (backdropUrl == null) backdropUrl = showTable?.backdropUrl
        // (v61) Official TITLE LOGO from TMDB's images (fetched inside
        // the same mapper call) beats the MetaHub guess; MetaHub stays as
        // last resort for titles TMDB can't logo.
        showTable?.logoUrl?.let { logoUrl = it }

        val malId = media.optInt("idMal", 0).takeIf { it != 0 }

        val resolvedKitsuId = malId?.let { fetchKitsuIdByMal(it) }
            ?: kitsuId.takeIf { members.size <= 1 }

        val detail = AniDetail(
            title = title,
            altTitle = altTitle,
            year = year,
            plot = plot,
            posterUrl = cover,
            backdropUrl = backdropUrl,
            logoUrl = logoUrl,
            rating = score,
            tags = genres,
            imdbId = imdbId,
            tmdbId = tmdbId,
            malId = malId,
            simklId = simklId,
            // (v61) Per-entry-correct Kitsu id, DETERMINISTIC: resolved
            // through Kitsu's own myanimelist/anime mapping from the
            // entry's authoritative AniList idMal (MAL 38524 → Kitsu
            // 41982 = "Season 3 Part 2", exactly). ani.zip's kitsu_id
            // fuzzily pointed at the franchise ROOT (25-ep Season 1) —
            // the "season 1 tracking data" the app sheet displayed. The
            // app's own getTracker fallback aborts once all three ids
            // exist, so this also stops the app from re-fuzzing it.
            // Root-ani.zip remains only for clean single-entry shows.
            kitsuId = resolvedKitsuId,
            episodes = episodes,
            format = format,
            actors = actors,
            trailerUrl = trailerUrl,
            members = members,
            epTable = epTable,
            recommendations = recs,
            franchiseTitles = franchiseTitles,
        )
        META_CACHE[id] = now to detail
        return detail
    }

    private val franchiseBroadcastFormats = setOf("TV", "TV_SHORT", "ONA")
    private val coursSplitRegex = Regex("""(?i)\b(part|cour)\s*\d+|\bpart\s+[ivxlcdm]+\b""")

    // ═══════════════════════════════════════════════════════════════════════
    //  (v48) Stacked franchise machinery
    // ═══════════════════════════════════════════════════════════════════════

    /** One AniList entry inside the assembled franchise page. */
    private data class FranchiseMember(
        val id: Int,
        val title: String,
        val altTitle: String?,
        val episodes: Int,
        val malId: Int?,
        val format: String?,
        // (v53) AniList streaming-episode rows of THIS entry (title+thumb).
        val streamEps: List<StreamEp> = emptyList(),
        // Stacked-site position, filled in by the caller:
        var siteSeason: Int = 0,
        // First stacked episode position of this member inside siteSeason
        // (cours parts continue where the previous segment stopped:
        // AoT S3 Part 2 → season 3, seasonStart 13).
        var seasonStart: Int = 1,
    )

    /** (v53) One row of AniList's streamingEpisodes (licensed-stream feed
     *  data — Crunchyroll/Hulu) — the ONLY per-episode title/thumbnail
     *  AniList has. Used first on episode rows; TMDB fills gaps. */
    private data class StreamEp(val title: String?, val thumb: String?)

    private fun JSONObject.toStreamEps(): List<StreamEp> =
        optJSONArray("streamingEpisodes")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i)
                StreamEp(o?.aOptStr("title"), o?.aOptStr("thumbnail"))
            }
        } ?: emptyList()

    private data class RelCand(
        val id: Int,
        val title: String,
        val alt: String?,
        val eps: Int,
        val malId: Int?,
        val fmt: String?,
        val streamEps: List<StreamEp> = emptyList(),
    )

    private fun JSONObject.toRelCand(): RelCand {
        val nt = optJSONObject("title")
        val en = nt?.aOptStr("english")
        val ro = nt?.aOptStr("romaji")
        val t = en ?: ro ?: nt?.aOptStr("native") ?: ""
        val alt = listOfNotNull(en, ro).firstOrNull { !it.equals(t, ignoreCase = true) }
        return RelCand(
            id = optInt("id", 0),
            title = t,
            alt = alt,
            eps = optInt("episodes", 0),
            malId = aOptInt("idMal"),
            fmt = aOptStr("format"),
            streamEps = toStreamEps(),
        )
    }

    /** Fetch the relation edges of one entry (one small AniList query). */
    private suspend fun relationsEdges(id: Int): JSONArray? {
        val gql = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                relations { edges { node { id type format episodes idMal title { english romaji native } streamingEpisodes { title thumbnail } } relationType } }
              }
            }
        """.trimIndent()
        val resp = anilistQuery(gql, JSONObject().put("id", id)) ?: return null
        return resp.optJSONObject("Media")?.optJSONObject("relations")?.optJSONArray("edges")
    }

    /** (counted, any-broadcast, bridge) among PREQUEL edges — the counted
     *  one ignores cours parts (the old offset walk's parity); the bridge
     *  is the first prequel of ANY format and is used to traverse OVA/movie
     *  link nodes (AniList chains seasons THROUGH them: Haikyuu S4's
     *  prequel is an OVA, AoT S1's "prequel" is the No-Regrets OVA). */
    private fun JSONArray.pickPrequel(visited: Set<Int>): Triple<RelCand?, RelCand?, RelCand?> {
        var counted: RelCand? = null
        var anyBroadcast: RelCand? = null
        var bridge: RelCand? = null
        for (i in 0 until length()) {
            val e = optJSONObject(i) ?: continue
            if (!e.optString("relationType").equals("PREQUEL", true)) continue
            val node = e.optJSONObject("node") ?: continue
            if (!node.optString("type").equals("ANIME", true)) continue
            val nid = node.optInt("id", 0).takeIf { it != 0 } ?: continue
            if (nid in visited) continue
            val cand = node.toRelCand()
            if (bridge == null) bridge = cand
            if (cand.fmt !in franchiseBroadcastFormats) continue
            if (anyBroadcast == null) anyBroadcast = cand
            if (!coursSplitRegex.containsMatchIn(cand.title)) {
                counted = cand
                break
            }
        }
        return Triple(counted, anyBroadcast, bridge)
    }

    /**
     * (v48) Build the stacked franchise around the opened entry: ordered
     * members ROOT → LEAF with the opened entry in its natural slot, plus
     * the counted prequel ancestors' titles (the v45 BDIX search keys,
     * franchise root LAST). Cours parts are regular members here — the
     * caller folds them into their parent stacked season via
     * titlePartNumber(). Extra hops cost one tiny AniList query each
     * (≤8 prequel + ≤6 sequel; single-entry shows stop immediately).
     */
    private suspend fun franchiseChain(
        id: Int,
        initialEdges: JSONArray?,
        opened: FranchiseMember,
    ): Pair<List<FranchiseMember>, List<String>> {
        val visited = hashSetOf(id)
        val pre = mutableListOf<RelCand>()          // nearest-prequel FIRST
        val rootTitles = mutableListOf<String>()    // counted ancestors, walk order
        var edges = initialEdges
        var hops = 0
        var bridges = 0
        while (hops < 8) {
            hops++
            val (counted, anyBroadcast, bridge) = (edges ?: break).pickPrequel(visited)
            // Only BROADCAST hops become members/search keys; OVA/movie
            // link nodes are traversed as bridges (never added) so the
            // walk can re-attach past them (Haikyuu S4 ← OVA ← S3), but
            // never pollute the season list (AoT S1 ← No Regrets OVA).
            val hop = counted ?: anyBroadcast
                ?: (if (bridges < 3) bridge else null)
                ?: break
            visited += hop.id
            if (hop.fmt in franchiseBroadcastFormats) {
                pre += hop
                if (counted != null && counted.id == hop.id) {
                    listOfNotNull(
                        counted.title.takeIf { it.isNotBlank() }, counted.alt
                    ).forEach { cand ->
                        if (rootTitles.none { it.equals(cand, true) }) rootTitles += cand
                    }
                }
            } else {
                bridges++
            }
            edges = relationsEdges(hop.id) ?: break
        }
        // Forward (sequel) walk from the opened entry: most-episode TV
        // candidate wins when branches appear; same bridge rule for
        // OVA/movie link nodes (Haikyuu S3 → OVA → TO THE TOP).
        val post = mutableListOf<RelCand>()
        // (v59) Recursive story-special absorption (user design): a
        // SEQUEL-chained long-form special — AniList format SPECIAL, like
        // Attack on Titan's "Kanketsu-hen Zenpen/Kouhen" (The Final
        // Chapters 1 & 2, one ~1-hour episode each) — isn't a side-story
        // bridge; it CONTINUES the final TV season and joins the stacked
        // fold right after it (S4E29, then S4E30). OVA/movie link nodes
        // still stay invisible bridges. At most 3 absorb per chain.
        val tailSpecials = mutableListOf<RelCand>()
        var sEdges = initialEdges
        var sHops = 0
        var sBridges = 0
        while (sHops < 6) {
            sHops++
            val arr = sEdges ?: break
            var best: RelCand? = null
            var bestEps = 0
            var sBridge: RelCand? = null
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (!e.optString("relationType").equals("SEQUEL", true)) continue
                val node = e.optJSONObject("node") ?: continue
                if (!node.optString("type").equals("ANIME", true)) continue
                val nid = node.optInt("id", 0).takeIf { it != 0 } ?: continue
                if (nid in visited) continue
                val cand = node.toRelCand()
                if (sBridge == null) sBridge = cand
                if (cand.fmt !in franchiseBroadcastFormats) continue
                if (cand.eps <= 0) continue
                if (best == null || cand.eps > bestEps) {
                    best = cand
                    bestEps = cand.eps
                }
            }
            val hop = best
                ?: (if (sBridges < 3) sBridge else null)
                ?: break
            visited += hop.id
            if (hop.fmt in franchiseBroadcastFormats) {
                post += hop
            } else {
                sBridges++
                // (v59) Sequel-chain story-specials are absorbed as chain
                // members so the fold can tail-attach them sequentially.
                if (hop.fmt == "SPECIAL" && hop.eps in 1..6 && tailSpecials.size < 3) {
                    tailSpecials += hop
                }
            }
            sEdges = relationsEdges(hop.id) ?: break
        }
        val members = ArrayList<FranchiseMember>()
        pre.asReversed().forEach { c ->
            members += FranchiseMember(
                id = c.id, title = c.title, altTitle = c.alt,
                episodes = if (c.eps > 0) c.eps else 12,
                malId = c.malId, format = c.fmt,
                streamEps = c.streamEps,
            )
        }
        members += opened
        post.forEach { c ->
            members += FranchiseMember(
                id = c.id, title = c.title, altTitle = c.alt,
                episodes = if (c.eps > 0) c.eps else 12,
                malId = c.malId, format = c.fmt,
                streamEps = c.streamEps,
            )
        }
        // (v59) Absorbed specials always tail the chain (the fold gives
        // them the continuing stacked numbers — Final Chapters 1 → S4E29,
        // 2 → S4E30 — no matter which entry was opened).
        tailSpecials.forEach { c ->
            members += FranchiseMember(
                id = c.id, title = c.title, altTitle = c.alt,
                episodes = if (c.eps > 0) c.eps else 1,
                malId = c.malId, format = c.fmt,
                streamEps = c.streamEps,
            )
        }
        // Belt & suspenders: relation loops can't double-list an entry.
        val seenIds = HashSet<Int>()
        val deduped = members.filter { seenIds.add(it.id) }
        return deduped to rootTitles
    }
    private val partNumberRegex = Regex(
        """(?i)\b(?:part|cour)\s*(\d{1,2})\b|\bpart\s+([ivxlcdm]{1,5})\b"""
    )

    private fun romanToInt(s: String): Int? {
        val vals = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100, 'd' to 500, 'm' to 1000)
        var total = 0
        var prev = 0
        for (ch in s.lowercase().reversed()) {
            val v = vals[ch] ?: return null
            total += if (v < prev) -v else v
            prev = v
        }
        return total.takeIf { it > 0 }
    }

    /**
     * Which cour of a shared season is this AniList entry? "… Season 3
     * Part 2" → 2, "2nd Cour" → 2. Cours marker without a readable
     * number defaults to 2 (AniList never ships a "Part 1" entry name —
     * part 1 is the counted base season itself). null = not a cours part.
     */
    private fun titlePartNumber(title: String): Int? {
        if (!coursSplitRegex.containsMatchIn(title)) return null
        val m = partNumberRegex.find(title) ?: return 2
        return m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            ?: m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::romanToInt)
            ?: 2
    }

    // (v54) module-internal (not private) so the unified WizstreamProvider
    // can reuse it for StreamPlay-style AniList enrichment on TMDB anime
    // pages after the re-merge.
    internal suspend fun anilistQuery(
        query: String,
        variables: JSONObject,
        bearerToken: String? = null,
    ): JSONObject? =
        runCatching {
            val body = JSONObject().apply {
                put("query", query); put("variables", variables)
            }.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val headers = mutableMapOf(
                "User-Agent" to A_UA,
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            )
            if (!bearerToken.isNullOrBlank()) headers["Authorization"] = "Bearer $bearerToken"
            val res = app.post(ANILIST_ENDPOINT,
                headers = headers,
                requestBody = body,
                timeout = 12_000)
            if (res.code !in 200..299) null
            else JSONObject(res.text).optJSONObject("data")
        }.getOrNull()

    // ── (v45) Personal AniList homepage rows ─────────────────────────────
    // Served ONLY while the user is logged into AniList in Cloudstream.
    // The whole MediaListCollection arrives in one authed call; we cache it
    // for 5 minutes and paginate client-side so home-page paging stays free.
    private val myListCache = ConcurrentHashMap<String, Pair<Long, List<SearchResponse>>>()

    private suspend fun myAnilistPage(page: Int, request: MainPageRequest): HomePageResponse {
        val perPage = 30
        val status = if (request.data == "my_watching") "CURRENT" else "PLANNING"
        // Token (private-list-proof, app ≥4.8) preferred; username (public
        // profile query, every app generation) as fallback. Neither → hide.
        val token = anilistAuthToken()
        // (v61) always resolved — if the token is expired and the viewer
        // call fails, the public username path still fills the rows.
        val loginName = anilistLoginName()
        if (token == null && loginName == null) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val cacheKey = status + ":" + (token?.takeLast(6) ?: ("@" + loginName))
        val now = System.currentTimeMillis()
        val cached = myListCache[cacheKey]
        val all: List<SearchResponse> = if (cached != null && now - cached.first < 5 * 60_000) {
            cached.second
        } else {
            fetchMyList(status, token, loginName).also { myListCache[cacheKey] = now to it }
        }
        val from = (page - 1) * perPage
        val items = all.drop(from).take(perPage)
        return newHomePageResponse(
            request.name, items, hasNext = from + items.size < all.size
        )
    }

    private suspend fun fetchMyList(
        status: String,
        token: String?,
        userName: String?,
    ): List<SearchResponse> {
        // (v61) THE userlist bug: the authenticated path used to send
        // userName:null — MediaListCollection has no implicit viewer, so
        // AniList rejected the whole call and BOTH personal rows silently
        // stayed empty. The token path now resolves the viewer's numeric
        // id first and queries by userId; the public fallback still goes
        // by userName.
        val gql = """
            query (${'$'}status: [MediaListStatus], ${'$'}userName: String, ${'$'}userId: Int) {
              MediaListCollection(
                userName: ${'$'}userName,
                userId: ${'$'}userId,
                type: ANIME,
                status_in: ${'$'}status,
                sort: UPDATED_TIME_DESC
              ) {
                lists {
                  entries {
                    progress status updatedAt
                    media {
                      id idMal title { romaji english native }
                      coverImage { extraLarge large }
                      bannerImage episodes format season seasonYear
                      averageScore genres startDate { year } status
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val viewerId = token?.let { fetchViewerId(it) }
        val variables = JSONObject().apply {
            put("status", JSONArray(listOf(status)))
            put("userName", userName ?: JSONObject.NULL)
            put("userId", viewerId ?: JSONObject.NULL)
        }
        val data = anilistQuery(gql, variables, token) ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        data.optJSONObject("MediaListCollection")?.optJSONArray("lists")?.let { lists ->
            for (i in 0 until lists.length()) {
                lists.optJSONObject(i)?.optJSONArray("entries")?.let { entries ->
                    for (j in 0 until entries.length()) {
                        entries.optJSONObject(j)?.optJSONObject("media")?.let { m ->
                            mediaToSearch(m)?.let(out::add)
                        }
                    }
                }
            }
        }
        return out
    }
    private suspend fun fetchSimklId(imdbId: String?, kind: String): Int? {
        if (imdbId.isNullOrBlank()) return null
        val type = if (kind == "movie") "movies" else "tv"
        return runCatching {
            val url = "https://api.simkl.com/$type/${URLEncoder.encode(imdbId, "UTF-8")}?client_id=%20&extended=full"
            val t = app.get(url, headers = mapOf(
                "User-Agent" to A_UA, "Accept" to "application/json"
            ), timeout = 6_000).text.trim()
            if (!t.startsWith("{")) null
            else JSONObject(t).optJSONObject("ids")?.optInt("simkl")?.takeIf { it != 0 }
        }.getOrNull()

    }

    // ═══════════════════════════════════════════════════════════════════════
    //  LinkContext
    // ═══════════════════════════════════════════════════════════════════════

    private data class LinkContext(
        val anilistId: Int,
        val imdbId: String? = null,
        val tmdbId: Int? = null,
        val malId: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val altTitle: String? = null,
        val isMovie: Boolean = false,
        val year: Int? = null,   // (v18) identity matching for BDIX resolvers
        // (v48) `season`/`episode` are STACKED (site) numbers — already
        // the season/episode the BDIX sites and TMDB use. `sourceSeason`
        // stays as legacy override (currently always == season).
        val sourceSeason: Int? = null,
        // (v48) The episode number inside the OWNING AniList entry (a
        // cours part's ep 1 while the stacked number is 13). The anime-web
        // sources mirror AniList's per-entry split, so they query with
        // this; BDIX/TMDB/embed lookups use the stacked one.
        val entryEpisode: Int? = null,
        // (v45) counted prequel-ancestor titles (root last) — extra BDIX
        // search keys for multi-season franchise entries.
        val franchiseTitles: List<String>? = null,
        // (v59) Canonical TMDB location of a tail-absorbed story-special
        // (recursive map: AniList "Kanketsu-hen Zenpen" = stacked S4E29 =
        // TMDB S0E36). BDIX/anime-web resolvers keep the stacked numbers;
        // only TMDB-indexed embed hosts get pointed at these.
        val tmdbSeason: Int? = null,
        val tmdbEpisode: Int? = null,
        val dub: DubStatus = DubStatus.Subbed,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("anilist_id", anilistId)
            imdbId?.let { put("imdb_id", it) }
            tmdbId?.let { put("tmdb_id", it) }
            malId?.let { put("mal_id", it) }
            season?.let { put("season", it) }
            episode?.let { put("episode", it) }
            title?.let { put("title", it) }
            altTitle?.let { put("alt_title", it) }
            year?.let { put("year", it) }
            sourceSeason?.let { put("src_season", it) }
            entryEpisode?.let { put("entry_ep", it) }
            tmdbSeason?.let { put("t_season", it) }
            tmdbEpisode?.let { put("t_ep", it) }
            franchiseTitles?.takeIf { it.isNotEmpty() }
                ?.let { put("src_franchise", JSONArray(it)) }
            put("is_movie", isMovie)
            put("dub", dub.ordinal)
        }.toString()

        companion object {
            fun fromJson(s: String): LinkContext {
                val o = JSONObject(s)
                return LinkContext(
                    anilistId = o.optInt("anilist_id", 0).takeIf { it != 0 } ?: 0,
                    imdbId = o.aOptStr("imdb_id"),
                    tmdbId = o.aOptInt("tmdb_id"),
                    malId = o.aOptInt("mal_id"),
                    season = o.aOptInt("season"),
                    episode = o.aOptInt("episode"),
                    title = o.aOptStr("title"),
                    altTitle = o.aOptStr("alt_title"),
                    year = o.aOptInt("year"),
                    isMovie = o.optBoolean("is_movie", false),
                    sourceSeason = o.aOptInt("src_season"),
                    entryEpisode = o.aOptInt("entry_ep"),
                    tmdbSeason = o.aOptInt("t_season"),
                    tmdbEpisode = o.aOptInt("t_ep"),
                    franchiseTitles = o.optJSONArray("src_franchise")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                    },
                    dub = DubStatus.values().getOrElse(o.optInt("dub", 0)) { DubStatus.Subbed },
                ).also { require(it.anilistId != 0) }
            }
        }
    }
}
