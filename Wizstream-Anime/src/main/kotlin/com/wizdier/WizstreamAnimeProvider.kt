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
 *
 * Catalogue:
 *   • AniList GraphQL (Trending / Popular this season / Top rated / Upcoming
 *     / All-time popular).
 *   • Resolves TMDB/IMDb IDs through ani.zip so vid-src-family embeds that
 *     prefer IMDB/TMDB IDs still get hit.
 *
 * Source resolution (same vid-host pool as WizstreamProvider, plus a few
 * anime-specific mirrors):
 *   • All VidSrc-family movie/tv embeds.
 *   • 2embed / superembed / multiembed / gomo / databasegdrive / smashystream.
 *   • Built-in Cloudstream `loadExtractor` fallbacks for every iframe URL so
 *     Cloudstream's native extractors (VidPlay, FileMoon, RabbitStream, …)
 *     also get a shot.
 */
class WizstreamAnimeProvider : MainAPI() {

    override var mainUrl = "https://anilist.co"
    override var name = "Wizstream-Anime"
    override var lang = "en"
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
        private const val ANILIST = "https://graphql.anilist.co"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"
        private const val IMG = "https://image.tmdb.org/t/p"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val META_CACHE = ConcurrentHashMap<Int, Pair<Long, AniDetail>>()
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        private data class VidHost(
            val label: String,
            val movie: (String) -> String,
            val tv: (String, Int, Int) -> String,
        )

        // Mirror pool. Any host that goes down is silently skipped.
        private val VID_HOSTS: List<VidHost> = listOf(
            VidHost("VidSrc",
                { id -> "https://vidsrc.icu/embed/movie/$id" },
                { id, s, e -> "https://vidsrc.icu/embed/tv/$id/$s/$e" }),
            VidHost("VidSrc.to",
                { id -> "https://vidsrc.to/embed/movie/$id" },
                { id, s, e -> "https://vidsrc.to/embed/tv/$id/$s/$e" }),
            VidHost("VidSrc.me",
                { id -> "https://vidsrc.me/embed/movie?imdb=$id" },
                { id, s, e -> "https://vidsrc.me/embed/tv?imdb=$id&season=$s&episode=$e" }),
            VidHost("VidSrc.ru",
                { id -> "https://vidsrc.xyz/embed/movie/$id" },
                { id, s, e -> "https://vidsrc.xyz/embed/tv/$id/$s-$e" }),
            VidHost("VidBinge",
                { id -> "https://vidbinge.com/e/$id" },
                { id, s, e -> "https://vidbinge.com/e/$id?s=$s&e=$e" }),
            VidHost("VidJoy",
                { id -> "https://vidjoy.to/embed/movie/$id" },
                { id, s, e -> "https://vidjoy.to/embed/tv/$id/$s/$e" }),
            VidHost("VidSrc.mov",
                { id -> "https://vidsrc.mov/embed/movie/$id" },
                { id, s, e -> "https://vidsrc.mov/embed/tv/$id/$s/$e" }),
            VidHost("2Embed",
                { id -> "https://www.2embed.cc/embed/$id" },
                { id, s, e -> "https://www.2embed.cc/embedtv/$id&s=$s&e=$e" }),
            VidHost("MultiEmbed",
                { id -> "https://multiembed.mov/?video_id=$id&tmdb=1" },
                { id, s, e -> "https://multiembed.mov/?video_id=$id&tmdb=1&s=$s&e=$e" }),
            VidHost("SuperEmbed",
                { id -> "https://getsuperembed.link/?video_id=$id" },
                { id, s, e -> "https://getsuperembed.link/?video_id=$id&season=$s&episode=$e" }),
            VidHost("Gomo",
                { id -> "https://gomo.to/movie/$id" },
                { id, s, e -> "https://gomo.to/tv/$id/$s/$e" }),
            VidHost("DatabaseGdrive",
                { id -> "https://databasegdriveplayer.co/player.php?imdb=$id" },
                { id, s, e -> "https://databasegdriveplayer.co/player.php?type=series&imdb=$id&season=$s&episode=$e" }),
            VidHost("SmashyStream",
                { id -> "https://embed.smashystream.com/playere.php?imdb=$id" },
                { id, s, e -> "https://embed.smashystream.com/playere.php?imdb=$id&season=$s&episode=$e" }),
            VidHost("VidAPI",
                { id -> "https://vidapi.ru/embed/movie/$id" },
                { id, s, e -> "https://vidapi.ru/embed/tv/$id/$s/$e" }),
            VidHost("VAPlayer",
                { id -> "https://vaplayer.ru/embed/movie/$id" },
                { id, s, e -> "https://vaplayer.ru/embed/tv/$id/$s/$e" }),
            VidHost("ApiPlayer",
                { id -> "https://apiplayer.ru/embed/movie/$id" },
                { id, s, e -> "https://apiplayer.ru/embed/tv/$id/$s/$e" }),
            VidHost("111Movies",
                { id -> "https://111movies.com/movie/$id" },
                { id, s, e -> "https://111movies.com/tv/$id/$s/$e" }),
            VidHost("Remotestream",
                { id -> "https://remotestre.am/movie/$id" },
                { id, s, e -> "https://remotestre.am/tv/$id/$s/$e" }),
            VidHost("AutoEmbe",
                { id -> "https://autoembe.xyz/embed/movie?imdb=$id" },
                { id, s, e -> "https://autoembe.xyz/embed/tv?imdb=$id&sea=$s&epi=$e" }),
            // Anime-specific mirrors (accept AniList IDs directly when no IMDB/TMDB).
            VidHost("AllManga",
                { id -> "https://allmanga.to/manga/$id" },
                { id, _, e -> "https://allmanga.to/streaming/anicdn.php?anime_id=$id&ep=$e" }),
            VidHost("AnimeStream",
                { id -> "https://anicdn.stream/anime/$id" },
                { id, _, e -> "https://anicdn.stream/episode/$id-$e" }),
            VidHost("ZoroAnime",
                { id -> "https://hianime.to/search?keyword=$id" },
                { id, _, e -> "https://hianime.to/ajax/v2/episode/sources?id=$id&ep=$e" }),
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Main pages — AniList GraphQL queries
    // ═══════════════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "trending" to "Trending Anime",
        "popular" to "Popular This Season",
        "top" to "Top Rated Anime",
        "upcoming" to "Upcoming (Next Season)",
        "alltime" to "All-Time Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val perPage = 30
        val (sort, season, seasonYear, status) = when (request.data) {
            "trending" -> listOf("TRENDING_DESC") to null
            "popular" -> listOf("POPULARITY_DESC") to currentSeasonFilter()
            "top" -> listOf("SCORE_DESC") to null
            "upcoming" -> listOf("POPULARITY_DESC") to nextSeasonFilter()
            "alltime" -> listOf("POPULARITY_DESC") to null
            else -> listOf("TRENDING_DESC") to null
        }
        val sortArr = JSONArray(sort.first)
        val variables = JSONObject().apply {
            put("page", page); put("perPage", perPage); put("sort", sortArr)
            put("type", "ANIME")
            season?.let { put("season", it); put("seasonYear", seasonYear) }
            if (request.data == "upcoming") put("status", "NOT_YET_RELEASED")
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

    private fun currentSeasonFilter(): Pair<String, Int>? {
        val cal = java.util.Calendar.getInstance()
        val month = cal.get(java.util.Calendar.MONTH) // 0-11
        val year = cal.get(java.util.Calendar.YEAR)
        val season = when {
            month in 2..4 -> "SPRING"
            month in 5..7 -> "SUMMER"
            month in 8..10 -> "FALL"
            else -> "WINTER"
        }
        return season to year
    }

    private fun nextSeasonFilter(): Pair<String, Int>? {
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

    // ═══════════════════════════════════════════════════════════════════════
    //  Load
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val id = parseAnilistUrl(url)
            ?: throw ErrorLoadingException("Invalid AniList URL: $url")

        val detail = fetchAniDetail(id)
            ?: throw ErrorLoadingException("Could not load AniList media $id")

        val title = detail.title
        val episodes = detail.episodes
        val type = when (detail.format) {
            "MOVIE", "SPECIAL" -> if (episodes <= 1) TvType.AnimeMovie else TvType.Anime
            "OVA", "ONA" -> TvType.OVA
            "MUSIC" -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        // Resolve a tv/movie id for embeds — AniList ID first (some anime mirrors
        // support it), then IMDB / TMDB via ani.zip mappings.
        val imdbId = detail.imdbId
        val tmdbId = detail.tmdbId
        val preferredId = imdbId ?: tmdbId?.toString() ?: id.toString()

        // Build episode list. For movies, a single pseudo-episode so loadLinks
        // still runs.
        val epList = when (type) {
            TvType.AnimeMovie -> listOf(newEpisode(LinkContext(
                anilistId = id, imdbId = imdbId, tmdbId = tmdbId,
                season = null, episode = null, title = title, isMovie = true,
                dub = DubStatus.Subbed,
            ).toJson()) { name = "Movie" })
            else -> (1..episodes).map { epNum ->
                val epMeta = detail.episodeMeta[epNum]
                newEpisode(LinkContext(
                    anilistId = id, imdbId = imdbId, tmdbId = tmdbId,
                    season = 1, episode = epNum, title = title, isMovie = false,
                    dub = DubStatus.Subbed,
                ).toJson()) {
                    name = epMeta?.title ?: "Episode $epNum"
                    season = 1
                    episode = epNum
                    posterUrl = epMeta?.stillUrl ?: detail.posterUrl
                    description = epMeta?.overview
                    runCatching { epMeta?.rating?.let { score = Score.from10(it) } }
                    runTime = epMeta?.runtime
                    this.date = epMeta?.airDate
                }
            }
        }

        val recs = detail.recommendations.map { m -> mediaToSearch(m)!! }

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
            if (ctx.isMovie || ctx.season == null || ctx.episode == null) {
                // For movies we try IMDB/TMDB id first, then fall back to AniList id
                // (in case an anime-only mirror supports it).
                ctx.imdbId?.let { add(it) }
                ctx.tmdbId?.let { add(it.toString()) }
                add(ctx.anilistId.toString())
            } else {
                ctx.imdbId?.let { add(it) }
                ctx.tmdbId?.let { add(it.toString()) }
                add(ctx.anilistId.toString())
            }
        }.distinct()

        val seenUrls = Collections.newSetFromMap<String>(ConcurrentHashMap())
        val seenSubs = Collections.newSetFromMap<String>(ConcurrentHashMap())
        val gate = Semaphore(8)
        var anyFound = false

        val jobs = VID_HOSTS.flatMap { host ->
            idList.map { id ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val embedUrl = if (ctx.isMovie || ctx.season == null || ctx.episode == null) {
                            host.movie(id)
                        } else {
                            host.tv(id, ctx.season, ctx.episode)
                        }
                        try {
                            val before = anyFound
                            loadExtractor(
                                embedUrl,
                                "https://" + java.net.URL(embedUrl).host + "/",
                                { sub ->
                                    if (seenSubs.add(sub.url)) subtitleCallback(sub)
                                }
                            ) { link ->
                                val url = link.url.trim()
                                if (url.isBlank() || !seenUrls.add(url)) return@loadExtractor
                                val labelled = link.copy(
                                    source = "Wizstream-A • ${host.label}",
                                    name = "${host.label} — ${link.name}".trimEnd('—', ' '),
                                )
                                callback(labelled)
                                anyFound = true
                            }
                            if (!anyFound && !before) {
                                callback(newExtractorLink(
                                    source = "Wizstream-A • ${host.label}",
                                    name = "${host.label} — Auto",
                                    url = embedUrl,
                                    type = INFER_TYPE,
                                ) {
                                    referer = "https://" + java.net.URL(embedUrl).host + "/"
                                    quality = Qualities.Unknown.value
                                })
                                anyFound = true
                            }
                        } catch (_: Throwable) {
                            // Host is probably down or blocked — skip silently.
                        }
                    }
                }
            }
        }
        jobs.awaitAll()
        anyFound
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  AniList + mapping helpers
    // ═══════════════════════════════════════════════════════════════════════

    private data class EpisodeMeta(
        val title: String?,
        val overview: String?,
        val stillUrl: String?,
        val rating: Double?,
        val airDate: Long?,
        val runtime: Int?,
    )

    private data class AniDetail(
        val title: String,
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
        val episodeMeta: Map<Int, EpisodeMeta>,
        val recommendations: List<JSONObject>,
    )

    private fun parseAnilistUrl(url: String): Int? {
        val m = Regex("wiz://anilist/(\\d+)").find(url)
            ?: Regex("anilist/(\\d+)").find(url)
            ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun mediaToSearch(m: JSONObject): SearchResponse? {
        val id = m.optInt("id", 0).takeIf { it != 0 } ?: return null
        val titles = m.optJSONObject("title")
        val title = titles?.optStringOrNull("english")
            ?: titles?.optStringOrNull("romaji")
            ?: titles?.optStringOrNull("native")
            ?: return null
        val cover = m.optJSONObject("coverImage")?.optStringOrNull("extraLarge")
            ?: m.optJSONObject("coverImage")?.optStringOrNull("large")
        val format = m.optStringOrNull("format") ?: "TV"
        val year = m.optJSONObject("startDate")?.optInt("year")?.takeIf { it != 0 }
        val tvType = when {
            format == "MOVIE" -> TvType.AnimeMovie
            format == "OVA" || format == "ONA" -> TvType.OVA
            else -> TvType.Anime
        }
        return when (tvType) {
            TvType.AnimeMovie -> newMovieSearchResponse(title, "wiz://anilist/$id", TvType.AnimeMovie) {
                this.posterUrl = cover; this.year = year
            }
            TvType.OVA -> newAnimeSearchResponse(title, "wiz://anilist/$id", TvType.OVA) {
                this.posterUrl = cover; this.year = year
            }
            else -> newAnimeSearchResponse(title, "wiz://anilist/$id", TvType.Anime) {
                this.posterUrl = cover; this.year = year
            }
        }
    }

    private suspend fun fetchAniDetail(id: Int): AniDetail? {
        val now = System.currentTimeMillis()
        META_CACHE[id]?.let { (ts, cached) ->
            if (now - ts < CACHE_TTL_MS) return cached
        }

        val gql = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id idMal idMals
                title { romaji english native userPreferred }
                coverImage { extraLarge large }
                bannerImage
                description(asHtml: false)
                averageScore meanScore
                genres tags { name }
                episodes duration format status season seasonYear
                startDate { year month day }
                endDate { year month day }
                trailer { id site }
                characters(sort: [ROLE, RELEVANCE], perPage: 10, role: MAIN) {
                  edges {
                    node { name { full } image { large } }
                    voiceActors(language: JAPANESE, sort: [RELEVANCE]) { name { full } image { large } }
                  }
                }
                relations { edges { node { id type title { romaji english } coverImage { large } format } relationType } }
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
        val title = titles?.optStringOrNull("english")
            ?: titles?.optStringOrNull("romaji")
            ?: titles?.optStringOrNull("native")
            ?: return null
        val cover = media.optJSONObject("coverImage")?.optStringOrNull("extraLarge")
            ?: media.optJSONObject("coverImage")?.optStringOrNull("large")
        val banner = media.optStringOrNull("bannerImage")
        val plot = media.optStringOrNull("description")
            ?.replace(Regex("<[^>]+>"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        val score = media.optInt("averageScore", 0).takeIf { it > 0 }?.let { it / 10.0 }
        val episodes = media.optInt("episodes", 12).takeIf { it > 0 } ?: 12
        val format = media.optStringOrNull("format")
        val year = media.optJSONObject("startDate")?.optInt("year")?.takeIf { it != 0 }
        val genres = media.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { it.isNotBlank() } }
        }
        val trailerObj = media.optJSONObject("trailer")
        val trailerUrl = trailerObj?.optStringOrNull("site")
            ?.takeIf { it.equals("youtube", true) }
            ?.let { "https://www.youtube.com/watch?v=${trailerObj.optString("id")}" }

        // ID mappings via ani.zip (TMDB/IMDB/Kitsu from AniList id).
        var imdbId: String? = null
        var tmdbId: Int? = null
        var kitsuId: String? = null
        runCatching {
            val text = app.get("https://api.ani.zip/mappings?anilist_id=$id",
                headers = mapOf("User-Agent" to UA), timeout = 8000).text
            val mapJson = JSONObject(text)
            val mappings = mapJson.optJSONObject("mappings")
            imdbId = mappings?.optStringOrNull("imdb_id")
            tmdbId = mappings?.optStringOrNull("themoviedb_id")?.toIntOrNull()
            kitsuId = mappings?.optStringOrNull("kitsu_id")
        }

        // If ani.zip didn't give us an imdbId but gave us a tmdbId, look it up.
        if (imdbId == null && tmdbId != null) {
            val kind = if (format == "MOVIE") "movie" else "tv"
            runCatching {
                val ext = tmdbGet("/$kind/${tmdbId}", mapOf(
                    "append_to_response" to "external_ids"
                ))?.optJSONObject("external_ids")
                imdbId = ext?.optStringOrNull("imdb_id")
            }
        }

        // Fetch TMDB enrichment: backdrop, logo, trailer, actors, episodes, recs
        // if we could resolve a TMDB id.
        var backdropUrl: String? = banner
        var logoUrl: String? = null
        var actors: List<ActorData>? = null
        var episodeMeta = emptyMap<Int, EpisodeMeta>()
        var simklId: Int? = null
        val recs = mutableListOf<JSONObject>()
        media.optJSONObject("recommendations")?.optJSONArray("nodes")?.let { nodes ->
            for (i in 0 until nodes.length()) {
                nodes.optJSONObject(i)?.optJSONObject("mediaRecommendation")?.let(recs::add)
            }
        }

        if (tmdbId != null) {
            val kind = if (format == "MOVIE") "movie" else "tv"
            val extDetails = tmdbGet("/$kind/${tmdbId}", mapOf(
                "append_to_response" to "credits,external_ids,images,videos,recommendations",
                "include_image_language" to "en,null"
            ))
            if (extDetails != null) {
                backdropUrl = extDetails.optStringOrNull("backdrop_path")?.toTmdbImg("original")
                    ?: backdropUrl
                logoUrl = pickLogo(extDetails.optJSONObject("images")?.optJSONArray("logos"))
                    ?: imdbId?.let { "https://live.metahub.space/logo/medium/$it/img" }
                actors = extDetails.optJSONObject("credits")?.optJSONArray("cast")?.toActors()
                val extTrailer = pickTrailer(extDetails.optJSONObject("videos")?.optJSONArray("results"))
                if (extTrailer != null && trailerUrl == null) {
                    // keep AniList trailer if present, otherwise use TMDB
                }
                simklId = fetchSimklId(imdbId, kind)
                if (kind == "tv") {
                    val seasonJson = tmdbGet("/tv/$tmdbId/season/1")
                    episodeMeta = seasonJson?.optJSONArray("episodes")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            val ep = arr.optJSONObject(i) ?: return@mapNotNull null
                            val n = ep.optIntOrNull("episode_number") ?: return@mapNotNull null
                            n to EpisodeMeta(
                                title = ep.optStringOrNull("name"),
                                overview = ep.optStringOrNull("overview"),
                                stillUrl = ep.optStringOrNull("still_path")?.toTmdbImg("original"),
                                rating = ep.optDoubleOrNull("vote_average"),
                                airDate = ep.optStringOrNull("air_date")?.let(::parseAirDate),
                                runtime = media.optIntOrNull("duration"),
                            )
                        }.toMap()
                    } ?: emptyMap()
                }
            }
        }

        val malId = media.optInt("idMal", 0).takeIf { it != 0 }

        val detail = AniDetail(
            title = title,
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
            kitsuId = kitsuId,
            episodes = episodes,
            format = format,
            actors = actors,
            trailerUrl = trailerUrl,
            episodeMeta = episodeMeta,
            recommendations = recs,
        )
        META_CACHE[id] = now to detail
        return detail
    }

    private suspend fun anilistQuery(query: String, variables: JSONObject): JSONObject? =
        runCatching {
            val body = JSONObject().apply {
                put("query", query); put("variables", variables)
            }.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val res = app.post(ANILIST,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                requestBody = body,
                timeout = 12_000)
            if (res.code !in 200..299) null
            else JSONObject(res.text).optJSONObject("data")
        }.getOrNull()

    private suspend fun tmdbGet(path: String, q: Map<String, Any?> = emptyMap()): JSONObject? =
        runCatching {
            val params = (mapOf("api_key" to TMDB_KEY, "language" to "en-US") + q)
                .filter { it.value != null }
                .entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v.toString(), "UTF-8")}"
                }
            val url = "$TMDB_API${if (path.startsWith("/")) path else "/$path"}?$params"
            val res = app.get(url, headers = mapOf(
                "User-Agent" to UA, "Accept" to "application/json"
            ), timeout = 8_000)
            if (res.code in 200..299) JSONObject(res.text) else null
        }.getOrNull()

    private suspend fun fetchSimklId(imdbId: String?, kind: String): Int? {
        if (imdbId.isNullOrBlank()) return null
        val type = if (kind == "movie") "movies" else "tv"
        return runCatching {
            val url = "https://api.simkl.com/$type/${URLEncoder.encode(imdbId, "UTF-8")}?client_id=%20&extended=full"
            val t = app.get(url, headers = mapOf(
                "User-Agent" to UA, "Accept" to "application/json"
            ), timeout = 6_000).text.trim()
            if (!t.startsWith("{")) null
            else JSONObject(t).optJSONObject("ids")?.optInt("simkl")?.takeIf { it != 0 }
        }.getOrNull()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  JSON helper types
    // ═══════════════════════════════════════════════════════════════════════

    private data class LinkContext(
        val anilistId: Int,
        val imdbId: String? = null,
        val tmdbId: Int? = null,
        val malId: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val isMovie: Boolean = false,
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
            put("is_movie", isMovie)
            put("dub", dub.ordinal)
        }.toString()

        companion object {
            fun fromJson(s: String): LinkContext {
                val o = JSONObject(s)
                return LinkContext(
                    anilistId = o.optInt("anilist_id", 0).takeIf { it != 0 } ?: 0,
                    imdbId = o.optStringOrNull("imdb_id"),
                    tmdbId = o.optIntOrNull("tmdb_id"),
                    malId = o.optIntOrNull("mal_id"),
                    season = o.optIntOrNull("season"),
                    episode = o.optIntOrNull("episode"),
                    title = o.optStringOrNull("title"),
                    isMovie = o.optBoolean("is_movie", false),
                    dub = DubStatus.values().getOrElse(o.optInt("dub", 0)) { DubStatus.Subbed },
                ).also { require(it.anilistId != 0) }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Utils
    // ═══════════════════════════════════════════════════════════════════════

    private fun String.toTmdbImg(size: String): String? =
        takeIf { it.isNotBlank() && it != "null" }?.let { "$IMG/$size$it" }

    private fun parseAirDate(s: String?): Long? {
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

    private fun JSONArray.toActors(limit: Int = 15): List<ActorData> =
        (0 until length()).mapNotNull { i ->
            val c = optJSONObject(i) ?: return@mapNotNull null
            val name = c.optStringOrNull("name") ?: c.optStringOrNull("original_name")
                ?: return@mapNotNull null
            val profile = c.optStringOrNull("profile_path")?.toTmdbImg("w185")
            val role = c.optStringOrNull("character")
            ActorData(Actor(name, profile), roleString = role ?: "")
        }.take(limit)

    private fun pickLogo(logos: JSONArray?): String? {
        if (logos == null || logos.length() == 0) return null
        var enPng: String? = null; var enSvg: String? = null; var anyPng: String? = null
        for (i in 0 until logos.length()) {
            val l = logos.optJSONObject(i) ?: continue
            val p = l.optString("file_path").takeIf { it.isNotBlank() } ?: continue
            val lang = l.optString("iso_639_1").trim().lowercase()
            val isSvg = p.endsWith(".svg", true)
            val url = "$IMG/w500$p"
            when {
                lang == "en" && !isSvg -> return url
                lang == "en" && isSvg && enSvg == null -> enSvg = url
                !isSvg && anyPng == null -> anyPng = url
            }
        }
        return enPng ?: enSvg ?: anyPng
    }

    private fun pickTrailer(videos: JSONArray?): String? {
        if (videos == null) return null
        var official: String? = null; var any: String? = null
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (!v.optString("site").equals("YouTube", true)) continue
            val key = v.optStringOrNull("key") ?: continue
            when {
                v.optString("type").equals("Trailer", true) && v.optBoolean("official") && official == null ->
                    official = "https://www.youtube.com/watch?v=$key"
                v.optString("type").equals("Trailer", true) && any == null ->
                    any = "https://www.youtube.com/watch?v=$key"
            }
        }
        return official ?: any
    }

    private fun JSONObject.optStringOrNull(k: String): String? =
        if (!has(k) || isNull(k)) null
        else optString(k, "").trim().takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.optIntOrNull(k: String): Int? =
        if (!has(k) || isNull(k)) null
        else optString(k, "").toIntOrNull()
            ?: optInt(k, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

    private fun JSONObject.optDoubleOrNull(k: String): Double? =
        if (!has(k) || isNull(k)) null
        else optString(k, "").toDoubleOrNull()
            ?: optDouble(k, Double.NaN).takeIf { !it.isNaN() }
}
