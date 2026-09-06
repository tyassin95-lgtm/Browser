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
 *    feedback loop of resize, scroll, resize.
 *
 * The result is only a decision. Applying it waits for scrolling to stop; see the caller.
 */
class ChromeScrollPolicy(density: Float) {

    private val hideDistancePx = HIDE_DISTANCE_DP * density
    private val showDistancePx = SHOW_DISTANCE_DP * density
    private val topSlopPx = TOP_SLOP_DP * density
    private val implausibleFramePx = IMPLAUSIBLE_FRAME_DP * density

    private var travel = 0f
    private var movingDown = true

    /** What the chrome should end up doing once the page stops moving. */
    var desired: Boolean = true
        private set

    fun onScroll(deltaPx: Int, scrollY: Int) {
        // The top of a page always shows the toolbar: there is nothing above to reveal.
        if (scrollY <= topSlopPx) {
            travel = 0f
            desired = true
            return
        }
        if (deltaPx == 0) return
        if (abs(deltaPx) > implausibleFramePx) {
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

        /** No finger moves a page this far between two frames; a relayout does. */
        const val IMPLAUSIBLE_FRAME_DP = 240f
    }
}
