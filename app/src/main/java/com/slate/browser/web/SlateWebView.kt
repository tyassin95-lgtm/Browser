package com.slate.browser.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import kotlin.math.abs

/** Where a horizontal navigation drag is heading. */
enum class NavigationDirection { BACK, FORWARD }

/**
 * Reports a horizontal drag that the page itself had no use for.
 *
 * [canNavigate] is asked before the gesture is taken over, so a drag toward the end of the
 * history stack is left to the page rather than being swallowed by a gesture that cannot do
 * anything.
 */
interface NavigationGestureListener {
    fun canNavigate(direction: NavigationDirection): Boolean
    fun onGestureStart(direction: NavigationDirection)
    fun onGestureProgress(distancePx: Float)
    fun onGestureFinish(distancePx: Float)
    fun onGestureCancel()
}

/**
 * A WebView that turns an unused horizontal swipe into history navigation.
 *
 * The rule is deliberately conservative: the page is offered every touch first, and the gesture
 * is only taken over once the drag is clearly horizontal *and* nothing on the page wanted it.
 * "Nothing wanted it" is two separate checks — the document itself cannot pan any further
 * ([canScrollHorizontally]), and the in-page agent did not find a horizontal scroller, a slider
 * or an active text selection under the finger. That is what keeps carousels, range inputs,
 * maps and selection handles working normally.
 *
 * Once taken over, the page is sent a cancel so it does not also treat the drag as a tap or a
 * scroll, which is what makes the gesture feel decisive rather than fighting the page.
 */
@SuppressLint("ViewConstructor")
class SlateWebView(context: Context) : WebView(context) {

    var navigationListener: NavigationGestureListener? = null

    /**
     * Set from the page agent on every touch down: true when the finger landed on something
     * that pans horizontally by itself.
     */
    @Volatile
    var suppressNavigationGesture: Boolean = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var decided = false
    private var owning = false
    private var direction = NavigationDirection.BACK

    // Clicks are never synthesised here: every touch that is not a committed horizontal swipe
    // goes to WebView untouched, so taps, links and the accessibility click path behave exactly
    // as they do in a stock WebView.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                decided = false
                owning = false
            }

            // A second finger means pinch-zoom or a two-finger pan, never navigation.
            MotionEvent.ACTION_POINTER_DOWN -> decided = true

            MotionEvent.ACTION_MOVE -> if (!decided && !owning) evaluate(event)
        }

        if (!owning) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> navigationListener?.onGestureProgress(event.x - downX)
            MotionEvent.ACTION_UP -> {
                navigationListener?.onGestureFinish(event.x - downX)
                owning = false
            }
            MotionEvent.ACTION_CANCEL -> {
                navigationListener?.onGestureCancel()
                owning = false
            }
        }
        return true
    }

    private fun evaluate(event: MotionEvent) {
        val dx = event.x - downX
        val dy = event.y - downY

        // Vertical intent settles the question: this is a scroll, leave it alone for good.
        if (abs(dy) > touchSlop && abs(dy) >= abs(dx)) {
            decided = true
            return
        }
        if (abs(dx) < touchSlop * 2 || abs(dx) < abs(dy) * HORIZONTAL_BIAS) return

        decided = true
        if (suppressNavigationGesture) return

        val direction = if (dx > 0) NavigationDirection.BACK else NavigationDirection.FORWARD
        // Negative x means "content moves left", which is what canScrollHorizontally asks about.
        val scrollDirection = if (dx > 0) -1 else 1
        if (canScrollHorizontally(scrollDirection)) return

        val listener = navigationListener ?: return
        if (!listener.canNavigate(direction)) return

        this.direction = direction
        owning = true
        // The page must stop treating this as its own gesture, or it will fire a click on release.
        sendCancelToPage(event)
        listener.onGestureStart(direction)
    }

    private fun sendCancelToPage(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        runCatching { super.onTouchEvent(cancel) }
        cancel.recycle()
    }

    private companion object {
        /** How much more horizontal than vertical a drag must be before it counts. */
        const val HORIZONTAL_BIAS = 1.6f
    }
}
