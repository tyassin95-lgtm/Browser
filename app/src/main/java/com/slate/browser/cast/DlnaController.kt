package com.slate.browser.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.slate.browser.cast.hls.StreamRelay
import com.slate.browser.cast.dlna.DlnaClient
import com.slate.browser.cast.dlna.MediaTypes
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
    /**
     * Commands, in the order they were asked for, on a thread of their own.
     *
     * One thread keeps a play from overtaking the pause before it, which on a renderer is the
     * difference between what the viewer asked for and the opposite. What must never share it is
     * anything that does not end: the poller used to run here, and because it loops for the
     * length of the session it held this thread for the length of the session — so every seek,
     * every pause and every volume change queued behind it and was never sent at all. That is
     * not a slow command. It is a command that does not happen.
     */
    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "slate-dlna").apply { isDaemon = true }
    }

    /** Anything that waits or loops, kept well away from the commands. */
    internal fun watcher(name: String, work: () -> Unit) {
        Thread({ runCatching { work() } }, name).apply { isDaemon = true }.start()
    }

    private var renderers: List<UpnpRenderer> = emptyList()
    private var connected: UpnpRenderer? = null
    private var loaded: CastVerdict.Castable? = null

    /** The phone's own stream server, used when the renderer cannot fetch what the page plays. */
    private val relay = StreamRelay()

    /** True while the television is being fed by this phone rather than fetching for itself. */
    @Volatile private var relaying = false

    /** What the relayed stream is, so a seek hands the renderer the same thing again. */
    @Volatile private var relaySource: CastSource? = null

    /**
     * What the connected renderer said it can play.
     *
     * Read once, on connecting, and used to name every stream afterwards. Empty when the device
     * would not say, which is treated as "let it decide" rather than as "it accepts nothing".
     */
    @Volatile private var accepts: Set<String> = emptySet()

    /**
     * The names already offered to a renderer that would not publish its list.
     *
     * With no list to read there is nothing to reason from, so the container's names are offered
     * in turn and the device answers with its behaviour. Bounded, remembered, and ending in a
     * refusal rather than another attempt.
     */
    private val namesTried = mutableSetOf<String>()

    /** Where the relayed stream was started from, since the renderer counts from zero. */
    @Volatile private var baseOffsetMs = 0L

    /** The duration the playlist declared, which a relayed stream cannot report for itself. */
    @Volatile private var knownDurationMs = 0L

    /**
     * Where the viewer has just asked to be, and until when to believe them over the renderer.
     *
     * A renderer reports the position it is playing, and for a second or two after a seek that
     * is still the old one — which is exactly what "the bar jumps back the moment I let go"
     * looks like. The asked-for position is shown until the renderer catches up to it or gives
     * up trying; it is reconciliation, not a delay, and the renderer always wins in the end.
     */
    @Volatile private var seekTargetMs = -1L

    @Volatile private var seekDeadline = 0L

    /**
     * When a stop reported by the renderer is the browser's own doing.
     *
     * Handing a renderer a new address stops it before it starts again. Without this the poll
     * would read the browser's own seek as somebody switching the television off.
     */
    @Volatile private var ignoreStopsUntil = 0L

    /** When the renderer was last handed an address, so a stale watch does not fire on a new one. */
    @Volatile private var handedOverAt = 0L

    private val userAgent: String =
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")
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
        // Searching runs for as long as the picker is open, which is exactly the shape that must
        // not be on the command thread: choosing a device is a command, and it would have been
        // queued behind the search it ended.
        watcher("slate-dlna-search") {
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
        namesTried.clear()
        accepts = emptySet()
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
            // While we have its attention: what does it actually play? Guessing at this is what
            // produces "File format not supported" on a set that would have played the same
            // bytes under a name it recognises.
            accepts = renderer.connectionManagerUrl
                ?.let {
                    client.invoke(it, DlnaClient.CONNECTION_MANAGER, "GetProtocolInfo", "")
                }
                ?.let { UpnpParsing.sinkTypes(it) }
                .orEmpty()

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
        stopRelay()
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

    fun load(castable: CastVerdict.Castable, startAtMs: Long, receiverCanFetch: Boolean = true) {
        val renderer = connected ?: return
        loaded = castable
        update { it.copy(stage = CastStage.LOADING, message = "", isLive = castable.isLive) }
        background.execute {
            // A DLNA renderer is a file player, and there are two reasons it cannot simply be
            // given the address the page is using. An adaptive stream is a playlist, which it
            // has no way of assembling and never will. And a great many hosts serve their video
            // only to the page that embeds it, so a television asking for the same address is
            // refused. The phone answers both by fetching the stream itself and serving it.
            val direct = castable.forFilePlayer
            val mustRelay = direct.format != StreamFormat.PROGRESSIVE || !receiverCanFetch
            val relayed = if (mustRelay) relayFor(castable, direct) else null
            if (relayed == null && mustRelay) {
                update {
                    it.copy(stage = CastStage.FAILED, message = relayFailure(renderer.name, direct))
                }
                return@execute
            }
            val source = relayed?.let {
                CastSource(it.url, StreamFormat.PROGRESSIVE, it.contentType)
            } ?: direct
            // Only a reassembled stream needs seeking done by restarting it. A file forwarded
            // byte for byte keeps its ranges, so the renderer seeks in it exactly as it would
            // have if it had fetched the file itself.
            relaying = relayed != null && !relayed.seekableByReceiver
            relaySource = source
            knownDurationMs = relayed?.durationMs ?: 0
            hand(renderer, castable, source, startAtMs)
        }
    }

    /**
     * Puts the stream through the phone, and returns the local address to hand the television.
     *
     * The fetching is done as the page: its referrer, its cookies, its user agent. That is not a
     * trick, it is the point — the site is answering the client it already trusts, and the
     * television is given a local address with no credentials anywhere in it.
     */
    private fun relayFor(
        castable: CastVerdict.Castable,
        direct: CastSource,
    ): StreamRelay.Published? {
        // A playlist is relayed as pieces; a file the television is not allowed to fetch is
        // relayed as a file. A DASH manifest is neither: its picture and its sound are separate
        // streams that would have to be muxed, which is a video pipeline rather than a relay.
        val stream = when {
            direct.format == StreamFormat.PROGRESSIVE -> direct
            castable.forAdaptiveReceiver.format == StreamFormat.HLS -> castable.forAdaptiveReceiver
            else -> return null
        }
        relay.stop()
        return relay.publish(
            StreamRelay.Source(
                playlistUrl = stream.url,
                // The browser's own referrer for this stream. A host that serves only its
                // embedded player is answered by the same thing it answered before.
                referer = castable.referer.ifBlank { castable.pageUrl },
                cookies = runCatching { CookieManager.getInstance().getCookie(stream.url) }
                    .getOrNull()
                    .orEmpty(),
                userAgent = userAgent,
            ),
        )
    }

    /** Hands the renderer an address and starts it, with the position asked for built in. */
    private fun hand(
        renderer: UpnpRenderer,
        castable: CastVerdict.Castable,
        source: CastSource,
        startAtMs: Long,
    ) {
        // A relayed stream carries no position of its own: the phone starts feeding it from
        // wherever it was asked to, and the renderer counts from zero. Remembering the offset
        // here is what keeps the progress bar honest.
        val startAt = if (castable.isLive) 0 else startAtMs.coerceAtLeast(0)
        val url = if (relaying) relay.urlFor(startAt / 1000) else source.url
        baseOffsetMs = if (relaying) startAt else 0
        // The renderer is about to stop and start, because that is what being given a new
        // address means. That is not the television being switched off.
        ignoreStopsUntil = System.currentTimeMillis() + HANDOVER_SETTLE_MS
        handedOverAt = System.currentTimeMillis()

        // The name the receiver knows this container by. A television that lists video/mpeg and
        // not video/mp2t plays the identical bytes under the first name and refuses them under
        // the second, and no amount of trying again changes that.
        val named = if (accepts.isEmpty()) {
            MediaTypes.namesFor(source.contentType).firstOrNull { it !in namesTried }
        } else {
            MediaTypes.nameFor(source.contentType, accepts)
        }
        if (named == null) {
            update { it.copy(stage = CastStage.FAILED, message = unsupportedMessage(renderer.name)) }
            return
        }
        namesTried += named
        relay.contentTypeOverride = if (relaying || relaySource != null) named else null

        val metadata = UpnpParsing.didl(
            title = castable.title,
            url = url,
            contentType = named,
            isLive = castable.isLive || relaying,
        )
        val set = client.call(
            renderer.avTransportUrl,
            DlnaClient.AV_TRANSPORT,
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${UpnpParsing.escape(url)}</CurrentURI>" +
                "<CurrentURIMetaData>${UpnpParsing.escape(metadata)}</CurrentURIMetaData>",
        )
        if (set !is SoapResult.Ok) {
            update {
                it.copy(stage = CastStage.FAILED, message = refusalMessage(renderer.name, source, set))
            }
            return
        }
        client.invoke(
            renderer.avTransportUrl,
            DlnaClient.AV_TRANSPORT,
            "Play",
            "<InstanceID>0</InstanceID><Speed>1</Speed>",
        )
        // A file the renderer fetches itself can be asked to start partway in. A relayed stream
        // already starts where it was told to, and asking again would only confuse it.
        if (!relaying && !castable.isLive && startAt > 1_000) {
            seekOnRenderer(renderer, startAt)
        }
        update {
            it.copy(
                stage = CastStage.PLAYING,
                positionMs = baseOffsetMs,
                durationMs = if (knownDurationMs > 0) knownDurationMs else it.durationMs,
            )
        }
        startPolling()
        watchForSilentRefusal(renderer, relaySource != null)
    }

    /**
     * Offers the same stream under the next name the container goes by.
     *
     * Only for a device that published no list, only for a stream this phone is serving, and
     * only for as many names as the container actually has. It is how a set that says nothing is
     * given a fair hearing without the browser pretending to know something it does not.
     */
    private fun retryUnderAnotherName(): Boolean {
        if (accepts.isNotEmpty() || !relay.wasFetched) return false
        val renderer = connected ?: return false
        val castable = loaded ?: return false
        val source = relaySource ?: return false
        val untried = MediaTypes.namesFor(source.contentType).any { it !in namesTried }
        if (!untried) return false
        background.execute { hand(renderer, castable, source, state.positionMs) }
        return true
    }

    /**
     * A television that accepted the address and then showed an error of its own.
     *
     * There is no protocol for this: `SetAVTransportURI` succeeds, `Play` succeeds, and the set
     * puts "File format not supported" on the screen while reporting STOPPED as though nothing
     * had happened. What separates the two possible causes is whether it ever came and asked the
     * phone for the stream — a set that fetched and then stopped could not decode it, and a set
     * that never fetched could not reach the phone at all. Both are worth saying out loud, and
     * neither is "check your network connection".
     */
    private fun watchForSilentRefusal(renderer: UpnpRenderer, throughRelay: Boolean) {
        if (!throughRelay) return
        val startedAt = System.currentTimeMillis()
        watcher("slate-dlna-watch") {
            Thread.sleep(REFUSAL_WINDOW_MS)
            // A newer hand-over has happened since; this watch is about something that is no
            // longer on screen.
            if (connected?.udn != renderer.udn || handedOverAt > startedAt) return@watcher
            if (relay.wasFetched) return@watcher
            update {
                CastState(
                    stage = CastStage.FAILED,
                    devices = it.devices,
                    message = "${renderer.name} never fetched the stream from this phone. " +
                        "They may be on different networks — check they are both on the same " +
                        "Wi-Fi, and that it is not a guest network.",
                )
            }
            stopRelay()
        }
    }

    /** Its own vocabulary, so the sentence is about this television rather than televisions. */
    internal fun unsupportedMessage(deviceName: String): String {
        val plays = MediaTypes.describe(accepts)
        return if (plays.isBlank()) {
            "$deviceName won't play this video. Screen mirroring will show it."
        } else {
            "$deviceName only plays $plays, and this isn't one of them. " +
                "Screen mirroring will show it."
        }
    }

    /**
     * Why the phone could not put the stream back together, which is a different failure from
     * the television refusing something.
     */
    internal fun relayFailure(deviceName: String, source: CastSource): String = when (source.format) {
        StreamFormat.PROGRESSIVE ->
            "The site wouldn't hand this video over for $deviceName. Screen mirroring will show it."
        StreamFormat.HLS ->
            "The browser couldn't rebuild this stream for $deviceName. Screen mirroring will show it."
        else ->
            "$deviceName can't play this kind of stream, and the browser can't convert it. " +
                "Screen mirroring will show it."
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

    /**
     * Moves the television, which for a relayed stream means starting a new one.
     *
     * A renderer can only seek in something it fetched itself and can ask for byte ranges of. A
     * stream the phone is feeding it has no length and no ranges — so seeking is done by handing
     * it the same stream again from a different offset, which is an operation every renderer
     * supports because it is just another address. That it is a restart rather than a seek is
     * invisible: it lands at the second the viewer asked for.
     */
    fun seekTo(positionMs: Long) {
        val renderer = connected ?: return
        val target = positionMs.coerceAtLeast(0)
        val castable = loaded
        seekTargetMs = target
        seekDeadline = System.currentTimeMillis() + SEEK_SETTLE_MS
        update { it.copy(positionMs = target) }
        background.execute {
            val relayed = relaySource
            if (relaying && castable != null && relayed != null) {
                // The same stream, started somewhere else — described to the renderer exactly as
                // it was the first time, because it is the same thing.
                hand(renderer, castable, relayed, target)
                return@execute
            }
            seekOnRenderer(renderer, target)
        }
    }

    /**
     * Asks a renderer to move, in both of the ways the specification allows.
     *
     * `REL_TIME` is the usual one and plenty of televisions refuse it while accepting
     * `ABS_TIME`; there is no way to know which without asking. A refusal from both is reported
     * rather than swallowed, because the alternative — the progress bar snapping back a second
     * later when the poll returns the position the television never left — is the browser
     * pretending it did something it did not.
     */
    /** A renderer has no idea of a live edge: the stream it is being fed is already at one. */
    fun seekToLiveEdge() = Unit

    private fun seekOnRenderer(renderer: UpnpRenderer, positionMs: Long) {
        val clock = UpnpParsing.formatClock(positionMs)
        val relative = client.call(
            renderer.avTransportUrl,
            DlnaClient.AV_TRANSPORT,
            "Seek",
            "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$clock</Target>",
        )
        if (relative is SoapResult.Ok) return
        val absolute = client.call(
            renderer.avTransportUrl,
            DlnaClient.AV_TRANSPORT,
            "Seek",
            "<InstanceID>0</InstanceID><Unit>ABS_TIME</Unit><Target>$clock</Target>",
        )
        if (absolute is SoapResult.Ok) return
        update { it.copy(message = "${renderer.name} won't let this stream be moved.") }
    }

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
        watcher("slate-dlna-poll") {
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
                        return@watcher
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
                val ourDoing = System.currentTimeMillis() < ignoreStopsUntil
                stops = if (stopped && everStarted && !ourDoing) stops + 1 else 0
                if (stops >= MAX_STOPS) {
                    // A renderer that fetched the stream and then stopped it did not like what
                    // it got. When it never told us what it does like, there is another name to
                    // try before giving up — and when it did, its list has already been obeyed
                    // and a stop is a stop.
                    if (retryUnderAnotherName()) {
                        stops = 0
                        everStarted = false
                        runCatching { Thread.sleep(POLL_INTERVAL_MS) }
                        continue
                    }
                    stoppedOnDevice()
                    return@watcher
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
                    // A relayed stream is fed from an offset and the renderer counts from zero,
                    // so its clock is added to where the phone started rather than taken as the
                    // position in the film. The duration comes from the playlist, which is the
                    // only place it exists at all.
                    val reported = baseOffsetMs + at
                    // Until the renderer reaches where it was sent, what the viewer asked for is
                    // the truer answer; after that the renderer is, always.
                    val settled = seekTargetMs < 0 ||
                        System.currentTimeMillis() > seekDeadline ||
                        reported >= seekTargetMs - SEEK_TOLERANCE_MS
                    if (settled) seekTargetMs = -1
                    update {
                        it.copy(
                            positionMs = if (settled) reported else seekTargetMs,
                            durationMs = if (knownDurationMs > 0) knownDurationMs else duration,
                        )
                    }
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
        stopRelay()
        update { CastState(stage = CastStage.IDLE, devices = it.devices, positionMs = position) }
    }

    private fun connectionLost(name: String) {
        val position = state.positionMs
        polling = false
        connected = null
        loaded = null
        stopRelay()
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

    /**
     * The phone stops being a server the moment it stops being a source.
     *
     * The relay exists for the length of one cast session and not a second longer: no listening
     * socket outlives the thing it was opened for.
     */
    private fun stopRelay() {
        relaying = false
        relaySource = null
        namesTried.clear()
        baseOffsetMs = 0
        knownDurationMs = 0
        runCatching { relay.stop() }
    }

    fun release() {
        polling = false
        connected = null
        stopRelay()
    }

    companion object {
        const val DLNA_PREFIX = "dlna:"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val MAX_SILENCES = 3
        private const val SEEK_SETTLE_MS = 6_000L
        private const val SEEK_TOLERANCE_MS = 2_000L
        private const val HANDOVER_SETTLE_MS = 8_000L
        private const val REFUSAL_WINDOW_MS = 6_000L
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
