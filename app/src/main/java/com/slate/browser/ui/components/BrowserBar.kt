package com.slate.browser.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slate.browser.ui.theme.Motion

/** A 44dp-minimum touch target with a quiet ripple, used for every bar action. */
@Composable
fun BarButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.32f,
        animationSpec = tween(Motion.FAST),
        label = "buttonAlpha",
    )
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp).alpha(alpha))
    }
}

/** The tab-count control: a rounded square carrying the number of open tabs. */
@Composable
fun TabCounter(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$count open tabs" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(21.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.8.dp, LocalContentColor.current, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (count > 99) "99" else count.toString(),
                fontSize = if (count > 9) 10.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                color = LocalContentColor.current,
            )
        }
    }
}

/**
 * Appears only while the page has a video worth watching, which keeps the toolbar as spare as
 * it always was on pages that have none. It is the primary way into fullscreen video, and it is
 * present whether or not the site's own player offers one.
 */
@Composable
private fun MediaButton(visible: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.MEDIUM)) + scaleIn(tween(Motion.MEDIUM), initialScale = 0.7f),
        exit = fadeOut(tween(Motion.FAST)) + scaleOut(tween(Motion.FAST), targetScale = 0.7f),
    ) {
        BarButton(Icons.Rounded.Movie, "Watch fullscreen", onClick = onClick)
    }
}

/**
 * Portrait chrome sits at the bottom, where thumbs are. A horizontal drag across the bar moves
 * between tabs, which is faster than opening the switcher for the common two-tab case.
 */
@Composable
fun PortraitBar(
    urlSlot: @Composable (Modifier) -> Unit,
    tabCount: Int,
    showMedia: Boolean,
    onMedia: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
    onSwipeTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(Unit) {
                val threshold = SWIPE_TAB_DISTANCE.toPx()
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = {
                        if (travelled <= -threshold) onSwipeTab(1)
                        if (travelled >= threshold) onSwipeTab(-1)
                    },
                ) { _, dragAmount -> travelled += dragAmount }
            }
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        urlSlot(Modifier.weight(1f))
        MediaButton(visible = showMedia, onClick = onMedia)
        TabCounter(tabCount, onTabs)
        BarButton(Icons.Rounded.MoreVert, "Menu", onClick = onMenu)
    }
}

/**
 * Landscape chrome moves to the top and gets shorter: vertical space is the scarce resource
 * when the phone is on its side, and the extra width makes room for real navigation buttons.
 */
@Composable
fun LandscapeBar(
    urlSlot: @Composable (Modifier) -> Unit,
    tabCount: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onImmersive: () -> Unit,
    showMedia: Boolean,
    onMedia: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BarButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", enabled = canGoBack, onClick = onBack)
        BarButton(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", enabled = canGoForward, onClick = onForward)
        Spacer(Modifier.width(4.dp))
        urlSlot(Modifier.weight(1f))
        Spacer(Modifier.width(4.dp))
        MediaButton(visible = showMedia, onClick = onMedia)
        BarButton(Icons.Rounded.Fullscreen, "Fullscreen browsing", onClick = onImmersive)
        TabCounter(tabCount, onTabs)
        BarButton(Icons.Rounded.MoreVert, "Menu", onClick = onMenu)
    }
}

/** Far enough that a tap or a scroll is never mistaken for a tab change. */
private val SWIPE_TAB_DISTANCE = 56.dp
