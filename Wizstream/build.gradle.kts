version = 81

cloudstream {
    description = "Wizstream — ONE install, BOTH catalogues (v81). " +
        "Main catalogue: movies, TV series, Asian drama & cartoons from " +
        "TMDB. Anime catalogue: a dedicated pure-AniList catalogue with " +
        "per-season entries, Japanese voice-actor cast and correct " +
        "multi-season/cours/special episode mapping — no second extension " +
        "to install. Links resolve through the shared WizstreamSources " +
        "engine: BDIX servers (Circle FTP, Cineplex BD, FTPBD, CTGMovies, " +
        "FM FTP, Mediaserver), web sources (Cineby, Bingr, Moonflix), the " +
        "Vid[x] embed family and seven anime-web sources. Dead links are " +
        "probed and pruned before playback. AniList/MAL/Kitsu/Simkl " +
        "tracking. Optional Wyzie Subs (subtitles for BDIX .mkv files) and " +
        "MDBList ratings — add your own free key in Open Settings."
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

// ── (v81) Anime catalogue mirrored IN ────────────────────────────────────
// Wizstream now registers BOTH catalogues, so it needs the anime provider
// compiled in. The single source of truth stays in the WizstreamAnime
// module; this copies it before every build so the two can never drift.
val syncAnimeProvider = tasks.register("syncAnimeProvider") {
    doLast {
        val src = rootProject.file(
            "WizstreamAnime/src/main/kotlin/com/wizdier/WizstreamAnimeProvider.kt"
        )
        val dst = file("src/main/kotlin/com/wizdier/WizstreamAnimeProvider.kt")
        if (src.exists() && (!dst.exists() || src.readText() != dst.readText())) {
            src.copyTo(dst, overwrite = true)
        }
    }
}
tasks.named("preBuild") { dependsOn(syncAnimeProvider) }

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
