package com.wizdier.streamflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════
//  STREAMFLIX EXTRACTORS
//  Every source uses CineStream's proven request/decrypt patterns.
//  All sources run concurrently; no source is skipped based on type.
// ═══════════════════════════════════════════════════════════════

// ── Response data classes (mirror CineStream's parser) ──
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

/**
 * WingsDatabase / Videasy — 11 servers, all queried in parallel.
 * No server is ever skipped based on media type (movie vs tv).
 * Uses the same encryption/decryption flow as the videasy.to player.
 */
suspend fun invokeVideasy(
    title: String,
    tmdbId: Int,
    imdbId: String?,
    year: Int?,
    season: Int?,
    episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val headers = mapOf(
        "Accept" to "*/*",
        "User-Agent" to USER_AGENT,
        "Origin" to "https://player.videasy.to",
        "Referer" to "https://player.videasy.to/"
    )

    // 11 servers — matches the current videasy.to player config.
    val servers = listOf(
        "myflixerzupcloud", "downloader2", "m4uhd", "hdmovie", "cdn",
        "superflix", "lamovie", "jett", "tejo", "neon2", "ym"
    )

    // Double URL-encode the title — videasy expects this.
    val encTitle = quote(quote(title))

    // Fetch the seed for this TMDB ID. Without a valid seed, decryption fails.
    val seed = try {
        JSONObject(app.get("$VIDEASY_API/seed?mediaId=$tmdbId", headers = headers, timeout = 10_000).text)
            .getString("seed")
    } catch (e: Exception) {
        Log.w("StreamFlix", "Videasy: seed fetch failed for tmdbId=$tmdbId — ${e.message}")
        return
    }

    // Query all 11 servers concurrently. Each is independent; if one fails
    // or returns no sources, the others still produce links.
    servers.safeAmap { server ->
        val url = if (season == null) {
            "$VIDEASY_API/$server/sources-with-title?title=$encTitle&mediaType=movie&year=$year&tmdbId=$tmdbId&imdbId=$imdbId&enc=2&seed=$seed"
        } else {
            "$VIDEASY_API/$server/sources-with-title?title=$encTitle&mediaType=tv&year=$year&tmdbId=$tmdbId&episodeId=$episode&seasonId=$season&imdbId=$imdbId&enc=2&seed=$seed"
        }

        val encData = try { app.get(url, headers = headers, timeout = 15_000).text }
        catch (e: Exception) { return@safeAmap }
        if (encData.isBlank() || encData.startsWith("{")) return@safeAmap

        // POST the encrypted payload to enc-dec.app for decryption.
        val resp = try {
            app.post("$ENC_DEC_API/dec-videasy",
                json = mapOf("text" to encData, "id" to tmdbId, "seed" to seed),
                headers = mapOf("Content-Type" to "application/json"),
                timeout = 20_000
            )
        } catch (e: Exception) { return@safeAmap }
        if (!resp.isSuccessful) return@safeAmap

        val result = try { JSONObject(resp.text).getJSONObject("result") }
        catch (e: Exception) { return@safeAmap }

        // Emit every source URL with proper type inference + quality detection.
        val sources = result.optJSONArray("sources") ?: return@safeAmap
        for (i in 0 until sources.length()) {
            val src = sources.getJSONObject(i)
            val sourceUrl = src.optString("url").takeIf { it.isNotBlank() } ?: continue
            val quality = src.optString("quality")
            val type = when {
                sourceUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                sourceUrl.contains(".mp4") || sourceUrl.contains(".mkv") -> ExtractorLinkType.VIDEO
                else -> INFER_TYPE
            }
            callback.invoke(
                newExtractorLink(
                    "Videasy[${server.capitalizeServer()}]",
                    "Videasy[${server.capitalizeServer()}] $quality",
                    sourceUrl, type
                ) {
                    this.quality = getIndexQuality(quality)
                    this.headers = headers
                }
            )
        }

        // Emit subtitles with proper language mapping.
        val subs = result.optJSONArray("subtitles") ?: return@safeAmap
        for (i in 0 until subs.length()) {
            val sb = subs.getJSONObject(i)
            val subUrl = sb.optString("url").takeIf { it.isNotBlank() } ?: continue
            val lang = sb.optString("language").ifBlank { sb.optString("lang") }
            subtitleCallback.invoke(
                newSubtitleFile(getLanguage(lang) ?: lang, subUrl)
            )
        }
    }
}

/**
 * Vidlink — uses enc-dec.app to encrypt the TMDB ID, then fetches the
 * M3U8 playlist from vidlink.pro's API. Returns parsed quality variants.
 */
suspend fun invokeVidlink(
    tmdbId: Int, season: Int?, episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val encResp = try { app.get("$ENC_DEC_API/enc-vidlink?text=$tmdbId", timeout = 10_000).text }
    catch (e: Exception) { return }
    val encData = try { JSONObject(encResp).getString("result") }
    catch (e: Exception) { return }

    val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Connection" to "keep-alive",
        "Referer" to "$VIDLINK_API/",
        "Origin" to VIDLINK_API
    )

    val epUrl = if (season == null) "$VIDLINK_API/api/b/movie/$encData"
                 else "$VIDLINK_API/api/b/tv/$encData/$season/$episode"

    val epJson = try { app.get(epUrl, headers = headers, timeout = 15_000).text }
    catch (e: Exception) { return }

    val m3u8 = tryParseJson<VidlinkResponse>(epJson)?.stream?.playlist ?: return
    getM3u8Qualities(m3u8, "$VIDLINK_API/", "Vidlink").forEach(callback)
}

/**
 * VidFast — uses enc-dec.app twice: once to decrypt the initial page
 * payload (yielding server list + stream base + token), then again
 * per-server to decrypt the final stream URL.
 */
suspend fun invokeVidFast(
    tmdbId: Int, season: Int?, episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val url = if (season == null) "$VIDFAST_API/movie/$tmdbId/"
              else "$VIDFAST_API/tv/$tmdbId/$season/$episode/"

    val headers = mutableMapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$VIDFAST_API/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    val response = try { app.get(url, headers = headers, timeout = 15_000).text }
    catch (e: Exception) { return }

    // Extract the encrypted "en" field from the page JSON.
    val encodedText = Regex("""\"en\\":\\"(.*?)\\"""").find(response)?.groupValues?.get(1) ?: return

    val decodedDataJson = try { app.get("$ENC_DEC_API/enc-vidfast?text=$encodedText", timeout = 10_000).text }
    catch (e: Exception) { return }
    val decodedData = tryParseJson<EncDecResponse>(decodedDataJson)?.result ?: return
    val serversUrl = decodedData.servers ?: return
    val streamBaseUrl = decodedData.stream ?: return
    val token = decodedData.token ?: return
    headers["X-CSRF-Token"] = token

    // Fetch the encrypted server list.
    val serversEncrypted = try { app.post(serversUrl, headers = headers, timeout = 10_000).text }
    catch (e: Exception) { return }

    val serversListJson = try {
        app.post("$ENC_DEC_API/dec-vidfast", json = mapOf("text" to serversEncrypted), timeout = 10_000).text
    } catch (e: Exception) { return }

    val serversList = tryParseJson<VidfastStreamResponse>(serversListJson)?.result ?: return

    // Query each server concurrently — each yields its own stream URL.
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

        // Emit subtitle tracks.
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
            newExtractorLink(
                "Vidfast[${server.name}]",
                "Vidfast[${server.name}] ${server.description ?: ""}",
                fileUrl, type
            ) {
                this.headers = headers
                this.quality = quality
            }
        )
    }
}

/**
 * Simple embed hosts — patterns verified live 2026-07-12.
 * Each is loaded via Cloudstream's built-in loadExtractor which handles
 * iframe scraping, m3u8 parsing, and known extractor APIs automatically.
 */
suspend fun invokeEmbeds(
    tmdbId: Int, imdbId: String?, season: Int?, episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val s = season; val e = episode
    val urls = mutableListOf<String>()

    // TMDB-based patterns
    if (s == null) {
        urls.add("$VIDSRC_TO_API/embed/movie/$tmdbId")
        urls.add("$TWOEMBED_API/embed/movie?id=$tmdbId")
        urls.add("$AUTOEMBED_API/movie/tmdb/$tmdbId")
        urls.add("$VIDKING_API/embed/movie/$tmdbId")
        urls.add("$VIDSRC_CC_API/v2/embed/movie/$tmdbId")
    } else {
        urls.add("$VIDSRC_TO_API/embed/tv/$tmdbId/$s/$e")
        urls.add("$TWOEMBED_API/embed/tv?id=$tmdbId&s=$s&e=$e")
        urls.add("$AUTOEMBED_API/tv/tmdb/$tmdbId-$s-$e")
        urls.add("$VIDKING_API/embed/tv/$tmdbId/$s/$e")
        urls.add("$VIDSRC_CC_API/v2/embed/tv/$tmdbId/$s/$e")
    }
    // IMDB-based patterns (extra coverage when IMDB ID is available)
    if (!imdbId.isNullOrBlank()) {
        if (s == null) {
            urls.add("$VIDSRC_TO_API/embed/movie/$imdbId")
            urls.add("$TWOEMBED_API/embed/movie?id=$imdbId")
            urls.add("$AUTOEMBED_API/movie/imdb/$imdbId")
        } else {
            urls.add("$VIDSRC_TO_API/embed/tv/$imdbId/$s/$e")
            urls.add("$TWOEMBED_API/embed/tv?id=$imdbId&s=$s&e=$e")
            urls.add("$AUTOEMBED_API/tv/imdb/$imdbId-$s-$e")
        }
    }

    // Load all embeds concurrently — each is error-isolated.
    val tasks: Array<suspend () -> Unit> = urls.map { url ->
        suspend { safeLoadExtractor(url, "https://www.themoviedb.org/", subtitleCallback, callback) }
    }.toTypedArray()
    runLimitedAsync(*tasks)
}
