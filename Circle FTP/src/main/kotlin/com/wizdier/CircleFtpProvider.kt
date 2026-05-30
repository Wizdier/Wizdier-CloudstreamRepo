package com.wizdier

import android.util.Log
import com.lagradost.cloudstream3.*
import com.wizdier.BuildConfig
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addTraktId
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import java.net.URLEncoder

class CircleFtpProvider : MainAPI() {
    override var mainUrl = "https://new.circleftp.net"
    private var mainApiUrl = "https://new.circleftp.net:5000"
    private val fallbackApiUrl = "https://15.1.1.50:5000"

    override var name = "Circle FTP"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true

    override val supportedSyncNames = setOf(
        SyncIdName.Anilist,
        SyncIdName.MyAnimeList,
        SyncIdName.Kitsu,
        SyncIdName.Simkl,
        SyncIdName.Imdb,
        SyncIdName.Trakt
    )

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

    // ─── API Client ────────────────────────────────────────────────────────

    private val apiClient = CircleFtpApiClient(mainApiUrl, fallbackApiUrl)

    // ─── Logging Helper ────────────────────────────────────────────────────

    private fun logError(method: String, e: Exception) {
        Log.w(CircleFtpPatterns.LOG_TAG, "$method: ${e.message}", e)
    }

    // ─── URL Construction / Parsing ─────────────────────────────────────────

    private fun makeContentUrl(
        postId: Int,
        seasonIndex: Int? = null,
        groupedPostIds: List<Int> = emptyList()
    ): String {
        val query = mutableListOf<String>()
        if (seasonIndex != null) query += "season=$seasonIndex"
        val variants = groupedPostIds.distinct().filter { variantId -> variantId != postId }
        if (variants.isNotEmpty()) query += "variants=${variants.joinToString(",")}"
        return if (query.isEmpty()) "$mainUrl/content/$postId"
        else "$mainUrl/content/$postId?${query.joinToString("&")}"
    }

    private fun selectedSeasonIndex(url: String): Int? =
        CircleFtpPatterns.RE_SEASON_PARAM.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun contentPostId(url: String): Int? =
        CircleFtpPatterns.RE_CONTENT_ID.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun groupedVariantPostIds(url: String): List<Int> {
        val baseId = contentPostId(url)
        val variantIds = CircleFtpPatterns.RE_VARIANTS_PARAM
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.split(",")
            ?.mapNotNull { v -> v.trim().toIntOrNull() }
            .orEmpty()
        return listOfNotNull(baseId).plus(variantIds).distinct()
    }

    // ─── FTP Domain → IP Mapping ──────────────────────────────────────────

    // IP mappings are loaded from BuildConfig.FTP_IP_OVERRIDES (set via the
    // FTP_IP_OVERRIDES env var at build time) so that internal IPs are never
    // committed to source control.  Format: "domain1=ip1,domain2=ip2,…"   If
    // the env var is empty the map stays empty and linkToIp becomes a no-op,
    // which means the original domain names are kept in stream URLs.
    private val ftpDomainToIp: Map<String, String> by lazy {
        val raw = BuildConfig.FTP_IP_OVERRIDES
        if (raw.isBlank()) emptyMap()
        else raw.split(",")
            .mapNotNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank())
                    parts[0].trim() to parts[1].trim()
                else null
            }
            .toMap(linkedMapOf())
    }

    private fun linkToIp(data: String?): String {
        if (data == null) return ""
        for ((domain, ip) in ftpDomainToIp) {
            if (data.contains(domain)) return data.replace(domain, ip)
        }
        return data
    }

    // ─── Stream Variant Helpers ──────────────────────────────────────────────

    private data class MergeKey(val title: String, val type: String, val year: Int?)

    data class StreamVariant(
        val url: String,
        val audio: String? = null,
        val sourceTitle: String? = null
    )

    private data class MergedEpisodeData(
        val episodeNumber: Int,
        val title: String?,
        val variants: List<StreamVariant>
    )

    private fun encodeStreamVariants(variants: List<StreamVariant>): String {
        val unique = variants.filter { v -> v.url.isNotBlank() }.distinctBy { v -> v.url }
        return if (unique.size == 1) unique.first().url else unique.toJson()
    }

    private fun parseStreamVariants(data: String): List<StreamVariant> {
        val trimmed = data.trim()
        if (!trimmed.startsWith("[")) return listOf(StreamVariant(url = data))
        return try {
            val array = JSONArray(trimmed)
            List(array.length()) { idx ->
                val item = array.getJSONObject(idx)
                StreamVariant(
                    url = item.optString("url"),
                    audio = item.optString("audio").takeIf { a -> a.isNotBlank() },
                    sourceTitle = item.optString("sourceTitle").takeIf { s -> s.isNotBlank() }
                )
            }.filter { v -> v.url.isNotBlank() }
        } catch (e: Exception) {
            logError("parseStreamVariants", e)
            listOf(StreamVariant(url = data))
        }
    }

    private fun sourceLabelForVariant(variant: StreamVariant): String {
        val audio = variant.audio ?: extractAudioTag(variant.sourceTitle)
        return if (audio.isNullOrBlank()) name else "$name [$audio]"
    }

    private fun mergeKeyForPost(post: Post): MergeKey {
        val rawTitle = post.name?.ifBlank { post.title } ?: post.title
        val isAnime = isAnimeContent(post.categories, post.title) || post.title.contains("anime", true)
        val isSeries = post.type.lowercase() == "series"
        val cleanedTitle = when {
            // Anime series: strip EVERY season marker so all seasons of a
            // franchise collapse to one merge key and group into a single tile.
            isAnime && isSeries -> stripSeasonSuffixForAnime(rawTitle).lowercase()
            isAnime -> cleanTitle(rawTitle).lowercase()
            else -> normalizeMergeTitle(rawTitle)
        }
        return MergeKey(
            title = cleanedTitle,
            type = post.type.lowercase(),
            year = if (isSeries && isAnime) null else selectUntilNonInt(post.year)
        )
    }

    // ─── Episode Stream Merging ──────────────────────────────────────────────

    private fun mergeEpisodeStreamsForSeason(
        contents: List<Content>,
        urlChecks: List<Boolean>,
        sourceTitles: List<String>
    ): List<MergedEpisodeData> {
        val variantsByEpisode = linkedMapOf<Int, MutableList<StreamVariant>>()
        val titleByEpisode = linkedMapOf<Int, String?>()

        contents.forEachIndexed { contentIndex, content ->
            content.episodes.forEachIndexed { episodeIndex, episodeData ->
                val episodeNumber = extractEpisodeNumber(episodeData.title) ?: (episodeIndex + 1)
                val link = if (urlChecks.getOrNull(contentIndex) == true) episodeData.link
                else linkToIp(episodeData.link)

                val audio = extractAudioTag(episodeData.title)
                    ?: extractAudioTag(sourceTitles.getOrNull(contentIndex))

                variantsByEpisode.getOrPut(episodeNumber) { mutableListOf() }
                    .add(StreamVariant(url = link, audio = audio, sourceTitle = episodeData.title))
                titleByEpisode.putIfAbsent(episodeNumber, episodeData.title)
            }
        }

        return variantsByEpisode.entries.map { entry ->
            MergedEpisodeData(
                episodeNumber = entry.key,
                title = titleByEpisode[entry.key],
                variants = entry.value.distinctBy { v -> v.url }
            )
        }
    }

    // ─── Search Result Builders ──────────────────────────────────────────────

    private suspend fun createSearchResult(
        post: Post,
        overrideTitle: String? = null,
        seasonIndex: Int? = null,
        posterOverride: String? = null,
        groupedPostIds: List<Int> = emptyList()
    ): SearchResponse? {
        val rawTitle = overrideTitle ?: post.name?.ifBlank { post.title } ?: post.title
        val url = makeContentUrl(post.id, seasonIndex, groupedPostIds)
        val quality = getSearchQuality((post.quality ?: post.title).lowercase())
        val isSeries = post.type == "series"
        val isAnime = isAnimeContent(post.categories, post.title) || post.title.contains("anime", true)
        val fallbackPoster = "$mainApiUrl/uploads/${post.imageSm}"
        val year = selectUntilNonInt(post.year)
        val cleanedForCache = cleanTitle(rawTitle).lowercase()
        val cachedAnimePoster = apiClient.getCachedAnimeMeta("${isSeries}|${year ?: 0}|$cleanedForCache")?.poster
        val cachedTmdbPoster = apiClient.getCachedTmdbMeta("${isSeries}|${year ?: 0}|$cleanedForCache")?.poster

        return if (isAnime) {
            newAnimeSearchResponse(rawTitle, url, if (isSeries) TvType.Anime else TvType.AnimeMovie) {
                this.posterUrl = posterOverride ?: cachedAnimePoster ?: fallbackPoster
                this.quality = quality
                addDubStatus(dubExist = isDubTitle(post.title), subExist = true)
            }
        } else if (isSeries) {
            newTvSeriesSearchResponse(rawTitle, url, TvType.TvSeries) {
                this.posterUrl = posterOverride ?: cachedTmdbPoster ?: fallbackPoster
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(rawTitle, url, TvType.Movie) {
                this.posterUrl = posterOverride ?: cachedTmdbPoster ?: fallbackPoster
                this.quality = quality
            }
        }
    }

    /**
     * Anime series = ONE base tile (season index 0). Everything else stays stacked as a single tile.
     */
    private suspend fun toSearchResults(post: Post, groupedPostIds: List<Int> = emptyList()): List<SearchResponse> {
        val isAnime = isAnimeContent(post.categories, post.title) || post.title.contains("anime", true)
        val baseTitle = post.name?.ifBlank { post.title } ?: post.title
        return if (post.type == "series" && isAnime) {
            listOfNotNull(
                createSearchResult(
                    post = post,
                    overrideTitle = stripSeasonSuffixForAnime(baseTitle),
                    seasonIndex = 0,
                    groupedPostIds = groupedPostIds
                )
            )
        } else {
            listOfNotNull(
                createSearchResult(
                    post = post,
                    overrideTitle = baseTitle,
                    seasonIndex = null,
                    groupedPostIds = groupedPostIds
                )
            )
        }
    }

    private suspend fun createMergedSearchResult(posts: List<Post>): List<SearchResponse> {
        if (posts.isEmpty()) return emptyList()
        val isAnimeGroup = posts.all { isAnimeContent(it.categories, it.title) || it.title.contains("anime", true) }
        val sortedPosts = if (isAnimeGroup) {
            posts.sortedBy { post -> extractSeasonNumberFromTitle(post.name ?: post.title) }
        } else {
            posts
        }
        val primary = sortedPosts.first()
        val groupedIds = sortedPosts.map { p -> p.id }.distinct()
        return toSearchResults(primary, groupedIds)
    }

    // ─── MainAPI Overrides ───────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = try {
            app.get("$mainApiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10", cacheTime = 60)
        } catch (e: Exception) {
            logError("getMainPage", e)
            app.get("$fallbackApiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10", cacheTime = 60)
        }

        val home = coroutineScope {
            AppUtils.parseJson<PageData>(json.text).posts
                .groupBy { post -> mergeKeyForPost(post) }
                .values
                .map { groupedPosts -> async { createMergedSearchResult(groupedPosts) } }
                .awaitAll()
                .flatten()
        }

        return newHomePageResponse(request.name, home, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val json = try {
            app.get("$mainApiUrl/api/posts?searchTerm=$encodedQuery&order=desc", cacheTime = 60)
        } catch (e: Exception) {
            logError("search", e)
            app.get("$fallbackApiUrl/api/posts?searchTerm=$encodedQuery&order=desc", cacheTime = 60)
        }

        return coroutineScope {
            AppUtils.parseJson<PageData>(json.text).posts
                .groupBy { post -> mergeKeyForPost(post) }
                .values
                .map { groupedPosts -> async { createMergedSearchResult(groupedPosts) } }
                .awaitAll()
                .flatten()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val postIds = groupedVariantPostIds(url)
        val responses = coroutineScope {
            postIds.map { postId -> async { apiClient.fetchPostResponse(postId) } }.awaitAll()
        }

        val primaryResponse = responses.first()
        val urlChecks = responses.map { r -> r.url.contains(mainApiUrl) }
        val loadDataList = responses.map { r -> AppUtils.parseJson<Data>(r.text) }
        val loadData = loadDataList.first()

        val title = loadData.name?.ifBlank { loadData.title } ?: loadData.title
        val cleanedTitle = cleanTitle(title)
        val fallbackPoster = "$fallbackApiUrl/uploads/${loadData.image}"
        val year = selectUntilNonInt(loadData.year)
        val isAnime = isAnimeContent(loadData.categories, title)
        val selectedSeason = selectedSeasonIndex(url)

        // ── Single-file movie / OVA ───────────────────────────────────────────
        if (loadData.type == "singleVideo") {
            val movieVariants = responses.mapIndexedNotNull { index, response ->
                val data = loadDataList.getOrNull(index) ?: return@mapIndexedNotNull null
                if (data.type != "singleVideo") return@mapIndexedNotNull null
                val movieUrl = response.parsed<Movies>().content ?: return@mapIndexedNotNull null
                val link = if (urlChecks.getOrNull(index) == true) movieUrl else linkToIp(movieUrl)
                StreamVariant(
                    url = link,
                    audio = extractAudioTag(data.title) ?: extractAudioTag(data.name),
                    sourceTitle = data.name ?: data.title
                )
            }

            val link = encodeStreamVariants(movieVariants)
            val duration = getDurationFromString(loadData.watchTime)

            return if (isAnime) {
                val meta = apiClient.resolveAnimeMetaCached(cleanedTitle, year, false)
                val trailer = meta.trailer
                newAnimeLoadResponse(meta.title.ifBlank { title }, url, TvType.AnimeMovie) {
                    this.posterUrl = meta.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta.background ?: meta.poster ?: fallbackPoster
                    this.year = year
                    this.plot = (meta.nextAiringInfo?.let { info -> "$info\n\n" } ?: "") + (meta.plot ?: loadData.metaData)
                    this.tags = meta.tags
                    this.score = Score.from100(meta.score100)
                    this.duration = duration
                    this.actors = meta.actors
                    this.logoUrl = meta.logoUrl
                    trailer?.let { t -> addTrailer(t) }
                    addAniListId(meta.anilistId)
                    addMalId(meta.malId)
                    meta.kitsuId?.let { k -> addKitsuId(k) }
                    meta.simklId?.let { s -> addSimklId(s) }
                    meta.traktId?.let { t -> addTraktId(t.toString()) }
                    meta.imdbId?.let { i -> addImdbId(i) }
                    addEpisodes(if (isDubTitle(title)) DubStatus.Dubbed else DubStatus.Subbed, listOf(newEpisode(link)))
                }
            } else {
                val meta = apiClient.getTmdbMetaCached(cleanedTitle, year, false)
                val trailer = apiClient.fetchTmdbTrailer(meta?.tmdbId, false)
                val actors = apiClient.fetchTmdbActors(meta?.tmdbId, false)
                newMovieLoadResponse(title, url, TvType.Movie, link) {
                    this.posterUrl = meta?.poster ?: fallbackPoster
                    this.backgroundPosterUrl = meta?.backdrop ?: meta?.poster ?: fallbackPoster
                    this.year = year
                    this.plot = meta?.overview ?: loadData.metaData
                    this.score = Score.from10(meta?.rating)
                    this.duration = duration
                    this.actors = actors
                    this.logoUrl = meta?.logoUrl
                    trailer?.let { t -> addTrailer(t) }
                    meta?.imdbId?.let { i -> addImdbId(i) }
                }
            }
        }

        // ── Series (Anime or regular TV) ─────────────────────────────────────
        val tvData = primaryResponse.parsed<TvSeries>()

        return if (isAnime) {
            val allParsedTv: List<TvSeries?> = responses.map { r ->
                try {
                    r.parsed<TvSeries>()
                } catch (e: Exception) {
                    logError("load-parseTvSeries", e)
                    null
                }
            }

            val isStacked = tvData.content.size > 1
            val allSeasons: List<Content>
            val seasonVariants: List<List<Content>>

            if (isStacked) {
                allSeasons = tvData.content
                seasonVariants = List(allSeasons.size) { sIdx ->
                    allParsedTv.mapNotNull { tv -> tv?.content?.getOrNull(sIdx) }
                }
            } else {
                allSeasons = allParsedTv.mapNotNull { tv -> tv?.content?.firstOrNull() }
                seasonVariants = allSeasons.map { content -> listOf(content) }
            }

            val targetIndex = selectedSeason ?: 0
            val currentSeasonData = allSeasons.getOrNull(targetIndex) ?: allSeasons.firstOrNull()
                ?: return newAnimeLoadResponse(title, url, TvType.Anime) {}

            val isMultiSeasonAnime = allSeasons.size > 1

            // ── Robust season-number resolution ───────────────────────────────
            // Order of trust:
            //   1. An explicit marker in THIS season's name ("2nd Season",
            //      "Part 2", "S3", roman numerals, trailing number, …)
            //   2. An explicit marker in the source/post title
            //   3. An explicit marker in the main title
            //   4. Positional index for stacked multi-season posts (index + 1)
            //   5. Final fallback: season 1
            // "Final Season" markers are mapped to the last positional slot so
            // they don't collapse back to season 1.
            val sourceTitleForSeason = loadDataList.getOrNull(targetIndex)?.let { d -> d.name ?: d.title }
            val explicitSeasonNumber =
                extractSeasonNumberOrNull(currentSeasonData.seasonName)
                    ?: extractSeasonNumberOrNull(sourceTitleForSeason)
                    ?: extractSeasonNumberOrNull(title)
            val isFinalSeason = isFinalSeasonMarker(currentSeasonData.seasonName) ||
                isFinalSeasonMarker(sourceTitleForSeason) ||
                isFinalSeasonMarker(title)

            val realSeasonNumber = when {
                explicitSeasonNumber != null -> explicitSeasonNumber
                isMultiSeasonAnime -> targetIndex + 1
                isFinalSeason -> 2 // a "Final Season" with no number is at least S2
                else -> 1
            }

            val metaTitle = titleForSeason(title, currentSeasonData.seasonName, realSeasonNumber)

            // ── Parallel fetch: baseMeta and season-specific resolution
            // baseMeta is the *franchise anchor* (Season 1). It MUST be resolved
            // from the franchise base title with every season suffix stripped so
            // that the AniList sequel-walk starts at Season 1 — otherwise titles
            // like "Overlord III" would anchor on Season 3 and overshoot.
            val franchiseBaseTitle = stripSeasonSuffixForAnime(title).ifBlank { cleanedTitle }
            val baseMeta: ResolvedAnimeMeta
            val seasonResolution: SeasonAnimeResolution
            coroutineScope {
                val baseMetaDeferred = async { apiClient.resolveAnimeMetaCached(franchiseBaseTitle, year, true) }
                baseMeta = baseMetaDeferred.await()
                seasonResolution = apiClient.resolveAnimeSeasonDynamically(
                    baseTitle = title,
                    seasonName = currentSeasonData.seasonName,
                    seasonNumber = realSeasonNumber,
                    year = year,
                    baseMeta = baseMeta
                )
            }

            val targetMeta = seasonResolution.meta
            val seasonIds = seasonResolution.ids
            val seasonAniListNode = seasonResolution.aniListNode
            val seasonAniZip = seasonResolution.aniZip

            // ── Parallel fetch: TMDB enrichment + AniZip episode titles
            val seasonTmdbId = seasonAniZip?.tmdbId?.toIntOrNull()
            val seasonTmdbMeta: TmdbMeta?
            val aniZipEpTitles: Map<Int, String>
            coroutineScope {
                val tmdbDeferred = async {
                    seasonTmdbId?.let { tId ->
                        TmdbMeta(
                            poster = null,
                            backdrop = apiClient.fetchTmdbBackdrop(tId, true),
                            rating = null,
                            overview = null,
                            logoUrl = apiClient.fetchTmdbLogo(tId, true),
                            imdbId = null,
                            tmdbId = tId
                        )
                    }
                }
                val aniZipEpTitlesDeferred = async {
                    seasonIds.anilistId
                        ?.let { alId -> apiClient.getAniZipEpisodeTitles(alId) }
                        ?: emptyMap()
                }
                seasonTmdbMeta = tmdbDeferred.await()
                aniZipEpTitles = aniZipEpTitlesDeferred.await()
            }

            val slotContents = seasonVariants.getOrNull(targetIndex) ?: emptyList()
            val sourceTitles = if (isStacked) {
                loadDataList.map { d -> d.name ?: d.title }
            } else {
                listOf(loadDataList.getOrNull(targetIndex)?.let { d -> d.name ?: d.title } ?: title)
            }
            val slotUrlChecks = if (isStacked) {
                urlChecks
            } else {
                listOf(urlChecks.getOrNull(targetIndex) ?: false)
            }

            val mergedEpisodes = mergeEpisodeStreamsForSeason(slotContents, slotUrlChecks, sourceTitles)

            // Rebase absolutely-numbered seasons (e.g. S2 starting at "Episode 26")
            // back to per-season numbering so episode titles, thumbnails and watch
            // tracking align with AniList / AniZip (which number each season from 1).
            val absOffset = absoluteEpisodeOffset(
                mergedEpisodes.map { it.episodeNumber },
                realSeasonNumber
            )

            val episodesData: List<Episode> = mergedEpisodes.mapIndexed { idx, mergedEpisode ->
                val epNum = (mergedEpisode.episodeNumber - absOffset).let { if (it >= 1) it else mergedEpisode.episodeNumber }
                val aniZipTitle = aniZipEpTitles[epNum]
                val aniListTitle = targetMeta.anilistEpisodes?.getOrNull(idx)?.title
                val serverTitle = mergedEpisode.title
                    ?.replace(CircleFtpPatterns.RE_EPISODE_WORD, "")
                    ?.trim()
                    ?.ifBlank { null }
                val epTitle = aniZipTitle ?: aniListTitle ?: serverTitle ?: "Episode $epNum"
                val thumbnail = targetMeta.anilistEpisodes?.getOrNull(idx)?.thumbnail ?: targetMeta.poster

                newEpisode(encodeStreamVariants(mergedEpisode.variants)) {
                    this.episode = epNum
                    this.season = realSeasonNumber
                    this.name = epTitle
                    this.posterUrl = thumbnail
                }
            }

            val recommendationItems: List<SearchResponse> = if (allSeasons.size <= 1) {
                emptyList()
            } else {
                val recommendationBaseId = postIds.firstOrNull() ?: loadData.id
                val seriesPoster = baseMeta.poster ?: fallbackPoster
                val currentSeasonPoster = if (targetMeta.poster != baseMeta.poster) {
                    targetMeta.poster ?: seriesPoster
                } else {
                    seriesPoster
                }
                allSeasons.mapIndexedNotNull { recIdx, _ ->
                    if (recIdx == targetIndex) return@mapIndexedNotNull null

                    val seasonNum = recIdx + 1
                    val seasonTitle = titleForSeason(title, allSeasons.getOrNull(recIdx)?.seasonName, seasonNum)
                    val recUrl = makeContentUrl(
                        postId = recommendationBaseId,
                        seasonIndex = recIdx,
                        groupedPostIds = postIds
                    )

                    newAnimeSearchResponse(seasonTitle, recUrl, TvType.Anime) {
                        this.posterUrl = seriesPoster
                        addDubStatus(dubExist = isDubTitle(title), subExist = true)
                    }
                }
            }

            val seasonTrailer: String? = apiClient.fetchTmdbTrailer(seasonTmdbMeta?.tmdbId, true)
                ?: apiClient.getAniListTrailerUrl(seasonAniListNode?.trailer)
                ?: targetMeta.trailer

            // Season 1: safe to merge with baseMeta as both should agree.
            // Season 2+: NEVER fall back to baseMeta — those are Season 1's IDs.
            val idsForCurrentSeason: SeasonIds = if (realSeasonNumber <= 1) {
                SeasonIds(
                    anilistId = seasonIds.anilistId ?: baseMeta.anilistId,
                    malId = seasonIds.malId ?: baseMeta.malId,
                    kitsuId = seasonIds.kitsuId ?: baseMeta.kitsuId,
                    simklId = seasonIds.simklId ?: baseMeta.simklId,
                    traktId = seasonIds.traktId ?: baseMeta.traktId
                )
            } else {
                // Season 2+ IDs are season-specific — no baseMeta fallback permitted.
                seasonIds
            }

            newAnimeLoadResponse(targetMeta.title.ifBlank { metaTitle }, url, TvType.Anime) {
                this.posterUrl = targetMeta.poster ?: fallbackPoster
                this.backgroundPosterUrl = apiClient.resolveBackdrop(
                    tmdbMeta = seasonTmdbMeta,
                    aniListMeta = seasonAniListNode,
                    fallback = targetMeta.background ?: targetMeta.poster ?: fallbackPoster
                )
                this.year = year
                this.plot = (targetMeta.nextAiringInfo?.let { info -> "$info\n\n" } ?: "") + (targetMeta.plot ?: loadData.metaData)
                this.tags = targetMeta.tags
                this.score = Score.from100(targetMeta.score100)
                this.actors = targetMeta.actors
                this.logoUrl = targetMeta.logoUrl ?: seasonTmdbMeta?.logoUrl
                seasonTrailer?.let { t -> addTrailer(t) }
                addAniListId(idsForCurrentSeason.anilistId)
                addMalId(idsForCurrentSeason.malId)
                idsForCurrentSeason.kitsuId?.let { k -> addKitsuId(k) }
                idsForCurrentSeason.simklId?.let { s -> addSimklId(s) }
                idsForCurrentSeason.traktId?.let { t -> addTraktId(t.toString()) }
                baseMeta.imdbId?.let { i -> addImdbId(i) }
                this.recommendations = recommendationItems
                addEpisodes(
                    if (isDubTitle(title)) DubStatus.Dubbed else DubStatus.Subbed,
                    episodesData
                )
            }
        } else {
            // ── Regular TV Series ────────────────────────────────────────────
            val meta = apiClient.getTmdbMetaCached(cleanedTitle, year, true)
            val tmdbId = meta?.tmdbId
            val trailer = apiClient.fetchTmdbTrailer(tmdbId, true)
            val actors = apiClient.fetchTmdbActors(tmdbId, true)
            val allSeriesVariants: List<TvSeries> = responses.mapNotNull { response ->
                try {
                    response.parsed<TvSeries>()
                } catch (e: Exception) {
                    logError("load-parseTvSeries", e)
                    null
                }
            }
            val sourceTitles = loadDataList.map { d -> d.name ?: d.title }
            val episodesData = mutableListOf<Episode>()

            tvData.content.forEachIndexed { seasonIndex, _ ->
                val seasonNum = seasonIndex + 1
                val tmdbEpisodes = tmdbId?.let { tId -> apiClient.fetchTmdbSeasonEpisodes(tId, seasonNum) } ?: emptyList()
                val seasonContents = allSeriesVariants.mapNotNull { series -> series.content.getOrNull(seasonIndex) }
                val mergedEpisodes = mergeEpisodeStreamsForSeason(seasonContents, urlChecks, sourceTitles)
                mergedEpisodes.forEachIndexed { idx, mergedEpisode ->
                    val tmdbEp = tmdbEpisodes.getOrNull(idx)
                    episodesData.add(
                        newEpisode(encodeStreamVariants(mergedEpisode.variants)) {
                            this.episode = mergedEpisode.episodeNumber
                            this.season = seasonNum
                            this.name = tmdbEp?.name?.takeIf { n -> n.isNotBlank() }
                                ?: mergedEpisode.title?.ifBlank { null }
                                ?: "Episode ${mergedEpisode.episodeNumber}"
                            this.posterUrl = tmdbEp?.stillPath?.let { p -> "https://image.tmdb.org/t/p/w500$p" } ?: meta?.poster
                            this.description = tmdbEp?.overview
                            parseAirDateMillis(tmdbEp?.airDate)?.let { d -> this.date = d }
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                this.posterUrl = meta?.poster ?: fallbackPoster
                this.backgroundPosterUrl = meta?.backdrop ?: meta?.poster ?: fallbackPoster
                this.year = year
                this.plot = meta?.overview ?: loadData.metaData
                this.score = Score.from10(meta?.rating)
                this.actors = actors
                this.logoUrl = meta?.logoUrl
                trailer?.let { t -> addTrailer(t) }
                meta?.imdbId?.let { i -> addImdbId(i) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val variants = parseStreamVariants(data)
        variants.forEach { variant ->
            val sourceLabel = sourceLabelForVariant(variant)
            callback.invoke(newExtractorLink(source = sourceLabel, name = sourceLabel, url = variant.url))
        }
        return variants.isNotEmpty()
    }
}
