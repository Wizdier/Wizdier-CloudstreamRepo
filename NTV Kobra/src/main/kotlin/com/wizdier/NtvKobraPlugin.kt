package com.wizdier

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NtvKobraPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NtvProvider("kobra", "NTV - Kobra"))
    }
}
