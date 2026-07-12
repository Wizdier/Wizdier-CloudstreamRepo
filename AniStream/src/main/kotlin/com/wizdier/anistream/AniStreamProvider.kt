package com.wizdier.anistream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray

/**
 * AniStream — AniList-powered anime catalog.
 *
 * Metadata: AniList GraphQL API (graphql.anilist.co)
 * Sources (concurrent, no type discrimination):
 *   1. Animepahe          — CF-protected, uses cfGet + Pahe/Kwik extractors
 *   2. AllAnime / MKissa  — GraphQL API with persisted queries
 *   3. GogoAnime          — search HTML → slug → episode
 *   4. Miruro             — direct AniList ID (CF-gated)
 *   5. TMDB-bridge        — title→TMDB lookup → Vidlink + VidFast +
 *                           Videasy (11 servers)
 *
 * Every source above is tried for every title — movie, TV, OVA, ONA, special.
 * No source is skipped based on format/type.
 */
class AniStreamProvider : MainAPI() {
    override var mainUrl = "https://anilist.co"
    override var name = "AniStream"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true

    private val imgBase = "https://image.tmdb.org/t/p"

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // ── Homepage: AniList-powered sections ──
    override val mainPage = mainPageOf(
        "trending" to "🔥 Trending Anime",
        "popular" to "⭐ Popular Anime",
        "top_100" to "🏆 Top 100 Anime",
        "airing" to "📺 Currently Airing",
        "upcoming" to "📅 Upcoming",
        "movie" to "🎬 Anime Movies",
        "action" to "💥 Action",
        "comedy" to "😂 Comedy",
        "drama" to "🎭 Drama",
        "horror" to "👻 Horror",
        "romance" to "💕 Romance",
        "scifi" to "🚀 Sci-Fi",
        "fantasy" to "🧙 Fantasy",
        "slice_of_life" to "🌸 Slice of Life",
        "mecha" to "🤖 Mecha",
        "isekai" to "🌍 Isekai",
        "shounen" to "🔥 Shounen",
        "seinen" to "🍺 Seinen",
        "thriller" to "🔪 Thriller",
        "supernatural" to "👁️ Supernatural"
    )

    // ═══════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════
    private fun poster(p: String?, sz: String = "w342") = p?.takeIf { !it.isNullOrBlank() }
    private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k, "").takeIf { it.isNotBlank() && it != "null" }

    // AniList GraphQL query helper
    private suspend fun anilistQuery(query: String, variables: JSONObject): JSONObject? {
        val body = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }
        return try {
            val resp = app.post("https://graphql.anilist.co",
                requestBody = body.toString().toRequestBody("application/json".toMediaTypeOrNull()),
                timeout = 15_000, headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
            ).text
            JSONObject(resp).optJSONObject("data")
        } catch (e: Exception) { Log.e(name, "AniList: ${e.message}"); null }
    }

    // Map AniList media object to SearchResponse
    private fun mediaToSearch(media: JSONObject): SearchResponse? {
        val id = media.optInt("id", 0).takeIf { it > 0 } ?: return null
        val titleObj = media.optJSONObject("title")
        val title = titleObj?.str("english") ?: titleObj?.str("romaji") ?: titleObj?.str("native") ?: "?"
        val image = media.optJSONObject("coverImage")?.str("large") ?: media.optJSONObject("coverImage")?.str("medium")
        val rating = media.optInt("averageScore", 0).takeIf { it > 0 }
        val year = media.optInt("seasonYear", 0).takeIf { it > 0 }
        val format = media.optString("format", "")
        val tvType = when (format) { "MOVIE" -> TvType.AnimeMovie; "OVA", "ONA", "SPECIAL" -> TvType.OVA; else -> TvType.Anime }
        return newAnimeSearchResponse(title, "anime/$id", tvType) {
            this.posterUrl = image
            if (rating != null) this.score = Score.from10(rating / 10.0)
            if (year != null) this.year = year
            addDubStatus(true, false)
        }
    }

    // ═══════════════════════════════════════════
    //  SEARCH
    // ═══════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = """
            query(${'$'}search: String) {
              Page(page: 1, perPage: 20) {
                media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
                  id title { romaji english native }
                  coverImage { large medium } averageScore seasonYear format isLicensed
                }
              }
            }
        """.trimIndent()
        val vars = JSONObject().apply { put("search", query) }
        return anilistQuery(q, vars)?.optJSONObject("Page")?.optJSONArray("media")?.let { a ->
            (0 until a.length()).mapNotNull { i -> mediaToSearch(a.getJSONObject(i)) }
        } ?: emptyList()
    }

    // ═══════════════════════════════════════════
    //  HOME PAGE
    // ═══════════════════════════════════════════
    override suspend fun getMainPage(pg: Int, req: MainPageRequest): HomePageResponse {
        val d = req.data; val results = mutableListOf<SearchResponse>()

        // Build AniList query based on section
        val (sort, genre, status) = when (d) {
            "trending" -> Triple("TRENDING_DESC", null, null)
            "popular" -> Triple("POPULARITY_DESC", null, null)
            "top_100" -> Triple("SCORE_DESC", null, null)
            "airing" -> Triple("POPULARITY_DESC", null, "RELEASING")
            "upcoming" -> Triple("POPULARITY_DESC", null, "NOT_YET_RELEASED")
            "movie" -> Triple("POPULARITY_DESC", null, null)  // filtered by format below
            "action" -> Triple("POPULARITY_DESC", "Action", null)
            "comedy" -> Triple("POPULARITY_DESC", "Comedy", null)
            "drama" -> Triple("POPULARITY_DESC", "Drama", null)
            "horror" -> Triple("POPULARITY_DESC", "Horror", null)
            "romance" -> Triple("POPULARITY_DESC", "Romance", null)
            "scifi" -> Triple("POPULARITY_DESC", "Sci-Fi", null)
            "fantasy" -> Triple("POPULARITY_DESC", "Fantasy", null)
            "slice_of_life" -> Triple("POPULARITY_DESC", "Slice of Life", null)
            "mecha" -> Triple("POPULARITY_DESC", "Mecha", null)
            "isekai" -> Triple(null, null, null) // handled by search tag
            "shounen" -> Triple("POPULARITY_DESC", null, null)
            "seinen" -> Triple("POPULARITY_DESC", null, null)
            "thriller" -> Triple("POPULARITY_DESC", "Thriller", null)
            "supernatural" -> Triple("POPULARITY_DESC", "Supernatural", null)
            else -> Triple("TRENDING_DESC", null, null)
        }

        val genreFilter = if (genre != null) ", genre: \"$genre\"" else ""
        val statusFilter = if (status != null) ", status: $status" else ""
        val formatFilter = if (d == "movie") ", format: MOVIE" else if (d == "shounen") ", genre: \"Shounen\"" else if (d == "seinen") ", genre: \"Seinen\"" else if (d == "isekai") ", search: \"isekai\"" else ""
        val sortStr = sort ?: "POPULARITY_DESC"

        val q = """
            query(${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(sort: $sortStr, type: ANIME$genreFilter$statusFilter$formatFilter) {
                  id title { romaji english native }
                  coverImage { large medium } averageScore seasonYear format isLicensed
                }
              }
            }
        """.trimIndent()
        val vars = JSONObject().apply { put("page", pg) }
        anilistQuery(q, vars)?.optJSONObject("Page")?.optJSONArray("media")?.let { a ->
            for (i in 0 until a.length()) { mediaToSearch(a.getJSONObject(i))?.let { results.add(it) } }
        }

        return newHomePageResponse(req.name, results, results.size >= 20)
    }

    // ═══════════════════════════════════════════
    //  LOAD — Anime detail
    // ═══════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        val sg = url.trimEnd('/').split("/"); if (sg.size < 2) return null
        val id = sg.last(); if (sg[sg.size - 2] != "anime") return null

        val q = """
            query(${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id idMal title { romaji english native }
                coverImage { extraLarge large } bannerImage description
                averageScore seasonYear format status episodes duration
                genres studios { nodes { name } }
                characters(page: 1, perPage: 15, sort: ROLE) {
                  edges {
                    role
                    node { name { full } image { large } }
                    voiceActors(language: JAPANESE) { name { full } image { large } }
                  }
                }
                relations { edges { relationType node { id title { romaji english } format } } }
                trailer { id site }
                nextAiringEpisode { episode airingAt timeUntilAiring }
              }
            }
        """.trimIndent()
        val vars = JSONObject().apply { put("id", id.toInt()) }
        val media = anilistQuery(q, vars)?.optJSONObject("Media") ?: return null

        val titleObj = media.optJSONObject("title")
        // Prefer English for search (most scrapers match English titles best),
        // fall back to romaji, then native — never blank.
        val title = titleObj?.str("english") ?: titleObj?.str("romaji") ?: titleObj?.str("native") ?: "?"
        val romaji = titleObj?.str("romaji") ?: title
        val image = media.optJSONObject("coverImage")?.str("extraLarge") ?: media.optJSONObject("coverImage")?.str("large")
        val banner = media.str("bannerImage")
        val plot = media.str("description")
        val rating = media.optInt("averageScore", 0).takeIf { it > 0 }
        val year = media.optInt("seasonYear", 0).takeIf { it > 0 }
        val format = media.optString("format", "")
        val tvType = when (format) { "MOVIE" -> TvType.AnimeMovie; "OVA", "ONA", "SPECIAL" -> TvType.OVA; else -> TvType.Anime }
        val episodes = media.optInt("episodes", 0).takeIf { it > 0 }
        val duration = media.optInt("duration", 0).takeIf { it > 0 }
        val malId = media.optInt("idMal", 0).takeIf { it > 0 }
        val nextAiringEp = media.optJSONObject("nextAiringEpisode")?.optInt("episode", 0)?.takeIf { it > 0 }

        // Genres
        val genres = media.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it, null as String?)?.takeIf { it.isNotBlank() } ?: a.getString(it).takeIf { it.isNotBlank() } } }

        // Studios
        val studios = media.optJSONObject("studios")?.optJSONArray("nodes")?.let { a ->
            (0 until a.length()).mapNotNull { a.getJSONObject(it).str("name") }
        }

        // Characters + Voice Actors
        val actors = media.optJSONObject("characters")?.optJSONArray("edges")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val edge = a.getJSONObject(i)
                val node = edge.optJSONObject("node")
                val charName = node?.optJSONObject("name")?.str("full") ?: "?"
                val charImg = node?.optJSONObject("image")?.str("large")
                val va = edge.optJSONArray("voiceActors")?.optJSONObject(0)
                val vaName = va?.optJSONObject("name")?.str("full")
                val vaImg = va?.optJSONObject("image")?.str("large")
                ActorData(Actor(charName, charImg), roleString = edge.str("role"), voiceActor = if (vaName != null) Actor(vaName, vaImg) else null)
            }
        }

        // Trailer
        var trailer: String? = null
        media.optJSONObject("trailer")?.let { tr ->
            if (tr.optString("site") == "youtube" && tr.str("id") != null)
                trailer = "https://www.youtube.com/watch?v=${tr.optString("id")}"
        }

        // Recommendations from relations
        val recs = media.optJSONObject("relations")?.optJSONArray("edges")?.let { a ->
            (0 until minOf(a.length(), 10)).mapNotNull { i ->
                val edge = a.getJSONObject(i)
                val node = edge.optJSONObject("node") ?: return@mapNotNull null
                val rid = node.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
                val rt = node.optJSONObject("title"); val rf = node.optString("format", "")
                val rtv = when (rf) { "MOVIE" -> TvType.AnimeMovie; "OVA", "ONA" -> TvType.OVA; else -> TvType.Anime }
                newAnimeSearchResponse(rt?.str("english") ?: rt?.str("romaji") ?: rt?.str("native") ?: "?", "anime/$rid", rtv) {
                    this.posterUrl = node.optJSONObject("coverImage")?.str("medium")
                }
            }
        }

        // Data for loadLinks — pack title + romaji for search fallback
        val data = "anime|$id|${java.net.URLEncoder.encode(title, "UTF-8")}|${java.net.URLEncoder.encode(romaji, "UTF-8")}"

        return if (tvType == TvType.AnimeMovie) {
            newMovieLoadResponse(title, "anime/$id", tvType, data) {
                this.posterUrl = image; this.backgroundPosterUrl = banner
                this.plot = plot; this.year = year; this.tags = (genres ?: emptyList()) + (studios ?: emptyList())
                if (rating != null) this.score = Score.from10(rating / 10.0)
                if (duration != null) this.duration = duration
                if (actors != null) this.actors = actors
                if (recs != null) this.recommendations = recs
                if (trailer != null) addTrailer(trailer)
            }
        } else {
            // ── Build episode list from AniList's REAL episode count ──
            // Single season (most anime sequels are separate AniList entries,
            // not multi-season). If count is unknown, use nextAiringEpisode as
            // a lower bound + a small buffer, else default to 12. Never split
            // into fake seasons and never cap long-running shows.
            val eps = mutableListOf<Episode>()
            val epCount = episodes ?: nextAiringEp?.let { it + 5 } ?: 12
            // Pack title + romaji into episode data for the search scrapers.
            val epData = "anime-ep|$id|1|EP|${java.net.URLEncoder.encode(title, "UTF-8")}|${java.net.URLEncoder.encode(romaji, "UTF-8")}"
            for (ep in 1..epCount) {
                eps.add(newEpisode(epData.replace("EP", ep.toString())) {
                    this.name = "Episode $ep"; this.season = 1; this.episode = ep
                })
            }
            newAnimeLoadResponse(title, "anime/$id", tvType) {
                addEpisodes(DubStatus.Subbed, eps)
                this.posterUrl = image; this.backgroundPosterUrl = banner
                this.plot = plot; this.year = year; this.tags = (genres ?: emptyList()) + (studios ?: emptyList())
                if (rating != null) this.score = Score.from10(rating / 10.0)
                if (actors != null) this.actors = actors
                if (recs != null) this.recommendations = recs
                if (trailer != null) addTrailer(trailer)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LOAD LINKS — every source concurrent, no type discrimination
    // ═══════════════════════════════════════════════════════════════
    override suspend fun loadLinks(data: String, isCasting: Boolean, sc: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit): Boolean {
        val p = data.split("|")
        val tp = p.getOrNull(0) ?: return false
        val anilistId = p.getOrNull(1)?.toIntOrNull() ?: return false
        if (tp != "anime" && tp != "anime-ep") return false

        val epNum: Int?; val title: String; val romaji: String
        when (tp) {
            "anime" -> {
                // movie data: anime|id|title|romaji
                epNum = null
                title = java.net.URLDecoder.decode(p.getOrElse(2) { "" }, "UTF-8")
                romaji = java.net.URLDecoder.decode(p.getOrElse(3) { "" }, "UTF-8")
            }
            "anime-ep" -> {
                // episode data: anime-ep|id|season|episode|title|romaji
                epNum = p.getOrElse(3) { "1" }.toIntOrNull() ?: 1
                title = java.net.URLDecoder.decode(p.getOrElse(4) { "" }, "UTF-8")
                romaji = java.net.URLDecoder.decode(p.getOrElse(5) { "" }, "UTF-8")
            }
            else -> return false
        }
        val searchTitle = title.takeIf { it.isNotBlank() } ?: romaji.takeIf { it.isNotBlank() } ?: return false

        // ── Dispatch every source concurrently ──
        // 5 source groups, all parallel via runLimitedAsync:
        //   1. Animepahe (CF-protected, uses cfGet + Pahe extractor)
        //   2. AllAnime/MKissa (GraphQL API with persisted queries)
        //   3. GogoAnime (search HTML → slug → episode)
        //   4. Miruro (direct AniList ID, CF-gated)
        //   5. TMDB-bridge (Vidlink + VidFast + Videasy 11 servers)
        // No source is skipped based on format — movies, TV, OVA, ONA, specials
        // all get every source tried.
        runLimitedAsync(
            { invokeAnimepahe(searchTitle, epNum, sc, cb) },
            { invokeAllanime(searchTitle, epNum, sc, cb) },
            { invokeGogo(searchTitle, epNum, sc, cb) },
            { invokeMiruro(anilistId, epNum, sc, cb) },
            { invokeTmdbBridge(searchTitle, epNum, sc, cb) }
        )
        return true
    }
}
