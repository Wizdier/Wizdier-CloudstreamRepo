package com.wizdier

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * WizstreamPlugin — ONE unified extension again (since v54), one .cs3
 * hosting both catalogue providers in ONE package, StreamPlay-style:
 *
 *   • WizstreamProvider — the TMDB catalogue (movies, TV series, Asian
 *     drama, cartoons). Japanese-animation pages are ENRICHED from AniList
 *     (via api.ani.zip mapping + GraphQL): MAL/AniList/Kitsu tracking ids,
 *     AniList banner art, Japanese voice-actor cast — exactly the way
 *     StreamPlay supplements its TMDB catalogue.
 *   • WizstreamAnimeProvider — the full AniList catalogue (anime/OVA/
 *     movies) with the stacked CircleFTP-style multi-season pages
 *     (cours merged: Attack on Titan Season 3 = all 22 episodes) and
 *     per-entry AniList episode titles where the streaming feed has them.
 *
 * Link resolution for BOTH comes from the bundled resolver engine:
 * `WizstreamSources` (BDIX: Cineplex BD, FTPBD, Circle FTP, CTGMovies,
 * FM FTP, Mediaserver + web: Cineby, Bingr, Moonflix + the Vid[x] embed
 * family), `WizstreamAnimeSources` (AniZone / Mkissa-AllAnime / Miruro /
 * AniChi) and the vid-embed extractor family registered below.
 */
@CloudstreamPlugin
class WizstreamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(WizstreamProvider())
        registerMainAPI(WizstreamAnimeProvider())

        // VsEmbed handles vidsrc.to / vidsrc.mov / vidsrc-embed.su /
        // vidsrc.me redirects (they all 302 to vsembed.ru).
        registerExtractorAPI(VsEmbedExtractor())
        registerExtractorAPI(TwoEmbedCcExtractor())
        registerExtractorAPI(VidFastExtractor())
        registerExtractorAPI(VidLinkExtractor())
    }
}
