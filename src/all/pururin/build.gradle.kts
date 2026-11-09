import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Pururin"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf("all", "en", "ja").forEach { sourceLang ->
        source {
            lang = sourceLang
            baseUrl = "https://pururin.me"
        }
    }

    deeplink {
        host("pururin.me")
        path("/gallery/..*")
    }
}
