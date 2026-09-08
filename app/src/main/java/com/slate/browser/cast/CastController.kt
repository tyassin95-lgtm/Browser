package com.slate.browser.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import java.util.concurrent.Executors

/** A receiver the browser can see, as the picker needs to show it. */
data class CastDevice(val id: String, val name: String, val isSelected: Boolean)

enum class CastStage { UNAVAILABLE, IDLE, CONNECTING, CONNECTED, LOADING, PLAYING, PAUSED, FAILED }

/** Everything the UI needs to know about casting, in one value. */
data class CastState(
    val stage: CastStage = CastStage.UNAVAILABLE,
    val devices: List<CastDevice> = emptyList(),
    val deviceName: String = "",
    val message: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = 1f,
    val isMuted: Boolean = false,
    val isLive: Boolean = false,
) {
    /** A session exists, whatever it is currently doing. */
    val isActive: Boolean
        get() = stage == CastStage.CONNECTING || stage == CastStage.CONNECTED ||
            stage == CastStage.LOADING || stage == CastStage.PLAYING || stage == CastStage.PAUSED

    /** Media is loaded on the receiver and the transport should drive it rather than the page. */
    val isPlayingRemotely: Boolean
        get() = stage == CastStage.PLAYING || stage == CastStage.PAUSED || stage == CastStage.LOADING

    val isPlaying: Boolean get() = stage == CastStage.PLAYING
    val canOffer: Boolean get() = stage != CastStage.UNAVAILABLE && devices.isNotEmpty()
}

/**
 * Google Cast, and the browser's side of a session.
 *
 * Cast is the only casting stack on Android with first-party discovery, a maintained library
 * and a receiver on enough hardware to be worth the name — Chromecast, Android TV, Google TV
 * and the televisions and speakers with it built in. It is also the one Chrome itself uses on
 * this platform. DLNA would mean an unmaintained third-party stack and hand-rolled SSDP;
 * AirPlay is not open to Android apps at all; screen mirroring is a system feature that sends
 * the whole phone rather than the media, and the system already offers it.
 *
 * Everything here is guarded. Play Services can be missing, out of date or disabled, and on
 * those devices the browser simply never offers to cast rather than failing at the moment
 * somebody taps the button.
 */
class CastController(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "slate-cast").apply { isDaemon = true }
    }

    private var castContext: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var session: CastSession? = null
    private var discovering = false
    private var activeScan = false

    /** What was loaded, so a reconnect or a hand-back knows what it was playing. */
    private var loaded: CastVerdict.Castable? = null

    var state: CastState = CastState()
        private set

    /** Called on the main thread whenever [state] changes. */
    var onStateChanged: (CastState) -> Unit = {}

    /**
     * Called when a session ends with the position the receiver had reached, so playback can be
     * picked up on the phone where the television left it.
     */
    var onHandBack: (positionMs: Long) -> Unit = {}

    // ---- Availability -------------------------------------------------------

    /**
     * Brings the framework up, off the main thread.
     *
     * `getSharedInstance` initialises Play Services and can be slow or throw outright; the
     * Task form keeps both off the launch path. Until it succeeds the browser behaves exactly
     * as it did before casting existed.
     */
    fun initialise() {
        if (castContext != null || initialising) return
        initialising = true
        runCatching {
            CastContext.getSharedInstance(appContext, background)
                .addOnSuccessListener { context -> main.post { onFrameworkReady(context) } }
                .addOnFailureListener { main.post { initialising = false } }
        }.onFailure { initialising = false }
    }

    private var initialising = false

    private fun onFrameworkReady(context: CastContext) {
        initialising = false
        castContext = context
        selector = runCatching { context.mergedSelector }.getOrNull()
        router = runCatching { MediaRouter.getInstance(appContext) }.getOrNull()
        runCatching {
            context.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
            context.sessionManager.currentCastSession?.let { adopt(it) }
        }
        update { it.copy(stage = if (it.isActive) it.stage else CastStage.IDLE) }
        refreshDevices()
    }

    // ---- Discovery ----------------------------------------------------------

    /**
     * Looks for receivers, at one of two intensities.
     *
     * Discovery has to be running before the cast button can honestly appear — a button that
     * offers to find something is worse than no button — but an active scan keeps the Wi-Fi
     * radio busy and is only worth it while a list of devices is actually on screen. So the
     * browser watches quietly whenever there is media that could be sent, and scans hard only
     * while the picker is open.
     */
    fun startDiscovery(active: Boolean) {
        val router = this.router ?: return
        val selector = this.selector ?: return
        if (discovering && activeScan == active) return
        discovering = true
        activeScan = active
        runCatching {
            // Re-adding replaces the previous registration's flags rather than stacking.
            router.addCallback(
                selector,
                routerCallback,
                if (active) {
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or
                        MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
                } else {
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
                },
            )
        }
        refreshDevices()
    }

    /** Stops looking entirely. An established session is unaffected. */
    fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        activeScan = false
        runCatching { router?.removeCallback(routerCallback) }
    }

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) =
            refreshDevices()

        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) =
            refreshDevices()
    }

    private fun refreshDevices() {
        val router = this.router
        val selector = this.selector
        if (router == null || selector == null) return
        val devices = runCatching {
            router.routes
                .filter { it.matchesSelector(selector) && !it.isDefault && !it.isBluetooth }
                .map { CastDevice(id = it.id, name = it.name, isSelected = it.isSelected) }
                .sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
        update { it.copy(devices = devices) }
    }

    // ---- Session ------------------------------------------------------------

    fun connect(deviceId: String) {
        val router = this.router ?: return
        val route = runCatching { router.routes.firstOrNull { it.id == deviceId } }.getOrNull()
        if (route == null) {
            // The receiver went away between being listed and being chosen, which on a busy
            // network is ordinary rather than exceptional.
            update { it.copy(stage = CastStage.FAILED, message = "That device is no longer nearby.") }
            refreshDevices()
            return
        }
        update { it.copy(stage = CastStage.CONNECTING, deviceName = route.name, message = "") }
        runCatching { router.selectRoute(route) }
            .onFailure {
                update { s -> s.copy(stage = CastStage.FAILED, message = "Couldn't connect to that device.") }
            }
    }

    /** Ends the session. The receiver stops and the phone is told where it had got to. */
    fun disconnect(stopReceiver: Boolean = true) {
        val position = currentPosition()
        runCatching { castContext?.sessionManager?.endCurrentSession(stopReceiver) }
        runCatching { router?.unselect(MediaRouter.UNSELECT_REASON_STOPPED) }
        releaseSession()
        update { CastState(stage = CastStage.IDLE, devices = it.devices) }
        if (position > 0) main.post { onHandBack(position) }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            update { it.copy(stage = CastStage.CONNECTING, deviceName = nameOf(session)) }
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) = adopt(session)

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            releaseSession()
            update {
                it.copy(
                    stage = CastStage.FAILED,
                    message = "Couldn't connect to ${nameOf(session)}.",
                )
            }
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            update { it.copy(stage = CastStage.CONNECTING, deviceName = nameOf(session)) }
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = adopt(session)

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            releaseSession()
            update { it.copy(stage = CastStage.FAILED, message = "Lost the connection to the device.") }
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            // The network dropped or the app went away. The session may come back on its own,
            // so this is reported rather than torn down.
            update { it.copy(stage = CastStage.CONNECTING, message = "Reconnecting…") }
        }

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) {
            val position = currentPosition()
            releaseSession()
            update { CastState(stage = CastStage.IDLE, devices = it.devices) }
            if (position > 0) main.post { onHandBack(position) }
        }
    }

    private fun adopt(session: CastSession) {
        this.session = session
        runCatching {
            session.remoteMediaClient?.registerCallback(mediaCallback)
            session.remoteMediaClient?.addProgressListener(progressListener, PROGRESS_INTERVAL_MS)
        }
        update { it.copy(stage = CastStage.CONNECTED, deviceName = nameOf(session), message = "") }
        syncFromRemote()
    }

    private fun releaseSession() {
        runCatching {
            session?.remoteMediaClient?.unregisterCallback(mediaCallback)
            session?.remoteMediaClient?.removeProgressListener(progressListener)
        }
        session = null
        loaded = null
    }

    private fun nameOf(session: CastSession): String =
        runCatching { session.castDevice?.friendlyName }.getOrNull().orEmpty().ifBlank { "the device" }

    // ---- Media --------------------------------------------------------------

    /**
     * Hands a stream to the receiver. The receiver fetches it itself — the phone is not in the
     * path, so nothing is downloaded here and re-uploaded there, and the original quality is
     * whatever the source serves.
     */
    fun load(castable: CastVerdict.Castable, startAtMs: Long) {
        val client = session?.remoteMediaClient
        if (client == null) {
            update { it.copy(stage = CastStage.FAILED, message = "Not connected to a device.") }
            return
        }
        loaded = castable
        update { it.copy(stage = CastStage.LOADING, message = "", isLive = castable.isLive) }

        val metadata = MediaMetadata(
            if (castable.isLive) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MOVIE,
        ).apply {
            putString(MediaMetadata.KEY_TITLE, castable.title)
            if (castable.posterUrl.isNotBlank()) {
                runCatching { addImage(WebImage(android.net.Uri.parse(castable.posterUrl))) }
            }
        }

        val info = MediaInfo.Builder(castable.url)
            .setStreamType(
                if (castable.isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED,
            )
            .setContentType(castable.contentType)
            .setMetadata(metadata)
            .build()

        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(if (castable.isLive) 0 else startAtMs.coerceAtLeast(0))
            .build()

        runCatching { client.load(request) }
            .getOrNull()
            ?.setResultCallback { result ->
                if (!result.status.isSuccess) {
                    // The receiver could not play it: a codec it lacks, a fetch it could not
                    // make. Its own words are more useful than a generic failure.
                    update {
                        it.copy(
                            stage = CastStage.FAILED,
                            message = "${state.deviceName.ifBlank { "The device" }} couldn't play this video.",
                        )
                    }
                }
            }
            ?: update { it.copy(stage = CastStage.FAILED, message = "Couldn't send this video.") }
    }

    fun play() = withClient { it.play() }
    fun pause() = withClient { it.pause() }
    fun stopRemote() = withClient { it.stop() }

    /**
     * Back to the live edge of a stream on the receiver. The edge is the receiver's own idea of
     * it, not the phone's — the two drift, and the receiver is the one playing.
     */
    fun seekToLiveEdge() = withClient { client ->
        val edge = runCatching { client.approximateLiveSeekableRangeEnd }.getOrDefault(0L)
        if (edge > 0) client.seek(MediaSeekOptions.Builder().setPosition(edge).build())
    }

    fun seekTo(positionMs: Long) = withClient {
        it.seek(MediaSeekOptions.Builder().setPosition(positionMs.coerceAtLeast(0)).build())
    }

    /** Receiver volume, which is the television's own rather than the phone's. */
    fun setVolume(volume: Float) {
        runCatching { session?.volume = volume.coerceIn(0f, 1f).toDouble() }
        update { it.copy(volume = volume.coerceIn(0f, 1f)) }
    }

    fun toggleMute() {
        val session = this.session ?: return
        val muted = runCatching { session.isMute }.getOrDefault(false)
        runCatching { session.isMute = !muted }
        update { it.copy(isMuted = !muted) }
    }

    private inline fun withClient(action: (RemoteMediaClient) -> Unit) {
        val client = session?.remoteMediaClient ?: return
        runCatching { action(client) }
    }

    private fun currentPosition(): Long =
        runCatching { session?.remoteMediaClient?.approximateStreamPosition ?: 0L }.getOrDefault(0L)

    private val progressListener = RemoteMediaClient.ProgressListener { position, duration ->
        update { it.copy(positionMs = position.coerceAtLeast(0), durationMs = duration.coerceAtLeast(0)) }
    }

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = syncFromRemote()
        override fun onMetadataUpdated() = syncFromRemote()
        override fun onQueueStatusUpdated() = syncFromRemote()
    }

    private fun syncFromRemote() {
        val client = session?.remoteMediaClient ?: return
        val playerState = runCatching { client.playerState }.getOrDefault(MediaStatus.PLAYER_STATE_UNKNOWN)
        val stage = when (playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> CastStage.PLAYING
            MediaStatus.PLAYER_STATE_PAUSED -> CastStage.PAUSED
            MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> CastStage.LOADING
            // Idle, whether because nothing has been loaded yet or because the receiver
            // reached the end: connected either way, with the transport showing no progress.
            else -> CastStage.CONNECTED
        }
        val volume = runCatching { session?.volume?.toFloat() }.getOrNull() ?: state.volume
        val muted = runCatching { session?.isMute }.getOrNull() ?: state.isMuted
        update {
            it.copy(
                stage = stage,
                positionMs = runCatching { client.approximateStreamPosition }.getOrDefault(it.positionMs),
                durationMs = runCatching { client.streamDuration }.getOrDefault(it.durationMs),
                volume = volume,
                isMuted = muted,
            )
        }
    }

    private fun update(change: (CastState) -> CastState) {
        val next = change(state)
        if (next == state) return
        state = next
        main.post { onStateChanged(next) }
    }

    /** Everything released; called when the browser is going away. */
    fun release() {
        stopDiscovery()
        runCatching {
            castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        }
        releaseSession()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 1_000L
    }
}
