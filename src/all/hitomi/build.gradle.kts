import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hitomi"
    versionCode = 41
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    listOf(
        "all", "en", "id", "jv", "ca",
        "ceb", "cs", "da", "de", "et",
        "es", "eo", "fr", "it", "hi",
        "hu", "pl", "pt", "vi", "tr",
        "ru", "uk", "ar", "ko", "zh", "ja",
    ).forEach { sourceLang ->
        source {
            lang = sourceLang
            baseUrl = "https://hitomi.la"
        }
    }
}
