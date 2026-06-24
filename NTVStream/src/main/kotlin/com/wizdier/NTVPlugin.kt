package com.wizdier

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NTVPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(NTVProvider.PREF_FILE, Context.MODE_PRIVATE)
        registerMainAPI(NTVProvider(prefs))
        openSettings = { ctx -> NTVSettingsUI.show(ctx, prefs) }
    }
}
