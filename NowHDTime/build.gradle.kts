// NowHDTime/build.gradle.kts
version = 1

cloudstream {
    language    = "bn"
    description = "Watch FREE Movies, TV Shows & Anime from NowHDTime (nowhdtime.com.bd). " +
                  "Multi-source extraction · MAL · AniList · Kitsu · Simkl tracking."
    authors     = listOf("Wizdier")

    /**
     * Status of the provider:
     * 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AsianDrama",
    )

    iconUrl = "https://nowhdtime.com.bd/favicon.ico"
}