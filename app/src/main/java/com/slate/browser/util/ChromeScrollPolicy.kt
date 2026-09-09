package com.slate.browser.util

import kotlin.math.abs

/**
 * Decides, from the page's scroll position, whether the browser chrome should be showing.
 *
 * The toolbar is part of the layout rather than drawn over the page, so every change to its
 * visibility resizes the WebView. That is fine once, and ruinous sixty times a second: a
 * WebView resized mid-scroll re-lays out and re-rasters while the compositor is still moving,
 * which is what produces torn and duplicated frames. It is worse in landscape, where the bar is
 * above the page — hiding it moves the page's origin as well as its height — and where the
 * viewport is short enough that the bar is a large fraction of it.
 *
 * So the decision is deliberately reluctant:
 *
 *  - travel is accumulated in one direction and reset the moment the finger reverses, so a
 *    decisive scroll moves the toolbar and jitter never does;
 *  - distances are in density-independent pixels, so the behaviour is the same on every screen
 *    rather than four times more sensitive on a 4x panel;
 *  - a single frame too large to have come from a finger is ignored, because that is the
 *    signature of the resize we ourselves just caused — which is what would otherwise close a
 *    feedback loop of resize, scroll, resize;
 *  - the movement our own resize causes is absorbed exactly, rather than being hoped to fall
 *    outside a threshold, because it does not;
 *  - and at the very bottom of a page the decision is frozen, because that is where a page has
 *    no room to absorb the resize and every clamp becomes another scroll event.
 *
 * The last two exist because of a loop that was visible on any long page. Hiding the toolbar
 * makes the WebView taller, which shortens the page's scroll range; at the bottom the engine has
 * to clamp the scroll position by exactly the toolbar's height, and reports that clamp as an
 * upward scroll. An upward scroll of a toolbar's height is far past the threshold for showing
 * the toolbar — so it came back, which shortened the viewport, which let the next downward pixel
 * hide it again. The toolbar flickered on and off for as long as a finger stayed near the
 * bottom of the page, and every step of it was the browser reacting to itself.
 *
 * The result is only a decision. Applying it waits for scrolling to stop; see the caller.
 */
class ChromeScrollPolicy(density: Float) {

    private val hideDistancePx = HIDE_DISTANCE_DP * density
    private val showDistancePx = SHOW_DISTANCE_DP * density
    private val topSlopPx = TOP_SLOP_DP * density
    private val implausibleFramePx = IMPLAUSIBLE_FRAME_DP * density
    private val absorbSlopPx = ABSORB_SLOP_DP * density

    private var travel = 0f
    private var movingDown = true

    /**
     * Movement still to be discounted as the browser's own.
     *
     * Set to the height of the chrome whenever the chrome changes, because that is exactly how
     * far the engine may move the page in response — no more and no less. Spending it, rather
     * than waiting out a timer, means a real gesture in the same moment is still felt: only the
     * pixels the resize can account for are ignored.
     */
    private var absorb = 0f

    /** What the chrome should end up doing once the page stops moving. */
    var desired: Boolean = true
        private set

    fun onScroll(deltaPx: Int, scrollY: Int, atBottom: Boolean = false) {
        // The top of a page always shows the toolbar: there is nothing above to reveal.
        if (scrollY <= topSlopPx) {
            travel = 0f
            absorb = 0f
            desired = true
            return
        }
        if (deltaPx == 0) return
        if (abs(deltaPx) > implausibleFramePx) {
            travel = 0f
            return
        }
        if (absorb > 0f) {
            absorb -= abs(deltaPx.toFloat())
            if (absorb <= 0f) absorb = 0f
            travel = 0f
            return
        }
        // At the bottom there is nothing left to reveal by scrolling further, and no room for
        // the page to take up the change in the viewport's height — so every adjustment lands
        // back here as another scroll event. Whatever the toolbar is doing when the page runs
        // out, it goes on doing.
        if (atBottom) {
            travel = 0f
            return
        }

        val down = deltaPx > 0
        if (down != movingDown) {
            movingDown = down
            travel = 0f
        }
        travel += abs(deltaPx.toFloat())

        when {
            down && travel >= hideDistancePx -> settle(false)
            !down && travel >= showDistancePx -> settle(true)
        }
    }

    /** Called when something other than scrolling decides the chrome's state. */
    fun reset(visible: Boolean) {
        desired = visible
        travel = 0f
        absorb = 0f
    }

    /**
     * The chrome has just appeared or disappeared, and the page is about to move because of it.
     *
     * [chromeHeightPx] is the whole of the movement the engine can produce in response: the
     * WebView changed height by exactly that much, so a page against its end can be clamped by
     * exactly that much. Discounting it is what keeps the browser from reading its own resize as
     * the user asking for the toolbar back.
     */
    fun onChromeChanged(chromeHeightPx: Float) {
        travel = 0f
        absorb = chromeHeightPx.coerceAtLeast(0f) + absorbSlopPx
    }

    private fun settle(value: Boolean) {
        desired = value
        travel = 0f
    }

    private companion object {
        /** A deliberate push down the page, not a nudge. */
        const val HIDE_DISTANCE_DP = 56f

        /** Reaching back for the toolbar should feel quicker than losing it. */
        const val SHOW_DISTANCE_DP = 32f

        const val TOP_SLOP_DP = 16f

        /**
         * A little more than the chrome's height, since a relayout can carry a page a pixel or
         * two further than the height that caused it.
         */
        const val ABSORB_SLOP_DP = 8f

        /** No finger moves a page this far between two frames; a relayout does. */
        const val IMPLAUSIBLE_FRAME_DP = 240f
    }
}
