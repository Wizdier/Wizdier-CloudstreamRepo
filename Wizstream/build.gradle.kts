version = 98

cloudstream {
    description = "Wizstream — ONE install, BOTH catalogues (v85). " +
        "Main catalogue: movies, TV series, Asian drama & cartoons from " +
        "TMDB. Anime catalogue: a dedicated pure-AniList catalogue with " +
        "per-season entries, Japanese voice-actor cast and correct " +
        "multi-season/cours/special episode mapping — no second extension " +
        "to install and no second module in the repo. Links resolve through the shared WizstreamSources " +
        "engine: BDIX servers (Circle FTP, Cineplex BD, FTPBD, CTGMovies, " +
        "FM FTP, Mediaserver), web sources (Cineby, Bingr, Moonflix, " +
        "CineJoy, ShuttleTV, M4UHD, CinemaOS), the Vid[x] embed family and the AniNeko, KickAssAnime, " +
        "AnimeX, Aniwaves, Anikoto, AniZone, AnimeStream, AniBD, AniDB.app, " +
        "AniHQ, 2Dhive, Anikage and ToonStream anime sites. Dead links are " +
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
