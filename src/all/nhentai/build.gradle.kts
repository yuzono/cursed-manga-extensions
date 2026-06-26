plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NHentai"
    className = "NHFactory"
    versionCode = 61
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    deeplink {
        host("nhentai.net")
        path("/g/..*")
    }
}

dependencies {
    implementation(project(":lib:randomua"))
}
