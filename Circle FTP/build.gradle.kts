version = 6

android {
    namespace = "com.wizdier"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField(
            "String",
            "TMDB_API",
            "\"${System.getenv("TMDB_API") ?: ""}\""
        )
    }
}

cloudstream {
    description = "Largest FTP site in Bangladesh"
    authors = listOf("Wizdier")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "OVA",
        "Cartoon",
        "AsianDrama",
        "Others",
        "Documentary",
    )
    language = "bn"
    iconUrl = "http://new.circleftp.net/static/media/logo.fce2c9029060a10687b8.png"
    isCrossPlatform = true
}
