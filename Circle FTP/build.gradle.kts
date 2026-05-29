version = 24

android {
    namespace = "com.wizdier"
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "TMDB_API_KEY", "\"${System.getenv("TMDB_API") ?: "0b2d522346f5ecbafa42ae4b0141c774"}\"")
        // FTP domain→IP mappings — kept out of source code so internal IPs
        // aren't exposed in the public repository.  Override via env var or
        // leave the default (empty) to fall back to domain-only resolution.
        buildConfigField("String", "FTP_IP_OVERRIDES", "\"${System.getenv("FTP_IP_OVERRIDES") ?: ""}\"")
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
    iconUrl = "https://new.circleftp.net/static/media/logo.fce2c9029060a10687b8.png"
    isCrossPlatform = true
}
