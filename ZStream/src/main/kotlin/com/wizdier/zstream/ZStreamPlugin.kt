package com.wizdier.zstream
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
@CloudstreamPlugin
class ZStreamPlugin : BasePlugin() { override fun load() { registerMainAPI(ZStreamProvider()) } }
