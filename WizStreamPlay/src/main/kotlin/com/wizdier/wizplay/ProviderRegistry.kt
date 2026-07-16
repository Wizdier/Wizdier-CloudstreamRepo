package com.wizdier.wizplay

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile

/**
 * ProviderRegistry – like CSX ProviderRegistry.kt but for WizPlay vid[x] + your extensions
 * Each entry filtered by isAnime, isMovie etc, executed concurrently with semaphore
 */

data class ProviderDef(
    val key:String,
    val displayName:String,
    val isAnime:Boolean=false,
    val isTorrent:Boolean=false,
    val execute: suspend (WizExtractors.LoadData, (SubtitleFile)->Unit, (ExtractorLink)->Unit)->Unit
)

object ProviderRegistry {
    val builtInProviders = listOf(
        // vid[x] family – x = src, nest, rock, fast, easy, zee, prime
        ProviderDef("p_vidSrcTo", "VidSrcTo"){ data, subCb, cb-> WizExtractors.invokeVidSrcTo(data, subCb, cb) },
        ProviderDef("p_vidSrcCc", "VidSrcCc"){ data, subCb, cb-> WizExtractors.invokeVidSrcCc(data, subCb, cb) },
        ProviderDef("p_embedSu", "EmbedSu"){ data, subCb, cb-> WizExtractors.invokeEmbedSu(data, subCb, cb) },
        ProviderDef("p_vidLinkPro", "VidLinkPro"){ data, subCb, cb-> WizExtractors.invokeVidLinkPro(data, subCb, cb) },
        ProviderDef("p_autoEmbed", "AutoEmbed"){ data, subCb, cb-> WizExtractors.invokeAutoEmbed(data, subCb, cb) },
        ProviderDef("p_vidNest", "VidNest"){ data, subCb, cb-> WizExtractors.invokeVidNest(data, subCb, cb) },
        ProviderDef("p_vidRock", "VidRock"){ data, subCb, cb-> WizExtractors.invokeVidRock(data, subCb, cb) },
        ProviderDef("p_vidFast", "VidFast"){ data, subCb, cb-> WizExtractors.invokeVidFast(data, subCb, cb) },
        ProviderDef("p_vidEasy", "VidEasy (Wings)"){ data, subCb, cb-> WizExtractors.invokeVidEasy(data, subCb, cb) },
        ProviderDef("p_vidZee", "VidZee"){ data, subCb, cb-> WizExtractors.invokeVidZee(data, subCb, cb) },
        ProviderDef("p_primeSrc", "PrimeSrc"){ data, subCb, cb-> WizExtractors.invokePrimeSrc(data, subCb, cb) },

        // Your own extensions as sources
        ProviderDef("p_cineplexBD", "CineplexBD"){ data, subCb, cb-> WizExtractors.invokeCineplexBD(data, subCb, cb) },
        ProviderDef("p_circleFTP", "CircleFTP"){ data, subCb, cb-> WizExtractors.invokeCircleFTP(data, subCb, cb) },
        ProviderDef("p_ctgMovies", "CTGMovies"){ data, subCb, cb-> WizExtractors.invokeCTG(data, subCb, cb) },
        ProviderDef("p_ftpBD", "FTPBD"){ data, subCb, cb-> WizExtractors.invokeFTPBD(data, subCb, cb) },
    )

    // For anime provider, we reuse same list but anime flag could filter
    val animeProviders = builtInProviders // plus anime-specific could be added

    val keys get() = builtInProviders.map{it.key}
    val namesMap get() = builtInProviders.associate{it.key to it.displayName}
}
