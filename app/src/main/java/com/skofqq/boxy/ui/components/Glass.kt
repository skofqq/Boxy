package com.skofqq.boxy.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import com.skofqq.boxy.ui.theme.Boxy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How glass surfaces look; built from the appearance settings. */
data class GlassStyle(
    val enabled: Boolean,
    val translucent: Boolean,
    /** 0..1 */
    val blur: Float,
    /** 0..1 */
    val lens: Float,
    val sheetBlur: Boolean,
    /** Glass on page headers, header buttons and pop-up windows too, not only on the bottom bar. */
    val elements: Boolean = true,
) {
    val blurRadius: Dp get() = (6 + blur * 34).dp
}

val LocalGlass = staticCompositionLocalOf { GlassStyle(false, true, 0.5f, 0.5f, false) }

/** Content recorded for glass surfaces to sample from, and where it sits on the screen. */
class Backdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Screen content recorded for glass surfaces to sample from. */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Everything on screen (tabs or the page opened over them), for glass in popup windows. Popups are separate
 * windows, so sampling the whole screen cannot draw a surface into itself the way an in-page one could.
 */
val LocalScreenBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Number of open bottom sheets; the main content is blurred while it is above zero. */
object SheetBlurState {
    var open by mutableIntStateOf(0)
}

/** Records the content into [backdrop] and draws it as usual. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = onGloballyPositioned { backdrop.origin = it.positionOnScreen() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

private const val LENS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float strength;
half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 q = abs(p - c) - (c - float2(radius));
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    float t = clamp(1.0 + d / radius, 0.0, 1.0);
    float2 dir = normalize(p - c + float2(0.001, 0.001));
    return content.eval(p - dir * t * t * strength);
}
"""

/**
 * Liquid glass: the backdrop under this surface, refracted near the edges (Android 13+),
 * blurred (Android 12+) and tinted. Falls back to a solid tint when blur is unavailable or off.
 */
@Composable
fun GlassSurface(
    shape: Shape,
    tint: Color,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    /** A page element (header, button): follows the "Liquid Glass for elements" switch. */
    element: Boolean = false,
    /** Refraction near the edges; off for flat bars. */
    lens: Boolean = true,
    /** Light rim along the shape. */
    edge: Boolean = true,
    /** Tint opacity instead of the translucency setting (menus need a fixed, readable one). */
    tintAlpha: Float? = null,
    /** In a popup window: samples the whole screen ([LocalScreenBackdrop]) rather than the page under it. */
    popup: Boolean = false,
    /** Background without glass; the tint by default. */
    fallback: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalGlass.current
    val backdrop = if (popup) LocalScreenBackdrop.current ?: LocalBackdrop.current else LocalBackdrop.current
    val active = glass.enabled && (!element || glass.elements) && backdrop != null && Build.VERSION.SDK_INT >= 31
    var pos by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { pos = it.positionOnScreen() }.clip(shape)) {
        if (active && backdrop != null) {
            // Opaque page colour first: the glass replaces what is under it. Without it, translucent content
            // (tinted cards, gaps between rows) let the sharp original show through the blurred copy.
            Box(Modifier.matchParentSize().background(Boxy.colors.page))
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val blurPx = glass.blurRadius.toPx()
                        val blur = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                        renderEffect = if (Build.VERSION.SDK_INT >= 33 && lens && glass.lens > 0.01f && cornerRadius > 0.dp) {
                            val shader = RuntimeShader(LENS_SHADER)
                            shader.setFloatUniform("size", size.width, size.height)
                            shader.setFloatUniform("radius", cornerRadius.toPx().coerceAtMost(size.minDimension / 2))
                            shader.setFloatUniform("strength", glass.lens * 26.dp.toPx())
                            RenderEffect.createChainEffect(blur, RenderEffect.createRuntimeShaderEffect(shader, "content")).asComposeRenderEffect()
                        } else {
                            blur.asComposeRenderEffect()
                        }
                        clip = true
                        this.shape = shape
                    }
                    .drawBehind { translate(backdrop.origin.x - pos.x, backdrop.origin.y - pos.y) { drawLayer(backdrop.layer) } },
            )
            Box(Modifier.matchParentSize().background(tint.copy(alpha = tintAlpha ?: if (glass.translucent) 0.45f else 0.78f)))
            // Light edge that sells the glass; stronger with a stronger lens.
            if (edge) Box(
                Modifier.matchParentSize().border(
                    1.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f + glass.lens * 0.3f), Color.White.copy(alpha = 0.04f))),
                    shape,
                ),
            )
        } else {
            Box(Modifier.matchParentSize().background(fallback ?: tint))
        }
        content()
    }
}

/**
 * Inside a Dialog with liquid glass on: blurs the app behind the dialog (like under bottom sheets) and lightens
 * the dim, so a translucent card on top reads as frosted glass. Returns whether that is active.
 */
@Composable
fun rememberFrostedDialog(): Boolean {
    val glass = LocalGlass.current
    val view = androidx.compose.ui.platform.LocalView.current
    val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
    val active = Build.VERSION.SDK_INT >= 31 && glass.enabled && glass.elements && window != null
    if (active && window != null) {
        androidx.compose.runtime.DisposableEffect(window) {
            window.setDimAmount(0.2f)
            SheetBlurState.open++
            onDispose { SheetBlurState.open-- }
        }
    }
    return active
}

/**
 * Pill-shaped filter chip in liquid glass: neutral glass when off, tinted with [color] when [active].
 * An optional [trailing] icon (a menu arrow) follows the text.
 */
@Composable
fun GlassChip(
    text: String,
    active: Boolean,
    color: Color = Boxy.colors.accent,
    trailing: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    GlassSurface(
        androidx.compose.foundation.shape.RoundedCornerShape(50),
        if (active) color else Boxy.colors.card,
        18.dp,
        element = true,
        tintAlpha = if (active) 0.18f else null,
        fallback = if (active) color.copy(alpha = 0.15f) else Boxy.colors.card,
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.clickable(onClick = onClick)
                .padding(start = 14.dp, end = if (trailing != null) 8.dp else 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Text(
                text,
                color = if (active) color else Boxy.colors.text,
                fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                maxLines = 1,
            )
            if (trailing != null) {
                androidx.compose.material3.Icon(
                    trailing,
                    null,
                    Modifier.padding(start = 2.dp).size(20.dp),
                    tint = if (active) color else Boxy.colors.text2,
                )
            }
        }
    }
}
