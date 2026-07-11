package com.wizdier.streamflix
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
@CloudstreamPlugin
class StreamFlixPlugin : BasePlugin() { override fun load() { registerMainAPI(StreamFlixProvider()) } }
