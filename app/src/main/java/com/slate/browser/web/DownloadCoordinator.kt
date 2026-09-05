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
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import androidx.core.content.getSystemService
import java.io.File

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

    /** True only between asking a page for a blob and receiving it back over the bridge. */
    private var awaitingBlob = false

    fun enqueue(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("data:")) saveDataUrl(url, mimeType)
        else enqueueHttp(url, userAgent, contentDisposition, mimeType)
    }

    private fun enqueueHttp(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val result = runCatching {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
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
            writeToDownloads(defaultName(mimeType), bytes)
        }.onSuccess { host.snack("Saved ${it.name}") }
            .onFailure { host.snack("Couldn't save this file") }
    }

    /**
     * Injected into the page to pull a blob back across the JS bridge. Kept deliberately small:
     * the bridge is the only script this browser ever adds to a page.
     */
    fun startBlobDownload(webView: WebView, url: String, mimeType: String?) {
        awaitingBlob = true
        val script = """
            (function() {
              var xhr = new XMLHttpRequest();
              xhr.open('GET', '${url.replace("'", "\\'")}', true);
              xhr.responseType = 'blob';
              xhr.onload = function() {
                if (xhr.status !== 200) { SlateDownload.failed(); return; }
                var reader = new FileReader();
                reader.onloadend = function() {
                  SlateDownload.receive(reader.result, xhr.response.type || '');
                };
                reader.onerror = function() { SlateDownload.failed(); };
                reader.readAsDataURL(xhr.response);
              };
              xhr.onerror = function() { SlateDownload.failed(); };
              xhr.send();
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    /**
     * The bridge is exposed to every page, so it must not be a way for a page to write files on
     * its own initiative: a call is only honoured while the browser is genuinely waiting for a
     * blob the user asked to download, and the permission is consumed by the first call.
     */
    inner class JsBridge {
        @JavascriptInterface
        fun receive(dataUrl: String, mimeType: String) {
            if (!awaitingBlob) return
            awaitingBlob = false
            runCatching {
                val payload = dataUrl.substringAfter("base64,", "")
                require(payload.isNotEmpty())
                val bytes = Base64.decode(payload, Base64.DEFAULT)
                writeToDownloads(defaultName(mimeType.ifBlank { null }), bytes)
            }.onSuccess { host.snack("Saved ${it.name}") }
                .onFailure { host.snack("Couldn't save this file") }
        }

        @JavascriptInterface
        fun failed() {
            if (!awaitingBlob) return
            awaitingBlob = false
            host.snack("Couldn't save this file")
        }
    }

    /**
     * Writes into the public Downloads collection. Scoped storage means that is MediaStore on
     * API 29+, and a plain file on the older releases this app still supports.
     */
    private fun writeToDownloads(name: String, bytes: ByteArray): SavedFile {
        val mime = guessMime(name)
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

    private fun defaultName(mimeType: String?): String {
        val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val stamp = System.currentTimeMillis()
        return if (extension.isNullOrBlank()) "download-$stamp" else "download-$stamp.$extension"
    }

    private fun guessMime(name: String): String {
        val extension = name.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    @Suppress("FunctionName")
    private fun UriHost(url: String): String = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
}
