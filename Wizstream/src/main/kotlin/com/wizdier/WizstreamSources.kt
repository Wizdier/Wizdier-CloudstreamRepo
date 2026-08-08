package com.wizdier

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WizstreamSources — bundled multi-source resolver.
 *
 * Each resolver is a 1:1 port of the parser used by the corresponding
 * standalone extension, so behaviour matches exactly.
 */
object WizstreamSources {

    private const val TAG = "WizstreamSources"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    suspend fun resolveAll(
        app: Requests,
        title: String,
        year: Int?,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        labelPrefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        tmdbId: Int? = null,
        imdbId: String? = null,
        altTitle: String? = null,
        // (v45) additional alternate titles from the Wizstream-Anime
        // franchise-prequel walk (root season title etc.). Resolved as
        // extra BDIX passes, each gated on "nothing found yet" so happy
        // titles never pay extra network cost.
        extraAltTitles: List<String> = emptyList(),
        // (v60) entry-local episode number (cours-part's own row: Part 2
        // ep 1 = 1, while the stacked number is 13). Some sites post
        // cours splits as SEPARATE one-bucket posts, which can never
        // answer stacked 13-22 — see the gated pass below.
        entryEpisode: Int? = null,
    ): Boolean = coroutineScope {
        if (title.isBlank() && tmdbId == null && imdbId == null) {
            return@coroutineScope false
        }
        // (v86) Request fingerprint — with the per-resolver "served" line
        // below, one filtered logcat answers "which source answered THIS
        // (season, episode) ask" in one glance (JJK S3 report, 07-31).
        Log.i(TAG, "resolveAll req '$title' s=$season e=$episode tmdb=$tmdbId entryEp=$entryEpisode")
        if (tmdbId == null) {
            // (v95) legacy-TV diagnosis: the four TMDB-gated web APIs
            // (cineby/bingr/moonflix/cinejoy) SKIP SILENTLY without an id,
            // so a null here must be loud — one filtered TV logcat then
            // separates "no id" (id-map unreachable) from "api failed".
            val skipped = TOGGLE_WEB_RESOLVERS
                .filter { WizSourcePrefs.isEnabled(it.toggleId) }
                .joinToString { it.toggleId }
            Log.i(TAG, "resolveAll: tmdbId null — web APIs skip: $skipped")
        }

        // (v61) BDIX vs web bookkeeping kept separate: rescue passes below
        // exist to fix BDIX catalog mismatch (cours splits, franchise
        // titles), and must NOT be suppressed just because a web-API
        // source emitted something — that made the v60 entry-local pass
        // and the v45 franchise passes dead code whenever Bingr/Moonflix
        // answered, which is precisely what kept cours-part pages dry.
        val bdixSources = TOGGLE_BDIX_RESOLVERS
        // (v68) per-source toggles (extension "Open Settings" menu) —
        // disabled sources are skipped here and in every rescue pass.
        val sources = (bdixSources + TOGGLE_WEB_RESOLVERS)
            .filter { WizSourcePrefs.isEnabled(it.toggleId) }

        // ── (v78) Wyzie Subs — runs ALONGSIDE every source ──────────────
        // Subtitles are keyed by IMDB/TMDB id, not by which server the video
        // came from, so this fires once per resolve in PARALLEL with the
        // resolvers (it never delays a link: its result goes to the
        // subtitle callback, and the whole thing no-ops without a user key).
        val wyzieJob = if (WizWyzieSubs.enabled()) async(Dispatchers.IO) {
            runCatching {
                WizWyzieSubs.emit(app, imdbId, tmdbId, season, episode, subtitleCallback)
            }.onFailure { t -> Log.w(TAG, "Wyzie failed: ${t.message}") }
        } else null

        val gate = Semaphore(4)
        val jobs = sources.map { src ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    src to runCatching {
                        src.resolve(
                            app = app,
                            title = title,
                            year = year,
                            isMovie = isMovie,
                            season = season,
                            episode = episode,
                            labelPrefix = labelPrefix,
                            subtitleCallback = subtitleCallback,
                            callback = callback,
                            tmdbId = tmdbId,
                            imdbId = imdbId,
                        )
                    }.onFailure { t ->
                        // (v42) ⓘ DIAG probes retired — CineplexBD is
                        // confirmed healthy on the user's device, so a
                        // resolver crash now leaves a log line only, never
                        // a chip in the user's source list.
                        Log.w(TAG, "resolver crashed: ${t.javaClass.simpleName}: ${t.message}")
                    }.getOrDefault(false).also { served ->
                        if (served) Log.i(TAG, "${src.toggleId}: served s=$season e=$episode")
                    }
                }
            }
        }
        val primary = jobs.awaitAll()
        var found = primary.any { it.second }
        var bdixFound = primary.any { (s, ok) -> ok && s in bdixSources }

        // ── (v31) Alternate-title pass for the BDIX resolvers ─────────────
        // AniList feeds romaji/English titles that BDIX catalogues may index
        // under the OTHER name ("Sousou no Frieren" vs "Frieren: Beyond
        // Journey's End"). Only the four BDIX resolvers search by raw title,
        // so only they are re-run with the alias; the API-backed sources
        // (Cineby/Bingr/Moonflix) key on TMDB IDs and would just duplicate.
        if (!altTitle.isNullOrBlank() && !altTitle.equals(title, ignoreCase = true)) {
            val bdix = TOGGLE_BDIX_RESOLVERS
                .filter { WizSourcePrefs.isEnabled(it.toggleId) }   // (v68) user toggles
            val altJobs = bdix.map { src ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        runCatching {
                            src.resolve(
                                app = app,
                                title = altTitle,
                                year = year,
                                isMovie = isMovie,
                                season = season,
                                episode = episode,
                                labelPrefix = labelPrefix,
                                subtitleCallback = subtitleCallback,
                                callback = callback,
                                tmdbId = tmdbId,
                                imdbId = imdbId,
                            )
                        }.getOrDefault(false)
                    }
                }
            }
            if (altJobs.awaitAll().any { it }) { found = true; bdixFound = true }
        }

        // ── (v45) Franchise-root passes for multi-season anime ──────────
        // Sequel AniList entries ("Attack on Titan: The Final Season") are
        // filed on BDIX sites under the franchise ROOT title ("Attack on
        // Titan"). Wizstream-Anime supplies those root titles here; each
        // extra pass re-runs ONLY while nothing has matched yet, so normal
        // titles never pay for them.
        extraAltTitles
            .asSequence()
            .filter { it.isNotBlank() }
            .filterNot { it.equals(title, ignoreCase = true) }
            .filterNot { it.equals(altTitle ?: "", ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(3)
            .toList()
            .forEach { alt ->
                // (v61) gated on BDIX success only — a web-API answer must
                // not cancel the franchise rescue (root posting is the
                // whole point of these passes).
                if (bdixFound) return@forEach
                val bdix = TOGGLE_BDIX_RESOLVERS
                    .filter { WizSourcePrefs.isEnabled(it.toggleId) }   // (v68) user toggles
                val altJobs = bdix.map { src ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            runCatching {
                                src.resolve(
                                    app = app,
                                    title = alt,
                                    year = year,
                                    isMovie = isMovie,
                                    season = season,
                                    episode = episode,
                                    labelPrefix = labelPrefix,
                                    subtitleCallback = subtitleCallback,
                                    callback = callback,
                                    tmdbId = tmdbId,
                                    imdbId = imdbId,
                                )
                            }.onFailure { t ->
                                Log.w(TAG, "franchise-alt resolver crashed: ${t.message}")
                            }.getOrDefault(false)
                        }
                    }
                }
                if (altJobs.awaitAll().any { it }) { found = true; bdixFound = true }
            }

        // ── (v60) Entry-local episode pass for cours-split posts ──────
        // The stacked (site/TMDB) episode number assumes the site merged
        // a cours split into one season bucket ("Season 3" = 22 rows).
        // When the site instead posts the parts separately ("Attack on
        // Titan Season 3 Part 2", one bucket of 10 rows), stacked ep 13-22
        // can never land; the entry-local number (1-10) is what that post
        // knows. Runs ONLY while nothing matched yet — merged layouts pay
        // nothing. Same BDIX set: web sources key on ids, not numbers.
        // (v61) re-gated on bdixFound: in v60 this checked the GLOBAL
        // found flag, which web sources (Bingr/Moonflix) had already set —
        // the pass never actually fired, exactly why S3 Part 2 stayed dry.
        if (!bdixFound && entryEpisode != null && !isMovie && season != null &&
            episode != null && entryEpisode > 0 && entryEpisode != episode
        ) {
            val bdix = TOGGLE_BDIX_RESOLVERS
                .filter { WizSourcePrefs.isEnabled(it.toggleId) }   // (v68) user toggles
            val altJobs = bdix.map { src ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        runCatching {
                            src.resolve(
                                app = app,
                                title = title,
                                year = year,
                                isMovie = isMovie,
                                season = season,
                                episode = entryEpisode,
                                labelPrefix = labelPrefix,
                                subtitleCallback = subtitleCallback,
                                callback = callback,
                                tmdbId = tmdbId,
                                imdbId = imdbId,
                            )
                        }.onFailure { t ->
                            Log.w(TAG, "entry-local resolver crashed: ${t.message}")
                        }.getOrDefault(false)
                    }
                }
            }
            if (altJobs.awaitAll().any { it }) { found = true; bdixFound = true }
        }
        // (v78) Let the subtitle fetch finish before the resolve returns, so
        // its files are attached to THIS playback session. It is bounded by
        // its own 8s timeout and can only ever add subtitles, never links.
        runCatching { wyzieJob?.await() }
        found
    }

    // ───────────────────────────────────────────────────────────────────────
    //  Shared helpers
    // ───────────────────────────────────────────────────────────────────────

    // (v90) ────────────────────────────────────────────────────────────────
    //  Rx — the regex holding pen. Every Pattern below used to be compiled
    //  at its call site: the title-identity ladder (toTitleMeta etc.) runs
    //  for EVERY BDIX candidate post, bareSeriesTitle runs per tier-3 rescue
    //  candidate, and the row-numbering ladder runs per episode link — so a
    //  single resolve used to allocate and compile 40+ Patterns. They are
    //  now compiled exactly once. Every pattern is byte-identical to the
    //  inline one it replaces; nothing about matching behaviour changes.
    // ─────────────────────────────────────────────────────────────────────
    internal object Rx {
        // shared helpers / title identity
        val WS_SPLIT_RE = Regex("\\s+")
        val TAIL_BRACKET_RE = Regex("\\s*[–-]\\s*\\[[^]]+]\\s*$")
        val BRACKET_ALL_RE = Regex("\\[[^]]+]")
        val MEDIA_JUNK_RE = Regex("(?i)\\b(hindi|dubbed|dual audio|multi audio|season \\d+|eng sub|bengali|korean|english|japanese|subbed)\\b")
        val NON_ALNUM_RE = Regex("[^a-z0-9]+")
        val YEAR_TOKEN_RE = Regex("\\b(19|20)\\d{2}\\b")
        // toTitleMeta ladder
        val TM_BRACKET_RE = Regex("\\[[^]]*]")
        val TM_PAREN_RE = Regex("\\([^)]*\\)")
        val TM_YEARI_RE = Regex("(?i)\\b(19|20)\\d{2}\\b")
        val TM_SEASON_RE = Regex("(?i)\\bseason\\s*\\d{1,2}\\b")
        val TM_SXE_RE = Regex("(?i)\\bs\\d{1,2}\\s*e\\d{1,3}\\b")
        val TM_SESS_RE = Regex("(?i)\\bs\\d{1,2}\\b")
        val TM_EPS_RE = Regex("(?i)\\be\\d{1,3}\\b")
        val TM_EPWORD_RE = Regex("(?i)\\b(?:ep|episode)\\s*\\d{1,3}\\b")
        val TM_SIZE_RE = Regex("(?i)\\b\\d+(?:[.,]\\d+)?\\s*(?:gb|mb|tb)\\b")
        val TM_RESP_RE = Regex("(?i)\\b\\d{3,4}p\\b")
        val TM_BIT_RE = Regex("(?i)\\b(?:8|10)bit\\b")
        // media-url hygiene
        val THUMB_SIZE_RE = Regex("""-\d{2,4}x\d{2,4}\.[a-zA-Z0-9]{2,4}(?:\?|$)""")
        val SCHEMELESS_HOST_RE = Regex("""^[\w.-]+\.[a-zA-Z]{2,}/\S*$""")
        // CircleFTP resolver: post-title cleaning + search-query hygiene
        val AUDIO_TAG_RE = Regex("(?i)\\b(dual[- ]?audio|multi[- ]?audio|dubbed|hindi[- ]?dubbed|eng[- ]?sub|bengali|hindi|dual|multi)\\b")
        val EXT_STRIP_RE = Regex("\\.[a-zA-Z0-9]{2,4}$")
        val SEARCH_PUNCT_RE = Regex("""[!?,.:;'""" + '"' + """()\-–—~*]+""")
        val PART_NUM_RE = Regex("(?i)\\bpart\\s*\\d{1,2}\\b")
        // bareSeriesTitle ladder
        val BST_BRACKET_RE = Regex("""\[[^\]]*\]""")
        val BST_PAREN_RE = Regex("""\([^)]*\)""")
        val BST_KIND_RE = Regex("""(?i)\b(tv series|tv anime|anime|animation|cartoon|series)\b""")
        val BST_ORDINAL_RE = Regex("""(?i)\b\d{1,2}(?:st|nd|rd|th)\b""")
        val BST_SEASON_RE = Regex("""(?i)\bseasons?\b\.?\s*\d{0,2}""")
        val BST_S_RE = Regex("""(?i)\bs\d{1,2}\b""")
        val BST_PART_RE = Regex("""(?i)\b(final|part|cour)\b\.?\s*\d{0,2}""")
        val BST_FINAL_CHAPTERS_RE = Regex("""(?i)\bthe\s+final\s+chapters?\b""")
        val BST_CHAPTER_RE = Regex("""(?i)\b(final\s+)?chapters?\b""")
        val BST_SPECIAL_RE = Regex("""(?i)\bspecials?\b\.?\s*\d{0,2}""")
        val BST_TRAIL_THE_RE = Regex("""(?i)\s+\bthe\s*$""")
        val BST_AUDIO_RE = Regex("""(?i)\b(dual|multi)[- ]?audio\b|\b\w{2,9}[- ](dub|dubbed|sub|subbed|audio)\b|\bdubbed\b""")
        val BST_QUALITY_RE = Regex("""(?i)\b(480p|576p|720p|1080p|2160p|4k|uhd|hdrip|webrip|web-?dl|bluray|bdrip|brrip|hdtc|x264|x265|hevc|h\.?26[45]|aac|ac3|eac3|10bit|8bit|batch|uncut|extended)\b""")
        val BST_YEAR_RE = Regex("""\b(19|20)\d{2}\b""")
        val BST_TRAIL_PUNCT_RE = Regex("""[\s.,:;_\-!]+$""")
        val VOWEL_RUN_RE = Regex("([aeiou])\\1+")
        // season/episode coordinate extraction
        val SEASON_ORDINAL_RE = Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)\\s+season\\b")
        val SEASON_WORD_RE = Regex("(?i)\\bseason\\s*(\\d+)\\b")
        val SEASON_S_RE = Regex("(?i)\\bS(\\d{1,2})\\b")
        val E_TOKEN_RE = Regex("(?i)E(\\d+)")
        val SXE_TOKEN_RE = Regex("""(?i)S\d+E(\d+)""")
        // main-site row numbering ladder
        val ROW_S_DOT_RE = Regex("""(?i)\.S\s*:?\s*(\d{1,2})(?=\.|\s|$)""")
        val ROW_S_COLON_E_RE = Regex("""(?i)\bS\s*:\s*(\d{1,2})\s*E""")
        val ROW_S_E_RE = Regex("""(?i)\bS(\d{1,2})\s*E\d{1,4}\b""")
        val ROW_S_RANGE_RE = Regex("""(?i)\bS(\d{1,2})\s*-\s*\d{1,3}\b""")
        val ROW_EP_WORD_RE = Regex("""(?i)\bepisode\s*:?\s*(\d{1,4})""")
        val ROW_S_E2_RE = Regex("""(?i)\bS\s*:?\s*\d{1,2}\s*\.?\s*E\s*:?\s*(\d{1,4})""")
        val ROW_S_RANGE2_RE = Regex("""(?i)\bS\d{1,2}\s*-\s*(\d{1,3})\s*(?:\(|$|\.)""")
        val ROW_DASH_EP_RE = Regex("""(?i)\s-\s(\d{1,2})\s\(""")
        // misc single sites
        val CH_RUNG_RE = Regex("""(\d{3,4})""")
    }

    /**
     * Title similarity score (Jaccard token overlap, 0..1). Used as a
     * secondary check after exact-match normalised comparison.
     */
    internal fun titleSimilarity(a: String, b: String): Double {
        val ax = a.normaliseTitle()
        val bx = b.normaliseTitle()
        if (ax == bx) return 1.0
        if (ax.isEmpty() || bx.isEmpty()) return 0.0
        val ta = ax.split(Rx.WS_SPLIT_RE).toSet()
        val tb = bx.split(Rx.WS_SPLIT_RE).toSet()
        val inter = ta.intersect(tb).size.toDouble()
        val union = ta.union(tb).size.toDouble()
        return if (union == 0.0) 0.0 else inter / union
    }

    /**
     * Ported from FTPBDProvider.cleanMediaTitle + normalizedTitle. Strips
     * `[Hindi Dubbed]`, `Season N`, etc. from titles before comparison so
     * anime like "One Piece [Hindi Dubbed]" matches the search query
     * "One Piece".
     */
    internal fun String.cleanMediaTitle(): String =
        replace(Rx.TAIL_BRACKET_RE, "")
            .replace(Rx.BRACKET_ALL_RE, "")
            .replace(Rx.MEDIA_JUNK_RE, "")
            .trim()
            .ifBlank { this }

    internal fun String.normaliseTitle(): String =
        cleanMediaTitle()
            .lowercase()
            .replace(Rx.NON_ALNUM_RE, " ")
            .trim()
            .replace(Rx.WS_SPLIT_RE, " ")

    internal fun encodeUrl(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ═══════════════════════════════════════════════════════════════════════
    //  Title-identity matching  (v18)
    //
    //  The BDIX search APIs return loose matches: searching "Scream" returns
    //  every Scream franchise entry plus anything else with "scream" in the
    //  title. Wizstream must attach links for THE movie/series TMDB says the
    //  user opened — nothing else — so posts are checked for *identity*,
    //  not token overlap:
    //    • identity tokens = title tokens with quality/rip/codec/lang junk,
    //      SxxEyy/Season markers, sizes and years stripped, roman numerals
    //      unified with digits ("VI" == "6")
    //    • a post matches when its identity tokens EQUAL the query's
    //      (stop-words ignored) — or differ only by edition keywords — AND
    //      the year (when both sides have one) is within ±1 of the TMDB year
    //
    //  This kills "the whole franchise in one video item" while still
    //  allowing several posts of the SAME film (multiple encodes/cuts).
    // ═══════════════════════════════════════════════════════════════════════

    internal val IDENTITY_JUNK_REGEX = Regex(
        "(?i)\\b(480p|576p|540p|720p|900p|1080p|1440p|2160p|4320p|[48]k|uhd|fhd|qhd|" +
            "blu[- ]?ray|bluray|bdremux|bdrip|brrip|web[- ]?dl|webdl|webrip|hdrip|" +
            "hd[- ]?rip|hdtv|pdtv|dvdrip|dvdscr|hdcam|hdts|hqcam|telesync|telecine|" +
            "camrip|screener|predvd|x264|x265|h\\.?26[45]|hevc|avc|av1|vp9|xvid|divx|" +
            "(?:8|10|12)[- ]?bit|hdr10\\+?|hdr|dolby[ -]?vision|sdr|" +
            "aac|ac3|e[- ]?ac3|ddp?[- ]?5\\.1|ddp?[- ]?7\\.1|dts|truehd|atmos|mp3|flac|" +
            "[257]\\.[01]|mkv|mp4|avi|mov|wmv|m4v|mpg|mpeg|hmulti|multi[- ]?audio|" +
            "dual[- ]?audio|dubbed|dub|subbed|subs?|esubs?|korsub|hc|" +
            "hindi|bengali|bangla|english|urdu|tamil|telugu|malayalam|kannada|nepali|" +
            "korean|japanese|chinese|french|german|spanish|portuguese|italian|russian|" +
            "arabic|turkish|persian|farsi|thai|" +
            "proper|repack|internal|limited|r5|nf|amzn|atvp|hulu|dsnp|hotstar|" +
            "open[- ]?matte|uncensored|readnfo|itunes|hybrid)\\b"
    )

    internal val IDENTITY_STOPWORDS = setOf("the", "a", "an", "of")

    // Edition/cut words that do NOT make a film a different film.
    internal val EDITION_TOKENS = setOf(
        "extended", "directors", "director", "cut", "final", "unrated", "uncut",
        "remastered", "remaster", "theatrical", "edition", "imax", "restored",
        "definitive", "ultimate", "anniversary", "special", "redux",
        // (v31) audio/language/rip decorations that do NOT make a post a
        // different film — BDIX catalogues tag almost everything with these
        // ("ONE PIECE Hindi Dubbed", "Dune Bengali Dubbed 720p" → still the
        // same media). Without them the identity gate silently rejected most
        // FTPBD/CineplexBD/CircleFTP posts.
        "hindi", "dubbed", "dub", "sub", "subbed", "dual", "multi", "audio",
        "bengali", "bangla", "english", "japanese", "korean", "chinese",
        "org", "proper", "complete", "added", "hevc", "x264", "x265", "avc",
        "aac", "eac3", "ac3", "hdrip", "webrip", "webdl", "web", "bluray",
        "x", "movie", "film",
        "bdrip", "brrip", "hdtv", "camrip", "hd", "hq", "uncensored",
    )

    private val ROMAN_EQUIV = mapOf(
        // (v31) single-letter romans ("i", "v", "x") removed — they collide
        // with real words in titles ("SPY x FAMILY" is not "SPY 10 FAMILY").
        "ii" to "2", "iii" to "3", "iv" to "4",
        "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9",
    )

    internal data class TitleMeta(val tokens: Set<String>, val year: Int?)

    internal fun String.toTitleMeta(): TitleMeta {
        var t = this
        val yr = Rx.YEAR_TOKEN_RE.find(t)?.value?.toIntOrNull()
        t = t
            .replace(Rx.TM_BRACKET_RE, " ")
            .replace(Rx.TM_PAREN_RE, " ")
            .replace(Rx.TM_YEARI_RE, " ")
            .replace(IDENTITY_JUNK_REGEX, " ")
            .replace(Rx.TM_SEASON_RE, " ")
            .replace(Rx.TM_SXE_RE, " ")
            .replace(Rx.TM_SESS_RE, " ")
            .replace(Rx.TM_EPS_RE, " ")
            .replace(Rx.TM_EPWORD_RE, " ")
            .replace(Rx.TM_SIZE_RE, " ")
            .replace(Rx.TM_RESP_RE, " ")
            .replace(Rx.TM_BIT_RE, " ")
        val toks = t.lowercase()
            .replace(Rx.NON_ALNUM_RE, " ")
            .split(Rx.WS_SPLIT_RE)
            .filter { it.isNotBlank() }
            .map { ROMAN_EQUIV[it] ?: it }
            .toSet()
        return TitleMeta(toks, yr)
    }

    /**
     * True when [postTitle] is the SAME movie/series as the TMDB item
     * ([queryTitle]/[queryYear]); false for franchise siblings, remakes with
     * a different year, and unrelated partial matches. A post may add pure
     * edition/cut keywords ("Extended Cut") and still be the same film, but
     * any other extra token ("2", "VI", "Queens", "Collection") marks a
     * DIFFERENT film.
     */
    internal fun isSameMediaTitle(postTitle: String, queryTitle: String, queryYear: Int?): Boolean {
        val q = queryTitle.toTitleMeta()
        val p = postTitle.toTitleMeta()
        if (q.tokens.isEmpty() || p.tokens.isEmpty()) return false
        // Year gate (Scream 1996 vs Scream 2022 — identical titles, different films).
        if (q.year != null && p.year != null && kotlin.math.abs(q.year - p.year) > 1) return false
        val missing = q.tokens - p.tokens - IDENTITY_STOPWORDS
        val extra = p.tokens - q.tokens - IDENTITY_STOPWORDS
        if (missing.isEmpty() && extra.isEmpty()) return true
        if (missing.isEmpty() && extra.all { it in EDITION_TOKENS }) return true
        return false
    }

    /** Token-set Jaccard over toTitleMeta() tokens (stop-words ignored). */
    internal fun metaSimilarity(postTitle: String, queryTitle: String): Double {
        val p = postTitle.toTitleMeta().tokens - IDENTITY_STOPWORDS
        val q = queryTitle.toTitleMeta().tokens - IDENTITY_STOPWORDS
        if (p.isEmpty() || q.isEmpty()) return 0.0
        val inter = p.intersect(q).size.toDouble()
        val union = p.union(q).size.toDouble()
        return if (union == 0.0) 0.0 else inter / union
    }

    /**
     * (v32) Softer sibling of [isSameMediaTitle] used as a SECOND tier when
     * the strict identity gate rejects every search hit. BDIX catalogues
     * decorate post titles so loosely ("Avengers Endgame 2019 BDRip 10bit
     * HEVC DTS-HD MA 7.1-ESub") that legitimate posts occasionally carry a
     * token the strict gate treats as "different film". A post passes the
     * fuzzy gate when its meta-token Jaccard with the query is ≥ 0.6 AND
     * any year both sides expose agrees (±1). Used only after the strict
     * gate produced zero matches, so franchise separation (Dune vs Dune:
     * Part Two → 0.33, years clash) still holds whenever it can.
     */
    internal fun isFuzzySameMedia(postTitle: String, queryTitle: String, queryYear: Int?): Boolean {
        val q = queryTitle.toTitleMeta()
        val p = postTitle.toTitleMeta()
        if (q.tokens.isEmpty() || p.tokens.isEmpty()) return false
        if (q.year != null && p.year != null && kotlin.math.abs(q.year - p.year) > 1) return false
        return metaSimilarity(postTitle, queryTitle) >= 0.6
    }

    internal fun ExtractorLink.relabel(newSource: String, newName: String): ExtractorLink =
        runBlocking {
            newExtractorLink(
                source = newSource,
                name = newName,
                url = this@relabel.url,
                type = this@relabel.type,
            ) {
                this.referer = this@relabel.referer
                this.quality = this@relabel.quality
                this.headers = this@relabel.headers
            }
        }

    internal fun qualityFromName(s: String?): Int {
        if (s == null) return Qualities.Unknown.value
        val n = s.lowercase()
        return when {
            "4k" in n || "2160" in n -> Qualities.P2160.value
            "1440" in n -> Qualities.P1440.value
            "1080" in n -> Qualities.P1080.value
            "720" in n -> Qualities.P720.value
            "480" in n -> Qualities.P480.value
            "360" in n -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Device decoder capability gate  (v27 — the "TV 4003" fix)
    //
    //  HTTP 4003 = ExoPlayer DECODING_FAILED: the device's hardware decoder
    //  refused (or died on) the stream. Phones swallow almost anything via
    //  lenient vendor codecs; TVs and TV boxes (Amlogic/Realtek/Broadcom)
    //  enforce limits strictly and throw instead. Verified triggers in the
    //  wild (2026-07-23):
    //    • Adaptive "Auto" master playlists begin with a 2160p variant; on a
    //      TV whose H.264 decoder caps at ~1080p, starting 3840×2160 → 4003.
    //    • Yoru's widescreen-crop ladders (e.g. 2580×1080 declared as AVC
    //      level 3.2, or 2576/3832px widths) violate the declared AVC level —
    //      strict TV decoders refuse at configure, phones don't care.
    //    • Genuine HEVC/AV1 content (Bingr / Moonflix CH / VidNest) on
    //      hardware without those decoders.
    //
    //  MediaCodecList tells us EXACTLY what the device can decode, so instead
  //  of warning users with a ⚠ tag and hoping they choose wisely, we simply
    //  never offer a link the decoder says it cannot play. Unknown verdicts
    //  keep the link (we only skip on an explicit NO).
    // ═══════════════════════════════════════════════════════════════════════

    internal enum class VCodec { H264, HEVC, AV1, VP9, UNKNOWN }

    /** Identify the video codec from an RFC-6381 CODECS attribute
     *  ("avc1.640028,mp4a.40.2") or a verbal name ("H264", "HEVC"). */
    internal fun videoCodecOf(codecsOrName: String?): VCodec {
        if (codecsOrName.isNullOrBlank()) return VCodec.UNKNOWN
        val c = codecsOrName.lowercase()
        return when {
            c.contains("hvc1") || c.contains("hev1") || c.contains("dvh1") ||
                c.contains("hevc") || c.contains("h.265") || c.contains("h265") ||
                c.contains("x265") || c.contains("265") -> VCodec.HEVC
            c.contains("av01") || c.contains("av1") -> VCodec.AV1
            c.contains("vp09") || c.contains("vp9") -> VCodec.VP9
            c.contains("avc1") || c.contains("avc3") || c.contains("avc") ||
                c.contains("h.264") || c.contains("h264") || c.contains("x264") ||
                c.contains("264") -> VCodec.H264
            else -> VCodec.UNKNOWN
        }
    }

    /** Human tag appended to link names: " · H.264" / " · HEVC" / " · AV1". */
    internal fun codecDisplayTag(codecsOrName: String?): String {
        if (codecsOrName.isNullOrBlank()) return ""
        val v = when (videoCodecOf(codecsOrName)) {
            VCodec.H264 -> " · H.264"
            VCodec.HEVC -> " · HEVC"
            VCodec.AV1 -> " · AV1"
            VCodec.VP9 -> " · VP9"
            VCodec.UNKNOWN -> ""
        }
        val c = codecsOrName.lowercase()
        val a = when {
            c.contains("ec-3") || c.contains("eac3") -> " · EAC3"
            c.contains("ac-3") || c.contains("ac3") -> " · AC3"
            else -> ""
        }
        return v + a
    }

    /** Map an actual pixel size to the quality chip the user expects.
     *  Width matters for widescreen-crop ladders: 3832×1604 IS the "4K"
     *  variant (not "1604p"); 2580×1080 is "1080p". */
    internal fun qualityFromDimensions(width: Int, height: Int): Int {
        val w = maxOf(width, 0)
        val h = maxOf(height, 0)
        return when {
            h >= 1900 || w >= 3400 -> Qualities.P2160.value
            h >= 1300 || w >= 2500 -> Qualities.P1440.value
            h >= 1000 || w >= 1750 -> Qualities.P1080.value
            h >= 650 || w >= 1100 -> Qualities.P720.value
            h >= 430 || w >= 700 -> Qualities.P480.value
            h in 1..649 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    /** One quality variant out of an HLS master playlist. */
    internal data class HlsVariantEntry(
        val url: String,
        val width: Int,
        val height: Int,
        val codecs: String?,
    )

    /** Parse the variant renditions out of master-playlist text. Exact
     *  failover duplicates (same size+codec on a second junk host — Cineby
     *  Neon ships two of every variant) are collapsed to the first one. */
    internal fun parseHlsMasterVariants(text: String, baseUrl: String): List<HlsVariantEntry> {
        if (!text.startsWith("#EXTM3U") || !text.contains("#EXT-X-STREAM-INF")) {
            return emptyList()
        }
        val out = mutableListOf<HlsVariantEntry>()
        val lines = text.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val codecs = Regex("""CODECS="([^"]+)"""").find(l)?.groupValues?.get(1)
                val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(l)
                val w = res?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val h = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val uri = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") }
                if (uri != null) {
                    out += HlsVariantEntry(resolveAbs(baseUrl, uri), w, h, codecs)
                }
            }
            i++
        }
        return out.distinctBy { Triple(it.width, it.height, it.codecs.orEmpty().substringBefore(",")) }
    }

    /**
     * (v28) Demuxed HLS masters — variants whose audio lives in separate
     * `#EXT-X-MEDIA` groups (Cineby Neon's `index-v1.m3u8` video-only
     * playlists + `index-a1.m3u8` audio). Emitted per-variant they would
     * play SILENT, so instead we emit the master itself once: ExoPlayer
     * muxes the audio group and adaptively steps down over-level variants
     * on its own (we still skip the whole master when its video codec is
     * missing on this device). The quality chip shows "Auto" deliberately.
     * Returns true when the master was demuxed (handled), false otherwise.
     */
    internal suspend fun emitDemuxedMaster(
        masterUrl: String,
        playlistText: String,
        source: String,
        name: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        qualityHint: Int = Qualities.Unknown.value,
    ): Boolean {
        if (!playlistText.contains("#EXT-X-MEDIA:") || !playlistText.contains("TYPE=AUDIO")) {
            return false
        }
        val variants = parseHlsMasterVariants(playlistText, masterUrl)
        if (variants.isEmpty()) return false
        val top = variants.maxByOrNull { it.width * it.height } ?: return false
        val skip = DeviceDecoderProbe.skipReason(videoCodecOf(top.codecs), 0, 0)
        if (skip != null) {
            Log.d(TAG, "demuxed master skipped ($source, ${top.codecs}): $skip")
            return true
        }
        callback(
            newExtractorLink(
                source = source,
                name = name + codecDisplayTag(top.codecs),
                url = masterUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = referer
                // (v36) Quality tag for demuxed masters. These CDNs (Bingr
                // Sirius, Moonflix HDGhar, both on the streamraiwind family)
                // serve PER-QUALITY masters — a "1080p" source's master tops
                // out at that rung — so tagging with the top variant's
                // resolution restores the same quality chip every other
                // source shows. (Was Qualities.Unknown — the user asked:
                // "add the quality tags for MoonTV and Bingr Sirius".)
                this.quality = if (top.height > 0) {
                    qualityFromDimensions(top.width, top.height)
                } else {
                    qualityHint
                }
                this.headers = headers
            }
        )
        return true
    }

    internal object DeviceDecoderProbe {        private const val MIME_AVC = "video/avc"
        private const val MIME_HEVC = "video/hevc"
        private const val MIME_AV1 = "video/av01"
        private const val MIME_VP9 = "video/x-vnd.on2.vp9"

        private data class Dec(
            val mime: String,
            val caps: MediaCodecInfo.CodecCapabilities?,
        )

        /** All platform decoder entries; null → couldn't enumerate at all. */
        private val decoders: List<Dec>? by lazy {
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { !it.isEncoder }
                    .flatMap { info ->
                        info.supportedTypes.map { t ->
                            Dec(t.lowercase(), runCatching { info.getCapabilitiesForType(t) }.getOrNull())
                        }
                    }
            }.onFailure { Log.d(TAG, "DeviceDecoderProbe: enumeration failed: ${it.message}") }
                .getOrNull()
        }

        private fun mimeOf(c: VCodec): String = when (c) {
            VCodec.H264 -> MIME_AVC
            VCodec.HEVC -> MIME_HEVC
            VCodec.AV1 -> MIME_AV1
            VCodec.VP9 -> MIME_VP9
            VCodec.UNKNOWN -> ""
        }

        /**
         * null   → keep the link (this device says it can play it, or we
         *          could not determine a verdict)
         * String → SKIP the link; value is the human-readable reason.
         */
        fun skipReason(codec: VCodec, width: Int, height: Int): String? {
            if (codec == VCodec.UNKNOWN) return null
            val list = decoders ?: return null
            val mime = mimeOf(codec)
            val matches = list.filter { it.mime == mime }
            if (matches.isEmpty()) return "no ${codec.name} decoder on this device"
            if (width <= 0 || height <= 0) return null
            var sawCaps = false
            for (m in matches) {
                val vc = m.caps?.videoCapabilities ?: continue
                sawCaps = true
                val ok = runCatching { vc.isSizeSupported(width, height) }.getOrNull()
                if (ok == true) return null // one decoder claims it — playable
            }
            return if (sawCaps) "${width}x$height exceeds this device's ${codec.name} decoder" else null
        }
    }

    internal fun isDirectMedia(url: String): Boolean {
        val u = url.lowercase()
        return u.endsWith(".m3u8") || u.endsWith(".mp4") || u.endsWith(".mkv") ||
            u.endsWith(".webm") || u.endsWith(".mov") || u.endsWith(".m4v") ||
            u.contains(".m3u8?") || u.contains(".mp4?") || u.contains(".mkv?")
    }

    /**
     * (v32) WordPress image thumbnails masquerading as media files. FTPBD's
     * pages embed attachment thumbnails like
     * `…/wp-content/uploads/2026/05/rings-of-power-280x176.avi` — a tiny
     * IMAGE with an .avi name — which the generic media-URL regex happily
     * collected, then handed to loadExtractor (always fails) or, when the
     * real stream wasn't found, made an entire episode look broken.
     * Only URLs under /wp-content/uploads/ whose filename carries the WP
     * resized-image "-{w}x{h}" suffix are dropped; real videos on CDN
     * hosts (server2.*, /wp-content/hls-file/) are untouched.
     */
    internal fun isLikelyThumbnailMediaUrl(url: String): Boolean {
        if (!url.contains("/wp-content/uploads/", ignoreCase = true)) return false
        return Rx.THUMB_SIZE_RE
            .containsMatchIn(url)
    }

    internal fun resolveAbs(baseUrl: String, maybeRelative: String): String {
        val u = maybeRelative.trim().replace("&amp;", "&")
        if (u.isBlank()) return u
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        // (v28) Scheme-less absolute references are common in the junk-CDN
        // playlists Cineby's Neon/Breach servers serve (e.g.
        // "fzgbzcajzbbb.interkh.com/12_30/…/index-v1.m3u8?key=…"). Without
        // this they were concatenated onto the playlist path and every
        // emitted Neon variant 404'd — silently killing the server.
        if (Rx.SCHEMELESS_HOST_RE.matches(u)) return "https://$u"

        // (v39) RFC 3986 resolution — THE movie-2004/series-death fix.
        // The old code appended relative refs onto the FULL page URL,
        // query string included:
        //   base "http://cineplexbd.net/player.php?id=76263"
        //   ref  "/ondemand/<hash>/index.m3u8"
        //   OLD  "http://cineplexbd.net/player.php?id=76263/ondemand/<hash>/index.m3u8"  ← junk
        //   NEW  "http://cineplexbd.net/ondemand/<hash>/index.m3u8"                      ← real
        // The site answers such junk paths with HTTP 200 + HTML (catch-all
        // rewrite), the player can't parse HTML as video → HTTP 2004.
        // Semantics: root-relative refs resolve against the ORIGIN
        // (scheme://host[:port]); document-relative refs resolve against the
        // base path's parent directory with query/fragment stripped (which
        // also fixes HLS variant resolution for token-signed masters).
        val b = baseUrl.trim()
        val originMatch = Regex("""^(https?://[^/?#]+)""").find(b)
        if (originMatch == null) {
            val base = b.trimEnd('/')
            return if (u.startsWith("/")) "$base$u" else "$base/$u"
        }
        val origin = originMatch.groupValues[1]
        if (u.startsWith("/")) return origin + u
        val pathPart = b.removePrefix(origin).substringBefore('#').substringBefore('?')
        val parent = pathPart.substringBeforeLast('/', "")
        return "$origin$parent/$u"
    }

    internal fun extractMediaUrlsFromHtml(html: String, baseUrl: String): LinkedHashSet<String> {
        val out = linkedSetOf<String>()
        val doc: Document = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull()
            ?: return out

        doc.select(
            "video[src], source[src], a[href*='.m3u8'], a[href*='.mp4'], a[href*='.mkv'], " +
                "a[href*='.webm'], a[href*='.m4v']"
        ).forEach { el ->
            val src = el.attr("src").ifBlank { el.attr("href") }
            val abs = resolveAbs(baseUrl, src)
            if (src.isNotBlank() && !isLikelyThumbnailMediaUrl(abs)) out += abs
        }

        // iframe srcs — many vid hosts wrap the actual video in an iframe.
        doc.select("iframe[src]").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                out += resolveAbs(baseUrl, src)
            }
        }

        Regex("""(?i)data-url=["']([^"']+)["']""")
            .findAll(html)
            .map { it.groupValues[1] }
            .forEach { raw ->
                if (raw.isNotBlank() && isDirectMedia(raw)) out += resolveAbs(baseUrl, raw)
            }

        Regex("""(?i)<source[^>]+src=["']([^"']+?\.m3u8[^"']*)["']""")
            .findAll(html).forEach { m -> out += resolveAbs(baseUrl, m.groupValues[1]) }
        Regex("""(?i)hls\.loadSource\(["']([^"']+?\.m3u8[^"']*)["']\)""")
            .findAll(html).forEach { m -> out += resolveAbs(baseUrl, m.groupValues[1]) }
        Regex("""(?i)(?:const|var)\s+videoSrc\s*=\s*["'](.*?)["']""")
            .findAll(html).forEach { m ->
                val u = m.groupValues[1]
                if (u.isNotBlank() && (u.startsWith("http") || isDirectMedia(u)))
                    out += resolveAbs(baseUrl, u)
            }

        Regex(
            """https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv|\.webm|\.avi|\.mov|\.m4v)(?:\?[^\s"'<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { m ->
            val u = m.value.replace("&amp;", "&")
            if (!isLikelyThumbnailMediaUrl(u)) out += u
        }

        return out
    }

    internal suspend fun emitDirect(
        app: Requests,
        url: String,
        sourceLabel: String,
        referer: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val clean = url.trim()
        if (clean.isBlank()) return false
        return try {
            if (clean.contains(".m3u8", ignoreCase = true)) {
                M3u8Helper.generateM3u8(
                    source = sourceLabel,
                    streamUrl = clean,
                    referer = referer,
                    headers = headers,
                ).forEach(callback)
                true
            } else if (isDirectMedia(clean)) {
                val link = newExtractorLink(
                    source = sourceLabel,
                    name = "$sourceLabel - Direct",
                    url = clean,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = referer
                    // (v38) Propagate resolver-supplied headers (notably the
                    // session Cookie captured from the player page) onto the
                    // link itself. Without this the player requests the file
                    // bare → CineplexBD's CDN rejects it → HTTP 2004 while
                    // the resolver's own scrape (which HAS the cookie)
                    // succeeds.
                    this.headers = headers
                    this.quality = qualityFromName(clean)
                }
                callback(link)
                true
            } else {
                var found = false
                runCatching {
                    loadExtractor(clean, referer, subtitleCallback) { link ->
                        callback(link.relabel(sourceLabel, "$sourceLabel — ${link.name}"))
                        found = true
                    }
                }
                found
            }
        } catch (t: Throwable) {
            Log.d(TAG, "emitDirect failed for $clean: ${t.message}")
            false
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  (v68) Per-source toggles + "Open Settings" dialog
    //  The app renders an "Open Settings" button for any plugin whose
    //  plugin-class (com.lagradost.cloudstream3.plugins.Plugin) sets
    //  `openSettings`; both Wizstream plugins wire it to WizSourcePrefs's
    //  dialog. Toggles persist in the app-wide DataStore (AcraApplication
    //  keys, "wiz_src_<id>") and are read LIVE at every resolve — changes
    //  apply instantly, no restart or reload needed. All sources default ON.
    // ════════════════════════════════════════════════════════════════════════

    internal fun wizToggleId(o: Any): String =
        o::class.simpleName.orEmpty().removeSuffix("Resolver").lowercase()

    internal val TOGGLE_BDIX_RESOLVERS: List<SourceResolver> = listOf(
        CineplexBdResolver, FtpBdResolver, CircleFtpResolver, CtgMoviesResolver,
        FmFtpResolver, MediaserverResolver,
    )
    internal val TOGGLE_WEB_RESOLVERS: List<SourceResolver> = listOf(
        // (v94, user: "add cinejoy.to, 2dhive.com, anihq.cc") +CineJoy —
        // the two anime-keyed newcomers are toggled from the anime engine.
        CinebyResolver, BingrResolver, MoonflixResolver, CineJoyResolver,
        // (v96, user: "add shuttletv.su") TMDB-keyed web source via
        // cinesrc.st embed (2-stage PoW incl. WASM — loadExtractor-only).
        ShuttletvResolver,
        // (v98, user: "add ww1.m4uhd.to + cinemaos.live, no 2004/3003") —
        // M4UHD's 9stream lane is fully in-repo (AES-cracked, verified);
        // CinemaOS walks its live lineage ladder, emit-on-verify only.
        M4uHdResolver, CinemaOsResolver,
    )

    private val TOGGLE_LABELS: Map<String, String> = mapOf(
        "cineplexbd" to "Cineplex BD",
        "ftpbd" to "FTPBD",
        "circleftp" to "Circle FTP",
        "ctgmovies" to "CTGMovies",
        "fmftp" to "FM FTP",
        "mediaserver" to "Mediaserver",
        "cineby" to "Cineby",
        "bingr" to "Bingr",
        "moonflix" to "Moonflix",
        // (v70) anime-web resolvers (WizstreamAnimeSources) — now toggled
        // from BOTH extensions' settings menus.
        "anineko" to "AniNeko",
        // (v89, user: "remove all the anime sources apart from anineko,
        // add kaa.lt + animex.one")
        "kaa" to "KickAssAnime",
        "animex" to "AnimeX",
        // (v91, user: "add aniwaves.ru/home as a source")
        "aniwaves" to "Aniwaves",
        // (v92, user: everythingmoe.com anime top-20 sweep)
        "anikoto" to "Anikoto",
        "anizone" to "AniZone",
        "animestream" to "AnimeStream",
        "anibd" to "AniBD",
        "anidb" to "AniDB.app",
        // (v94, user: "add cinejoy.to, 2dhive.com, anihq.cc")
        "cinejoy" to "CineJoy",
        "anihq" to "AniHQ",
        "dhive" to "2Dhive",
        // (v96, user: "add shuttletv.su, anikage.cc")
        "shuttletv" to "ShuttleTV",
        "anikage" to "Anikage",
        // (v98, user: "add https://toon-stream.site/home")
        "toonstream" to "ToonStream",
        // (v98, user: "add ww1.m4uhd.to + cinemaos.live")
        "m4uhd" to "M4UHD",
        "cinemaos" to "CinemaOS",
        // (v78) integrations — not video sources; both need a user API key.
        "wyziesubs" to "Wyzie Subs",
        "mdblist" to "MDBList ratings",
    )

    /** (v87, user: "settings can be more beautiful and effective") one
     *  honest line under each toggle saying what it actually serves, so
     *  choosing sources is informed instead of a hostname guessing game. */
    private val TOGGLE_BLURBS: Map<String, String> = mapOf(
        "cineplexbd" to "BDIX · big movie & series catalogue",
        "ftpbd" to "BDIX · movies, series & anime",
        "circleftp" to "BDIX · LAN-speed movies, series & anime",
        "ctgmovies" to "BDIX · movies & series",
        "fmftp" to "BDIX · movies & series",
        "mediaserver" to "BDIX · movies & series",
        "cineby" to "Web · clean API, keyed by TMDB id",
        "bingr" to "Web · Bingr servers (incl. Sirius) · lists low-first",
        "moonflix" to "Web · HDGhar + CH ladders · lists low-first",
        "anineko" to "Anime site · subs",
        "kaa" to "Anime site · subs · CatStream HLS",
        "animex" to "Anime site · exact AniList match · subs",
        "aniwaves" to "Anime site · sub + dub · BYFMS 720p HLS",
        "anikoto" to "Anime site · sub + hardsub · MegaPlay HLS",
        "anizone" to "Anime site · multi-audio HLS · many subtitle tracks",
        "animestream" to "Anime site · signed HLS · sub + dub",
        "anibd" to "Bangladeshi anime site · subs",
        "anidb" to "Anime site · sub + dub · self-hosted HLS",
        "cinejoy" to "Web · 5 providers · subs · links verified before listing",
        "anihq" to "Anime site · sub + dub · VOE HLS · links verified",
        "dhive" to "Anime site · sub + dub · MegaPlay HLS · links verified",
        // (v96) ShuttleTV via cinesrc.st embed — heavy PoW incl. WASM
        "shuttletv" to "Web · movies + TV via cinesrc.st embed · needs modern WebView",
        "anikage" to "Anime site · AniList-keyed · 5 providers · sub + dub",
        // (v98) ToonStream — verified VidMoly lane + app-extractor fallback
        "toonstream" to "Cartoon/anime site · Hindi & multi dubs · VidMoly lanes verified",
        // (v98) M4UHD — in-repo 9stream 1080p HLS + EN subs, verified
        "m4uhd" to "Web · movies & series · 9stream 1080p HLS + EN subs · links verified",
        // (v98) CinemaOS — TMDB-keyed ladder; quiet while their backend is down
        "cinemaos" to "Web · TMDB-keyed CinemaOS lanes · links verified before listing",
    )

    object WizSourcePrefs {
        private const val PFX = "wiz_src_"

        class Src(
            val id: String,
            val label: String,
            val section: String,   // "BDIX SOURCES", "WEB SOURCES", "ANIME-WEB SOURCES"
            val host: String,      // cosmetic subtitle, e.g. "new.circleftp.net"
            val blurb: String = "", // (v87) one-line "what this toggle gives you"
        )

        /** Build a dialog entry from a resolver object (label from the map). */
        fun src(o: Any, section: String, host: String): Src {
            val id = wizToggleId(o)
            return Src(id, TOGGLE_LABELS[id] ?: id, section, host,
                blurb = TOGGLE_BLURBS[id] ?: "")
        }

        fun isEnabled(id: String): Boolean = runCatching {
            CloudStreamApp.getKey(PFX + id, true) ?: true
        }.getOrDefault(true)

        fun setEnabled(id: String, on: Boolean) {
            runCatching { CloudStreamApp.setKey(PFX + id, on) }
        }

        // ── (v78) INTEGRATION KEYS — Wyzie Subs + MDBList ──────────────
        // Both services now require a PER-USER API key. Wyzie's own docs
        // forbid shipping a key inside a mobile binary ("If the key
        // reaches an end user's machine, treat it as public") and quota
        // burned that way is non-refundable, so NOTHING is hardcoded:
        // the user pastes their own key here and it lives only in this
        // device's DataStore. Blank key = feature silently OFF (no calls,
        // no log spam, zero added latency). Shared keys across BOTH
        // extensions, exactly like the source toggles above.
        private const val KEY_PFX = "wiz_key_"
        const val KEY_WYZIE = "wyzie"
        const val KEY_MDBLIST = "mdblist"

        fun apiKey(id: String): String? = runCatching {
            CloudStreamApp.getKey<String>(KEY_PFX + id)?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        fun setApiKey(id: String, value: String) {
            runCatching {
                CloudStreamApp.setKey(KEY_PFX + id, value.trim())
            }
        }

        /**
         * (v69) Immersive source-settings SHEET — real switches, grouped
         * sections, host subtitles, live apply. Built programmatically (the
         * extension ships no Android resources), wired to the app's native
         * `openSettings` plugin hook. Every toggle writes its DataStore key
         * immediately; the resolve engine reads keys on the NEXT resolution,
         * so no restart/reload is ever needed.
         */
        @Suppress("SetTextI18n")
        fun openDialog(context: android.content.Context, sources: List<Src>) {
            val dm = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * dm).toInt()

            // ── (v98, user: "astronomical dark, not violet, no emojis") ─────
            //  Deep-space palette: near-black navy canvas, moonlit navy cards,
            //  starlight text, and FOUR celestial accents used as section
            //  tints (amber star, ice planet, nebula coral, aurora teal) —
            //  zero violet, zero emoji; icons are drawn monogram chips.
            val accent = 0xFF8ED1FF.toInt()       // ice-planet blue — primary accent
            val accentDim = 0x338ED1FF.toInt()
            val starAmber = 0xFFF2C97D.toInt()    // BDIX (home-base starlight)
            val nebulaCoral = 0xFFF4A988.toInt()  // Anime-web (nebula warmth)
            val auroraTeal = 0xFF5CE6C8.toInt()   // Web sources (aurora)
            val cometPearl = 0xFFD9E2EC.toInt()   // Integrations (comet pearl)
            val textPrimary = 0xFFF0F4FA.toInt()  // starlight
            val textMuted = 0xFF98A3B8.toInt()    // blue-grey moon dust
            val cardBg = 0xFF131724.toInt()       // deep-space navy card
            val fieldBg = 0xFF0D1018.toInt()      // void-black input field
            val chipOn = 0xFF1E5C46.toInt()
            val chipOff = 0xFF592B34.toInt()

            /** Rounded card container — the extension ships no XML/drawables,
             *  so every shape is built with GradientDrawable at runtime. */
            fun card(): android.widget.LinearLayout =
                android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(cardBg)
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(1), 0x22FFFFFF)
                    }
                    setPadding(dp(14), dp(10), dp(14), dp(12))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                }

            fun pill(text: String, bg: Int): android.widget.TextView =
                android.widget.TextView(context).apply {
                    this.text = text
                    setTextColor(textPrimary)
                    textSize = 11f
                    setPadding(dp(9), dp(3), dp(9), dp(3))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(bg); cornerRadius = dp(999).toFloat()
                    }
                }

            /** (v98) Monogram chip — the emoji replacement: a small rounded
             *  square with a 1-2 letter glyph, tinted per section. Drawn
             *  programmatically (the extension ships no drawables), so it
             *  renders identically on phone, tablet and the old TV. */
            fun chip(letters: String, tint: Int, sizeDp: Int = 26): android.view.View =
                android.widget.TextView(context).apply {
                    text = letters
                    setTextColor(tint)
                    textSize = if (letters.length > 1) 10.5f else 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(tint and 0x00FFFFFF or 0x1F000000)   // 12% tint fill
                        setStroke(dp(1), tint and 0x00FFFFFF or 0x59000000) // 35% rim
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        dp(sizeDp), dp(sizeDp),
                    ).apply { rightMargin = dp(9) }
                }

            val root = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(6))
            }

            // ── Header ────────────────────────────────────────────────
            root.addView(android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(chip("W", accent, 34))
                addView(android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(android.widget.TextView(context).apply {
                        text = "Wizstream"
                        setTextColor(textPrimary)
                        textSize = 21f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        letterSpacing = 0.02f
                    })
                    addView(android.widget.TextView(context).apply {
                        text = "SETTINGS"
                        setTextColor(accent)
                        textSize = 10.5f
                        letterSpacing = 0.30f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(0, dp(1), 0, 0)
                    })
                })
            })
            val summary = android.widget.TextView(context).apply {
                setTextColor(textMuted)
                textSize = 12.5f
                setPadding(0, dp(3), 0, dp(2))
            }
            root.addView(summary)
            root.addView(android.widget.TextView(context).apply {
                text = "Changes apply on the very next episode you tap — no restart."
                setTextColor(textMuted)
                textSize = 11.5f
                setPadding(0, 0, 0, dp(2))
            })

            // ── Live filter box ───────────────────────────────────────
            val search = android.widget.EditText(context).apply {
                hint = "Search sources…"
                setHintTextColor(textMuted)
                setTextColor(textPrimary)
                textSize = 13f
                setSingleLine(true)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(fieldBg)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), accentDim)
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            root.addView(search)

            /** (v98) Switch tinted to the palette — stock green/purple clashed
             *  with the deep-space sheet. Track dimmed when off, ice-blue on. */
            fun styleSwitch(sw: android.widget.Switch, on: Boolean) {
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 23) {
                        sw.thumbTintList = android.content.res.ColorStateList.valueOf(
                            if (on) accent else 0xFF6B7488.toInt(),
                        )
                        sw.trackTintList = android.content.res.ColorStateList.valueOf(
                            if (on) accentDim else 0xFF2A2F3D.toInt(),
                        )
                    }
                }
            }

            val switches = mutableListOf<android.widget.Switch>()
            val keyEditors = mutableListOf<Pair<String, android.widget.EditText>>()
            // row view + its source, so the filter can hide/show and the
            // header counters can refresh without rebuilding the dialog.
            val rowIndex = mutableListOf<Triple<Src, android.view.View, android.widget.TextView>>()
            val sectionCards = mutableListOf<Triple<String, android.view.View, android.widget.TextView>>()

            fun refreshSummary() {
                val on = sources.count { isEnabled(it.id) }
                summary.text = "$on of ${sources.size} sources enabled"
                sectionCards.forEach { (sec, _, counter) ->
                    val g = sources.filter { it.section == sec }
                    counter.text = "${g.count { isEnabled(it.id) }}/${g.size}"
                }
            }

            // ── Section cards + switch rows ───────────────────────────
            val bySection = sources.groupBy { it.section }
            bySection.forEach { (section, group) ->
                val c = card()
                val head = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                // (v98) section monogram chips — instant visual scanning,
                // zero emoji; each section owns one celestial tint.
                val (secGlyph, secTint) = when {
                    section.contains("BDIX") -> ("BD" to starAmber)
                    section.contains("ANIME") -> ("AN" to nebulaCoral)
                    section.contains("WEB") -> ("WB" to auroraTeal)
                    else -> ("▸" to accent)
                }
                head.addView(chip(secGlyph, secTint))
                head.addView(android.widget.TextView(context).apply {
                    text = section
                    setTextColor(secTint)
                    textSize = 12f
                    letterSpacing = 0.10f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                val counter = pill("", accentDim)
                head.addView(counter)
                c.addView(head)
                if (section.contains("BDIX")) {
                    c.addView(android.widget.TextView(context).apply {
                        text = "Needs a BDIX ISP connection"
                        setTextColor(textMuted)
                        textSize = 11f
                        setPadding(0, dp(1), 0, 0)
                    })
                }
                // per-section bulk toggle
                val bulk = android.widget.TextView(context).apply {
                    text = "Toggle all in this group"
                    setTextColor(accent)
                    textSize = 11.5f
                    setPadding(0, dp(6), 0, dp(2))
                }
                c.addView(bulk)
                sectionCards += Triple(section, c, counter)

                group.forEachIndexed { srcIdx, src ->
                    // (v87) hairline divider between rows — separates the
                    // list visually without boxes around every row.
                    if (srcIdx > 0) c.addView(android.view.View(context).apply {
                        setBackgroundColor(0x14FFFFFF)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                        )
                    })
                    val row = android.widget.LinearLayout(context).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0, dp(6), 0, dp(6))
                    }
                    val labels = android.widget.LinearLayout(context).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    }
                    val on = isEnabled(src.id)
                    val name = android.widget.TextView(context).apply {
                        text = src.label
                        setTextColor(if (on) textPrimary else textMuted)
                        textSize = 15f
                    }
                    val host = android.widget.TextView(context).apply {
                        // (v87) host + one-line purpose — the toggle now
                        // tells you what it serves, not just where it lives.
                        text = if (src.blurb.isBlank()) src.host
                            else "${src.host} · ${src.blurb}"
                        setTextColor(textMuted)
                        textSize = 11.5f
                    }
                    labels.addView(name)
                    labels.addView(host)
                    val sw = android.widget.Switch(context).apply {
                        isChecked = on
                        styleSwitch(this, on)
                        setOnCheckedChangeListener { _, checked ->
                            setEnabled(src.id, checked)
                            name.setTextColor(if (checked) textPrimary else textMuted)
                            styleSwitch(this, checked)
                            refreshSummary()
                        }
                    }
                    switches += sw
                    row.addView(labels)
                    row.addView(sw)
                    row.setOnClickListener { sw.toggle() }
                    c.addView(row)
                    rowIndex += Triple(src, row as android.view.View, name)
                }
                bulk.setOnClickListener {
                    val allOn = group.all { isEnabled(it.id) }
                    group.forEach { setEnabled(it.id, !allOn) }
                    rowIndex.filter { it.first.section == section }.forEach { (s, r, n) ->
                        val v = (r as android.widget.LinearLayout).getChildAt(1)
                        if (v is android.widget.Switch) {
                            v.isChecked = isEnabled(s.id)
                            styleSwitch(v, v.isChecked)
                            n.setTextColor(if (v.isChecked) textPrimary else textMuted)
                        }
                    }
                    refreshSummary()
                }
                root.addView(c)
            }

            // ── INTEGRATIONS card ─────────────────────────────────────
            val intCard = card()
            intCard.addView(android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(chip("KEY", cometPearl))
                addView(android.widget.TextView(context).apply {
                    text = "INTEGRATIONS"
                    setTextColor(cometPearl)
                    textSize = 12f
                    letterSpacing = 0.10f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
            })
            intCard.addView(android.widget.TextView(context).apply {
                text = "Your own API key, stored on this device only. Leave blank to keep off."
                setTextColor(textMuted)
                textSize = 11f
                setPadding(0, dp(1), 0, dp(2))
            })

            fun integrationRow(
                toggleId: String, keyId: String,
                title: String, blurb: String, where: String,
            ) {
                val on = isEnabled(toggleId)
                val head = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, 0)
                }
                val labels = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                val name = android.widget.TextView(context).apply {
                    text = title
                    setTextColor(if (on) textPrimary else textMuted)
                    textSize = 15f
                }
                labels.addView(name)
                labels.addView(android.widget.TextView(context).apply {
                    text = blurb
                    setTextColor(textMuted)
                    textSize = 11.5f
                })
                val statusPill = pill(
                    if (apiKey(keyId) != null) "KEY SET" else "NO KEY",
                    if (apiKey(keyId) != null) chipOn else chipOff
                )
                val sw = android.widget.Switch(context).apply {
                    isChecked = on
                    styleSwitch(this, on)
                    setOnCheckedChangeListener { _, checked ->
                        setEnabled(toggleId, checked)
                        name.setTextColor(if (checked) textPrimary else textMuted)
                        styleSwitch(this, checked)
                    }
                }
                head.addView(labels)
                head.addView(statusPill)
                head.addView(sw)
                head.setOnClickListener { sw.toggle() }
                intCard.addView(head)

                val edit = android.widget.EditText(context).apply {
                    setText(apiKey(keyId) ?: "")
                    hint = "Paste your API key"
                    setHintTextColor(textMuted)
                    setTextColor(textPrimary)
                    textSize = 13f
                    setSingleLine(true)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(fieldBg)
                        cornerRadius = dp(9).toFloat()
                        setStroke(dp(1), accentDim)
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                }
                intCard.addView(edit)
                intCard.addView(android.widget.TextView(context).apply {
                    text = where
                    setTextColor(textMuted)
                    textSize = 10.5f
                    setPadding(0, dp(2), 0, dp(2))
                })
                keyEditors += keyId to edit
            }

            integrationRow(
                "wyziesubs", KEY_WYZIE, "Wyzie Subs",
                "Subtitles for every source — including BDIX .mkv files",
                "Free key (1 000/day): store.wyzie.io/redeem",
            )
            integrationRow(
                "mdblist", KEY_MDBLIST, "MDBList ratings",
                "IMDb · RT · Metacritic · MAL scores on show pages",
                "Free key (1 000/day): mdblist.com → Preferences",
            )
            root.addView(intCard)

            root.addView(android.widget.TextView(context).apply {
                text = "Keys save when you press Done and never leave this device " +
                    "except in requests to that service."
                setTextColor(textMuted)
                textSize = 10.5f
                setPadding(0, dp(8), 0, dp(2))
            })

            refreshSummary()

            // Live filter: hide non-matching rows and any card left empty.
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) {
                    val q = e?.toString()?.trim()?.lowercase().orEmpty()
                    rowIndex.forEach { (src, view, _) ->
                        val hit = q.isEmpty() ||
                            src.label.lowercase().contains(q) ||
                            src.host.lowercase().contains(q) ||
                            src.section.lowercase().contains(q) ||
                            src.blurb.lowercase().contains(q)
                        view.visibility =
                            if (hit) android.view.View.VISIBLE else android.view.View.GONE
                    }
                    sectionCards.forEach { (sec, card, _) ->
                        val any = rowIndex.any {
                            it.first.section == sec &&
                                it.second.visibility == android.view.View.VISIBLE
                        }
                        card.visibility =
                            if (any) android.view.View.VISIBLE else android.view.View.GONE
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })

            val scroll = android.widget.ScrollView(context).apply { addView(root) }
            fun saveKeys() = keyEditors.forEach { (id, box) ->
                setApiKey(id, box.text?.toString().orEmpty())
            }
            android.app.AlertDialog.Builder(context)
                .setView(scroll)
                .setPositiveButton("Done") { _, _ -> saveKeys() }
                .setNegativeButton("All off") { dlg, _ ->
                    saveKeys()
                    sources.forEach { setEnabled(it.id, false) }
                    dlg.dismiss()
                    openDialog(context, sources)
                }
                .setNeutralButton("All on") { dlg, _ ->
                    saveKeys()
                    sources.forEach { setEnabled(it.id, true) }
                    dlg.dismiss()
                    openDialog(context, sources)
                }
                .show()
        }
    }

    internal interface SourceResolver {
        /** (v68) toggle identity — class-name derived and stable
         *  (extension dex is not obfuscated: CircleFtpResolver → "circleftp"). */
        val toggleId: String
            get() = wizToggleId(this)

        suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            tmdbId: Int? = null,
            imdbId: String? = null,
        ): Boolean
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 1: Cineplex BD  (http://cineplexbd.net)
    //  Parser ported from CineplexBDProvider.kt — uses /player.php?id=$id
    //  indirection (movies) and watch.php?…&meta=1 JSON endpoint (TV).
    // ════════════════════════════════════════════════════════════════════════

    internal object CineplexBdResolver : SourceResolver {
        private const val SITE = "http://cineplexbd.net"
        private const val LABEL = "CineplexBD"

        private val HEADERS = mapOf(
            // (v35) Byte-identical to CineplexBDProvider.cfHeaders — the
            // standalone's exact Chrome/121 UA, not the generic UA.
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Referer" to "$SITE/",
        )

override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            // 1. Search via search.php — same selectors as CineplexBDProvider.
            val searchUrl = "$SITE/search.php?q=${encodeUrl(title)}&page=1"
            val searchResp = runCatching {
                app.get(searchUrl, headers = HEADERS, timeout = 10_000)
            }.getOrNull()
            if (searchResp == null) return false
            if (searchResp.code !in 200..299) return false
            val html = searchResp.text

            val doc = Jsoup.parse(html, searchUrl)
            val candidates = mutableListOf<Pair<String, String>>()
            doc.select(
                "a[href*='view.php'], a[href*='watch.php'], a[href*='tview.php'], " +
                    ".movie-card a, a:has(.poster), a:has(img[src*='uploads'])"
            ).forEach { a ->
                val href = a.attr("href").ifBlank { return@forEach }
                val id = if (href.contains("series_id=")) {
                    href.substringAfter("series_id=").substringBefore("&")
                } else {
                    href.substringAfter("id=").substringBefore("&")
                }
                if (id.isBlank() || id == href) return@forEach

                val absHref = when {
                    href.contains("series_id=") -> "$SITE/watch.php?series_id=$id"
                    href.contains("tview.php") -> "$SITE/tview.php?id=$id"
                    href.contains("watch.php") -> "$SITE/watch.php?id=$id"
                    else -> "$SITE/view.php?id=$id"
                }
                val titleEl = a.selectFirst(
                    "span.truncate, div.text-sm, div.cp-title, h2, .card-title, .title"
                )
                val candTitle = titleEl?.text()?.ifBlank {
                    a.selectFirst("img")?.attr("alt")
                } ?: return@forEach
                if (candTitle.isBlank()) return@forEach
                candidates += absHref to candTitle
            }

            if (candidates.isEmpty()) return false

            // Filter: prefer series page for TV, view page for movie.
            //
            // FIX (v12): For movies, ONLY include /view.php URLs — NOT
            // /watch.php URLs. /watch.php?id=X is a SERIES page (for series
            // that use `id` instead of `series_id`), not a movie page. The
            // old filter `!u.contains("watch.php?series_id")` still allowed
            // /watch.php?id=X, which caused the title matcher to pick a
            // series page as the "best match" for a movie query. The resolver
            // then fetched /player.php?id=<series_id>, which returned a
            // series episode page instead of a movie player — extraction
            // failed and no links were emitted.
            val filtered = candidates.filter { (u, _) ->
                if (isMovie) u.contains("view.php") && !u.contains("watch")
                else u.contains("watch.php") || u.contains("tview.php")
            }.ifEmpty { candidates }

            // Exact-normalised match first, then fall back to highest similarity.
            // FIX (v18): identity matching. The old exact-then-Jaccard pick
            // with a hard 0.4 cutoff silently rejected legitimate matches
            // whenever the card title carried extras the TMDB title lacks
            // (year, quality, "Bengali Dubbed", ...) — which is why
            // CineplexBD looked dead inside Wizstream while the standalone
            // (no auto-match gate, the user picks by hand) worked fine.
            // Multiple candidates of the same film (quality variants) are
            // still tried in best-similarity order.
            // (v32) Tiered matching + multi-candidate tries. The v18 strict
            // identity gate silently rejected legitimate BDIX posts whose
            // titles carried extra cut/rip tokens, which is why CineplexBD
            // looked dead inside Wizstream. Tier 1 = strict identity (keeps
            // franchise separation first). Tier 2 = fuzzy meta match (year-
            // compatible, ≥0.6) — only used when tier 1 finds nothing.
            // The top-3 survivors are tried in order until one emits links,
            // so a quality-duplicate post picked first no longer poisons
            // the whole resolve when its player page is broken.
            // (v41) Multiple QUALITY copies of the same title are filed as
            // SEPARATE posts on CineplexBD (user-confirmed: "if a movie has
            // multiple quality then all the quality of those movies are
            // separate items"). Trying only the single best-matching post
            // therefore surfaces only ONE quality. v41 fetches EVERY matching
            // post (movies up to 6, series up to 4 — series resolutions are
            // multi-round-trip and costly), dedupes identical stream URLs
            // across posts, and passes each post's title-derived quality
            // label down as the chip for streams whose own URL/manifest
            // can't prove one.
            val identityMatches = filtered.filter { (_, ct) -> isSameMediaTitle(ct, title, year) }
            val pool = identityMatches.ifEmpty {
                filtered.filter { (_, ct) -> isFuzzySameMedia(ct, title, year) }
            }
            val tryList = pool
                .sortedByDescending { (_, ct) -> titleSimilarity(ct, title) }
                .distinctBy { it.first }
                .take(if (isMovie) 6 else 4)
            if (tryList.isEmpty()) return false

            val srcLabel = "$labelPrefix • $LABEL"
            val seenStreamUrls = linkedSetOf<String>()
            val dedupCallback: (ExtractorLink) -> Unit = { link ->
                if (seenStreamUrls.add(link.url)) callback(link)
            }

            var anyEmitted = false
            tryList.forEach { cand ->
                val ok = try {
                    if (isMovie) {
                        resolveMovieOne(
                            app, cand, srcLabel, subtitleCallback, dedupCallback,
                            qualityHint = qualityFromName(cand.second),
                        )
                    } else {
                        resolveTvOne(
                            app, cand, season, episode, srcLabel, subtitleCallback, dedupCallback,
                            qualityHint = qualityFromName(cand.second),
                        )
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "CineplexBD: resolve crashed: ${t.javaClass.simpleName}")
                    false
                }
                if (ok) anyEmitted = true
            }
            return anyEmitted
        }

        private suspend fun resolveMovieOne(
            app: Requests,
            best: Pair<String, String>,
            srcLabel: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            qualityHint: Int = Qualities.Unknown.value,
        ): Boolean {
            // (moved under resolveMovieOne in v32; body otherwise unchanged)

            // ── MOVIE PATH ──────────────────────────────────────────────────
            // CineplexBDProvider.load() builds the data URL as
            //   /player.php?id=$id
            // then loadLinks fetches /player.php?id=$id which contains the
            // actual video embed. The player page may use one of:
            //   • Old style:  const videoSrc = "….m3u8|mp4|mkv"
            //   • Legacy:     <source src="…">
            //   • Quetta:     data-quetta-video-id="qv_xxx_xxx" (loaded via JS)
            //
            // We capture cookies from the response and forward them as a
            // Cookie header — this is required for protected video URLs and
            // matches the Aniyomi CineplexBD extension's pattern.
                return run {
                // Defensive check: if the best match is NOT a /view.php URL
                // (e.g. it's a /watch.php series page), bail out — fetching
                // /player.php?id=<series_id> would return a series page, not
                // a movie player, and extraction would fail silently.
                if (!best.first.contains("view.php")) {
                    return false
                }

                val id = best.first.substringAfter("id=").substringBefore("&")
                if (id.isBlank()) return false
                val playerUrl = "$SITE/player.php?id=$id"
                val playerResp = runCatching {
                    app.get(playerUrl, headers = HEADERS, timeout = 15_000)
                }.getOrNull()
                if (playerResp == null) {
                    return false
                }
                val playerHtml = playerResp.text

                // Forward Set-Cookie values + Referer to downstream requests.
                // NiceResponse.cookies is a Map<String, String> of cookie
                // name → value, already deduplicated by the cookie jar.
                val cookieHeader = try {
                    playerResp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                } catch (_: Throwable) { "" }
                val videoHeaders = HEADERS.toMutableMap().apply {
                    if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
                    put("Referer", playerUrl)
                }.toMap()

                // (v40) One helper does the whole player-page scrape:
                // page subtitles, media URLs (incl. JS/relative player-config
                // shapes), HLS fan-out into per-quality links with real chips
                // and master subtitle/audio tracks, Quetta fallback, and a
                // one-level recursion into player.php-style sub-pages.
                var any = scrapeCineplexPageHtml(
                    app, playerUrl, playerHtml, videoHeaders, srcLabel,
                    subtitleCallback, callback, depth = 0, qualityHint = qualityHint,
                )

                // (v40 — was the standalone-style Quetta extraction; the
                // scrape helper now runs Quetta for every page it processes.)

                // ── download.php fallback ─────────────────────────────────
                // /download.php?id=<id> often has a direct <a href="/Data/…">
                // link we can scrape.
                if (!any) {
                    val dlUrl = "$SITE/download.php?id=$id"
                    runCatching {
                        val dlHtml = app.get(dlUrl, headers = videoHeaders, timeout = 15_000).text
                        val dlUrls = extractCineplexMedia(dlHtml, dlUrl)
                        dlUrls.forEach { u ->
                            if (emitCineplexAny(app, u, srcLabel, dlUrl, videoHeaders, subtitleCallback, callback, qualityHint)) any = true
                        }
                    }
                }

                // Also try the view.php page itself (sometimes the player is
                // inline) — same smart scrape.
                if (!any) {
                    val viewResp = runCatching {
                        app.get(best.first, headers = HEADERS, timeout = 15_000)
                    }.getOrNull()
                    if (viewResp != null && viewResp.code in 200..299) {
                        val viewHeaders = HEADERS.toMutableMap().apply {
                            put("Referer", best.first)
                        }.toMap()
                        if (scrapeCineplexPageHtml(
                                app, best.first, viewResp.text, viewHeaders, srcLabel,
                                subtitleCallback, callback, depth = 0,
                                qualityHint = qualityHint,
                            )
                        ) any = true
                    }
                }
                if (!any) {
                    Log.d(TAG, "CineplexBD: movie player+view scraped, 0 media found")
                }
                return any
            }
        }

        // ── TV PATH ─────────────────────────────────────────────────────
        // Use the watch.php?…&season=N&meta=1 JSON endpoint that
        // CineplexBDProvider.parseEpisodesFromMetaJson uses. Each
        // episode object has a `path` field pointing to a /Data/… direct
        // media URL OR a /player.php?id=… indirection.
        private suspend fun resolveTvOne(
            app: Requests,
            best: Pair<String, String>,
            season: Int?,
            episode: Int?,
            srcLabel: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            qualityHint: Int = Qualities.Unknown.value,
        ): Boolean {
            val seriesIdKey = if (best.first.contains("series_id=")) "series_id" else "id"
            val seriesIdVal = if (seriesIdKey == "series_id") {
                best.first.substringAfter("series_id=").substringBefore("&")
            } else {
                best.first.substringAfter("id=").substringBefore("&")
            }
            val seasonToUse = season ?: 1
            // (v31) Episode-path collection extracted into a local helper so
            // the season-probe fallback can reuse it. Adds are synchronized
            // because season probes run concurrently.
            fun collectEpisodePaths(metaText: String, out: MutableList<Pair<Int, String>>) {
                val root = runCatching { JSONObject(metaText) }.getOrNull() ?: return
                val episodesNode: Any? = root.opt("episodes") ?: root.opt("data") ?: root
                when (episodesNode) {
                    is JSONObject -> {
                        val keys = episodesNode.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = episodesNode.optJSONObject(k) ?: continue
                            // (v33) epNum also from the episode's own title
                            // ("Episode 4", "E04") — CineplexBDProvider's
                            // buildEpisode does the same.
                            val rawName = v.optStringOrNullCp("title")
                                ?: v.optStringOrNullCp("name") ?: ""
                            val epNum = v.optStringOrNullCp("episode_number")?.toIntOrNull()
                                ?: v.optInt("episode_number", 0).takeIf { it != 0 }
                                ?: k.toIntOrNull()
                                ?: Rx.E_TOKEN_RE.find(rawName)
                                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                                ?: 0
                            val path = v.optStringOrNullCp("path") ?: v.optStringOrNullCp("url")
                                ?: v.optStringOrNullCp("src") ?: v.optStringOrNullCp("file")
                            if (path != null) synchronized(out) { out += epNum to path }
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until episodesNode.length()) {
                            val v = episodesNode.optJSONObject(i) ?: continue
                            val rawName = v.optStringOrNullCp("title")
                                ?: v.optStringOrNullCp("name") ?: ""
                            val epNum = v.optStringOrNullCp("episode_number")?.toIntOrNull()
                                ?: v.optInt("episode_number", 0).takeIf { it != 0 }
                                ?: Rx.E_TOKEN_RE.find(rawName)
                                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                                ?: (i + 1)
                            val path = v.optStringOrNullCp("path") ?: v.optStringOrNullCp("url")
                                ?: v.optStringOrNullCp("src") ?: v.optStringOrNullCp("file")
                            if (path != null) synchronized(out) { out += epNum to path }
                        }
                    }
                }
            }

            val allPaths = mutableListOf<Pair<Int, String>>() // (epNum, path)

            val metaUrl = "$SITE/watch.php?$seriesIdKey=$seriesIdVal&season=$seasonToUse&meta=1"
            runCatching {
                app.get(metaUrl, headers = HEADERS, timeout = 12_000).text
            }.getOrNull()?.let { collectEpisodePaths(it, allPaths) }

            // ── (v31/v33) Fallbacks mirrored from the standalone provider ─
            // (a) Numeric season sweep — the standalone fires seasons 1..12
            //     concurrently whenever the JSON path has so far produced
            //     nothing (its "3-extra" numeric fallback). v31 probed only
            //     2..8 and only when season 1 was requested, which mis-served
            //     shows whose server-side season numbers drift. v33: if the
            //     requested season's meta yielded zero paths AT ALL, sweep
            //     every *other* season in parallel; episode identity still
            //     requires the exact (epNum) match below, and paths from a
            //     different season can only surface when the requested
            //     season had no matches (guard at `matchPath` sites).
            // Sweep results land in a per-season map first — flat-merging
            // them into allPaths would let an S1 path masquerade as the
            // requested S2 episode. Only ONE non-empty alternate season is
            // accepted (the common "single-season show filed under a wrong
            // number" case); several non-empty seasons means the site has
            // genuine multi-season data and the empty requested season is a
            // server glitch — auto-picking there would serve the wrong
            // season, so we bail and let the next candidate post try.
            if (allPaths.isEmpty()) {
                val bySeason = java.util.concurrent.ConcurrentHashMap<Int, MutableList<Pair<Int, String>>>()
                coroutineScope {
                    ((1..12) - seasonToUse).map { s ->
                        async(Dispatchers.IO) {
                            val u = "$SITE/watch.php?$seriesIdKey=$seriesIdVal&season=$s&meta=1"
                            val text = runCatching {
                                app.get(u, headers = HEADERS, timeout = 10_000).text
                            }.getOrNull() ?: return@async
                            val tmp = mutableListOf<Pair<Int, String>>()
                            collectEpisodePaths(text, tmp)
                            if (tmp.isNotEmpty()) bySeason[s] = tmp
                        }
                    }.awaitAll()
                }
                if (bySeason.size == 1) {
                    allPaths += bySeason.values.first()
                }
            }
            // (b) Scrape numbered episode anchors straight off the watch page
            //     (standalone's parseEpisodesFromWatchPage).
            if (allPaths.isEmpty()) {
                runCatching {
                    app.get(best.first, headers = HEADERS, timeout = 12_000).text
                }.getOrNull()?.let { watchHtml ->
                    val wdoc = Jsoup.parse(watchHtml, best.first)
                    val anchors = wdoc.select(
                        "a[href*='ep='], a[href*='episode='], a[href*='/Data/'], " +
                            "a.episode, a[data-episode], .episode-list a, .episodes a"
                    )
                    anchors.forEachIndexed { idx, a ->
                        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEachIndexed
                        // (v34) The watch page lists ONE season's episodes and
                        // every anchor carries &season=N. Keep only the
                        // requested season's anchors (or unmarked ones) so a
                        // leftover S1 strip can never answer an S2 request.
                        val anchorSeason = Regex("""season=(\d+)""")
                            .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        if (anchorSeason != null && anchorSeason != seasonToUse) {
                            return@forEachIndexed
                        }
                        val epNum = Regex("""(?i)(?:ep(?:isode)?[=\s]*|\bE)(\d+)""")
                            .find(href + " " + a.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: (idx + 1)
                        allPaths += epNum to href
                    }
                }
            }

            if (allPaths.isEmpty()) {
                Log.d(TAG, "CineplexBD: tv S$seasonToUse meta+sweep+anchors yielded 0 episode paths")
            }
            val epToUse = episode ?: 1
            // (v33) exact episode only — the old first-episode fallback meant
            // asking for S2E10 could silently serve S1E1 (wrong content).
            var matchPath = allPaths.firstOrNull { it.first == epToUse }?.second
            // (v37) Last-resort: scrape the show's LANDING watch page for the
            // stream, mirroring the standalone's "Watch" placeholder episode
            // (CineplexBDProvider.collectEpisodesForGroup step 5). BOUNDED to
            // S1E1 requests only: the landing page always plays season 1, so
            // it can never masquerade as a higher season/episode.
            if (matchPath == null && seasonToUse == 1 && epToUse == 1) {
                Log.d(TAG, "CineplexBD: no ep paths — trying landing-page scrape for S1E1")
                matchPath = best.first
            }
            if (matchPath == null) {
                Log.d(TAG, "CineplexBD: tv E$epToUse not among ${allPaths.size} paths")
                return false
            }

            // The path can be:
            //   • /Data/…/movie.mkv  → direct media URL
            //   • /view.php?id=… or /player.php?id=… → indirection page
            //   • Quetta player page → data-quetta-video-id="qv_…"
            var absPath = resolveAbs(SITE, matchPath)
            // (v34) 1:1 with CineplexBDProvider.loadLinksWithLabel:
            //   • .m3u8/.mp4/.mkv … (or /Data/) → emit directly;
            //   • EVERYTHING else — player.php, view.php AND the watch-page
            //     episode anchors (watch.php?id=…&season=…&ep=N) — is an
            //     HTML PAGE that must be fetched and scraped for the real
            //     media URL.
            //     v33's `else -> emitDirect` routed page URLs into
            //     loadExtractor, which knows no cineplexbd extractor, so
            //     every episode silently died → "CineplexBD not working at
            //     all" while the standalone scraped the very same pages.
            if (isDirectMedia(absPath) || absPath.contains("/Data/")) {
                // (v40) emitCineplexAny — an m3u8 master fans out into real
                // per-quality links here too, and its embedded subtitle
                // tracks reach the player.
                if (emitCineplexAny(app, absPath, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback, qualityHint)) {
                    return true
                }
                // (v41) DON'T give up: meta-JSON/stored episode paths can be
                // stale (expired token, moved file) — the server answers
                // them with catch-all junk that the v40 hygiene rightfully
                // DROPS. On the user's device this exact shape surfaced as a
                // single "all 1 candidates tried, 0 links" diag chip with no
                // inner stage chip. Fall through to scraping the REAL episode
                // page instead — that's where the fresh player URL lives.
                // (Guessed shape: the same watch.php CGI the anchors use,
                // with this episode's season/ep params.)
                absPath = "$SITE/watch.php?$seriesIdKey=$seriesIdVal&season=$seasonToUse&ep=$epToUse"
            }
            return run {
                    val playerResp = runCatching {
                        app.get(absPath, headers = HEADERS, timeout = 15_000)
                    }.getOrNull()
                    if (playerResp == null) {
                        Log.d(TAG, "CineplexBD: tv episode page fetch failed")
                        return false
                    }
                    val playerHtml = playerResp.text

                    // Forward cookies + Referer to downstream video requests.
                    val cookieHeader = try {
                        playerResp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    } catch (_: Throwable) { "" }
                    val videoHeaders = HEADERS.toMutableMap().apply {
                        if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
                        put("Referer", absPath)
                    }.toMap()

                    // (v40) Smart scrape of the episode page — page subs,
                    // JS/relative player-config media, HLS fan-out with real
                    // quality chips + master tracks, Quetta, and ONE level of
                    // recursion into player.php-style sub-pages. v39 scraped
                    // only the surface HTML: on series, whose stream lives a
                    // second server round-trip away (mirroring the movie
                    // flow's player.php hop), that meant 0 media every time.
                    var any = scrapeCineplexPageHtml(
                        app, absPath, playerHtml, videoHeaders, srcLabel,
                        subtitleCallback, callback, depth = 0,
                        qualityHint = qualityHint,
                    )

                    // (v40) Constructed player-page candidates. The movie
                    // path proves the stream lives in /player.php?id=…, so
                    // the same CGI with season/ep params is the likeliest
                    // SERIES target when the episode page embeds nothing.
                    if (!any) {
                        val playerCands = listOf(
                            "$SITE/player.php?$seriesIdKey=$seriesIdVal&season=$seasonToUse&ep=$epToUse",
                            "$SITE/player.php?$seriesIdKey=$seriesIdVal&season=$seasonToUse&episode=$epToUse",
                            "$SITE/tplayer.php?$seriesIdKey=$seriesIdVal&season=$seasonToUse&ep=$epToUse",
                        )
                        for (cand in playerCands) {
                            if (any) break
                            val candResp = runCatching {
                                app.get(cand, headers = HEADERS, timeout = 12_000)
                            }.getOrNull() ?: continue
                            if (candResp.code !in 200..299) continue
                            val candHtml = candResp.text
                            if (candHtml.isBlank() || candHtml == playerHtml) continue
                            val cookieStr = candResp.cookies.entries
                                .joinToString("; ") { e -> e.key + "=" + e.value }
                            val candHeaders = HEADERS.toMutableMap().apply {
                                if (cookieStr.isNotBlank()) put("Cookie", cookieStr)
                                put("Referer", cand)
                            }.toMap()
                            if (scrapeCineplexPageHtml(
                                    app, cand, candHtml, candHeaders, srcLabel,
                                    subtitleCallback, callback, depth = 0,
                                    qualityHint = qualityHint,
                                )
                            ) any = true
                        }
                    }

                    // download.php fallback for TV episodes.
                    if (!any && absPath.contains("player.php")) {
                        val id = absPath.substringAfter("id=", "").substringBefore("&")
                        if (id.isNotBlank()) {
                            val dlUrl = "$SITE/download.php?id=$id"
                            runCatching {
                                val dlHtml = app.get(dlUrl, headers = videoHeaders, timeout = 15_000).text
                                val dlUrls = extractCineplexMedia(dlHtml, dlUrl)
                                dlUrls.forEach { u ->
                                    if (emitCineplexAny(app, u, srcLabel, dlUrl, videoHeaders, subtitleCallback, callback, qualityHint)) any = true
                                }
                            }
                        }
                    }
                    if (!any) {
                        Log.d(TAG, "CineplexBD: tv episode+player pages scraped, 0 media found")
                    }
                    any
            }
        }

        /**
         * (v34) Port of CineplexBDProvider.collectSubtitles — subtitle files
         * referenced directly by an episode/player page (<track> tags,
         * download anchors, or any absolute .srt/.vtt/.ass URL in the
         * markup).
         */
        private suspend fun collectPageSubs(
            html: String,
            baseUrl: String,
            subtitleCallback: (SubtitleFile) -> Unit,
        ) {
            val seen = linkedSetOf<String>()
            val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull()
            doc?.select("track[src], a[href*='.srt'], a[href*='.vtt'], a[href*='.ass']")
                ?.forEach { el ->
                    val raw = el.attr("src").ifBlank { el.attr("href") }.ifBlank { return@forEach }
                    val subUrl = resolveAbs(baseUrl, raw)
                    if (subUrl.isBlank() || !seen.add(subUrl)) return@forEach
                    val label = el.attr("label")
                        .ifBlank { el.attr("srclang") }
                        .ifBlank { el.text() }
                        .ifBlank {
                            subUrl.substringAfterLast('/').substringBefore('?')
                                .substringBeforeLast('.')
                        }
                    subtitleCallback(newSubtitleFile("[$LABEL] $label", subUrl))
                }
            Regex(
                """https?://[^\s"'<>]+\.(?:srt|vtt|ass)(?:\?[^\s"'<>]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(html)
                .map { it.value.replace("&amp;", "&") }
                .forEach { raw ->
                    if (raw.isNotBlank() && seen.add(raw)) {
                        val label = raw.substringAfterLast('/').substringBefore('?')
                            .substringBeforeLast('.')
                        subtitleCallback(newSubtitleFile("[$LABEL] $label", raw))
                    }
                }

            // (v40) Player-config subtitles — JWPlayer `tracks:[{file:…}]`,
            // artplayer/DPlayer `subtitle: {url:…}` / `subtitle: '…'` styles.
            // These sit inside inline scripts, invisible to the HTML selector
            // pass above; without them a title whose subs only exist in the
            // player config shows NO selectable text track.
            val unescaped = html.replace("""\/""", "/")
            suspend fun emitRawSub(raw: String) {
                val subUrl = resolveAbs(baseUrl, raw.replace("&amp;", "&"))
                if (subUrl.isBlank() || !seen.add(subUrl)) return
                val label = subUrl.substringAfterLast('/').substringBefore('?')
                    .substringBeforeLast('.')
                subtitleCallback(newSubtitleFile("[$LABEL] $label", subUrl))
            }
            Regex("""(?i)tracks\s*:\s*\[([^\]]*)\]""")
                .findAll(unescaped).forEach { block ->
                    Regex("""file\s*:\s*["']([^"']+\.(?:srt|vtt|ass)[^"']*)["']""", RegexOption.IGNORE_CASE)
                        .findAll(block.groupValues[1]).forEach { m -> emitRawSub(m.groupValues[1]) }
                }
            Regex("""(?i)["']?(?:subtitle|captions?|subs?)["']?\s*:\s*\{?\s*(?:url\s*:\s*)?["']([^"']+\.(?:srt|vtt|ass)[^"']*)["']""")
                .findAll(unescaped).forEach { m -> emitRawSub(m.groupValues[1]) }
        }

        /**
         * (v34) Port of CineplexBDProvider.collectM3u8Subtitles — scans an
         * HLS master manifest for #EXT-X-MEDIA:TYPE=SUBTITLES tracks and
         * forwards them as subtitle files.
         */
        private suspend fun collectM3u8Subs(
            app: Requests,
            manifestUrl: String,
            referer: String,
            subtitleCallback: (SubtitleFile) -> Unit,
        ) {
            val h = HEADERS.toMutableMap().apply { put("Referer", referer) }
            val manifest = runCatching {
                // (v38) 6s cap — series resolves chain several of these and
                // a dead manifest host must not stall the whole resolve.
                app.get(manifestUrl, headers = h, timeout = 6_000).text
            }.getOrNull() ?: return
            if (!manifest.startsWith("#EXTM3U")) return
            Regex("""#EXT-X-MEDIA:([^\r\n]+)""", RegexOption.IGNORE_CASE)
                .findAll(manifest)
                .forEach { match ->
                    val attrs = match.groupValues[1]
                    if (!attrs.contains("TYPE=SUBTITLES", ignoreCase = true)) return@forEach
                    val uri = m3u8Attr(attrs, "URI") ?: return@forEach
                    val subUrl = resolveAbs(manifestUrl, uri)
                    val label = m3u8Attr(attrs, "NAME") ?: m3u8Attr(attrs, "LANGUAGE")
                        ?: subUrl.substringAfterLast('/').substringBefore('?')
                            .substringBeforeLast('.')
                    subtitleCallback(newSubtitleFile("[$LABEL] $label", subUrl))
                }
        }

        private fun m3u8Attr(attrs: String, key: String): String? =
            Regex("$key=\"([^\"]*)\"").find(attrs)?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }

        // ════════════════════════════════════════════════════════════════════
        //  (v40) CineplexBD smart emission + recursive page scraping
        //  v39 problems addressed here:
        //   1. "No quality tag" — every .m3u8 went straight to M3u8Helper, so
        //      a single-rendition media playlist became ONE chip-less link;
        //      a master playlist's per-quality fan-out never happened either.
        //   2. "No track selection" — subtitle/audio groups embedded in the
        //      HLS master (#EXT-X-MEDIA) and player-config subtitle entries
        //      (JWP `tracks:[{file:…}]` / artplayer `subtitle: '…'`) were
        //      never parsed.
        //   3. "Nothing for series" — the episode page was scraped ONLY at
        //      its surface HTML. The movie flow proves CineplexBD keeps the
        //      real stream one further server round-trip away (player.php);
        //      episode pages follow the same pattern, so we now recurse ONE
        //      level into player.php-style sub-pages when the surface has no
        //      media.
        // ════════════════════════════════════════════════════════════════════

        /** extractMediaUrlsFromHtml plus the JS/relative shapes CineplexBD's
         *  player config actually uses: quoted relative media
         *  ("ondemand/<hash>/index.m3u8", "/Data/film.mkv") and JSON-escaped
         *  slashes inside inline scripts. */
        private fun extractCineplexMedia(html: String, baseUrl: String): LinkedHashSet<String> {
            val unescaped = html.replace("""\/""", "/")
            val out = extractMediaUrlsFromHtml(unescaped, baseUrl)
            Regex("""(?i)["'](/?[\w\-./%]+\.(?:m3u8|mp4|mkv|webm|m4v|mov)(?:\?[^"']*)?)["']""")
                .findAll(unescaped).forEach { m ->
                    val abs = resolveAbs(baseUrl, m.groupValues[1].replace("&amp;", "&"))
                    if (!isLikelyThumbnailMediaUrl(abs)) out += abs
                }
            return out
        }

        /** .php sub-page URLs referenced by a CineplexBD page — the second
         *  server round-trip where the stream actually lives. watch.php is
         *  EXCLUDED on purpose: those links are episode neighbours, and
         *  scraping ep=2's page while resolving ep=1 would emit the WRONG
         *  episode's stream. Auth/nav/report pages are excluded too. */
        private fun extractCineplexPageLinks(html: String, baseUrl: String, selfUrl: String): List<String> {
            val out = linkedSetOf<String>()
            Regex("""(?i)(?:src|href)\s*=\s*["']([^"']*\.php[^"']*)["']""")
                .findAll(html).forEach { out += it.groupValues[1] }
            Regex("""(?i)["']((?:https?://[\w.\-]+)?/?[a-z0-9_\-]*(?:player|embed|get_stream|stream|video|vod|ajax)[a-z0-9_\-]*\.php\?[^"']+)["']""")
                .findAll(html).forEach { out += it.groupValues[1] }
            val ban = Regex("""(?i)search\.php|index\.php|login|logout|signup|register|report|comment|contact|request|watch\.php""")
            val selfNorm = selfUrl.removePrefix("$SITE/").trimEnd('/')
            return out.map { resolveAbs(baseUrl, it.replace("&amp;", "&")) }
                .filter { u ->
                    u.contains(SITE, ignoreCase = true) &&
                        !ban.containsMatchIn(u) &&
                        u.removePrefix("$SITE/").trimEnd('/') != selfNorm
                }
                .distinct()
                .take(4)
        }

        /** HLS emission with REAL quality chips + selectable tracks: fetch the
         *  manifest, forward TYPE=SUBTITLES groups to subtitleCallback, route
         *  TYPE=AUDIO demuxed masters through emitDemuxedMaster (top-variant
         *  chip, ExoPlayer muxes the audio group), otherwise emit each variant
         *  with its own resolution chip. A single-rendition media playlist
         *  emits one link (chip only when the URL itself hints a quality).
         *  Fetch failure → v39 behaviour (M3u8Helper) so nothing regresses.
         *  A 200 that is NOT a playlist is the site's catch-all junk — drop. */
        private suspend fun emitCineplexHls(
            app: Requests,
            url: String,
            srcLabel: String,
            referer: String,
            headers: Map<String, String>,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            qualityHint: Int = Qualities.Unknown.value,
        ): Boolean {
            val h = headers.toMutableMap().apply { put("Referer", referer) }
            val resp = runCatching { app.get(url, headers = h, timeout = 10_000) }.getOrNull()
                ?: return emitDirect(app, url, srcLabel, referer, headers, subtitleCallback, callback)
            if (resp.code !in 200..299) return false
            val text = resp.text
            if (!text.trimStart().startsWith("#EXTM3U")) {
                Log.d(TAG, "CineplexBD: $url answered HTTP ${resp.code} but is not a playlist — dropped")
                return false
            }
            Regex("""#EXT-X-MEDIA:([^\r\n]+)""", RegexOption.IGNORE_CASE)
                .findAll(text).forEach { match ->
                    val attrs = match.groupValues[1]
                    if (!attrs.contains("TYPE=SUBTITLES", ignoreCase = true)) return@forEach
                    val uri = m3u8Attr(attrs, "URI") ?: return@forEach
                    val subUrl = resolveAbs(url, uri)
                    val label = m3u8Attr(attrs, "NAME") ?: m3u8Attr(attrs, "LANGUAGE")
                        ?: subUrl.substringAfterLast('/').substringBefore('?').substringBeforeLast('.')
                    subtitleCallback(newSubtitleFile("[$LABEL] $label", subUrl))
                }
            if (emitDemuxedMaster(url, text, srcLabel, "$srcLabel — HLS", referer, headers, callback, qualityHint)) {
                return true
            }
            val variants = parseHlsMasterVariants(text, url)
            if (variants.isEmpty()) {
                callback(
                    newExtractorLink(
                        source = srcLabel,
                        name = "$srcLabel — HLS",
                        url = url,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = referer
                        this.headers = headers
                        // (v41) Single-rendition playlists can't prove their
                        // own quality — the post title the stream came from
                        // carries it (each quality is a separate site item).
                        this.quality = qualityFromName(url).takeIf { it != Qualities.Unknown.value }
                            ?: qualityHint
                    }
                )
                return true
            }
            var any = false
            variants.forEach { v ->
                val skip = DeviceDecoderProbe.skipReason(videoCodecOf(v.codecs), v.width, v.height)
                if (skip != null) {
                    Log.d(TAG, "CineplexBD: variant skipped (${v.width}x${v.height}, ${v.codecs}): $skip")
                    return@forEach
                }
                callback(
                    newExtractorLink(
                        source = srcLabel,
                        name = "$srcLabel — HLS" + codecDisplayTag(v.codecs),
                        url = v.url,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = referer
                        this.headers = headers
                        this.quality = if (v.height > 0) {
                            qualityFromDimensions(v.width, v.height)
                        } else {
                            qualityHint
                        }
                    }
                )
                any = true
            }
            return any
        }

        /** What the CineplexBD resolvers call INSTEAD of emitDirect (v40):
         *  .m3u8 gets the smart-chips path; everything else is exactly v39's
         *  emitDirect (direct-video links with cookie headers, extractor
         *  delegation for foreign pages). (v41) qualityHint — the post
         *  title's quality label — fills the chip when the stream itself
         *  can't prove one (each quality is a separate item on this site). */
        private suspend fun emitCineplexAny(
            app: Requests,
            url: String,
            srcLabel: String,
            referer: String,
            headers: Map<String, String>,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            qualityHint: Int = Qualities.Unknown.value,
        ): Boolean {
            val clean = url.trim()
            return when {
                clean.contains(".m3u8", ignoreCase = true) ->
                    emitCineplexHls(app, clean, srcLabel, referer, headers, subtitleCallback, callback, qualityHint)
                isDirectMedia(clean) || clean.contains("/Data/") -> {
                    callback(
                        newExtractorLink(
                            source = srcLabel,
                            name = "$srcLabel - Direct",
                            url = clean,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = referer
                            this.headers = headers
                            this.quality = qualityFromName(clean).takeIf { it != Qualities.Unknown.value }
                                ?: qualityHint
                        }
                    )
                    true
                }
                else -> emitDirect(app, clean, srcLabel, referer, headers, subtitleCallback, callback)
            }
        }

        /** Scrape ONE already-fetched CineplexBD page: page subtitles → media
         *  URLs via the smart emitter → Quetta fallback → (surface level only)
         *  recurse into player.php-style sub-pages, refreshing cookies and
         *  the Referer chain on the way down. */
        private suspend fun scrapeCineplexPageHtml(
            app: Requests,
            pageUrl: String,
            pageHtml: String,
            videoHeaders: Map<String, String>,
            srcLabel: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            depth: Int,
            qualityHint: Int = Qualities.Unknown.value,
        ): Boolean {
            collectPageSubs(pageHtml, pageUrl, subtitleCallback)
            var any = false
            extractCineplexMedia(pageHtml, pageUrl).forEach { u ->
                if (emitCineplexAny(app, u, srcLabel, pageUrl, videoHeaders, subtitleCallback, callback, qualityHint)) any = true
            }
            if (!any) {
                val quettaId: String? = Regex(
                    """data-quetta-video-id=["']?(qv_[a-z0-9_]+)["']?""",
                    RegexOption.IGNORE_CASE
                ).find(pageHtml)?.groupValues?.getOrNull(1)
                if (!quettaId.isNullOrBlank()) {
                    if (emitQuettaVideo(app, quettaId, pageUrl, videoHeaders, srcLabel, subtitleCallback, callback, qualityHint)) {
                        any = true
                    }
                }
            }
            if (!any && depth < 1) {
                extractCineplexPageLinks(pageHtml, pageUrl, pageUrl).forEach { sub ->
                    val sr = runCatching {
                        app.get(sub, headers = videoHeaders, timeout = 12_000)
                    }.getOrNull() ?: return@forEach
                    if (sr.code !in 200..299) return@forEach
                    val sHtml = sr.text
                    if (sHtml.isBlank() || sHtml == pageHtml) return@forEach
                    val cookieStr = sr.cookies.entries.joinToString("; ") { e -> e.key + "=" + e.value }
                    val subHeaders = videoHeaders.toMutableMap().apply {
                        if (cookieStr.isNotBlank()) put("Cookie", cookieStr)
                        put("Referer", sub)
                    }.toMap()
                    if (scrapeCineplexPageHtml(
                            app, sub, sHtml, subHeaders, srcLabel,
                            subtitleCallback, callback, depth = depth + 1,
                            qualityHint = qualityHint,
                        )
                    ) any = true
                }
            }
            return any
        }

        /**
         * Try multiple candidate Quetta API endpoints to resolve a
         * `data-quetta-video-id` to a playable URL.
         *
         * The actual Quetta API endpoint is embedded in a JS file loaded by
         * player.php and isn't publicly documented. We try the most common
         * shapes used by similar player frameworks.
         */
        private suspend fun emitQuettaVideo(
            app: Requests,
            quettaId: String,
            playerUrl: String,
            videoHeaders: Map<String, String>,
            sourceLabel: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            qualityHint: Int = Qualities.Unknown.value,
        ): Boolean {
            val candidates = listOf(
                "https://vidquettaplayer.com/api/?id=$quettaId",
                "https://vidquettaplayer.com/api/source/$quettaId",
                "https://vidquettaplayer.com/Quetta/?id=$quettaId",
                "https://quetta.com/api/?id=$quettaId",
                "https://api.quetta.io/v1/video/$quettaId",
                "$SITE/api/quetta/?id=$quettaId",
                "$SITE/Quetta/api/?id=$quettaId",
            )
            for (apiUrl in candidates) {
                val resp = runCatching {
                    app.get(apiUrl, headers = videoHeaders, timeout = 10_000)
                }.getOrNull() ?: continue
                if (resp.code !in 200..299 || resp.text.isBlank()) continue
                // Skip HTML responses (likely 404 pages or CF challenge pages).
                if (resp.text.startsWith("<") || resp.text.contains("<!DOCTYPE", true)) continue
                val json = runCatching { JSONObject(resp.text) }.getOrNull() ?: continue
                val mediaUrls = linkedSetOf<String>()
                // Try common JSON shapes.
                json.optJSONObject("data")?.let { dataObj ->
                    dataObj.optStringOrNullCp("src")?.let { mediaUrls += it }
                    dataObj.optStringOrNullCp("url")?.let { mediaUrls += it }
                    dataObj.optStringOrNullCp("file")?.let { mediaUrls += it }
                    dataObj.optJSONArray("sources")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val s = arr.optJSONObject(i) ?: continue
                            s.optStringOrNullCp("file")?.let { mediaUrls += it }
                            s.optStringOrNullCp("src")?.let { mediaUrls += it }
                            s.optStringOrNullCp("url")?.let { mediaUrls += it }
                        }
                    }
                }
                json.optStringOrNullCp("url")?.let { mediaUrls += it }
                json.optStringOrNullCp("src")?.let { mediaUrls += it }
                json.optJSONArray("sources")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        s.optStringOrNullCp("file")?.let { mediaUrls += it }
                        s.optStringOrNullCp("src")?.let { mediaUrls += it }
                        s.optStringOrNullCp("url")?.let { mediaUrls += it }
                    }
                }
                if (mediaUrls.isEmpty()) continue
                var any = false
                for (u in mediaUrls) {
                    val abs = if (u.startsWith("http")) u else "$SITE/${u.trimStart('/')}"
                    // (v40) smart path — Quetta HLS answers get the same
                    // quality chips + track parsing as everything else.
                    if (emitCineplexAny(app, abs, sourceLabel, playerUrl, videoHeaders, subtitleCallback, callback, qualityHint)) {
                        any = true
                    }
                }
                if (any) return true
            }
            return false
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 2: FTPBD  (https://ftpbd.net)
    //  Parser ported from FTPBDProvider.kt — uses exact-clean-title match
    //  (cleanMediaTitle + normalizedTitle) for accurate anime resolution.
    // ════════════════════════════════════════════════════════════════════════

    internal object FtpBdResolver : SourceResolver {
        private const val SITE = "https://ftpbd.net"
        private const val LABEL = "FTPBD"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            // FTPBD has separate post_type for movies vs tv_shows. Anime
            // lives under tv_shows with "Anime" in the title or /tv_shows/
            // path. For anime we search tv_shows first then movies.
            val postTypes = if (isMovie) listOf("movies") else listOf("tv_shows", "movies")
            val expectedPaths = if (isMovie) listOf("/movie/") else listOf("/tv_shows/", "/movie/")

            val candidates = mutableListOf<Pair<String, String>>()
            coroutineScope {
                postTypes.mapIndexed { idx, postType ->
                    async(Dispatchers.IO) {
                        val url = "$SITE/?s=${encodeUrl(title)}&post_type=$postType"
                        runCatching {
                            val doc = app.get(url, headers = HEADERS, timeout = 12_000).document
                            // EXACT selector from FTPBDProvider.parseCardItems
                            doc.select(".site-main .jws-post-item, .site-main .post-inner")
                                .forEach { card ->
                                    val a = card.selectFirst("a[href*='${expectedPaths[idx]}']") ?: return@forEach
                                    val href = a.absUrl("href").ifBlank { a.attr("href") }
                                    if (href.isBlank() || !href.contains(expectedPaths[idx])) return@forEach
                                    val t = card.selectFirst("h6 a, h5 a, h4 a, .post-title a")?.text()?.trim()
                                        ?: a.attr("title").trim().ifBlank { null }
                                        ?: a.text().trim()
                                    if (t.isNotBlank() && !t.equals("Play Now", true)) {
                                        synchronized(candidates) { candidates += href to t }
                                    }
                                }
                        }
                    }
                }.awaitAll()
            }

            if (candidates.isEmpty()) return false

            // ── TITLE MATCHING ──────────────────────────────────────────────
            // Use FTPBDProvider's exact-normalised match first (cleanMediaTitle +
            // normalizedTitle). This is critical for anime — without stripping
            // "[Hindi Dubbed]" / "Season 1", "One Piece" would match the wrong
            // series. Fall back to Jaccard similarity only if no exact match.
            // (v20) REVERTED to the pre-v18 fuzzy pick — the v18 identity+year
            // gate was too strict for FTPBD's catalogue (bad/absent years),
            // which made titles that used to resolve silently disappear.
            val qNorm = title.normaliseTitle()
            val best = candidates
                .distinctBy { it.first }
                .firstOrNull { (_, ct) -> ct.normaliseTitle() == qNorm }
                ?: candidates.distinctBy { it.first }
                    .maxByOrNull { (_, ct) -> titleSimilarity(ct, title) }
                ?: return false
            if (titleSimilarity(best.second, title) < 0.4) return false

            val srcLabel = "$labelPrefix • $LABEL"
            val detailHtml = runCatching {
                app.get(best.first, headers = HEADERS, timeout = 15_000).text
            }.getOrNull() ?: return false

            // For TV: find the episode's permalink via /episodes/ path.
            val episodePageUrl: String? = if (!isMovie && season != null && episode != null) {
                findEpisodePageUrl(app, detailHtml, best.first, season, episode)
            } else {
                null
            }

            val mediaUrls = extractMediaUrlsFromHtml(detailHtml, best.first)
            if (episodePageUrl != null) {
                runCatching {
                    app.get(episodePageUrl, headers = HEADERS, timeout = 15_000).text
                }.getOrNull()?.let { epHtml ->
                    mediaUrls += extractMediaUrlsFromHtml(epHtml, episodePageUrl)
                }
            }

            Log.d(
                TAG, "FtpBD: emit ${mediaUrls.size} url(s) for " +
                    "'$title' s=$season e=$episode"
            )
            var any = false
            mediaUrls.forEach { u ->
                if (emitDirect(app, u, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)) any = true
            }
            return any
        }

        /** (v31) FTPBD TV fix: episode permalinks are slug-based
         *  (/episodes/romance-dawn/) with NO S/E numbers in the URL, so the
         *  old href-pattern matcher never matched anything and FTPBD emitted
         *  nothing for series. The standalone provider scrapes the numbered
         *  grid at <series>/episodes/?season=N (span.episodes-number badges),
         *  which is what we now do first; the detail page's own slider is
         *  the last-resort fallback. Verified live against /tv_shows/one-piece. */
        private suspend fun findEpisodePageUrl(
            app: Requests,
            detailHtml: String,
            detailUrl: String,
            season: Int,
            episode: Int,
        ): String? {
            // 1) Numbered episode grid on the dedicated episodes page.
            val gridUrl = "${detailUrl.trimEnd('/')}/episodes/?season=$season"
            runCatching {
                app.get(gridUrl, headers = HEADERS, timeout = 15_000).text
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { gridHtml ->
                val doc = Jsoup.parse(gridHtml, gridUrl)
                doc.select("span.episodes-number").forEach { badge ->
                    val n = badge.text().trim().toIntOrNull() ?: return@forEach
                    if (n != episode) return@forEach
                    val a = badge.parent()?.selectFirst("a[href*='/episodes/']")
                    val href = a?.attr("href")?.takeIf { it.isNotBlank() }
                    if (href != null) return resolveAbs(gridUrl, href)
                }
                // Card fallback: numbering from the surrounding card.
                doc.select("a[href*='/episodes/']").forEach { a ->
                    val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                    val holder = a.closest(".jws-post-item, .post-inner, .episode-item, li")
                    val num = holder?.selectFirst(".episodes-number")?.text()?.trim()?.toIntOrNull()
                        ?: Rx.SXE_TOKEN_RE.find(holder?.text().orEmpty())
                            ?.groupValues?.get(1)?.toIntOrNull()
                    if (num == episode) return resolveAbs(gridUrl, href)
                }
            }

            // 2) Detail page slider fallback — SxxEyy / badge numbering.
            val doc = Jsoup.parse(detailHtml, detailUrl)
            val anchors = doc.select("a[href*='/episodes/']")
                .distinctBy { it.attr("href") }
            anchors.forEach { a ->
                val holder = a.closest(".jws-post-item, li, div") ?: a
                val text = holder.text()
                val sm = Regex("""(?i)S(\d+)E(\d+)""").find(text)
                val num = if (sm != null && sm.groupValues[1].toIntOrNull() == season) {
                    sm.groupValues[2].toIntOrNull()
                } else {
                    holder.selectFirst(".episodes-number")?.text()?.trim()?.toIntOrNull()
                }
                if (num == episode) {
                    val href = a.attr("href").takeIf { it.isNotBlank() }
                    if (href != null) return resolveAbs(detailUrl, href)
                }
            }
            // DOM-order last resort: on single-season pages the episode
            // slider lists Season 1 in order.
            if (season == 1) {
                val ord = anchors.getOrNull(episode - 1)?.attr("href")
                if (!ord.isNullOrBlank()) return resolveAbs(detailUrl, ord)
            }
            return null
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 3: Circle FTP  (http://new.circleftp.net)
    //  Parser ported from CircleFtpProvider.kt. Key insight from
    //  CircleFtpProvider.load():
    //    • For movies (postObj.type == "singleVideo"), the URL is at
    //      postObj.optString("content") — a STRING, not an array.
    //    • For TV/anime, postObj.optJSONArray("content") returns a
    //      JSONArray of seasons, where content[seasonIndex].episodes[epIndex].link
    //      is the URL. Season index is 0-based (season-1).
    //    • URLs are encoded as "circleftp://movie?data=<base64>" or
    //      "circleftp://episode?data=<base64>" payloads, where the base64
    //      decodes to a JSON array of {url, audio?} objects.
    // ════════════════════════════════════════════════════════════════════════

    internal object CircleFtpResolver : SourceResolver {
        private const val SITE = "http://new.circleftp.net"
        private const val PRIMARY_API = "http://new.circleftp.net:5000"
        private const val FALLBACK_API = "http://15.1.1.50:5000"
        private const val LABEL = "CircleFTP"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
        )

        /**
         * Map of CDN hostname → raw IP. Ported 1:1 from
         * CircleFtpProvider.cdnHostToIp (lines 1479-1496).
         *
         * Circle FTP serves media from `*.circleftp.net` hostnames that
         * only resolve on BDIX networks. When the user is on BDIX, the
         * hostname DNS lookup fails but the underlying IP is reachable.
         * We swap the hostname for the IP in the URL so the request goes
         * through. (Matches CircleFtpProvider.linkToIp behaviour.)
         */
        private val cdnHostToIp: List<Pair<String, String>> = listOf(
            "index.circleftp.net"  to "15.1.4.2",
            "index2.circleftp.net" to "15.1.4.5",
            "index1.circleftp.net" to "15.1.4.9",
            "ftp3.circleftp.net"   to "15.1.4.7",
            "ftp4.circleftp.net"   to "15.1.1.5",
            "ftp5.circleftp.net"   to "15.1.1.15",
            "ftp6.circleftp.net"   to "15.1.2.3",
            "ftp7.circleftp.net"   to "15.1.4.8",
            "ftp8.circleftp.net"   to "15.1.2.2",
            "ftp9.circleftp.net"   to "15.1.2.12",
            "ftp10.circleftp.net"  to "15.1.4.3",
            "ftp11.circleftp.net"  to "15.1.2.6",
            "ftp12.circleftp.net"  to "15.1.2.1",
            "ftp13.circleftp.net"  to "15.1.1.18",
            "ftp15.circleftp.net"  to "15.1.4.12",
            "ftp17.circleftp.net"  to "15.1.3.8",
        )

        /** Swap any *.circleftp.net hostname in the URL for its BDIX IP. */
        private fun linkToIp(data: String?): String {
            if (data.isNullOrEmpty()) return ""
            for ((host, ip) in cdnHostToIp) {
                if (host in data) return data.replace(host, ip)
            }
            return data
        }

        /**
         * Detect whether a Circle FTP post is anime (vs regular TV/movie).
         * Ported from CircleFtpProvider.isPostAnime (lines 980-996).
         */
        private fun isPostAnime(categoriesArr: org.json.JSONArray?, postTitle: String): Boolean {
            val titleLower = postTitle.lowercase()
            if (titleLower.contains("anime") || titleLower.contains("animation") ||
                titleLower.contains("cartoon")
            ) return true
            if (categoriesArr != null) {
                for (i in 0 until categoriesArr.length()) {
                    val catObj = categoriesArr.optJSONObject(i) ?: continue
                    val catId = catObj.optInt("id")
                    val catName = catObj.optString("name", "").lowercase()
                    if (catId == 21 || catId == 1 || catName.contains("anime") ||
                        catName.contains("animation") || catName.contains("cartoon")
                    ) return true
                }
            }
            return false
        }

        /**
         * Clean a Circle FTP post title to extract the base anime/show name
         * + any audio tag. Ported from CircleFtpProvider.cleanFtpTitle.
         *
         * Returns (cleanedTitle, audioTag?) — e.g.
         *   "One Piece [Hindi Dubbed]" → ("One Piece", "HINDI DUBBED")
         *   "Naruto Season 2"          → ("Naruto Season 2", null)
         */
        private fun cleanFtpTitle(postName: String?, postTitle: String): Pair<String, String?> {
            val audioMatches = Rx.AUDIO_TAG_RE.findAll(postTitle).map { it.value.trim() }.toList()
            val audioTag = if (audioMatches.isNotEmpty()) {
                audioMatches.joinToString(" ").uppercase()
            } else null

            var cleaned = postName?.trim().orEmpty()
            if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true)) {
                cleaned = postTitle.replace(Rx.EXT_STRIP_RE, "")
                    .replace(".", " ")
                    .replace("_", " ")
                    .replace("-", " ")
                    .replace(Rx.YEAR_TOKEN_RE, "")
                    .trim()
            }
            cleaned = cleaned.split(" ").joinToString(" ") {
                it.replaceFirstChar { c -> c.uppercase() }
            }
            return Pair(cleaned, audioTag)
        }

        /** Normalised title for grouping. Ported from CircleFtpProvider.normalizedGroupTitle. */
        private fun normalizedGroupTitle(title: String): String =
            title.lowercase()
                .replace(Rx.NON_ALNUM_RE, " ")
                .trim()
                .replace(Rx.WS_SPLIT_RE, " ")

        /** (v46) Query hygiene for the site search: drop punctuation the
         *  server's substring matcher chokes on ("Haikyuu!!", "Re:ZERO",
         *  "Don't Toy with Me, Miss Nagatoro"). Only used to widen the
         *  SEARCH call — matching runs on the original title. */
        private fun cleanedSearchTerm(t: String): String =
            t.replace(Rx.SEARCH_PUNCT_RE, " ")
                .replace(Rx.WS_SPLIT_RE, " ")
                .trim()

        // (v48) ── Anime-category BROWSE rescue ──────────────────────────
        // The user's saved proof ("Haikyuu!! (TV Series 2014-2020) Anime
        // [Dual Audio] [Eng＋Japanese]") showed Haikyuu/Final/Part-2 kept
        // failing despite every search widening: the server-side search
        // simply isn't a plain substring match we can steer from outside
        // Bangladesh. The standalone's own anime listing, however, is a
        // plain paged catalogue (categoryExact=21, "Anime Series") — so for
        // SERIES requests we can walk it ourselves and run the same tier
        // gates client-side, which is exactly what a human browsing the
        // site does. Cached 10 minutes; pages stop on an empty/short page.
        private var animeCategoryCache:
            Pair<Long, Pair<List<org.json.JSONObject>, Boolean>>? = null

        private suspend fun animeCategoryPosts(
            app: Requests,
        ): Pair<List<org.json.JSONObject>, Boolean> {
            val now = System.currentTimeMillis()
            animeCategoryCache?.let { (ts, v) ->
                if (now - ts < 10 * 60_000L) return v
            }
            val out = mutableListOf<org.json.JSONObject>()
            var usedIp = false
            // (v70) 12 → 40 pages: the catalogue is upload-date-ordered, so
            // legacy mega posts (Haikyuu 2014-2020, Attack on Titan 2013-)
            // sink far below the first 720 rows. 2400 rows covers the
            // backlog a human reaches by paging the site's Anime category.
            for (page in 1..40) {
                val resp = fetchWithFallback(
                    app,
                    primary = "$PRIMARY_API/api/posts?categoryExact=21&page=$page&order=desc&limit=60",
                    fallback = "$FALLBACK_API/api/posts?categoryExact=21&page=$page&order=desc&limit=60",
                ) ?: break
                usedIp = usedIp || resp.second
                val arr = runCatching { JSONObject(resp.first).optJSONArray("posts") }
                    .getOrNull() ?: break
                val len = arr.length()
                if (len == 0) break
                for (i in 0 until len) arr.optJSONObject(i)?.let { out += it }
                if (len < 60) break
                // Yield between pages: 40 sequential API hits is a long
                // crawl — keep the resolver cancellable/cooperative.
                kotlinx.coroutines.yield()
            }
            Log.d(TAG, "CircleFTP: anime browse catalogued ${out.size} posts")
            val value = out to usedIp
            animeCategoryCache = now to value
            return value
        }

        /** (v45) Decoration-stripped post title for the tier-3 multi-season
         *  rescue match: drops "(TV Series 2024-)"/"[Dual Audio]" wrappers,
         *  season numbers, part/cour/final markers, quality/audio junk and
         *  years — the residual string is the site's bare show name. */
        private fun bareSeriesTitle(t: String): String =
            t.replace(Rx.BST_BRACKET_RE, " ")
                .replace(Rx.BST_PAREN_RE, " ")
                .replace(Rx.BST_KIND_RE, " ")
                // (v47) ordinal season wording: "HAIKYU!! 2nd Season" must
                // bare down to "HAIKYU!!", not "HAIKYU!! 2nd".
                .replace(Rx.BST_ORDINAL_RE, " ")
                .replace(Rx.BST_SEASON_RE, " ")
                .replace(Rx.BST_S_RE, " ")
                .replace(Rx.BST_PART_RE, " ")
                // (v90b) Sequel-wording leftovers that kept SPECIAL/CHAPTER
                // entries from baring down to their franchise root:
                // "Attack on Titan Final Season THE FINAL CHAPTERS
                // Special 1" lost "final"/"season" above but still carried
                // "THE CHAPTERS Special" — unusable as a search term or
                // alias key. Fold those words away too (and a dangling
                // trailing "the" left behind by "…: The Final Season").
                .replace(Rx.BST_FINAL_CHAPTERS_RE, " ")
                .replace(Rx.BST_CHAPTER_RE, " ")
                .replace(Rx.BST_SPECIAL_RE, " ")
                .replace(Rx.BST_TRAIL_THE_RE, " ")
                .replace(Rx.BST_AUDIO_RE, " ")
                .replace(Rx.BST_QUALITY_RE, " ")
                .replace(Rx.BST_YEAR_RE, " ")
                .replace(Rx.BST_TRAIL_PUNCT_RE, "")
                .replace(Rx.WS_SPLIT_RE, " ")
                .trim()

        /**
         * (v72) Conservative series-root alias key for the tier-3 rescue.
         * AniList spells the volleyball franchise HAIKYU!! (one final U),
         * while Circle Network's legacy mega post spells it Haikyuu!! (two).
         * Strict/fuzzy identity must keep treating such strings as distinct
         * for films; only the already-season-gated series rescue is allowed
         * this vowel-run fold. It also harmlessly covers common romaji
         * lengthening variants such as Hoozuki/Hozuki.
         */
        private fun seriesRootAliasKey(t: String): String =
            bareSeriesTitle(t).normaliseTitle()
                .replace(Rx.VOWEL_RUN_RE, "$1")

        /** Shared tier-3 test for the new API, category browse and old
         * WordPress searches. The normal containment test protects franchise
         * siblings; an exact alias-key match is the narrow HAIKYU/HAIKYUU
         * escape hatch described above. */
        private fun isSeriesRescueMatch(postTitle: String, queryTitle: String): Boolean {
            val qNorm = queryTitle.normaliseTitle()
            val pNorm = bareSeriesTitle(postTitle).normaliseTitle()
            if (qNorm.length < 4 || pNorm.length < 4) return false
            val containment = pNorm.contains(qNorm) ||
                (qNorm.startsWith(pNorm) &&
                    (qNorm.length == pNorm.length || qNorm[pNorm.length] == ' '))
            if (containment) return true
            val qRoot = seriesRootAliasKey(queryTitle)
            val pRoot = seriesRootAliasKey(postTitle)
            return qRoot.length >= 5 && pRoot.length >= 5 && qRoot == pRoot
        }

        // ── (v71) MAIN-SITE rescue (main.circleftp.net) ─────────────────────
        // The "new" site (new.circleftp.net:5000) is a RE-CATALOGUE, not a
        // migration: posts the operator never re-upped exist ONLY on the old
        // WordPress site ("Circle Network"). Device-verified by the user:
        // "Haikyuu is not available on new.circleftp.net but available on
        // it's main site main.circleftp.net" — no amount of search/paging on
        // the new API can find it (structurally absent), so a resolve that
        // ends dry on the new API now falls through to here.
        //
        // The main site is plain WordPress:
        //   • wp-json search: /wp-json/wp/v2/search?search=<q>&per_page=50
        //     &subtype=any (API presence confirmed via the oembed/wp-json
        //     discovery links in the user's saved SingleFile captures) —
        //     the classic ?s= search page is the fallback when the JSON
        //     route is disabled/blocked. Post permalinks live under
        //     /cn/<slug>/ (proven by the same captures: the Haikyuu and
        //     Attack-on-Titan posts).
        //   • Post pages are server-rendered: per-season su_tabs + <table>
        //     rows of
        //       <td>Haikyuu!!.S1.Episode:1</td>
        //       <td><a href=http://ftp15.circleftp.net/FILE/.../Season%201/
        //       %5BJudas%5D%20Haikyuu%21%21%20S1%20-%2001.mkv>Download</a></td>
        //     (structure proven by the saved Haikyuu capture: 85 distinct
        //     mkv URLs = TMDB's exact 25/25/10/25 season counts).
        // So: search → gate candidates through the SAME identity/fuzzy/
        // tier-3 chain as the new-API pipeline → parse each post's media
        // anchors into (season, episode, url) rows → pick the requested
        // episode NUMBER-TRUE (same doctrine as the v70 new-API pool
        // picker: when ≥50% of a post's in-season rows declare a number,
        // only exact number matches are served; else positional indexing).
        private const val MAIN_SITE = "http://main.circleftp.net"
        private val MAIN_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$MAIN_SITE/",
            // (v74) Look like a browser page load, not an API client: the
            // saved captures came from the user's browser (cookies + any
            // anti-bot clearance). A bare-OkHttp call is exactly the shape
            // WAF/security plugins challenge — which would silently empty
            // every search route below on-device.
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
        )

        private data class MainSiteRow(val season: Int?, val epNum: Int?, val url: String)

        /** Parsed media rows per post URL, 10-minute cache — one post
         *  serves every episode of a binge session. */
        private val mainSiteRowsCache =
            java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<MainSiteRow>>>()

        // (v73) Candidate search cache. A legacy mega-post needs the same
        // WordPress search and title gates for every tapped episode, while
        // the selected post's rows are already cached below. Cache both a
        // hit and a short-lived miss so a binge session does not serially
        // repeat up to five WordPress queries per episode on BDIX.
        private val mainSiteSearchCache =
            java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Pair<String, String>>>>()
        private const val MAIN_SITE_SEARCH_CACHE_MS = 5 * 60_000L
        // (v74) Negatives are cached SEPARATELY and briefly (90 s), and only
        // when the site demonstrably answered (a true "nothing there"). The
        // v73 design cached empty results in the positive map with the same
        // 5-minute TTL — if the first tap hit a transient block/timeout,
        // EVERY later tap failed instantly for five minutes (prime suspect
        // for the device report "still no link after v72").
        private val mainSiteNegCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private const val MAIN_SITE_NEG_CACHE_MS = 90_000L
        private fun mainSiteSearchCacheKey(
            title: String, year: Int?, isMovie: Boolean, season: Int?,
        ): String = "${title.normaliseTitle()}|${year ?: 0}|$isMovie|${season ?: 0}"

        /**
         * WordPress's REST and classic-search routes do not promise one
         * permalink shape. v71 accepted only /cn|anime|tv|series|movie/slug,
         * which silently discarded a valid legacy post if this installation
         * returned a dated/custom permalink. Accept a same-host content path,
         * while explicitly excluding WordPress infrastructure and archive
         * paths; the title gates below still decide media identity.
         */
        private val mainPostUrlRx = Regex(
            """^https?://(?:www\.)?main\.circleftp\.net/[^?#]+/?$""",
            RegexOption.IGNORE_CASE,
        )
        private val mainNonPostFirstPaths = setOf(
            "wp-json", "wp-admin", "wp-content", "wp-includes", "category",
            "tag", "author", "feed", "page", "search"
        )
        private fun isMainPostUrl(rawUrl: String): Boolean {
            val u = rawUrl.trim()
            if (!mainPostUrlRx.matches(u)) return false
            val path = runCatching { java.net.URI(u).path.orEmpty().trim('/') }
                .getOrNull().orEmpty()
            if (path.isBlank()) return false
            if (path.substringBefore('/').lowercase() in mainNonPostFirstPaths) return false
            return !path.contains("popular-useful-software", ignoreCase = true)
        }
        private val mainMediaFileRx = Regex("""(?i)\.(mkv|mp4|avi|m4v|ts|webm)$""")
        private val mainJunkFileRx = Regex(
            """(?i)\.(srt|vtt|ass|ssa|sub|zip|rar|7z|txt|nfo|jpg|jpeg|png|webp|exe|apk|iso)$"""
        )

        /**
         * Parse one main-site post page into (season, episode, url) media
         * rows from its HTML anchors (the episode tables link STRAIGHT to
         * files on ftp*.circleftp.net — proven by the saved captures).
         */
        private suspend fun mainSiteRowsForPost(
            app: Requests,
            postUrl: String,
        ): List<MainSiteRow> {
            val now = System.currentTimeMillis()
            mainSiteRowsCache[postUrl]?.let { (ts, rows) ->
                if (now - ts < 10 * 60_000L) return rows
            }
            val resp = runCatching {
                app.get(postUrl, headers = MAIN_HEADERS, verify = false, cacheTime = 60, timeout = 12_000)
            }.getOrNull()
            if (resp == null || resp.code !in 200..299 || resp.text.isBlank()) return emptyList()
            val doc = runCatching { Jsoup.parse(resp.text, postUrl) }.getOrNull()
                ?: return emptyList()
            val rows = ArrayList<MainSiteRow>()
            for (a in doc.select("a[href]")) {
                val href = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                if (!href.startsWith("http")) continue
                val decoded = runCatching {
                    java.net.URLDecoder.decode(href, "UTF-8")
                }.getOrElse { href }
                val fileName = decoded.substringBefore('?').substringBefore('#')
                    .substringAfterLast('/')
                val looksMedia = mainMediaFileRx.containsMatchIn(fileName) ||
                    (href.contains("/FILE/", ignoreCase = true) &&
                        !mainJunkFileRx.containsMatchIn(fileName))
                if (!looksMedia) continue
                // Row label: the episode tables carry the file's display
                // name in the row's FIRST <td> ("Haikyuu!!.S1.Episode:1")
                // while the anchor text itself is just "Download"/"Watch
                // Online" — prefer the table-cell text, fall back to the
                // anchor text, then to the decoded filename alone.
                val cell = a.closest("tr")?.selectFirst("td")?.text().orEmpty()
                val anchor = a.text().trim()
                val label = when {
                    cell.isNotBlank() && !cell.equals("download", true) &&
                        !cell.equals("watch online", true) -> "$cell $fileName"
                    anchor.isNotBlank() && !anchor.equals("download", true) &&
                        !anchor.equals("watch online", true) -> "$anchor $fileName"
                    else -> fileName
                }
                val basis = "$label $decoded"
                // Season: explicit ".S1" / "S:1" / "S1E" / "S1 - " forms
                // first, then the shared title parser (its "Season 1"
                // pattern catches the decoded URL path /FILE/.../Season 1/).
                val seasonNum =
                    Rx.ROW_S_DOT_RE.find(basis)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Rx.ROW_S_COLON_E_RE.find(basis)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Rx.ROW_S_E_RE.find(basis)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Rx.ROW_S_RANGE_RE.find(basis)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: extractSeasonFromTitle(basis)
                // Episode number — number-true doctrine; the captures prove
                // the forms: "Episode:1", "S:4E:29", "S2 E0",
                // Judas "S1 - 01", KaiDubs "S4 - 01 (60)".
                val epNum =
                    Rx.ROW_EP_WORD_RE.find(basis)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Rx.ROW_S_E2_RE.find(basis)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Rx.ROW_S_RANGE2_RE.find(basis)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Rx.ROW_DASH_EP_RE.find(basis)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                rows += MainSiteRow(seasonNum, epNum, href)
            }
            val out = rows.distinctBy { it.url }
            mainSiteRowsCache[postUrl] = now to out
            return out
        }

        // ── (v74) SITEMAP catalogue tier ────────────────────────────────────
        // WordPress's OWN sitemap is the deterministic catalogue: unlike
        // the search routes (per-term AND semantics on decorated multi-word
        // queries, possible WAF challenges on API-shaped requests), the
        // sitemap either exists or it doesn't and lists EVERY public post
        // permalink. Slugs are title-derived, so the same identity/fuzzy/
        // tier-3 gates can match them client-side — the exact doctrine the
        // v48 anime-category browse already uses on the new API. This is
        // v74's primary reliability fix for the Haikyuu case: even if BOTH
        // search routes empty out on-device, the mega post's URL
        // (/cn/haikyuu-tv-series-2014-2020-anime-dual-audio-engjapanese/ —
        // proven by the oembed link in the owner's saved capture) is
        // enumerated here and matched by the romaji alias fold.
        private data class MainSitemapEntry(val url: String, val slugTitle: String)

        private var mainSitemapCache: Pair<Long, List<MainSitemapEntry>>? = null
        private const val MAIN_SITEMAP_CACHE_MS = 30 * 60_000L
        private const val MAIN_SITEMAP_MAX_SUBS = 8
        private const val MAIN_SITEMAP_MAX_URLS = 16000
        private val locRx = Regex("""<loc>\s*([^<]+?)\s*</loc>""")

        private suspend fun mainSitemapCatalogue(app: Requests): List<MainSitemapEntry> {
            val now = System.currentTimeMillis()
            mainSitemapCache?.let { (ts, v) ->
                if (now - ts < MAIN_SITEMAP_CACHE_MS) return v
            }
            val indexUrls = listOf(
                "$MAIN_SITE/wp-sitemap.xml",      // WP core (5.5+)
                "$MAIN_SITE/sitemap_index.xml",   // Yoast-style SEO plugins
                "$MAIN_SITE/sitemap.xml",          // RankMath-style / generic
            )
            val out = LinkedHashMap<String, MainSitemapEntry>()
            outer@ for (indexUrl in indexUrls) {
                val idx = runCatching {
                    app.get(indexUrl, headers = MAIN_HEADERS, verify = false, cacheTime = 60, timeout = 12_000)
                }.getOrNull()
                if (idx == null || idx.code !in 200..299) continue
                if (!idx.text.contains("<loc>")) continue
                // An index lists sub-sitemaps; a flat urlset lists posts
                // directly. Walk sub-sitemaps that look like CONTENT maps
                // (post types / pages), never taxonomy/author maps.
                val subs = locRx.findAll(idx.text).map { it.groupValues[1].trim() }
                    .filter {
                        it.contains("-posts-", ignoreCase = true) ||
                            it.contains("post", ignoreCase = true) ||
                            it.contains("cn-", ignoreCase = true)
                    }
                    .toList()
                val walk = if (subs.isEmpty()) listOf(indexUrl) else subs.take(MAIN_SITEMAP_MAX_SUBS)
                val walkStart = System.currentTimeMillis()
                var scanned = 0
                for (sub in walk) {
                    // (v75) bound the whole walk to ~20s: a dead/slow
                    // sub-sitemap must not hold an episode tap hostage.
                    if (System.currentTimeMillis() - walkStart > 20_000L) break
                    val resp = runCatching {
                        app.get(sub, headers = MAIN_HEADERS, verify = false, cacheTime = 60, timeout = 12_000)
                    }.getOrNull()
                    if (resp == null || resp.code !in 200..299) continue
                    for (m in locRx.findAll(resp.text)) {
                        val loc = m.groupValues[1].trim()
                        if (!isMainPostUrl(loc)) continue
                        val slug = loc.trimEnd('/').substringAfterLast('/')
                        if (slug.isBlank()) continue
                        out[loc] = MainSitemapEntry(loc, slug.replace('-', ' '))
                        if (++scanned >= MAIN_SITEMAP_MAX_URLS) break@outer
                    }
                    kotlinx.coroutines.yield()
                }
                if (out.isNotEmpty()) break
            }
            val value = out.values.toList()
            mainSitemapCache = now to value
            Log.d(TAG, "CircleFTP: main-site sitemap catalogued ${value.size} post urls")
            return value
        }

        /**
         * Search the main site for post candidates and gate them through
         * the SAME identity → fuzzy → tier-3 chain the new-API pipeline
         * uses (franchise siblings rejected identically).
         * Returns (postUrl, postTitle) pairs.
         */
        private suspend fun mainSiteSearchPosts(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
        ): List<Pair<String, String>> {
            val now = System.currentTimeMillis()
            val cacheKey = mainSiteSearchCacheKey(title, year, isMovie, season)
            mainSiteSearchCache[cacheKey]?.let { (savedAt, candidates) ->
                if (now - savedAt < MAIN_SITE_SEARCH_CACHE_MS) {
                    Log.d(TAG, "CircleFTP: main-site cache '$title' candidates=${candidates.size}")
                    return candidates
                }
            }
            mainSiteNegCache[cacheKey]?.let { savedAt ->
                if (now - savedAt < MAIN_SITE_NEG_CACHE_MS) {
                    Log.d(TAG, "CircleFTP: main-site cache '$title' candidates=0 (neg)")
                    return emptyList()
                }
            }
            // (v74) becomes true only when WordPress demonstrably ANSWERED
            // somewhere below — distinguishes "site up, genuinely nothing"
            // (a cacheable 90-second negative) from "blocked/unreachable"
            // (never cached, so the very next tap retries fresh).
            // (v75) AtomicBoolean: the v75 discovery routes write it from
            // concurrent coroutines.
            val sawHttpOk = java.util.concurrent.atomic.AtomicBoolean(false)
            val partStripped = title
                .replace(Rx.PART_NUM_RE, " ")
                .replace(Rx.WS_SPLIT_RE, " ").trim()
            val queryVariants = listOf(
                title,
                cleanedSearchTerm(title),
                partStripped,
                cleanedSearchTerm(partStripped),
                cleanedSearchTerm(bareSeriesTitle(title)),
            ).filter { it.isNotBlank() }.distinct()
            val merged = LinkedHashMap<String, String>()  // postUrl → title
            // (v75) PARALLEL DISCOVERY (device report at 23:09 — "still
            // taking a lot of time to fetch the link"): the v74 flow was
            // strictly SERIAL — 5 query variants x wp-json(10s) + ?s=(10s)
            // each, then the sitemap walk — ~4 minutes worst case inside ONE
            // episode tap, and his ring-log had rolled past the resolver
            // lines before he could capture. Every route now races
            // CONCURRENTLY: discovery worst case ≈ the slowest single
            // request (8s caps), not the sum of all of them. Cache-warm
            // taps are still instant (positive cache checked above).
            suspend fun searchOneVariant(q: String) {
                val local = LinkedHashMap<String, String>()
                // 1) WordPress REST search (v74 doctrine unchanged:
                //    subtype=any, retry default subtype on 400, WAF
                //    HTML-challenge detection).
                var jsonResp = runCatching {
                    app.get(
                        "$MAIN_SITE/wp-json/wp/v2/search?search=${encodeUrl(q)}" +
                            "&per_page=50&subtype=any",
                        headers = MAIN_HEADERS, verify = false, cacheTime = 60, timeout = 8_000,
                    )
                }.getOrNull()
                if (jsonResp != null && jsonResp.code == 400) {
                    jsonResp = runCatching {
                        app.get(
                            "$MAIN_SITE/wp-json/wp/v2/search?search=${encodeUrl(q)}" +
                                "&per_page=50",
                            headers = MAIN_HEADERS, verify = false, cacheTime = 60, timeout = 8_000,
                        )
                    }.getOrNull()
                }
                if (jsonResp != null) {
                    if (jsonResp.code !in 200..299) {
                        Log.d(TAG, "CircleFTP: main-site wp-json http=${jsonResp.code}")
                    } else if (jsonResp.text.trimStart().startsWith("<")) {
                        Log.w(
                            TAG, "CircleFTP: main-site wp-json answered HTML " +
                                "not JSON (challenge?) len=${jsonResp.text.length}"
                        )
                    } else {
                        sawHttpOk.set(true)
                        val arr = runCatching { JSONArray(jsonResp.text) }.getOrNull()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val u = o.optStringOrNullCp("url")?.trim() ?: continue
                                val tRaw = o.optStringOrNullCp("title") ?: continue
                                if (!isMainPostUrl(u)) continue
                                // wp-json titles are HTML-escaped ("&#8211;") —
                                // decode or entity fragments pollute the token
                                // gates ("8211" junk tokens).
                                val t = runCatching { Jsoup.parse(tRaw).text() }
                                    .getOrDefault(tRaw).trim()
                                if (t.isNotBlank()) local[u] = t
                            }
                        }
                    }
                }
                // 2) Classic ?s= fallback — only when THIS variant produced
                //    nothing from the REST route (per-variant, as v74).
                if (local.isEmpty()) {
                    val htmlResp = runCatching {
                        app.get(
                            "$MAIN_SITE/?s=${encodeUrl(q)}",
                            headers = MAIN_HEADERS, verify = false, cacheTime = 60, timeout = 8_000,
                        )
                    }.getOrNull()
                    if (htmlResp != null && htmlResp.code in 200..299) {
                        sawHttpOk.set(true)
                        // (v74) prefer content-area anchors: v72's relaxed
                        // permalink acceptance would otherwise also admit
                        // sidebar/footer "recent posts" links page-wide.
                        val doc = runCatching { Jsoup.parse(htmlResp.text, MAIN_SITE) }
                            .getOrNull()
                        val scoped = doc?.select(
                            "article a[href], .entry-content a[href], " +
                                ".post-list a[href], main a[href]"
                        )
                        val anchorScope =
                            if (scoped != null && scoped.isNotEmpty()) scoped
                            else doc?.select("a[href]")
                        anchorScope?.forEach { a ->
                            val u = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                            if (!isMainPostUrl(u)) return@forEach
                            val t = a.text().trim()
                                .ifBlank { a.attr("title").trim() }
                                .ifBlank { a.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                            if (t.isNotBlank()) local[u] = t
                        }
                    }
                }
                val agg = synchronized(merged) {
                    for ((u, t) in local) merged[u] = t
                    merged.size
                }
                Log.d(
                    TAG, "CircleFTP: main-site search '$q' +${local.size} rows " +
                        "(aggregate=$agg)"
                )
            }
            // The sitemap catalogue starts downloading NOW, in parallel
            // with the search variants — launched OUTSIDE the search scope
            // so a slow walk never delays the search-success path (when the
            // tier below needs it, it's already mostly done; when it
            // doesn't, the walk still finishes quietly and keeps the
            // 30-minute catalogue cache warm for later taps).
            val catalogueDeferred = kotlinx.coroutines
                .CoroutineScope(kotlin.coroutines.coroutineContext).async(Dispatchers.IO) {
                    runCatching { mainSitemapCatalogue(app) }.getOrElse { emptyList() }
                }
            coroutineScope {
                queryVariants.map { q ->
                    async(Dispatchers.IO) { runCatching { searchOneVariant(q) }.getOrNull() }
                }.awaitAll()
            }
            // Gates — 1:1 with the browse tier of the new-API pipeline.
            val identity = mutableListOf<Pair<String, String>>()
            val fuzzy = mutableListOf<Pair<String, String>>()
            val rescue = mutableListOf<Pair<String, String>>()
            for ((u, t) in merged) {
                val postYear = Regex("\\b(19|20)\\d{2}\\b").find(t)?.value?.toIntOrNull()
                val effectiveYear = year ?: postYear
                if (isSameMediaTitle(t, title, effectiveYear)) {
                    identity += u to t
                    continue
                }
                if (isFuzzySameMedia(t, title, effectiveYear)) {
                    fuzzy += u to t
                    continue
                }
                if (!isMovie && season != null && isSeriesRescueMatch(t, title)) {
                    rescue += u to t
                }
            }
            var result: List<Pair<String, String>> =
                identity.ifEmpty { fuzzy.ifEmpty { rescue } }

            // ── (v74) SITEMAP discovery tier ────────────────────────────
            // Fires when BOTH search routes produced nothing gate-worthy:
            // zero raw URLs (multi-term AND search starving on decorated
            // AniList titles, or an API-shaped-request WAF block) or raw
            // URLs that were all siblings. The sitemap IS the site — its
            // post slugs are title-derived, so the same gates match them
            // client-side (Haikyuu's mega post slug contains "haikyuu",
            // caught by the alias fold even though "engjapanese" noise
            // defeats strict slug equality).
            if (result.isEmpty()) {
                // (v75) the catalogue has been downloading in the background
                // since the search variants started — only a genuinely slow
                // walk is awaited here, hard-capped at 20s so a flaky
                // sub-sitemap can't hold the tap hostage.
                val catalogue = withTimeoutOrNull(20_000L) { catalogueDeferred.await() }
                    ?: emptyList()
                if (catalogue.isNotEmpty()) {
                    val qRoot = seriesRootAliasKey(title)
                    val smIdentity = mutableListOf<Pair<String, String>>()
                    val smFuzzy = mutableListOf<Pair<String, String>>()
                    val smRescue = mutableListOf<Pair<String, String>>()
                    for (entry in catalogue) {
                        val t = entry.slugTitle
                        if (isSameMediaTitle(t, title, year)) {
                            smIdentity += entry.url to t
                            continue
                        }
                        if (isFuzzySameMedia(t, title, year)) {
                            smFuzzy += entry.url to t
                            continue
                        }
                        val aliasForward = qRoot.length >= 4 &&
                            seriesRootAliasKey(t).contains(qRoot)
                        if (!isMovie && season != null &&
                            (isSeriesRescueMatch(t, title) || aliasForward)
                        ) {
                            smRescue += entry.url to t
                        }
                    }
                    Log.d(
                        TAG, "CircleFTP: main-site sitemap '$title' " +
                            "catalogue=${catalogue.size} identity=${smIdentity.size} " +
                            "fuzzy=${smFuzzy.size} rescue=${smRescue.size}"
                    )
                    // (shortest, least-decorated slug first in the rescue
                    // pool: the franchise mega post outranks themed
                    // spin-offs and movies when several slugs clear the
                    // gate)
                    val smRescueSorted: List<Pair<String, String>> =
                        smRescue.sortedBy { it.first.length }
                    result = when {
                        smIdentity.isNotEmpty() -> smIdentity.toList()
                        smFuzzy.isNotEmpty() -> smFuzzy.toList()
                        else -> smRescueSorted
                    }
                } else {
                    Log.d(TAG, "CircleFTP: main-site sitemap empty/unavailable for '$title'")
                }
            }

            // (v74) Cache discipline: a POSITIVE list is cached 5 minutes;
            // an EMPTY list is only a cacheable negative when the site
            // provably answered (sawHttpOk) — and then only for 90 seconds.
            // A blocked/unreachable phone tap therefore never poisons the
            // next one; every tap retries live.
            if (result.isNotEmpty()) {
                mainSiteSearchCache[cacheKey] = now to result
                mainSiteNegCache.remove(cacheKey)
            } else if (sawHttpOk.get()) {
                mainSiteNegCache[cacheKey] = now
            }
            return result
        }

        /**
         * (v71) Full main-site resolve: search → gate → parse ≤3 posts →
         * number-true episode pick. Returns media URLs fed into the SHARED
         * emit section (links go out in hostname form with no referer —
         * 1:1 with the site's own web player; the user's BDIX connection
         * is the same one that browses the main site, so DNS is fine).
         */
        private suspend fun mainSiteMediaUrls(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
        ): Set<String> {
            val candidates = mainSiteSearchPosts(app, title, year, isMovie, season)
            if (candidates.isEmpty()) {
                Log.d(TAG, "CircleFTP: main-site '$title' candidates=0")
                return emptySet()
            }
            Log.i(TAG, "CircleFTP: main-site '$title' candidates=${candidates.size}")
            val out = linkedSetOf<String>()
            val seasonToUse = season ?: 1
            val episodeToUse = episode ?: 1
            // (v75) The up-to-5 candidate pages are fetched CONCURRENTLY
            // (independent page loads — serial fetching was the other half
            // of the minutes-long tap; 10-minute per-post row cache below
            // still makes binge taps instant). Picking then evaluates in
            // candidate order and stops after enough posts actually SERVED:
            // 2 for series (mega post + a variant), 3 for movies.
            val fetchStart = System.currentTimeMillis()
            val fetched = coroutineScope {
                candidates.distinctBy { it.first }.take(5).map { (postUrl, _) ->
                    async(Dispatchers.IO) {
                        postUrl to runCatching { mainSiteRowsForPost(app, postUrl) }
                            .getOrElse { emptyList<MainSiteRow>() }
                    }
                }.awaitAll()
            }
            var successes = 0
            val maxSuccesses = if (isMovie) 3 else 2
            for ((postUrl, rows) in fetched) {
                if (successes >= maxSuccesses) break
                if (rows.isEmpty()) continue
                if (isMovie) {
                    // Movie post: the page already passed the identity/
                    // fuzzy gate, so every media row on it is the film
                    // (parts / encode variants all wanted).
                    out += rows.map { it.url }
                    successes++
                    continue
                }
                // Season filter: rows that DECLARE a season must match the
                // requested one; undeclared rows keep their positional
                // chance (v11 doctrine — unlabeled mega content).
                val seasonRows = rows.filter { it.season == null || it.season == seasonToUse }
                if (seasonRows.isEmpty()) continue
                val parsed = seasonRows.count { it.epNum != null }
                val numberTrue = parsed * 2 >= seasonRows.size
                val picked: List<MainSiteRow> = if (numberTrue) {
                    seasonRows.filter { it.epNum == episodeToUse }
                } else {
                    seasonRows.getOrNull(episodeToUse - 1)?.let { listOf(it) } ?: emptyList()
                }
                val slug = postUrl.trimEnd('/').substringAfterLast('/')
                Log.d(
                    TAG, "CircleFTP: main-site post $slug " +
                        "seasonRows=${seasonRows.size} " +
                        "seasons=${seasonRows.mapNotNull { it.season }.distinct()} " +
                        "ep=$episodeToUse pick=${if (numberTrue) "num" else "pos"} " +
                        "hit=${picked.size}"
                )
                if (picked.isNotEmpty()) {
                    out += picked.map { it.url }
                    successes++
                }
            }
            // (v75) end-to-end timing for the device reports.
            Log.d(
                TAG, "CircleFTP: main-site done '$title' urls=${out.size} " +
                    "inspected=${fetched.count { it.second.isNotEmpty() }} " +
                    "took=${System.currentTimeMillis() - fetchStart}ms"
            )
            return out
        }

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            // 1. Search posts — fetch all results, not just the best one.
            // (v46) Search-term variants: site search is a naive substring
            // match, so franchise titles carrying leftovers from AniList
            // ("Haikyuu!!", "Re:ZERO") starve it — "Haikyuu" finds the
            // posts, "Haikyuu!!" finds nothing. Retry once with punctuation
            // / symbol junk stripped when the raw query yields zero posts.
            // Downstream matching is unaffected (tier gates run on the
            // original query title).
            // (v47) Third variant — the DECORATION-STRIPPED query itself.
            // AniList uses TITLE-FORM season wording ("HAIKYU!! 2nd
            // Season", "Shingeki no Kyojin Season 3 Part 2") while the
            // site files FILE-FORM ("Haikyuu Season 2", "Shingeki no
            // Kyojin S3"): no word-order/punctuation cleanup ever makes
            // one a substring of the other, so even the punct-stripped
            // term came back empty for every Haikyuu season. Reducing the
            // query to its bare franchise words ("HAIKYU", "Shingeki no
            // Kyojin") is what the tier-3 rescue then matches against.
            // (v64) Search robustness: (a) query variants gain the
            // PART-STRIPPED form ("…Season 3 Part 2" → "…Season 3") so a
            // full-phrase server search can't starve on the part suffix;
            // (b) results are PAGED (limit=60, up to 4 pages, deduped by
            // post id) — franchise mega posts are old uploads that sink
            // below page 1 on busy root-title searches, and single-page
            // reads silently missed them.
            val partStripped = title
                .replace(Rx.PART_NUM_RE, " ")
                .replace(Rx.WS_SPLIT_RE, " ").trim()
            val queryVariants = listOf(
                title,
                cleanedSearchTerm(title),
                partStripped,
                cleanedSearchTerm(partStripped),
                cleanedSearchTerm(bareSeriesTitle(title)),
            ).filter { it.isNotBlank() }.distinct()
            val merged = LinkedHashMap<Int, org.json.JSONObject>()
            var ipRewriteLinks = false
            var searchHit = false
            // (v90b) SERIES asks fetch EVERY variant, not just the first
            // one that answers. Root cause of the "Attack on Titan after
            // Season 3" no-links report: sequel re-uploads ("Attack on
            // Titan: The Final Season …") satisfy the full-title variant,
            // and the early break then kept the bare "Attack on Titan"
            // variant — the only one that surfaces the 2013 season-labeled
            // mega post whose "Season 4" bucket actually serves Final
            // Season / Part 2 / SP 1-2 — from ever being queried. Movie
            // asks keep the cheap first-hit break.
            val isSeriesAsk = season != null && episode != null
            for (q in queryVariants) {
                var page = 0
                var sawAny = false
                while (page < 4) {
                    page++
                    val resp = fetchWithFallback(
                        app,
                        primary = "$PRIMARY_API/api/posts?searchTerm=${encodeUrl(q)}&order=desc&limit=60&page=$page",
                        fallback = "$FALLBACK_API/api/posts?searchTerm=${encodeUrl(q)}&order=desc&limit=60&page=$page",
                    ) ?: break
                    val arr = runCatching { JSONObject(resp.first).optJSONArray("posts") }
                        .getOrNull() ?: break
                    if (arr.length() == 0) break
                    sawAny = true
                    ipRewriteLinks = ipRewriteLinks || resp.second
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { row ->
                            merged[row.optInt("id", -(merged.size + 1))] = row
                        }
                    }
                    if (arr.length() < 60) break
                }
                if (sawAny) {
                    searchHit = true
                    Log.d(
                        TAG, "CircleFTP: search '$q' rows=${merged.size} " +
                            "(query variant hit)"
                    )
                    if (!isSeriesAsk) break
                } else {
                    Log.d(TAG, "CircleFTP: search '$q' rows=0 — next variant")
                }
            }
            // (v70) THE DEAD-RESCUE BUG (user report: "Haikyuu still not
            // getting fetched" while AoT/JJK worked): when EVERY text-search
            // variant starved server-side, this `return false` fired BEFORE
            // the v48 anime-category browse/rescue tiers below — the exact
            // case those tiers exist for. The browse now ALWAYS gets its
            // chance on a dry search; only a dry search AND a dry browse
            // still ends the resolve.
            if (!searchHit || merged.isEmpty()) {
                Log.d(
                    TAG, "CircleFTP: all search variants dry for '$title' " +
                        "— continuing into category browse/rescue tiers"
                )
            }
            val searchText = JSONObject()
                .put("posts", JSONArray(merged.values.toList())).toString()

            // (v34) Hostname-vs-IP parity with CircleFtpProvider. The
            // standalone only swaps *.circleftp.net hosts for their raw BDIX
            // IPs when the loaded URL itself carries the raw-IP API host
            // (urlCheck = !url.contains(apiUrl)) — and its grouped
            // "load?data=" URLs never contain the IP, so in practice the
            // standalone ALWAYS emits the hostname form
            // (http://ftp17.circleftp.net/…), which is exactly what the
            // site's own web player and the user's working standalone play.
            // v19–v33 rewrote the host to the raw IP UNCONDITIONALLY
            // (http://15.1.3.8/…) — the CDN doesn't serve those files on
            // vhost-less/IP requests on the user's ISP, so the player got a
            // 404 → HTTP 2004 while standalone/web played the same episode
            // clean (verified against post 102185 + the site's watch page:
            // every stream/download anchor uses the hostname form; no
            // 15.1.x.x IP appears anywhere).
            // Now: rewrite ONLY when the raw-IP API mirror actually answered
            // — the situation where the user's DNS can't resolve
            // circleftp.net and the IP is the working route.

            val postsArr = runCatching { JSONObject(searchText).optJSONArray("posts") }
                .getOrNull() ?: JSONArray()
            // (v70) empty search results NO LONGER bail here (see the
            // dead-rescue fix above) — identity/fuzzy/tier-3 simply find
            // nothing in an empty array and the category-browse tier below
            // still runs.

            // 2. Collect all posts, then keep only the ones that ARE the
            //    requested TMDB item.
            //
            //    FIX (v18): v17 collected EVERY post the search API returned
            //    and the "4b" zero-token check only dropped posts with no
            //    shared word — so "Scream" pulled in Scream 2/3/4/VI and
            //    anything else containing "scream", and their links were all
            //    merged into one video item. Posts are now identity-matched
            //    (tokens + year) against the TMDB title; franchise siblings
            //    and unrelated matches are dropped, while multiple posts of
            //    the SAME film (different encodes / cuts / audio variants)
            //    are kept. If NOTHING matches, we emit nothing from Circle
            //    FTP — wrong links are worse than missing links.
            val identityFiltered = mutableListOf<Pair<Int, String>>()
            // (v32) second-chance pool for loosely-decorated anime/BDIX
            // posts that the strict gate kills; used ONLY when tier 1 empty.
            val fuzzyFiltered = mutableListOf<Pair<Int, String>>()
            // (v90b) tier-3 franchise-rescue pool, now collected on EVERY
            // series pass (it used to be consulted only when both tiers
            // above came back empty — see the additive-union note below).
            val rescueFiltered = mutableListOf<Pair<Int, String>>()
            //
            //    FIX (v17): The previous code filtered posts by title similarity
            //    (score >= 0.5 AND pNorm.contains(qNorm)) which was TOO STRICT.
            //    This caused short-titled movies like "Dune", "Joker", "Inception"
            //    to be rejected because their post titles often have extra words
            //    (year, quality, "Extended Edition") that diluted the Jaccard
            //    similarity score. Only long titles like "Lord of the Rings:
            //    The Return of the King" had enough tokens to pass the filter.
            //
            //    The standalone CircleFtpProvider does NOT filter by similarity
            //    at all — it groups ALL posts returned by the search API and
            //    lets the user pick. We now do the same: collect every post
            //    the API returns, then filter by `type` after fetching details.
            //
            //    The search API itself does the relevance filtering server-side,
            //    so we trust its results. If the API returns 0 posts, we bail.
            for (i in 0 until postsArr.length()) {
                val p = postsArr.optJSONObject(i) ?: continue
                val ptitle = p.optString("title").ifBlank { p.optString("name") ?: "" }
                if (ptitle.isBlank()) continue
                // Year hint for the identity check: title year first, then the
                // post's date fields (API rows often carry them).
                val postYear = Regex("\\b(19|20)\\d{2}\\b").find(ptitle)?.value?.toIntOrNull()
                    ?: p.optStringOrNullCp("date")?.take(4)?.toIntOrNull()
                    ?: p.optStringOrNullCp("created_at")?.take(4)?.toIntOrNull()
                    ?: p.optStringOrNullCp("upload_date")?.take(4)?.toIntOrNull()
                val effectiveYear = year ?: postYear
                if (isSameMediaTitle(ptitle, title, effectiveYear)) {
                    identityFiltered += p.optInt("id", -1) to ptitle
                } else if (isFuzzySameMedia(ptitle, title, effectiveYear)) {
                    fuzzyFiltered += p.optInt("id", -1) to ptitle
                } else if (isSeriesAsk && isSeriesRescueMatch(ptitle, title)) {
                    rescueFiltered += p.optInt("id", -1) to ptitle
                }
            }
            // (v90b) TIER-3 RESCUE IS NOW ADDITIVE for series (was: only
            // when strict+fuzzy found nothing). A sibling post that passes
            // strict/fuzzy for a sequel title (the "Final Season"-named
            // re-uploads) silently dead-ends at the bucket stage — it has
            // no "Season 4" labels to satisfy a stacked Final-Season ask —
            // while its mere existence suppressed the rescue that would
            // have found the season-labeled mega post. Union the rescue
            // picks in (deduped by id, shortest-title-first, capped):
            // downstream per-post gates (v70 coverage guard, v63
            // label-first pools, v60 strict cours equality, v48
            // positional-title guard) still decide whether ANY post may
            // serve an episode, so an added rescue post can never emit a
            // wrong link — worst case it adds nothing.
            val basePicks = identityFiltered.ifEmpty { fuzzyFiltered }
            val rescueExtras = if (isSeriesAsk) {
                val already = basePicks.map { it.first }.toHashSet()
                rescueFiltered.asSequence()
                    .filter { it.first !in already }
                    .sortedBy { it.second.length }
                    .take(8)
                    .toList()
            } else {
                emptyList()
            }
            var matchingPostIds = basePicks + rescueExtras
            Log.d(
                TAG, "CircleFTP: '$title' s=$season e=$episode hit=$searchHit " +
                    "posts=${merged.size} identity=${identityFiltered.size} " +
                    "fuzzy=${fuzzyFiltered.size} " +
                    "rescue+${rescueExtras.size}/${rescueFiltered.size}"
            )

            // ── (v45) TIER 3: multi-season anime rescue ─────────────────
            // AniList files every anime season as a SEPARATE entry, so the
            // resolver receives sequel titles like "Demon Slayer:
            // Entertainment District Arc" while Circle FTP files the same
            // show as "Demon Slayer (TV Series 2019-) Anime [Dual Audio]"
            // (mega post) or "Demon Slayer Season 2" (per-season post) —
            // both die at the strict AND fuzzy gates above. When the site
            // IS requested for a series and nothing matched, accept posts
            // whose DECORATION-STRIPPED bare title contains the query
            // (forward) or is a clean prefix of it (reverse: the sequel's
            // subtitle is always a trailing decoration). Episodes are still
            // only ever taken from content seasons whose markers match the
            // requested season (the v11 guard below), so a rescued post can
            // never serve the wrong season — worst case it yields nothing.
            // (v90b) The old gated tier-3 block ("rescue ONLY when strict+
            // fuzzy found nothing") is gone: the same rescue test now runs
            // inside the collection pass above and its picks are UNIONED
            // into matchingPostIds for every series ask, empty base or
            // not. That gate was the exact mechanism that let a dead-ending
            // sequel re-upload suppress the season-labeled mega post.
            // ── (v48) Anime-category browse rescue ───────────────────────
            // The text search is opaque (not plain substring — final and
            // cours entries got zero rows despite correct titles). For
            // SERIES requests, walk the anime catalogue pages ourselves
            // and run the same strict→fuzzy→tier-3 gates client-side.
            if (matchingPostIds.isEmpty() && season != null && episode != null) {
                val (catPosts, catUsedIp) = animeCategoryPosts(app)
                ipRewriteLinks = ipRewriteLinks || catUsedIp
                if (catPosts.isNotEmpty()) {
                    val identity = mutableListOf<Pair<Int, String>>()
                    val fuzzy = mutableListOf<Pair<Int, String>>()
                    val rescue = mutableListOf<Pair<Int, String>>()
                    for (p in catPosts) {
                        val ptitle = p.optString("title").ifBlank { p.optString("name") ?: "" }
                        if (ptitle.isBlank()) continue
                        val postYear = Regex("\\b(19|20)\\d{2}\\b").find(ptitle)?.value?.toIntOrNull()
                        val effectiveYear = year ?: postYear
                        if (isSameMediaTitle(ptitle, title, effectiveYear)) {
                            identity += p.optInt("id", -1) to ptitle
                            continue
                        }
                        if (isFuzzySameMedia(ptitle, title, effectiveYear)) {
                            fuzzy += p.optInt("id", -1) to ptitle
                            continue
                        }
                        if (isSeriesRescueMatch(ptitle, title)) {
                            rescue += p.optInt("id", -1) to ptitle
                        }
                    }
                    matchingPostIds = identity.ifEmpty { fuzzy.ifEmpty { rescue } }
                    if (matchingPostIds.isNotEmpty()) {
                        Log.d(
                            TAG,
                            "CircleFTP: anime-category browse matched " +
                                "${matchingPostIds.size} post(s) for '$title' s=$season e=$episode"
                        )
                    }
                }
            }
            val srcLabel = "$labelPrefix • $LABEL"
            val mediaUrls = linkedSetOf<String>()

            // (v71) MAIN-SITE FIRST CHANCE (the Haikyuu case): every
            // new-API tier missed. The old WordPress site is tried BEFORE
            // declaring failure — its catalogue was never migrated into the
            // new API, so legacy mega posts (Haikyuu 2014-2020) exist only
            // there.
            var mainSiteTried = false
            if (matchingPostIds.isEmpty()) {
                mainSiteTried = true
                Log.i(
                    TAG, "CircleFTP: no new-site match for '$title' " +
                        "(year=$year) — trying main-site"
                )
                mediaUrls += mainSiteMediaUrls(app, title, year, isMovie, season, episode)
                if (mediaUrls.isEmpty()) {
                    Log.d(TAG, "CircleFTP: no match for '$title' (year=$year) — skipping")
                    return false
                }
            }

            // (v71) The whole new-API post pipeline (sections 3-6) is wrapped
            // so any dead end inside falls THROUGH to the main-site second
            // chance below instead of returning false outright (previously
            // matched-but-empty posts killed the resolve). It is skipped
            // entirely when no posts matched — the main-site rescue above
            // already ran in that case. (Inner body kept at its original
            // indentation on purpose, to keep this diff reviewable.)
            run {
                if (matchingPostIds.isEmpty()) return@run

            // 3. Fetch ALL matching post details concurrently. Each post's
            //    detail JSON contains:
            //      • type == "singleVideo"  → movie/direct video
            //      • type != "singleVideo"  → content[season-1].episodes[ep-1].link
            //    We aggregate links from EVERY matching post so users get
            //    multiple audio variants (subbed/dual/hindi) for the same ep.
            val postDetails = coroutineScope {
                matchingPostIds.distinctBy { it.first }.map { (id, _) ->
                    async(Dispatchers.IO) {
                        if (id < 0) return@async null
                        val text = fetchWithFallback(
                            app,
                            primary = "$PRIMARY_API/api/posts/$id",
                            fallback = "$FALLBACK_API/api/posts/$id",
                        )?.first ?: return@async null
                        runCatching { JSONObject(text) }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            Log.d(
                TAG, "CircleFTP: details=${postDetails.size} " +
                    "candidates=${matchingPostIds.map { it.first }}"
            )
            if (postDetails.isEmpty()) {
                Log.d(TAG, "CircleFTP: no details — bailing for '$title'")
                return@run
            }

            // 4. Filter posts by type ONLY for movies. For TV/anime, keep ALL
            //    posts (the pre-v11 behaviour that was working). This is
            //    safer because:
            //      • For movies (isMovie=true): we need only singleVideo
            //        posts, so filtering is correct.
            //      • For TV/anime (isMovie=false): the type field might be
            //        inconsistent or missing for some posts. Filtering here
            //        risks excluding valid posts. The TV path already
            //        handles both singleVideo and non-singleVideo posts
            //        gracefully (it checks content JSONArray vs String).
            val typeFiltered = if (isMovie) {
                postDetails.filter { it.optString("type", "") == "singleVideo" }
            } else {
                postDetails
            }
            if (typeFiltered.isEmpty()) return@run

            // (v18) identity matching happened before the detail fetches —
            // every post here is the requested title, so no relevance
            // re-check (and no "keep everything" fallback) is needed.
            val filteredDetails = typeFiltered
            if (filteredDetails.isEmpty()) return@run

            // 5. Route to movie or TV/anime path based on the user's request.
            if (isMovie) {
                // ── MOVIE PATH ──────────────────────────────────────────────
                // postObj.optString("content") returns the direct media URL
                // as a STRING for singleVideo posts. (Matches
                // CircleFtpProvider line 1230.)
                //
                // We use `detail.opt("content")` and check `is String`
                // to avoid emitting JSON-array-as-string garbage (which
                // happens if a post is mis-typed as singleVideo but has
                // a content array).
                filteredDetails.forEach { detail ->
                    val contentField = detail.opt("content")
                    if (contentField is String) {
                        contentField.takeIf { it.isNotBlank() }?.let { mediaUrls += it }
                    }
                    // Some posts put the URL in `url` / `file` / `src` instead.
                    detail.optStringOrNullCp("url")?.let { mediaUrls += it }
                    detail.optStringOrNullCp("file")?.let { mediaUrls += it }
                    detail.optStringOrNullCp("src")?.let { mediaUrls += it }
                }
            } else {
                // ── TV/ANIME PATH ───────────────────────────────────────────
                // Each post has content[seasonIndex].episodes[epIndex].link
                // We collect links from ALL matching posts for the requested
                // (season, episode) — this gives the user multiple audio
                // variants (subbed/dual/hindi-dubbed) just like the
                // standalone CircleFtpProvider does.
                //
                // Multi-season handling (FIX v11):
                //   • If a post has content[N] where N >= season, use
                //     content[season-1].episodes[episode-1].link
                //   • If a post is a season-specific separate post (e.g.
                //     "One Piece Season 2"), its content[] array usually
                //     has just 1 entry for that season. We use content[0]
                //     ONLY IF the post title's season number matches the
                //     requested season — otherwise we'd return the wrong
                //     season's episode.
                //   • If the post title has no "Season N" / "S<N>" marker
                //     AND content[season-1] doesn't exist, fall back to
                //     content[0] (single-season anime case).
                val seasonToUse = season ?: 1
                val episodeToUse = episode ?: 1

                // (v70) CROSS-POST SEASON COVERAGE (user report: "JJK got
                // two different entries in CircleFTP ... not mapping
                // correctly with the episodes"). JJK lives as TWO posts —
                // one carries ONLY "Season 1" (24 rows), the other ONLY
                // "Season 2" (E0-E23) — and both identity-match the show,
                // so both used to contribute to EVERY request: for a
                // Season-1 ask, the Season-2 post's positional fallback
                // served S2E(N-1) next to the correct S1 link; for a
                // Season-2 ask, the S1 post mirrored its Season-1 rows as
                // "season 2". Rule now: when the requested season is
                // covered by SOME post's labeled blocks, posts whose
                // blocks are labeled but DON'T cover it are skipped —
                // their positional path could only ever serve the wrong
                // season. Posts with NO labels at all keep their legacy
                // positional chance (unlabeled mega posts, v11 doctrine).
                val perPostBlocks = filteredDetails.map { blocksWithSeasons(it) }
                val perPostSeasons = perPostBlocks.map { blocks ->
                    blocks.mapNotNull { it.second }.toSet()
                }
                val seasonCoveredElsewhere = perPostSeasons.any { seasonToUse in it }
                Log.d(
                    TAG, "CircleFTP: season-map per post = " +
                        "${perPostSeasons.map { it.toList() }} need=$seasonToUse " +
                        "covered=$seasonCoveredElsewhere"
                )

                filteredDetails.forEachIndexed { postIdx, detail ->
                    val labelBlocks = perPostBlocks[postIdx]
                    val labeledSeasons = perPostSeasons[postIdx]
                    if (seasonCoveredElsewhere && labeledSeasons.isNotEmpty() &&
                        seasonToUse !in labeledSeasons
                    ) {
                        Log.d(
                            TAG, "CircleFTP: post ${detail.optInt("id", -1)} has " +
                                "seasons $labeledSeasons — no season $seasonToUse " +
                                "(covered cross-post, v70) — skipped"
                        )
                        return@forEachIndexed
                    }
                    val contentArray = detail.optJSONArray("content")
                    if (contentArray == null || contentArray.length() == 0) return@forEachIndexed

                    // Determine the post's season number from its title.
                    // "One Piece Season 2" → 2; "One Piece S3" → 3; "One Piece" → null.
                    val postTitleStr = detail.optString("title", "")
                        .ifBlank { detail.optString("name", "") }
                    val titleSeasonNum = extractSeasonFromTitle(postTitleStr)

                    // (v63) LABEL-FIRST season selection (v70: the label
                    // walk itself is hoisted into blocksWithSeasons and
                    // shared with the coverage guard above) — positional
                    // content[season-1] breaks the moment a site splits one
                    // season into cours blocks: with [S1][S2][S3-P1][S3-P2]
                    // [S4], "Season 4" rows sit at array index 3 where
                    // content[season-1] points — the request then silently
                    // pulls S3-Part-2's rows (wrong episodes), and stacked
                    // Part-2 numbers (13-22) fell off the 12-row Part-1
                    // block entirely ("S3 Part 2 fetches NOTHING"). Every
                    // content block on this API carries a season label
                    // (proven by the saved post-102185 detail JSON), so the
                    // labels decide: blocks of the requested season pool IN
                    // ORDER, and — the same recursive cours doctrine the
                    // episode-table mapper uses — a "Part N"-styled block
                    // with no season number of its own CONTINUES the season
                    // of the block before it.
                    val seasonBlocks =
                        labelBlocks.filter { it.second == seasonToUse }.map { it.first }
                    if (seasonBlocks.isNotEmpty()) {
                        // Entry-local aim: when the queried TITLE itself
                        // names a part ("Attack on Titan Season 3 Part 2" →
                        // part 2), numbers 1..N belong to THAT cours block —
                        // the entry-local pass lands there exactly. Stacked
                        // numbers (13-22 on a 10-row part) overshoot and
                        // fall through to the pooled index below.
                        val partAsk = Regex("(?i)\\bpart\\s*(\\d{1,2})\\b")
                            .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        if (partAsk != null && partAsk in 1..seasonBlocks.size) {
                            val pb = rowsOfBlock(seasonBlocks[partAsk - 1])
                            // (v70) number-true pick inside the cours block
                            // too — stray E0-style rows shift position.
                            val (prow, pmode) = pickPoolRow(pb, episodeToUse)
                            val link = prow?.let { rowLink(it) }
                            if (!link.isNullOrEmpty()) {
                                Log.d(
                                    TAG, "CircleFTP: post ${detail.optInt("id", -1)} " +
                                        "part-aim hit (part $partAsk, pick=$pmode) " +
                                        "ep=$episodeToUse"
                                )
                                mediaUrls += link
                                return@forEachIndexed
                            }
                        }
                        val pool = seasonBlocks.flatMap { rowsOfBlock(it) }
                        val (row, pickMode) = pickPoolRow(pool, episodeToUse)
                        Log.d(
                            TAG, "CircleFTP: post ${detail.optInt("id", -1)} " +
                                "labels=${labelBlocks.map { it.second }} pool=${pool.size} " +
                                "ep=$episodeToUse partAsk=$partAsk pick=$pickMode"
                        )
                        val link = row?.let { rowLink(it) }
                        if (!link.isNullOrEmpty()) mediaUrls += link
                        return@forEachIndexed
                    }

                    Log.d(
                        TAG, "CircleFTP: post ${detail.optInt("id", -1)} has no " +
                            "season-$seasonToUse labels — positional path " +
                            "(blocks=${contentArray.length()}, ep=$episodeToUse)"
                    )
                    // Positional fallback (labels absent on this post) —
                    // original v11 layout, unchanged.
                    var seasonObj = contentArray.optJSONObject(seasonToUse - 1)
                    var episodesArray = seasonObj?.optJSONArray("episodes")

                    // Fallback: if content[season-1] doesn't exist or has no
                    // episodes, try content[0] — but ONLY when:
                    //   • The post title explicitly says "Season N" matching
                    //     the requested season (so content[0] IS the
                    //     requested season), OR
                    //   • The post title has no "Season N" marker AND the
                    //     post holds exactly ONE season bucket (a genuine
                    //     single-season post). (v48: the mark-less mega
                    //     posts — "Attack on Titan (TV Series 2013-)" —
                    //     previously served Season 1's episodes for ANY
                    //     missing season bucket, silently wrong.)
                    if (episodesArray == null || episodesArray.length() == 0) {
                        val canFallbackToContent0 = titleSeasonNum == seasonToUse ||
                            (titleSeasonNum == null && contentArray.length() == 1)
                        if (canFallbackToContent0) {
                            seasonObj = contentArray.optJSONObject(0)
                            episodesArray = seasonObj?.optJSONArray("episodes")
                        }
                    }

                    // (v60) Labeled cours-pool rescue: some posts split
                    // ONE site season over several content blocks (e.g.
                    // "Season 3" (12) + "Season 3 Part 2" (10)) while the
                    // stacked request counts them as one season of 22 —
                    // ep 13 falls off the first block. When direct
                    // indexing misses, pool IN ORDER the episode rows of
                    // every block whose season label equals the requested
                    // season (strict equality — wrong-season protection
                    // unchanged) and index into the pool.
                    if (episodesArray == null || episodeToUse > episodesArray.length()) {
                        val pool = mutableListOf<org.json.JSONObject>()
                        var poolSawLabel = false
                        for (ci in 0 until contentArray.length()) {
                            val block = contentArray.optJSONObject(ci) ?: continue
                            val blockEps = block.optJSONArray("episodes") ?: continue
                            val label = block.optStringOrNullCp("seasonName")
                                ?: block.optStringOrNullCp("season_name")
                                ?: block.optStringOrNullCp("title")
                            val labelSeason = label?.let { extractSeasonFromTitle(it) }
                            val belongs = when {
                                labelSeason != null -> {
                                    poolSawLabel = true
                                    labelSeason == seasonToUse
                                }
                                ci == seasonToUse - 1 -> true   // unlabeled standard slot
                                else -> false
                            }
                            if (belongs) for (ei in 0 until blockEps.length()) {
                                blockEps.optJSONObject(ei)?.let { pool += it }
                            }
                        }
                        if (poolSawLabel && pool.size > (episodesArray?.length() ?: 0)) {
                            // (v70) number-true pick here too.
                            val (vrow, vmode) = pickPoolRow(pool, episodeToUse)
                            val link = vrow?.let { rowLink(it) }
                            if (!link.isNullOrEmpty()) {
                                Log.d(
                                    TAG, "CircleFTP: post ${detail.optInt("id", -1)} " +
                                        "cours-pool rescue hit (pick=$vmode) ep=$episodeToUse"
                                )
                                mediaUrls += link
                                return@forEachIndexed
                            }
                        }
                    }

                    if (episodesArray != null && episodeToUse in 1..episodesArray.length()) {
                        val rows = (0 until episodesArray.length())
                            .mapNotNull { episodesArray.optJSONObject(it) }
                        // (v70) number-true pick on the plain positional
                        // path as well — unlabeled posts whose rows still
                        // carry SxEy names get exact-number service.
                        val (erow, _) = pickPoolRow(rows, episodeToUse)
                        val link = erow?.let { rowLink(it) }
                        if (link != null && link.isNotEmpty()) {
                            mediaUrls += link
                        }
                    }
                }

                // (v33) REMOVED the old "dump every episode link from every
                // season" last resort. When the requested (season, episode)
                // is not in the post, emitting a random season's links meant
                // the user silently got the WRONG episodes (and the dead CDN
                // boxes among them produced the HTTP 2004/timeout wave the
                // standalone never shows — the standalone has no such
                // fallback; it lists seasons and lets you pick the real one).
            }

            // 6. Also pick up any direct download links arrays.
            filteredDetails.forEach { detail ->
                detail.optJSONArray("downloadLinks")?.let { dlArr ->
                    for (i in 0 until dlArr.length()) {
                        val dl = dlArr.optJSONObject(i) ?: continue
                        val u = dl.optStringOrNullCp("url") ?: dl.optStringOrNullCp("link")
                        if (u != null && u.isNotBlank()) mediaUrls += u
                    }
                }
            }

            }  // end new-API pipeline (v71 fall-through wrapper)

            // (v71) MAIN-SITE SECOND CHANCE: the new API DID have matching
            // posts, but none yielded this exact (season, episode) — e.g.
            // a half-migrated re-upload whose missing seasons are still
            // only on the old site. Same rescue, one attempt.
            if (mediaUrls.isEmpty() && !mainSiteTried) {
                mainSiteTried = true
                Log.i(
                    TAG, "CircleFTP: new-site pipeline empty for '$title' " +
                        "s=$season e=$episode — trying main-site"
                )
                mediaUrls += mainSiteMediaUrls(app, title, year, isMovie, season, episode)
            }

            // (v74) ONE compact WARN verdict per fully-exhausted resolve —
            // survives a quick filtered logcat skim and immediately tells
            // WHICH show/episode exhausted both site tiers (the D-lines
            // above carry the per-stage detail when they can be captured).
            if (mediaUrls.isEmpty()) {
                Log.w(
                    TAG, "CircleFTP: DRY '$title' s=$season e=$episode — " +
                        "new-site tiers + main-site exhausted"
                )
            }

            // 7. Emit — v33: 1:1 with CircleFtpProvider.loadLinks.
            //    Every link goes out as a PLAIN link: no referer, no extra
            //    headers, no player-page probing, no payload fetch. The old
            //    version routed raw URLs through emitDirect, which (a)
            //    attached Referer/headers the CDN rejects (→ HTTP 2004 in
            //    Wizstream while the standalone, which attaches nothing,
            //    played fine) and (b) fetched m3u8 playlists up-front via
            //    generateM3u8, so a slow/dead CDN box surfaced as a load-time
            //    timeout instead of a player decision.
            //    (v83) The v76 pre-emit liveness probe/prune is REMOVED at
            //    user request — links are emitted exactly as discovered,
            //    zero pre-checks, zero added latency.
            Log.d(
                TAG, "CircleFTP: emit ${mediaUrls.size} url(s) for " +
                    "'$title' s=$season e=$episode"
            )
            var any = false
            mediaUrls.forEach { u ->
                if (u.contains("movie?data=") || u.contains("episode?data=") ||
                    u.contains("circleftp://")
                ) {
                    if (emitCircleFtpEncoded(u, srcLabel, ipRewriteLinks, callback)) any = true
                } else {
                    val resolvedUrl = if (ipRewriteLinks) linkToIp(u) else u
                    if (resolvedUrl.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                source = srcLabel,
                                name = srcLabel,
                                url = resolvedUrl,
                            ) {
                                this.quality = qualityFromName(resolvedUrl)
                            }
                        )
                        any = true
                    }
                }
            }
            return any
        }

        /**
         * Extract a season number from a post title.
         * Returns null if the title has no season marker.
         *
         * Examples:
         *   "One Piece Season 2" → 2
         *   "One Piece S3"       → 3
         *   "One Piece Season 2 [Hindi Dubbed]" → 2
         *   "One Piece"          → null
         */
        private fun extractSeasonFromTitle(title: String): Int? {
            // (v47) Match ordinal wording first: "Haikyuu 2nd Season" → 2.
            Rx.SEASON_ORDINAL_RE.find(title)?.let {
                return it.groupValues.getOrNull(1)?.toIntOrNull()
            }
            // Match "Season N" or "SeasonN" (case-insensitive).
            Rx.SEASON_WORD_RE.find(title)?.let {
                return it.groupValues.getOrNull(1)?.toIntOrNull()
            }
            // Match "S<N>" but not "S" alone or "s03e15" (which is episode).
            // We require a word boundary before S and the number to be 1-2 digits.
            Rx.SEASON_S_RE.find(title)?.let {
                return it.groupValues.getOrNull(1)?.toIntOrNull()
            }
            return null
        }

        /** Episode-row link extractor — the API keys it as "link" (post
         *  102185's saved detail JSON), but tolerate url/file/src too. */
        private fun rowLink(o: org.json.JSONObject): String? =
            o.optStringOrNullCp("link") ?: o.optStringOrNullCp("url")
                ?: o.optStringOrNullCp("file") ?: o.optStringOrNullCp("src")

        /** (v70) Label each content block of a post detail with the season
         *  number its own label declares. A "Part N"-styled block with no
         *  season number of its own CONTINUES the season of the block
         *  before it (the recursive cours doctrine — proven needed by
         *  post 102185's saved JSON where blocks arrive as
         *  "Season 3"(12) + "Season 3 Part 2"(10)). */
        internal fun blocksWithSeasons(
            detail: org.json.JSONObject,
        ): List<Pair<org.json.JSONObject, Int?>> {
            val contentArray = detail.optJSONArray("content") ?: return emptyList()
            val partLabelRx = Regex(
                "(?i)\\b(?:part|cour)\\s*\\.?\\s*\\d{1,2}\\b|\\bpt\\s*\\.?\\s*\\d{1,2}\\b|\\bp\\d{1,2}\\b"
            )
            val out = ArrayList<Pair<org.json.JSONObject, Int?>>()
            var lastLabelSeason: Int? = null
            for (ci in 0 until contentArray.length()) {
                val b = contentArray.optJSONObject(ci) ?: continue
                val lbl = b.optStringOrNullCp("seasonName")
                    ?: b.optStringOrNullCp("season_name")
                    ?: b.optStringOrNullCp("title")
                var bs = lbl?.let { extractSeasonFromTitle(it) }
                if (bs == null && lbl != null && partLabelRx.containsMatchIn(lbl)) {
                    bs = lastLabelSeason
                }
                if (bs != null) lastLabelSeason = bs
                out += b to bs
            }
            return out
        }

        internal fun rowsOfBlock(b: org.json.JSONObject): List<org.json.JSONObject> {
            val a = b.optJSONArray("episodes") ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }

        /** (v70) The episode number a row DECLARES for itself — the site
         *  tags rows "Attack On Titan.S:4E:27", "Jujutsu Kaisen.S:2E:0",
         *  "[SubsPlease] Jujutsu Kaisen S2 E0 (1080p)" — read from the
         *  row's name/label fields first, then the decoded link filename.
         *  Returns null when the row carries no explicit number (KaiDubs
         *  "S4 - 01 (60)" batches) — callers stay positional there. */
        private fun rowEpisodeNumber(o: org.json.JSONObject): Int? {
            val texts = ArrayList<String>()
            o.optStringOrNullCp("name")?.let { texts += it }
            o.optStringOrNullCp("title")?.let { texts += it }
            o.optStringOrNullCp("label")?.let { texts += it }
            o.optStringOrNullCp("episode")?.let { texts += it }
            rowLink(o)?.let { u ->
                runCatching { java.net.URLDecoder.decode(u, "UTF-8") }
                    .getOrNull()?.let { texts += it }
            }
            val seRx = Regex("(?i)\\bS\\d{1,2}\\s*[-_. ]?E\\s*:?\\s*(\\d{1,4})\\b")
            val sColonERx = Regex("(?i)\\bS\\s*:\\s*\\d{1,2}\\s*E\\s*:\\s*(\\d{1,4})\\b")
            val epWordRx = Regex("(?i)\\bEpisode\\s*:?\\s*(\\d{1,4})\\b")
            for (t in texts) {
                seRx.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
                sColonERx.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            }
            for (t in texts) {
                epWordRx.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            }
            return null
        }

        /** (v70) NUMBER-TRUE row pick. When ≥ half of the pooled rows
         *  declare parseable episode numbers, the requested episode is
         *  served by NUMBER — never by position. The JJK Season-2 bucket
         *  starts at row "E:0" (a stray special), so positional E1 → the
         *  special's video and EVERY episode shifted by one; number-true
         *  E1 → the row labeled E:1. Rows that don't parse keep the old
         *  positional read (unchanged v11-69 behaviour for KaiDubs-style
         *  batches). */
        internal fun pickPoolRow(
            pool: List<org.json.JSONObject>,
            episodeToUse: Int,
        ): Pair<org.json.JSONObject?, String> {
            if (pool.isEmpty()) return null to "empty"
            val nums = pool.map { rowEpisodeNumber(it) }
            val parsed = nums.count { it != null }
            if (parsed * 2 >= pool.size) {
                pool.indices.firstOrNull { nums[it] == episodeToUse }
                    ?.let { return pool[it] to "num" }
            }
            return pool.getOrNull(episodeToUse - 1) to "pos"
        }

        private suspend fun emitCircleFtpEncoded(
            data: String,
            sourceLabel: String,
            ipRewrite: Boolean,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val raw = data.substringAfter("data=").substringBefore("&")
                .substringBefore(" ").trim()
            if (raw.isBlank()) return false
            val cleaned = raw.removePrefix("circleftp://")
            val jsonStr = runCatching {
                String(Base64.getDecoder().decode(cleaned))
            }.getOrNull() ?: return false
            val arr = runCatching { JSONArray(jsonStr) }.getOrNull() ?: return false
            if (arr.length() == 0) return false
            var any = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url").ifBlank { o.optString("link") }
                if (u.isBlank()) continue
                // (v34) Only rewrite to the BDIX IP when the raw-IP API
                // mirror served this resolve (see the v34 note above).
                val resolvedUrl = if (ipRewrite) linkToIp(u) else u
                val audio = o.optString("audio").takeIf { it.isNotBlank() }
                val name = if (audio != null) "$sourceLabel [$audio]" else sourceLabel
                // (v33) Standalone parity: no referer, no headers, no explicit
                // type — the player infers HLS/mp4 itself. (The old
                // referer=SITE was what made the CDN answer 403/404 = player
                // HTTP 2004 while the standalone played the same URL clean.)
                val link = newExtractorLink(
                    source = sourceLabel,
                    name = name,
                    url = resolvedUrl,
                ) {
                    this.quality = qualityFromName(resolvedUrl)
                }
                callback(link)
                any = true
            }
            return any
        }

        /**
         * (v34) Returns (body, usedFallback). The usedFallback flag drives
         * the hostname-vs-IP decision downstream — see the v34 note at the
         * search call site.
         *
         * CRITICAL: verify=false and cacheTime=60 are REQUIRED for the
         * CircleFTP API to work — the standalone CircleFtpHttp uses
         * these exact flags. Without verify=false, the API returns
         * empty/error responses on BDIX networks. Without cacheTime,
         * every request hits the server (no caching).
         */
        private suspend fun fetchWithFallback(
            app: Requests,
            primary: String,
            fallback: String,
        ): Pair<String, Boolean>? {
            val a = runCatching {
                app.get(primary, headers = HEADERS, verify = false, cacheTime = 60, timeout = 10_000)
            }.getOrNull()
            if (a != null && a.code in 200..299 && a.text.isNotBlank()) return a.text to false
            val b = runCatching {
                app.get(fallback, headers = HEADERS, verify = false, cacheTime = 60, timeout = 10_000)
            }.getOrNull()
            if (b != null && b.code in 200..299 && b.text.isNotBlank()) return b.text to true
            return null
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 4: CTGMovies  (https://ctgmovies.com)
    //  Already worked in v2 — unchanged.
    // ════════════════════════════════════════════════════════════════════════

    internal object CtgMoviesResolver : SourceResolver {
        private const val SITE = "https://ctgmovies.com"
        private const val DEFAULT_API_BASE = "https://cockpit.103.109.92.178.nip.io/api/v1"
        private const val LABEL = "CTGMovies"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Origin" to SITE,
            "Accept" to "application/json",
            "Accept-Language" to "en",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            val kinds = if (isMovie) listOf("movies", "tv", "anime") else listOf("tv", "anime", "movies")
            val params = mapOf("search" to title)
            val candidates = mutableListOf<Triple<String, String, String>>()

            coroutineScope {
                kinds.map { kind ->
                    async(Dispatchers.IO) {
                        runCatching {
                            val text = apiGet(app, "/$kind", params)
                            parseSearchResults(text, kind)?.let { list ->
                                synchronized(candidates) { candidates.addAll(list) }
                            }
                        }
                    }
                }.awaitAll()
            }

            if (candidates.isEmpty()) return false

            // (v20) REVERTED to the pre-v18 fuzzy pick — CTG's API often has
            // missing/mismatched years, so the v18 identity+year gate rejected
            // posts that used to resolve fine.
            val best = candidates
                .maxByOrNull { (_, _, ct) -> titleSimilarity(ct, title) }
                ?: return false
            if (titleSimilarity(best.third, title) < 0.4) return false

            val detailText = apiGet(app, "/${best.first}/${encodeUrl(best.second)}", emptyMap())
            val detail = runCatching { JSONObject(detailText) }.getOrNull() ?: return false

            val srcLabel = "$labelPrefix • $LABEL"
            val linksArr = detail.optJSONArray("links") ?: JSONArray().also { it.put(detail) }

            var any = false
            for (i in 0 until linksArr.length()) {
                val link = linksArr.optJSONObject(i) ?: continue
                if (link.optBoolean("broken", false)) continue
                val rawUrl = link.optString("hls_url").ifBlank {
                    link.optString("url")
                }.ifBlank {
                    link.optString("file")
                }.ifBlank {
                    link.optString("src")
                }.ifBlank {
                    link.optString("link")
                }.ifBlank { continue }

                if (emitDirect(app, rawUrl, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)) {
                    any = true
                }
            }

            if (!isMovie && season != null && episode != null) {
                // (v32) CTG serves episodes in TWO shapes depending on the
                // catalogue (verified live 2026-07-24):
                //   • /tv and /anime detail → FLAT "episodes":[{season_number,
                //     episode_number, links:[{url, hls_url?, quality}]}, …]
                //     (the legacy seasons[] tree is metadata-only there)
                //   • older shape → seasons[]→episodes[]→links[] tree
                // Handle the flat array first (it is what real /tv and
                // /anime responses carry today), keep the tree as fallback —
                // this is why CTG worked for movies but never for TV/anime.
                val flat = detail.optJSONArray("episodes")
                if (flat != null && flat.length() > 0) {
                    val seasonEps = (0 until flat.length())
                        .mapNotNull { flat.optJSONObject(it) }
                        .filter { ep ->
                            val s2 = ep.optInt("season_number", 0)
                            // season_number==0/absent → single-season entry;
                            // accept it only when season 1 was requested.
                            s2 == season || (season == 1 && s2 == 0)
                        }
                    val epObj = seasonEps.firstOrNull { ep ->
                        ep.optInt("episode_number", 0) == episode
                    } ?: seasonEps.getOrNull(episode - 1)
                    epObj?.optJSONArray("links")?.let { epLinks ->
                        for (i in 0 until epLinks.length()) {
                            val link = epLinks.optJSONObject(i) ?: continue
                            val u = link.optString("hls_url").ifBlank {
                                link.optString("url")
                            }.ifBlank { link.optString("file") }
                                .ifBlank { link.optString("src") }
                            if (u.isNotBlank() &&
                                emitDirect(app, u, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)
                            ) any = true
                        }
                    }
                } else {
                    detail.optJSONArray("seasons")?.let { seasonsArr ->
                        for (si in 0 until seasonsArr.length()) {
                            val seasonObj = seasonsArr.optJSONObject(si) ?: continue
                            val sn = seasonObj.optInt("season",
                                seasonObj.optInt("season_number", si + 1))
                            if (sn != season) continue
                            val epsArr = seasonObj.optJSONArray("episodes") ?: continue
                            val epObj = epsArr.optJSONObject(episode - 1) ?: continue
                            val epLinks = epObj.optJSONArray("links") ?: continue
                            for (i in 0 until epLinks.length()) {
                                val link = epLinks.optJSONObject(i) ?: continue
                                val u = link.optString("hls_url").ifBlank {
                                    link.optString("url")
                                }.ifBlank { link.optString("file") }
                                    .ifBlank { link.optString("src") }
                                if (u.isNotBlank() &&
                                    emitDirect(app, u, srcLabel, "$SITE/", HEADERS, subtitleCallback, callback)
                                ) any = true
                            }
                        }
                    }
                }
            }
            return any
        }

        private fun parseSearchResults(text: String, kind: String): List<Triple<String, String, String>>? {
            // (v32) The CTG API is inconsistent per catalogue: /movies wraps
            // hits in an envelope ({"page":…,"movies":[…]}) while /tv and
            // /anime return a BARE JSON ARRAY at the top level. The old
            // object-only parse therefore returned null for every TV/anime
            // search and CTG silently worked for movies only.
            val arr: JSONArray = runCatching { JSONArray(text) }.getOrNull()
                ?: runCatching { JSONObject(text) }.getOrNull()?.let { obj ->
                    obj.optJSONArray("data") ?: obj.optJSONArray("results")
                        ?: obj.optJSONArray(kind)
                } ?: return null
            val out = mutableListOf<Triple<String, String, String>>()
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val id = it.optString("id").ifBlank { it.optString("slug") }
                    .ifBlank { it.optString("_id") }
                val title = it.optString("title").ifBlank { it.optString("name") }
                if (id.isNotBlank() && title.isNotBlank()) out += Triple(kind, id, title)
            }
            return out
        }

        private suspend fun apiGet(app: Requests, path: String, query: Map<String, Any?>): String {
            val p = if (path.startsWith("/")) path else "/$path"
            val qs = if (query.isEmpty()) "" else "?" + query.entries
                .filter { it.value != null }
                .joinToString("&") { (k, v) ->
                    "${encodeUrl(k)}=${encodeUrl(v.toString())}"
                }
            val primary = "$DEFAULT_API_BASE$p$qs"

            val r1 = runCatching {
                app.get(primary, headers = HEADERS, timeout = 10_000)
            }.getOrNull()
            if (r1 != null && r1.code in 200..299 && r1.text.isNotBlank()) return r1.text

            val fallback = "$SITE/api/v1$p$qs"
            val r2 = runCatching {
                app.get(fallback, headers = HEADERS, timeout = 10_000)
            }.getOrNull()
            if (r2 != null && r2.code in 200..299 && r2.text.isNotBlank()) return r2.text

            return ""
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 5: Cineby  (https://www.cineby.at)
    //
    //  Ported from the Aniyomi Cineby extension (Wizdier-AniRepo).
    //  Cineby is a Next.js SPA that delegates video resolution to a
    //  Videasy/Insertunit backend at api.speedracelight.com.
    //
    //  v30 flow (site rebuilt June 2026 — reverse-engineered from cineby.at):
    //    1. GET /seed?mediaId={tmdbId} → {seed, ttlMs}
    //    2. GET /mbx/sources-with-title?...&enc=2&seed={seed}  (aggregate: ALL
    //       servers in ONE response — the site's own player calls exactly this)
    //    3. Decrypt the base64url payload LOCALLY with the "mvm1" stream cipher
    //       ported 1:1 from the site's JS (the enc-dec.app service is retired).
    //    Fallback: the old per-server /{path}/sources-with-title endpoints are
    //       still alive (used by the site's watch-party) — same local decrypt.
    //
    //  8 servers on api.speedracelight.com:
    //    Neon    (neon2)     — Original audio, movies + TV
    //    Yoru    (cdn)       — Original audio, movies + TV (v19), may have 4K
    //    Breach  (m4uhd)     — Original audio, movies + TV
    //    Vyse    (hdmovie)   — English quality filter
    //    Killjoy (meine)     — German audio
    //    Fade    (hdmovie)   — Hindi quality filter
    //    Omen    (lamovie)   — Spanish audio
    //    Raze    (superflix) — Portuguese audio
    //
    //  CRITICAL for HTTP 2004/3003 prevention:
    //    • Send Referer+Origin headers on EVERY request to api.speedracelight.com
    //    • Set ExtractorLink.referer = "https://www.cineby.at/" on emitted links
    //    • Filter out non-http URLs and HTML bodies before emitting (prevents 3003)
    //    • Force-rewrite http:// → https:// (prevents 2007 cleartext error)
    //    • Title must be DOUBLE percent-encoded
    //    • Always pass &enc=2 and &seed=...
    // ════════════════════════════════════════════════════════════════════════

    internal object CinebyResolver : SourceResolver {
        private const val SITE = "https://www.cineby.at"
        private const val API_BASE = "https://api.speedracelight.com"
        private const val SUBS_API = "https://subs.videasy.to/search"
        private const val TMDB_PROXY = "https://db.speedracelight.com/3"
        private const val LABEL = "Cineby"

        // TMDB API key — same as the one used in WizstreamProvider.
        // Used only for the year lookup when ctx.year is null.
        private const val TMDB_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"

        private val API_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Origin" to SITE,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
        )

        private data class CinebyServer(
            val displayName: String,
            val path: String,
            val language: String? = null,
            val movieOnly: Boolean = false,
            val audioLabel: String? = null,
            val qualityFilter: String? = null,
        )

        private val SERVERS = listOf(
            // (v90c) Neon dropped — its /neon2 route was removed server-side
            // alongside /mbx (404, verified 2026-07-31). Dead upstreams
            // (hdmovie/superflix currently 500) stay: they fail quietly
            // under the bounded fan-out and recover when their hosts heal.
            // (v19) Yoru serves TV series too — verified against the live API
            // (mediaType=tv returns sources+subtitles). The old movieOnly flag
            // was the only reason it never appeared for series.
            CinebyServer("Yoru",    "cdn",      audioLabel = "Original"),
            CinebyServer("Breach",  "m4uhd",    audioLabel = "Original"),
            CinebyServer("Vyse",    "hdmovie",  qualityFilter = "English", audioLabel = "Original"),
            CinebyServer("Killjoy", "meine",    language = "german", audioLabel = "German"),
            CinebyServer("Fade",    "hdmovie",  qualityFilter = "Hindi",  audioLabel = "Hindi"),
            CinebyServer("Omen",    "lamovie",  audioLabel = "Spanish"),
            CinebyServer("Raze",    "superflix",audioLabel = "Portuguese"),
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            // Cineby REQUIRES a TMDB ID — without it we can't call the API.
            if (tmdbId == null) return false
            val srcLabel = "$labelPrefix • $LABEL"
            val seasonId = if (isMovie) "1" else (season?.toString() ?: "1")
            val episodeId = if (isMovie) "1" else (episode?.toString() ?: "1")
            val mediaType = if (isMovie) "movie" else "tv"

            // If year is null, look it up via TMDB proxy so the backend can
            // disambiguate (improves hit-rate).
            val yearStr = year?.toString() ?: fetchYear(app, tmdbId, mediaType) ?: ""

            // If IMDB ID wasn't provided, look it up via TMDB external_ids.
            val imdbIdStr = imdbId?.takeIf { it.isNotBlank() }
                ?: fetchImdbId(app, tmdbId, mediaType) ?: ""

            // ── v30: /mbx aggregate first, per-server endpoints as fallback ──
            var seed = fetchSeed(app, tmdbId)
            if (seed.isBlank()) {
                Log.d(TAG, "Cineby: seed fetch failed for tmdbId=$tmdbId")
                return false
            }
            suspend fun refreshSeed(): Boolean {
                val s = fetchSeed(app, tmdbId)
                if (s.isBlank()) return false
                seed = s
                return true
            }

            var any = false
            // (v90c) The /mbx aggregate endpoint was RETIRED server-side —
            // a fresh-seed call now answers 404 "Route GET:/mbx/sources-
            // with-title … not found" (verified 2026-07-31). The per-server
            // fan-out below is no longer a fallback, it IS the flow — which
            // matches the site's own BV() behaviour anyway, since its
            // fallback was always this same fan-out.
            if (true) {
                // Videasy subtitle search runs ONCE per media item (it used
                // to ride the mbx call — kept, now on the fan-out path so
                // the [Videasy] tracks aren't lost with the dead route).
                runCatching {
                    fetchVideasySubs(
                        app, tmdbId, isMovie, seasonId, episodeId, subtitleCallback,
                    )
                }
                val eligible = SERVERS.filter { !it.movieOnly || isMovie }
                val gate = Semaphore(3)
                val refreshedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
                coroutineScope {
                    eligible.map { server ->
                        async(Dispatchers.IO) {
                            gate.withPermit {
                                suspend fun callServer(): Boolean = resolveOneServer(
                                    app, server, tmdbId, seed, title, yearStr, imdbIdStr,
                                    mediaType, seasonId, episodeId,
                                    srcLabel, subtitleCallback, callback,
                                )
                                try {
                                    callServer()
                                } catch (e: SeedInvalidException) {
                                    if (refreshedOnce.compareAndSet(false, true)) refreshSeed()
                                    runCatching { callServer() }.getOrDefault(false)
                                } catch (e: Exception) {
                                    Log.d(TAG, "Cineby: server ${server.displayName} failed: ${e.message}")
                                    false
                                }
                            }
                        }
                    }.awaitAll().forEach { if (it) any = true }
                }
            }
            if (!any) {
                Log.d(TAG, "Cineby: all endpoints failed for tmdbId=$tmdbId title=$title")
            }
            return any
        }

        /**
         * Fetch the seed ONCE per media item. The seed is valid for 30 seconds
         * and is the same for all 8 servers (same apiBase + mediaId).
         * Uses cacheTime=0 to prevent Cloudstream from caching the seed
         * response across different media items.
         */
        private suspend fun fetchSeed(app: Requests, tmdbId: Int): String {
            val seedUrl = "$API_BASE/seed?mediaId=$tmdbId"
            val resp = runCatching {
                app.get(seedUrl, headers = API_HEADERS, cacheTime = 0, timeout = 10_000)
            }.getOrNull() ?: return ""
            if (resp.code !in 200..299 || resp.text.isBlank()) return ""
            return runCatching {
                JSONObject(resp.text).optString("seed", "")
            }.getOrDefault("")
        }

        /** Build the /sources-with-title query string for one endpoint path
         * ("mbx" aggregate or a per-server path like "neon2"). enc=2/seed are
         * appended by fetchAndDecrypt. */
        private fun buildSourcesQuery(
            path: String,
            tmdbId: Int,
            titleDoubled: String,
            mediaType: String,
            yearStr: String,
            imdbIdStr: String,
            seasonId: String,
            episodeId: String,
            language: String?,
        ): String = buildString {
            append(API_BASE).append('/').append(path).append("/sources-with-title")
            append("?title=").append(titleDoubled)
            append("&mediaType=").append(mediaType)
            append("&year=").append(encodeUrl(yearStr))
            append("&episodeId=").append(episodeId)
            append("&seasonId=").append(seasonId)
            append("&tmdbId=").append(tmdbId)
            if (imdbIdStr.isNotBlank()) append("&imdbId=").append(encodeUrl(imdbIdStr))
            language?.let { append("&language=").append(it) }
        }

        /** The backend answers 401 when the seed expired — callers refresh the
         *  seed and retry once, exactly like the site's BV() helper. */
        private class SeedInvalidException : Exception()

        /** GET an encrypted sources payload and decrypt it LOCALLY (mvm1).
         *  Throws SeedInvalidException on 401 / stale-seed responses. */
        private suspend fun fetchAndDecrypt(
            app: Requests,
            query: String,
            tmdbId: Int,
            seed: String,
        ): String? {
            val url = "$query&enc=2&seed=${encodeUrl(seed)}"
            val resp = runCatching {
                app.get(url, headers = API_HEADERS, cacheTime = 0, timeout = 15_000)
            }.getOrNull() ?: return null
            if (resp.code == 401) throw SeedInvalidException()
            if (resp.code !in 200..299) return null
            var txt = resp.text.trim()
            if (txt.isEmpty()) return null
            if (txt.startsWith("{")) {
                // Server-side error object.
                if (txt.contains("STREAMCRYPTO_SEED_INVALID")) throw SeedInvalidException()
                return null
            }
            // Some proxies wrap the binary blob in a JSON string literal.
            if (txt.length > 2 && txt.startsWith("\"") && txt.endsWith("\"")) {
                txt = txt.substring(1, txt.length - 1)
            }
            return runCatching { Mvm1.decryptToString(txt, tmdbId, seed) }
                .onFailure { Log.d(TAG, "Cineby: mvm1 decrypt failed: ${it.message}") }
                .getOrNull()
        }

        /** Parse the decrypted JSON — unwrap a {"result":{…}} shell if present. */
        private fun unwrapResult(plaintext: String): JSONObject? {
            val j = runCatching { JSONObject(plaintext) }.getOrNull() ?: return null
            j.optJSONObject("result")?.let { return it }
            return j.takeIf { it.has("sources") || it.has("subtitles") }
        }


        /** v30: the site's player fetches subtitles from subs.videasy.to. */
        private suspend fun fetchVideasySubs(
            app: Requests,
            tmdbId: Int,
            isMovie: Boolean,
            seasonId: String,
            episodeId: String,
            subtitleCallback: (SubtitleFile) -> Unit,
        ) {
            val url = buildString {
                append(SUBS_API).append("?id=").append(tmdbId)
                if (!isMovie) append("&season=").append(seasonId).append("&episode=").append(episodeId)
            }
            val txt = runCatching {
                app.get(url, headers = API_HEADERS, cacheTime = 0, timeout = 8_000).text
            }.getOrNull().orEmpty().trim()
            if (!txt.startsWith("[")) return
            val arr = runCatching { JSONArray(txt) }.getOrNull() ?: return
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val subUrl = s.optStringOrNullCp("url") ?: continue
                val display = s.optStringOrNullCp("display") ?: s.optStringOrNullCp("language")
                    ?: s.optStringOrNullCp("label") ?: "Subtitle"
                subtitleCallback(SubtitleFile("[Videasy] $display", subUrl))
            }
        }


        private val LANGUAGE_WORDS = listOf(
            "hindi" to "Hindi", "german" to "German", "spanish" to "Spanish",
            "portuguese" to "Portuguese", "french" to "French", "english" to "English",
        )

        /** On this backend a "quality" string can carry an audio language
         *  ("Hindi", "English") instead of a resolution — surface that in the
         *  link NAME (resolutions belong to link.quality, never the name). */
        private fun languageTagOf(quality: String): String {
            val low = quality.lowercase()
            val lang = LANGUAGE_WORDS.firstOrNull { it.first in low }?.second
            val dub = "dub" in low
            return when {
                lang != null && dub -> " · $lang DUB"
                dub -> " · DUB"
                lang != null -> " · $lang"
                else -> ""
            }
        }

        // ── mvm1 payload cipher (v30) ────────────────────────────────────────
        // 1:1 port of the live cineby.at frontend cipher (chunk 831, module
        // 84737). Verified byte-for-byte against node executions of the site's
        // own JavaScript on multiple (seed, mediaId, plaintext) vectors.
        private object Mvm1 {
            private val K: Int = 2654435769L.toInt() // golden-ratio multiplier
            private val MAGIC = byteArrayOf(109, 118, 109, 49) // "mvm1"

            private fun f(e0: Int): Int {
                var e = e0
                e = e xor (e ushr 16); e *= 2246822507L.toInt()
                e = e xor (e ushr 13); e *= 3266489909L.toInt()
                e = e xor (e ushr 16)
                return e
            }

            private fun rotl(e: Int, t: Int): Int {
                val s = t and 31
                return if (s == 0) e else (e shl s) or (e ushr (32 - s))
            }

            private fun fnv(s: String): Int {
                var t = 2166136261L.toInt()
                for (ch in s) t = (t xor ch.code) * 16777619
                return f(t)
            }

            private class State(
                val s: IntArray = IntArray(61),
                val assigned: BooleanArray = BooleanArray(61),
                var acc: Int = 0,
            )

            private fun schedule(seed: String, mediaId: Int): State {
                val st = State()
                var n = f(fnv(seed) xor f(mediaId xor K))
                // In the site JS the c(e)-branch is always taken for e in 0..7
                // (e*(e+1) is always even) — the KSA alternative is dead code.
                for (e in 0 until 8) {
                    val t = ((n.toLong() and 0xFFFFFFFFL) % 61L).toInt()
                    n = rotl(n + K, 7 + (7 and e))
                    st.s[t] = n xor f(n)
                    st.assigned[t] = true
                    n = f(n + t)
                }
                st.acc = f(2779096485L.toInt() xor n)
                return st
            }

            private fun step(st: State, counter: Int): Int {
                var a = st.acc
                val i = ((a.toLong() and 0xFFFFFFFFL) % 61L).toInt()
                val uBit = if (st.assigned[i]) -1 else 0
                val n = st.s[i] xor (K * (counter + 1))
                val r = a
                var c = (r xor n) or (r and n and uBit)
                // (v90c) Re-verified against the live site chunk 831
                // (2026-07-31): the second rotate is (i*7) & 31 — the v30
                // transcription double-applied the 31 factor (217*i mod 32
                // ≠ 7*i mod 32), so keystream words diverged and the
                // "mvm1" magic check failed on real payloads, silently
                // nulling every Cineby link. One-line rotation fix, now
                // proven against live /cdn payloads (movie + TV, decrypts
                // to sources+subtitles JSON).
                c = rotl(c + a, i and 31) xor rotl(a, (i * 7) and 31)
                a = f(c + K)
                st.s[i] = a
                st.assigned[i] = true
                st.acc = a
                return a
            }

            private val B64REV = IntArray(256) { -1 }.apply {
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
                    .forEachIndexed { idx, ch -> this[ch.code] = idx }
            }

            private fun b64UrlDecode(s: String): ByteArray {
                val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
                var acc = 0
                var bits = 0
                for (ch in s) {
                    val v = if (ch.code < 256) B64REV[ch.code] else -1
                    if (v < 0) continue
                    acc = (acc shl 6) or v
                    bits += 6
                    if (bits >= 8) {
                        bits -= 8
                        out.write((acc ushr bits) and 0xFF)
                    }
                }
                return out.toByteArray()
            }

            /** Decrypt a base64url payload: XOR keystream, verify "mvm1" magic,
             *  return the UTF-8 JSON body. Throws on tampered/wrong-seed data. */
            fun decryptToString(b64: String, mediaId: Int, seed: String): String {
                val raw = b64UrlDecode(b64.trim())
                if (raw.isEmpty()) throw IllegalArgumentException("mvm1: empty payload")
                val st = schedule(seed, mediaId)
                var counter = 0
                var cur = 0
                var curBits = 0
                for (i in raw.indices) {
                    if (curBits == 0) {
                        cur = step(st, counter++)
                        curBits = 32
                    }
                    raw[i] = (raw[i].toInt() xor cur).toByte()
                    cur = cur ushr 8
                    curBits -= 8
                }
                for (m in MAGIC.indices) {
                    if (raw.size <= m || raw[m] != MAGIC[m]) {
                        throw IllegalStateException("mvm1: bad magic (stale seed?)")
                    }
                }
                return String(raw, MAGIC.size, raw.size - MAGIC.size, Charsets.UTF_8)
            }
        }

        private suspend fun resolveOneServer(
            app: Requests,
            server: CinebyServer,
            tmdbId: Int,
            seed: String,
            title: String,
            yearStr: String,
            imdbIdStr: String,
            mediaType: String,
            seasonId: String,
            episodeId: String,
            srcLabel: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            // Index each server as its own source group in the picker.
            // This gives the user a clean hierarchy:
            //   Wizstream • Cineby · Neon
            //     ├── 1080p · Original audio
            //     └── 720p · Original audio
            //   Wizstream • Cineby · Yoru
            //     ├── 2160p · 4K · Original audio
            //     └── 1080p · Original audio
            //   ...
            val serverSourceLabel = "$srcLabel · ${server.displayName}"

            // Step 1 (seed) is already done by the caller — reuse the same
            // seed for all 8 servers to avoid 8x rate-limit on the seed endpoint.

            // Steps 2+3 (v30): fetch the mvm1-encrypted payload and decrypt
            // it LOCALLY — the enc-dec.app service was retired by the site.
            val query = buildSourcesQuery(
                server.path, tmdbId, doubleEncode(title), mediaType, yearStr,
                imdbIdStr, seasonId, episodeId, server.language,
            )
            val plaintext = fetchAndDecrypt(app, query, tmdbId, seed) ?: return false
            val result = unwrapResult(plaintext) ?: return false

            // Step 4: Emit sources + subtitles.
            val subtitles = result.optJSONArray("subtitles")
            if (subtitles != null) {
                for (i in 0 until subtitles.length()) {
                    val s = subtitles.optJSONObject(i) ?: continue
                    val subUrl = s.optStringOrNullCp("url") ?: s.optStringOrNullCp("file")
                        ?: s.optStringOrNullCp("src") ?: continue
                    val rawSubLabel = s.optStringOrNullCp("language") ?: s.optStringOrNullCp("label")
                        ?: s.optStringOrNullCp("lang") ?: s.optStringOrNullCp("name") ?: "Subtitle"
                    // Prefix subtitle label with the server name so the user
                    // can see which subtitles came from which server. This is
                    // important because Cloudstream merges ALL subtitles from
                    // ALL sources into one list — without the prefix the user
                    // can't tell which subtitle matches their chosen source.
                    // Example: "[Neon] English", "[Neon] Japanese",
                    //           "[Yoru] English", "[Killjoy] German"
                    val subLabel = "[${server.displayName}] $rawSubLabel"
                    subtitleCallback(SubtitleFile(subLabel, subUrl))
                }
            }

            var any = false
            val sourcesArr = result.optJSONArray("sources")
            if (sourcesArr != null) {
                for (i in 0 until sourcesArr.length()) {
                    val s = sourcesArr.optJSONObject(i) ?: continue
                    val url = s.optStringOrNullCp("url") ?: continue
                    // FILTER: prevent 3003 — only emit real http(s) URLs.
                    if (!url.startsWith("http")) continue
                    // Force https to prevent 2007 cleartext error.
                    val safeUrl = if (url.startsWith("http://")) {
                        url.replaceFirst("http://", "https://")
                    } else url
                    val quality = s.optStringOrNullCp("quality") ?: "Auto"

                    // Apply server's qualityFilter (Vyse=English, Fade=Hindi).
                    if (server.qualityFilter != null &&
                        !quality.equals(server.qualityFilter, ignoreCase = true)
                    ) continue

                    val name = buildLabel(server)
                    if (emitTaggedMedia(app, safeUrl, serverSourceLabel, name, callback)) {
                        any = true
                    } else if (safeUrl.contains(".m3u8", true)) {
                        // Use M3u8Helper to expand master playlist → per-quality links.
                        // CRITICAL: pass Referer+Origin headers to prevent 2004.
                        M3u8Helper.generateM3u8(
                            source = serverSourceLabel,
                            streamUrl = safeUrl,
                            referer = "$SITE/",
                            headers = API_HEADERS,
                        ).forEach { link ->
                            callback(
                                newExtractorLink(
                                    source = serverSourceLabel,
                                    name = name,
                                    url = link.url,
                                    type = ExtractorLinkType.M3U8,
                                ) {
                                    this.referer = "$SITE/"
                                    this.quality = link.quality
                                    this.headers = API_HEADERS
                                }
                            )
                            any = true
                        }
                    } else if (safeUrl.contains(".mp4", true) ||
                        safeUrl.contains(".mkv", true) ||
                        safeUrl.contains(".webm", true)
                    ) {
                        callback(
                            newExtractorLink(
                                source = serverSourceLabel,
                                name = name,
                                url = safeUrl,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = "$SITE/"
                                this.quality = qualityFromName(safeUrl)
                                this.headers = API_HEADERS
                            }
                        )
                        any = true
                    } else if (safeUrl.contains(".mpd", true)) {
                        callback(
                            newExtractorLink(
                                source = serverSourceLabel,
                                name = name,
                                url = safeUrl,
                                type = ExtractorLinkType.DASH,
                            ) {
                                this.referer = "$SITE/"
                                this.quality = qualityFromName(safeUrl)
                                this.headers = API_HEADERS
                            }
                        )
                        any = true
                    }
                    // Else: skip — the URL is likely a non-media resource.
                }
            } else {
                // Legacy shape 1: {url:"...m3u8", subtitles:[...]}
                val singleUrl = result.optStringOrNullCp("url")
                if (singleUrl != null && singleUrl.startsWith("http")) {
                    val safeUrl = if (singleUrl.startsWith("http://")) {
                        singleUrl.replaceFirst("http://", "https://")
                    } else singleUrl
                    val name = buildLabel(server)
                    if (safeUrl.contains(".m3u8", true)) {
                        M3u8Helper.generateM3u8(
                            source = serverSourceLabel,
                            streamUrl = safeUrl,
                            referer = "$SITE/",
                            headers = API_HEADERS,
                        ).forEach { link ->
                            callback(
                                newExtractorLink(
                                    source = serverSourceLabel,
                                    name = name,
                                    url = link.url,
                                    type = ExtractorLinkType.M3U8,
                                ) {
                                    this.referer = "$SITE/"
                                    this.quality = link.quality
                                    this.headers = API_HEADERS
                                }
                            )
                            any = true
                        }
                    }
                } else {
                    // Legacy shape 2: {streams:{"1080p":"url","720p":"url"}, subtitles:[...]}
                    val streams = result.optJSONObject("streams")
                    if (streams != null) {
                        val keys = streams.keys()
                        while (keys.hasNext()) {
                            val q = keys.next()
                            val url = streams.optString(q, "").ifBlank { continue }
                            if (!url.startsWith("http")) continue
                            val safeUrl = if (url.startsWith("http://")) {
                                url.replaceFirst("http://", "https://")
                            } else url
                            val name = buildLabel(server)
                            if (safeUrl.contains(".m3u8", true)) {
                                M3u8Helper.generateM3u8(
                                    source = serverSourceLabel,
                                    streamUrl = safeUrl,
                                    referer = "$SITE/",
                                    headers = API_HEADERS,
                                ).forEach { link ->
                                    callback(
                                        newExtractorLink(
                                            source = serverSourceLabel,
                                            name = name,
                                            url = link.url,
                                            type = ExtractorLinkType.M3U8,
                                        ) {
                                            this.referer = "$SITE/"
                                            this.quality = link.quality
                                            this.headers = API_HEADERS
                                        }
                                    )
                                    any = true
                                }
                            } else {
                                callback(
                                    newExtractorLink(
                                        source = serverSourceLabel,
                                        name = name,
                                        url = safeUrl,
                                        type = INFER_TYPE,
                                    ) {
                                        this.referer = "$SITE/"
                                        this.quality = qualityFromName(q)
                                        this.headers = API_HEADERS
                                    }
                                )
                                any = true
                            }
                        }
                    }
                }
            }
            return any
        }

        /**
         * Build the link NAME (not source) for a Cineby ExtractorLink.
         * The `source` field is set to `serverSourceLabel` (e.g.,
         * "Wizstream • Cineby · Neon") which groups all links from the
         * same server together. The `name` field only contains quality +
         * audio info, so the picker shows a clean hierarchy:
         *
         *   Wizstream • Cineby · Neon
         *     ├── 1080p · Original audio
         *     └── 720p · Original audio
         */

        // ── Codec probing + device gate (v18 tags / v27 skip-unplayable) ────
        // The API response doesn't say which codec a source uses, so we
        // fetch the HLS master / DASH MPD (one small request — it replaces
        // the fetch M3u8Helper would do anyway) and probe MP4 headers, then
        // tag every emitted link ("· H.264" etc.) and — since v27 — silently
        // drop any variant MediaCodecList says this device cannot decode.
        // That kills ExoPlayer 4003 on TVs: 2160p variants and over-level
        // H.264 never reach the picker on hardware that can't play them.

        /** Fetch HLS playlist text (master or media playlist). */
        private suspend fun fetchHlsText(app: Requests, url: String): String =
            runCatching {
                app.get(url, headers = API_HEADERS, cacheTime = 0, timeout = 12_000).text
            }.getOrNull().orEmpty()

        /** Probe the first bytes of a progressive MP4 for its ftyp brands /
         *  sample-entry fourccs; returns the codec token found, if any. */
        private suspend fun probeMp4Codecs(app: Requests, url: String): String? {
            val resp = runCatching {
                app.get(
                    url,
                    headers = API_HEADERS + ("Range" to "bytes=0-65535"),
                    cacheTime = 0,
                    timeout = 10_000,
                )
            }.getOrNull() ?: return null
            if (resp.code !in 200..299 && resp.code != 206) return null
            val rx = Regex("hvc1|hev1|dvh1|av01|vp09|avc1|avc3")
            return rx.find(resp.text)?.value
        }

        /** Emit one HLS/DASH/progressive link with codec tagging, dropping
         *  every variant this device's hardware decoder says it cannot play
         *  (v27 — see DeviceDecoderProbe; this is the TV 4003 fix). */
        private suspend fun emitTaggedMedia(
            app: Requests,
            safeUrl: String,
            serverSourceLabel: String,
            name: String,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            when {
                safeUrl.contains(".m3u8", true) -> {
                    val hlsText = fetchHlsText(app, safeUrl)
                    // (v30) A 200/OK that isn't a playlist means a parked CDN
                    // or an ad-wall "lander" page (this is what killed Yoru when
                    // cdntv.one expired). Emitting such a URL is a guaranteed
                    // player-side 3003, so drop the link entirely. Blank bodies
                    // (timeouts/geo-blocks) still keep the link, per policy.
                    if (hlsText.isNotBlank() && !hlsText.startsWith("#EXTM3U")) {
                        Log.d(TAG, "Cineby: dropped non-playlist body for $safeUrl")
                        return true
                    }
                    // Demuxed A/V (separate audio groups): emit the master
                    // itself — never its video-only variants (silent video!).
                    if (hlsText.startsWith("#EXTM3U") &&
                        emitDemuxedMaster(safeUrl, hlsText, serverSourceLabel, name, "$SITE/", API_HEADERS, callback)
                    ) return true
                    val variants = parseHlsMasterVariants(hlsText, safeUrl)
                    if (variants.isEmpty()) {
                        // Media playlist (no variants) or unreachable — emit as-is.
                        callback(
                            newExtractorLink(
                                source = serverSourceLabel,
                                name = name,
                                url = safeUrl,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = "$SITE/"
                                this.quality = qualityFromName(safeUrl)
                                this.headers = API_HEADERS
                            }
                        )
                        return true
                    }
                    variants.distinctBy { it.url }.forEach { v ->
                        val skip = DeviceDecoderProbe.skipReason(
                            videoCodecOf(v.codecs), v.width, v.height
                        )
                        if (skip != null) {
                            Log.d(TAG, "Cineby: skipped ${v.width}x${v.height} (${v.codecs}) for $serverSourceLabel: $skip")
                            return@forEach
                        }
                        callback(
                            newExtractorLink(
                                source = serverSourceLabel,
                                name = name + codecDisplayTag(v.codecs),
                                url = v.url,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = "$SITE/"
                                this.quality = if (v.height > 0) {
                                    qualityFromDimensions(v.width, v.height)
                                } else qualityFromName(safeUrl)
                                this.headers = API_HEADERS
                            }
                        )
                    }
                    return true
                }
                safeUrl.contains(".mpd", true) -> {
                    // Fetch the MPD text once to extract codec info.
                    val mpd = runCatching {
                        app.get(safeUrl, headers = API_HEADERS, cacheTime = 0, timeout = 12_000).text
                    }.getOrNull().orEmpty()
                    val videoCodecs = Regex("""codecs="([^"]+)"""").findAll(mpd)
                        .map { it.groupValues[1] }
                        .firstOrNull { videoCodecOf(it) != VCodec.UNKNOWN }
                    val skip = DeviceDecoderProbe.skipReason(videoCodecOf(videoCodecs), 0, 0)
                    if (skip != null) {
                        Log.d(TAG, "Cineby: skipped DASH ($videoCodecs) for $serverSourceLabel: $skip")
                        return true
                    }
                    callback(
                        newExtractorLink(
                            source = serverSourceLabel,
                            name = name + codecDisplayTag(videoCodecs),
                            url = safeUrl,
                            type = ExtractorLinkType.DASH,
                        ) {
                            this.referer = "$SITE/"
                            this.quality = qualityFromName(safeUrl)
                            this.headers = API_HEADERS
                        }
                    )
                    return true
                }
                safeUrl.contains(".mp4", true) || safeUrl.contains(".mkv", true) ||
                    safeUrl.contains(".webm", true) -> {
                    val codecs = if (safeUrl.contains(".mp4", true)) probeMp4Codecs(app, safeUrl) else null
                    val skip = DeviceDecoderProbe.skipReason(videoCodecOf(codecs), 0, 0)
                    if (skip != null) {
                        Log.d(TAG, "Cineby: skipped progressive ($codecs) for $serverSourceLabel: $skip")
                        return true
                    }
                    callback(
                        newExtractorLink(
                            source = serverSourceLabel,
                            name = name + codecDisplayTag(codecs),
                            url = safeUrl,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = "$SITE/"
                            this.quality = qualityFromName(safeUrl)
                            this.headers = API_HEADERS
                        }
                    )
                    return true
                }
                else -> {
                    // (v28) Extension-less URLs — some Cineby servers hand out
                    // bare paths (e.g. "…/nodash/12_30_23/…") that are really
                    // HLS playlists. One small sniff request: if the body is
                    // a playlist, expand + gate it like any other master.
                    val text = runCatching {
                        app.get(
                            safeUrl,
                            headers = API_HEADERS + ("Range" to "bytes=0-16384"),
                            cacheTime = 0, timeout = 12_000,
                        ).text
                    }.getOrNull() ?: return false
                    if (!text.startsWith("#EXTM3U")) return false
                    if (emitDemuxedMaster(safeUrl, text, serverSourceLabel, name, "$SITE/", API_HEADERS, callback)) {
                        return true
                    }
                    val variants = parseHlsMasterVariants(text, safeUrl)
                    if (variants.isEmpty()) {
                        callback(
                            newExtractorLink(
                                source = serverSourceLabel,
                                name = name,
                                url = safeUrl,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = "$SITE/"
                                this.quality = qualityFromName(safeUrl)
                                this.headers = API_HEADERS
                            }
                        )
                        return true
                    }
                    variants.forEach { v ->
                        val skip = DeviceDecoderProbe.skipReason(
                            videoCodecOf(v.codecs), v.width, v.height
                        )
                        if (skip != null) {
                            Log.d(TAG, "Cineby: skipped ${v.width}x${v.height} (${v.codecs}) for $serverSourceLabel: $skip")
                            return@forEach
                        }
                        callback(
                            newExtractorLink(
                                source = serverSourceLabel,
                                name = name + codecDisplayTag(v.codecs),
                                url = v.url,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = "$SITE/"
                                this.quality = if (v.height > 0) {
                                    qualityFromDimensions(v.width, v.height)
                                } else qualityFromName(safeUrl)
                                this.headers = API_HEADERS
                            }
                        )
                    }
                    return true
                }
            }
            return false
        }

        /**
         * (v26) The in-player source switcher renders ONLY name (+ the
         * quality it appends itself) - the `source` field is invisible
         * there. So the full "Cineby · Server" path lives INSIDE the name,
         * and the resolution is intentionally NOT included (the UI appends
         * it from link.quality, which used to produce "720p · Hindi 720p"
         * duplicates).
         */
        private fun buildLabel(server: CinebyServer): String =
            "Cineby · ${server.displayName} — ${server.audioLabel ?: "Original"} audio"

        // ── TMDB helpers (use the keyless proxy first, fall back to TMDB direct) ──

        private suspend fun fetchYear(app: Requests, tmdbId: Int, mediaType: String): String? {
            // Try the keyless proxy first.
            val proxyUrl = "$TMDB_PROXY/$mediaType/$tmdbId?language=en-US"
            val proxyResp = runCatching {
                app.get(proxyUrl, headers = API_HEADERS, cacheTime = 0, timeout = 8_000)
            }.getOrNull()
            if (proxyResp != null && proxyResp.code in 200..299 && proxyResp.text.isNotBlank()) {
                val json = runCatching { JSONObject(proxyResp.text) }.getOrNull()
                if (json != null) {
                    val dateStr = if (mediaType == "movie") {
                        json.optStringOrNullCp("release_date")
                    } else {
                        json.optStringOrNullCp("first_air_date")
                    }
                    dateStr?.substringBefore("-")?.takeIf { it.length == 4 }?.let { return it }
                }
            }
            // Fall back to TMDB direct with API key.
            val directUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbId?api_key=$TMDB_KEY&language=en-US"
            val directResp = runCatching {
                app.get(directUrl, headers = API_HEADERS, cacheTime = 0, timeout = 8_000)
            }.getOrNull() ?: return null
            if (directResp.code !in 200..299 || directResp.text.isBlank()) return null
            val json = runCatching { JSONObject(directResp.text) }.getOrNull() ?: return null
            val dateStr = if (mediaType == "movie") {
                json.optStringOrNullCp("release_date")
            } else {
                json.optStringOrNullCp("first_air_date")
            }
            return dateStr?.substringBefore("-")?.takeIf { it.length == 4 }
        }

        private suspend fun fetchImdbId(app: Requests, tmdbId: Int, mediaType: String): String? {
            // Try the keyless proxy with append_to_response=external_ids.
            val proxyUrl = "$TMDB_PROXY/$mediaType/$tmdbId?append_to_response=external_ids&language=en-US"
            val proxyResp = runCatching {
                app.get(proxyUrl, headers = API_HEADERS, cacheTime = 0, timeout = 8_000)
            }.getOrNull()
            if (proxyResp != null && proxyResp.code in 200..299 && proxyResp.text.isNotBlank()) {
                val json = runCatching { JSONObject(proxyResp.text) }.getOrNull()
                json?.optJSONObject("external_ids")?.optStringOrNullCp("imdb_id")?.let { return it }
            }
            // Fall back to TMDB direct.
            val directUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbId" +
                "?api_key=$TMDB_KEY&append_to_response=external_ids&language=en-US"
            val directResp = runCatching {
                app.get(directUrl, headers = API_HEADERS, cacheTime = 0, timeout = 8_000)
            }.getOrNull() ?: return null
            if (directResp.code !in 200..299 || directResp.text.isBlank()) return null
            val json = runCatching { JSONObject(directResp.text) }.getOrNull() ?: return null
            return json.optJSONObject("external_ids")?.optStringOrNullCp("imdb_id")
        }

        // ── Percent-encoding helpers (must match the Aniyomi implementation) ──

        private const val HEX = "0123456789ABCDEF"

        private fun pctEncode(s: String): String {
            val bytes = s.toByteArray(Charsets.UTF_8)
            val out = StringBuilder(bytes.size * 3)
            for (raw in bytes) {
                val c = raw.toInt() and 0xFF
                val unreserved = (c in 0x30..0x39) || (c in 0x41..0x5A) || (c in 0x61..0x7A) ||
                    c == 0x2D || c == 0x2E || c == 0x5F || c == 0x7E
                if (unreserved) {
                    out.append(c.toChar())
                } else {
                    out.append('%')
                    out.append(HEX[(c ushr 4) and 0x0F])
                    out.append(HEX[c and 0x0F])
                }
            }
            return out.toString()
        }

        private fun doubleEncode(s: String): String = pctEncode(pctEncode(s))
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 6: Bingr  (https://bingr.one)
    //
    //  Recon (2026-07-22): Vite SPA backed by one anonymous JSON API:
    //    POST https://api.bingr.one/api/stream
    //    body  {"srv": <serverId>, "t": "movie"|"tv", "id": <tmdbId>,
    //           "query": {"title":…, "year":…, "season":…, "episode":…}}
    //    resp  {"scraperName": "Sirius",
    //           "sources":   [{"url","quality","language","type","label"}],
    //           "subtitles": []}
    //  Seven active scraper servers (three more exist but are flagged
    //  `comingSoon` server-side and return nothing):
    //    s11 Sirius · s10 Elysium · s1 Miller · s2 Mann · s3 Edmunds ·
    //    s4 Luna · s5 Aditya
    //  Returned media URLs are already proxied (filmu.in / workers.dev) and
    //  embed whatever referer the origin host needs — dead variants are
  //  common, so every link is verified with a probe before it is emitted.
    // ════════════════════════════════════════════════════════════════════════

    internal object BingrResolver : SourceResolver {
        private const val SITE = "https://bingr.one"
        private const val API = "https://api.bingr.one/api"
        private const val LABEL = "Bingr"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Origin" to SITE,
            "Accept" to "application/json, text/plain, */*",
        )

        private val SERVERS = listOf(
            "s11" to "Sirius",
            "s10" to "Elysium",
            "s1" to "Miller",
            "s2" to "Mann",
            "s3" to "Edmunds",
            "s4" to "Luna",
            "s5" to "Aditya",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            if (tmdbId == null) return false
            val srcLabel = "$labelPrefix • $LABEL"
            val mediaType = if (isMovie) "movie" else "tv"

            val query = JSONObject().apply {
                put("title", title)
                year?.let { put("year", it) }
                if (!isMovie) {
                    put("season", season ?: 1)
                    put("episode", episode ?: 1)
                }
            }

            var any = false
            val gate = Semaphore(3)
            coroutineScope {
                SERVERS.map { (srvId, srvName) ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            runCatching {
                                resolveOneServer(
                                    app, srvId, srvName, mediaType, tmdbId,
                                    query, srcLabel, subtitleCallback, callback,
                                )
                            }.getOrDefault(false)
                        }
                    }
                }.awaitAll().forEach { if (it) any = true }
            }
            return any
        }

        private suspend fun resolveOneServer(
            app: Requests,
            srvId: String,
            srvName: String,
            mediaType: String,
            tmdbId: Int,
            query: JSONObject,
            srcLabel: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val body = JSONObject().apply {
                put("srv", srvId)
                put("t", mediaType)
                put("id", tmdbId.toString())
                put("query", query)
            }.toString()
            val resp = runCatching {
                app.post(
                    "$API/stream",
                    headers = HEADERS + ("Content-Type" to "application/json"),
                    requestBody = body.toRequestBody("application/json".toMediaTypeOrNull()),
                    cacheTime = 0,
                    timeout = 20_000,
                )
            }.getOrNull() ?: return false
            if (resp.code !in 200..299 || resp.text.isBlank()) return false
            val json = runCatching { JSONObject(resp.text) }.getOrNull() ?: return false

            val serverSourceLabel = "$srcLabel · $srvName"
            var any = false
            // (v85) WEB-PARITY STARTUP (user report: "bingr-sirius plays
            // fine on web, in Cloudstream it fetches slowly"): these
            // sources serve PER-QUALITY ladders in descending order, so
            // the app's default/auto pick used to grab the TOP rung first
            // (8 Mbps Sirius 1080p) and buffer on links the web starts at
            // its adaptive LOW rung. Collect first, then emit LOW-quality-
            // FIRST at the end — the default pick now starts light like
            // the web; 1080p stays one tap above, chips unchanged.
            val pending = ArrayList<ExtractorLink>()
            val emit: (ExtractorLink) -> Unit = { pending += it }

            // Subtitles ride the top-level array.
            val subs = json.optJSONArray("subtitles")
            if (subs != null) {
                for (i in 0 until subs.length()) {
                    val s = subs.optJSONObject(i) ?: continue
                    val subUrl = s.optStringOrNullCp("url") ?: s.optStringOrNullCp("file")
                        ?: s.optStringOrNullCp("src") ?: continue
                    if (!subUrl.startsWith("http")) continue
                    val rawLabel = s.optStringOrNullCp("label") ?: s.optStringOrNullCp("language")
                        ?: s.optStringOrNullCp("lang") ?: "Subtitle"
                    subtitleCallback(SubtitleFile("[$srvName] $rawLabel", subUrl))
                }
            }

            val sources = json.optJSONArray("sources") ?: return any
            for (i in 0 until sources.length()) {
                val s = sources.optJSONObject(i) ?: continue
                val url = s.optStringOrNullCp("url") ?: continue
                if (!url.startsWith("http")) continue
                val quality = s.optStringOrNullCp("quality") ?: "Auto"
                val language = s.optStringOrNullCp("language")
                // (v26) name carries the full "Bingr · Server" path + tags;
                // quality is left to the UI's own quality chip.
                val langPart = when {
                    language.isNullOrBlank() || language.equals("Original", true) -> ""
                    else -> language
                }
                val type = s.optStringOrNullCp("type") ?: ""
                val isHls = url.contains(".m3u8", true) ||
                    type.contains("mpegurl", true) || type.contains("m3u8", true)
                val isMp4 = !isHls && (url.contains(".mp4", true) ||
                    type.contains("mp4", true))

                // Probe before emitting — Bingr scrapers return dead variants
                // often; a 403/404/5xx link must never reach the player.
                when {
                    isHls -> {
                        val masterText = runCatching {
                            app.get(url, headers = HEADERS + ("Range" to "bytes=0-16384"),
                                cacheTime = 0, timeout = 12_000)
                        }.getOrNull()
                        if (masterText == null || masterText.code !in 200..299 && masterText.code != 206) continue
                        val text = masterText.text
                        if (!text.contains("#EXTM3U")) continue
                        // (v28) Demuxed A/V masters: emit the master itself.
                        if (emitDemuxedMaster(url, text, serverSourceLabel,
                                bingrName(srvName, langPart, ""), "$SITE/", HEADERS, emit)
                        ) {
                            any = true
                            continue
                        }
                        // (v27) Expand masters into per-resolution variants so
                        // no adaptive "Auto" link can smuggle 2160p/etc onto a
                        // TV that can't decode it; gate every variant against
                        // the device's MediaCodecList capabilities.
                        val variants = parseHlsMasterVariants(text, url)
                        if (variants.isNotEmpty()) {
                            variants.distinctBy { it.url }.forEach { v ->
                                val skip = DeviceDecoderProbe.skipReason(
                                    videoCodecOf(v.codecs), v.width, v.height
                                )
                                if (skip != null) {
                                    Log.d(TAG, "Bingr: skipped ${v.width}x${v.height} (${v.codecs}) on $srvName: $skip")
                                    return@forEach
                                }
                                emit(
                                    newExtractorLink(
                                        source = serverSourceLabel,
                                        name = bingrName(srvName, langPart, codecDisplayTag(v.codecs)),
                                        url = v.url,
                                        type = ExtractorLinkType.M3U8,
                                    ) {
                                        this.referer = "$SITE/"
                                        this.quality = if (v.height > 0) {
                                            qualityFromDimensions(v.width, v.height)
                                        } else qualityFromName(quality)
                                        this.headers = HEADERS
                                    }
                                )
                                any = true
                            }
                            continue
                        }
                        // Single-rendition media playlist.
                        val codecs = Regex("""CODECS=\"([^\"]+)\"""").find(text)?.groupValues?.get(1)
                        val skip = DeviceDecoderProbe.skipReason(videoCodecOf(codecs), 0, 0)
                        if (skip != null) {
                            Log.d(TAG, "Bingr: skipped media playlist ($codecs) on $srvName: $skip")
                            continue
                        }
                        emit(
                            newExtractorLink(
                                source = serverSourceLabel,
                                name = bingrName(srvName, langPart, codecDisplayTag(codecs)),
                                url = url,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = "$SITE/"
                                this.quality = qualityFromName(quality)
                                this.headers = HEADERS
                            }
                        )
                        any = true
                    }
                    isMp4 -> {
                        val probe = runCatching {
                            app.get(url, headers = HEADERS + ("Range" to "bytes=0-511"),
                                cacheTime = 0, timeout = 12_000)
                        }.getOrNull()
                        if (probe == null || probe.code !in 200..299 && probe.code != 206) continue
                        emit(
                            newExtractorLink(
                                source = serverSourceLabel,
                                name = bingrName(srvName, langPart, ""),
                                url = url,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = "$SITE/"
                                this.quality = qualityFromName(quality)
                                this.headers = HEADERS
                            }
                        )
                        any = true
                    }
                    else -> continue // unknown container — skip (prevents 3003)
                }
            }
            // (v85) See the collector note above: emit collected links
            // LOWEST quality first (stable sort; unknown/last-resort rungs
            // go last). Server order is untouched.
            val ordered = pending.sortedBy { l ->
                if (l.quality in 1..4320) l.quality else Int.MAX_VALUE
            }
            if (ordered.isNotEmpty()) {
                Log.d(TAG, "Bingr: emit ${ordered.size} link(s) low-first on $srvName")
            }
            ordered.forEach(callback)
            return any
        }

        private fun bingrName(srvName: String, langPart: String, codecTag: String): String {
            val tags = listOf(langPart, codecTag.trimStart('·', ' '))
                .filter { it.isNotBlank() }
            return "Bingr · $srvName" + if (tags.isEmpty()) "" else " — " + tags.joinToString(" · ")
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 7: Moonflix  (https://moonflix.website)
    //
    //  Recon (2026-07-22): Lovable/Vite SPA. Playback happens on
    //  player.moonflix.website, which queries three anonymous Railway
    //  backends (all TMDB-keyed, no auth, no cookies):
    //    CH = https://confident-harmony-production-0578.up.railway.app
    //         /movie/{id}  ·  /tv/{id}/{season}/{episode}
    //         → {streams: [{language, available, qualities:
    //                       [{resolution, codec, raw_url}]}], subtitles: []}
    //         Multi-audio direct mp4 ladder (English/Hindi/Original/…).
    //         ALSO: /subtitles/movie/{id} · /subtitles/tv/{id}/{s}/{e}
    //         (a bare JSON array or {"subtitles": [...]} — handle both).
    //    HV = https://hvhyu-production.up.railway.app  (same paths)
    //         → {streams: [{quality, url(.m3u8), type: "hls"}]}
    //    SE = https://series-production-5c1c.up.railway.app   (TV only)
    //         → {sources: [{name: "VIP 1"/"LUL 2"/…, url, proxy_url}]}
    //  Live-verified limitations: CH's CDN (hakunaymatata) reverse-proxies
    //  behind a cache layer that denies datacenter IPs and rate-limits hard,
    //  so we canary-probe it ONCE with the site's own player referer and
  //  emit the whole ladder only when the user's network can actually eat it.
    // ════════════════════════════════════════════════════════════════════════

    internal object MoonflixResolver : SourceResolver {
        private const val SITE = "https://moonflix.website"
        private const val PLAYER = "https://player.moonflix.website"
        private const val LABEL = "Moonflix"
        private const val API_CH = "https://confident-harmony-production-0578.up.railway.app"
        private const val API_HV = "https://hvhyu-production.up.railway.app"
        private const val API_SE = "https://series-production-5c1c.up.railway.app"

        private val HEADERS = mapOf("User-Agent" to UA)
        private val PLAYER_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$PLAYER/",
            "Origin" to PLAYER,
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            if (tmdbId == null) return false
            val srcLabel = "$labelPrefix • $LABEL"
            val sNum = if (isMovie) 1 else (season ?: 1)
            val eNum = if (isMovie) 1 else (episode ?: 1)
            val mediaPath = if (isMovie) "movie/$tmdbId" else "tv/$tmdbId/$sNum/$eNum"

            var any = false
            coroutineScope {
                val chJob = async(Dispatchers.IO) {
                    runCatching {
                        fetchCh(app, mediaPath, srcLabel, callback)
                    }.getOrDefault(false)
                }
                val hvJob = async(Dispatchers.IO) {
                    runCatching {
                        fetchHv(app, mediaPath, srcLabel, callback)
                    }.getOrDefault(false)
                }
                val seJob = async(Dispatchers.IO) {
                    if (isMovie) return@async false
                    runCatching {
                        fetchSe(app, tmdbId, sNum, eNum, srcLabel, callback)
                    }.getOrDefault(false)
                }
                val subJob = async(Dispatchers.IO) {
                    runCatching { fetchSubs(app, mediaPath, subtitleCallback) }
                }
                listOf(chJob, hvJob, seJob).awaitAll().forEach { if (it) any = true }
                subJob.await()
            }
            return any
        }

        // ── CH backend: multi-language direct mp4 ladder ────────────────────

        private suspend fun fetchCh(
            app: Requests,
            mediaPath: String,
            srcLabel: String,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val json = getJson(app, "$API_CH/$mediaPath") ?: return false
            val streams = json.optJSONArray("streams") ?: return false
            if (streams.length() == 0) return false

            data class ChLink(val lang: String, val resolution: String, val codec: String, val url: String)
            val links = mutableListOf<ChLink>()
            for (i in 0 until streams.length()) {
                val st = streams.optJSONObject(i) ?: continue
                if (!st.optBoolean("available", false)) continue
                val lang = st.optStringOrNullCp("language") ?: continue
                val qualities = st.optJSONArray("qualities") ?: continue
                for (q in 0 until qualities.length()) {
                    val qual = qualities.optJSONObject(q) ?: continue
                    val raw = qual.optStringOrNullCp("raw_url") ?: continue
                    if (!raw.startsWith("http")) continue
                    links += ChLink(
                        lang = lang,
                        resolution = qual.optStringOrNullCp("resolution") ?: "Auto",
                        codec = qual.optStringOrNullCp("codec") ?: "",
                        url = raw,
                    )
                }
            }
            if (links.isEmpty()) return false

            // (v85) WEB-PARITY STARTUP: the site's player starts adaptive/
            // light and ramps — Cloudstream's default pick used to grab
            // the first (top) rung and buffer ("Moonflix slow vs web").
            // Language order preserved (first-seen), LOW resolution first
            // inside each language; every rung still listed with its chip.
            val langOrder = links.map { it.lang }.distinct()
            links.sortWith(compareBy(
                { langOrder.indexOf(it.lang) },
                { Rx.CH_RUNG_RE.find(it.resolution)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE },
            ))
            Log.d(TAG, "Moonflix CH: ladder ${links.size} link(s) low-first")

            // Canary probe — CH's CDN (squid "web cache") hard-denies
            // datacenter IPs with 403 and rate-limits aggressively, so we
            // must NOT fire one probe per link. One probe on the first link:
            // reachable → the user's network is good, emit the whole ladder;
            // denied    → drop the group entirely (clean absence, no 2004s).
            val canary = runCatching {
                app.get(
                    links.first().url,
                    headers = PLAYER_HEADERS + ("Range" to "bytes=0-511"),
                    cacheTime = 0, timeout = 12_000,
                )
            }.getOrNull() ?: return false
            if (canary.code !in 200..299 && canary.code != 206) return false

            var any = false
            for (l in links) {
                val pretty = l.lang.replaceFirstChar { it.uppercase() }
                // (v27) skip anything this device's decoder can't play
                // (CH serves HEVC mp4s on some titles — TV 4003 otherwise).
                val resH = Regex("""(\d{3,4})""").find(l.resolution)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val skip = DeviceDecoderProbe.skipReason(videoCodecOf(l.codec), 0, resH)
                if (skip != null) {
                    Log.d(TAG, "Moonflix CH: skipped $pretty ${l.resolution} (${l.codec}): $skip")
                    continue
                }
                // (v26) resolution omitted from the name — the player UI
                // appends link.quality itself ("1080p 1080p" otherwise).
                val name = "Moonflix · $pretty" + codecDisplayTag(l.codec)
                callback(
                    newExtractorLink(
                        source = "$srcLabel · $pretty",
                        name = name,
                        url = l.url,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = "$PLAYER/"
                        this.quality = qualityFromName(l.resolution)
                        this.headers = PLAYER_HEADERS
                    }
                )
                any = true
            }
            return any
        }

        // ── HV backend: per-quality HLS ──────────────────────────────────────

        private suspend fun fetchHv(
            app: Requests,
            mediaPath: String,
            srcLabel: String,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val json = getJson(app, "$API_HV/$mediaPath") ?: return false
            val streams = json.optJSONArray("streams") ?: return false
            var any = false
            // (v85) WEB-PARITY STARTUP (same as Bingr, the "HDGhar slow"
            // report): per-quality ladders arrived descending → the
            // default/auto pick grabbed the top rung and buffered.
            // Collect, then emit LOW-quality-first at the end.
            val pending = ArrayList<ExtractorLink>()
            val emit: (ExtractorLink) -> Unit = { pending += it }
            val serverSourceLabel = "$srcLabel · HDGhar"
            for (i in 0 until streams.length()) {
                val st = streams.optJSONObject(i) ?: continue
                val url = st.optStringOrNullCp("url") ?: continue
                if (!url.startsWith("http")) continue
                val quality = st.optStringOrNullCp("quality") ?: "Auto"
                if (url.contains(".m3u8", true)) {
                    // Probe the playlist (single GET doubles as the codec probe).
                    val master = runCatching {
                        app.get(url, headers = HEADERS, cacheTime = 0, timeout = 12_000)
                    }.getOrNull()
                    if (master == null || master.code !in 200..299 && master.code != 206 ||
                        !master.text.contains("#EXTM3U")
                    ) continue
                    // (v28) Demuxed A/V masters: emit the master itself.
                    if (emitDemuxedMaster(url, master.text, serverSourceLabel,
                            "Moonflix · HDGhar", "$PLAYER/", HEADERS, emit)
                    ) {
                        any = true
                        continue
                    }
                    // (v27) Expand masters into per-resolution variants and
                    // drop anything this device's decoder can't handle.
                    val variants = parseHlsMasterVariants(master.text, url)
                    if (variants.isNotEmpty()) {
                        variants.distinctBy { it.url }.forEach { v ->
                            val skip = DeviceDecoderProbe.skipReason(
                                videoCodecOf(v.codecs), v.width, v.height
                            )
                            if (skip != null) {
                                Log.d(TAG, "Moonflix HV: skipped ${v.width}x${v.height} (${v.codecs}): $skip")
                                return@forEach
                            }
                            emit(
                                newExtractorLink(
                                    source = serverSourceLabel,
                                    name = "Moonflix · HDGhar" + codecDisplayTag(v.codecs),
                                    url = v.url,
                                    type = ExtractorLinkType.M3U8,
                                ) {
                                    this.referer = "$PLAYER/"
                                    this.quality = if (v.height > 0) {
                                        qualityFromDimensions(v.width, v.height)
                                    } else qualityFromName(quality)
                                    this.headers = HEADERS
                                }
                            )
                            any = true
                        }
                        continue
                    }
                    val codecs = Regex("""CODECS=\"([^\"]+)\"""").find(master.text)?.groupValues?.get(1)
                    val skip = DeviceDecoderProbe.skipReason(videoCodecOf(codecs), 0, 0)
                    if (skip != null) {
                        Log.d(TAG, "Moonflix HV: skipped media playlist ($codecs): $skip")
                        continue
                    }
                    emit(
                        newExtractorLink(
                            source = serverSourceLabel,
                            name = "Moonflix · HDGhar" + codecDisplayTag(codecs),
                            url = url,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.referer = "$PLAYER/"
                            this.quality = qualityFromName(quality)
                            this.headers = HEADERS
                        }
                    )
                    any = true
                } else {
                    emit(
                        newExtractorLink(
                            source = serverSourceLabel,
                            name = "Moonflix · HDGhar",
                            url = url,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = "$PLAYER/"
                            this.quality = qualityFromName(quality)
                            this.headers = HEADERS
                        }
                    )
                    any = true
                }
            }
            // (v85) Emit collected links LOWEST quality first — see the
            // collector note at the top of fetchHv.
            val ordered = pending.sortedBy { l ->
                if (l.quality in 1..4320) l.quality else Int.MAX_VALUE
            }
            if (ordered.isNotEmpty()) {
                Log.d(TAG, "Moonflix HV: emit ${ordered.size} link(s) low-first")
            }
            ordered.forEach(callback)
            return any
        }

        // ── SE backend: named VIP/LUL HLS servers (TV only) ─────────────────

        private suspend fun fetchSe(
            app: Requests,
            tmdbId: Int,
            seasonNum: Int,
            episodeNum: Int,
            srcLabel: String,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val json = getJson(app, "$API_SE/tv/$tmdbId/$seasonNum/$episodeNum") ?: return false
            val sources = json.optJSONArray("sources") ?: return false
            var any = false
            for (i in 0 until sources.length()) {
                val st = sources.optJSONObject(i) ?: continue
                val name = st.optStringOrNullCp("name") ?: "Server"
                // The site itself prefers the proxied URL (bypasses origin
                // referer checks); raw url is the fallback.
                val url = st.optStringOrNullCp("proxy_url")
                    ?: st.optStringOrNullCp("url") ?: continue
                if (!url.startsWith("http")) continue
                if (!url.contains(".m3u8", true) && !url.contains("proxy?url=", true)) continue
                val probe = runCatching {
                    app.get(url, headers = HEADERS + ("Range" to "bytes=0-8192"),
                        cacheTime = 0, timeout = 12_000)
                }.getOrNull()
                if (probe == null || probe.code !in 200..299 && probe.code != 206) continue
                if (probe.text.isNotBlank() && !probe.text.startsWith("#EXTM3U") &&
                    !probe.text.contains("mpegurl", true) && probe.text.length < 40
                ) continue
                // (v28) Demuxed A/V masters: emit the master itself.
                if (probe.text.startsWith("#EXTM3U") &&
                    emitDemuxedMaster(url, probe.text, "$srcLabel · $name",
                        "Moonflix · $name", "$PLAYER/", HEADERS, callback)
                ) {
                    any = true
                    continue
                }
                // (v27) Expand masters into variants + device-decoder gate.
                val variants = if (probe.text.startsWith("#EXTM3U")) {
                    parseHlsMasterVariants(probe.text, url)
                } else emptyList()
                if (variants.isNotEmpty()) {
                    variants.distinctBy { it.url }.forEach { v ->
                        val skip = DeviceDecoderProbe.skipReason(
                            videoCodecOf(v.codecs), v.width, v.height
                        )
                        if (skip != null) {
                            Log.d(TAG, "Moonflix SE: skipped ${v.width}x${v.height} (${v.codecs}) on $name: $skip")
                            return@forEach
                        }
                        callback(
                            newExtractorLink(
                                source = "$srcLabel · $name",
                                name = "Moonflix · $name" + codecDisplayTag(v.codecs),
                                url = v.url,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = "$PLAYER/"
                                this.quality = if (v.height > 0) {
                                    qualityFromDimensions(v.width, v.height)
                                } else qualityFromName(url)
                                this.headers = HEADERS
                            }
                        )
                        any = true
                    }
                    continue
                }
                callback(
                    newExtractorLink(
                        source = "$srcLabel · $name",
                        name = "Moonflix · $name · HLS",
                        url = url,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = "$PLAYER/"
                        this.quality = qualityFromName(url)
                        this.headers = HEADERS
                    }
                )
                any = true
            }
            return any
        }

        // ── Subtitles (CH) ──────────────────────────────────────────────────

        private suspend fun fetchSubs(
            app: Requests,
            mediaPath: String,
            subtitleCallback: (SubtitleFile) -> Unit,
        ) {
            val resp = runCatching {
                app.get("$API_CH/subtitles/$mediaPath", headers = HEADERS,
                    cacheTime = 0, timeout = 15_000)
            }.getOrNull() ?: return
            if (resp.code !in 200..299 || resp.text.isBlank()) return
            val trimmed = resp.text.trimStart()
            // Shape is either a bare array or {"subtitles": [...]}.
            val arr = when {
                trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
                trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrNull()
                    ?.optJSONArray("subtitles")
                else -> null
            } ?: return
            var emitted = 0
            for (i in 0 until arr.length()) {
                if (emitted >= 12) break
                val s = arr.optJSONObject(i) ?: continue
                val url = s.optStringOrNullCp("url") ?: s.optStringOrNullCp("file")
                    ?: s.optStringOrNullCp("src") ?: continue
                if (!url.startsWith("http")) continue
                val lang = s.optStringOrNullCp("lang") ?: s.optStringOrNullCp("language")
                    ?: s.optStringOrNullCp("label") ?: "Unknown"
                subtitleCallback(SubtitleFile("[Moonflix] ${lang.uppercase()}", url))
                emitted++
            }
        }

        private suspend fun getJson(app: Requests, url: String): JSONObject? {
            val resp = runCatching {
                app.get(url, headers = HEADERS, cacheTime = 0, timeout = 20_000)
            }.getOrNull() ?: return null
            if (resp.code !in 200..299 || resp.text.isBlank()) return null
            return runCatching { JSONObject(resp.text) }.getOrNull()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 8: FM FTP  (https://fmftp.net)
    //
    //  Parser ported 1:1 from FmFtpProvider.kt (v43). Site = React SPA over a
    //  public "Cinefy" REST API (/api/) + nginx autoindex file listings:
    //    • GET /api/search?search={q} → BARE JSON array, movies+shows mixed;
    //      item.Library.type=="TV_SHOW" marks series (movies carry file_path).
    //    • GET /api/movies/{id} → detail; "url" = public FILE path (raw
    //      spaces → percent-encoded before emit). One row PER FILE, so one
    //      film often has several rows = several quality renditions.
    //    • GET /api/tv-shows/{id} → detail; "url" = public DIRECTORY →
    //      autoindex lists "Season N 1080p/" folders holding files named
    //      "Title (Year) - SxxEyy - Name.mkv"; quality tag lives on the
    //      FOLDER name. Episode files verified HTTP 206 direct-playable.
    // ════════════════════════════════════════════════════════════════════════
    internal object FmFtpResolver : SourceResolver {
        private const val SITE = "https://fmftp.net"
        private const val API = "$SITE/api"
        private const val LABEL = "FM FTP"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
        )
        private val VIDEO_EXT = listOf(".mp4", ".mkv", ".avi", ".m4v", ".mov", ".webm", ".ts")
        private val SUB_EXT = listOf(".srt", ".vtt", ".ass", ".ssa")

        private data class Cand(val url: String, val candTitle: String)

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            val text = runCatching {
                app.get("$API/search?search=${encodeUrl(title)}", headers = HEADERS, timeout = 12_000)
            }.getOrNull()?.takeIf { it.code in 200..299 }?.text ?: return false
            val arr = runCatching { JSONArray(text) }.getOrNull() ?: return false

            val wantShow = !isMovie
            val candidates = mutableListOf<Cand>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optInt("id", 0)
                if (id == 0) continue
                val lib = o.optJSONObject("Library")
                val isShow = (lib?.optString("type") == "TV_SHOW") ||
                    (!o.has("file_path") && o.has("path"))
                if (isShow != wantShow) continue
                val t = o.optString("title").trim()
                if (t.isBlank()) continue
                candidates += Cand(if (isShow) "$API/tv-shows/$id" else "$API/movies/$id", t)
            }
            if (candidates.isEmpty()) return false

            // Identity tier → fuzzy tier (same two-tier gate as CTG/CineplexBD),
            // sorted by similarity. Movies: keep up to 6 (each film row = one
            // file = one quality rendition → multi-quality chips). Shows: 4.
            val distinct = candidates.distinctBy { it.url }
            val tier1 = distinct.filter { isSameMediaTitle(it.candTitle, title, year) }
            val picks = (tier1.ifEmpty {
                distinct.filter { isFuzzySameMedia(it.candTitle, title, year) }
            }).sortedByDescending { titleSimilarity(it.candTitle, title) }
                .take(if (isMovie) 6 else 4)
            if (picks.isEmpty()) return false

            val srcLabel = "$labelPrefix • $LABEL"
            var any = false
            picks.forEach { pick ->
                val detail = fetchJson(app, pick.url) ?: return@forEach
                val rel = detail.optString("url").trim()
                if (rel.isBlank()) return@forEach
                if (isMovie) {
                    val abs = SITE + encodeFmPath(rel)
                    runCatching {
                        callback(
                            newExtractorLink(
                                srcLabel, "$srcLabel - Direct", abs, ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$SITE/"
                                this.headers = HEADERS
                                this.quality = qualityFromName(rel)
                            }
                        )
                    }.onSuccess { any = true }
                } else if (season != null && episode != null) {
                    if (emitFmEpisode(
                            app, rel, season, episode, srcLabel, subtitleCallback, callback
                        )
                    ) any = true
                }
            }
            return any
        }

        /** Mirror of FmFtpProvider.emitEpisodeFiles: walk the show dir →
         *  matching season folder(s) → Sxx Eyy file(s); bare-Exx only
         *  as a last resort (covers flat show roots).
         *  (v90 — 2026-08-01 site-update fix) fmftp.net restructured its
         *  show archives to "s01/" folders holding "Title S01 E01.mkv"
         *  (was "Season N <quality>/" + "S01E01"). Both layouts accepted:
         *  seasonRe matches the sNN form at a boundary, epRe tolerates
         *  separators between the Sxx and Eyy tokens. */
        private suspend fun emitFmEpisode(
            app: Requests,
            dir: String,
            season: Int,
            episode: Int,
            srcLabel: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val base = SITE + encodeFmPath(dir.trimEnd('/') + "/")
            val seasonRe = Regex("""(?i)(?:^|[\s._-])(?:season[\s._-]*|s)0*""" + season + """(?=\D|$)""")
            val epRe = Regex("""(?i)S0*""" + season + """[\s._-]*E0*""" + episode + """(\D|$)""")
            val eOnlyRe = Regex("""(?i)(\s|\.|_|-|^)E0*""" + episode + """(\D|$)""")

            val topDoc = fetchDoc(app, base) ?: return false
            val topLinks = fmIndexLinks(topDoc)
            val seasonDirs = topLinks
                .filter { it.endsWith("/") }
                .filter { seasonRe.containsMatchIn(fmDecode(it)) }

            var any = false
            var sawSxxEyy = false

            seasonDirs.forEach { dirHref ->
                val folderName = fmDecode(dirHref).trimEnd('/')
                val folderUrl = base + dirHref
                val doc = fetchDoc(app, folderUrl) ?: return@forEach
                fmIndexLinks(doc).filter { !it.endsWith("/") }.forEach { fileHref ->
                    val decoded = fmDecode(fileHref)
                    if (!epRe.containsMatchIn(decoded)) return@forEach
                    val abs = folderUrl + fileHref
                    if (SUB_EXT.any { decoded.endsWith(it, ignoreCase = true) }) {
                        runCatching { subtitleCallback(newSubtitleFile("[$LABEL] Subtitle", abs)) }
                        return@forEach
                    }
                    if (!VIDEO_EXT.any { decoded.endsWith(it, ignoreCase = true) }) return@forEach
                    sawSxxEyy = true
                    runCatching {
                        callback(
                            newExtractorLink(
                                srcLabel, "$srcLabel - Direct", abs, ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$SITE/"
                                this.headers = HEADERS
                                this.quality = qualityFromName(folderName + "/" + decoded)
                            }
                        )
                    }.onSuccess { any = true }
                }
            }

            if (!sawSxxEyy) {
                val flat = mutableListOf<Triple<String, String, String>>()
                topLinks.filter { !it.endsWith("/") }
                    .forEach { flat += Triple(base, it, fmDecode(it)) }
                seasonDirs.forEach { dirHref ->
                    val folderUrl = base + dirHref
                    fetchDoc(app, folderUrl)?.let { doc ->
                        fmIndexLinks(doc).filter { !it.endsWith("/") }
                            .forEach { flat += Triple(folderUrl, it, fmDecode(it)) }
                    }
                }
                flat.forEach { (folderUrl, href, decoded) ->
                    if (!eOnlyRe.containsMatchIn(decoded)) return@forEach
                    if (!VIDEO_EXT.any { decoded.endsWith(it, ignoreCase = true) }) return@forEach
                    runCatching {
                        callback(
                            newExtractorLink(
                                srcLabel, "$srcLabel - Direct", folderUrl + href,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$SITE/"
                                this.headers = HEADERS
                                this.quality = qualityFromName(decoded)
                            }
                        )
                    }.onSuccess { any = true }
                }
            }
            return any
        }

        private fun fmIndexLinks(doc: Document): List<String> =
            doc.select("a[href]")
                .mapNotNull { a ->
                    val href = a.attr("href").trim()
                    if (href.isBlank() || href.startsWith("../") || href.startsWith("?") ||
                        href.startsWith("/")) null
                    else href
                }
                .distinct()

        private suspend fun fetchJson(app: Requests, url: String): JSONObject? {
            val resp = runCatching { app.get(url, headers = HEADERS, timeout = 15_000) }.getOrNull()
                ?: return null
            if (resp.code !in 200..299 || resp.text.isBlank()) return null
            return runCatching { JSONObject(resp.text) }.getOrNull()
        }

        private suspend fun fetchDoc(app: Requests, url: String): Document? {
            val resp = runCatching { app.get(url, headers = HEADERS, timeout = 15_000) }.getOrNull()
                ?: return null
            if (resp.code !in 200..299 || resp.text.isBlank()) return null
            return runCatching { Jsoup.parse(resp.text, url) }.getOrNull()
        }

        private fun encodeFmPath(p: String): String = buildString(p.length + 16) {
            for (c in p) {
                when (c) {
                    ' ' -> append("%20")
                    '#' -> append("%23")
                    '?' -> append("%3F")
                    '%' -> append("%25")
                    else -> append(c)
                }
            }
        }

        private fun fmDecode(s: String): String =
            runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 9: Mediaserver  (http://103.225.94.27/mediaserver)
    //
    //  Parser ported 1:1 from MediaserverProvider.kt (v43). Plain server-
    //  rendered WordPress (streamTube), no login; ONE flat post type under
    //  /index.php/video/<id>/ — movies AND single episodes ("One Piece
    //  S01E08") are sibling posts. Player = inline <video-js data-settings
    //  ="JSON"> whose sources[] are direct mp4 URLs (206-verified).
    //  Series matching: post title must carry the requested SxxEyy token,
    //  and the title with that token stripped must pass the title gate.
    // ════════════════════════════════════════════════════════════════════════
    internal object MediaserverResolver : SourceResolver {
        private const val SITE = "http://103.225.94.27/mediaserver"
        private const val LABEL = "Mediaserver"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
        )
        private val MEDIA_URL_RE = Regex(
            """https?://[^\s"'<>\\]+\.(?:mp4|mkv|m3u8|webm|m4v)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )

        private data class Cand(val url: String, val postTitle: String)

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            val searchUrl = "$SITE/index.php/?s=${encodeUrl(title)}"
            val resp = runCatching {
                app.get(searchUrl, headers = HEADERS, timeout = 12_000)
            }.getOrNull() ?: return false
            if (resp.code !in 200..299 || resp.text.isBlank()) return false
            val doc = Jsoup.parse(resp.text, searchUrl)

            val cards = doc.select("h2.post-meta__title a[href], h2.post-title a[href]")
                .mapNotNull { a ->
                    val href = a.absUrl("href").ifBlank { a.attr("href") }.trim()
                    val t = a.text().trim()
                    if (href.isBlank() || t.isBlank()) null else Cand(href, t)
                }
                .distinctBy { it.url }
            if (cards.isEmpty()) return false

            val picks: List<Cand> = if (!isMovie && season != null && episode != null) {
                val sxeRe = Regex("""(?i)S0*""" + season + """E0*""" + episode + """(\D|$)""")
                cards.mapNotNull { c ->
                    val m = sxeRe.find(c.postTitle) ?: return@mapNotNull null
                    // Base title = post title with the SxxEyy token (and the
                    // episode name after it) removed → "One Piece S01E08
                    // Romance Dawn" → "One Piece".
                    val base = c.postTitle.substring(0, m.range.first)
                        .trim(' ', '-', '_', ':', '.', '(', ')', '[', ']')
                    if (base.isBlank()) return@mapNotNull null
                    c.copy(postTitle = base)
                }.let { epCards ->
                    val tier1 = epCards.filter { isSameMediaTitle(it.postTitle, title, year) }
                    (tier1.ifEmpty {
                        epCards.filter { isFuzzySameMedia(it.postTitle, title, year) }
                    }).sortedByDescending { titleSimilarity(it.postTitle, title) }.take(4)
                }
            } else {
                val tier1 = cards.filter { isSameMediaTitle(it.postTitle, title, year) }
                (tier1.ifEmpty {
                    cards.filter { isFuzzySameMedia(it.postTitle, title, year) }
                }).sortedByDescending { titleSimilarity(it.postTitle, title) }.take(4)
            }
            if (picks.isEmpty()) return false

            val srcLabel = "$labelPrefix • $LABEL"
            var any = false
            picks.forEach { pick ->
                val page = runCatching {
                    app.get(pick.url, headers = HEADERS, timeout = 15_000)
                }.getOrNull() ?: return@forEach
                if (page.code !in 200..299) return@forEach
                val pdoc = Jsoup.parse(page.text, pick.url)
                val emitted = LinkedHashSet<String>()

                // 1) Primary: inline video-js settings JSON.
                pdoc.select("video-js[data-settings]").forEach { vj ->
                    val settings = runCatching { JSONObject(vj.attr("data-settings")) }.getOrNull()
                        ?: return@forEach
                    val sources = settings.optJSONArray("sources") ?: JSONArray()
                    for (i in 0 until sources.length()) {
                        val s = sources.optJSONObject(i) ?: continue
                        val src = s.optString("src").trim()
                        if (src.isBlank() || !emitted.add(src)) continue
                        if (emitMsMedia(src, srcLabel, callback)) any = true
                    }
                }
                // 2) Plain <video><source> fallback.
                pdoc.select("video source[src], video[src]").forEach { el ->
                    val src = el.absUrl("src").ifBlank { el.attr("src") }.trim()
                    if (src.isBlank() || !emitted.add(src)) return@forEach
                    if (emitMsMedia(src, srcLabel, callback)) any = true
                }
                // 3) Regex last resort (lazy-init players).
                if (emitted.isEmpty()) {
                    MEDIA_URL_RE.findAll(page.text).forEach { m ->
                        val src = m.value
                        if (src.contains("/wp-content/uploads/")) return@forEach
                        if (!emitted.add(src)) return@forEach
                        if (emitMsMedia(src, srcLabel, callback)) any = true
                    }
                }
            }
            return any
        }

        private suspend fun emitMsMedia(
            src: String,
            srcLabel: String,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val isHls = src.contains(".m3u8", ignoreCase = true)
            return try {
                callback(
                    newExtractorLink(
                        srcLabel, "$srcLabel - Direct", src,
                        if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$SITE/"
                        this.headers = HEADERS
                        this.quality = qualityFromName(src)
                    }
                )
                true
            } catch (t: Throwable) {
                Log.d(TAG, "Mediaserver emit: ${t.message}")
                false
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver: CineJoy  (https://cinejoy.to)   — v94
    //
    //  SvelteKit SPA whose streams ride a bespoke wire protocol on
    //  api.shegu.st ("lumen-wire-v2", proof-of-work gated). The complete
    //  client was re-implemented from scratch and PROVEN live against the
    //  api (movie + TV + all five providers, 2026-08-03):
    //
    //    rid = Wk(canonical): 16B salt t + AES-256-CTR(kk[32:64],
    //      counter kk[64:80])( 0x02 | 12B iv m | AES-256-GCM(kk[0:32],
    //      iv m, aad "lumen-wire-v2|c2s")(canonical) ), base64url — where
    //      kk = HKDF-SHA256(ikm=IKM, salt=t, info="lumen-wire-v2|{ns}", 80).
    //    GET /challenge?rid={rid}   → {v,b,s,e,n,r,p,d,k,g}
    //    token: smallest Q≥0 whose scrypt("pow2|{b}|{s}|{Q}",
    //      salt=SHA256("pow2-salt|{s}|{b}"), N=n,r=r,p=p) has ≥d leading
    //      zero bits; header X-At: base64(json(challenge ⊕ {c:Q})).
    //    GET /{rid} (X-At) → same wire sealed, decrypt info "…|s2c" →
    //      {"stream":[{"type":"hls","playlist":…,"captions":[…]}]}
    //    Providers Lisbon/Solara/Joy/Arrow/Sakura each keep their own
    //    catalogue; a 404 at the watch call = provider lacks the title and
    //    is skipped silently (never an empty row).
    //    Canonical paths (both live-verified):
    //        movie: "/{P}/movie/{tmdb}?tmdb={tmdb}"
    //        tv:    "/{P}/series?episode={e}&season={s}&tmdb={tmdb}"
    //      (params sorted alphabetically; "tv"/id-in-path variants all 404.)
    //
    //  PLAY-OUT GATES (the no-2004 / no-3003 guarantee), all live-probed:
    //    • help.earthcleaner.cc (Lisbon): 418 without it — needs exactly
    //      Referer: https://cinejoy.to/ (any UA, or none, passes). Master
    //      is SPLIT-AUDIO (#EXT-X-MEDIA) → keepMaster (v93's AniZone fix).
    //    • ibm.earthcleaner.cc (Solara): 403 without that same Referer.
    //    • api.shegu.st/synthetic/… (Sakura): same Referer needed.
    //    • api.shegu.st/subtitles/…: open (200 bare) — safe subtitle tracks.
    //  Every emitted link carries Referer https://cinejoy.to/ and STILL
    //  passes the device-side probePlayable check before it can be listed.
    //  The scrypt below is a self-contained RFC-7914 implementation (byte-
    //  checked against the RFC vectors) — no external crypto dependency.
    // ════════════════════════════════════════════════════════════════════════

    internal object CineJoyResolver : SourceResolver {
        private const val SITE = "https://cinejoy.to"
        private const val API = "https://api.shegu.st"
        private const val LABEL = "CineJoy"
        private const val INFO_PREFIX = "lumen-wire-v2|"

        private val PROVIDERS = listOf("Lisbon", "Solara", "Joy", "Arrow", "Sakura")
        private val REF_HEADERS = mapOf("Referer" to "$SITE/")

        // IKM unwrapped from the site's own bundle (z6 ^ U6, byte-wise XOR).
        private val IKM: ByteArray = run {
            val z6 = intArrayOf(208,332*26+2103+-10712,-10078+3413*3,239,-7*1145+-6651*-1+1606*1,-1306*3+7304+2*-1693,14837+-1*14681,4751*1+-158+-4348,209,-1*8131+-383*21+-902*-18,215,57,1*6031+-6695+725,152,2493+1*-2473,3127+7*-790+2622,-739*2+-4576+8*760,5619+-2919*-2+17*-661,-1084*6+3677+2950,207,-5729+449*13,3094+-5*1277+-1165*-3,-1*-274+4211+-1*4413,757*1+131+-666,105,1*-7062+-1035+-6*-1361,255,225+1*-4204+4131,9086+16*-563,1579*1+-9221*-1+10765*-1,1765*-2+4426+-712*1,991*9+-4252+-4431)
            val u6 = intArrayOf(-1*-7911+7293+-15046,-3977*-1+-4010+114,4059*-1+-3274*1+7360,101,2070+577*-1+-1387,-3203+19*397+-4113,1*-8339+-2186+-10537*-1,-5607+962*6,-1*615+-1287*-2+-1865,-8639+910*-8+16068,-8543+10*-563+14377,8116*-1+11*557+2213*1,29,4147+1306*-3,4*268+-1*1613+542,1541*3+1871*-3+1128,431*-22+-2*-1164+7267,48,106,119,1*9004+1*2917+11717*-1,1381*6+-8188+28,-11*-511+1*-7053+1519,-134+9449*-1+9664,9897*1+1*-4231+-5498,470+7034*-1+6728,1674*-2+1291+2082,244,1*-947+-572+1727,182,19,-9571+1*-7311+16885)
            ByteArray(32) { i -> ((z6[i] xor u6[i]) and 0xff).toByte() }
        }
        private val RNG = SecureRandom()

        private val SUB_LANG_NAMES = mapOf(
            "en" to "English", "es" to "Spanish", "pt" to "Portuguese",
            "fr" to "French", "de" to "German", "ar" to "Arabic",
            "it" to "Italian", "ru" to "Russian", "hi" to "Hindi",
            "id" to "Indonesian", "tr" to "Turkish", "vi" to "Vietnamese",
            "th" to "Thai", "ms" to "Malay", "pl" to "Polish",
            "nl" to "Dutch", "ja" to "Japanese", "ko" to "Korean",
            "zh" to "Chinese", "ro" to "Romanian", "cs" to "Czech",
            "hu" to "Hungarian", "el" to "Greek", "sv" to "Swedish",
            "da" to "Danish", "fi" to "Finnish", "no" to "Norwegian",
            "fa" to "Persian", "he" to "Hebrew", "uk" to "Ukrainian",
            "bn" to "Bengali", "ur" to "Urdu",
        )

        // ── scrypt (RFC 7914) — vector-checked, zero dependencies ────────
        private fun rotl(a: Int, b: Int): Int = (a shl b) or (a ushr (32 - b))

        private fun qr(v: IntArray, a: Int, b: Int, c: Int, d: Int) {
            v[b] = v[b] xor rotl(v[a] + v[d], 7)
            v[c] = v[c] xor rotl(v[b] + v[a], 9)
            v[d] = v[d] xor rotl(v[c] + v[b], 13)
            v[a] = v[a] xor rotl(v[d] + v[c], 18)
        }

        private fun salsa208(block: ByteArray) {
            val x = IntArray(16) { i ->
                (block[i * 4].toInt() and 0xff) or
                    ((block[i * 4 + 1].toInt() and 0xff) shl 8) or
                    ((block[i * 4 + 2].toInt() and 0xff) shl 16) or
                    ((block[i * 4 + 3].toInt() and 0xff) shl 24)
            }
            val z = x.copyOf()
            repeat(4) {
                qr(x, 0, 4, 8, 12); qr(x, 5, 9, 13, 1)
                qr(x, 10, 14, 2, 6); qr(x, 15, 3, 7, 11)
                qr(x, 0, 1, 2, 3); qr(x, 5, 6, 7, 4)
                qr(x, 10, 11, 8, 9); qr(x, 15, 12, 13, 14)
            }
            for (i in 0 until 16) {
                val w = x[i] + z[i]
                block[i * 4] = w.toByte()
                block[i * 4 + 1] = (w ushr 8).toByte()
                block[i * 4 + 2] = (w ushr 16).toByte()
                block[i * 4 + 3] = (w ushr 24).toByte()
            }
        }

        private fun blockMix(x: ByteArray, r: Int): ByteArray {
            val xw = x.copyOfRange(x.size - 64, x.size)
            val y = ByteArray(x.size)
            for (i in 0 until 2 * r) {
                for (k in 0 until 64) xw[k] = (xw[k].toInt() xor x[i * 64 + k].toInt()).toByte()
                salsa208(xw)
                val off = if (i % 2 == 0) (i / 2) * 64 else (r + i / 2) * 64
                System.arraycopy(xw, 0, y, off, 64)
            }
            return y
        }

        private fun pbkdf2(pass: ByteArray, salt: ByteArray, iter: Int, dkLen: Int): ByteArray {
            // HMAC zero-pads short keys to its block size, so a 64-byte zero
            // key is bit-identical to an empty one (kept for the RFC vector
            // path; cinejoy passwords are never empty).
            val key = if (pass.isEmpty()) ByteArray(64) else pass
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            val blocks = (dkLen + 31) / 32
            val outAll = ByteArray(blocks * 32)
            var written = 0
            for (i in 1..blocks) {
                val msg = ByteArray(salt.size + 4)
                System.arraycopy(salt, 0, msg, 0, salt.size)
                msg[salt.size] = (i ushr 24).toByte()
                msg[salt.size + 1] = (i ushr 16).toByte()
                msg[salt.size + 2] = (i ushr 8).toByte()
                msg[salt.size + 3] = i.toByte()
                var u = mac.doFinal(msg)
                val t = u.copyOf()
                for (c in 2..iter) {
                    u = mac.doFinal(u)
                    for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
                System.arraycopy(t, 0, outAll, written, 32)
                written += 32
            }
            return outAll.copyOf(dkLen)
        }

        private fun scrypt(
            pass: ByteArray, salt: ByteArray,
            n: Int, r: Int, p: Int, dkLen: Int,
        ): ByteArray {
            val blockSize = 128 * r
            val b = pbkdf2(pass, salt, 1, p * blockSize)
            val v = ByteArray(n * blockSize)
            var x = ByteArray(blockSize)
            for (i in 0 until p) {
                System.arraycopy(b, i * blockSize, x, 0, blockSize)
                for (j in 0 until n) {
                    System.arraycopy(x, 0, v, j * blockSize, blockSize)
                    x = blockMix(x, r)
                }
                for (j in 0 until n) {
                    // Integerify: LE u64 = first 8 bytes of the last 64B block
                    var integ = 0L
                    for (k in 0 until 8) {
                        integ = integ or ((x[blockSize - 64 + k].toLong() and 0xff) shl (8 * k))
                    }
                    val vIdx = (integ and (n - 1).toLong()).toInt() * blockSize
                    for (k in 0 until blockSize) {
                        x[k] = (x[k].toInt() xor v[vIdx + k].toInt()).toByte()
                    }
                    x = blockMix(x, r)
                }
                System.arraycopy(x, 0, b, i * blockSize, blockSize)
            }
            return pbkdf2(pass, b, 1, dkLen)
        }

        // ── lumen-wire-v2 ────────────────────────────────────────────────
        private fun hkdf(salt: ByteArray, info: ByteArray, len: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
            val prk = mac.doFinal(IKM)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val okm = ByteArray(len)
            var t = ByteArray(0)
            var pos = 0
            var i = 1
            while (pos < len) {
                mac.reset()
                mac.update(t)
                mac.update(info)
                mac.update(i.toByte())
                t = mac.doFinal()
                val take = minOf(t.size, len - pos)
                System.arraycopy(t, 0, okm, pos, take)
                pos += take
                i++
            }
            return okm
        }

        private class WireKeys(val gcm: SecretKeySpec, val ctr: SecretKeySpec, val ctrIv: ByteArray)

        private fun s7(t: ByteArray, ns: String): WireKeys {
            val kk = hkdf(t, (INFO_PREFIX + ns).toByteArray(Charsets.UTF_8), 80)
            return WireKeys(
                SecretKeySpec(kk, 0, 32, "AES"),
                SecretKeySpec(kk, 32, 32, "AES"),
                kk.copyOfRange(64, 80),
            )
        }

        private fun b64url(b: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        private fun ub64(s0: String): ByteArray {
            var s = s0.replace('-', '+').replace('_', '/')
            while (s.length % 4 != 0) s += "="
            return Base64.getDecoder().decode(s)
        }

        private fun wk(payload: ByteArray, ns: String): String {
            val t = ByteArray(16).also { RNG.nextBytes(it) }
            val m = ByteArray(12).also { RNG.nextBytes(it) }
            val k = s7(t, ns)
            val aad = (INFO_PREFIX + ns).toByteArray(Charsets.UTF_8)
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.ENCRYPT_MODE, k.gcm, GCMParameterSpec(128, m))
            aes.updateAAD(aad)
            val ct = aes.doFinal(payload)
            val u = ByteArray(1 + 12 + ct.size)
            u[0] = 2
            System.arraycopy(m, 0, u, 1, 12)
            System.arraycopy(ct, 0, u, 13, ct.size)
            val ctr = Cipher.getInstance("AES/CTR/NoPadding")
            ctr.init(Cipher.ENCRYPT_MODE, k.ctr, IvParameterSpec(k.ctrIv))
            val masked = ctr.doFinal(u)
            val out = ByteArray(16 + masked.size)
            System.arraycopy(t, 0, out, 0, 16)
            System.arraycopy(masked, 0, out, 16, masked.size)
            return b64url(out)
        }

        private fun ck(text: String, ns: String): ByteArray {
            val t2 = ub64(text.trim())
            val m2 = t2.copyOfRange(0, 16)
            val k = s7(m2, ns)
            val aad = (INFO_PREFIX + ns).toByteArray(Charsets.UTF_8)
            val ctr = Cipher.getInstance("AES/CTR/NoPadding")
            ctr.init(Cipher.DECRYPT_MODE, k.ctr, IvParameterSpec(k.ctrIv))
            val b = ctr.doFinal(t2.copyOfRange(16, t2.size))
            if (b.isEmpty() || b[0].toInt() != 2) {
                throw IllegalStateException("malformed packet")
            }
            val iv = b.copyOfRange(1, 13)
            val ct = b.copyOfRange(13, b.size)
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.DECRYPT_MODE, k.gcm, GCMParameterSpec(128, iv))
            aes.updateAAD(aad)
            return aes.doFinal(ct)
        }

        private fun leadingZeroBits(u: ByteArray): Int {
            var n = 0
            for (d in u) {
                val v = d.toInt() and 0xff
                if (v == 0) { n += 8; continue }
                n += Integer.numberOfLeadingZeros(v) - 24
                break
            }
            return n
        }

        private data class CjStream(
            val playlist: String,
            val captions: List<Pair<String, String>>,   // (langLabel, url)
        )

        /** One provider end-to-end; null when it doesn't carry the title. */
        private suspend fun fetchProvider(
            app: Requests,
            provider: String,
            canonical: String,
        ): List<CjStream>? {
            val rid = runCatching {
                wk(canonical.toByteArray(Charsets.UTF_8), "c2s")
            }.getOrNull() ?: return null
            // (v95) browser-parity headers on the api calls too — shegu is
            // Cloudflare-fronted, and the closer the request looks to the
            // site's own SPA the friendlier the edge is to old clients.
            val apiHeaders = mapOf(
                "User-Agent" to UA,
                "Referer" to "$SITE/",
                "Origin" to SITE,
            )
            val chResp = runCatching {
                app.get(
                    "$API/challenge?rid=${encodeUrl(rid)}",
                    headers = apiHeaders, timeout = 12_000,
                )
            }.getOrNull() ?: return null
            if (chResp.code !in 200..299) return null
            val ch = runCatching { JSONObject(chResp.text) }.getOrNull() ?: return null
            val n = ch.optInt("n", 0)
            val rr = ch.optInt("r", 0)
            val pp = ch.optInt("p", 0)
            val dd = ch.optInt("d", -1)
            val bS = ch.optStringOrNullCp("b") ?: return null
            val sS = ch.optStringOrNullCp("s") ?: return null
            // old-TV safety rails: anything outside observed parameters → bail
            if (n <= 1 || (n and (n - 1)) != 0 || n > (1 shl 17)) return null
            if (rr <= 0 || rr > 16 || pp <= 0 || pp > 8 || dd < 0 || dd > 32) return null
            val salt = MessageDigest.getInstance("SHA-256")
                .digest("pow2-salt|$sS|$bS".toByteArray(Charsets.UTF_8))
            var q = -1L
            var i = 0L
            while (i < 2_000_000L) {
                val h = scrypt(
                    "pow2|$bS|$sS|$i".toByteArray(Charsets.UTF_8),
                    salt, n, rr, pp, 32,
                )
                if (leadingZeroBits(h) >= dd) { q = i; break }
                i++
            }
            if (q < 0) return null
            ch.put("c", q)
            val token = Base64.getEncoder()
                .encodeToString(ch.toString().toByteArray(Charsets.UTF_8))
            val wResp = runCatching {
                app.get(
                    "$API/$rid",
                    headers = apiHeaders + ("X-At" to token),
                    timeout = 15_000,
                )
            }.getOrNull() ?: return null
            if (wResp.code !in 200..299) return null   // 404 = provider lacks it
            val pt = runCatching {
                String(ck(wResp.text.trim(), "s2c"), Charsets.UTF_8)
            }.getOrNull() ?: return null
            val j = runCatching { JSONObject(pt) }.getOrNull() ?: return null
            val streams = j.optJSONArray("stream") ?: return null
            val out = mutableListOf<CjStream>()
            for (si in 0 until streams.length()) {
                val st = streams.optJSONObject(si) ?: continue
                val pl = st.optStringOrNullCp("playlist") ?: continue
                if (!pl.startsWith("http")) continue
                val caps = mutableListOf<Pair<String, String>>()
                st.optJSONArray("captions")?.let { arr ->
                    for (ci in 0 until arr.length()) {
                        val co = arr.optJSONObject(ci) ?: continue
                        val cu = co.optStringOrNullCp("url") ?: continue
                        if (!cu.startsWith("http")) continue
                        val code = co.optStringOrNullCp("language")
                            ?: co.optStringOrNullCp("id") ?: ""
                        val lang = SUB_LANG_NAMES[code.lowercase().substringBefore('-')]
                            ?: code.uppercase().ifBlank { "Subtitle" }
                        caps += lang to cu
                    }
                }
                out += CjStream(pl, caps)
            }
            return out.takeIf { it.isNotEmpty() }
        }

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            // The whole API is keyed by TMDB id — without it there is no
            // path in (same rule as Cineby).
            if (tmdbId == null || tmdbId <= 0) return false
            val srcLabel = "$labelPrefix • $LABEL"
            val seasonNo = season ?: 1
            val episodeNo = if (isMovie) 1 else (episode ?: 1)
            val canonicals = PROVIDERS.map { p ->
                p to (
                    if (isMovie) "/$p/movie/$tmdbId?tmdb=$tmdbId"
                    else "/$p/series?episode=$episodeNo&season=$seasonNo&tmdb=$tmdbId"
                    )
            }

            // Parallel provider fan-out (3 at a time — each does its own
            // proof-of-work, so parallelism hides most of the scrypt
            // wall-clock too: one resolve ≈ one PoW, not five serial ones).
            val cands = java.util.Collections.synchronizedList(
                mutableListOf<WizstreamAnimeSources.MediaCandidate>(),
            )
            val capAcc = java.util.Collections.synchronizedList(
                mutableListOf<Pair<String, String>>(),
            )
            val gate = Semaphore(3)
            coroutineScope {
                canonicals.map { (p, canonical) ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            runCatching {
                                fetchProvider(app, p, canonical)
                            }.onFailure {
                                Log.d(TAG, "CineJoy: $p failed: ${it.message}")
                            }.getOrNull()?.forEach { st ->
                                capAcc.addAll(st.captions)
                                val name = "$srcLabel · $p"
                                cands += WizstreamAnimeSources.MediaCandidate(
                                    url = st.playlist,
                                    sourceLabel = name, name = name,
                                    referer = "$SITE/",
                                    headers = REF_HEADERS,
                                    // split-audio masters (Lisbon/earthcleaner)
                                    // — the v93 AniZone keep-master fix applies
                                    keepMaster = true,
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
            if (cands.isEmpty()) {
                Log.d(TAG, "CineJoy: no provider carried tmdb=$tmdbId")
                return false
            }
            // api-hosted subtitle tracks (header-free — verified 200 bare),
            // deduped; then the probe-gated emission: a dead or non-media
            // playlist can never reach a row.
            val seenSubs = mutableSetOf<String>()
            capAcc.forEach { (lang, u) ->
                if (seenSubs.add(u)) subtitleCallback(SubtitleFile(lang, u))
            }
            return WizstreamAnimeSources.emitMediaCandidates(
                cands.toList(), subtitleCallback, callback,
            )
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  (v96, user: "add shuttletv.su") Resolver 11: ShuttleTV
    //  TMDB-keyed web source via cinesrc.st embed.
    // ════════════════════════════════════════════════════════════════════════
    //
    // Recon (live-verified 2026-08-05, sandbox):
    //  • shuttletv.su is a Next.js App Router catalogue that uses TMDB
    //    directly (their public TMDB key ea021b3b0775c8531592713ab727f254
    //    is in the bundle).
    //  • The /api/* endpoints on shuttletv.su (/api/folders, /api/watchlist,
    //    /api/watch-progress, /api/settings, /api/recommendations,
    //    /api/upcoming) are all USER-features gated by Better Auth (signup
    //    is open) — NOT needed for streaming.
    //  • The actual stream is served by cinesrc.st — a self-described
    //    "Free video streaming API ... Built by the ShuttleTV team" — a
    //    TMDB-id-keyed embed service analogous to vidsrc.to / 2embed.cc.
    //  • Watch URLs:
    //      Movie: https://cinesrc.st/embed/movie/{tmdbId}
    //      TV:    https://cinesrc.st/embed/tv/{tmdbId}?s={season}&e={episode}
    //  • The embed page is a Next.js SPA that fetches the m3u8 via a
    //    2-stage PoW flow:
    //      1. POST /api/c/bootstrap with x-cs-q header
    //         (= base64url(JSON.stringify([type,id,season,episode])))
    //         → {v:1, r:"v1...", p:"..."} (auth tokens)
    //      2. GET /api/c/issue with x-cs-r + x-cs-q + x-cs-p headers
    //         → {w, t, n, s} (hashcash-style PoW challenge)
    //      3. GET /api/c/stage2/issue with x-cs-r + x-cs-q headers
    //         → {pack:[hash, difficulty, base64, hash, base64]}
    //         (WASM-based PoW via /pow-v3.wasm — too complex for Kotlin port)
    //      4. Solve both PoWs client-side, construct token, fetch m3u8.
    //    The m3u8 is played by Shaka Player (DASH) or HLS.js (HLS).
    //
    // HONEST CAVEAT (delivered to user, do NOT over-promise):
    //   The cinesrc.st PoW (especially the WASM stage2) is too complex to
    //   port to Kotlin server-side. This resolver emits the embed URL via
    //   loadExtractor, which means Cloudstream's WebView loads the page
    //   client-side, solves the PoW in JS, and the m3u8 is intercepted
    //   from network traffic. This works on modern Android phone WebViews
    //   but MAY fail on older Android TV WebViews (the v95 ani.zip/CF
    //   challenge class of issue). If loadExtractor returns nothing, the
    //   user should test in TV-browser:
    //     https://cinesrc.st/embed/movie/550
    //   If that page plays video, the resolver failure is Cloudstream
    //   WebView not handling the PoW (need a WebView update). If the page
    //   shows "Just a moment…" or a CF challenge, the user's IP/TV stack
    //   is blocked at the CF edge (NOT code-fixable our side).
    internal object ShuttletvResolver : SourceResolver {
        private const val SITE = "https://shuttletv.su"
        private const val EMBED_HOST = "https://cinesrc.st"
        private const val LABEL = "ShuttleTV"
        private val REF_HEADERS = mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
        )

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            // TMDB-keyed — no id, no resolve (matches the Cineby/Bingr/Moonflix
            // doctrine; the v95 fallback covers id-mapping failures).
            if (tmdbId == null || tmdbId <= 0) {
                Log.d(TAG, "ShuttleTV: skip — tmdbId null")
                return false
            }

            // Construct the cinesrc.st embed URL.
            val (kind, embedUrl) = if (season != null && episode != null && season > 0 && episode > 0) {
                "tv" to "$EMBED_HOST/embed/tv/$tmdbId?s=$season&e=$episode&back=close"
            } else {
                "movie" to "$EMBED_HOST/embed/movie/$tmdbId?back=close"
            }
            Log.i(TAG, "ShuttleTV: resolve $kind tmdb=$tmdbId s=${season ?: '-'} e=${episode ?: '-'} → $embedUrl")

            // Emit ONE link via loadExtractor. Cloudstream's WebView will:
            //   1. Load the cinesrc.st embed page
            //   2. Run the Next.js SPA + 2-stage PoW client-side
            //   3. Intercept the m3u8 URL from network traffic
            //   4. Emit it as an ExtractorLink via our callback
            //
            // If loadExtractor returns false (no video URL found within the
            // timeout), the resolver silently skips — never emits a broken
            // link (the v93 doctrine: no 2004/3003 errors).
            var emitted = false
            runCatching {
                loadExtractor(embedUrl, SITE + "/", subtitleCallback) { link ->
                    val tag = if (kind == "tv") "S${season}E${episode}" else "Movie"
                    callback(
                        link.relabel(
                            "$labelPrefix $LABEL",
                            "$labelPrefix $LABEL · $tag",
                        )
                    )
                    emitted = true
                }
            }.onFailure { t ->
                Log.w(TAG, "ShuttleTV: loadExtractor failed for $embedUrl — ${t.message}")
            }
            if (!emitted) {
                Log.d(TAG, "ShuttleTV: no links extracted from $embedUrl " +
                    "(cinesrc.st PoW may have failed in WebView, or CF challenge blocked the page)")
            }
            return emitted
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 12: M4UHD  (https://ww1.m4uhd.to)   — v98, user request
    //
    //  Full chain reverse-engineered from the LIVE site on 2026-08-07
    //  (every step verified from this sandbox, no browser):
    //
    //  MOVIES
    //    1. GET  /search/{slug}.html                    (slug = site's own
    //       simpler() JS port: lowercase, strip quotes, punct/space → '-')
    //       → `.movie-item a[href^=watch-]` + `title="Name (Year) "` attrs.
    //    2. GET  watch page (cookies) → inline `_token` (Laravel CSRF) +
    //       `.play-movie` span tokens (3 = primary / VidSrc / Abyss backup).
    //    3. POST /ajax  {m4u:<span token>, _token} (Cookie + Referer)
    //       → tiny HTML with ONE iframe src:
    //         • if9.ppzj-youtube.cfd/play/{fileId}  ← site-built 9stream HLS
    //         • vidsrcme.ru/embed/movie/tt…          ← loadExtractor lane
    //         • abyssplayer.com/?v=…                 ← loadExtractor lane
    //
    //  TV SERIES
    //    2a. Series page embeds `const seasons = {"1":[{epi_name:"S01-E01",
    //        idepisode:"fyc7i"},…]}` — full season/episode map inline.
    //    2b. POST /ajaxtv {idepisode, _token} → the same `.play-movie`
    //        token trio for that episode → step 3 identical.
    //
    //  THE 9STREAM (if9) LANE — fully cracked, in-repo, zero WebView:
    //    The if9 page ships obfuscated JS (javascript-obfuscator) that was
    //    executed headlessly here to extract its whole protocol:
    //      consts: idfile_enc / idUser_enc (hex of OpenSSL-Salted AES-256-
    //      CBC, EVP_BytesToKey-MD5 KDF), DOMAIN_API, data_subs (EN SRT).
    //      idUser = AES-dec(idUser_enc, PW_IDUSER)   (24-hex site user id)
    //      payload = {"idfile":<path id>,"iduser":…,"domain_play":
    //                 "https://ww1.m4uhd.to","platform":"Win32",
    //                 "hlsSupport":false}
    //      body    = hex(AES-enc(json, PW_REQ)) + "|" +
    //                md5(hex + SIG_SECRET)
    //      POST {DOMAIN_API}/playiframe {"data": body}
    //        → {"status":1,"type":"url-m3u8-encv1","data":<hex salted>}
    //      m3u8    = AES-dec(resp.data, PW_RESP)
    //        → https://9str-m3u8-play-….ppzj-youtube.cfd/m3u8/tp1-rdv1/1080/…
    //    The playlist plays 200 with ZERO headers (plain UA only) — verified
    //    — so it is safe on the old Android TV WebView too.
    //
    //    The five passphrases are baked into the site's static player
    //    script; they were confirmed STABLE across different movie pages
    //    on 2026-08-07. If the site ever re-obfuscates with new keys, the
    //    chain fails CLOSED (no rows, no 2004) — never emits garbage.
    //
    //  NO-2004 DOCTRINE: the 9stream m3u8 is emitted only after a device-
    //  side GET proves #EXTM3U (200/206); EN SRT only after a 200/206
    //  probe. VidSrc/Abyss lanes go through loadExtractor (app-side
    //  WebView) and are emitted only when the extractor actually finds a
  //    video. If nothing verifies, the resolver returns false and the
    //  player row simply stays absent.
    // ════════════════════════════════════════════════════════════════════════
    internal object M4uHdResolver : SourceResolver {
        private const val SITE = "https://ww1.m4uhd.to"
        private const val LABEL = "M4UHD"
        // (v98) if9/9stream protocol keys — see protocol notes above.
        private const val PW_IDUSER = "PZZ3J3LDbLT0GY7qSA5wW5vchqgpO36O"
        private const val PW_REQ = "vlVbUQhkOhoSfyteyzGeeDzU0BHoeTyZ"
        private const val SIG_SECRET = "KRWN3AdgmxEMcd2vLN1ju9qKe8Feco5h"
        private const val PW_RESP = "oJwmvmVBajMaRCTklxbfjavpQO7SZpsL"
        private val HEADERS = mapOf("User-Agent" to UA, "Referer" to "$SITE/")

        // ── OpenSSL-Salted AES-256-CBC (EVP_BytesToKey MD5 KDF) ────────────
        private fun evpBytesToKey(pass: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
            var out = byteArrayOf()
            var prev = byteArrayOf()
            val md = MessageDigest.getInstance("MD5")
            while (out.size < 32 + 16) {
                md.reset(); md.update(prev); md.update(pass); md.update(salt)
                prev = md.digest(); out += prev
            }
            return out.copyOfRange(0, 32) to out.copyOfRange(32, 48)
        }

        private fun saltedEncrypt(plain: String, pass: String): ByteArray {
            val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val (k, iv) = evpBytesToKey(pass.toByteArray(Charsets.UTF_8), salt)
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(iv))
            return "Salted__".toByteArray(Charsets.UTF_8) + salt + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        }

        private fun saltedDecrypt(saltedBytes: ByteArray, pass: String): String? {
            if (saltedBytes.size < 33) return null
            if (!String(saltedBytes.copyOfRange(0, 8), Charsets.UTF_8).equals("Salted__")) return null
            val salt = saltedBytes.copyOfRange(8, 16)
            val (k, iv) = evpBytesToKey(pass.toByteArray(Charsets.UTF_8), salt)
            return runCatching {
                val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(iv))
                String(c.doFinal(saltedBytes.copyOfRange(16, saltedBytes.size)), Charsets.UTF_8)
            }.getOrNull()
        }

        private fun hexToBytes(hex: String): ByteArray? {
            val h = hex.trim()
            if (h.length % 2 != 0) return null
            return runCatching {
                ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            }.getOrNull()
        }

        private fun bytesToHex(b: ByteArray): String {
            val sb = StringBuilder(b.size * 2)
            for (x in b) sb.append(String.format("%02x", x.toInt() and 0xFF))
            return sb.toString()
        }

        private fun md5Hex(s: String): String =
            bytesToHex(MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)))

        // site's simpler() slug — see js-min-v1.js
        private fun m4uSlug(title: String): String {
            var s = title.lowercase().replace("'", "")
            s = s.replace(Regex("[!@%^*()+=<>?/,.:; \"&#\\[\\]~\$_]+"), "-")
            s = s.replace(Regex("-+"), "-").trim('-')
            return s
        }

        private fun normTitle(s: String): String =
            s.lowercase().replace(Regex("\\((19|20)\\d{2}\\)"), " ")
                .replace(Regex("[^a-z0-9]+"), " ").trim()

        private fun titleHits(ask: String, cand: String): Boolean {
            val a = normTitle(ask); val c = normTitle(cand)
            if (a.isBlank() || c.isBlank()) return false
            if (a == c || a.startsWith(c) || c.startsWith(a) || a.contains(c) || c.contains(a)) return true
            val at = a.split(" ").toSet(); val ct = c.split(" ").toSet()
            val inter = at.intersect(ct).size.toDouble()
            val minSize = minOf(at.size, ct.size).toDouble()
            return minSize > 0 && inter / minSize >= 0.66
        }

        private suspend fun searchCandidates(
            app: Requests, title: String, extra: String?
        ): List<Pair<String, String>> {
            val terms = listOfNotNull(title, extra).filter { it.isNotBlank() }.distinct()
            for (term in terms) {
                val slug = m4uSlug(term)
                if (slug.isBlank()) continue
                val resp = runCatching {
                    app.get("$SITE/search/$slug.html", headers = HEADERS, cacheTime = 0, timeout = 15_000)
                }.getOrNull() ?: continue
                if (resp.code !in 200..299) continue
                val doc = Jsoup.parse(resp.text)
                val out = mutableListOf<Pair<String, String>>()   // (pageUrl, siteTitle)
                doc.select(".movie-item a[href], a[href*='watch-']").forEach { a ->
                    val href = a.attr("href").trim()
                    if (!href.contains("watch-")) return@forEach
                    val siteTitle = a.attr("title").ifBlank { a.selectFirst("h3")?.text() ?: a.text() }
                    if (siteTitle.isBlank()) return@forEach
                    if (!titleHits(term, siteTitle) && !titleHits(title, siteTitle)) return@forEach
                    val page = if (href.startsWith("http")) href else "$SITE/${href.trimStart('/')}"
                    if (out.none { it.first == page }) out += page to siteTitle
                }
                if (out.isNotEmpty()) return out.take(4)
            }
            return emptyList()
        }

        private data class NStreamResult(val m3u8: String, val subsFile: String?)

        /** Warm-lane memory (v98 fix): a served 9stream playlist is reused for
         *  ~100 min so re-taps during a signing-API outage don't re-run the
         *  whole chain (and re-poke the cold endpoint that hangs the row scan). */
        private val laneCache = HashMap<String, Pair<Long, NStreamResult>>()
        private const val LANE_TTL = 100L * 60L * 1000L

        /** Walk one if9.ppzj-youtube.cfd player page down to its playable
         *  9stream HLS playlist + optional EN SRT (the v98 cracked chain).
         *  [fastFail] = the API already cold-hung this resolve: one short try. */
        private suspend fun resolve9Stream(
            app: Requests, if9Url: String, watchPageUrl: String, fastFail: Boolean = false,
        ): NStreamResult? {
            val fileId = if9Url.trimEnd('/').substringAfterLast('/')
            if (fileId.isBlank()) return null
            laneCache[fileId]?.let { (t, cached) ->
                if (System.currentTimeMillis() - t < LANE_TTL) {
                    Log.d(TAG, "M4UHD: 9stream lane cache hit $fileId")
                    return cached
                }
            }
            val page = runCatching {
                app.get(if9Url, headers = mapOf("User-Agent" to UA, "Referer" to watchPageUrl),
                    cacheTime = 0, timeout = 20_000)
            }.getOrNull() ?: return null
            if (page.code !in 200..299) return null
            val html = page.text

            val idUserEnc = Regex("""idUser_enc = "([0-9a-fA-F]+)""").find(html)?.groupValues?.get(1)
                ?: return null
            val domainApi = Regex("""DOMAIN_API = '([^']+)'""").find(html)?.groupValues?.get(1)
                ?: return null
            val subsFile = Regex("""data_subs = '(\[.*?\])'""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)?.let { arrStr ->
                    runCatching {
                        val arr = JSONArray(arrStr)
                        (0 until arr.length()).asSequence()
                            .map { arr.optJSONObject(it) }
                            .filterNotNull()
                            .firstOrNull { it.optString("kind") == "captions" }
                            ?.optString("file")?.takeIf { it.startsWith("http") }
                    }.getOrNull()
                }

            val idUserBytes = hexToBytes(idUserEnc) ?: return null
            val idUser = saltedDecrypt(idUserBytes, PW_IDUSER)
                ?.trim()?.takeIf { it.matches(Regex("[0-9a-f]{16,32}")) } ?: return null

            val domainPlay = Regex("""^(https?://[^/?#]+)""").find(watchPageUrl)?.groupValues?.get(1)
                ?: SITE
            val payload = JSONObject()
                .put("idfile", fileId)
                .put("iduser", idUser)
                .put("domain_play", domainPlay)
                .put("platform", "Win32")
                .put("hlsSupport", false)
                .toString()
            val ctHex = bytesToHex(saltedEncrypt(payload, PW_REQ))
            val dataField = ctHex + "|" + md5Hex(ctHex + SIG_SECRET)

            val origin9 = Regex("""^(https?://[^/?#]+)""").find(if9Url)?.groupValues?.get(1) ?: ""
            val playHeaders = mapOf(
                "User-Agent" to UA,
                "Referer" to if9Url,
                "Origin" to origin9,
                "Content-Type" to "application/json",
            )
            val playBody = JSONObject().put("data", dataField).toString()
                .toRequestBody("application/json".toMediaTypeOrNull())
            // v98 resilience fix (user report 2026-08-08): the 9stream signing
            // origin periodically cold-hangs — Cloudflare returns 504 at ~60s,
            // and the site's own player hangs identically (server-side issue,
            // our keys are unchanged — reverified same day). A cold attempt
            // wakes the origin, so a single follow-up after a short pause
            // wins whenever the hiccup is short; anything longer is a real
            // outage and the lane fails CLOSED (no rows, no 2004).
            val maxTries = if (fastFail) 1 else 2
            var apiText: String? = null
            var tryN = 0
            while (apiText == null && tryN < maxTries) {
                tryN++
                val t0 = System.currentTimeMillis()
                val resp = runCatching {
                    app.post(
                        "$domainApi/playiframe",
                        headers = playHeaders,
                        requestBody = playBody,
                        cacheTime = 0,
                        timeout = if (fastFail) 12_000 else if (tryN == 1) 25_000 else 40_000,
                    )
                }.getOrNull()
                val ms = System.currentTimeMillis() - t0
                if (resp == null) {
                    Log.d(TAG, "M4UHD: playiframe try$tryN no-response after ${ms}ms")
                } else if (resp.code !in 200..299 || resp.text.isBlank()) {
                    Log.d(TAG, "M4UHD: playiframe try$tryN http ${resp.code} after ${ms}ms (origin cold-hang if 504)")
                } else {
                    Log.d(TAG, "M4UHD: playiframe try$tryN http 200 after ${ms}ms")
                    apiText = resp.text
                }
                if (apiText == null && tryN < maxTries) {
                    Log.d(TAG, "M4UHD: retrying playiframe once — origin cold-sign wake-up")
                    delay(6_000)
                }
            }
            val jo = runCatching { JSONObject(apiText ?: return null) }.getOrNull() ?: return null
            if (jo.optInt("status", 0) != 1) return null
            val encData = jo.optString("data", "")
            val encBytes = hexToBytes(encData) ?: return null
            val m3u8 = saltedDecrypt(encBytes, PW_RESP)?.trim()
                ?.takeIf { it.startsWith("http") && (it.contains(".m3u8") || it.contains("/m3u8/")) }
                ?: return null
            val res9 = NStreamResult(m3u8, subsFile)
            laneCache[fileId] = System.currentTimeMillis() to res9
            if (laneCache.size > 60) laneCache.clear()
            return res9
        }

        /** Shared 9stream emission path — probe the playlist device-side,
         *  expand variants when it's a master, attach the verified EN SRT. */
        private suspend fun emit9Stream(
            app: Requests, res: NStreamResult, labelPrefix: String, seTag: String?,
            subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val probe = runCatching {
                app.get(res.m3u8, headers = mapOf("User-Agent" to UA),
                    cacheTime = 0, timeout = 20_000)
            }.getOrNull() ?: return false
            if (probe.code !in 200..299 && probe.code != 206) return false
            val text = probe.text
            if (!text.contains("#EXTM3U")) return false

            val srcLabel = "$labelPrefix $LABEL"
            val suffix = if (seTag != null) " · $seTag" else ""
            var any = false

            if (text.contains("#EXT-X-STREAM-INF")) {
                val variants = parseHlsMasterVariants(text, res.m3u8)
                if (variants.isNotEmpty()) {
                    variants.distinctBy { it.url }.forEach { v ->
                        val skip = DeviceDecoderProbe.skipReason(videoCodecOf(v.codecs), v.width, v.height)
                        if (skip != null) {
                            Log.d(TAG, "M4UHD: skipped ${v.width}x${v.height} (${v.codecs}) — $skip")
                            return@forEach
                        }
                        callback(
                            newExtractorLink(
                                source = srcLabel,
                                name = "$srcLabel · 9stream ${v.height}p$suffix",
                                url = v.url,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = ""
                                this.quality = if (v.height > 0) qualityFromDimensions(v.width, v.height)
                                    else qualityFromName("1080")
                                this.headers = emptyMap()
                            }
                        )
                        any = true
                    }
                }
            }
            if (!any) {
                // single-rendition media playlist (observed: /1080/ lane)
                val q = Regex("""/(\d{3,4})/""").find(res.m3u8)?.groupValues?.get(1) ?: "1080"
                callback(
                    newExtractorLink(
                        source = srcLabel,
                        name = "$srcLabel · 9stream ${q}p$suffix",
                        url = res.m3u8,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = ""
                        this.quality = qualityFromName(q)
                        this.headers = emptyMap()
                    }
                )
                any = true
            }

            // verified EN subtitle
            val sub = res.subsFile
            if (any && sub != null) {
                val subProbe = runCatching {
                    app.get(sub, headers = mapOf("User-Agent" to UA, "Range" to "bytes=0-127"),
                        cacheTime = 0, timeout = 8_000)
                }.getOrNull()
                if (subProbe != null && (subProbe.code in 200..299 || subProbe.code == 206 || subProbe.code == 416)) {
                    runCatching { subtitleCallback(SubtitleFile("M4UHD · EN", sub)) }
                }
            }
            return any
        }

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            val asks = ((season ?: 0) > 0 && (episode ?: 0) > 0)
            Log.i(TAG, "M4UHD: resolve '$title' y=$year s=$season e=$episode")
            val candidates = searchCandidates(app, title, null)
            if (candidates.isEmpty()) {
                Log.d(TAG, "M4UHD: search '$title' no candidates")
                return false
            }

            var any = false
            var nineCold = false   // v98: once the 9stream API cold-hangs in
            // this resolve, remaining candidates probe it once and briefly
            for ((pageUrl, siteTitle) in candidates) {
                // year soft-gate when we know it
                if (year != null) {
                    val yIn = Regex("""\((19|20)\d{2}\)""").find(siteTitle)?.value
                        ?.trim('(', ')')?.toIntOrNull()
                    if (yIn != null && kotlin.math.abs(yIn - year) > 1) continue
                }
                val page = runCatching {
                    app.get(pageUrl, headers = HEADERS, cacheTime = 0, timeout = 20_000)
                }.getOrNull() ?: continue
                if (page.code !in 200..299) continue
                val html = page.text
                val cookies = page.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val token = Regex(""""_token: ?['"]([A-Za-z0-9]+)['"]""").find(html)?.groupValues?.get(1)
                    ?: continue
                val xh = HEADERS + mapOf(
                    "Cookie" to cookies,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to pageUrl,
                )

                // ── TV: seasons map → ajaxtv → episode-level server tokens ──
                val playTokens = mutableListOf<String>()
                var seTag: String? = null
                if (asks) {
                    val seasonsJson = Regex("""const seasons = (\{.+?\});""", RegexOption.DOT_MATCHES_ALL)
                        .find(html)?.groupValues?.get(1)
                    if (seasonsJson == null) continue   // a movie page can't help a series ask
                    var idepisode: String? = null
                    runCatching {
                        val root = JSONObject(seasonsJson)
                        val want = "S%02d-E%02d".format(season, episode)
                        for (sk in root.keys()) {
                            val arr = root.optJSONArray(sk) ?: continue
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                if (o.optString("epi_name").equals(want, ignoreCase = true)) {
                                    idepisode = o.optString("idepisode").takeIf { it.isNotBlank() }
                                    break
                                }
                            }
                            if (idepisode != null) break
                        }
                    }
                    if (idepisode == null) {
                        Log.d(TAG, "M4UHD: S${season}E${episode} not in seasons map for $pageUrl")
                        continue
                    }
                    val epHtml = runCatching {
                        app.post("$SITE/ajaxtv", headers = xh,
                            data = mapOf("idepisode" to idepisode!!, "_token" to token),
                            cacheTime = 0, timeout = 20_000)
                    }.getOrNull() ?: continue
                    if (epHtml.code !in 200..299) continue
                    seTag = "S${season}E${episode}"
                    Regex("""class="play-movie[^"]*"[^>]*data="([^"\s>]+)""")
                        .findAll(epHtml.text).forEach { playTokens += it.groupValues[1] }
                } else {
                    Regex("""class="play-movie[^"]*"[^>]*data="([^"\s>]+)""")
                        .findAll(html).forEach { playTokens += it.groupValues[1] }
                }
                if (playTokens.isEmpty()) continue

                // ── server tokens → iframe lanes (site order: primary first) ──
                var got9 = false
                for (tok in playTokens.take(3)) {
                    val ajax = runCatching {
                        app.post("$SITE/ajax", headers = xh,
                            data = mapOf("m4u" to tok, "_token" to token),
                            cacheTime = 0, timeout = 20_000)
                    }.getOrNull() ?: continue
                    if (ajax.code !in 200..299) continue
                    val iframe = Regex("""src="(https?://[^"\s<>']+)""").find(ajax.text)
                        ?.groupValues?.get(1)?.trim() ?: continue
                    when {
                        iframe.contains("ppzj-youtube.cfd/play/") -> {
                            // in-repo 9stream lane (the no-2004 guarantee): ONE
                            // verified attempt is enough — skip further if9 dupes.
                            if (got9) continue
                            got9 = true
                            val res = runCatching {
                                resolve9Stream(app, iframe, pageUrl, nineCold)
                            }.getOrNull()
                            if (res != null) {
                                any = emit9Stream(app, res, labelPrefix, seTag, subtitleCallback, callback) || any
                                if (any) Log.i(TAG, "M4UHD: 9stream lane served $siteTitle")
                            } else {
                                nineCold = true
                                Log.d(TAG, "M4UHD: 9stream chain failed for $iframe")
                            }
                        }
                        else -> {
                            // Abyss / VidSrc / other hosts: app-side extractor lane
                            any = emitDirect(app, iframe, "$labelPrefix $LABEL", pageUrl,
                                emptyMap(), subtitleCallback, callback) || any
                        }
                    }
                }
                if (any) break   // first verified candidate wins
            }
            if (!any) Log.d(TAG, "M4UHD: nothing verified for '$title'")
            return any
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolver 13: CinemaOS  (https://cinemaos.live)  — v98, user request
    //
    //  HONEST 2026-08-07 RECON SUMMARY (all live-verified):
    //   • The site is a Next.js 15 fork of the "Rive" project, TMDB-keyed:
    //     /watch/movie/{tmdbId} and /watch/tv/{tmdbId}?s=&e= — the old
    //     /movie/watch/{id} 308-redirects to the new form.
    //   • Its own video player is currently BROKEN site-wide: the lazy
    //     player chunks the watch page imports (webpack chunks 6220/4099)
    //     HTTP 404 for everyone (deploy bug server-side), so not even the
    //     site's visitors in a real browser can see the source list right
    //     now. Nothing about that is fixable from our side.
    //   • Its upstream provider family (vidsrc-api-* "simple-scrape-api"
    //     FastAPI: /vidsrc /vsrcme /streams /subs) is reachable but returns
    //     empty payloads today.
    //
    //  DESIGN: TMDB-keyed (same doctrine as ShuttleTV/Cineby — tmdbId
    //  required; v95's TMDB fallback covers id-mapping). We walk the whole
    //  CinemaOS lineage ladder with SHORT timeouts and parse GENERICALLY
    //  (any JSON we can reach gets scanned for media/embed URLs):
    //     1. site APIs  (a few /api/* guesses — 404 today, kept for the day
    //        they add one; silent miss costs nothing)
    //     2. the watch page HTML itself (media tags/iframes — none today)
    //     3. the provider API used by the upstream project
    //        (vidsrc-api-pearl /vsrcme /vidsrc /streams × {tmdb, imdb})
    //  Every media URL found is device-side PROBED before listing (range
    //  512B must answer 200/206, m3u8 must contain #EXTM3U) and every
    //  embed URL goes through loadExtractor. If all lanes are empty the
  //    resolver returns false quietly — the no-2004/3003 contract holds:
    //  NO unverifiable link is ever emitted. When the CinemaOS backend
    //  comes back to life, rows appear automatically with zero code change.
    // ════════════════════════════════════════════════════════════════════════
    internal object CinemaOsResolver : SourceResolver {
        private const val SITE = "https://cinemaos.live"
        private const val LABEL = "CinemaOS"
        private const val LEGACY_API = "https://vidsrc-api-pearl.vercel.app"
        private val HEADERS = mapOf("User-Agent" to UA)

        /** Recursively collect http(s) strings that look like media or
         *  embed targets out of any JSON shape a backend returns. */
        private fun collectUrls(node: Any?, media: MutableList<String>, embeds: MutableList<String>) {
            when (node) {
                is JSONObject -> {
                    val ks = node.keys()
                    while (ks.hasNext()) collectUrls(node.opt(ks.next()), media, embeds)
                }
                is JSONArray -> for (i in 0 until node.length()) collectUrls(node.opt(i), media, embeds)
                is String -> {
                    val s = node.trim()
                    if (!s.startsWith("http")) return
                    if (isDirectMedia(s) || s.contains(".m3u8", true)) media += s
                    else if (s.contains("embed", true) || s.contains("/play", true) ||
                        s.contains("/e/", true) || s.contains("watch", true)) embeds += s
                }
            }
        }

        private suspend fun probeAndEmit(
            app: Requests,
            url: String,
            srcLabel: String,
            referer: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val clean = url.trim()
            if (clean.isBlank()) return false
            // direct media must answer a device-side range probe
            if (isDirectMedia(clean) || clean.contains(".m3u8", true)) {
                val probe = runCatching {
                    app.get(clean, headers = HEADERS + ("Range" to "bytes=0-512"),
                        cacheTime = 0, timeout = 12_000)
                }.getOrNull() ?: return false
                if (probe.code !in 200..299 && probe.code != 206) return false
                if (clean.contains(".m3u8", true) && !probe.text.contains("#EXTM3U")) return false
                return emitDirect(app, clean, srcLabel, referer, HEADERS, subtitleCallback, callback)
            }
            // embed pages: app-side extractor, emitted only when it finds video
            return emitDirect(app, clean, srcLabel, referer, HEADERS, subtitleCallback, callback)
        }

        override suspend fun resolve(
            app: Requests,
            title: String,
            year: Int?,
            isMovie: Boolean,
            season: Int?,
            episode: Int?,
            labelPrefix: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            tmdbId: Int?,
            imdbId: String?,
        ): Boolean {
            if (tmdbId == null || tmdbId <= 0) {
                Log.d(TAG, "CinemaOS: skip — tmdbId null")
                return false
            }
            val s = season ?: 0; val e = episode ?: 0
            val isTvAsk = s > 0 && e > 0
            Log.i(TAG, "CinemaOS: resolve tmdb=$tmdbId imdb=$imdbId tv=$isTvAsk")
            val media = mutableListOf<String>()
            val embeds = mutableListOf<String>()

            fun scanJsonBody(body: String) {
                val trimmed = body.trim()
                if (trimmed.isEmpty()) return
                runCatching { collectUrls(JSONObject(trimmed), media, embeds) }
                    .onFailure { runCatching { collectUrls(JSONArray(trimmed), media, embeds) } }
            }

            // lane 1 — site's own API (guessed routes, silent 404s today)
            for (u in listOf(
                "$SITE/api/stream?tmdb=$tmdbId&type=" + (if (isTvAsk) "tv&s=$s&e=$e" else "movie"),
                "$SITE/api/sources?tmdb=$tmdbId" + if (isTvAsk) "&s=$s&e=$e" else "",
                "$SITE/api/watch?movie=$tmdbId" + if (isTvAsk) "&season=$s&episode=$e" else "",
            )) {
                runCatching {
                    val r = app.get(u, headers = HEADERS, cacheTime = 0, timeout = 8_000)
                    if (r.code in 200..299) scanJsonBody(r.text)
                }
            }

            // lane 2 — the watch page itself (media tags / iframes inside)
            val watchUrl = if (isTvAsk) "$SITE/watch/tv/$tmdbId" else "$SITE/watch/movie/$tmdbId"
            runCatching {
                val r = app.get(watchUrl, headers = HEADERS, cacheTime = 0, timeout = 15_000)
                if (r.code in 200..299) {
                    extractMediaUrlsFromHtml(r.text, watchUrl).forEach { u ->
                        if (isDirectMedia(u) || u.contains(".m3u8", true)) media += u else embeds += u
                    }
                }
            }

            // lane 3 — upstream project's provider API (empty payloads today)
            val idForms = buildList {
                add(tmdbId.toString())
                if (!imdbId.isNullOrBlank()) add(imdbId)
            }
            for (id in idForms) {
                for (path in listOf("/vsrcme/", "/vidsrc/", "/streams/")) {
                    runCatching {
                        val r = app.get(
                            LEGACY_API + path + id + if (isTvAsk && path == "/streams/") "/$s/$e" else "",
                            headers = HEADERS, cacheTime = 0, timeout = 10_000)
                        if (r.code in 200..299) scanJsonBody(r.text)
                    }
                }
            }

            val watchRef = watchUrl
            var any = false
            media.distinct().take(6).forEach { u ->
                val host = Regex("""^https?://([^/?#]+)""").find(u)?.groupValues?.get(1) ?: "web"
                any = probeAndEmit(app, u, "$labelPrefix $LABEL · $host",
                    watchRef, subtitleCallback, callback) || any
            }
            embeds.distinct().take(6).forEach { u ->
                val host = Regex("""^https?://([^/?#]+)""").find(u)?.groupValues?.get(1) ?: "web"
                any = probeAndEmit(app, u, "$labelPrefix $LABEL · $host",
                    watchRef, subtitleCallback, callback) || any
            }
            if (!any) {
                Log.d(TAG, "CinemaOS: all lanes empty for tmdb=$tmdbId " +
                    "(site player chunks 404 and provider API empty — see v98 notes)")
            }
            return any
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared JSON helpers
// ─────────────────────────────────────────────────────────────────────────────

internal fun JSONObject.optStringOrNullCp(k: String): String? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").trim().takeIf { it.isNotBlank() && it != "null" }

// ═════════════════════════════════════════════════════════════════════════════
//  (v59) WizEpisodeTable — recursive multi-season anime episode-table mapper
// ═════════════════════════════════════════════════════════════════════════════
//
// CircleFTP (and BDIX season packs generally) file a multi-season anime as
// ONE catalogue: per-season tabs whose rows count SEQUENTIALLY through
// cours splits AND long story-specials — Attack on Titan's "Season 4" tab
// holds 30 rows: the 28 TV episodes PLUS "The Final Chapters" parts 1 & 2
// (two ~1-hour specials).
//
// TMDB stores the exact same canon differently: the 28 under Season 4 and
// the specials under "Season 0" (S0E36 "The Final Chapters Special (1)",
// S0E37 "(2)" — each with full titles, descriptions, stills and runtimes).
//
// This mapper recurses TMDB's canon season by season: for every TV season
// it walks the show's specials and repeatedly TAIL-ATTACHES the ones that
//   • aired AFTER that season's finale, and
//   • aired BEFORE the next season's premiere (for the FINAL season the
//     window is open-ended — TMDB's last_air_date ignores specials, so
//     the Final Chapters land chronologically "after the show ended"), and
//   • run ≥ 55 minutes — the "1-hour-long episode" rule: kills chibi
//     shorts, 24-minute OVAs and 50-minute recap omnibuses, and
//   • aren't named like a ceremony ("Reading & Live Event" must never
//     masquerade as a season episode),
// continuing the season's numbering SEQUENTIALLY (S4E29, S4E30) exactly
// like the site's rows. At most 3 specials absorb into one season. A row
// also remembers where TMDB canonically stores it (S0E36), so embed hosts
// can be pointed at the real location instead of the stacked number.
internal object WizEpisodeTable {

    private const val TABLE_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"
    private const val TABLE_API = "https://api.themoviedb.org/3"
    private const val TABLE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val STORY_SPECIAL_MIN_RUNTIME_MIN = 55
    private const val MAX_ABSORBED_PER_SEASON = 3
    // Named like a ceremony, not like story content (TMDB's specials
    // season also hosts live events/stage readings — those must never
    // become numbered season rows).
    private val NON_STORY_SPECIAL = Regex(
        """(?i)\b(live\s*&?\s*event|stage\s*event|stage\s*play|stage\s*greeting|reading|concert|panel|fan\s*meeting|screening\s*event|talk\s*show)\b"""
    )
    private const val CACHE_MS = 45 * 60 * 1000L
    private const val NEG_CACHE_MS = 5 * 60 * 1000L

    class EpRow(
        val name: String?,
        val overview: String?,
        val stillUrl: String?,
        val airDate: Long?,   // unix ms, UTC day — what Episode.date wants
        val runtime: Int?,
        val score: Double?,
        // Where TMDB canonically stores an absorbed special (0/36 etc.).
        // null = regular row, stacked number == TMDB's own numbering.
        val tmdbSeason: Int?,
        val tmdbEpisode: Int?,
    )

    // (v61) The table + the show-level artwork the same calls already pay
    // for: a high-res LANDSCAPE backdrop (w1280) and the official TITLE
    // LOGO (TMDB images, English first then language-neutral). The pure
    // module uses these to fill what AniList cannot supply.
    class Table(
        val seasons: Map<Int, Map<Int, EpRow>>,
        val backdropUrl: String?,
        val logoUrl: String?,
        // (v80) PER-ENTRY LANDSCAPE POOL. AniList files every cour/season/
        // special as its OWN entry, but TMDB has exactly ONE show-level
        // backdrop — so every de-stacked entry of a franchise used to show
        // the IDENTICAL header image (user report: "if the landscape poster
        // gets fetched in every item it won't look that good").
        // `/tv/{id}/images` is ALREADY fetched here for the logo and
        // typically carries dozens of HD backdrops (Attack on Titan: 100 at
        // ≥1920w, Jujutsu Kaisen 97, Oshi no Ko 20). Ranked best-first, they
        // are dealt ONE PER ENTRY so each season/part/special gets its own
        // real key art at zero extra network cost.
        val backdropPool: List<String> = emptyList(),
    )

    private val cache =
        java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Table?>>()

    private fun parseYmd(s: String?): Long? {
        if (s == null) return null
        val parts = s.split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        return runCatching {
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            c.clear(); c.set(y, m - 1, d)
            c.timeInMillis
        }.getOrNull()
    }

    private suspend fun getJson(app: Requests, path: String): JSONObject? = wizRetryOnce("tmdb-json") {
        val joiner = if ('?' in path) "&" else "?"
        val url = "$TABLE_API$path$joiner" + "api_key=$TABLE_KEY&language=en-US"
        val res = app.get(url, headers = mapOf(
            "User-Agent" to TABLE_UA,
            "Accept" to "application/json",
        ), timeout = 10_000)
        if (res.code !in 200..299) null else JSONObject(res.text)
    }

    /** Site-stacked episode table + show art. */
    suspend fun table(app: Requests, tmdbId: Int): Table? {
        val now = System.currentTimeMillis()
        cache[tmdbId]?.let { (ts, v) ->
            if (now - ts < (if (v == null) NEG_CACHE_MS else CACHE_MS)) return v
        }
        val built = runCatching { buildTable(app, tmdbId) }.getOrNull()
        cache[tmdbId] = now to built
        return built
    }

    // ── (v70) TMDB search fallback + movie art ──────────────────────────
    // User report: episode titles/thumbnails/landscape art "suck
    // sometimes" — the gaps are entries ani.zip NEVER mapped, so no TMDB
    // id existed to build a table from and every TMDB-fed field stayed
    // empty. TMDB's own /search/tv recovers most of those shows from the
    // AniList title + start year; correctness gate = title identity plus
    // a ±1 year window when both sides know the year.
    private val findCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int?>>()

    private fun normCmp(t: String): String =
        t.lowercase()
            .replace(WizstreamSources.Rx.TM_BRACKET_RE, " ")
            .replace(WizstreamSources.Rx.TM_PAREN_RE, " ")
            .replace(WizstreamSources.Rx.NON_ALNUM_RE, " ")
            .trim()
            .replace(WizstreamSources.Rx.WS_SPLIT_RE, " ")

    /** TMDB tv-show id for an unmapped AniList entry, or null. */
    suspend fun findShow(app: Requests, title: String, year: Int?): Int? {
        if (title.isBlank()) return null
        val key = "${normCmp(title)}|$year"
        val now = System.currentTimeMillis()
        findCache[key]?.let { (ts, v) -> if (now - ts < CACHE_MS) return v }
        val found = runCatching {
            val q = java.net.URLEncoder.encode(title, "UTF-8")
            val yearQ = year?.let { "&first_air_date_year=$it" } ?: ""
            val j = getJson(app, "/search/tv?query=$q$yearQ")
                ?: year?.let { getJson(app, "/search/tv?query=$q") }
            val arr = j?.optJSONArray("results") ?: return@runCatching null
            val qn = normCmp(title)
            var bestId: Int? = null
            var bestScore = -1
            for (i in 0 until arr.length().coerceAtMost(8)) {
                val r = arr.optJSONObject(i) ?: continue
                val names = listOfNotNull(
                    r.optStringOrNullCp("name"),
                    r.optStringOrNullCp("original_name"),
                ).map { normCmp(it) }
                if (names.isEmpty()) continue
                val exact = names.any { it == qn }
                val either = names.any { it.contains(qn) || qn.contains(it) }
                if (!exact && !either) continue
                val ry = r.optStringOrNullCp("first_air_date")
                    ?.take(4)?.toIntOrNull()
                val yearOk = year == null || ry == null ||
                    kotlin.math.abs(ry - year) <= 1
                if (!yearOk) continue
                var score = 0
                if (exact) score += 4
                if (either) score += 1
                if (ry != null && year != null && ry == year) score += 2
                if (score > bestScore) {
                    bestScore = score
                    bestId = r.optInt("id", 0).takeIf { it != 0 }
                }
            }
            bestId
        }.getOrNull()
        findCache[key] = now to found
        return found
    }

    /** LANDSCAPE backdrop + title LOGO for an anime MOVIE (the table
     *  builder above is tv-shaped; movies only need art, no episodes). */
    suspend fun movieBackdrop(app: Requests, tmdbId: Int): String? =
        getJson(app, "/movie/$tmdbId")
            ?.optStringOrNullCp("backdrop_path")
            ?.let { "https://image.tmdb.org/t/p/w1280$it" }

    private suspend fun buildTable(app: Requests, tmdbId: Int): Table? {
        val detail = getJson(app, "/tv/$tmdbId") ?: return null
        val seasonsArr = detail.optJSONArray("seasons") ?: return null
        val seasons = (0 until seasonsArr.length()).mapNotNull { i ->
            seasonsArr.optJSONObject(i)?.optInt("season_number")?.takeIf { it > 0 }
        }.sorted().distinct()
        if (seasons.isEmpty()) return null

        // Pull every TV season + the specials season + the image list,
        // bounded-parallel.
        val sem = Semaphore(4)
        val (seasonPairs, imagesJson) = coroutineScope {
            val seasonJobs = (listOf(0) + seasons).map { s ->
                async(Dispatchers.IO) {
                    sem.withPermit { s to getJson(app, "/tv/$tmdbId/season/$s") }
                }
            }
            val imgJob = async(Dispatchers.IO) {
                sem.withPermit { getJson(app, "/tv/$tmdbId/images?include_image_language=en,null") }
            }
            seasonJobs.awaitAll().toMap() to imgJob.await()
        }
        val seasonJson = seasonPairs
        val logos = imagesJson?.optJSONArray("logos")
        val logoPath = if (logos == null || logos.length() == 0) null
        else (0 until logos.length()).mapNotNull { logos.optJSONObject(it) }
            .filter { it.optStringOrNullCp("file_path") != null }
            .maxByOrNull { it.optDouble("vote_average") }
            ?.optStringOrNullCp("file_path")
        val artBackdrop = detail.optStringOrNullCp("backdrop_path")
            ?.let { "https://image.tmdb.org/t/p/w1280$it" }
        val artLogo = logoPath?.let { "https://image.tmdb.org/t/p/w500$it" }

        // (v80) Rank the whole backdrop pool ONCE, best art first:
        // highest community vote, then widest, then file path for a stable
        // deterministic order (the same entry must always get the same
        // image across app restarts — no shuffling headers).
        val backdropsArr = imagesJson?.optJSONArray("backdrops")
        val pool = if (backdropsArr == null) emptyList()
        else (0 until backdropsArr.length())
            .mapNotNull { backdropsArr.optJSONObject(it) }
            .filter { (it.optInt("width").takeIf { w -> w > 0 } ?: 0) >= 1280 }
            .filter { it.optStringOrNullCp("file_path") != null }
            .sortedWith(
                compareByDescending<JSONObject> { it.optDouble("vote_average").takeIf { d -> !d.isNaN() } ?: 0.0 }
                    .thenByDescending { it.optInt("width") }
                    .thenBy { it.optStringOrNullCp("file_path") ?: "" }
            )
            .mapNotNull { it.optStringOrNullCp("file_path") }
            .map { "https://image.tmdb.org/t/p/w1280$it" }

        fun rowOf(e: JSONObject): EpRow = EpRow(
            name = e.optStringOrNullCp("name"),
            overview = e.optStringOrNullCp("overview"),
            stillUrl = e.optStringOrNullCp("still_path")
                ?.let { "https://image.tmdb.org/t/p/w780$it" },
            airDate = parseYmd(e.optStringOrNullCp("air_date")),
            runtime = e.optInt("runtime").takeIf { it > 0 },
            score = e.optDouble("vote_average").takeIf { !it.isNaN() && it > 0.0 },
            tmdbSeason = null, tmdbEpisode = null,
        )

        val out = LinkedHashMap<Int, MutableList<EpRow>>()
        val lastAirBySeason = HashMap<Int, Long>()
        val firstAirBySeason = HashMap<Int, Long>()
        for (s in seasons) {
            val arr = seasonJson[s]?.optJSONArray("episodes")
            val raw = if (arr == null) emptyList()
            else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { it.optInt("episode_number", 0) > 0 }
                .sortedBy { it.optInt("episode_number") }
            out[s] = raw.map(::rowOf).toMutableList()
            raw.mapNotNull { parseYmd(it.optStringOrNullCp("air_date")) }.let { days ->
                days.minOrNull()?.let { firstAirBySeason[s] = it }
                days.maxOrNull()?.let { lastAirBySeason[s] = it }
            }
        }

        // ── Recursive specials tail-attach ────────────────────────────────
        val s0 = seasonJson[0]?.optJSONArray("episodes")
        if (s0 != null && s0.length() > 0) {
            class Cand(val airMs: Long, val s0Ep: Int, val row: EpRow)
            val cands = mutableListOf<Cand>()
            for (i in 0 until s0.length()) {
                val e = s0.optJSONObject(i) ?: continue
                val s0Ep = e.optInt("episode_number", 0).takeIf { it > 0 } ?: continue
                val run = e.optInt("runtime", 0)
                if (run < STORY_SPECIAL_MIN_RUNTIME_MIN) continue   // "1-hour" rule
                val specialName = e.optStringOrNullCp("name")
                if (specialName != null && NON_STORY_SPECIAL.containsMatchIn(specialName)) continue
                val air = parseYmd(e.optStringOrNullCp("air_date")) ?: continue
                cands += Cand(air, s0Ep, EpRow(
                    name = e.optStringOrNullCp("name"),
                    overview = e.optStringOrNullCp("overview"),
                    stillUrl = e.optStringOrNullCp("still_path")
                        ?.let { "https://image.tmdb.org/t/p/w780$it" },
                    airDate = air,
                    runtime = run,
                    score = e.optDouble("vote_average").takeIf { !it.isNaN() && it > 0.0 },
                    tmdbSeason = 0, tmdbEpisode = s0Ep,
                ))
            }
            cands.sortWith(compareBy({ it.airMs }, { it.s0Ep }))
            val absorbed = HashMap<Int, Int>()
            for (c in cands) {
                // Whose tail does this special continue? The LAST season
                // whose finale aired before it …
                val after = seasons.lastOrNull { s ->
                    lastAirBySeason[s]?.let { c.airMs > it } == true
                } ?: continue
                // … provided it aired BEFORE the next season's premiere.
                // For the FINAL season the window is OPEN-ENDED: TMDB's
                // last_air_date only counts regular episodes, so story
                // specials (the Final Chapters) land chronologically
                // "after the show ended" and would otherwise never absorb.
                val nextSeason = seasons.firstOrNull { it > after }
                val withinWindow = if (nextSeason == null) true
                else firstAirBySeason[nextSeason]?.let { c.airMs < it } == true
                if (!withinWindow) continue
                if ((absorbed[after] ?: 0) >= MAX_ABSORBED_PER_SEASON) continue
                // Skip pure re-packagings of the finale itself.
                if (out[after]?.lastOrNull()?.name?.equals(c.row.name, true) == true) continue
                absorbed[after] = (absorbed[after] ?: 0) + 1
                out[after]?.add(c.row)
            }
        }

        // Renumber sequentially after every attach: stacked EpRow index.
        val tableSeasons = out.mapValues { (_, rows) ->
            rows.mapIndexed { idx, r -> (idx + 1) to r }.toMap()
        }
        return Table(tableSeasons, artBackdrop, artLogo, pool)
    }

    /**
     * (v80) Pick a DISTINCT landscape header for ONE de-stacked entry.
     *
     * The problem: AniList has no stacked items — every season, cour part and
     * long special is its own entry — while TMDB has a single show-level
     * backdrop, so all of them rendered the same header image.
     *
     * TMDB has no per-season backdrops either (`/tv/{id}/season/{n}/images`
     * returns POSTERS only — verified live), so the fix is a priority chain
     * that is deterministic, offline-provable and costs no extra requests:
     *
     *   A. the show's backdrop POOL, dealt one-per-entry by [entryIndex]
     *      (real key art, ranked best-first, never repeats);
     *   B. else the best-voted EPISODE STILL from inside this entry's OWN
     *      stacked episode window (always season-accurate, and every season
     *      has them — AoT S1 25/25, S3 22/22, S4 28/28);
     *   C. else the shared show backdrop (previous behaviour, last resort).
     *
     * @param entryIndex position of this entry in its franchise (0-based,
     *        oldest first) — the deal index into the pool.
     * @param season     this entry's STACKED season number.
     * @param epFrom/epTo this entry's stacked episode window (inclusive).
     */
    fun entryBackdrop(
        table: Table?,
        entryIndex: Int,
        season: Int?,
        epFrom: Int?,
        epTo: Int?,
    ): String? {
        if (table == null) return null
        // (v81) ORDER INVERTED vs v80 — CORRECTNESS BEFORE PRETTINESS.
        //
        // v80 dealt the show-wide backdrop pool one image per entry. That
        // made every header DIFFERENT, but the images were not RELATED to
        // the season showing them: TMDB's pool is franchise-wide and
        // unordered with respect to seasons, so "Season 1" could easily be
        // handed art from the Final Season (a spoiler) or vice versa —
        // exactly the user's report ("the poster may indicate a season
        // before it or after it which does not look good").
        //
        // The only landscape art TMDB has that is PROVABLY tied to a given
        // season is the episode STILL, because it is addressed by
        // (season, episode). So the still now comes FIRST, chosen from the
        // entry's own stacked window — Season 3 Part 2 can only ever draw
        // from stacked S3E13-22, Special 2 only from S4E30, etc. It is
        // season-accurate by construction and cannot spoil a later arc.
        // (Verified live: AoT's 6 main entries → 6 distinct stills, each
        // from inside its own window; every season has full coverage —
        // S1 25/25, S2 12/12, S3 22/22, S4 28/28.)
        if (season != null && epFrom != null && epTo != null) {
            val rows = table.seasons[season]
            if (rows != null) {
                val best = rows.entries
                    .filter { it.key in epFrom..epTo }
                    .mapNotNull { e -> e.value.stillUrl?.let { u -> u to (e.value.score ?: 0.0) } }
                    // Highest-voted frame in the window, tie-broken by the
                    // episode number so the pick is deterministic.
                    .maxWithOrNull(compareBy({ it.second }, { it.first }))
                    ?.first
                // Stills are w780 in the table; ask for the wide variant so
                // the header isn't upscaled.
                if (best != null) return best.replace("/t/p/w780", "/t/p/w1280")
            }
        }
        // Fallback only when this entry has NO stills at all (unaired or
        // unmapped seasons): deal a distinct pool image so at least the
        // headers still differ, rather than repeating one picture.
        table.backdropPool.getOrNull(entryIndex)?.let { return it }
        // Last resort — the shared show backdrop.
        return table.backdropUrl
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  (v78) WizWyzieSubs — Wyzie Subs subtitle integration (sub.wyzie.io)
// ═════════════════════════════════════════════════════════════════════════════
//
// Wyzie is a subtitle-scraping API keyed by IMDB or TMDB id, which is exactly
// what every Wizstream page already carries — so it works for BDIX .mkv files
// (which expose no subtitle track to the player), for web sources, and for
// anime alike, independently of where the video itself came from.
//
//   GET https://sub.wyzie.io/search?id=<tt…|tmdbId>[&season=&episode=]
//                                  [&language=][&format=]&key=<KEY>
//
// Response = a JSON ARRAY of objects: { url, display, language, format,
// isHearingImpaired, source, encoding, … }. `url` is a ready-to-play
// subtitle file, so each entry maps 1:1 onto a Cloudstream SubtitleFile.
//
// KEY POLICY: a key is REQUIRED by the service and is NEVER shipped in this
// extension — the user pastes their own into Settings (WizSourcePrefs
// KEY_WYZIE). No key = this whole object no-ops instantly.

// (v78) File-level aliases: the integration objects below live at TOP level
// (outside the WizstreamSources object), so they cannot see its PRIVATE
// TAG/UA constants. Same values, one place.
private const val WIZ_TAG = "WizstreamSources"
private const val WIZ_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

internal object WizWyzieSubs {
    private const val API = "https://sub.wyzie.io/search"
    private const val LABEL = "Wyzie"
    private const val TIMEOUT_MS = 8_000
    // Free tier is 1 000 requests/day — a short cache keeps rapid
    // re-taps/rotations of the same episode from spending quota twice.
    private const val CACHE_MS = 10 * 60 * 1000L
    private const val MAX_SUBS = 40

    private val cache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Pair<String, String>>>>()

    /** True when the user has pasted a key. */
    fun enabled(): Boolean =
        WizstreamSources.WizSourcePrefs.isEnabled("wyziesubs") &&
            WizstreamSources.WizSourcePrefs.apiKey(WizstreamSources.WizSourcePrefs.KEY_WYZIE) != null

    /**
     * Fetch and emit subtitles for one episode/movie. Fail-soft in every
     * direction: no key, no ids, HTTP error, malformed body or zero results
     * all just return quietly — a subtitle service must never be able to
     * break link resolution.
     */
    suspend fun emit(
        app: Requests,
        imdbId: String?,
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val key = WizstreamSources.WizSourcePrefs.apiKey(WizstreamSources.WizSourcePrefs.KEY_WYZIE) ?: return
        if (!WizstreamSources.WizSourcePrefs.isEnabled("wyziesubs")) return
        // IMDB id preferred (Wyzie matches it most reliably); TMDB accepted.
        val id = imdbId?.takeIf { it.startsWith("tt") } ?: tmdbId?.takeIf { it > 0 }?.toString()
        if (id.isNullOrBlank()) return

        val ck = "$id|$season|$episode"
        val now = System.currentTimeMillis()
        cache[ck]?.let { (ts, subs) ->
            if (now - ts < CACHE_MS) {
                Log.d(WIZ_TAG, "Wyzie: cache hit $ck (${subs.size} sub(s))")
                subs.forEach { (lang, url) -> runCatching { subtitleCallback(SubtitleFile(lang, url)) } }
                return
            }
        }

        val sb = StringBuilder("$API?id=${WizstreamSources.encodeUrl(id)}")
        if (season != null && episode != null && season > 0 && episode > 0) {
            sb.append("&season=").append(season).append("&episode=").append(episode)
        }
        sb.append("&key=").append(WizstreamSources.encodeUrl(key))
        val url = sb.toString()

        val body = runCatching {
            val res = app.get(
                url,
                headers = mapOf("User-Agent" to WIZ_UA, "Accept" to "application/json"),
                timeout = TIMEOUT_MS.toLong(),
            )
            if (res.code !in 200..299) {
                // 401/403 = bad or exhausted key: say so ONCE, plainly.
                Log.w(WIZ_TAG, "Wyzie: HTTP ${res.code} — " + when (res.code) {
                    401, 403 -> "key rejected (check Settings → Integrations)"
                    429 -> "daily quota exhausted"
                    else -> "service error"
                })
                null
            } else res.text
        }.getOrNull() ?: return

        val arr = runCatching { JSONArray(body.trim()) }.getOrNull() ?: run {
            Log.w(WIZ_TAG, "Wyzie: response was not a JSON array")
            return
        }

        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length().coerceAtMost(MAX_SUBS)) {
            val o = arr.optJSONObject(i) ?: continue
            val subUrl = o.optStringOrNullCp("url") ?: continue
            val display = o.optStringOrNullCp("display")
                ?: o.optStringOrNullCp("language") ?: "Unknown"
            val hi = o.optBoolean("isHearingImpaired", false)
            val fmt = o.optStringOrNullCp("format")?.uppercase()
            val src = o.optStringOrNullCp("source")
            // Label carries language first (the app sorts/labels by it),
            // then the useful qualifiers. NEVER a resolution — house rule.
            val label = buildString {
                append("[$LABEL] ").append(display)
                if (hi) append(" · HI")
                if (fmt != null) append(" · ").append(fmt)
                if (src != null) append(" · ").append(src)
            }
            out += label to subUrl
        }
        if (out.isEmpty()) {
            Log.d(WIZ_TAG, "Wyzie: no subtitles for id=$id s=$season e=$episode")
            return
        }
        cache[ck] = now to out
        out.forEach { (lang, u) -> runCatching { subtitleCallback(SubtitleFile(lang, u)) } }
        Log.i(WIZ_TAG, "Wyzie: emitted ${out.size} subtitle(s) for id=$id s=$season e=$episode")
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  (v78) WizMdbList — MDBList ratings integration (api.mdblist.com)
// ═════════════════════════════════════════════════════════════════════════════
//
// IMPORTANT, DOCUMENTED FINDING: MDBList is a RATINGS + LISTS api, NOT an
// artwork api. Its published OpenAPI schema (api.mdblist.com/schema/) contains
// ZERO `backdrop`, `logo` or `fanart` fields; the only `poster` is
// `append_to_response=poster` on LIST endpoints ("up to 5 cached list
// posters" = list thumbnails, not show art). The media endpoint returns
// id / imdb_id / title / year / type / score / ratings / streams.
// So logo + landscape art keep coming from TMDB (WizEpisodeTable already
// fetches both), and MDBList is integrated for what it actually serves:
// ─────────────────────────────────────────────────────────────────────────────
//  (v86, user report) ani.zip PER-EPISODE fallback layer.
//
//  The JJK S3 "Culling Game Part 1" page (AniList 172463) shipped bare
//  "Episode N" rows and one key-visual thumbnail on every row: its
//  AniList streamingEpisodes feed is EMPTY (verified live) and when the
//  absolute-packed TMDB table misses on-device, nothing remained. Yet the
//  ani.zip id-map call every anime page already makes hand-maps each
//  ENTRY to TVDB episode rows carrying real EN titles, overviews, stills,
//  air dates and runtimes — entry-locally keyed ("1".."12"), so NO
//  season/absolute alignment math is needed. This parses that payload
//  once and shares it with both providers (the main catalogue fetches it
//  per franchise member, 30-min cached).
// ─────────────────────────────────────────────────────────────────────────────
// ─────────────────────────────────────────────────────────────────────────────
//  (v88, user: "robustify the extension") ONE bounded retry for the
//  page-load-critical metadata APIs. Before this, a single transient
//  mobile-network blip (TMDB/AniList/ani.zip/MDBList timing out once)
//  silently degraded a whole page: no episode table → bare "Episode N"
//  rows, no id-map → no episode fallback art/titles at all. On the happy
//  path nothing changes (one call, as always); on failure the block runs
//  ONE more time after 350 ms and only then gives up — worst-case +one
//  timeout, which beats a permanently broken page for that session.
// ─────────────────────────────────────────────────────────────────────────────
internal suspend fun <T> wizRetryOnce(tag: String, block: suspend () -> T?): T? {
    val first = runCatching { block() }.getOrNull()
    if (first != null) return first
    kotlinx.coroutines.delay(350)
    val second = runCatching { block() }.getOrNull()
    if (second == null) Log.w(WIZ_TAG, "$tag: failed twice — giving up this round")
    return second
}

internal object WizAniZip {
    data class Ep(
        val title: String?,
        val overview: String?,
        val image: String?,
        val airMs: Long?,
        val runtime: Int?,
        val score10: Double?,
    )

    private const val CACHE_MS = 30 * 60 * 1000L
    private val cache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Map<Int, Ep>>>()

    private fun airMsOf(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .apply { isLenient = false }
                .parse(s.take(10))?.time
        }.getOrNull()
    }

    /** Parse the `episodes` object of an ani.zip /mappings response. */
    fun parse(mapJson: JSONObject?): Map<Int, Ep> {
        val epsObj = mapJson?.optJSONObject("episodes") ?: return emptyMap()
        val out = LinkedHashMap<Int, Ep>()
        val keys = epsObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val localEp = k.toIntOrNull() ?: continue
            val o = epsObj.optJSONObject(k) ?: continue
            val titles = o.optJSONObject("title")
            val title = titles?.optStringOrNullCp("en")
                ?: titles?.optStringOrNullCp("x-jat")
                ?: titles?.optStringOrNullCp("ja")
            val rating = o.optStringOrNullCp("rating")?.toDoubleOrNull()
                ?: o.opt("rating")?.let { (it as? Number)?.toDouble() }
            out[localEp] = Ep(
                title = title?.takeIf { it.isNotBlank() },
                overview = (o.optStringOrNullCp("overview")
                    ?: o.optStringOrNullCp("summary")
                        ?.substringBefore("\nSource:"))?.takeIf { it.isNotBlank() },
                image = o.optStringOrNullCp("image")?.takeIf { it.startsWith("http") },
                airMs = airMsOf(o.optStringOrNullCp("airDate")
                    ?: o.optStringOrNullCp("airdate")),
                runtime = (o.opt("runtime") as? Number)?.toInt()?.takeIf { it > 0 }
                    ?: (o.opt("length") as? Number)?.toInt()?.takeIf { it > 0 },
                score10 = rating?.takeIf { it > 0.0 && it <= 10.0 },
            )
        }
        return out
    }

    /** Cached per-entry episode map (anime pages already hold the response
     *  from their own id-map call — they use [parse] directly; the main
     *  catalogue calls this per franchise member). */
    suspend fun episodes(app: Requests, anilistId: Int): Map<Int, Ep> {
        val now = System.currentTimeMillis()
        cache[anilistId]?.let { (ts, v) -> if (now - ts < CACHE_MS) return v }
        // (v88) one retry — this map is the v86 episode-title/art fallback,
        // so a single dropped request must not strip a page for 30 minutes.
        val parsed: Map<Int, Ep> = wizRetryOnce("ani-zip eps $anilistId") {
            val res = app.get(
                "https://api.ani.zip/mappings?anilist_id=$anilistId",
                headers = mapOf("User-Agent" to WIZ_UA),
                timeout = 8_000,
            )
            if (res.code !in 200..299) null else parse(JSONObject(res.text))
        } ?: emptyMap()
        cache[anilistId] = now to parsed
        return parsed
    }
}

// aggregated ratings from IMDb, TMDb, Trakt, Letterboxd, RogerEbert,
// Rotten Tomatoes, Metacritic and MyAnimeList.
//
//   GET https://api.mdblist.com/{provider}/{type}/{id}/?apikey=<KEY>
//       provider ∈ imdb|tmdb|tvdb|trakt|mal|mdblist ; type ∈ movie|show|any
//
// KEY POLICY: same as Wyzie — user-supplied, never bundled. No key = OFF.
internal object WizMdbList {
    private const val API = "https://api.mdblist.com"
    private const val TIMEOUT_MS = 8_000L
    private const val CACHE_MS = 6 * 60 * 60 * 1000L   // ratings barely move
    private const val NEG_CACHE_MS = 10 * 60 * 1000L

    class Ratings(
        /** 0-10 normalised aggregate for the page's score ring, or null. */
        val score10: Double?,
        /** Pretty one-line summary for the plot header. */
        val line: String?,
    )

    private val cache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Ratings?>>()

    fun enabled(): Boolean =
        WizstreamSources.WizSourcePrefs.isEnabled("mdblist") &&
            WizstreamSources.WizSourcePrefs.apiKey(WizstreamSources.WizSourcePrefs.KEY_MDBLIST) != null

    /** Pretty source names for the ratings line. */
    private val NICE = mapOf(
        "imdb" to "IMDb", "tmdb" to "TMDb", "trakt" to "Trakt",
        "letterboxd" to "Letterboxd", "tomatoes" to "RT",
        "audience" to "RT Audience", "metacritic" to "Metacritic",
        "myanimelist" to "MAL", "roger_ebert" to "Ebert",
        // (v87) two MDBList sources that rendered raw and ugly on-device
        // ("metacriticuser 8.1", "popcorn 89" — user screenshot 07-31).
        "metacriticuser" to "MC Users", "popcorn" to "Popcorn",
    )

    /**
     * Aggregated ratings for one title. Fail-soft: any error → null, and the
     * page renders exactly as it does today.
     */
    suspend fun ratings(
        app: Requests,
        imdbId: String?,
        tmdbId: Int?,
        isMovie: Boolean,
    ): Ratings? {
        val key = WizstreamSources.WizSourcePrefs.apiKey(WizstreamSources.WizSourcePrefs.KEY_MDBLIST) ?: return null
        if (!WizstreamSources.WizSourcePrefs.isEnabled("mdblist")) return null
        val provider: String
        val id: String
        when {
            !imdbId.isNullOrBlank() && imdbId.startsWith("tt") -> {
                provider = "imdb"; id = imdbId
            }
            tmdbId != null && tmdbId > 0 -> {
                provider = "tmdb"; id = tmdbId.toString()
            }
            else -> return null
        }
        val type = if (isMovie) "movie" else "show"
        val ck = "$provider|$type|$id"
        val now = System.currentTimeMillis()
        cache[ck]?.let { (ts, v) ->
            if (now - ts < (if (v == null) NEG_CACHE_MS else CACHE_MS)) return v
        }

        val url = "$API/$provider/$type/$id/?apikey=${WizstreamSources.encodeUrl(key)}"
        // (v88) one bounded retry — a single timeout shouldn't strip the
        // ratings line from a page for the user's remaining session.
        val parsed = wizRetryOnce("mdblist $ck") {
            val res = app.get(
                url,
                headers = mapOf("User-Agent" to WIZ_UA, "Accept" to "application/json"),
                timeout = TIMEOUT_MS,
            )
            if (res.code !in 200..299) {
                Log.w(WIZ_TAG, "MDBList: HTTP ${res.code} — " + when (res.code) {
                    401, 403 -> "key rejected (check Settings → Integrations)"
                    429 -> "daily quota exhausted"
                    else -> "service error"
                })
                return@wizRetryOnce null
            }
            val o = JSONObject(res.text)
            val arr = o.optJSONArray("ratings")
            val parts = ArrayList<String>()
            var imdbVal: Double? = null
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val srcName = r.optStringOrNullCp("source") ?: continue
                    // `value` is on the source's own scale (IMDb 0-10,
                    // RT/Metacritic 0-100, …) — shown verbatim, which is
                    // what a ratings line should do.
                    val v = r.opt("value")
                    val num = when (v) {
                        is Number -> v.toDouble()
                        is String -> v.toDoubleOrNull()
                        else -> null
                    } ?: continue
                    if (num <= 0.0) continue
                    if (srcName.equals("imdb", true)) imdbVal = num
                    val nice = NICE[srcName.lowercase()] ?: srcName
                    val shown = if (num == Math.floor(num)) num.toInt().toString()
                        else String.format(java.util.Locale.US, "%.1f", num)
                    parts += "$nice $shown"
                }
            }
            // Page score ring: prefer IMDb (already 0-10), else MDBList's
            // own `score` field, which it publishes as a 0-100 percentage.
            val score10 = imdbVal
                ?: o.opt("score")?.let { s ->
                    when (s) {
                        is Number -> s.toDouble()
                        is String -> s.toDoubleOrNull()
                        else -> null
                    }
                }?.takeIf { it > 0 }?.let { if (it > 10.0) it / 10.0 else it }
            // (v88, user choice) Ratings stay on ONE flowing line
            // ("⭐ IMDb 8.5 · TMDb 85 · Trakt 85 · MAL 8.5") — the blank
            // line after the block comes from the callers' <br><br>
            // separator before the synopsis. (Line breaks MUST be HTML:
            // Cloudstream's description view is HtmlCompat.fromHtml —
            // raw \n collapses, v87-verified.)
            if (parts.isEmpty() && score10 == null) null
            else Ratings(score10, parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  "))
        }
        cache[ck] = now to parsed
        if (parsed != null) {
            Log.i(WIZ_TAG, "MDBList: $ck → ${parsed.line ?: "score only"}")
        }
        return parsed
    }
}
