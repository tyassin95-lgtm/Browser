package com.slate.browser.util

import android.net.Uri
import android.util.Patterns
import com.slate.browser.data.SearchEngine
import com.slate.browser.web.UrlSafety

object UrlUtils {

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val IP_LIKE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")

    /**
     * Turns whatever the user typed into something loadable: an explicit URL is kept, a bare
     * host is completed with https, and anything else becomes a search.
     */
    fun toLoadableUrl(input: String, engine: SearchEngine): String {
        val text = input.trim()
        if (text.isEmpty()) return ""

        // An explicit scheme is honoured only if the browser will actually navigate to it.
        // Anything else becomes a search rather than being loaded: `javascript:` typed or
        // pasted into the address bar runs against the page already open, which is how a user
        // is talked into attacking their own signed-in session, and `data:` renders
        // attacker-authored markup under an address bar with nothing useful to show.
        if (SCHEME.containsMatchIn(text) || text.startsWith("about:")) {
            return if (UrlSafety.isNavigable(text)) text else engine.urlFor(text)
        }
        // A schemeless prefix of a refused scheme is the same attempt with the slashes left
        // off; `Uri` parses `javascript:alert(1)` as a scheme without them.
        UrlSafety.schemeOf(text)?.let { if (!UrlSafety.isNavigable(text)) return engine.urlFor(text) }
        if (text == "localhost" || text.startsWith("localhost:") || text.startsWith("localhost/")) {
            return "http://$text"
        }
        if (IP_LIKE.matches(text)) return "http://$text"

        val looksLikeHost = !text.contains(' ') &&
            text.contains('.') &&
            !text.endsWith('.') &&
            Patterns.WEB_URL.matcher(text).matches()

        return if (looksLikeHost) "https://$text" else engine.urlFor(text)
    }

    /**
     * The part of a URL worth showing in the omnibox: the host, written so it cannot lie.
     *
     * `Uri.getHost` is what makes this safe against the oldest trick in the book — the host of
     * `https://accounts.google.com@evil.example/` is `evil.example`, not the part before the
     * `@` that a reader's eye goes to first. [UrlSafety.displayHost] then decides whether the
     * host can be shown in Unicode without being able to impersonate another one.
     */
    fun displayHost(url: String): String = runCatching {
        val host = Uri.parse(url).host ?: return@runCatching url
        UrlSafety.displayHost(host).removePrefix("www.")
    }.getOrDefault(url)

    fun isHttpLike(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    fun isSecure(url: String): Boolean = url.startsWith("https://")

    /** A short, human label for a page that has no title yet. */
    fun fallbackTitle(url: String): String {
        if (url.isBlank()) return "New tab"
        val host = displayHost(url)
        return if (host.isNotBlank() && host != url) host else url
    }
}
