plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hentai20"
    versionCode = 10
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    theme = "mangathemesia"

    source {
        lang = "en"
        baseUrl = "https://hentai20.io"
    }
}
