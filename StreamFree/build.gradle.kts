version = 1

cloudstream {
    language = "en"

    description = "StreamFree — Free NBA, NFL, UFC, soccer, tennis, and live sports streams."
    authors = listOf("Wizdier")

    /**
     * Status:
     *   0 = Down · 1 = Ok · 2 = Slow · 3 = Beta
     */
    status = 1

    tvTypes = listOf(
        "Livestreams"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=streamfree.app&sz=%size%"
}

android {
    namespace = "com.wizdier.streamfree"
}
