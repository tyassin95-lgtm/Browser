package com.slate.browser

import com.slate.browser.web.DownloadNaming
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a download ends up called, and what Android is told it is.
 *
 * The cases are the ones that produced `.bin`: a generic content type, a missing one, a name
 * that only the address knows, and a name that only the header knows.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadNamingTest {

    private fun resolve(url: String, disposition: String? = null, mime: String? = null) =
        DownloadNaming.resolve(url, disposition, mime)

    @Test
    fun `a video served as octet-stream keeps its own extension and gains its real type`() {
        val resolved = resolve("https://cdn.example.com/videos/clip.mp4", mime = "application/octet-stream")
        assertEquals("clip.mp4", resolved.fileName)
        assertEquals("video/mp4", resolved.mimeType)
    }

    @Test
    fun `a generic content type is never the source of an extension`() {
        // This is the whole bug: the platform helper answers ".bin" here.
        listOf(
            "application/octet-stream",
            "binary/octet-stream",
            "application/force-download",
            "application/unknown",
        ).forEach { generic ->
            val resolved = resolve("https://example.com/files/holiday.mp4", mime = generic)
            assertEquals("holiday.mp4", resolved.fileName)
            assertEquals("video/mp4", resolved.mimeType)
        }
    }

    @Test
    fun `the name in Content-Disposition wins over the address`() {
        val resolved = resolve(
            "https://example.com/download.php?id=91827",
            disposition = "attachment; filename=\"Holiday Video.mp4\"",
            mime = "application/octet-stream",
        )
        assertEquals("Holiday Video.mp4", resolved.fileName)
        assertEquals("video/mp4", resolved.mimeType)
    }

    @Test
    fun `the RFC 5987 form is understood, which is how non-ASCII names arrive`() {
        val resolved = resolve(
            "https://example.com/get",
            disposition = "attachment; filename=\"fallback.dat\"; filename*=UTF-8''caf%C3%A9%20song.mp3",
        )
        assertEquals("café song.mp3", resolved.fileName)
        assertEquals("audio/mpeg", resolved.mimeType)
    }

    @Test
    fun `a real content type supplies the extension when nothing else has one`() {
        val resolved = resolve("https://example.com/stream/2481", mime = "video/mp4")
        assertTrue(resolved.fileName, resolved.fileName.endsWith(".mp4"))
        assertEquals("video/mp4", resolved.mimeType)
    }

    @Test
    fun `common types all survive the round trip`() {
        val cases = mapOf(
            "https://e.com/a/photo.jpg" to ("photo.jpg" to "image/jpeg"),
            "https://e.com/a/shot.png" to ("shot.png" to "image/png"),
            "https://e.com/a/anim.webp" to ("anim.webp" to "image/webp"),
            "https://e.com/a/report.pdf" to ("report.pdf" to "application/pdf"),
            "https://e.com/a/bundle.zip" to ("bundle.zip" to "application/zip"),
            "https://e.com/a/track.mp3" to ("track.mp3" to "audio/mpeg"),
            "https://e.com/a/movie.mkv" to ("movie.mkv" to "video/x-matroska"),
            "https://e.com/a/clip.webm" to ("clip.webm" to "video/webm"),
            "https://e.com/a/notes.txt" to ("notes.txt" to "text/plain"),
            "https://e.com/a/app.apk" to ("app.apk" to "application/vnd.android.package-archive"),
        )
        cases.forEach { (url, expected) ->
            val resolved = resolve(url, mime = "application/octet-stream")
            assertEquals(url, expected.first, resolved.fileName)
            assertEquals(url, expected.second, resolved.mimeType)
        }
    }

    @Test
    fun `a query string is not part of the name`() {
        val resolved = resolve("https://cdn.example.com/v/clip.mp4?token=abc123&expires=99")
        assertEquals("clip.mp4", resolved.fileName)
    }

    @Test
    fun `a percent-encoded name is decoded`() {
        val resolved = resolve("https://example.com/files/My%20Report%202024.pdf")
        assertEquals("My Report 2024.pdf", resolved.fileName)
        assertEquals("application/pdf", resolved.mimeType)
    }

    @Test
    fun `a header trying to escape the downloads folder cannot`() {
        val resolved = resolve(
            "https://example.com/x",
            disposition = "attachment; filename=\"../../../etc/passwd\"",
        )
        assertEquals("passwd", resolved.fileName)
    }

    @Test
    fun `nothing to go on produces a plain name rather than a false extension`() {
        val resolved = resolve("https://example.com/", mime = "application/octet-stream")
        assertTrue(resolved.fileName, resolved.fileName.startsWith("download-"))
        assertTrue(resolved.fileName, !resolved.fileName.contains('.'))
        assertEquals("application/octet-stream", resolved.mimeType)
    }

    @Test
    fun `the content type names the file when the address cannot`() {
        // Some CDNs serve every download from one endpoint; there the type is all there is.
        val resolved = resolve("https://example.com/dl.php", mime = "application/pdf")
        assertEquals("dl.pdf", resolved.fileName)
        assertEquals("application/pdf", resolved.mimeType)
    }

    @Test
    fun `a misleading content type does not rename a file that is already named`() {
        // Both of these are real responses: servers mislabel far more often than addresses do,
        // so a name that already ends in a real extension keeps it.
        val zip = resolve("https://codeload.github.com/x/y/zip/refs/tags/v1.zip", mime = "application/json")
        assertEquals("v1.zip", zip.fileName)
        assertEquals("application/zip", zip.mimeType)

        val jpeg = resolve("https://upload.example.org/photo.jpg", mime = "text/html; charset=utf-8")
        assertEquals("photo.jpg", jpeg.fileName)
        assertEquals("image/jpeg", jpeg.mimeType)
    }

    /**
     * Real responses, replayed.
     *
     * The `Content-Type` and `Content-Disposition` here were captured from the live servers by
     * `tools/capture-download-headers.mjs`, misleading ones included — a zip served as JSON and
     * a JPEG served as HTML are both in the sample. Every one of them has to arrive named after
     * what it is.
     */
    @Test
    fun `real download responses all keep their real names and types`() {
        val lines = javaClass.classLoader!!.getResourceAsStream("live-download-headers.jsonl")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }
        assertTrue("the captured sample is missing", lines.size >= 5)
        lines.forEach { line ->
            val json = JSONObject(line)
            val url = json.getString("url")
            val expected = url.substringAfterLast('/')
            val resolved = resolve(
                url,
                disposition = json.getString("contentDisposition").ifBlank { null },
                mime = json.getString("contentType").ifBlank { null },
            )
            assertEquals(url, expected, resolved.fileName)
            assertEquals(
                url,
                DownloadNaming.mimeForFileName(expected),
                resolved.mimeType,
            )
            assertTrue("$url became $resolved", !resolved.fileName.endsWith(".bin"))
        }
    }

    @Test
    fun `a file already named correctly reports the right type for the media store`() {
        assertEquals("video/mp4", DownloadNaming.mimeForFileName("clip.mp4"))
        assertEquals("image/jpeg", DownloadNaming.mimeForFileName("a.jpg"))
        assertEquals("application/octet-stream", DownloadNaming.mimeForFileName("mystery"))
    }
}
