package com.skofqq.boxy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.skofqq.boxy.data.ThemeMode

/** Surface palette of the BFR design (page, card, secondary surface, outline, text, secondary text, accent). */
@Immutable
data class BoxyColors(
    val page: Color,
    val card: Color,
    val surface2: Color,
    val outline: Color,
    val text: Color,
    val text2: Color,
    val accent: Color,
    val isDark: Boolean,
)

private val LightPalette = BoxyColors(
    page = Color(0xFFF1F0F7),
    card = Color(0xFFFEFBFF),
    surface2 = Color(0xFFF0F0FA),
    outline = Color.Black.copy(alpha = 0.07f),
    text = Color(0xFF0D1117),
    text2 = Color(0xFF57606A),
    accent = Color(0xFF0969DA),
    isDark = false,
)

private val DarkPalette = BoxyColors(
    page = Color(0xFF0A0C0F),
    card = Color(0xFF1C1F26),
    surface2 = Color(0xFF252930),
    outline = Color.White.copy(alpha = 0.09f),
    text = Color(0xFFF5F7FA),
    text2 = Color(0xFFB4BCC8),
    accent = Color(0xFF58A6FF),
    isDark = true,
)

val LocalBoxyColors = staticCompositionLocalOf { LightPalette }

object Boxy {
    val colors: BoxyColors
        @Composable get() = LocalBoxyColors.current
}

@Composable
fun BoxyTheme(mode: ThemeMode, trueBlack: Boolean, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM, ThemeMode.MATERIAL -> systemDark
    }
    val dynamic = mode == ThemeMode.MATERIAL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current

    val scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = DarkPalette.accent, background = DarkPalette.page, surface = DarkPalette.card)
        else -> lightColorScheme(primary = LightPalette.accent, background = LightPalette.page, surface = LightPalette.card)
    }

    var palette = when {
        dynamic -> BoxyColors(
            page = scheme.surfaceContainer,
            card = scheme.surfaceContainerLowest.takeUnless { dark } ?: scheme.surfaceContainerHigh,
            surface2 = scheme.surfaceContainerHighest,
            outline = scheme.outlineVariant.copy(alpha = 0.5f),
            text = scheme.onSurface,
            text2 = scheme.onSurfaceVariant,
            accent = scheme.primary,
            isDark = dark,
        )
        dark -> DarkPalette
        else -> LightPalette
    }
    if (dark && trueBlack) palette = palette.copy(page = Color.Black)

    CompositionLocalProvider(LocalBoxyColors provides palette) {
        MaterialTheme(colorScheme = scheme, typography = BoxyTypography) {
            // Ripple plus a tap vibration for every clickable with the default indication.
            CompositionLocalProvider(
                androidx.compose.foundation.LocalIndication provides
                    com.skofqq.boxy.ui.components.HapticIndication(androidx.compose.material3.ripple()),
                content = content,
            )
        }
    }
}
