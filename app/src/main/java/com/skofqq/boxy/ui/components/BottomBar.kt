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

data class BarItem(val label: String, val icon: ImageVector)

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
                    // A tap makes the icon pop: it dips, tilts and springs back a little past its size.
                    var taps by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
                    val tilt = remember { androidx.compose.animation.core.Animatable(0f) }
                    androidx.compose.runtime.LaunchedEffect(taps) {
                        if (taps == 0) return@LaunchedEffect
                        kotlinx.coroutines.coroutineScope {
                            launch {
                                scale.snapTo(0.72f)
                                scale.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 420f))
                            }
                            launch {
                                tilt.snapTo(-14f)
                                tilt.animateTo(0f, spring(dampingRatio = 0.25f, stiffness = 300f))
                            }
                        }
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
                                scaleX = scale.value
                                scaleY = scale.value
                                rotationZ = tilt.value
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
