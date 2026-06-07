package com.wizdier

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnikotoTVPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnikotoTV())
    }
}