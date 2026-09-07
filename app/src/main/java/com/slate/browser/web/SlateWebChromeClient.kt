package com.slate.browser.web

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.slate.browser.tabs.Tab

/** A modal the page asked for, surfaced by the UI layer rather than by a platform dialog. */
sealed interface JsDialogRequest {
    val message: String
    val origin: String

    data class Alert(override val message: String, override val origin: String, val result: JsResult) : JsDialogRequest
    data class Confirm(override val message: String, override val origin: String, val result: JsResult) : JsDialogRequest
    data class Prompt(
        override val message: String,
        override val origin: String,
        val defaultValue: String,
        val result: JsPromptResult,
    ) : JsDialogRequest
}

/** A capability the page asked for, phrased for a human. */
data class SitePermissionRequest(
    val origin: String,
    val labels: List<String>,
    val onDecision: (Boolean) -> Unit,
)

class SlateWebChromeClient(
    private val tab: Tab,
    private val host: BrowserHost,
    private val onProgress: (Tab, Int) -> Unit,
    private val onTitle: (Tab, String) -> Unit,
    private val onIcon: (Tab, Bitmap) -> Unit,
    private val onNewWindow: (Message, Boolean) -> Boolean,
    private val closeWindow: (Tab) -> Unit,
    private val onJsDialog: (JsDialogRequest) -> Unit,
    private val onSitePermission: (SitePermissionRequest) -> Unit,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(tab, newProgress)

    override fun onReceivedTitle(view: WebView, title: String?) = onTitle(tab, title.orEmpty())

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
        icon?.let { onIcon(tab, it) }
    }

    // ---- Fullscreen media ---------------------------------------------------

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        host.onEnterElementFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        host.onExitElementFullscreen()
    }

    // ---- Popups -------------------------------------------------------------

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean = onNewWindow(resultMsg, isUserGesture)

    override fun onCloseWindow(window: WebView) = closeWindow(tab)

    // ---- Page-initiated dialogs --------------------------------------------

    /**
     * How many modals this document has been allowed so far.
     *
     * A loop of `alert()` is the oldest way to make a browser unusable, and the modern version
     * — a page that will not let go until the visitor calls a phone number — is the same trick
     * with different words. After a handful the page has said what it has to say, and the rest
     * are dismissed without being drawn. Reset by every navigation, in [onPageStarted]'s tab
     * state, so a site is never punished for what the last one did.
     */
    private var dialogsShown = 0

    /**
     * Text a page put in a dialog, trimmed to something a person could read.
     *
     * A megabyte of text in an alert is not a message; it is a way to push the buttons off the
     * screen, which is how a dialog becomes a trap rather than a question.
     */
    private fun readable(message: String?): String {
        val text = message.orEmpty()
        return if (text.length <= MAX_DIALOG_CHARS) text else text.take(MAX_DIALOG_CHARS) + "…"
    }

    /**
     * Whether the page may put up one more modal.
     *
     * A loop of `alert()` is the oldest way to make a browser unusable, and the modern version
     * — a page that will not let go until the visitor rings a phone number — is the same trick
     * with different words. After a handful the page has said what it has to say and the rest
     * are dismissed without being drawn. The count lives on the tab, so it is reset by
     * navigating and not by the page asking again.
     */
    private fun allowDialog(result: JsResult): Boolean {
        if (tab.dialogsSuppressed || tab.dialogsShown >= MAX_DIALOGS_PER_PAGE) {
            tab.dialogsSuppressed = true
            result.cancel()
            return false
        }
        tab.dialogsShown++
        return true
    }

    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        if (!allowDialog(result)) return true
        onJsDialog(JsDialogRequest.Alert(readable(message), originOf(url), result))
        return true
    }

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        if (!allowDialog(result)) return true
        onJsDialog(JsDialogRequest.Confirm(readable(message), originOf(url), result))
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult,
    ): Boolean {
        if (!allowDialog(result)) return true
        onJsDialog(
            JsDialogRequest.Prompt(readable(message), originOf(url), readable(defaultValue), result),
        )
        return true
    }

    override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        if (!allowDialog(result)) return true
        onJsDialog(
            JsDialogRequest.Confirm(
                "Leave this page? Changes you made may not be saved.",
                originOf(url),
                result,
            )
        )
        return true
    }

    // ---- Capabilities -------------------------------------------------------

    /**
     * Camera and microphone are granted in three stages: the origin must be one the platform
     * considers trustworthy, the app must hold the Android runtime permission, and the user
     * must then allow this specific origin. None of the three is assumed.
     */
    override fun onPermissionRequest(request: PermissionRequest) {
        val origin = request.origin
        // A capability handed to a page an attacker on the network can rewrite is a capability
        // handed to the attacker. Every browser draws this line at a secure context.
        if (!isTrustworthy(origin)) {
            request.deny()
            return
        }

        val wanted = request.resources.orEmpty()
        // Only what the user is actually shown is ever granted: a request naming a resource
        // this browser has no words for is not silently approved along with the rest.
        val understood = wanted.filter { it in KNOWN_RESOURCES }.toTypedArray()
        if (understood.isEmpty()) {
            request.deny()
            return
        }

        val androidPermissions = buildList {
            if (understood.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) add(Manifest.permission.CAMERA)
            if (understood.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) add(Manifest.permission.RECORD_AUDIO)
        }
        val labels = understood.mapNotNull { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
                // Protected media is a per-device identifier the page keeps. It is what makes
                // commercial video play, and it is also a durable way to recognise this phone
                // across every site that asks — so it is asked for rather than assumed, even
                // though nothing in Android requires a permission for it.
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "protected media"
                PermissionRequest.RESOURCE_MIDI_SYSEX -> "MIDI devices"
                else -> null
            }
        }.distinct()

        onSitePermission(
            SitePermissionRequest(origin?.host ?: "This site", labels) { allowed ->
                if (!allowed) {
                    request.deny()
                    return@SitePermissionRequest
                }
                if (androidPermissions.isEmpty()) {
                    request.grant(understood)
                } else {
                    host.requestSystemPermissions(androidPermissions.toTypedArray()) { granted ->
                        if (granted) request.grant(understood) else request.deny()
                    }
                }
            }
        )
    }

    /**
     * Whether an origin may be offered a device capability at all.
     *
     * `https` and the loopback exception, which is the same rule the web platform applies to
     * every powerful feature. A page delivered over plain http has no integrity worth the name.
     */
    private fun isTrustworthy(origin: Uri?): Boolean {
        val scheme = origin?.scheme?.lowercase() ?: return false
        if (scheme == "https") return true
        val host = origin.host?.lowercase()
        return scheme == "http" && (host == "localhost" || host == "127.0.0.1" || host == "::1")
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) = Unit

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        if (!isTrustworthy(runCatching { Uri.parse(origin) }.getOrNull())) {
            callback.invoke(origin, false, false)
            return
        }
        onSitePermission(
            SitePermissionRequest(Uri.parse(origin).host ?: origin, listOf("your location")) { allowed ->
                if (!allowed) {
                    callback.invoke(origin, false, false)
                    return@SitePermissionRequest
                }
                host.requestSystemPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                ) { granted -> callback.invoke(origin, granted, false) }
            }
        )
    }

    // ---- File uploads -------------------------------------------------------

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>?>,
        fileChooserParams: FileChooserParams,
    ): Boolean = host.openFileChooser(fileChooserParams.createIntent(), filePathCallback)

    private companion object {
        /** Enough for a page to say something; not enough to hold the browser hostage. */
        const val MAX_DIALOGS_PER_PAGE = 4
        const val MAX_DIALOG_CHARS = 2048

        /** The capabilities this browser has words for. Anything else is refused, not granted. */
        val KNOWN_RESOURCES = setOf(
            PermissionRequest.RESOURCE_VIDEO_CAPTURE,
            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
            PermissionRequest.RESOURCE_MIDI_SYSEX,
        )
    }

    private fun originOf(url: String?): String =
        runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("").ifBlank { "This page" }
}
