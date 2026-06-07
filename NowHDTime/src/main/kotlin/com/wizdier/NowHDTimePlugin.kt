package com.wizdier

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NowHDTimePlugin : Plugin() {
    override fun load(context: Context) {
        // Main catalog provider
        registerMainAPI(NowHDTimeProvider())

        // Page-embedded extractor chain
        registerExtractorAPI(NowHDTimeStreamExtractor())
        registerExtractorAPI(NowHDTimeDroploadExtractor())
        registerExtractorAPI(NowHDTimeVidhideExtractor())
        registerExtractorAPI(NowHDTimeFilelionsExtractor())
        registerExtractorAPI(NowHDTimeStreamwishExtractor())
        registerExtractorAPI(NowHDTimeDoodExtractor())
        registerExtractorAPI(NowHDTimeMixdropExtractor())
        registerExtractorAPI(NowHDTimeVoeExtractor())
        registerExtractorAPI(NowHDTimeUpstreamExtractor())
    }
}
