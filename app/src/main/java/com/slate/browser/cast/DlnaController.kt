package com.slate.browser.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.slate.browser.cast.dlna.DlnaClient
import com.slate.browser.cast.dlna.SoapResult
import com.slate.browser.cast.dlna.UpnpParsing
import com.slate.browser.cast.dlna.UpnpRenderer
import java.util.concurrent.Executors

/**
 * Casting to UPnP AV renderers — televisions, receivers and speakers that speak DLNA.
 *
 * This exists because Google Cast, for all that it is the better protocol, is a Google product:
 * Samsung's televisions and Amazon's Fire TV both leave it out deliberately, and between them
 * that is a great many living rooms. DLNA is the published standard those devices do speak, and
 * it does what is needed here — hand a receiver an address and control what it does with it.
 *
 * Kept behind the same shape as [CastController] so the transport and the picker never learn
 * which protocol is carrying a session.
 */
class DlnaController(context: Context) {

    private val client = DlnaClient(context)
    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "slate-dlna").apply { isDaemon = true }
    }

    private var renderers: List<UpnpRenderer> = emptyList()
    private var connected: UpnpRenderer? = null
    private var loaded: CastVerdict.Castable? = null
    private var discovering = false
    private var wantActive = false
    private var polling = false

    var state: CastState = CastState(stage = CastStage.IDLE)
        private set

    var onStateChanged: (CastState) -> Unit = {}
    var onHandBack: (positionMs: Long) -> Unit = {}

    // ---- Discovery ----------------------------------------------------------

    /**
     * Searches the network. SSDP has no completion — only a window devices answer in — so this
     * runs a whole search each time rather than pretending to be a live subscription.
     */
    fun startDiscovery(active: Boolean) {
        // The quiet mode really is quiet: a multicast search is a burst of traffic on the
        // network, and running one when nothing is looking at the result would spend battery
        // for nobody. DLNA therefore only searches while the picker is open.
        wantActive = active
        if (!active || discovering) return
        discovering = true
        background.execute {
            // Searched repeatedly while the picker is up: SSDP has no completion, devices
            // answer late, and a television that was asleep when the first search went out
            // will answer the second.
            while (wantActive) {
                val found = runCatching { client.discover() }.getOrDefault(emptyList())
                // Devices already seen are kept: a renderer that misses one search round has
                // not gone away, and a list that flickers is unusable.
                renderers = (found + renderers).distinctBy { it.udn }
                val devices = renderers.map {
                    CastDevice(
                        id = DLNA_PREFIX + it.udn,
                        name = it.name,
                        isSelected = it.udn == connected?.udn,
                    )
                }
                update { it.copy(devices = devices) }
            }
            discovering = false
        }
    }

    fun stopDiscovery() {
        wantActive = false
    }

    // ---- Session ------------------------------------------------------------

    fun connect(deviceId: String) {
        val renderer = renderers.firstOrNull { DLNA_PREFIX + it.udn == deviceId } ?: run {
            update { it.copy(stage = CastStage.FAILED, message = "That device is no longer nearby.") }
            return
        }
        connected = renderer
        update { it.copy(stage = CastStage.CONNECTED, deviceName = renderer.name, message = "") }
    }

    fun disconnect(): Long {
        val position = state.positionMs
        val renderer = connected
        connected = null
        loaded = null
        polling = false
        if (renderer != null) {
            background.execute {
                runCatching {
                    client.invoke(
                        renderer.avTransportUrl,
                        DlnaClient.AV_TRANSPORT,
                        "Stop",
                        "<InstanceID>0</InstanceID>",
                    )
                }
            }
        }
        update { CastState(stage = CastStage.IDLE, devices = it.devices) }
        if (position > 0) main.post { onHandBack(position) }
        return position
    }

    // ---- Media --------------------------------------------------------------

    fun load(castable: CastVerdict.Castable, startAtMs: Long) {
        val renderer = connected ?: return
        loaded = castable
        // A DLNA renderer is a file player, not a streaming client: it is given the plain file
        // if the page gave the browser one, and only falls back to a manifest when that is the
        // only address in existence.
        val source = castable.forFilePlayer
        update { it.copy(stage = CastStage.LOADING, message = "", isLive = castable.isLive) }
        background.execute {
            val metadata = UpnpParsing.didl(
                title = castable.title,
                url = source.url,
                contentType = source.contentType,
                isLive = castable.isLive,
            )
            val set = client.call(
                renderer.avTransportUrl,
                DlnaClient.AV_TRANSPORT,
                "SetAVTransportURI",
                "<InstanceID>0</InstanceID>" +
                    "<CurrentURI>${UpnpParsing.escape(source.url)}</CurrentURI>" +
                    "<CurrentURIMetaData>${UpnpParsing.escape(metadata)}</CurrentURIMetaData>",
            )
            if (set !is SoapResult.Ok) {
                update {
                    it.copy(
                        stage = CastStage.FAILED,
                        message = refusalMessage(renderer.name, source, set),
                    )
                }
                return@execute
            }
            client.invoke(
                renderer.avTransportUrl,
                DlnaClient.AV_TRANSPORT,
                "Play",
                "<InstanceID>0</InstanceID><Speed>1</Speed>",
            )
            // Renderers vary on whether they honour a start position at all, so it is asked for
            // after playback begins and its refusal is not treated as a failure.
            if (!castable.isLive && startAtMs > 1_000) {
                client.invoke(
                    renderer.avTransportUrl,
                    DlnaClient.AV_TRANSPORT,
                    "Seek",
                    "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
                        "<Target>${UpnpParsing.formatClock(startAtMs)}</Target>",
                )
            }
            update { it.copy(stage = CastStage.PLAYING) }
            startPolling()
        }
    }

    /**
     * Why the television said no, in words that suggest what to do instead.
     *
     * The common case is not a bug and never will be: a DLNA renderer was designed to play
     * files off a NAS, and most televisions reject an adaptive manifest outright — which is
     * what almost every video site serves. Saying that, and pointing at the mirroring that does
     * work, is worth more than a number nobody can act on. The number is still included, because
     * when it is one of the unusual ones it is the only thing that identifies the problem.
     */
    internal fun refusalMessage(deviceName: String, source: CastSource, result: SoapResult): String {
        if (result is SoapResult.Unreachable) {
            return "$deviceName stopped answering."
        }
        val fault = (result as? SoapResult.Refused)?.fault
        if (source.format != StreamFormat.PROGRESSIVE) {
            return "$deviceName can't play this kind of stream — DLNA televisions play video " +
                "files, not the adaptive streams most sites use. Screen mirroring will show it."
        }
        return when (fault?.code) {
            ILLEGAL_MIME -> "$deviceName doesn't support this video's format."
            RESOURCE_NOT_FOUND -> "$deviceName couldn't fetch this video."
            TRANSITION_NOT_AVAILABLE -> "$deviceName is busy with something else."
            null -> "$deviceName wouldn't accept this video."
            else -> "$deviceName wouldn't accept this video (error ${fault.code})."
        }
    }

    fun play() = transport("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>", CastStage.PLAYING)

    fun pause() = transport("Pause", "<InstanceID>0</InstanceID>", CastStage.PAUSED)

    fun stopRemote() = transport("Stop", "<InstanceID>0</InstanceID>", CastStage.CONNECTED)

    fun seekTo(positionMs: Long) {
        val renderer = connected ?: return
        update { it.copy(positionMs = positionMs.coerceAtLeast(0)) }
        background.execute {
            client.invoke(
                renderer.avTransportUrl,
                DlnaClient.AV_TRANSPORT,
                "Seek",
                "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
                    "<Target>${UpnpParsing.formatClock(positionMs)}</Target>",
            )
        }
    }

    fun seekToLiveEdge() = Unit

    /** Renderer volume is a whole number out of a hundred, not a fraction. */
    fun setVolume(volume: Float) {
        val renderer = connected ?: return
        val control = renderer.renderingControlUrl ?: return
        val level = (volume.coerceIn(0f, 1f) * 100).toInt()
        update { it.copy(volume = volume.coerceIn(0f, 1f)) }
        background.execute {
            client.invoke(
                control,
                DlnaClient.RENDERING_CONTROL,
                "SetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
                    "<DesiredVolume>$level</DesiredVolume>",
            )
        }
    }

    fun toggleMute() {
        val renderer = connected ?: return
        val control = renderer.renderingControlUrl ?: return
        val muted = !state.isMuted
        update { it.copy(isMuted = muted) }
        background.execute {
            client.invoke(
                control,
                DlnaClient.RENDERING_CONTROL,
                "SetMute",
                "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
                    "<DesiredMute>${if (muted) 1 else 0}</DesiredMute>",
            )
        }
    }

    private fun transport(action: String, arguments: String, stage: CastStage) {
        val renderer = connected ?: return
        update { it.copy(stage = stage) }
        background.execute {
            client.invoke(renderer.avTransportUrl, DlnaClient.AV_TRANSPORT, action, arguments)
        }
    }

    /**
     * Asks the renderer where it has got to.
     *
     * UPnP has an eventing mechanism for this, which would mean running an HTTP server inside
     * the browser for the renderer to call back into. Polling once a second is a great deal
     * less machinery, and a second's granularity is what a progress bar shows anyway.
     */
    private fun startPolling() {
        if (polling) return
        polling = true
        background.execute {
            while (polling && connected != null) {
                val renderer = connected ?: break
                val reply = client.invoke(
                    renderer.avTransportUrl,
                    DlnaClient.AV_TRANSPORT,
                    "GetPositionInfo",
                    "<InstanceID>0</InstanceID>",
                )
                if (reply != null) {
                    val position = UpnpParsing.parseClock(UpnpParsing.soapValue(reply, "RelTime"))
                    val duration = UpnpParsing.parseClock(UpnpParsing.soapValue(reply, "TrackDuration"))
                    update { it.copy(positionMs = position, durationMs = duration) }
                }
                runCatching { Thread.sleep(POLL_INTERVAL_MS) }
            }
        }
    }

    private fun update(change: (CastState) -> CastState) {
        val next = change(state)
        if (next == state) return
        state = next
        main.post { onStateChanged(next) }
    }

    fun release() {
        polling = false
        connected = null
    }

    companion object {
        const val DLNA_PREFIX = "dlna:"
        private const val POLL_INTERVAL_MS = 1_000L

        // UPnP AV error codes, as the AVTransport service defines them.
        private const val TRANSITION_NOT_AVAILABLE = 701
        private const val ILLEGAL_MIME = 714
        private const val RESOURCE_NOT_FOUND = 716
    }
}
