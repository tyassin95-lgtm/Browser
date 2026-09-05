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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.delay

/**
 * The interface over a video playing full screen.
 *
 * In [Mode.BROWSER] the browser is presenting the video itself, so the overlay takes every
 * touch: the page's own controls and overlays are exactly what clutters and crops the view on a
 * phone, and everything the viewer needs is here instead.
 *
 * In [Mode.PAGE] the site opened its own fullscreen and its player is worth keeping — a
 * scrubber, quality picker and captions the browser has no equivalent for — so touches fall
 * through and only the escape hatches are added on top.
 *
 * Either way there are three independent ways out, so nobody can get stuck: swipe down, the
 * close button, or the system back gesture.
 */
enum class MediaOverlayMode { BROWSER, PAGE }

@Composable
fun MediaFullscreenOverlay(
    mode: MediaOverlayMode,
    isPlaying: Boolean,
    isMuted: Boolean,
    isLive: Boolean,
    isFilling: Boolean,
    showFitControl: Boolean,
    onExit: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val owned = mode == MediaOverlayMode.BROWSER
    var controlsVisible by remember { mutableStateOf(true) }
    // Bumped on every interaction so the auto-hide timer restarts rather than stacking.
    var interaction by remember { mutableIntStateOf(0) }

    LaunchedEffect(controlsVisible, interaction) {
        if (!controlsVisible) return@LaunchedEffect
        delay(CONTROLS_TIMEOUT_MS)
        controlsVisible = false
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
            detectTapGestures(
                onTap = {
                    controlsVisible = !controlsVisible
                    interaction++
                },
                onDoubleTap = { if (showFitControl) { onToggleFit(); interaction++ } },
            )
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
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScrimButton(Icons.Rounded.Close, "Exit fullscreen") { onExit() }
                    if (isLive && owned) {
                        Spacer(Modifier.width(10.dp))
                        LiveBadge()
                    }
                    Spacer(Modifier.weight(1f))
                    if (showFitControl && owned) {
                        ScrimButton(
                            icon = if (isFilling) Icons.Rounded.CropFree else Icons.Rounded.Fullscreen,
                            description = if (isFilling) "Fit the whole frame" else "Fill the screen",
                        ) { onToggleFit(); interaction++ }
                    }
                    if (owned) {
                        Spacer(Modifier.width(4.dp))
                        ScrimButton(
                            icon = if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                            description = if (isMuted) "Unmute" else "Mute",
                        ) { onToggleMute(); interaction++ }
                    }
                }

                if (owned) {
                    ScrimButton(
                        icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        description = if (isPlaying) "Pause" else "Play",
                        size = 64.dp,
                        iconSize = 32.dp,
                        modifier = Modifier.align(Alignment.Center),
                    ) { onTogglePlay(); interaction++ }
                }
            }
        }

        HintPill(
            text = if (owned) "Swipe down to exit" else "Swipe down from the top to exit",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        )
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

@Composable
private fun LiveBadge() {
    Row(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(percent = 50)).background(Color(0xFFFF4438)))
        Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

private const val CONTROLS_TIMEOUT_MS = 3_200L
private const val HINT_TIMEOUT_MS = 2_600L
private val EXIT_DRAG_DISTANCE = 72.dp

/** How much of the top edge listens for the exit swipe when the page owns the picture. */
private val EDGE_STRIP_HEIGHT = 40.dp
