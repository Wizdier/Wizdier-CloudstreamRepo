version = 1

cloudstream {
    language = "en"

    description = "NTVStream (ntv.cx) — 6 live sports servers in one plugin: Kobra, Raptor, Falcon, Phoenix, Titan, Viper. Toggle individual servers in settings. Falcon server uses direct m3u8 extraction (no WebView needed — works on old Android TVs)."
    authors = listOf("Wizdier")

    status = 1

    tvTypes = listOf(
        "Livestreams"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=ntv.cx&sz=%size%"
}

android {
    namespace = "com.wizdier.ntvstream"
}
