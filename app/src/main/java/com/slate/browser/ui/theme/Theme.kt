package com.slate.browser.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.slate.browser.data.ThemeMode

/**
 * A deliberately quiet palette. The page is the content; browser chrome is a neutral surface
 * with a single accent used only for state that matters (secure, loading, selected).
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2A6BF2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE7FF),
    onPrimaryContainer = Color(0xFF0A2A6B),
    secondary = Color(0xFF5A6472),
    onSecondary = Color.White,
    background = Color(0xFFFBFBFD),
    onBackground = Color(0xFF14161A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161A),
    surfaceVariant = Color(0xFFEFF1F5),
    onSurfaceVariant = Color(0xFF5A6472),
    outline = Color(0xFFC6CBD4),
    outlineVariant = Color(0xFFE2E5EB),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    surfaceContainer = Color(0xFFF3F4F8),
    surfaceContainerHigh = Color(0xFFEDEFF4),
    surfaceContainerHighest = Color(0xFFE7E9EF),
    scrim = Color(0x99000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC0FF),
    onPrimary = Color(0xFF002C6E),
    primaryContainer = Color(0xFF1B3E7D),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFFA8B2C0),
    onSecondary = Color(0xFF1B2027),
    background = Color(0xFF0D0F12),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF14171B),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF20242A),
    onSurfaceVariant = Color(0xFFA8B2C0),
    outline = Color(0xFF3A4048),
    outlineVariant = Color(0xFF272C33),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    surfaceContainer = Color(0xFF181B20),
    surfaceContainerHigh = Color(0xFF1E2228),
    surfaceContainerHighest = Color(0xFF252A31),
    scrim = Color(0xB3000000),
)

private val SlateTypography = Typography().let { base ->
    base.copy(
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
        // The omnibox needs a slightly tighter, more compact face than body text.
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 14.5.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.05.sp,
        ),
    )
}

@Composable
fun SlateTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        // Dynamic colour keeps the browser at home on the user's device without adding
        // decoration of its own.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = SlateTypography,
        content = content,
    )
}

/** Shared motion constants, so every surface in the app moves the same way. */
object Motion {
    const val FAST = 140
    const val MEDIUM = 220
    const val SLOW = 320
}
