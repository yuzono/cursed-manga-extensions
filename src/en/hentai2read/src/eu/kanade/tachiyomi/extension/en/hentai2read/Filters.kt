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
            "All" to "",
            "Original" to "original",
            "Doujinshi" to "doujinshi",
            "Image sets" to "imageset",
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
            "Best match" to "",
            "Newest" to "newest",
            "Oldest" to "oldest",
            "Recently updated" to "latest",
            "Most read" to "most-viewed",
            "Highest rated" to "top-rated",
            "A to Z" to "alphabetical",
            "Most chapters" to "most-chapters",
            "Most pages" to "most-pages",
            "Fewest pages" to "fewest-pages",
        ),
    )

// The ranking's period tabs; Yesterday is the plain /ranking.
internal val RANKING_PERIODS = listOf(
    "Yesterday" to "",
    "Last week" to "week",
    "Last month" to "month",
    "All time" to "all",
)

internal class RankingFilter : Filter.Select<String>("Ranking", (listOf("None") + RANKING_PERIODS.map { it.first }).toTypedArray()) {
    val period get() = RANKING_PERIODS.getOrNull(state - 1)?.second
}

internal class TagFilter(val id: Int, name: String, val url: String) : Filter.TriState(name)

internal class TagGroup(name: String, tags: List<TagFilter>) : Filter.Group<TagFilter>(name, tags)

// Checked artists are sent with the included tags.
internal class ArtistFilter(val id: Int, name: String, val url: String) : Filter.CheckBox(name)

internal class ArtistGroup(artists: List<ArtistFilter>) : Filter.Group<ArtistFilter>("Top artists", artists)

// Unchecked, a book must have every included tag.
internal class MatchAnyFilter : Filter.CheckBox("Match any included tag")

// Artist, parody and character lists run to thousands of entries, so only these are offered in full.
internal val TAG_TYPES = listOf("category", "content")

internal fun hentai2readFilters(tags: List<TagDto>): FilterList {
    val filters = buildList<Filter<*>> {
        add(Filter.Header("Search also takes artist:name, tag:name and -tag:name. Quote names with spaces: artist:\"two words\""))
        add(Filter.Separator())
        add(Filter.Header("Without search text, a ranking, or a single included tag or artist, opens its own page; a ranking ignores the other filters"))
        add(RankingFilter())
        add(Filter.Separator())
        add(TypeFilter())
        add(StatusFilter())
        add(SortFilter())
        add(MatchAnyFilter())

        TAG_TYPES.forEach { type ->
            val group = tags.filter { it.type == type }.map { it.toFilter() }.sortedBy { it.name.lowercase() }
            if (group.isNotEmpty()) add(TagGroup(type.replaceFirstChar { it.uppercase() }, group))
        }

        // Kept in the site's order, most series first.
        val artists = tags.filter { it.type == "artist" }.map { it.toArtistFilter() }
        if (artists.isNotEmpty()) add(ArtistGroup(artists))
    }
    return FilterList(filters)
}
