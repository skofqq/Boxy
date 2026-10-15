package com.skofqq.boxy.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.skofqq.boxy.R
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import com.skofqq.boxy.util.Format

private enum class TaskVisual { WORKING, PROGRESS, OK, FAILED }

private val PERCENT = Regex("""(\d{1,3}(?:[.,]\d+)?)\s*%""")
private val WGET_SIZE = Regex("""(\d+)([kKMG])\s+(\d+:\d\d:\d\d)\s+ETA""")
private val VERSION = Regex("""(?i)(?:download|latest|version|installed)\D*?\bv?(\d+\.\d+(?:\.\d+)?)""")

/** What the output of a download task says so far. */
private class TaskProgress(lines: List<String>) {
    val percent: Float?
    val downloading: Boolean
    val size: Long?
    val eta: String?
    val version: String?

    init {
        val lastProgress = lines.indexOfLast { PERCENT.containsMatchIn(it) }
        val progressLine = lines.getOrNull(lastProgress)
        percent = progressLine?.let { PERCENT.findAll(it).lastOrNull()?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull() }?.coerceIn(0f, 100f)
        // Still downloading while the latest line is the progress line and it is short of 100 %.
        downloading = percent != null && lastProgress == lines.lastIndex && percent < 100f
        val m = progressLine?.let { WGET_SIZE.find(it) }
        size = m?.let {
            val mul = when (it.groupValues[2].lowercase()) { "k" -> 1024L; "m" -> 1024L * 1024; else -> 1024L * 1024 * 1024 }
            it.groupValues[1].toLong() * mul
        }
        eta = m?.groupValues?.get(3)?.removePrefix("0:")?.takeIf { it != "00:00" }
        version = lines.asReversed().firstNotNullOfOrNull { VERSION.find(it)?.groupValues?.get(1) }
    }
}

/**
 * Pop-up window for a module task that downloads something (dnscrypt-proxy, cores, subscriptions):
 * a Material 3 Expressive indicator, the percentage and remaining time while downloading, a badge at the end,
 * and the raw output on demand (opened by itself when the task fails).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TaskDialog(title: String, lines: List<String>, done: Boolean, ok: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val p = remember(lines.size, lines.lastOrNull()) { TaskProgress(lines) }
    val visual = when {
        done && ok -> TaskVisual.OK
        done -> TaskVisual.FAILED
        p.downloading -> TaskVisual.PROGRESS
        else -> TaskVisual.WORKING
    }
    var showLog by rememberSaveable { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(visual) {
        if (visual == TaskVisual.FAILED) showLog = true
        if (visual == TaskVisual.OK || visual == TaskVisual.FAILED) Haptics.result(view, visual == TaskVisual.OK)
    }

    Dialog(
        onDismissRequest = { if (done) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = done, dismissOnClickOutside = done, usePlatformDefaultWidth = false),
    ) {
        // Springy entrance: the card grows from 85 % with a little overshoot.
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 420f)) }
        Column(
            Modifier
                .padding(horizontal = 28.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val v = appear.value
                    scaleX = 0.85f + 0.15f * v
                    scaleY = 0.85f + 0.15f * v
                    alpha = v.coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(32.dp))
                .background(Boxy.colors.page)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = visual,
                    transitionSpec = {
                        (scaleIn(spring(dampingRatio = 0.5f, stiffness = 400f), initialScale = 0.5f) + fadeIn()) togetherWith
                            (scaleOut(targetScale = 0.6f) + fadeOut())
                    },
                    label = "task-visual",
                ) { v ->
                    Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                        when (v) {
                            TaskVisual.WORKING -> LoadingIndicator(Modifier.size(104.dp), color = Boxy.colors.accent)
                            TaskVisual.PROGRESS -> {
                                val shown by animateFloatAsState(
                                    (p.percent ?: 0f) / 100f,
                                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow),
                                    label = "task-progress",
                                )
                                CircularWavyProgressIndicator(
                                    progress = { shown },
                                    modifier = Modifier.size(120.dp),
                                    color = Boxy.colors.accent,
                                    trackColor = Boxy.colors.accent.copy(alpha = 0.16f),
                                )
                                Text(
                                    "${(shown * 100).toInt()}%",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Boxy.colors.text,
                                )
                            }
                            TaskVisual.OK, TaskVisual.FAILED -> {
                                val tint = if (v == TaskVisual.OK) Tints.green else Tints.red
                                Box(
                                    Modifier.size(104.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(tint.bg),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(if (v == TaskVisual.OK) BoxyIcons.Check else BoxyIcons.Close, null, Modifier.size(48.dp), tint = tint.fg)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = Boxy.colors.text, textAlign = TextAlign.Center)
            val status = when (visual) {
                TaskVisual.OK -> stringResource(R.string.update_completed, listOfNotNull(title, p.version).joinToString(" "))
                TaskVisual.FAILED -> stringResource(R.string.update_failed)
                TaskVisual.PROGRESS -> stringResource(R.string.import_downloading)
                TaskVisual.WORKING -> if (p.percent != null) stringResource(R.string.task_installing) else stringResource(R.string.update_started, title)
            }
            AnimatedContent(targetState = status, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "task-status") {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2, textAlign = TextAlign.Center)
            }
            AnimatedVisibility(visual == TaskVisual.PROGRESS && p.size != null) {
                val parts = listOfNotNull(
                    p.size?.let { Format.bytes(context, it) },
                    p.eta?.let { stringResource(R.string.task_eta, it) },
                )
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, modifier = Modifier.padding(top = 2.dp))
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable { showLog = !showLog }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(if (showLog) R.string.task_hide_details else R.string.task_show_details),
                    style = MaterialTheme.typography.labelLarge,
                    color = Boxy.colors.accent,
                )
                Icon(if (showLog) BoxyIcons.ExpandLess else BoxyIcons.ExpandMore, null, Modifier.size(18.dp), tint = Boxy.colors.accent)
            }
            AnimatedVisibility(
                showLog,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                val vScroll = rememberScrollState()
                LaunchedEffect(lines.size) { vScroll.scrollTo(vScroll.maxValue) }
                Box(
                    Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 80.dp, max = 220.dp)
                        .clip(RoundedCornerShape(18.dp)).background(Boxy.colors.card).padding(12.dp),
                ) {
                    Column(Modifier.verticalScroll(vScroll).horizontalScroll(rememberScrollState())) {
                        lines.takeLast(200).forEach {
                            Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Clip)
                        }
                    }
                }
            }
            AnimatedVisibility(done, enter = expandVertically(spring(dampingRatio = 0.7f, stiffness = 400f)) + fadeIn()) {
                Column(Modifier.padding(top = 16.dp)) {
                    SheetButtons(stringResource(R.string.action_done), onDismiss)
                }
            }
        }
    }
}

/** Adds a line of task output, keeping only the latest of repeated download progress lines. */
fun MutableList<String>.addTaskLine(line: String) {
    if (line.isBlank()) return
    val last = lastOrNull()
    if (last != null && PERCENT.containsMatchIn(line) && PERCENT.containsMatchIn(last)) {
        this[lastIndex] = line
    } else {
        add(line)
    }
}
