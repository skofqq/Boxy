package com.skofqq.boxy.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints

/** Room around the menu inside its popup window for the soft shadow. */
private val SHADOW_ROOM = 20.dp
private val MENU_CORNER = 26.dp

/**
 * iOS-style liquid glass menu: a frosted, refracting card with a soft shadow that opens out of the
 * button it belongs to. Put it in a Box next to that button, like DropdownMenu.
 */
@Composable
fun GlassMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    if (!state.currentState && !state.targetState) return

    val density = LocalDensity.current
    val room = with(density) { SHADOW_ROOM.roundToPx() }
    val gap = with(density) { 6.dp.roundToPx() }
    var fromRight by remember { mutableStateOf(true) }
    var fromTop by remember { mutableStateOf(true) }
    val provider = remember(room, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val w = popupContentSize.width - 2 * room
                val h = popupContentSize.height - 2 * room
                // Right edge under the button's right edge when it fits, else from its left edge.
                val right = anchorBounds.right - w >= 0
                val x = (if (right) anchorBounds.right - w else anchorBounds.left).coerceIn(0, (windowSize.width - w).coerceAtLeast(0))
                val below = anchorBounds.bottom + gap + h <= windowSize.height
                val y = if (below) anchorBounds.bottom + gap else (anchorBounds.top - gap - h).coerceAtLeast(0)
                fromRight = right
                fromTop = below
                return IntOffset(x - room, y - room)
            }
        }
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        val transition = rememberTransition(state, label = "menu")
        // Opens with a little spring out of the button's corner, closes quickly.
        val reveal by transition.animateFloat(
            { if (targetState) spring(dampingRatio = 0.78f, stiffness = 480f) else tween(130) },
            label = "reveal",
        ) { if (it) 1f else 0f }
        val alpha by transition.animateFloat({ tween(if (targetState) 110 else 120) }, label = "alpha") { if (it) 1f else 0f }
        val dark = Boxy.colors.isDark
        Box(
            Modifier
                .graphicsLayer { this.alpha = alpha }
                .padding(SHADOW_ROOM)
                .drawWithContent {
                    // Clip to a rounded rect growing from the corner next to the button (with room for the shadow).
                    // Clipping in the draw phase keeps the glass in place, so it samples the right part of the screen.
                    val s = 0.35f + 0.65f * reveal.coerceIn(0f, 1f)
                    val w = size.width * s
                    val h = size.height * s
                    val left = if (fromRight) size.width - w else 0f
                    val top = if (fromTop) 0f else size.height - h
                    val room = SHADOW_ROOM.toPx()
                    val r = MENU_CORNER.toPx() + room
                    val clip = Path().apply { addRoundRect(RoundRect(left - room, top - room, left + w + room, top + h + room, CornerRadius(r))) }
                    clipPath(clip) { this@drawWithContent.drawContent() }
                },
        ) {
            GlassSurface(
                RoundedCornerShape(MENU_CORNER),
                if (dark) Color(0xFF23262D) else Color.White,
                MENU_CORNER,
                Modifier
                    .shadow(24.dp, RoundedCornerShape(MENU_CORNER), ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                    .widthIn(min = 210.dp, max = 290.dp)
                    .width(IntrinsicSize.Max),
                element = true,
                tintAlpha = if (dark) 0.58f else 0.64f,
                popup = true,
            ) {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
            }
        }
    }
}

/** One row of a [GlassMenu]: SF-like 17 sp label with an optional symbol, red when destructive, a check when selected. */
@Composable
fun GlassMenuItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    selected: Boolean = false,
) {
    val color = if (destructive) Tints.red.fg else Boxy.colors.text
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 22.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = color)
            Spacer(Modifier.width(14.dp))
        }
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, letterSpacing = (-0.4).sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(BoxyIcons.Check, null, Modifier.size(18.dp), tint = Boxy.colors.accent)
        }
    }
}
