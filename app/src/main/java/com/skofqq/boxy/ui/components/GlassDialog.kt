package com.skofqq.boxy.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.skofqq.boxy.ui.theme.Boxy

/** Card of a pop-up window: frosted glass with a light rim over the blurred app, or [solid] without glass. */
@Composable
fun Modifier.glassCard(frosted: Boolean, shape: Shape, solid: Color = Boxy.colors.page): Modifier {
    if (!frosted) return clip(shape).background(solid)
    val translucent = LocalGlass.current.translucent
    val rim = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (Boxy.colors.isDark) 0.22f else 0.7f), Color.White.copy(alpha = 0.06f)))
    return clip(shape)
        .background(Boxy.colors.page.copy(alpha = if (translucent) 0.72f else 0.88f))
        .border(1.dp, rim, shape)
}

/**
 * Drop-in for Material's AlertDialog in the app's liquid glass style: a frosted card that springs in over the
 * blurred app, title, content, and the buttons at the bottom right.
 */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = Boxy.colors.card,
) {
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false)) {
        val frosted = rememberFrostedDialog()
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 420f)) }
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        // Full-screen window with the card centred by us: a tap outside the card dismisses, like AlertDialog.
        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismissRequest),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = 28.dp)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .graphicsLayer {
                        val v = appear.value
                        scaleX = 0.88f + 0.12f * v
                        scaleY = 0.88f + 0.12f * v
                        alpha = v.coerceIn(0f, 1f)
                    }
                    .glassCard(frosted, RoundedCornerShape(30.dp), containerColor)
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 14.dp),
            ) {
                if (title != null) {
                    CompositionLocalProvider(LocalContentColor provides Boxy.colors.text) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if (text != null) {
                    Box(Modifier.weight(1f, fill = false)) {
                        CompositionLocalProvider(LocalContentColor provides Boxy.colors.text2) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
