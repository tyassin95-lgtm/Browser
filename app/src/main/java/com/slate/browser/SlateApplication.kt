package com.slate.browser

import android.app.Application
import android.content.ComponentCallbacks2
import android.webkit.WebView

class SlateApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // A debuggable build gets remote inspection; release builds never do.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }

    /**
     * The browser's UI is no longer visible. This is the honest moment to release the renderers
     * of tabs the user is not looking at — they cost several megabytes each and nothing on
     * screen depends on them.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            trimListeners.forEach { it() }
        }
    }

    companion object {
        /** Registered by the activity so the tab layer can shed renderers under pressure. */
        val trimListeners = mutableListOf<() -> Unit>()
    }
}
