package com.wizdier

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * WizstreamPlugin — single Cloudstream plugin that registers TWO MainAPI
 * providers from one .cs3 file (the "streamplay" pattern):
 *
 *   1. WizstreamProvider        — TMDB catalogue (movies & TV series)
 *   2. WizstreamAnimeProvider   — AniList catalogue (anime / OVA / movies)
 *
 * Both providers share the same `WizstreamSources` resolver bundle so
 * every loadLinks call attempts the 4 BDIX source sites (Cineplex BD,
 * FTPBD, Circle FTP, CTGMovies) AND the Vid[x] embed family
 * (vidsrc, vidnest, vidplay, vidup, vidrock, vidfast, videasy) in
 * parallel. Duplicates are de-duped by URL.
 *
 * Custom extractors (WizstreamExtractors.kt) are registered here so
 * `loadExtractor` can dispatch to them when a vid embed URL is encountered
 * that Cloudstream's built-in extractor registry doesn't cover.
 */
@CloudstreamPlugin
class WizstreamPlugin : BasePlugin() {
    override fun load() {
        // Register both catalogue providers.
        registerMainAPI(WizstreamProvider())
        registerMainAPI(WizstreamAnimeProvider())

        // Register custom vid-embed extractors. These complement
        // Cloudstream's built-in extractor registry — when loadExtractor
        // is called with a URL whose host matches one of our extractors'
        // mainUrl, our extractor's getUrl() is invoked.
        //
        // VsEmbed handles vidsrc.to / vidsrc.mov / vidsrc-embed.su /
        // vidsrc.me redirects (they all 302 to vsembed.ru).
        registerExtractorAPI(VsEmbedExtractor())
        registerExtractorAPI(TwoEmbedCcExtractor())
        registerExtractorAPI(SmashyStreamExtractor())
        registerExtractorAPI(VidFastExtractor())
        registerExtractorAPI(VidLinkExtractor())
    }
}
