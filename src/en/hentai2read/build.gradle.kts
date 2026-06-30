plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hentai2Read"
    versionCode = 19
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        lang = "en"
        baseUrl = "https://hentai2read.com"
    }

    deeplink {
        host("hentai2read.com")
        path("/..*")
    }
}
