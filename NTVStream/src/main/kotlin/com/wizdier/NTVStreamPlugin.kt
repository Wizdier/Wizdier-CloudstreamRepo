package com.wizdier

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NTVStreamPlugin : Plugin() {
    override fun load() {
        registerMainAPI(NTVStreamKobra())
        registerMainAPI(NTVStreamRaptor())
        registerMainAPI(NTVStreamFalcon())
        registerMainAPI(NTVStreamPhoenix())
        registerMainAPI(NTVStreamViper())
    }
}
