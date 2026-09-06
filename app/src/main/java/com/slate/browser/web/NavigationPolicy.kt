package com.slate.browser.web

import android.net.Uri

/**
 * Tracks whether the user has recently touched the page, and whether that touch has already
 * been spent.
 *
 * WebView reports whether a navigation carried a user gesture, but not *which* gesture, so a
 * single tap can be replayed into a dozen `window.open` calls that all claim to be user
 * initiated — which is exactly how one tap on a play button becomes five advertising tabs.
 * Identity has to be reconstructed from the touch stream instead: a real touch starts an
 * activation, and the first thing to ask for it consumes it.
 *
 * The time window is a platform requirement rather than a tuning knob. It exists because the
 * activation and the navigation arrive on different paths with no shared token.
 */
class UserActivation(private val clock: () -> Long = System::currentTimeMillis) {

    // Absent rather than a sentinel: subtracting a sentinel from the clock overflows, and an
    // overflowed elapsed time reads as "just touched", which would make every check below pass.
    private var lastTouchAt: Long? = null
    private var consumedTouchAt: Long? = null

    fun recordTouch() {
        lastTouchAt = clock()
    }

    fun isActive(): Boolean {
        val touched = lastTouchAt ?: return false
        return clock() - touched <= WINDOW_MS
    }

    /** True once per touch. The second caller for the same touch is a page helping itself. */
    fun consume(): Boolean {
        if (!isActive()) return false
        if (consumedTouchAt == lastTouchAt) return false
        consumedTouchAt = lastTouchAt
        return true
    }

    fun reset() {
        lastTouchAt = null
        consumedTouchAt = null
    }

    private companion object {
        /** Long enough to cover a click handler doing real work, short enough to not linger. */
        const val WINDOW_MS = 1_000L
    }
}

/** What the browser decided to do with a navigation a page asked for. */
sealed interface NavigationDecision {
    /** Let WebView carry on with it. */
    data object Allow : NavigationDecision

    /** Refuse it silently; it was never going anywhere the user asked to go. */
    data class Block(val reason: String) : NavigationDecision

    /** Leaving the browser needs the user to say so. */
    data class ConfirmExternal(val url: String, val label: String) : NavigationDecision

    /** Handle it here rather than handing it to another app. */
    data class KeepInBrowser(val url: String) : NavigationDecision
}

/**
 * Decides what to do with every navigation a page attempts.
 *
 * The rules are layered rather than resting on any single list, because each layer catches a
 * different abuse: the blocklist catches known advertising destinations, activation catches a
 * page navigating without being asked, scheme handling catches an attempt to leave for another
 * app, and the web-URL rule catches the specific trick of dressing a browser hand-off up as an
 * app link. Anything the user actually touched is still allowed through all of them.
 */
class NavigationPolicy(
    private val blocker: ContentBlocker,
    private val activation: UserActivation,
) {

    fun decide(
        url: String,
        isMainFrame: Boolean,
        hasGesture: Boolean,
        isRedirect: Boolean,
        currentPageUrl: String?,
        blockingEnabled: Boolean,
    ): NavigationDecision {
        val scheme = schemeOf(url)

        if (scheme == "http" || scheme == "https") {
            if (blockingEnabled && blocker.isBlockedDestination(url)) {
                return NavigationDecision.Block("advertising destination")
            }
            // A page moving itself somewhere else entirely, with nothing behind it. Server-side
            // redirects are part of a navigation that was already asked for, so they pass.
            if (blockingEnabled && isMainFrame && !hasGesture && !isRedirect &&
                !activation.isActive() && isCrossOrigin(url, currentPageUrl)
            ) {
                return NavigationDecision.Block("redirect")
            }
            return NavigationDecision.Allow
        }

        // Everything below here leaves the browser, so it needs a touch behind it.
        if (SAFE_SCHEMES.contains(scheme)) {
            return if (hasGesture || activation.isActive()) {
                NavigationDecision.ConfirmExternal(url, scheme.orEmpty())
            } else {
                NavigationDecision.Block("external scheme without a gesture")
            }
        }

        if (scheme == "intent") {
            val target = intentWebFallback(url)
            // An intent whose payload is an ordinary web address is a hand-off to another
            // browser dressed as an app link. The page can have the address; it cannot have
            // the hand-off.
            if (target != null) return NavigationDecision.KeepInBrowser(target)
        }

        if (!hasGesture && !activation.isActive()) {
            return NavigationDecision.Block("app launch without a gesture")
        }
        if (!activation.consume()) {
            return NavigationDecision.Block("app launch beyond one per touch")
        }
        return NavigationDecision.ConfirmExternal(url, scheme.orEmpty())
    }

    /** Whether a window the page asked to open may exist at all. */
    fun allowWindow(url: String?, blockingEnabled: Boolean): Boolean {
        if (url != null && blockingEnabled && blocker.isBlockedDestination(url)) return false
        return activation.consume()
    }

    private fun schemeOf(url: String): String? =
        runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()

    private fun isCrossOrigin(url: String, currentPageUrl: String?): Boolean {
        if (currentPageUrl.isNullOrBlank()) return false
        val target = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
        val current = runCatching { Uri.parse(currentPageUrl).host }.getOrNull() ?: return false
        return registrable(target) != registrable(current)
    }

    /** Good enough for "is this the same site": the last two labels of the host. */
    private fun registrable(host: String): String {
        val labels = host.lowercase().split('.')
        return if (labels.size <= 2) host.lowercase() else labels.takeLast(2).joinToString(".")
    }

    /** The http(s) address an `intent:` URL is really carrying, if any. */
    private fun intentWebFallback(url: String): String? {
        Regex("S\\.browser_fallback_url=([^;]+)").find(url)?.groupValues?.get(1)?.let { encoded ->
            val decoded = runCatching { Uri.decode(encoded) }.getOrNull()
            if (decoded != null && (decoded.startsWith("http://") || decoded.startsWith("https://"))) {
                return decoded
            }
        }
        // intent://host/path#Intent;scheme=https;... is the same hand-off written another way.
        val scheme = Regex("scheme=([^;]+)").find(url)?.groupValues?.get(1)?.lowercase()
        if (scheme == "http" || scheme == "https") {
            val body = url.removePrefix("intent://").substringBefore("#Intent")
            if (body.isNotBlank()) return "$scheme://$body"
        }
        return null
    }

    private companion object {
        /** Schemes that go to a well-understood system app rather than an arbitrary one. */
        val SAFE_SCHEMES = setOf("tel", "mailto", "sms", "smsto", "geo")
    }
}
