// ───────────────────────────────────────────────────────────────────────────
// Per-plugin Gradle config for the "Circle FTP" Cloudstream extension.
//
// AGP 8+ REQUIREMENT — see the long explanation in Cineplex BD/build.gradle.kts.
// Each module must declare its own unique `namespace`.
//
// Previous bug fixed here:
//   • `buildFeatures { buildConfig = true }` was nested INSIDE `defaultConfig`
//     (illegal in AGP 8). The Kotlin source for this plugin never actually
//     reads BuildConfig (TMDB key + FTP IP mappings are hard-coded inside
//     CircleFtpProvider.kt), so the misleading plumbing has been removed
//     entirely. If you ever externalise those values, re-add a properly-
//     scoped `android { buildFeatures { buildConfig = true } }` block here.
// ───────────────────────────────────────────────────────────────────────────

version = 8

cloudstream {
    description = "Circle FTP — movies, TV series and anime with rich metadata."
    authors = listOf("Wizdier")

    /**
     * Status:
     *   0 = Down · 1 = Ok · 2 = Slow · 3 = Beta
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "Cartoon",
        "AsianDrama",
        "Documentary",
        "OVA",
        "Others"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=new.circleftp.net&sz=%size%"
}

android {
    namespace = "com.wizdier.circleftp"
}
