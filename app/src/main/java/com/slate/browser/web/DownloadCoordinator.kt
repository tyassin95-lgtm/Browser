package com.slate.browser.web

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.core.content.getSystemService
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Downloads are handed to the platform DownloadManager so they survive the browser being
 * backgrounded or killed, appear in the system download UI, and resume on their own.
 *
 * `blob:` URLs have no counterpart the DownloadManager can fetch — they only exist inside the
 * page — so those are round-tripped through the page itself and written locally.
 */
class DownloadCoordinator(
    private val context: Context,
    private val host: BrowserHost,
) {

    /** The blob download in flight, if any. Cleared by the first reply, right or wrong. */
    @Volatile
    private var pending: PendingBlob? = null

    fun enqueue(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("data:")) saveDataUrl(url, mimeType)
        else enqueueHttp(url, userAgent, contentDisposition, mimeType)
    }

    private fun enqueueHttp(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val (fileName, mime) = DownloadNaming.resolve(url, contentDisposition, mimeType)
        val result = runCatching {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                // The resolved type, not the one the server declared: a video announced as
                // `application/octet-stream` has to reach the media store as a video, or
                // nothing on the device will offer to play it.
                setMimeType(mime)
                addRequestHeader("User-Agent", userAgent ?: "")
                addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                setTitle(fileName)
                setDescription(UriHost(url))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            context.getSystemService<DownloadManager>()?.enqueue(request)
        }
        if (result.isSuccess && result.getOrNull() != null) {
            host.snack("Downloading $fileName")
        } else {
            host.snack("Couldn't start the download")
        }
    }

    private fun saveDataUrl(url: String, mimeType: String?) {
        runCatching {
            val payload = url.substringAfter("base64,", "")
            require(payload.isNotEmpty()) { "unsupported data URL" }
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            // A data URL declares its own type ahead of the comma; that beats whatever the
            // download listener was told.
            val declared = url.removePrefix("data:").substringBefore(';').substringBefore(',')
            val resolved = DownloadNaming.resolveForBytes(declared.ifBlank { mimeType })
            writeToDownloads(resolved.fileName, bytes)
        }.onSuccess { host.snack("Saved ${it.name}") }
            .onFailure { host.snack("Couldn't save this file") }
    }

    /**
     * Pulls a `blob:` URL back out of the page so it can be written to disk.
     *
     * A blob only exists inside the document that made it, so there is nothing the
     * DownloadManager could fetch — the page has to hand the bytes back. That makes this the
     * one path where web content supplies a file the browser then writes, so it is fenced on
     * every side: the script runs in the main frame only, it carries a single-use token the
     * browser generated for this one download, the reply must come from the origin that asked
     * and from the main frame, and the payload is capped.
     */
    fun startBlobDownload(webView: WebView, url: String, mimeType: String?) {
        val token = newToken()
        pending = PendingBlob(token = token, origin = originOf(webView.url), mimeType = mimeType)
        val script = """
            (function() {
              var token = '$token';
              function tell(message) {
                try {
                  message.token = token;
                  if (window.SlateDownload && window.SlateDownload.postMessage) {
                    window.SlateDownload.postMessage(JSON.stringify(message));
                  }
                } catch (e) { /* the browser will time the request out */ }
              }
              try {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', ${JSONObject.quote(url)}, true);
                xhr.responseType = 'blob';
                xhr.onload = function() {
                  if (xhr.status && xhr.status !== 200) { tell({ type: 'failed' }); return; }
                  if (xhr.response.size > $MAX_BLOB_BYTES) { tell({ type: 'failed' }); return; }
                  var reader = new FileReader();
                  reader.onloadend = function() {
                    tell({ type: 'blob', data: reader.result, mime: xhr.response.type || '' });
                  };
                  reader.onerror = function() { tell({ type: 'failed' }); };
                  reader.readAsDataURL(xhr.response);
                };
                xhr.onerror = function() { tell({ type: 'failed' }); };
                xhr.send();
              } catch (e) { tell({ type: 'failed' }); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    /**
     * The reply channel for [startBlobDownload].
     *
     * Reachable by any page — a listener cannot be installed for one document — so being
     * reachable has to mean nothing. A message is only acted on when a download is genuinely
     * outstanding, the token matches the one issued for it, and it came from the origin that
     * asked; the token is spent on first use either way, so a page cannot retry its way in.
     */
    fun bridge(): PageBridge = PageBridge.named(BRIDGE_NAME) { origin, payload ->
        val outstanding = pending ?: return@named
        val token = payload.optString("token")
        // Compared without an early exit: how long a wrong guess takes should not tell a page
        // how much of it was right.
        if (!constantTimeEquals(token, outstanding.token)) return@named
        pending = null
        if (origin != outstanding.origin) return@named

        when (payload.optString("type")) {
            "blob" -> {
                val dataUrl = payload.optString("data")
                if (dataUrl.length > MAX_DATA_URL_CHARS) {
                    host.snack("That file is too large to save")
                    return@named
                }
                val declared = payload.optString("mime").takeIf { it.isNotBlank() }
                    ?: outstanding.mimeType
                saveBytes(dataUrl, declared)
            }

            else -> host.snack("Couldn't save this file")
        }
    }

    private fun saveBytes(dataUrl: String, mimeType: String?) {
        runCatching {
            val payload = dataUrl.substringAfter("base64,", "")
            require(payload.isNotEmpty()) { "unsupported payload" }
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            require(bytes.size <= MAX_BLOB_BYTES) { "too large" }
            writeToDownloads(DownloadNaming.resolveForBytes(mimeType).fileName, bytes)
        }.onSuccess { host.snack("Saved ${it.name}") }
            .onFailure { host.snack("Couldn't save this file") }
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var difference = 0
        for (i in a.indices) difference = difference or (a[i].code xor b[i].code)
        return difference == 0
    }

    private fun originOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return runCatching {
            val uri = Uri.parse(url)
            val host = uri.host ?: return ""
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            "${uri.scheme}://$host$port"
        }.getOrDefault("")
    }

    /** One outstanding blob download, and what it will take to answer it. */
    private data class PendingBlob(val token: String, val origin: String, val mimeType: String?)

    /**
     * Writes into the public Downloads collection. Scoped storage means that is MediaStore on
     * API 29+, and a plain file on the older releases this app still supports.
     */
    private fun writeToDownloads(name: String, bytes: ByteArray): SavedFile {
        val mime = DownloadNaming.mimeForFileName(name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("no download slot")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SavedFile(name)
        }

        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val file = uniqueFile(dir, name)
        file.writeBytes(bytes)
        @Suppress("DEPRECATION")
        runCatching {
            context.getSystemService<DownloadManager>()?.addCompletedDownload(
                file.name, file.name, true, mime, file.absolutePath, bytes.size.toLong(), true,
            )
        }
        return SavedFile(file.name)
    }

    @JvmInline
    value class SavedFile(val name: String)

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(dir, "$base ($index)$suffix")
            index++
        }
        return candidate
    }

    @Suppress("FunctionName")
    private fun UriHost(url: String): String = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")

    private companion object {
        /** The object name the injected script posts to; must match [bridge]. */
        const val BRIDGE_NAME = "SlateDownload"

        /**
         * The largest blob the browser will take back through the page. A blob has to be held
         * in memory twice over — once as bytes, once base64-encoded — so an unbounded one is a
         * way for a page to end the browser rather than a way to save a file.
         */
        const val MAX_BLOB_BYTES = 64 * 1024 * 1024

        /** Base64 is four characters per three bytes, plus the `data:` preamble. */
        const val MAX_DATA_URL_CHARS = MAX_BLOB_BYTES / 3 * 4 + 128
    }
}
