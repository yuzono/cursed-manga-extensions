plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Pururin"
    versionCode = 11
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    listOf("all", "en", "ja").forEach { sourceLang ->
        source {
            lang = sourceLang
            baseUrl = "https://pururin.me"
        }
    }
}
