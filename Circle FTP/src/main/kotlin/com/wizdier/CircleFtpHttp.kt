package com.wizdier

import com.lagradost.cloudstream3.app
import com.lagradost.api.Log
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// CircleFtpHttp — resilience layer for the Circle FTP provider.
//
// Circle FTP exposes two API mirrors (the public hostname and a raw IP).
// This helper centralises the try-primary-then-fallback logic AND adds:
//   • explicit per-request timeouts (the old code could hang indefinitely
//     on a dead mirror)
//   • bounded retries with exponential back-off
//   • a single, consistent cache key + cacheTime so Cloudstream's built-in
//     HTTP cache actually fires
//
// Every method returns null on total failure instead of throwing, so the
// provider's parsing code never has to deal with network exceptions.
// ─────────────────────────────────────────────────────────────────────────────
internal object CircleFtpHttp {

    private const val TAG = "CircleFtpHttp"
    private const val DEFAULT_TIMEOUT = 12_000  // ms
    private const val DEFAULT_CACHE = 60        // s

    /**
     * Fetch [path] from [primary], falling back to [fallback] on any error.
     * Retries each mirror [retries] times with back-off. Returns the response
     * body text, or null if both mirrors are exhausted.
     */
    suspend fun fetchWithFallback(
        primary: String,
        fallback: String,
        retries: Int = 2,
        cacheTime: Int = DEFAULT_CACHE,
    ): String? {
        // Try primary first, then fallback. Each gets `retries` attempts.
        for (mirror in listOf(primary, fallback)) {
            repeat(retries) { attempt ->
                val result = runCatching {
                    app.get(mirror, verify = false, cacheTime = cacheTime, timeout = DEFAULT_TIMEOUT)
                }.getOrNull()
                if (result != null && result.code in 200..299 && result.text.isNotBlank()) {
                    return result.text
                }
                if (attempt < retries - 1) {
                    delay((300L * (attempt + 1)).coerceAtMost(1_200))
                }
            }
        }
        Log.w(TAG, "All mirrors failed for $primary")
        return null
    }

    /**
     * Convenience wrapper for a single URL with retry + timeout (no fallback).
     */
    suspend fun fetchText(
        url: String,
        retries: Int = 2,
        cacheTime: Int = DEFAULT_CACHE,
    ): String? {
        repeat(retries) { attempt ->
            val result = runCatching {
                app.get(url, verify = false, cacheTime = cacheTime, timeout = DEFAULT_TIMEOUT)
            }.getOrNull()
            if (result != null && result.code in 200..299 && result.text.isNotBlank()) {
                return result.text
            }
            if (attempt < retries - 1) {
                delay((300L * (attempt + 1)).coerceAtMost(1_200))
            }
        }
        return null
    }
}
