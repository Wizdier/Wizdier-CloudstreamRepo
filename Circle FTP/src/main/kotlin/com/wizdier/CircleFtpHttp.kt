package com.wizdier

import com.lagradost.cloudstream3.app
import com.lagradost.api.Log
import kotlinx.coroutines.delay

internal object CircleFtpHttp {
    private const val TAG = "CircleFtpHttp"
    private const val DEFAULT_TIMEOUT = 12_000L
    private const val DEFAULT_CACHE = 60
    private const val MAX_BACKOFF_MS = 2_000L

    suspend fun fetchWithFallback(primary:String, fallback:String, retries:Int=2, cacheTime:Int=DEFAULT_CACHE): String?{
        for(mirror in listOf(primary, fallback)){
            repeat(retries){ attempt->
                val result=runCatching{ app.get(mirror, verify=false, cacheTime=cacheTime, timeout=DEFAULT_TIMEOUT) }.getOrNull()
                if(result!=null && result.code in 200..299 && result.text.isNotBlank()) return result.text
                if(attempt<retries-1) delay(jitteredBackoff(attempt))
            }
        }
        Log.w(TAG, "All mirrors failed for $primary")
        return null
    }

    suspend fun fetchText(url:String, retries:Int=2, cacheTime:Int=DEFAULT_CACHE): String?{
        repeat(retries){ attempt->
            val result=runCatching{ app.get(url, verify=false, cacheTime=cacheTime, timeout=DEFAULT_TIMEOUT) }.getOrNull()
            if(result!=null && result.code in 200..299 && result.text.isNotBlank()) return result.text
            if(attempt<retries-1) delay(jitteredBackoff(attempt))
        }
        return null
    }

    private fun jitteredBackoff(attempt:Int): Long{
        val base=(300L shl attempt).coerceAtMost(MAX_BACKOFF_MS)
        val jitter=(base*0.25*(Math.random()-0.5)*2).toLong()
        return (base+jitter).coerceAtLeast(0)
    }
}
