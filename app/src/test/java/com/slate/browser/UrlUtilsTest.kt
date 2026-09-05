package com.slate.browser

import com.slate.browser.data.SearchEngine
import com.slate.browser.util.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The omnibox has to decide "URL or search?" on every keystroke, and getting it wrong is the
 * most visible bug a browser can have.
 */
@RunWith(RobolectricTestRunner::class)
class UrlUtilsTest {

    private val engine = SearchEngine.DUCKDUCKGO

    @Test
    fun `explicit schemes are left alone`() {
        assertEquals("https://example.com/a?b=c", UrlUtils.toLoadableUrl("https://example.com/a?b=c", engine))
        assertEquals("http://example.com", UrlUtils.toLoadableUrl("http://example.com", engine))
        assertEquals("about:blank", UrlUtils.toLoadableUrl("about:blank", engine))
    }

    @Test
    fun `bare hosts are completed with https`() {
        assertEquals("https://example.com", UrlUtils.toLoadableUrl("example.com", engine))
        assertEquals("https://news.bbc.co.uk/sport", UrlUtils.toLoadableUrl("news.bbc.co.uk/sport", engine))
    }

    @Test
    fun `localhost and bare IPs stay on http`() {
        assertEquals("http://localhost:8080", UrlUtils.toLoadableUrl("localhost:8080", engine))
        assertEquals("http://192.168.1.4:3000", UrlUtils.toLoadableUrl("192.168.1.4:3000", engine))
    }

    @Test
    fun `anything with a space is a search`() {
        val result = UrlUtils.toLoadableUrl("example.com news", engine)
        assertTrue(result.startsWith("https://duckduckgo.com/?q="))
    }

    @Test
    fun `plain words are a search, not a host`() {
        assertTrue(UrlUtils.toLoadableUrl("weather", engine).startsWith("https://duckduckgo.com/?q="))
        assertTrue(UrlUtils.toLoadableUrl("how to tie a tie", engine).startsWith("https://duckduckgo.com/?q="))
    }

    @Test
    fun `search queries are percent-encoded`() {
        val result = UrlUtils.toLoadableUrl("a b&c=d", engine)
        assertFalse("query must not leak raw separators", result.endsWith("a b&c=d"))
        assertTrue(result.contains("%26") || result.contains("%3D"))
    }

    @Test
    fun `blank input loads nothing`() {
        assertEquals("", UrlUtils.toLoadableUrl("   ", engine))
    }

    @Test
    fun `display host drops the www prefix and the rest of the url`() {
        assertEquals("example.com", UrlUtils.displayHost("https://www.example.com/deep/path?q=1"))
        assertEquals("m.example.com", UrlUtils.displayHost("https://m.example.com/"))
    }

    @Test
    fun `security is judged by scheme`() {
        assertTrue(UrlUtils.isSecure("https://example.com"))
        assertFalse(UrlUtils.isSecure("http://example.com"))
    }

    @Test
    fun `fallback title uses the host when a page has no title`() {
        assertEquals("example.com", UrlUtils.fallbackTitle("https://example.com/x"))
        assertEquals("New tab", UrlUtils.fallbackTitle(""))
    }
}
