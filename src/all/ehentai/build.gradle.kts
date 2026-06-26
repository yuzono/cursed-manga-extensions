plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "E-Hentai"
    className = "EHFactory"
    versionCode = 28
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    deeplink {
        host("e-hentai.org")
        host("exhentai.org")
        path("/g/..*/..*")
    }
}
