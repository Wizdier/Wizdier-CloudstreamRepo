version = 30

cloudstream {
    description = "Wizstream — unified TMDB + AniList catalogue in one plugin. " +
        "Two providers share a single source resolver: the TMDB catalogue " +
        "(Wizstream) covers movies & TV; the AniList catalogue (Wizstream-Anime) " +
        "covers anime / OVA / movies. Both fetch links from the Vid[x] family " +
        "(vidsrc, vidnest, vidplay, vidup, vidrock, vidfast, videasy) plus the " +
        "extended VidSrc/2Embed/MultiEmbed/SuperEmbed/Gomo/SmashyStream/VAPlayer " +
        "embeds, bundled BDIX source lookups (Cineplex BD, FTPBD, Circle FTP, " +
        "CTGMovies), and dedicated anime streaming sources (AniZone, Mkissa via " +
        "AllAnime API, Miruro secure-pipe, AniChi via mapper.nekostream.site) " +
        "with MAL / AniList / Kitsu / Simkl tracking."
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
    // TMDB catalogue icon — the AniList catalogue reuses the same .cs3
    // icon space; Cloudstream picks the icon from the first registered
    // provider's source file. Users see two catalogue entries in-app.
    iconUrl = "https://raw.githubusercontent.com/Wizdier/Wizdier-CloudstreamRepo/main/icons/WizstreamIcon.png"
}

android {
    namespace = "com.wizdier.wizstream"
}
