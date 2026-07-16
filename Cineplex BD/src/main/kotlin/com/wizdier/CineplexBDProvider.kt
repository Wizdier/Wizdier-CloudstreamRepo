package com.wizdier

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// ─────────────────────────────────────────────────────────────────────────────
// CineplexBD – Robust V4 with TMDB + AniList enrichments + StreamPlay MultiAPI
// StreamPlay pattern: unlimited stremio addon links via settings, catalog+stream dual, torrent fallback
// Here we implement dual enrichment: TMDB for movies/tv, AniList for anime, fallback to TMDB
// ─────────────────────────────────────────────────────────────────────────────

internal object CineplexCore {
    const val PAR = 8
    suspend fun <T,R> parallelMapNotNull(items:List<T>, concurrency:Int=PAR, fetch:suspend(T)->R?): List<R>{
        if(items.isEmpty()) return emptyList()
        val gate=Semaphore(concurrency.coerceAtLeast(1))
        return coroutineScope{ items.map{ async{ try{ gate.withPermit{ fetch(it) } } catch(e:CancellationException){ throw e } catch(_:Throwable){ null } } }.awaitAll().filterNotNull() }
    }
    suspend fun <T> retry(max:Int=3, delayMs:Long=400, block:suspend()->T): T?{
        var b=delayMs
        repeat(max){ att->
            try{ return block() } catch(ce:CancellationException){ throw ce } catch(_:Throwable){
                if(att==max-1) return null
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
    fun buildSourceName(label:String?, url:String, idx:Int)="""${if(!label.isNullOrBlank()) label else runCatching{ java.net.URI(url).host?.substringBefore('.')?.replaceFirstChar{it.uppercase()} }.getOrNull() ?: "Source $idx"}"""
    fun String.encodeUrl()=URLEncoder.encode(this,"UTF-8")
    fun String.toAbs(base:String)=if(startsWith("http")) this else base.trimEnd('/') + "/" + trimStart('/')
}

// ── Dual Enrichment: TMDB + AniList (StreamPlay MultiAPI inspired) ──
internal object WizEnrich {
    private const val TMDB_API="https://api.themoviedb.org/3"
    private const val TMDB_KEY="98ae14df2b8d8f8f8136499daf79f0e0"
    private const val IMG="https://image.tmdb.org/t/p"
    private val tmdbCache=CineplexCore.TtlCache<String, EnrichedMeta>(ttlMs=10*60*1000L)
    private val anilistCache=CineplexCore.TtlCache<String, EnrichedMeta>(ttlMs=10*60*1000L)

    data class EnrichedMeta(
        val title:String?=null,
        val origTitle:String?=null,
        val plot:String?=null,
        val poster:String?=null,
        val backdrop:String?=null,
        val logo:String?=null,
        val trailer:String?=null,
        val year:Int?=null,
        val rating:Double?=null,
        val tags:List<String>?=null,
        val imdbId:String?=null,
        val tmdbId:Int?=null,
        val anilistId:Int?=null,
        val malId:Int?=null,
        val kitsuId:String?=null,
        val actors:List<ActorData>?=null,
        val isAnime:Boolean=false
    )

    private fun poster(p:String?, size:String="w500")=p?.takeIf{it.isNotBlank()&&it!="null"}?.let{"$IMG/$size$it"}
    private suspend fun safeJson(url:String): JSONObject? = CineplexCore.retry{ JSONObject(app.get(url, timeout=8000).text) }

    // TMDB Movie
    suspend fun tmdbMovie(title:String, year:Int?): EnrichedMeta?{
        val key="tmdb_movie_${title.lowercase()}_$year"
        tmdbCache.get(key)?.let{return it}
        val q=title.encodeUrl()
        val search=safeJson("$TMDB_API/search/movie?api_key=$TMDB_KEY&query=$q${year?.let{"&year=$it"}?:""}") ?: return null
        val first=search.optJSONArray("results")?.optJSONObject(0) ?: return null
        val id=first.optInt("id",0); if(id==0) return null
        val detail=safeJson("$TMDB_API/movie/$id?api_key=$TMDB_KEY&append_to_response=images,videos,external_ids,credits") ?: return null
        val meta=EnrichedMeta(
            title=detail.optString("title").ifBlank{first.optString("title")},
            plot=detail.optString("overview").takeIf{it.isNotBlank()},
            poster=poster(detail.optString("poster_path")),
            backdrop=poster(detail.optString("backdrop_path"),"original"),
            year=detail.optString("release_date").take(4).toIntOrNull() ?: year,
            rating=detail.optDouble("vote_average",0.0).takeIf{it>0},
            tags=detail.optJSONArray("genres")?.let{a-> (0 until a.length()).map{a.getJSONObject(it).getString("name")} },
            imdbId=detail.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf{it.startsWith("tt")},
            tmdbId=id,
            isAnime=false
        )
        tmdbCache.put(key, meta); return meta
    }

    // TMDB TV
    suspend fun tmdbTv(title:String): EnrichedMeta?{
        val key="tmdb_tv_${title.lowercase()}"
        tmdbCache.get(key)?.let{return it}
        val q=title.encodeUrl()
        val search=safeJson("$TMDB_API/search/tv?api_key=$TMDB_KEY&query=$q") ?: return null
        val first=search.optJSONArray("results")?.optJSONObject(0) ?: return null
        val id=first.optInt("id",0); if(id==0) return null
        val detail=safeJson("$TMDB_API/tv/$id?api_key=$TMDB_KEY&append_to_response=images,videos,external_ids") ?: return null
        val meta=EnrichedMeta(
            title=detail.optString("name").ifBlank{first.optString("name")},
            plot=detail.optString("overview").takeIf{it.isNotBlank()},
            poster=poster(detail.optString("poster_path")),
            backdrop=poster(detail.optString("backdrop_path"),"original"),
            year=detail.optString("first_air_date").take(4).toIntOrNull(),
            rating=detail.optDouble("vote_average",0.0).takeIf{it>0},
            tags=detail.optJSONArray("genres")?.let{a-> (0 until a.length()).map{a.getJSONObject(it).getString("name")} },
            imdbId=detail.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf{it.startsWith("tt")},
            tmdbId=id,
            isAnime=false
        )
        tmdbCache.put(key, meta); return meta
    }

    // AniList – GraphQL (like CircleFTP fetchMetadata anime branch + CSX convertTmdbToAnimeId)
    suspend fun anilist(title:String): EnrichedMeta?{
        val key="anilist_${title.lowercase()}"
        anilistCache.get(key)?.let{return it}
        val query="""
            query (${'$'}search: String) {
              Page(page: 1, perPage: 5) {
                media(search: ${'$'}search, type: ANIME) {
                  id
                  idMal
                  title { romaji english native }
                  coverImage { extraLarge large }
                  bannerImage
                  description
                  averageScore
                  genres
                  episodes
                }
              }
            }
        """.trimIndent()
        val variables=JSONObject().put("search", title)
        val body=JSONObject().put("query", query).put("variables", variables)
        val resText=CineplexCore.retry{
            app.post("https://graphql.anilist.co", requestBody=body.toString().toRequestBody("application/json".toMediaTypeOrNull()), timeout=10_000).text
        } ?: return null
        val media=runCatching{
            JSONObject(resText).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")?.optJSONObject(0)
        }.getOrNull() ?: return null
        val eng=media.optJSONObject("title")?.optString("english")?.takeIf{it.isNotBlank()}
        val rom=media.optJSONObject("title")?.optString("romaji")
        val nat=media.optJSONObject("title")?.optString("native")
        val cover=media.optJSONObject("coverImage")?.optString("extraLarge") ?: media.optJSONObject("coverImage")?.optString("large")
        val banner=media.optString("bannerImage").takeIf{it.isNotBlank()}
        val desc=media.optString("description").replace(Regex("<[^>]*>"),"").takeIf{it.isNotBlank()}
        val score=media.optInt("averageScore",0).takeIf{it>0}?.let{it/10.0}
        val genres=media.optJSONArray("genres")?.let{a-> (0 until a.length()).map{a.getString(it)} }
        val aniId=media.optInt("id",0).takeIf{it!=0}
        val malId=media.optInt("idMal",0).takeIf{it!=0}
        val meta=EnrichedMeta(
            title=eng ?: rom ?: nat ?: title,
            origTitle=rom,
            plot=desc,
            poster=cover,
            backdrop=banner,
            year=null,
            rating=score,
            tags=genres,
            anilistId=aniId,
            malId=malId,
            isAnime=true
        )
        anilistCache.put(key, meta); return meta
    }

    // Unified enrich – tries anime detection then both
    suspend fun enrich(rawTitle:String, year:Int?, isSeries:Boolean): EnrichedMeta{
        val isAnimeHint=rawTitle.lowercase().let{ t-> listOf("anime","one piece","naruto","bleach","jujutsu","demon slayer","attack on titan","solo leveling").any{t.contains(it)} } || rawTitle.contains("season",true) && rawTitle.length<40 && rawTitle.lowercase().contains("anime")
        return if(isAnimeHint || isSeries && rawTitle.lowercase().contains("anime")){
            anilist(rawTitle) ?: tmdbTv(rawTitle) ?: tmdbMovie(rawTitle, year) ?: EnrichedMeta(title=rawTitle, year=year, isAnime=true)
        } else {
            // For movies/tv, try TMDB first, fallback to AniList for cartoon check
            tmdbMovie(rawTitle, year) ?: tmdbTv(rawTitle) ?: anilist(rawTitle) ?: EnrichedMeta(title=rawTitle, year=year)
        }
    }

    private fun String.encodeUrl()=URLEncoder.encode(this,"UTF-8")
}

// ── StreamPlay MultiAPI: Stremio Addon aggregator (like phisher98 StreamPlay) ──
internal object WizStremio {
    // Format: user provides addon base URLs like https://torrentio.strem.fun/...
    // We query /stream/{type}/{imdbId}.json or /stream/{type}/{imdbId}:{s}:{e}.json
    data class StremioStream(val title:String, val url:String)

    suspend fun fetchStreams(imdbId:String?, type:String, season:Int?, episode:Int?, addons:List<String>): List<StremioStream>{
        if(imdbId.isNullOrBlank() || addons.isEmpty()) return emptyList()
        val sId=when{
            type=="movie" -> imdbId
            season!=null && episode!=null -> "$imdbId:$season:$episode"
            else -> imdbId
        }
        return CineplexCore.parallelMapNotNull(addons, concurrency=4){ base->
            try{
                val cleanBase=base.trim().trimEnd('/')
                val url="$cleanBase/stream/$type/$sId.json"
                val json=CineplexCore.retry{ app.get(url, timeout=10_000).text } ?: return@parallelMapNotNull null
                val obj=JSONObject(json)
                val streams=obj.optJSONArray("streams") ?: return@parallelMapNotNull null
                (0 until streams.length()).mapNotNull{ i->
                    val st=streams.optJSONObject(i) ?: return@mapNotNull null
                    val u=st.optString("url").ifBlank{return@mapNotNull null}
                    val t=st.optString("title","Stremio")
                    StremioStream(t, u)
                }
            } catch(_:Exception){ null }
        }.flatten()
    }
}

class CineplexBD : MainAPI() {
    override var mainUrl = "http://cineplexbd.net"
    override var name = "Cineplex BD"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.Cartoon)

    private val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36", "Referer" to "$mainUrl/")
    private val mainPageCache=CineplexCore.TtlCache<String, List<SearchResponse>>()

    override val mainPage = mainPageOf(
        "$mainUrl/search.php?q=&year[]=2026&year[]=2025&page=" to "Latest (2025-2026)",
        "$mainUrl/category.php?category=English&page=" to "English Movies",
        "$mainUrl/category.php?category=Hindi&page=" to "Hindi Movies",
        "$mainUrl/category.php?category=Korean&page=" to "Korean Movies",
        "$mainUrl/category.php?category=Anime&page=" to "Anime",
        "$mainUrl/category.php?category=Animation&page=" to "Animation",
        "$mainUrl/tcategory.php?category=Web%20Series&page=" to "Web Series",
        "$mainUrl/tcategory.php?category=Korean%20Series&page=" to "Korean Series",
    )

    private data class LinkItem(val url:String, val label:String?)
    private data class GroupedItem(val url:String, val label:String?)

    override suspend fun getMainPage(page:Int, request:MainPageRequest): HomePageResponse{
        val cacheKey="${request.data}_$page"
        mainPageCache.get(cacheKey)?.let{ return newHomePageResponse(HomePageList(request.name, it), it.isNotEmpty()) }
        val url=request.data+page
        val doc=CineplexCore.retry{ app.get(url, headers=headers, timeout=10_000).document } ?: return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        val items=parseAndGroup(doc).let{ list-> if(request.name.startsWith("Latest")) list.filterNot{ it.name.lowercase().let{t-> t.contains("bangla")||t.contains("pakistani")}} else list }
        mainPageCache.put(cacheKey, items)
        val hasNext=doc.select("ul.pagination li.active + li a, a:contains(Next)").isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, items), hasNext)
    }

    override suspend fun search(query:String): List<SearchResponse>{
        if(query.isBlank()) return emptyList()
        return coroutineScope{
            val q=query.trim()
            val a=async{ runCatching{ searchOne("$mainUrl/search.php?q=${q.encodeUrl()}&page=1") }.getOrDefault(emptyList()) }
            val b=async{ runCatching{ searchOne("$mainUrl/search.php?q=${q.encodeUrl()}&year[]=2025&page=1") }.getOrDefault(emptyList()) }
            (a.await()+b.await()).distinctBy{it.url}
        }
    }
    private suspend fun searchOne(url:String): List<SearchResponse>{
        val doc=CineplexCore.retry{ app.get(url, headers=headers, timeout=10_000).document } ?: return emptyList()
        return parseAndGroup(doc)
    }

    private fun parseAndGroup(doc:Document): List<SearchResponse>{
        val map=linkedMapOf<String, MutableList<LinkItem>>()
        doc.select("div.movie-card, a[href*='/movie/'], a[href*='/tv/'], .tvCard, a[href*='watch.php']").forEach{ el->
            val a=if(el.tagName()=="a") el else el.selectFirst("a") ?: return@forEach
            val href=a.attr("href"); if(href.isBlank()) return@forEach
            val title=a.attr("title").ifBlank{a.text()}.trim(); if(title.isBlank()) return@forEach
            val clean=title.replace(Regex("""\s*\(\d{4}\)|\s*\[.*?]|\s*\d{3,4}p""",RegexOption.IGNORE_CASE),"").trim()
            val key=clean.lowercase()
            map.getOrPut(key){ mutableListOf() }.add(LinkItem(href, clean))
        }
        return map.values.mapNotNull{ group->
            if(group.isEmpty()) return@mapNotNull null
            val first=group.first()
            val type=if(first.url.contains("watch.php")||first.url.contains("/tv/")) TvType.TvSeries else TvType.Movie
            if(group.size==1){
                if(type==TvType.TvSeries) newTvSeriesSearchResponse(first.label?:first.url, first.url, type){} else newMovieSearchResponse(first.label?:first.url, first.url, type){}
            } else {
                val arr=JSONArray(); group.forEach{ arr.put(JSONObject().put("url",it.url).put("label",it.label)) }
                val b64=Base64.getEncoder().encodeToString(arr.toString().toByteArray())
                newMovieSearchResponse(first.label?:first.url, "$mainUrl/group?data=$b64", type){}
            }
        }
    }

    override suspend fun load(url:String): LoadResponse?{
        if(url.contains("/group?data=")){
            val b64=url.substringAfter("data=").substringBefore("&")
            val arr=runCatching{ JSONArray(String(Base64.getDecoder().decode(b64))) }.getOrNull() ?: return null
            val links=(0 until arr.length()).mapNotNull{ arr.optJSONObject(it)?.let{ o-> GroupedItem(o.optString("url"), o.optString("label").takeIf{it.isNotBlank()}) } }
            return loadGrouped(links)
        }
        val abs=if(url.startsWith("http")) url else mainUrl+url
        val doc=CineplexCore.retry{ app.get(abs, headers=headers, timeout=10_000).document } ?: return null
        val isSeries=abs.contains("watch.php")
        return if(isSeries) loadSeries(abs, doc, listOf(GroupedItem(abs,null))) else loadMovie(abs, doc, listOf(GroupedItem(abs,null)))
    }

    private suspend fun loadGrouped(groups:List<GroupedItem>): LoadResponse?{
        val loaded=CineplexCore.parallelMapNotNull(groups.distinctBy{it.url}, concurrency=6){ g->
            runCatching{
                val abs=if(g.url.startsWith("http")) g.url else mainUrl+g.url
                val d=app.get(abs, headers=headers, timeout=10_000).document
                Triple(abs,d,g.label)
            }.getOrNull()
        }
        if(loaded.isEmpty()) return null
        val primary=loaded.first()
        return if(primary.first.contains("watch.php")) loadSeries(primary.first, primary.second, groups) else loadMovie(primary.first, primary.second, groups)
    }

    private suspend fun loadMovie(url:String, doc:Document, all:List<GroupedItem>): LoadResponse?{
        val raw=doc.selectFirst("h1,.movie-title,title")?.text()?.replace(" — Watch","")?.trim() ?: "Unknown"
        val year=Regex("""\d{4}""").find(doc.text())?.value?.toIntOrNull()
        val enriched=CineplexCore.retry{ WizEnrich.enrich(raw, year, false) } ?: WizEnrich.EnrichedMeta(title=raw, year=year)
        val poster=enriched.poster ?: doc.selectFirst("img.poster,.tvCard img,.movie-poster img")?.let{ it.attr("data-src").ifBlank{it.attr("src")} }?.let{ if(it.startsWith("http")) it else "$mainUrl/${it.trimStart('/')}"}
        val plot=enriched.plot ?: doc.selectFirst("p.leading-relaxed,#synopsis,.description")?.text()
        val playerLinks=all.mapNotNull{
            val abs=if(it.url.startsWith("http")) it.url else mainUrl+it.url
            val id=abs.substringAfter("id=").substringBefore("&").takeIf{s->s.isNotBlank()&&s!=abs} ?: return@mapNotNull null
            LinkItem("/player.php?id=$id", it.label)
        }.ifEmpty{ listOf(LinkItem(url,null)) }
        val dataUrl=if(playerLinks.size>1){
            val arr=JSONArray(); playerLinks.forEach{ arr.put(JSONObject().put("url",it.url).put("label",it.label).put("imdbId", enriched.imdbId).put("anilistId", enriched.anilistId)) }
            Base64.getEncoder().encodeToString(arr.toString().toByteArray())
        } else {
            val obj=JSONObject().put("url", playerLinks.first().url).put("imdbId", enriched.imdbId).put("anilistId", enriched.anilistId)
            Base64.getEncoder().encodeToString(obj.toString().toByteArray())
        }
        val isAnime=enriched.isAnime
        return newMovieLoadResponse(enriched.title ?: raw, url, if(isAnime) TvType.AnimeMovie else TvType.Movie, dataUrl){
            this.posterUrl=poster
            backgroundPosterUrl=enriched.backdrop
            this.plot=plot
            this.year=enriched.year ?: year
            enriched.rating?.let{ score=Score.from10(it) }
            enriched.tags?.let{ tags=it }
            enriched.trailer?.let{ addTrailer(it) }
            enriched.imdbId?.let{ addImdbId(it) }
            enriched.anilistId?.let{ addAniListId(it) }
            enriched.malId?.let{ addMalId(it) }
        }
    }

    private suspend fun loadSeries(url:String, doc:Document, all:List<GroupedItem>): LoadResponse?{
        val raw=doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val enriched=CineplexCore.retry{ WizEnrich.enrich(raw, null, true) } ?: WizEnrich.EnrichedMeta(title=raw, isAnime=false)
        val title=enriched.title ?: raw
        val epEls=doc.select("a[href*='episode'], .episode-list a, .episodes a")
        val episodes: List<Episode> = if(epEls.isNotEmpty()){
            CineplexCore.parallelMapNotNull(epEls.toList(), concurrency=8){ el->
                val href=el.attr("href"); val name=el.text().trim().ifBlank{"Episode"}
                val num=Regex("""\d+""").find(name)?.value?.toIntOrNull() ?: 1
                val obj=JSONObject().put("url", if(href.startsWith("http")) href else mainUrl+href).put("imdbId", enriched.imdbId).put("anilistId", enriched.anilistId).put("season",1).put("episode",num)
                val b64=Base64.getEncoder().encodeToString(obj.toString().toByteArray())
                newEpisode(b64){ this.name=name; season=1; episode=num }
            }
        } else {
            listOf(newEpisode(Base64.getEncoder().encodeToString(JSONObject().put("url",url).put("imdbId",enriched.imdbId).toString().toByteArray())){ name="Episode 1"; season=1; episode=1 })
        }
        return newTvSeriesLoadResponse(title, url, if(enriched.isAnime) TvType.Anime else TvType.TvSeries, episodes){
            posterUrl=enriched.poster
            backgroundPosterUrl=enriched.backdrop
            plot=enriched.plot
            year=enriched.year
            enriched.rating?.let{ score=Score.from10(it) }
            enriched.tags?.let{ tags=it }
            enriched.imdbId?.let{ addImdbId(it) }
            enriched.anilistId?.let{ addAniListId(it) }
            enriched.malId?.let{ addMalId(it) }
        }
    }

    override suspend fun loadLinks(data:String, isCasting:Boolean, subtitleCallback:(SubtitleFile)->Unit, callback:(ExtractorLink)->Unit): Boolean {
        // data is base64 JSON with url + imdbId + anilistId
        val links:List<JSONObject> = runCatching{
            val txt=runCatching{ String(Base64.getDecoder().decode(data)) }.getOrElse{ data }
            if(txt.trim().startsWith("[")){
                val arr=JSONArray(txt); (0 until arr.length()).map{ arr.getJSONObject(it) }
            } else if(txt.trim().startsWith("{")){
                listOf(JSONObject(txt))
            } else listOf(JSONObject().put("url", txt))
        }.getOrDefault(listOf(JSONObject().put("url", data)))

        var found=false
        val seen=linkedSetOf<String>()
        // Local links
        for(obj in links){
            val rawUrl=obj.optString("url"); if(rawUrl.isBlank()) continue
            val abs=if(rawUrl.startsWith("http")) rawUrl else mainUrl+rawUrl
            if(!seen.add(abs)) continue
            val label=obj.optString("label").takeIf{it.isNotBlank()}
            if(abs.contains(".m3u8",true)){
                M3u8Helper.generateM3u8(CineplexCore.buildSourceName(label, abs, seen.size), abs, mainUrl, headers=headers).forEach(callback); found=true
            } else {
                val html=CineplexCore.retry{ app.get(abs, headers=headers, timeout=15_000).text } ?: continue
                val doc=org.jsoup.Jsoup.parse(html, abs)
                doc.select("source[src*=.m3u8]").forEach{ el->
                    val src=el.attr("src"); if(src.isNotBlank()){
                        val u=if(src.startsWith("http")) src else abs.substringBeforeLast('/') + "/" + src
                        if(seen.add(u)){ M3u8Helper.generateM3u8(CineplexCore.buildSourceName(label,u,seen.size), u, mainUrl, headers=headers).forEach(callback); found=true }
                    }
                }
                if(!found){
                    doc.select("a[href*=.mp4], a[href*=.mkv]").forEach{ el->
                        val href=el.attr("href"); if(href.isNotBlank()){
                            val u=if(href.startsWith("http")) href else mainUrl+href
                            if(seen.add(u)){ callback(newExtractorLink(CineplexCore.buildSourceName(label,u,seen.size), CineplexCore.buildSourceName(label,u,seen.size), u){ quality=getQualityFromName(u) }); found=true }
                        }
                    }
                    runCatching{ loadExtractor(abs, mainUrl, subtitleCallback){ callback(it); found=true } }
                }
            }
        }

        // StreamPlay MultiAPI: try stremio addons if we have imdbId
        val first=links.firstOrNull()
        val imdbId=first?.optString("imdbId")?.takeIf{it.startsWith("tt")}
        val anilistId=first?.optInt("anilistId",0)?.takeIf{it!=0}
        val season=first?.optInt("season",1)
        val episode=first?.optInt("episode",1)
        val isSeries=season!=null

        // Default StreamPlay addons (like phisher98 StreamPlay uses torrentio + others)
        val defaultAddons=listOf(
            "https://torrentio.strem.fun/limit=4",
            "https://torrentsdb.com/eyJsaW1pdCI6IjMiLCJkZWJyaWRvcHRpb25zIjpbIm5vZG93bmxvYWRsaW5rcyJdfQ=="
        )
        if(imdbId!=null){
            val stremioStreams=WizStremio.fetchStreams(imdbId, if(isSeries) "series" else "movie", season, episode, defaultAddons)
            stremioStreams.forEach{ s->
                if(seen.add(s.url)){
                    callback(newExtractorLink("Stremio ${s.title}", s.title, s.url){ quality=getQualityFromName(s.title) })
                    found=true
                }
            }
        }

        return found
    }
}
