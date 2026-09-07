package com.slate.browser

import com.slate.browser.web.DownloadNaming
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which downloads are worth stopping the user for.
 *
 * A download the user did not understand is the last step of most attacks that get this far, so
 * the question is not whether the file is malicious — the browser cannot know — but whether
 * opening it would run somebody else's code, and whether its name is trying to hide that.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadSafetyTest {

    private fun resolve(url: String, disposition: String? = null, mime: String? = null) =
        DownloadNaming.resolve(url, disposition, mime)

    @Test
    fun `installable packages and scripts are recognised`() {
        listOf(
            "https://e.test/app.apk", "https://e.test/mod.xapk", "https://e.test/tool.exe",
            "https://e.test/setup.msi", "https://e.test/run.sh", "https://e.test/x.jar",
            "https://e.test/a.dex", "https://e.test/go.bat", "https://e.test/p.ps1",
            "https://e.test/s.vbs", "https://e.test/l.lnk", "https://e.test/pkg.deb",
        ).forEach { url ->
            assertTrue("$url must be treated as executable", resolve(url).isExecutable)
        }
    }

    @Test
    fun `ordinary files are not interrupted`() {
        // A browser that asks about every photo teaches people to say yes without reading.
        listOf(
            "https://e.test/photo.jpg", "https://e.test/report.pdf", "https://e.test/clip.mp4",
            "https://e.test/song.mp3", "https://e.test/notes.txt", "https://e.test/sheet.xlsx",
            "https://e.test/archive.zip", "https://e.test/page.html",
        ).forEach { url ->
            assertFalse("$url must download without a prompt", resolve(url).isExecutable)
            assertFalse("$url must not look disguised", resolve(url).isDisguised)
        }
    }

    @Test
    fun `a name dressed up as a document is caught`() {
        // The first extension is all a truncating file list shows.
        val disguised = resolve(
            "https://e.test/download",
            disposition = "attachment; filename=\"Invoice-2024.pdf.apk\"",
        )
        assertTrue(disguised.fileName, disguised.isExecutable)
        assertTrue(disguised.fileName, disguised.isDisguised)

        val photo = resolve("https://e.test/holiday.jpeg.exe")
        assertTrue(photo.isExecutable)
        assertTrue(photo.isDisguised)
    }

    @Test
    fun `a generic content type cannot hide what a file is`() {
        // The server calling an APK `application/octet-stream` changes nothing: the extension
        // from its own header is what the browser reads.
        val apk = resolve(
            "https://e.test/get?id=9",
            disposition = "attachment; filename=app-release.apk",
            mime = "application/octet-stream",
        )
        assertTrue(apk.isExecutable)
    }

    @Test
    fun `a header cannot smuggle a path out of the downloads folder`() {
        val escaped = resolve(
            "https://e.test/x",
            disposition = "attachment; filename=\"../../../../data/data/com.slate.browser/evil.apk\"",
        )
        assertFalse(escaped.fileName, escaped.fileName.contains('/'))
        assertFalse(escaped.fileName, escaped.fileName.contains(".."))
        assertTrue(escaped.isExecutable)
    }
}
