package com.slate.browser

import com.slate.browser.util.ChromeScrollPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether the toolbar is showing.
 *
 * Every change here resizes the WebView, so the property that matters is not "does it hide"
 * but "does it stay put when it should" — a rule that flips on ordinary scroll jitter resizes
 * the page continuously, which is what tears.
 */
class ChromeScrollPolicyTest {

    private val density = 3f
    private fun policy() = ChromeScrollPolicy(density)
    private fun dp(value: Float) = (value * density).toInt()

    @Test
    fun `a decisive scroll down hides the toolbar`() {
        val policy = policy()
        var y = 1000
        repeat(6) {
            y += dp(20f)
            policy.onScroll(dp(20f), y)
        }
        assertFalse(policy.desired)
    }

    @Test
    fun `scrolling back up brings it back`() {
        val policy = policy()
        var y = 2000
        repeat(6) { y += dp(20f); policy.onScroll(dp(20f), y) }
        assertFalse(policy.desired)

        repeat(4) { y -= dp(20f); policy.onScroll(-dp(20f), y) }
        assertTrue(policy.desired)
    }

    @Test
    fun `jitter never moves the toolbar`() {
        // A finger held roughly still still produces a stream of small alternating deltas.
        val policy = policy()
        var y = 1000
        repeat(200) { step ->
            val delta = if (step % 2 == 0) dp(6f) else -dp(6f)
            y += delta
            policy.onScroll(delta, y)
        }
        assertTrue("alternating nudges must not accumulate into a decision", policy.desired)
    }

    @Test
    fun `reversing direction discards the travel already banked`() {
        val policy = policy()
        var y = 1000
        // Almost far enough down...
        repeat(2) { y += dp(20f); policy.onScroll(dp(20f), y) }
        // ...then back up, then down again by the same near-miss.
        y -= dp(10f); policy.onScroll(-dp(10f), y)
        repeat(2) { y += dp(20f); policy.onScroll(dp(20f), y) }
        assertTrue("banked travel must not survive a reversal", policy.desired)
    }

    @Test
    fun `the top of the page always shows the toolbar`() {
        val policy = policy()
        var y = 1000
        repeat(6) { y += dp(20f); policy.onScroll(dp(20f), y) }
        assertFalse(policy.desired)

        policy.onScroll(-dp(400f), 0)
        assertTrue(policy.desired)
    }

    @Test
    fun `a jump too large to be a finger is ignored`() {
        // This is the signature of the resize the toolbar itself just caused. Acting on it
        // closes a loop: hide, page reflows, that reflow reads as a scroll, show, reflow again.
        val policy = policy()
        policy.onScroll(dp(600f), 5000)
        assertTrue(policy.desired)

        policy.onScroll(-dp(600f), 5000)
        assertTrue(policy.desired)
    }

    @Test
    fun `the same gesture behaves the same on any screen density`() {
        val outcomes = listOf(1f, 2f, 2.75f, 3.5f, 4f).map { d ->
            val policy = ChromeScrollPolicy(d)
            var y = 1000
            // A 60dp drag, expressed in that screen's pixels.
            repeat(4) {
                val delta = (15f * d).toInt()
                y += delta
                policy.onScroll(delta, y)
            }
            policy.desired
        }
        assertEquals(
            "a fixed physical distance must decide the same thing everywhere",
            1,
            outcomes.distinct().size,
        )
        assertFalse(outcomes.first())
    }

    @Test
    fun `a small deliberate scroll is not enough on its own`() {
        val policy = policy()
        var y = 1000
        repeat(2) { y += dp(10f); policy.onScroll(dp(10f), y) }
        assertTrue(policy.desired)
    }

    @Test
    fun `reset forgets everything`() {
        val policy = policy()
        var y = 1000
        repeat(6) { y += dp(20f); policy.onScroll(dp(20f), y) }
        assertFalse(policy.desired)

        policy.reset(visible = true)
        assertTrue(policy.desired)
        // And the travel that had built up is gone with it.
        y += dp(20f)
        policy.onScroll(dp(20f), y)
        assertTrue(policy.desired)
    }
}
