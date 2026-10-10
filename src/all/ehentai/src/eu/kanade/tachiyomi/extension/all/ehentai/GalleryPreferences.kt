package eu.kanade.tachiyomi.extension.all.ehentai

import android.content.SharedPreferences

internal const val MEMBER_ID = "MEMBER_ID"
internal const val PASS_HASH = "PASS_HASH"
internal const val IGNEOUS = "IGNEOUS"
internal const val FORCE_EH = "FORCE_EH"
internal const val ORIGINAL_IMAGE = "ORIGINAL_IMAGE_all"
internal const val LEGACY_SETTINGS_IMPORTED = "LEGACY_SETTINGS_IMPORTED"

// IDs from the former language sources, including the explicitly assigned pt-BR ID.
internal val legacyGallerySources = mapOf(
    "ja" to 8100626124886895451L,
    "en" to 57122881048805941L,
    "zh" to 4678440076103929247L,
    "nl" to 1876021963378735852L,
    "fr" to 3955189842350477641L,
    "de" to 4348288691341764259L,
    "hu" to 773611868725221145L,
    "it" to 5759417018342755550L,
    "ko" to 825187715438990384L,
    "pl" to 6116711405602166104L,
    "pt-BR" to 7151438547982231541L,
    "ru" to 2171445159732592630L,
    "es" to 3032959619549451093L,
    "th" to 5980349886941016589L,
    "vi" to 6073266008352078708L,
    "none" to 5499077866612745456L,
    "other" to 6140480779421365791L,
)

private val credentialKeys = listOf(MEMBER_ID, PASS_HASH, IGNEOUS)
private val defaultSettings = mapOf(
    MEMBER_ID to "",
    PASS_HASH to "",
    IGNEOUS to "",
    FORCE_EH to true,
    ORIGINAL_IMAGE to false,
)

internal class LegacyGallerySettings(val language: String, val values: Map<String, Any>)

internal fun legacyGallerySettings(language: String, values: Map<String, *>): LegacyGallerySettings = LegacyGallerySettings(
    language,
    buildMap {
        credentialKeys.forEach { put(it, values[it] as? String ?: "") }
        put(FORCE_EH, values[FORCE_EH] as? Boolean ?: true)
        put(ORIGINAL_IMAGE, values["ORIGINAL_IMAGE_$language"] as? Boolean ?: false)
    }.takeUnless { it == defaultSettings }.orEmpty(),
)

internal fun gallerySettingsToMigrate(current: Map<String, *>, previous: List<LegacyGallerySettings>): Map<String, Any>? {
    val configurations = previous.map { it.values }.filter { it.isNotEmpty() }.distinct()
    if (configurations.isEmpty()) return emptyMap()
    // A unified source cannot choose between different accounts or conflicting settings.
    val selected = configurations.singleOrNull() ?: return null
    val keepCurrentAccount = credentialKeys.any { it in current }
    return selected.filterKeys { it !in current && !(keepCurrentAccount && it in credentialKeys) }
}

internal fun SharedPreferences.importGallerySettings(values: Map<String, Any>) {
    edit().apply {
        values.forEach { (key, value) ->
            when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
            }
        }
        putBoolean(LEGACY_SETTINGS_IMPORTED, true)
    }.apply()
}
