package com.wizdier

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

internal object NTVConcurrent {

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

    class TtlCache<K, V>(private val ttlMs: Long = 3 * 60 * 1000L) {
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

        fun clear() = store.clear()
    }
}
