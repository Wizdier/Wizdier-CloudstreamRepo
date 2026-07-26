version = 58

cloudstream {
    description = "WizstreamAnime — THE anime catalogue (in-app source " +
        "name: \"Wizstream-Anime\"), PURE AniList metadata: every piece " +
        "(titles, posters, banners, summaries, genres, scores, trailers, " +
        "full Japanese voice-actor cast, MAL/AniList tracking) comes from " +
        "AniList alone — zero TMDB calls, zero TMDB fields. Episode " +
        "titles/thumbnails use AniList's own licensed-streaming feed per " +
        "entry; where that feed is missing rows honestly show " +
        "\"Episode N\" with the show poster. Stacked CircleFTP-style " +
        "multi-season mega pages (cours merged: Attack on Titan Season 3 " +
        "= all 22 episodes). Links resolve through the same engine as " +
        "Wizstream: BDIX sources (Cineplex BD, FTPBD, Circle FTP, " +
        "CTGMovies, FM FTP, Mediaserver) + web sources (Cineby, Bingr, " +
        "Moonflix) + the Vid[x] embed family + anime streaming sources " +
        "(AniZone, Mkissa-AllAnime, Miruro, AniChi)."
    authors = listOf("Wizdier")
    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA",
    )
    iconUrl = "https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/main/icons/WizstreamAnimeIcon.png"
}

android {
    namespace = "com.wizdier.wizstreamanime"
}

// ── Shared resolver engine ─────────────────────────────────────────────────
// WizstreamSources.kt + WizstreamExtractors.kt are the SAME link-scraping
// engine bundled by the Wizstream plugin. The single source of truth lives
// in ../Wizstream/src/main/kotlin/com/wizdier/ — this task mirrors the
// committed copies here before every build so the two plugins can never
// drift apart. (WizstreamAnimeSources.kt and WizstreamAnimeProvider.kt are
// THIS module's own: the provider is stripped to PURE AniList metadata.)
val syncSharedSources = tasks.register("syncSharedSources") {
    doLast {
        listOf("WizstreamSources.kt", "WizstreamExtractors.kt").forEach { f ->
            val src = rootProject.file("Wizstream/src/main/kotlin/com/wizdier/$f")
            val dst = file("src/main/kotlin/com/wizdier/$f")
            if (!dst.exists() || src.readText() != dst.readText()) {
                src.copyTo(dst, overwrite = true)
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(syncSharedSources) }
