package com.slate.browser.web

import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/**
 * The activity-level services the web layer needs but must not reach for itself: anything that
 * touches the window, the system permission dialogs, or another app.
 */
interface BrowserHost {
    /** A `<video>` (or any element) asked to occupy the whole screen. */
    fun onEnterElementFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)

    fun onExitElementFullscreen()

    /** Requests runtime permissions, invoking [onResult] with whether all were granted. */
    fun requestSystemPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit)

    fun openFileChooser(intent: Intent, callback: ValueCallback<Array<Uri>?>): Boolean

    /** Hands a non-web URL to whichever app can handle it. Returns false when nothing can. */
    fun openExternally(url: String): Boolean

    fun toast(message: String)

    fun snack(message: String, actionLabel: String? = null, action: (() -> Unit)? = null)
}
