package eu.kanade.tachiyomi.extension.all.nhentai

object NHUtils {
    fun getArtists(data: Hentai): String = data.tags.filter { it.type == "artist" }.joinToString(", ") { it.name }

    fun getGroups(data: Hentai): String? = data.tags.filter { it.type == "group" }.joinToString { it.name }.takeIf { it.isNotBlank() }

    fun getTagDescription(data: Hentai): String {
        val tagMap = data.tags.groupBy { it.type }
        return buildString {
            tagMap["category"]?.joinToString { it.name }?.let {
                append("Categories: ", it, "\n")
            }
            tagMap["parody"]?.joinToString { it.name }?.let {
                append("Parodies: ", it, "\n")
            }
            tagMap["character"]?.joinToString { it.name }?.let {
                append("Characters: ", it, "\n")
            }
            append("\n")
        }
    }

    fun getTags(data: Hentai): String = data.tags.filter { it.type == "tag" }.map { it.name }.sorted().joinToString()
}
