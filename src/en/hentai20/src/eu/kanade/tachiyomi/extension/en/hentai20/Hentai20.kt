package eu.kanade.tachiyomi.extension.en.hentai20

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.OkHttpClient

@Source
abstract class Hentai20 : MangaThemesia() {
    override fun OkHttpClient.Builder.configureClient() = rateLimit(1)
}
