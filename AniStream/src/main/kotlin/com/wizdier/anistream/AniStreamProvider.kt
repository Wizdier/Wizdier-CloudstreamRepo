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
 *   1. Miruro              — direct AniList ID
 *   2. GogoAnime           — search by title → slug → episode
 *   3. AnimePahe           — search by title → JSON API → episode session
 *   4. AllAnime / MKissa   — search by title → API → embed
 *   5. TMDB-bridge embeds  — title→TMDB lookup → vidsrc.to, vidlink.pro,
 *                            vidfast.pro, 2embed.cc, autoembed.co,
 *                            vidking.net, vidsrc.cc
 *   6. WingsDatabase       — via TMDB lookup, 8 servers (Jett, Yoru, Tejo,
 *                            Neon, Sage, Cypher, Breach, Vyse)
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
    private val tmdbAPI = "https://api.themoviedb.org/3"
    private val tmdbKey = "98ae14df2b8d8f8f8136499daf79f0e0"
    private val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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

    // ═══════════════════════════════════════════
    //  LOAD LINKS — concurrent, no type discrimination
    // ═══════════════════════════════════════════
    private val langNames = mapOf(
        "en" to "English", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
        "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
        "pt" to "Portuguese", "ru" to "Russian", "ar" to "Arabic", "hi" to "Hindi",
        "tr" to "Turkish", "th" to "Thai", "vi" to "Vietnamese", "id" to "Indonesian"
    )
    private val emittedSubs = mutableSetOf<String>()

    override suspend fun loadLinks(data: String, isCasting: Boolean, sc: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit): Boolean {
        emittedSubs.clear()
        val p = data.split("|")
        val tp = p.getOrNull(0) ?: return false
        val id = p.getOrNull(1) ?: return false
        if (tp != "anime" && tp != "anime-ep") return false

        val sid: String; val eid: String; val epNum: Int; val title: String; val romaji: String
        when (tp) {
            "anime" -> {
                // movie data: anime|id|title|romaji
                sid = "1"; eid = "1"; epNum = 1
                title = java.net.URLDecoder.decode(p.getOrElse(2) { "" }, "UTF-8")
                romaji = java.net.URLDecoder.decode(p.getOrElse(3) { "" }, "UTF-8")
            }
            "anime-ep" -> {
                // episode data: anime-ep|id|season|episode|title|romaji
                sid = p.getOrElse(2) { "1" }
                eid = p.getOrElse(3) { "1" }
                epNum = eid.toIntOrNull() ?: 1
                title = java.net.URLDecoder.decode(p.getOrElse(4) { "" }, "UTF-8")
                romaji = java.net.URLDecoder.decode(p.getOrElse(5) { "" }, "UTF-8")
            }
            else -> return false
        }
        // If the search title is blank, fall back to romaji
        val searchTitle = title.takeIf { it.isNotBlank() } ?: romaji.takeIf { it.isNotBlank() } ?: return false

        // ── Concurrent multi-source loading ──
        // Every source runs in parallel; one slow or dead host can never block
        // the others. No source is skipped based on anime format — movies, TV,
        // OVA, ONA, specials all get every source tried.
        coroutineScope {
            listOf(
                // 1. Miruro — direct AniList ID (CF-gated, loadExtractor handles via WebView)
                async {
                    try {
                        val url = if (tp == "anime") "https://www.miruro.tv/anime/$id"
                                  else "https://www.miruro.tv/watch/$id?ep=$epNum"
                        loadExtractor(url, mainUrl, sc, cb)
                    } catch (e: Exception) { Log.w(name, "Miruro: ${e.message}") }
                },
                // 2. GogoAnime — search by title → slug → episode
                async {
                    try { gogo(searchTitle, epNum, sc, cb) }
                    catch (e: Exception) { Log.w(name, "Gogo: ${e.message}") }
                },
                // 3. AnimePahe — search via JSON API → episode session → play URL
                async {
                    try { pahe(searchTitle, epNum, sc, cb) }
                    catch (e: Exception) { Log.w(name, "Pahe: ${e.message}") }
                },
                // 4. AllAnime / MKissa — search via API → embed URL
                async {
                    try { allanime(searchTitle, epNum, sc, cb) }
                    catch (e: Exception) { Log.w(name, "MKissa: ${e.message}") }
                },
                // 5. TMDB-bridge: title → TMDB lookup → 7 embed hosts + WingsDatabase (8 servers)
                async {
                    try { tmdbEmbeds(searchTitle, epNum, sc, cb) }
                    catch (e: Exception) { Log.w(name, "TMDB-bridge: ${e.message}") }
                }
            ).awaitAll()
        }
        return true
    }

    // ═══════════════════════════════════════════
    //  SOURCE HELPERS — each is independent & robust
    // ═══════════════════════════════════════════

    // GogoAnime — search HTML → first match's slug → episode URL → loadExtractor
    private suspend fun gogo(title: String, ep: Int, sc: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) {
        val q = java.net.URLEncoder.encode(title, "UTF-8")
        val doc = app.get("https://gogoanime3.co/search.html?keyword=$q",
            headers = mapOf("User-Agent" to UA), timeout = 10_000).document
        val href = doc.selectFirst("ul.items li a")?.attr("href")?.takeIf { it.isNotBlank() } ?: return
        val slug = href.substringAfterLast("/")
        loadExtractor("https://gogoanime3.co/$slug-episode-$ep", mainUrl, sc, cb)
    }

    // AnimePahe — search JSON API → resolve episode session → play URL → loadExtractor
    private suspend fun pahe(title: String, ep: Int, sc: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) {
        val q = java.net.URLEncoder.encode(title, "UTF-8")
        val search = JSONObject(app.get("https://animepahe.pw/api?m=search&q=$q",
            headers = mapOf("User-Agent" to UA), timeout = 10_000).text)
        val results = search.optJSONArray("data") ?: return
        if (results.length() == 0) return
        val first = results.getJSONObject(0)
        val animeId = first.optInt("id", 0).takeIf { it > 0 } ?: return
        val animeSession = first.optString("session").takeIf { it.isNotBlank() } ?: return

        // Fetch episode list and find the requested episode number
        val relResp = app.get("https://animepahe.pw/api?m=release&id=$animeId&sort=episode_asc&page=1",
            headers = mapOf("User-Agent" to UA), timeout = 10_000).text
        val eps = JSONObject(relResp).optJSONArray("data") ?: return
        var epSession: String? = null
        for (i in 0 until eps.length()) {
            val e = eps.getJSONObject(i)
            if (e.optInt("episode", 0) == ep) { epSession = e.optString("session"); break }
        }
        if (epSession.isNullOrBlank()) return
        loadExtractor("https://animepahe.pw/play/$animeSession/$epSession", mainUrl, sc, cb)
    }

    // AllAnime / MKissa — search API → slug → episode URL → loadExtractor
    private suspend fun allanime(title: String, ep: Int, sc: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) {
        val q = java.net.URLEncoder.encode(title, "UTF-8")
        // MKissa is the current domain of AllAnime; API returns JSON search results
        val resp = app.get("https://api.mkissa.to/api/v1/hianime/search?q=$q",
            headers = mapOf("User-Agent" to UA, "Accept" to "application/json"), timeout = 10_000).text
        val j = JSONObject(resp)
        val results = j.optJSONArray("data") ?: j.optJSONArray("results") ?: return
        if (results.length() == 0) return
        val first = results.optJSONObject(0) ?: return
        val slug = first.optString("slug").ifBlank { first.optString("id") }.takeIf { it.isNotBlank() } ?: return
        loadExtractor("https://mkissa.to/watch/$slug/ep-$ep", mainUrl, sc, cb)
    }

    // TMDB title-search → (mediaType, tmdbId). Returns null if no match.
    // Tries TV first (most anime), then movie (anime movies).
    private suspend fun tmdbIdFor(title: String): Pair<String, Int>? {
        val q = java.net.URLEncoder.encode(title, "UTF-8")
        // Try TV with Japanese-language filter for better anime matching
        try {
            val url = "$tmdbAPI/search/tv?api_key=$tmdbKey&query=$q&with_original_language=ja"
            val results = JSONObject(app.get(url, headers = mapOf("User-Agent" to UA), timeout = 10_000).text)
                .optJSONArray("results")
            if (results != null && results.length() > 0) {
                val id = results.getJSONObject(0).optInt("id", 0)
                if (id > 0) return "tv" to id
            }
        } catch (_: Exception) { }
        // Fall back to movie
        try {
            val url = "$tmdbAPI/search/movie?api_key=$tmdbKey&query=$q"
            val results = JSONObject(app.get(url, headers = mapOf("User-Agent" to UA), timeout = 10_000).text)
                .optJSONArray("results")
            if (results != null && results.length() > 0) {
                val id = results.getJSONObject(0).optInt("id", 0)
                if (id > 0) return "movie" to id
            }
        } catch (_: Exception) { }
        return null
    }

    // TMDB-bridge multi-embed: search TMDB by anime title, then use the TMDB ID
    // with every alive embed host + WingsDatabase (8 servers). If the TMDB lookup
    // fails (obscure anime not on TMDB), this source is skipped silently — the
    // anime-specific sources above (Miruro, Gogo, Pahe, MKissa) still work.
    private suspend fun tmdbEmbeds(title: String, ep: Int, sc: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) {
        val (mt, tmdbId) = tmdbIdFor(title) ?: return
        val s = "1"; val e = ep.toString()

        val urls = mutableListOf<String>()
        if (mt == "movie") {
            urls.add("https://vidsrc.to/embed/movie/$tmdbId")
            urls.add("https://vidlink.pro/movie/$tmdbId")
            urls.add("https://www.2embed.cc/embed/movie?id=$tmdbId")
            urls.add("https://vidfast.pro/movie/$tmdbId")
            urls.add("https://autoembed.co/movie/tmdb/$tmdbId")
            urls.add("https://www.vidking.net/embed/movie/$tmdbId")
            urls.add("https://vidsrc.cc/v2/embed/movie/$tmdbId")
        } else {
            urls.add("https://vidsrc.to/embed/tv/$tmdbId/$s/$e")
            urls.add("https://vidlink.pro/tv/$tmdbId/$s/$e")
            urls.add("https://www.2embed.cc/embed/tv?id=$tmdbId&s=$s&e=$e")
            urls.add("https://vidfast.pro/tv/$tmdbId/$s/$e")
            urls.add("https://autoembed.co/tv/tmdb/$tmdbId-$s-$e")
            urls.add("https://www.vidking.net/embed/tv/$tmdbId/$s/$e")
            urls.add("https://vidsrc.cc/v2/embed/tv/$tmdbId/$s-$e")
        }

        coroutineScope {
            val tasks = urls.map { url ->
                async {
                    try { loadExtractor(url, mainUrl, sc, cb) }
                    catch (_: Exception) { }
                }
            } + async {
                try { wdbFromTmdb(mt, tmdbId.toString(), s, e, title, "", "", cb, sc) }
                catch (_: Exception) { }
            }
            tasks.awaitAll()
        }
    }

    // WingsDatabase via TMDB ID — same 8-server extraction as StreamFlix.
    // All 8 servers (Jett, Yoru, Tejo, Neon, Sage, Cypher, Breach, Vyse) are
    // queried in parallel; no server is ever skipped based on media type.
    private suspend fun wdbFromTmdb(mt: String, id: String, s: String, e: String, t: String, y: String, imdb: String, cb: (ExtractorLink) -> Unit, sc: (SubtitleFile) -> Unit) {
        val servers = listOf("jett" to "Jett", "cdn" to "Yoru", "tejo" to "Tejo", "neon2" to "Neon", "ym" to "Sage", "downloader2" to "Cypher", "m4uhd" to "Breach", "hdmovie" to "Vyse")
        val seed: String = try {
            JSONObject(app.get("https://api.wingsdatabase.com/seed?mediaId=$id", timeout = 10000,
                headers = mapOf("User-Agent" to UA, "Accept" to "application/json",
                    "Referer" to "https://www.vidking.net/", "Origin" to "https://www.vidking.net")).text
            ).str("seed") ?: return
        } catch (_: Exception) { return }
        val et = java.net.URLEncoder.encode(java.net.URLEncoder.encode(t, "UTF-8"), "UTF-8")

        coroutineScope {
            servers.map { (k, lb) ->
                async {
                    try {
                        val q = listOf("title" to et, "mediaType" to mt, "year" to y,
                            "episodeId" to e, "seasonId" to s, "tmdbId" to id,
                            "imdbId" to imdb, "enc" to "2", "seed" to seed
                        ).joinToString("&") { (key, value) -> "$key=${java.net.URLEncoder.encode(value, "UTF-8")}" }
                        val enc = app.get("https://api.wingsdatabase.com/$k/sources-with-title?$q", timeout = 15000,
                            headers = mapOf("User-Agent" to UA, "Accept" to "application/json, text/plain, */*",
                                "Referer" to "https://www.vidking.net/", "Origin" to "https://www.vidking.net",
                                "Cache-Control" to "no-cache")).text
                        if (enc.isBlank() || enc.startsWith("{")) return@async
                        val bd = JSONObject().apply { put("text", enc); put("id", id); put("seed", seed) }
                        val rp = JSONObject(app.post("https://enc-dec.app/api/dec-videasy",
                            requestBody = bd.toString().toRequestBody("application/json".toMediaTypeOrNull()), timeout = 20000,
                            headers = mapOf("Content-Type" to "application/json")).text)
                        if (rp.optInt("status", -1) != 200) return@async
                        val obj = rp.optJSONObject("result") ?: return@async
                        val srcs = obj.optJSONArray("sources") ?: return@async
                        for (i in 0 until srcs.length()) {
                            val src = srcs.getJSONObject(i)
                            src.str("url")?.let { u ->
                                cb(newExtractorLink("$name ($lb)", "$lb - ${src.str("quality") ?: "?"}", u) {
                                    this.quality = getQualityFromName(src.str("quality") ?: "Unknown")
                                })
                            }
                        }
                        obj.optJSONArray("subtitles")?.let { subs ->
                            for (i in 0 until subs.length()) { val sb = subs.getJSONObject(i); sb.str("url")?.let { emSub(it, sb.str("lang"), sc) } }
                        }
                    } catch (_: Exception) { }
                }
            }.awaitAll()
        }
    }

    private fun emSub(url: String, raw: String?, sc: (SubtitleFile) -> Unit) {
        if (url in emittedSubs) return; emittedSubs.add(url)
        val c = raw?.trim()?.lowercase()?.substringBefore("-")?.takeIf { it.length in 2..3 } ?: "unknown"
        sc(SubtitleFile(url, langNames[c] ?: c.uppercase()))
    }
}
