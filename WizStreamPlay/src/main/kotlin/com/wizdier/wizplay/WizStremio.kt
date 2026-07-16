package com.wizdier.wizplay

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * WizStremio – StreamPlay multiAPI aggregator (phisher98 pattern)
 * Unlimited addon links, catalog+stream dual via AIOStreams, torrent fallback
 */
object WizStremio {
    data class StremioStream(val title:String, val url:String, val behaviorHints:JSONObject? = null)

    // Default addons like StreamPlay uses
    val defaultAddons = listOf(
        "https://torrentio.strem.fun/limit=4",
        "https://torrentsdb.com/eyJsaW1pdCI6IjMiLCJkZWJyaWRvcHRpb25zIjpbIm5vZG93bmxvYWRsaW5rcyJdfQ==",
        "https://comet.elfhosted.com",
        "https://mediafusion.elfhosted.com"
    )

    suspend fun fetchStreams(imdbId:String?, type:String, season:Int?, episode:Int?, addons:List<String>, cb:(ExtractorLink)->Unit): Boolean {
        if(imdbId.isNullOrBlank() || addons.isEmpty()) return false
        val sId = when{
            type=="movie" -> imdbId
            season!=null && episode!=null -> "$imdbId:$season:$episode"
            else -> imdbId
        }
        var found=false
        val results = WizCore.parallelMapNotNull(addons, concurrency=4){ base->
            try{
                val clean=base.trim().trimEnd('/')
                val url="$clean/stream/$type/$sId.json"
                val json=WizCore.retry{ com.lagradost.cloudstream3.app.get(url, timeout=10_000).text } ?: return@parallelMapNotNull null
                val obj=JSONObject(json)
                val streams=obj.optJSONArray("streams") ?: return@parallelMapNotNull null
                (0 until streams.length()).mapNotNull{ i->
                    val st=streams.optJSONObject(i) ?: return@mapNotNull null
                    val u=st.optString("url").ifBlank{return@mapNotNull null}
                    val t=st.optString("title","Stremio")
                    StremioStream(t, u, st.optJSONObject("behaviorHints"))
                }
            } catch(_:Exception){ null }
        }.flatten()

        results.forEach{ s->
            cb(newExtractorLink("Stremio ${s.title.take(30)}", s.title, s.url){ quality=getQualityFromName(s.title) })
            found=true
        }
        return found
    }

    // AIOStreams wrapper helper – like StreamPlay docs
    fun buildAIOStreamsUrl(stremioAddons:List<String>): String {
        // AIOStreams expects base64 or encoded addon list – simplified: return mediafusion which wraps many
        // Real implementation would encode addon URLs into AIOStreams config
        return "https://mediafusion.elfhosted.com"
    }
}
