package com.wizdier

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * NowHDTime Cloudstream Plugin
 * 
 * Entry point for the extension. Registers the main provider
 * and all video extractors in order of specificity.
 */
@CloudstreamPlugin
class NowHDTimePlugin : Plugin() {
    
    override fun load(context: Context) {
        // ═══════════════════════════════════════════════════════════════
        // Main Catalogue Provider
        // ═══════════════════════════════════════════════════════════════
        registerMainAPI(NowHDTimeProvider())

        // ═══════════════════════════════════════════════════════════════
        // Video Extractors
        // Order matters: more specific extractors should come first
        // ═══════════════════════════════════════════════════════════════
        
        // Native site player (handles internal embeds)
        registerExtractorAPI(NowHDTimeStreamExtractor())
        
        // Anime-specific embed host (AniList-based)
        registerExtractorAPI(NontongoExtractor())
        
        // File hosting services
        registerExtractorAPI(DroploadExtractor())
        registerExtractorAPI(VidHideExtractor())
        registerExtractorAPI(FileLionsExtractor())
        registerExtractorAPI(StreamWishExtractor())
        
        // General video hosts
        registerExtractorAPI(DoodExtractor())
        registerExtractorAPI(MixdropExtractor())
        registerExtractorAPI(VoeExtractor())
        registerExtractorAPI(UpstreamExtractor())
        
        // Fallback generic extractor
        registerExtractorAPI(GenericM3u8Extractor())
    }
}
