package com.wizdier.wizplay

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

object WizEnrich {
    private const val TMDB_API = "https://api.themoviedb.org/3"
    private const val TMDB_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"
    private const val IMG = "https://image.tmdb.org/t/p"
    private val tmdbCache = WizCore.TtlCache<String, EnrichedMeta>()
    private val aniCache = WizCore.TtlCache<String, EnrichedMeta>()

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
        val isAnime:Boolean=false,
        val episodes:List<EpisodeMeta>?=null
    )
    data class EpisodeMeta(val name:String?, val overview:String?, val still:String?, val rating:Double?)

    private fun poster(p:String?, size:String="w500") = p?.takeIf{it.isNotBlank()&&it!="null"}?.let{"$IMG/$size$it"}
    private suspend fun safeJson(url:String): JSONObject? = WizCore.retry{ JSONObject(app.get(url, timeout=8000).text) }

    // TMDB Movie
    suspend fun tmdbMovie(title:String, year:Int?): EnrichedMeta?{
        val key="tmdb_m_${title.lowercase()}_$year"
        tmdbCache.get(key)?.let{return it}
        val q=WizCore.run { title.encodeUrl() }
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
            trailer=detail.optJSONObject("videos")?.optJSONArray("results")?.let{a-> (0 until a.length()).firstNotNullOfOrNull{i-> val v=a.getJSONObject(i); if(v.optString("type")=="Trailer" && v.optString("site")=="YouTube") "https://www.youtube.com/watch?v=${v.optString("key")}" else null } },
            actors=detail.optJSONObject("credits")?.optJSONArray("cast")?.let{a-> (0 until minOf(a.length(),15)).mapNotNull{i-> val p=a.getJSONObject(i); p.optString("name").takeIf{it.isNotBlank()}?.let{ ActorData(Actor(it, poster(p.optString("profile_path"),"w185")), roleString=p.optString("character")) } } },
            isAnime=false
        )
        tmdbCache.put(key, meta); return meta
    }

    // TMDB TV
    suspend fun tmdbTv(title:String): EnrichedMeta?{
        val key="tmdb_tv_${title.lowercase()}"
        tmdbCache.get(key)?.let{return it}
        val q=WizCore.run { title.encodeUrl() }
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

    // AniList GraphQL
    suspend fun anilist(title:String): EnrichedMeta?{
        val key="ani_${title.lowercase()}"
        aniCache.get(key)?.let{return it}
        val query="""
            query (${'$'}search:String){
              Page(page:1, perPage:5){
                media(search:${'$'}search, type:ANIME){
                  id idMal
                  title{ romaji english native }
                  coverImage{ extraLarge large }
                  bannerImage
                  description
                  averageScore
                  genres
                  episodes
                  characters(page:1, perPage:10){
                    edges{
                      node{ name{full} image{large} }
                      voiceActors(language:JAPANESE){ name{full} image{large} }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val vars=JSONObject().put("search", title)
        val body=JSONObject().put("query", query).put("variables", vars)
        val resText=WizCore.retry{
            app.post("https://graphql.anilist.co", requestBody=body.toString().toRequestBody("application/json".toMediaTypeOrNull()), timeout=10_000).text
        } ?: return null
        val media=runCatching{
            JSONObject(resText).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")?.optJSONObject(0)
        }.getOrNull() ?: return null
        val eng=media.optJSONObject("title")?.optString("english")?.takeIf{it.isNotBlank()}
        val rom=media.optJSONObject("title")?.optString("romaji")
        val cover=media.optJSONObject("coverImage")?.optString("extraLarge") ?: media.optJSONObject("coverImage")?.optString("large")
        val banner=media.optString("bannerImage").takeIf{it.isNotBlank()}
        val desc=media.optString("description").replace(Regex("<[^>]*>"),"").takeIf{it.isNotBlank()}
        val score=media.optInt("averageScore",0).takeIf{it>0}?.let{it/10.0}
        val genres=media.optJSONArray("genres")?.let{a-> (0 until a.length()).map{a.getString(it)} }
        val aniId=media.optInt("id",0).takeIf{it!=0}
        val malId=media.optInt("idMal",0).takeIf{it!=0}
        val meta=EnrichedMeta(
            title=eng ?: rom ?: title,
            origTitle=rom,
            plot=desc,
            poster=cover,
            backdrop=banner,
            rating=score,
            tags=genres,
            anilistId=aniId,
            malId=malId,
            isAnime=true
        )
        aniCache.put(key, meta); return meta
    }

    // Unified: tries anime detection then both
    suspend fun enrich(rawTitle:String, year:Int?, isSeries:Boolean): EnrichedMeta{
        val low=rawTitle.lowercase()
        val isAnimeHint=listOf("anime","one piece","naruto","bleach","jujutsu","demon slayer","attack on titan","solo leveling","my hero").any{low.contains(it)} || (isSeries && low.contains("anime"))
        return if(isAnimeHint){
            anilist(rawTitle) ?: tmdbTv(rawTitle) ?: tmdbMovie(rawTitle, year) ?: EnrichedMeta(title=rawTitle, year=year, isAnime=true)
        } else {
            tmdbMovie(rawTitle, year) ?: tmdbTv(rawTitle) ?: anilist(rawTitle) ?: EnrichedMeta(title=rawTitle, year=year)
        }
    }

    private fun String.encodeUrl(): String = URLEncoder.encode(this,"UTF-8")
}
