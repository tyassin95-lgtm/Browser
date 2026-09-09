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

    /** The newest volume asked for while an earlier one is still on the wire. */
    @Volatile private var pendingVolume: Float? = null
    @Volatile private var volumeInFlight = false

    var state: CastState = CastState(stage = CastStage.IDLE)
        private set

    var onStateChanged: (CastState) -> Unit = {}

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

    /**
     * Connects, which for UPnP means proving the renderer is really there.
     *
     * There is no session to establish — the protocol is stateless — so the temptation is to
     * declare success the moment a device is picked. That is exactly how a browser ends up
     * showing a connection to a television that was switched off ten minutes ago. Instead the
     * renderer is asked a harmless question, and only an answer counts as connected.
     */
    fun connect(deviceId: String) {
        val renderer = renderers.firstOrNull { DLNA_PREFIX + it.udn == deviceId } ?: run {
            update { it.copy(stage = CastStage.FAILED, message = "That device is no longer nearby.") }
            return
        }
        update { it.copy(stage = CastStage.CONNECTING, deviceName = renderer.name, message = "") }
        background.execute {
            val reply = client.call(
                renderer.avTransportUrl,
                DlnaClient.AV_TRANSPORT,
                "GetTransportInfo",
                "<InstanceID>0</InstanceID>",
            )
            // A refusal still proves the device is there and listening; only silence does not.
            if (reply is SoapResult.Unreachable) {
                connected = null
                update {
                    CastState(
                        stage = CastStage.FAILED,
                        devices = it.devices,
                        message = "${renderer.name} didn't answer.",
                    )
                }
                return@execute
            }
            connected = renderer
            update { it.copy(stage = CastStage.CONNECTED, deviceName = renderer.name, message = "") }
        }
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
        update { CastState(stage = CastStage.IDLE, devices = it.devices, positionMs = position) }
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
    /**
     * Receiver volume, with only the newest value sent.
     *
     * A volume slider produces a value per frame, and each one here is a SOAP round trip to a
     * television. Sending all of them queues the renderer solid and it stops answering anything
     * else; sending the latest when the previous one has been answered keeps the slider live
     * and the renderer responsive. The UI is updated immediately either way, so the control
     * never feels like it is lagging behind the finger.
     */
    fun setVolume(volume: Float) {
        val renderer = connected ?: return
        val control = renderer.renderingControlUrl ?: return
        val level = volume.coerceIn(0f, 1f)
        update { it.copy(volume = level) }
        pendingVolume = level
        if (volumeInFlight) return
        volumeInFlight = true
        background.execute {
            while (true) {
                val next = pendingVolume ?: break
                pendingVolume = null
                client.invoke(
                    control,
                    DlnaClient.RENDERING_CONTROL,
                    "SetVolume",
                    "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
                        "<DesiredVolume>${(next * 100).toInt()}</DesiredVolume>",
                )
            }
            volumeInFlight = false
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
     * Asks the renderer what it is doing, once a second, and believes the answer.
     *
     * UPnP has an eventing mechanism, which would mean running an HTTP server inside the
     * browser for the renderer to call back into. Polling is a great deal less machinery, and a
     * second's granularity is what a progress bar shows anyway.
     *
     * What is polled matters more than how often. Position alone cannot tell the browser that
     * somebody stopped the video with the television's own remote, or that the set was switched
     * off — and a browser that goes on showing a cast session in either case is lying about the
     * room. So the transport state is read as well, and both a stop and a silence end the
     * session rather than being smoothed over.
     */
    private fun startPolling() {
        if (polling) return
        polling = true
        background.execute {
            var silences = 0
            var everStarted = false
            var stops = 0
            while (polling && connected != null) {
                val renderer = connected ?: break

                val transport = client.call(
                    renderer.avTransportUrl,
                    DlnaClient.AV_TRANSPORT,
                    "GetTransportInfo",
                    "<InstanceID>0</InstanceID>",
                )
                if (transport is SoapResult.Unreachable) {
                    silences++
                    // A television is allowed to miss an answer; it is not allowed to miss
                    // several. Three seconds of silence is a set that has been switched off or
                    // a network that has gone, and either way there is no session left.
                    if (silences >= MAX_SILENCES) {
                        connectionLost(renderer.name)
                        return@execute
                    }
                    runCatching { Thread.sleep(POLL_INTERVAL_MS) }
                    continue
                }
                silences = 0

                val reported = (transport as? SoapResult.Ok)
                    ?.let { UpnpParsing.soapValue(it.body, "CurrentTransportState") }
                    ?.trim()
                    .orEmpty()
                if (reported == PLAYING || reported == PAUSED) everStarted = true
                val stopped = reported == STOPPED || reported == NO_MEDIA
                // A renderer reads as stopped in the moment between being handed a URL and
                // starting it, so a stop only counts once it has been seen playing, and then
                // only when it persists.
                stops = if (stopped && everStarted) stops + 1 else 0
                if (stops >= MAX_STOPS) {
                    stoppedOnDevice()
                    return@execute
                }
                stageFor(reported)?.let { stage -> update { it.copy(stage = stage) } }

                val position = client.invoke(
                    renderer.avTransportUrl,
                    DlnaClient.AV_TRANSPORT,
                    "GetPositionInfo",
                    "<InstanceID>0</InstanceID>",
                )
                if (position != null) {
                    val at = UpnpParsing.parseClock(UpnpParsing.soapValue(position, "RelTime"))
                    val duration = UpnpParsing.parseClock(UpnpParsing.soapValue(position, "TrackDuration"))
                    update { it.copy(positionMs = at, durationMs = duration) }
                }
                runCatching { Thread.sleep(POLL_INTERVAL_MS) }
            }
        }
    }

    /** The renderer's own vocabulary, which is fixed by the specification. */
    internal fun stageFor(transportState: String): CastStage? = when (transportState) {
        PLAYING -> CastStage.PLAYING
        PAUSED -> CastStage.PAUSED
        TRANSITIONING -> CastStage.BUFFERING
        else -> null
    }

    /**
     * Somebody stopped it on the television. That is a decision, and the browser honours it by
     * ending the session and giving the phone its playback back.
     */
    private fun stoppedOnDevice() {
        val position = state.positionMs
        polling = false
        connected = null
        loaded = null
        update { CastState(stage = CastStage.IDLE, devices = it.devices, positionMs = position) }
    }

    private fun connectionLost(name: String) {
        val position = state.positionMs
        polling = false
        connected = null
        loaded = null
        update {
            CastState(
                stage = CastStage.FAILED,
                devices = it.devices,
                message = "Lost the connection to $name.",
                positionMs = position,
            )
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
        private const val MAX_SILENCES = 3
        private const val MAX_STOPS = 2

        // AVTransport's own words for what a renderer is doing.
        private const val PLAYING = "PLAYING"
        private const val PAUSED = "PAUSED_PLAYBACK"
        private const val TRANSITIONING = "TRANSITIONING"
        private const val STOPPED = "STOPPED"
        private const val NO_MEDIA = "NO_MEDIA_PRESENT"

        // UPnP AV error codes, as the AVTransport service defines them.
        private const val TRANSITION_NOT_AVAILABLE = 701
        private const val ILLEGAL_MIME = 714
        private const val RESOURCE_NOT_FOUND = 716
    }
}
