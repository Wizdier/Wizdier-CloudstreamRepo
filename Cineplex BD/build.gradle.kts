// use an integer for version numbers
version = 1


cloudstream {
    language = "bn"
    // All of these properties are optional, you can safely remove them

    description = "Stream movies, anime, web-series & TV from cineplexbd.net"
    authors = listOf("Wizdier")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "Cartoon",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=cineplexbd.net&sz=%size%"
}
