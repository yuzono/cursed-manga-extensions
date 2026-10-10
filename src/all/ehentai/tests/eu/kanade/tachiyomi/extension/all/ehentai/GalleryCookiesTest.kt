package eu.kanade.tachiyomi.extension.all.ehentai

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryCookiesTest {
    private val ex = "https://exhentai.org"
    private val eh = "https://e-hentai.org"
    private val forum = "https://forums.e-hentai.org"

    @Test
    fun requestCookiesAvoidRereadingWebViewAndKeepProfileSettings() {
        for (session in listOf("first", "refreshed")) {
            val credentials = galleryCredentials(
                false,
                { error("The request already contains the WebView cookies") },
                account("member") + "; igneous=$session; sk=profile; sp=2; sl=dm_2; uconfig=prn_n",
            ) { error("Stored credentials are unnecessary") }
            assertAccount(credentials, "member", "member-pass", session)
            val settings = mapOf("sk" to "profile", "sp" to "2", "sl" to "dm_2")
            assertEquals(settings, credentials.settings)
        }
    }

    @Test
    fun profilesStayOnTheirSiteAndRefreshWithWebView() {
        val cookies = mutableMapOf(
            ex to "${account("ex")}; sk=ex-profile; uconfig=ex-settings",
            eh to "${account("eh")}; sk=eh-profile; uconfig=eh-settings",
            forum to "sk=forum-profile; uconfig=forum-settings",
        )
        for ((forceEh, site) in listOf(false to "ex", true to "eh")) {
            val credentials = galleryCredentials(forceEh, cookies::get) { "" }
            assertAccount(credentials, site, "$site-pass", "")
            assertEquals(
                mapOf("sk" to "$site-profile", "uconfig" to "$site-settings"),
                credentials.settings,
            )
        }
        cookies[eh] = "${account("eh")}; sk=changed"
        val changed = galleryCredentials(true, cookies::get) { "" }.settings
        assertEquals(mapOf("sk" to "changed"), changed)
        cookies.remove(eh)
        assertEquals(
            emptyMap<String, String>(),
            galleryCredentials(true, cookies::get) { "" }.settings,
        )
    }

    @Test
    fun snapshotsRefreshBetweenRequestsAndReadEachRequiredHostOnce() {
        for (forceEh in listOf(false, true)) {
            var session: String? = null
            val reads = mutableListOf<String>()
            val cookies: (String) -> String? = { url ->
                reads += url
                session?.let { "${account("member")}; igneous=$it" }
            }
            for (value in listOf(null, "first", "refreshed")) {
                session = value
                reads.clear()
                val credentials = galleryCredentials(forceEh, cookies) { "stored-$it" }
                assertAccount(
                    credentials,
                    if (value == null) "stored-ipb_member_id" else "member",
                    if (value == null) "stored-ipb_pass_hash" else "member-pass",
                    if (forceEh) "" else value ?: "stored-igneous",
                )
                val sites = if (forceEh) listOf(eh, forum) else listOf(ex, eh, forum)
                assertEquals(if (value == null) sites else sites.take(1), reads)
            }
        }
    }

    @Test
    fun accountFallbacksNeverMixMemberIdsAndPasswords() {
        val cases = listOf(
            mapOf(forum to account("forum")) to "forum",
            mapOf(ex to "ipb_member_id=ex; igneous=session", eh to account("eh")) to "eh",
            mapOf(ex to "ipb_member_id=ex", eh to "ipb_pass_hash=eh-pass") to "stored",
        )
        for ((cookies, expected) in cases) {
            val credentials = galleryCredentials(false, cookies::get) {
                when (it) {
                    "ipb_member_id" -> "stored"
                    "ipb_pass_hash" -> "stored-pass"
                    else -> if (expected == "eh") "stored-session" else "session"
                }
            }
            assertAccount(credentials, expected, "$expected-pass", "session")
        }
        val incomplete = galleryCredentials(false, { "ipb_member_id=ex" }) {
            if (it == "ipb_member_id") "stored" else ""
        }
        assertAccount(incomplete, "", "", "")
    }

    @Test
    fun cookieParsingKeepsEqualsInValuesAndAcceptsEmptySessions() {
        val cookies = " unrelated=1;ipb_member_id=42;ipb_pass_hash=value=tail ;igneous="
        assertAccount(galleryCredentials(false, { cookies }) { "" }, "42", "value=tail", "")
    }

    private fun account(member: String) = "ipb_member_id=$member; ipb_pass_hash=$member-pass"

    private fun assertAccount(value: GalleryCredentials, id: String, pass: String, token: String) {
        assertEquals(id, value.memberId)
        assertEquals(pass, value.passHash)
        assertEquals(token, value.igneous)
    }
}
