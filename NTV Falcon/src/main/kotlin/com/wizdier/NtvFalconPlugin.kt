package com.wizdier

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NtvFalconPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NtvProvider("falcon", "NTV - Falcon"))
    }
}
