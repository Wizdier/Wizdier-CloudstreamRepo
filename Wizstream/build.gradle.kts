version = 89

cloudstream {
    description = "Wizstream — ONE install, BOTH catalogues (v85). " +
        "Main catalogue: movies, TV series, Asian drama & cartoons from " +
        "TMDB. Anime catalogue: a dedicated pure-AniList catalogue with " +
        "per-season entries, Japanese voice-actor cast and correct " +
        "multi-season/cours/special episode mapping — no second extension " +
        "to install and no second module in the repo. Links resolve through the shared WizstreamSources " +
        "engine: BDIX servers (Circle FTP, Cineplex BD, FTPBD, CTGMovies, " +
        "FM FTP, Mediaserver), web sources (Cineby, Bingr, Moonflix), the " +
        "Vid[x] embed family and seven anime-web sources. Dead links are " +
        "probed and pruned before playback. AniList/MAL/Kitsu/Simkl " +
        "tracking. Optional Wyzie Subs (subtitles for BDIX .mkv files) and " +
        "MDBList ratings — add your own free key in Open Settings."
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
