version = 9

cloudstream {
    language = "bn"

    description = "Stream movies, anime, web-series & TV from cineplexbd.net."
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

    iconUrl = "https://www.google.com/s2/favicons?domain=cineplexbd.net&sz=%size%"
}

android {
    namespace = "com.wizdier.cineplexbd"
}
