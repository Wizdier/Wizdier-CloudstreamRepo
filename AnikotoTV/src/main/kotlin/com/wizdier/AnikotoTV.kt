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
import org.jsoup.Jsoup
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

// ─────────────────────────────────────────────────────────────────────────────
// AnikotoTV Provider for Cloudstream
//
// A flawless, robust, and highly optimized anime provider utilizing the
// Anikoto API (HiAnime-compatible library) and direct web scraping fallbacks.
//
// Features:
// • Full Multi-Server Source extraction (Sub & Dub) with clean server labeling
// • Sync tracking (MAL, AniList, Kitsu, Simkl)
// • AniList Character & Voice Actor mapping
// • TMDB/AniList metadata enrichment with high-accuracy matching
// • Parallelized network operations for maximum speed and responsiveness
// ─────────────────────────────────────────────────────────────────────────────

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
        "Referer" to "${mainUrl}/",
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Referer" to "${mainUrl}/",
        "X-Requested-With" to "XMLHttpRequest",
    )

    // ═══════════════════════════════════════════════════════════════════
    //                        MAIN PAGE
    // ═══════════════════════════════════════════════════════════════════
    override val mainPage = mainPageOf(
        "${ANIKOTO_API}/recent-anime?per_page=24&page=" to "Recently Updated",
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
            it.optInt("current_page", 1) < it.optInt("last_page", 1)
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
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "${mainUrl}/search?keyword=${encodedQuery}"
        
        return coroutineScope {
            val webSearch = async {
                val results = mutableListOf<SearchResponse>()
                runCatching {
                    val doc = app.get(searchUrl, headers = headers).document
                    doc.select(".film_list-wrap .flw-item, #list-items .item, .item").forEach { el ->
                        val anchor = el.selectFirst("a[href]") ?: return@forEach
                        val href = anchor.attr("href").let {
                            if (it.startsWith("http")) it else "${mainUrl}${it}"
                        }
                        val title = el.selectFirst("h3, .film-name, .dynamic-name, a.dynamic-name")
                            ?.text()?.trim() ?: return@forEach
                        val poster = el.selectFirst("img")?.let { img ->
                            img.attr("data-src").ifBlank { img.attr("src") }
                        }
                        results.add(
                            newAnimeSearchResponse(title, href, TvType.Anime) {
                                this.posterUrl = poster
                            }
                        )
                    }
                }
                results
            }

            val apiSearch = async {
                val results = mutableListOf<SearchResponse>()
                runCatching {
                    for (pg in 1..3) {
                        val apiUrl = "${ANIKOTO_API}/recent-anime?page=${pg}&per_page=50"
                        val text = app.get(apiUrl).text
                        val root = JSONObject(text)
                        val dataArr = root.optJSONArray("data") ?: break

                        for (i in 0 until dataArr.length()) {
                            val anime = dataArr.getJSONObject(i)
                            val t = anime.optString("title", "")
                            val alt = anime.optString("alternative", "")
                            val titles = anime.optString("titles", "")
                            val q = query.lowercase()
                            if (t.lowercase().contains(q) || alt.lowercase().contains(q) || titles.lowercase().contains(q)) {
                                results.add(anime.toSearchResponse())
                            }
                        }
                    }
                }
                results
            }

            (webSearch.await() + apiSearch.await()).distinctBy { it.url }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //                           LOAD
    // ═══════════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse {
        val animeId = resolveAnimeId(url)
            ?: throw ErrorLoadingException("Could not resolve anime ID from: ${url}")

        val seriesText = app.get("${ANIKOTO_API}/series/${animeId}").text
        val seriesRoot = JSONObject(seriesText)

        if (!seriesRoot.optBoolean("ok", false)) {
            throw ErrorLoadingException("API error for ID: ${animeId}")
        }

        val data = seriesRoot.getJSONObject("data")
        val anime = data.getJSONObject("anime")
        val apiEpisodes = data.optJSONArray("episodes") ?: JSONArray()

        // ─── Basic Info ────────────────────────────────────────────
        val title = anime.optString("title", "Unknown")
        val altTitle = anime.optStringOrNull("alternative")
        val nativeTitle = anime.optStringOrNull("native")
        val slug = anime.optStringOrNull("slug") ?: ""
        val description = anime.optStringOrNull("description")
            ?.replace(Regex("<[^>]+>"), "")
            ?.replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&")?.trim()
        val poster = anime.optStringOrNull("poster")
        val background = anime.optStringOrNull("background_image")
        val year = anime.optInt("year", 0).takeIf { it > 0 }
        val rating = anime.optStringOrNull("score")?.toDoubleOrNull()
        val status = anime.optStringOrNull("status")
        val duration = parseDuration(anime.optStringOrNull("duration"))
        val aired = anime.optStringOrNull("aired")

        val malId = anime.optStringOrNull("mal_id")?.toIntOrNull()
        val anilistId = anime.optStringOrNull("ani_id")?.toIntOrNull()

        val termsObj = anime.optJSONObject("terms_by_type")
        val genres = mutableListOf<String>()
        termsObj?.optJSONArray("genre")?.let { arr ->
            for (i in 0 until arr.length()) genres.add(arr.getString(i))
        }
        val studios = mutableListOf<String>()
        termsObj?.optJSONArray("studios")?.let { arr ->
            for (i in 0 until arr.length()) studios.add(arr.getString(i))
        }
        val animeType = termsObj?.optJSONArray("type")?.let {
            if (it.length() > 0) it.getString(0) else null
        }

        val tvType = when (animeType?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        // ─── Fetch Enriched Metadata & AniList Characters in Parallel ───
        val (enriched, castList) = coroutineScope {
            val metaJob = async { runCatching { enrichMetadata(title, malId, anilistId, year) }.getOrNull() }
            val charJob = async { runCatching { fetchAniListCharacters(anilistId) }.getOrDefault(emptyList()) }
            metaJob.await() to charJob.await()
        }

        val finalPoster = enriched?.posterUrl ?: poster
        val finalBackdrop = enriched?.backdropUrl ?: background
        val kitsuId = enriched?.kitsuId
        val tmdbEpisodes = enriched?.episodes ?: emptyList()

        // ─── Build episodes using API embed data ─────────────────────
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        for (i in 0 until apiEpisodes.length()) {
            val epObj = apiEpisodes.getJSONObject(i)
            val epNum = epObj.optInt("number", i + 1)
            val epTitle = epObj.optStringOrNull("title")
                ?.replace("&#39;", "'")?.replace("&quot;", "\"").replace("&amp;", "&")
            val jpTitle = epObj.optStringOrNull("jp_title")
            val embedId = epObj.optStringOrNull("episode_embed_id") ?: ""
            val embedUrlObj = epObj.optJSONObject("embed_url")

            val tmdbEp = tmdbEpisodes.getOrNull(epNum - 1)
            val finalEpName = tmdbEp?.name?.takeIf { it.isNotBlank() }
                ?: epTitle?.takeIf { !it.startsWith("Episode ") }
                ?: jpTitle?.takeIf { !it.startsWith("Episode ") }
                ?: "Episode ${epNum}"

            val payload = JSONObject().apply {
                put("slug", slug)
                put("ep_num", epNum)
                put("embed_id", embedId)
                put("mal_id", malId ?: 0)
                put("anilist_id", anilistId ?: 0)
                put("has_sub", embedUrlObj?.has("sub") == true)
                put("has_dub", embedUrlObj?.has("dub") == true)
            }

            if (embedUrlObj?.has("sub") == true || embedId.isNotBlank()) {
                val subPayload = JSONObject(payload.toString()).apply { put("lang", "sub") }
                subEpisodes.add(newEpisode(subPayload.toString()) {
                    this.name = finalEpName
                    this.season = 1
                    this.episode = epNum
                    this.posterUrl = tmdbEp?.stillUrl
                    this.description = tmdbEp?.overview
                })
            }

            if (embedUrlObj?.has("dub") == true) {
                val dubPayload = JSONObject(payload.toString()).apply { put("lang", "dub") }
                dubEpisodes.add(newEpisode(dubPayload.toString()) {
                    this.name = finalEpName
                    this.season = 1
                    this.episode = epNum
                    this.posterUrl = tmdbEp?.stillUrl
                    this.description = tmdbEp?.overview
                })
            }
        }

        // ─── Build Plot ────────────────────────────────────────────
        val plot = buildString {
            description?.let { append(it) }
            if (!altTitle.isNullOrBlank() && altTitle != title) append("\n\nAlso known as: ${altTitle}")
            if (!nativeTitle.isNullOrBlank()) append("\nNative: ${nativeTitle}")
            if (studios.isNotEmpty()) append("\nStudio: ${studios.joinToString(", ")}")
            if (!aired.isNullOrBlank()) append("\nAired: ${aired}")
            if (!status.isNullOrBlank()) append("\nStatus: ${status}")
        }.trim().ifBlank { null }

        return newAnimeLoadResponse(title, url, tvType) {
            addEpisodes(DubStatus.Subbed, subEpisodes)
            addEpisodes(DubStatus.Dubbed, dubEpisodes)
            this.posterUrl = finalPoster
            this.backgroundPosterUrl = finalBackdrop
            this.year = year
            this.plot = plot
            this.duration = duration
            this.tags = genres.takeIf { it.isNotEmpty() }
            this.actors = castList
            this.showStatus = when (status?.lowercase()) {
                "currently airing" -> ShowStatus.Ongoing
                "finished airing" -> ShowStatus.Completed
                else -> null
            }
            runCatching { rating?.let { this.score = Score.from10(it) } }
            runCatching { malId?.let { addMalId(it) } }
            runCatching { anilistId?.let { addAniListId(it) } }
            runCatching { kitsuId?.let { addKitsuId(it) } }
            runCatching { enriched?.trailerUrl?.let { addTrailer(it) } }
            runCatching { enriched?.logoUrl?.let { this.logoUrl = it } }
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
        val payload = runCatching { JSONObject(data) }.getOrNull() ?: return false

        val embedId = payload.optString("embed_id", "")
        val lang = payload.optString("lang", "sub")
        val epNum = payload.optInt("ep_num", 1)
        val slug = payload.optString("slug", "")
        val malId = payload.optInt("mal_id", 0).takeIf { it > 0 }
        val anilistId = payload.optInt("anilist_id", 0).takeIf { it > 0 }

        val langLabel = if (lang == "dub") "Dub" else "Sub"
        var found = false

        // ── Strategy 1: HiAnime-style AJAX (primary) ──────────────────
        if (embedId.isNotBlank()) {
            found = extractViaAjax(embedId, lang, langLabel, subtitleCallback, callback)
        }

        // ── Strategy 2: Scrape the watch page for the data-id ─────────
        if (!found && slug.isNotBlank()) {
            runCatching {
                val watchUrl = "${mainUrl}/watch/${slug}/ep-${epNum}"
                val watchHtml = app.get(watchUrl, headers = headers).text
                val watchDoc = Jsoup.parse(watchHtml)

                val epItem = watchDoc.select("a.ep-item[data-number=${epNum}], a.ep-item")
                    .firstOrNull { it.attr("data-number") == epNum.toString() }

                val dataId = epItem?.attr("data-id")
                    ?: Regex("""data-id\s*=\s*["'](\d+)["']""").find(watchHtml)?.groupValues?.get(1)

                if (dataId != null) {
                    found = extractViaAjax(dataId, lang, langLabel, subtitleCallback, callback)
                }

                if (!found) {
                    watchDoc.select("iframe[src], iframe[data-src]").forEach { iframe ->
                        val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                        if (src.isNotBlank()) {
                            runCatching {
                                loadExtractor(src, mainUrl, subtitleCallback, callback)
                                found = true
                            }
                        }
                    }
                }

                watchDoc.select("script").forEach { script ->
                    val scriptText = script.html()
                    if (scriptText.isNotBlank()) {
                        extractSubtitlesFromText(scriptText, subtitleCallback)
                    }
                }

                watchDoc.select("track[src], track[data-src]").forEach { track ->
                    val trackSrc = track.attr("data-src").ifBlank { track.attr("src") }
                    val trackLabel = track.attr("label").ifBlank {
                        track.attr("srclang").ifBlank { "English" }
                    }
                    val kind = track.attr("kind")
                    if (trackSrc.isNotBlank() && (kind.isBlank() || kind == "subtitles" || kind == "captions")) {
                        val fullUrl = if (trackSrc.startsWith("http")) trackSrc else "${mainUrl}${trackSrc}"
                        subtitleCallback.invoke(SubtitleFile(trackLabel, fullUrl))
                    }
                }
            }
        }

        // ── Strategy 3: MegaPlay direct embeds via API data ───────────
        if (!found && embedId.isNotBlank()) {
            val megaplayUrl = "https://megaplay.buzz/stream/s-2/${embedId}/${lang}"
            runCatching {
                loadExtractor(megaplayUrl, mainUrl, subtitleCallback, callback)
                found = true
            }
            if (!found) {
                runCatching {
                    val embedHtml = app.get(megaplayUrl, headers = mapOf(
                        "Referer" to "${mainUrl}/"
                    )).text
                    extractSubtitlesFromText(embedHtml, subtitleCallback)
                    found = extractSourcesFromHtml(embedHtml, megaplayUrl, langLabel, callback)
                }
            }
        }

        // ── Strategy 4: MAL / AniList based MegaPlay fallback ─────────
        if (!found && malId != null) {
            val malUrl = "https://megaplay.buzz/stream/mal/${malId}/${epNum}/${lang}"
            runCatching {
                loadExtractor(malUrl, mainUrl, subtitleCallback, callback)
                found = true
            }
            if (!found) {
                runCatching {
                    val html = app.get(malUrl, headers = mapOf("Referer" to "${mainUrl}/")).text
                    extractSubtitlesFromText(html, subtitleCallback)
                    found = extractSourcesFromHtml(html, malUrl, "MAL ${langLabel}", callback)
                }
            }
        }
        if (!found && anilistId != null) {
            val aniUrl = "https://megaplay.buzz/stream/ani/${anilistId}/${epNum}/${lang}"
            runCatching {
                loadExtractor(aniUrl, mainUrl, subtitleCallback, callback)
                found = true
            }
            if (!found) {
                runCatching {
                    val html = app.get(aniUrl, headers = mapOf("Referer" to "${mainUrl}/")).text
                    extractSubtitlesFromText(html, subtitleCallback)
                    found = extractSourcesFromHtml(html, aniUrl, "AniList ${langLabel}", callback)
                }
            }
        }

        return found
    }

    // ═══════════════════════════════════════════════════════════════════
    //         AJAX-BASED SOURCE EXTRACTION (HiAnime pattern)
    // ═══════════════════════════════════════════════════════════════════
    private suspend fun extractViaAjax(
        episodeId: String,
        targetLang: String,
        langLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        val serversUrl = "${mainUrl}/ajax/v2/episode/servers?episodeId=${episodeId}"
        val serversJson = runCatching {
            JSONObject(app.get(serversUrl, headers = headers).text)
        }.getOrNull() ?: return false

        val serversHtml = serversJson.optString("html", "")
        if (serversHtml.isBlank()) return false

        val serversDoc = Jsoup.parse(serversHtml)
        val serverItems = serversDoc.select(".server-item[data-type=${targetLang}]")
        val allItems = if (serverItems.isEmpty()) serversDoc.select(".server-item") else serverItems

        coroutineScope {
            allItems.map { serverEl ->
                async {
                    val serverId = serverEl.attr("data-id").ifBlank { return@async }
                    val serverType = serverEl.attr("data-type").ifBlank { targetLang }
                    val serverName = serverEl.text().trim().ifBlank { "Server" }

                    val serverLangLabel = when (serverType) {
                        "dub" -> "Dub"
                        "raw" -> "Raw"
                        else -> "Sub"
                    }

                    val sourcesUrl = "${mainUrl}/ajax/v2/episode/sources?id=${serverId}"
                    val sourcesJson = runCatching {
                        JSONObject(app.get(sourcesUrl, headers = headers).text)
                    }.getOrNull() ?: return@async

                    val embedLink = sourcesJson.optString("link", "")
                    if (embedLink.isBlank()) return@async

                    val success = runCatching {
                        loadExtractor(
                            embedLink,
                            mainUrl,
                            subtitleCallback,
                            { link ->
                                val finalName = buildString {
                                    append("[")
                                    append(serverLangLabel)
                                    append("] ")
                                    append(serverName)
                                    if (link.name.isNotBlank() && !link.name.contains(serverName, true)) {
                                        append(" - ")
                                        append(link.name)
                                    }
                                }
                                callback.invoke(
                                    ExtractorLink(
                                        link.source,
                                        finalName,
                                        link.url,
                                        link.referer,
                                        link.quality,
                                        link.isM3u8,
                                        link.headers,
                                        link.extractorData
                                    )
                                )
                            }
                        )
                        true
                    }.getOrDefault(false)

                    if (success) {
                        found = true
                    } else {
                        runCatching {
                            val embedHtml = app.get(embedLink, headers = mapOf(
                                "Referer" to "${mainUrl}/"
                            )).text
                            extractSubtitlesFromText(embedHtml, subtitleCallback)
                            if (extractSourcesFromHtml(embedHtml, embedLink, "${serverName} [${serverLangLabel}]", callback)) {
                                found = true
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        return found
    }

    // ═══════════════════════════════════════════════════════════════════
    //                   SOURCE EXTRACTION FROM HTML
    // ═══════════════════════════════════════════════════════════════════
    private suspend fun extractSourcesFromHtml(
        html: String,
        referer: String,
        sourceLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        Regex("""sources\s*[:=]\s*(\[[\s\S]*?\])""").find(html)?.let { match ->
            runCatching {
                val arr = JSONArray(match.groupValues[1])
                for (i in 0 until arr.length()) {
                    val src = arr.getJSONObject(i)
                    val srcUrl = src.optString("file", src.optString("src", ""))
                    val quality = src.optString("label", "Auto")
                    if (srcUrl.isNotBlank()) {
                        if (srcUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                source = "${name} — ${sourceLabel}",
                                streamUrl = srcUrl,
                                referer = referer,
                            ).forEach(callback)
                        } else {
                            callback.invoke(newExtractorLink(
                                source = name,
                                name = "${name} — ${sourceLabel} ${quality}",
                                url = srcUrl,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = referer
                                this.quality = getQualityFromName(quality)
                            })
                        }
                        found = true
                    }
                }
            }
        }

        Regex("""(?:src|file|source)\s*[:=]\s*['"](https?://[^'"]*\.m3u8[^'"]*)['"]""")
            .findAll(html).forEach { m ->
                M3u8Helper.generateM3u8("${name} — ${sourceLabel}", m.groupValues[1], referer)
                    .forEach(callback)
                found = true
            }
        Regex("""(?:src|file|source)\s*[:=]\s*['"](https?://[^'"]*\.mp4[^'"]*)['"]""")
            .findAll(html).forEach { m ->
                callback.invoke(newExtractorLink(
                    source = name, name = "${name} — ${sourceLabel}",
                    url = m.groupValues[1], type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
                found = true
            }

        return found
    }

    // ═══════════════════════════════════════════════════════════════════
    //                   SUBTITLE EXTRACTION
    // ═══════════════════════════════════════════════════════════════════
    private fun extractSubtitlesFromText(
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val added = mutableSetOf<String>()

        listOf(
            Regex("""(?:tracks|subtitles|captions)\s*[:=]\s*([[\s\S]*?\])"""),
            Regex(""""(?:tracks|subtitles|captions)"\s*:\s*([[\s\S]*?\])"""),
        ).forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                runCatching {
                    val arr = JSONArray(match.groupValues[1])
                    for (i in 0 until arr.length()) {
                        val t = arr.getJSONObject(i)
                        val kind = t.optString("kind", "").lowercase()
                        if (kind.isNotBlank() && kind != "subtitles" && kind != "captions") continue
                        val url = t.optStringOrNull("file")
                            ?: t.optStringOrNull("url")
                            ?: t.optStringOrNull("src") ?: continue
                        if (!url.contains(".vtt") && !url.contains(".srt") && !url.contains(".ass")) continue
                        if (url in added) continue
                        val label = t.optStringOrNull("label")
                            ?: t.optStringOrNull("lang") ?: "English"
                        val fullUrl = if (url.startsWith("http")) url
                            else if (url.startsWith("//")) "https:${url}" else url
                        added.add(fullUrl)
                        subtitleCallback.invoke(SubtitleFile(label, fullUrl))
                    }
                }
            }
        }

        Regex("""<track[^>]*src\s*=\s*['"](https?://[^'"]*\.(?:vtt|srt|ass)[^'"]*)['"][^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val url = m.groupValues[1]
                if (url !in added) {
                    val label = Regex("""label\s*=\s*['"]([^'"]+)['"]""").find(m.value)?.groupValues?.get(1) ?: "English"
                    added.add(url)
                    subtitleCallback.invoke(SubtitleFile(label, url))
                }
            }

        Regex("""['"](https?://[^'"]*\.(?:vtt|srt|ass)(?:\?[^'"]*)?)['"]""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val url = m.groupValues[1]
                if (url !in added && !url.contains("thumb") && !url.contains("sprite")) {
                    added.add(url)
                    val lang = when {
                        url.contains("eng", true) -> "English"
                        url.contains("ara", true) -> "Arabic"
                        url.contains("spa", true) -> "Spanish"
                        url.contains("fre", true) || url.contains("fra", true) -> "French"
                        url.contains("ger", true) || url.contains("deu", true) -> "German"
                        url.contains("ita", true) -> "Italian"
                        url.contains("por", true) -> "Portuguese"
                        url.contains("rus", true) -> "Russian"
                        url.contains("jpn", true) || url.contains("japanese", true) -> "Japanese"
                        url.contains("kor", true) -> "Korean"
                        url.contains("chi", true) -> "Chinese"
                        url.contains("hin", true) -> "Hindi"
                        url.contains("tur", true) -> "Turkish"
                        url.contains("ind", true) -> "Indonesian"
                        url.contains("vie", true) -> "Vietnamese"
                        url.contains("tha", true) -> "Thai"
                        url.contains("pol", true) -> "Polish"
                        url.contains("dut", true) || url.contains("nld", true) -> "Dutch"
                        else -> "Unknown"
                    }
                    subtitleCallback.invoke(SubtitleFile(lang, url))
                }
            }
    }

    // ═══════════════════════════════════════════════════════════════════
    //                   METADATA ENRICHMENT
    // ═══════════════════════════════════════════════════════════════════
    data class EpisodeMeta(val name: String?, val overview: String?, val stillUrl: String?, val rating: Double?)
    data class EnrichedMeta(
        val posterUrl: String?, val backdropUrl: String?, val logoUrl: String?,
        val trailerUrl: String?, val kitsuId: String?, val imdbId: String?,
        val episodes: List<EpisodeMeta>,
    )

    private suspend fun enrichMetadata(title: String, malId: Int?, anilistId: Int?, year: Int?): EnrichedMeta? {
        var kitsuId: String? = null
        var tmdbId: Int? = null
        var imdbId: String? = null

        if (anilistId != null && anilistId > 0) {
            runCatching {
                val m = JSONObject(app.get("https://api.ani.zip/mappings?anilist_id=${anilistId}").text)
                    .optJSONObject("mappings")
                kitsuId = m?.optStringOrNull("kitsu_id")
                tmdbId = m?.optStringOrNull("themoviedb_id")?.toIntOrNull()
            }
        }
        if (tmdbId == null && malId != null && malId > 0) {
            runCatching {
                val m = JSONObject(app.get("https://api.ani.zip/mappings?mal_id=${malId}").text)
                    .optJSONObject("mappings")
                if (kitsuId == null) kitsuId = m?.optStringOrNull("kitsu_id")
                tmdbId = m?.optStringOrNull("themoviedb_id")?.toIntOrNull()
            }
        }
        if (tmdbId == null) {
            runCatching {
                val q = URLEncoder.encode(title, "UTF-8")
                val yp = year?.let { "&first_air_date_year=${it}" } ?: ""
                val r = JSONObject(app.get("${TMDB_API}/search/tv?api_key=${TMDB_KEY}&query=${q}${yp}").text)
                    .optJSONArray("results")
                if (r != null && r.length() > 0) tmdbId = r.getJSONObject(0).optInt("id")
            }
        }
        if (tmdbId == null) return EnrichedMeta(null, null, null, null, kitsuId, null, emptyList())

        return runCatching {
            val d = JSONObject(app.get(
                "${TMDB_API}/tv/${tmdbId}?api_key=${TMDB_KEY}&append_to_response=images,videos,external_ids"
            ).text)
            val backdrop = d.optString("backdrop_path", "").toImg("original")
            val poster = d.optString("poster_path", "").toImg("w500")
            val logo = d.optJSONObject("images")?.optJSONArray("logos")?.pickLogo()
            val trailer = d.optJSONObject("videos")?.optJSONArray("results")?.pickTrailer()
            imdbId = d.optJSONObject("external_ids")?.optStringOrNull("imdb_id")?.takeIf { it.startsWith("tt") }

            val episodes = runCatching {
                val s = JSONObject(app.get("${TMDB_API}/tv/${tmdbId}/season/1?api_key=${TMDB_KEY}").text)
                val ea = s.optJSONArray("episodes") ?: JSONArray()
                (0 until ea.length()).map { i ->
                    val e = ea.getJSONObject(i)
                    EpisodeMeta(
                        e.optStringOrNull("name"), e.optStringOrNull("overview"),
                        e.optString("still_path", "").takeIf { it.isNotBlank() && it != "null" }
                            ?.let { "${IMG_BASE}/original${it}" },
                        e.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
                    )
                }
            }.getOrDefault(emptyList())

            EnrichedMeta(poster, backdrop, logo, trailer, kitsuId, imdbId, episodes)
        }.getOrNull() ?: EnrichedMeta(null, null, null, null, kitsuId, null, emptyList())
    }

    // ═══════════════════════════════════════════════════════════════════
    //                   ANILIST CHARACTER FETCHING
    // ═══════════════════════════════════════════════════════════════════
    private suspend fun fetchAniListCharacters(anilistId: Int?): List<ActorData> {
        if (anilistId == null || anilistId <= 0) return emptyList()

        val query = "query (\$id: Int) { Media(id: \$id, type: ANIME) { characters(sort: ROLE, perPage: 15) { edges { node { name { full } image { large } } role voiceActors(language: JAPANESE) { name { full } image { large } } } } } }"

        val variables = JSONObject().apply { put("id", anilistId) }
        val payload = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }.toString()

        val text = runCatching {
            app.post(
                "https://graphql.anilist.co",
                headers = mapOf("Content-Type" to "application/json"),
                requestBody = payload.toRequestBody("application/json".toMediaTypeOrNull())
            ).text
        }.getOrNull() ?: return emptyList()

        val castList = mutableListOf<ActorData>()
        runCatching {
            val root = JSONObject(text)
            val data = root.optJSONObject("data")
            val media = data?.optJSONObject("Media")
            val characters = media?.optJSONObject("characters")
            val edges = characters?.optJSONArray("edges") ?: JSONArray()

            for (i in 0 until edges.length()) {
                val edge = edges.getJSONObject(i)
                val node = edge.optJSONObject("node") ?: continue
                val charName = node.optJSONObject("name")?.optString("full", "") ?: ""
                val charImg = node.optJSONObject("image")?.optStringOrNull("large")

                val vaArr = edge.optJSONArray("voiceActors")
                if (vaArr != null && vaArr.length() > 0) {
                    val va = vaArr.getJSONObject(0)
                    val vaName = va.optJSONObject("name")?.optString("full", "") ?: ""
                    val vaImg = va.optJSONObject("image")?.optStringOrNull("large")

                    if (vaName.isNotEmpty()) {
                        castList.add(
                            ActorData(
                                actor = Actor(charName, charImg),
                                roleString = "Voice Actor",
                                voiceActor = Actor(vaName, vaImg)
                            )
                        )
                    }
                } else {
                    castList.add(
                        ActorData(
                            actor = Actor(charName, charImg),
                            roleString = edge.optString("role", "Supporting")
                        )
                    )
                }
            }
        }

        return castList
    }

    // ═══════════════════════════════════════════════════════════════════
    //                         HELPERS
    // ═══════════════════════════════════════════════════════════════════
    private fun JSONObject.toSearchResponse(): SearchResponse {
        val id = optInt("id")
        val title = optString("title", "Unknown")
        val poster = optStringOrNull("poster")
        val slug = optStringOrNull("slug") ?: ""
        val subCount = optInt("is_sub", 0)
        val dubCount = optInt("is_dub", 0)
        val type = optJSONObject("terms_by_type")?.optJSONArray("type")
            ?.let { if (it.length() > 0) it.getString(0) else null }

        val tvType = when (type?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA" -> TvType.OVA
            else -> TvType.Anime
        }

        val href = "${mainUrl}/detail/${id}/${slug}"

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            addDubStatus(
                dubExist = dubCount > 0, subExist = subCount > 0,
                dubEpisodes = if (dubCount > 0) dubCount else null,
                subEpisodes = if (subCount > 0) subCount else null,
            )
        }
    }

    private suspend fun resolveAnimeId(url: String): Int {
        Regex("""/detail/(d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        val slug = Regex("""/watch/([^/]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/anime/([^/]+)""").find(url)?.groupValues?.get(1)

        if (slug != null) {
            for (pg in 1..10) {
                val text = runCatching { app.get("${ANIKOTO_API}/recent-anime?page=${pg}&per_page=50").text }
                    .getOrNull() ?: break
                val root = JSONObject(text)
                val arr = root.optJSONArray("data") ?: break
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    if (a.optStringOrNull("slug") == slug) return a.optInt("id")
                }
                val p = root.optJSONObject("pagination")
                if ((p?.optInt("current_page", 1) ?: 1) >= (p?.optInt("last_page", 1) ?: 1)) break
            }
        }
        return 0
    }

    private fun String.toImg(size: String): String? =
        takeIf { it.isNotBlank() && it != "null" }?.let { "${IMG_BASE}/${size}${it}" }

    private fun parseDuration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val h = Regex("""(d+)h""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("""(d+)s*m""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (h * 60 + m).takeIf { it > 0 }
    }

    private fun JSONArray.pickLogo(): String? {
        for (i in 0 until length()) {
            val l = optJSONObject(i) ?: continue
            val p = l.optString("file_path", "")
            if (p.isBlank() || p.endsWith(".svg", true)) continue
            if (l.optString("iso_639_1", "").trim().lowercase() == "en") return "${IMG_BASE}/w500${p}"
        }
        for (i in 0 until length()) {
            val l = optJSONObject(i) ?: continue
            val p = l.optString("file_path", "")
            if (p.isNotBlank() && !p.endsWith(".svg", true)) return "${IMG_BASE}/w500${p}"
        }
        return null
    }

    private fun JSONArray.pickTrailer(): String? {
        for (i in 0 until length()) {
            val v = optJSONObject(i) ?: continue
            if (!v.optString("site").equals("YouTube", true)) continue
            val key = v.optString("key", "").takeIf { it.isNotBlank() } ?: continue
            if (v.optString("type").equals("Trailer", true) && v.optBoolean("official", false))
                return "https://www.youtube.com/watch?v=${key}"
        }
        for (i in 0 until length()) {
            val v = optJSONObject(i) ?: continue
            if (!v.optString("site").equals("YouTube", true)) continue
            val key = v.optString("key", "").takeIf { it.isNotBlank() } ?: continue
            if (v.optString("type").equals("Trailer", true))
                return "https://www.youtube.com/watch?v=${key}"
        }
        return null
    }
}
