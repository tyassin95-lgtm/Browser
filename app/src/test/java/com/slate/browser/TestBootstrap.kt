package com.slate.browser

import org.robolectric.shadows.ShadowLooper

/**
 * Runs [BrowserViewModel.bootstrap] and waits for it to finish.
 *
 * Startup is asynchronous by design: the stored preferences decide whether the previous session
 * is restored, and they arrive from DataStore a moment after launch. Reading them from the
 * settings state flow instead would read the defaults, which is what made "reopen tabs on
 * launch" impossible to turn off. A test therefore has to let that finish before asserting,
 * and Robolectric only advances the main looper when asked.
 */
fun BrowserViewModel.bootstrapAndWait(initialUrl: String? = null) {
    bootstrap(initialUrl)
    val deadline = System.currentTimeMillis() + TIMEOUT_MS
    while (tabManager.count == 0 && System.currentTimeMillis() < deadline) {
        ShadowLooper.idleMainLooper()
        Thread.sleep(2)
    }
    check(tabManager.count > 0) { "bootstrap produced no tab within ${TIMEOUT_MS}ms" }
}

private const val TIMEOUT_MS = 10_000L
