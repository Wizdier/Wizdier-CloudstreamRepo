package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document

object NowHDTimeUtils {

    const val mainUrl = "https://nowhdtime.com.bd"
    const val name = "NowHDTime"

    // ── Quality ──────────────────────────────────────────────────────────────
    fun qualityFromString(text: String?): Int {
        if (text.isNullOrBlank()) return Qualities.Unknown.value
        return when {
            text.contains("2160") || text.contains("4k", ignoreCase = true) || text.contains("uhd", ignoreCase = true)
                -> Qualities.P2160.value
            text.contains("1080") || text.contains("fhd", ignoreCase = true)
                -> Qualities.P1080.value
            text.contains("720") || text.contains("hd", ignoreCase = true)
                -> Qualities.P720.value
            text.contains("480") -> Qualities.P480.value
            text.contains("360") -> Qualities.P360.value
            text.contains("240") -> Qualities.P240.value
            text.contains("cam", ignoreCase = true) -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    // ── Title cleanup ─────────────────────────────────────────────────────────
    fun cleanTitle(title: String): String =
        title
            .replace(Regex("\\s*\\(\\d{4}\\)\\s*"), " ")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("(?i)\\s*(web-dl|webrip|bluray|bdrip|hdtv|hdrip|dvdrip|camrip|hdcam|1080p|720p|480p)\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ── Year ──────────────────────────────────────────────────────────────────
    fun extractYear(text: String?): Int? =
        text?.let { Regex("((?:19|20)\\d{2})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    // ── Rating (CloudStream uses score * 1000) ────────────────────────────────
    fun extractRating(text: String?): Int? =
        text?.let { Regex("(\\d+(?:\\.\\d+)?)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
            ?.let { (it * 1000).toInt() }

    // ── Duration ──────────────────────────────────────────────────────────────
    fun parseDuration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val t = text.lowercase()
        var mins = 0
        Regex("(\\d+)\\s*(?:h|hr|hour)").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { mins += it * 60 }
        Regex("(\\d+)\\s*(?:m|min)").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { mins += it }
        if (mins == 0) Regex("(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { mins = it }
        return if (mins > 0) mins else null
    }

    // ── Poster URL fixer ──────────────────────────────────────────────────────
    fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            !url.startsWith("http") -> "https://$url"
            else -> url
        }
    }

    // ── Check if URL is a direct video link ───────────────────────────────────
    fun isDirectVideo(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".mp4") ||
                lower.endsWith(".m3u8") ||
                lower.endsWith(".mkv") ||
                lower.endsWith(".mpd") ||
                lower.endsWith(".webm") ||
                lower.endsWith(".avi")
    }

    // ── Extract all raw embed / iframe / direct-video URLs from HTML ──────────
    fun extractEmbeds(html: String): List<String> {
        val urls = mutableListOf<String>()
        // iframes
        Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html).forEach { urls.add(it.groupValues[1]) }
        // embed
        Regex("<embed[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html).forEach { urls.add(it.groupValues[1]) }
        // JS file/src keys pointing to video
        Regex("""["'](https?://[^"']+\.(?:mp4|m3u8|mkv|mpd)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { urls.add(it.groupValues[1]) }
        return urls.distinct().filter { it.startsWith("http") }
    }

    // ── Extract subtitles from HTML ───────────────────────────────────────────
    fun extractSubtitles(html: String): List<Pair<String, String>> {
        val subs = mutableListOf<Pair<String, String>>()
        // Look for .srt / .vtt / .ass links with optional label
        val subRegex = Regex("""(["'])(https?://[^"']+\.(?:srt|vtt|ass)[^"']*)\1""", RegexOption.IGNORE_CASE)
        subRegex.findAll(html).forEach { match ->
            val url = match.groupValues[2]
            if (url.startsWith("http")) {
                subs.add("Auto" to url)
            }
        }
        // Also try track elements
        val trackRegex = Regex("""<track[^>]+src=["']([^"']+)["'][^>]*(?:label=["']([^"']*)["'])?[^>]*>""", RegexOption.IGNORE_CASE)
        trackRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val label = match.groupValues[2].ifBlank { "Auto" }
            if (url.startsWith("http") || url.startsWith("//")) {
                val fixedUrl = fixUrl(url) ?: return@forEach
                subs.add(label to fixedUrl)
            }
        }
        return subs.distinctBy { it.second }
    }

    // ── Extract tracking IDs embedded in HTML ─────────────────────────────────
    data class TrackingIds(
        val imdbId: String? = null,
        val tmdbId: Int? = null,
        val malId: Int? = null,
        val anilistId: Int? = null,
        val kitsuId: Int? = null,
        val simklId: Int? = null,
    )

    fun extractTrackingIds(document: Document): TrackingIds {
        val html = document.html()
        return TrackingIds(
            imdbId = Regex("(tt\\d{7,8})").find(html)?.groupValues?.get(1),
            tmdbId = Regex("themoviedb\\.org/(?:movie|tv)/(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull(),
            malId = Regex("myanimelist\\.net/anime/(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull(),
            anilistId = Regex("anilist\\.co/anime/(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull(),
            kitsuId = Regex("kitsu\\.io/anime/(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull(),
            simklId = Regex("simkl\\.com/anime/(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    // ── Episode / Season number helpers ───────────────────────────────────────
    fun extractEpNum(text: String): Int? {
        for (pat in listOf(
            Regex("(?i)(?:episode|ep|e)[\\s._-]*(\\d+)"),
            Regex("(?i)(\\d+)\\s*(?:st|nd|rd|th)\\s*ep"),
            Regex("^(\\d+)$")
        )) pat.find(text.trim())?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    fun extractSeasonNum(text: String): Int? {
        for (pat in listOf(
            Regex("(?i)(?:season|s)[\\s._-]*(\\d+)"),
            Regex("(?i)(\\d+)\\s*(?:st|nd|rd|th)\\s*season")
        )) pat.find(text.trim())?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    // ── Tags from document ────────────────────────────────────────────────────
    fun extractTags(document: Document): List<String> {
        val tags = mutableListOf<String>()
        document.select(".genre a, .genres a, .tag a, .tags a, a[rel='tag'], a[rel='category'], .category a").forEach {
            val t = it.text().trim()
            if (t.isNotBlank()) tags.add(t)
        }
        // Also try og:keywords meta
        document.selectFirst("meta[name=keywords]")?.attr("content")
            ?.split(",")?.forEach { t -> if (t.trim().isNotBlank()) tags.add(t.trim()) }
        return tags.distinct().take(10)
    }

    // ── Actor extraction ──────────────────────────────────────────────────────
    fun extractActors(document: Document): List<ActorData> {
        val actors = mutableListOf<ActorData>()
        document.select(".cast-list li, .cast a, .actors a, .starring a, .featured-cast .cast-card").forEach { el ->
            val name = el.selectFirst("h3, h4, .name, p")?.text()?.trim()
                ?: el.text().trim()
            val image = el.selectFirst("img")?.let { img ->
                fixUrl(img.attr("src").ifBlank { img.attr("data-src") })
            }
            val role = el.selectFirst(".character, .role, small")?.text()?.trim()
            if (name.isNotBlank()) actors.add(ActorData(Actor(name, image), roleString = role))
        }
        return actors.distinctBy { it.actor.name }.take(20)
    }
}
