plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "E-Hentai"
    versionCode = 28
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    listOf(
        "ja", "en", "zh", "nl", "fr",
        "de", "hu", "it", "ko", "pl",
        "pt-BR", "ru", "es", "th", "vi",
        "none", "other",
    ).forEach { sourceLang ->
        source {
            lang = sourceLang
            baseUrl = "https://e-hentai.org"
            if (sourceLang == "pt-BR") id = 7151438547982231541
        }
    }

    deeplink {
        host("e-hentai.org")
        host("exhentai.org")
        path("/g/..*/..*")
    }
}
