@file:JvmName("JsoupExtensionsKt")

package eu.kanade.tachiyomi.util

import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// Test host implementation of the extension API's response parser stub.
fun Response.asJsoup(html: String? = null): Document = Jsoup.parse(html ?: body.string(), request.url.toString())
