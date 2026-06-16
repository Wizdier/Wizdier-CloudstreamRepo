version = 10
cloudstream {
    language = "en"

    description = "NTVStream — live sports streams with separate Kobra, Raptor, Falcon, Phoenix and Viper server providers."
    authors = listOf("Wizdier")

    /**
     * Status:
     *   0 = Down · 1 = Ok · 2 = Slow · 3 = Beta
     */
    status = 1

    tvTypes = listOf(
        "Livestreams"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=ntvs.cx&sz=%size%"
}

android {
    namespace = "com.wizdier.ntvstream"
}
