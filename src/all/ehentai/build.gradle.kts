import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "E-Hentai"
    versionCode = 41
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        id = 1713178126840476467
        lang = "all"
        baseUrl = "https://e-hentai.org"
    }

    deeplink {
        host("e-hentai.org")
        host("exhentai.org")
        path("/g/..*/..*")
    }
}

android {
    sourceSets.named("test") {
        kotlin.directories.add("tests")
    }
}

// Source metadata is generated for the main variants; tests do not declare sources.
tasks.matching { it.name.startsWith("ksp") && it.name.contains("UnitTest") }.configureEach {
    enabled = false
}

dependencies {
    testImplementation(libs.jsoup)
    testImplementation(libs.okhttp.core)
    testImplementation(libs.tachiyomi.lib.v14)
    testImplementation(libs.rxjava)
    testImplementation(libs.junit)
}
