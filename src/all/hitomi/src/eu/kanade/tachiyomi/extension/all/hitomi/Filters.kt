package eu.kanade.tachiyomi.extension.all.hitomi

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

fun getFilters(popularPeriod: String = "today"): FilterList = FilterList(
    LanguageFilter(),
    SelectFilter("Sort by", getSortsList, getSortsList.indexOfFirst { it.second == "popular" && it.third == popularPeriod }),
    TypeFilter("Types"),
    Filter.Separator(),
    Filter.Header("Separate tags with commas (,)"),
    Filter.Header("Prepend with dash (-) to exclude"),
    TextFilter("Groups", "group"),
    TextFilter("Artists", "artist"),
    TextFilter("Series", "series"),
    TextFilter("Characters", "character"),
    TextFilter("Male Tags", "male"),
    TextFilter("Female Tags", "female"),
    Filter.Header("Please don't put Female/Male tags here, they won't work!"),
    TextFilter("Tags", "tag"),
)

internal val hitomiLanguages = listOf(
    "All languages" to "all",
    "English" to "english",
    "Indonesian" to "indonesian",
    "Javanese" to "javanese",
    "Catalan" to "catalan",
    "Cebuano" to "cebuano",
    "Czech" to "czech",
    "Danish" to "danish",
    "German" to "german",
    "Estonian" to "estonian",
    "Spanish" to "spanish",
    "Esperanto" to "esperanto",
    "French" to "french",
    "Italian" to "italian",
    "Hindi" to "hindi",
    "Hungarian" to "hungarian",
    "Polish" to "polish",
    "Portuguese" to "portuguese",
    "Vietnamese" to "vietnamese",
    "Turkish" to "turkish",
    "Russian" to "russian",
    "Ukrainian" to "ukrainian",
    "Arabic" to "arabic",
    "Korean" to "korean",
    "Chinese" to "chinese",
    "Japanese" to "japanese",
)

internal class LanguageFilter :
    Filter.Select<String>(
        "Language",
        arrayOf("Default language", *hitomiLanguages.map { it.first }.toTypedArray()),
    ) {
    fun getLanguage(defaultLanguage: String) = if (state == 0) defaultLanguage else hitomiLanguages[state - 1].second
}

internal open class TextFilter(name: String, val type: String) : Filter.Text(name)
internal open class SelectFilter(name: String, val vals: List<Triple<String, String?, String>>, state: Int = 0) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray(), state) {
    fun getArea() = vals[state].second
    fun getValue() = vals[state].third
}
internal class TypeFilter(name: String) :
    Filter.Group<CheckBoxFilter>(
        name,
        listOf(
            Pair("Anime", "anime"),
            Pair("Artist CG", "artistcg"),
            Pair("Doujinshi", "doujinshi"),
            Pair("Game CG", "gamecg"),
            Pair("Image Set", "imageset"),
            Pair("Manga", "manga"),
        ).map { CheckBoxFilter(it.first, it.second, true) },
    )
internal open class CheckBoxFilter(name: String, val value: String, state: Boolean) : Filter.CheckBox(name, state)

internal fun FilterList.canUseNozomiPage(query: String) = query.isBlank() && none {
    when (it) {
        is TextFilter -> it.state.isNotBlank()
        is TypeFilter -> it.state.any { type -> !type.state }
        is SelectFilter -> it.vals[it.state].first == "Random"
        else -> false
    }
}

internal fun FilterList.searchKey(query: String, language: String): List<String> = buildList {
    add(query.trim().lowercase())
    add(language)
    this@searchKey.forEach { filter ->
        when (filter) {
            is SelectFilter -> add(filter.vals[filter.state].first)
            is TypeFilter -> add(filter.state.filter { !it.state }.joinToString { it.value })
            is TextFilter -> add("${filter.type}:${filter.state}")
            else -> {}
        }
    }
}

private val getSortsList: List<Triple<String, String?, String>> = listOf(
    Triple("Date Added", null, "index"),
    Triple("Date Published", "date", "published"),
    Triple("Popular: Today", "popular", "today"),
    Triple("Popular: Week", "popular", "week"),
    Triple("Popular: Month", "popular", "month"),
    Triple("Popular: Year", "popular", "year"),
    Triple("Random", "popular", "year"),
)
