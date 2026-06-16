package com.wizdier

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NTVStreamPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(NTVStreamSettings.PREF_FILE, Context.MODE_PRIVATE)

        when (NTVStreamSettings.selectedServer(prefs)) {
            NTVStreamSettings.SERVER_KOBRA -> registerMainAPI(NTVStreamKobra())
            NTVStreamSettings.SERVER_RAPTOR -> registerMainAPI(NTVStreamRaptor())
            NTVStreamSettings.SERVER_FALCON -> registerMainAPI(NTVStreamFalcon())
            NTVStreamSettings.SERVER_PHOENIX -> registerMainAPI(NTVStreamPhoenix())
            NTVStreamSettings.SERVER_VIPER -> registerMainAPI(NTVStreamViper())
            else -> registerAllServers()
        }

        openSettings = { ctx ->
            NTVStreamSettings.show(ctx, prefs)
        }
    }

    private fun registerAllServers() {
        registerMainAPI(NTVStreamKobra())
        registerMainAPI(NTVStreamRaptor())
        registerMainAPI(NTVStreamFalcon())
        registerMainAPI(NTVStreamPhoenix())
        registerMainAPI(NTVStreamViper())
    }
}
