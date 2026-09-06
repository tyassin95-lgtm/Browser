package com.slate.browser.web

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/** What the user pressed and held on, and therefore what can sensibly be done with it. */
data class LinkContext(
    val linkUrl: String? = null,
    val imageUrl: String? = null,
    val title: String? = null,
) {
    val hasLink: Boolean get() = !linkUrl.isNullOrBlank()
    val hasImage: Boolean get() = !imageUrl.isNullOrBlank()
    val isActionable: Boolean get() = hasLink || hasImage

    /** What the sheet is about, in one line. */
    val label: String get() = linkUrl ?: imageUrl.orEmpty()
}

/**
 * Turns a long press into a description of the element underneath it.
 *
 * WebView's hit test names the type and gives one URL, which for an image inside a link is the
 * image — the anchor's href has to be asked for separately and arrives on a message. Both are
 * needed to offer the right actions, so the resolution waits for the second before deciding.
 */
object LinkContextResolver {

    /**
     * Whether the press landed on something the browser will offer actions for. Answered
     * synchronously because the long-click listener has to decide there and then whether to
     * consume the gesture or leave it to the page.
     */
    fun isActionable(webView: WebView): Boolean = when (webView.hitTestResult.type) {
        WebView.HitTestResult.SRC_ANCHOR_TYPE,
        WebView.HitTestResult.IMAGE_TYPE,
        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
        -> !webView.hitTestResult.extra.isNullOrBlank()

        else -> false
    }

    /**
     * Resolves what was pressed, or calls back with null when it is something the page should
     * keep handling itself — text, form fields, anything selectable.
     */
    fun resolve(webView: WebView, onResolved: (LinkContext?) -> Unit) {
        val hit = webView.hitTestResult
        val extra = hit.extra

        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE ->
                onResolved(LinkContext(linkUrl = extra))

            WebView.HitTestResult.IMAGE_TYPE ->
                onResolved(LinkContext(imageUrl = extra))

            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                // The href lives on the anchor, which only requestFocusNodeHref will tell us.
                val handler = object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(message: android.os.Message) {
                        val href = message.data.getString("url")
                        onResolved(LinkContext(linkUrl = href, imageUrl = extra))
                    }
                }
                webView.requestFocusNodeHref(handler.obtainMessage())
            }

            // Text, editable fields, phone numbers and addresses stay with the page, so
            // selection handles and the platform's own text menu keep working.
            else -> onResolved(null)
        }
    }
}
