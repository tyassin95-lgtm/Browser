package com.slate.browser.web

import android.net.Uri
import android.webkit.MimeTypeMap
import java.net.URLDecoder

/**
 * Works out what a download should be called and what it actually is.
 *
 * `URLUtil.guessFileName` is the obvious tool for this and it is the reason downloads arrive as
 * `.bin`: when it cannot derive an extension it appends one, and it cannot derive one from the
 * generic content types servers send for file downloads — `application/octet-stream` above all.
 * A video served that way became `downloadfile.bin`, which Android then had no idea how to open.
 *
 * The rule here is that a generic content type carries no information and must never be a
 * source of an extension. What is left is the two places that do carry information — the
 * `Content-Disposition` header and the address itself — and either of those is preferred over
 * the type. Only when all three are silent is a name invented, and then without an extension
 * that would claim something untrue.
 */
object DownloadNaming {

    data class Resolved(val fileName: String, val mimeType: String)

    /**
     * Content types that mean "some bytes are coming" and nothing more. Servers use these for
     * anything they want the browser to download rather than display, so they say nothing about
     * what the file is.
     */
    private val GENERIC_TYPES = setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "application/octetstream",
        "application/force-download",
        "application/download",
        "application/x-download",
        "application/unknown",
        "application/binary",
        "*/*",
    )

    /**
     * The browser's own extension-to-type table.
     *
     * Consulted before the platform's [MimeTypeMap], which is missing entries on some devices
     * and differs between them — a download must not be labelled correctly on one phone and
     * not on another. The platform map is still asked afterwards, so anything unusual is
     * still resolved; this is the floor, not the whole answer.
     */
    private val KNOWN_TYPES: Map<String, String> = mapOf(
        // Images
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "svg" to "image/svg+xml", "ico" to "image/x-icon", "avif" to "image/avif",
        "heic" to "image/heic", "heif" to "image/heif", "jxl" to "image/jxl",
        "tif" to "image/tiff", "tiff" to "image/tiff",
        // Video
        "mp4" to "video/mp4", "m4v" to "video/x-m4v", "webm" to "video/webm",
        "mkv" to "video/x-matroska", "mov" to "video/quicktime", "avi" to "video/x-msvideo",
        "3gp" to "video/3gpp", "flv" to "video/x-flv", "wmv" to "video/x-ms-wmv",
        "mpg" to "video/mpeg", "mpeg" to "video/mpeg", "ts" to "video/mp2t",
        "m3u8" to "application/vnd.apple.mpegurl", "mpd" to "application/dash+xml",
        // Audio
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac",
        "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/opus",
        "flac" to "audio/flac", "wav" to "audio/wav", "weba" to "audio/webm",
        "mid" to "audio/midi", "amr" to "audio/amr",
        // Documents
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown",
        "csv" to "text/csv", "html" to "text/html", "htm" to "text/html",
        "xml" to "text/xml", "json" to "application/json", "rtf" to "application/rtf",
        "epub" to "application/epub+zip",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        // Archives and packages
        "zip" to "application/zip", "gz" to "application/gzip", "tar" to "application/x-tar",
        "7z" to "application/x-7z-compressed", "rar" to "application/vnd.rar",
        "bz2" to "application/x-bzip2", "xz" to "application/x-xz", "zst" to "application/zstd",
        "apk" to "application/vnd.android.package-archive",
        "torrent" to "application/x-bittorrent",
    )

    /**
     * The reverse direction. Built from [KNOWN_TYPES], keeping the first extension listed for
     * each type as the canonical one, plus the aliases servers actually send.
     */
    private val KNOWN_EXTENSIONS: Map<String, String> =
        buildMap {
            KNOWN_TYPES.forEach { (extension, type) -> putIfAbsent(type, extension) }
            put("audio/x-m4a", "m4a")
            put("audio/mpeg3", "mp3")
            put("audio/x-mpeg-3", "mp3")
            put("audio/mp3", "mp3")
            put("image/jpg", "jpg")
            put("application/x-zip-compressed", "zip")
            put("text/javascript", "js")
            put("application/x-pdf", "pdf")
        }

    /** The file name and content type to save a download under. */
    fun resolve(url: String, contentDisposition: String?, mimeType: String?): Resolved {
        val declaredType = normaliseType(mimeType)
        val fromDisposition = fileNameFromDisposition(contentDisposition)
        val fromUrl = fileNameFromUrl(url)

        val candidate = fromDisposition ?: fromUrl
        val candidateExtension = extensionOf(candidate)

        /*
         * A name that already ends in a real file extension keeps it.
         *
         * The extension came from the server's own `Content-Disposition` or from the address,
         * and both describe the file; the content type is the field servers get wrong. Real
         * responses seen while building this include a zip archive served as
         * `application/json` and a JPEG served as `text/html`, and renaming those to match
         * would be worse than the `.bin` this replaced. The type is used where it is the only
         * thing that knows — a download endpoint such as `/dl.php?id=…`, whose address ends in
         * nothing useful.
         */
        val extension = when {
            candidateExtension != null && typeForExtension(candidateExtension) != null ->
                candidateExtension
            declaredType != null -> extensionForType(declaredType) ?: candidateExtension
            else -> candidateExtension
        }

        // And the type follows the extension that was chosen, so what Android records always
        // agrees with what the file is called.
        val mime = extension?.let { typeForExtension(it) }
            ?: declaredType
            ?: "application/octet-stream"

        val base = candidate
            ?.let { if (candidateExtension == null) it else it.dropLast(candidateExtension.length + 1) }
            ?.let(::sanitise)
            ?.takeIf { it.isNotBlank() }
            ?: "download-${System.currentTimeMillis()}"

        val name = if (extension.isNullOrBlank()) base else "$base.$extension"
        return Resolved(name.take(MAX_NAME), mime)
    }

    /** A name for content that arrives with no address at all, such as a `data:` or `blob:` URL. */
    fun resolveForBytes(mimeType: String?, suggestedName: String? = null): Resolved =
        resolve(url = "", contentDisposition = null, mimeType = mimeType).let { resolved ->
            val suggestedExtension = suggestedName?.let(::extensionOf)
            if (suggestedName.isNullOrBlank() || suggestedExtension == null) resolved
            else resolved.copy(fileName = sanitise(suggestedName).take(MAX_NAME))
        }

    /** The content type to record for a file that is already named. */
    fun mimeForFileName(fileName: String): String =
        extensionOf(fileName)?.let { typeForExtension(it) } ?: "application/octet-stream"

    private fun normaliseType(raw: String?): String? {
        val type = raw?.substringBefore(';')?.trim()?.lowercase()
        if (type.isNullOrBlank()) return null
        if (type in GENERIC_TYPES) return null
        // A type has to look like one; WebView occasionally passes through junk.
        if (!type.contains('/')) return null
        return type
    }

    /**
     * The name a server asked for. Handles both forms: the plain `filename=` and RFC 5987's
     * `filename*=UTF-8''…`, which the platform helper ignores and which is the one non-ASCII
     * names actually arrive in.
     */
    internal fun fileNameFromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null

        EXTENDED.find(header)?.let { match ->
            val value = match.groupValues[2]
            val decoded = runCatching {
                URLDecoder.decode(value, match.groupValues[1].ifBlank { "UTF-8" })
            }.getOrNull()
            sanitise(decoded ?: value).takeIf { it.isNotBlank() }?.let { return it }
        }

        PLAIN.find(header)?.let { match ->
            val value = match.groupValues[1].ifBlank { match.groupValues[2] }
            sanitise(value).takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun fileNameFromUrl(url: String): String? {
        if (url.isBlank()) return null
        val path = runCatching { Uri.parse(url).path }.getOrNull() ?: url.substringBefore('?')
        val last = path.trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        return sanitise(decoded).takeIf { it.isNotBlank() }
    }

    /** The extension of a name, lowercased, or null when it has none worth the name. */
    internal fun extensionOf(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        val extension = name.substring(dot + 1).lowercase()
        // Anything longer or stranger than a real extension is part of the name.
        if (extension.length > 5 || !extension.all { it.isLetterOrDigit() }) return null
        return extension
    }

    private fun typeForExtension(extension: String): String? =
        KNOWN_TYPES[extension]
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

    private fun extensionForType(type: String): String? =
        KNOWN_EXTENSIONS[type]
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(type)

    /** Strips everything a file name may not contain, including any attempt at a path. */
    private fun sanitise(raw: String): String =
        raw.substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(ILLEGAL, "_")
            .trim()
            .trim('.')

    private const val MAX_NAME = 128
    private val ILLEGAL = Regex("""[\x00-\x1f<>:"|?*]""")
    private val EXTENDED = Regex("""filename\*\s*=\s*([\w-]*)''([^;\r\n]+)""", RegexOption.IGNORE_CASE)
    private val PLAIN = Regex("""filename\s*=\s*(?:"([^"]*)"|([^;\r\n]+))""", RegexOption.IGNORE_CASE)
}
