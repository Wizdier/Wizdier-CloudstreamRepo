package com.wizdier

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * WizstreamAnimePlugin — the AniList half of Wizstream, split into its own
 * extension in v51 ("fetch from TMDB" / "fetch from AniList" as two simple
 * separate plugins, all link-scraping logic unchanged).
 *
 *   • WizstreamAnimeProvider — AniList catalogue (anime / OVA / movies),
 *     stacked CircleFTP-style multi-season pages with cours merged
 *     (Attack on Titan Season 3 = all 22 episodes on one site-style
 *     bucket), MAL / AniList / Kitsu / Simkl tracking.
 *
 * Link resolution reuses the SAME engine as the Wizstream (TMDB) plugin:
 * `WizstreamSources` (BDIX: Cineplex BD, FTPBD, Circle FTP, CTGMovies,
 * FM FTP, Mediaserver + web: Cineby, Bingr, Moonflix) plus the anime-web
 * resolvers in `WizstreamAnimeSources` (AniZone / Mkissa-AllAnime /
 * Miruro / AniChi) and the vid-embed extractor family registered below.
 *
 * NOTE: WizstreamSources.kt + WizstreamExtractors.kt in this module are
 * build-time mirrors of the files in ../Wizstream — edit those, not these.
 */
@CloudstreamPlugin
class WizstreamAnimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(WizstreamAnimeProvider())

        // Same extractor registrations as the TMDB plugin — both resolve
        // vid-embed URLs through the shared WizstreamSources helpers.
        registerExtractorAPI(VsEmbedExtractor())
        registerExtractorAPI(TwoEmbedCcExtractor())
        registerExtractorAPI(VidFastExtractor())
        registerExtractorAPI(VidLinkExtractor())
    }
}
