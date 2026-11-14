package com.skofqq.boxy.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skofqq.boxy.R
import com.skofqq.boxy.configs.CoreSubscription
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import com.skofqq.boxy.util.Format
import kotlinx.coroutines.launch

/**
 * What loading a config from a subscription gave, the Material 3 Expressive way: a cookie with a check (or a cross)
 * that pops in, the title, the numbers as tiles (servers, links skipped, traffic used), the panel's profile name and
 * announce, the skipped links on demand, then the main action over Cancel.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImportResultDialog(
    outcome: CoreSubscription.Outcome,
    /** Why it failed, already in words (empty when it worked). */
    problem: String,
    action: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val ok = outcome.ok
    val tint = if (ok) Tints.green else Tints.red
    val pop = remember { Animatable(0.4f) }
    val turn = remember { Animatable(-30f) }
    var showSkipped by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f)) }
            launch { turn.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 200f)) }
        }
    }
    ExpressiveDialogFrame(true, onDismiss) {
        Box(
            Modifier.size(104.dp).graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                rotationZ = turn.value
            }.clip((if (ok) MaterialShapes.Cookie9Sided else MaterialShapes.Cookie7Sided).toShape()).background(tint.bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (ok) BoxyIcons.Check else BoxyIcons.Close, null, Modifier.size(46.dp).graphicsLayer { rotationZ = -turn.value }, tint = tint.fg)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (ok) R.string.core_setup_done else R.string.core_setup_failed),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Boxy.colors.text,
            textAlign = TextAlign.Center,
        )
        // The panel's name for the subscription, as a pill.
        val title = outcome.profile?.title?.takeIf { it.isNotBlank() }
        if (title != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.card).padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(BoxyIcons.Subscriptions, null, Modifier.size(16.dp), tint = Boxy.colors.accent)
                Text(title, style = MaterialTheme.typography.labelLarge, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (ok) {
            Spacer(Modifier.height(18.dp))
            val info = outcome.profile?.info
            val tiles = buildList {
                add(Triple(BoxyIcons.Dns, outcome.servers.toString(), R.string.import_result_servers))
                if (outcome.skipped.isNotEmpty()) add(Triple(BoxyIcons.Block, outcome.skipped.size.toString(), R.string.import_result_skipped))
                if (info != null && (info.upload + info.download) > 0) {
                    add(Triple(BoxyIcons.BarChart, Format.bytes(context, info.upload + info.download), R.string.import_result_used))
                }
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                tiles.forEach { (icon, value, label) ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Boxy.colors.card).padding(vertical = 12.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(icon, null, Modifier.size(20.dp), tint = Boxy.colors.accent)
                        Spacer(Modifier.height(6.dp))
                        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text, maxLines = 1)
                        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = Boxy.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (outcome.skipped.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable { showSkipped = !showSkipped }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(if (showSkipped) R.string.task_hide_details else R.string.task_show_details), style = MaterialTheme.typography.labelLarge, color = Boxy.colors.accent)
                    Icon(if (showSkipped) BoxyIcons.ExpandLess else BoxyIcons.ExpandMore, null, Modifier.size(18.dp), tint = Boxy.colors.accent)
                }
                AnimatedVisibility(showSkipped, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        outcome.skipped.joinToString("\n") { com.skofqq.boxy.ui.tools.skipText(context, it) },
                        Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(18.dp)).background(Boxy.colors.card)
                            .verticalScroll(rememberScrollState()).padding(12.dp),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = Boxy.colors.text2,
                    )
                }
            }
        } else if (problem.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                problem,
                Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
                color = Boxy.colors.text2,
                textAlign = TextAlign.Center,
            )
        }
        outcome.profile?.announce?.takeIf { it.isNotBlank() }?.let { a ->
            Spacer(Modifier.height(12.dp))
            Text(
                a,
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Tints.amber.bg).padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Tints.amber.fg,
            )
        }
        Column(Modifier.padding(top = 22.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetButton(action, Boxy.colors.accent, Color.White, Modifier.fillMaxWidth(), onAction)
            if (ok) SheetButton(stringResource(R.string.action_cancel), Boxy.colors.card, Boxy.colors.text, Modifier.fillMaxWidth(), onDismiss)
        }
    }
}
