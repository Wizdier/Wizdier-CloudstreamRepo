package com.wizdier.streamflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder

// ═══════════════════════════════════════════════════════════════
//  SHARED UTILITIES
//  Adapted from CineStream (github.com/SaurabhKaperwan/CSX) —
//  proven patterns for concurrent, fault-tolerant scraping.
// ═══════════════════════════════════════════════════════════════

// Desktop Chrome UA — matches what CineStream uses for embed hosts.
// Many vid-source CDNs reject mobile UAs or serve degraded responses.
const val USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

// API base URLs — kept in one place so domain changes are trivial.
const val VIDEASY_API = "https://api.wingsdatabase.com"
const val VIDLINK_API = "https://vidlink.pro"
const val VIDFAST_API = "https://vidfast.vc"
const val ENC_DEC_API = "https://enc-dec.app/api"
const val TWOEMBED_API = "https://www.2embed.cc"
const val AUTOEMBED_API = "https://autoembed.co"
const val VIDSRC_TO_API = "https://vidsrc.to"
const val VIDSRC_CC_API = "https://vidsrc.cc"
const val VIDKING_API = "https://www.vidking.net"

// Single-URL-encode with %20 (not +) — required by WingsDatabase.
fun quote(s: String): String =
    URLEncoder.encode(s, "UTF-8").replace("+", "%20")

// Capitalize first char — for nice server display names.
fun String.capitalizeServer() = replaceFirstChar { it.uppercase() }

// Quality string → numeric quality value (e.g. "1080p" → 1080).
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

// ISO 639-1 / language name → Cloudstream language tag.
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

// Concurrent map with error isolation — one failed item never kills the batch.
// Defaults to 7 parallel (matches CineStream's proven concurrency).
suspend fun <A, B> Iterable<A>.safeAmap(
    concurrency: Int = 7,
    f: suspend (A) -> B?
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

// Concurrent task runner with bounded concurrency — each task is independent.
suspend fun runLimitedAsync(
    vararg tasks: suspend () -> Unit,
    concurrency: Int = 7
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

// Wrap loadExtractor with error isolation.
suspend fun safeLoadExtractor(
    url: String, referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    try { loadExtractor(url, referer, subtitleCallback, callback) }
    catch (e: Throwable) { Log.w("StreamFlix", "extractor failed $url: ${e.message}") }
}

// M3u8 helper — generates per-quality links from a master playlist.
suspend fun getM3u8Qualities(
    m3u8Link: String, referer: String, qualityName: String
): List<ExtractorLink> = M3u8Helper.generateM3u8(qualityName, m3u8Link, referer)
