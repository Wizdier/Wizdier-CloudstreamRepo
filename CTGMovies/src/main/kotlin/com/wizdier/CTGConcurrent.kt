package com.wizdier

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
// CTGConcurrent — concurrency, retry, and caching infrastructure for the
// CTGMovies provider.
//
// Keeping these primitives in one place lets the provider stay focused on
// parsing logic while getting battle-tested parallelism, exponential-backoff
// retries, and a self-evicting TTL cache for free.
// ─────────────────────────────────────────────────────────────────────────────
internal object CTGConcurrent {

    /**
     * Run [fetch] for every item in [items] concurrently and return only the
     * non-null results, preserving original order. Each fetch is wrapped in
     * runCatching so a single failure (timeout, parse error) can never drop
     * the rest of the batch.
     *
     * This is the single biggest speed-up: N sequential network round-trips
     * collapse into ~1 round-trip of wall-clock time.
     */
    suspend fun <T, R> parallelMapNotNull(
        items: List<T>,
        fetch: suspend (T) -> R?,
    ): List<R> = coroutineScope {
        items
            .map { item -> async { runCatching { fetch(item) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }

    /**
     * Execute [block] up to [maxAttempts] times with exponential back-off.
     * Returns the first success or null if every attempt failed. A momentary
     * 5xx or socket timeout no longer surfaces as an empty result.
     */
    suspend fun <T> retry(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 400,
        block: suspend () -> T,
    ): T? {
        var backoff = initialDelayMs
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (_: Throwable) {
                if (attempt < maxAttempts - 1) {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(4_000)
                }
            }
        }
        return null
    }

    /**
     * Thread-safe TTL cache. Entries auto-expire after [ttlMs], so stale
     * metadata is refreshed automatically while repeat loads within the
     * window return instantly with zero network I/O.
     */
    class TtlCache<K, V>(private val ttlMs: Long = 10 * 60 * 1000L) {
        private val store = ConcurrentHashMap<K, Pair<V, Long>>()

        fun get(key: K): V? {
            val (value, ts) = store[key] ?: return null
            if (System.currentTimeMillis() - ts > ttlMs) {
                store.remove(key)
                return null
            }
            return value
        }

        fun put(key: K, value: V) {
            store[key] = value to System.currentTimeMillis()
        }

        fun getOrPut(key: K, compute: () -> V): V =
            get(key) ?: compute().also { put(key, it) }

        fun clear() = store.clear()
    }
}
