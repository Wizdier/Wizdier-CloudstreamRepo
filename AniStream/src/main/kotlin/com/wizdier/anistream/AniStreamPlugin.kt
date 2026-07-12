package com.wizdier.anistream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AniStreamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AniStreamProvider())
        // Custom extractors required for Animepahe sources.
        registerExtractorAPI(Kwik())
        registerExtractorAPI(Pahe())
    }
}
