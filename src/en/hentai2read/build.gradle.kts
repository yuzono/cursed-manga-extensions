plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hentai2Read"
    className = "Hentai2Read"
    versionCode = 19
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    deeplink {
        host("hentai2read.com")
        path("/..*")
    }
}
