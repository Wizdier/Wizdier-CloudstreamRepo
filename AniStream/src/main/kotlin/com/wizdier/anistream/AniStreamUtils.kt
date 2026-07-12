package com.wizdier.anistream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder

// ═══════════════════════════════════════════════════════════════
//  SHARED UTILITIES — AniStream
//  Adapted from CineStream (github.com/SaurabhKaperwan/CSX).
// ═══════════════════════════════════════════════════════════════

const val USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

// CF bypass UA — must match WebView UA for cookie validation.
private const val CF_BYPASS_UA =
    "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"

// API base URLs
const val VIDEASY_API = "https://api.wingsdatabase.com"
const val VIDLINK_API = "https://vidlink.pro"
const val VIDFAST_API = "https://vidfast.vc"
const val ENC_DEC_API = "https://enc-dec.app/api"
const val ANIMEPAHE_API = "https://animepahe.pw"
const val ALLANIME_API = "https://api.allanime.day/api"
const val ALLANIME_REFERER = "https://allmanga.to"
const val GOGO_API = "https://gogoanime3.co"
const val MIRURO_API = "https://www.miruro.tv"
const val TMDB_API = "https://api.themoviedb.org/3"
const val TMDB_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"
const val ANILIST_API = "https://graphql.anilist.co"
const val MALSYNC_API = "https://api.malsync.moe"

// Persisted-query hashes for AllAnime's GraphQL API.
const val ALLANIME_SEARCH_HASH = "a24c500a1b765c68ae1d8dd85174931f661c71369c89b92b88b75a725afc471c"
const val ALLANIME_EPISODE_HASH = "d405d0edd690624b66baba3068e0edc3ac90f1597d898a1ec8db4e5c43c00fec"

fun quote(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
fun String.capitalizeServer() = replaceFirstChar { it.uppercase() }

fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val lower = str.lowercase()
    return when {
        lower.contains("8k") -> 4320
        lower.contains("4k") -> 2160
        lower.contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

val languageMap = mapOf(
    "Afrikaans" to listOf("af", "afr"), "Arabic" to listOf("ar", "ara"),
    "Bengali" to listOf("bn", "ben"), "Bulgarian" to listOf("bg", "bul"),
    "Chinese" to listOf("zh", "zho"), "Croatian" to listOf("hr", "hrv"),
    "Czech" to listOf("cs", "ces"), "Danish" to listOf("da", "dan"),
    "Dutch" to listOf("nl", "nld"), "English" to listOf("en", "eng"),
    "Finnish" to listOf("fi", "fin"), "French" to listOf("fr", "fra"),
    "German" to listOf("de", "deu", "ger"), "Greek" to listOf("el", "ell"),
    "Hebrew" to listOf("he", "heb"), "Hindi" to listOf("hi", "hin"),
    "Hungarian" to listOf("hu", "hun"), "Indonesian" to listOf("id", "ind"),
    "Italian" to listOf("it", "ita"), "Japanese" to listOf("ja", "jpn"),
    "Korean" to listOf("ko", "kor"), "Malay" to listOf("ms", "msa"),
    "Norwegian" to listOf("no", "nor"), "Persian" to listOf("fa", "fas"),
    "Polish" to listOf("pl", "pol"), "Portuguese" to listOf("pt", "por"),
    "Romanian" to listOf("ro", "ron"), "Russian" to listOf("ru", "rus"),
    "Serbian" to listOf("sr", "srp"), "Spanish" to listOf("es", "spa"),
    "Swedish" to listOf("sv", "swe"), "Tamil" to listOf("ta", "tam"),
    "Telugu" to listOf("te", "tel"), "Thai" to listOf("th", "tha"),
    "Turkish" to listOf("tr", "tur"), "Ukrainian" to listOf("uk", "ukr"),
    "Urdu" to listOf("ur", "urd"), "Vietnamese" to listOf("vi", "vie")
)

fun getLanguage(language: String?): String? {
    language ?: return null
    var normalized = when {
        language.contains("-") -> language.substringBefore("-")
        language.contains(" ") -> language.substringBefore(" ")
        language.contains("CR_") -> language.substringAfter("CR_")
        else -> language
    }
    if (normalized.isBlank()) normalized = language
    return languageMap.entries.find { it.value.contains(normalized) }?.key ?: normalized
}

// Concurrent map with error isolation.
suspend fun <A, B> Iterable<A>.safeAmap(
    concurrency: Int = 7, f: suspend (A) -> B?
): List<B> = supervisorScope {
    val semaphore = Semaphore(concurrency)
    map { item ->
        async<B?>(Dispatchers.IO) {
            semaphore.withPermit {
                try { f(item) }
                catch (e: CancellationException) { if (!isActive) throw e; null }
                catch (e: Throwable) { Log.e("safeAmap", "Item failed: $item — ${e.message}"); null }
            }
        }
    }.awaitAll().filterNotNull()
}

suspend fun runLimitedAsync(
    vararg tasks: suspend () -> Unit, concurrency: Int = 7
) = supervisorScope {
    val semaphore = Semaphore(concurrency)
    tasks.map { task ->
        async<Unit>(Dispatchers.IO) {
            semaphore.withPermit {
                try { task() }
                catch (e: CancellationException) { if (!isActive) throw e }
                catch (e: Throwable) { Log.e("runLimitedAsync", "Task failed: ${e.message}") }
            }
        }
    }.awaitAll()
}

suspend fun safeLoadExtractor(
    url: String, referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    try { loadExtractor(url, referer, subtitleCallback, callback) }
    catch (e: Throwable) { Log.w("AniStream", "extractor failed $url: ${e.message}") }
}

suspend fun getM3u8Qualities(
    m3u8Link: String, referer: String, qualityName: String
): List<ExtractorLink> = M3u8Helper.generateM3u8(qualityName, m3u8Link, referer)

// ── Cloudflare bypass helper ──
// Tries normal GET first; on 403/503 retries with CloudflareKiller under a
// mutex (cfKiller is stateful — concurrent use corrupts its cookie store).
private val cfKiller by lazy { CloudflareKiller() }
private val cfMutex = Mutex()

private fun isCloudflarePage(response: NiceResponse): Boolean = response.code in listOf(403, 503)

suspend fun cfGet(url: String, headers: Map<String, String> = emptyMap()): NiceResponse {
    val h = headers.toMutableMap().apply {
        if (!containsKey("User-Agent")) put("User-Agent", CF_BYPASS_UA)
    }
    val response = app.get(url, headers = h)
    if (!isCloudflarePage(response)) return response
    // CF challenge — retry with CloudflareKiller.
    return cfMutex.withLock {
        val retry = app.get(url, headers = h, interceptor = cfKiller)
        if (!isCloudflarePage(retry)) retry
        else {
            cfKiller.savedCookies.clear()
            app.get(url, headers = h, interceptor = cfKiller)
        }
    }
}

// AllAnime URL fixer — turns relative API paths into absolute .json URLs.
fun String.fixUrlPath(): String {
    return if (this.contains(".json?")) "https://allanime.day" + this
    else "https://allanime.day" + java.net.URI(this).path + ".json?" + java.net.URI(this).query
}
