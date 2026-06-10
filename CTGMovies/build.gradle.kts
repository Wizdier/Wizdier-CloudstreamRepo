version = 3

cloudstream {
    language = "bn"

    description = "CTGMovies — movies, TV shows and anime with account/token settings for protected content."
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

    iconUrl = "https://www.google.com/s2/favicons?domain=ctgmovies.com&sz=%size%"
}

android {
    namespace = "com.wizdier.ctgmovies"
}
s