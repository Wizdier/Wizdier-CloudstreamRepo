package com.wizdier

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// Ultra Robust – unified concurrent primitives (CSX-inspired)
internal object CineplexConcurrent {
    private const val DEFAULT_PARALLELISM = 8
    suspend fun <T,R> parallelMapNotNull(items:List<T>, concurrency:Int=DEFAULT_PARALLELISM, fetch:suspend(T)->R?): List<R>{
        if(items.isEmpty()) return emptyList()
        val gate=Semaphore(concurrency.coerceAtLeast(1))
        return coroutineScope{
            items.map{ item-> async{ try{ gate.withPermit{ fetch(item) } } catch(e:CancellationException){ throw e } catch(_:Throwable){ null } } }.awaitAll().filterNotNull()
        }
    }
    suspend fun <T> retry(maxAttempts:Int=3, initialDelayMs:Long=400, block:suspend()->T): T?{
        var backoff=initialDelayMs
        repeat(maxAttempts){ attempt->
            try{ return block() } catch(ce:CancellationException){ throw ce } catch(_:Throwable){
                if(attempt==maxAttempts-1) return null
                val jitter=(backoff*0.25*(Math.random()-0.5)*2).toLong()
                delay((backoff+jitter).coerceAtLeast(0)); backoff=(backoff*2).coerceAtMost(8000)
            }
        }
        return null
    }
    class TtlCache<K,V>(private val ttlMs:Long=10*60*1000L, private val maxEntries:Int=256){
        private data class E<V>(val v:V, val ts:Long)
        private val store=ConcurrentHashMap<K,E<V>>()
        private val sweep=AtomicLong(0)
        fun get(key:K):V?{ val e=store[key]?:return null; if(System.currentTimeMillis()-e.ts>ttlMs){ store.remove(key,e); return null }; return e.v }
        fun put(key:K,value:V){ store[key]=E(value, System.currentTimeMillis()); maybeSweep() }
        fun clear()=store.clear()
        fun size()=store.size
        private fun maybeSweep(){
            val c=sweep.incrementAndGet(); if(c%32!=0L) return
            val now=System.currentTimeMillis()
            store.entries.removeAll{ (_,e)-> now-e.ts>ttlMs }
            if(store.size<=maxEntries) return
            store.entries.sortedBy{it.value.ts}.take(store.size-maxEntries).forEach{(k,_)->store.remove(k)}
        }
    }
}
