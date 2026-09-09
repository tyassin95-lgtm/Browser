package com.slate.browser

import com.slate.browser.cast.CastDevice
import com.slate.browser.cast.CastStage
import com.slate.browser.cast.CastState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the rest of the browser reads off a cast session.
 *
 * These are the predicates the transport and the toolbar branch on, so getting one wrong sends
 * play/pause to the wrong end — the phone and the television both playing, or neither.
 */
class CastStateTest {

    @Test
    fun `a session that is merely connecting is active but not yet driving playback`() {
        val connecting = CastState(stage = CastStage.CONNECTING, deviceName = "Living Room")
        assertTrue("the indicator must show while connecting", connecting.isActive)
        assertFalse(
            "the transport must still drive the phone until the receiver has the media",
            connecting.isPlayingRemotely,
        )
    }

    @Test
    fun `loading, playing and paused all mean the receiver owns the media`() {
        listOf(CastStage.LOADING, CastStage.PLAYING, CastStage.PAUSED).forEach { stage ->
            assertTrue("$stage must route the transport to the receiver", CastState(stage = stage).isPlayingRemotely)
        }
        assertTrue(CastState(stage = CastStage.PLAYING).isPlaying)
        assertFalse(CastState(stage = CastStage.PAUSED).isPlaying)
    }

    @Test
    fun `an idle or failed session gives the controls back to the phone`() {
        listOf(CastStage.IDLE, CastStage.FAILED, CastStage.UNAVAILABLE).forEach { stage ->
            val state = CastState(stage = stage)
            assertFalse("$stage must not be active", state.isActive)
            assertFalse("$stage must not hold the transport", state.isPlayingRemotely)
        }
    }

    @Test
    fun `the button is offered whenever the framework is up, found a device or not`() {
        assertFalse(
            "no framework means no button, however many devices were remembered",
            CastState(stage = CastStage.UNAVAILABLE, devices = listOf(device())).canOffer,
        )
        // Not conditioned on having already found something. Requiring a device meant the
        // control appeared only after a scan the control itself starts, so on a quiet network
        // the user got no button and no explanation — the picker's empty state is the answer.
        assertTrue(
            "the button must appear before anything has been found",
            CastState(stage = CastStage.IDLE).canOffer,
        )
        assertTrue(CastState(stage = CastStage.IDLE, devices = listOf(device())).canOffer)
    }

    @Test
    fun `the default state offers nothing at all`() {
        // A device without Play Services, or with it disabled, must behave exactly as the
        // browser did before casting existed.
        val fresh = CastState()
        assertEquals(CastStage.UNAVAILABLE, fresh.stage)
        assertFalse(fresh.canOffer)
        assertFalse(fresh.isActive)
        assertFalse(fresh.isPlayingRemotely)
    }

    private fun device() = CastDevice(id = "route-1", name = "Living Room TV", isSelected = false)

    @Test
    fun `buffering is the receiver playing, not the session ending`() {
        // Anything that reads buffering as "not rendering" hands playback back to the phone
        // every time the network hesitates, and the room hears both.
        val buffering = CastState(stage = CastStage.BUFFERING, deviceName = "Living Room")
        assertTrue(buffering.isActive)
        assertTrue("the page must stay pinned while the receiver buffers", buffering.isPlayingRemotely)
        assertTrue("the button must offer to pause, not to start it again", buffering.looksPlaying)
        assertTrue(buffering.isBusy)
    }

    @Test
    fun `a failed session is not a session`() {
        val failed = CastState(stage = CastStage.FAILED, deviceName = "Living Room", message = "No")
        assertFalse("nothing may claim to be casting after a failure", failed.isActive)
        assertFalse(failed.isPlayingRemotely)
        assertTrue("the button must still be offered", failed.canOffer)
    }

    @Test
    fun `a reconnecting session has not handed anything back`() {
        val reconnecting = CastState(stage = CastStage.CONNECTING, deviceName = "Living Room")
        assertTrue(reconnecting.isReconnecting)
        assertFalse(reconnecting.isPlayingRemotely)
    }

    @Test
    fun `the banner says what the session is doing, not what the video is`() {
        assertEquals(
            "Buffering on Living Room…",
            CastState(stage = CastStage.BUFFERING, deviceName = "Living Room").summary,
        )
        assertEquals(
            "Playing on Living Room",
            CastState(stage = CastStage.PLAYING, deviceName = "Living Room").summary,
        )
        assertEquals(
            "Connecting to Living Room…",
            CastState(stage = CastStage.CONNECTING, deviceName = "Living Room").summary,
        )
    }
}
