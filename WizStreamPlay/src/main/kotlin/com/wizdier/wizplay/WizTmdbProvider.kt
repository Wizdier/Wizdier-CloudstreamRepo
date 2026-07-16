package com.wizdier.wizplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * WizPlay TMDB Provider – one half of dual extension like StreamPlay
 * TMDB = movies/tv catalog, AniList = anime catalog (second provider)
 * MultiAPI: fetches all sources present in user's extensions + vid[x] src,nest,rock,fast,easy,zee,prime
 */

class WizTmdbProvider : MainAPI() {
    override var mainUrl = "https://www.themoviedb.org"
    override var name = "WizPlay - TMDB"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama, TvType.Documentary)

    private val apiKey = "98ae14df2b8d8f8f8136499daf79f0e0"
    private val apiUrl = "https://api.themoviedb.org/3"
    private val mainPageCache = WizCore.TtlCache<String, List<SearchResponse>>()

    override val mainPage = mainPageOf(
        "trending/all/day?api_key=$apiKey" to "Trending Today",
        "trending/movie/week?api_key=$apiKey" to "Popular Movies",
        "trending/tv/week?api_key=$apiKey" to "Popular TV Shows",
        "movie/now_playing?api_key=$apiKey" to "Now Playing Movies",
        "tv/airing_today?api_key=$apiKey" to "Airing Today TV",
        "discover/movie?api_key=$apiKey&with_original_language=bn" to "Bangla Trending",
        "discover/movie?api_key=$apiKey&with_original_language=hi" to "Hindi Trending",
        "discover/tv?api_key=$apiKey&with_original_language=ko" to "Korean Dramas",
    )

    data class Data(val id:Int, val type:String, val title:String?=null, val year:Int?=null, val imdbId:String?=null, val anilistId:Int?=null)

    data class LinkData(
        val tmdbId:Int,
        val imdbId:String?,
        val title:String,
        val year:Int?,
        val season:Int?,
        val episode:Int?,
        val type:String,
        val isAnime:Boolean,
        val anilistId:Int?=null
    )

    private fun getImageUrl(path:String?): String?{
        if(path==null) return null
        return if(path.startsWith("/")) "https://image.tmdb.org/t/p/w500$path" else path
    }

    override suspend fun getMainPage(page:Int, request:MainPageRequest): HomePageResponse {
        val cacheKey="${request.data}_$page"
        mainPageCache.get(cacheKey)?.let{ return newHomePageResponse(request.name, it, it.isNotEmpty()) }
        val res=WizCore.retry{ app.get("$apiUrl/${request.data}&page=$page", timeout=10_000).text } ?: return newHomePageResponse(request.name, emptyList())
        val json=runCatching{ JSONObject(res) }.getOrNull() ?: return newHomePageResponse(request.name, emptyList())
        val arr=json.optJSONArray("results") ?: return newHomePageResponse(request.name, emptyList())
        val list=(0 until arr.length()).mapNotNull{ i->
            val obj=arr.optJSONObject(i) ?: return@mapNotNull null
            val id=obj.optInt("id",0); if(id==0) return@mapNotNull null
            val title=obj.optString("title").ifBlank{obj.optString("name")}.ifBlank{return@mapNotNull null}
            val type=if(obj.has("title")) "movie" else "tv"
            val poster=getImageUrl(obj.optString("poster_path"))
            newMovieSearchResponse(title, Data(id,type,title,null,null,null).toJson(), if(type=="movie") TvType.Movie else TvType.TvSeries){ this.posterUrl=poster }
        }
        mainPageCache.put(cacheKey, list)
        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query:String): List<SearchResponse>{
        if(query.isBlank()) return emptyList()
        // Fan-out like CSX CineStreamProvider.search – movie + tv concurrent
        return coroutineScope{
            val movie=async{
                val res=WizCore.retry{ app.get("$apiUrl/search/movie?api_key=$apiKey&query=${query.encodeUrl()}", timeout=10_000).text } ?: return@async emptyList<SearchResponse>()
                val arr=runCatching{ JSONObject(res).optJSONArray("results") }.getOrNull() ?: return@async emptyList()
                (0 until arr.length()).mapNotNull{ i->
                    val obj=arr.optJSONObject(i) ?: return@mapNotNull null
                    val id=obj.optInt("id",0); if(id==0) return@mapNotNull null
                    val title=obj.optString("title").ifBlank{return@mapNotNull null}
                    newMovieSearchResponse(title, Data(id,"movie",title,null,null,null).toJson(), TvType.Movie){ posterUrl=getImageUrl(obj.optString("poster_path")) }
                }
            }
            val tv=async{
                val res=WizCore.retry{ app.get("$apiUrl/search/tv?api_key=$apiKey&query=${query.encodeUrl()}", timeout=10_000).text } ?: return@async emptyList<SearchResponse>()
                val arr=runCatching{ JSONObject(res).optJSONArray("results") }.getOrNull() ?: return@async emptyList()
                (0 until arr.length()).mapNotNull{ i->
                    val obj=arr.optJSONObject(i) ?: return@mapNotNull null
                    val id=obj.optInt("id",0); if(id==0) return@mapNotNull null
                    val title=obj.optString("name").ifBlank{return@mapNotNull null}
                    newMovieSearchResponse(title, Data(id,"tv",title,null,null,null).toJson(), TvType.TvSeries){ posterUrl=getImageUrl(obj.optString("poster_path")) }
                }
            }
            (movie.await()+tv.await()).distinctBy{it.url}
        }
    }

    private fun String.encodeUrl()=java.net.URLEncoder.encode(this,"UTF-8")

    override suspend fun load(url:String): LoadResponse?{
        val data=parseJson<Data>(url)
        val type=data.type
        val id=data.id
        val append="credits,external_ids,videos,images"
        val resUrl="$apiUrl/${if(type=="movie") "movie" else "tv"}/$id?api_key=$apiKey&append_to_response=$append"
        val jsonTxt=WizCore.retry{ app.get(resUrl, timeout=10_000).text } ?: return null
        val obj=JSONObject(jsonTxt)
        val title=obj.optString("title").ifBlank{obj.optString("name")}.ifBlank{return null}
        val poster=getImageUrl(obj.optString("poster_path"))
        val backdrop=getImageUrl(obj.optString("backdrop_path"))
        val plot=obj.optString("overview")
        val year=(obj.optString("release_date").ifBlank{obj.optString("first_air_date")}).take(4).toIntOrNull()
        val imdbId=obj.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf{it.startsWith("tt")}
        val isAnime=obj.optJSONArray("genres")?.let{a-> (0 until a.length()).any{a.getJSONObject(it).optString("name").contains("Animation",true)} } ?: false

        // Dual enrichment: if anime detected, also fetch AniList for extra IDs
        var anilistId:Int?=null
        var malId:Int?=null
        if(isAnime){
            val aniMeta=WizCore.retry{ WizEnrich.anilist(title) }
            anilistId=aniMeta?.anilistId
            malId=aniMeta?.malId
        }

        val trailer=obj.optJSONObject("videos")?.optJSONArray("results")?.let{a-> (0 until a.length()).firstNotNullOfOrNull{i-> val v=a.getJSONObject(i); if(v.optString("type")=="Trailer" && v.optString("site")=="YouTube") "https://www.youtube.com/watch?v=${v.optString("key")}" else null } }

        return if(type=="movie"){
            val linkData=LinkData(id, imdbId, title, year, null, null, "movie", isAnime, anilistId).toJson()
            newMovieLoadResponse(title, url, if(isAnime) TvType.AnimeMovie else TvType.Movie, linkData){
                this.posterUrl=poster; this.backgroundPosterUrl=backdrop; this.plot=plot; this.year=year
                obj.optDouble("vote_average",0.0).takeIf{it>0}?.let{ score=Score.from10(it) }
                this.tags=obj.optJSONArray("genres")?.let{a-> (0 until a.length()).map{a.getJSONObject(it).getString("name")} }
                trailer?.let{ addTrailer(it) }
                imdbId?.let{ addImdbId(it) }
                anilistId?.let{ addAniListId(it) }
                malId?.let{ addMalId(it) }
            }
        } else {
            // TV – fetch seasons episodes with parallelMapNotNull (robust pattern)
            val seasonsCount=minOf(obj.optInt("number_of_seasons",1), 15)
            val episodes=mutableListOf<Episode>()
            val seasonsData=WizCore.parallelMapNotNull((1..seasonsCount).toList(), concurrency=4){ sNum->
                val seasonJson=WizCore.retry{ app.get("$apiUrl/tv/$id/season/$sNum?api_key=$apiKey", timeout=10_000).text } ?: return@parallelMapNotNull null
                val seasonObj=runCatching{ JSONObject(seasonJson) }.getOrNull() ?: return@parallelMapNotNull null
                val eps=seasonObj.optJSONArray("episodes") ?: return@parallelMapNotNull null
                sNum to eps
            }
            for((sNum, epsArr) in seasonsData){
                for(j in 0 until epsArr.length()){
                    val ep=epsArr.getJSONObject(j)
                    val epNum=ep.optInt("episode_number", j+1)
                    val linkData=LinkData(id, imdbId, title, year, sNum, epNum, "tv", isAnime, anilistId).toJson()
                    episodes.add(newEpisode(linkData){
                        name=ep.optString("name","Episode $epNum"); season=sNum; episode=epNum; posterUrl=getImageUrl(ep.optString("still_path")); description=ep.optString("overview")
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, if(isAnime) TvType.Anime else TvType.TvSeries, episodes){
                this.posterUrl=poster; this.backgroundPosterUrl=backdrop; this.plot=plot; this.year=year
                obj.optDouble("vote_average",0.0).takeIf{it>0}?.let{ score=Score.from10(it) }
                this.tags=obj.optJSONArray("genres")?.let{a-> (0 until a.length()).map{a.getJSONObject(it).getString("name")} }
                imdbId?.let{ addImdbId(it) }
                anilistId?.let{ addAniListId(it) }
                malId?.let{ addMalId(it) }
            }
        }
    }

    override suspend fun loadLinks(data:String, isCasting:Boolean, subtitleCallback:(SubtitleFile)->Unit, callback:(ExtractorLink)->Unit): Boolean {
        val linkData=parseJson<LinkData>(data)
        val loadData=WizExtractors.LoadData(
            title=linkData.title,
            year=linkData.year,
            tmdbId=linkData.tmdbId,
            imdbId=linkData.imdbId,
            season=linkData.season,
            episode=linkData.episode,
            isAnime=linkData.isAnime,
            anilistId=linkData.anilistId,
            malId=null
        )

        var found=false

        // 1. Parallel vid[x] sources – src,nest,rock,fast,easy,zee,prime + your 5 extensions
        coroutineScope{
            ProviderRegistry.builtInProviders.map{ def->
                async{
                    try{
                        def.execute(loadData, subtitleCallback){ link->
                            callback.invoke(link); found=true
                        }
                    } catch(_:Throwable){}
                }
            }.awaitAll()
        }

        // 2. Stremio MultiAPI aggregator like StreamPlay (torrentio + mediafusion etc)
        if(linkData.imdbId!=null){
            val addons=WizStremio.defaultAddons
            val stremioFound=WizStremio.fetchStreams(linkData.imdbId, if(linkData.type=="movie") "movie" else "series", linkData.season, linkData.episode, addons, callback)
            if(stremioFound) found=true
        }

        return found
    }
}
