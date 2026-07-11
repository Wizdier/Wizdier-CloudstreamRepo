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
 * Sources: Top streaming sites from everythingmoe.com:
 *   Anikoto, animepahe, MKissa/AllAnime, Miruro, AniZone, AniNeko,
 *   Senshi, AnimeStream, Crunchyroll, KickAssAnime, AnimeOnsen,
 *   AnimeNexus, Bilibili, AniSnatch, AnimeX
 *
 * Video extraction: Uses loadExtractor for the embed/API endpoints
 *   of each site plus WingsDatabase direct sources.
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
            addDubStatus(media.optBoolean("isLicensed", false), false)
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
        val title = titleObj?.str("english") ?: titleObj?.str("romaji") ?: titleObj?.str("native") ?: "?"
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
        val status = media.str("status")

        // Genres
        val genres = media.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it) } }

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

        // Data for loadLinks
        val data = "anime|$id|${java.net.URLEncoder.encode(title, "UTF-8")}"

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
            // Build episode placeholders based on episode count
            val eps = mutableListOf<Episode>()
            val epCount = episodes ?: 12
            for (s in 1..minOf(if (epCount > 100) 4 else 1, 4)) {
                val seasonEps = if (epCount > 100) minOf(epCount / (s), 26) else epCount
                for (ep in 1..minOf(seasonEps, 26)) {
                    eps.add(newEpisode("anime-ep|$id|$s|$ep|${java.net.URLEncoder.encode(title, "UTF-8")}") {
                        this.name = "Episode $ep"; this.season = s; this.episode = ep
                    })
                }
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
    //  LOAD LINKS — Top 15 anime sites + embeds
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
        val p = data.split("|"); if (p.size < 2) return false
        val tp = p[0]; val id = p[1]; val title = java.net.URLDecoder.decode(p.getOrElse(2) { "" }, "UTF-8")
        if (tp !in listOf("anime", "anime-ep")) return false

        val (sid, eid) = if (tp == "anime-ep") { Pair(p.getOrElse(2) { "1" }, p.getOrElse(3) { "1" }) } else { Pair("1", "1") }

        fun ld(u: String) { loadExtractor(u, mainUrl, sc, cb) }

        // ── Anime-specific embed & API sources ──
        // AnimeKai-style embeds (use AniList ID)
        ld("https://vidsrc.to/embed/anime/$id/$sid/$eid")
        ld("https://anikoto.vercel.app/api/anime/$id/$sid/$eid")

        // General-purpose embeds that also work with anime
        ld("https://vidlink.pro/anime/$id/$sid/$eid")
        ld("https://www.2embed.cc/embed/anime/$id/$sid/$eid")
        ld("https://embed.su/embed/anime/$id/$sid/$eid")

        // AnimeKai scraper endpoints
        ld("https://animekai.to/watch/$id/$sid/$eid")

        // AnimePahe API
        ld("https://animepahe.ru/api?m=search&q=${java.net.URLEncoder.encode(title, "UTF-8")}")

        // Miruro embed
        ld("https://miruro.tv/anime/$id")

        // AllAnime/MKissa
        ld("https://allanime.to/anime/$id")

        // KickAssAnime
        ld("https://kickassanime.mx/anime/$id")

        // AnimeOnsen
        ld("https://animeonsen.xyz/anime/$id")

        // General vid embeds
        ld("https://nites.is/embed/anime/$id/$sid/$eid")
        ld("https://vidsrc.cc/v2/embed/anime/$id/$sid/$eid")
        ld("https://player.smashy.stream/anime/$id/$sid/$eid")

        // WingsDatabase direct sources
        wdb("anime", id, sid, eid, title, "", "", cb, sc)

        return true
    }

    private suspend fun wdb(mt: String, id: String, s: String, e: String, t: String, y: String, imdb: String, cb: (ExtractorLink) -> Unit, sc: (SubtitleFile) -> Unit) {
        val servers = listOf("jett" to "Jett", "cdn" to "Yoru", "tejo" to "Tejo", "neon2" to "Neon", "ym" to "Sage", "downloader2" to "Cypher", "m4uhd" to "Breach", "hdmovie" to "Vyse")
        val seed: String
        try { seed = JSONObject(app.get("https://api.wingsdatabase.com/seed?mediaId=$id", timeout = 10000, headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14)", "Accept" to "application/json", "Referer" to "https://www.vidking.net/", "Origin" to "https://www.vidking.net")).text).str("seed") ?: return } catch (_: Exception) { return }
        val et = java.net.URLEncoder.encode(java.net.URLEncoder.encode(t, "UTF-8"), "UTF-8")
        for ((k, lb) in servers) {
            try {
                val q = listOf("title" to et, "mediaType" to "anime", "year" to y, "episodeId" to e, "seasonId" to s, "tmdbId" to id, "imdbId" to imdb, "enc" to "2", "seed" to seed).joinToString("&") { (kv, vv) -> "$kv=${java.net.URLEncoder.encode(vv, "UTF-8")}" }
                val enc = app.get("https://api.wingsdatabase.com/${k}/sources-with-title?$q", timeout = 15000, headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14)", "Accept" to "application/json, text/plain, */*", "Referer" to "https://www.vidking.net/", "Origin" to "https://www.vidking.net", "Cache-Control" to "no-cache")).text
                if (enc.isBlank() || enc.startsWith("{") || enc.length < 100) continue
                val bd = JSONObject().apply { put("text", enc); put("id", id); put("seed", seed) }
                val rp = JSONObject(app.post("https://enc-dec.app/api/dec-videasy", requestBody = bd.toString().toRequestBody("application/json".toMediaTypeOrNull()), timeout = 20000, headers = mapOf("Content-Type" to "application/json")).text)
                if (rp.optInt("status", -1) != 200) continue
                val obj = rp.optJSONObject("result") ?: continue; val srcs = obj.optJSONArray("sources") ?: continue
                for (i in 0 until srcs.length()) { val src = srcs.getJSONObject(i); src.str("url")?.let { u -> cb(newExtractorLink("$name ($lb)", "$lb - ${src.str("quality") ?: "?"}", u) { this.quality = getQualityFromName(src.str("quality") ?: "Unknown") }) } }
                obj.optJSONArray("subtitles")?.let { subs -> for (i in 0 until subs.length()) { val sb = subs.getJSONObject(i); sb.str("url")?.let { emSub(it, sb.str("lang"), sc) } } }
            } catch (_: Exception) { continue }
        }
    }

    private fun emSub(url: String, raw: String?, sc: (SubtitleFile) -> Unit) {
        if (url in emittedSubs) return; emittedSubs.add(url)
        val c = raw?.trim()?.lowercase()?.substringBefore("-")?.takeIf { it.length in 2..3 } ?: "unknown"
        sc(SubtitleFile(url, langNames[c] ?: c.uppercase()))
    }
}
