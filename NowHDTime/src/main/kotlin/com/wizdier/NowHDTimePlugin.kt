package com.wizdier

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NowHDTimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NowHDTimeProvider())

        // Extractors — more specific first
        registerExtractorAPI(NowHDTimeStreamExtractor())
        registerExtractorAPI(NontongoExtractor())
        registerExtractorAPI(DroploadExtractor())
        registerExtractorAPI(VidHideExtractor())
        registerExtractorAPI(FileLionsExtractor())
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(DoodExtractor())
        registerExtractorAPI(MixdropExtractor())
        registerExtractorAPI(VoeExtractor())
        registerExtractorAPI(UpstreamExtractor())
    }
}
