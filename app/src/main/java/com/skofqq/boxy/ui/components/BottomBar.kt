package com.skofqq.boxy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.ui.theme.Boxy

data class BarItem(val label: String, val icon: ImageVector, val motion: TapMotion = TapMotion.POP)

/** How a bar icon moves when tapped; each tab gets one that suits its symbol. */
enum class TapMotion {
    /** Dips, tilts and springs back a little past its size. */
    POP,
    /** Turns once around (a globe). */
    SPIN,
    /** Turns a tooth or two with an overshoot (a gear). */
    GEAR,
    /** Shakes sideways (a link being pulled). */
    SHAKE,
    /** Flips over like a page. */
    FLIP,
    /** Drops in from above and bounces (a filter). */
    DROP,
    /** Hops up, squashing as it lands (a house). */
    JUMP,
    /** Rocks back and forth (a wrench at work). */
    WIGGLE,
}

/** The values a [TapMotion] animates; all rest at the identity. */
private class TapMotionState {
    val scale = androidx.compose.animation.core.Animatable(1f)
    val squash = androidx.compose.animation.core.Animatable(1f)
    val rotation = androidx.compose.animation.core.Animatable(0f)
    val flip = androidx.compose.animation.core.Animatable(0f)
    val dx = androidx.compose.animation.core.Animatable(0f)
    val dy = androidx.compose.animation.core.Animatable(0f)

    suspend fun play(motion: TapMotion) = kotlinx.coroutines.coroutineScope {
        val bouncy = spring<Float>(dampingRatio = 0.3f, stiffness = 380f)
        when (motion) {
            TapMotion.POP -> {
                launch { scale.snapTo(0.72f); scale.animateTo(1f, bouncy) }
                launch { rotation.snapTo(-14f); rotation.animateTo(0f, spring(dampingRatio = 0.25f, stiffness = 300f)) }
            }
            TapMotion.SPIN -> {
                launch { rotation.snapTo(0f); rotation.animateTo(360f, androidx.compose.animation.core.tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing)); rotation.snapTo(0f) }
                launch { scale.snapTo(0.85f); scale.animateTo(1f, bouncy) }
            }
            TapMotion.GEAR -> {
                launch { rotation.snapTo(0f); rotation.animateTo(180f, spring(dampingRatio = 0.4f, stiffness = 120f)); rotation.snapTo(0f) }
            }
            TapMotion.SHAKE -> {
                launch {
                    dx.animateTo(0f, androidx.compose.animation.core.keyframes {
                        durationMillis = 420
                        -8f at 60; 8f at 140; -6f at 220; 4f at 300; -2f at 370
                    })
                }
            }
            TapMotion.FLIP -> {
                launch { flip.snapTo(0f); flip.animateTo(360f, androidx.compose.animation.core.tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing)); flip.snapTo(0f) }
            }
            TapMotion.DROP -> {
                launch { dy.snapTo(-10f); dy.animateTo(0f, spring(dampingRatio = 0.28f, stiffness = 500f)) }
            }
            TapMotion.JUMP -> {
                launch {
                    dy.animateTo(0f, androidx.compose.animation.core.keyframes {
                        durationMillis = 480
                        -8f at 160 using androidx.compose.animation.core.FastOutSlowInEasing; 0f at 320; -2f at 400
                    })
                }
                launch {
                    squash.animateTo(1f, androidx.compose.animation.core.keyframes {
                        durationMillis = 480
                        0.8f at 60; 1.1f at 160; 1f at 300; 0.86f at 340
                    })
                }
            }
            TapMotion.WIGGLE -> {
                launch {
                    rotation.animateTo(0f, androidx.compose.animation.core.keyframes {
                        durationMillis = 500
                        -22f at 80; 18f at 180; -12f at 280; 6f at 380
                    })
                }
            }
        }
    }
}

/**
 * Floating pill navigation bar with a sliding highlight, 3 to 5 items, drawn as liquid glass
 * when blur effects are on. Width is 92% of the screen so four Russian labels fit.
 */
@Composable
fun BottomBar(items: List<BarItem>, selected: Float, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val select = withTap1(onSelect)
    val colors = Boxy.colors
    val shape = RoundedCornerShape(50)
    GlassSurface(
        shape = shape,
        tint = colors.card,
        cornerRadius = 34.dp,
        modifier = modifier
            .fillMaxWidth(if (items.size > 4) 0.96f else 0.92f)
            .height(68.dp)
            .shadow(12.dp, shape, clip = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().border(1.dp, colors.outline, shape).padding(4.dp)) {
            val itemWidth = maxWidth / items.size
            val position by animateFloatAsState(selected, spring(dampingRatio = 0.8f, stiffness = 500f), label = "indicator")
            Box(
                Modifier
                    .offset(x = itemWidth * position)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(colors.surface2.copy(alpha = 0.85f)),
            )
            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                items.forEachIndexed { index, item ->
                    val active = index == selected.toInt() && selected % 1f == 0f
                    val tint by androidx.compose.animation.animateColorAsState(if (active) colors.accent else colors.text, label = "tint")
                    // A tap animates the icon in its own way (BarItem.motion).
                    var taps by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                    val motion = remember { TapMotionState() }
                    androidx.compose.runtime.LaunchedEffect(taps) {
                        if (taps > 0) motion.play(item.motion)
                    }
                    Column(
                        Modifier
                            .width(itemWidth)
                            .fillMaxHeight()
                            .clip(shape)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                taps++
                                select(index)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            item.icon,
                            null,
                            Modifier.size(22.dp).graphicsLayer {
                                scaleX = motion.scale.value
                                scaleY = motion.scale.value * motion.squash.value
                                rotationZ = motion.rotation.value
                                rotationY = motion.flip.value
                                cameraDistance = 12 * density
                                translationX = motion.dx.value.dp.toPx()
                                translationY = motion.dy.value.dp.toPx()
                            },
                            tint = tint,
                        )
                        Text(
                            item.label,
                            // Five items (the panel's tabs) need the smaller size for "Соединения" to fit.
                            style = if (items.size > 4) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
