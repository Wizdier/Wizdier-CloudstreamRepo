// use an integer for version numbers
version = 1

cloudstream {
    language = "bn"
    // All of these properties are optional, you can safely remove them

    description = "NowHDTime - Premium Bangla & Multi-language Movie/Series Streaming"
    authors = listOf("Wizdier")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama",
        "Anime",
        "Cartoon"
    )

    iconUrl = "https://nowhdtime.com.bd/wp-content/uploads/2024/01/cropped-favicon-32x32.png"

    requiresResources = false

    isSensitive = false
}

android {
    compileSdkVersion(33)

    defaultConfig {
        minSdkVersion(21)
        targetSdkVersion(33)
    }
}

dependencies {
    implementation("com.lagradost:cloudstream3:pre-release")
}