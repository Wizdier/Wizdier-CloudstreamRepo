package com.wizdier

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

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
class WizstreamAnimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(WizstreamAnimeProvider())

        registerExtractorAPI(VsEmbedExtractor())
        registerExtractorAPI(TwoEmbedCcExtractor())
        registerExtractorAPI(VidFastExtractor())
        registerExtractorAPI(VidLinkExtractor())
    }
}
