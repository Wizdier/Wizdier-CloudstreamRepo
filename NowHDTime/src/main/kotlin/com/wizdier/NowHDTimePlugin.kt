package com.wizdier

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NowHDTimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NowHDTimeProvider())
    }
}
