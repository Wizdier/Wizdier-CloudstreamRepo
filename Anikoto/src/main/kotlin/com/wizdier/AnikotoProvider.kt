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
        "/filter?sort=recently_added&page="   to "Recently Added",
        "/filter?sort=most_watched&page="     to "Most Watched"
    )

    // ---------------------------------------------------------------------------
    // Sync provider IDs
    // ---------------------------------------------------------------------------

    override val supportedSyncNames = setOfNotNull(
        com.lagradost.cloudstream3.syncproviders.SyncIdName.Anilist,
        com.lagradost.cloudstream3.syncproviders.SyncIdName.MyAnimeList,
        runCatching { com.lagradost.cloudstream3.syncproviders.SyncIdName.valueOf("Kitsu") }.getOrNull(),
        runCatching { com.lagradost.cloudstream3.syncproviders.SyncIdName.valueOf("Simkl") }.getOrNull()
    )

    // ---------------------------------------------------------------------------
    // Main page
    // ---------------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl${request.data}$page").document
        val items = doc.select(".item").mapNotNull { it.toSearchResult() }
        val hasNext = doc.select("a[rel=next]").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    // ---------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/filter?keyword=$query").document
        return doc.select(".item").mapNotNull { it.toSearchResult() }
    }

    // FIX: prefix relative hrefs with mainUrl so deep-links work correctly.
    private fun Element.toSearchResult(): SearchResponse? {
        val a     = this.selectFirst("a") ?: return null
        val title = a.attr("title").ifBlank { return null }
        val href  = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val poster = this.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // ---------------------------------------------------------------------------
    // AniList metadata
    // ---------------------------------------------------------------------------

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

        return app.post(
            "https://graphql.anilist.co",
            json = mapOf("query" to query, "variables" to mapOf("idMal" to malId))
        ).parsedSafe<AnilistResponse>()?.data?.Media
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

    // ---------------------------------------------------------------------------
    // Load
    // ---------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title       = doc.selectFirst("h2.title")?.text() ?: return null
        val poster      = doc.selectFirst(".poster img")?.attr("src")
        val description = doc.selectFirst(".description")?.text()

        // Parse meta fields
        var year:     Int? = null
        val tags            = mutableListOf<String>()
        var rating:   Int? = null
        var duration: Int? = null

        doc.select(".meta .item").forEach { item ->
            val text = item.text()
            when {
                text.contains("Premiered:") ->
                    year = text.substringAfter("Premiered:").trim().toIntOrNull()

                text.contains("Genres:") ->
                    tags.addAll(item.select("a").map { it.text().trim() })

                // FIX: Score.from10() already expects a 0-10 value; store the raw MAL score
                // directly instead of scaling ×10 and then dividing back down.
                text.contains("MAL:") -> {
                    val score = text.substringAfter("MAL:").trim().toDoubleOrNull()
                    if (score != null) rating = score.toInt()   // keep as 0-10 integer
                }

                text.contains("Duration:") ->
                    duration = getDurationFromString(text.substringAfter("Duration:").trim())
            }
        }

        val dataId = doc.selectFirst("div[data-id]")?.attr("data-id") ?: return null

        // Episode list
        val episodes = mutableListOf<Episode>()
        var malId: Int? = null

        val epRes = app.get(
            "$mainUrl/ajax/episode/list/$dataId",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to url)
        ).parsedSafe<ResponseJson>()

        if (epRes?.status == 200 && epRes.result != null) {
            val epSoup = org.jsoup.Jsoup.parse(epRes.result)
            epSoup.select("a[data-ids]").forEach { a ->
                if (malId == null) malId = a.attr("data-mal").toIntOrNull()

                val epNum  = a.attr("data-num").toIntOrNull()
                val epName = a.parent()?.attr("title")?.takeIf { it.isNotBlank() }
                    ?: "Episode $epNum"
                val dataIds = a.attr("data-ids")

                val linkData = """{"data_ids":"$dataIds"}"""
                val payload  = java.util.Base64.getEncoder().encodeToString(linkData.toByteArray())

                episodes.add(
                    newEpisode(payload) {
                        this.name    = epName
                        this.episode = epNum
                    }
                )
            }
        }

        // AniList / tracker metadata
        var backgroundPosterUrl: String? = null
        var actorsList:          List<ActorData>? = null
        var anilistId:           Int?    = null
        var kitsuId:             String? = null
        var imdbId:              String? = null

        val resolvedMalId = malId
        if (resolvedMalId != null && resolvedMalId != 0) {
            runCatching {
                val anilistMeta = fetchAnilistMetadata(resolvedMalId)
                if (anilistMeta != null) {
                    anilistId           = anilistMeta.id
                    backgroundPosterUrl = anilistMeta.bannerImage

                    val parsedActors = mutableListOf<ActorData>()
                    anilistMeta.characters?.edges?.forEach { edge ->
                        val charName = edge.node?.name?.full ?: return@forEach
                        val charImg  = edge.node.image?.large
                        val vaNode   = edge.voiceActors?.firstOrNull() ?: return@forEach
                        val vaName   = vaNode.name?.full ?: return@forEach
                        parsedActors.add(
                            ActorData(
                                actor       = Actor(charName, charImg),
                                roleString  = "Voice Artist",
                                voiceActor  = Actor(vaName, vaNode.image?.large)
                            )
                        )
                    }
                    if (parsedActors.isNotEmpty()) actorsList = parsedActors
                }

                // FIX: guard against anilistId being null before building the AniZip URL
                // so we don't fire a useless request with "?anilist_id=null".
                val resolvedAnilistId = anilistId
                if (resolvedAnilistId != null) {
                    val aniZipText = app.get(
                        "https://api.ani.zip/mappings?anilist_id=$resolvedAnilistId"
                    ).text
                    if (aniZipText.contains("mappings")) {
                        val aniZip = parseJson<AniZipData>(aniZipText)
                        kitsuId = aniZip.mappings?.get("kitsu_id")?.toString()
                        imdbId  = aniZip.mappings?.get("imdb_id")?.toString()
                    }
                }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl          = poster
            this.backgroundPosterUrl = backgroundPosterUrl
            this.plot               = description
            this.year               = year
            this.tags               = tags
            this.duration           = duration

            // FIX: Score.from10() expects a value in the 0–10 range; rating is already 0–10.
            rating?.let { this.score = Score.from10(it.toDouble()) }

            actorsList?.let { this.actors = it }

            malId?.let     { addMalId(it) }
            anilistId?.let { addAniListId(it) }
            kitsuId?.let   { addKitsuId(it) }
            imdbId?.let    { addImdbId(it) }

            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------------------------------------------------------------------
    // Load links
    // ---------------------------------------------------------------------------

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
        val dataIds = parseJson<Map<String, String>>(decoded)["data_ids"] ?: return false

        // FIX: use parsedSafe so a bad/empty response doesn't throw — just returns false.
        val serverRes = app.get(
            "$mainUrl/ajax/server/list?servers=$dataIds",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<ResponseJson>()

        if (serverRes?.status != 200 || serverRes.result == null) return false

        val serverSoup = org.jsoup.Jsoup.parse(serverRes.result)

        // FIX (THE CORE BUG): forEach { } is a plain lambda — not a coroutine body —
        // so suspend functions like loadExtractor() cannot be called inside it.
        // Solution: convert the list to a plain List and iterate with a for-loop,
        // which correctly inherits the suspend context of loadLinks().
        val serverItems = serverSoup.select("li[data-link-id]").toList()
        for (li in serverItems) {
            val linkId = li.attr("data-link-id")
            // Restore any stripped Base64 padding before passing to the API.
            val paddedLinkId = linkId.padEnd(linkId.length + (4 - linkId.length % 4) % 4, '=')

            // FIX: parsedSafe instead of parsed to avoid a throw on malformed JSON.
            val srvRes = app.get(
                "$mainUrl/ajax/server?get=$paddedLinkId",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<ServerJson>()

            val finalUrl = srvRes?.result?.url?.replace("\\/", "/") ?: continue

            // Directly pass callback — no manual re-wrapping needed; loadExtractor
            // already produces correctly typed ExtractorLinks.
            loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
        }

        return true
    }
}
