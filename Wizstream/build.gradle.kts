version = 54

cloudstream {
    description = "Wizstream — ONE unified plugin: the full catalogue + " +
        "metadata experience in the StreamPlay style. TMDB hosts the main " +
        "catalogue (movies, TV series, Asian drama, cartoons) and every " +
        "Japanese-animation page is enriched from AniList (MAL/AniList/" +
        "Kitsu tracking ids, AniList banner art, Japanese voice-actor " +
        "cast); the AniList catalogue with stacked CircleFTP-style " +
        "multi-season mega pages also ships inside the same package. " +
        "Every title resolves links through the WizstreamSources engine: " +
        "BDIX lookups (Cineplex BD, FTPBD, Circle FTP, CTGMovies, FM FTP, " +
        "Mediaserver) plus web sources (Cineby, Bingr, Moonflix), the " +
        "Vid[x] family (vidsrc, vidnest, vidplay, vidup, vidrock, vidfast, " +
        "videasy), the extended VidSrc/2Embed/MultiEmbed/SuperEmbed/Gomo " +
        "embeds, and anime streaming sources (AniZone, Mkissa-AllAnime, " +
        "Miruro, AniChi). MAL / AniList / Kitsu / Simkl tracking."
    authors = listOf("Wizdier")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama",
        "Cartoon",
        "Anime",
        "AnimeMovie",
        "OVA",
    )
    iconUrl = "https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/main/icons/WizstreamIcon.png"
}

android {
    namespace = "com.wizdier.wizstream"
}
