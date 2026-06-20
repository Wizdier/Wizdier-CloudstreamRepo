version = 1

cloudstream {
    language = "en"

    description = "AnikotoTV — Watch anime online free with Sub & Dub, no ads. Latest episodes, ongoing series, movies, and upcoming releases."
    authors = listOf("Wizdier")

    /**
     * Status:
     *   0 = Down · 1 = Ok · 2 = Slow · 3 = Beta
     */
    status = 1

    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=anikototv.to&sz=%size%"
}

android {
    namespace = "com.wizdier.anikototv"
}