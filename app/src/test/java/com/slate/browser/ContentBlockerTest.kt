package com.slate.browser

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.core.app.ApplicationProvider
import com.slate.browser.web.ContentBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the blocker refuses, and — more importantly — what it does not.
 *
 * A blocker that is slightly too eager breaks sign-ins and payment flows, which costs far more
 * than the advert it removed, so the rules are matched by registrable domain and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
class ContentBlockerTest {

    private lateinit var blocker: ContentBlocker

    @Before
    fun setUp() {
        blocker = ContentBlocker(ApplicationProvider.getApplicationContext())
    }

    private fun request(url: String, mainFrame: Boolean = false): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame() = mainFrame
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }

    @Test
    fun `known advertising domains are blocked`() {
        assertTrue(blocker.matches("doubleclick.net"))
        assertTrue(blocker.matches("googlesyndication.com"))
        assertTrue(blocker.matches("adnxs.com"))
    }

    @Test
    fun `subdomains of a blocked domain are blocked too`() {
        assertTrue(blocker.matches("stats.g.doubleclick.net"))
        assertTrue(blocker.matches("pagead2.googlesyndication.com"))
        assertTrue(blocker.matches("a.b.c.d.criteo.com"))
    }

    @Test
    fun `ordinary sites are left alone`() {
        listOf(
            "example.com",
            "en.wikipedia.org",
            "github.com",
            "accounts.google.com",
            "checkout.stripe.com",
            "cdn.jsdelivr.net",
            "livejasmin.com",
        ).forEach { host ->
            assertFalse("$host must not be blocked", blocker.matches(host))
        }
    }

    @Test
    fun `a domain that merely contains a rule as a substring is not blocked`() {
        // The suffix walk is by label, so these are unrelated sites that happen to read alike.
        assertFalse(blocker.matches("notdoubleclick.net"))
        assertFalse(blocker.matches("mycriteo.com.example.org"))
        assertFalse(blocker.matches("doubleclick.net.evil.test"))
    }

    @Test
    fun `a bare top level domain is never a match`() {
        assertFalse(blocker.matches("net"))
        assertFalse(blocker.matches("com"))
    }

    @Test
    fun `the page the user asked for is never blocked as a subresource`() {
        // Where to navigate is a decision for the navigation policy, which can explain itself.
        assertFalse(blocker.shouldBlock(request("https://doubleclick.net/", mainFrame = true)))
        assertTrue(blocker.shouldBlock(request("https://doubleclick.net/ad.js")))
    }

    @Test
    fun `a blocked image gets an image back, so nothing draws a broken glyph`() {
        val imageRequest = object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse("https://doubleclick.net/banner.gif")
            override fun isForMainFrame() = false
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = mutableMapOf("Accept" to "image/webp,image/*")
        }
        assertEquals("image/gif", blocker.blockedResponse(imageRequest).mimeType)
        assertEquals("text/plain", blocker.blockedResponse(request("https://doubleclick.net/a.js")).mimeType)
    }

    @Test
    fun `navigations to a blocked destination are recognised`() {
        assertTrue(blocker.isBlockedDestination("https://go.propellerads.com/redirect?x=1"))
        assertFalse(blocker.isBlockedDestination("https://example.com/article"))
        assertFalse(blocker.isBlockedDestination("not a url at all"))
    }

    @Test
    fun `blocked requests are counted`() {
        blocker.resetCount()
        assertEquals(0, blocker.totalBlocked)
        repeat(3) { blocker.recordBlock() }
        assertEquals(3, blocker.totalBlocked)
    }
}
