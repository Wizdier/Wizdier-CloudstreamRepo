// ───────────────────────────────────────────────────────────────────────────
// Per-plugin Gradle config for the "CTG Movies" Cloudstream extension.
// ───────────────────────────────────────────────────────────────────────────

version = 1

cloudstream {
    language = "en"

    description = "Stream movies, anime, web-series & TV from CTGMovies.com"
    authors = listOf("Wizdier")

    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://ctgmovies.com/favicon.ico"
}

android {
    namespace = "com.wizdier.ctgmovies"
}
