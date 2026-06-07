package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.util.regex.Pattern

object NowHDTimeUtils {

    const val mainUrl = "https://nowhdtime.com.bd"
    const val name = "NowHDTime"

    // ===== Quality Detection =====
    fun getQualityFromString(quality: String?): Int {
        if (quality == null) return Qualities.Unknown.value
        val q = quality.lowercase().trim()
        return when {
            q.contains("2160") || q.contains("4k") || q.contains("uhd") -> Qualities.UHD4K.value
            q.contains("1080") || q.contains("fhd") || q.contains("full hd") || q.contains("fullhd") -> Qualities.P1080.value
            q.contains("720") || q.contains("hd") -> Qualities.P720.value
            q.contains("480") || q.contains("sd") -> Qualities.P480.value
            q.contains("360") -> Qualities.P360.value
            q.contains("240") -> Qualities.P240.value
            q.contains("cam") || q.contains("hdcam") || q.contains("camrip") -> Qualities.CamQuality.value
            q.contains("web-dl") || q.contains("webdl") -> Qualities.P1080.value
            q.contains("webrip") -> Qualities.P720.value
            q.contains("bluray") || q.contains("blu-ray") || q.contains("bdrip") || q.contains("brrip") -> Qualities.P1080.value
            q.contains("dvd") || q.contains("dvdrip") -> Qualities.P480.value
            q.contains("hdtv") -> Qualities.P720.value
            q.contains("hdrip") -> Qualities.P720.value
            else -> Qualities.Unknown.value
        }
    }

    // ===== Type Detection =====
    fun getTypeFromString(type: String?): TvType {
        if (type == null) return TvType.Movie
        val t = type.lowercase().trim()
        return when {
            t.contains("series") || t.contains("tv show") || t.contains("tv-show") || t.contains("tvshow") -> TvType.TvSeries
            t.contains("anime") -> TvType.Anime
            t.contains("cartoon") -> TvType.Cartoon
            t.contains("asian") || t.contains("drama") || t.contains("kdrama") || t.contains("cdrama") -> TvType.AsianDrama
            t.contains("movie") || t.contains("film") -> TvType.Movie
            else -> TvType.Movie
        }
    }

    // ===== Clean Title =====
    fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\[.*?]"), "")
            .replace(Regex("\\(.*?p\\)"), "")
            .replace(Regex("(?i)bangla\\s*(dubbed|subtitle|sub)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)(web-dl|webrip|bluray|bdrip|hdtv|hdrip|dvdrip|camrip|hdcam)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ===== Extract Year =====
    fun extractYear(text: String): Int? {
        val yearPattern = Pattern.compile("((?:19|20)\\d{2})")
        val matcher = yearPattern.matcher(text)
        return if (matcher.find()) {
            matcher.group(1)?.toIntOrNull()
        } else null
    }

    // ===== Extract Rating =====
    fun extractRating(text: String): Int? {
        val ratingPattern = Pattern.compile("(\\d\\.\\d)")
        val matcher = ratingPattern.matcher(text)
        return if (matcher.find()) {
            val rating = matcher.group(1)?.toDoubleOrNull()
            rating?.let { (it * 1000).toInt() } // CloudStream uses rating * 1000
        } else null
    }

    // ===== Parse Search Result Card =====
    fun parseSearchResult(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a[href]") ?: return null
        val href = linkElement.attr("href")
        if (href.isBlank() || !href.startsWith(mainUrl)) return null

        val title = element.selectFirst(".entry-title, .post-title, h2, h3, .title")?.text()
            ?: linkElement.attr("title")
            ?: return null

        val posterUrl = element.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-lazy-src").ifBlank { img.attr("src") } }
        }

        val qualityText = element.selectFirst(".quality, .qlty, .qual, span.quality")?.text()
        val quality = getSearchQuality(qualityText)

        val year = extractYear(title) ?: extractYear(element.text())

        val type = when {
            href.contains("/series/") || href.contains("/tv-show/") || href.contains("/tv-series/") -> TvType.TvSeries
            href.contains("/anime/") -> TvType.Anime
            href.contains("/cartoon/") -> TvType.Cartoon
            href.contains("/drama/") || href.contains("/asian-drama/") || href.contains("/kdrama/") -> TvType.AsianDrama
            else -> TvType.Movie
        }

        return if (type == TvType.TvSeries || type == TvType.Anime || type == TvType.AsianDrama) {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = quality
            }
        }
    }

    // ===== Search Quality =====
    private fun getSearchQuality(quality: String?): SearchQuality? {
        if (quality == null) return null
        val q = quality.lowercase().trim()
        return when {
            q.contains("webrip") || q.contains("web-dl") || q.contains("webdl") -> SearchQuality.WebRip
            q.contains("bluray") || q.contains("blu-ray") || q.contains("bdrip") || q.contains("brrip") -> SearchQuality.BlueRay
            q.contains("hdcam") || q.contains("cam") || q.contains("camrip") || q.contains("ts") || q.contains("telesync") -> SearchQuality.CamQuality
            q.contains("hdrip") || q.contains("hd") -> SearchQuality.HD
            q.contains("dvd") || q.contains("dvdrip") -> SearchQuality.SD
            q.contains("hdtv") -> SearchQuality.HdCam
            else -> null
        }
    }

    // ===== Extract IDs for Tracking =====
    data class TrackingIds(
        val malId: Int? = null,
        val anilistId: Int? = null,
        val imdbId: String? = null,
        val tmdbId: Int? = null
    )

    fun extractTrackingIds(document: Document): TrackingIds {
        val pageText = document.text()
        val pageHtml = document.html()

        // IMDB ID extraction
        val imdbPattern = Pattern.compile("(tt\\d{7,8})")
        val imdbMatcher = imdbPattern.matcher(pageHtml)
        val imdbId = if (imdbMatcher.find()) imdbMatcher.group(1) else null

        // TMDB ID extraction
        val tmdbPattern = Pattern.compile("themoviedb\\.org/(?:movie|tv)/(\\d+)")
        val tmdbMatcher = tmdbPattern.matcher(pageHtml)
        val tmdbId = if (tmdbMatcher.find()) tmdbMatcher.group(1)?.toIntOrNull() else null

        // MAL ID extraction
        val malPattern = Pattern.compile("myanimelist\\.net/anime/(\\d+)")
        val malMatcher = malPattern.matcher(pageHtml)
        val malId = if (malMatcher.find()) malMatcher.group(1)?.toIntOrNull() else null

        // AniList ID extraction
        val anilistPattern = Pattern.compile("anilist\\.co/anime/(\\d+)")
        val anilistMatcher = anilistPattern.matcher(pageHtml)
        val anilistId = if (anilistMatcher.find()) anilistMatcher.group(1)?.toIntOrNull() else null

        return TrackingIds(
            malId = malId,
            anilistId = anilistId,
            imdbId = imdbId,
            tmdbId = tmdbId
        )
    }

    // ===== Duration Parsing =====
    fun parseDuration(durationText: String?): Int? {
        if (durationText == null) return null
        val text = durationText.lowercase().trim()

        var totalMinutes = 0

        val hoursPattern = Pattern.compile("(\\d+)\\s*(?:h|hr|hrs|hour|hours)")
        val hoursMatcher = hoursPattern.matcher(text)
        if (hoursMatcher.find()) {
            totalMinutes += (hoursMatcher.group(1)?.toIntOrNull() ?: 0) * 60
        }

        val minutesPattern = Pattern.compile("(\\d+)\\s*(?:m|min|mins|minute|minutes)")
        val minutesMatcher = minutesPattern.matcher(text)
        if (minutesMatcher.find()) {
            totalMinutes += minutesMatcher.group(1)?.toIntOrNull() ?: 0
        }

        // If only a number is found, assume minutes
        if (totalMinutes == 0) {
            val numPattern = Pattern.compile("(\\d+)")
            val numMatcher = numPattern.matcher(text)
            if (numMatcher.find()) {
                totalMinutes = numMatcher.group(1)?.toIntOrNull() ?: 0
            }
        }

        return if (totalMinutes > 0) totalMinutes else null
    }

    // ===== Extract all iframe and embed URLs from HTML =====
    fun extractEmbedUrls(html: String): List<String> {
        val urls = mutableListOf<String>()

        // iframe src
        val iframePattern = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
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