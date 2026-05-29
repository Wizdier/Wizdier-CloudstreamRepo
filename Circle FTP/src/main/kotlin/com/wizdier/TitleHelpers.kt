package com.wizdier

import com.lagradost.cloudstream3.SearchQuality

// ─── Keyword / Category lists ──────────────────────────────────────────────

val animeKeywords = listOf(
    "anime", "naruto", "dragon ball", "one piece", "attack on titan",
    "demon slayer", "my hero academia", "boruto", "bleach", "fairy tail",
    "pokemon", "digimon", "sailor moon", "tokyo ghoul", "death note",
    "cowboy bebop", "fullmetal alchemist", "hunter x hunter", "jojo",
    "violet evergarden", "aot", "snk", "jujutsu kaisen", "chainsaw man"
)

val nonAnimeKeywords = listOf(
    "cartoon", "animation", "animated", "pixar", "disney", "dreamworks"
)

val animeCategories = setOf(21)
val possiblyAnimeCategories = setOf(1)

// ─── String / Title Helpers ──────────────────────────────────────────────

fun cleanTitle(title: String): String {
    var t = title
    t = t.replace(CircleFtpPatterns.RE_SEASON_EP, "")
    t = t.replace(CircleFtpPatterns.RE_SEASON_EPISODE_WORD, "")
    t = t.replace(CircleFtpPatterns.RE_LANGUAGE_QUALITY, "")
    t = t.replace(CircleFtpPatterns.RE_SOURCE_QUALITY, "")
    t = t.replace(CircleFtpPatterns.RE_RESOLUTION_CODEC, "")
    t = t.replace(CircleFtpPatterns.RE_BRACKETS, "")
    t = t.replace(CircleFtpPatterns.RE_PUNCTUATION, " ")
    t = t.replace(CircleFtpPatterns.RE_MULTISPACE, " ")
    return t.trim()
}

fun stripSeasonSuffixForAnime(title: String): String =
    cleanTitle(title)
        .replace(CircleFtpPatterns.RE_ANIME_WORD, "")
        .replace(CircleFtpPatterns.RE_SEASON_WORD, "")
        .replace(CircleFtpPatterns.RE_MULTISPACE, " ")
        .trim()

fun titleForSeason(baseTitle: String, seasonName: String?, seasonNumber: Int): String {
    val base = cleanTitle(baseTitle)
        .replace(CircleFtpPatterns.RE_ANIME_WORD, "")
        .replace(CircleFtpPatterns.RE_MULTISPACE, " ")
        .trim()
    val season = seasonName?.trim().orEmpty()
    val genericSeason = season.isBlank() ||
        season.equals("Season $seasonNumber", true) ||
        season.matches(CircleFtpPatterns.RE_SEASON_WORD)

    return when {
        seasonNumber <= 1 -> base
        !genericSeason && !base.contains(season, ignoreCase = true) -> "$base: $season"
        else -> "$base Season $seasonNumber"
    }
}

fun extractSeasonNumberOrNull(value: String?): Int? {
    if (value.isNullOrBlank()) return null
    return CircleFtpPatterns.RE_SEASON_NUM.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: CircleFtpPatterns.RE_S_NUM.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

fun extractSeasonNumberFromTitle(title: String): Int =
    extractSeasonNumberOrNull(title) ?: 1

fun isDubTitle(title: String): Boolean {
    val t = title.lowercase()
    return listOf("dubbed", "dual audio", "multi audio", "eng+jap", "english+japanese", "hindi").any { kw ->
        t.contains(kw)
    }
}

fun selectUntilNonInt(string: String?): Int? =
    string?.let { s -> CircleFtpPatterns.RE_YEAR.find(s)?.value?.toIntOrNull() }

fun ordinal(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

fun getSearchQuality(check: String?): SearchQuality? {
    val c = check?.lowercase() ?: return null
    return when {
        c.contains("webrip") || c.contains("web-dl") -> SearchQuality.WebRip
        c.contains("bluray") -> SearchQuality.BlueRay
        c.contains("hdts") || c.contains("hdcam") || c.contains("hdtc") -> SearchQuality.HdCam
        c.contains("dvd") -> SearchQuality.DVD
        c.contains("camrip") -> SearchQuality.CamRip
        c.contains("cam") -> SearchQuality.Cam
        c.contains("hdrip") || c.contains("hd") || c.contains("hdtv") -> SearchQuality.HD
        c.contains("telesync") -> SearchQuality.Telesync
        c.contains("telecine") -> SearchQuality.Telecine
        else -> null
    }
}

/**
 * Parses a human-readable watch-time string (e.g. "1h 24m", "90 min", "45m") into minutes.
 * Returns null when the string is blank or cannot be parsed.
 */
fun getDurationFromString(watchTime: String?): Int? {
    if (watchTime.isNullOrBlank()) return null
    val t = watchTime.lowercase()
    // "1h 24m" / "1h24m" / "1 h 24 m"
    val hm = Regex("""(\d+)\s*h(?:our[s]?)?\s*(\d+)\s*m(?:in(?:utes?)?)?""").find(t)
    if (hm != null) {
        val hours = hm.groupValues[1].toIntOrNull() ?: 0
        val mins = hm.groupValues[2].toIntOrNull() ?: 0
        return hours * 60 + mins
    }
    // "1h" / "2 hours"
    val hOnly = Regex("""(\d+)\s*h(?:our[s]?)?""").find(t)
    if (hOnly != null) {
        return (hOnly.groupValues[1].toIntOrNull() ?: 0) * 60
    }
    // "90 min" / "45m" / "45 minutes"
    val mOnly = Regex("""(\d+)\s*m(?:in(?:utes?)?)?""").find(t)
    if (mOnly != null) {
        return mOnly.groupValues[1].toIntOrNull()
    }
    return t.filter { c -> c.isDigit() }.toIntOrNull()
}

fun isAnimeContent(categories: List<Category>?, title: String): Boolean {
    val t = title.lowercase()
    if (categories?.any { cat -> cat.id in animeCategories } == true) return true
    if (categories?.any { cat -> cat.id in possiblyAnimeCategories } == true) {
        if (nonAnimeKeywords.any { kw -> t.contains(kw) }) return false
        return true
    }
    return animeKeywords.any { kw -> t.contains(kw) }
}

fun extractAudioTag(title: String?): String? {
    val value = title?.lowercase() ?: return null
    return when {
        value.contains("dual audio") || value.contains("dual-audio") -> "Dual Audio"
        value.contains("multi audio") || value.contains("multi-audio") -> "Multi Audio"
        value.contains("hindi dubbed") || value.contains("hindi dub") -> "Hindi Dubbed"
        CircleFtpPatterns.RE_HINDI.containsMatchIn(value) -> "Hindi"
        CircleFtpPatterns.RE_ENGLISH.containsMatchIn(value) -> "English"
        CircleFtpPatterns.RE_JAPANESE.containsMatchIn(value) -> "Japanese"
        CircleFtpPatterns.RE_TAMIL.containsMatchIn(value) -> "Tamil"
        CircleFtpPatterns.RE_TELUGU.containsMatchIn(value) -> "Telugu"
        CircleFtpPatterns.RE_MALAYALAM.containsMatchIn(value) -> "Malayalam"
        CircleFtpPatterns.RE_KANNADA.containsMatchIn(value) -> "Kannada"
        CircleFtpPatterns.RE_BENGALI.containsMatchIn(value) -> "Bengali"
        value.contains("dubbed") || CircleFtpPatterns.RE_DUB.containsMatchIn(value) -> "Dubbed"
        value.contains("subbed") || CircleFtpPatterns.RE_SUB.containsMatchIn(value) -> "Subbed"
        else -> null
    }
}

fun normalizeMergeTitle(title: String): String {
    var v = title
    v = v.replace(CircleFtpPatterns.RE_MERGE_LANGUAGE, "")
    v = v.replace(CircleFtpPatterns.RE_MERGE_QUALITY, "")
    v = v.replace(CircleFtpPatterns.RE_EMPTY_BRACKETS, "")
    v = v.replace(CircleFtpPatterns.RE_BRACKETS, "")
    v = v.replace(CircleFtpPatterns.RE_PUNCTUATION, " ")
    v = v.replace(CircleFtpPatterns.RE_MULTISPACE, " ")
    return v.trim().lowercase()
}

fun extractEpisodeNumber(title: String?): Int? {
    if (title.isNullOrBlank()) return null
    return CircleFtpPatterns.RE_EP_NUM
        .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
}
