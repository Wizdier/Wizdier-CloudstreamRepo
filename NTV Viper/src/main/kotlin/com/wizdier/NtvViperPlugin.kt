package com.wizdier

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NtvViperPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NtvProvider("viper", "NTV - Viper"))
    }
}
