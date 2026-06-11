// ───────────────────────────────────────────────────────────────────────────
// Per-plugin Gradle config for the "NowHDTime" Cloudstream extension.

version = 2

cloudstream {
    language = "en"

    description = "Stream movies, web-series, anime & TV from nowhdtime.com.bd with all servers."
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
        "Cartoon"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=nowhdtime.com.bd&sz=%size%"
}

android {
    namespace = "com.wizdier.nowhdtime"
}