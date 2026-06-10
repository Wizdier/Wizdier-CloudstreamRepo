package com.wizdier

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FTPBDPlugin : Plugin() {
    override fun load() {
        registerMainAPI(FTPBD())
    }
}
