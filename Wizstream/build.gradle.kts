version = 1

cloudstream {
    description = "Wizstream — TMDB multi-catalogue provider fetching from user's 4 repo extensions + direct vid sources."
    authors = listOf("Wizdier")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "Cartoon",
        "AsianDrama",
        "OVA"
    )
    iconUrl = "https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/main/icons/WizstreamIcon.png"
}

android {
    namespace = "com.wizdier.wizstream"
}
