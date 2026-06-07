package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document

object NowHDTimeUtils {

    const val mainUrl = "https://nowhdtime.com.bd"
    const val name    = "NowHDTime"

    // ── Quality ──────────────────────────────────────────────────────────────
    fun qualityFromString(text: String?): Int {
        if (text.isNullOrBlank()) return Qualities.Unknown.value
        return when {
            text.contains("2160") || text.contains("4k", ignoreCase = true) || text.contains("uhd", ignoreCase = true)
                                                          -> Qualities.UHD4K.value
            text.contains("1080") || text.contains("fhd", ignoreCase = true)
                                                          -> Qualities.P1080.value
            text.contains("720")  || text.contains("hd",  ignoreCase = true)
                                                          -> Qualities.P720.value
            text.contains("480")                          -> Qualities.P480.value
            text.contains("360")                          -> Qualities.P360.value
            text.contains("240")                          -> Qualities.P240.value
            text.contains("cam", ignoreCase = true)       -> Qualities.CamQuality.value
            else                                          -> Qualities.Unknown.value
        }
    }

    // ── Title cleanup ─────────────────────────────────────────────────────────
    fun cleanTitle(title: String): String =
        title
            .replace(Regex("\\s*\\(\\d{4}\\)\\s*"), " ")
            .replace(Regex("\\[.*?]"), "")
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
            url.startsWith("//")   -> "https:$url"
            url.startsWith("/")    -> "$mainUrl$url"
            !url.startsWith("http") -> "https://$url"
            else                   -> url
        }
    }

    // ── Extract all raw embed / iframe / direct-video URLs from HTML ──────────
    fun extractEmbeds(html: String): List<String> {
        val urls = mutableListOf<String>()
        // iframes
        Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html).forEach { urls.add(it.groupValues[1]) }
        // <source>
        Regex("<source[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html).forEach { urls.add(it.groupValues[1]) }
        // JS file/src keys pointing to video
        Regex("""["'](https?://[^"']+\.(?:mp4|m3u8|mkv|mpd)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { urls.add(it.groupValues[1]) }
        return urls.distinct().filter { it.startsWith("http") }
    }

    // ── Extract tracking IDs embedded in HTML ─────────────────────────────────
    data class TrackingIds(
        val imdbId: String?  = null,
        val tmdbId: Int?     = null,
        val malId:  Int?     = null,
        val anilistId: Int?  = null,
        val kitsuId: Int?    = null,
        val simklId: Int?    = null,
    )

    fun extractTrackingIds(document: Document): TrackingIds {
        val html = document.html()
        return TrackingIds(
            imdbId    = Regex("(tt\\d{7,8})").find(html)?.groupValues?.get(1),
            tmdbId    = Regex("themoviedb\\.org/(?:movie|tv)/(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull(),
            malId     = Regex("myanimelist\\.net/anime/(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull(),
            anilistId = Regex("anilist\\.co/anime/(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull(),
            kitsuId   = Regex("kitsu\\.io/anime/(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull(),
            simklId   = Regex("simkl\\.com/anime/(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull(),
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
            val name  = el.selectFirst("h3, h4, .name, p")?.text()?.trim()
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
al castSelectors = listOf(
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

    /* ── Episode / Season number extraction ── */
    fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Pattern.compile("(?:episode|ep|e)[\\s.-]*(\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d+)\\s*(?:st|nd|rd|th)\\s*episode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(\\d+)$")
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(text.trim())
            if (matcher.find()) return matcher.group(1)?.toIntOrNull()
        }
        return null
    }

    fun extractSeasonNumber(text: String): Int? {
        val patterns = listOf(
            Pattern.compile("(?:season|s)[\\s.-]*(\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d+)\\s*(?:st|nd|rd|th)\\s*season", Pattern.CASE_INSENSITIVE)
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(text.trim())
            if (matcher.find()) return matcher.group(1)?.toIntOrNull()
        }
        return null
    }
}
tDownloadLinks(document: Document): List<Pair<String, String>> {
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