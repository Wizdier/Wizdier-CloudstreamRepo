package com.wizdier.wizplay

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object WizCore {
    const val PARALLELISM = 8
    const val TIMEOUT = 12_000L

    suspend fun <T,R> parallelMapNotNull(items:List<T>, concurrency:Int=PARALLELISM, fetch:suspend(T)->R?): List<R>{
        if(items.isEmpty()) return emptyList()
        val gate=Semaphore(concurrency.coerceAtLeast(1))
        return coroutineScope{
            items.map{ item->
                async{
                    try{ gate.withPermit{ fetch(item) } }
                    catch(e:CancellationException){ throw e }
                    catch(_:Throwable){ null }
                }
            }.awaitAll().filterNotNull()
        }
    }

    suspend fun <A,B> Iterable<A>.safeAmap(concurrency:Int=PARALLELISM, f:suspend(A)->B?): List<B> = parallelMapNotNull(this.toList(), concurrency, f)

    suspend fun <T> retry(maxAttempts:Int=3, initialDelayMs:Long=400, block:suspend()->T): T?{
        var backoff=initialDelayMs
        repeat(maxAttempts){ attempt->
            try{ return block() }
            catch(ce:CancellationException){ throw ce }
            catch(_:Throwable){
                if(attempt==maxAttempts-1) return null
                val jitter=(backoff*0.25*(Math.random()-0.5)*2).toLong()
                delay((backoff+jitter).coerceAtLeast(0))
                backoff=(backoff*2).coerceAtMost(8_000)
            }
        }
        return null
    }

    fun jitteredBackoff(attempt:Int, baseMs:Long=300, capMs:Long=2_000L): Long{
        val base=(baseMs shl attempt).coerceAtMost(capMs)
        val jitter=(base*0.25*(Math.random()-0.5)*2).toLong()
        return (base+jitter).coerceAtLeast(0)
    }

    class TtlCache<K,V>(private val ttlMs:Long=10*60*1000L, private val maxEntries:Int=256){
        private data class Entry<V>(val v:V, val ts:Long)
        private val store=ConcurrentHashMap<K,Entry<V>>()
        private val sweep=AtomicLong(0)
        fun get(key:K):V?{
            val e=store[key]?:return null
            if(System.currentTimeMillis()-e.ts>ttlMs){ store.remove(key,e); return null }
            return e.v
        }
        fun put(key:K, value:V){
            store[key]=Entry(value, System.currentTimeMillis())
            maybeSweep()
        }
        suspend fun getOrPutSuspend(key:K, compute:suspend()->V?): V?{
            get(key)?.let{return it}
            val v=compute()?:return null
            put(key,v); return v
        }
        fun clear()=store.clear()
        fun size()=store.size
        private fun maybeSweep(){
            if(sweep.incrementAndGet()%32!=0L) return
            val now=System.currentTimeMillis()
            store.entries.removeAll{ (_,e)-> now-e.ts>ttlMs }
            if(store.size<=maxEntries) return
            store.entries.sortedBy{it.value.ts}.take(store.size-maxEntries).forEach{(k,_)->store.remove(k)}
        }
    }

    fun String.encodeUrl(): String = URLEncoder.encode(this,"UTF-8")
    fun String.toAbs(base:String): String = if(startsWith("http")) this else base.trimEnd('/') + "/" + trimStart('/')

    fun buildSourceName(label:String?, url:String, idx:Int): String{
        if(!label.isNullOrBlank()) return label
        return runCatching{ java.net.URI(url).host?.substringBefore('.')?.replaceFirstChar{it.uppercase()} }.getOrNull() ?: "Source $idx"
    }

    // Simplified title from CSX SpecOption
    private val SIZE_REGEX = """(\d+(?:\.\d+)?\s?(?:MB|GB))""".toRegex(RegexOption.IGNORE_CASE)
    fun getSimplifiedTitle(title:String): String{
        val size=SIZE_REGEX.find(title)?.value?.uppercase()?.let{"$it 💾"}
        val qual=when{
            title.contains("4K",true)||title.contains("2160p",true)->"4K 💿"
            title.contains("1080p",true)->"1080p 🌟"
            title.contains("720p",true)->"720p ✨"
            title.contains("WEB-DL",true)->"WEB-DL ☁️"
            title.contains("WEBRip",true)->"WEBRip 🌐"
            title.contains("HDR",true)->"HDR 🔆"
            else->""
        }
        val res=listOfNotNull(qual.takeIf{it.isNotBlank()}, size?.takeIf{it.isNotBlank()}).joinToString(" | ")
        return if(res.isBlank()) "" else "\n$res"
    }

    // RSC blob parsing (CSX) for Next.js sites like CTGMovies
    fun unescapeNextChunk(raw:String):String{
        val sb=StringBuilder(raw.length); var i=0
        while(i<raw.length){
            val c=raw[i]
            if(c=='\\' && i+1<raw.length){
                when(raw[i+1]){
                    '"'->{sb.append('"'); i+=2}
                    '\\'->{sb.append('\\'); i+=2}
                    'n'->{sb.append('\n'); i+=2}
                    't'->{sb.append('\t'); i+=2}
                    'u'->{ if(i+5<raw.length){ sb.append(raw.substring(i+2,i+6).toInt(16).toChar()); i+=6 } else {sb.append(c); i++} }
                    else->{sb.append(raw[i+1]); i+=2}
                }
            } else {sb.append(c); i++}
        }
        return sb.toString()
    }
    fun buildRscBlob(html:String):String{
        val pref="self.__next_f.push([1,\""; val suff="\"])"
        val blob=StringBuilder(); var from=0
        while(true){
            val s=html.indexOf(pref,from); if(s==-1) break
            val cs=s+pref.length; val e=html.indexOf(suff,cs); if(e==-1) break
            blob.append(unescapeNextChunk(html.substring(cs,e))); from=e+suff.length
        }
        return blob.toString()
    }
    fun extractLinksArrays(blob:String):List<String>{
        val key="\"links\":["; val res=mutableListOf<String>(); var from=0
        while(true){
            val ki=blob.indexOf(key,from); if(ki==-1) break
            val st=ki+key.length-1; var depth=0; var i=st; var en=-1
            while(i<blob.length){ when(blob[i]){ '['->depth++; ']'->{depth--; if(depth==0){en=i; break}} }; i++ }
            if(en==-1) break; res.add(blob.substring(st,en+1)); from=en+1
        }
        return res
    }
}
