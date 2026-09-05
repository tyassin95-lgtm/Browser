package com.slate.browser.util

import android.net.Uri
import android.util.Patterns
import com.slate.browser.data.SearchEngine

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

        if (SCHEME.containsMatchIn(text)) return text
        if (text.startsWith("about:") || text.startsWith("javascript:") ||
            text.startsWith("data:") || text.startsWith("file:")
        ) {
            return text
        }
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

    /** The part of a URL worth showing in the omnibox: the registrable host. */
    fun displayHost(url: String): String = runCatching {
        val host = Uri.parse(url).host ?: return@runCatching url
        host.removePrefix("www.")
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
