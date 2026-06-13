package com.wizdier

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
// FTPBDConcurrent — concurrency, retry, and caching infrastructure for the
// FTPBD provider.
//
// Mirrors CTGConcurrent: parallel batch-fetch, exponential-backoff retry,
// and a self-evicting TTL cache. See that file for full rationale.
// ─────────────────────────────────────────────────────────────────────────────
internal object FTPBDConcurrent {

    suspend fun <T, R> parallelMapNotNull(
        items: List<T>,
        fetch: suspend (T) -> R?,
    ): List<R> = coroutineScope {
        items
            .map { item -> async { runCatching { fetch(item) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }

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
