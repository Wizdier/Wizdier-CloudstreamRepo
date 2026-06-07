version = 1

cloudstream {
    language = "bn"

    description = "NowHDTime – Movies, TV Shows, Anime & Asian Dramas.\n" +
        "Bangla · Hindi · English · Korean · Japanese and more."

    authors = listOf("Wizdier")

    /**
     * Status:
     * 0 = Down   1 = OK   2 = Slow   3 = Beta
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "AsianDrama"
    )

    iconUrl = "https://i.postimg.cc/bvKvW66z/nowhd-logo.png"
}

android {
    namespace = "com.wizdier.nowhdtime"
}
