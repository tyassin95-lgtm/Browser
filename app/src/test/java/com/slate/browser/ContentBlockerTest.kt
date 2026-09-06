package com.slate.browser

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.core.app.ApplicationProvider
import com.slate.browser.web.ContentBlocker
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the blocker refuses, and — more importantly — what it does not.
 *
 * A blocker that is slightly too eager breaks sign-ins and payment flows, which costs far more
 * than the advert it removed.
 */
@RunWith(RobolectricTestRunner::class)
class ContentBlockerTest {

    private lateinit var blocker: ContentBlocker

    @Before
    fun setUp() {
        blocker = ContentBlocker(ApplicationProvider.getApplicationContext())
        assertTrue("filter lists failed to load: ${blocker.loadFailure}", blocker.awaitReady())
    }

    private fun request(
        url: String,
        mainFrame: Boolean = false,
        headers: MutableMap<String, String> = mutableMapOf(),
    ): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = headers
    }

    private fun blocks(url: String, page: String = "https://example.com/", headers: MutableMap<String, String> = mutableMapOf()) =
        blocker.shouldBlock(request(url, headers = headers), page)

    @Test
    fun `the published lists are loaded, not a handful of domains`() {
        // The point of the rewrite: the rule set is the community lists, at their real size.
        assertTrue("only ${blocker.ruleCount} rules loaded", blocker.ruleCount > 50_000)
    }

    @Test
    fun `known advertising and tracking requests are blocked`() {
        listOf(
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
            "https://ib.adnxs.com/ttj?id=123",
            "https://static.criteo.net/js/ld/ld.js",
            "https://s.magsrv.com/splash.php?idzone=1",
            "https://a.exdynsrv.com/ads.js",
            "https://www.googletagmanager.com/gtag/js?id=G-1",
        ).forEach { url -> assertTrue("$url must be blocked", blocks(url)) }
    }

    @Test
    fun `ordinary browsing is left alone`() {
        listOf(
            "https://en.wikipedia.org/w/load.php",
            "https://github.githubassets.com/assets/app.js",
            "https://accounts.google.com/gsi/client",
            "https://js.stripe.com/v3/",
            "https://cdn.jsdelivr.net/npm/vue/dist/vue.js",
            "https://fonts.gstatic.com/s/roboto/v30/font.woff2",
        ).forEach { url -> assertFalse("$url must not be blocked", blocks(url)) }
    }

    @Test
    fun `a site's own resources are never treated as third-party advertising`() {
        // Cam sites are themselves advertisers, so the lists block them as a third party. On
        // their own pages that is first-party and must load.
        assertFalse(
            blocks("https://static.livejasmin.com/player/player.js", page = "https://m.livejasmin.com/"),
        )
        // The registrable domain decides party, so a site serving its own player is first-party
        // even when the path reads like ad tooling.
        assertFalse(blocks("https://cdn.example.com/ads/player.js", page = "https://example.com/watch"))
    }

    @Test
    fun `type scoping is honoured`() {
        // `$script` rules must not fire on an image request and the other way round; getting
        // this wrong is how a blocker starts eating page furniture.
        val asImage = mutableMapOf("Sec-Fetch-Dest" to "image")
        assertFalse(blocks("https://example.com/logo.png", headers = asImage))
    }

    @Test
    fun `the page the user asked for is never blocked as a subresource`() {
        // Where to navigate is a decision for the navigation policy, which can explain itself.
        assertFalse(
            blocker.shouldBlock(
                request("https://pagead2.googlesyndication.com/", mainFrame = true),
                "https://example.com/",
            ),
        )
    }

    @Test
    fun `popup destinations are recognised as such`() {
        assertTrue(blocker.isBlockedDestination("https://go.propellerads.com/redirect?x=1"))
        assertFalse(blocker.isBlockedDestination("https://en.wikipedia.org/wiki/Main_Page"))
        assertFalse(blocker.isBlockedDestination("not a url at all"))
    }

    @Test
    fun `a blocked image gets an image back, so nothing draws a broken glyph`() {
        val headers = mutableMapOf("Accept" to "image/webp,image/*")
        assertEquals("image/gif", blocker.blockedResponse(request("https://doubleclick.net/b.gif", headers = headers)).mimeType)
        assertEquals("text/plain", blocker.blockedResponse(request("https://doubleclick.net/a.js")).mimeType)
    }

    /**
     * The real thing: every third-party address embedded in the live markup of the sites the
     * blocking was reported inadequate on, fetched from those sites and replayed through the
     * shipped engine and the shipped lists.
     *
     * The assertions are what the outcome has to be for the page to come out right — the
     * advertising and tracking hosts those pages actually embed are refused, and the hosts that
     * carry the pages' own images and video are not. A blocker that got the second half wrong
     * would score well on "how much did you block" and leave the user with a broken site.
     */
    @Test
    fun `real requests captured from the reported sites get the right verdicts`() {
        val corpus = JSONObject(
            javaClass.classLoader!!.getResourceAsStream("live-site-requests.json")!!
                .bufferedReader().readText(),
        )
        val verdicts = HashMap<String, MutableList<String>>()
        for (site in corpus.keys()) {
            val urls = corpus.getJSONArray(site)
            for (i in 0 until urls.length()) {
                val url = urls.getString(i)
                val host = runCatching { Uri.parse(url).host }.getOrNull() ?: continue
                verdicts.getOrPut(host.lowercase()) { mutableListOf() }
                    .add(if (blocks(url, page = "https://$site/")) "block" else "allow")
            }
        }
        assertTrue("the captured corpus is missing", verdicts.size > 40)

        // Advertising and tracking these pages really embed.
        listOf(
            "a.adtng.com", "a.magsrv.com", "cdn.tsyndicate.com", "www.exoclick.com",
            "www.googletagmanager.com", "go.mayzaent.com", "go.bluetrafficstream.com",
            "go.whitetrafsa.com", "s.happyleafmotion.com",
        ).forEach { host ->
            assertEquals("$host must be blocked", listOf("block"), verdicts[host]?.distinct())
        }

        // What those same pages need in order to work.
        listOf(
            "cdn.javmiku.com", "xmoviescdn.online", "vidara.so", "doodstream.com",
            "lulustream.com", "fonts.googleapis.com", "cdnjs.cloudflare.com",
        ).forEach { host ->
            assertEquals("$host must not be blocked", listOf("allow"), verdicts[host]?.distinct())
        }
    }

    @Test
    fun `element hiding rules are indexed for the sites that have them`() {
        // Site-scoped cosmetic rules are what removes an overlay served from the site's own
        // origin, which no network rule can reach.
        assertTrue(blocker.cosmeticSelectors("jav.guru").isNotEmpty())
        assertTrue(blocker.cosmeticSelectors("some-site-nobody-wrote-rules-for.test").isNotEmpty())
    }

}
