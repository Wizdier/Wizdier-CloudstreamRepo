package com.wizdier.movies67
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
@CloudstreamPlugin
class Movies67Plugin : BasePlugin() { override fun load() { registerMainAPI(Movies67Provider()) } }
