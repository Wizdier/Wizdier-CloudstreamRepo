package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class NowHDTime : MainAPI() {
    override var mainUrl = "https://nowhdtime.com.bd"
    override var name = "NowHDTime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies?page=" to "Movies",
        "$mainUrl/tv-shows?page=" to "TV Shows",
        "$mainUrl/anime?page=" to "Anime",
        "$mainUrl/trending?page=" to "Trending",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page, headers = headers).document
        val items = doc.select("a[href*='/movie/watch-'], a[href*='/tv-show/watch-']")
            .distinctBy { it.attr("href") }
            .mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null
        val url = if (href.startsWith("http")) href else mainUrl + href
        
        val title = selectFirst("h3, h4, .title, img")?.let {
            it.attr("alt").ifBlank { it.text() }
        } ?: return null
        
        val poster = selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        
        val isTv = url.contains("/tv-show/")
        return if (isTv) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$mainUrl/api/search?q=$query", headers = headers).parsedSafe<SearchApi>() ?: return emptyList()
        val movies = res.movies.mapNotNull { it.toSearchResponse(false) }
        val tv = res.tvShows.mapNotNull { it.toSearchResponse(true) }
        return movies + tv
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers).document
        
        val title = doc.selectFirst("h1, meta[property=og:title]")?.let {
            it.text().ifBlank { it.attr("content") }
        }?.substringBefore(" -")?.trim() ?: return null
        
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img[src*='tmdb']")?.attr("src")
        
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("p:contains(Real-life), .overview")?.text()
        
        val year = doc.selectFirst("meta[property=og:url]")?.attr("content")
            ?.let { Regex("(\\d{4})").find(it)?.value?.toIntOrNull() }
            ?: doc.text().let { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() }
        
        val isTv = url.contains("/tv-show/")
        
        // Extract players JSON from page - robust method
        val playersJson = Regex("const players = (\\[.*?\\]);").find(doc.html())?.groupValues?.get(1)
        val players = try {
            playersJson?.let { jacksonObjectMapper().readValue<List<Player>>(it) } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        
        if (!isTv) {
            // Movie - store players data
            val data = LoadData(url = url, players = players, tmdbId = players.firstOrNull()?.url?.let { extractId(it) })
            return newMovieLoadResponse(title, url, TvType.Movie, data.toJson()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            // TV Series - parse episodes
            val tvId = doc.selectFirst("[data-tv-show-id]")?.attr("data-tv-show-id")
                ?: Regex("openReportModal\\('tv_show', (\\d+)").find(doc.html())?.groupValues?.get(1)
                ?: return null
            
            val episodes = doc.select("[data-episode-id]").mapNotNull { el ->
                val season = el.attr("data-season").toIntOrNull() ?: return@mapNotNull null
                val episode = el.attr("data-episode").toIntOrNull() ?: return@mapNotNull null
                val epTitle = el.selectFirst("img")?.attr("alt") ?: "Episode $episode"
                val epPoster = el.selectFirst("img")?.attr("src")
                
                Episode(
                    data = EpisodeData(tvId = tvId, season = season, episode = episode, players = players).toJson(),
                    name = epTitle,
                    season = season,
                    episode = episode,
                    posterUrl = epPoster
                )
            }.distinctBy { it.season to it.episode }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mapper = jacksonObjectMapper()
        
        // Try to parse as movie data
        val loadData = try {
            mapper.readValue<LoadData>(data)
        } catch (e: Exception) { null }
        
        val episodeData = try {
            mapper.readValue<EpisodeData>(data)
        } catch (e: Exception) { null }
        
        val sources = mutableListOf<String>()
        
        when {
            loadData != null -> {
                // Movie - use players from page or construct from tmdbId
                if (loadData.players.isNotEmpty()) {
                    sources.addAll(loadData.players.map { it.url })
                } else if (loadData.tmdbId != null) {
                    sources.addAll(buildEmbeds("movie", loadData.tmdbId))
                } else {
                    // Fallback: extract ID from URL
                    val id = extractId(loadData.url)
                    if (id != null) sources.addAll(buildEmbeds("movie", id))
                }
            }
            episodeData != null -> {
                // TV Episode - construct embeds
                sources.addAll(buildEmbeds("tv", episodeData.tvId, episodeData.season, episodeData.episode))
            }
        }
        
        // Load every server source - robust with extractors
        sources.distinct().forEach { sourceUrl ->
            try {
                // Use Cloudstream's built-in extractor which handles videasy, vidnest, nhdapi
                loadExtractor(sourceUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                // Fallback direct
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = sourceUrl.substringAfter("://").substringBefore("/"),
                        url = sourceUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
        }
        
        return sources.isNotEmpty()
    }
    
    private fun buildEmbeds(type: String, id: String, season: Int? = null, episode: Int? = null): List<String> {
        return if (type == "movie") {
            listOf(
                "https://nhdapi.com/embed/movie/$id",
                "https://vidnest.fun/movie/$id",
                "https://player.videasy.net/movie/$id"
            )
        } else {
            listOf(
                "https://nhdapi.com/embed/tv/$id/$season/$episode",
                "https://vidnest.fun/tv/$id/$season/$episode",
                "https://player.videasy.net/tv/$id/$season/$episode"
            )
        }
    }
    
    private fun extractId(url: String): String? {
        return Regex("/(?:movie|tv)/(\\d+)").find(url)?.groupValues?.get(1)
            ?: Regex("embed/(?:movie|tv)/(\\d+)").find(url)?.groupValues?.get(1)
    }
    
    // Data classes
    data class SearchApi(
        val movies: List<SearchItem> = emptyList(),
        val tvShows: List<SearchItem> = emptyList()
    )
    
    data class SearchItem(
        val id: Int? = null,
        val title: String? = null,
        val year: String? = null,
        val poster: String? = null,
        val url: String? = null
    ) {
        fun toSearchResponse(isTv: Boolean): SearchResponse? {
            val u = url ?: return null
            val t = title ?: return null
            return if (isTv) {
                newTvSeriesSearchResponse(t, u, TvType.TvSeries) { posterUrl = poster }
            } else {
                newMovieSearchResponse(t, u, TvType.Movie) { posterUrl = poster }
            }
        }
    }
    
    data class Player(
        val type: String = "",
        val name: String = "",
        val url: String = "",
        val server: String? = null,
        val index: Int = 0
    )
    
    data class LoadData(
        val url: String,
        val players: List<Player> = emptyList(),
        val tmdbId: String? = null
    )
    
    data class EpisodeData(
        val tvId: String,
        val season: Int,
        val episode: Int,
        val players: List<Player> = emptyList()
    )
}