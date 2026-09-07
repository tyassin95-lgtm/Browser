package com.slate.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.core.app.ApplicationProvider
import com.slate.browser.web.ContentBlocker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The blocker must not break the machinery that decides whether a page loads at all.
 *
 * Anti-bot challenges answer with a 5xx and a page that resolves itself once its script runs.
 * Block a piece of that and the challenge can never complete, so the site keeps answering 503
 * and the user is left pressing Retry — a failure the browser caused and the server gets blamed
 * for. Script CDNs are here for the same reason: a page whose jQuery is refused is a page that
 * does not work, and no advert was removed by refusing it.
 */
@RunWith(RobolectricTestRunner::class)
class ChallengeInfrastructureTest {

    private lateinit var blocker: ContentBlocker

    @Before
    fun setUp() {
        blocker = ContentBlocker(ApplicationProvider.getApplicationContext<Context>())
        assertTrue("filter lists failed to load: ${blocker.loadFailure}", blocker.awaitReady())
    }

    private fun blocked(url: String): Boolean {
        val request = object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame() = false
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = mutableMapOf("Sec-Fetch-Dest" to "script")
        }
        return blocker.shouldBlock(request, "https://somesite.example/")
    }

    @Test
    fun `challenge and captcha machinery is never refused`() {
        listOf(
            "https://challenges.cloudflare.com/turnstile/v0/api.js",
            "https://somesite.example/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1",
            "https://somesite.example/cdn-cgi/scripts/7d0fa10a/cloudflare-static/rocket-loader.min.js",
            "https://www.google.com/recaptcha/api.js",
            "https://www.gstatic.com/recaptcha/releases/x/recaptcha__en.js",
            "https://hcaptcha.com/1/api.js",
            "https://newassets.hcaptcha.com/captcha/v1/x/hcaptcha.js",
        ).forEach { url -> assertFalse("$url must load or the challenge can never pass", blocked(url)) }
    }

    @Test
    fun `the libraries pages are built on are never refused`() {
        listOf(
            "https://ajax.googleapis.com/ajax/libs/jquery/3.6.0/jquery.min.js",
            "https://cdnjs.cloudflare.com/ajax/libs/moment.js/2.29.4/moment.min.js",
            "https://cdn.jsdelivr.net/npm/bootstrap@5/dist/js/bootstrap.bundle.min.js",
            "https://code.jquery.com/jquery-3.6.0.min.js",
            "https://unpkg.com/react@18/umd/react.production.min.js",
            "https://somesite.example/wp-includes/js/jquery/jquery.min.js",
            "https://fonts.googleapis.com/css2?family=Roboto",
        ).forEach { url -> assertFalse("$url must not be refused", blocked(url)) }
    }

    @Test
    fun `analytics beacons are still refused`() {
        // The line has to fall somewhere: measurement goes, machinery stays.
        assertTrue(blocked("https://www.googletagmanager.com/gtag/js?id=G-1"))
        assertTrue(blocked("https://static.cloudflareinsights.com/beacon.min.js"))
    }
}
