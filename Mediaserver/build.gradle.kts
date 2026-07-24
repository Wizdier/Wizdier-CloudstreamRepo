version = 1

cloudstream {
    language = "bn"

    description = "Mediaserver — movies, series episodes and anime from the 103.225.94.27 WordPress video library (BDIX)."
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
        "AnimeMovie"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=103.225.94.27&sz=%size%"
}

android {
    namespace = "com.wizdier.mediaserver"
}
