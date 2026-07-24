package com.wizdier

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FmFtpPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FmFtpProvider())
    }
}
