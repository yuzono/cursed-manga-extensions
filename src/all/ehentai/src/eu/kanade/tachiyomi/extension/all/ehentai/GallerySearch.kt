package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.utils.firstInstanceOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal fun gallerySearchUrl(baseUrl: String, query: String, filters: FilterList): HttpUrl {
    val galleryList = filters.firstInstanceOrNull<GalleryListFilter>()
    if (galleryList?.path == "popular") return "$baseUrl/popular".toHttpUrl()
    var modifiedQuery = filters.firstInstanceOrNull<GalleryLanguageFilter>()?.addToQuery(query) ?: query
    filters.filterIsInstance<EHentai.TextFilter>().forEach { filter ->
        filter.state.split(',').filter(String::isNotBlank).forEach { tag ->
            val trimmed = tag.trim().lowercase()
            val tagName = trimmed.removePrefix("-")
            modifiedQuery += if (trimmed.startsWith('-')) {
                " -${filter.type}:\"$tagName\""
            } else {
                " ${filter.type}:\"$tagName\""
            }
        }
    }
    val builder = "$baseUrl/${galleryList?.path.orEmpty()}".toHttpUrl().newBuilder()
    if (modifiedQuery.isNotBlank()) builder.addQueryParameter("f_search", modifiedQuery.trim())
    filters.filterIsInstance<UriFilter>().forEach { it.addToUri(builder) }
    return builder.build()
}
