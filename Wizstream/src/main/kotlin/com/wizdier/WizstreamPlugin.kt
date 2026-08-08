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
import com.wizdier.WizstreamSources.CineJoyResolver
import com.wizdier.WizstreamSources.ShuttletvResolver
import com.wizdier.WizstreamSources.M4uHdResolver
import com.wizdier.WizstreamSources.CinemaOsResolver

/**
 * WizstreamPlugin — ONE extension hosting BOTH catalogues,
 * StreamPlay-style (single install → two sources):
 *
 *   • WizstreamProvider — the TMDB catalogue (movies, TV series, Asian
 *     drama, cartoons). Japanese-animation pages are ENRICHED from AniList
 *     (via api.ani.zip mapping + GraphQL): MAL/AniList/Kitsu tracking ids,
 *     AniList banner art, Japanese voice-actor cast.
 *   • WizstreamAnimeProvider ("Wizstream-Anime" in-app) — the PURE
 *     AniList-metadata catalogue. (v81) BOUND into this single install;
 *     (v82) the separate WizstreamAnime extension module is RETIRED — the
 *     provider + resolvers now live here permanently, no build-time
 *     mirroring anywhere.
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
            // (v94) cinejoy.to — movies & series (TMDB-keyed, PoW-gated API)
            WizSourcePrefs.src(CineJoyResolver, "WEB SOURCES", "cinejoy.to"),
            // (v96) shuttletv.su — movies & series (TMDB-keyed via cinesrc.st
            // embed; 2-stage PoW incl. WASM — loadExtractor-only)
            WizSourcePrefs.src(ShuttletvResolver, "WEB SOURCES", "shuttletv.su"),
            // (v99, user request) ww1.m4uhd.to — 9stream 1080p HLS in-repo
            // (AES chain ported; links verified before listing) + EN subs
            WizSourcePrefs.src(M4uHdResolver, "WEB SOURCES", "m4uhd.to"),
            // (v99, user request) cinemaos.live — TMDB-keyed ladder
            // (quiet until the CinemaOS backend serves again)
            WizSourcePrefs.src(CinemaOsResolver, "WEB SOURCES", "cinemaos.live"),
            // (v70) anime streaming sources — the de-stacked anime pages
            // ("CircleFTP structure") resolve through these too. Mirrored
            // from the WizstreamAnime module; same shared pref keys, so a
            // toggle here also applies in Wizstream-Anime and vice versa.
            // (v89) roster cut to AniNeko + KickAssAnime + AnimeX per user.
            WizSourcePrefs.src(WizstreamAnimeSources.AniNekoResolver, "ANIME-WEB SOURCES", "anineko.to"),
            WizSourcePrefs.src(WizstreamAnimeSources.KaaResolver, "ANIME-WEB SOURCES", "kaa.lt"),
            WizSourcePrefs.src(WizstreamAnimeSources.AnimexResolver, "ANIME-WEB SOURCES", "animex.one"),
            WizSourcePrefs.src(WizstreamAnimeSources.AniwavesResolver, "ANIME-WEB SOURCES", "aniwaves.ru"),
            // (v92) everythingmoe.com anime top-20 sweep — five new arrivals.
            WizSourcePrefs.src(WizstreamAnimeSources.AnikotoResolver, "ANIME-WEB SOURCES", "anikototv.to"),
            WizSourcePrefs.src(WizstreamAnimeSources.AnizoneResolver, "ANIME-WEB SOURCES", "anizone.to"),
            WizSourcePrefs.src(WizstreamAnimeSources.AnimeStreamResolver, "ANIME-WEB SOURCES", "anime.uniquestream.net"),
            WizSourcePrefs.src(WizstreamAnimeSources.AnibdResolver, "ANIME-WEB SOURCES", "anibd.app"),
            WizSourcePrefs.src(WizstreamAnimeSources.AnidbResolver, "ANIME-WEB SOURCES", "anidb.app"),
            // (v94, user request) anihq.cc + 2dhive.com
            WizSourcePrefs.src(WizstreamAnimeSources.AnihqResolver, "ANIME-WEB SOURCES", "anihq.cc"),
            WizSourcePrefs.src(WizstreamAnimeSources.DhiveResolver, "ANIME-WEB SOURCES", "2dhive.com"),
            // (v96, user request) anikage.cc — AniList-keyed, 5 providers
            WizSourcePrefs.src(WizstreamAnimeSources.AnikageResolver, "ANIME-WEB SOURCES", "anikage.cc"),
            // (v98, user request) toon-stream.site — cartoons/anime, Hindi+multi dubs
            WizSourcePrefs.src(WizstreamAnimeSources.ToonStreamResolver, "ANIME-WEB SOURCES", "toon-stream.site"),
        )
        openSettings = {
                ctx -> WizstreamSources.WizSourcePrefs.openDialog(ctx, toggleSources)
        }
    }
}
