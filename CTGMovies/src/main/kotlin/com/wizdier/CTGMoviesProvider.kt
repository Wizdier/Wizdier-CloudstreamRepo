package com.wizdier

import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object CTGCore {
    suspend fun <T,R> parallelMapNotNull(items:List<T>, concurrency:Int=8, fetch:suspend(T)->R?): List<R>{
        if(items.isEmpty()) return emptyList()
        val gate=Semaphore(concurrency.coerceAtLeast(1))
        return coroutineScope{ items.map{ item-> async{ try{ gate.withPermit{ fetch(item) } } catch(e:Exception){ null } } }.awaitAll().filterNotNull() }
    }
    suspend fun <T> retry(max:Int=3, init:Long=400, block:suspend()->T): T?{
        var b=init
        repeat(max){ a->
            try{ return block() } catch(ce:kotlinx.coroutines.CancellationException){ throw ce } catch(_:Throwable){
                if(a==max-1) return null
                val jitter=(b*0.25*(Math.random()-0.5)*2).toLong()
                delay((b+jitter).coerceAtLeast(0)); b=(b*2).coerceAtMost(8000)
            }
        }
        return null
    }
    class TtlCache<K,V>(private val ttlMs:Long=10*60*1000L, private val max:Int=256){
        private data class E<V>(val v:V, val ts:Long)
        private val map=ConcurrentHashMap<K,E<V>>()
        private val c=AtomicLong(0)
        fun get(k:K):V?{ val e=map[k]?:return null; if(System.currentTimeMillis()-e.ts>ttlMs){map.remove(k,e); return null}; return e.v }
        fun put(k:K,v:V){ map[k]=E(v,System.currentTimeMillis()); if(c.incrementAndGet()%32==0L){ val now=System.currentTimeMillis(); map.entries.removeAll{(_,e)-> now-e.ts>ttlMs }; if(map.size>max){ map.entries.sortedBy{it.value.ts}.take(map.size-max).forEach{(kk,_)->map.remove(kk)} } } }
        suspend fun getOrPutSuspend(k:K, compute:suspend()->V?): V?{ get(k)?.let{return it}; val v=compute()?:return null; put(k,v); return v }
    }
    fun unescapeNextChunk(raw:String):String{
        val sb=StringBuilder(raw.length); var i=0
        while(i<raw.length){ val c=raw[i]; if(c=='\\' && i+1<raw.length){ when(raw[i+1]){ '"'->{sb.append('"'); i+=2}; '\\'->{sb.append('\\'); i+=2}; 'n'->{sb.append('\n'); i+=2}; 't'->{sb.append('\t'); i+=2}; 'u'->{ if(i+5<raw.length){ sb.append(raw.substring(i+2,i+6).toInt(16).toChar()); i+=6 } else {sb.append(c); i++} }; else->{sb.append(raw[i+1]); i+=2} } } else {sb.append(c); i++} }
        return sb.toString()
    }
    fun buildRscBlob(html:String):String{
        val pref="self.__next_f.push([1,\""; val suff="\"])"
        val blob=StringBuilder(); var from=0
        while(true){ val s=html.indexOf(pref,from); if(s==-1) break; val cs=s+pref.length; val e=html.indexOf(suff,cs); if(e==-1) break; blob.append(unescapeNextChunk(html.substring(cs,e))); from=e+suff.length }
        return blob.toString()
    }
    fun extractLinksArrays(blob:String):List<String>{
        val key="\"links\":["; val res=mutableListOf<String>(); var from=0
        while(true){ val ki=blob.indexOf(key,from); if(ki==-1) break; val st=ki+key.length-1; var depth=0; var i=st; var en=-1; while(i<blob.length){ when(blob[i]){ '['->depth++; ']'->{depth--; if(depth==0){en=i; break}} }; i++ }; if(en==-1) break; res.add(blob.substring(st,en+1)); from=en+1 }
        return res
    }
    data class CtgLink(val quality:String, val url:String, val hlsUrl:String?, val type:String, val source:String)
    fun parseCtgLinks(html:String):List<CtgLink>{
        val blob=buildRscBlob(html); val arrJsons=extractLinksArrays(blob); if(arrJsons.isEmpty()) return emptyList()
        val out=mutableListOf<CtgLink>()
        for(j in arrJsons){ val arr=runCatching{JSONArray(j)}.getOrNull()?:continue; for(idx in 0 until arr.length()){ val obj=arr.optJSONObject(idx)?:continue; val url=obj.optString("url").takeIf{it.isNotBlank()}?:continue; out.add(CtgLink(obj.optString("quality"), url, obj.optString("hls_url").takeIf{it.isNotBlank()&&it!="null"}, obj.optString("type"), obj.optString("source"))) } }
        return out.distinctBy{it.url}
    }
    fun buildSourceName(label:String?, url:String, idx:Int):String{
        if(!label.isNullOrBlank()) return label
        return runCatching{ java.net.URI(url).host?.substringBefore('.')?.replaceFirstChar{it.uppercase()} }.getOrNull() ?: "Source $idx"
    }
}

internal object WizEnrichCTG {
    private const val TMDB_API="https://api.themoviedb.org/3"
    private const val TMDB_KEY="98ae14df2b8d8f8f8136499daf79f0e0"
    private const val IMG="https://image.tmdb.org/t/p"
    private val tmdbCache=CTGCore.TtlCache<String, JSONObject>()
    private val aniCache=CTGCore.TtlCache<String, JSONObject>()

    private suspend fun safeJson(url:String)=CTGCore.retry{ JSONObject(app.get(url, timeout=8000).text) }
    private fun poster(p:String?, size:String="w500")=p?.takeIf{it.isNotBlank()&&it!="null"}?.let{"$IMG/$size$it"}

    suspend fun tmdbMovie(title:String, year:Int?): JSONObject?{
        val key="movie_${title.lowercase()}_$year"
        tmdbCache.get(key)?.let{return it}
        val q=URLEncoder.encode(title,"UTF-8")
        val search=safeJson("$TMDB_API/search/movie?api_key=$TMDB_KEY&query=$q${year?.let{"&year=$it"}?:""}") ?: return null
        val first=search.optJSONArray("results")?.optJSONObject(0) ?: return null
        val id=first.optInt("id",0); if(id==0) return null
        val detail=safeJson("$TMDB_API/movie/$id?api_key=$TMDB_KEY&append_to_response=images,videos,external_ids,credits") ?: return null
        tmdbCache.put(key, detail); return detail
    }
    suspend fun tmdbTv(title:String): JSONObject?{
        val key="tv_${title.lowercase()}"
        tmdbCache.get(key)?.let{return it}
        val q=URLEncoder.encode(title,"UTF-8")
        val search=safeJson("$TMDB_API/search/tv?api_key=$TMDB_KEY&query=$q") ?: return null
        val first=search.optJSONArray("results")?.optJSONObject(0) ?: return null
        val id=first.optInt("id",0); if(id==0) return null
        val detail=safeJson("$TMDB_API/tv/$id?api_key=$TMDB_KEY&append_to_response=images,videos,external_ids,credits") ?: return null
        tmdbCache.put(key, detail); return detail
    }
    // AniList
    suspend fun anilist(title:String): JSONObject?{
        val key="ani_${title.lowercase()}"
        aniCache.get(key)?.let{return it}
        val query="""query (${'$'}search:String){ Page(page:1, perPage:5){ media(search:${'$'}search, type:ANIME){ id idMal title{ romaji english native } coverImage{ extraLarge large } bannerImage description averageScore genres } } }"""
        val vars=JSONObject().put("search", title)
        val body=JSONObject().put("query", query).put("variables", vars)
        val resText=CTGCore.retry{ app.post("https://graphql.anilist.co", requestBody=body.toString().toRequestBody("application/json".toMediaTypeOrNull()), timeout=10_000).text } ?: return null
        val media=runCatching{ JSONObject(resText).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")?.optJSONObject(0) }.getOrNull() ?: return null
        // Wrap into expected format: store as JSONObject with fields compatible
        val out=JSONObject()
        out.put("title", media.optJSONObject("title")?.optString("english") ?: media.optJSONObject("title")?.optString("romaji") ?: title)
        out.put("overview", media.optString("description").replace(Regex("<[^>]*>"),""))
        out.put("poster_path", media.optJSONObject("coverImage")?.optString("extraLarge"))
        out.put("backdrop_path", media.optString("bannerImage"))
        out.put("vote_average", media.optInt("averageScore",0)/10.0)
        out.put("genres", media.optJSONArray("genres"))
        out.put("anilistId", media.optInt("id",0))
        out.put("malId", media.optInt("idMal",0))
        out.put("isAnime", true)
        aniCache.put(key, out); return out
    }

    suspend fun enrich(title:String, year:Int?, isAnime:Boolean): JSONObject?{
        return if(isAnime) anilist(title) ?: tmdbTv(title) ?: tmdbMovie(title, year)
        else tmdbMovie(title, year) ?: tmdbTv(title) ?: anilist(title)
    }
}

class CTGMovies(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var mainUrl = "https://ctgmovies.com"
    override var name = "CTGMovies"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)

    companion object {
        const val PREF_FILE = "CTGMovies"
        const val PREF_EMAIL = "ctg_email"
        const val PREF_PASSWORD = "ctg_password"
        const val PREF_TOKEN = "ctg_token"
        const val PREF_COOKIE = "ctg_cookie"
        const val PREF_API_BASE = "ctg_api_base"
        const val DEFAULT_API_BASE = "https://cockpit.103.109.92.178.nip.io/api/v1"
        private val mainPageCache = CTGCore.TtlCache<String, List<SearchResponse>>()
    }

    private val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36", "Referer" to "$mainUrl/")

    private fun apiBase(): String = prefs?.getString(PREF_API_BASE, DEFAULT_API_BASE)?.takeIf{it.isNotBlank()} ?: DEFAULT_API_BASE

    private suspend fun apiGet(path:String, params:Map<String,Any?> = emptyMap()): String {
        val base=apiBase()
        val qs=params.entries.filter{it.value!=null}.joinToString("&"){ "${it.key}=${URLEncoder.encode(it.value.toString(),"UTF-8")}" }
        val url="$base$path${if(qs.isNotBlank()) "?$qs" else ""}"
        CTGCore.retry{ app.get(url, headers=headers, timeout=12_000).text }?.let{return it}
        // fallback remote
        val remote=CTGCore.retry{ app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json", timeout=8000).text }?.let{ runCatching{ JSONObject(it) }.getOrNull() }
        remote?.optString("ctgmovies","").takeIf{s->s.isNotBlank()}?.let{ fb->
            val fbUrl="$fb$path${if(qs.isNotBlank()) "?$qs" else ""}"
            CTGCore.retry{ app.get(fbUrl, headers=headers, timeout=12_000).text }?.let{return it}
        }
        throw Exception("apiGet failed $path")
    }

    override val mainPage = mainPageOf("movies" to "Latest Movies", "tv" to "Latest TV Shows", "anime" to "Latest Anime")

    override suspend fun getMainPage(page:Int, request:MainPageRequest): HomePageResponse {
        val cacheKey="${request.data}_$page"
        mainPageCache.get(cacheKey)?.let{ return newHomePageResponse(HomePageList(request.name, it), it.isNotEmpty()) }
        val text=CTGCore.retry{ apiGet("/${request.data}", mapOf("page" to page)) } ?: return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        val items=parseList(text, request.data)
        mainPageCache.put(cacheKey, items)
        return newHomePageResponse(HomePageList(request.name, items), items.isNotEmpty())
    }

    private fun parseList(text:String, kind:String): List<SearchResponse>{
        val json=runCatching{ JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr=json.optJSONArray("data") ?: json.optJSONArray("results") ?: JSONArray()
        return (0 until arr.length()).mapNotNull{ i-> arr.optJSONObject(i)?.let{ parseItem(it, kind) } }
    }
    private fun parseItem(obj:JSONObject, kind:String): SearchResponse?{
        val id=obj.optString("id").ifBlank{obj.optString("_id")}.ifBlank{return null}
        val title=obj.optString("title","").ifBlank{return null}
        val poster=obj.optString("poster").takeIf{it.isNotBlank()}
        val year=obj.optInt("year",0).takeIf{it!=0}
        val type=when(kind){ "tv"->TvType.TvSeries; "anime"->TvType.Anime; else->TvType.Movie }
        val url="$mainUrl/$kind/$id"
        return newMovieSearchResponse(title, url, type){ this.posterUrl=poster; this.year=year }
    }

    override suspend fun search(query:String): List<SearchResponse>{
        val q=query.trim(); if(q.isBlank()) return emptyList()
        val params=mapOf("search" to q)
        return coroutineScope{
            val m=async{ runCatching{ parseList(apiGet("/movies", params),"movies") }.getOrDefault(emptyList()) }
            val t=async{ runCatching{ parseList(apiGet("/tv", params),"tv") }.getOrDefault(emptyList()) }
            val a=async{ runCatching{ parseList(apiGet("/anime", params),"anime") }.getOrDefault(emptyList()) }
            (m.await()+t.await()+a.await()).distinctBy{it.url}
        }
    }

    override suspend fun load(url:String): LoadResponse?{
        val clean=url.substringBefore("?")
        val kind=when{ clean.contains("/anime/")->"anime"; clean.contains("/tv/")->"tv"; else->"movies" }
        val slug=clean.substringAfterLast("/")
        val obj=runCatching{ JSONObject(apiGet("/$kind/$slug")) }.getOrNull() ?: return null
        return when(kind){
            "movies"->loadMovie(url,obj)
            "anime"->loadAnime(url,obj)
            else->loadTv(url,obj)
        }
    }

    private fun isAnimeObj(obj:JSONObject)=obj.optString("category","").contains("anime",true) || obj.optString("type","").contains("anime",true)

    private suspend fun loadMovie(url:String, obj:JSONObject): LoadResponse?{
        val title=obj.optString("title","Unknown")
        val year=obj.optInt("year",0).takeIf{it!=0}
        val isAnime=isAnimeObj(obj)
        val meta=CTGCore.retry{ WizEnrichCTG.enrich(title, year, isAnime) }
        val links=extractLinks(obj)
        val data=linksToData(links, meta)
        return newMovieLoadResponse(meta?.optString("title")?.takeIf{it.isNotBlank()} ?: title, url, if(isAnime||meta?.optBoolean("isAnime")==true) TvType.AnimeMovie else TvType.Movie, data){
            posterUrl=meta?.optString("poster_path")?.let{"https://image.tmdb.org/t/p/w500$it"} ?: meta?.optString("poster") ?: obj.optString("poster").takeIf{it.isNotBlank()}
            backgroundPosterUrl=meta?.optString("backdrop_path")?.let{"https://image.tmdb.org/t/p/original$it"} ?: meta?.optString("backdrop") ?: meta?.optString("bannerImage")
            plot=meta?.optString("overview") ?: meta?.optString("description") ?: obj.optString("description")
            this.year=meta?.optInt("year") ?: year
            meta?.optDouble("vote_average",0.0)?.takeIf{it>0}?.let{ score=Score.from10(it) }
            meta?.optString("imdb_id")?.takeIf{it.startsWith("tt")}?.let{ addImdbId(it) }
            meta?.optInt("anilistId",0)?.takeIf{it!=0}?.let{ addAniListId(it) }
            meta?.optInt("malId",0)?.takeIf{it!=0}?.let{ addMalId(it) }
        }
    }

    private suspend fun loadTv(url:String, obj:JSONObject): LoadResponse?{
        val title=obj.optString("title","Unknown")
        val meta=CTGCore.retry{ WizEnrichCTG.enrich(title, null, false) }
        val isAnime=isAnimeObj(obj) || meta?.optBoolean("isAnime")==true
        val seasons=obj.optJSONArray("seasons") ?: JSONArray().put(obj)
        val episodes=mutableListOf<Episode>()
        val seasonData=CTGCore.parallelMapNotNull((0 until seasons.length()).toList(), concurrency=4){ si->
            val sObj=seasons.optJSONObject(si) ?: return@parallelMapNotNull emptyList<Episode>()
            val seasonNum=sObj.optInt("season_number", si+1)
            val epsArr=sObj.optJSONArray("episodes") ?: JSONArray()
            (0 until epsArr.length()).mapNotNull{ ei->
                val eObj=epsArr.optJSONObject(ei) ?: return@mapNotNull null
                val epNum=eObj.optInt("episode_number", ei+1)
                val epLinks=extractLinks(eObj); val data=linksToData(epLinks, meta)
                newEpisode(data){ name=eObj.optString("title","Episode $epNum"); season=seasonNum; episode=epNum }
            }
        }
        seasonData.forEach{ episodes.addAll(it) }
        if(episodes.isEmpty()){
            val links=extractLinks(obj); val data=linksToData(links, meta)
            episodes.add(newEpisode(data){ name="Episode 1"; season=1; episode=1 })
        }
        return newTvSeriesLoadResponse(meta?.optString("name")?.takeIf{it.isNotBlank()} ?: meta?.optString("title")?.takeIf{it.isNotBlank()} ?: title, url, if(isAnime) TvType.Anime else TvType.TvSeries, episodes){
            posterUrl=meta?.optString("poster_path")?.let{"https://image.tmdb.org/t/p/w500$it"} ?: meta?.optString("poster") ?: obj.optString("poster").takeIf{it.isNotBlank()}
            backgroundPosterUrl=meta?.optString("backdrop_path")?.let{"https://image.tmdb.org/t/p/original$it"} ?: meta?.optString("backdrop")
            plot=meta?.optString("overview") ?: obj.optString("description")
            meta?.optDouble("vote_average",0.0)?.takeIf{it>0}?.let{ score=Score.from10(it) }
            meta?.optString("imdb_id")?.takeIf{it.startsWith("tt")}?.let{ addImdbId(it) }
            meta?.optInt("anilistId",0)?.takeIf{it!=0}?.let{ addAniListId(it) }
        }
    }

    private suspend fun loadAnime(url:String, obj:JSONObject): LoadResponse?{
        val title=obj.optString("title","Unknown")
        val meta=CTGCore.retry{ WizEnrichCTG.enrich(title, null, true) }
        val epsArr=obj.optJSONArray("episodes") ?: JSONArray()
        val episodes=(0 until epsArr.length()).mapNotNull{ i->
            val eObj=epsArr.optJSONObject(i) ?: return@mapNotNull null
            val links=extractLinks(eObj); val data=linksToData(links, meta)
            newEpisode(data){ name=eObj.optString("title","Episode ${i+1}"); season=1; episode=eObj.optInt("episode_number",i+1) }
        }.ifEmpty{ listOf(newEpisode(linksToData(extractLinks(obj), meta)){ name=title; season=1; episode=1 }) }
        return newAnimeLoadResponse(meta?.optString("title")?.takeIf{it.isNotBlank()} ?: title, url, TvType.Anime, episodes){
            posterUrl=meta?.optString("poster") ?: meta?.optString("poster_path")?.let{"https://image.tmdb.org/t/p/w500$it"} ?: obj.optString("poster").takeIf{it.isNotBlank()}
            plot=meta?.optString("overview") ?: obj.optString("description")
            meta?.optInt("anilistId",0)?.takeIf{it!=0}?.let{ addAniListId(it) }
            meta?.optInt("malId",0)?.takeIf{it!=0}?.let{ addMalId(it) }
        }
    }

    private data class InternalLink(val url:String, val quality:String?, val type:String?)
    private fun extractLinks(obj:JSONObject): List<InternalLink>{
        val rawHtml=obj.optString("rawHtml")
        if(rawHtml.isNotBlank()){
            val parsed=CTGCore.parseCtgLinks(rawHtml)
            if(parsed.isNotEmpty()) return parsed.map{ InternalLink(it.hlsUrl?:it.url, it.quality, it.type) }
        }
        val arr=obj.optJSONArray("links") ?: obj.optJSONArray("download_links") ?: JSONArray()
        return (0 until arr.length()).mapNotNull{ i-> val o=arr.optJSONObject(i)?:return@mapNotNull null; val u=o.optString("url").ifBlank{return@mapNotNull null}; InternalLink(u, o.optString("quality"), o.optString("type")) }
    }
    private fun linksToData(links:List<InternalLink>, meta:JSONObject?): String{
        val arr=JSONArray()
        links.forEach{ l-> arr.put(JSONObject().put("url",l.url).put("quality",l.quality).put("type",l.type).put("imdbId", meta?.optString("imdb_id")).put("anilistId", meta?.optInt("anilistId"))) }
        return arr.toString()
    }

    override suspend fun loadLinks(data:String, isCasting:Boolean, subtitleCallback:(SubtitleFile)->Unit, callback:(ExtractorLink)->Unit): Boolean {
        val arr=runCatching{ JSONArray(data) }.getOrElse{ JSONArray().put(JSONObject().put("url",data)) }
        if(arr.length()==0) return false
        var found=false
        val seen=linkedSetOf<String>()
        for(i in 0 until arr.length()){
            val obj=arr.optJSONObject(i)?:continue
            if(obj.optBoolean("broken",false)) continue
            val raw=obj.optString("url").ifBlank{continue}
            if(!seen.add(raw)) continue
            val quality=obj.optString("quality","Unknown")
            val sourceName=CTGCore.buildSourceName(obj.optString("source"), raw, i+1)
            if(raw.contains(".m3u8",true)){
                M3u8Helper.generateM3u8(sourceName, raw, mainUrl).forEach(callback); found=true
            } else if(raw.contains(".mp4",true)||raw.contains(".mkv",true)){
                callback(newExtractorLink(sourceName, "$sourceName - $quality", raw){ this.quality=getQualityFromName(quality) }); found=true
            } else {
                runCatching{ loadExtractor(raw, mainUrl, subtitleCallback){ callback(it); found=true } }
            }
        }
        // StreamPlay torrentio fallback
        val imdbId=arr.optJSONObject(0)?.optString("imdbId")?.takeIf{it.startsWith("tt")}
        if(imdbId!=null && !found){
            val torrents=CTGCore.retry{ app.get("https://torrentio.strem.fun/stream/movie/$imdbId.json", timeout=10_000).text }?.let{ runCatching{ JSONObject(it).optJSONArray("streams") }.getOrNull() }
            torrents?.let{
                for(i in 0 until it.length()){
                    val st=it.optJSONObject(i) ?: continue
                    val u=st.optString("url").ifBlank{continue}
                    if(seen.add(u)){
                        callback(newExtractorLink("Torrentio ${st.optString("title","")}", st.optString("title","Torrentio"), u){ quality=getQualityFromName(st.optString("title","")) }); found=true
                    }
                }
            }
        }
        return found
    }
}
