// ───────────────────────────────────────────────────────────────────────────
// Per-plugin metadata for the Cineplex BD extension.
//
// The android { namespace = ... } block is intentionally OMITTED here because
// the root build.gradle.kts derives a unique namespace per module from the
// folder name (`com.wizdier.cineplexbd`). Adding a manual override here would
// silently shadow that and risk collisions with sibling modules.
// ───────────────────────────────────────────────────────────────────────────

// Integer plugin version — bump on every release.
version = 2

cloudstream {
    language = "bn"

    description = "Stream movies, anime, web-series & TV from cineplexbd.net."
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
        "AnimeMovie",
        "Cartoon"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=cineplexbd.net&sz=%size%"
}
