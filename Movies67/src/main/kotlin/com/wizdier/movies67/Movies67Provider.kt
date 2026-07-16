package com.wizdier.movies67

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object Movies67Core {
    suspend fun <T,R> parallelMapNotNull(items:List<T>, concurrency:Int=8, fetch:suspend(T)->R?): List<R>{
        if(items.isEmpty()) return emptyList()
        val gate=Semaphore(concurrency)
        return coroutineScope{ items.map{ async{ try{ gate.withPermit{ fetch(it) } } catch(_:Throwable){ null } } }.awaitAll().filterNotNull() }
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
    class TtlCache<K,V>(private val ttl:Long=10*60*1000L, private val max:Int=256){
        private data class E<V>(val v:V, val ts:Long)
        private val map=ConcurrentHashMap<K,E<V>>()
        private val c=AtomicLong(0)
        fun get(k:K):V?{ val e=map[k]?:return null; if(System.currentTimeMillis()-e.ts>ttl){map.remove(k,e); return null}; return e.v }
        fun put(k:K,v:V){ map[k]=E(v,System.currentTimeMillis()); if(c.incrementAndGet()%32==0L){ val now=System.currentTimeMillis(); map.entries.removeAll{(_,e)-> now-e.ts>ttl }; if(map.size>max){ map.entries.sortedBy{it.value.ts}.take(map.size-max).forEach{(kk,_)->map.remove(kk)} } } }
        suspend fun getOrPutSuspend(k:K, compute:suspend()->V?): V?{ get(k)?.let{return it}; val v=compute()?:return null; put(k,v); return v }
    }
    fun getSimplifiedTitle(s:String): String{
        val size=Regex("""(\d+(?:\.\d+)?\s?(?:MB|GB))""",RegexOption.IGNORE_CASE).find(s)?.value?.uppercase()?.let{"$it 💾"} ?: ""
        val qual=when{
            s.contains("4K",true)->"4K 💿"; s.contains("1080p",true)->"1080p 🌟"; s.contains("720p",true)->"720p ✨"; s.contains("WEB-DL",true)->"WEB-DL ☁️"; else->""
        }
        val res=listOfNotNull(qual.takeIf{it.isNotBlank()}, size.takeIf{it.isNotBlank()}).joinToString(" | ")
        return if(res.isBlank()) "" else "\n$res"
    }
}

// Dual enrichment for Movies67 – TMDB for movies/tv + AniList for anime (StreamPlay multiAPI pattern)
internal object WizEnrichM67 {
    private const val TMDB_API="https://api.themoviedb.org/3"
    private const val TMDB_KEY="98ae14df2b8d8f8f8136499daf79f0e0"
    private const val IMG="https://image.tmdb.org/t/p"
    private val cache=Movies67Core.TtlCache<String, JSONObject>()
    private val aniCache=Movies67Core.TtlCache<String, JSONObject>()

    private suspend fun safeJson(url:String)=Movies67Core.retry{ JSONObject(app.get(url, timeout=8000).text) }
    private fun poster(p:String?, size:String="w500")=p?.takeIf{it.isNotBlank()&&it!="null"}?.let{"$IMG/$size$it"}

    suspend fun tmdbMovie(id:String): JSONObject?{
        val key="movie_$id"
        cache.get(key)?.let{return it}
        val detail=safeJson("$TMDB_API/movie/$id?api_key=$TMDB_KEY&append_to_response=external_ids,images,videos,credits") ?: return null
        cache.put(key, detail); return detail
    }
    suspend fun tmdbTv(id:String): JSONObject?{
        val key="tv_$id"
        cache.get(key)?.let{return it}
        val detail=safeJson("$TMDB_API/tv/$id?api_key=$TMDB_KEY&append_to_response=external_ids,images,videos,credits") ?: return null
        cache.put(key, detail); return detail
    }
    suspend fun anilistByTitle(title:String): JSONObject?{
        val key="ani_${title.lowercase()}"
        aniCache.get(key)?.let{return it}
        val query="""query (${'$'}search:String){ Page(page:1, perPage:5){ media(search:${'$'}search, type:ANIME){ id idMal title{ romaji english native } coverImage{ extraLarge large } bannerImage description averageScore genres } } }"""
        val vars=JSONObject().put("search", title)
        val body=JSONObject().put("query", query).put("variables", vars)
        val resText=Movies67Core.retry{ app.post("https://graphql.anilist.co", requestBody=body.toString().toRequestBody("application/json".toMediaTypeOrNull()), timeout=10_000).text } ?: return null
        val media=runCatching{ JSONObject(resText).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")?.optJSONObject(0) }.getOrNull() ?: return null
        val out=JSONObject()
        out.put("title", media.optJSONObject("title")?.optString("english") ?: media.optJSONObject("title")?.optString("romaji") ?: title)
        out.put("overview", media.optString("description").replace(Regex("<[^>]*>"),""))
        out.put("poster", media.optJSONObject("coverImage")?.optString("extraLarge"))
        out.put("backdrop", media.optString("bannerImage"))
        out.put("vote_average", media.optInt("averageScore",0)/10.0)
        out.put("anilistId", media.optInt("id",0))
        out.put("malId", media.optInt("idMal",0))
        out.put("isAnime", true)
        aniCache.put(key, out); return out
    }
}

class Movies67Provider : MainAPI() {
    override var mainUrl = "https://67movies.nl"
    override var name = "Movies67"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true

    private val tmdbKey = "98ae14df2b8d8f8f8136499daf79f0e0"
    private val imgBase = "https://image.tmdb.org/t/p"
    private val mainPageCache=Movies67Core.TtlCache<String, List<SearchResponse>>()

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.AsianDrama, TvType.Documentary)

    override val mainPage = mainPageOf(
        "trending_movie_day" to "Trending Movies",
        "trending_tv_day" to "Trending TV Shows",
        "movie_now_playing" to "Now Playing",
        "movie_upcoming" to "Upcoming Movies",
        "movie_popular" to "Popular Movies",
        "tv_popular" to "Popular TV Shows",
        "discover_movie_28" to "Action",
        "discover_movie_35" to "Comedy",
        "discover_movie_27" to "Horror",
        "discover_movie_878" to "Sci-Fi",
        "discover_tv_10765" to "Sci-Fi & Fantasy TV"
    )

    private fun poster(p:String?, sz:String="w342")=p?.takeIf{it.isNotBlank()}?.let{"$imgBase/$sz$it"}
    private fun JSONObject.str(k:String)=if(isNull(k)) null else optString(k,"").takeIf{it.isNotBlank()&&it!="null"}
    private suspend fun tmdb(ep:String): JSONObject? = Movies67Core.retry{ JSONObject(app.get("https://api.themoviedb.org/3/$ep${if(ep.contains("?")) "&" else "?"}api_key=$tmdbKey", timeout=10_000).text) }

    private fun logo(logos:JSONArray, lng:String?): String?{
        if(logos.length()==0) return null
        var best:JSONObject?=null
        for(i in 0 until logos.length()){
            val l=logos.optJSONObject(i)?:continue
            val p=l.optString("file_path",""); if(p.isBlank()) continue
            if(best==null || l.optDouble("vote_average",0.0) > best.optDouble("vote_average",0.0)) best=l
        }
        return best?.optString("file_path")?.let{"$imgBase/w500$it"}
    }
    private fun extImdb(j:JSONObject)=j.optJSONObject("external_ids")?.str("imdb_id")

    // StreamPlay multiAPI: embed registry + stremio addons
    data class EmbedSource(val key:String, val label:String, val movieUrl:(String)->String, val tvUrl:(String,String,String)->String)
    private val embedRegistry = listOf(
        EmbedSource("vidsrc_to","VidSrc.to", {id->"https://vidsrc.to/embed/movie/$id"}, {id,s,e->"https://vidsrc.to/embed/tv/$id/$s/$e"}),
        EmbedSource("vidsrc_cc","VidSrc.cc", {id->"https://vidsrc.cc/v2/embed/movie/$id"}, {id,s,e->"https://vidsrc.cc/v2/embed/tv/$id/$s/$e"}),
        EmbedSource("embed_su","Embed.su", {id->"https://embed.su/embed/movie/$id"}, {id,s,e->"https://embed.su/embed/tv/$id/$s/$e"}),
        EmbedSource("vidlink","VidLink", {id->"https://vidlink.pro/movie/$id"}, {id,s,e->"https://vidlink.pro/tv/$id/$s/$e"}),
        EmbedSource("autoembed","AutoEmbed", {id->"https://autoembed.cc/movie/tmdb/$id"}, {id,s,e->"https://autoembed.cc/tv/tmdb/$id-$s-$e"})
    )

    override suspend fun search(q:String): List<SearchResponse>{
        if(q.isBlank()) return emptyList()
        return coroutineScope{
            val m=async{ sr("movie",q) }
            val t=async{ sr("tv",q) }
            m.await()+t.await()
        }
    }
    private suspend fun sr(tp:String,q:String)=tmdb("search/$tp?query=${java.net.URLEncoder.encode(q,"UTF-8")}")?.optJSONArray("results")?.let{ a->
        (0 until a.length()).mapNotNull{ i-> val it=a.getJSONObject(i); val id=it.optInt("id",0).takeIf{it>0}?:return@mapNotNull null
            newMovieSearchResponse(it.str("title")?:it.str("name")?: "?", "$tp/$id", if(tp=="movie") TvType.Movie else TvType.TvSeries){ posterUrl=poster(it.str("poster_path")); it.optDouble("vote_average",0.0).takeIf{it>0}?.let{score=Score.from10(it)} }
        }
    } ?: emptyList()

    override suspend fun getMainPage(page:Int, request:MainPageRequest): HomePageResponse {
        val cacheKey="${request.data}_$page"
        mainPageCache.get(cacheKey)?.let{ return newHomePageResponse(request.name, it, it.isNotEmpty() && page<10) }
        val d=request.data; val r=mutableListOf<SearchResponse>()
        when{
            d.startsWith("trending_")->{ val p=d.removePrefix("trending_").split("_"); tmdb("trending/${p[0]}/${p.getOrElse(1){"day"}}?page=$page")?.optJSONArray("results")?.let{ a-> for(i in 0 until a.length()){ val it=a.getJSONObject(i); val id=it.optInt("id",0).takeIf{it>0}?:continue; val mt=it.str("media_type")?:"movie"; r.add(newMovieSearchResponse(it.str("title")?:it.str("name")?:"?", "$mt/$id", if(mt=="movie") TvType.Movie else TvType.TvSeries){ posterUrl=poster(it.str("poster_path")); it.optDouble("vote_average",0.0).takeIf{it>0}?.let{score=Score.from10(it)} }) } } }
            d.startsWith("discover_")->{ val p=d.removePrefix("discover_").split("_"); val tp=p[0]; val pm=p.getOrElse(1){""}; val q=mutableListOf<String>(); pm.toIntOrNull()?.let{q.add("with_genres=$it")}; q.add("sort_by=popularity.desc"); q.add("page=$page"); addArr(tmdb("discover/$tp?${q.joinToString("&")}")?.optJSONArray("results"), tp, r) }
            else->{ addArr(tmdb("$d?page=$page")?.optJSONArray("results"), if(d.contains("movie")) "movie" else "tv", r) }
        }
        mainPageCache.put(cacheKey, r)
        return newHomePageResponse(request.name, r, r.isNotEmpty() && page<10)
    }
    private fun addArr(a:JSONArray?, tp:String, dst:MutableList<SearchResponse>){
        if(a==null) return
        val tvT=if(tp=="movie") TvType.Movie else TvType.TvSeries
        for(i in 0 until a.length()){
            val it=a.getJSONObject(i); val id=it.optInt("id",0).takeIf{it>0}?:continue
            dst.add(newMovieSearchResponse(it.str("title")?:it.str("name")?:"?", "$tp/$id", tvT){ posterUrl=poster(it.str("poster_path")); it.optDouble("vote_average",0.0).takeIf{it>0}?.let{score=Score.from10(it)} })
        }
    }

    override suspend fun load(url:String): LoadResponse?{
        val sg=url.trimEnd('/').split("/"); if(sg.size<2) return null
        val id=sg.last(); val tp=sg[sg.size-2]
        return when(tp){ "movie"->loadMovie(id); "tv"->loadTv(id); else->null }
    }

    private suspend fun loadMovie(id:String): LoadResponse?{
        val j=tmdb("movie/$id?append_to_response=credits,videos,recommendations,external_ids,images")?:return null
        val t=j.str("title")?:return null; val yr=j.str("release_date")?.substringBefore("-")?:""; val imdb=extImdb(j)
        // Also try AniList if genre Animation
        val isAnimeGenre=j.optJSONArray("genres")?.let{a-> (0 until a.length()).any{a.getJSONObject(it).optString("name").contains("Animation",true)} } ?: false
        var aniMeta: JSONObject?=null
        if(isAnimeGenre){
            aniMeta=Movies67Core.retry{ WizEnrichM67.anilistByTitle(t) }
        }
        val data="movie|$id|${java.net.URLEncoder.encode(t,"UTF-8")}|$yr|${imdb?:""}|${aniMeta?.optInt("anilistId",0) ?: 0}|${aniMeta?.optInt("malId",0) ?: 0}"
        return newMovieLoadResponse(aniMeta?.optString("title")?.takeIf{it.isNotBlank()} ?: t, "movie/$id", if(isAnimeGenre) TvType.AnimeMovie else TvType.Movie, data){
            posterUrl=aniMeta?.optString("poster") ?: poster(j.str("poster_path"))
            backgroundPosterUrl=aniMeta?.optString("backdrop") ?: poster(j.str("backdrop_path"),"w780")
            plot=aniMeta?.optString("overview") ?: j.str("overview")
            year=yr.toIntOrNull()
            j.optDouble("vote_average",0.0).takeIf{it>0}?.let{ score=Score.from10(it) }
            var tr:String?=null; j.optJSONObject("videos")?.optJSONArray("results")?.let{ a-> for(i in 0 until a.length()){ val v=a.getJSONObject(i); if(v.optString("type")=="Trailer"&&v.optString("site")=="YouTube"){ tr="https://www.youtube.com/watch?v=${v.optString("key")}"; break }}}
            if(tr!=null) addTrailer(tr); imdb?.let{addImdbId(it)}
            aniMeta?.optInt("anilistId",0)?.takeIf{it!=0}?.let{ addAniListId(it) }
            aniMeta?.optInt("malId",0)?.takeIf{it!=0}?.let{ addMalId(it) }
            j.optJSONObject("images")?.optJSONArray("logos")?.let{logoUrl=logo(it,lang)}
        }
    }

    private suspend fun loadTv(id:String): LoadResponse?{
        val j=tmdb("tv/$id?append_to_response=external_ids,credits,recommendations,images")?:return null
        val t=j.str("name")?:return null; val yr=j.str("first_air_date")?.substringBefore("-")?:""; val imdb=extImdb(j)?:j.str("imdb_id")
        val isAnimeGenre=j.optJSONArray("genres")?.let{a-> (0 until a.length()).any{a.getJSONObject(it).optString("name").contains("Animation",true)} } ?: false
        val isAnime=isAnimeGenre || j.optString("original_language") in listOf("ja","zh") && j.optJSONArray("genres")?.let{a-> (0 until a.length()).any{a.getJSONObject(it).optString("name").contains("Animation",true)} } == true
        var aniMeta: JSONObject?=null
        if(isAnime){
            aniMeta=Movies67Core.retry{ WizEnrichM67.anilistByTitle(t) }
        }
        val ss=minOf(j.optInt("number_of_seasons",1),15)
        val episodes=mutableListOf<Episode>()
        val seasonsData=Movies67Core.parallelMapNotNull((1..ss).toList(), concurrency=4){ s-> app.get("https://api.themoviedb.org/3/tv/$id/season/$s?api_key=$tmdbKey", timeout=10_000).text.let{ txt-> s to (runCatching{ JSONObject(txt).optJSONArray("episodes") }.getOrNull()) } }
        for((s,ea) in seasonsData){ if(ea==null) continue; for(j2 in 0 until ea.length()){ val e=ea.getJSONObject(j2); val en=e.optInt("episode_number", j2+1)
            val edata="tv|$id|$s|$en|${java.net.URLEncoder.encode(t,"UTF-8")}|$yr|${imdb?:""}|${aniMeta?.optInt("anilistId",0) ?: 0}|${aniMeta?.optInt("malId",0) ?: 0}"
            episodes.add(newEpisode(edata){ name=e.str("name")?: "Episode $en"; season=s; episode=en; posterUrl=poster(e.str("still_path"),"w300"); description=e.str("overview") })
        }}
        val tvType=if(isAnime) TvType.Anime else TvType.TvSeries
        return newTvSeriesLoadResponse(aniMeta?.optString("title")?.takeIf{it.isNotBlank()} ?: t, "tv/$id", tvType, episodes){
            posterUrl=aniMeta?.optString("poster") ?: poster(j.str("poster_path"))
            backgroundPosterUrl=aniMeta?.optString("backdrop") ?: poster(j.str("backdrop_path"),"w780")
            plot=aniMeta?.optString("overview") ?: j.str("overview")
            year=yr.toIntOrNull()
            j.optDouble("vote_average",0.0).takeIf{it>0}?.let{score=Score.from10(it)}
            imdb?.let{addImdbId(it)}
            aniMeta?.optInt("anilistId",0)?.takeIf{it!=0}?.let{ addAniListId(it) }
            aniMeta?.optInt("malId",0)?.takeIf{it!=0}?.let{ addMalId(it) }
            j.optJSONObject("images")?.optJSONArray("logos")?.let{logoUrl=logo(it,lang)}
        }
    }

    private val subsSeen=mutableSetOf<String>()
    override suspend fun loadLinks(data:String, isCasting:Boolean, sc:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit): Boolean {
        subsSeen.clear()
        val p=data.split("|"); if(p.size<3) return false
        val tp=p[0]; val id=p[1]; if(tp !in listOf("movie","tv")) return false
        val t=java.net.URLDecoder.decode(if(tp=="movie") p.getOrElse(2){""} else p.getOrElse(4){""},"UTF-8")
        val y=if(tp=="movie") p.getOrElse(3){""} else p.getOrElse(5){""}
        val imdb=if(tp=="movie") p.getOrElse(4){""} else p.getOrElse(6){""}
        val anilistId=if(tp=="movie") p.getOrElse(5){""} else p.getOrElse(7){""}
        val malId=if(tp=="movie") p.getOrElse(6){""} else p.getOrElse(8){""}
        val sid=if(tp=="tv") p.getOrElse(2){"1"} else "1"
        val eid=if(tp=="tv") p.getOrElse(3){"1"} else "1"
        val mt=tp
        fetchWingsDb(mt,id,sid,eid,t,y,imdb,cb,sc)
        embedRegistry.forEach{ src->
            val url=if(mt=="movie") src.movieUrl(id) else src.tvUrl(id,sid,eid)
            runCatching{ loadExtractor(url, mainUrl, sc, cb) }
        }
        // StreamPlay torrentio fallback also if imdb present
        if(imdb.isNotBlank() && imdb.startsWith("tt")){
            val torrents=Movies67Core.retry{ app.get("https://torrentio.strem.fun/stream/${if(mt=="movie") "movie" else "series"}/$imdb${if(mt=="tv") ":$sid:$eid" else ""}.json", timeout=10_000).text }?.let{ runCatching{ JSONObject(it).optJSONArray("streams") }.getOrNull() }
            torrents?.let{
                for(i in 0 until it.length()){
                    val st=it.optJSONObject(i) ?: continue
                    val u=st.optString("url").ifBlank{continue}
                    cb(newExtractorLink("Torrentio ${st.optString("title","")}", st.optString("title","Torrentio"), u){ quality=getQualityFromName(st.optString("title","")) })
                }
            }
        }
        return true
    }

    private suspend fun fetchWingsDb(mt:String, id:String, s:String, e:String, title:String, year:String, imdb:String, cb:(ExtractorLink)->Unit, sc:(SubtitleFile)->Unit){
        val servers=listOf("jett" to "Jett", "cdn" to "Yoru", "tejo" to "Tejo", "neon2" to "Neon", "ym" to "Sage", "downloader2" to "Cypher", "m4uhd" to "Breach", "hdmovie" to "Vyse")
        val seed:String = try{
            JSONObject(app.get("https://api.wingsdatabase.com/seed?mediaId=$id", timeout=10000, headers=mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14)","Accept" to "application/json","Referer" to "https://www.vidking.net/","Origin" to "https://www.vidking.net")).text).str("seed") ?: return
        } catch(_:Exception){ return }
        val encTitle=java.net.URLEncoder.encode(java.net.URLEncoder.encode(title,"UTF-8"),"UTF-8")
        for((key,label) in servers){
            if(key=="cdn" && mt!="movie") continue
            try{
                val qs=listOf("title" to encTitle, "mediaType" to mt, "year" to year, "episodeId" to e, "seasonId" to s, "tmdbId" to id, "imdbId" to imdb, "enc" to "2", "seed" to seed).joinToString("&"){(kv,vv)->"$kv=${java.net.URLEncoder.encode(vv,"UTF-8")}"}
                val enc=app.get("https://api.wingsdatabase.com/${key}/sources-with-title?$qs", timeout=15000, headers=mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14)","Accept" to "application/json, text/plain, */*","Referer" to "https://www.vidking.net/","Origin" to "https://www.vidking.net")).text
                if(enc.isBlank()||enc.startsWith("{")||enc.length<100) continue
                val body=JSONObject().apply{ put("text",enc); put("id",id); put("seed",seed) }
                val rp=JSONObject(app.post("https://enc-dec.app/api/dec-videasy", requestBody=body.toString().toRequestBody("application/json".toMediaTypeOrNull()), timeout=20000, headers=mapOf("Content-Type" to "application/json")).text)
                if(rp.optInt("status",-1)!=200) continue
                val obj=rp.optJSONObject("result")?:continue
                obj.optJSONArray("sources")?.let{ srcs-> for(i in 0 until srcs.length()){ val src=srcs.getJSONObject(i); src.str("url")?.let{ u-> cb(newExtractorLink("$name ($label)", "$label - ${src.str("quality")?:"?"}${Movies67Core.getSimplifiedTitle(src.str("quality")?:"")}", u){ quality=getQualityFromName(src.str("quality")?:"Unknown") }) } } }
                obj.optJSONArray("subtitles")?.let{ subs-> for(i in 0 until subs.length()){ val sb=subs.getJSONObject(i); val su=sb.str("url")?:continue; val lang=sb.str("lang")?:"unknown"; if(su !in subsSeen){ subsSeen.add(su); sc(SubtitleFile(su,lang)) } } }
            } catch(_:Exception){ continue }
        }
    }
}
