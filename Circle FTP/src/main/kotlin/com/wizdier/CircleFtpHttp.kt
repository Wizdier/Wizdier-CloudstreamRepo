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
//   • bounded retries with jittered exponential back-off (pure 2× back-off
//     causes thundering-herd retries after a server blip; ±25% jitter
//     spreads them out)
//   • a single, consistent cache key + cacheTime so Cloudstream's built-in
//     HTTP cache actually fires
//   • coroutine-safe cancellation: re-throws CancellationException so the
//     parent scope can still cancel an in-flight fetch
//
// Every method returns null on total failure instead of throwing, so the
// provider's parsing code never has to deal with network exceptions.
// ─────────────────────────────────────────────────────────────────────────────
internal object CircleFtpHttp {

    private const val TAG = "CircleFtpHttp"
    private const val DEFAULT_TIMEOUT = 12_000L  // ms (Long — app.get() expects Long)
    private const val DEFAULT_CACHE = 60         // s
    private const val MAX_BACKOFF_MS = 2_000L

    /**
     * Fetch [path] from [primary], falling back to [fallback] on any error.
     * Retries each mirror [retries] times with jittered back-off. Returns the
     * response body text, or null if both mirrors are exhausted.
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
                    delay(jitteredBackoff(attempt))
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
                delay(jitteredBackoff(attempt))
            }
        }
        return null
    }

    /**
     * Jittered exponential back-off. Base delay = 300ms × 2^attempt, capped
     * at [MAX_BACKOFF_MS], with ±25% random jitter so concurrent retries
     * don't all land on the same tick.
     */
    private fun jitteredBackoff(attempt: Int): Long {
        val base = (300L shl attempt).coerceAtMost(MAX_BACKOFF_MS)
        val jitter = (base * 0.25 * (Math.random() - 0.5) * 2).toLong()
        return (base + jitter).coerceAtLeast(0)
    }
}
