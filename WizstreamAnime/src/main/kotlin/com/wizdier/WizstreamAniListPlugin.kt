package com.wizdier

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * WizstreamAniListPlugin — the PURE-AniList stand-alone module (v55+).
 *
 *   • WizstreamAnimeProvider (registered here as "Wizstream-AniList" so it
 *     can never collide with the "Wizstream-Anime" provider inside the
 *     unified Wizstream package) — the AniList catalogue with EVERY piece
 *     of metadata sourced from AniList alone: zero TMDB calls, zero TMDB
 *     fields. Stacked CircleFTP-style franchise pages, per-entry
 *     streaming-feed episode titles/thumbs, Japanese-VA cast, MAL/AniList
 *     tracking, AniList watchlist home rows.
 *
 * Link resolution reuses the SAME engine as Wizstream: WizstreamSources
 * (BDIX + web + Vid[x] family), WizstreamAnimeSources (AniZone /
 * Mkissa-AllAnime / Miruro / AniChi) and the vid-embed extractor family
 * registered below.
 */
@CloudstreamPlugin
class WizstreamAniListPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(WizstreamAnimeProvider())

        registerExtractorAPI(VsEmbedExtractor())
        registerExtractorAPI(TwoEmbedCcExtractor())
        registerExtractorAPI(VidFastExtractor())
        registerExtractorAPI(VidLinkExtractor())
    }
}
