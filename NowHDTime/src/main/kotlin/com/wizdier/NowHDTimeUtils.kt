package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import java.net.URLDecoder

object NowHDTimeUtils {

    const val mainUrl = "https://nowhdtime.com.bd"
    const val name = "NowHDTime"

    // ── Quality Detection ──
    fun getQualityFromString(quality: String?): Int {
        if (quality == null) return Qualities.Unknown.value
        val q = quality.lowercase().trim()
        return when {
            q.contains("2160") || q.contains("4k") || q.contains("uhd") -> Qualities.UHD4K.value
            q.contains("1080") || q.contains("fhd") || q.contains("full hd") -> Qualities.P1080.value
            q.contains("720") || q.contains("hd") -> Qualities.P720.value
            q.contains("480") || q.contains("sd") -> Qualities.P480.value
            q.contains("360") -> Qualities.P360.value
            q.contains("240") -> Qualities.P240.value
            q.contains("cam") || q.contains("hdcam") -> Qualities.CamQuality.value
            q.contains("web-dl") || q.contains("webdl") -> Qualities.P1080.value
            q.contains("webrip") -> Qualities.P720.value
            q.contains("bluray") || q.contains("blu-ray") || q.contains("bdrip") -> Qualities.P1080.value
            q.contains("dvd") || q.contains("dvdrip") -> Qualities.P480.value
            q.contains("hdtv") -> Qualities.P720.value
            q.contains("hdrip") -> Qualities.P720.value
            else -> Qualities.Unknown.value
        }
    }

    // ── Clean Title ──
    fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\[.*?]"), "")
            .replace(Regex("\\(.*?p\\)"), "")
            .replace(Regex("(?i)bangla\\s*(dubbed|subtitle|sub)"), "")
            .replace(Regex("(?i)(web-dl|webrip|bluray|bdrip|hdtv|hdrip|dvdrip|camrip|hdcam)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ── Extract Year ──
    fun extractYear(text: String): Int? {
        val match = Regex("((?:19|20)\\d{2})").find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    // ── Extract Rating (returns rating * 1000 for CloudStream) ──
    fun extractRating(text: String): Int? {
        val match = Regex("(\\d+\\.\\d)").find(text)
        return match?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toInt() }
    }

    // ── Tracking IDs ──
    data class TrackingIds(
        val malId: Int? = null,
        val anilistId: Int? = null,
        val imdbId: String? = null,
        val tmdbId: Int? = null
    )

    fun extractTrackingIds(document: Document): TrackingIds {
        val html = document.html()

        val imdbId = Regex("(tt\\d{7,8})").find(html)?.groupValues?.get(1)
        val tmdbId = Regex("themoviedb\\.org/(?:movie|tv)/(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull()
        val malId = Regex("myanimelist\\.net/anime/(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull()
        val anilistId = Regex("anilist\\.co/anime/(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull()

        return TrackingIds(malId, anilistId, imdbId, tmdbId)
    }

    // ── Duration Parsing ──
    fun parseDuration(durationText: String?): Int? {
        if (durationText == null) return null
        val text = durationText.lowercase().trim()
        var totalMinutes = 0

        Regex("(\\d+)\\s*(?:h|hr|hrs|hour|hours)").find(text)?.let {
            totalMinutes += (it.groupValues[1].toIntOrNull() ?: 0) * 60
        }
        Regex("(\\d+)\\s*(?:m|min|mins|minute|minutes)").find(text)?.let {
            totalMinutes += it.groupValues[1].toIntOrNull() ?: 0
        }
        if (totalMinutes == 0) {
            Regex("(\\d+)").find(text)?.let {
                totalMinutes = it.groupValues[1].toIntOrNull() ?: 0
            }
        }
        return if (totalMinutes > 0) totalMinutes else null
    }

    // ── Extract all embed/iframe/source URLs from raw HTML ──
    fun extractEmbedUrls(html: String): List<String> {
        val urls = mutableListOf<String>()

        Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html).forEach {
            urls.add(it.groupValues[1])
        }
        Regex("<embed[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html).forEach {
            urls.add(it.groupValues[1])
        }
        Regex("<source[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html).forEach {
            urls.add(it.groupValues[1])
        }
        Regex("[\"'](https?://[^\"']+\\.(?:mp4|mkv|m3u8|mpd)(?:\\?[^\"']*)?)[\"']", RegexOption.IGNORE_CASE).findAll(html).forEach {
            urls.add(it.groupValues[1])
        }

        return urls.distinct().filter { it.startsWith("http") }
    }

    // ── Extract download links ──
    fun extractDownloadLinks(document: Document): List<Pair<String, String>> {
        val links = mutableListOf<Pair<String, String>>()
        val selectors = listOf(
            "a[href*=download]", "a.download-link", "a.btn-download",
            ".download-links a", ".download a", "a[download]",
            ".entry-content a[href*='.mkv']", ".entry-content a[href*='.mp4']",
            "a[href*='drive.google']", "a[href*='mega.nz']", "a[href*='mediafire']",
            "a[href*='dropload']", "a[href*='filelions']", "a[href*='streamwish']",
            "a[href*='vidhide']", "a[href*='dood']", "a[href*='mixdrop']",
            "a[href*='voe']", "a[href*='upstream']", "a[href*='gdtot']",
            "a[href*='hubcloud']", "a[href*='gdflix']", "a[href*='filebox']",
            "a[href*='fastdl']", "a[href*='direct']", "a[href*='worker']",
            "a[href*='pixeldrain']", "a[href*='gofile']"
        )

        for (selector in selectors) {
            document.select(selector).forEach { element ->
                val href = element.attr("href")
                if (href.isNotBlank() && href.startsWith("http") && !href.contains("nowhdtime.com.bd")) {
                    val text = element.text().ifBlank { element.attr("title") }
                    links.add(Pair(href, text))
                }
            }
        }
        return links.distinctBy { it.first }
    }

    // ── URL Helpers ──
    fun decodeUrl(url: String): String {
        return try {
            URLDecoder.decode(url, "UTF-8")
        } catch (_: Exception) {
            url
        }
    }

    fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            !url.startsWith("http") -> "https://$url"
            else -> url
        }
    }

    // ── Tags ──
    fun extractTags(document: Document): List<String> {
        val tags = mutableListOf<String>()
        document.select(".genre a, .genres a, .tag a, .tags a, a[rel='tag']").forEach {
            tags.add(it.text().trim())
        }
        document.select(".category a, .categories a, a[rel='category']").forEach {
            tags.add(it.text().trim())
        }
        return tags.distinct().filter { it.isNotBlank() }
    }

    // ── Actors ──
    fun extractActors(document: Document): List<ActorData> {
        val actors = mutableListOf<ActorData>()
        val selectors = listOf(
            ".cast-list li", ".cast a", ".actors a", ".starring a",
            "span:contains(Cast) ~ a", "span:contains(Stars) ~ a"
        )
        for (selector in selectors) {
            document.select(selector).forEach { element ->
                val actorName = element.text().trim()
                val actorImage = element.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }
                }
                if (actorName.isNotBlank()) {
                    actors.add(ActorData(Actor(actorName, fixPosterUrl(actorImage))))
                }
            }
        }
        return actors.distinctBy { it.actor.name }
    }

    // ── Episode / Season number extraction ──
    fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("(?i)(?:episode|ep|e)[\\s.-]*(\\d+)"),
            Regex("(?i)(\\d+)\\s*(?:st|nd|rd|th)\\s*episode"),
            Regex("^(\\d+)$")
        )
        for (pattern in patterns) {
            pattern.find(text.trim())?.let {
                return it.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    fun extractSeasonNumber(text: String): Int? {
        val patterns = listOf(
            Regex("(?i)(?:season|s)[\\s.-]*(\\d+)"),
            Regex("(?i)(\\d+)\\s*(?:st|nd|rd|th)\\s*season")
        )
        for (pattern in patterns) {
            pattern.find(text.trim())?.let {
                return it.groupValues[1].toIntOrNull()
            }
        }
        return null
    }
}+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val iframeMatcher = iframePattern.matcher(html)
        while (iframeMatcher.find()) {
            iframeMatcher.group(1)?.let { urls.add(it) }
        }

        // embed src
        val embedPattern = Pattern.compile("<embed[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val embedMatcher = embedPattern.matcher(html)
        while (embedMatcher.find()) {
            embedMatcher.group(1)?.let { urls.add(it) }
        }

        // source src (video tags)
        val sourcePattern = Pattern.compile("<source[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val sourceMatcher = sourcePattern.matcher(html)
        while (sourceMatcher.find()) {
            sourceMatcher.group(1)?.let { urls.add(it) }
        }

        // Direct video file URLs in JavaScript
        val jsVideoPattern = Pattern.compile("[\"'](https?://[^\"']+\\.(?:mp4|mkv|m3u8|mpd)(?:\\?[^\"']*)?)[\"']", Pattern.CASE_INSENSITIVE)
        val jsVideoMatcher = jsVideoPattern.matcher(html)
        while (jsVideoMatcher.find()) {
            jsVideoMatcher.group(1)?.let { urls.add(it) }
        }

        return urls.distinct().filter { it.startsWith("http") }
    }

    // ===== Extract download links =====
    fun extractDownloadLinks(document: Document): List<Pair<String, String>> {
        val links = mutableListOf<Pair<String, String>>() // Pair<URL, Quality/Name>

        // Common download button/link selectors for WordPress movie sites
        val downloadSelectors = listOf(
            "a[href*=download]",
            "a.download-link",
            "a.btn-download",
            ".download-links a",
            ".download a",
            ".dwnl a",
            ".dlbtn a",
            "a[download]",
            ".entry-content a[href*='.mkv']",
            ".entry-content a[href*='.mp4']",
            "a[href*='drive.google']",
            "a[href*='mega.nz']",
            "a[href*='mediafire']",
            "a[href*='dropload']",
            "a[href*='filelions']",
            "a[href*='streamwish']",
            "a[href*='vidhide']",
            "a[href*='dood']",
            "a[href*='mixdrop']",
            "a[href*='voe']",
            "a[href*='upstream']",
            "a[href*='gdtot']",
            "a[href*='hubcloud']",
            "a[href*='gdflix']",
            "a[href*='filebox']",
            "a[href*='fastdl']",
            "a[href*='direct']",
            "a[href*='worker']",
            "a[href*='pixeldrain']",
            "a[href*='gofile']",
            ".post-content a[href]",
            "article a[href]"
        )

        for (selector in downloadSelectors) {
            document.select(selector).forEach { element ->
                val href = element.attr("href")
                if (href.isNotBlank() && href.startsWith("http") && !href.contains("nowhdtime.com.bd")) {
                    val text = element.text().ifBlank { element.attr("title") }
                    links.add(Pair(href, text))
                }
            }
        }

        return links.distinctBy { it.first }
    }

    // ===== Decode encoded/obfuscated URLs =====
    fun decodeUrl(url: String): String {
        return try {
            URLDecoder.decode(url, "UTF-8")
        } catch (e: Exception) {
            url
        }
    }

    // ===== Build proper poster URL =====
    fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            !url.startsWith("http") -> "https://$url"
            else -> url
        }
    }

    // ===== Tag extraction for better categorization =====
    fun extractTags(document: Document): List<String> {
        val tags = mutableListOf<String>()

        // Genre tags
        document.select(".genre a, .genres a, .tag a, .tags a, a[rel='tag']").forEach {
            tags.add(it.text().trim())
        }

        // Category tags
        document.select(".category a, .categories a, a[rel='category']").forEach {
            tags.add(it.text().trim())
        }

        return tags.distinct().filter { it.isNotBlank() }
    }

    // ===== Actor/Cast extraction =====
    fun extractActors(document: Document): List<ActorData> {
        val actors = mutableListOf<ActorData>()

        // Common selectors for cast information
        val castSelectors = listOf(
            ".cast-list li",
            ".cast a",
            ".actors a",
            ".starring a",
            "span:contains(Cast) ~ a",
            "span:contains(Stars) ~ a",
            "span:contains(Actor) ~ a"
        )

        for (selector in castSelectors) {
            document.select(selector).forEach { element ->
                val actorName = element.text().trim()
                val actorImage = element.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }
                }
                if (actorName.isNotBlank()) {
                    actors.add(ActorData(Actor(actorName, fixPosterUrl(actorImage))))
                }
            }
        }

        return actors.distinctBy { it.actor.name }
    }

    // ===== Episode number extraction =====
    fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Pattern.compile("(?:episode|ep|e)[\\s.-]*(\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d+)\\s*(?:st|nd|rd|th)\\s*episode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(\\d+)$")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text.trim())
            if (matcher.find()) {
                return matcher.group(1)?.toIntOrNull()
            }
        }
        return null
    }

    // ===== Season number extraction =====
    fun extractSeasonNumber(text: String): Int? {
        val patterns = listOf(
            Pattern.compile("(?:season|s)[\\s.-]*(\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d+)\\s*(?:st|nd|rd|th)\\s*season", Pattern.CASE_INSENSITIVE)
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text.trim())
            if (matcher.find()) {
                return matcher.group(1)?.toIntOrNull()
            }
        }
        return null
    }
}