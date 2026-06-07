package com.wizdier

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NowHDTimePlugin : Plugin() {
    override fun load(context: Context) {
        // ── Main catalogue provider ──
        registerMainAPI(NowHDTimeProvider())

        // ── Extractors — order matters: more specific first ──
        registerExtractorAPI(NowHDTimeStreamExtractor())   // nowhdtime.com.bd internal player
        registerExtractorAPI(NontongoExtractor())           // nontongo.win  (anime embed host)
        registerExtractorAPI(DroploadExtractor())           // dropload.io
        registerExtractorAPI(VidHideExtractor())            // vidhide.com / vidhide.to
        registerExtractorAPI(FileLionsExtractor())          // filelions.to / filelions.live
        registerExtractorAPI(StreamWishExtractor())         // streamwish.to / streamwish.com
        registerExtractorAPI(DoodExtractor())               // dood.to and all mirrors
        registerExtractorAPI(MixdropExtractor())            // mixdrop.co and mirrors
        registerExtractorAPI(VoeExtractor())                // voe.sx
        registerExtractorAPI(UpstreamExtractor())           // upstream.to
    }
}
