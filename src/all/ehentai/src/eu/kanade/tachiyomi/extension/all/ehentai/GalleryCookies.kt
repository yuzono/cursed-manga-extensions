package eu.kanade.tachiyomi.extension.all.ehentai

import okhttp3.OkHttpClient

internal class GalleryCredentials(
    val memberId: String,
    val passHash: String,
    val igneous: String,
    val settings: Map<String, String> = emptyMap(),
)

internal fun OkHttpClient.Builder.addGalleryCookies(
    credentialsForSite: (Boolean, String?) -> GalleryCredentials,
): OkHttpClient.Builder = addNetworkInterceptor { chain ->
    val request = chain.request()
    val host = request.url.host
    val forceEh = when {
        host == "e-hentai.org" || host.endsWith(".e-hentai.org") -> true
        host == "exhentai.org" || host.endsWith(".exhentai.org") -> false
        else -> return@addNetworkInterceptor chain.proceed(request)
    }
    val credentials = credentialsForSite(forceEh, request.header("Cookie"))
    // Keep the website's profile and filtering settings instead of replacing uconfig.
    val cookies = linkedMapOf("nw" to "1").apply { putAll(credentials.settings) }
    if (credentials.memberId.isNotEmpty()) cookies["ipb_member_id"] = credentials.memberId
    if (credentials.passHash.isNotEmpty()) cookies["ipb_pass_hash"] = credentials.passHash
    if (credentials.igneous.isNotEmpty()) cookies["igneous"] = credentials.igneous
    // WebView owns the live session. Merge it into this request without writing it back per request.
    val cookieHeader = buildList {
        request.header("Cookie")?.splitToSequence(';')?.map(String::trim)
            ?.filter { it.isNotEmpty() && it != "uconfig=prn_n" && it.substringBefore('=') !in cookies }?.let(::addAll)
        cookies.forEach { (name, value) -> add("$name=$value") }
    }.joinToString("; ")
    chain.proceed(request.newBuilder().header("Cookie", cookieHeader).build())
}

private val ehCookieUrls = listOf("https://e-hentai.org", "https://forums.e-hentai.org")
private val exCookieUrls = listOf("https://exhentai.org") + ehCookieUrls
private val credentialNames = setOf("ipb_member_id", "ipb_pass_hash", "igneous")
private val settingsNames = setOf("sk", "sp", "sl", "uconfig")

internal fun galleryCredentials(
    forceEh: Boolean,
    cookiesForUrl: (String) -> String?,
    requestCookie: String? = null,
    storedCookie: (String) -> String,
): GalleryCredentials {
    val sites = if (forceEh) ehCookieUrls else exCookieUrls
    val snapshots = mutableMapOf<String, Map<String, String>>()
    fun cookies(url: String): Map<String, String> = snapshots.getOrPut(url) {
        // The host cookie jar has already read WebView for this request.
        val header = if (url == sites.first() && requestCookie != null) requestCookie else cookiesForUrl(url)
        buildMap {
            header?.splitToSequence(';')?.forEach {
                val entry = it.trim()
                val name = entry.substringBefore('=')
                val value = entry.substringAfter('=', "")
                if ((name in credentialNames || name in settingsNames) && value.isNotEmpty() && name !in this) put(name, value)
            }
        }
    }
    val account = sites.firstNotNullOfOrNull { url ->
        val values = cookies(url)
        val member = values["ipb_member_id"]
        val pass = values["ipb_pass_hash"]
        if (member != null && pass != null) member to pass else null
    } ?: run {
        val member = storedCookie("ipb_member_id")
        val pass = storedCookie("ipb_pass_hash")
        if (member.isNotEmpty() && pass.isNotEmpty()) member to pass else "" to ""
    }
    val igneous = if (forceEh) "" else cookies("https://exhentai.org")["igneous"] ?: storedCookie("igneous")
    // Older extension versions wrote this synthetic value into WebView's cookie store.
    val settings = cookies(sites.first()).filter { (name, value) -> name in settingsNames && !(name == "uconfig" && value == "prn_n") }
    return GalleryCredentials(account.first, account.second, igneous, settings)
}
