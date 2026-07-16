package com.wizdier.wizplay

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray

/**
 * WizExtractors – MultiAPI aggregator (precious, robust, efficient)
 * Fetches all sources present in your 5 extensions + vid[x] family: vidSrc, vidNest, vidRock, vidFast, vidEasy, vidZee, vidPrime
 * Pattern stolen from CSX CineStreamExtractors + Movies67 WingsDatabase + phisher98 StreamPlay torrentio
 */

object WizExtractors {
    data class LoadData(
        val title:String,
        val year:Int?,
        val tmdbId:Int?,
        val imdbId:String?,
        val season:Int?,
        val episode:Int?,
        val isAnime:Boolean,
        val anilistId:Int?,
        val malId:Int?
    )

    private val subsSeen = mutableSetOf<String>()

    // ────────────────────────── vid[x] extractors ──────────────────────────

    // vidSrc – vidsrc.to, vidsrc.cc, vidsrc.me, autoembed.cc, embed.su, vidlink.pro, primeSrc.me, vidzee.wtf, vidnest?, vidrock.ru, vidfast.vc
    suspend fun invokeVidSrcTo(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val id=data.tmdbId ?: return
            val url=if(data.season==null) "https://vidsrc.to/embed/movie/$id" else "https://vidsrc.to/embed/tv/$id/${data.season}/${data.episode}"
            com.lagradost.cloudstream3.utils.loadExtractor(url, "https://vidsrc.to", subCb, cb)
        } catch(_:Exception){}
    }

    suspend fun invokeVidSrcCc(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val id=data.tmdbId ?: return
            val url=if(data.season==null) "https://vidsrc.cc/v2/embed/movie/$id" else "https://vidsrc.cc/v2/embed/tv/$id/${data.season}/${data.episode}"
            com.lagradost.cloudstream3.utils.loadExtractor(url, "https://vidsrc.cc", subCb, cb)
        } catch(_:Exception){}
    }

    suspend fun invokeEmbedSu(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val id=data.tmdbId ?: return
            val url=if(data.season==null) "https://embed.su/embed/movie/$id" else "https://embed.su/embed/tv/$id/${data.season}/${data.episode}"
            com.lagradost.cloudstream3.utils.loadExtractor(url, "https://embed.su", subCb, cb)
        } catch(_:Exception){}
    }

    suspend fun invokeVidLinkPro(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val id=data.tmdbId ?: return
            val url=if(data.season==null) "https://vidlink.pro/movie/$id" else "https://vidlink.pro/tv/$id/${data.season}/${data.episode}"
            com.lagradost.cloudstream3.utils.loadExtractor(url, "https://vidlink.pro", subCb, cb)
        } catch(_:Exception){}
    }

    suspend fun invokeAutoEmbed(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val id=data.tmdbId ?: return
            val url=if(data.season==null) "https://autoembed.cc/movie/tmdb/$id" else "https://autoembed.cc/tv/tmdb/$id-${data.season}-${data.episode}"
            com.lagradost.cloudstream3.utils.loadExtractor(url, "https://autoembed.cc", subCb, cb)
        } catch(_:Exception){}
    }

    // vidNest – often https://vidnest.fun / vidnest.to – using same pattern as embed.su
    suspend fun invokeVidNest(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val id=data.tmdbId ?: return
            // Try vidnest.cc pattern
            val urls=listOf(
                if(data.season==null) "https://vidnest.fun/movie/$id" else "https://vidnest.fun/tv/$id/${data.season}/${data.episode}",
                if(data.season==null) "https://vidnest.to/movie/$id" else "https://vidnest.to/tv/$id/${data.season}/${data.episode}"
            )
            for(u in urls){
                try{ com.lagradost.cloudstream3.utils.loadExtractor(u, "https://vidnest.fun", subCb, cb) } catch(_:Exception){}
            }
        } catch(_:Exception){}
    }

    // vidRock – https://vidrock.ru / https://vidrock.net
    suspend fun invokeVidRock(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val id=data.tmdbId ?: return
            val url=if(data.season==null) "https://vidrock.net/movie/$id" else "https://vidrock.net/tv/$id/${data.season}/${data.episode}"
            com.lagradost.cloudstream3.utils.loadExtractor(url, "https://vidrock.net", subCb, cb)
        } catch(_:Exception){}
        // Also vidrock.ru direct API like CSX invokeVidrock
        try{
            val tmdbId=data.tmdbId ?: return
            val api="https://vidrock.ru/api/${if(data.season==null) "movie" else "tv"}/$tmdbId${if(data.season!=null) "/${data.season}/${data.episode}" else ""}"
            val json=WizCore.retry{ app.get(api, timeout=10_000).text } ?: return
            val obj=JSONObject(json)
            obj.optJSONArray("sources")?.let{ arr->
                for(i in 0 until arr.length()){
                    val src=arr.optJSONObject(i) ?: continue
                    val u=src.optString("url").ifBlank{continue}
                    cb(newExtractorLink("VidRock", "VidRock ${src.optString("quality","")}${WizCore.getSimplifiedTitle(src.optString("quality",""))}", u){ quality=getQualityFromName(src.optString("quality","")) })
                }
            }
        } catch(_:Exception){}
    }

    // vidFast – https://vidfast.vc / https://vidfast.pro – CSX vidfastProApi
    suspend fun invokeVidFast(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val id=data.tmdbId ?: return
            val url=if(data.season==null) "https://vidfast.vc/movie/$id" else "https://vidfast.vc/tv/$id/${data.season}/${data.episode}"
            com.lagradost.cloudstream3.utils.loadExtractor(url, "https://vidfast.vc", subCb, cb)
        } catch(_:Exception){}
    }

    // vidEasy – WingsDatabase 8-server (jett, yoru, tejo, neon, sage, cypher, breach, vyse) – like Movies67
    suspend fun invokeVidEasy(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        subsSeen.clear()
        val id=data.tmdbId?.toString() ?: return
        val title=data.title; val year=data.year?.toString() ?: ""
        val imdb=data.imdbId ?: ""
        val s=data.season?.toString() ?: "1"
        val e=data.episode?.toString() ?: "1"
        val mt=if(data.season==null) "movie" else "tv"
        val servers=listOf("jett" to "Jett", "cdn" to "Yoru", "tejo" to "Tejo", "neon2" to "Neon", "ym" to "Sage", "downloader2" to "Cypher", "m4uhd" to "Breach", "hdmovie" to "Vyse")
        val seed=try{
            JSONObject(app.get("https://api.wingsdatabase.com/seed?mediaId=$id", timeout=10_000, headers=mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14)","Accept" to "application/json","Referer" to "https://www.vidking.net/","Origin" to "https://www.vidking.net")).text).optString("seed").ifBlank{return}
        } catch(_:Exception){ return }
        val encTitle=java.net.URLEncoder.encode(java.net.URLEncoder.encode(title,"UTF-8"),"UTF-8")
        for((key,label) in servers){
            if(key=="cdn" && mt!="movie") continue
            try{
                val qs=listOf("title" to encTitle, "mediaType" to mt, "year" to year, "episodeId" to e, "seasonId" to s, "tmdbId" to id, "imdbId" to imdb, "enc" to "2", "seed" to seed).joinToString("&"){(kv,vv)->"$kv=${java.net.URLEncoder.encode(vv,"UTF-8")}"}
                val enc=app.get("https://api.wingsdatabase.com/${key}/sources-with-title?$qs", timeout=15_000, headers=mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14)","Accept" to "application/json, text/plain, */*","Referer" to "https://www.vidking.net/","Origin" to "https://www.vidking.net")).text
                if(enc.isBlank()||enc.startsWith("{")||enc.length<100) continue
                val body=JSONObject().apply{ put("text",enc); put("id",id); put("seed",seed) }
                val rp=JSONObject(app.post("https://enc-dec.app/api/dec-videasy", requestBody=body.toString().toRequestBody("application/json".toMediaTypeOrNull()), timeout=20_000, headers=mapOf("Content-Type" to "application/json")).text)
                if(rp.optInt("status",-1)!=200) continue
                val obj=rp.optJSONObject("result")?:continue
                obj.optJSONArray("sources")?.let{ srcs->
                    for(i in 0 until srcs.length()){
                        val src=srcs.getJSONObject(i)
                        val u=src.optString("url").ifBlank{continue}
                        cb(newExtractorLink("WizPlay ($label)", "$label - ${src.optString("quality","?")}${WizCore.getSimplifiedTitle(src.optString("quality",""))}", u){ quality=getQualityFromName(src.optString("quality","Unknown")) })
                    }
                }
                obj.optJSONArray("subtitles")?.let{ subs->
                    for(i in 0 until subs.length()){
                        val sb=subs.getJSONObject(i); val su=sb.optString("url").ifBlank{continue}; val lang=sb.optString("lang","unknown")
                        if(su !in subsSeen){ subsSeen.add(su); subCb(SubtitleFile(su, lang)) }
                    }
                }
            } catch(_:Exception){ continue }
        }
    }

    // vidZee – https://player.vidzee.wtf
    suspend fun invokeVidZee(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val id=data.tmdbId ?: return
            val url=if(data.season==null) "https://player.vidzee.wtf/api/movie/$id" else "https://player.vidzee.wtf/api/tv/${id}/${data.season}/${data.episode}"
            val json=WizCore.retry{ app.get(url, timeout=10_000).text } ?: return
            val obj=JSONObject(json)
            obj.optJSONArray("streams")?.let{ arr->
                for(i in 0 until arr.length()){
                    val s=arr.optJSONObject(i) ?: continue
                    val u=s.optString("url").ifBlank{continue}
                    cb(newExtractorLink("VidZee", "VidZee ${s.optString("quality","")}", u){ quality=getQualityFromName(s.optString("quality","")) })
                }
            }
        } catch(_:Exception){}
    }

    // primeSrc – https://primesrc.me
    suspend fun invokePrimeSrc(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val url="https://primesrc.me/api/${if(data.season==null) "movie" else "tv"}/${data.tmdbId}${if(data.season!=null) "/${data.season}/${data.episode}" else ""}"
            com.lagradost.cloudstream3.utils.loadExtractor(url, "https://primesrc.me", subCb, cb)
        } catch(_:Exception){}
    }

    // ── Sources from your own extensions (CineplexBD, CircleFTP, CTGMovies, FTPBD, Movies67) – simplified aggregator versions ──
    // We reuse their public APIs via search + link extraction pattern – efficient fallback

    suspend fun invokeCineplexBD(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val q=WizCore.run{ data.title.encodeUrl() }
            val doc=WizCore.retry{ app.get("http://cineplexbd.net/search.php?q=$q&page=1", timeout=10_000).document } ?: return
            val link=doc.selectFirst("a[href*='/movie/'], a[href*='watch.php']")?.attr("href") ?: return
            val playerPage=WizCore.retry{ app.get(if(link.startsWith("http")) link else "http://cineplexbd.net$link", timeout=10_000).text } ?: return
            val m3u8=Regex("""https?://[^'"\s]+\.m3u8[^'"\s]*""").findAll(playerPage).map{it.value}.toList()
            m3u8.forEach{ u-> cb(newExtractorLink("CineplexBD", "CineplexBD HLS", u){ quality=getQualityFromName(u) }) }
        } catch(_:Exception){}
    }

    suspend fun invokeCircleFTP(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val q=WizCore.run{ data.title.encodeUrl() }
            val txt=WizCore.retry{ app.get("http://new.circleftp.net:5000/api/v1/search?query=$q", verify=false, timeout=12_000).text } ?: return
            val arr=runCatching{ JSONObject(txt).optJSONArray("data") }.getOrNull() ?: return
            if(arr.length()==0) return
            val first=arr.optJSONObject(0) ?: return
            val id=first.optInt("id",0); if(id==0) return
            val detail=JSONObject(WizCore.retry{ app.get("http://new.circleftp.net:5000/api/v1/post/$id", verify=false, timeout=12_000).text } ?: return)
            val links=detail.optJSONObject("data")?.optJSONArray("download_links") ?: JSONArray()
            for(i in 0 until links.length()){
                val u=links.optJSONObject(i)?.optString("url")?.ifBlank{continue} ?: continue
                cb(newExtractorLink("CircleFTP", "CircleFTP ${i+1}", u){ quality=getQualityFromName(u) })
            }
        } catch(_:Exception){}
    }

    suspend fun invokeCTG(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val base="https://cockpit.103.109.92.178.nip.io/api/v1"
            val q=JSONObject().apply{ put("search", data.title) }.toString()
            val txt=WizCore.retry{ app.get("$base/movies?search=${WizCore.run{ data.title.encodeUrl() }}", timeout=10_000).text } ?: return
            val arr=runCatching{ JSONObject(txt).optJSONArray("data") }.getOrNull() ?: return
            for(i in 0 until arr.length()){
                val obj=arr.optJSONObject(i) ?: continue
                val links=obj.optJSONArray("links") ?: continue
                for(j in 0 until links.length()){
                    val u=links.optJSONObject(j)?.optString("url")?.ifBlank{continue} ?: continue
                    cb(newExtractorLink("CTGMovies", "CTG ${links.optJSONObject(j)?.optString("quality","")}", u){ quality=getQualityFromName(links.optJSONObject(j)?.optString("quality","")) })
                }
            }
        } catch(_:Exception){}
    }

    suspend fun invokeFTPBD(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val q=WizCore.run{ data.title.encodeUrl() }
            val doc=WizCore.retry{ app.get("https://ftpbd.net/?s=$q&post_type=movies", timeout=10_000).document } ?: return
            val a=doc.selectFirst("a[href*='/movie/']") ?: return
            val link=a.attr("href")
            val page=WizCore.retry{ app.get(link, timeout=10_000).text } ?: return
            Regex("""https?://[^'"\s]+\.(?:mp4|mkv|m3u8)[^'"\s]*""", RegexOption.IGNORE_CASE).findAll(page).forEach{ m->
                val u=m.value
                val name=WizCore.buildSourceName(null, u, 1)
                cb(newExtractorLink("FTPBD", name, u){ quality=getQualityFromName(u) })
            }
        } catch(_:Exception){}
    }

    suspend fun invokeMovies67(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        // Movies67 already uses WingsDatabase + embed, but we can directly call its embed sources – already covered by vidSrc etc.
        // So this is placeholder for direct 67movies.nl scraping if ever needed
    }

    // Main aggregator – like CSX invokeAllSources + StreamPlay multi addon
    suspend fun invokeAllWizSources(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        // Supervisor scope + semaphore bounded like CSX
        WizCore.parallelMapNotNull(
            listOf<suspend ()->Unit>(
                { invokeVidEasy(data, subCb, cb) },
                { invokeVidSrcTo(data, subCb, cb) },
                { invokeVidSrcCc(data, subCb, cb) },
                { invokeEmbedSu(data, subCb, cb) },
                { invokeVidLinkPro(data, subCb, cb) },
                { invokeAutoEmbed(data, subCb, cb) },
                { invokeVidNest(data, subCb, cb) },
                { invokeVidRock(data, subCb, cb) },
                { invokeVidFast(data, subCb, cb) },
                { invokeVidZee(data, subCb, cb) },
                { invokePrimeSrc(data, subCb, cb) },
                // Your own extensions as additional sources
                { invokeCineplexBD(data, subCb, cb) },
                { invokeCircleFTP(data, subCb, cb) },
                { invokeCTG(data, subCb, cb) },
                { invokeFTPBD(data, subCb, cb) },
            ), concurrency=6
        ){ it.invoke() }
    }

    // Anime specific sources
    suspend fun invokeAllAnimeSources(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        // For anime, we also include anime-specific extractors plus vid sources
        invokeAllWizSources(data, subCb, cb)
        // Additional animepahe, allanime, anizone etc from CSX – simplified
        try{
            val title=data.title
            val url="https://animepahe.pw/api?m=search&q=${WizCore.run{ title.encodeUrl() }}"
            // Placeholder – actual anime extractors can be added from CSX
        } catch(_:Exception){}
    }
}
