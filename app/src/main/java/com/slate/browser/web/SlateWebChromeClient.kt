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

    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        onJsDialog(JsDialogRequest.Alert(message.orEmpty(), originOf(url), result))
        return true
    }

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        onJsDialog(JsDialogRequest.Confirm(message.orEmpty(), originOf(url), result))
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult,
    ): Boolean {
        onJsDialog(JsDialogRequest.Prompt(message.orEmpty(), originOf(url), defaultValue.orEmpty(), result))
        return true
    }

    override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        onJsDialog(
            JsDialogRequest.Confirm(
                message?.takeIf { it.isNotBlank() } ?: "Leave this page? Changes you made may not be saved.",
                originOf(url),
                result,
            )
        )
        return true
    }

    // ---- Capabilities -------------------------------------------------------

    /**
     * Camera and microphone are granted in two stages: the app must hold the Android runtime
     * permission, and the user must then allow this specific origin. Neither is assumed.
     */
    override fun onPermissionRequest(request: PermissionRequest) {
        val wanted = request.resources.orEmpty()
        val androidPermissions = buildList {
            if (wanted.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) add(Manifest.permission.CAMERA)
            if (wanted.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) add(Manifest.permission.RECORD_AUDIO)
        }
        val labels = buildList {
            if (wanted.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) add("camera")
            if (wanted.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) add("microphone")
            if (wanted.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) add("protected media")
            if (wanted.contains(PermissionRequest.RESOURCE_MIDI_SYSEX)) add("MIDI devices")
        }

        // Protected media (DRM) alone needs no Android permission and no extra prompt: it is
        // what makes commercial video play at all.
        if (labels.size == 1 && labels.first() == "protected media") {
            request.grant(wanted)
            return
        }

        val origin = request.origin?.host ?: "This site"
        onSitePermission(
            SitePermissionRequest(origin, labels) { allowed ->
                if (!allowed) {
                    request.deny()
                    return@SitePermissionRequest
                }
                if (androidPermissions.isEmpty()) {
                    request.grant(wanted)
                } else {
                    host.requestSystemPermissions(androidPermissions.toTypedArray()) { granted ->
                        if (granted) request.grant(wanted) else request.deny()
                    }
                }
            }
        )
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) = Unit

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
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

    private fun originOf(url: String?): String =
        runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("").ifBlank { "This page" }
}
