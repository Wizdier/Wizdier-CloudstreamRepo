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
        "OVA"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=new.circleftp.net&sz=%size%"
}

android {
    namespace = "com.wizdier.circleftp"
}
