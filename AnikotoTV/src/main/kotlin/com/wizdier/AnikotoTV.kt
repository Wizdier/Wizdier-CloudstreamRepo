package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class AnikotoTV : MainAPI() {
    override var mainUrl = "https://anikototv.to"
    override var name = "AnikotoTV"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Cartoon,
    )

    // ── Tracking: MAL, AniList, Kitsu, Simkl ────────────────────────────
    override val supportedSyncNames = setOfNotNull(
        SyncIdName.Anilist,
        SyncIdName.MyAnimeList,
        runCatching { SyncIdName.valueOf("Kitsu") }.getOrNull(),
        runCatching { SyncIdName.valueOf("Simkl") }.getOrNull(),
    )

    companion object {
        private const val ANIKOTO_API = "https://anikotoapi.site"
        private const val MEGAPLAY_BASE = "https://megaplay.buzz"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"
        private const val IMG_BASE = "https://image.tmdb.org/t/p"

        fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key, "").takeIf { it.isNotBlank() && it != "null" }
        }
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
    )

    // ═══════════════════════════════════════════════════════════════════
    //                        MAIN PAGE
    // ═══════════════════════════════════════════════════════════════════
    override val mainPage = mainPageOf(
        "$ANIKOTO_API/recent-anime?per_page=24&page=" to "Recently Updated",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val text = app.get(url).text
        val root = JSONObject(text)
        val dataArr = root.optJSONArray("data") ?: JSONArray()

        val items = mutableListOf<SearchResponse>()
        for (i in 0 until dataArr.length()) {
            val anime = dataArr.getJSONObject(i)
            items.add(anime.toSearchResponse())
        }

        val pagination = root.optJSONObject("pagination")
        val hasNext = pagination?.let {
            val currentPage = it.optInt("current_page", 1)
            val lastPage = it.optInt("last_page", 1)
            currentPage < lastPage
        } ?: (items.size >= 20)

        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //                          SEARCH
    // ═══════════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        // Anikoto API doesn't have a search endpoint, so we scrape the website
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search?keyword=$encodedQuery"
        val doc = app.get(searchUrl, headers = defaultHeaders).document

        val results = mutableListOf<SearchResponse>()

        // Parse search results from HTML — each anime card is an anchor with film-poster class
        doc.select("#list-items .item, .film_list-wrap .flw-item, .item, div.ani").forEach { el ->
            val anchor = el.selectFirst("a[href*='/anime/'], a[href*='/watch/']") ?: return@forEach
            val href = anchor.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }
            val title = el.selectFirst("h3, .film-name, .dynamic-name, a.dynamic-name")
                ?.text()?.trim() ?: return@forEach
            val poster = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }

            // Extract slug from URL for API lookup
            val slug = extractSlugFromUrl(href)

            val subCount = el.select("span:contains(Sub), label:contains(Sub)")
                .firstOrNull()?.parent()?.selectFirst("span")?.text()?.toIntOrNull()
            val dubCount = el.select("span:contains(Dub), label:contains(Dub)")
                .firstOrNull()?.parent()?.selectFirst("span")?.text()?.toIntOrNull()

            val type = el.selectFirst("label:matches(Movie|OVA|ONA|Special|TV)")
                ?.text()?.trim()

            val tvType = when (type?.uppercase()) {
                "MOVIE" -> TvType.AnimeMovie
                "OVA" -> TvType.OVA
                else -> TvType.Anime
            }

            val dataUrl = slug?.let { "anikoto://$it" } ?: href

            results.add(
                newAnimeSearchResponse(title, dataUrl, tvType) {
                    this.posterUrl = poster
                    addDubStatus(
                        dubExist = dubCount != null && dubCount > 0,
                        subExist = subCount != null && subCount > 0,
                        dubEpisodes = dubCount,
                        subEpisodes = subCount,
                    )
                }
            )
        }

        // Fallback: use API recent-anime and filter by query
        if (results.isEmpty()) {
            for (page in 1..5) {
                val apiUrl = "$ANIKOTO_API/recent-anime?page=$page&per_page=50"
                val text = runCatching { app.get(apiUrl).text }.getOrNull() ?: break
                val root = JSONObject(text)
                val dataArr = root.optJSONArray("data") ?: break

                for (i in 0 until dataArr.length()) {
                    val anime = dataArr.getJSONObject(i)
                    val title = anime.optString("title", "")
                    val alt = anime.optString("alternative", "")
                    val titles = anime.optString("titles", "")
                    val native = anime.optString("native", "")

                    val queryLower = query.lowercase()
                    val matchesTitle = title.lowercase().contains(queryLower) ||
                            alt.lowercase().contains(queryLower) ||
                            titles.lowercase().contains(queryLower) ||
                            native.lowercase().contains(queryLower)

                    if (matchesTitle) {
                        results.add(anime.toSearchResponse())
                    }
                }

                if (results.size >= 20) break
                val pagination = root.optJSONObject("pagination")
                val currentPage = pagination?.optInt("current_page", 1) ?: 1
                val lastPage = pagination?.optInt("last_page", 1) ?: 1
                if (currentPage >= lastPage) break
            }
        }

        return results.distinctBy { it.url }
    }

    // ═══════════════════════════════════════════════════════════════════
    //                           LOAD
    // ═══════════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse {
        // Resolve the anime ID from URL
        val animeId = resolveAnimeId(url)
            ?: throw ErrorLoadingException("Could not resolve anime ID from: $url")

        // Fetch full series data from Anikoto API
        val seriesText = app.get("$ANIKOTO_API/series/$animeId").text
        val seriesRoot = JSONObject(seriesText)

        if (!seriesRoot.optBoolean("ok", false)) {
            throw ErrorLoadingException("API returned error for anime ID: $animeId")
        }

        val data = seriesRoot.getJSONObject("data")
        val anime = data.getJSONObject("anime")
        val episodesArr = data.optJSONArray("episodes") ?: JSONArray()

        // ─── Basic Info ────────────────────────────────────────────────
        val title = anime.optString("title", "Unknown")
        val altTitle = anime.optStringOrNull("alternative")
        val nativeTitle = anime.optStringOrNull("native")
        val description = anime.optStringOrNull("description")
            ?.replace(Regex("<[^>]+>"), "")
            ?.replace("&#39;", "'")
            ?.replace("&quot;", "\"")
            ?.replace("&amp;", "&")
            ?.trim()
        val poster = anime.optStringOrNull("poster")
        val background = anime.optStringOrNull("background_image")
        val year = anime.optInt("year", 0).takeIf { it > 0 }
        val rating = anime.optStringOrNull("score")?.toDoubleOrNull()
        val status = anime.optStringOrNull("status")
        val duration = parseDuration(anime.optStringOrNull("duration"))
        val aired = anime.optStringOrNull("aired")

        // ─── IDs for Tracking ──────────────────────────────────────────
        val malId = anime.optStringOrNull("mal_id")?.toIntOrNull()
        val anilistId = anime.optStringOrNull("ani_id")?.toIntOrNull()

        // ─── Genres, Studios, Producers ────────────────────────────────
        val termsObj = anime.optJSONObject("terms_by_type")
        val genres = mutableListOf<String>()
        val studios = mutableListOf<String>()
        termsObj?.optJSONArray("genre")?.let { arr ->
            for (i in 0 until arr.length()) genres.add(arr.getString(i))
        }
        termsObj?.optJSONArray("studios")?.let { arr ->
            for (i in 0 until arr.length()) studios.add(arr.getString(i))
        }
        val animeType = termsObj?.optJSONArray("type")?.let {
            if (it.length() > 0) it.getString(0) else null
        }

        // ─── Determine TvType ──────────────────────────────────────────
        val tvType = when (animeType?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA" -> TvType.OVA
            "ONA" -> TvType.OVA
            "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        // ─── Has Dub/Sub ──────────────────────────────────────────────
        val hasSub = anime.optInt("is_sub", 0) > 0
        val hasDub = anime.optInt("is_dub", 0) > 0

        // ─── Enrich with TMDB/AniList ─────────────────────────────────
        val enriched = runCatching { enrichMetadata(title, malId, anilistId, year) }.getOrNull()

        val finalPoster = enriched?.posterUrl ?: poster
        val finalBackdrop = enriched?.backdropUrl ?: background
        val logoUrl = enriched?.logoUrl
        val trailerUrl = enriched?.trailerUrl
        val kitsuId = enriched?.kitsuId
        val imdbId = enriched?.imdbId
        val tmdbEpisodes = enriched?.episodes ?: emptyList()

        // ─── Build Episodes ────────────────────────────────────────────
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        for (i in 0 until episodesArr.length()) {
            val epObj = episodesArr.getJSONObject(i)
            val epNum = epObj.optInt("number", i + 1)
            val epTitle = epObj.optStringOrNull("title")
                ?.replace("&#39;", "'")
                ?.replace("&quot;", "\"")
                ?.replace("&amp;", "&")
            val jpTitle = epObj.optStringOrNull("jp_title")
            val embedId = epObj.optStringOrNull("episode_embed_id") ?: continue
            val embedUrlObj = epObj.optJSONObject("embed_url")

            // TMDB episode enrichment
            val tmdbEp = tmdbEpisodes.getOrNull(epNum - 1)

            val finalEpName = tmdbEp?.name?.takeIf { it.isNotBlank() }
                ?: epTitle?.takeIf { !it.startsWith("Episode ") }
                ?: jpTitle?.takeIf { !it.startsWith("Episode ") }
                ?: "Episode $epNum"

            val epDescription = tmdbEp?.overview
            val epStill = tmdbEp?.stillUrl

            // ── SUB episode ────────────────────────────────────────
            val subUrl = embedUrlObj?.optStringOrNull("sub")
            if (subUrl != null) {
                val dataPayload = JSONObject().apply {
                    put("embed_id", embedId)
                    put("lang", "sub")
                    put("embed_url", subUrl)
                    put("anime_title", title)
                    put("ep_num", epNum)
                    put("mal_id", malId ?: 0)
                    put("anilist_id", anilistId ?: 0)
                }.toString()

                subEpisodes.add(
                    newEpisode(dataPayload) {
                        this.name = finalEpName
                        this.season = 1
                        this.episode = epNum
                        this.posterUrl = epStill
                        this.description = epDescription
                        runCatching { tmdbEp?.rating?.let { this.score = Score.from10(it) } }
                    }
                )
            }

            // ── DUB episode ────────────────────────────────────────
            val dubUrl = embedUrlObj?.optStringOrNull("dub")
            if (dubUrl != null) {
                val dataPayload = JSONObject().apply {
                    put("embed_id", embedId)
                    put("lang", "dub")
                    put("embed_url", dubUrl)
                    put("anime_title", title)
                    put("ep_num", epNum)
                    put("mal_id", malId ?: 0)
                    put("anilist_id", anilistId ?: 0)
                }.toString()

                dubEpisodes.add(
                    newEpisode(dataPayload) {
                        this.name = finalEpName
                        this.season = 1
                        this.episode = epNum
                        this.posterUrl = epStill
                        this.description = epDescription
                        runCatching { tmdbEp?.rating?.let { this.score = Score.from10(it) } }
                    }
                )
            }
        }

        // ─── Build Plot ────────────────────────────────────────────────
        val plotBuilder = StringBuilder()
        description?.let { plotBuilder.append(it) }
        if (!altTitle.isNullOrBlank() && altTitle != title) {
            plotBuilder.append("\n\nAlso known as: $altTitle")
        }
        if (!nativeTitle.isNullOrBlank()) {
            plotBuilder.append("\nNative: $nativeTitle")
        }
        if (studios.isNotEmpty()) {
            plotBuilder.append("\nStudio: ${studios.joinToString(", ")}")
        }
        if (!aired.isNullOrBlank()) {
            plotBuilder.append("\nAired: $aired")
        }
        if (!status.isNullOrBlank()) {
            plotBuilder.append("\nStatus: $status")
        }

        // ─── Build Response ────────────────────────────────────────────
        return newAnimeLoadResponse(title, url, tvType) {
            addEpisodes(DubStatus.Subbed, subEpisodes)
            addEpisodes(DubStatus.Dubbed, dubEpisodes)

            this.posterUrl = finalPoster
            this.backgroundPosterUrl = finalBackdrop
            this.year = year
            this.plot = plotBuilder.toString().trim().ifBlank { null }
            this.duration = duration
            this.tags = genres.takeIf { it.isNotEmpty() }
            this.showStatus = when (status?.lowercase()) {
                "currently airing" -> ShowStatus.Ongoing
                "finished airing" -> ShowStatus.Completed
                else -> null
            }

            runCatching { rating?.let { this.score = Score.from10(it) } }
            runCatching { malId?.let { addMalId(it) } }
            runCatching { anilistId?.let { addAniListId(it) } }
            runCatching { kitsuId?.let { addKitsuId(it) } }
            runCatching { trailerUrl?.let { addTrailer(it) } }
            runCatching { logoUrl?.let { this.logoUrl = it } }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //                       LOAD LINKS
    // ═══════════════════════════════════════════════════════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = runCatching { JSONObject(data) }.getOrNull()
            ?: return false

        val embedId = payload.optStringOrNull("embed_id") ?: return false
        val lang = payload.optString("lang", "sub")
        val embedUrl = payload.optStringOrNull("embed_url")
        val animeTitle = payload.optString("anime_title", "AnikotoTV")
        val epNum = payload.optInt("ep_num", 1)
        val malId = payload.optInt("mal_id", 0).takeIf { it > 0 }
        val anilistId = payload.optInt("anilist_id", 0).takeIf { it > 0 }

        val langLabel = if (lang == "dub") "Dub" else "Sub"
        var found = false

        // ── Source 1: MegaPlay Embed (primary) ─────────────────────────
        val megaplayUrl = embedUrl
            ?: "$MEGAPLAY_BASE/stream/s-2/$embedId/$lang"

        runCatching {
            val embedHtml = app.get(
                megaplayUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "$mainUrl/",
                )
            ).text

            // ─── Extract Subtitles from MegaPlay ───────────────────────
            extractSubtitles(embedHtml, megaplayUrl, subtitleCallback)

            // Extract M3U8 or MP4 sources from the embed page
            val m3u8Matches = Regex("""(?:src|file|source)\s*[:=]\s*['"](https?://[^'"]*\.m3u8[^'"]*)['"]""")
                .findAll(embedHtml)
            val mp4Matches = Regex("""(?:src|file|source)\s*[:=]\s*['"](https?://[^'"]*\.mp4[^'"]*)['"]""")
                .findAll(embedHtml)

            // Parse any JSON sources
            val jsonSourceMatches = Regex("""sources\s*[:=]\s*(\[[\s\S]*?\])""")
                .find(embedHtml)
            if (jsonSourceMatches != null) {
                runCatching {
                    val arr = JSONArray(jsonSourceMatches.groupValues[1])
                    for (i in 0 until arr.length()) {
                        val src = arr.getJSONObject(i)
                        val srcUrl = src.optString("file", src.optString("src", ""))
                        val quality = src.optString("label", "Auto")
                        if (srcUrl.isNotBlank()) {
                            val isM3u8 = srcUrl.contains(".m3u8")
                            if (isM3u8) {
                                M3u8Helper.generateM3u8(
                                    source = "$name — MegaPlay [$langLabel]",
                                    streamUrl = srcUrl,
                                    referer = megaplayUrl,
                                ).forEach(callback)
                            } else {
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name — MegaPlay [$langLabel] $quality",
                                        url = srcUrl,
                                        type = ExtractorLinkType.VIDEO,
                                    ) {
                                        this.referer = megaplayUrl
                                        this.quality = getQualityFromName(quality)
                                    }
                                )
                            }
                            found = true
                        }
                    }
                }
            }

            m3u8Matches.forEach { match ->
                val streamUrl = match.groupValues[1]
                M3u8Helper.generateM3u8(
                    source = "$name — MegaPlay [$langLabel]",
                    streamUrl = streamUrl,
                    referer = megaplayUrl,
                ).forEach(callback)
                found = true
            }

            mp4Matches.forEach { match ->
                val videoUrl = match.groupValues[1]
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name — MegaPlay [$langLabel]",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = megaplayUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        // ── Source 2: MAL-based fallback ───────────────────────────────
        if (!found && malId != null) {
            runCatching {
                val malUrl = "$MEGAPLAY_BASE/stream/mal/$malId/$epNum/$lang"
                val malHtml = app.get(malUrl, headers = mapOf(
                    "Referer" to "$mainUrl/"
                )).text

                // Extract subtitles from MAL endpoint
                extractSubtitles(malHtml, malUrl, subtitleCallback)

                Regex("""(?:src|file|source)\s*[:=]\s*['"](https?://[^'"]*\.m3u8[^'"]*)['"]""")
                    .findAll(malHtml).forEach { match ->
                        M3u8Helper.generateM3u8(
                            source = "$name — MAL [$langLabel]",
                            streamUrl = match.groupValues[1],
                            referer = malUrl,
                        ).forEach(callback)
                        found = true
                    }
            }
        }

        // ── Source 3: AniList-based fallback ───────────────────────────
        if (!found && anilistId != null) {
            runCatching {
                val aniUrl = "$MEGAPLAY_BASE/stream/ani/$anilistId/$epNum/$lang"
                val aniHtml = app.get(aniUrl, headers = mapOf(
                    "Referer" to "$mainUrl/"
                )).text

                // Extract subtitles from AniList endpoint
                extractSubtitles(aniHtml, aniUrl, subtitleCallback)

                Regex("""(?:src|file|source)\s*[:=]\s*['"](https?://[^'"]*\.m3u8[^'"]*)['"]""")
                    .findAll(aniHtml).forEach { match ->
                        M3u8Helper.generateM3u8(
                            source = "$name — AniList [$langLabel]",
                            streamUrl = match.groupValues[1],
                            referer = aniUrl,
                        ).forEach(callback)
                        found = true
                    }
            }
        }

        // ── Source 4: Direct website scrape as ultimate fallback ───────
        if (!found) {
            runCatching {
                // Try to get the watch page directly
                val slug = resolveSlugFromId(payload.optInt("anime_id", 0))
                if (slug != null) {
                    val watchUrl = "$mainUrl/watch/$slug/ep-$epNum"
                    val watchDoc = app.get(watchUrl, headers = defaultHeaders).document

                    // Extract subtitles from HTML track elements
                    watchDoc.select("track[src], track[data-src]").forEach { track ->
                        val trackSrc = track.attr("data-src").ifBlank { track.attr("src") }
                        val trackLang = track.attr("label").ifBlank { 
                            track.attr("srclang").ifBlank { "English" }
                        }
                        val trackKind = track.attr("kind")
                        if (trackSrc.isNotBlank() && (trackKind.isBlank() || trackKind == "subtitles" || trackKind == "captions")) {
                            val fullUrl = if (trackSrc.startsWith("http")) trackSrc else "$mainUrl$trackSrc"
                            subtitleCallback.invoke(SubtitleFile(trackLang, fullUrl))
                        }
                    }

                    // Extract subtitles from inline scripts
                    watchDoc.select("script").forEach { script ->
                        val scriptText = script.html()
                        if (scriptText.isNotBlank()) {
                            extractSubtitles(scriptText, watchUrl, subtitleCallback)
                        }
                    }

                    // Extract iframe sources
                    watchDoc.select("iframe[src], iframe[data-src]").forEach { iframe ->
                        val iframeSrc = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                        if (iframeSrc.isNotBlank()) {
                            // Fetch iframe content for subtitles too
                            runCatching {
                                val iframeHtml = app.get(iframeSrc, headers = mapOf(
                                    "Referer" to watchUrl
                                )).text
                                extractSubtitles(iframeHtml, iframeSrc, subtitleCallback)
                            }
                            runCatching { loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback) }
                            found = true
                        }
                    }

                    // Extract video sources
                    watchDoc.select("source[src]").forEach { source ->
                        val src = source.attr("src")
                        if (src.isNotBlank()) {
                            val type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8
                            else ExtractorLinkType.VIDEO
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name — Direct [$langLabel]",
                                    url = src,
                                    type = type,
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        }
                    }
                }
            }
        }

        return found
    }

    // ═══════════════════════════════════════════════════════════════════
    //                   SUBTITLE EXTRACTION
    // ═══════════════════════════════════════════════════════════════════
    
    private fun extractSubtitles(
        html: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val addedUrls = mutableSetOf<String>()
        
        // Pattern 1: JSON tracks/subtitles array
        // e.g., tracks: [{file: "url", label: "English", kind: "captions"}]
        // or subtitles: [{url: "...", lang: "..."}]
        listOf(
            Regex("""(?:tracks|subtitles|captions)\s*[:=]\s*(\[[\s\S]*?\])"""),
            Regex(""""(?:tracks|subtitles|captions)"\s*:\s*(\[[\s\S]*?\])"""),
        ).forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                runCatching {
                    val arr = JSONArray(match.groupValues[1])
                    for (i in 0 until arr.length()) {
                        val track = arr.getJSONObject(i)
                        val kind = track.optString("kind", "").lowercase()
                        
                        // Skip if not subtitles/captions (e.g., thumbnails)
                        if (kind.isNotBlank() && kind != "subtitles" && kind != "captions") continue
                        
                        val url = track.optStringOrNull("file")
                            ?: track.optStringOrNull("url")
                            ?: track.optStringOrNull("src")
                            ?: continue
                        
                        if (url in addedUrls) continue
                        if (!url.contains(".vtt") && !url.contains(".srt") && !url.contains(".ass")) continue
                        
                        val label = track.optStringOrNull("label")
                            ?: track.optStringOrNull("lang")
                            ?: track.optStringOrNull("language")
                            ?: track.optStringOrNull("srclang")
                            ?: "English"
                        
                        val fullUrl = if (url.startsWith("http")) url 
                            else if (url.startsWith("//")) "https:$url"
                            else url
                        
                        addedUrls.add(fullUrl)
                        subtitleCallback.invoke(SubtitleFile(label, fullUrl))
                    }
                }
            }
        }
        
        // Pattern 2: HTML track elements in script strings
        // e.g., <track src="..." label="English" kind="subtitles">
        Regex("""<track[^>]*src\s*=\s*['"](https?://[^'"]*\.(?:vtt|srt|ass)[^'"]*)['"][^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { match ->
                val url = match.groupValues[1]
                if (url !in addedUrls) {
                    val labelMatch = Regex("""label\s*=\s*['"]([^'"]+)['"]""").find(match.value)
                    val langMatch = Regex("""srclang\s*=\s*['"]([^'"]+)['"]""").find(match.value)
                    val label = labelMatch?.groupValues?.get(1) 
                        ?: langMatch?.groupValues?.get(1) 
                        ?: "English"
                    addedUrls.add(url)
                    subtitleCallback.invoke(SubtitleFile(label, url))
                }
            }
        
        // Pattern 3: Direct VTT/SRT/ASS URLs in various formats
        // e.g., subtitle: "url", captions: "url", "subtitle_url": "..."
        Regex("""(?:subtitle|caption|sub|track)[_\-]?(?:url|file|src)?\s*['":]?\s*['"](https?://[^'"]*\.(?:vtt|srt|ass)[^'"]*)['"]""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { match ->
                val url = match.groupValues[1]
                if (url !in addedUrls) {
                    addedUrls.add(url)
                    subtitleCallback.invoke(SubtitleFile("English", url))
                }
            }
        
        // Pattern 4: Object notation with file and label
        // e.g., {file: "subtitle.vtt", label: "English"}
        Regex("""\{\s*(?:["']?file["']?\s*:\s*["'](https?://[^'"]*\.(?:vtt|srt|ass)[^'"]*)["'])[^}]*(?:["']?label["']?\s*:\s*["']([^'"]+)["'])?[^}]*\}""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { match ->
                val url = match.groupValues[1]
                if (url !in addedUrls) {
                    val label = match.groupValues.getOrNull(2)?.ifBlank { null } ?: "English"
                    addedUrls.add(url)
                    subtitleCallback.invoke(SubtitleFile(label, url))
                }
            }
        
        // Pattern 5: Reversed order (label before file)
        Regex("""\{\s*(?:["']?label["']?\s*:\s*["']([^'"]+)["'])[^}]*(?:["']?file["']?\s*:\s*["'](https?://[^'"]*\.(?:vtt|srt|ass)[^'"]*)["'])[^}]*\}""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { match ->
                val label = match.groupValues[1]
                val url = match.groupValues[2]
                if (url !in addedUrls) {
                    addedUrls.add(url)
                    subtitleCallback.invoke(SubtitleFile(label, url))
                }
            }
        
        // Pattern 6: Simple array of subtitle URLs
        // e.g., ["https://...english.vtt", "https://...spanish.vtt"]
        Regex("""['"](https?://[^'"]*?(?:english|eng|en)[^'"]*\.(?:vtt|srt|ass)[^'"]*)['"]""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { match ->
                val url = match.groupValues[1]
                if (url !in addedUrls) {
                    addedUrls.add(url)
                    subtitleCallback.invoke(SubtitleFile("English", url))
                }
            }
        
        // Pattern 7: Other language subtitles
        val langPatterns = mapOf(
            "Spanish" to listOf("spanish", "spa", "es"),
            "Portuguese" to listOf("portuguese", "por", "pt"),
            "French" to listOf("french", "fra", "fr"),
            "German" to listOf("german", "deu", "de"),
            "Italian" to listOf("italian", "ita", "it"),
            "Russian" to listOf("russian", "rus", "ru"),
            "Arabic" to listOf("arabic", "ara", "ar"),
            "Japanese" to listOf("japanese", "jpn", "ja"),
            "Korean" to listOf("korean", "kor", "ko"),
            "Chinese" to listOf("chinese", "chi", "zh"),
            "Indonesian" to listOf("indonesian", "ind", "id"),
            "Thai" to listOf("thai", "tha", "th"),
            "Vietnamese" to listOf("vietnamese", "vie", "vi"),
            "Hindi" to listOf("hindi", "hin", "hi"),
            "Turkish" to listOf("turkish", "tur", "tr"),
            "Polish" to listOf("polish", "pol", "pl"),
            "Dutch" to listOf("dutch", "nld", "nl"),
            "Greek" to listOf("greek", "ell", "el"),
            "Hebrew" to listOf("hebrew", "heb", "he"),
            "Romanian" to listOf("romanian", "ron", "ro"),
            "Swedish" to listOf("swedish", "swe", "sv"),
            "Norwegian" to listOf("norwegian", "nor", "no"),
            "Danish" to listOf("danish", "dan", "da"),
            "Finnish" to listOf("finnish", "fin", "fi"),
            "Czech" to listOf("czech", "ces", "cs"),
            "Hungarian" to listOf("hungarian", "hun", "hu"),
            "Malay" to listOf("malay", "msa", "ms"),
            "Filipino" to listOf("filipino", "fil", "tl"),
        )
        
        for ((langName, codes) in langPatterns) {
            val codePattern = codes.joinToString("|")
            Regex("""['"](https?://[^'"]*?(?:$codePattern)[^'"]*\.(?:vtt|srt|ass)[^'"]*)['"]""", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { match ->
                    val url = match.groupValues[1]
                    if (url !in addedUrls) {
                        addedUrls.add(url)
                        subtitleCallback.invoke(SubtitleFile(langName, url))
                    }
                }
        }
        
        // Pattern 8: Generic subtitle URLs without language hints (fallback)
        Regex("""['"](https?://[^'"]*\.(?:vtt|srt|ass))['"]""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { match ->
                val url = match.groupValues[1]
                if (url !in addedUrls) {
                    // Try to detect language from URL
                    val detectedLang = when {
                        url.contains("thumb", true) || url.contains("sprite", true) -> return@forEach
                        url.contains("eng", true) || url.contains("english", true) -> "English"
                        url.contains("default", true) -> "English"
                        else -> "Unknown"
                    }
                    addedUrls.add(url)
                    subtitleCallback.invoke(SubtitleFile(detectedLang, url))
                }
            }
    }

    // ═══════════════════════════════════════════════════════════════════
    //                   METADATA ENRICHMENT
    // ═══════════════════════════════════════════════════════════════════

    data class EpisodeMeta(
        val name: String? = null,
        val overview: String? = null,
        val stillUrl: String? = null,
        val rating: Double? = null,
    )

    data class EnrichedMeta(
        val posterUrl: String? = null,
        val backdropUrl: String? = null,
        val logoUrl: String? = null,
        val trailerUrl: String? = null,
        val kitsuId: String? = null,
        val imdbId: String? = null,
        val episodes: List<EpisodeMeta> = emptyList(),
    )

    private suspend fun enrichMetadata(
        title: String,
        malId: Int?,
        anilistId: Int?,
        year: Int?,
    ): EnrichedMeta? {
        var kitsuId: String? = null
        var tmdbId: Int? = null
        var imdbId: String? = null

        // Try ani.zip for Kitsu + TMDB mappings
        if (anilistId != null && anilistId > 0) {
            runCatching {
                val mappings = JSONObject(
                    app.get("https://api.ani.zip/mappings?anilist_id=$anilistId").text
                ).optJSONObject("mappings")
                kitsuId = mappings?.optStringOrNull("kitsu_id")
                tmdbId = mappings?.optStringOrNull("themoviedb_id")?.toIntOrNull()
            }
        }

        if (tmdbId == null && malId != null && malId > 0) {
            runCatching {
                val mappings = JSONObject(
                    app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                ).optJSONObject("mappings")
                if (kitsuId == null) kitsuId = mappings?.optStringOrNull("kitsu_id")
                tmdbId = mappings?.optStringOrNull("themoviedb_id")?.toIntOrNull()
            }
        }

        // Fallback: TMDB search
        if (tmdbId == null) {
            runCatching {
                val q = URLEncoder.encode(title, "UTF-8")
                val yearParam = year?.let { "&first_air_date_year=$it" } ?: ""
                val searchUrl = "$TMDB_API/search/tv?api_key=$TMDB_KEY&query=$q$yearParam"
                val results = JSONObject(app.get(searchUrl).text).optJSONArray("results")
                if (results != null && results.length() > 0) {
                    tmdbId = results.getJSONObject(0).optInt("id")
                }
            }
        }

        if (tmdbId == null) return EnrichedMeta(kitsuId = kitsuId)

        // Fetch TMDB details
        return runCatching {
            val detailsUrl = "$TMDB_API/tv/$tmdbId?api_key=$TMDB_KEY" +
                    "&append_to_response=images,videos,external_ids"
            val details = JSONObject(app.get(detailsUrl).text)

            val backdrop = details.optString("backdrop_path", "").toTmdbImg("original")
            val poster = details.optString("poster_path", "").toTmdbImg("w500")
            val logo = pickLogo(details.optJSONObject("images")?.optJSONArray("logos"))
            val trailer = pickTrailer(details.optJSONObject("videos")?.optJSONArray("results"))
            imdbId = details.optJSONObject("external_ids")
                ?.optStringOrNull("imdb_id")?.takeIf { it.startsWith("tt") }

            // Fetch season 1 episodes for enrichment
            val episodes = runCatching {
                val seasonUrl = "$TMDB_API/tv/$tmdbId/season/1?api_key=$TMDB_KEY"
                val seasonData = JSONObject(app.get(seasonUrl).text)
                val epArr = seasonData.optJSONArray("episodes") ?: JSONArray()
                val epList = mutableListOf<EpisodeMeta>()
                for (i in 0 until epArr.length()) {
                    val ep = epArr.getJSONObject(i)
                    val stillPath = ep.optString("still_path", "")
                        .takeIf { it.isNotBlank() && it != "null" }
                    epList.add(
                        EpisodeMeta(
                            name = ep.optStringOrNull("name"),
                            overview = ep.optStringOrNull("overview"),
                            stillUrl = stillPath?.let { "$IMG_BASE/original$it" },
                            rating = ep.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
                        )
                    )
                }
                epList
            }.getOrDefault(emptyList())

            EnrichedMeta(
                posterUrl = poster,
                backdropUrl = backdrop,
                logoUrl = logo,
                trailerUrl = trailer,
                kitsuId = kitsuId,
                imdbId = imdbId,
                episodes = episodes,
            )
        }.getOrNull() ?: EnrichedMeta(kitsuId = kitsuId)
    }

    // ═══════════════════════════════════════════════════════════════════
    //                         HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun JSONObject.toSearchResponse(): SearchResponse {
        val id = optInt("id")
        val title = optString("title", "Unknown")
        val poster = optStringOrNull("poster")
        val subCount = optInt("is_sub", 0)
        val dubCount = optInt("is_dub", 0)
        val animeType = optJSONObject("terms_by_type")
            ?.optJSONArray("type")?.let { if (it.length() > 0) it.getString(0) else null }

        val tvType = when (animeType?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA" -> TvType.OVA
            "ONA" -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, "anikoto://$id", tvType) {
            this.posterUrl = poster
            addDubStatus(
                dubExist = dubCount > 0,
                subExist = subCount > 0,
                dubEpisodes = if (dubCount > 0) dubCount else null,
                subEpisodes = if (subCount > 0) subCount else null,
            )
        }
    }

    private suspend fun resolveAnimeId(url: String): Int? {
        // Direct API ID
        if (url.startsWith("anikoto://")) {
            val idPart = url.removePrefix("anikoto://")
            // Could be numeric ID or slug
            idPart.toIntOrNull()?.let { return it }

            // If slug, search API
            return resolveIdFromSlug(idPart)
        }

        // URL with slug
        val slug = extractSlugFromUrl(url)
        if (slug != null) {
            return resolveIdFromSlug(slug)
        }

        return null
    }

    private suspend fun resolveIdFromSlug(slug: String): Int? {
        // Browse recent anime pages to find the ID
        for (page in 1..10) {
            val apiUrl = "$ANIKOTO_API/recent-anime?page=$page&per_page=50"
            val text = runCatching { app.get(apiUrl).text }.getOrNull() ?: break
            val root = JSONObject(text)
            val dataArr = root.optJSONArray("data") ?: break

            for (i in 0 until dataArr.length()) {
                val anime = dataArr.getJSONObject(i)
                val animeSlug = anime.optStringOrNull("slug")
                if (animeSlug == slug) {
                    return anime.optInt("id")
                }
            }

            val pagination = root.optJSONObject("pagination")
            val currentPage = pagination?.optInt("current_page", 1) ?: 1
            val lastPage = pagination?.optInt("last_page", 1) ?: 1
            if (currentPage >= lastPage) break
        }
        return null
    }

    private fun resolveSlugFromId(id: Int): String? {
        // Not needed for most flows, but useful for fallback
        return null
    }

    private fun extractSlugFromUrl(url: String): String? {
        // https://anikototv.to/watch/slug-xxxx/ep-1
        // https://anikototv.to/anime/slug-xxxx
        val patterns = listOf(
            Regex("""/watch/([^/]+)"""),
            Regex("""/anime/([^/]+)"""),
        )
        for (pattern in patterns) {
            pattern.find(url)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    private fun String.toTmdbImg(size: String): String? =
        takeIf { it.isNotBlank() && it != "null" }?.let { "$IMG_BASE/$size$it" }

    private fun parseDuration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val h = Regex("(\\d+)h").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)\\s*m").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = h * 60 + m
        return if (total > 0) total else null
    }

    private fun pickLogo(logos: JSONArray?): String? {
        if (logos == null || logos.length() == 0) return null
        for (i in 0 until logos.length()) {
            val l = logos.optJSONObject(i) ?: continue
            val p = l.optString("file_path", "")
            if (p.isBlank() || p.endsWith(".svg", true)) continue
            val lang = l.optString("iso_639_1", "").trim().lowercase()
            if (lang == "en") return "$IMG_BASE/w500$p"
        }
        // Fallback to any non-SVG
        for (i in 0 until logos.length()) {
            val l = logos.optJSONObject(i) ?: continue
            val p = l.optString("file_path", "")
            if (p.isNotBlank() && !p.endsWith(".svg", true)) return "$IMG_BASE/w500$p"
        }
        return null
    }

    private fun pickTrailer(videos: JSONArray?): String? {
        if (videos == null || videos.length() == 0) return null
        var officialTrailer: String? = null
        var anyTrailer: String? = null
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (!v.optString("site").equals("YouTube", true)) continue
            val key = v.optString("key", "").takeIf { it.isNotBlank() } ?: continue
            val type = v.optString("type", "")
            val isOfficial = v.optBoolean("official", false)
            val url = "https://www.youtube.com/watch?v=$key"
            when {
                type.equals("Trailer", true) && isOfficial -> return url
                type.equals("Trailer", true) && anyTrailer == null -> anyTrailer = url
                officialTrailer == null -> officialTrailer = url
            }
        }
        return officialTrailer ?: anyTrailer
    }
}