version = 76

cloudstream {
    description = "Wizstream — the main catalogue, StreamPlay style. " +
        "TMDB hosts identity (titles, posters, cast, crew) for movies, " +
        "TV series, Asian dramas & cartoons, plus a Trending Anime row; " +
        "every Japanese-animation page is enriched from AniList " +
        "(MAL/AniList/Kitsu tracking ids, AniList banner art, Japanese " +
        "voice-actor cast) and resolves links through the " +
        "WizstreamSources engine: BDIX lookups (Cineplex BD, FTPBD, " +
        "Circle FTP, CTGMovies, FM FTP, Mediaserver) plus web sources " +
        "(Cineby, Bingr, Moonflix) and the Vid[x] embed family. " +
        "The pure-AniList anime catalogue is the separate WizstreamAnime " +
        "module (\"Wizstream-Anime\" in-app)."
    authors = listOf("Wizdier")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama",
        "Cartoon",
        "Anime",
        "AnimeMovie",
    )
    iconUrl = "https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/main/icons/WizstreamIcon.png"
}

android {
    namespace = "com.wizdier.wizstream"
}

// ── Anime-web resolver mirror (v70) ───────────────────────────────────────
// WizstreamAnimeSources.kt (the 7 anime streaming resolvers: AniZone,
// Allmanga, AniChi, UniqueStream, AniNeko, ReANIME, TokyoInsider) is owned
// by the WizstreamAnime module — single source of truth, exactly like
// WizstreamSources.kt is owned HERE and mirrored the other way. Since v70
// Wizstream's anime pages are per-entry de-stacked (CircleFTP structure:
// every AniList entry its own group of entry-local episodes), so their
// rows resolve through the anime-web sites too — this task mirrors the
// file in before every build so both plugins ship identical resolvers.
val syncAnimeSources = tasks.register("syncAnimeSources") {
    doLast {
        val src = rootProject.file(
            "WizstreamAnime/src/main/kotlin/com/wizdier/WizstreamAnimeSources.kt"
        )
        val dst = file("src/main/kotlin/com/wizdier/WizstreamAnimeSources.kt")
        if (!dst.exists() || src.readText() != dst.readText()) {
            src.copyTo(dst, overwrite = true)
        }
    }
}
tasks.named("preBuild") { dependsOn(syncAnimeSources) }
