// ───────────────────────────────────────────────────────────────────────────
// Per-plugin Gradle config for the "AnikotoTV" Cloudstream extension.
// ───────────────────────────────────────────────────────────────────────────

version = 1

cloudstream {
    language = "en"

    description = "Stream anime (Sub & Dub) from anikototv.to with rich metadata, MAL/AniList/Kitsu/Simkl tracking, and multi-server support."
    authors = listOf("Wizdier")

    /**
     * Status:
     *   0 = Down · 1 = Ok · 2 = Slow · 3 = Beta
     */
    status = 1

    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA",
        "Cartoon"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=anikototv.to&sz=%size%"
}

android {
    namespace = "com.wizdier.anikototv"
}