import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hitomi"
    versionCode = 43
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        id = 690123758188633713
        lang = "all"
        baseUrl = "https://hitomi.la"
    }
}

android {
    sourceSets.named("test") {
        kotlin.directories.add("tests")
    }
}

tasks.matching { it.name.startsWith("ksp") && it.name.contains("UnitTest") }.configureEach {
    enabled = false
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.rxjava)
    testImplementation(libs.kotlin.json)
    testImplementation(libs.tachiyomi.lib.v14)
}
