package eu.kanade.tachiyomi.extension.all.ehentai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryPreferencesTest {
    @Test
    fun everyPreviousLanguageCanImportItsAccountAndOriginalImageSetting() {
        for (language in legacyGallerySources.keys) {
            val previous = legacyGallerySettings(
                language,
                mapOf(
                    MEMBER_ID to "member", PASS_HASH to "pass", IGNEOUS to "session",
                    FORCE_EH to false, "ORIGINAL_IMAGE_$language" to true,
                    "ENFORCE_LANGUAGE_$language" to true,
                ),
            )
            assertEquals(
                mapOf(MEMBER_ID to "member", PASS_HASH to "pass", IGNEOUS to "session", FORCE_EH to false, ORIGINAL_IMAGE to true),
                gallerySettingsToMigrate(emptyMap<String, Any>(), listOf(previous)),
            )
        }
    }

    @Test
    fun identicalSettingsMergeWhileUnusedSourcesDoNotCreateConflicts() {
        val previous = legacyGallerySources.keys.map { language ->
            legacyGallerySettings(language, mapOf(MEMBER_ID to "member", PASS_HASH to "pass"))
        } + legacyGallerySettings("other", emptyMap<String, Any>())
        assertEquals("member", gallerySettingsToMigrate(emptyMap<String, Any>(), previous)?.get(MEMBER_ID))
        assertEquals(emptyMap<String, Any>(), gallerySettingsToMigrate(emptyMap<String, Any>(), emptyList()))
    }

    @Test
    fun differentAccountsOrImageSettingsRequireAnExplicitChoice() {
        val first = legacyGallerySettings("ja", mapOf(MEMBER_ID to "first", PASS_HASH to "first-pass"))
        val second = legacyGallerySettings("zh", mapOf(MEMBER_ID to "second", PASS_HASH to "second-pass"))
        assertNull(gallerySettingsToMigrate(emptyMap<String, Any>(), listOf(first, second)))
        val original = legacyGallerySettings("en", mapOf(MEMBER_ID to "first", PASS_HASH to "first-pass", "ORIGINAL_IMAGE_en" to true))
        assertNull(gallerySettingsToMigrate(emptyMap<String, Any>(), listOf(first, original)))
    }

    @Test
    fun currentSettingsArePreservedAndPartialAccountsNeverBorrowLegacyCredentials() {
        val previous = listOf(legacyGallerySettings("pt-BR", mapOf(MEMBER_ID to "old", PASS_HASH to "old-pass", IGNEOUS to "old-session", FORCE_EH to false)))
        val current = mapOf(MEMBER_ID to "current", FORCE_EH to true, ORIGINAL_IMAGE to true)
        assertEquals(emptyMap<String, Any>(), gallerySettingsToMigrate(current, previous))
    }
}
