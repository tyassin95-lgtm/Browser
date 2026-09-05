package com.slate.browser

import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.slate.browser.data.Settings
import com.slate.browser.web.WebViewConfigurator
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings are replayed onto tabs that are already on screen, so configuration has to be safe
 * to apply to a live, attached WebView — not just to one that has yet to be added to a parent.
 */
@RunWith(RobolectricTestRunner::class)
class WebViewConfiguratorTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `reconfiguring an attached webview does not break its parent's layout contract`() {
        val host = FrameLayout(context)
        val webView = WebView(context)
        host.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // What every settings toggle does to the tab the user is looking at.
        WebViewConfigurator.configure(webView, Settings(), desktopMode = false)

        assertTrue(
            "layout params belong to the parent that adopted the view; replacing them with a " +
                "bare ViewGroup.LayoutParams makes FrameLayout throw on its next layout pass",
            webView.layoutParams is FrameLayout.LayoutParams,
        )

        // The cast FrameLayout performs on every pass is the thing that actually crashed.
        host.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, 1080, 1920)
    }

    @Test
    fun `configuring a detached webview leaves layout to whoever adopts it`() {
        val webView = WebView(context)
        WebViewConfigurator.configure(webView, Settings(), desktopMode = false)
        // No parent yet, so the configurator must not have invented layout params of its own.
        val host = FrameLayout(context)
        host.addView(webView)
        assertTrue(webView.layoutParams is FrameLayout.LayoutParams)
    }
}
