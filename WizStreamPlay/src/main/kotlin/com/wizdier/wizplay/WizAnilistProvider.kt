package com.wizdier.wizplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.DubStatus
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * WizPlay AniList Provider – second half of dual extension like StreamPlay's StremioC
 * Uses AniList GraphQL for catalog/search/load (anime focused)
 * MultiAPI: same vid[x] sources + your extensions + stremio aggregator
 */

class WizAnilistProvider : MainAPI() {
    override var mainUrl = "https://anilist.co"
    override var name = "WizPlay - AniList"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val anilistApi = "https://graphql.anilist.co"
    private val mainPageCache = WizCore.TtlCache<String, List<SearchResponse>>()

    data class AnilistData(val id:Int, val idMal:Int?, val title:String, val isMovie:Boolean)
    data class LinkData(
        val anilistId:Int,
        val malId:Int?,
        val title:String,
        val episode:Int?,
        val tmdbId:Int?,
        val imdbId:String?,
        val season:Int?=1
    )

    override val mainPage = mainPageOf(
        "trending" to "Trending Anime",
        "popular" to "Popular Anime",
        "top" to "Top Rated Anime",
        "airing" to "Airing Now",
        "movie" to "Anime Movies"
    )

    private suspend fun anilistQuery(query:String, variables:JSONObject): JSONObject?{
        val body=JSONObject().put("query", query).put("variables", variables)
        val txt=WizCore.retry{
            app.post(anilistApi, requestBody=body.toString().toRequestBody("application/json".toMediaTypeOrNull()), timeout=10_000).text
        } ?: return null
        return runCatching{ JSONObject(txt) }.getOrNull()
    }

    override suspend fun getMainPage(page:Int, request:MainPageRequest): HomePageResponse {
        val cacheKey="${request.data}_$page"
        mainPageCache.get(cacheKey)?.let{ return newHomePageResponse(request.name, it) }
        val sort = when(request.data){
            "trending"->"TRENDING_DESC"
            "popular"->"POPULARITY_DESC"
            "top"->"SCORE_DESC"
            "airing"->"POPULARITY_DESC"
            "movie"->"POPULARITY_DESC"
            else->"POPULARITY_DESC"
        }
        val format = if(request.data=="movie") "MOVIE" else null
        val query="""
            query (${'$'}page:Int, ${'$'}sort:[MediaSort], ${'$'}format:MediaFormat){
              Page(page:${'$'}page, perPage:20){
                media(sort:${'$'}sort, type:ANIME, format:${'$'}format, isAdult:false){
                  id idMal
                  title{ romaji english native }
                  coverImage{ extraLarge large }
                  bannerImage
                  description
                  averageScore
                  episodes
                  format
                }
              }
            }
        """.trimIndent()
        val vars=JSONObject().put("page", page).put("sort", sort)
        if(format!=null) vars.put("format", format)
        val json=anilistQuery(query, vars) ?: return newHomePageResponse(request.name, emptyList())
        val mediaArr=json.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return newHomePageResponse(request.name, emptyList())
        val list=(0 until mediaArr.length()).mapNotNull{ i->
            val m=mediaArr.optJSONObject(i) ?: return@mapNotNull null
            val id=m.optInt("id",0); if(id==0) return@mapNotNull null
            val mal=m.optInt("idMal",0).takeIf{it!=0}
            val title=m.optJSONObject("title")?.optString("english")?.takeIf{it.isNotBlank()} ?: m.optJSONObject("title")?.optString("romaji") ?: "Unknown"
            val poster=m.optJSONObject("coverImage")?.optString("extraLarge") ?: m.optJSONObject("coverImage")?.optString("large")
            val isMovie=m.optString("format")=="MOVIE"
            val data=AnilistData(id, mal, title, isMovie).toJson()
            newAnimeSearchResponse(title, data, if(isMovie) TvType.AnimeMovie else TvType.Anime){ this.posterUrl=poster }
        }
        mainPageCache.put(cacheKey, list)
        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query:String): List<SearchResponse>{
        if(query.isBlank()) return emptyList()
        val gqlQuery="""
            query (${'$'}search:String){
              Page(page:1, perPage:20){
                media(search:${'$'}search, type:ANIME, isAdult:false){
                  id idMal
                  title{ romaji english native }
                  coverImage{ extraLarge large }
                  bannerImage
                  format
                }
              }
            }
        """.trimIndent()
        val vars=JSONObject().put("search", query)
        val json=anilistQuery(gqlQuery, vars) ?: return emptyList()
        val arr=json.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()
        return (0 until arr.length()).mapNotNull{ i->
            val m=arr.optJSONObject(i) ?: return@mapNotNull null
            val id=m.optInt("id",0); if(id==0) return@mapNotNull null
            val mal=m.optInt("idMal",0).takeIf{it!=0}
            val title=m.optJSONObject("title")?.optString("english")?.takeIf{it.isNotBlank()} ?: m.optJSONObject("title")?.optString("romaji") ?: return@mapNotNull null
            val poster=m.optJSONObject("coverImage")?.optString("extraLarge")
            val isMovie=m.optString("format")=="MOVIE"
            val data=AnilistData(id, mal, title, isMovie).toJson()
            newAnimeSearchResponse(title, data, if(isMovie) TvType.AnimeMovie else TvType.Anime){ this.posterUrl=poster }
        }
    }

    override suspend fun load(url:String): LoadResponse?{
        val data=parseJson<AnilistData>(url)
        val id=data.id
        val query="""
            query (${'$'}id:Int){
              Media(id:${'$'}id, type:ANIME){
                id idMal
                title{ romaji english native }
                coverImage{ extraLarge large }
                bannerImage
                description
                averageScore
                genres
                episodes
                duration
                format
                studios{ nodes{ name } }
                characters(page:1, perPage:10){
                  edges{
                    node{ name{full} image{large} }
                    role
                  }
                }
                trailer{ id site }
              }
            }
        """.trimIndent()
        val vars=JSONObject().put("id", id)
        val json=anilistQuery(query, vars) ?: return null
        val media=json.optJSONObject("data")?.optJSONObject("Media") ?: return null
        val eng=media.optJSONObject("title")?.optString("english")?.takeIf{it.isNotBlank()}
        val rom=media.optJSONObject("title")?.optString("romaji")
        val title=eng ?: rom ?: data.title
        val poster=media.optJSONObject("coverImage")?.optString("extraLarge")
        val banner=media.optString("bannerImage").takeIf{it.isNotBlank()}
        val desc=media.optString("description").replace(Regex("<[^>]*>"),"").takeIf{it.isNotBlank()}
        val score=media.optInt("averageScore",0).takeIf{it>0}?.let{it/10.0}
        val genres=media.optJSONArray("genres")?.let{a-> (0 until a.length()).map{a.getString(it)} }
        val episodesCount=media.optInt("episodes",12).coerceAtLeast(1)
        val format=media.optString("format")
        val isMovie=format=="MOVIE"
        val actors=media.optJSONObject("characters")?.optJSONArray("edges")?.let{a-> (0 until minOf(a.length(),15)).mapNotNull{i-> val edge=a.optJSONObject(i) ?: return@mapNotNull null; val node=edge.optJSONObject("node") ?: return@mapNotNull null; val name=node.optJSONObject("name")?.optString("full")?.takeIf{it.isNotBlank()} ?: return@mapNotNull null; val img=node.optJSONObject("image")?.optString("large"); ActorData(Actor(name, img), roleString=edge.optString("role")) } }

        // Also try TMDB enrichment for anime (dual enrichment)
        val tmdbMeta=WizCore.retry{ WizEnrich.tmdbTv(title) ?: WizEnrich.tmdbMovie(title, null) }
        val imdbId=tmdbMeta?.imdbId
        val tmdbId=tmdbMeta?.tmdbId

        return if(isMovie){
            val linkData=LinkData(data.id, data.idMal, title, null, tmdbId, imdbId, null).toJson()
            newMovieLoadResponse(title, url, TvType.AnimeMovie, linkData){
                this.posterUrl=poster ?: tmdbMeta?.poster
                this.backgroundPosterUrl=banner ?: tmdbMeta?.backdrop
                this.plot=desc ?: tmdbMeta?.plot
                this.tags=genres ?: tmdbMeta?.tags
                score?.let{ this.score=Score.from10(it) } ?: tmdbMeta?.rating?.let{ this.score=Score.from10(it) }
                this.actors=actors ?: tmdbMeta?.actors
                media.optJSONObject("trailer")?.let{ tr-> if(tr.optString("site")=="youtube") addTrailer("https://www.youtube.com/watch?v=${tr.optString("id")}") }
                addAniListId(data.id)
                data.idMal?.let{ addMalId(it) }
                imdbId?.let{ addImdbId(it) }
            }
        } else {
            val episodes=(1..episodesCount).map{ epNum->
                val epData=LinkData(data.id, data.idMal, title, epNum, tmdbId, imdbId, 1).toJson()
                newEpisode(epData){ name="Episode $epNum"; episode=epNum; season=1 }
            }
            newAnimeLoadResponse(title, url, TvType.Anime){
                addEpisodes(DubStatus.Subbed, episodes)
                this.posterUrl=poster ?: tmdbMeta?.poster
                this.backgroundPosterUrl=banner ?: tmdbMeta?.backdrop
                this.plot=desc ?: tmdbMeta?.plot
                this.tags=genres ?: tmdbMeta?.tags
                score?.let{ this.score=Score.from10(it) } ?: tmdbMeta?.rating?.let{ this.score=Score.from10(it) }
                this.actors=actors ?: tmdbMeta?.actors
                addAniListId(data.id)
                data.idMal?.let{ addMalId(it) }
                imdbId?.let{ addImdbId(it) }
            }
        }
    }

    override suspend fun loadLinks(data:String, isCasting:Boolean, subtitleCallback:(SubtitleFile)->Unit, callback:(ExtractorLink)->Unit): Boolean {
        val linkData=parseJson<LinkData>(data)
        // We need to get title from linkData, but we don't have tmdbId yet – try enrich to get tmdbId if missing
        var tmdbId=linkData.tmdbId
        var imdbId=linkData.imdbId
        var year:Int?=null
        var title=linkData.title

        if(tmdbId==null){
            val tmdbMeta=WizCore.retry{ WizEnrich.tmdbTv(title) ?: WizEnrich.tmdbMovie(title, null) }
            tmdbId=tmdbMeta?.tmdbId
            imdbId=imdbId ?: tmdbMeta?.imdbId
            title=tmdbMeta?.title ?: title
        }

        val loadData=WizExtractors.LoadData(
            title=title,
            year=year,
            tmdbId=tmdbId,
            imdbId=imdbId,
            season=linkData.season,
            episode=linkData.episode,
            isAnime=true,
            anilistId=linkData.anilistId,
            malId=linkData.malId
        )

        var found=false

        // 1. vid[x] + your 5 extensions + anime sources
        coroutineScope{
            ProviderRegistry.animeProviders.map{ def->
                async{
                    try{
                        def.execute(loadData, subtitleCallback){ link-> callback.invoke(link); found=true }
                    } catch(_:Throwable){}
                }
            }.awaitAll()
        }

        // 2. Stremio MultiAPI aggregator like StreamPlay
        if(imdbId!=null){
            val addons=WizStremio.defaultAddons
            val type=if(linkData.episode==null) "movie" else "series"
            val stremioFound=WizStremio.fetchStreams(imdbId, type, linkData.season, linkData.episode, addons, callback)
            if(stremioFound) found=true
        }

        // 3. Also try anilistId based stremio (some addons support anilist)
        if(linkData.anilistId!=null && !found){
            // Could query anilist-based torrent addons via anizip etc – placeholder
        }

        return found
    }
}
