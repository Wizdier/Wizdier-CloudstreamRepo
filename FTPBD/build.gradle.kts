version = 3

cloudstream {
    language = "bn"

    description = "FTPBD — movies and TV shows from ftpbd.net with HLS/direct FTP links."
    authors = listOf("Wizdier")

    /**
     * Status:
     *   0 = Down · 1 = Ok · 2 = Slow · 3 = Beta
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AnimeMovie"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=ftpbd.net&sz=%size%"
}

android {
    namespace = "com.wizdier.ftpbd"
}
