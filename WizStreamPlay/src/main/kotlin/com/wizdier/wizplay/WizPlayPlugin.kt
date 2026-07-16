package com.wizdier.wizplay

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class WizPlayPlugin : BasePlugin() {
    override fun load() {
        // Two extensions in one like StreamPlay (StremioX + StremioC)
        // Here: TMDB + AniList dual providers
        registerMainAPI(WizTmdbProvider())
        registerMainAPI(WizAnilistProvider())
        // With WizCore robust patterns, ProviderRegistry vid[x] aggregation, and StreamPlay multiAPI stremio
    }
}
