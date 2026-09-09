package com.slate.browser.cast

import android.content.Context

/**
 * Every way this browser can put a video on another screen, behind one door.
 *
 * There is no single casting standard, and pretending otherwise is how a cast button ends up
 * searching for ever. Google Cast is the better protocol and covers Chromecast, Android TV and
 * Google TV — but Samsung and Amazon both leave it out of their televisions deliberately, and
 * those are a great many living rooms. DLNA is the published standard most of those speak.
 *
 * Both are searched at once and their results are one list; the coordinator remembers which
 * protocol a device came from and sends everything afterwards to the right place. The transport
 * and the picker never learn the difference.
 */
class MediaReceivers(context: Context) {

    private val cast = CastController(context)
    private val dlna = DlnaController(context)

    /** Which controller owns the session, once there is one. */
    private var active: Owner? = null

    private enum class Owner { CAST, DLNA }

    var state: CastState = CastState()
        private set

    /**
     * Told the current state the moment it is installed, not only on the next change.
     *
     * The state is seeded during construction — a phone with no Play Services can still search
     * for DLNA renderers, and that is worth knowing before anything happens. A listener that
     * arrives afterwards would otherwise sit on the default UNAVAILABLE for ever, because the
     * next merge produces an identical state and is correctly suppressed as a no-op. That is
     * exactly how the cast button disappears.
     */
    var onStateChanged: (CastState) -> Unit = {}
        set(value) {
            field = value
            value(state)
        }

    init {
        cast.onStateChanged = { merge() }
        dlna.onStateChanged = { merge() }
        // Seeded rather than left at the default, so a phone with no Play Services still knows
        // it can search for DLNA renderers before either controller has said anything.
        merge()
    }

    fun initialise() = cast.initialise()

    /**
     * Merges the two into one state.
     *
     * Whichever controller has a session speaks for the browser; when neither does, the devices
     * are pooled and the stage is the more capable of the two — Cast reports UNAVAILABLE on a
     * phone without Play Services, and that must not hide the DLNA half.
     */
    private fun merge() {
        val castState = cast.state
        val dlnaState = dlna.state
        val owner = when {
            castState.isActive -> Owner.CAST
            dlnaState.isActive -> Owner.DLNA
            else -> null
        }
        active = owner
        val devices = (castState.devices + dlnaState.devices).distinctBy { it.id }
        val next = when (owner) {
            Owner.CAST -> castState.copy(devices = devices)
            Owner.DLNA -> dlnaState.copy(devices = devices)
            // No session: report whichever has something to say, preferring a real failure
            // message over silence so a refused connection is not swallowed by the other half.
            null -> {
                val base = when {
                    castState.stage == CastStage.FAILED -> castState
                    dlnaState.stage == CastStage.FAILED -> dlnaState
                    castState.stage != CastStage.UNAVAILABLE -> castState
                    else -> dlnaState
                }
                base.copy(devices = devices)
            }
        }
        if (next == state) return
        state = next
        onStateChanged(next)
    }

    /**
     * Whether the connected receiver is a file player rather than a streaming client.
     *
     * A DLNA television plays files; a Cast receiver plays manifests. The two are not asked for
     * the same address, and whatever checks that address beforehand has to check the right one.
     */
    val prefersPlainFile: Boolean get() = active == Owner.DLNA

    /**
     * Whether the phone will be the one fetching the media rather than the receiver.
     *
     * It changes what is worth checking beforehand: a probe that asks "could a stranger fetch
     * this?" answers a question nobody is asking when the fetching is done by the browser, with
     * the page's own cookies, on the connection that is already playing it.
     */
    fun willRelay(castable: CastVerdict.Castable): Boolean =
        canRelay && castable.forFilePlayer.format != StreamFormat.PROGRESSIVE

    /**
     * Whether the phone could fetch this on the receiver's behalf if it had to.
     *
     * It is what turns "the site won't serve this to a television" from a refusal into a
     * different route: the browser is the client the site trusts, and the receiver only ever
     * sees a local address.
     */
    val canRelay: Boolean get() = active == Owner.DLNA

    fun startDiscovery(active: Boolean) {
        cast.startDiscovery(active)
        dlna.startDiscovery(active)
    }

    fun stopDiscovery() {
        cast.stopDiscovery()
        dlna.stopDiscovery()
    }

    fun connect(deviceId: String) {
        if (deviceId.startsWith(DlnaController.DLNA_PREFIX)) dlna.connect(deviceId) else cast.connect(deviceId)
    }

    fun disconnect() {
        when (active) {
            Owner.DLNA -> dlna.disconnect()
            Owner.CAST -> cast.disconnect()
            // Nothing is playing remotely, but a half-made connection may still exist.
            null -> {
                cast.disconnect()
                dlna.disconnect()
            }
        }
    }

    fun load(
        castable: CastVerdict.Castable,
        startAtMs: Long,
        /** Whether the receiver can fetch this itself, or the phone has to fetch it for it. */
        receiverCanFetch: Boolean = true,
    ) = withOwner(
        onCast = { cast.load(castable, startAtMs) },
        onDlna = { dlna.load(castable, startAtMs, receiverCanFetch) },
    )

    fun play() = withOwner({ cast.play() }, { dlna.play() })
    fun pause() = withOwner({ cast.pause() }, { dlna.pause() })
    fun seekTo(positionMs: Long) = withOwner({ cast.seekTo(positionMs) }, { dlna.seekTo(positionMs) })
    fun seekToLiveEdge() = withOwner({ cast.seekToLiveEdge() }, { dlna.seekToLiveEdge() })
    fun setVolume(volume: Float) = withOwner({ cast.setVolume(volume) }, { dlna.setVolume(volume) })
    fun toggleMute() = withOwner({ cast.toggleMute() }, { dlna.toggleMute() })

    private inline fun withOwner(onCast: () -> Unit, onDlna: () -> Unit) {
        when (active) {
            Owner.CAST -> onCast()
            Owner.DLNA -> onDlna()
            null -> Unit
        }
    }

    fun release() {
        cast.release()
        dlna.release()
    }
}
