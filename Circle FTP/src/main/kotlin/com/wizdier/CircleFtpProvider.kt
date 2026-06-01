package com.wizdier

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.getDurationFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import kotlin.math.max

class CircleFtpProvider : MainAPI() {
    override var mainUrl = "http://new.circleftp.net"
    private var mainApiUrl = "http://new.circleftp.net:5000"
    private val apiUrl = "http://15.1.1.50:5000"
    override var name = "Circle FTP"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.AsianDrama,
        TvType.Documentary,
        TvType.OVA,
        TvType.Others
    )

    override val mainPage = mainPageOf(
        "80" to "Featured",
        "6" to "English Movies",
        "9" to "English & Foreign TV Series",
        "22" to "Dubbed TV Series",
        "2" to "Hindi Movies",
        "5" to "Hindi TV Series",
        "238" to "Indian TV Show",
        "7" to "English & Foreign Hindi Dubbed Movies",
        "8" to "Foreign Language Movies",
        "3" to "South Indian Dubbed Movies",
        "4" to "South Indian Movies",
        "1" to "Animation Movies",
        "21" to "Anime Series",
        "85" to "Documentary",
        "15" to "WWE"
    )

    // ------------------------------------------------------------------
    // Metadata configuration
    // ------------------------------------------------------------------
    // TMDB v3 API key. Required to enrich movies & live-action series with
    // posters, landscape backdrops, title logos, trailers and IMDB/TMDB ids.
    // Leave blank to disable TMDB enrichment gracefully.
    private val tmdbApiKey = ""

    // Simkl client id. Optional. Used only to resolve a Simkl tracking id
    // from an anime's MAL id. Leave blank to disable.
    private val simklClientId = ""

    private val imageCdn = "https://image.tmdb.org/t/p"

    // Simple in-memory caches so the same title is never resolved twice.
    private val metaCache = HashMap<String, Meta?>()

    // ==================================================================
    // Home page
    // ==================================================================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val json = safeGet(
            "$mainApiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=20",
            "$apiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=20"
        )
        val posts = AppUtils.parseJson<PageData>(json.text).posts
        return newHomePageResponse(request.name, groupPosts(posts), true)
    }

    // ==================================================================
    // Search
    // ==================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val json = safeGet(
            "$mainApiUrl/api/posts?searchTerm=${query.enc()}&order=desc",
            "$apiUrl/api/posts?searchTerm=${query.enc()}&order=desc"
        )
        val posts = AppUtils.parseJson<PageData>(json.text).posts
        return groupPosts(posts)
    }

    /**
     * Collapses a raw post list into clean, de-duplicated search results.
     *
     * Posts that resolve to the same canonical title (after stripping quality
     * and audio tags) are merged into a single entry whose load url carries
     * every underlying post id, so their links surface together — each tagged
     * with its own audio variant (Dual / Multi / Dubbed / Subbed).
     */
    private fun groupPosts(posts: List<Post>): List<SearchResponse> {
        val groups = LinkedHashMap<String, MutableList<Post>>()
        posts.filter { it.type == "singleVideo" || it.type == "series" }.forEach { post ->
            val key = mergeKey(post.title) + "::" + post.type
            groups.getOrPut(key) { mutableListOf() }.add(post)
        }
        return groups.values.mapNotNull { group ->
            if (group.size == 1) toSearchResult(group.first())
            else toMergedSearchResult(group)
        }
    }

    private fun toSearchResult(post: Post): SearchResponse {
        val display = cleanTitle(post.title)
        val raw = post.title.lowercase()
        return newAnimeSearchResponse(display, "$mainUrl/content/${post.id}", TvType.Movie) {
            this.posterUrl = "$mainApiUrl/uploads/${post.imageSm}"
            this.quality = getSearchQuality(raw)
            addDubStatus(dubExist = isDub(raw), subExist = !isDub(raw))
        }
    }

    private fun toMergedSearchResult(group: List<Post>): SearchResponse {
        val first = group.first()
        val display = cleanTitle(first.title)
        val ids = group.joinToString(",") { it.id.toString() }
        val raw = group.joinToString(" ") { it.title.lowercase() }
        return newAnimeSearchResponse(display, "$mainUrl/merged/$ids", TvType.Movie) {
            this.posterUrl = "$mainApiUrl/uploads/${first.imageSm}"
            this.quality = getSearchQuality(raw)
            addDubStatus(dubExist = isDub(raw), subExist = true)
        }
    }

    // ==================================================================
    // Load
    // ==================================================================
    override suspend fun load(url: String): LoadResponse {
        if ("/merged/" in url) return loadMerged(url)

        val id = url.substringAfter("/content/").substringBefore("?")
        val requestedSeason = Regex("season=(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

        val fetched = fetchPost(id)
        val loadData = AppUtils.parseJson<Data>(fetched.text)
        val cleanName = cleanTitle(loadData.title)
        val fallbackPoster = "$apiUrl/uploads/${loadData.image}"
        val fallbackYear = selectUntilNonInt(loadData.year)
        val isDubbed = isDub(loadData.title)

        // ---- Single video (movie / anime movie) ----
        if (loadData.type == "singleVideo") {
            val movieUrl = tryParseJson<Movies>(fetched.text)?.content
            val link = if (fetched.useMain) movieUrl else linkToIp(movieUrl)
            val data = encodeLinks(listOf(LinkData(audioLabel(loadData.title), link ?: "")))
            val meta = fetchMeta(cleanName, fallbackYear, isTv = false)
            val type = if (meta?.isAnime == true) TvType.AnimeMovie else TvType.Movie
            val fb = Fallback(meta?.title ?: cleanName, fallbackPoster, loadData.metaData, fallbackYear)
            return newMovieLoadResponse(fb.title, url, type, data) {
                applyMeta(meta, fb)
                this.duration = getDurationFromString(loadData.watchTime)
            }
        }

        // ---- Series (possibly multi-season stacked as one item) ----
        val tvData = tryParseJson<TvSeries>(fetched.text)
        val seasons = tvData?.content ?: emptyList()
        val seasonCount = seasons.size
        val selected = requestedSeason.coerceIn(1, max(1, seasonCount))
        val current = seasons.getOrNull(selected - 1)

        // Each season is treated as its own item with its own metadata.
        val seasonLabel = current?.seasonName?.takeIf { it.isNotBlank() }
        val metaQuery = when {
            seasonCount <= 1 -> cleanName
            seasonLabel != null -> "$cleanName ${cleanSeasonName(seasonLabel)}"
            else -> "$cleanName Season $selected"
        }
        val meta = fetchMeta(metaQuery, fallbackYear, isTv = true, seasonHint = selected)

        val episodes = current?.episodes?.mapIndexed { index, ep ->
            val epLink = if (fetched.useMain) ep.link else linkToIp(ep.link)
            newEpisode(encodeLinks(listOf(LinkData(audioLabel(loadData.title), epLink)))) {
                this.episode = index + 1
                this.season = 1
            }
        } ?: emptyList()

        // Other seasons become recommendations, each with its own metadata.
        val recommendations = if (seasonCount > 1) {
            seasons.mapIndexedNotNull { index, season ->
                val seasonNo = index + 1
                if (seasonNo == selected) return@mapIndexedNotNull null
                val recQuery = season.seasonName?.takeIf { it.isNotBlank() }
                    ?.let { "$cleanName ${cleanSeasonName(it)}" } ?: "$cleanName Season $seasonNo"
                val recMeta = fetchMeta(recQuery, fallbackYear, isTv = true, seasonHint = seasonNo)
                newAnimeSearchResponse(
                    recMeta?.title ?: "$cleanName Season $seasonNo",
                    "$mainUrl/content/$id?season=$seasonNo",
                    TvType.Anime
                ) {
                    this.posterUrl = recMeta?.poster ?: fallbackPoster
                }
            }
        } else null

        val displayTitle = (meta?.title ?: cleanName).let {
            if (seasonCount > 1) "$it — Season $selected" else it
        }
        val fb = Fallback(displayTitle, fallbackPoster, loadData.metaData, fallbackYear)

        return if (meta?.isAnime == true) {
            newAnimeLoadResponse(displayTitle, url, TvType.Anime) {
                addEpisodes(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, episodes)
                applyMeta(meta, fb)
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(displayTitle, url, TvType.TvSeries, episodes) {
                applyMeta(meta, fb)
                this.recommendations = recommendations
            }
        }
    }

    /**
     * Loads a merged item: several posts that share a canonical title but
     * differ only by audio. Their links are combined into one entry, each
     * link tagged with its audio variant.
     */
    private suspend fun loadMerged(url: String): LoadResponse {
        val ids = url.substringAfter("/merged/").split(",").filter { it.isNotBlank() }
        val variants = ids.mapNotNull { id ->
            runCatching {
                val fetched = fetchPost(id)
                Variant(AppUtils.parseJson<Data>(fetched.text), fetched.text, fetched.useMain)
            }.getOrNull()
        }
        if (variants.isEmpty()) throw RuntimeException("No content available")

        val first = variants.first().data
        val cleanName = cleanTitle(first.title)
        val fallbackPoster = "$apiUrl/uploads/${first.image}"
        val fallbackYear = selectUntilNonInt(first.year)
        val isTv = first.type == "series"
        val meta = fetchMeta(cleanName, fallbackYear, isTv = isTv)
        val fb = Fallback(meta?.title ?: cleanName, fallbackPoster, first.metaData, fallbackYear)

        if (!isTv) {
            val links = variants.mapNotNull { v ->
                val content = tryParseJson<Movies>(v.text)?.content ?: return@mapNotNull null
                val link = if (v.useMain) content else linkToIp(content)
                if (link.isNullOrBlank()) null else LinkData(audioLabel(v.data.title), link)
            }
            val type = if (meta?.isAnime == true) TvType.AnimeMovie else TvType.Movie
            return newMovieLoadResponse(fb.title, url, type, encodeLinks(links)) {
                applyMeta(meta, fb)
                this.duration = getDurationFromString(first.watchTime)
            }
        }

        // Merge episodes across every audio variant, keyed by season+episode.
        val episodeMap = LinkedHashMap<Pair<Int, Int>, MutableList<LinkData>>()
        variants.forEach { v ->
            val tv = tryParseJson<TvSeries>(v.text) ?: return@forEach
            val label = audioLabel(v.data.title)
            var seasonNo = 0
            tv.content.forEach { season ->
                seasonNo++
                var episodeNo = 0
                season.episodes.forEach { ep ->
                    episodeNo++
                    val link = if (v.useMain) ep.link else linkToIp(ep.link)
                    if (!link.isNullOrBlank()) {
                        episodeMap.getOrPut(seasonNo to episodeNo) { mutableListOf() }
                            .add(LinkData(label, link))
                    }
                }
            }
        }
        val episodes = episodeMap.entries.map { (key, links) ->
            newEpisode(encodeLinks(links)) {
                this.season = key.first
                this.episode = key.second
            }
        }
        val type = if (meta?.isAnime == true) TvType.Anime else TvType.TvSeries
        return newTvSeriesLoadResponse(fb.title, url, type, episodes) {
            applyMeta(meta, fb)
        }
    }

    // ==================================================================
    // Links
    // ==================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        decodeLinks(data).filter { it.url.isNotBlank() }.forEach { link ->
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = if (link.name.isBlank()) this.name else "${this.name} • ${link.name}",
                    url = link.url
                )
            )
        }
        return true
    }

    // ==================================================================
    // Metadata enrichment
    // ==================================================================
    private fun LoadResponse.applyMeta(meta: Meta?, fb: Fallback) {
        this.posterUrl = meta?.poster ?: fb.poster
        this.backgroundPosterUrl = meta?.landscape ?: fb.poster
        this.plot = meta?.plot ?: fb.plot
        this.year = meta?.year ?: fb.year
        meta?.tags?.takeIf { it.isNotEmpty() }?.let { this.tags = it.take(6) }
        meta?.trailer?.let { addTrailer(it) }
        // Trackers: MAL & AniList via first-party helpers, the rest via syncData.
        meta?.malId?.let { addMalId(it) }
        meta?.aniListId?.let { addAniListId(it) }
        meta?.simklId?.let { this.syncData["simkl"] = it.toString() }
        meta?.kitsuId?.let { this.syncData["kitsu"] = it.toString() }
        meta?.imdbId?.let { this.syncData["imdb"] = it }
        meta?.tmdbId?.let { this.syncData["tmdb"] = it.toString() }
    }

    private suspend fun fetchMeta(
        title: String,
        year: Int?,
        isTv: Boolean,
        seasonHint: Int = 1
    ): Meta? {
        val key = "$isTv|$seasonHint|${title.lowercase()}|$year"
        metaCache[key]?.let { return it }
        if (metaCache.containsKey(key)) return null

        val resolved = runCatching {
            anilistMeta(title) ?: tmdbMeta(title, year, isTv)
        }.getOrNull()
        metaCache[key] = resolved
        return resolved
    }

    private suspend fun anilistMeta(title: String): Meta? {
        val query = """
            query (${'$'}search: String) {
              Media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
                id idMal format seasonYear averageScore
                description(asHtml: false)
                genres
                title { romaji english }
                coverImage { extraLarge large }
                bannerImage
                trailer { id site }
              }
            }
        """.trimIndent()
        val media = runCatching {
            app.post(
                "https://graphql.anilist.co",
                json = mapOf("query" to query, "variables" to mapOf("search" to title)),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                cacheTime = 120
            ).parsedSafe<AniListResponse>()?.data?.media
        }.getOrNull() ?: return null

        // Only accept the match if it genuinely resembles the request, otherwise
        // live-action titles would be mislabelled as anime.
        val romaji = media.title?.romaji
        val english = media.title?.english
        if (!similar(title, romaji) && !similar(title, english)) return null

        val malId = media.idMal
        return Meta(
            isAnime = true,
            title = english ?: romaji ?: title,
            poster = media.coverImage?.extraLarge ?: media.coverImage?.large,
            landscape = media.bannerImage,
            logo = null,
            trailer = media.trailer
                ?.takeIf { it.site.equals("youtube", true) && !it.id.isNullOrBlank() }
                ?.let { "https://www.youtube.com/watch?v=${it.id}" },
            plot = media.description?.let { stripHtml(it) },
            year = media.seasonYear,
            malId = malId,
            aniListId = media.id,
            simklId = simklFromMal(malId),
            kitsuId = kitsuFromMal(malId),
            imdbId = null,
            tmdbId = null,
            tags = media.genres
        )
    }

    private suspend fun tmdbMeta(title: String, year: Int?, isTv: Boolean): Meta? {
        if (tmdbApiKey.isBlank()) return null
        val type = if (isTv) "tv" else "movie"
        val yearParam = year?.let { if (isTv) "&first_air_date_year=$it" else "&year=$it" } ?: ""
        val first = runCatching {
            app.get(
                "https://api.themoviedb.org/3/search/$type?api_key=$tmdbApiKey&query=${title.enc()}$yearParam",
                cacheTime = 120
            ).parsedSafe<TmdbSearch>()?.results?.firstOrNull()
        }.getOrNull() ?: return null

        val detail = runCatching {
            app.get(
                "https://api.themoviedb.org/3/$type/${first.id}?api_key=$tmdbApiKey" +
                    "&append_to_response=images,videos,external_ids&include_image_language=en,null",
                cacheTime = 120
            ).parsedSafe<TmdbDetail>()
        }.getOrNull()

        val logo = detail?.images?.logos
            ?.sortedByDescending { it.iso6391 == "en" }
            ?.firstOrNull()?.filePath
        val trailerKey = detail?.videos?.results?.firstOrNull {
            it.site.equals("YouTube", true) && (it.type == "Trailer" || it.type == "Teaser")
        }?.key
        val resolvedYear = (detail?.releaseDate ?: detail?.firstAirDate
        ?: first.releaseDate ?: first.firstAirDate)?.take(4)?.toIntOrNull() ?: year

        return Meta(
            isAnime = false,
            title = detail?.title ?: detail?.name ?: first.title ?: first.name ?: title,
            poster = first.posterPath?.let { "$imageCdn/w500$it" },
            landscape = (first.backdropPath ?: detail?.backdropPath)?.let { "$imageCdn/original$it" },
            logo = logo?.let { "$imageCdn/original$it" },
            trailer = trailerKey?.let { "https://www.youtube.com/watch?v=$it" },
            plot = detail?.overview ?: first.overview,
            year = resolvedYear,
            malId = null,
            aniListId = null,
            simklId = null,
            kitsuId = null,
            imdbId = detail?.externalIds?.imdbId,
            tmdbId = first.id,
            tags = detail?.genres?.map { it.name }
        )
    }

    private suspend fun kitsuFromMal(malId: Int?): Int? {
        malId ?: return null
        return runCatching {
            app.get(
                "https://kitsu.io/api/edge/mappings?filter[externalSite]=myanimelist/anime" +
                    "&filter[externalId]=$malId&include=item",
                cacheTime = 120
            ).parsedSafe<KitsuMapping>()?.included?.firstOrNull()?.id?.toIntOrNull()
        }.getOrNull()
    }

    private suspend fun simklFromMal(malId: Int?): Int? {
        if (malId == null || simklClientId.isBlank()) return null
        return runCatching {
            app.get(
                "https://api.simkl.com/search/id?mal=$malId&client_id=$simklClientId",
                cacheTime = 120
            ).parsedSafe<List<SimklResult>>()?.firstOrNull()?.ids?.simkl
        }.getOrNull()
    }

    // ==================================================================
    // Helpers
    // ==================================================================
    private suspend fun safeGet(primary: String, fallback: String) = try {
        app.get(primary, verify = false, cacheTime = 60)
    } catch (_: Exception) {
        app.get(fallback, verify = false, cacheTime = 60)
    }

    private suspend fun fetchPost(id: String): Fetched {
        return try {
            val r = app.get("$mainApiUrl/api/posts/$id", verify = false, cacheTime = 60)
            Fetched(r.text, true)
        } catch (_: Exception) {
            val r = app.get("$apiUrl/api/posts/$id", verify = false, cacheTime = 60)
            Fetched(r.text, false)
        }
    }

    private fun encodeLinks(links: List<LinkData>): String =
        base64Encode(toJson(links).toByteArray())

    private fun decodeLinks(data: String): List<LinkData> = try {
        tryParseJson<List<LinkData>>(base64Decode(data)) ?: listOf(LinkData("", data))
    } catch (_: Exception) {
        listOf(LinkData("", data))
    }

    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")

    private fun linkToIp(data: String?): String {
        if (data.isNullOrBlank()) return ""
        ipMap.forEach { (host, ip) -> if (host in data) return data.replace(host, ip) }
        return data
    }

    private val ipMap = mapOf(
        "index.circleftp.net" to "15.1.4.2",
        "index2.circleftp.net" to "15.1.4.5",
        "index1.circleftp.net" to "15.1.4.9",
        "ftp3.circleftp.net" to "15.1.4.7",
        "ftp4.circleftp.net" to "15.1.1.5",
        "ftp5.circleftp.net" to "15.1.1.15",
        "ftp6.circleftp.net" to "15.1.2.3",
        "ftp7.circleftp.net" to "15.1.4.8",
        "ftp8.circleftp.net" to "15.1.2.2",
        "ftp9.circleftp.net" to "15.1.2.12",
        "ftp10.circleftp.net" to "15.1.4.3",
        "ftp11.circleftp.net" to "15.1.2.6",
        "ftp12.circleftp.net" to "15.1.2.1",
        "ftp13.circleftp.net" to "15.1.1.18",
        "ftp15.circleftp.net" to "15.1.4.12",
        "ftp17.circleftp.net" to "15.1.3.8"
    )

    private fun selectUntilNonInt(string: String?): Int? {
        return string?.let { Regex("^.*?(?=\\D|$)").find(it)?.value?.toIntOrNull() }
    }

    // ---- Title normalisation ----
    private val bracketRegex = Regex("[\\[(\\{][^\\]\\)\\}]*[\\])\\}]")
    private val sizeRegex = Regex("(?i)\\b\\d+(?:\\.\\d+)?\\s?(?:mb|gb)\\b")
    private val seasonEpRegex =
        Regex("(?i)\\b(?:s\\d{1,2}(?:e\\d{1,3})?|season\\s?\\d{1,2}|episode\\s?\\d{1,3}|ep\\s?\\d{1,3}|part\\s?\\d{1,2}|complete)\\b")
    private val multiSpace = Regex("\\s{2,}")
    private val junkRegex = Regex(
        "(?i)\\b(?:" +
            "480p|540p|720p|1080p|1440p|2160p|4k|8k|uhd|fhd|qhd|hd|sd|hdr10|hdr|dolby\\s?vision|dovi|dv|" +
            "bluray|blu-ray|brrip|bdrip|bd|web-?dl|webrip|web|hdrip|hdtv|hdcam|hdts|hdtc|dvdrip|dvdscr|dvd|" +
            "camrip|cam|telesync|telecine|remux|hc|" +
            "x264|x265|h\\.?264|h\\.?265|hevc|avc|xvid|divx|10\\s?bit|8\\s?bit|hi10p|" +
            "dual\\s?audio|multi\\s?audio|dual|multi|dubbed|dub|subbed|sub|esubs?|msubs?|hindi\\s?dub|" +
            "aac|ac3|eac3|ddp?\\s?5\\.1|dd\\s?2\\.0|dts|truehd|atmos|5\\.1|7\\.1|2\\.0|flac|mp3|opus|" +
            "extended|uncut|unrated|proper|repack|limited|internal|batch|imax|remastered|esub" +
            ")\\b"
    )

    private fun cleanTitle(raw: String): String {
        var t = " $raw "
        t = bracketRegex.replace(t, " ")
        t = t.replace(Regex("[._]"), " ")
        t = sizeRegex.replace(t, " ")
        t = junkRegex.replace(t, " ")
        t = multiSpace.replace(t, " ").trim().trim('-', ':', '|', '.', ',', ' ').trim()
        return t.ifBlank { raw.trim() }
    }

    private fun cleanSeasonName(name: String): String =
        name.trim().ifBlank { "" }

    private fun mergeKey(raw: String): String =
        cleanTitle(seasonEpRegex.replace(raw, " "))
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")

    private fun similar(a: String?, b: String?): Boolean {
        val x = a?.lowercase()?.replace(Regex("[^a-z0-9]"), "") ?: return false
        val y = b?.lowercase()?.replace(Regex("[^a-z0-9]"), "") ?: return false
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
            .let { multiSpace.replace(it, " ") }
            .trim()

    private fun isDub(title: String): Boolean {
        val t = title.lowercase()
        return listOf("dubbed", "dual audio", "multi audio", "dual-audio", "multi-audio", "hindi dub")
            .any { it in t }
    }

    private fun audioLabel(title: String): String {
        val t = title.lowercase()
        return when {
            "multi audio" in t || "multi-audio" in t -> "Multi Audio"
            "dual audio" in t || "dual-audio" in t -> "Dual Audio"
            "dubbed" in t || "hindi dub" in t -> "Dubbed"
            "esub" in t || "subbed" in t -> "Subbed"
            else -> "Original"
        }
    }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val c = check?.lowercase() ?: return null
        return when {
            c.contains("webrip") || c.contains("web-dl") || c.contains("webdl") -> SearchQuality.WebRip
            c.contains("bluray") || c.contains("blu-ray") -> SearchQuality.BlueRay
            c.contains("hdts") || c.contains("hdcam") || c.contains("hdtc") -> SearchQuality.HdCam
            c.contains("dvd") -> SearchQuality.DVD
            c.contains("camrip") -> SearchQuality.CamRip
            c.contains("cam") -> SearchQuality.Cam
            c.contains("hdrip") || c.contains("hdtv") || c.contains("hd") -> SearchQuality.HD
            c.contains("telesync") -> SearchQuality.Telesync
            c.contains("telecine") -> SearchQuality.Telecine
            else -> null
        }
    }

    // ==================================================================
    // Data models
    // ==================================================================
    private data class Fetched(val text: String, val useMain: Boolean)
    private data class Variant(val data: Data, val text: String, val useMain: Boolean)
    private data class Fallback(val title: String, val poster: String?, val plot: String?, val year: Int?)

    data class LinkData(val name: String, val url: String)

    data class Meta(
        val isAnime: Boolean,
        val title: String?,
        val poster: String?,
        val landscape: String?,
        val logo: String?,
        val trailer: String?,
        val plot: String?,
        val year: Int?,
        val malId: Int?,
        val aniListId: Int?,
        val simklId: Int?,
        val kitsuId: Int?,
        val imdbId: String?,
        val tmdbId: Int?,
        val tags: List<String>?
    )

    data class PageData(val posts: List<Post>)

    data class Post(
        val id: Int,
        val type: String,
        val imageSm: String,
        val title: String,
        val name: String?
    )

    data class Data(
        val type: String,
        val imageSm: String,
        val title: String,
        val image: String,
        val metaData: String?,
        val name: String,
        val quality: String?,
        val year: String?,
        val watchTime: String?
    )

    data class TvSeries(val content: List<Content>)
    data class Content(val episodes: List<EpisodeData>, val seasonName: String?)
    data class EpisodeData(val link: String, val title: String?)
    data class Movies(val content: String?)

    // ---- AniList ----
    data class AniListResponse(@JsonProperty("data") val data: AniListData?)
    data class AniListData(@JsonProperty("Media") val media: AniListMedia?)
    data class AniListMedia(
        val id: Int?,
        val idMal: Int?,
        val format: String?,
        val seasonYear: Int?,
        val averageScore: Int?,
        val description: String?,
        val genres: List<String>?,
        val title: AniListTitle?,
        val coverImage: AniListCover?,
        val bannerImage: String?,
        val trailer: AniListTrailer?
    )

    data class AniListTitle(val romaji: String?, val english: String?)
    data class AniListCover(val extraLarge: String?, val large: String?)
    data class AniListTrailer(val id: String?, val site: String?)

    // ---- Kitsu ----
    data class KitsuMapping(val included: List<KitsuItem>?)
    data class KitsuItem(val id: String?)

    // ---- Simkl ----
    data class SimklResult(val ids: SimklIds?)
    data class SimklIds(val simkl: Int?)

    // ---- TMDB ----
    data class TmdbSearch(val results: List<TmdbResult>?)
    data class TmdbResult(
        val id: Int?,
        val title: String?,
        val name: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        val overview: String?
    )

    data class TmdbDetail(
        val title: String?,
        val name: String?,
        val overview: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        val genres: List<TmdbGenre>?,
        val images: TmdbImages?,
        val videos: TmdbVideos?,
        @JsonProperty("external_ids") val externalIds: TmdbExternalIds?
    )

    data class TmdbGenre(val name: String)
    data class TmdbImages(val logos: List<TmdbLogo>?)
    data class TmdbLogo(
        @JsonProperty("file_path") val filePath: String?,
        @JsonProperty("iso_639_1") val iso6391: String?
    )

    data class TmdbVideos(val results: List<TmdbVideo>?)
    data class TmdbVideo(val site: String?, val type: String?, val key: String?)
    data class TmdbExternalIds(@JsonProperty("imdb_id") val imdbId: String?)
}
