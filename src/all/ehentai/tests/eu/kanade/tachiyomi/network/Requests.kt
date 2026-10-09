@file:JvmName("RequestsKt")

package eu.kanade.tachiyomi.network

import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.Request

// Test host implementation of the extension API's GET stub.
fun GET(
    url: String,
    headers: Headers = Headers.Builder().build(),
    cache: CacheControl = CacheControl.Builder().build(),
): Request = Request.Builder().url(url).headers(headers).cacheControl(cache).build()
