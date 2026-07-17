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
 */
@CloudstreamPlugin
class WizstreamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(WizstreamProvider())
        registerMainAPI(WizstreamAnimeProvider())
    }
}
