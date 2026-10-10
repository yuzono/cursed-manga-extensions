package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

/**
 * UriGroup
 */
open class UriGroup<V>(name: String, state: List<V>) :
    Filter.Group<V>(name, state),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        state.forEach {
            if (it is UriFilter) it.addToUri(builder)
        }
    }
}
