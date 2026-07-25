version = 51

cloudstream {
    description = "Wizstream Anime — the AniList catalogue as its own plugin. " +
        "AniList powers identity (titles, posters, cast, MAL/AniList/Kitsu/Simkl " +
        "tracking) and multi-season franchises render as stacked CircleFTP-style " +
        "mega pages (cours merged, e.g. Attack on Titan Season 3 = 22 episodes). " +
        "Links come from the same resolver engine as the Wizstream (TMDB) " +
        "plugin: BDIX lookups (Cineplex BD, FTPBD, Circle FTP, CTGMovies, " +
        "FM FTP, Mediaserver) plus web sources (Cineby, Bingr, Moonflix), " +
        "the Vid[x] embed family and dedicated anime streaming sources " +
        "(AniZone, Mkissa via AllAnime API, Miruro secure-pipe, AniChi via " +
        "mapper.nekostream.site)."
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
// engine bundled by the Wizstream (TMDB) plugin. The single source of
// truth lives in ../Wizstream/src/main/kotlin/com/wizdier/ — this task
// mirrors the committed copies here before every build so the two plugins
// can never drift apart.
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
