package com.slate.browser.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slate.browser.ui.theme.Motion
import com.slate.browser.web.MediaState
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The interface over a video playing full screen.
 *
 * In [MediaOverlayMode.BROWSER] the browser is presenting the video itself, so the overlay takes
 * every touch: the page's own controls are exactly what clutters and crops the view on a phone,
 * and a full transport is provided here instead.
 *
 * In [MediaOverlayMode.PAGE] the site opened its own fullscreen and its player is worth keeping
 * — a scrubber, quality picker and captions the browser has no equivalent for — so touches fall
 * through and only the escape hatches are added on top.
 *
 * Either way there are three independent ways out, so nobody can get stuck: swipe down, the
 * close button, or the system back gesture.
 */
enum class MediaOverlayMode { BROWSER, PAGE }

@Composable
fun MediaFullscreenOverlay(
    mode: MediaOverlayMode,
    media: MediaState,
    isFilling: Boolean,
    showFitControl: Boolean,
    onExit: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleFit: () -> Unit,
    onSeek: (Long) -> Unit,
    onJumpToLive: () -> Unit,
    onVolume: (Float) -> Unit,
    /** Whether a receiver is nearby; the control is absent rather than disabled when not. */
    canCast: Boolean,
    /** The name of the receiver currently playing this, or blank when playing here. */
    castingTo: String,
    onCast: () -> Unit,
    onStopCast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val owned = mode == MediaOverlayMode.BROWSER
    var controlsVisible by remember { mutableStateOf(true) }
    var showVolume by remember { mutableStateOf(false) }
    // Bumped on every interaction so the auto-hide timer restarts rather than stacking.
    var interaction by remember { mutableIntStateOf(0) }
    val touch = { interaction++ }

    LaunchedEffect(controlsVisible, interaction) {
        if (!controlsVisible) return@LaunchedEffect
        delay(CONTROLS_TIMEOUT_MS)
        controlsVisible = false
        showVolume = false
    }

    val gestures = Modifier
        .pointerInput(Unit) {
            val threshold = EXIT_DRAG_DISTANCE.toPx()
            var travelled = 0f
            detectVerticalDragGestures(
                onDragStart = { travelled = 0f },
                onDragEnd = { if (travelled > threshold) onExit() },
            ) { _, amount -> travelled += amount }
        }
        .pointerInput(Unit) {
            detectTapGestures {
                controlsVisible = !controlsVisible
                touch()
            }
        }

    Box(modifier.fillMaxSize()) {
        // When the page owns the picture, only a strip along the top edge listens, so the
        // player underneath keeps every one of its own controls.
        Box(
            if (owned) {
                Modifier.fillMaxSize().then(gestures)
            } else {
                Modifier.fillMaxWidth().height(EDGE_STRIP_HEIGHT).then(gestures)
            },
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(Motion.FAST)),
            exit = fadeOut(tween(Motion.MEDIUM)),
        ) {
            Box(Modifier.fillMaxSize()) {
                TopControls(
                    owned = owned,
                    media = media,
                    isFilling = isFilling,
                    showFitControl = showFitControl && owned,
                    onExit = onExit,
                    onToggleFit = { onToggleFit(); touch() },
                    onJumpToLive = { onJumpToLive(); touch() },
                    canCast = canCast,
                    castingTo = castingTo,
                    onCast = { onCast(); touch() },
                    onStopCast = { onStopCast(); touch() },
                    modifier = Modifier.align(Alignment.TopStart),
                )

                if (owned) {
                    TransportBar(
                        media = media,
                        showVolume = showVolume,
                        onTogglePlay = { onTogglePlay(); touch() },
                        onToggleMute = { onToggleMute(); touch() },
                        onToggleVolumePanel = { showVolume = !showVolume; touch() },
                        onSeek = { onSeek(it); touch() },
                        onVolume = { onVolume(it); touch() },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        if (castingTo.isNotBlank()) {
            CastingBanner(deviceName = castingTo, modifier = Modifier.align(Alignment.Center))
        }

        HintPill(
            text = if (owned) "Swipe down to exit" else "Swipe down from the top to exit",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        )
    }
}

@Composable
private fun TopControls(
    owned: Boolean,
    media: MediaState,
    isFilling: Boolean,
    showFitControl: Boolean,
    onExit: () -> Unit,
    onToggleFit: () -> Unit,
    onJumpToLive: () -> Unit,
    canCast: Boolean,
    castingTo: String,
    onCast: () -> Unit,
    onStopCast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScrimButton(Icons.Rounded.Close, "Exit fullscreen") { onExit() }
        if (media.isLive && owned) {
            Spacer(Modifier.width(10.dp))
            LiveBadge(atEdge = media.isAtLiveEdge, onJumpToLive = onJumpToLive)
        }
        Spacer(Modifier.weight(1f))
        // One control, two meanings: send it somewhere, or bring it back. Nothing is added to
        // the bar when there is nothing nearby to send to.
        if (castingTo.isNotBlank()) {
            ScrimButton(Icons.Rounded.CastConnected, "Stop casting to ${'$'}castingTo", onClick = onStopCast)
        } else if (canCast) {
            ScrimButton(Icons.Rounded.Cast, "Cast to a device", onClick = onCast)
        }
        if (showFitControl) {
            Spacer(Modifier.width(4.dp))
            ScrimButton(
                icon = if (isFilling) Icons.Rounded.CropFree else Icons.Rounded.Fullscreen,
                description = if (isFilling) "Fit the whole frame" else "Fill the screen",
                onClick = onToggleFit,
            )
        }
    }
}

/**
 * Play, position and volume, laid out the way a phone video player is expected to be.
 *
 * A live stream with no rewind buffer gets no scrub bar at all rather than a bar that snaps
 * back to the edge, because a control that cannot do anything is worse than none.
 */
@Composable
private fun TransportBar(
    media: MediaState,
    showVolume: Boolean,
    onTogglePlay: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleVolumePanel: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // While a drag is in progress the bar follows the finger, not the stream, so it does not
    // jump backwards each time a position update arrives mid-gesture.
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }

    val scrubbable = media.isSeekable || media.hasLiveWindow
    val start = if (media.isSeekable) 0f else media.seekableStartMs.toFloat()
    val end = if (media.isSeekable) media.durationMs.toFloat() else media.seekableEndMs.toFloat()
    val position = if (scrubbing) scrubPosition else media.positionMs.toFloat()

    Column(
        modifier
            .fillMaxWidth()
            .background(scrimGradient())
            .navigationBarsPadding()
            .padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 10.dp),
    ) {
        AnimatedVisibility(visible = showVolume) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.VolumeUp,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(12.dp))
                Slider(
                    value = if (media.isMuted) 0f else media.volume,
                    onValueChange = onVolume,
                    colors = whiteSlider(),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Volume" },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ScrimButton(
                icon = if (media.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                description = if (media.isPlaying) "Pause" else "Play",
                onClick = onTogglePlay,
            )

            if (scrubbable) {
                Spacer(Modifier.width(4.dp))
                // While a drag is in progress the label reads the thumb, not the stream, so
                // the number and the bar never disagree mid-gesture.
                TimeLabel(
                    when {
                        scrubbing -> if (media.isSeekable) scrubPosition.toLong() else (scrubPosition - end).toLong()
                        media.isSeekable -> media.positionMs
                        else -> -media.behindLiveMs
                    },
                    signed = !media.isSeekable,
                )
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = position.coerceIn(start, maxOf(start, end)),
                    onValueChange = {
                        scrubbing = true
                        scrubPosition = it
                    },
                    onValueChangeFinished = {
                        scrubbing = false
                        onSeek(scrubPosition.toLong())
                    },
                    valueRange = start..maxOf(start + 1f, end),
                    colors = whiteSlider(),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Seek" },
                )
                Spacer(Modifier.width(8.dp))
                TimeLabel(if (media.isSeekable) media.durationMs else media.seekableEndMs - media.seekableStartMs)
            } else {
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.width(4.dp))
            ScrimButton(
                icon = if (media.isMuted || media.volume <= 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                description = if (media.isMuted) "Unmute" else "Volume",
                onClick = onToggleVolumePanel,
                onLongClick = onToggleMute,
            )
        }
    }
}

@Composable
private fun TimeLabel(millis: Long, signed: Boolean = false) {
    Text(
        text = if (signed && millis < 0) "-${formatDuration(-millis)}" else formatDuration(millis),
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** m:ss for anything under an hour, h:mm:ss beyond it. */
private fun formatDuration(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** Shown briefly on entry so the exit gesture is never something the viewer has to discover. */
@Composable
private fun HintPill(text: String, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(HINT_TIMEOUT_MS)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.SLOW)),
        exit = fadeOut(tween(Motion.SLOW)),
        modifier = modifier,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** Tapping it when behind the edge is the one-step way back to live. */
@Composable
private fun LiveBadge(atEdge: Boolean, onJumpToLive: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.Black.copy(alpha = 0.45f))
            .then(if (atEdge) Modifier else Modifier.clickable(onClick = onJumpToLive))
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics { contentDescription = if (atEdge) "Live" else "Go live" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (atEdge) Color(0xFFFF4438) else Color(0xFF9AA1AC)),
        )
        Text(
            text = if (atEdge) "LIVE" else "GO LIVE",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Controls sit on a translucent disc rather than the theme surface: they have to stay legible
 * over arbitrary video without tinting the picture around them.
 */
@Composable
private fun ScrimButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.Black.copy(alpha = 0.45f))
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                } else {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
                    }
                },
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun whiteSlider() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Color.White,
    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
)

/** Keeps white controls readable over a bright frame without dimming the whole picture. */
private fun scrimGradient() = androidx.compose.ui.graphics.Brush.verticalGradient(
    listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
)

/**
 * What is on screen while the television has the picture.
 *
 * The page's own video is paused behind this, so without it the viewer would be looking at a
 * still frame or a black rectangle with no explanation of where their video went.
 */
@Composable
private fun CastingBanner(deviceName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.CastConnected,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(38.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Casting to ${'$'}deviceName",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private const val CONTROLS_TIMEOUT_MS = 3_600L
private const val HINT_TIMEOUT_MS = 2_600L
private val EXIT_DRAG_DISTANCE = 72.dp
private val EDGE_STRIP_HEIGHT = 40.dp
