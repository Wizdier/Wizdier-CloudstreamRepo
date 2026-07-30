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
 * WizstreamPlugin — ONE extension hosting the main catalogue (since v54),
 * StreamPlay-style:
 *
 *   • WizstreamProvider — the TMDB catalogue (movies, TV series, Asian
 *     drama, cartoons). Japanese-animation pages are ENRICHED from AniList
 *     (via api.ani.zip mapping + GraphQL): MAL/AniList/Kitsu tracking ids,
 *     AniList banner art, Japanese voice-actor cast.
 *
 * (v56) The AniList catalogue is NOT bundled here anymore — it lives in
 * the separate WizstreamAnime module (PURE AniList metadata, registered
 * there as "Wizstream-Anime").
 *
 * Link resolution comes from the bundled `WizstreamSources` resolver
 * engine: BDIX sources (Cineplex BD, FTPBD, Circle FTP, CTGMovies, FM FTP,
 * Mediaserver), web sources (Cineby, Bingr, Moonflix) and the Vid[x] embed
 * family (extractors registered below).
 */
@CloudstreamPlugin
class WizstreamPlugin : Plugin() {   // (v68) app-side Plugin ⇒ openSettings button
    override fun load() {
        registerMainAPI(WizstreamProvider())
        // (v81) BOUND: one install now provides BOTH catalogues. The
        // separate "Wizstream-Anime" extension no longer has to be found
        // and installed by hand — installing Wizstream registers the pure
        // AniList anime catalogue too, under its same in-app name, so
        // existing pages/bookmarks/tracking keep resolving unchanged.
        registerMainAPI(WizstreamAnimeProvider())

        // VsEmbed handles vidsrc.to / vidsrc.mov / vidsrc-embed.su /
        // vidsrc.me redirects (they all 302 to vsembed.ru).
        registerExtractorAPI(VsEmbedExtractor())
        registerExtractorAPI(TwoEmbedCcExtractor())
        registerExtractorAPI(VidFastExtractor())
        registerExtractorAPI(VidLinkExtractor())

        // (v68) Per-source on/off menu. The app shows an "Open Settings"
        // action on this extension's card whenever openSettings is set;
        // toggles persist in DataStore keys and apply on the NEXT resolve
        // (instant — no restart). True = BDIX source (tagged in the list).
        val toggleSources = listOf(
            WizSourcePrefs.src(CineplexBdResolver, "BDIX SOURCES", "cineplexbd.net"),
            WizSourcePrefs.src(FtpBdResolver, "BDIX SOURCES", "ftpbd.net"),
            WizSourcePrefs.src(CircleFtpResolver, "BDIX SOURCES", "new.circleftp.net"),
            WizSourcePrefs.src(CtgMoviesResolver, "BDIX SOURCES", "ctgmovies.com"),
            WizSourcePrefs.src(FmFtpResolver, "BDIX SOURCES", "FM FTP · BDIX"),
            WizSourcePrefs.src(MediaserverResolver, "BDIX SOURCES", "Mediaserver · BDIX"),
            WizSourcePrefs.src(CinebyResolver, "WEB SOURCES", "www.cineby.at"),
            WizSourcePrefs.src(BingrResolver, "WEB SOURCES", "bingr.one"),
            WizSourcePrefs.src(MoonflixResolver, "WEB SOURCES", "Moonflix · web"),
            // (v70) anime streaming sources — the de-stacked anime pages
            // ("CircleFTP structure") resolve through these too. Mirrored
            // from the WizstreamAnime module; same shared pref keys, so a
            // toggle here also applies in Wizstream-Anime and vice versa.
            WizSourcePrefs.src(WizstreamAnimeSources.AniZoneResolver, "ANIME-WEB SOURCES", "anizone.to"),
            WizSourcePrefs.src(WizstreamAnimeSources.AllmangaResolver, "ANIME-WEB SOURCES", "allmanga.to"),
            WizSourcePrefs.src(WizstreamAnimeSources.AniChiResolver, "ANIME-WEB SOURCES", "anichi.to"),
            WizSourcePrefs.src(WizstreamAnimeSources.UniqueStreamResolver, "ANIME-WEB SOURCES", "anime.uniquestream.net"),
            WizSourcePrefs.src(WizstreamAnimeSources.AniNekoResolver, "ANIME-WEB SOURCES", "anineko.to"),
            WizSourcePrefs.src(WizstreamAnimeSources.ReAnimeResolver, "ANIME-WEB SOURCES", "reanime.to"),
            WizSourcePrefs.src(WizstreamAnimeSources.TokyoInsiderResolver, "ANIME-WEB SOURCES", "tokyoinsider.com"),
        )
        openSettings = {
                ctx -> WizstreamSources.WizSourcePrefs.openDialog(ctx, toggleSources)
        }
    }
}
