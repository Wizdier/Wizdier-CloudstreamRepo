package com.wizdier.movies67

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.json.JSONArray

class Movies67Provider : MainAPI() {
    override var mainUrl = "https://67movies.nl"
    override var name = "67Movies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true

    private val tmdbKey = "98ae14df2b8d8f8f8136499daf79f0e0"
    private val imgBase = "https://image.tmdb.org/t/p"

    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie,
        TvType.AsianDrama, TvType.Documentary
    )

    override val mainPage = mainPageOf(
        "trending_movie_day" to "Trending Movies",
        "trending_tv_day" to "Trending TV Shows",
        "movie_now_playing" to "Now Playing",
        "movie_upcoming" to "Upcoming Movies",
        "movie_popular" to "Popular Movies",
        "tv_popular" to "Popular TV Shows",
        "movie_top_rated" to "Top Rated Movies",
        "tv_top_rated" to "Top Rated TV Shows",
        "tv_airing_today" to "Airing Today",
        "trending_all_day" to "Trending Today (All)",
        "discover_movie_28" to "Action",
        "discover_movie_35" to "Comedy",
        "discover_movie_27" to "Horror",
        "discover_movie_10749" to "Romance",
        "discover_movie_878" to "Sci-Fi",
        "discover_movie_16" to "Animation",
        "discover_tv_18" to "Drama TV",
        "discover_tv_10765" to "Sci-Fi & Fantasy TV",
        "discover_tv_10759" to "Action & Adventure TV"
    )

    // ── Helpers ──
    private fun poster(p: String?, sz: String = "w342") = p?.takeIf { !it.isNullOrBlank() }?.let { "$imgBase/$sz$it" }
    private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k, "").takeIf { it.isNotBlank() && it != "null" }
    private suspend fun tmdb(ep: String) = try {
        val s = if (ep.contains("?")) "&" else "?"
        JSONObject(app.get("https://api.themoviedb.org/3/$ep${s}api_key=$tmdbKey", timeout = 10_000).text)
    } catch (e: Exception) { Log.e(name, "TMDB: ${e.message}"); null }

    private fun logo(logos: JSONArray, lng: String?): String? {
        if (logos.length() == 0) return null
        val lang = lng?.trim()?.lowercase()?.substringBefore("-")
        var enSvg: JSONObject? = null; var any: JSONObject? = null; var anySvg: JSONObject? = null
        for (i in 0 until logos.length()) {
            val l = logos.optJSONObject(i) ?: continue; val p = l.optString("file_path", ""); if (p.isBlank()) continue
            val ll = l.optString("iso_639_1").trim().lowercase(); val sv = p.endsWith(".svg", true)
            if (ll == lang && !sv) return "$imgBase/w500$p"
            if (ll == lang && sv && enSvg == null) enSvg = l
            if (!sv && any == null) any = l; if (sv && anySvg == null) anySvg = l
        }
        return (enSvg ?: any ?: anySvg)?.optString("file_path")?.takeIf { it.isNotBlank() }?.let { "$imgBase/w500$it" }
    }

    private fun extImdb(j: JSONObject) = j.optJSONObject("external_ids")?.str("imdb_id")

    // ── SEARCH ──
    override suspend fun search(q: String): List<SearchResponse> {
        if (q.isBlank()) return emptyList()
        return coroutineScope { sr("movie", q) + sr("tv", q) }
    }
    private suspend fun sr(tp: String, q: String) = tmdb("search/$tp?query=${java.net.URLEncoder.encode(q, "UTF-8")}")
        ?.optJSONArray("results")?.let { a -> (0 until a.length()).mapNotNull { i -> val it = a.getJSONObject(i); val id = it.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
            newMovieSearchResponse(it.str("title") ?: it.str("name") ?: "?", "$tp/$id", if (tp == "movie") TvType.Movie else TvType.TvSeries) {
                this.posterUrl = poster(it.str("poster_path")); it.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) } } } } ?: emptyList()

    // ── HOME PAGE ──
    override suspend fun getMainPage(pg: Int, req: MainPageRequest): HomePageResponse {
        val d = req.data; val r = mutableListOf<SearchResponse>()
        when {
            d.startsWith("trending_") -> { val p = d.removePrefix("trending_").split("_")
                tmdb("trending/${p[0]}/${p.getOrElse(1) { "day" }}?page=$pg")?.optJSONArray("results")?.let { a ->
                    for (i in 0 until a.length()) { val it = a.getJSONObject(i); val id = it.optInt("id", 0).takeIf { it > 0 } ?: continue; val mt = it.str("media_type") ?: "movie"
                        r.add(newMovieSearchResponse(it.str("title") ?: it.str("name") ?: "?", "$mt/$id", if (mt == "movie") TvType.Movie else TvType.TvSeries) {
                            this.posterUrl = poster(it.str("poster_path")); it.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) } }) } } }
            d.endsWith("_top_rated") || d.endsWith("_popular") -> { val sx = if (d.endsWith("_top_rated")) "_top_rated" else "_popular"; val tp = d.removeSuffix(sx)
                addArr(tmdb("$tp/${if (sx == "_top_rated") "top_rated" else "popular"}?page=$pg")?.optJSONArray("results"), tp, r) }
            d.startsWith("movie_now_playing") -> addArr(tmdb("movie/now_playing?page=$pg")?.optJSONArray("results"), "movie", r)
            d.startsWith("movie_upcoming") -> addArr(tmdb("movie/upcoming?page=$pg")?.optJSONArray("results"), "movie", r)
            d.startsWith("tv_airing_today") -> addArr(tmdb("tv/airing_today?page=$pg")?.optJSONArray("results"), "tv", r)
            d.startsWith("discover_") -> { val p = d.removePrefix("discover_").split("_"); val tp = p[0]; val pm = p.getOrElse(1) { "" }
                val q = mutableListOf<String>(); pm.toIntOrNull()?.let { q.add("with_genres=$it") }; if (pm.length == 2 && pm !in listOf("tv", "mo")) q.add("with_original_language=$pm")
                q.add("sort_by=popularity.desc"); q.add("page=$pg"); addArr(tmdb("discover/$tp?${q.joinToString("&")}")?.optJSONArray("results"), tp, r) }
        }
        return newHomePageResponse(req.name, r, r.isNotEmpty() && pg < 10)
    }
    private fun addArr(a: JSONArray?, tp: String, d: MutableList<SearchResponse>) {
        if (a == null) return; val tv = if (tp == "movie") TvType.Movie else TvType.TvSeries
        for (i in 0 until a.length()) { val it = a.getJSONObject(i); val id = it.optInt("id", 0).takeIf { it > 0 } ?: continue
            d.add(newMovieSearchResponse(it.str("title") ?: it.str("name") ?: "?", "$tp/$id", tv) {
                this.posterUrl = poster(it.str("poster_path")); it.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) } }) }
    }

    // ── LOAD ──
    override suspend fun load(url: String): LoadResponse? {
        val sg = url.trimEnd('/').split("/"); if (sg.size < 2) return null
        val id = sg.last(); val tp = sg[sg.size - 2]
        return when (tp) { "movie" -> loadMovie(id); "tv" -> loadTv(id); else -> null }
    }

    private suspend fun loadMovie(id: String): LoadResponse? {
        val j = tmdb("movie/$id?append_to_response=credits,videos,recommendations,external_ids,images") ?: return null
        val t = j.str("title") ?: return null; val yr = j.str("release_date")?.substringBefore("-") ?: ""; val imdb = extImdb(j)
        val data = "movie|$id|${java.net.URLEncoder.encode(t, "UTF-8")}|$yr|${imdb ?: ""}"
        return newMovieLoadResponse(t, "movie/$id", TvType.Movie, data) {
            this.posterUrl = poster(j.str("poster_path")); this.backgroundPosterUrl = poster(j.str("backdrop_path"), "w780")
            this.plot = j.str("overview"); this.year = yr.toIntOrNull()
            j.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) }
            this.tags = j.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
            this.actors = j.optJSONObject("credits")?.optJSONArray("cast")?.let { a ->
                (0 until minOf(a.length(), 15)).mapNotNull { i -> val p = a.getJSONObject(i); p.str("name")?.let { ActorData(Actor(it, poster(p.str("profile_path"), "w185")), roleString = p.str("character")) } } }
            var tr: String? = null; j.optJSONObject("videos")?.optJSONArray("results")?.let { a -> for (i in 0 until a.length()) { val v = a.getJSONObject(i); if (v.optString("type") == "Trailer" && v.optString("site") == "YouTube") { tr = "https://www.youtube.com/watch?v=${v.optString("key")}"; break } } }
            if (tr != null) addTrailer(tr); imdb?.let { addImdbId(it) }; j.optInt("runtime", 0).takeIf { it > 0 }?.let { this.duration = it }
            j.optJSONObject("images")?.optJSONArray("logos")?.let { this.logoUrl = logo(it, lang) }
            this.recommendations = j.optJSONObject("recommendations")?.optJSONArray("results")?.let { a ->
                (0 until minOf(a.length(), 10)).mapNotNull { i -> val it = a.getJSONObject(i); val rid = it.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
                    newMovieSearchResponse(it.str("title") ?: "?", "movie/$rid", TvType.Movie) { this.posterUrl = poster(it.str("poster_path")) } } }
        }
    }

    private suspend fun loadTv(id: String): LoadResponse? {
        val j = tmdb("tv/$id?append_to_response=external_ids,credits,recommendations,images") ?: return null
        val t = j.str("name") ?: return null; val yr = j.str("first_air_date")?.substringBefore("-") ?: ""; val imdb = extImdb(j) ?: j.str("imdb_id")
        val ss = minOf(j.optInt("number_of_seasons", 1), 15); val episodes = mutableListOf<Episode>()
        val szn = coroutineScope { (1..ss).map { s -> async { val sj = tmdb("tv/$id/season/$s"); s to (sj?.optJSONArray("episodes")) } }.awaitAll() }
        for ((s, ea) in szn) { if (ea == null) continue; for (j2 in 0 until ea.length()) { val e = ea.getJSONObject(j2); val en = e.optInt("episode_number", j2 + 1)
            val edata = "tv|$id|$s|$en|${java.net.URLEncoder.encode(t, "UTF-8")}|$yr|${imdb ?: ""}"
            episodes.add(newEpisode(edata) { this.name = e.str("name") ?: "Episode $en"; this.season = s; this.episode = en; this.posterUrl = poster(e.str("still_path"), "w300"); this.description = e.str("overview") }) } }
        return newTvSeriesLoadResponse(t, "tv/$id", TvType.TvSeries, episodes) {
            this.posterUrl = poster(j.str("poster_path")); this.backgroundPosterUrl = poster(j.str("backdrop_path"), "w780"); this.plot = j.str("overview"); this.year = yr.toIntOrNull()
            j.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { this.score = Score.from10(it) }
            this.tags = j.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
            this.actors = j.optJSONObject("credits")?.optJSONArray("cast")?.let { a -> (0 until minOf(a.length(), 15)).mapNotNull { i -> val p = a.getJSONObject(i); p.str("name")?.let { ActorData(Actor(it, poster(p.str("profile_path"), "w185")), roleString = p.str("character")) } } }
            imdb?.let { addImdbId(it) }; j.optJSONObject("images")?.optJSONArray("logos")?.let { this.logoUrl = logo(it, lang) }
            this.recommendations = j.optJSONObject("recommendations")?.optJSONArray("results")?.let { a ->
                (0 until minOf(a.length(), 10)).mapNotNull { i -> val it = a.getJSONObject(i); val rid = it.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
                    newMovieSearchResponse(it.str("name") ?: "?", "tv/$rid", TvType.TvSeries) { this.posterUrl = poster(it.str("poster_path")) } } }
        }
    }

    // ── LOAD LINKS ──
    private val langNames = mapOf("en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
        "pt" to "Portuguese", "ru" to "Russian", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "ar" to "Arabic",
        "hi" to "Hindi", "tr" to "Turkish", "th" to "Thai", "vi" to "Vietnamese", "id" to "Indonesian", "nl" to "Dutch",
        "sv" to "Swedish", "no" to "Norwegian", "pl" to "Polish", "bg" to "Bulgarian", "el" to "Greek", "fa" to "Persian")
    private val emittedSubs = mutableSetOf<String>()

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

        // Primary: VidLove.cc (the site's actual player)
        if (tp == "movie") ld("https://player.vidlove.cc/embed/movie/$id") else ld("https://player.vidlove.cc/embed/tv/$id/$sid/$eid")

        // Fallback embed sources
        if (tp == "movie") {
            ld("https://vidsrc.to/embed/movie/$id"); ld("https://vidlink.pro/movie/$id")
            ld("https://nites.is/embed/movie/$id"); ld("https://www.2embed.cc/embed/movie/$id")
            ld("https://embed.su/embed/movie/$id"); ld("https://vidsrc.cc/v2/embed/movie/$id")
        } else {
            ld("https://vidsrc.to/embed/tv/$id/$sid/$eid"); ld("https://vidlink.pro/tv/$id/$sid/$eid")
            ld("https://nites.is/embed/tv/$id/$sid/$eid"); ld("https://www.2embed.cc/embed/tv/$id/$sid/$eid")
            ld("https://embed.su/embed/tv/$id/$sid/$eid"); ld("https://vidsrc.cc/v2/embed/tv/$id/$sid/$eid")
        }

        return true
    }

    private fun emSub(url: String, raw: String?, sc: (SubtitleFile) -> Unit) {
        if (url in emittedSubs) return; emittedSubs.add(url)
        val c = raw?.trim()?.lowercase()?.substringBefore("-")?.takeIf { it.length in 2..3 } ?: "unknown"
        sc(SubtitleFile(url, langNames[c] ?: c.uppercase()))
    }
}
