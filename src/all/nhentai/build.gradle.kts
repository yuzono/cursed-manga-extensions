plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NHentai"
    versionCode = 62
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    listOf("all", "en", "ja", "zh").forEach { sourceLang ->
        source {
            lang = sourceLang
            baseUrl = "https://nhentai.net"
            if (sourceLang == "all") id = 7309872737163460316
        }
    }

    deeplink {
        host("nhentai.net")
        path("/g/..*")
    }
}

dependencies {

    implementation(project(":lib:randomua"))
}
