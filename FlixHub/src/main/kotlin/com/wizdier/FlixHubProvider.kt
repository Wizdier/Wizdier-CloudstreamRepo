package com.wizdier

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * FlixHub — Cloudstream extension for flixhub.net
 *
 * BDIX-accessible streaming site with movies and TV series.
 *
 * Page structure (from HTML analysis):
 *   • Watch movie:  /watch/movie/<slug>
 *   • Watch series: /watch/series/<slug>
 *   • Watch episode: /watch/episode/<episode-slug>
 *   • Stream URL:   data-download-url attribute on the Download button
 *                   = https://flixhub.net/stream/movie/<slug>
 *                   = https://flixhub.net/stream/episode/<episode-slug>
 *   • Video element: <source type="video/x-matroska"> (no src — loaded via JS)
 *   • Poster:       data-poster on <video> = TMDB image URL
 *   • Metadata:     player-mobile-info-title section has "Title Rating · Year · Runtime · Genre"
 *   • Synopsis:     player-mobile-synopsis-wrap section
 *
 * TMDB enrichment uses the same API key as Wizstream (98ae14df2b8d8f8f8136499daf79f0e0)
 * to fetch posters, backdrops, logos, trailers, genres, cast, and recommendations.
 */
class FlixHubProvider : MainAPI() {
    override var mainUrl = "https://flixhub.net"
    override var name = "FlixHub"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )

    private val cfKiller = CloudflareKiller()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    // ═══════════════════════════════════════════════════════════════════════
    //  TMDB constants
    // ═══════════════════════════════════════════════════════════════════════

    private val tmdbApiKey = "98ae14df2b8d8f8f8136499daf79f0e0"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImg = "https://image.tmdb.org/t/p"

    // ═══════════════════════════════════════════════════════════════════════
    //  Main pages
    // ═══════════════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "movies?sort=latest" to "Latest Movies",
        "movies?sort=popular" to "Popular Movies",
        "movies?category=hollywood" to "Hollywood",
        "movies?category=bollywood" to "Bollywood",
        "movies?category=south-indian" to "South Indian",
        "movies?category=Animation" to "Animation",
        "tv-series?sort=latest" to "Latest TV Series",
        "tv-series?sort=popular" to "Popular TV Series",
        "movies?category=Drama" to "Drama",
        "movies?category=Action" to "Action",
        "movies?category=Comedy" to "Comedy",
        "movies?category=Horror" to "Horror",
        "movies?category=Romance" to "Romance",
        "movies?category=Thriller" to "Thriller",
        "movies?category=Adventure" to "Adventure",
        "movies?category=Family" to "Family",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}&page=$page"
        val doc = fetchDocument(url) ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = parseCards(doc)
        val hasNext = doc.select("a[rel=next], a:contains(Next), .pagination a:contains(›)").isNotEmpty()
            || items.size >= 18
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Search
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val url = "$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val doc = fetchDocument(url) ?: return emptyList()
        return parseCards(doc)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Load
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDocument(url) ?: throw ErrorLoadingException("Failed to load $url")

        val isSeries = url.contains("/watch/series/") || url.contains("/watch/episode/")
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = extractPoster(doc)
        val plot = extractSynopsis(doc)
        val meta = extractMetadata(doc)
        val genres = extractGenres(doc)

        // TMDB enrichment — use site poster as PRIMARY (not TMDB) because
        // the site's data-poster is what the user sees in their browser and
        // is guaranteed to be the correct movie. TMDB poster is fallback only.
        val tmdbData = fetchTmdbByTitle(title, meta.year, if (isSeries) "tv" else "movie")

        val finalPoster = poster ?: tmdbData?.posterUrl
        val finalBackdrop = tmdbData?.backdropUrl
        val finalPlot = tmdbData?.plot ?: plot
        val finalGenres = tmdbData?.genres ?: genres
        val finalRating = tmdbData?.rating ?: meta.rating
        val finalYear = tmdbData?.year ?: meta.year
        val finalRuntime = tmdbData?.runtime ?: meta.runtime
        val finalLogo = tmdbData?.logoUrl
        val finalTrailer = tmdbData?.trailerUrl
        val finalActors = tmdbData?.actors
        val finalRecs = tmdbData?.recommendations

        return if (isSeries) {
            val episodes = parseEpisodes(doc)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.plot = finalPlot
                this.year = finalYear
                this.tags = finalGenres
                this.duration = finalRuntime
                runCatching { finalRating?.let { score = Score.from10(it) } }
                runCatching { finalActors?.let { this.actors = it } }
                runCatching { finalLogo?.let { this.logoUrl = it } }
                runCatching { finalTrailer?.let { addTrailer(it) } }
                runCatching { finalRecs?.let { if (it.isNotEmpty()) this.recommendations = it } }
            }
        } else {
            val slug = url.substringAfter("/watch/movie/").substringBefore("?").substringBefore("#")
            val streamUrl = extractStreamUrl(doc, slug, isMovie = true)
            newMovieLoadResponse(title, url, TvType.Movie, streamUrl ?: slug) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.plot = finalPlot
                this.year = finalYear
                this.tags = finalGenres
                this.duration = finalRuntime
                runCatching { finalRating?.let { score = Score.from10(it) } }
                runCatching { finalActors?.let { this.actors = it } }
                runCatching { finalLogo?.let { this.logoUrl = it } }
                runCatching { finalTrailer?.let { addTrailer(it) } }
                runCatching { finalRecs?.let { if (it.isNotEmpty()) this.recommendations = it } }
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
    ): Boolean {
        // The stream URL IS the direct .mkv video file.
        // FlixHub's /stream/movie/<slug> and /stream/episode/<slug> endpoints
        // serve the actual Matroska file directly — no need to fetch and parse.
        // We emit it directly as an ExtractorLink and Cloudstream's player
        // handles the rest.
        val streamUrl = if (data.startsWith("http")) {
            data
        } else if (data.startsWith("/stream/")) {
            "$mainUrl$data"
        } else {
            // It's a slug — construct the stream URL
            "$mainUrl/stream/movie/$data"
        }

        // Emit the stream URL directly as a video link.
        // The .mkv file will be played directly by Cloudstream's player.
        callback(
            newExtractorLink(
                source = "FlixHub",
                name = "FlixHub",
                url = streamUrl,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = mainUrl
                this.quality = getQualityFromUrl(streamUrl)
            }
        )

        // Also try fetching the watch page to find any additional sources
        // (multiple quality options, backup mirrors, etc.)
        runCatching {
            val watchUrl = streamUrl.replace("/stream/", "/watch/")
            val doc = fetchDocument(watchUrl)
            if (doc != null) {
                // Find data-download-url (the primary stream URL — might differ from what we have)
                val dlUrl = doc.selectFirst("[data-download-url]")?.attr("data-download-url")
                if (dlUrl != null && dlUrl.isNotBlank() && dlUrl != streamUrl) {
                    callback(
                        newExtractorLink(
                            source = "FlixHub",
                            name = "FlixHub (Download)",
                            url = dlUrl,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = mainUrl
                            this.quality = getQualityFromUrl(dlUrl)
                        }
                    )
                }

                // Find any additional <source> tags with src attributes
                doc.select("video source[src], source[src]").forEach { el ->
                    val src = el.attr("src")
                    if (src.isNotBlank() && src.startsWith("http") && src != streamUrl) {
                        callback(
                            newExtractorLink(
                                source = "FlixHub",
                                name = "FlixHub (Alt)",
                                url = src,
                                type = if (src.contains(".m3u8", true)) ExtractorLinkType.M3U8
                                       else ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = mainUrl
                                this.quality = getQualityFromUrl(src)
                            }
                        )
                    }
                }

                // Find any <a> tags pointing to /stream/ URLs
                doc.select("a[href*=/stream/]").forEach { el ->
                    val href = el.attr("href")
                    if (href.isNotBlank() && href != streamUrl && href != dlUrl) {
                        callback(
                            newExtractorLink(
                                source = "FlixHub",
                                name = "FlixHub (Mirror)",
                                url = href,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = mainUrl
                                this.quality = getQualityFromUrl(href)
                            }
                        )
                    }
                }
            }
        }

        return true
    }

    private suspend fun emitDirectLink(url: String, callback: (ExtractorLink) -> Unit) {
        val u = url.trim()
        if (u.isBlank()) return
        if (u.contains(".m3u8", true)) {
            M3u8Helper.generateM3u8(
                source = "FlixHub",
                streamUrl = u,
                referer = mainUrl,
                headers = headers,
            ).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = "FlixHub",
                    name = "FlixHub",
                    url = u,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = mainUrl
                    this.quality = getQualityFromUrl(u)
                }
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HTML parsing helpers
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun fetchDocument(url: String): Document? {
        return try {
            val res = app.get(url, headers = headers, interceptor = cfKiller, timeout = 20_000)
            if (res.code !in 200..299) return null
            Jsoup.parse(res.text, url)
        } catch (e: Exception) {
            Log.d("FlixHub", "fetchDocument failed for $url: ${e.message}")
            null
        }
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        return doc.select("article.movie-card-final, article[data-watch-url]").mapNotNull { card ->
            val watchUrl = card.attr("data-watch-url")
                .ifBlank { card.selectFirst("a[href*=/watch/]")?.attr("href") }
                ?: return@mapNotNull null
            val title = card.selectFirst(".movie-card-browse-title, .movie-title, .card-overlay p")?.text()
                ?: card.selectFirst("a[aria-label]")?.attr("aria-label")
                ?: return@mapNotNull null
            val isSeries = watchUrl.contains("/watch/series/")
            val type = if (isSeries) TvType.TvSeries else TvType.Movie

            if (isSeries) {
                newTvSeriesSearchResponse(title, watchUrl, type) {}
            } else {
                newMovieSearchResponse(title, watchUrl, type) {}
            }
        }
    }

    private fun parseEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seenSlugs = mutableSetOf<String>()

        // Episode links: /watch/episode/<slug>
        val epLinks = doc.select("a[href*=/watch/episode/]")
        for (link in epLinks) {
            val href = link.attr("href")
            val slug = href.substringAfter("/episode/").substringBefore("?").substringBefore("#")
            if (slug.isBlank() || slug in seenSlugs) continue
            seenSlugs.add(slug)

            val epText = link.text().trim()
            val epNum = Regex("""(?:Episode\s*)?(\d+)""", RegexOption.IGNORE_CASE)
                .find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: seenSlugs.size

            val streamUrl = "$mainUrl/stream/episode/$slug"
            episodes.add(newEpisode(streamUrl) {
                this.name = "Episode $epNum"
                this.episode = epNum
                this.season = 1
            })
        }

        return episodes.ifEmpty {
            // Fallback: use data-episode-slug from the player
            val epSlug = doc.selectFirst("#theaterPlayer")?.attr("data-episode-slug")
            if (epSlug != null && epSlug.isNotBlank()) {
                listOf(newEpisode("$mainUrl/stream/episode/$epSlug") {
                    this.name = "Episode 1"
                    this.episode = 1
                    this.season = 1
                })
            } else {
                emptyList()
            }
        }
    }

    /**
     * Extract the stream URL from the watch page.
     * The Download button has data-download-url=https://flixhub.net/stream/movie/<slug>
     */
    private fun extractStreamUrl(doc: Document, slug: String, isMovie: Boolean): String? {
        // Try data-download-url attribute on the Download button
        val dlUrl = doc.selectFirst("[data-download-url]")?.attr("data-download-url")
        if (dlUrl != null && dlUrl.isNotBlank()) return dlUrl

        // Try <a href*="/stream/">
        val streamLink = doc.selectFirst("a[href*=/stream/]")?.attr("href")
        if (streamLink != null && streamLink.isNotBlank()) return streamLink

        // Construct the URL from the slug
        val path = if (isMovie) "/stream/movie/$slug" else "/stream/episode/$slug"
        return "$mainUrl$path"
    }

    private fun extractPoster(doc: Document): String? {
        // data-poster on <video> — this is the poster image from the site.
        // It uses TMDB's image CDN (image.tmdb.org) but with /original/ size
        // which is very large. Convert to /w500/ for faster loading.
        val dataPoster = doc.selectFirst("video[data-poster]")?.attr("data-poster")
        if (!dataPoster.isNullOrBlank() && dataPoster.startsWith("http")) {
            // Convert /original/ to /w500/ for smaller, faster-loading poster
            return dataPoster.replace("/original/", "/w500/")
        }
        // TMDB img with src
        val tmdbImgEl = doc.selectFirst("img[src*=image.tmdb.org]")?.attr("src")
        if (!tmdbImgEl.isNullOrBlank()) return tmdbImgEl
        return null
    }

    private fun extractSynopsis(doc: Document): String? {
        val synopsis = doc.selectFirst(".player-mobile-synopsis-wrap, .synopsis, .description, .overview")?.text()
            ?.replace("See more", "")?.trim()
        return synopsis?.takeIf { it.isNotBlank() }
    }

    data class PageMeta(val rating: Double?, val year: Int?, val runtime: Int?, val genre: String?)

    private fun extractMetadata(doc: Document): PageMeta {
        // The info section has text like: "Evil Dead Burn 7.1 · 2026 · 1h 50m · Horror"
        val infoText = doc.selectFirst(".player-mobile-info-title")?.text()
            ?: doc.selectFirst(".movie-info")?.text()
            ?: ""
        val rating = Regex("""(\d+(?:\.\d+)?)\s*·""").find(infoText)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val year = Regex("""·\s*((?:19|20)\d{2})\s*·""").find(infoText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val runtime = Regex("""(\d+)h\s*(\d+)?m""").find(infoText)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            (h * 60 + min).takeIf { it > 0 }
        }
        val genre = Regex("""·\s*([A-Za-z]+)\s*(?:See more|$)""").find(infoText)?.groupValues?.getOrNull(1)
        return PageMeta(rating, year, runtime, genre)
    }

    private fun extractGenres(doc: Document): List<String>? {
        val genres = doc.select("a[href*=category=]").mapNotNull { el ->
            val name = el.text().trim()
            name.takeIf { it.isNotBlank() && it.lowercase() !in listOf("hollywood", "bollywood", "south-indian") }
        }.distinct()
        return genres.takeIf { it.isNotEmpty() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TMDB enrichment
    // ═══════════════════════════════════════════════════════════════════════

    private data class TmdbData(
        val posterUrl: String?,
        val backdropUrl: String?,
        val logoUrl: String?,
        val plot: String?,
        val rating: Double?,
        val year: Int?,
        val runtime: Int?,
        val genres: List<String>?,
        val actors: List<ActorData>?,
        val trailerUrl: String?,
        val recommendations: List<SearchResponse>?,
    )

    private suspend fun fetchTmdbByTitle(title: String, year: Int?, type: String): TmdbData? {
        return try {
            // Step 1: Search TMDB
            val searchParams = mapOf(
                "api_key" to tmdbApiKey,
                "query" to title,
                "year" to year?.toString(),
                "language" to "en-US",
            ).filter { it.value != null }

            val searchUrl = "$tmdbApi/search/${if (type == "tv") "tv" else "movie"}?" +
                searchParams.entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v.toString(), "UTF-8")}"
                }

            val searchRes = app.get(searchUrl, headers = mapOf("Accept" to "application/json"), timeout = 10_000)
            if (searchRes.code !in 200..299) return null

            val searchJson = JSONObject(searchRes.text)
            val results = searchJson.optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            // Pick the first result (most relevant)
            val tmdbId = results.optJSONObject(0)?.optInt("id", 0) ?: return null
            if (tmdbId == 0) return null

            // Step 2: Fetch detailed info
            val detailParams = mapOf(
                "api_key" to tmdbApiKey,
                "append_to_response" to "credits,images,videos,recommendations,external_ids",
                "include_image_language" to "en,null",
                "language" to "en-US",
            )
            val detailUrl = "$tmdbApi/$type/$tmdbId?" +
                detailParams.entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }

            val detailRes = app.get(detailUrl, headers = mapOf("Accept" to "application/json"), timeout = 10_000)
            if (detailRes.code !in 200..299) return null

            val d = JSONObject(detailRes.text)

            val posterUrl = d.optString("poster_path").takeIf { it.isNotBlank() }?.let { "$tmdbImg/w780$it" }
            val backdropUrl = d.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { "$tmdbImg/original$it" }
            val plot = d.optString("overview").takeIf { it.isNotBlank() }
            val rating = d.optDouble("vote_average", 0.0).takeIf { it > 0 }
            val dateStr = if (type == "movie") d.optString("release_date") else d.optString("first_air_date")
            val yr = Regex("""\d{4}""").find(dateStr)?.value?.toIntOrNull()
            val runtime = if (type == "movie") d.optInt("runtime", 0).takeIf { it > 0 }
                else d.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
            val genres = d.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { s -> s.isNotBlank() } }
            }

            // Logo
            val logoUrl = d.optJSONObject("images")?.optJSONArray("logos")?.let { logos ->
                (0 until logos.length()).mapNotNull { i ->
                    val l = logos.optJSONObject(i) ?: return@mapNotNull null
                    val p = l.optString("file_path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val lang = l.optString("iso_639_1").lowercase()
                    if (lang == "en" && !p.endsWith(".svg")) "$tmdbImg/w500$p" else null
                }.firstOrNull()
            }

            // Cast
            val actors = d.optJSONObject("credits")?.optJSONArray("cast")?.let { cast ->
                (0 until minOf(cast.length(), 15)).mapNotNull { i ->
                    val c = cast.optJSONObject(i) ?: return@mapNotNull null
                    val name = c.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val profile = c.optString("profile_path").takeIf { it.isNotBlank() }?.let { "$tmdbImg/w185$it" }
                    val role = c.optString("character").takeIf { it.isNotBlank() }
                    ActorData(Actor(name, profile), roleString = role ?: "")
                }
            }

            // Trailer
            val trailerUrl = d.optJSONObject("videos")?.optJSONArray("results")?.let { vids ->
                (0 until vids.length()).mapNotNull { i ->
                    val v = vids.optJSONObject(i) ?: return@mapNotNull null
                    if (v.optString("site") == "YouTube" && v.optString("type") == "Trailer") {
                        "https://www.youtube.com/watch?v=${v.optString("key")}"
                    } else null
                }.firstOrNull()
            }

            // Recommendations
            val recs = d.optJSONObject("recommendations")?.optJSONArray("results")?.let { arr ->
                (0 until minOf(arr.length(), 15)).mapNotNull { i ->
                    val r = arr.optJSONObject(i) ?: return@mapNotNull null
                    val rTitle = if (type == "movie") r.optString("title") else r.optString("name")
                    if (rTitle.isBlank()) return@mapNotNull null
                    val rId = r.optInt("id", 0)
                    if (rId == 0) return@mapNotNull null
                    val rPoster = r.optString("poster_path").takeIf { it.isNotBlank() }?.let { "$tmdbImg/w500$it" }
                    val rDateStr = if (type == "movie") r.optString("release_date") else r.optString("first_air_date")
                    val rYear = Regex("""\d{4}""").find(rDateStr)?.value?.toIntOrNull()

                    if (type == "movie") {
                        newMovieSearchResponse(rTitle, "$mainUrl/watch/movie/${rTitle.lowercase().replace(" ", "-")}", TvType.Movie) {
                            this.posterUrl = rPoster
                            this.year = rYear
                        }
                    } else {
                        newTvSeriesSearchResponse(rTitle, "$mainUrl/watch/series/${rTitle.lowercase().replace(" ", "-")}", TvType.TvSeries) {
                            this.posterUrl = rPoster
                            this.year = rYear
                        }
                    }
                }
            }

            TmdbData(posterUrl, backdropUrl, logoUrl, plot, rating, yr, runtime, genres, actors, trailerUrl, recs)
        } catch (e: Exception) {
            Log.d("FlixHub", "TMDB enrichment failed: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Media URL helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun isDirectMedia(url: String): Boolean {
        val u = url.lowercase()
        return u.endsWith(".mkv") || u.endsWith(".mp4") || u.endsWith(".webm") ||
            u.endsWith(".m3u8") || u.contains(".mkv?") || u.contains(".mp4?") ||
            u.contains(".m3u8?")
    }

    private fun extractMediaUrls(html: String, baseUrl: String): LinkedHashSet<String> {
        val out = linkedSetOf<String>()
        Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv|\.webm)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m -> out.add(m.value.replace("&amp;", "&")) }
        Regex("""["'](/(?:stream|storage|Data|video)/[^"']+\.(?:m3u8|mp4|mkv|webm)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m -> out.add("$mainUrl${m.groupValues[1]}") }
        return out
    }

    private fun getQualityFromUrl(url: String): Int {
        val n = url.lowercase()
        return when {
            "4k" in n || "2160" in n -> Qualities.P2160.value
            "1440" in n -> Qualities.P1440.value
            "1080" in n -> Qualities.P1080.value
            "720" in n -> Qualities.P720.value
            "480" in n -> Qualities.P480.value
            "360" in n -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
