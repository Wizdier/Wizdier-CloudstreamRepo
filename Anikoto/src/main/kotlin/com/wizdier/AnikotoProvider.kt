package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikototv.to"
    override var name = "Anikoto"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "/filter?sort=recently_updated&page=" to "Recently Updated",
        "/filter?sort=recently_added&page=" to "Recently Added",
        "/filter?sort=most_watched&page=" to "Most Watched"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl${request.data}$page").document
        val items = doc.select(".item").mapNotNull { it.toSearchResult() }
        val hasNext = doc.select("a[rel=next]").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/filter?keyword=$query").document
        return doc.select(".item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val title = a.attr("title").ifBlank { return null }
        val href = a.attr("href")
        val poster = this.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

        
    override val supportedSyncNames = setOfNotNull(
        com.lagradost.cloudstream3.syncproviders.SyncIdName.Anilist,
        com.lagradost.cloudstream3.syncproviders.SyncIdName.MyAnimeList,
        runCatching { com.lagradost.cloudstream3.syncproviders.SyncIdName.valueOf("Kitsu") }.getOrNull(),
        runCatching { com.lagradost.cloudstream3.syncproviders.SyncIdName.valueOf("Simkl") }.getOrNull()
    )

    private suspend fun fetchAnilistMetadata(malId: Int): AnilistMedia? {
        val query = """
            query (${'$'}idMal: Int) {
              Media(idMal: ${'$'}idMal, type: ANIME) {
                id
                bannerImage
                characters(sort: ROLE, perPage: 10) {
                  edges {
                    node { name { full } image { large } }
                    voiceActors(language: JAPANESE) { name { full } image { large } }
                  }
                }
              }
            }
        """.trimIndent()

        val req = app.post(
            "https://graphql.anilist.co",
            json = mapOf("query" to query, "variables" to mapOf("idMal" to malId))
        ).parsedSafe<AnilistResponse>()
        return req?.data?.Media
    }

    data class AnilistResponse(val data: AnilistData?)
    data class AnilistData(val Media: AnilistMedia?)
    data class AnilistMedia(val id: Int?, val bannerImage: String?, val characters: AnilistCharacters?)
    data class AnilistCharacters(val edges: List<AnilistEdge>?)
    data class AnilistEdge(val node: AnilistNode?, val voiceActors: List<AnilistNode>?)
    data class AnilistNode(val name: AnilistName?, val image: AnilistImage?)
    data class AnilistName(val full: String?)
    data class AnilistImage(val large: String?)
    data class AniZipData(val mappings: Map<String, Any>?)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h2.title")?.text() ?: return null
        val poster = doc.selectFirst(".poster img")?.attr("src")
        val description = doc.selectFirst(".description")?.text()
        
        val metaDiv = doc.select(".meta .item")
        var year: Int? = null
        val tags = mutableListOf<String>()
        var rating: Int? = null
        var duration: Int? = null
        
        metaDiv.forEach { item ->
            val text = item.text()
            if (text.contains("Premiered:")) {
                year = text.substringAfter("Premiered:").trim().toIntOrNull()
            } else if (text.contains("Genres:")) {
                tags.addAll(item.select("a").map { it.text().trim() })
            } else if (text.contains("MAL:")) {
                val score = text.substringAfter("MAL:").trim().toDoubleOrNull()
                if (score != null) rating = (score * 10).toInt()
            } else if (text.contains("Duration:")) {
                duration = getDurationFromString(text.substringAfter("Duration:").trim())
            }
        }
        
        val dataId = doc.selectFirst("div[data-id]")?.attr("data-id") ?: return null
        
        val episodes = mutableListOf<Episode>()
        var malId: Int? = null
        var anilistId: Int? = null
        var kitsuId: String? = null
        var imdbId: String? = null
        
        val epListUrl = "$mainUrl/ajax/episode/list/$dataId"
        val epRes = app.get(epListUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to url)).parsedSafe<ResponseJson>()
        
        if (epRes?.status == 200 && epRes.result != null) {
            val epSoup = org.jsoup.Jsoup.parse(epRes.result)
            epSoup.select("a[data-ids]").forEach { a ->
                if (malId == null) malId = a.attr("data-mal").toIntOrNull()
                val epNum = a.attr("data-num").toIntOrNull()
                val epName = a.parent()?.attr("title")?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                val dataIds = a.attr("data-ids")
                
                val linkData = """{"data_ids":"$dataIds"}"""
                val payload = java.util.Base64.getEncoder().encodeToString(linkData.toByteArray())
                
                episodes.add(
                    newEpisode(payload) {
                        this.name = epName
                        this.episode = epNum
                    }
                )
            }
        }
        
        var backgroundPosterUrl: String? = null
        var actorsList: List<ActorData>? = null
        
        if (malId != null && malId != 0) {
            runCatching {
                val anilistMeta = fetchAnilistMetadata(malId!!)
                if (anilistMeta != null) {
                    anilistId = anilistMeta.id
                    backgroundPosterUrl = anilistMeta.bannerImage
                    
                    val parsedActors = mutableListOf<ActorData>()
                    anilistMeta.characters?.edges?.forEach { edge ->
                        val charName = edge.node?.name?.full ?: return@forEach
                        val charImg = edge.node.image?.large
                        val vaNode = edge.voiceActors?.firstOrNull()
                        if (vaNode != null) {
                            val vaName = vaNode.name?.full ?: ""
                            val vaImg = vaNode.image?.large
                            parsedActors.add(
                                ActorData(
                                    actor = Actor(charName, charImg),
                                    roleString = "Voice Artist",
                                    voiceActor = Actor(vaName, vaImg)
                                )
                            )
                        }
                    }
                    if (parsedActors.isNotEmpty()) actorsList = parsedActors
                }
                
                // Fetch Kitsu & IMDB mapping from AniZip
                val aniZipUrl = "https://api.ani.zip/mappings?anilist_id=$anilistId"
                val aniZipText = app.get(aniZipUrl).text
                if (aniZipText.contains("mappings")) {
                    val aniZip = com.lagradost.cloudstream3.utils.AppUtils.parseJson<AniZipData>(aniZipText)
                    kitsuId = aniZip.mappings?.get("kitsu_id")?.toString()
                    val rawTmdb = aniZip.mappings?.get("themoviedb_id")?.toString()
                    // TMDB id can be used to resolve Simkl ID, or IMDB id is also supplied sometimes.
                    // AniZip usually includes imdb_id, but the schema map handles it.
                    imdbId = aniZip.mappings?.get("imdb_id")?.toString()
                }
            }
        }
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backgroundPosterUrl
            this.plot = description
            this.year = year
            this.tags = tags
            this.duration = duration
            if (rating != null) {
                this.score = rating?.let { Score.from10(it / 10.0) }
            }
            if (actorsList != null) {
                this.actors = actorsList
            }
            
            malId?.let { addMalId(it) }
            anilistId?.let { addAniListId(it) }
            kitsuId?.let { addKitsuId(it) }
            imdbId?.let { addImdbId(it) }
            
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    data class ResponseJson(val status: Int?, val result: String?)
    data class ServerJson(val status: Int?, val result: ServerResult?)
    data class ServerResult(val url: String?)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val decoded = String(java.util.Base64.getDecoder().decode(data))
        val dataIds = com.lagradost.cloudstream3.utils.AppUtils.parseJson<Map<String, String>>(decoded)["data_ids"] ?: return false
        
        val serverListUrl = "$mainUrl/ajax/server/list?servers=$dataIds"
        val serverRes = app.get(serverListUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).parsed<ResponseJson>()
        
        if (serverRes.status == 200 && serverRes.result != null) {
            val serverSoup = org.jsoup.Jsoup.parse(serverRes.result)
            
            serverSoup.select("li[data-link-id]").forEach { li ->
                val linkId = li.attr("data-link-id")
                // Need to fix padding for Base64 since the API fails if there are missing '='
                val paddedLinkId = linkId.padEnd(linkId.length + (4 - linkId.length % 4) % 4, '=')
                
                val srvUrl = "$mainUrl/ajax/server?get=$paddedLinkId"
                val srvRes = app.get(srvUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).parsed<ServerJson>()
                
                if (srvRes.status == 200 && srvRes.result?.url != null) {
                    val finalUrl = srvRes.result.url.replace("\\/", "/")
                    
                    val wrappedCallback = { link: ExtractorLink ->
                        callback.invoke(
                            newExtractorLink(
                                source = link.source,
                                name = link.name,
                                url = link.url,
                                type = if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = link.referer
                                this.quality = link.quality
                                this.headers = link.headers
                            }
                        )
                    }
                    loadExtractor(finalUrl, mainUrl, subtitleCallback, wrappedCallback)
                }
            }
        }
        
        return true
    }
}
