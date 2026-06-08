package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.utils.AppUtils.toJson

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
        TvType.Anime
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies?page=" to "Movies",
        "$mainUrl/tv-shows?page=" to "TV Shows",
        "$mainUrl/anime?page=" to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page, headers = headers).document
        val items = doc.select("a[href*='/movie/watch-'], a[href*='/tv-show/watch-']")
            .distinctBy { it.attr("href") }
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, items), items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null
        val url = if (href.startsWith("http")) href else mainUrl + href
        val title = selectFirst("img")?.attr("alt")?.ifBlank { text() } ?: text()
        if (title.isBlank()) return null
        val poster = selectFirst("img")?.attr("src")
        return if (url.contains("/tv-show/")) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = app.get("$mainUrl/api/search?q=$query", headers = headers).text
        val data = jacksonObjectMapper().readValue<SearchApi>(json)
        val out = mutableListOf<SearchResponse>()
        data.movies.forEach { it.url?.let { u -> it.title?.let { t -> out.add(newMovieSearchResponse(t, u, TvType.Movie){ posterUrl = it.poster }) } } }
        data.tvShows.forEach { it.url?.let { u -> it.title?.let { t -> out.add(newTvSeriesSearchResponse(t, u, TvType.TvSeries){ posterUrl = it.poster }) } } }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text() ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        val year = Regex("(20\\d{2})").find(doc.html())?.value?.toIntOrNull()
        val isTv = url.contains("/tv-show/")
        
        val playersJson = Regex("const players = (\\[.*?\\]);").find(doc.html())?.groupValues?.get(1) ?: "[]"
        val players = try { jacksonObjectMapper().readValue<List<Player>>(playersJson) } catch (_:Exception){ emptyList() }
        
        return if (!isTv) {
            val tmdbId = players.firstOrNull()?.url?.let { Regex("/(\\d+)").find(it)?.value?.trim('/') }
            val data = mapOf("url" to url, "id" to (tmdbId ?: ""), "players" to players.map { it.url })
            newMovieLoadResponse(title, url, TvType.Movie, data.toJson()) {
                this.posterUrl = poster; this.plot = plot; this.year = year
            }
        } else {
            val tvId = doc.selectFirst("[data-tv-show-id]")?.attr("data-tv-show-id")
                ?: Regex("openReportModal\\('tv_show', (\\d+)").find(doc.html())?.groupValues?.get(1) ?: "0"
            val eps = doc.select("[data-episode-id]").map { el ->
                val s = el.attr("data-season").toIntOrNull() ?: 1
                val e = el.attr("data-episode").toIntOrNull() ?: 1
                val epTitle = el.selectFirst("img")?.attr("alt") ?: "Episode $e"
                newEpisode(mapOf("tvId" to tvId, "s" to s, "e" to e).toJson()) {
                    name = epTitle; season = s; episode = e
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster; this.plot = plot; this.year = year
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val map = try { jacksonObjectMapper().readValue<Map<String,Any>>(data) } catch (_:Exception){ emptyMap() }
        val sources = mutableListOf<String>()
        
        if (map.containsKey("players")) {
            @Suppress("UNCHECKED_CAST")
            (map["players"] as? List<String>)?.let { sources.addAll(it) }
        }
        
        val id = map["id"] as? String
        val tvId = map["tvId"] as? String
        val s = (map["s"] as? Int) ?: (map["s"] as? String)?.toIntOrNull()
        val e = (map["e"] as? Int) ?: (map["e"] as? String)?.toIntOrNull()
        
        if (sources.isEmpty()) {
            if (tvId != null && s != null && e != null) {
                sources.addAll(listOf(
                    "https://nhdapi.com/embed/tv/$tvId/$s/$e",
                    "https://vidnest.fun/tv/$tvId/$s/$e",
                    "https://player.videasy.net/tv/$tvId/$s/$e"
                ))
            } else if (!id.isNullOrBlank()) {
                sources.addAll(listOf(
                    "https://nhdapi.com/embed/movie/$id",
                    "https://vidnest.fun/movie/$id",
                    "https://player.videasy.net/movie/$id"
                ))
            }
        }
        
        sources.distinct().forEach { url ->
            loadExtractor(url, mainUrl, subtitleCallback, callback)
        }
        return sources.isNotEmpty()
    }

    data class SearchApi(val movies: List<Item> = emptyList(), val tvShows: List<Item> = emptyList())
    data class Item(val title: String? = null, val url: String? = null, val poster: String? = null)
    data class Player(val url: String = "")
}