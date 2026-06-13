// ───────────────────────────────────────────────────────────────────────────
// Per-plugin Gradle config for the "Cineplex BD" Cloudstream extension.
//
// AGP 8+ REQUIREMENT
// ──────────────────
// Every Android library module MUST declare its own `namespace`. Setting it
// only inside the root `subprojects { android { ... } }` block is NOT enough
// for AGP 8.7's LibraryVariantBuilderImpl — the variant builder reads the
// namespace from the module's own LibraryExtension before the root
// configuration lambda fully resolves, which produced this failure:
//
//   > Could not create an instance of type
//     com.android.build.api.variant.impl.LibraryVariantBuilderImpl.
//   > Namespace not specified. Specify a namespace in the module's build file
//
// Hence the explicit `android { namespace = "…" }` below. Each module gets a
// UNIQUE namespace to avoid R-class / manifest merge collisions with sibling
// plugins that share the `com.wizdier` package root.
// ───────────────────────────────────────────────────────────────────────────

version = 5

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

android {
    namespace = "com.wizdier.cineplexbd"
}
