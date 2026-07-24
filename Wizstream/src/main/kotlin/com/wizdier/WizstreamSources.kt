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
import java.util.Base64

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
    ): Boolean = coroutineScope {
        if (title.isBlank() && tmdbId == null && imdbId == null) {
            return@coroutineScope false
        }

        val sources = listOf(
            CineplexBdResolver,
            FtpBdResolver,
            CircleFtpResolver,
            CtgMoviesResolver,
            FmFtpResolver,
            MediaserverResolver,
            CinebyResolver,
            BingrResolver,
            MoonflixResolver,
        )

        val gate = Semaphore(4)
        val jobs = sources.map { src ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
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
                    }.getOrDefault(false)
                }
            }
        }
        var found = jobs.awaitAll().any { it }

        // ── (v31) Alternate-title pass for the BDIX resolvers ─────────────
        // AniList feeds romaji/English titles that BDIX catalogues may index
        // under the OTHER name ("Sousou no Frieren" vs "Frieren: Beyond
        // Journey's End"). Only the four BDIX resolvers search by raw title,
        // so only they are re-run with the alias; the API-backed sources
        // (Cineby/Bingr/Moonflix) key on TMDB IDs and would just duplicate.
        if (!altTitle.isNullOrBlank() && !altTitle.equals(title, ignoreCase = true)) {
            val bdix = listOf(
                CineplexBdResolver, FtpBdResolver, CircleFtpResolver, CtgMoviesResolver,
                FmFtpResolver, MediaserverResolver,
            )
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
            if (altJobs.awaitAll().any { it }) found = true
        }
        found
    }

    // ───────────────────────────────────────────────────────────────────────
    //  Shared helpers
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Title similarity score (Jaccard token overlap, 0..1). Used as a
     * secondary check after exact-match normalised comparison.
     */
    internal fun titleSimilarity(a: String, b: String): Double {
        val ax = a.normaliseTitle()
        val bx = b.normaliseTitle()
        if (ax == bx) return 1.0
        if (ax.isEmpty() || bx.isEmpty()) return 0.0
        val ta = ax.split(Regex("\\s+")).toSet()
        val tb = bx.split(Regex("\\s+")).toSet()
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
        replace(Regex("\\s*[–-]\\s*\\[[^]]+]\\s*$"), "")
            .replace(Regex("\\[[^]]+]"), "")
            .replace(Regex("(?i)\\b(hindi|dubbed|dual audio|multi audio|season \\d+|eng sub|bengali|korean|english|japanese|subbed)\\b"), "")
            .trim()
            .ifBlank { this }

    internal fun String.normaliseTitle(): String =
        cleanMediaTitle()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

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
        val yr = Regex("\\b(19|20)\\d{2}\\b").find(t)?.value?.toIntOrNull()
        t = t
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("(?i)\\b(19|20)\\d{2}\\b"), " ")
            .replace(IDENTITY_JUNK_REGEX, " ")
            .replace(Regex("(?i)\\bseason\\s*\\d{1,2}\\b"), " ")
            .replace(Regex("(?i)\\bs\\d{1,2}\\s*e\\d{1,3}\\b"), " ")
            .replace(Regex("(?i)\\bs\\d{1,2}\\b"), " ")
            .replace(Regex("(?i)\\be\\d{1,3}\\b"), " ")
            .replace(Regex("(?i)\\b(?:ep|episode)\\s*\\d{1,3}\\b"), " ")
            .replace(Regex("(?i)\\b\\d+(?:[.,]\\d+)?\\s*(?:gb|mb|tb)\\b"), " ")
            .replace(Regex("(?i)\\b\\d{3,4}p\\b"), " ")
            .replace(Regex("(?i)\\b(?:8|10)bit\\b"), " ")
        val toks = t.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(Regex("\\s+"))
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
        return Regex("""-\d{2,4}x\d{2,4}\.[a-zA-Z0-9]{2,4}(?:\?|$)""")
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
        if (Regex("""^[\w.-]+\.[a-zA-Z]{2,}/\S*$""").matches(u)) return "https://$u"

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

    internal interface SourceResolver {
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
                                ?: Regex("(?i)E(\\d+)").find(rawName)
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
                                ?: Regex("(?i)E(\\d+)").find(rawName)
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
                        ?: Regex("""(?i)S\d+E(\d+)""").find(holder?.text().orEmpty())
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
            val audioRegex = Regex("(?i)\\b(dual[- ]?audio|multi[- ]?audio|dubbed|hindi[- ]?dubbed|eng[- ]?sub|bengali|hindi|dual|multi)\\b")
            val audioMatches = audioRegex.findAll(postTitle).map { it.value.trim() }.toList()
            val audioTag = if (audioMatches.isNotEmpty()) {
                audioMatches.joinToString(" ").uppercase()
            } else null

            var cleaned = postName?.trim().orEmpty()
            if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true)) {
                cleaned = postTitle.replace(Regex("\\.[a-zA-Z0-9]{2,4}$"), "")
                    .replace(".", " ")
                    .replace("_", " ")
                    .replace("-", " ")
                    .replace(Regex("\\b(19|20)\\d{2}\\b"), "")
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
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")

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
            val searchResp = fetchWithFallback(
                app,
                primary = "$PRIMARY_API/api/posts?searchTerm=${encodeUrl(title)}&order=desc",
                fallback = "$FALLBACK_API/api/posts?searchTerm=${encodeUrl(title)}&order=desc",
            ) ?: return false
            val searchText = searchResp.first

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
            val ipRewriteLinks = searchResp.second

            val postsArr = runCatching { JSONObject(searchText).optJSONArray("posts") }
                .getOrNull() ?: return false
            if (postsArr.length() == 0) return false

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
                }
            }
            val matchingPostIds = identityFiltered.ifEmpty { fuzzyFiltered }
            if (matchingPostIds.isEmpty()) {
                Log.d(TAG, "CircleFTP: no match for '$title' (year=$year) — skipping")
                return false
            }

            val srcLabel = "$labelPrefix • $LABEL"
            val mediaUrls = linkedSetOf<String>()

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
            if (postDetails.isEmpty()) return false

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
            if (typeFiltered.isEmpty()) return false

            // (v18) identity matching happened before the detail fetches —
            // every post here is the requested title, so no relevance
            // re-check (and no "keep everything" fallback) is needed.
            val filteredDetails = typeFiltered
            if (filteredDetails.isEmpty()) return false

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

                filteredDetails.forEach { detail ->
                    val contentArray = detail.optJSONArray("content")
                    if (contentArray == null || contentArray.length() == 0) return@forEach

                    // Determine the post's season number from its title.
                    // "One Piece Season 2" → 2; "One Piece S3" → 3; "One Piece" → null.
                    val postTitleStr = detail.optString("title", "")
                        .ifBlank { detail.optString("name", "") }
                    val titleSeasonNum = extractSeasonFromTitle(postTitleStr)

                    // Try content[season-1] first (standard multi-season layout).
                    var seasonObj = contentArray.optJSONObject(seasonToUse - 1)
                    var episodesArray = seasonObj?.optJSONArray("episodes")

                    // Fallback: if content[season-1] doesn't exist or has no
                    // episodes, try content[0] — but ONLY when:
                    //   • The post title explicitly says "Season N" matching
                    //     the requested season (so content[0] IS the
                    //     requested season), OR
                    //   • The post title has no "Season N" marker (so this
                    //     is likely a single-season post).
                    if (episodesArray == null || episodesArray.length() == 0) {
                        val canFallbackToContent0 = titleSeasonNum == null || titleSeasonNum == seasonToUse
                        if (canFallbackToContent0) {
                            seasonObj = contentArray.optJSONObject(0)
                            episodesArray = seasonObj?.optJSONArray("episodes")
                        }
                    }

                    if (episodesArray != null && episodeToUse in 1..episodesArray.length()) {
                        val epObj = episodesArray.optJSONObject(episodeToUse - 1)
                        val link = epObj?.optStringOrNullCp("link")
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

            // 7. Emit — v33: 1:1 with CircleFtpProvider.loadLinks.
            //    Every link goes out as a PLAIN link: no referer, no extra
            //    headers, no player-page probing, no payload fetch. The old
            //    version routed raw URLs through emitDirect, which (a)
            //    attached Referer/headers the CDN rejects (→ HTTP 2004 in
            //    Wizstream while the standalone, which attaches nothing,
            //    played fine) and (b) fetched m3u8 playlists up-front via
            //    generateM3u8, so a slow/dead CDN box surfaced as a load-time
            //    timeout instead of a player decision.
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
            // Match "Season N" or "SeasonN" (case-insensitive).
            Regex("(?i)\\bseason\\s*(\\d+)\\b").find(title)?.let {
                return it.groupValues.getOrNull(1)?.toIntOrNull()
            }
            // Match "S<N>" but not "S" alone or "s03e15" (which is episode).
            // We require a word boundary before S and the number to be 1-2 digits.
            Regex("(?i)\\bS(\\d{1,2})\\b").find(title)?.let {
                return it.groupValues.getOrNull(1)?.toIntOrNull()
            }
            return null
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
            CinebyServer("Neon",    "neon2",    audioLabel = "Original"),
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
            // Primary: aggregate endpoint (what the site's own player calls).
            // Retried once with a fresh seed on a 401, mirroring the site's BV().
            for (attempt in 0..1) {
                try {
                    any = resolveMbx(
                        app, tmdbId, title, yearStr, imdbIdStr,
                        mediaType, seasonId, episodeId, isMovie,
                        seed, srcLabel, subtitleCallback, callback,
                    )
                    break
                } catch (e: SeedInvalidException) {
                    Log.d(TAG, "Cineby: mbx seed rejected, refreshing (attempt $attempt)")
                    if (attempt == 1 || !refreshSeed()) break
                } catch (e: Exception) {
                    Log.d(TAG, "Cineby: mbx failed: ${e.message}")
                    break
                }
            }

            // Fallback: per-server fan-out, bounded concurrency (same as before,
            // but now with local mvm1 decryption instead of enc-dec.app).
            if (!any) {
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

        /** v30 primary path: the aggregate /mbx endpoint — one request returns
         *  sources for every server the backend considers alive. */
        private suspend fun resolveMbx(
            app: Requests,
            tmdbId: Int,
            title: String,
            yearStr: String,
            imdbIdStr: String,
            mediaType: String,
            seasonId: String,
            episodeId: String,
            isMovie: Boolean,
            seed: String,
            srcLabel: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val query = buildSourcesQuery(
                "mbx", tmdbId, doubleEncode(title), mediaType, yearStr,
                imdbIdStr, seasonId, episodeId, language = null,
            )
            val plaintext = fetchAndDecrypt(app, query, tmdbId, seed) ?: return false
            val result = unwrapResult(plaintext) ?: return false

            var any = false
            val sourcesArr = result.optJSONArray("sources")
            if (sourcesArr != null) {
                emitSubtitles(result.optJSONArray("subtitles"), "Cineby", subtitleCallback)
                for (i in 0 until sourcesArr.length()) {
                    val s = sourcesArr.optJSONObject(i) ?: continue
                    val url = s.optStringOrNullCp("url") ?: continue
                    if (!url.startsWith("http")) continue
                    val safeUrl = if (url.startsWith("http://")) {
                        url.replaceFirst("http://", "https://")
                    } else url
                    val quality = s.optStringOrNullCp("quality") ?: "Auto"
                    val tag = serverTagOf(s, safeUrl)
                    val group = if (tag != null) "$srcLabel · $tag" else srcLabel
                    val name = "Cineby" + (tag?.let { " · $it" } ?: "") + languageTagOf(quality)
                    if (emitMedia(app, safeUrl, quality, group, name, callback)) {
                        any = true
                    }
                }
            }
            if (any) {
                // The site fetches subtitles for the player from subs.videasy.to
                // separately — mirror that so Cineby picks keep subtitles.
                fetchVideasySubs(app, tmdbId, isMovie, seasonId, episodeId, subtitleCallback)
            }
            return any
        }

        /** v30: shared emission ladder for the aggregate path — same probe and
         *  device gates the per-server path applies inline. */
        private suspend fun emitMedia(
            app: Requests,
            safeUrl: String,
            qualityRaw: String,
            serverSourceLabel: String,
            name: String,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            if (emitTaggedMedia(app, safeUrl, serverSourceLabel, name, callback)) return true
            when {
                safeUrl.contains(".m3u8", true) -> {
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
                    }
                    return true
                }
                safeUrl.contains(".mp4", true) || safeUrl.contains(".mkv", true) ||
                    safeUrl.contains(".webm", true) -> {
                    callback(
                        newExtractorLink(
                            source = serverSourceLabel,
                            name = name,
                            url = safeUrl,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = "$SITE/"
                            this.quality = qualityFromName(safeUrl).takeIf { it > 0 }
                                ?: qualityFromName(qualityRaw)
                            this.headers = API_HEADERS
                        }
                    )
                    return true
                }
                safeUrl.contains(".mpd", true) -> {
                    callback(
                        newExtractorLink(
                            source = serverSourceLabel,
                            name = name,
                            url = safeUrl,
                            type = ExtractorLinkType.DASH,
                        ) {
                            this.referer = "$SITE/"
                            this.quality = qualityFromName(safeUrl).takeIf { it > 0 }
                                ?: qualityFromName(qualityRaw)
                            this.headers = API_HEADERS
                        }
                    )
                    return true
                }
            }
            return false
        }

        /** Emit a subtitles JSON array with the "[Tag] Lang" labelling convention. */
        private fun emitSubtitles(
            arr: JSONArray?,
            tag: String,
            subtitleCallback: (SubtitleFile) -> Unit,
        ) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val subUrl = s.optStringOrNullCp("url") ?: s.optStringOrNullCp("file")
                    ?: s.optStringOrNullCp("src") ?: continue
                val rawSubLabel = s.optStringOrNullCp("language") ?: s.optStringOrNullCp("label")
                    ?: s.optStringOrNullCp("lang") ?: s.optStringOrNullCp("name") ?: "Subtitle"
                subtitleCallback(SubtitleFile("[$tag] $rawSubLabel", subUrl))
            }
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

        private val KNOWN_SERVER_TAGS = listOf(
            "neon" to "Neon", "yoru" to "Yoru", "breach" to "Breach",
            "vyse" to "Vyse", "gekko" to "Gekko", "kayo" to "Kayo",
            "jett" to "Jett", "sage" to "Sage", "omen" to "Omen",
            "cypher" to "Cypher", "tejo" to "Tejo", "chamber" to "Chamber",
            "harbor" to "Harbor", "killjoy" to "Killjoy", "fade" to "Fade",
            "raze" to "Raze",
        )

        /** Identify which backend server an aggregate source came from — explicit
         *  JSON keys when present, CDN host fingerprints otherwise. */
        private fun serverTagOf(src: JSONObject, url: String): String? {
            for (key in listOf("server", "provider", "source", "site")) {
                val v = src.optStringOrNullCp(key) ?: continue
                val low = v.lowercase()
                if (low.contains("http") || v.length > 24) continue
                KNOWN_SERVER_TAGS.firstOrNull { it.first in low }?.let { return it.second }
            }
            val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }
                .getOrDefault("")
            return when {
                "ironwall" in host || "interkh" in host -> "Neon"
                "cdntv" in host -> "Yoru"
                else -> null
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
                c = rotl(c + a, i and 31) xor rotl(a, (31 * (i * 7)) and 31)
                c += K
                a = f(c)
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
                                bingrName(srvName, langPart, ""), "$SITE/", HEADERS, callback)
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
                                callback(
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
                        callback(
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
                        callback(
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
                            "Moonflix · HDGhar", "$PLAYER/", HEADERS, callback)
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
                            callback(
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
                    callback(
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
                    callback(
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
         *  matching "Season N …" folder(s) → SxxEyy file(s); bare-Exx only
         *  as a last resort (covers flat show roots). */
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
            val seasonRe = Regex("""(?i)season[\s._-]*0*""" + season + """(\D|$)""")
            val epRe = Regex("""(?i)S0*""" + season + """E0*""" + episode + """(\D|$)""")
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
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared JSON helpers
// ─────────────────────────────────────────────────────────────────────────────

internal fun JSONObject.optStringOrNullCp(k: String): String? =
    if (!has(k) || isNull(k)) null
    else optString(k, "").trim().takeIf { it.isNotBlank() && it != "null" }
