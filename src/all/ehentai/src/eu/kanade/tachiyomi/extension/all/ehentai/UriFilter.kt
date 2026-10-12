package eu.kanade.tachiyomi.extension.all.ehentai

import okhttp3.HttpUrl

/**
 * Uri filter
 */
interface UriFilter {
    fun addToUri(builder: HttpUrl.Builder)
}
