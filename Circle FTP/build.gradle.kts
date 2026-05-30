// ───────────────────────────────────────────────────────────────────────────
// Per-plugin Gradle config for the "Circle FTP" Cloudstream extension.
//
// This file MUST define the BuildConfig fields the Kotlin code reads:
//   • BuildConfig.TMDB_API_KEY      (used in ApiClients.kt)
//   • BuildConfig.FTP_IP_OVERRIDES  (used in CircleFtpProvider.kt)
//
// IMPORTANT name mapping:
//   Your GitHub Actions workflow exports the env var named  TMDB_API,
//   but the code expects the BuildConfig field named        TMDB_API_KEY.
//   The buildConfigField() call below bridges that gap, so leaving the
//   workflow as-is (TMDB_API / FTP_IP_OVERRIDES) is fine.
// ───────────────────────────────────────────────────────────────────────────

// use an integer for version numbers
version = 5

cloudstream {
    // All of these properties are optional, you can safely remove them.

    description = "Circle FTP – movies, TV series and anime with rich metadata."
    authors = listOf("wizdier")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    // List of video source types. Users can filter for extensions in a given category.
    // You can find a complete list of the types here:
    // https://recloudstream.github.io/dokka/library/com.lagradost.cloudstream3/-tv-type/index.html
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "Cartoon",
        "AsianDrama",
        "Documentary",
        "OVA",
        "Others",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=new.circleftp.net&sz=%size%"
}

android {
    namespace = "com.wizdier"

    defaultConfig {
        // Enable BuildConfig generation for this module.
        buildFeatures {
            buildConfig = true
        }

        // Read secrets from the environment (set by the GitHub Actions workflow).
        // Fall back to empty strings locally so the project still compiles
        // without secrets configured.
        val tmdbApiKey = System.getenv("TMDB_API") ?: ""
        val ftpIpOverrides = System.getenv("FTP_IP_OVERRIDES") ?: ""

        // Escaped string literals — note the surrounding \"...\" is REQUIRED.
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "FTP_IP_OVERRIDES", "\"$ftpIpOverrides\"")
    }
}
