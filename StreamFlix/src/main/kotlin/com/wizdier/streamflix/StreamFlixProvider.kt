package com.wizdier.streamflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
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
 * StreamFlix — TMDB-powered catalog with every available vid source.
 *
 * Metadata: api.themoviedb.org
 * Sources: VidSrc, VidLink, VidKing/WingsDb, 2Embed, NiteS, EmbedSu,
 *          VidFast, VidNest, SmashyStream, AutoEmbed, VidsrcCC
 * Subtitles: WingsDatabase (direct) + embedded in embeds
 */
class StreamFlixProvider : MainAPI() {
    override var mainUrl = "https://www.themoviedb.org"
    override var name = "StreamFlix"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true

    private val tmdbAPI = "https://api.themoviedb.org/3"
    private val tmdbKey = "98ae14df2b8d8f8f8136499daf79f0e0"
    private val imgBase = "https://image.tmdb.org/t/p"

    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie,
        TvType.AsianDrama, TvType.Documentary, TvType.OVA
    )

    // ── Homepage: 24 curated sections ──
    override val mainPage = mainPageOf(
        "trending_movie_day" to "🔥 Trending Movies",
        "trending_tv_day" to "🔥 Trending TV Shows",
        "movie_now_playing" to "🎬 Now Playing",
        "movie_upcoming" to "📅 Upcoming",
        "movie_popular" to "⭐ Popular Movies",
        "tv_popular" to "⭐ Popular TV Shows",
        "movie_top_rated" to "🏆 Top Rated Movies",
        "tv_top_rated" to "🏆 Top Rated TV Shows",
        "tv_airing_today" to "📺 Airing Today",
        "trending_all_day" to "🔥 Trending Today (All)",
        "discover_movie_28" to "💥 Action",
        "discover_movie_35" to "😂 Comedy",
        "discover_movie_27" to "👻 Horror",
        "discover_movie_10749" to "💕 Romance",
        "discover_movie_878" to "🚀 Sci-Fi",
        "discover_movie_16" to "🎨 Animation",
        "discover_tv_18" to "🎭 Drama TV",
        "discover_tv_10765" to "🛸 Sci-Fi & Fantasy TV",
        "discover_tv_10759" to "⚔️ Action & Adventure TV",
        "discover_tv_16" to "🎨 Anime TV",
        "discover_movie_ko" to "🇰🇷 Korean",
        "discover_movie_ja" to "🇯🇵 Japanese",
        "discover_movie_hi" to "🇮🇳 Indian",
        "discover_tv_10768" to "🏛️ War & Politics TV"
    )

    // ═══════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════
    private fun poster(p: String?, sz: String = "w342") = p?.takeIf { !it.isNullOrBlank() }?.let { "$imgBase/$sz$it" }
    private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k, "").takeIf { it.isNotBlank() && it != "null" }

    private suspend fun tmdb(ep: String): JSONObject? {
        val s = if (ep.contains("?")) "&" else "?"
        return try { JSONObject(app.get("$tmdbAPI/$ep${s}api_key=$tmdbKey", timeout = 10_000).text) }
        catch (e: Exception) { Log.e(name, "TMDB: ${e.message}"); null }
    }

    private fun logo(logos: JSONArray, lng: String?): String? {
        if (logos.length() == 0) return null
        val lang = lng?.trim()?.lowercase()?.substringBefore("-")
        var enSvg: JSONObject? = null; var any: JSONObject? = null; var anySvg: JSONObject? = null
        for (i in 0 until logos.length()) {
            val l = logos.optJSONObject(i) ?: continue
            val p = l.optString("file_path", ""); if (p.isBlank()) continue
            val ll = l.optString("iso_639_1").trim().lowercase(); val sv = p.endsWith(".svg", true)
            if (ll == lang && !sv) return "$imgBase/w500$p"
            if (ll == lang && sv && enSvg == null) enSvg = l
            if (!sv && any == null) any = l; if (sv && anySvg == null) anySvg = l
        }
        return (enSvg ?: any ?: anySvg)?.optString("file_path")?.takeIf { it.isNotBlank() }?.let { "$imgBase/w500$it" }
    }

    // ═══════════════════════════════════════════
    //  SEARCH
    // ═══════════════════════════════════════════
    override suspend fun search(q: String): List<SearchResponse> {
        if (q.isBlank()) return emptyList()
        return coroutineScope { sr("movie", q) + sr("tv", q) }
    }

    private suspend fun sr(tp: String, q: String) = tmdb("search/$tp?query=${java.net.URLEncoder.encode(q, "UTF-8")}")
        ?.optJSONArray("results")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val it = a.getJSONObject(i); val id = it.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
                val tv = if (tp == "movie") TvType.Movie else TvType.TvSeries
                newMovieSearchResponse(it.str("title") ?: it.str("name") ?: "?", "$tp/$id", tv) {
                    this.posterUrl = poster(it.str("poster_path"))
                    it.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) }
                }
            }
        } ?: emptyList()

    // ═══════════════════════════════════════════
    //  HOME PAGE
    // ═══════════════════════════════════════════
    override suspend fun getMainPage(pg: Int, req: MainPageRequest): HomePageResponse {
        val d = req.data; val r = mutableListOf<SearchResponse>()
        when {
            d.startsWith("trending_") -> {
                val p = d.removePrefix("trending_").split("_")
                tmdb("trending/${p[0]}/${p.getOrElse(1) { "day" }}?page=$pg")?.optJSONArray("results")?.let { a ->
                    for (i in 0 until a.length()) {
                        val it = a.getJSONObject(i); val id = it.optInt("id", 0).takeIf { it > 0 } ?: continue
                        val mt = it.str("media_type") ?: "movie"
                        r.add(newMovieSearchResponse(it.str("title") ?: it.str("name") ?: "?", "$mt/$id",
                            if (mt == "movie") TvType.Movie else TvType.TvSeries
                        ) {
                            this.posterUrl = poster(it.str("poster_path"))
                            it.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) }
                        })
                    }
                }
            }
            d.endsWith("_top_rated") || d.endsWith("_popular") -> {
                val sx = if (d.endsWith("_top_rated")) "_top_rated" else "_popular"
                val tp = d.removeSuffix(sx)
                addArr(tmdb("$tp/${if (sx == "_top_rated") "top_rated" else "popular"}?page=$pg")?.optJSONArray("results"), tp, r)
            }
            d.startsWith("movie_now_playing") -> addArr(tmdb("movie/now_playing?page=$pg")?.optJSONArray("results"), "movie", r)
            d.startsWith("movie_upcoming") -> addArr(tmdb("movie/upcoming?page=$pg")?.optJSONArray("results"), "movie", r)
            d.startsWith("tv_airing_today") -> addArr(tmdb("tv/airing_today?page=$pg")?.optJSONArray("results"), "tv", r)
            d.startsWith("discover_") -> {
                val p = d.removePrefix("discover_").split("_"); val tp = p[0]; val pm = p.getOrElse(1) { "" }
                val q = mutableListOf<String>()
                pm.toIntOrNull()?.let { q.add("with_genres=$it") }
                if (pm.length == 2 && pm !in listOf("tv", "mo")) q.add("with_original_language=$pm")
                q.add("sort_by=popularity.desc"); q.add("page=$pg")
                addArr(tmdb("discover/$tp?${q.joinToString("&")}")?.optJSONArray("results"), tp, r)
            }
        }
        return newHomePageResponse(req.name, r, r.isNotEmpty() && pg < 10)
    }

    private fun addArr(a: JSONArray?, tp: String, d: MutableList<SearchResponse>) {
        if (a == null) return; val tv = if (tp == "movie") TvType.Movie else TvType.TvSeries
        for (i in 0 until a.length()) {
            val it = a.getJSONObject(i); val id = it.optInt("id", 0).takeIf { it > 0 } ?: continue
            d.add(newMovieSearchResponse(it.str("title") ?: it.str("name") ?: "?", "$tp/$id", tv) {
                this.posterUrl = poster(it.str("poster_path"))
                it.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) }
            })
        }
    }

    // ═══════════════════════════════════════════
    //  LOAD — Movie
    // ═══════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        val sg = url.trimEnd('/').split("/"); if (sg.size < 2) return null
        val id = sg.last(); val tp = sg[sg.size - 2]
        return when (tp) { "movie" -> loadMovie(id); "tv" -> loadTv(id); else -> null }
    }

    private suspend fun loadMovie(id: String): LoadResponse? {
        val j = tmdb("movie/$id?append_to_response=credits,videos,recommendations,external_ids,images") ?: return null
        val t = j.str("title") ?: return null
        val yr = j.str("release_date")?.substringBefore("-") ?: ""
        val imdb = j.optJSONObject("external_ids")?.str("imdb_id") ?: j.str("imdb_id")
        val data = "movie|$id|${java.net.URLEncoder.encode(t, "UTF-8")}|$yr|${imdb ?: ""}"
        return newMovieLoadResponse(t, "movie/$id", TvType.Movie, data) {
            this.posterUrl = poster(j.str("poster_path"))
            this.backgroundPosterUrl = poster(j.str("backdrop_path"), "w780")
            this.plot = j.str("overview"); this.year = yr.toIntOrNull()
            j.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) }
            this.tags = j.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.getJSONObject(it).str("name") } }
            this.actors = j.optJSONObject("credits")?.optJSONArray("cast")?.let { a ->
                (0 until minOf(a.length(), 15)).mapNotNull { i ->
                    val p = a.getJSONObject(i); p.str("name")?.let {
                        ActorData(Actor(it, poster(p.str("profile_path"), "w185")), roleString = p.str("character"))
                    }
                }
            }
            var tr: String? = null; j.optJSONObject("videos")?.optJSONArray("results")?.let { a ->
                for (i in 0 until a.length()) { val v = a.getJSONObject(i); if (v.optString("type") == "Trailer" && v.optString("site") == "YouTube") { tr = "https://www.youtube.com/watch?v=${v.optString("key")}"; break } }
            }
            if (tr != null) addTrailer(tr); imdb?.let { addImdbId(it) }
            j.optInt("runtime", 0).takeIf { it > 0 }?.let { this.duration = it }
            j.optJSONObject("images")?.optJSONArray("logos")?.let { this.logoUrl = logo(it, lang) }
            this.recommendations = j.optJSONObject("recommendations")?.optJSONArray("results")?.let { a ->
                (0 until minOf(a.length(), 10)).mapNotNull { i ->
                    val it = a.getJSONObject(i); val rid = it.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
                    newMovieSearchResponse(it.str("title") ?: "?", "movie/$rid", TvType.Movie) { this.posterUrl = poster(it.str("poster_path")) }
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    //  LOAD — TV
    // ═══════════════════════════════════════════
    private suspend fun loadTv(id: String): LoadResponse? {
        val j = tmdb("tv/$id?append_to_response=external_ids,credits,recommendations,images") ?: return null
        val t = j.str("name") ?: return null
        val yr = j.str("first_air_date")?.substringBefore("-") ?: ""
        val imdb = j.optJSONObject("external_ids")?.str("imdb_id") ?: j.str("imdb_id")
        val ss = minOf(j.optInt("number_of_seasons", 1), 15)
        val episodes = mutableListOf<Episode>()
        val srz = coroutineScope { (1..ss).map { s -> async { val sj = tmdb("tv/$id/season/$s"); s to (sj?.optJSONArray("episodes")) } }.awaitAll() }
        for ((s, ea) in srz) {
            if (ea == null) continue
            for (j2 in 0 until ea.length()) {
                val e = ea.getJSONObject(j2); val en = e.optInt("episode_number", j2 + 1)
                val edata = "tv|$id|$s|$en|${java.net.URLEncoder.encode(t, "UTF-8")}|$yr|${imdb ?: ""}"
                episodes.add(newEpisode(edata) {
                    this.name = e.str("name") ?: "Episode $en"; this.season = s; this.episode = en
                    this.posterUrl = poster(e.str("still_path"), "w300"); this.description = e.str("overview")
                })
            }
        }
        return newTvSeriesLoadResponse(t, "tv/$id", TvType.TvSeries, episodes) {
            this.posterUrl = poster(j.str("poster_path")); this.backgroundPosterUrl = poster(j.str("backdrop_path"), "w780")
            this.plot = j.str("overview"); this.year = yr.toIntOrNull()
            j.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) }
            this.tags = j.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.getJSONObject(it).str("name") } }
            this.actors = j.optJSONObject("credits")?.optJSONArray("cast")?.let { a ->
                (0 until minOf(a.length(), 15)).mapNotNull { i ->
                    val p = a.getJSONObject(i); p.str("name")?.let {
                        ActorData(Actor(it, poster(p.str("profile_path"), "w185")), roleString = p.str("character"))
                    }
                }
            }
            imdb?.let { addImdbId(it) }
            j.optJSONObject("images")?.optJSONArray("logos")?.let { this.logoUrl = logo(it, lang) }
            this.recommendations = j.optJSONObject("recommendations")?.optJSONArray("results")?.let { a ->
                (0 until minOf(a.length(), 10)).mapNotNull { i ->
                    val it = a.getJSONObject(i); val rid = it.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
                    newMovieSearchResponse(it.str("name") ?: "?", "tv/$rid", TvType.TvSeries) { this.posterUrl = poster(it.str("poster_path")) }
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    //  LOAD LINKS — EVERY vid source
    // ═══════════════════════════════════════════
    private val langNames = mapOf(
        "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
        "pt" to "Portuguese", "ru" to "Russian", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
        "ar" to "Arabic", "hi" to "Hindi", "bn" to "Bengali", "tr" to "Turkish", "th" to "Thai",
        "vi" to "Vietnamese", "id" to "Indonesian", "uk" to "Ukrainian", "el" to "Greek", "bg" to "Bulgarian",
        "fa" to "Persian", "nl" to "Dutch", "sv" to "Swedish", "no" to "Norwegian", "pl" to "Polish"
    )
    private val emittedSubs = mutableSetOf<String>()

    private fun emSub(url: String, raw: String?, sc: (SubtitleFile) -> Unit) {
        if (url in emittedSubs) return; emittedSubs.add(url)
        val c = raw?.trim()?.lowercase()?.substringBefore("-")?.takeIf { it.length in 2..3 } ?: "unknown"
        sc(SubtitleFile(url, langNames[c] ?: c.uppercase()))
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, sc: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit): Boolean {
        emittedSubs.clear()
        val p = data.split("|"); if (p.size < 3) return false
        val tp = p[0]; if (tp !in listOf("movie", "tv")) return false
        val id = p[1]
        val (sid, eid, t, y, imdb) = if (tp == "movie") {
            listOf("1", "1", java.net.URLDecoder.decode(p.getOrElse(2) { "" }, "UTF-8"), p.getOrElse(3) { "" }, p.getOrElse(4) { "" })
        } else {
            listOf(p.getOrElse(2) { "1" }, p.getOrElse(3) { "1" }, java.net.URLDecoder.decode(p.getOrElse(4) { "" }, "UTF-8"), p.getOrElse(5) { "" }, p.getOrElse(6) { "" })
        }

        suspend fun ld(u: String) { loadExtractor(u, mainUrl, sc, cb) }

        // ── ALL embed sources (13 total for movies, 14 for TV) — every vid source ──
        if (tp == "movie") {
            ld("https://vidsrc.to/embed/movie/$id")
            ld("https://vidlink.pro/movie/$id")
            ld("https://nites.is/embed/movie/$id")
            ld("https://www.2embed.cc/embed/movie/$id")
            ld("https://embed.su/embed/movie/$id")
            ld("https://vidsrc.cc/v2/embed/movie/$id")
            ld("https://vidfast.pro/movie/$id")
            ld("https://vidnest.vip/embed/movie/$id")
            ld("https://player.smashy.stream/movie/$id")
            ld("https://autoembed.co/movie/$id")
            ld("https://www.vidking.net/embed/movie/$id")
            ld("https://vidsrc.xyz/embed/movie/$id")
            // VidKing/WingsDatabase direct sources (8 servers, highest quality)
            wdb("movie", id, "1", "1", t, y, imdb, cb, sc)
        } else {
            ld("https://vidsrc.to/embed/tv/$id/$sid/$eid")
            ld("https://vidlink.pro/tv/$id/$sid/$eid")
            ld("https://nites.is/embed/tv/$id/$sid/$eid")
            ld("https://www.2embed.cc/embed/tv/$id/$sid/$eid")
            ld("https://embed.su/embed/tv/$id/$sid/$eid")
            ld("https://vidsrc.cc/v2/embed/tv/$id/$sid/$eid")
            ld("https://vidfast.pro/tv/$id/$sid/$eid")
            ld("https://vidnest.vip/embed/tv/$id/$sid/$eid")
            ld("https://player.smashy.stream/tv/$id/$sid/$eid")
            ld("https://autoembed.co/tv/$id/$sid/$eid")
            ld("https://www.vidking.net/embed/tv/$id/$sid/$eid")
            ld("https://vidsrc.xyz/embed/tv/$id/$sid/$eid")
            wdb("tv", id, sid, eid, t, y, imdb, cb, sc)
        }
        return true
    }

    // ── WingsDatabase 8-server extraction ──
    private suspend fun wdb(mt: String, id: String, s: String, e: String, t: String, y: String, imdb: String, cb: (ExtractorLink) -> Unit, sc: (SubtitleFile) -> Unit) {
        val servers = listOf("jett" to "Jett", "cdn" to "Yoru", "tejo" to "Tejo", "neon2" to "Neon", "ym" to "Sage", "downloader2" to "Cypher", "m4uhd" to "Breach", "hdmovie" to "Vyse")
        val seed: String
        try {
            seed = JSONObject(app.get("https://api.wingsdatabase.com/seed?mediaId=$id", timeout = 10000,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14)", "Accept" to "application/json", "Referer" to "https://www.vidking.net/", "Origin" to "https://www.vidking.net")).text
            ).str("seed") ?: return
        } catch (_: Exception) { return }
        val et = java.net.URLEncoder.encode(java.net.URLEncoder.encode(t, "UTF-8"), "UTF-8")
        for ((k, lb) in servers) {
            if (k == "cdn" && mt != "movie") continue
            try {
                val q = listOf("title" to et, "mediaType" to mt, "year" to y, "episodeId" to e, "seasonId" to s, "tmdbId" to id, "imdbId" to imdb, "enc" to "2", "seed" to seed)
                    .joinToString("&") { (key, value) -> "$key=${java.net.URLEncoder.encode(value, "UTF-8")}" }
                val enc = app.get("https://api.wingsdatabase.com/${k}/sources-with-title?$q", timeout = 15000,
                    headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14)", "Accept" to "application/json, text/plain, */*", "Referer" to "https://www.vidking.net/", "Origin" to "https://www.vidking.net", "Cache-Control" to "no-cache")).text
                if (enc.isBlank() || enc.startsWith("{") || enc.length < 100) continue
                val bd = JSONObject().apply { put("text", enc); put("id", id); put("seed", seed) }
                val rp = JSONObject(app.post("https://enc-dec.app/api/dec-videasy",
                    requestBody = bd.toString().toRequestBody("application/json".toMediaTypeOrNull()), timeout = 20000,
                    headers = mapOf("Content-Type" to "application/json")).text)
                if (rp.optInt("status", -1) != 200) continue
                val obj = rp.optJSONObject("result") ?: continue
                val srcs = obj.optJSONArray("sources") ?: continue
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
            } catch (_: Exception) { continue }
        }
    }
}
