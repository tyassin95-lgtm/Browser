package com.slate.browser

import androidx.test.core.app.ApplicationProvider
import com.slate.browser.web.ContentBlocker
import com.slate.browser.web.NavigationDecision
import com.slate.browser.web.NavigationPolicy
import com.slate.browser.web.UserActivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The navigation policy, which is the layer that decides whether a page gets to move the user.
 *
 * The cases that matter are the abusive ones — a play button that opens tabs, a redirect chain,
 * an app hand-off dressed as a link — alongside the ordinary ones that must keep working, since
 * a policy that blocks those is worse than no policy at all.
 */
@RunWith(RobolectricTestRunner::class)
class NavigationPolicyTest {

    private var now = 10_000L
    private lateinit var activation: UserActivation
    private lateinit var policy: NavigationPolicy

    @Before
    fun setUp() {
        now = 10_000L
        activation = UserActivation { now }
        policy = NavigationPolicy(ContentBlocker(ApplicationProvider.getApplicationContext()), activation)
    }

    private fun decide(
        url: String,
        mainFrame: Boolean = true,
        gesture: Boolean = false,
        redirect: Boolean = false,
        from: String? = "https://example.com/watch",
    ) = policy.decide(url, mainFrame, gesture, redirect, from, blockingEnabled = true)

    // ---- Ordinary browsing keeps working -------------------------------------

    @Test
    fun `a link the user taps is allowed`() {
        activation.recordTouch()
        assertEquals(NavigationDecision.Allow, decide("https://other.example/page", gesture = true))
    }

    @Test
    fun `a server redirect is allowed`() {
        // Part of a navigation the user already asked for; sign-in flows are built on these.
        assertEquals(
            NavigationDecision.Allow,
            decide("https://accounts.other.test/oauth", redirect = true),
        )
    }

    @Test
    fun `a page navigating within its own site is allowed`() {
        assertEquals(NavigationDecision.Allow, decide("https://example.com/other"))
    }

    @Test
    fun `subframes are left to the page`() {
        assertEquals(NavigationDecision.Allow, decide("https://widget.test/embed", mainFrame = false))
    }

    // ---- Abuse ---------------------------------------------------------------

    @Test
    fun `a page cannot move the user off-site on its own`() {
        val decision = decide("https://elsewhere.test/landing")
        assertTrue(decision is NavigationDecision.Block)
    }

    @Test
    fun `a touch permits the navigation that follows it`() {
        activation.recordTouch()
        assertEquals(NavigationDecision.Allow, decide("https://elsewhere.test/landing"))
    }

    @Test
    fun `a touch stops permitting things once it is stale`() {
        activation.recordTouch()
        now += 5_000
        assertTrue(decide("https://elsewhere.test/landing") is NavigationDecision.Block)
    }

    @Test
    fun `a known advertising destination is refused even when tapped`() {
        activation.recordTouch()
        val decision = decide("https://go.propellerads.com/x", gesture = true)
        assertTrue("blocking a destination is the point of the blocker", decision is NavigationDecision.Block)
    }

    // ---- Leaving the browser -------------------------------------------------

    @Test
    fun `an app link asks first`() {
        activation.recordTouch()
        val decision = decide("myapp://open/thing", gesture = true)
        assertTrue(decision is NavigationDecision.ConfirmExternal)
    }

    @Test
    fun `an app link nobody asked for is refused outright`() {
        assertTrue(decide("myapp://open/thing") is NavigationDecision.Block)
    }

    @Test
    fun `one touch launches at most one app`() {
        activation.recordTouch()
        assertTrue(decide("myapp://one", gesture = true) is NavigationDecision.ConfirmExternal)
        // The same tap replayed into a second launch is the page helping itself.
        assertTrue(decide("myapp://two", gesture = true) is NavigationDecision.Block)
    }

    @Test
    fun `an intent carrying a web address is kept in this browser`() {
        activation.recordTouch()
        val decision = decide(
            "intent://www.youtube.com/watch?v=x#Intent;scheme=https;package=com.google.android.youtube;end",
            gesture = true,
        )
        assertEquals(
            "handing a web address to another app is how a page moves the user to another browser",
            NavigationDecision.KeepInBrowser("https://www.youtube.com/watch?v=x"),
            decision,
        )
    }

    @Test
    fun `an intent with a web fallback is kept in this browser too`() {
        activation.recordTouch()
        val decision = decide(
            "intent://x/#Intent;scheme=zzz;S.browser_fallback_url=https%3A%2F%2Fexample.org%2Fa;end",
            gesture = true,
        )
        assertEquals(NavigationDecision.KeepInBrowser("https://example.org/a"), decision)
    }

    @Test
    fun `telephone and mail links still work, with a confirmation`() {
        activation.recordTouch()
        assertTrue(decide("tel:+441234567890", gesture = true) is NavigationDecision.ConfirmExternal)
        activation.recordTouch()
        assertTrue(decide("mailto:someone@example.com", gesture = true) is NavigationDecision.ConfirmExternal)
    }

    // ---- Windows -------------------------------------------------------------

    @Test
    fun `one touch opens at most one window`() {
        activation.recordTouch()
        assertTrue("the window the user asked for", policy.allowWindow(null, true))
        assertFalse("and none of the ones the page added", policy.allowWindow(null, true))
        assertFalse(policy.allowWindow(null, true))
    }

    @Test
    fun `a window with no touch behind it is refused`() {
        assertFalse(policy.allowWindow(null, true))
    }

    @Test
    fun `a window aimed at an advertising domain is refused however it was triggered`() {
        activation.recordTouch()
        assertFalse(policy.allowWindow("https://exoclick.com/pop", true))
    }

    @Test
    fun `a fresh touch permits a window again`() {
        activation.recordTouch()
        assertTrue(policy.allowWindow(null, true))
        now += 50
        activation.recordTouch()
        assertTrue("a second real tap is a second real intention", policy.allowWindow(null, true))
    }
}
