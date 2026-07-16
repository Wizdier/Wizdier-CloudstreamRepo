package com.wizdier

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
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object FTPBDCore {
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
    fun buildSourceName(label:String?, url:String, idx:Int):String{
        if(!label.isNullOrBlank()) return label
        return runCatching{ java.net.URI(url).host?.substringBefore('.')?.replaceFirstChar{it.uppercase()} }.getOrNull() ?: "Source $idx"
    }
    fun String.encodeUrl()=URLEncoder.encode(this,"UTF-8")
    fun String.toAbs(base:String)=if(startsWith("http")) this else base.trimEnd('/') + "/" + trimStart('/')
}

internal object WizEnrichFTP {
    private const val TMDB_API="https://api.themoviedb.org/3"
    private const val TMDB_KEY="98ae14df2b8d8f8f8136499daf79f0e0"
    private const val IMG="https://image.tmdb.org/t/p"
    private val cache=FTPBDCore.TtlCache<String, JSONObject>()
    private val aniCache=FTPBDCore.TtlCache<String, JSONObject>()

    private suspend fun safeJson(url:String)=FTPBDCore.retry{ JSONObject(app.get(url, timeout=8000).text) }
    private fun poster(p:String?, size:String="w500")=p?.takeIf{it.isNotBlank()&&it!="null"}?.let{"$IMG/$size$it"}

    suspend fun tmdbMovie(title:String, year:Int?): JSONObject?{
        val key="movie_${title.lowercase()}_$year"
        cache.get(key)?.let{return it}
        val q=URLEncoder.encode(title,"UTF-8")
        val search=safeJson("$TMDB_API/search/movie?api_key=$TMDB_KEY&query=$q${year?.let{"&year=$it"}?:""}") ?: return null
        val first=search.optJSONArray("results")?.optJSONObject(0) ?: return null
        val id=first.optInt("id",0); if(id==0) return null
        val detail=safeJson("$TMDB_API/movie/$id?api_key=$TMDB_KEY&append_to_response=external_ids,images,videos") ?: return null
        cache.put(key, detail); return detail
    }
    suspend fun tmdbTv(title:String): JSONObject?{
        val key="tv_${title.lowercase()}"
        cache.get(key)?.let{return it}
        val q=URLEncoder.encode(title,"UTF-8")
        val search=safeJson("$TMDB_API/search/tv?api_key=$TMDB_KEY&query=$q") ?: return null
        val first=search.optJSONArray("results")?.optJSONObject(0) ?: return null
        val id=first.optInt("id",0); if(id==0) return null
        val detail=safeJson("$TMDB_API/tv/$id?api_key=$TMDB_KEY&append_to_response=external_ids,images,videos") ?: return null
        cache.put(key, detail); return detail
    }
    suspend fun anilist(title:String): JSONObject?{
        val key="ani_${title.lowercase()}"
        aniCache.get(key)?.let{return it}
        val query="""query (${'$'}search:String){ Page(page:1, perPage:5){ media(search:${'$'}search, type:ANIME){ id idMal title{ romaji english native } coverImage{ extraLarge large } bannerImage description averageScore genres } } }"""
        val vars=JSONObject().put("search", title)
        val body=JSONObject().put("query", query).put("variables", vars)
        val resText=FTPBDCore.retry{ app.post("https://graphql.anilist.co", requestBody=body.toString().toRequestBody("application/json".toMediaTypeOrNull()), timeout=10_000).text } ?: return null
        val media=runCatching{ JSONObject(resText).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")?.optJSONObject(0) }.getOrNull() ?: return null
        val out=JSONObject()
        out.put("title", media.optJSONObject("title")?.optString("english") ?: media.optJSONObject("title")?.optString("romaji") ?: title)
        out.put("overview", media.optString("description").replace(Regex("<[^>]*>"),""))
        out.put("poster_path", media.optJSONObject("coverImage")?.optString("extraLarge"))
        out.put("bannerImage", media.optString("bannerImage"))
        out.put("vote_average", media.optInt("averageScore",0)/10.0)
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

class FTPBD : MainAPI() {
    override var mainUrl = "https://ftpbd.net"
    override var name = "FTPBD"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AnimeMovie)

    private val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36","Referer" to "$mainUrl/")
    private val cache = FTPBDCore.TtlCache<String, List<SearchResponse>>(ttl=60*1000L)

    override val mainPage = mainPageOf(
        "movie" to "Latest Movies",
        "tv_shows" to "Latest TV Shows",
        "movies_cat/bollywood" to "Bollywood",
        "movies_cat/south-indian-movies" to "South Indian Movies",
        "movies_cat/english-movies" to "English Movies",
        "movies_cat/anime" to "Anime Movies",
        "movies_cat/action" to "Action",
        "movies_cat/horror" to "Horror",
    )

    private fun pageUrl(cat:String, page:Int)=if(page<=1) "$mainUrl/$cat/" else "$mainUrl/$cat/page/$page/"

    override suspend fun getMainPage(page:Int, request:MainPageRequest): HomePageResponse {
        val cacheKey="${request.data}_$page"
        cache.get(cacheKey)?.let{ return newHomePageResponse(HomePageList(request.name, it), it.isNotEmpty()) }
        val url=pageUrl(request.data, page)
        val doc=FTPBDCore.retry{ app.get(url, headers=headers, timeout=10_000).document } ?: return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        val items=parseCards(doc, if(request.data=="tv_shows") "/tv_shows/" else "/movie/")
        cache.put(cacheKey, items)
        return newHomePageResponse(HomePageList(request.name, items), items.isNotEmpty())
    }

    // Robust parse for FTPBD Elementor theme – finds all movie/tv links even if structure changes
    private fun parseCards(doc:org.jsoup.nodes.Document, expected:String): List<SearchResponse>{
        val seen=LinkedHashMap<String, SearchResponse>()
        // Primary: all anchors containing expected path
        doc.select("a[href*=\"$expected\"]").forEach{ a->
            var href=a.attr("href").trim()
            if(href.isBlank()) return@forEach
            // Filter out non-content links
            if(href.contains("/page/") || href.contains("/movies_cat/") && !href.matches(Regex(".*/(movie|tv_shows)/[^/]+/?$")) && expected=="/movie/") {
                // allow category pages themselves, but for movie list we only want content
                // For category pages, expected is /movie/ but link is /movie/xxx – ok
                // Skip pagination and category index links
                if(href.endsWith("/movie/") || href.endsWith("/tv_shows/") || href.endsWith("/movies_cat/") || href.matches(Regex(".*/movies_cat/[^/]+/?$"))) {
                    // This is actually category page itself if we're on homepage? But for mainPage we want content, so skip pure category links when expected is /movie/?
                    // We skip only if href exactly matches category listing, not content
                    if(href.trimEnd('/').endsWith("/movie") || href.trimEnd('/').endsWith("/tv_shows") || href.contains("/movies_cat/") && !href.contains("/movie/") && !href.contains("/tv_shows/")) {
                        // For mainPage where cat is "movie", href /movie/ is the page itself, skip
                        if(href.trimEnd('/').equals("$mainUrl/movie".trimEnd('/')) || href.trimEnd('/').equals("$mainUrl/tv_shows".trimEnd('/')) ) return@forEach
                    }
                }
            }
            // Ensure content link pattern: /movie/slug/ or /tv_shows/slug/
            if(!href.matches(Regex(".*/(movie|tv_shows)/[^/]+/?$"))){
                // Also allow /movie/slug without trailing slash already matched above
                if(!(href.contains("/movie/") && href.count{it=='/'}>=4) && !(href.contains("/tv_shows/") && href.count{it=='/'}>=4)) {
                    // Not a content link
                    // But still try to keep if it's clearly a movie
                    if(!href.contains("/movie/") && !href.contains("/tv_shows/")) return@forEach
                }
            }
            if(href.startsWith("/")) href=mainUrl+href
            if(seen.containsKey(href)) return@forEach
            if(href==mainUrl || href=="$mainUrl/" || href.contains("/feed/")) return@forEach

            // Title extraction – try multiple strategies
            var title=a.attr("title").trim()
            if(title.isBlank()){
                // Try parent with video_title class
                val parent=a.parents().firstOrNull{ it.hasClass("jws-post-item") || it.hasClass("movies-content") || it.hasClass("video_title") }
                title=parent?.selectFirst(".video_title a, .video_title, h2, h3, .title")?.text()?.trim() ?: ""
            }
            if(title.isBlank()){
                title=a.text().trim()
            }
            if(title.isBlank()){
                // Try sibling
                title=a.parent()?.selectFirst("h2, h3, .video_title")?.text()?.trim() ?: ""
            }
            if(title.isBlank() || title.length<2 || title.equals("Movies",true) || title.equals("TV Shows",true)) return@forEach

            // Poster
            val container=a.parents().firstOrNull{ it.selectFirst("img")!=null } ?: a.parent()?.parent()
            val img=container?.selectFirst("img") ?: a.selectFirst("img")
            val rawImg=img?.attr("data-src")?.ifBlank{ img.attr("src") }?.ifBlank{ img.attr("data-lazy-src") }
            val poster=rawImg?.let{
                when{
                    it.startsWith("http")->it
                    it.startsWith("//")->"https:$it"
                    it.startsWith("/")->mainUrl+it
                    else->"$mainUrl/${it.trimStart('/')}"
                }
            }

            val isSeries=href.contains("/tv_shows/")
            val year=Regex("""\b(19|20)\d{2}\b""").find(a.parents().joinToString(" "){ it.text() })?.value?.toIntOrNull()

            val resp=if(isSeries){
                newTvSeriesSearchResponse(title, href, TvType.TvSeries){ this.posterUrl=poster; this.year=year }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie){ this.posterUrl=poster; this.year=year }
            }
            seen[href]=resp
        }

        // Fallback: if still empty, try broader selector jws-post-item
        if(seen.isEmpty()){
            doc.select("div.jws-post-item, div.movies_advanced_content div.jws-post-item, div.movies-content").forEach{ el->
                val a=el.selectFirst("a[href*=\"/movie/\"], a[href*=\"/tv_shows/\"]") ?: return@forEach
                val href=a.attr("href").let{if(it.startsWith("/")) mainUrl+it else it}
                if(href.isBlank() || seen.containsKey(href)) return@forEach
                val title=el.selectFirst(".video_title a, .video_title, h2, h3")?.text()?.trim() ?: a.attr("title").ifBlank{a.text()}.trim()
                if(title.isBlank()) return@forEach
                val img=el.selectFirst("img")
                val poster=img?.attr("data-src")?.ifBlank{img.attr("src")}?.let{if(it.startsWith("http")) it else mainUrl+it}
                val isSeries=href.contains("/tv_shows/")
                val resp=if(isSeries) newTvSeriesSearchResponse(title, href, TvType.TvSeries){ this.posterUrl=poster } else newMovieSearchResponse(title, href, TvType.Movie){ this.posterUrl=poster }
                seen[href]=resp
            }
        }

        return seen.values.toList()
    }

    override suspend fun search(query:String): List<SearchResponse>{
        val q=query.trim(); if(q.isBlank()) return emptyList()
        return coroutineScope{
            val m=async{ runCatching{ parseSearch("$mainUrl/?s=${q.encodeUrl()}&post_type=movies") }.getOrDefault(emptyList()) }
            val t=async{ runCatching{ parseSearch("$mainUrl/?s=${q.encodeUrl()}&post_type=tv_shows") }.getOrDefault(emptyList()) }
            (m.await()+t.await()).distinctBy{it.url}
        }
    }
    private suspend fun parseSearch(url:String): List<SearchResponse>{
        val doc=FTPBDCore.retry{ app.get(url, headers=headers, timeout=10_000).document } ?: return emptyList()
        return parseCards(doc, "/movie/") + parseCards(doc, "/tv_shows/")
    }
    private fun String.encodeUrl()=URLEncoder.encode(this,"UTF-8")
    private fun String.toAbs():String=if(startsWith("http")) this else "$mainUrl/${trimStart('/')}"

    override suspend fun load(url:String): LoadResponse?{
        val abs=url.toAbs()
        val doc=FTPBDCore.retry{ app.get(abs, headers=headers, timeout=10_000).document } ?: return null
        return when{
            abs.contains("/tv_shows/")->loadSeries(abs, doc)
            abs.contains("/episodes/")->loadMovie(abs, doc)
            else->loadMovie(abs, doc)
        }
    }

    private suspend fun loadMovie(url:String, doc:org.jsoup.nodes.Document): LoadResponse?{
        val title=doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val year=Regex("""\d{4}""").find(doc.text())?.value?.toIntOrNull()
        val isAnime=title.lowercase().contains("anime") || doc.text().lowercase().contains("anime")
        val meta=FTPBDCore.retry{ WizEnrichFTP.enrich(title, year, isAnime) }
        val poster=meta?.optString("poster_path")?.let{"https://image.tmdb.org/t/p/w500$it"} ?: meta?.optString("poster") ?: doc.selectFirst("img[property='og:image'], .movie-poster img")?.attr("src")
        val plot=meta?.optString("overview") ?: doc.selectFirst("div.description,.plot,p")?.text()
        val links=extractLinks(doc, url)
        val arr=JSONArray(); links.forEach{ (u,l)-> arr.put(JSONObject().put("url",u).put("label",l).put("imdbId", meta?.optString("imdb_id")).put("anilistId", meta?.optInt("anilistId"))) }
        val data=Base64.getEncoder().encodeToString(arr.toString().toByteArray())
        val type=if(isAnime || meta?.optBoolean("isAnime")==true) TvType.AnimeMovie else TvType.Movie
        return newMovieLoadResponse(meta?.optString("title")?.ifBlank{meta.optString("name")} ?: title, url, type, data){
            this.posterUrl=poster
            backgroundPosterUrl=meta?.optString("backdrop_path")?.let{"https://image.tmdb.org/t/p/original$it"} ?: meta?.optString("bannerImage")
            this.plot=plot
            this.year=meta?.optString("release_date")?.take(4)?.toIntOrNull() ?: meta?.optString("first_air_date")?.take(4)?.toIntOrNull() ?: year
            meta?.optDouble("vote_average",0.0)?.takeIf{it>0}?.let{ score=Score.from10(it) }
            meta?.optString("imdb_id")?.takeIf{it.startsWith("tt")}?.let{ addImdbId(it) }
            meta?.optInt("anilistId",0)?.takeIf{it!=0}?.let{ addAniListId(it) }
            meta?.optInt("malId",0)?.takeIf{it!=0}?.let{ addMalId(it) }
        }
    }

    private suspend fun loadSeries(url:String, doc:org.jsoup.nodes.Document): LoadResponse?{
        val title=doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val isAnimeHint=title.lowercase().contains("anime") || doc.text().lowercase().contains("anime")
        val meta=FTPBDCore.retry{ WizEnrichFTP.enrich(title, null, isAnimeHint) }
        val epEls=doc.select("ul.episodes li a, div.episode a, a[href*='/episodes/']")
        val episodes=FTPBDCore.parallelMapNotNull(epEls.toList(), concurrency=8){ el->
            val href=el.attr("href").toAbs(); val name=el.text().trim().ifBlank{"Episode"}
            val num=Regex("""\d+""").find(name)?.value?.toIntOrNull() ?: 1
            newEpisode(href){ this.name=name; episode=num; season=1 }
        }
        val finalEps=if(episodes.isEmpty()) listOf(newEpisode(url){ name="Episode 1"; episode=1; season=1 }) else episodes
        val type=if(isAnimeHint || meta?.optBoolean("isAnime")==true) TvType.Anime else TvType.TvSeries
        return newTvSeriesLoadResponse(meta?.optString("name")?.ifBlank{meta.optString("title")} ?: title, url, type, finalEps){
            posterUrl=meta?.optString("poster_path")?.let{"https://image.tmdb.org/t/p/w500$it"} ?: meta?.optString("poster")
            backgroundPosterUrl=meta?.optString("backdrop_path")?.let{"https://image.tmdb.org/t/p/original$it"} ?: meta?.optString("bannerImage")
            plot=meta?.optString("overview")
            meta?.optDouble("vote_average",0.0)?.takeIf{it>0}?.let{ score=Score.from10(it) }
            meta?.optString("imdb_id")?.takeIf{it.startsWith("tt")}?.let{ addImdbId(it) }
            meta?.optInt("anilistId",0)?.takeIf{it!=0}?.let{ addAniListId(it) }
        }
    }

    private fun extractLinks(doc:org.jsoup.nodes.Document, base:String): List<Pair<String,String?>>{
        val out=mutableListOf<Pair<String,String?>>()
        doc.select("a[href*=.mp4], a[href*=.mkv], a[href*=.m3u8], [data-url]").forEach{ el->
            val href=el.attr("href").ifBlank{el.attr("data-url")}; if(href.isNotBlank()) out.add(href.toAbs() to el.text().trim().takeIf{it.isNotBlank()})
        }
        return out.distinctBy{it.first}
    }

    override suspend fun loadLinks(data:String, isCasting:Boolean, subtitleCallback:(SubtitleFile)->Unit, callback:(ExtractorLink)->Unit): Boolean {
        val links=try{
            val json=String(Base64.getDecoder().decode(data))
            val arr=JSONArray(if(json.trim().startsWith("[")) json else "[{\"url\":\"$json\"}]")
            (0 until arr.length()).map{ arr.optJSONObject(it) }
        } catch(_:Exception){ if(data.startsWith("http")) listOf(JSONObject().put("url",data.toAbs())) else emptyList() }
        var found=false
        val seen=linkedSetOf<String>()
        links.forEach{ obj->
            val url=obj.optString("url").toAbs(); if(url.isBlank() || !seen.add(url)) return@forEach
            if(url.contains(".m3u8",true)){
                M3u8Helper.generateM3u8(FTPBDCore.buildSourceName(obj.optString("label"),url,seen.size), url, mainUrl, headers=headers).forEach(callback); found=true
            } else if(url.contains(".mp4",true)||url.contains(".mkv",true)){
                callback(newExtractorLink(FTPBDCore.buildSourceName(obj.optString("label"),url,seen.size), FTPBDCore.buildSourceName(obj.optString("label"),url,seen.size), url){ quality=getQualityFromName(url) }); found=true
            } else {
                val html=FTPBDCore.retry{ app.get(url, headers=headers, timeout=15_000).text } ?: return@forEach
                Regex("""(?i)<source[^>]+src=['"]([^'"]+?\.m3u8[^'"]*)['"]""").findAll(html).forEach{ m->
                    val u=m.groupValues[1].toAbs(); if(seen.add(u)){ M3u8Helper.generateM3u8(FTPBDCore.buildSourceName(null,u,seen.size), u, mainUrl).forEach(callback); found=true }
                }
                runCatching{ loadExtractor(url, mainUrl, subtitleCallback){ callback(it); found=true } }
            }
        }
        // StreamPlay torrentio fallback
        val imdbId=links.firstOrNull()?.optString("imdbId")?.takeIf{it.startsWith("tt")}
        if(imdbId!=null){
            val torrents=FTPBDCore.retry{ app.get("https://torrentio.strem.fun/stream/movie/$imdbId.json", timeout=10_000).text }?.let{ runCatching{ JSONObject(it).optJSONArray("streams") }.getOrNull() }
            torrents?.let{
                for(i in 0 until it.length()){
                    val st=it.optJSONObject(i) ?: continue
                    val u=st.optString("url").ifBlank{continue}
                    if(seen.add(u)){ callback(newExtractorLink("Torrentio", st.optString("title","Torrentio"), u){ quality=getQualityFromName(st.optString("title","")) }); found=true }
                }
            }
        }
        return found
    }
}
