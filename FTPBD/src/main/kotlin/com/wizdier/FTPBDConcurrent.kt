package com.wizdier

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// ─────────────────────────────────────────────────────────────────────────────
// FTPBDConcurrent — concurrency, retry, and caching infrastructure for the
// FTPBD provider.
//
// Mirrors CTGConcurrent / CineplexConcurrent: parallel batch-fetch (bounded by
// a Semaphore so a big batch can't exhaust sockets), jittered exponential-
// backoff retry (re-throws CancellationException), and a self-evicting TTL +
// LRU cache. See CTGConcurrent.kt for the full rationale.
// ─────────────────────────────────────────────────────────────────────────────
internal object FTPBDConcurrent {

    /** Default cap on concurrent in-flight work inside `parallelMapNotNull`. */
    private const val DEFAULT_PARALLELISM = 8

    /**
     * Run [fetch] for every item in [items] concurrently and return only the
     * non-null results, preserving original order. Each fetch is wrapped in
     * runCatching so a single failure (timeout, parse error) can never drop
     * the rest of the batch. In-flight concurrency is bounded by [concurrency]
     * to avoid socket exhaustion and API rate-limit blowups.
     */
    suspend fun <T, R> parallelMapNotNull(
        items: List<T>,
        fetch: suspend (T) -> R?,
        concurrency: Int = DEFAULT_PARALLELISM,
    ): List<R> {
        if (items.isEmpty()) return emptyList()
        val limited = concurrency.coerceAtLeast(1)
        val gate = Semaphore(limited)
        return coroutineScope {
            items.map { item ->
                async {
                    runCatching { gate.withPermit { fetch(item) } }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Execute [block] up to [maxAttempts] times with jittered exponential
     * back-off. Returns the first success or null if every attempt failed.
     *
     * Re-throws `CancellationException` so coroutine cancellation still
     * propagates correctly (the previous `catch (_: Throwable)` ate it).
     */
    suspend fun <T> retry(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 400,
        block: suspend () -> T,
    ): T? {
        require(maxAttempts > 0) { "maxAttempts must be > 0" }
        var backoff = initialDelayMs
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Throwable) {
                if (attempt == maxAttempts - 1) return null
                val jitter = (backoff * 0.25 * (Math.random() - 0.5) * 2).toLong()
                delay((backoff + jitter).coerceAtLeast(0))
                backoff = (backoff * 2).coerceAtMost(8_000)
            }
        }
        return null
    }

    /**
     * Thread-safe TTL + LRU cache. Entries auto-expire after [ttlMs] AND are
     * swept on access, so stale metadata refreshes automatically while repeat
     * loads within the window return instantly with zero network I/O.
     *
     * A bounded [maxEntries] (default 256) keeps memory in check: when the
     * cache grows past the cap, the oldest entries (by insertion timestamp)
     * are evicted lazily on the next put.
     */
    class TtlCache<K, V>(
        private val ttlMs: Long = 10 * 60 * 1000L,
        private val maxEntries: Int = 256,
    ) {
        private val store = ConcurrentHashMap<K, Entry<V>>()
        private val sweepCounter = AtomicLong(0)

        private class Entry<V>(val value: V, val ts: Long)

        fun get(key: K): V? {
            val e = store[key] ?: return null
            if (System.currentTimeMillis() - e.ts > ttlMs) {
                store.remove(key, e)
                return null
            }
            return e.value
        }

        fun put(key: K, value: V) {
            store[key] = Entry(value, System.currentTimeMillis())
            maybeSweep()
        }

        fun getOrPut(key: K, compute: () -> V): V {
            get(key)?.let { return it }
            val v = compute()
            put(key, v)
            return v
        }

        suspend fun getOrPutSuspend(
            key: K,
            producerTimeoutMs: Long = 5_000,
            compute: suspend () -> V?,
        ): V? {
            get(key)?.let { return it }
            val v = compute() ?: return null
            put(key, v)
            return v
        }

        fun clear() = store.clear()

        fun size(): Int = store.size

        private fun maybeSweep() {
            val count = sweepCounter.incrementAndGet()
            if (count % 32 != 0L) return
            val now = System.currentTimeMillis()
            store.entries.removeAll { (_, e) -> now - e.ts > ttlMs }
            if (store.size <= maxEntries) return
            store.entries
                .sortedBy { it.value.ts }
                .take(store.size - maxEntries)
                .forEach { (k, _) -> store.remove(k) }
        }
    }

    /** Run [block] only if the surrounding coroutine is still active. */
    suspend fun <T> ifActive(scope: CoroutineScope, block: suspend () -> T): T? {
        if (!scope.isActive) return null
        return runCatching { block() }.getOrNull()
    }
}
