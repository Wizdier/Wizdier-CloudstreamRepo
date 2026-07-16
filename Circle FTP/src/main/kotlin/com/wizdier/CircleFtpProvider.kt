package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object CircleCore {
    const val TIMEOUT=12_000L
    const val CACHE=60
    suspend fun fetchWithFallback(primary:String, fallback:String): String?{
        for(mirror in listOf(primary, fallback)){
            repeat(2){ attempt->
                val res=runCatching{ app.get(mirror, verify=false, cacheTime=CACHE, timeout=TIMEOUT) }.getOrNull()
                if(res!=null && res.code in 200..299 && res.text.isNotBlank()) return res.text
                if(attempt==0) delay(jitter(attempt))
            }
        }
        return null
    }
    fun jitter(a:Int): Long{ val base=(300L shl a).coerceAtMost(2000L); val j=(base*0.25*(Math.random()-0.5)*2).toLong(); return (base+j).coerceAtLeast(0) }
    suspend fun <T,R> parallelMapNotNull(items:List<T>, concurrency:Int=8, fetch:suspend(T)->R?): List<R>{
        if(items.isEmpty()) return emptyList()
        val gate=Semaphore(concurrency)
        return coroutineScope{ items.map{ async{ try{ gate.withPermit{ fetch(it) } } catch(_:Throwable){ null } } }.awaitAll().filterNotNull() }
    }
    suspend fun <T> retry(max:Int=3, init:Long=400, block:suspend()->T): T?{
        var b=init
        repeat(max){ attempt->
            try{ return block() } catch(ce:kotlinx.coroutines.CancellationException){ throw ce } catch(_:Throwable){
                if(attempt==max-1) return null
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
        fun get(k:K):V?{ val e=map[k]?:return null; if(System.currentTimeMillis()-e.ts>ttlMs){ map.remove(k,e); return null }; return e.v }
        fun put(k:K,v:V){ map[k]=E(v,System.currentTimeMillis()); if(c.incrementAndGet()%32==0L){ val now=System.currentTimeMillis(); map.entries.removeAll{(_,e)-> now-e.ts>ttlMs }; if(map.size>max){ map.entries.sortedBy{it.value.ts}.take(map.size-max).forEach{(kk,_)->map.remove(kk)} } } }
        suspend fun getOrPutSuspend(k:K, compute:suspend()->V?): V?{ get(k)?.let{return it}; val v=compute()?:return null; put(k,v); return v }
    }
    fun buildSourceName(label:String?, url:String, idx:Int):String{
        if(!label.isNullOrBlank()) return label
        return runCatching{ java.net.URI(url).host?.substringBefore('.')?.replaceFirstChar{it.uppercase()} }.getOrNull() ?: "Source $idx"
    }
    fun String.encodeUrl()=URLEncoder.encode(this,"UTF-8")
}

internal object WizEnrichCircle {
    private const val TMDB_API="https://api.themoviedb.org/3"
    private const val TMDB_KEY="98ae14df2b8d8f8f8136499daf79f0e0"
    private const val IMG="https://image.tmdb.org/t/p"
    private val cache=CircleCore.TtlCache<String, Enriched>()
    private val aniCache=CircleCore.TtlCache<String, Enriched>()

    data class Enriched(
        val title:String?=null, val plot:String?=null, val poster:String?=null, val backdrop:String?=null,
        val year:Int?=null, val rating:Double?=null, val tags:List<String>?=null,
        val imdbId:String?=null, val tmdbId:Int?=null, val anilistId:Int?=null, val malId:Int?=null, val isAnime:Boolean=false
    )
    private fun poster(p:String?, size:String="w500")=p?.takeIf{it.isNotBlank()&&it!="null"}?.let{"$IMG/$size$it"}
    private suspend fun safeJson(url:String)=CircleCore.retry{ JSONObject(app.get(url, timeout=8000).text) }

    suspend fun tmdbMovie(title:String, year:Int?): Enriched?{
        val key="movie_${title.lowercase()}_$year"
        cache.get(key)?.let{return it}
        val q=title.encodeUrl()
        val search=safeJson("$TMDB_API/search/movie?api_key=$TMDB_KEY&query=$q${year?.let{"&year=$it"}?:""}") ?: return null
        val first=search.optJSONArray("results")?.optJSONObject(0) ?: return null
        val id=first.optInt("id",0); if(id==0) return null
        val detail=safeJson("$TMDB_API/movie/$id?api_key=$TMDB_KEY&append_to_response=external_ids,images") ?: return null
        val meta=Enriched(
            title=detail.optString("title").ifBlank{first.optString("title")},
            plot=detail.optString("overview").takeIf{it.isNotBlank()},
            poster=poster(detail.optString("poster_path")),
            backdrop=poster(detail.optString("backdrop_path"),"original"),
            year=detail.optString("release_date").take(4).toIntOrNull() ?: year,
            rating=detail.optDouble("vote_average",0.0).takeIf{it>0},
            tags=detail.optJSONArray("genres")?.let{a-> (0 until a.length()).map{a.getJSONObject(it).getString("name")} },
            imdbId=detail.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf{it.startsWith("tt")},
            tmdbId=id, isAnime=false
        )
        cache.put(key, meta); return meta
    }

    suspend fun tmdbTv(title:String): Enriched?{
        val key="tv_${title.lowercase()}"
        cache.get(key)?.let{return it}
        val q=title.encodeUrl()
        val search=safeJson("$TMDB_API/search/tv?api_key=$TMDB_KEY&query=$q") ?: return null
        val first=search.optJSONArray("results")?.optJSONObject(0) ?: return null
        val id=first.optInt("id",0); if(id==0) return null
        val detail=safeJson("$TMDB_API/tv/$id?api_key=$TMDB_KEY&append_to_response=external_ids,images") ?: return null
        val meta=Enriched(
            title=detail.optString("name").ifBlank{first.optString("name")},
            plot=detail.optString("overview").takeIf{it.isNotBlank()},
            poster=poster(detail.optString("poster_path")),
            backdrop=poster(detail.optString("backdrop_path"),"original"),
            year=detail.optString("first_air_date").take(4).toIntOrNull(),
            rating=detail.optDouble("vote_average",0.0).takeIf{it>0},
            tags=detail.optJSONArray("genres")?.let{a-> (0 until a.length()).map{a.getJSONObject(it).getString("name")} },
            imdbId=detail.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf{it.startsWith("tt")},
            tmdbId=id, isAnime=false
        )
        cache.put(key, meta); return meta
    }

    suspend fun anilist(title:String): Enriched?{
        val key="ani_${title.lowercase()}"
        aniCache.get(key)?.let{return it}
        val query="""query (${'$'}search:String){ Page(page:1, perPage:5){ media(search:${'$'}search, type:ANIME){ id idMal title{ romaji english native } coverImage{ extraLarge large } bannerImage description averageScore genres } } }"""
        val vars=JSONObject().put("search", title)
        val body=JSONObject().put("query", query).put("variables", vars)
        val resText=CircleCore.retry{ app.post("https://graphql.anilist.co", requestBody=body.toString().toRequestBody("application/json".toMediaTypeOrNull()), timeout=10_000).text } ?: return null
        val media=runCatching{ JSONObject(resText).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")?.optJSONObject(0) }.getOrNull() ?: return null
        val eng=media.optJSONObject("title")?.optString("english")?.takeIf{it.isNotBlank()}
        val rom=media.optJSONObject("title")?.optString("romaji")
        val cover=media.optJSONObject("coverImage")?.optString("extraLarge") ?: media.optJSONObject("coverImage")?.optString("large")
        val banner=media.optString("bannerImage").takeIf{it.isNotBlank()}
        val desc=media.optString("description").replace(Regex("<[^>]*>"),"").takeIf{it.isNotBlank()}
        val score=media.optInt("averageScore",0).takeIf{it>0}?.let{it/10.0}
        val genres=media.optJSONArray("genres")?.let{a-> (0 until a.length()).map{a.getString(it)} }
        val aniId=media.optInt("id",0).takeIf{it!=0}
        val malId=media.optInt("idMal",0).takeIf{it!=0}
        val meta=Enriched(title=eng ?: rom ?: title, plot=desc, poster=cover, backdrop=banner, rating=score, tags=genres, anilistId=aniId, malId=malId, isAnime=true)
        aniCache.put(key, meta); return meta
    }

    suspend fun enrich(title:String, year:Int?, isSeries:Boolean): Enriched{
        val isAnimeHint=title.lowercase().let{ t-> listOf("anime","naruto","one piece","bleach","jujutsu","demon slayer","solo leveling").any{t.contains(it)} }
        return if(isAnimeHint) anilist(title) ?: tmdbTv(title) ?: tmdbMovie(title, year) ?: Enriched(title=title, year=year, isAnime=true)
        else tmdbMovie(title, year) ?: tmdbTv(title) ?: anilist(title) ?: Enriched(title=title, year=year)
    }

    private fun String.encodeUrl()=URLEncoder.encode(this,"UTF-8")
}

class CircleFtpProvider : MainAPI() {
    override var mainUrl = "http://new.circleftp.net"
    private var mainApiUrl = "http://new.circleftp.net:5000"
    private var fallbackApiUrl = "http://15.1.1.50:5000"
    override var name = "Circle FTP"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.Cartoon, TvType.AsianDrama, TvType.Documentary, TvType.OVA)

    private val mainPageCache = CircleCore.TtlCache<String, List<SearchResponse>>()
    private val dynamicApiCache = CircleCore.TtlCache<String, String>(ttlMs=5*60*1000L)

    override val mainPage = mainPageOf(
        "80" to "Featured", "6" to "English Movies", "9" to "English & Foreign TV Series",
        "22" to "Dubbed TV Series", "2" to "Hindi Movies", "7" to "English & Foreign Hindi Dubbed Movies",
        "8" to "Foreign Language Movies", "3" to "South Indian Dubbed Movies", "4" to "South Indian Movies",
        "1" to "Animation Movies", "21" to "Anime Series", "85" to "Documentary", "15" to "WWE"
    )

    private suspend fun resolveApiBase(): Pair<String,String>{
        val cp=dynamicApiCache.get("primary"); val cf=dynamicApiCache.get("fallback")
        if(cp!=null && cf!=null) return cp to cf
        val remote=CircleCore.retry{ app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json", timeout=8000).text }?.let{ runCatching{ JSONObject(it) }.getOrNull() }
        remote?.let{
            val p=it.optString("circle_primary","").takeIf{s->s.isNotBlank()}
            val f=it.optString("circle_fallback","").takeIf{s->s.isNotBlank()}
            if(p!=null && f!=null){ dynamicApiCache.put("primary", p); dynamicApiCache.put("fallback", f); return p to f }
        }
        dynamicApiCache.put("primary", mainApiUrl); dynamicApiCache.put("fallback", fallbackApiUrl)
        return mainApiUrl to fallbackApiUrl
    }

    private suspend fun fetch(path:String): String?{
        val (primary, fallback)=resolveApiBase()
        return CircleCore.fetchWithFallback("$primary$path", "$fallback$path")
    }

    override suspend fun getMainPage(page:Int, request:MainPageRequest): HomePageResponse {
        val cacheKey="${request.data}_$page"
        mainPageCache.get(cacheKey)?.let{ return newHomePageResponse(HomePageList(request.name, it), it.isNotEmpty()) }
        val txt=fetch("/api/v1/posts?category=${request.data}&page=$page") ?: return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        val obj=runCatching{ JSONObject(txt) }.getOrNull() ?: return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        val arr=obj.optJSONArray("data") ?: obj.optJSONArray("posts") ?: JSONArray()
        val list=(0 until arr.length()).mapNotNull{ i-> parsePost(arr.optJSONObject(i)) }
        mainPageCache.put(cacheKey, list)
        return newHomePageResponse(HomePageList(request.name, list), list.isNotEmpty())
    }

    override suspend fun search(query:String): List<SearchResponse>{
        val q=query.trim(); if(q.isBlank()) return emptyList()
        return coroutineScope{
            val a=async{ runCatching{ searchPosts(q) }.getOrDefault(emptyList()) }
            val b=async{ runCatching{ searchPosts(q) }.getOrDefault(emptyList()) }
            (a.await()+b.await()).distinctBy{it.url}
        }
    }

    private suspend fun searchPosts(q:String): List<SearchResponse>{
        val txt=fetch("/api/v1/search?query=${q.encodeUrl()}") ?: return emptyList()
        val arr=runCatching{ JSONObject(txt).optJSONArray("data") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull{ parsePost(arr.optJSONObject(it)) }
    }

    private fun parsePost(o:JSONObject?): SearchResponse?{
        if(o==null) return null
        val id=o.optInt("id",0).takeIf{it!=0} ?: o.optString("id").toIntOrNull() ?: return null
        val title=o.optString("title", o.optString("name","")).trim(); if(title.isBlank()) return null
        val poster=o.optString("poster").takeIf{it.isNotBlank()}
        val typeStr=o.optString("type","movie").lowercase()
        val tvType=when{
            typeStr.contains("anime") && typeStr.contains("movie")->TvType.AnimeMovie
            typeStr.contains("anime")->TvType.Anime
            typeStr.contains("series")||typeStr.contains("tv")->TvType.TvSeries
            else->TvType.Movie
        }
        val data=Base64.getEncoder().encodeToString(JSONObject().put("id",id).put("title",title).put("isAnime",tvType==TvType.Anime).toString().toByteArray())
        return newMovieSearchResponse(title, "load?data=$data", tvType){ this.posterUrl=poster }
    }

    override suspend fun load(url:String): LoadResponse?{
        val b64=url.substringAfter("data=").substringBefore("&")
        val obj=runCatching{ JSONObject(String(Base64.getDecoder().decode(b64))) }.getOrNull() ?: return null
        val id=obj.optInt("id",0)
        val rawTitle=obj.optString("title","Unknown")
        val isAnimeFlag=obj.optBoolean("isAnime", false)
        val detailTxt=fetch("/api/v1/post/$id") ?: return null
        val detail=runCatching{ JSONObject(detailTxt).optJSONObject("data") ?: JSONObject(detailTxt) }.getOrNull() ?: return null
        val title=detail.optString("title", rawTitle)
        // Dual enrichment: TMDB + AniList
        val enriched=CircleCore.retry{ WizEnrichCircle.enrich(title, detail.optInt("year",0).takeIf{it!=0}, isAnimeFlag) } ?: WizEnrichCircle.Enriched(title=title, isAnime=isAnimeFlag)
        val isAnime=enriched.isAnime || isAnimeFlag || detail.optString("category","").contains("anime",true)
        val linksArr=detail.optJSONArray("download_links") ?: detail.optJSONArray("links") ?: JSONArray()
        val downloadLinks=(0 until linksArr.length()).mapNotNull{ i-> linksArr.optJSONObject(i)?.let{ it.optString("url").takeIf{s->s.isNotBlank()} to it.optString("quality","") } }
        val episodesArr=detail.optJSONArray("episodes")
        return if(episodesArr!=null && episodesArr.length()>0){
            val episodes=(0 until episodesArr.length()).mapNotNull{ i->
                val ep=episodesArr.optJSONObject(i) ?: return@mapNotNull null
                val epUrl=ep.optString("url").takeIf{it.isNotBlank()} ?: return@mapNotNull null
                newEpisode(epUrl){ name=ep.optString("title","Episode ${i+1}"); season=ep.optInt("season",1); episode=ep.optInt("episode",i+1) }
            }
            newTvSeriesLoadResponse(enriched.title ?: title, url, if(isAnime) TvType.Anime else TvType.TvSeries, episodes){
                posterUrl=enriched.poster ?: detail.optString("poster").takeIf{it.isNotBlank()}
                backgroundPosterUrl=enriched.backdrop
                plot=enriched.plot ?: detail.optString("description")
                year=enriched.year ?: detail.optInt("year",0).takeIf{it!=0}
                enriched.rating?.let{ score=Score.from10(it) }
                enriched.tags?.let{ tags=it }
                enriched.imdbId?.let{ addImdbId(it) }
                enriched.anilistId?.let{ addAniListId(it) }
                enriched.malId?.let{ addMalId(it) }
            }
        } else {
            val arr=JSONArray(); downloadLinks.forEach{ (u,q)-> arr.put(JSONObject().put("url",u).put("quality",q).put("imdbId", enriched.imdbId).put("anilistId", enriched.anilistId)) }
            val dataUrl=Base64.getEncoder().encodeToString(arr.toString().toByteArray())
            newMovieLoadResponse(enriched.title ?: title, url, if(isAnime) TvType.AnimeMovie else TvType.Movie, dataUrl){
                posterUrl=enriched.poster ?: detail.optString("poster").takeIf{it.isNotBlank()}
                backgroundPosterUrl=enriched.backdrop
                plot=enriched.plot ?: detail.optString("description")
                year=enriched.year ?: detail.optInt("year",0).takeIf{it!=0}
                enriched.rating?.let{ score=Score.from10(it) }
                enriched.tags?.let{ tags=it }
                enriched.imdbId?.let{ addImdbId(it) }
                enriched.anilistId?.let{ addAniListId(it) }
                enriched.malId?.let{ addMalId(it) }
            }
        }
    }

    override suspend fun loadLinks(data:String, isCasting:Boolean, subtitleCallback:(SubtitleFile)->Unit, callback:(ExtractorLink)->Unit): Boolean {
        val links=try{
            val json=String(Base64.getDecoder().decode(data))
            val arr=if(json.trim().startsWith("[")) JSONArray(json) else JSONArray().put(JSONObject().put("url",json))
            (0 until arr.length()).map{ arr.getJSONObject(it) }
        } catch(_:Exception){ if(data.startsWith("http")) listOf(JSONObject().put("url",data)) else emptyList() }
        if(links.isEmpty()) return false
        var found=false
        val seen=linkedSetOf<String>()
        links.forEach{ obj->
            val url=obj.optString("url").trim(); if(url.isBlank() || !seen.add(url)) return@forEach
            val quality=obj.optString("quality","")
            if(url.contains(".m3u8",true)){
                M3u8Helper.generateM3u8(CircleCore.buildSourceName(quality,url,seen.size), url, mainUrl).forEach(callback); found=true
            } else if(url.contains(".mp4",true)||url.contains(".mkv",true)){
                callback(newExtractorLink(CircleCore.buildSourceName(quality,url,seen.size), CircleCore.buildSourceName(quality,url,seen.size), url){ this.quality=getQualityFromName(quality.ifBlank{url}) }); found=true
            } else {
                runCatching{ loadExtractor(url, mainUrl, subtitleCallback){ found=true; callback(it) } }
            }
        }
        // StreamPlay multiAPI fallback: torrentio
        val imdbId=links.firstOrNull()?.optString("imdbId")?.takeIf{it.startsWith("tt")}
        if(imdbId!=null){
            val torrents=CircleCore.retry{
                app.get("https://torrentio.strem.fun/stream/movie/$imdbId.json", timeout=10_000).text
            }?.let{ txt-> runCatching{ JSONObject(txt).optJSONArray("streams") }.getOrNull() }
            torrents?.let{ arr->
                for(i in 0 until arr.length()){
                    val st=arr.optJSONObject(i) ?: continue
                    val u=st.optString("url").ifBlank{continue}
                    if(seen.add(u)){
                        callback(newExtractorLink("Torrentio ${st.optString("title","")}", st.optString("title","Torrentio"), u){ quality=getQualityFromName(st.optString("title","")) }); found=true
                    }
                }
            }
        }
        return found
    }

    private fun String.encodeUrl()=URLEncoder.encode(this,"UTF-8")
}
