package com.wizdier

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NTVStreamPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(NTVStreamSettings.PREF_FILE, Context.MODE_PRIVATE)
        val selectedServers = NTVStreamSettings.selectedServers(prefs)

        if (NTVStreamSettings.SERVER_KOBRA in selectedServers) registerMainAPI(NTVStreamKobra())
        if (NTVStreamSettings.SERVER_RAPTOR in selectedServers) registerMainAPI(NTVStreamRaptor())
        if (NTVStreamSettings.SERVER_FALCON in selectedServers) registerMainAPI(NTVStreamFalcon())
        if (NTVStreamSettings.SERVER_PHOENIX in selectedServers) registerMainAPI(NTVStreamPhoenix())
        if (NTVStreamSettings.SERVER_VIPER in selectedServers) registerMainAPI(NTVStreamViper())

        openSettings = { ctx ->
            NTVStreamSettings.show(ctx, prefs)
        }
    }
}
