package com.wizdier

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

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
class WizstreamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(WizstreamProvider())

        // VsEmbed handles vidsrc.to / vidsrc.mov / vidsrc-embed.su /
        // vidsrc.me redirects (they all 302 to vsembed.ru).
        registerExtractorAPI(VsEmbedExtractor())
        registerExtractorAPI(TwoEmbedCcExtractor())
        registerExtractorAPI(VidFastExtractor())
        registerExtractorAPI(VidLinkExtractor())
    }
}
