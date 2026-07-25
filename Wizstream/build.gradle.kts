version = 51

cloudstream {
    description = "Wizstream — the TMDB catalogue as its own plugin. " +
        "TMDB powers identity (titles, posters, cast, crew) for movies, " +
        "TV series, Asian dramas & cartoons, and every title resolves links " +
        "through the WizstreamSources engine: BDIX lookups (Cineplex BD, " +
        "FTPBD, Circle FTP, CTGMovies, FM FTP, Mediaserver) plus web sources " +
        "(Cineby, Bingr, Moonflix), the Vid[x] family (vidsrc, vidnest, " +
        "vidplay, vidup, vidrock, vidfast, videasy) and the extended " +
        "VidSrc/2Embed/MultiEmbed/SuperEmbed/Gomo embeds. " +
        "MAL / AniList / Kitsu / Simkl tracking. " +
        "Anime lives in the separate Wizstream-Anime plugin."
    authors = listOf("Wizdier")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama",
        "Cartoon",
        "Anime",
        "AnimeMovie",
    )
    iconUrl = "https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/main/icons/WizstreamIcon.png"
}

android {
    namespace = "com.wizdier.wizstream"
}
