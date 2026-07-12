package com.wizdier.anistream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ═══════════════════════════════════════════════════════════════
//  ANISTREAM EXTRACTORS
//  Every source uses CineStream's proven patterns. All sources run
//  concurrently; no source is skipped based on anime format.
// ═══════════════════════════════════════════════════════════════

// ── Data classes ──
data class EncDecResponse(@param:JsonProperty("result") val result: EncDecResult?)
data class EncDecResult(
    @param:JsonProperty("servers") val servers: String?,
    @param:JsonProperty("stream") val stream: String?,
    @param:JsonProperty("token") val token: String?,
)
data class VidlinkResponse(@param:JsonProperty("stream") val stream: VidlinkStream)
data class VidlinkStream(@param:JsonProperty("playlist") val playlist: String)
data class VidfastServers(@param:JsonProperty("name") val name: String?, @param:JsonProperty("description") val description: String?, @param:JsonProperty("data") val data: String?)
data class VidfastStreamResponse(val result: List<VidfastServers>)
data class VidfastServersStreamRoot(val result: VidfastServer)
data class VidfastServer(@param:JsonProperty("url") val url: String?, @param:JsonProperty("tracks") val tracks: List<VidfastTrack>?, @param:JsonProperty("4kAvailable") val is4kAvailable: Boolean?)
data class VidfastTrack(@param:JsonProperty("file") val file: String?, @param:JsonProperty("label") val label: String?)

data class EncryptedResponse(@param:JsonProperty("data") val data: EncryptedData? = null)
data class EncryptedData(@param:JsonProperty("_m") val _m: String? = null, @param:JsonProperty("tobeparsed") val tobeparsed: String? = null)

data class Anichi(val data: AnichiData)
data class AnichiData(val shows: AnichiShows)
data class AnichiShows(val edges: List<Edge>)
data class Edge(@param:JsonProperty("_id") val id: String, val name: String, val englishName: String, val nativeName: String)

data class AnichiEP(val data: AnichiEPData? = null, val episode: AnichiEpisode? = null)
data class AnichiEPData(val episode: AnichiEpisode? = null)
data class AnichiEpisode(val sourceUrls: List<SourceUrl> = emptyList())
data class SourceUrl(val sourceUrl: String, val sourceName: String)

data class AnichiVideoApiResponse(@param:JsonProperty("links") val links: List<AnichiLinks>)
data class AnichiLinks(
    @param:JsonProperty("link") val link: String,
    @param:JsonProperty("hls") val hls: Boolean? = null,
    @param:JsonProperty("resolutionStr") val resolutionStr: String,
    @param:JsonProperty("headers") val headers: AnichiHeaders? = null,
    @param:JsonProperty("subtitles") val subtitles: ArrayList<AnichiSubtitles>? = arrayListOf(),
)
data class AnichiHeaders(@param:JsonProperty("Referer") val referer: String? = null, @param:JsonProperty("user-agent") val userAgent: String? = null)
data class AnichiSubtitles(@param:JsonProperty("lang") val lang: String?, @param:JsonProperty("label") val label: String?, @param:JsonProperty("src") val src: String?)

data class animepahe(val data: List<Daum>)
data class Daum(val episode: Int, val session: String, val audio: String)

// ── Crypto helpers for AllAnime encrypted responses ──
fun decrypthex(inputStr: String): String {
    val hexString = if (inputStr.startsWith("-")) inputStr.substringAfterLast("-") else inputStr
    val bytes = ByteArray(hexString.length / 2) { i ->
        val hexByte = hexString.substring(i * 2, i * 2 + 2)
        (hexByte.toInt(16) and 0xFF).toByte()
    }
    return bytes.joinToString("") { (it.toInt() xor 56).toChar().toString() }
}

fun decodeToBeParsed(encoded: String): String? = try {
    val raw = base64DecodeArray(encoded)
    if (raw.size < 29) return null
    val iv = raw.copyOfRange(1, 13)
    val ctr = ByteArray(16).also { System.arraycopy(iv, 0, it, 0, iv.size); it[15] = 0x02 }
    val ciphertext = raw.copyOfRange(13, raw.size - 16)
    val key = MessageDigest.getInstance("SHA-256").digest("Xot36i3lK3:v1".toByteArray())
    val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ctr))
    }
    cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
} catch (e: Exception) { null }

// ═══════════════════════════════════════════════════════════════
//  SOURCE 1: Animepahe via MALSync — resolves exact anime URL by MAL ID
//  Much more reliable than title search (which matches wrong anime).
// ═══════════════════════════════════════════════════════════════
suspend fun invokeAnimepaheByMal(
    malId: Int?, episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (malId == null) return
    val headers = mapOf("User-Agent" to USER_AGENT, "Cookie" to "__ddg2_=1234567890")

    // Use MALSync to get the exact Animepahe URL for this MAL ID.
    val malsyncUrl = "$MALSYNC_API/mal/anime/$malId"
    val malsyncJson = try { app.get(malsyncUrl, headers = headers, timeout = 10_000).text }
                       catch (e: Exception) { return }
    val sites = try { JSONObject(malsyncJson).optJSONObject("Sites") } catch (e: Exception) { return } ?: return
    val animepaheObj = sites.optJSONObject("animepahe") ?: return
    val animepaheUrl = animepaheObj.keys()?.asSequence()?.firstOrNull()?.let { animepaheObj.optJSONObject(it)?.optString("url") }
        ?: return
    if (animepaheUrl.isBlank()) return

    // Fetch the anime page to get the internal ID (from og:url meta tag).
    val pageUrl = animepaheUrl.replace(".com", ".pw")
    val doc = try { cfGet(pageUrl, headers).document } catch (e: Exception) { return }
    val animeId = doc.selectFirst("meta[property=og:url]")?.attr("content")?.toString()?.substringAfterLast("/")
        ?: return
    if (animeId.isBlank()) return

    // Fetch the episode list.
    val epData = try {
        cfGet("$ANIMEPAHE_API/api?m=release&id=$animeId&sort=episode_asc&page=1", headers)
            .parsedSafe<animepahe>()
    } catch (e: Exception) { return } ?: return

    // Pick the episode session.
    val session = if (episode == null) epData.data.firstOrNull()?.session ?: return
                  else epData.data.firstOrNull { it.episode == episode }?.session ?: return

    // Fetch the play page which contains download + stream links.
    val playDoc = try { cfGet("$ANIMEPAHE_API/play/$animeId/$session", headers).document }
                  catch (e: Exception) { return }

    runLimitedAsync(
        { playDoc.select("div#pickDownload > a").safeAmap {
            val href = it.attr("href").ifBlank { return@safeAmap }
            val type = if (it.attr("data-audio") == "Eng") "DUB" else "SUB"
            loadCustomExtractor("Animepahe [$type]", href, "$ANIMEPAHE_API/",
                subtitleCallback, callback, getIndexQuality(it.text()))
        }},
        { playDoc.select("div#resolutionMenu > button").safeAmap {
            val type = if (it.attr("data-audio") == "Eng") "DUB" else "SUB"
            val quality = it.attr("data-resolution")
            val href = it.attr("data-src").ifBlank { return@safeAmap }
            if (href.contains("kwik.cx")) {
                loadCustomExtractor("Animepahe [$type]", href, "$ANIMEPAHE_API/",
                    subtitleCallback, callback, getQualityFromName(quality))
            }
        }},
        concurrency = 2
    )
}

// ═══════════════════════════════════════════════════════════════
//  SOURCE 2: AllAnime / MKissa — GraphQL API with persisted queries
// ═══════════════════════════════════════════════════════════════
suspend fun invokeAllanime(
    title: String, episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val type = if (episode == null) "Movie" else "TV"
    val q = quote(title)

    // Search for the show via persisted query.
    val searchQuery = "$ALLANIME_API?variables={\"search\":{\"types\":[\"$type\"],\"query\":\"$title\"},\"limit\":26,\"page\":1,\"translationType\":\"sub\",\"countryOrigin\":\"ALL\"}&extensions={\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"$ALLANIME_SEARCH_HASH\"}}"
    val res = try { app.get(searchQuery, referer = ALLANIME_REFERER, timeout = 15_000) }
              catch (e: Exception) { return }
    val edges = res.parsedSafe<Anichi>()?.data?.shows?.edges ?: return
    val showId = edges.firstOrNull()?.id ?: return

    val headers = mapOf(
        "app-version" to "android_c-253",
        "platformstr" to "android_c",
        "Referer" to ALLANIME_REFERER,
        "from-app" to base64Decode("YW5pbWVjaGlja2Vu")
    )

    // Try both sub and dub.
    listOf("sub", "dub").safeAmap { langType ->
        val epQuery = "$ALLANIME_API?variables={\"showId\":\"$showId\",\"translationType\":\"$langType\",\"episodeString\":\"${episode ?: 1}\"}&extensions={\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"$ALLANIME_EPISODE_HASH\"}}"
        val responseText = try { app.get(epQuery, referer = ALLANIME_REFERER, timeout = 15_000).text }
                           catch (e: Exception) { return@safeAmap }

        // Response may be encrypted — decrypt if needed.
        val episodeLinks = run {
            val encrypted = tryParseJson<EncryptedResponse>(responseText)?.data?.tobeparsed
            val finalJson = encrypted?.let { decodeToBeParsed(it) } ?: responseText
            tryParseJson<AnichiEP>(finalJson)?.let { it.data?.episode?.sourceUrls ?: it.episode?.sourceUrls }
        } ?: return@safeAmap

        // Process each source URL — 3 cases: http, //, or encoded relative path.
        episodeLinks.safeAmap { source ->
            val sourceUrl = source.sourceUrl
            when {
                sourceUrl.startsWith("http") -> {
                    val sourcename = sourceUrl.getHost()
                    loadCustomExtractor("Allanime [${langType.uppercase()}] [$sourcename]",
                        sourceUrl, "", subtitleCallback, callback)
                }
                URI(sourceUrl).isAbsolute || sourceUrl.startsWith("//") -> {
                    val fixedLink = if (sourceUrl.startsWith("//")) "https:$sourceUrl" else sourceUrl
                    val host = fixedLink.getHost()
                    loadCustomExtractor("Allanime [$host] [${langType.uppercase()}]",
                        fixedLink, "", subtitleCallback, callback)
                }
                else -> {
                    val decodedlink = if (sourceUrl.startsWith("--")) decrypthex(sourceUrl) else sourceUrl
                    val fixedLink = decodedlink.fixUrlPath()
                    val links = try {
                        app.get(fixedLink, headers = headers, timeout = 15_000)
                            .parsedSafe<AnichiVideoApiResponse>()?.links ?: emptyList()
                    } catch (e: Exception) { return@safeAmap }
                    links.forEach { server ->
                        val host = server.link.getHost()
                        when {
                            server.hls == null -> {
                                callback.invoke(
                                    newExtractorLink(
                                        "Allanime [${langType.uppercase()}] ${host.replaceFirstChar { it.uppercase() }}",
                                        "Allanime [${langType.uppercase()}] ${host.replaceFirstChar { it.uppercase() }}",
                                        server.link, INFER_TYPE
                                    ) { this.quality = Qualities.P1080.value }
                                )
                            }
                            server.hls == true -> {
                                val endpoint = "https://allanime.day/player?uri=" +
                                    (if (URI(server.link).host.isNotEmpty()) server.link
                                     else "https://allanime.day" + URI(server.link).path)
                                val referer = server.headers?.referer ?: endpoint
                                getM3u8Qualities(server.link, referer, "Allanime [${langType.uppercase()}] $host")
                                    .forEach(callback)
                            }
                            else -> {
                                server.subtitles?.forEach { sub ->
                                    val lang = sub.lang ?: sub.label ?: ""
                                    val src = sub.src ?: return@forEach
                                    subtitleCallback(newSubtitleFile(getLanguage(lang) ?: lang, httpsify(src)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  SOURCE 3: GogoAnime via MALSync — resolves exact anime URL by MAL ID
//  Classic search.html is dead on all GogoAnime domains (verified 2026-07-12).
//  MALSync provides direct GogoAnime URLs for many anime.
// ═══════════════════════════════════════════════════════════════
suspend fun invokeGogoByMal(
    malId: Int?, episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (malId == null) return
    val headers = mapOf("User-Agent" to USER_AGENT)

    // Use MALSync to find GogoAnime URL.
    val malsyncUrl = "$MALSYNC_API/mal/anime/$malId"
    val malsyncJson = try { app.get(malsyncUrl, headers = headers, timeout = 10_000).text }
                       catch (e: Exception) { return }
    val sites = try { JSONObject(malsyncJson).optJSONObject("Sites") } catch (e: Exception) { return } ?: return

    // MALSync may list GogoAnime under "Gogoanime" or "9anime" etc.
    // Try common keys.
    val gogoUrl = listOf("Gogoanime", "GogoAnime", "gogoanime").firstNotNullOfOrNull { key ->
        sites.optJSONObject(key)?.keys()?.asSequence()?.firstOrNull()?.let {
            sites.optJSONObject(key)?.optJSONObject(it)?.optString("url")?.takeIf { url -> url.isNotBlank() }
        }
    } ?: return

    // The MALSync URL is the category page (e.g. /category/naruto).
    // Build the episode URL from the slug.
    val ep = episode ?: 1
    val slug = gogoUrl.substringAfterLast("/").substringBefore("/")
    // Try multiple GogoAnime domains — they change frequently.
    val domains = listOf("https://gogoanime3.co", "https://gogoanime.fi", "https://gogoanimehd.to")
    domains.safeAmap { domain ->
        val epUrl = "$domain/$slug-episode-$ep"
        safeLoadExtractor(epUrl, domain, subtitleCallback, callback)
    }
}

// ═══════════════════════════════════════════════════════════════
//  TMDB BRIDGE: title → TMDB lookup → Vidlink + VidFast + Videasy + embeds
//  For anime not directly on TMDB, this source is skipped silently —
//  the anime-specific sources above still work.
// ═══════════════════════════════════════════════════════════════
suspend fun tmdbIdFor(title: String): Pair<String, Int>? {
    val q = quote(title)
    // Try TV first (most anime are TMDB TV entries), then movie.
    try {
        val url = "$TMDB_API/search/tv?api_key=$TMDB_KEY&query=$q&with_original_language=ja"
        val results = JSONObject(app.get(url, headers = mapOf("User-Agent" to USER_AGENT), timeout = 10_000).text)
            .optJSONArray("results")
        if (results != null && results.length() > 0) {
            val id = results.getJSONObject(0).optInt("id", 0)
            if (id > 0) return "tv" to id
        }
    } catch (_: Exception) { }
    try {
        val url = "$TMDB_API/search/movie?api_key=$TMDB_KEY&query=$q"
        val results = JSONObject(app.get(url, headers = mapOf("User-Agent" to USER_AGENT), timeout = 10_000).text)
            .optJSONArray("results")
        if (results != null && results.length() > 0) {
            val id = results.getJSONObject(0).optInt("id", 0)
            if (id > 0) return "movie" to id
        }
    } catch (_: Exception) { }
    return null
}

suspend fun invokeTmdbBridge(
    title: String, episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val (mt, tmdbId) = tmdbIdFor(title) ?: return
    val season: Int?; val ep: Int?
    if (mt == "movie") { season = null; ep = null }
    else { season = 1; ep = episode ?: 1 }

    runLimitedAsync(
        { invokeVideasy(title, tmdbId, null, null, season, ep, subtitleCallback, callback) },
        { invokeVidlink(tmdbId, season, ep, subtitleCallback, callback) },
        { invokeVidFast(tmdbId, season, ep, subtitleCallback, callback) }
    )
}

// ── Videasy — exact match of CineStream's invokeVideasy (no extra filters) ──
suspend fun invokeVideasy(
    title: String, tmdbId: Int, imdbId: String?, year: Int?,
    season: Int?, episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val headers = mapOf(
        "Accept" to "*/*",
        "User-Agent" to USER_AGENT,
        "Origin" to "https://player.videasy.to",
        "Referer" to "https://player.videasy.to/"
    )
    val servers = listOf(
        "myflixerzupcloud", "downloader2", "m4uhd", "hdmovie", "cdn",
        "superflix", "lamovie", "jett", "tejo", "neon2", "ym"
    )
    val encTitle = quote(quote(title))
    val enc = 2

    val seedJson = app.get("$VIDEASY_API/seed?mediaId=$tmdbId", headers = headers).text
    val seed = JSONObject(seedJson).getString("seed")

    servers.safeAmap { server ->
        val url = if (season == null) {
            "$VIDEASY_API/$server/sources-with-title?title=$encTitle&mediaType=movie&year=$year&tmdbId=$tmdbId&imdbId=$imdbId&enc=$enc&seed=$seed"
        } else {
            "$VIDEASY_API/$server/sources-with-title?title=$encTitle&mediaType=tv&year=$year&tmdbId=$tmdbId&episodeId=$episode&seasonId=$season&imdbId=$imdbId&enc=$enc&seed=$seed"
        }

        val enc_data = app.get(url, headers = headers).text

        val jsonBody = mapOf("text" to enc_data, "id" to tmdbId, "seed" to seed)
        val response = app.post("$ENC_DEC_API/dec-videasy", json = jsonBody)

        if (response.isSuccessful) {
            val result = JSONObject(response.text).getJSONObject("result")

            val sourcesArray = result.getJSONArray("sources")
            for (i in 0 until sourcesArray.length()) {
                val obj = sourcesArray.getJSONObject(i)
                val quality = obj.getString("quality")
                val source = obj.getString("url")

                val type = if (source.contains(".m3u8")) ExtractorLinkType.M3U8
                           else if (source.contains(".mp4") || source.contains(".mkv")) ExtractorLinkType.VIDEO
                           else INFER_TYPE

                callback.invoke(
                    newExtractorLink(
                        "Videasy[${server.capitalizeServer()}]",
                        "Videasy[${server.capitalizeServer()}] $quality",
                        source, type
                    ) {
                        this.quality = getIndexQuality(quality)
                        this.headers = headers
                    }
                )
            }

            val subtitlesArray = result.getJSONArray("subtitles")
            for (i in 0 until subtitlesArray.length()) {
                val obj = subtitlesArray.getJSONObject(i)
                val source = obj.getString("url")
                val language = obj.getString("language")

                subtitleCallback.invoke(
                    newSubtitleFile(getLanguage(language) ?: language, source)
                )
            }
        }
    }
}

suspend fun invokeVidlink(
    tmdbId: Int, season: Int?, episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val encResp = try { app.get("$ENC_DEC_API/enc-vidlink?text=$tmdbId", timeout = 10_000).text }
                  catch (e: Exception) { return }
    val encData = try { JSONObject(encResp).getString("result") }
                  catch (e: Exception) { return }
    val headers = mapOf("User-Agent" to USER_AGENT, "Connection" to "keep-alive",
        "Referer" to "$VIDLINK_API/", "Origin" to VIDLINK_API)
    val epUrl = if (season == null) "$VIDLINK_API/api/b/movie/$encData"
                 else "$VIDLINK_API/api/b/tv/$encData/$season/$episode"
    val epJson = try { app.get(epUrl, headers = headers, timeout = 15_000).text }
                 catch (e: Exception) { return }
    val m3u8 = tryParseJson<VidlinkResponse>(epJson)?.stream?.playlist ?: return
    getM3u8Qualities(m3u8, "$VIDLINK_API/", "Vidlink").forEach(callback)
}

suspend fun invokeVidFast(
    tmdbId: Int, season: Int?, episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val url = if (season == null) "$VIDFAST_API/movie/$tmdbId/"
              else "$VIDFAST_API/tv/$tmdbId/$season/$episode/"
    val headers = mutableMapOf("User-Agent" to USER_AGENT, "Referer" to "$VIDFAST_API/",
        "X-Requested-With" to "XMLHttpRequest")
    val response = try { app.get(url, headers = headers, timeout = 15_000).text }
                   catch (e: Exception) { return }
    val encodedText = Regex("""\"en\\":\\"(.*?)\\"""").find(response)?.groupValues?.get(1) ?: return

    val decodedDataJson = try { app.get("$ENC_DEC_API/enc-vidfast?text=$encodedText", timeout = 10_000).text }
                          catch (e: Exception) { return }
    val decodedData = tryParseJson<EncDecResponse>(decodedDataJson)?.result ?: return
    val serversUrl = decodedData.servers ?: return
    val streamBaseUrl = decodedData.stream ?: return
    val token = decodedData.token ?: return
    headers["X-CSRF-Token"] = token

    val serversEncrypted = try { app.post(serversUrl, headers = headers, timeout = 10_000).text }
                           catch (e: Exception) { return }
    val serversListJson = try {
        app.post("$ENC_DEC_API/dec-vidfast", json = mapOf("text" to serversEncrypted), timeout = 10_000).text
    } catch (e: Exception) { return }
    val serversList = tryParseJson<VidfastStreamResponse>(serversListJson)?.result ?: return

    serversList.safeAmap { server ->
        val serverHash = server.data ?: return@safeAmap
        val finalStreamUrl = "$streamBaseUrl/$serverHash"
        val streamDataEncrypted = try { app.post(finalStreamUrl, headers = headers, timeout = 10_000).text }
                                  catch (e: Exception) { return@safeAmap }
        if (streamDataEncrypted.isBlank()) return@safeAmap
        val streamDataJson = try {
            app.post("$ENC_DEC_API/dec-vidfast", json = mapOf("text" to streamDataEncrypted), timeout = 10_000).text
        } catch (e: Exception) { return@safeAmap }
        val streamData = tryParseJson<VidfastServersStreamRoot>(streamDataJson)?.result ?: return@safeAmap

        streamData.tracks?.forEach { track ->
            val file = track.file ?: return@forEach
            val label = track.label ?: return@forEach
            subtitleCallback.invoke(newSubtitleFile(getLanguage(label) ?: label, file))
        }
        val fileUrl = streamData.url ?: return@safeAmap
        val type = if (fileUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val is4k = streamData.is4kAvailable == true || server.description?.contains("4K", true) == true
        val quality = if (is4k) Qualities.P2160.value else Qualities.P1080.value
        callback.invoke(
            newExtractorLink("Vidfast[${server.name}]",
                "Vidfast[${server.name}] ${server.description ?: ""}", fileUrl, type) {
                this.headers = headers; this.quality = quality
            }
        )
    }
}

// Helper: loadCustomExtractor wraps loadExtractor with a custom source name.
// Uses coroutineScope + launch inside processLink because newExtractorLink
// is suspend but loadExtractor expects a non-suspend callback.
suspend fun loadCustomExtractor(
    name: String, url: String, referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
    quality: Int? = null
) = kotlinx.coroutines.coroutineScope {
    val processLink: (ExtractorLink) -> Unit = { link ->
        launch {
            val newLink = newExtractorLink(name, name, link.url, type = link.type) {
                this.quality = quality ?: link.quality
                this.referer = link.referer
                this.headers = link.headers
                this.extractorData = link.extractorData
            }
            callback(newLink)
        }
    }
    loadExtractor(url, referer, subtitleCallback, processLink)
}

// Extract host name from a URL — for display in source labels.
fun String.getHost(): String {
    return try { fixTitle(java.net.URI(this).host.substringBeforeLast(".").substringAfterLast(".")) }
    catch (e: Exception) { this }
}
