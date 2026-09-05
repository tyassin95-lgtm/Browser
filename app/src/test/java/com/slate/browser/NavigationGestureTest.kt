package com.slate.browser

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import com.slate.browser.web.NavigationDirection
import com.slate.browser.web.NavigationGestureListener
import com.slate.browser.web.SlateWebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The horizontal navigation gesture, judged by the thing that actually matters: whether it
 * fires when the page had no use for the swipe, and stays out of the way when it did.
 */
@RunWith(RobolectricTestRunner::class)
class NavigationGestureTest {

    private lateinit var webView: SlateWebView
    private var started: NavigationDirection? = null
    private var finished: NavigationDirection? = null
    private var cancelled = false
    private var allowBack = true
    private var allowForward = true

    private val slop by lazy {
        ViewConfiguration.get(ApplicationProvider.getApplicationContext()).scaledTouchSlop
    }

    @Before
    fun setUp() {
        started = null
        finished = null
        cancelled = false
        allowBack = true
        allowForward = true
        webView = SlateWebView(ApplicationProvider.getApplicationContext())
        webView.navigationListener = object : NavigationGestureListener {
            override fun canNavigate(direction: NavigationDirection) =
                if (direction == NavigationDirection.BACK) allowBack else allowForward

            override fun onGestureStart(direction: NavigationDirection) {
                started = direction
            }

            override fun onGestureProgress(distancePx: Float) = Unit

            override fun onGestureFinish(distancePx: Float) {
                finished = started
            }

            override fun onGestureCancel() {
                cancelled = true
            }
        }
    }

    private fun swipe(dx: Float, dy: Float, pointers: Int = 1) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, 300f, 600f)
        if (pointers > 1) send(MotionEvent.ACTION_POINTER_DOWN, down, 320f, 620f)
        // Several moves, the way a finger actually travels.
        for (step in 1..5) {
            send(MotionEvent.ACTION_MOVE, down, 300f + dx * step / 5f, 600f + dy * step / 5f)
        }
        send(MotionEvent.ACTION_UP, down, 300f + dx, 600f + dy)
    }

    private fun send(action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        webView.onTouchEvent(event)
        event.recycle()
    }

    @Test
    fun `dragging right goes back`() {
        swipe(dx = slop * 8f, dy = 0f)
        assertEquals(NavigationDirection.BACK, started)
        assertEquals(NavigationDirection.BACK, finished)
    }

    @Test
    fun `dragging left goes forward`() {
        swipe(dx = -slop * 8f, dy = 0f)
        assertEquals(NavigationDirection.FORWARD, started)
    }

    @Test
    fun `a vertical scroll is left to the page`() {
        swipe(dx = 0f, dy = slop * 8f)
        assertNull("scrolling must never navigate", started)
    }

    @Test
    fun `a mostly vertical drag is left to the page`() {
        swipe(dx = slop * 3f, dy = slop * 8f)
        assertNull(started)
    }

    @Test
    fun `a short drag is left to the page`() {
        swipe(dx = slop * 1f, dy = 0f)
        assertNull(started)
    }

    @Test
    fun `a carousel or slider under the finger wins`() {
        // What the page agent reports on touch down when the target pans horizontally itself.
        webView.suppressNavigationGesture = true
        swipe(dx = slop * 8f, dy = 0f)
        assertNull("the page's own horizontal surface must keep the gesture", started)
    }

    @Test
    fun `a pinch is never navigation`() {
        swipe(dx = slop * 8f, dy = 0f, pointers = 2)
        assertNull(started)
    }

    @Test
    fun `nothing happens at the ends of the history`() {
        allowBack = false
        swipe(dx = slop * 8f, dy = 0f)
        assertNull("a gesture that cannot do anything must be left to the page", started)

        allowForward = false
        swipe(dx = -slop * 8f, dy = 0f)
        assertNull(started)
    }

    @Test
    fun `once the drag turns vertical it stays the page's`() {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, 300f, 600f)
        send(MotionEvent.ACTION_MOVE, down, 300f, 600f + slop * 4f)
        // A late horizontal flick must not steal a scroll already in progress.
        send(MotionEvent.ACTION_MOVE, down, 300f + slop * 10f, 600f + slop * 4f)
        send(MotionEvent.ACTION_UP, down, 300f + slop * 10f, 600f + slop * 4f)
        assertNull(started)
    }

    @Test
    fun `an interrupted gesture is cancelled, not committed`() {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, 300f, 600f)
        send(MotionEvent.ACTION_MOVE, down, 300f + slop * 8f, 600f)
        send(MotionEvent.ACTION_CANCEL, down, 300f + slop * 8f, 600f)
        assertEquals(NavigationDirection.BACK, started)
        assertNull(finished)
        assertEquals(true, cancelled)
    }
}
