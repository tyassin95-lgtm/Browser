package com.slate.browser.ui.components

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts the active tab's WebView.
 *
 * The container is created once and children are swapped by hand, so switching tabs never
 * recreates a WebView and never triggers a reload — the view is simply re-parented.
 */
@Composable
fun WebViewHost(webView: WebView?, backgroundColor: Color, modifier: Modifier = Modifier) {
    val argb = backgroundColor.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { container ->
            // Both the host and the page are painted opaque. A transparent WebView has to be
            // blended with whatever is behind it on every frame instead of being handed to the
            // compositor as a finished layer, and any gap the blend does not cover shows the
            // previous frame through — most visibly at the moment the view is resized.
            container.setBackgroundColor(argb)
            webView?.setBackgroundColor(argb)
            val current = container.getChildAt(0)
            if (current === webView) return@AndroidView
            if (current != null) container.removeAllViews()
            if (webView != null) {
                (webView.parent as? ViewGroup)?.removeView(webView)
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        onReset = { container -> container.removeAllViews() },
        onRelease = { container -> container.removeAllViews() },
    )
}

/** Fills the screen with a page-provided fullscreen element, such as a `<video>`. */
@Composable
fun FullscreenHost(view: android.view.View, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        },
        update = { container ->
            if (container.getChildAt(0) === view) return@AndroidView
            container.removeAllViews()
            (view.parent as? ViewGroup)?.removeView(view)
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        },
        onRelease = { container -> container.removeAllViews() },
    )
}
