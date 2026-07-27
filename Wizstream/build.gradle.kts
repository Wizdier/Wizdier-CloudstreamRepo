version = 69

cloudstream {
    description = "Wizstream — the main catalogue, StreamPlay style. " +
        "TMDB hosts identity (titles, posters, cast, crew) for movies, " +
        "TV series, Asian dramas & cartoons, plus a Trending Anime row; " +
        "every Japanese-animation page is enriched from AniList " +
        "(MAL/AniList/Kitsu tracking ids, AniList banner art, Japanese " +
        "voice-actor cast) and resolves links through the " +
        "WizstreamSources engine: BDIX lookups (Cineplex BD, FTPBD, " +
        "Circle FTP, CTGMovies, FM FTP, Mediaserver) plus web sources " +
        "(Cineby, Bingr, Moonflix) and the Vid[x] embed family. " +
        "The pure-AniList anime catalogue is the separate WizstreamAnime " +
        "module (\"Wizstream-Anime\" in-app)."
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
