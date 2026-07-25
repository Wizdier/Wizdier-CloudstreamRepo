package com.wizdier

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * WizstreamPlugin — the TMDB half of Wizstream, since v51 its own
 * single-purpose extension (the AniList catalogue moved out into the
 * separate WizstreamAnime plugin):
 *
 *   • WizstreamProvider — TMDB catalogue (movies, TV series, Asian drama,
 *     cartoons).
 *
 * Link resolution comes from the bundled `WizstreamSources` resolver
 * bundle: the BDIX sources (Cineplex BD, FTPBD, Circle FTP, CTGMovies,
 * FM FTP, Mediaserver), the web sources (Cineby, Bingr, Moonflix) and the
 * Vid[x] embed family, all in parallel, de-duplicated by URL.
 *
 * Custom extractors (WizstreamExtractors.kt) are registered here so
 * `loadExtractor` can dispatch to them when a vid embed URL is encountered
 * that Cloudstream's built-in extractor registry doesn't cover.
 *
 * NOTE: the same WizstreamSources.kt + WizstreamExtractors.kt files are
 * mirrored into ../WizstreamAnime at build time — edit them HERE (the
 * Wizstream module is the single source of truth).
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
