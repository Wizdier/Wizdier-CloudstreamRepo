package com.wizdier

import com.lagradost.cloudstream3.ActorData

// ─── Data Models ─────────────────────────────────────────────────────────

data class PageData(val posts: List<Post>)

data class KitsuMeta(
    val id: String?,
    val title: String?,
    val poster: String?,
    val cover: String?,
    val synopsis: String?,
    val averageRating: Double?
)

data class ResolvedAnimeMeta(
    val title: String,
    val poster: String?,
    val background: String?,
    val plot: String?,
    val score100: Int?,
    val tags: List<String>?,
    val trailer: String?,
    val anilistEpisodes: List<AniListStreamingEpisode>?,
    val logoUrl: String?,
    val actors: List<ActorData>?,
    val anilistId: Int?,
    val malId: Int?,
    val kitsuId: String?,
    val simklId: Int?,
    val traktId: Int?,
    val imdbId: String?,
    val nextAiringInfo: String? = null
)

data class SeasonIds(
    val anilistId: Int? = null,
    val malId: Int? = null,
    val kitsuId: String? = null,
    val simklId: Int? = null,
    val traktId: Int? = null
)

data class Post(
    val id: Int,
    val type: String,
    val imageSm: String,
    val title: String,
    val name: String? = null,
    val image: String? = null,
    val cover: String? = null,
    val quality: String? = null,
    val year: String? = null,
    val tags: String? = null,
    val categories: List<Category>? = null
)

data class Data(
    val id: Int,
    val type: String,
    val imageSm: String,
    val title: String,
    val image: String,
    val metaData: String?,
    val name: String?,
    val quality: String?,
    val year: String?,
    val watchTime: String?,
    val categories: List<Category>?
)

data class Category(val id: Int, val name: String?)
data class TvSeries(val content: List<Content>)
data class Content(val episodes: List<EpisodeData>, val seasonName: String)
data class EpisodeData(val link: String, val title: String)
data class Movies(val content: String?)

data class AniListResponse(val data: AniListData?)
data class AniListData(val Media: AniListMeta?)

data class AniListMeta(
    val id: Int?,
    val idMal: Int?,
    val coverImage: AniListCoverImage?,
    val bannerImage: String?,
    val averageScore: Int?,
    val genres: List<String>?,
    val description: String?,
    val title: AniListTitle?,
    val trailer: AniListTrailer?,
    val streamingEpisodes: List<AniListStreamingEpisode>?,
    val episodes: Int?,
    val status: String?,
    val nextAiringEpisode: AniListNextAiringEpisode?,
    val characters: AniListCharacterConnection?,
    val relations: AniListRelationConnection?
)

data class AniListNextAiringEpisode(
    val airingAt: Int?,
    val timeUntilAiring: Int?,
    val episode: Int?
)

data class AniListCoverImage(val extraLarge: String?, val large: String?)
data class AniListTitle(val romaji: String?, val english: String?)
data class AniListTrailer(val id: String?, val site: String?, val thumbnail: String?)
data class AniListStreamingEpisode(val title: String?, val thumbnail: String?, val url: String?, val site: String?)
data class AniListCharacterConnection(val edges: List<AniListCharacterEdge>?)
data class AniListRelationConnection(val edges: List<AniListRelationEdge>?)
data class AniListRelationEdge(val relationType: String?, val node: AniListMeta?)
data class AniListCharacterEdge(val node: AniListCharacterNode?, val role: String?)
data class AniListCharacterNode(val name: AniListCharacterName?, val image: AniListCharacterImage?)
data class AniListCharacterName(val full: String?)
data class AniListCharacterImage(val large: String?)

data class AniZipFull(
    val anilistId: Int?,
    val malId: Int?,
    val kitsuId: String?,
    val simklId: Int?,
    val tmdbId: String?,
    val traktId: Int?
)

data class TmdbSearchResponse(val results: List<TmdbResult>?)

data class TmdbResult(
    val id: Int?,
    val posterPath: String?,
    val backdropPath: String?,
    val voteAverage: Double?,
    val overview: String?
)

data class TmdbMeta(
    val poster: String?,
    val backdrop: String?,
    val rating: Double?,
    val overview: String?,
    val logoUrl: String?,
    val imdbId: String?,
    val tmdbId: Int?
)

data class TmdbEpisode(
    val name: String?,
    val overview: String?,
    val stillPath: String?,
    val airDate: String?,
    val rating: Double?
)

data class SeasonAnimeResolution(
    val meta: ResolvedAnimeMeta,
    val ids: SeasonIds,
    val aniListNode: AniListMeta?,
    val aniZip: AniZipFull?
)

// ─── Compiled Regex patterns & constants ──────────────────────────────────
// Extracting Regex instances as object constants avoids recompilation on
// every call — a significant perf win for methods that run hundreds of
// times during a page load.

object CircleFtpPatterns {
    const val LOG_TAG = "CircleFtp"

    // cleanTitle patterns
    val RE_SEASON_EP by lazy { Regex("""(?i)S\d{1,2}E\d{1,2}""") }
    val RE_SEASON_EPISODE_WORD by lazy { Regex("""(?i)season\s*\d+|episode\s*\d+""") }
    val RE_LANGUAGE_QUALITY by lazy { Regex("""(?i)\b(hindi|english|tamil|telugu|malayalam|kannada|bengali|marathi|dual audio|multi audio|dubbed|subbed|uncut|extended director'?s? cut)\b""") }
    val RE_SOURCE_QUALITY by lazy { Regex("""(?i)\b(web[- ]?dl|webrip|bluray|hdrip|brrip|dvdrip|hdtv|hdcam|hdts|camrip|hdtc|hq|hd|uhd)\b""") }
    val RE_RESOLUTION_CODEC by lazy { Regex("""(?i)\b(1080p|720p|480p|2160p|4k|hevc|x264|x265|10bit|hdr|dv)\b""") }
    val RE_BRACKETS by lazy { Regex("""[\[\(].*?[\]\)]""") }
    val RE_PUNCTUATION by lazy { Regex("""[:\-–—]""") }
    val RE_MULTISPACE by lazy { Regex("""\s{2,}""") }

    // stripSeasonSuffixForAnime
    val RE_ANIME_WORD by lazy { Regex("""(?i)\banime\b""") }
    val RE_SEASON_WORD by lazy { Regex("""(?i)\bseason\s*\d+\b""") }

    // normalizeMergeTitle patterns
    val RE_MERGE_LANGUAGE by lazy { Regex("""(?i)\b(dual[- ]?audio|multi[- ]?audio|hindi dubbed|hindi dub|dubbed|subbed|hindi|english|japanese|tamil|telugu|malayalam|kannada|bengali|bangla)\b""") }
    val RE_MERGE_QUALITY by lazy { Regex("""(?i)\b(web[- ]?dl|webrip|bluray|hdrip|brrip|dvdrip|hdtv|hdcam|hdts|camrip|hdtc|hq|hd|uhd|1080p|720p|480p|2160p|4k|hevc|x264|x265|10bit|hdr|dv)\b""") }
    val RE_EMPTY_BRACKETS by lazy { Regex("""[\[\(][\s\-_,]*[\]\)]""") }

    // extractAudioTag
    val RE_HINDI by lazy { Regex("""\bhindi\b""") }
    val RE_ENGLISH by lazy { Regex("""\benglish\b|\beng\b""") }
    val RE_JAPANESE by lazy { Regex("""\bjapanese\b|\bjap\b|\bjpn\b""") }
    val RE_TAMIL by lazy { Regex("""\btamil\b""") }
    val RE_TELUGU by lazy { Regex("""\btelugu\b""") }
    val RE_MALAYALAM by lazy { Regex("""\bmalayalam\b""") }
    val RE_KANNADA by lazy { Regex("""\bkannada\b""") }
    val RE_BENGALI by lazy { Regex("""\bbengali\b|\bbangla\b""") }
    val RE_DUB by lazy { Regex("""\bdub\b""") }
    val RE_SUB by lazy { Regex("""\bsub\b""") }

    // URL parsing
    val RE_SEASON_PARAM by lazy { Regex("""[?&]season=(\d+)""") }
    val RE_CONTENT_ID by lazy { Regex("""/content/(\d+)""") }
    val RE_VARIANTS_PARAM by lazy { Regex("""[?&]variants=([^&]+)""") }

    // extractSeasonNumber
    val RE_SEASON_NUM by lazy { Regex("""(?i)\bseasons?\s*(\d+)\b""") }
    val RE_S_NUM by lazy { Regex("""(?i)\bs(\d{1,2})\b""") }

    // Extended season-number detection (multi-seasonal anime)
    //   "2nd Season", "3rd Cour"  -> captures the leading number
    val RE_ORDINAL_SEASON by lazy { Regex("""(?i)\b(\d+)\s*(?:st|nd|rd|th)\s+(?:season|cour)\b""") }
    //   "Part 2", "Cour 2"        -> captures the trailing number
    val RE_PART_NUM by lazy { Regex("""(?i)\b(?:part|cour)\s*(\d+)\b""") }
    //   "Kaguya-sama 2"           -> trailing standalone 1-2 digit number
    val RE_TRAILING_NUM by lazy { Regex("""(?i)[a-z].*?\s(\d{1,2})\s*$""") }
    //   Trailing roman numeral    -> "Overlord III"
    val RE_ROMAN_SEASON by lazy { Regex("""(?i)(?:^|\s)(VIII|VII|VI|IV|IX|III|II|XI|X|V)\s*$""") }
    //   "Final Season"
    val RE_FINAL_SEASON by lazy { Regex("""(?i)\bfinal\s+season\b""") }

    // Aggressive season-suffix stripping helpers (anime franchise base title)
    val RE_ORDINAL_SEASON_STRIP by lazy { Regex("""(?i)\b\d+\s*(?:st|nd|rd|th)\s+(?:season|cour)\b""") }
    val RE_PART_STRIP by lazy { Regex("""(?i)\b(?:part|cour)\s*\d+\b""") }
    val RE_TRAILING_ROMAN_STRIP by lazy { Regex("""(?i)\s+(?:VIII|VII|VI|IV|IX|III|II|XI|X|V)\s*$""") }
    val RE_TRAILING_NUM_STRIP by lazy { Regex("""(?i)\s+\d{1,2}\s*$""") }

    // extractEpisodeNumber
    val RE_EP_NUM by lazy { Regex("""(?i)\b(?:episode|ep|e)\s*\.?\s*(\d{1,4})\b""") }

    // extractSeasonNumber generic
    val RE_YEAR by lazy { Regex("""\d{4}""") }

    // HTML stripping
    val RE_HTML_TAGS by lazy { Regex("<[^>]*>") }

    // Episode title cleanup
    val RE_EPISODE_WORD by lazy { Regex("(?i)Episode\\s*\\d+") }
}
