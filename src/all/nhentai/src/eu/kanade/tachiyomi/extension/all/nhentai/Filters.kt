package eu.kanade.tachiyomi.extension.all.nhentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal fun getFilters(defaultSort: String?) = FilterList(
    Filter.Header("Separate tags with commas (,)"),
    Filter.Header("Prepend with dash (-) to exclude"),
    TagFilter(),
    CategoryFilter(),
    GroupFilter(),
    ArtistFilter(),
    ParodyFilter(),
    CharactersFilter(),
    Filter.Header("Uploaded valid units are h, d, w, m, y."),
    Filter.Header("example: (>20d)"),
    UploadedFilter(),
    Filter.Header("Filter by pages, for example: (>20)"),
    PagesFilter(),

    Filter.Separator(),
    SortFilter(SORT_OPTIONS.indexOfFirst { it.second == defaultSort }.coerceAtLeast(0)),
    OffsetPageFilter(),
    Filter.Header("Sort is ignored if favorites only"),
    FavoriteFilter(),
)

internal fun FilterList.combineQuery(): String = buildString {
    filterIsInstance<AdvSearchEntryFilter>().forEach { filter ->
        filter.state.split(",")
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { tag ->
                val y = !(filter.name == "Pages" || filter.name == "Uploaded")
                if (tag.startsWith("-")) append("-")
                append(filter.name, ':')
                if (y) append('"')
                append(tag.removePrefix("-"))
                if (y) append('"')
                append(" ")
            }
    }
}

internal class TagFilter : AdvSearchEntryFilter("Tags")
internal class CategoryFilter : AdvSearchEntryFilter("Categories")
internal class GroupFilter : AdvSearchEntryFilter("Groups")
internal class ArtistFilter : AdvSearchEntryFilter("Artists")
internal class ParodyFilter : AdvSearchEntryFilter("Parodies")
internal class CharactersFilter : AdvSearchEntryFilter("Characters")
internal class UploadedFilter : AdvSearchEntryFilter("Uploaded")
internal class PagesFilter : AdvSearchEntryFilter("Pages")
internal open class AdvSearchEntryFilter(name: String) : Filter.Text(name)

internal class OffsetPageFilter : Filter.Text("Offset results by # pages")

internal class FavoriteFilter : Filter.CheckBox("Show favorites only", false)

internal class SortFilter(default: Int) : UriPartFilter("Sort By", SORT_OPTIONS, default)

internal open class UriPartFilter(
    displayName: String,
    val vals: Array<Pair<String, String>>,
    state: Int,
) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
    fun toUriPart() = vals[state].second
}

internal val SORT_OPTIONS = arrayOf(
    Pair("Popular: All Time", "popular"),
    Pair("Popular: Month", "popular-month"),
    Pair("Popular: Week", "popular-week"),
    Pair("Popular: Today", "popular-today"),
    Pair("Recent", "date"),
)
