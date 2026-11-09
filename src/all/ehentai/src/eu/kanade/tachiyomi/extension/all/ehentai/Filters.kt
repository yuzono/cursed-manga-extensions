package eu.kanade.tachiyomi.extension.all.ehentai

import android.net.Uri
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal fun getFilters(enforceLanguage: Boolean) = FilterList(
    EnforceLanguageFilter(enforceLanguage),
    Favorites(),
    Watched(),
    GenreGroup(),
    Filter.Header("Separate tags with commas (,)"),
    Filter.Header("Prepend with dash (-) to exclude"),
    Filter.Header("Use 'Female Tags' or 'Male Tags' for specific categories. 'Tags' searches all categories."),
    TextFilter("Tags", "tag"),
    TextFilter("Female Tags", "female"),
    TextFilter("Male Tags", "male"),
    AdvancedGroup(),
)

internal interface UriFilter {
    fun addToUri(builder: Uri.Builder)
}

internal open class UriGroup<V>(name: String, state: List<V>) :
    Filter.Group<V>(name, state),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        state.forEach {
            if (it is UriFilter) it.addToUri(builder)
        }
    }
}

internal open class TextFilter(name: String, val type: String, val specific: String = "") : Filter.Text(name)

internal class Watched :
    Filter.CheckBox("Watched List"),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state) {
            builder.appendPath("watched")
        }
    }
}

internal class Favorites :
    Filter.CheckBox("Favorites"),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state) {
            builder.appendPath("favorites.php")
        }
    }
}

internal class GenreOption(name: String, private val genreId: String) :
    Filter.CheckBox(name, false),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        builder.appendQueryParameter("f_$genreId", if (state) "1" else "0")
    }
}

internal class GenreGroup :
    UriGroup<GenreOption>(
        "Genres",
        listOf(
            GenreOption("Dōjinshi", "doujinshi"),
            GenreOption("Manga", "manga"),
            GenreOption("Artist CG", "artistcg"),
            GenreOption("Game CG", "gamecg"),
            GenreOption("Western", "western"),
            GenreOption("Non-H", "non-h"),
            GenreOption("Image Set", "imageset"),
            GenreOption("Cosplay", "cosplay"),
            GenreOption("Asian Porn", "asianporn"),
            GenreOption("Misc", "misc"),
        ),
    )

internal class AdvancedOption(name: String, private val param: String, defValue: Boolean = false) :
    Filter.CheckBox(name, defValue),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state) {
            builder.appendQueryParameter(param, "on")
        }
    }
}

internal open class PageOption(name: String, private val queryKey: String) :
    Filter.Text(name),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state.isNotBlank()) {
            if (builder.build().getQueryParameters("f_sp").isEmpty()) {
                builder.appendQueryParameter("f_sp", "on")
            }

            builder.appendQueryParameter(queryKey, state.trim())
        }
    }
}

internal class MinPagesOption : PageOption("Minimum Pages", "f_spf")
internal class MaxPagesOption : PageOption("Maximum Pages", "f_spt")

internal class RatingOption :
    Filter.Select<String>(
        "Minimum Rating",
        arrayOf(
            "Any",
            "2 stars",
            "3 stars",
            "4 stars",
            "5 stars",
        ),
    ),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state > 0) {
            builder.appendQueryParameter("f_srdd", (state + 1).toString())
            builder.appendQueryParameter("f_sr", "on")
        }
    }
}

// Explicit type arg for listOf() to workaround this: KT-16570
internal class AdvancedGroup :
    UriGroup<Filter<*>>(
        "Advanced Options",
        listOf(
            AdvancedOption("Search Gallery Name", "f_sname", true),
            AdvancedOption("Search Gallery Tags", "f_stags", true),
            AdvancedOption("Search Gallery Description", "f_sdesc"),
            AdvancedOption("Search Torrent Filenames", "f_storr"),
            AdvancedOption("Only Show Galleries With Torrents", "f_sto"),
            AdvancedOption("Search Low-Power Tags", "f_sdt1"),
            AdvancedOption("Search Downvoted Tags", "f_sdt2"),
            AdvancedOption("Show Expunged Galleries", "f_sh"),
            RatingOption(),
            MinPagesOption(),
            MaxPagesOption(),
        ),
    )

internal class EnforceLanguageFilter(default: Boolean) : Filter.CheckBox("Enforce language", default)
