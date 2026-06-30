package eu.kanade.tachiyomi.extension.en.hentai20

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.OkHttpClient

@Source
abstract class Hentai20 : MangaThemesia() {

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(1)
        .build()
}
