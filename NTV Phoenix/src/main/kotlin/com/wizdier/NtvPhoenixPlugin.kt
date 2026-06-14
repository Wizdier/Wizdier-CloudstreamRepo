package com.wizdier

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NtvPhoenixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NtvProvider("phoenix", "NTV - Phoenix"))
    }
}
