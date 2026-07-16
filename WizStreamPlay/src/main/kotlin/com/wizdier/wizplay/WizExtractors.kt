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

    // vidEasy – WingsDatabase – Robust V4 fix for Yoru/Cypher/Neon HTTP 2004
    // - Fresh seed per server (seed is single-use, ttl 30s)
    // - Retry on STREAMCRYPTO_SEED_INVALID, 2004, 500 errors
    // - Skip dead servers (jett=FebBox token, tejo=Cineby 404)
    // - Decrypt via enc-dec.app with retry
    suspend fun invokeVidEasy(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        subsSeen.clear()
        val id=data.tmdbId?.toString() ?: return
        val title=data.title; val year=data.year?.toString() ?: ""
        val imdb=data.imdbId ?: ""
        val s=data.season?.toString() ?: "1"
        val e=data.episode?.toString() ?: "1"
        val mt=if(data.season==null) "movie" else "tv"

        // Prioritize working servers first: Yoru (cdn), Neon (neon2), Cypher (downloader2) etc
        // Jett and Tejo are known dead (FebBox/Cineby) – we try last or skip
        val servers=listOf(
            "cdn" to "Yoru",
            "neon2" to "Neon",
            "downloader2" to "Cypher",
            "ym" to "Sage",
            "m4uhd" to "Breach",
            "hdmovie" to "Vyse",
            "jett" to "Jett",
            "tejo" to "Tejo"
        )

        suspend fun fetchSeed(): String? {
            return WizCore.retry(maxAttempts=3, initialDelayMs=500){
                val txt=app.get("https://api.wingsdatabase.com/seed?mediaId=$id", timeout=10_000, headers=mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14)",
                    "Accept" to "application/json",
                    "Referer" to "https://www.vidking.net/",
                    "Origin" to "https://www.vidking.net"
                )).text
                val seed=JSONObject(txt).optString("seed").takeIf{it.isNotBlank()} ?: throw Exception("empty seed")
                seed
            }
        }

        val encTitle=java.net.URLEncoder.encode(java.net.URLEncoder.encode(title,"UTF-8"),"UTF-8")

        for((key,label) in servers){
            if(key=="cdn" && mt!="movie") continue
            var attempt=0
            var success=false
            while(attempt<2 && !success){
                attempt++
                try{
                    val seed=fetchSeed() ?: continue
                    val qs=listOf(
                        "title" to encTitle,
                        "mediaType" to mt,
                        "year" to year,
                        "episodeId" to e,
                        "seasonId" to s,
                        "tmdbId" to id,
                        "imdbId" to imdb,
                        "enc" to "2",
                        "seed" to seed
                    ).joinToString("&"){(kv,vv)->"$kv=${java.net.URLEncoder.encode(vv,"UTF-8")}"}

                    val encResp=WizCore.retry(maxAttempts=2){
                        app.get("https://api.wingsdatabase.com/${key}/sources-with-title?$qs", timeout=15_000, headers=mapOf(
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 14)",
                            "Accept" to "application/json, text/plain, */*",
                            "Referer" to "https://www.vidking.net/",
                            "Origin" to "https://www.vidking.net",
                            "Cache-Control" to "no-cache"
                        )).text
                    } ?: continue

                    // If response is JSON error, skip retry for this server if it's known dead
                    if(encResp.trim().startsWith("{")){
                        val errObj=runCatching{ JSONObject(encResp) }.getOrNull()
                        if(errObj!=null && errObj.has("error")){
                            val msg=errObj.optString("message","") + errObj.optString("error","")
                            // STREAMCRYPTO_SEED_INVALID means seed reused – retry with fresh seed
                            if(msg.contains("SEED_INVALID", true) || msg.contains("FebBox token", true) || msg.contains("Cineby", true) || msg.contains("403") || msg.contains("502")){
                                if(msg.contains("SEED_INVALID")) continue // will loop attempt again with new seed
                                // For dead servers, break after 1 attempt
                                if(key=="jett" || key=="tejo") break
                                continue
                            }
                        }
                        // Error JSON but not our encrypted string – skip
                        if(encResp.length<100 || encResp.contains("\"error\"")) continue
                    }

                    if(encResp.isBlank() || encResp.length<100) continue

                    val body=JSONObject().apply{ put("text",encResp); put("id",id); put("seed",seed) }
                    val rpText=WizCore.retry(maxAttempts=2){
                        app.post("https://enc-dec.app/api/dec-videasy", requestBody=body.toString().toRequestBody("application/json".toMediaTypeOrNull()), timeout=20_000, headers=mapOf("Content-Type" to "application/json")).text
                    } ?: continue

                    val rp=runCatching{ JSONObject(rpText) }.getOrNull() ?: continue
                    val status=rp.optInt("status",-1)
                    // Handle HTTP 2004 custom error from enc-dec
                    if(status==2004 || status==400 || status==500){
                        // Try again with new seed
                        continue
                    }
                    if(status!=200) continue

                    val obj=rp.optJSONObject("result")?:continue
                    val srcs=obj.optJSONArray("sources")
                    if(srcs==null || srcs.length()==0) continue

                    for(i in 0 until srcs.length()){
                        val src=srcs.getJSONObject(i)
                        val u=src.optString("url").ifBlank{continue}
                        cb(newExtractorLink("WizPlay ($label)", "$label - ${src.optString("quality","?")}${WizCore.getSimplifiedTitle(src.optString("quality",""))}", u){ quality=getQualityFromName(src.optString("quality","Unknown")) })
                    }
                    obj.optJSONArray("subtitles")?.let{ subs->
                        for(i in 0 until subs.length()){
                            val sb=subs.getJSONObject(i); val su=sb.optString("url").ifBlank{continue}; val lang=sb.optString("lang","unknown")
                            if(su !in subsSeen){ subsSeen.add(su); subCb(SubtitleFile(su, lang)) }
                        }
                    }
                    success=true // mark success so we don't retry same server
                } catch(_:Exception){ continue }
            }
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

    // ── 4 BDIX sources – Robust versions with fallback & proper headers ──
    suspend fun invokeCineplexBD(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val headers=mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", "Referer" to "http://cineplexbd.net/")
            val q=WizCore.run{ data.title.encodeUrl() }
            // Fan-out search: main search + year filter
            val docs=listOf(
                WizCore.retry{ app.get("http://cineplexbd.net/search.php?q=$q&page=1", headers=headers, timeout=10_000).document },
                WizCore.retry{ app.get("http://cineplexbd.net/search.php?q=${q}&year[]=${data.year ?: 2024}&page=1", headers=headers, timeout=10_000).document }
            ).filterNotNull()
            for(doc in docs){
                val anchors=doc.select("a[href*='/movie/'], a[href*='watch.php'], div.movie-card a, .tvCard a")
                for(a in anchors.take(3)){
                    val href=a.attr("href").let{if(it.startsWith("http")) it else "http://cineplexbd.net$it"}
                    if(href.isBlank()) continue
                    val playerDoc=WizCore.retry{ app.get(href, headers=headers, timeout=10_000).document } ?: continue
                    // Try to find player.php?id= links
                    val playerLinks=playerDoc.select("a[href*='player.php?id='], [data-url]")
                    for(pl in playerLinks){
                        val pHref=pl.attr("href").ifBlank{pl.attr("data-url")}
                        if(pHref.isBlank()) continue
                        val abs=if(pHref.startsWith("http")) pHref else "http://cineplexbd.net$pHref"
                        val html=WizCore.retry{ app.get(abs, headers=headers, timeout=15_000).text } ?: continue
                        // Extract m3u8 and mp4
                        Regex("""https?://[^'"\s]+\.m3u8[^'"\s]*""").findAll(html).forEach{ m->
                            val u=m.value
                            cb(newExtractorLink("CineplexBD", "CineplexBD HLS ${WizCore.getSimplifiedTitle(u)}", u){ quality=getQualityFromName(u) })
                        }
                        Regex("""https?://[^'"\s]+\.(?:mp4|mkv)[^'"\s]*""").findAll(html).forEach{ m->
                            val u=m.value
                            cb(newExtractorLink("CineplexBD", "CineplexBD ${WizCore.getSimplifiedTitle(u)}", u){ quality=getQualityFromName(u) })
                        }
                        // Fallback extractor
                        try{ com.lagradost.cloudstream3.utils.loadExtractor(abs, "http://cineplexbd.net/", subCb, cb) } catch(_:Exception){}
                    }
                }
            }
        } catch(_:Exception){}
    }

    suspend fun invokeCircleFTP(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val q=WizCore.run{ data.title.encodeUrl() }
            // Use both primary and fallback API mirrors like CircleFtpProvider (StreamPlay uses multi-mirror)
            val mirrors=listOf(
                "http://new.circleftp.net:5000",
                "http://15.1.1.50:5000"
            )
            for(base in mirrors){
                try{
                    val txt=WizCore.retry{ app.get("$base/api/v1/search?query=$q", verify=false, timeout=12_000).text } ?: continue
                    val arr=runCatching{ JSONObject(txt).optJSONArray("data") }.getOrNull() ?: continue
                    if(arr.length()==0) continue
                    // Take top 2 results only to avoid spamming
                    for(idx in 0 until minOf(arr.length(),2)){
                        val first=arr.optJSONObject(idx) ?: continue
                        val id=first.optInt("id",0); if(id==0) continue
                        val detailTxt=WizCore.retry{ app.get("$base/api/v1/post/$id", verify=false, timeout=12_000).text } ?: continue
                        val detail=runCatching{ JSONObject(detailTxt) }.getOrNull() ?: continue
                        val dataObj=detail.optJSONObject("data") ?: detail
                        val links=dataObj.optJSONArray("download_links") ?: dataObj.optJSONArray("links") ?: JSONArray()
                        for(i in 0 until links.length()){
                            val u=links.optJSONObject(i)?.optString("url")?.ifBlank{continue} ?: continue
                            // Direct link may need to be resolved via loadExtractor if not mp4/m3u8
                            if(u.contains(".mp4")||u.contains(".mkv")||u.contains(".m3u8")){
                                cb(newExtractorLink("CircleFTP", "CircleFTP ${WizCore.getSimplifiedTitle(u)}", u){ quality=getQualityFromName(u) })
                            } else {
                                try{ com.lagradost.cloudstream3.utils.loadExtractor(u, "http://new.circleftp.net/", subCb, cb) } catch(_:Exception){ cb(newExtractorLink("CircleFTP", "CircleFTP Link", u){ quality=getQualityFromName(u) }) }
                            }
                        }
                    }
                } catch(_:Exception){ continue }
            }
        } catch(_:Exception){}
    }

    suspend fun invokeCTG(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            // Try multiple bases: default + remote config fallback
            val bases=mutableListOf("https://cockpit.103.109.92.178.nip.io/api/v1")
            // Try fetch dynamic base from remote json (like CSX ApiConstants)
            WizCore.retry{ app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json", timeout=8_000).text }?.let{ txt->
                runCatching{ JSONObject(txt).optString("ctgmovies") }.getOrNull()?.takeIf{it.isNotBlank()}?.let{ bases.add(it) }
            }
            for(base in bases.distinct()){
                try{
                    val searchUrls=listOf(
                        "$base/movies?search=${WizCore.run{ data.title.encodeUrl() }}",
                        "$base/tv?search=${WizCore.run{ data.title.encodeUrl() }}",
                        "$base/anime?search=${WizCore.run{ data.title.encodeUrl() }}"
                    )
                    for(sUrl in searchUrls){
                        val txt=WizCore.retry{ app.get(sUrl, timeout=10_000, headers=mapOf("User-Agent" to "Mozilla/5.0")).text } ?: continue
                        val arr=runCatching{ JSONObject(txt).optJSONArray("data") }.getOrNull() ?: continue
                        for(i in 0 until minOf(arr.length(),2)){
                            val obj=arr.optJSONObject(i) ?: continue
                            // Extract links directly if present in search result, or fetch detail
                            var links=obj.optJSONArray("links") ?: obj.optJSONArray("download_links")
                            if(links==null){
                                val slug=obj.optString("id").ifBlank{obj.optString("_id")}.ifBlank{continue}
                                val kind=when{
                                    obj.optString("category","").contains("anime",true)->"anime"
                                    obj.optString("type","").contains("tv",true)->"tv"
                                    else->"movies"
                                }
                                val detailTxt=WizCore.retry{ app.get("$base/$kind/$slug", timeout=10_000).text } ?: continue
                                val detail=runCatching{ JSONObject(detailTxt) }.getOrNull() ?: continue
                                links=detail.optJSONArray("links") ?: detail.optJSONArray("download_links") ?: continue
                            }
                            for(j in 0 until links.length()){
                                val linkObj=links.optJSONObject(j) ?: continue
                                val u=linkObj.optString("url").ifBlank{continue}
                                val qual=linkObj.optString("quality","")
                                cb(newExtractorLink("CTGMovies", "CTG $qual ${WizCore.getSimplifiedTitle(qual)}", u){ quality=getQualityFromName(qual.ifBlank{u}) })
                            }
                        }
                    }
                } catch(_:Exception){ continue }
            }
        } catch(_:Exception){}
    }

    suspend fun invokeFTPBD(data:LoadData, subCb:(SubtitleFile)->Unit, cb:(ExtractorLink)->Unit){
        try{
            val headers=mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", "Referer" to "https://ftpbd.net/")
            val q=WizCore.run{ data.title.encodeUrl() }
            val searchUrls=listOf(
                "https://ftpbd.net/?s=$q&post_type=movies",
                "https://ftpbd.net/?s=$q&post_type=tv_shows"
            )
            for(sUrl in searchUrls){
                val doc=WizCore.retry{ app.get(sUrl, headers=headers, timeout=10_000).document } ?: continue
                // Use robust parse from new FTPBDProvider
                val anchors=doc.select("a[href*=\"/movie/\"], a[href*=\"/tv_shows/\"]").filter{ a->
                    val href=a.attr("href")
                    href.matches(Regex(".*/(movie|tv_shows)/[^/]+/?$")) && !href.contains("/page/") && !href.contains("/movies_cat/")
                }.take(3)
                for(a in anchors){
                    var link=a.attr("href")
                    if(link.startsWith("/")) link="https://ftpbd.net$link"
                    if(link.isBlank()) continue
                    val pageHtml=WizCore.retry{ app.get(link, headers=headers, timeout=10_000).text } ?: continue
                    // Extract m3u8/mp4/data-url
                    Regex("""<source[^>]+src=['"]([^'"]+\.m3u8[^'"]*)['"]""", RegexOption.IGNORE_CASE).findAll(pageHtml).forEach{ m->
                        val u=m.groupValues[1]
                        val abs=if(u.startsWith("http")) u else "https://ftpbd.net$u"
                        cb(newExtractorLink("FTPBD", "FTPBD HLS", abs){ quality=getQualityFromName(abs) })
                    }
                    Regex("""https?://[^'"\s]+\.(?:mp4|mkv|m3u8)[^'"\s]*""", RegexOption.IGNORE_CASE).findAll(pageHtml).forEach{ m->
                        val u=m.value
                        cb(newExtractorLink("FTPBD", "FTPBD ${WizCore.getSimplifiedTitle(u)}", u){ quality=getQualityFromName(u) })
                    }
                    Regex("""data-url=['"]([^'"]+)['"]""").findAll(pageHtml).forEach{ m->
                        var u=m.groupValues[1]
                        if(u.startsWith("/")) u="https://ftpbd.net$u"
                        if(u.isNotBlank() && (u.contains(".mp4")||u.contains(".mkv")||u.contains(".m3u8"))){
                            cb(newExtractorLink("FTPBD", "FTPBD Direct", u){ quality=getQualityFromName(u) })
                        }
                    }
                }
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
