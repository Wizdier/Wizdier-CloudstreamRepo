package com.wizdier

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.wizdier.WizstreamSources.WizSourcePrefs
import com.wizdier.WizstreamSources.CineplexBdResolver
import com.wizdier.WizstreamSources.FtpBdResolver
import com.wizdier.WizstreamSources.CircleFtpResolver
import com.wizdier.WizstreamSources.CtgMoviesResolver
import com.wizdier.WizstreamSources.FmFtpResolver
import com.wizdier.WizstreamSources.MediaserverResolver
import com.wizdier.WizstreamSources.CinebyResolver
import com.wizdier.WizstreamSources.BingrResolver
import com.wizdier.WizstreamSources.MoonflixResolver

/**
 * WizstreamAnimePlugin — the PURE-AniList stand-alone module.
 *
 *   • WizstreamAnimeProvider (THE anime catalogue, registered with the
 *     canonical name "Wizstream-Anime") — every piece of metadata sourced
 *     from AniList alone: zero TMDB calls, zero TMDB fields. Stacked
 *     CircleFTP-style franchise pages, per-entry streaming-feed episode
 *     titles/thumbs, Japanese-VA cast, MAL/AniList tracking, AniList
 *     watchlist home rows.
 *
 * Link resolution reuses the SAME engine as Wizstream: WizstreamSources
 * (BDIX + web + Vid[x] family), WizstreamAnimeSources (AniZone /
 * Mkissa-AllAnime / Miruro / AniChi) and the vid-embed extractor family
 * registered below.
 */
@CloudstreamPlugin
class WizstreamAnimePlugin : Plugin() {   // (v68) app-side Plugin ⇒ openSettings button
    override fun load() {
        registerMainAPI(WizstreamAnimeProvider())

        registerExtractorAPI(VsEmbedExtractor())
        registerExtractorAPI(TwoEmbedCcExtractor())
        registerExtractorAPI(VidFastExtractor())
        registerExtractorAPI(VidLinkExtractor())

        // (v68) Per-source on/off menu: the shared BDIX + web resolvers
        // PLUS this module's anime-web resolvers (ids must match each
        // resolver's class-name-derived toggleId — AniZoneResolver →
        // "anizone", UniqueStreamResolver → "uniquestream", etc).
        val toggleSources = listOf(
            WizSourcePrefs.src(CineplexBdResolver, true),
            WizSourcePrefs.src(FtpBdResolver, true),
            WizSourcePrefs.src(CircleFtpResolver, true),
            WizSourcePrefs.src(CtgMoviesResolver, true),
            WizSourcePrefs.src(FmFtpResolver, true),
            WizSourcePrefs.src(MediaserverResolver, true),
            WizSourcePrefs.src(CinebyResolver, false),
            WizSourcePrefs.src(BingrResolver, false),
            WizSourcePrefs.src(MoonflixResolver, false),
        ) + listOf(
            WizstreamSources.WizSourcePrefs.Src("anizone", "AniZone", false),
            WizstreamSources.WizSourcePrefs.Src("allmanga", "AllAnime (Mkissa)", false),
            WizstreamSources.WizSourcePrefs.Src("anichi", "AniChi", false),
            WizstreamSources.WizSourcePrefs.Src("uniquestream", "UniqueStream", false),
            WizstreamSources.WizSourcePrefs.Src("anineko", "AniNeko", false),
            WizstreamSources.WizSourcePrefs.Src("reanime", "ReAnime", false),
            WizstreamSources.WizSourcePrefs.Src("tokyoinsider", "TokyoInsider", false),
        )
        openSettings = {
                ctx -> WizstreamSources.WizSourcePrefs.openDialog(ctx, toggleSources)
        }
    }
}
