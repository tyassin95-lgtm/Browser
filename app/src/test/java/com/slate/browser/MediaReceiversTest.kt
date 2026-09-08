package com.slate.browser

import androidx.test.core.app.ApplicationProvider
import com.slate.browser.cast.CastStage
import com.slate.browser.cast.CastState
import com.slate.browser.cast.MediaReceivers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The coordinator that puts Google Cast and DLNA behind one door.
 *
 * The interesting case is the very first state. It is settled during construction, before the
 * browser has installed its listener, and every merge afterwards produces an identical state
 * and is correctly suppressed as a no-op — so a listener that is only told about *changes*
 * hears nothing at all, sits on the default UNAVAILABLE, and the cast button never appears.
 * That is a bug this browser has now shipped once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaReceiversTest {

    private fun receivers() = MediaReceivers(ApplicationProvider.getApplicationContext())

    @Test
    fun `a listener is told the state it arrives to, not only the next one`() {
        val receivers = receivers()
        var seen: CastState? = null
        receivers.onStateChanged = { seen = it }
        assertNotNull("installing a listener must deliver the state that already exists", seen)
        assertEquals(receivers.state, seen)
    }

    @Test
    fun `casting is offered on a phone with no Play Services, because DLNA does not need it`() {
        // Robolectric has no Play Services, so the Cast half can never leave UNAVAILABLE here —
        // exactly the situation on a phone where Cast is missing, disabled or still updating.
        val receivers = receivers()
        var offered = false
        receivers.onStateChanged = { offered = it.canOffer }
        assertTrue(
            "the DLNA half can still search, so the button must be offered",
            offered,
        )
        assertTrue(receivers.state.stage != CastStage.UNAVAILABLE)
    }
}
