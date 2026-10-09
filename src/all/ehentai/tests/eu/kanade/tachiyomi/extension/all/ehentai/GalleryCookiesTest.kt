package eu.kanade.tachiyomi.extension.all.ehentai

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryCookiesTest {
    @Test
    fun completeRequestCookiesAvoidReadingWebViewAgainAndStayFresh() {
        for (session in listOf("first", "refreshed")) {
            val credentials = galleryCredentials(
                false,
                { error("The cookie jar already read the current WebView session") },
                "ipb_member_id=member; ipb_pass_hash=pass; igneous=$session; sk=profile; sp=2; sl=dm_2; uconfig=prn_n",
            ) { error("Stored credentials are unnecessary") }
            assertEquals(session, credentials.igneous)
            assertEquals(mapOf("sk" to "profile", "sp" to "2", "sl" to "dm_2"), credentials.settings)
        }
    }

    @Test
    fun profileSettingsStayOnTheirWebsiteAndRefreshWithWebView() {
        val cookies = mutableMapOf(
            "https://exhentai.org" to "ipb_member_id=member; ipb_pass_hash=pass; sk=ex-profile; uconfig=ex-settings",
            "https://e-hentai.org" to "ipb_member_id=member; ipb_pass_hash=pass; sk=eh-profile; uconfig=eh-settings",
            "https://forums.e-hentai.org" to "sk=forum-profile; uconfig=forum-settings",
        )
        assertEquals(mapOf("sk" to "ex-profile", "uconfig" to "ex-settings"), galleryCredentials(false, cookies::get) { "" }.settings)
        assertEquals(mapOf("sk" to "eh-profile", "uconfig" to "eh-settings"), galleryCredentials(true, cookies::get) { "" }.settings)
        cookies["https://e-hentai.org"] = "ipb_member_id=member; ipb_pass_hash=pass; sk=changed-profile"
        assertEquals(mapOf("sk" to "changed-profile"), galleryCredentials(true, cookies::get) { "" }.settings)
        cookies.remove("https://e-hentai.org")
        assertEquals(emptyMap<String, String>(), galleryCredentials(true, cookies::get) { "" }.settings)
    }

    @Test
    fun readsAndParsesEachWebViewHostOnceForAllCredentials() {
        val reads = mutableListOf<String>()
        val credentials = galleryCredentials(false, { url ->
            reads += url
            "ipb_member_id=member; ipb_pass_hash=pass; igneous=session"
        }) { error("WebView already has the credentials") }
        assertEquals("member", credentials.memberId)
        assertEquals("pass", credentials.passHash)
        assertEquals("session", credentials.igneous)
        assertEquals(listOf("https://exhentai.org"), reads)
    }

    @Test
    fun missingWebViewCookiesAreNotReadRepeatedlyWithinOneRequest() {
        val reads = mutableListOf<String>()
        val credentials = galleryCredentials(false, { url ->
            reads += url
            null
        }) { "stored-$it" }
        assertEquals("stored-ipb_member_id", credentials.memberId)
        assertEquals("stored-ipb_pass_hash", credentials.passHash)
        assertEquals("stored-igneous", credentials.igneous)
        assertEquals(listOf("https://exhentai.org", "https://e-hentai.org", "https://forums.e-hentai.org"), reads)
    }

    @Test
    fun credentialSnapshotsRefreshBetweenRequests() {
        var session = "first"
        val cookiesForUrl: (String) -> String? = { "ipb_member_id=member; ipb_pass_hash=pass; igneous=$session" }
        assertEquals("first", galleryCredentials(false, cookiesForUrl) { "" }.igneous)
        session = "refreshed"
        assertEquals("refreshed", galleryCredentials(false, cookiesForUrl) { "" }.igneous)
    }

    @Test
    fun ehentaiDoesNotReadTheUnneededExhentaiSession() {
        val reads = mutableListOf<String>()
        val credentials = galleryCredentials(true, { url ->
            reads += url
            "ipb_member_id=eh-member; ipb_pass_hash=eh-pass"
        }) { error("WebView already has the credentials") }
        assertEquals("eh-member", credentials.memberId)
        assertEquals("eh-pass", credentials.passHash)
        assertEquals("", credentials.igneous)
        assertEquals(listOf("https://e-hentai.org"), reads)
    }

    @Test
    fun choosesTheActiveSiteAccountBeforeForumCookies() {
        val cookies = mapOf(
            "https://exhentai.org" to "ipb_member_id=ex-account; ipb_pass_hash=ex-pass; igneous=ex-session",
            "https://e-hentai.org" to "ipb_member_id=eh-account; ipb_pass_hash=eh-pass",
            "https://forums.e-hentai.org" to "ipb_member_id=forum-account; ipb_pass_hash=forum-pass",
        )
        assertEquals("ex-account", galleryCredentials(false, cookies::get) { "" }.memberId)
        assertEquals("eh-account", galleryCredentials(true, cookies::get) { "" }.memberId)
    }

    @Test
    fun observesWebViewLoginAndCookieRefreshWithoutRestarting() {
        var cookies: String? = null
        val getCookies: (String) -> String? = { cookies }
        assertEquals("", galleryCredentials(false, getCookies) { "" }.igneous)
        cookies = "igneous=first-session"
        assertEquals("first-session", galleryCredentials(false, getCookies) { "" }.igneous)
        cookies = "igneous=refreshed-session"
        assertEquals("refreshed-session", galleryCredentials(false, getCookies) { "" }.igneous)
    }

    @Test
    fun acceptsCookieSeparatorsAndKeepsEqualsInValues() {
        val credentials = galleryCredentials(false, { " unrelated=1;ipb_member_id=42;ipb_pass_hash=value=tail ;igneous=" }) { "" }
        assertEquals("value=tail", credentials.passHash)
        assertEquals("", credentials.igneous)
    }

    @Test
    fun memberCookiesCanComeFromAnExistingForumLogin() {
        val credentials = galleryCredentials(false, { url ->
            "ipb_member_id=forum-account; ipb_pass_hash=forum-pass".takeIf { url == "https://forums.e-hentai.org" }
        }) { "" }
        assertEquals("forum-account", credentials.memberId)
        assertEquals("forum-pass", credentials.passHash)
    }

    @Test
    fun incompleteHostAccountDoesNotBorrowAnotherAccountsPassword() {
        val cookies = mapOf(
            "https://exhentai.org" to "ipb_member_id=ex-account; igneous=ex-session",
            "https://e-hentai.org" to "ipb_member_id=eh-account; ipb_pass_hash=eh-pass",
        )
        val credentials = galleryCredentials(false, cookies::get) { error("A complete WebView account is available") }
        assertEquals("eh-account", credentials.memberId)
        assertEquals("eh-pass", credentials.passHash)
        assertEquals("ex-session", credentials.igneous)
    }

    @Test
    fun storedAccountIsSelectedAsAPairInsteadOfFillingPartialWebViewCookies() {
        val cookies = mapOf(
            "https://exhentai.org" to "ipb_member_id=ex-account",
            "https://e-hentai.org" to "ipb_pass_hash=eh-pass",
        )
        val credentials = galleryCredentials(false, cookies::get) { "stored-$it" }
        assertEquals("stored-ipb_member_id", credentials.memberId)
        assertEquals("stored-ipb_pass_hash", credentials.passHash)
        val incomplete = galleryCredentials(false, cookies::get) { if (it == "ipb_member_id") "stored-member" else "" }
        assertEquals("", incomplete.memberId)
        assertEquals("", incomplete.passHash)
    }
}
