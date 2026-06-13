package com.wizdier

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// ─────────────────────────────────────────────────────────────────────────────
// MetadataEnricher
//
// Pulls rich metadata from TMDB / AniList / ani.zip and packs it into
// one MetaInfo blob that the plugin's load() can splat onto a LoadResponse.
//
// Public surface (all suspend, all best-effort — never throws):
//   • enrichMovieOrTv(rawTitle, hintYear?, seasonHint?)
//   • enrichAnime(rawTitle, seasonHint?)
//   • Returned MetaInfo includes:
//       - landscape backdrop, title logo PNG, YouTube trailer
//       - IMDB / MAL / AniList / Kitsu IDs
//       - per-episode stills + overviews + ratings (for the target season)
//       - "recommendations" list — title, poster, year — for the More-like-this
//         carousel CloudStream renders below the synopsis
//
// All TMDB requests are hits to the public v3 API with a read-only key. No
// secrets, no auth, no rate-limit dance — TMDB allows ~40 req/10s per IP
// which is way more than this plugin ever needs.
//
// Network safety: every call is wrapped in runCatching. A flaky API can never
// take down `load()`.
// ─────────────────────────────────────────────────────────────────────────────
internal object MetadataEnricher {

    private const val TMDB_API = "https://api.themoviedb.org/3"
    private const val TMDB_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"
    private const val IMG_BASE = "https://image.tmdb.org/t/p"

    // TTL cache so repeat loads of the same title return instantly.
    private val metaCache = CineplexConcurrent.TtlCache<String, MetaInfo>(ttlMs = 10 * 60 * 1000L)

    data class EpisodeMeta(
        val name: String? = null,
        val overview: String? = null,
        val stillUrl: String? = null,    // landscape episode screenshot
        val rating: Double? = null,      // 0..10
        val airDate: Long? = null,       // epoch millis
    )

    data class RecommendationItem(
        val title: String,
        val tmdbId: Int,
        val mediaType: String,           // "movie" | "tv"
        val posterUrl: String?,
        val year: Int?,
    )

    data class ActorMeta(
        val name: String,
        val role: String? = null,
        val imageUrl: String? = null,
    )

    data class MetaInfo(
        val title: String? = null,
        val originalTitle: String? = null,
        val plot: String? = null,
        val rating: Double? = null,
        val year: Int? = null,
        val posterUrl: String? = null,
        val backdropUrl: String? = null,
        val logoUrl: String? = null,
        val trailerUrl: String? = null,
        val tmdbId: Int? = null,
        val mediaType: String? = null,   // "movie" | "tv" | null
        val imdbId: String? = null,
        val simklId: Int? = null,
        val malId: Int? = null,
        val anilistId: Int? = null,
        val kitsuId: String? = null,
        val tags: List<String>? = null,
        val isAnimeHint: Boolean = false,
        // Per-target-season episode metadata (parallel-indexed when possible).
        // Empty list if season fetch failed or media is a movie.
        val episodes: List<EpisodeMeta> = emptyList(),
        val recommendations: List<RecommendationItem> = emptyList(),
        val actors: List<ActorMeta> = emptyList(),
    )

    // Strip junk that breaks remote searches.
    fun cleanTitle(raw: String): String =
        raw.replace(Regex("""\[[^\]]+\]"""), " ")
            .replace(Regex("""\([^)]+\)"""), " ")
            .replace(Regex("""(?i)\b(season|s)\s*\d+\b"""), " ")
            .replace(Regex("""(?i)\b(part|chapter)\s*\d+\b"""), " ")
            .replace(
                Regex(
                    """(?i)\b(hd|hdrip|web[- ]?dl|blu[- ]?ray|x264|x265|10bit|""" +
                            """1080p|720p|480p|2160p|4k|hevc|dual[- ]?audio|""" +
                            """bangla|dubbed|subbed)\b"""
                ),
                " "
            )
            .replace(Regex("""\s+"""), " ")
            .trim()

    // ─────────────────────── Movies / generic TV via TMDB ───────────────────

    suspend fun enrichMovieOrTv(
        rawTitle: String,
        hintYear: Int?,
        seasonHint: Int? = null,
    ): MetaInfo {
        val cleaned = cleanTitle(rawTitle).ifBlank { rawTitle }
        // Cache hit → instant return, zero network I/O.
        val cacheKey = "mtv|${cleaned.lowercase()}|$hintYear|$seasonHint"
        metaCache.get(cacheKey)?.let { return it }

        val q = URLEncoder.encode(cleaned, "UTF-8")
        val yearParam = hintYear?.let { "&year=$it" } ?: ""
        val searchUrl = "$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$q&include_adult=false$yearParam"

        val searchRoot = safeJson(searchUrl) ?: return MetaInfo()
        val results = searchRoot.optJSONArray("results") ?: return MetaInfo()
        if (results.length() == 0) return MetaInfo()

        var picked: JSONObject? = null
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val mt = r.optString("media_type")
            if (mt == "movie" || mt == "tv") { picked = r; break }
        }
        picked ?: return MetaInfo()

        val mediaType = picked.getString("media_type")
        val tmdbId = picked.optInt("id").takeIf { it != 0 } ?: return MetaInfo()

        val details = safeJson(
            "$TMDB_API/$mediaType/$tmdbId?api_key=$TMDB_KEY" +
                    "&append_to_response=external_ids,videos,images,recommendations,alternative_titles,credits"
        ) ?: return MetaInfo(tmdbId = tmdbId, mediaType = mediaType)

        val titleKey = if (mediaType == "movie") "title" else "name"
        val origKey = if (mediaType == "movie") "original_title" else "original_name"
        val dateKey = if (mediaType == "movie") "release_date" else "first_air_date"

        val titleFromTmdb = details.optStringOrNull(titleKey)
        val origTitle = details.optStringOrNull(origKey)
        val plot = details.optStringOrNull("overview")
        val rating = details.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
        val year = details.optString(dateKey, "").take(4).toIntOrNull()
        val poster = details.optString("poster_path", "").toTmdbImg("w500")
        val backdrop = details.optString("backdrop_path", "").toTmdbImg("original")
        val originalLanguage = details.optString("original_language", "")

        val logo = pickLogo(details.optJSONObject("images")?.optJSONArray("logos"))
        val trailer = pickTrailer(details.optJSONObject("videos")?.optJSONArray("results"))

        val imdbId = (details.optJSONObject("external_ids")?.optStringOrNull("imdb_id")
            ?: details.optStringOrNull("imdb_id"))
            ?.takeIf { it.startsWith("tt") }

        val genres = mutableListOf<String>()
        details.optJSONArray("genres")?.forEachObject {
            it.optStringOrNull("name")?.let(genres::add)
        }

        val isAnimeHint = originalLanguage == "ja" &&
                genres.any { it.equals("Animation", true) }

        // Episode list for the target season (TV only).
        val episodes = if (mediaType == "tv") {
            fetchTmdbSeasonEpisodes(tmdbId, seasonHint ?: 1)
        } else emptyList()

        val recs = parseRecommendations(
            details.optJSONObject("recommendations")?.optJSONArray("results")
        )

        val simklId = fetchSimklId(imdbId, mediaType)
        val actors = parseActors(details.optJSONObject("credits")?.optJSONArray("cast"))

        val result = MetaInfo(
            title = titleFromTmdb,
            originalTitle = origTitle,
            plot = plot,
            rating = rating,
            year = year,
            posterUrl = poster,
            backdropUrl = backdrop,
            logoUrl = logo,
            trailerUrl = trailer,
            tmdbId = tmdbId,
            mediaType = mediaType,
            imdbId = imdbId,
            simklId = simklId,
            tags = genres.takeIf { it.isNotEmpty() },
            isAnimeHint = isAnimeHint,
            episodes = episodes,
            recommendations = recs,
            actors = actors,
        )
        metaCache.put(cacheKey, result)
        return result
    }

    // ─────────────────────────────── Anime ──────────────────────────────────

    suspend fun enrichAnime(rawTitle: String, seasonHint: Int? = null): MetaInfo {
        val cleaned = cleanTitle(rawTitle).ifBlank { rawTitle }
        // Cache hit → instant return.
        val cacheKey = "anime|${cleaned.lowercase()}|$seasonHint"
        metaCache.get(cacheKey)?.let { return it }

        val anilistQuery = """
            query (${'$'}search: String) {
              Page(perPage: 5) {
                media(search: ${'$'}search, type: ANIME) {
                  id
                  idMal
                  title { english romaji native }
                  description(asHtml: false)
                  coverImage { extraLarge large }
                  bannerImage
                  averageScore
                  startDate { year }
                  genres
                }
              }
            }
        """.trimIndent()

        val payload = JSONObject().apply {
            put("query", anilistQuery)
            put("variables", JSONObject().put("search", cleaned))
        }.toString()

        val anilistText = runCatching {
            app.post(
                "https://graphql.anilist.co",
                headers = mapOf("Content-Type" to "application/json"),
                requestBody = payload.toRequestBody("application/json".toMediaTypeOrNull()),
                timeout = 8000
            ).text
        }.getOrNull()
            ?: return enrichMovieOrTv(rawTitle, null, seasonHint).copy(isAnimeHint = true)

        val media = runCatching {
            JSONObject(anilistText).optJSONObject("data")
                ?.optJSONObject("Page")
                ?.optJSONArray("media")
        }.getOrNull()
        if (media == null || media.length() == 0) {
            return enrichMovieOrTv(rawTitle, null, seasonHint).copy(isAnimeHint = true)
        }

        val best = media.getJSONObject(0)
        val aniId = best.optInt("id").takeIf { it != 0 }
        val malId = best.optInt("idMal").takeIf { it != 0 }
        val titleObj = best.optJSONObject("title")
        val eng = titleObj?.optStringOrNull("english")
        val rom = titleObj?.optStringOrNull("romaji")
        val nat = titleObj?.optStringOrNull("native")

        val cover = best.optJSONObject("coverImage")
        val posterFromAni = cover?.optStringOrNull("extraLarge") ?: cover?.optStringOrNull("large")
        val bannerFromAni = best.optStringOrNull("bannerImage")
        val plot = best.optStringOrNull("description")
            ?.replace(Regex("<[^>]+>"), "")
            ?.replace("&quot;", "\"")?.replace("&amp;", "&")?.trim()
        val score = best.optDouble("averageScore", 0.0).takeIf { it > 0.0 }?.let { it / 10.0 }
        val year = best.optJSONObject("startDate")?.optInt("year")?.takeIf { it != 0 }

        val genres = mutableListOf<String>()
        best.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i, null)?.takeIf { it.isNotBlank() }?.let(genres::add)
            }
        }

        var kitsuId: String? = null
        var tmdbId: Int? = null
        if (aniId != null) {
            runCatching {
                val mappings = JSONObject(
                    app.get("https://api.ani.zip/mappings?anilist_id=$aniId", timeout = 8000).text
                ).optJSONObject("mappings")
                kitsuId = mappings?.optStringOrNull("kitsu_id")
                tmdbId = mappings?.optStringOrNull("themoviedb_id")?.toIntOrNull()
            }
        }

        var logo: String? = null
        var betterBackdrop: String? = null
        var trailer: String? = null
        var imdbId: String? = null
        var recs: List<RecommendationItem> = emptyList()
        var episodes: List<EpisodeMeta> = emptyList()
        if (tmdbId != null) {
            runCatching {
                val tv = JSONObject(
                    app.get(
                        "$TMDB_API/tv/$tmdbId?api_key=$TMDB_KEY" +
                                "&append_to_response=images,videos,external_ids,recommendations",
                        timeout = 8000
                    ).text
                )
                logo = pickLogo(tv.optJSONObject("images")?.optJSONArray("logos"))
                trailer = pickTrailer(tv.optJSONObject("videos")?.optJSONArray("results"))
                val bp = tv.optString("backdrop_path", "")
                betterBackdrop = bp.toTmdbImg("original")
                imdbId = tv.optJSONObject("external_ids")
                    ?.optStringOrNull("imdb_id")?.takeIf { it.startsWith("tt") }
                recs = parseRecommendations(
                    tv.optJSONObject("recommendations")?.optJSONArray("results")
                )
            }
            episodes = fetchTmdbSeasonEpisodes(tmdbId, seasonHint ?: 1)
        }

        val simklId = fetchSimklId(imdbId, "tv")

        val result = MetaInfo(
            title = eng ?: rom ?: nat ?: rawTitle,
            originalTitle = rom ?: nat,
            plot = plot,
            rating = score,
            year = year,
            posterUrl = posterFromAni,
            backdropUrl = betterBackdrop ?: bannerFromAni,
            logoUrl = logo,
            trailerUrl = trailer,
            tmdbId = tmdbId,
            mediaType = if (tmdbId != null) "tv" else null,
            imdbId = imdbId,
            simklId = simklId,
            malId = malId,
            anilistId = aniId,
            kitsuId = kitsuId,
            tags = genres.takeIf { it.isNotEmpty() },
            isAnimeHint = true,
            episodes = episodes,
            recommendations = recs,
        )
        metaCache.put(cacheKey, result)
        return result
    }

    // ─────────────────────────────── helpers ────────────────────────────────

    private suspend fun fetchTmdbSeasonEpisodes(tmdbId: Int, season: Int): List<EpisodeMeta> {
        val url = "$TMDB_API/tv/$tmdbId/season/$season?api_key=$TMDB_KEY"
        val json = safeJson(url) ?: return emptyList()
        val arr = json.optJSONArray("episodes") ?: return emptyList()
        val out = mutableListOf<EpisodeMeta>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val stillPath = e.optString("still_path", "").takeIf { it.isNotBlank() && it != "null" }
            out += EpisodeMeta(
                name = e.optStringOrNull("name"),
                overview = e.optStringOrNull("overview"),
                stillUrl = stillPath?.let { "$IMG_BASE/original$it" },
                rating = e.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
                airDate = parseAirDate(e.optStringOrNull("air_date")),
            )
        }
        return out
    }

    private suspend fun fetchSimklId(imdbId: String?, mediaType: String?): Int? {
        if (imdbId.isNullOrBlank()) return null
        val type = if (mediaType == "movie") "movies" else "tv"
        return runCatching {
            val url = "https://api.simkl.com/$type/${URLEncoder.encode(imdbId, "UTF-8")}?client_id=%20&extended=full"
            val res = app.get(url, timeout = 8000).text.trim()
            if (!res.startsWith("{")) return@runCatching null
            JSONObject(res).optJSONObject("ids")?.optInt("simkl")?.takeIf { it != 0 }
        }.getOrNull()
    }

    private fun parseActors(arr: JSONArray?): List<ActorMeta> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = mutableListOf<ActorMeta>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val name = a.optStringOrNull("name") ?: a.optStringOrNull("original_name") ?: continue
            val role = a.optStringOrNull("character")
            val image = a.optString("profile_path", "").toTmdbImg("w185")
            out += ActorMeta(name = name, role = role, imageUrl = image)
            if (out.size >= 20) break
        }
        return out
    }

    private fun parseRecommendations(arr: JSONArray?): List<RecommendationItem> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = mutableListOf<RecommendationItem>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val mt = r.optString("media_type")
            val mediaType = when {
                mt == "movie" || mt == "tv" -> mt
                r.has("title") -> "movie"     // /recommendations on /movie returns no media_type
                r.has("name") -> "tv"
                else -> continue
            }
            val id = r.optInt("id").takeIf { it != 0 } ?: continue
            val title = (if (mediaType == "movie") r.optStringOrNull("title")
                         else r.optStringOrNull("name")) ?: continue
            val poster = r.optString("poster_path", "").toTmdbImg("w500")
            val dateKey = if (mediaType == "movie") "release_date" else "first_air_date"
            val year = r.optString(dateKey, "").take(4).toIntOrNull()
            out += RecommendationItem(title, id, mediaType, poster, year)
            if (out.size >= 20) break
        }
        return out
    }

    private fun parseAirDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        // TMDB returns yyyy-MM-dd
        val parts = s.split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        return runCatching {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(y, m - 1, d, 0, 0, 0)
            cal.timeInMillis
        }.getOrNull()
    }

    private suspend fun safeJson(url: String): JSONObject? = runCatching {
        JSONObject(app.get(url, timeout = 8000).text)
    }.getOrNull()

    private fun String.toTmdbImg(size: String): String? =
        takeIf { it.isNotBlank() && it != "null" }?.let { "$IMG_BASE/$size$it" }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null
        else optString(key, "").takeIf { it.isNotBlank() && it != "null" }

    private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }

    private fun pickLogo(logos: JSONArray?): String? {
        if (logos == null || logos.length() == 0) return null
        var enSvg: String? = null
        var anyNonSvg: String? = null
        var anySvg: String? = null
        for (i in 0 until logos.length()) {
            val l = logos.optJSONObject(i) ?: continue
            val p = l.optString("file_path", "")
            if (p.isBlank()) continue
            val lang = l.optString("iso_639_1", "").trim().lowercase()
            val isSvg = p.endsWith(".svg", true)
            val url = "$IMG_BASE/w500$p"
            if (lang == "en" && !isSvg) return url
            if (lang == "en" && isSvg && enSvg == null) enSvg = url
            if (!isSvg && anyNonSvg == null) anyNonSvg = url
            if (isSvg && anySvg == null) anySvg = url
        }
        return enSvg ?: anyNonSvg ?: anySvg
    }

    private fun pickTrailer(videos: JSONArray?): String? {
        if (videos == null || videos.length() == 0) return null
        var officialTrailer: String? = null
        var anyTrailer: String? = null
        var fallback: String? = null
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (!v.optString("site").equals("YouTube", true)) continue
            val key = v.optString("key", "").takeIf { it.isNotBlank() } ?: continue
            val type = v.optString("type", "")
            val isOfficial = v.optBoolean("official", false)
            val url = "https://www.youtube.com/watch?v=$key"
            when {
                type.equals("Trailer", true) && isOfficial && officialTrailer == null -> officialTrailer = url
                type.equals("Trailer", true) && anyTrailer == null -> anyTrailer = url
                (type.equals("Teaser", true) || type.equals("Clip", true)) && fallback == null -> fallback = url
            }
        }
        return officialTrailer ?: anyTrailer ?: fallback
    }
}
