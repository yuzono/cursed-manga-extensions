package eu.kanade.tachiyomi.extension.en.hentai2read

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal open class QueryPartFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val value get() = options[state].second
}

internal class TypeFilter :
    QueryPartFilter(
        "Type",
        listOf(
            "Any" to "",
            "Original" to "original",
            "Doujinshi" to "doujinshi",
            "Image sets" to "imageset",
            "Videos" to "video",
        ),
    )

internal class StatusFilter :
    QueryPartFilter(
        "Status",
        listOf(
            "Any status" to "",
            "Ongoing" to "ongoing",
            "Completed" to "completed",
        ),
    )

internal class SortFilter :
    QueryPartFilter(
        "Sort by",
        listOf(
            "Recently updated" to "",
            "Newest" to "newest",
            "Most read" to "most-viewed",
            "Highest rated" to "top-rated",
            "Top yesterday" to "yesterday",
            "Top last week" to "last-week",
            "Top last month" to "last-month",
            "A to Z" to "alphabetical",
        ),
    )

// Include maps to `with`, exclude maps to `without`.
internal class TagFilter(val id: Int, name: String) : Filter.TriState(name)

internal class TagGroup(name: String, tags: List<TagFilter>) : Filter.Group<TagFilter>(name, tags)

// Tag types offered in the filter. Artist, parody and character lists run to thousands of entries.
internal val TAG_TYPES = listOf("category", "content")

internal fun hentai2readFilters(tags: List<TagDto>): FilterList {
    val filters = buildList<Filter<*>> {
        add(TypeFilter())
        add(StatusFilter())
        add(SortFilter())

        if (tags.isEmpty()) {
            add(Filter.Separator())
            add(Filter.Header("Press 'Reset' to load tags"))
        }

        // The groups are always present, empty until the tag data has been fetched.
        TAG_TYPES.forEach { type ->
            val label = type.replaceFirstChar { it.uppercase() }
            val group = tags.filter { it.type == type }.map { it.toFilter() }.sortedBy { it.name.lowercase() }
            add(TagGroup(label, group))
        }
    }
    return FilterList(filters)
}
