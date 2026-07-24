version = 1

cloudstream {
    language = "bn"

    description = "FM FTP — movies, TV shows and anime from the fmftp.net mediaserver (Cinefy API + file index)."
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

    iconUrl = "https://www.google.com/s2/favicons?domain=fmftp.net&sz=%size%"
}

android {
    namespace = "com.wizdier.fmftp"
}
